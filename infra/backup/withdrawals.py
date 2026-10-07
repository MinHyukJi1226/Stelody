#!/usr/bin/env python3
"""Prepare recovery reconciliation from the independent, encrypted withdrawal journal."""
import argparse
from datetime import timedelta
import json
import os
from pathlib import Path
import re
import tempfile
import uuid

import database
import offsite

PREFIX = 'stelody/withdrawals/records/'
COVERAGE = 'stelody/withdrawals/coverage.json'
KEY = re.compile(re.escape(PREFIX) + r'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.age\Z')
RETENTION = timedelta(days=8)
MAX_BYTES = 8192


def remote():
    return offsite.remote('WITHDRAWAL')


def validate_storage(client, bucket):
    if any(g.get('Grantee', {}).get('URI') for g in client.get_bucket_acl(Bucket=bucket).get('Grants', [])):
        raise database.OperationError('Withdrawal storage must be private')
    hides, deletes = False, False
    for rule in client.get_bucket_lifecycle_configuration(Bucket=bucket).get('Rules', []):
        if rule.get('Status') != 'Enabled':
            continue
        prefix = rule.get('Filter', {}).get('Prefix', rule.get('Prefix'))
        if prefix is None:
            raise database.OperationError('Unknown withdrawal lifecycle filter')
        if not (PREFIX.startswith(prefix) or prefix.startswith(PREFIX)):
            continue
        if prefix != PREFIX:
            raise database.OperationError('Use eight-day hiding and one-day deletion only on the records prefix')
        expiration = rule.get('Expiration')
        if expiration is not None:
            if 'Days' in expiration:
                if expiration['Days'] != 8:
                    raise database.OperationError('Withdrawal hiding must use eight days')
                hides = True
            elif expiration.get('ExpiredObjectDeleteMarker') is not True:
                raise database.OperationError('Unknown withdrawal expiration')
        noncurrent = rule.get('NoncurrentVersionExpiration')
        if noncurrent is not None:
            if noncurrent.get('NoncurrentDays') != 1:
                raise database.OperationError('Withdrawal deletion must use one day')
            deletes = True
    if not hides or not deletes:
        raise database.OperationError('Withdrawal lifecycle missing')


def read_object(client, bucket, key, version=None):
    args = {'Bucket': bucket, 'Key': key}
    if version:
        args['VersionId'] = version
    response = client.get_object(**args)
    with response['Body'] as stream:
        body = stream.read(MAX_BYTES + 1)
    if len(body) > MAX_BYTES:
        raise database.OperationError('Withdrawal object exceeds size limit')
    return body, response.get('Metadata', {})


def versions(client, bucket, prefix):
    items, markers = [], []
    for page in client.get_paginator('list_object_versions').paginate(Bucket=bucket, Prefix=prefix):
        items.extend(page.get('Versions', []))
        markers.extend(page.get('DeleteMarkers', []))
        if len(items) + len(markers) > 10000:
            raise database.OperationError('Too many journal objects; inspect before recovery')
    return items, markers


def coverage(client, bucket):
    items, markers = versions(client, bucket, COVERAGE)
    exact = [i for i in items if i['Key'] == COVERAGE]
    if len(exact) != 1 or any(i['Key'] == COVERAGE for i in markers):
        raise database.OperationError('Coverage anchor missing or replaced; require a new verified recovery boundary')
    body, _ = read_object(client, bucket, COVERAGE, exact[0]['VersionId'])
    value = json.loads(body)
    if set(value) != {'format', 'ledgerId', 'recordingSince'} or value['format'] != 1:
        raise database.OperationError('Unknown withdrawal coverage format')
    if value['ledgerId'] != str(uuid.UUID(value['ledgerId'])):
        raise database.OperationError('Invalid ledger identity')
    since = database.instant(value['recordingSince'])
    if since > database.now():
        raise database.OperationError('Future coverage anchor')
    return value


def initialize(client, bucket, ledger_id):
    validate_storage(client, bucket)
    items, markers = versions(client, bucket, 'stelody/withdrawals/')
    if items or markers:
        raise database.OperationError('Journal is not empty; never replace a coverage anchor')
    value = {'format': 1, 'ledgerId': str(uuid.UUID(ledger_id)), 'recordingSince': database.now().isoformat()}
    body = (json.dumps(value) + '\n').encode()
    response = client.put_object(Bucket=bucket, Key=COVERAGE, Body=body, ContentType='application/json')
    version = response.get('VersionId')
    if not version or version == 'null' or read_object(client, bucket, COVERAGE, version)[0] != body:
        raise database.OperationError('Coverage anchor verification failed')
    return value


def decrypt(body):
    identity = Path(os.environ['BACKUP_AGE_IDENTITY_FILE'])
    offsite.private_file(identity)
    with tempfile.TemporaryDirectory(prefix='stelody-withdrawal-') as temporary:
        root = Path(temporary)
        cipher, plain = root / 'cipher.age', root / 'record.json'
        cipher.write_bytes(body)
        cipher.chmod(0o600)
        offsite.age(['--decrypt', '--identity', str(identity), '--output', str(plain), str(cipher)])
        plain.chmod(0o600)
        if plain.stat().st_size > 1024:
            raise database.OperationError('Withdrawal plaintext exceeds size limit')
        return json.loads(plain.read_bytes())


def reconcile(client, bucket, bundle, stopped):
    started = database.now()
    manifest = database.validate_archive(bundle)
    if not database.instant(manifest['createdAt']) <= stopped <= started or started - stopped > timedelta(minutes=30):
        raise database.OperationError('Stop all services and reconcile within thirty minutes')
    validate_storage(client, bucket)
    anchor = coverage(client, bucket)
    since = database.instant(anchor['recordingSince'])
    if database.instant(manifest['createdAt']) < since:
        raise database.OperationError('Backup predates independent recording; complete recovery cannot be inferred')
    items, markers = versions(client, bucket, PREFIX)
    withdrawn, seen = set(), {}
    for item in items:
        key, version = item['Key'], item.get('VersionId')
        match = KEY.fullmatch(key)
        if not match or not version or version == 'null':
            raise database.OperationError('Unknown journal key/version')
        body, metadata = read_object(client, bucket, key, version)
        value = decrypt(body)
        if (set(value) != {'format','ledgerId','recordId','userId','requestedAt'} or value['format'] != 1
                or value['ledgerId'] != anchor['ledgerId'] or value['recordId'] != match[1]):
            raise database.OperationError('Withdrawal record identity/format mismatch')
        user = str(uuid.UUID(value['userId']))
        requested = database.instant(value['requestedAt'])
        if not since <= requested <= stopped:
            raise database.OperationError('Record outside verified coverage; ensure every service is stopped')
        # Java Instant emits Z; Python emits +00:00. Compare the parsed instants.
        if (set(metadata) != {'stelody-withdrawal-format','expires-at'}
                or metadata['stelody-withdrawal-format'] != '1'
                or database.instant(metadata['expires-at']) != requested + RETENTION):
            raise database.OperationError('Withdrawal retention metadata mismatch')
        if key in seen and seen[key] != value:
            raise database.OperationError('Conflicting withdrawal record versions')
        seen[key] = value
        if requested + RETENTION > started:
            # Include prior-to-backup intents too: their DB commit may have failed.
            withdrawn.add(user)
    for marker in markers:
        record = seen.get(marker['Key'])
        if record is not None:
            if database.instant(record['requestedAt']) + RETENTION > started:
                raise database.OperationError('Early withdrawal hide marker; journal completeness is uncertain')
        elif marker['LastModified'] >= started - database.MAX_AGE:
            raise database.OperationError('Unexplained recent hide marker; journal completeness is uncertain')
    verified = database.now()
    if verified - stopped > timedelta(minutes=30):
        raise database.OperationError('Reconciliation exceeded shutdown freshness limit')
    return {'complete': True, 'servicesStoppedAt': stopped.isoformat(), 'verifiedThrough': verified.isoformat(),
            'withdrawnUserIds': sorted(withdrawn), 'ledgerId': anchor['ledgerId'], 'recordingSince': anchor['recordingSince']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    init = commands.add_parser('initialize')
    init.add_argument('--ledger-id', required=True)
    init.add_argument('--confirm-all-writers-recording', action='store_true', required=True)
    recover = commands.add_parser('reconcile')
    recover.add_argument('--backup', type=Path, required=True)
    recover.add_argument('--services-stopped-at', required=True)
    recover.add_argument('--confirm-continuous-recording', action='store_true', required=True)
    recover.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    client = None
    try:
        client, bucket = remote()
        if args.action == 'initialize':
            value = initialize(client, bucket, args.ledger_id)
            print(json.dumps(value))  # Public identity and activation boundary only.
        else:
            value = reconcile(client, bucket, args.backup, database.instant(args.services_stopped_at))
            database.private_directory(args.output.parent)
            descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as output:
                json.dump(value, output)
                output.write('\n')
            print(json.dumps({'withdrawalReconciliationPrepared':True,'readyForPublicTraffic':False}))
    except Exception:
        # Never print decrypted UUIDs, SDK exception bodies, credentials or private paths.
        parser.exit(1, 'Withdrawal journal operation failed; verify coverage, storage and recovery inputs\n')
    finally:
        if client:
            client.close()


if __name__ == '__main__':
    main()
