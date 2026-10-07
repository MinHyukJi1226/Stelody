from datetime import timedelta
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backup'))
import database
import withdrawals


class Store:
    def __init__(self):
        self.objects = {}
        self.markers = []
        self.public = False
        self.days = 8

    def get_bucket_acl(self, **args):
        return {'Grants': [{'Grantee': {'URI': 'public'}}] if self.public else []}

    def get_bucket_lifecycle_configuration(self, **args):
        return {'Rules': [{'Status': 'Enabled', 'Filter': {'Prefix': withdrawals.PREFIX},
                           'Expiration': {'Days': self.days}, 'NoncurrentVersionExpiration': {'NoncurrentDays': 1}}]}

    def get_paginator(self, name):
        assert name == 'list_object_versions'
        return self

    def paginate(self, **args):
        # Two pages exercise traversal, not just the first page.
        items = [{'Key': key, 'VersionId': version, 'LastModified': database.now()}
                 for key,version in self.objects if key.startswith(args['Prefix'])]
        return [{'Versions': items[:1]}, {'Versions': items[1:],
                'DeleteMarkers': [m for m in self.markers if m['Key'].startswith(args['Prefix'])]}]

    def put_object(self, **args):
        key = args['Key']
        version = 'v' + str(len(self.objects) + 1)
        self.objects[key,version] = (args['Body'], args.get('Metadata', {}))
        return {'VersionId': version}

    def get_object(self, **args):
        body,meta = self.objects[args['Key'],args['VersionId']]
        return {'Body': io.BytesIO(body), 'Metadata': meta}


class WithdrawalJournalTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.store = Store()
        self.ledger = str(uuid.uuid4())
        self.since = database.now() - timedelta(hours=4)
        self.created = database.now() - timedelta(hours=2)
        self.stopped = database.now() - timedelta(seconds=1)
        self.bundle = self.root / 'bundle'
        self.bundle.mkdir(mode=0o700)
        dump = self.bundle / 'database.dump'
        dump.write_bytes(b'fixture archive')
        dump.chmod(0o600)
        self.manifest = {'format': 1, 'schemaVersion': 19, 'createdAt': self.created.isoformat(),
                         'expiresAt': (self.created + timedelta(days=7)).isoformat(),
                         'sha256': database.digest(dump), 'excludedData': list(database.EXCLUDED_DATA)}
        self.save_manifest()
        self.anchor = {'format': 1, 'ledgerId': self.ledger, 'recordingSince': self.since.isoformat()}
        self.store.objects[withdrawals.COVERAGE,'v1'] = (json.dumps(self.anchor).encode(), {})
        # Crypto is tested separately below; these cases focus on recovery boundaries and rejection.
        self.decrypt = patch.object(withdrawals, 'decrypt', side_effect=json.loads)
        self.decrypt.start()
        self.addCleanup(self.decrypt.stop)

    def save_manifest(self):
        path = self.bundle / 'manifest.json'
        path.write_text(json.dumps(self.manifest))
        path.chmod(0o600)

    def record(self, user=None, requested=None):
        record = str(uuid.uuid4())
        requested = self.created + timedelta(minutes=30) if requested is None else requested
        value = {'format':1, 'ledgerId':self.ledger, 'recordId':record,
                 'userId':str(uuid.uuid4()) if user is None else user, 'requestedAt':requested.isoformat()}
        key = withdrawals.PREFIX + record + '.age'
        self.store.objects[key,'v1'] = (json.dumps(value).encode(),
            {'stelody-withdrawal-format':'1','expires-at':(requested+timedelta(days=8)).isoformat()})
        return key,value

    def reconcile(self):
        return withdrawals.reconcile(self.store,'private-bucket',self.bundle,self.stopped)

    def test_merges_all_pages_and_duplicate_versions_including_prior_backup_intents(self):
        one,first = self.record(requested=self.created-timedelta(minutes=30))
        two,second = self.record()
        self.store.objects[one,'v2'] = self.store.objects[one,'v1']
        result = self.reconcile()
        self.assertEqual(result['withdrawnUserIds'], sorted([first['userId'], second['userId']]))
        self.assertTrue(result['complete'])
        # The generated file is accepted by the existing restore guard, not a new bypass.
        output = self.root / 'withdrawals.json'
        output.write_text(json.dumps(result)); output.chmod(0o600)
        self.assertEqual(database.withdrawal_ids(output,database.now(),self.created),result['withdrawnUserIds'])

    def test_backup_before_coverage_is_rejected(self):
        self.manifest['createdAt'] = (self.since-timedelta(minutes=1)).isoformat()
        self.manifest['expiresAt'] = (self.since+timedelta(days=6)).isoformat()
        self.save_manifest()
        with self.assertRaisesRegex(database.OperationError,'predates'):
            self.reconcile()

    def test_replaced_or_hidden_coverage_is_rejected(self):
        self.store.objects[withdrawals.COVERAGE,'v2'] = self.store.objects[withdrawals.COVERAGE,'v1']
        with self.assertRaisesRegex(database.OperationError,'replaced'):
            self.reconcile()
        del self.store.objects[withdrawals.COVERAGE,'v2']
        self.store.markers.append({'Key':withdrawals.COVERAGE,'LastModified':database.now()})
        with self.assertRaises(database.OperationError):
            self.reconcile()

    def test_future_and_stale_service_shutdown_times_are_rejected(self):
        for stopped in [database.now()+timedelta(seconds=1),database.now()-timedelta(hours=1)]:
            with self.subTest(stopped=stopped), self.assertRaises(database.OperationError):
                withdrawals.reconcile(self.store,'private-bucket',self.bundle,stopped)

    def test_record_after_shutdown_or_from_another_ledger_is_rejected(self):
        key,value = self.record(requested=database.now())
        with self.assertRaisesRegex(database.OperationError,'outside'):
            self.reconcile()
        del self.store.objects[key,'v1']
        key,value = self.record()
        value['ledgerId'] = str(uuid.uuid4())
        body,meta = self.store.objects[key,'v1']
        self.store.objects[key,'v1'] = (json.dumps(value).encode(),meta)
        with self.assertRaisesRegex(database.OperationError,'identity'):
            self.reconcile()

    def test_early_hide_or_short_lifecycle_cannot_claim_completeness(self):
        key,_ = self.record()
        self.store.markers.append({'Key':key,'LastModified':database.now()})
        with self.assertRaisesRegex(database.OperationError,'hide'):
            self.reconcile()
        self.store.markers=[]; self.store.days=1
        with self.assertRaisesRegex(database.OperationError,'eight days'):
            self.reconcile()

    def test_native_expiry_of_an_old_record_does_not_block_a_current_backup(self):
        self.anchor['recordingSince'] = (database.now()-timedelta(days=10)).isoformat()
        self.store.objects[withdrawals.COVERAGE,'v1'] = (json.dumps(self.anchor).encode(), {})
        key,_ = self.record(requested=database.now()-timedelta(days=9))
        self.store.markers.append({'Key':key,'LastModified':database.now()})
        self.assertEqual(self.reconcile()['withdrawnUserIds'], [])

    def test_backblaze_separate_hide_delete_and_orphan_rules_are_supported(self):
        rules = [{'Status':'Enabled','Filter':{'Prefix':withdrawals.PREFIX},**action} for action in [
            {'Expiration':{'Days':8}}, {'Expiration':{'ExpiredObjectDeleteMarker':True}},
            {'NoncurrentVersionExpiration':{'NoncurrentDays':1}}]]
        with patch.object(self.store,'get_bucket_lifecycle_configuration',return_value={'Rules':rules}):
            self.assertTrue(self.reconcile()['complete'])
        with patch.object(self.store,'get_bucket_lifecycle_configuration',return_value={'Rules':rules[:2]}):
            with self.assertRaisesRegex(database.OperationError,'missing'):
                self.reconcile()

    def test_unknown_key_oversized_file_and_conflicting_versions_are_rejected(self):
        key,value = self.record()
        self.store.objects[withdrawals.PREFIX+'unknown','v1'] = (b'{}',{})
        with self.assertRaisesRegex(database.OperationError,'key'):
            self.reconcile()
        del self.store.objects[withdrawals.PREFIX+'unknown','v1']
        original=self.store.objects[key,'v1']
        self.store.objects[key,'v1']=(b'x'*(withdrawals.MAX_BYTES+1),{})
        with self.assertRaisesRegex(database.OperationError,'size'):
            self.reconcile()
        self.store.objects[key,'v1']=original
        value['userId']=str(uuid.uuid4())
        self.store.objects[key,'v2']=(json.dumps(value).encode(),original[1])
        with self.assertRaisesRegex(database.OperationError,'Conflicting'):
            self.reconcile()

    def test_public_storage_and_retention_metadata_mismatch_are_rejected(self):
        self.store.public=True
        with self.assertRaisesRegex(database.OperationError,'private'):
            self.reconcile()
        self.store.public=False
        key,value=self.record()
        body,meta=self.store.objects[key,'v1']
        meta['expires-at']=self.created.isoformat()
        with self.assertRaisesRegex(database.OperationError,'retention'):
            self.reconcile()

    def test_initialize_only_empty_journal_and_verify_readback(self):
        self.store.objects={}
        result=withdrawals.initialize(self.store,'private-bucket',self.ledger)
        self.assertEqual(result['ledgerId'],self.ledger)
        self.assertEqual(withdrawals.coverage(self.store,'private-bucket'),result)
        with self.assertRaisesRegex(database.OperationError,'not empty'):
            withdrawals.initialize(self.store,'private-bucket',self.ledger)

    def test_actual_age_decrypts_the_minimal_record_and_rejects_tampering(self):
        self.decrypt.stop()
        keygen=Path(os.environ.get('AGE_BIN','age')).with_name('age-keygen')
        identity=self.root/'recovery.key'
        subprocess.run([str(keygen),'-o',str(identity)],check=True,capture_output=True)
        recipient=subprocess.check_output([str(keygen),'-y',str(identity)],text=True).strip()
        record={'format':1,'ledgerId':self.ledger,'recordId':str(uuid.uuid4()),'userId':str(uuid.uuid4()),'requestedAt':self.created.isoformat()}
        result=subprocess.run([os.environ.get('AGE_BIN','age'),'-e','-r',recipient],input=json.dumps(record).encode(),capture_output=True,check=True)
        with patch.dict(os.environ,{'BACKUP_AGE_IDENTITY_FILE':str(identity)}):
            self.assertEqual(withdrawals.decrypt(result.stdout),record)
            tampered=bytearray(result.stdout);tampered[-1]^=1
            with self.assertRaises(database.OperationError):
                withdrawals.decrypt(tampered)
