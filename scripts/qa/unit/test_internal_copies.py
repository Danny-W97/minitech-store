import contextlib,io,json,os,tempfile,threading,unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch
from test_platform_credentials import ROOT,load,server
history=load('cc_dispatch_history_qa','scripts/control-center/web/cc_dispatch_history.py')

class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=ROOT/'tmp');self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.legacy=self.root/'dispatch-history.json'
    def row(self,n=1):return {'id':f'{n:08x}','timestamp':'2026-10-30 12:00:00','documentType':'INVOICE','documentId':n,'orderId':n,'status':'SENT'}
    def test_new_metadata_excludes_personal_fields_and_keeps_legacy_bytes(self):
        legacy={**self.row(),'recipient':'private@fixture.example','subject':'private subject','customMessage':'private content'};self.legacy.write_text(json.dumps([legacy]));before=self.legacy.read_bytes()
        history.append(self.root,{**self.row(2),'recipient':'second@fixture.example','customMessage':'do not retain'})
        saved=(self.root/'dispatch-history-v2.json').read_text();self.assertNotIn('recipient',saved);self.assertNotIn('retain',saved);self.assertEqual(before,self.legacy.read_bytes())
        view=history.load(self.root,self.legacy);self.assertEqual(2,len(view));self.assertTrue(view[1]['legacyReviewRequired']);self.assertNotIn('private',json.dumps(view))
    def test_concurrent_writers_do_not_lose_metadata(self):
        with ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(lambda n:history.append(self.root,self.row(n)),range(1,41)))
        self.assertEqual(40,len(history.load(self.root,self.legacy)))
    def test_size_bound_does_not_prune_or_rewrite_legacy_history(self):
        self.legacy.write_text(json.dumps([self.row()]));before=self.legacy.read_bytes()
        with patch.object(history,'LIMIT',3):
            for n in range(1,6):history.append(self.root,self.row(n))
            self.assertEqual(3,len(history.read(self.root/'dispatch-history-v2.json')))
        self.assertEqual(before,self.legacy.read_bytes())
    def test_malformed_new_history_is_preserved_for_manual_review(self):
        target=self.root/'dispatch-history-v2.json';target.write_text('[invalid');before=target.read_bytes()
        with self.assertRaises(history.HistoryUnavailable):history.append(self.root,self.row())
        self.assertEqual(before,target.read_bytes())
    @unittest.skipUnless(os.name=='posix','POSIX link proof')
    def test_symlink_and_hardlink_targets_are_never_modified(self):
        target=self.root/'dispatch-history-v2.json';other=self.root/'outside';other.write_text('keep')
        target.symlink_to(other)
        with self.assertRaises((history.HistoryUnavailable,OSError)):history.append(self.root,self.row())
        target.unlink();os.link(other,target)
        with self.assertRaises(history.HistoryUnavailable):history.append(self.root,self.row())
        self.assertEqual('keep',other.read_text())
    def test_legacy_string_ids_project_to_metadata_without_changing_original(self):
        self.legacy.write_text(json.dumps([{**self.row(),'documentId':'1','orderId':'2','recipient':'private'}]));before=self.legacy.read_bytes()
        self.assertEqual(2,history.load(self.root,self.legacy)[0]['orderId']);self.assertEqual(before,self.legacy.read_bytes())
    def test_ambiguous_legacy_row_requires_review_instead_of_an_empty_history(self):
        self.legacy.write_text(json.dumps([{'recipient':'private@example.test','documentId':'unknown'}]));before=self.legacy.read_bytes()
        with self.assertRaises(history.HistoryUnavailable):history.load(self.root,self.legacy)
        self.assertEqual(before,self.legacy.read_bytes())
    def test_invalid_document_scope_cannot_trigger_pdf_or_smtp_requests(self):
        with patch.object(server,'platform_request') as outbound:
            result=server.send_document_email('INVOICE','../private',1,'fixture','recipient@fixture.example')
            self.assertFalse(result['success']);outbound.assert_not_called()
    def send(self,fail_history=False):
        self.legacy.write_text(json.dumps([{**self.row(),'recipient':'legacy-private@example.test'}]));before=self.legacy.read_bytes()
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(server,'RUNTIME_ROOT',self.root));stack.enter_context(patch.object(server,'DISPATCH_HISTORY_FILE',self.legacy))
            stack.enter_context(patch.object(server,'platform_request',return_value=object()))
            stack.enter_context(patch('urllib.request.urlopen',side_effect=lambda *a,**k:io.BytesIO(b'%PDF-fixture'+b'x'*200)))
            smtp=stack.enter_context(patch('smtplib.SMTP'))
            if fail_history:stack.enter_context(patch.object(server.cc_data.cc_dispatch_history,'append',side_effect=history.HistoryUnavailable('fixture failure')))
            result=server.send_document_email('INVOICE',2,2,'PRIVATE-FV','recipient-private@example.test','Private subject','Private message','Private buyer')
            self.assertEqual(1,smtp.return_value.__enter__.return_value.send_message.call_count)
        self.assertEqual(before,self.legacy.read_bytes());return result
    def test_successful_sending_keeps_message_but_records_only_safe_metadata(self):
        result=self.send();self.assertTrue(result['success']);self.assertTrue(result['historyRecorded'])
        self.assertNotIn('Private',json.dumps(result));saved=(self.root/'dispatch-history-v2.json').read_text();self.assertNotIn('recipient',saved);self.assertNotIn('Private',saved)
    def test_history_failure_after_sending_is_not_a_send_failure_or_a_retry(self):
        result=self.send(True);self.assertTrue(result['success']);self.assertFalse(result['historyRecorded']);self.assertFalse((self.root/'dispatch-history-v2.json').exists())

import test_control_center_security as session_fixtures
class InternalSessionHttpTests(unittest.TestCase):
    setUp=session_fixtures.PanelHttpTests.setUp
    tearDown=session_fixtures.PanelHttpTests.tearDown
    request=session_fixtures.PanelHttpTests.request
    def test_logout_revokes_only_current_session_and_does_not_cancel_jobs(self):
        first=self.request()[1]['token'];second=self.request()[1]['token']
        with patch.object(server,'terminate_job') as terminate:
            self.assertEqual(200,self.request('/api/session/logout','POST',{},first)[0]);terminate.assert_not_called()
        self.assertEqual(401,self.request('/api/jobs',token=first)[0]);self.assertEqual(200,self.request('/api/jobs',token=second)[0])
    def test_access_log_does_not_copy_queries_or_request_paths(self):
        token=self.request()[1]['token'];out=io.StringIO()
        with contextlib.redirect_stdout(out),patch.object(server,'get_customers_data',return_value={'customers':[]}):
            self.request('/api/data/customers?search=private%40fixture.example&token=fixture-secret',token=token)
        self.assertNotIn('private',out.getvalue());self.assertNotIn('fixture-secret',out.getvalue());self.assertIn('category=data',out.getvalue())
