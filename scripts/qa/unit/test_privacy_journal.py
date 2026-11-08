import base64,copy,importlib.util,json,shutil,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
from test_platform_credentials import ROOT,load

journal=load('privacy_journal_tests','scripts/backup/privacy-journal.py')
helper=load('privacy_journal_fixture_tests','scripts/qa/privacy_journal_fixture.py')

class PrivacyJournalTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='journal-qa-',dir=ROOT/'tmp');self.addCleanup(self.directory.cleanup)
        self.fixture=helper.JournalFixture(Path(self.directory.name));self.store=self.fixture.store
        self.capsule={'schemaVersion':1,'customerId':'17','requestId':str(uuid.uuid4()),'confirmedAt':'2026-10-29T00:00:00Z','accountPolicyHash':'a'*64,'commercePolicyHash':'b'*64,'financePolicyHash':'c'*64}
    def test_signed_encrypted_chain_is_idempotent_and_contains_no_plaintext_contact(self):
        first,pin=self.store.append(self.capsule);again,unchanged=self.store.append(self.capsule)
        self.assertEqual(first,again);self.assertEqual(pin,unchanged);head,entries=self.store.load(pin);self.assertEqual(1,head['sequence']);self.assertEqual(self.capsule,entries[0]['capsule'])
        for path in self.store.directory.glob('*.age'):self.assertNotIn(self.capsule['requestId'].encode(),path.read_bytes())
        self.assertNotIn('email',json.dumps(first));self.assertNotIn('token',json.dumps(first))
    def test_stale_independently_pinned_head_cannot_validate_a_new_chain(self):
        old=self.fixture.pin;entry,pin=self.store.append(self.capsule)
        with self.assertRaisesRegex(ValueError,'pinned'):self.store.load(old)
        self.assertEqual(pin,self.store.pin(self.store.load(pin)[0]))
    def test_same_subject_or_request_cannot_change_policy_or_identity(self):
        self.store.append(self.capsule)
        for changed in ({**self.capsule,'accountPolicyHash':'d'*64},{**self.capsule,'customerId':'18'},{**self.capsule,'requestId':str(uuid.uuid4())}):
            with self.subTest(changed=changed),self.assertRaisesRegex(ValueError,'rebound'):self.store.append(changed)
    def test_missing_record_and_uncommitted_tail_fail_closed(self):
        entry,pin=self.store.append(self.capsule);path=self.store.directory/'00000000000000000001.json.age';data=path.read_bytes();path.unlink()
        with self.assertRaises(ValueError):self.store.load(pin)
        path.write_bytes(data);(self.store.directory/'00000000000000000002.json.age').write_bytes(data)
        with self.assertRaises(ValueError):self.store.load(pin)
    def test_modified_signature_is_rejected_even_if_an_attacker_can_encrypt(self):
        entry,pin=self.store.append(self.capsule);entry['signature']=base64.b64encode(bytes(64)).decode();self.store.write('00000000000000000001.json.age',entry)
        with self.assertRaises(RuntimeError):self.store.load(pin)
    def test_journal_and_key_files_cannot_live_in_backup_storage(self):
        for directory in (self.store.backup_root,self.store.backup_root/'ledger'):
            with self.subTest(directory=directory),self.assertRaises(ValueError):journal.Journal(directory,self.fixture.identity,self.fixture.public,self.store.backup_root)
        with self.assertRaises(ValueError):journal.Journal(self.store.directory,self.store.directory/'secret.key',self.fixture.public,self.store.backup_root)
    def test_unknown_fields_booleans_and_overflow_ids_are_not_privacy_capsules(self):
        for changed in ({**self.capsule,'email':'other@example.test'},{**self.capsule,'schemaVersion':True},{**self.capsule,'customerId':'9223372036854775808'},{**self.capsule,'confirmedAt':'yesterday'}):
            with self.subTest(changed=changed),self.assertRaises(ValueError):journal.capsule_hash(changed)
        journal.capsule_hash({**self.capsule,'customerId':'9223372036854775807'})
    def test_duplicate_json_keys_and_reinitialization_are_rejected(self):
        with self.assertRaises(ValueError):journal.strict(b'{"a":1,"a":2}')
        with self.assertRaises(ValueError):self.store.initialize(self.fixture.ledger_id)
    def test_signature_cannot_be_rebound_to_another_capsule(self):
        entry,pin=self.store.append(self.capsule);entry['capsule']['customerId']='18';entry['entryHash']=journal.digest(journal.entry_frame(entry));self.store.write('00000000000000000001.json.age',entry)
        with self.assertRaises(RuntimeError):self.store.load(pin)
    def test_sync_verifies_chain_once_and_commits_each_intent_before_ack(self):
        import io
        second={**self.capsule,'customerId':'18','requestId':str(uuid.uuid4())}
        page={'schemaVersion':1,'requests':[{'capsule':self.capsule},{'capsule':second}],'nextCustomerId':'18','end':True}
        writes=[]
        class Api:
            def open(_,request,timeout):
                if request.data:
                    entry=json.loads(base64.urlsafe_b64decode(json.loads(request.data)['receipt']+'=='))
                    # A signed encrypted head for this very entry already exists on disk.
                    self.assertEqual(entry['sequence'],self.store.read('head.json.age')['sequence']);writes.append(entry['capsule'])
                    return io.BytesIO(b'{"acknowledged":true}')
                return io.BytesIO(json.dumps(page).encode())
        with patch.object(journal,'build_opener',return_value=Api()),patch.object(self.store,'load',wraps=self.store.load) as read:
            result=journal.sync(self.store,'http://127.0.0.1:1','fixture-token');self.assertEqual(1,read.call_count)
        self.assertEqual([self.capsule,second],writes);self.assertEqual(2,result['acknowledgedRequests'])
        self.assertEqual(2,self.store.load(result['currentHead'])[0]['sequence'])
    def test_operator_credentials_are_never_sent_to_a_redirect_target(self):
        import threading
        from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
        received=[]
        class Redirect(BaseHTTPRequestHandler):
            def do_GET(self):received.append(self.path);self.send_response(302);self.send_header('Location','http://127.0.0.1:1/private');self.end_headers()
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Redirect);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with self.assertRaises(Exception):journal.sync(self.store,'http://127.0.0.1:'+str(server.server_port),'fixture-token')
            self.assertEqual(1,len(received))
        finally:server.shutdown();server.server_close();thread.join()

class BackupPreviewTests(unittest.TestCase):
    def test_capture_clock_uses_elapsed_monotonic_time(self):
        from datetime import timedelta
        recovery=load('privacy_clock_tests','scripts/backup/platform-recovery.py')
        with patch.object(recovery.time,'monotonic',side_effect=[100,103,99]):
            clock=recovery.CaptureClock();first=clock.now();second=clock.now()
        self.assertEqual((clock.utc+timedelta(seconds=3)).isoformat(),first)
        self.assertEqual(clock.utc.isoformat(),second)
    def test_technical_restore_never_authorizes_publication(self):
        recovery=load('privacy_report_tests','scripts/backup/platform-recovery.py')
        with tempfile.TemporaryDirectory() as directory:
            report=Path(directory)/'report.json'
            with patch.object(recovery,'Restored',side_effect=RuntimeError('fixture')):
                with self.assertRaises(RuntimeError):recovery.drill('fixture','fixture',report)
            data=json.loads(report.read_text());self.assertFalse(data['releaseAllowed']);self.assertFalse(data['privacyReconciled'])

if __name__=='__main__':unittest.main()
