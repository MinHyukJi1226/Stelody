import importlib.util
from datetime import timedelta
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup', Path(__file__).resolve().parents[1] / 'backup/database.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class BackupGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / 'stelody-valid'
        self.bundle.mkdir(mode=0o700)
        self.dump = self.bundle / 'database.dump'
        self.dump.write_bytes(b'test archive')
        self.dump.chmod(0o600)
        self.metadata = {'format': 1, 'schemaVersion': 19, 'schemaOwner': 'owner',
                         'sourceDatabase': 'source', 'createdAt': backup.now().isoformat(),
                         'expiresAt': (backup.now() + timedelta(days=6)).isoformat(),
                         'sha256': backup.digest(self.dump), 'excludedData': list(backup.EXCLUDED_DATA)}
        self.save_manifest()

    def save_manifest(self):
        path = self.bundle / 'manifest.json'
        path.write_text(json.dumps(self.metadata))
        path.chmod(0o600)

    def test_v19_and_v20_archives_remain_valid_but_unknown_schema_is_rejected(self):
        for version in (19, 20):
            self.metadata['schemaVersion'] = version
            self.save_manifest()
            self.assertEqual(version, backup.validate_archive(self.bundle)['schemaVersion'])
        self.metadata['schemaVersion'] = 21
        self.save_manifest()
        with self.assertRaisesRegex(backup.OperationError, 'Unsupported backup format/schema'):
            backup.validate_archive(self.bundle)

    def ledger(self, **overrides):
        timestamp = backup.now().isoformat()
        data = {'complete': True, 'servicesStoppedAt': timestamp,
                'verifiedThrough': timestamp, 'withdrawnUserIds': []}
        data.update(overrides)
        path = self.root / 'withdrawals.json'
        path.write_text(json.dumps(data))
        path.chmod(0o600)
        return path

    def test_corrupt_archive_rejected_before_database_access(self):
        self.dump.write_bytes(b'corrupted')
        with patch.object(backup, 'database_info') as database:
            with self.assertRaisesRegex(backup.OperationError, 'checksum'):
                backup.restore(self.bundle, 'target', self.ledger())
            database.assert_not_called()

    def test_expired_and_future_backups_rejected(self):
        for offset in (-timedelta(days=7), timedelta(minutes=1)):
            self.metadata['createdAt'] = (backup.now() + offset).isoformat()
            self.metadata['expiresAt'] = (backup.now() + offset + timedelta(days=6)).isoformat()
            self.save_manifest()
            with self.assertRaisesRegex(backup.OperationError, 'expired|future'):
                backup.validate_archive(self.bundle)

    def test_source_expiry_can_shorten_seven_day_retention(self):
        self.metadata['createdAt'] = (backup.now() - timedelta(hours=1)).isoformat()
        self.metadata['expiresAt'] = (backup.now() - timedelta(minutes=1)).isoformat()
        self.save_manifest()
        with self.assertRaisesRegex(backup.OperationError, 'expired'):
            backup.validate_archive(self.bundle)

    def test_exposed_files_and_symlinked_archives_rejected(self):
        self.dump.chmod(0o644)
        with self.assertRaisesRegex(backup.OperationError, 'private'):
            backup.validate_archive(self.bundle)
        self.dump.chmod(0o600)
        alias = self.root / 'alias'
        alias.symlink_to(self.bundle, target_is_directory=True)
        with self.assertRaisesRegex(backup.OperationError, 'real directory'):
            backup.validate_archive(alias)

    def test_incomplete_or_stale_withdrawals_rejected(self):
        started = backup.now()
        for data in ({'complete': False},
                     {'servicesStoppedAt': (started - timedelta(hours=1)).isoformat()},
                     {'verifiedThrough': (started + timedelta(hours=1)).isoformat()}):
            with self.assertRaises(backup.OperationError):
                backup.withdrawal_ids(self.ledger(**data), backup.now(),
                                      started - timedelta(days=1))

    def test_uuid_input_cannot_inject_sql(self):
        path = self.ledger(withdrawnUserIds=["'; DELETE FROM app.song_entry;--"])
        with self.assertRaises(ValueError):
            backup.withdrawal_ids(path, backup.now(), backup.now() - timedelta(days=1))

    def test_original_database_and_occupied_target_rejected_before_restore(self):
        for name, schemas in [('source', '0'), ('target', '1')]:
            with patch.object(backup, 'database_info', return_value={'database': name, 'user': 'owner'}), \
                 patch.object(backup, 'sql', return_value=schemas), patch.object(backup, 'run') as restore:
                with self.assertRaises(backup.OperationError):
                    backup.restore(self.bundle, name, self.ledger())
                restore.assert_not_called()

    def test_prune_retains_recent_unrecognized_and_symlinked_entries(self):
        expired = self.root / 'stelody-expired'
        expired.mkdir()
        old = dict(self.metadata, createdAt=(backup.now() - timedelta(days=8)).isoformat(),
                   expiresAt=(backup.now() - timedelta(days=1)).isoformat())
        (expired / 'manifest.json').write_text(json.dumps(old))
        unrelated = self.root / 'stelody-unrecognized'
        unrelated.mkdir()
        external = self.root / 'external'
        external.mkdir()
        (self.root / 'stelody-symlink').symlink_to(external, target_is_directory=True)
        with patch('builtins.print'):
            backup.prune(self.root)
        self.assertFalse(expired.exists())
        self.assertTrue(self.bundle.exists())
        self.assertTrue(unrelated.exists())
        self.assertTrue(external.exists())

    def partial(self, name, age, metadata=None):
        directory = self.root / ('.partial-' + name)
        directory.mkdir(mode=0o700)
        (directory / 'database.dump').write_bytes(b'synthetic archive')
        if metadata is not None:
            (directory / 'retention.json').write_text(json.dumps(metadata))
        timestamp = (backup.now() - age).timestamp()
        os.utime(directory, (timestamp, timestamp))
        return directory

    def test_prune_legacy_and_corrupt_partial_records_at_seven_days(self):
        old = self.partial('legacy', timedelta(days=8))
        corrupt = self.partial('corrupt', timedelta(days=8), {'format': 1})
        recent = self.partial('recent', timedelta(days=6))
        with patch('builtins.print'):
            backup.prune(self.root)
        self.assertFalse(old.exists())
        self.assertFalse(corrupt.exists())
        self.assertTrue(recent.exists())

    def test_prune_partial_honors_earlier_source_expiry(self):
        old = self.partial('source-expired', timedelta(minutes=1),
                           dict(self.metadata, createdAt=(backup.now() - timedelta(hours=2)).isoformat(),
                                expiresAt=(backup.now() - timedelta(hours=1)).isoformat()))
        recent = self.partial('source-valid', timedelta(minutes=1), self.metadata)
        with patch('builtins.print'):
            backup.prune(self.root)
        self.assertFalse(old.exists())
        self.assertTrue(recent.exists())

    def test_prune_never_follows_partial_or_retention_symlinks(self):
        external = self.root / 'external'
        external.mkdir()
        marker = external / 'keep'
        marker.write_text('preserve')
        alias = self.root / '.partial-symlink'
        alias.symlink_to(external, target_is_directory=True)
        old = self.partial('old-link', timedelta(days=8))
        (old / 'retention.json').symlink_to(marker)
        timestamp = (backup.now() - timedelta(days=8)).timestamp()
        os.utime(old, (timestamp, timestamp))
        with patch('builtins.print'):
            backup.prune(self.root)
        self.assertTrue(alias.is_symlink())
        self.assertEqual('preserve', marker.read_text())
        self.assertFalse(old.exists())

    def test_active_backup_blocks_prune_and_another_backup(self):
        old = self.partial('active', timedelta(days=8))
        with backup.backup_lock(self.root), patch('builtins.print') as output:
            backup.prune(self.root)
            self.assertTrue(json.loads(output.call_args.args[0])['skippedActiveBackup'])
            with patch.object(backup, 'database_info') as database:
                with self.assertRaisesRegex(backup.OperationError, 'running'):
                    backup.backup(self.root)
                database.assert_not_called()
        self.assertTrue(old.exists())
        with patch('builtins.print'):
            backup.prune(self.root)
        self.assertFalse(old.exists())

    def test_symlinked_lock_cannot_redirect_locking(self):
        target = self.root / 'unrelated'
        target.write_text('preserve')
        (self.root / '.backup.lock').symlink_to(target)
        with self.assertRaises(OSError):
            backup.prune(self.root)
        self.assertEqual('preserve', target.read_text())

    def test_retention_written_before_dump_with_child_lock_and_shorter_expiry(self):
        expiry = (backup.now() + timedelta(hours=1)).isoformat()

        def dump(command, **kwargs):
            if command[0] == 'pg_dump':
                path = Path(next(arg[7:] for arg in command if arg.startswith('--file=')))
                retention = path.parent / 'retention.json'
                self.assertEqual(expiry, json.loads(retention.read_text())['expiresAt'])
                self.assertEqual(0, retention.stat().st_mode & 0o077)
                self.assertEqual(1, len(kwargs['pass_fds']))
                with backup.backup_lock(self.root) as descriptor:
                    self.assertIsNone(descriptor)
                path.write_bytes(b'synthetic archive')
            return ''

        with patch.object(backup, 'database_info', return_value={'owners': ['owner'], 'user': 'owner', 'database': 'test'}), \
             patch.object(backup, 'sql', side_effect=['20', expiry]), \
             patch.object(backup, 'run', side_effect=dump), patch('builtins.print'):
            backup.backup(self.root)
        completed = [path for path in self.root.glob('stelody-*') if path != self.bundle]
        self.assertEqual(1, len(completed))
        self.assertEqual(expiry, backup.validate_archive(completed[0])['expiresAt'])
        self.assertEqual(20, backup.validate_archive(completed[0])['schemaVersion'])
        self.assertFalse(list(self.root.glob('.partial-*')))

    def wait_until(self, predicate):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.01)
        self.fail('Timed out waiting for backup subprocess')

    def test_sigkill_staging_is_pruned_after_inherited_dump_lock_released(self):
        # Real process termination and an orphaned dump child, using synthetic bytes
        # and mocked DB preflight; PostgreSQL/user data are never accessed.
        ready = self.root / 'ready'
        release = self.root / 'release'
        worker = '''import importlib.util, sys
from pathlib import Path
from datetime import timedelta
spec = importlib.util.spec_from_file_location('backup', sys.argv[1])
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
timestamp = b.now() - timedelta(days=8)
b.now = lambda: timestamp
b.database_info = lambda: {'owners': ['owner'], 'user': 'owner', 'database': 'test'}
b.sql = lambda statement: '19' if 'flyway' in statement else ''
original_run = b.run
child = """import sys, time
from pathlib import Path
Path(sys.argv[1]).write_bytes(b'synthetic archive')
Path(sys.argv[2]).write_text('ready')
while not Path(sys.argv[3]).exists(): time.sleep(0.01)
"""
def dump(command, **kwargs):
    path = next(arg[7:] for arg in command if arg.startswith('--file='))
    return original_run([sys.executable, '-B', '-c', child, path, sys.argv[3], sys.argv[4]], **kwargs)
b.run = dump
b.backup(Path(sys.argv[2]))
'''
        process = subprocess.Popen([sys.executable, '-B', '-c', worker, str(Path(spec.origin)),
                                    str(self.root), str(ready), str(release)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            self.wait_until(ready.exists)
            process.kill()
            process.wait(timeout=5)
            partial = list(self.root.glob('.partial-*'))
            self.assertEqual(1, len(partial))
            with patch('builtins.print') as output:
                backup.prune(self.root)
                self.assertTrue(json.loads(output.call_args.args[0])['skippedActiveBackup'])
            self.assertTrue((partial[0] / 'database.dump').exists())
            release.touch()

            def unlocked():
                with backup.backup_lock(self.root) as descriptor:
                    return descriptor is not None

            self.wait_until(unlocked)
            with patch('builtins.print') as output:
                backup.prune(self.root)
                self.assertEqual(1, json.loads(output.call_args.args[0])['expiredPartialBackupsRemoved'])
            self.assertFalse(partial[0].exists())
            self.assertTrue(self.bundle.exists())
        finally:
            release.touch()
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
