#!/usr/bin/env python3
"""PostgreSQL 17 backup/isolated restore. Connection details use libpq environment only."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid

MAX_AGE = timedelta(days=7)
EXCLUDED_DATA = (
    'session.spring_session', 'session.spring_session_attributes',
    'app.youtube_connection', 'app.youtube_authorization',
    'app.youtube_export', 'app.youtube_export_item', 'app.account_reauthentication',
    'app.view_snapshot', 'app.daily_video_view', 'app.published_video_view',
    'app.collection_run', 'app.collection_target', 'app.discovery_run',
    'app.collection_retry_request',
)
BASE = Path(__file__).resolve().parent


class OperationError(Exception):
    pass


def now():
    return datetime.now(timezone.utc)


def instant(value):
    result = datetime.fromisoformat(value)
    if result.tzinfo is None:
        raise OperationError('Timestamp must include timezone')
    return result


def run(command, *, input_text=None, log=None, pass_fds=()):
    result = subprocess.run(command, input=input_text, text=True, capture_output=True, check=False,
                            pass_fds=pass_fds)
    if log is not None:
        log.write_text(result.stderr, encoding='utf-8')
        log.chmod(0o600)
    if result.returncode:
        # PostgreSQL errors can contain row data or connection information.
        raise OperationError(f'{command[0]} failed' + (f'; private log: {log}' if log else ''))
    return result.stdout.strip()


def sql(statement, log=None):
    return run(['psql', '-X', '--no-password', '-v', 'ON_ERROR_STOP=1', '-At'],
               input_text=statement, log=log)


def database_info():
    value = json.loads(sql("""SELECT json_build_object(
        'database',current_database(),'user',current_user,
        'serverVersion',current_setting('server_version_num')::int,
        'owners',(SELECT json_agg(DISTINCT pg_get_userbyid(nspowner))
                  FROM pg_namespace WHERE nspname IN ('app','session')));"""))
    if not 170000 <= value['serverVersion'] < 180000:
        raise OperationError('This procedure requires PostgreSQL 17')
    return value


def private_directory(path):
    if path.is_symlink():
        raise OperationError('Directory must not be a symlink')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.stat().st_mode & 0o077:
        raise OperationError('Directory permissions must be 700')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


@contextmanager
def backup_lock(root):
    # Keep this inode permanently: unlinking a lock file can create independent locks.
    descriptor = os.open(root / '.backup.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
            raise OperationError('Backup lock must be a private regular file (600)')
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield None
        else:
            # Closing (rather than explicitly unlocking) keeps an inherited child lock
            # alive if Python is killed while pg_dump or pg_restore is still running.
            yield descriptor
    finally:
        os.close(descriptor)


def backup(root):
    private_directory(root)
    with backup_lock(root) as descriptor:
        if descriptor is None:
            raise OperationError('Another backup or expiry cleanup is running')
        create_backup(root, descriptor)


def create_backup(root, descriptor):
    info = database_info()
    if info['owners'] != [info['user']]:
        raise OperationError('Connect as the app/session schema owner')
    version = sql("SELECT max(version::int) FROM app.flyway_schema_history WHERE success;")
    if version != '19':
        raise OperationError('This backup procedure is validated for schema V19')
    created = now()
    expires = created + MAX_AGE
    source_expiry = sql("""SELECT min(expires_at)::text FROM (
        SELECT source_observed_at+INTERVAL '30 days' AS expires_at FROM app.video
            WHERE source_observed_at IS NOT NULL AND
                (source_title<>'' OR source_published_at IS NOT NULL OR source_thumbnail_url IS NOT NULL OR source_duration_seconds IS NOT NULL)
        UNION ALL SELECT source_observed_at+INTERVAL '30 days' FROM app.review_item
            WHERE source_title IS NOT NULL OR source_published_at IS NOT NULL OR source_thumbnail_url IS NOT NULL OR source_duration_seconds IS NOT NULL
        UNION ALL SELECT source_expires_at FROM app.special_event_review WHERE evidence<>'[]'::jsonb
    ) sources;""")
    if source_expiry:
        expires = min(expires, instant(source_expiry))
    if expires <= created:
        raise OperationError('Clean up expired source metadata before backup')
    staging = Path(tempfile.mkdtemp(prefix='.partial-', dir=root))
    destination = root / ('stelody-' + created.strftime('%Y%m%dT%H%M%S%fZ'))
    try:
        # Persist retention before any user data can be written, including when
        # source metadata requires a shorter lifetime than seven days.
        retention = staging / 'retention.json'
        retention.write_text(json.dumps({'format': 1, 'createdAt': created.isoformat(),
                                         'expiresAt': expires.isoformat()}) + '\n')
        retention.chmod(0o600)
        with retention.open('rb') as stream:
            os.fsync(stream.fileno())
        directory_descriptor = os.open(staging, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
        dump = staging / 'database.dump'
        command = ['pg_dump', '--no-password', '--format=custom', '--schema=app', '--schema=session',
                   '--extension=pg_trgm', '--no-owner', '--file=' + str(dump)]
        command += ['--exclude-table-data=' + table for table in EXCLUDED_DATA]
        run(command, log=staging / 'dump.log', pass_fds=(descriptor,))
        dump.chmod(0o600)
        run(['pg_restore', '--list', str(dump)], log=staging / 'archive-check.log', pass_fds=(descriptor,))
        manifest = {'format': 1, 'createdAt': created.isoformat(), 'expiresAt': expires.isoformat(), 'schemaVersion': 19,
                    'schemaOwner': info['user'], 'sourceDatabase': info['database'],
                    'sha256': digest(dump), 'excludedData': list(EXCLUDED_DATA)}
        (staging / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        (staging / 'manifest.json').chmod(0o600)
        staging.rename(destination)
    except BaseException:
        shutil.rmtree(staging)
        raise
    print(json.dumps({'backup': str(destination), 'expiresAt': expires.isoformat()}))


def validate_archive(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise OperationError('Backup must be a real directory')
    if directory.stat().st_mode & 0o077:
        raise OperationError('Backup directory must be private (700)')
    for name in ('manifest.json', 'database.dump'):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise OperationError('Missing or unsafe backup file')
        if path.stat().st_mode & 0o077:
            raise OperationError('Backup files must be private (600)')
    metadata = json.loads((directory / 'manifest.json').read_text())
    if metadata.get('format') != 1 or metadata.get('schemaVersion') != 19:
        raise OperationError('Unsupported backup format/schema')
    if metadata.get('excludedData') != list(EXCLUDED_DATA):
        raise OperationError('Backup exclusion policy does not match')
    age = now() - instant(metadata['createdAt'])
    expiry = instant(metadata['expiresAt'])
    created = instant(metadata['createdAt'])
    if expiry > created + MAX_AGE or expiry <= created:
        raise OperationError('Invalid backup expiry')
    if age < timedelta(0) or age >= MAX_AGE or now() >= expiry:
        raise OperationError('Backup is expired or has a future timestamp')
    if digest(directory / 'database.dump') != metadata['sha256']:
        raise OperationError('Backup checksum mismatch')
    return metadata


def withdrawal_ids(path, restored_at, backup_created_at):
    if path.is_symlink() or path.stat().st_mode & 0o077:
        raise OperationError('Withdrawal reconciliation file must be private (600)')
    data = json.loads(path.read_text())
    stopped = instant(data['servicesStoppedAt'])
    verified = instant(data['verifiedThrough'])
    if (data.get('complete') is not True or not backup_created_at <= stopped <= verified <= restored_at
            or restored_at - stopped > timedelta(minutes=30)):
        raise OperationError('Confirm stopped services and complete withdrawals through that time (within 30 minutes)')
    return sorted({str(uuid.UUID(value)) for value in data['withdrawnUserIds']})


def restore(directory, target, reconciliations):
    started = now()
    metadata = validate_archive(directory)
    withdrawn = withdrawal_ids(reconciliations, started, instant(metadata['createdAt']))
    info = database_info()
    if info['database'] != target or info['database'] == metadata['sourceDatabase']:
        raise OperationError('Restore only to the explicitly named, different database')
    if info['user'] != metadata['schemaOwner']:
        raise OperationError('Target schema owner role must match the backup')
    if sql("""SELECT count(*) FROM pg_namespace WHERE nspname IN ('app','session');""") != '0':
        raise OperationError('Restore target already contains app/session schemas')
    if sql("""SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
               WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname<>'information_schema'
                 AND c.relkind IN ('r','p','v','m','S','f');""") != '0':
        raise OperationError('Restore target must be empty')
    # No --clean, --create, --disable-triggers, or --no-acl: existing data is never overwritten,
    # and PUBLIC revocations plus runtime/collector permissions must survive restoration.
    temporary = Path(tempfile.mkdtemp(prefix='stelody-restore-'))
    try:
        log = temporary / 'restore.log'
        run(['pg_restore', '--no-password', '--exit-on-error', '--single-transaction',
             '--no-owner', '--dbname=' + target, str(directory / 'database.dump')], log=log)
        sanitation = (BASE / 'restore-sanitize.sql').read_text()
        deletions = '\n'.join(
            "UPDATE app.catalog_audit SET target_id=gen_random_uuid() "
            f"WHERE target_type='ACCOUNT_ROLE' AND target_id='{value}'::uuid;\n"
            f"DELETE FROM app.app_user WHERE id='{value}'::uuid;" for value in withdrawn)
        # One transaction covers sanitation and deletion replay. Keep all services stopped.
        sanitation = sanitation.replace('COMMIT;', deletions + '\nCOMMIT;')
        sql(sanitation, log)
        for table in EXCLUDED_DATA:
            if sql('SELECT count(*) FROM ' + table + ';') != '0':
                raise OperationError('Ephemeral data unexpectedly restored')
        if withdrawn:
            remaining = sql("SELECT count(*) FROM app.app_user WHERE id IN (" +
                            ','.join("'%s'::uuid" % value for value in withdrawn) + ');')
            if remaining != '0':
                raise OperationError('Withdrawn account unexpectedly restored')
    except BaseException:
        # Keep private diagnostics after failed restoration; the target stays offline.
        raise
    else:
        shutil.rmtree(temporary)
    print(json.dumps({'restored': target, 'sanitized': True, 'withdrawalsReconciled': len(withdrawn),
                      'readyForPublicTraffic': False}))


def prune_unlocked(root):
    removed = 0
    for directory in root.glob('stelody-*'):
        if directory.is_symlink() or not directory.is_dir():
            continue
        manifest = directory / 'manifest.json'
        if manifest.is_symlink() or not manifest.is_file():
            continue
        metadata = json.loads(manifest.read_text())
        if metadata.get('format') == 1 and now() >= min(instant(metadata['createdAt']) + MAX_AGE, instant(metadata['expiresAt'])):
            shutil.rmtree(directory)
            removed += 1
    partial_removed = 0
    for directory in root.glob('.partial-*'):
        if directory.is_symlink() or not directory.is_dir():
            continue
        # Legacy staging folders have no retention record. Their directory mtime
        # provides a conservative fallback, also used for interrupted record writes.
        expiry = datetime.fromtimestamp(directory.stat().st_mtime, timezone.utc) + MAX_AGE
        retention = directory / 'retention.json'
        if not retention.is_symlink() and retention.is_file():
            try:
                metadata = json.loads(retention.read_text())
                if metadata.get('format') == 1:
                    expiry = min(expiry, instant(metadata['createdAt']) + MAX_AGE,
                                 instant(metadata['expiresAt']))
            except (ValueError, KeyError, TypeError, OperationError):
                pass
        if now() >= expiry:
            shutil.rmtree(directory)
            partial_removed += 1
    print(json.dumps({'expiredBackupsRemoved': removed, 'expiredPartialBackupsRemoved': partial_removed,
                      'skippedActiveBackup': False}))


def prune(root):
    private_directory(root)
    with backup_lock(root) as descriptor:
        if descriptor is None:
            print(json.dumps({'expiredBackupsRemoved': 0, 'expiredPartialBackupsRemoved': 0,
                              'skippedActiveBackup': True}))
            return
        prune_unlocked(root)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    save = commands.add_parser('backup')
    save.add_argument('--directory', type=Path, required=True)
    recover = commands.add_parser('restore')
    recover.add_argument('--backup', type=Path, required=True)
    recover.add_argument('--target', required=True)
    recover.add_argument('--withdrawals', type=Path, required=True)
    expire = commands.add_parser('prune')
    expire.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'backup':
            backup(args.directory)
        elif args.command == 'restore':
            restore(args.backup, args.target, args.withdrawals)
        else:
            prune(args.directory)
    except OperationError as error:
        print(str(error), file=sys.stderr)
        return 1
    except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError):
        # Even malformed input/errors may contain credentials. Never emit their raw values.
        print('Operation failed. Check connection, permissions, backup age/integrity, empty target, and reconciliation.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
