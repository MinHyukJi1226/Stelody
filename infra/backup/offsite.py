#!/usr/bin/env python3
"""Encrypt validated backups with age; upload/download only to private Backblaze B2."""
import argparse
from datetime import timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import uuid

import database

PREFIX = 'stelody/backups/'
KEY = re.compile(r'stelody/backups/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}\.age\Z')
MAX_BYTES = 128 * 1024 * 1024


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        raise database.OperationError('File must be private and regular (600)')
    if info.st_size > MAX_BYTES:
        raise database.OperationError('Backup exceeds the 128 MiB safety limit')


def age(arguments, source=None):
    result = subprocess.run([os.environ.get('AGE_BIN', 'age'), *arguments], stdin=source,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=60)
    if result.returncode:
        raise database.OperationError('age operation failed; verify recipient/identity and file integrity')


def seal(bundle, directory):
    database.private_directory(directory)
    recipient = os.environ['BACKUP_AGE_RECIPIENT']
    if not re.fullmatch(r'age1[0-9a-z]{58}', recipient):
        raise database.OperationError('Use an age X25519 public recipient')
    with database.backup_lock(bundle.parent) as lock:
        if lock is None:
            raise database.OperationError('Backup/expiry cleanup is running')
        metadata = database.validate_archive(bundle)
        with tempfile.TemporaryDirectory(prefix='.seal-', dir=directory) as temporary:
            staging = Path(temporary)
            payload = staging / 'payload.tar'
            with tarfile.open(payload, 'w') as archive:
                for name in ('manifest.json', 'database.dump'):
                    private_file(bundle / name)
                    archive.add(bundle / name, arcname=name)
            payload.chmod(0o600)
            private_file(payload)
            key = PREFIX + database.instant(metadata['createdAt']).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex + '.age'
            encrypted = staging / 'backup.age'
            with payload.open('rb') as stream:
                age(['--encrypt', '--recipient', recipient, '--output', str(encrypted)], stream)
            encrypted.chmod(0o600)
            private_file(encrypted)
            # Expiry remains bounded by the source API data and the original seven-day limit.
            envelope = {'format': 1, 'key': key, 'createdAt': metadata['createdAt'],
                        'expiresAt': metadata['expiresAt'], 'sha256': database.digest(encrypted)}
            destination = directory / Path(key).name
            encrypted.rename(destination)
            destination.with_suffix('.json').write_text(json.dumps(envelope) + '\n')
            destination.with_suffix('.json').chmod(0o600)
            return destination


def unseal(encrypted, directory):
    private_file(encrypted)
    database.private_directory(directory)
    identity = Path(os.environ['BACKUP_AGE_IDENTITY_FILE'])
    private_file(identity)
    with tempfile.TemporaryDirectory(prefix='.unseal-', dir=directory) as temporary:
        staging = Path(temporary)
        payload = staging / 'payload.tar'
        age(['--decrypt', '--identity', str(identity), '--output', str(payload), str(encrypted)])
        payload.chmod(0o600)
        private_file(payload)
        bundle = staging / 'bundle'
        bundle.mkdir(mode=0o700)
        with tarfile.open(payload, 'r:') as archive:
            members = archive.getmembers()
            if (len(members) != 2 or {m.name for m in members} != {'manifest.json', 'database.dump'}
                    or any(not m.isfile() or m.size > MAX_BYTES for m in members)):
                raise database.OperationError('Unexpected archive entries')
            for member in members:
                with archive.extractfile(member) as source, (bundle / member.name).open('xb') as output:
                    shutil.copyfileobj(source, output)
                (bundle / member.name).chmod(0o600)
        database.validate_archive(bundle)
        destination = directory / ('stelody-recovered-' + uuid.uuid4().hex)
        bundle.rename(destination)
        return destination


def envelope(encrypted):
    private_file(encrypted)
    sidecar = encrypted.with_suffix('.json')
    private_file(sidecar)
    value = json.loads(sidecar.read_text())
    if value.get('format') != 1 or not KEY.fullmatch(value['key']):
        raise database.OperationError('Unsupported encrypted backup format/key')
    created, expiry = database.instant(value['createdAt']), database.instant(value['expiresAt'])
    if not created <= database.now() < expiry <= created + database.MAX_AGE:
        raise database.OperationError('Encrypted backup expired or has invalid timestamps')
    if database.digest(encrypted) != value['sha256']:
        raise database.OperationError('Encrypted backup checksum mismatch')
    return value


def remote():
    endpoint, bucket = os.environ['BACKUP_B2_ENDPOINT'], os.environ['BACKUP_B2_BUCKET']
    match = re.fullmatch(r'https://s3\.([a-z]{2}-[a-z]+-[0-9]{3})\.backblazeb2\.com', endpoint)
    if not match:
        raise database.OperationError('Expected a Backblaze B2 HTTPS endpoint')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', bucket):
        raise database.OperationError('Invalid dedicated bucket name')
    import boto3
    from botocore.config import Config
    client = boto3.client('s3', endpoint_url=endpoint, region_name=match.group(1),
                         aws_access_key_id=os.environ['BACKUP_B2_ACCESS_KEY_ID'],
                         aws_secret_access_key=os.environ['BACKUP_B2_SECRET_ACCESS_KEY'],
                         config=Config(signature_version='s3v4', connect_timeout=10, read_timeout=60,
                                       retries={'mode': 'standard', 'max_attempts': 3},
                                       s3={'addressing_style': 'path'},
                                       request_checksum_calculation='when_required',
                                       response_checksum_validation='when_required'))
    return client, bucket


def validate_storage(client, bucket):
    acl = client.get_bucket_acl(Bucket=bucket)
    if any(grant.get('Grantee', {}).get('URI') for grant in acl.get('Grants', [])):
        raise database.OperationError('Backup bucket must be private')
    rules = client.get_bucket_lifecycle_configuration(Bucket=bucket).get('Rules', [])
    scoped = [rule for rule in rules if rule.get('Status') == 'Enabled'
              and rule.get('Filter', {}).get('Prefix', rule.get('Prefix')) == PREFIX]
    hides = any(rule.get('Expiration', {}).get('Days') == 1 for rule in scoped)
    deletes = any(rule.get('NoncurrentVersionExpiration', {}).get('NoncurrentDays') == 1 for rule in scoped)
    if not hides or not deletes:
        raise database.OperationError('Configure one-day hiding and one-day permanent deletion before upload')


def object_metadata(value):
    return {'stelody-format': '1', 'created-at': value['createdAt'],
            'expires-at': value['expiresAt'], 'cipher-sha256': value['sha256']}


def verify_remote(client, bucket, key, version, expected):
    response = client.get_object(Bucket=bucket, Key=key, VersionId=version)
    with response['Body'] as stream:
        digest = hashlib.sha256()
        size = 0
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(chunk)
            if size > MAX_BYTES:
                raise database.OperationError('Remote backup exceeds size limit')
            digest.update(chunk)
    if response.get('Metadata') != object_metadata(expected) or digest.hexdigest() != expected['sha256']:
        raise database.OperationError('Remote backup verification failed')


def upload(encrypted):
    value = envelope(encrypted)
    # B2 lifecycle hides after one day and deletes after one further day, with
    # each stage processed daily. Leave five days before the original data expiry.
    if database.instant(value['expiresAt']) < database.now() + timedelta(days=5):
        raise database.OperationError('Refresh source data before offsite backup (need at least five days until expiry)')
    client, bucket = remote()
    validate_storage(client, bucket)
    with encrypted.open('rb') as stream:
        response = client.put_object(Bucket=bucket, Key=value['key'], Body=stream,
                                     ContentType='application/octet-stream', Metadata=object_metadata(value))
    version = response.get('VersionId')
    if not version or version == 'null':
        raise database.OperationError('Storage did not return an immutable object version')
    verify_remote(client, bucket, value['key'], version, value)
    return value['key']


def download(key, directory):
    if not KEY.fullmatch(key):
        raise database.OperationError('Only managed Stelody backup keys may be downloaded')
    database.private_directory(directory)
    client, bucket = remote()
    response = client.get_object(Bucket=bucket, Key=key)
    metadata = response.get('Metadata', {})
    value = {'format': 1, 'key': key, 'createdAt': metadata.get('created-at'),
             'expiresAt': metadata.get('expires-at'), 'sha256': metadata.get('cipher-sha256')}
    destination = directory / Path(key).name
    if destination.exists() or destination.with_suffix('.json').exists():
        response['Body'].close()
        raise database.OperationError('Download destination already exists')
    try:
        with response['Body'] as stream, destination.open('xb') as output:
            size = 0
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise database.OperationError('Remote backup exceeds size limit')
                output.write(chunk)
        destination.chmod(0o600)
        destination.with_suffix('.json').write_text(json.dumps(value) + '\n')
        destination.with_suffix('.json').chmod(0o600)
        if metadata.get('stelody-format') != '1':
            raise database.OperationError('Unknown remote backup format')
        envelope(destination)
    except BaseException:
        destination.unlink(missing_ok=True)
        destination.with_suffix('.json').unlink(missing_ok=True)
        raise
    return destination


def prune():
    client, bucket = remote()
    versions, markers = [], []
    for page in client.get_paginator('list_object_versions').paginate(Bucket=bucket, Prefix=PREFIX):
        versions.extend(item for item in page.get('Versions', []) if KEY.fullmatch(item['Key']))
        markers.extend(item for item in page.get('DeleteMarkers', []) if KEY.fullmatch(item['Key']))
        if len(versions) + len(markers) > 10000:
            raise database.OperationError('Unexpected backup version count; inspect storage before cleanup')
    retained = set()
    removed = 0
    for item in versions:
        key, version = item['Key'], item.get('VersionId')
        if not version or version == 'null':
            raise database.OperationError('Storage version missing; refusing a hide-only delete')
        metadata = client.head_object(Bucket=bucket, Key=key, VersionId=version).get('Metadata', {})
        if metadata.get('stelody-format') != '1':
            retained.add(key)
            continue
        created, expiry = database.instant(metadata['created-at']), database.instant(metadata['expires-at'])
        if database.now() >= min(expiry, created + database.MAX_AGE):
            # Without VersionId, B2 inserts a hide marker and retains the bytes.
            client.delete_object(Bucket=bucket, Key=key, VersionId=version)
            removed += 1
        else:
            retained.add(key)
    # Remove hide markers only after all listed data versions were deleted. Removing
    # a current marker while data remains could make an older version visible again.
    for item in markers:
        if item['Key'] in retained:
            continue
        version = item.get('VersionId')
        if not version or version == 'null':
            raise database.OperationError('Storage marker version missing')
        client.delete_object(Bucket=bucket, Key=item['Key'], VersionId=version)
        removed += 1
    return removed


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('seal', 'unseal'):
        command = sub.add_parser(name)
        command.add_argument('--source', type=Path, required=True)
        command.add_argument('--directory', type=Path, required=True)
    command = sub.add_parser('upload')
    command.add_argument('--source', type=Path, required=True)
    command = sub.add_parser('download')
    command.add_argument('--key', required=True)
    command.add_argument('--directory', type=Path, required=True)
    sub.add_parser('prune')
    args = parser.parse_args()
    try:
        if args.command == 'seal':
            result = {'encrypted': str(seal(args.source, args.directory))}
        elif args.command == 'unseal':
            result = {'bundle': str(unseal(args.source, args.directory)), 'readyForPublicTraffic': False}
        elif args.command == 'upload':
            result = {'uploaded': upload(args.source), 'verifiedByDownload': True}
        elif args.command == 'download':
            result = {'encrypted': str(download(args.key, args.directory))}
        else:
            result = {'expiredObjectsRemoved': prune()}
        print(json.dumps(result))
        return 0
    except Exception:
        # Storage/crypto/database exception text may expose request credentials or row data.
        print('Offsite backup failed. Check private credentials, encryption, integrity and retention settings.', file=__import__('sys').stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
