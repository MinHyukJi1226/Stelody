import importlib.util
from datetime import timedelta
import json
from pathlib import Path
import tempfile
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


if __name__ == '__main__':
    unittest.main()
