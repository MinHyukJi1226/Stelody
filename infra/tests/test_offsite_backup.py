from datetime import timedelta
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backup'))
import database
import offsite


class Store:
    def __init__(self):
        self.objects = {}
        self.deleted = []
        self.requests = 0
        self.corrupt = False
        self.versions = {}
        self.markers = []
        self.public = False
        self.lifecycle = True
        self.latest_versions = {}

    def get_bucket_acl(self, **args):
        return {'Grants': [{'Grantee': {'URI':'http://acs.amazonaws.com/groups/global/AllUsers'}}] if self.public else []}

    def get_bucket_lifecycle_configuration(self, **args):
        return {'Rules': [{'Status':'Enabled','Filter':{'Prefix':offsite.PREFIX},
                           'Expiration':{'Days':1},'NoncurrentVersionExpiration':{'NoncurrentDays':1}}] if self.lifecycle else []}

    def put_object(self, **args):
        self.requests += 1
        self.objects[args['Key']] = (args['Body'].read(), args['Metadata'])
        self.versions[(args['Key'], 'v1')] = self.objects[args['Key']]
        self.latest_versions[args['Key']] = 'v1'
        return {'VersionId': 'v1'}

    def get_object(self, **args):
        self.requests += 1
        data, metadata = self.versions[(args['Key'], args['VersionId'])] if 'VersionId' in args else self.objects[args['Key']]
        return {'Body': io.BytesIO(data + (b'bad' if self.corrupt else b'')), 'Metadata': metadata}

    def head_object(self, **args):
        return {'Metadata': self.versions[(args['Key'], args['VersionId'])][1]}

    def get_paginator(self, name):
        assert name == 'list_object_versions'
        return self

    def paginate(self, **args):
        return [{'Versions': [{'Key': k, 'VersionId': v,
                               'IsLatest': self.latest_versions.get(k) == v
                               and not any(m['Key'] == k and m.get('IsLatest', True) for m in self.markers)}
                              for k,v in self.versions], 'DeleteMarkers': self.markers}]

    def delete_object(self, **args):
        assert args.get('VersionId')
        self.deleted.append((args['Key'], args['VersionId']))
        self.versions.pop((args['Key'],args['VersionId']), None)


class OffsiteBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.store = Store()
        self.encrypted = self.root / 'encrypted.age'
        self.encrypted.write_bytes(b'age encrypted fixture')
        self.encrypted.chmod(0o600)
        self.value = {'format': 1, 'key': offsite.PREFIX + '20261005T000000Z-' + 'a' * 32 + '.age',
                      'createdAt': database.now().isoformat(),
                      'expiresAt': (database.now() + timedelta(days=6)).isoformat(),
                      'sha256': database.digest(self.encrypted)}
        self.save()
        self.remote = patch.object(offsite, 'remote', return_value=(self.store, 'stelody-backups'))
        self.remote.start()
        self.addCleanup(self.remote.stop)

    def save(self):
        sidecar = self.encrypted.with_suffix('.json')
        sidecar.write_text(json.dumps(self.value))
        sidecar.chmod(0o600)

    def test_upload_reads_back_ciphertext_and_detects_corruption(self):
        key = offsite.upload(self.encrypted)
        self.assertEqual(key, self.value['key'])
        self.assertEqual(self.store.requests, 2)
        self.store.corrupt = True
        with self.assertRaisesRegex(database.OperationError, 'verification'):
            offsite.upload(self.encrypted)

    def test_expired_short_lived_corrupt_or_exposed_bundle_never_uploaded(self):
        self.value['expiresAt'] = (database.now() + timedelta(days=2)).isoformat()
        self.save()
        with self.assertRaisesRegex(database.OperationError, 'five days'):
            offsite.upload(self.encrypted)
        self.assertEqual(self.store.requests, 0)
        self.value['expiresAt'] = (database.now() - timedelta(seconds=1)).isoformat()
        self.save()
        with self.assertRaises(database.OperationError):
            offsite.upload(self.encrypted)
        self.encrypted.chmod(0o644)
        with self.assertRaises(database.OperationError):
            offsite.upload(self.encrypted)
        self.assertEqual(self.store.requests, 0)

    def test_upload_refuses_public_bucket_or_missing_permanent_expiry(self):
        self.store.public = True
        with self.assertRaisesRegex(database.OperationError, 'private'):
            offsite.upload(self.encrypted)
        self.assertEqual(self.store.requests, 0)
        self.store.public = False
        self.store.lifecycle = False
        with self.assertRaisesRegex(database.OperationError, 'permanent deletion'):
            offsite.upload(self.encrypted)
        self.assertEqual(self.store.requests, 0)

    def test_download_validates_checksum_and_does_not_overwrite(self):
        offsite.upload(self.encrypted)
        destination = self.root / 'download'
        recovered = offsite.download(self.value['key'], destination)
        self.assertEqual(recovered.read_bytes(), self.encrypted.read_bytes())
        self.assertEqual(recovered.stat().st_mode & 0o777, 0o600)
        with self.assertRaisesRegex(database.OperationError, 'already exists'):
            offsite.download(self.value['key'], destination)
        self.store.corrupt = True
        other = self.root / 'corrupt'
        with self.assertRaisesRegex(database.OperationError, 'checksum'):
            offsite.download(self.value['key'], other)
        self.assertEqual(list(other.iterdir()), [])

    def test_prune_only_removes_expired_managed_objects(self):
        offsite.upload(self.encrypted)
        self.assertEqual(offsite.prune(), 0)
        metadata = self.store.objects[self.value['key']][1]
        metadata['expires-at'] = (database.now() - timedelta(seconds=1)).isoformat()
        self.store.versions[('unrelated','v1')] = (b'other', metadata)
        self.assertEqual(offsite.prune(), 1)
        self.assertEqual(self.store.deleted, [(self.value['key'],'v1')])

    def test_prune_hard_deletes_noncurrent_versions_and_preserves_hidden_live_data(self):
        offsite.upload(self.encrypted)
        key = self.value['key']
        expired = dict(self.store.objects[key][1])
        expired['expires-at'] = (database.now() - timedelta(seconds=1)).isoformat()
        self.store.versions[(key, 'old')] = (b'old', expired)
        self.store.markers = [{'Key': key, 'VersionId': 'hide'}]
        self.assertEqual(offsite.prune(), 1)
        self.assertEqual(self.store.deleted, [(key, 'old')])
        self.assertIn((key, 'v1'), self.store.versions)
        self.store.versions[(key, 'v1')] = (b'current', expired)
        self.assertEqual(offsite.prune(), 2)
        self.assertEqual(self.store.deleted[-2:], [(key, 'v1'), (key, 'hide')])

    def test_prune_keeps_unknown_versions_and_their_hide_markers(self):
        key = self.value['key']
        self.store.versions[(key, 'unknown')] = (b'unknown', {})
        self.store.markers = [{'Key': key, 'VersionId': 'hide'}, {'Key':'unrelated','VersionId':'other'}]
        self.assertEqual(offsite.prune(), 0)
        self.assertEqual(self.store.deleted, [])

    def test_scheduled_backup_recovers_missed_run_at_any_hour(self):
        offsite.upload(self.encrypted)
        created = database.instant(self.value['createdAt'])
        for elapsed, required in ((timedelta(hours=23, minutes=59), False),
                                  (timedelta(hours=24), True),
                                  (timedelta(hours=29, minutes=13), True)):
            with self.subTest(elapsed=elapsed), patch.object(database, 'now', return_value=created + elapsed):
                result = offsite.backup_due()
                self.assertEqual(result['backupRequired'], required)
                self.assertEqual(result['reason'], 'stale' if required else 'recent')
                self.assertEqual(result['latestBackupCreatedAt'], self.value['createdAt'])
        self.assertEqual(self.store.deleted, [])

    def test_missing_hidden_noncurrent_and_expired_backups_require_replacement(self):
        self.assertEqual(offsite.backup_due()['reason'], 'missing')
        offsite.upload(self.encrypted)
        key = self.value['key']
        self.store.markers = [{'Key': key, 'VersionId': 'hide', 'IsLatest': True}]
        self.assertTrue(offsite.backup_due()['backupRequired'])
        self.store.markers = []
        self.store.latest_versions.clear()
        self.assertTrue(offsite.backup_due()['backupRequired'])
        self.store.latest_versions[key] = 'v1'
        with patch.object(database, 'now', return_value=database.instant(self.value['expiresAt'])):
            self.assertEqual(offsite.backup_due()['reason'], 'missing')
        self.assertEqual(self.store.deleted, [])

    def test_due_check_uses_newest_created_time_across_all_pages(self):
        offsite.upload(self.encrypted)
        old_key = offsite.PREFIX + '20261004T000000Z-' + 'b' * 32 + '.age'
        old_metadata = dict(offsite.object_metadata(self.value))
        old_metadata['created-at'] = (database.now() - timedelta(days=2)).isoformat()
        old_metadata['expires-at'] = (database.now() + timedelta(days=4)).isoformat()
        self.store.versions[(old_key, 'old')] = (b'old', old_metadata)
        pages = [{'Versions': [{'Key': old_key, 'VersionId': 'old', 'IsLatest': True}]},
                 {'Versions': [{'Key': self.value['key'], 'VersionId': 'v1', 'IsLatest': True}]}]
        with patch.object(self.store, 'paginate', return_value=pages):
            self.assertFalse(offsite.backup_due()['backupRequired'])

    def test_due_check_never_accepts_corrupt_recent_backup_as_success(self):
        offsite.upload(self.encrypted)
        self.store.corrupt = True
        with self.assertRaisesRegex(database.OperationError, 'verification'):
            offsite.backup_due()

    def test_due_check_refuses_invalid_metadata_and_unsafe_storage(self):
        offsite.upload(self.encrypted)
        original = offsite.object_metadata(self.value)
        invalid = ({'stelody-format': 'unknown'},
                   {'created-at': (database.now() + timedelta(hours=1)).isoformat()},
                   {'expires-at': (database.now() + timedelta(days=8)).isoformat()},
                   {'cipher-sha256': 'invalid'}, {'created-at': '2026-10-09T12:00:00'})
        for changes in invalid:
            self.store.versions[(self.value['key'], 'v1')] = (self.encrypted.read_bytes(), original | changes)
            with self.subTest(changes=changes), self.assertRaises(database.OperationError):
                offsite.backup_due()
        self.store.versions[(self.value['key'], 'v1')] = (self.encrypted.read_bytes(), original)
        self.store.public = True
        with self.assertRaisesRegex(database.OperationError, 'private'):
            offsite.backup_due()
        self.store.public = False
        self.store.lifecycle = False
        with self.assertRaisesRegex(database.OperationError, 'permanent deletion'):
            offsite.backup_due()

    def test_due_cli_writes_boolean_output_and_redacts_provider_errors(self):
        output_file = self.root / 'github-output'
        for required in (True, False):
            with patch.object(sys, 'argv', ['offsite.py', 'backup-due', '--github-output', str(output_file)]), \
                    patch.object(offsite, 'backup_due', return_value={'backupRequired': required}), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(offsite.main(), 0)
        self.assertEqual(output_file.read_text(), 'backup_required=true\nbackup_required=false\n')
        output_file.unlink()
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', ['offsite.py', 'backup-due', '--github-output', str(output_file)]), \
                patch.object(offsite, 'remote', side_effect=RuntimeError('private-provider-credential')), \
                redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(offsite.main(), 1)
        self.assertNotIn('private-provider-credential', stdout.getvalue() + stderr.getvalue())
        self.assertFalse(output_file.exists())

    def test_rejects_other_storage_endpoints_and_keys(self):
        self.remote.stop()
        with patch.dict(os.environ, BACKUP_B2_ENDPOINT='https://example.com', BACKUP_B2_BUCKET='stelody-backups'):
            with self.assertRaisesRegex(database.OperationError, 'Backblaze'):
                offsite.remote()
        with self.assertRaises(database.OperationError):
            offsite.download('../other', self.root)

    @unittest.skipUnless(shutil.which('age') and shutil.which('age-keygen'), 'age tools needed for encryption integration')
    def test_actual_encryption_decryption_and_tamper_rejection(self):
        import subprocess
        identity = self.root / 'identity'
        subprocess.run(['age-keygen', '-o', str(identity)], check=True, capture_output=True)
        identity.chmod(0o600)
        recipient = subprocess.check_output(['age-keygen', '-y', str(identity)], text=True).strip()
        bundle = self.root / 'source'
        bundle.mkdir(mode=0o700)
        dump = bundle / 'database.dump'
        dump.write_bytes(b'private fixture: neither ciphertext nor logs should contain this')
        dump.chmod(0o600)
        metadata = {'format': 1, 'schemaVersion': 19, 'schemaOwner': 'owner', 'sourceDatabase': 'source',
                    'createdAt': self.value['createdAt'], 'expiresAt': self.value['expiresAt'],
                    'sha256': database.digest(dump), 'excludedData': list(database.EXCLUDED_DATA)}
        manifest = bundle / 'manifest.json'
        manifest.write_text(json.dumps(metadata))
        manifest.chmod(0o600)
        with patch.dict(os.environ, BACKUP_AGE_RECIPIENT=recipient, BACKUP_AGE_IDENTITY_FILE=str(identity)):
            encrypted = offsite.seal(bundle, self.root / 'sealed')
            self.assertNotIn(dump.read_bytes(), encrypted.read_bytes())
            restored = offsite.unseal(encrypted, self.root / 'restored')
            self.assertEqual((restored / 'database.dump').read_bytes(), dump.read_bytes())
            content = bytearray(encrypted.read_bytes())
            content[-1] ^= 1
            encrypted.write_bytes(content)
            with self.assertRaisesRegex(database.OperationError, 'age operation failed'):
                offsite.unseal(encrypted, self.root / 'bad')
            self.assertEqual(list((self.root / 'bad').iterdir()), [])
