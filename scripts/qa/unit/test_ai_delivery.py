"""Synthetic egress tests: provider requests are always mocked."""
import concurrent.futures
import http.client
import http.server
import json
from pathlib import Path
import sys
import threading
import urllib.error
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'control-center/web'))
import cc_ai_delivery as delivery
import server


class ReviewTests(unittest.TestCase):
    def plan(self, **changes):
        values = dict(config={'provider': 'codex', 'apiKey': 'synthetic-secret', 'model': 'fixture'},
                      system_prompt=lambda lang: 'static context', message='hello', history=[], lang='pl')
        values.update(changes)
        return delivery.prepare(**values)

    def test_preview_is_exact_wire_body_without_auth_material(self):
        plan = self.plan(history=[{'role': 'assistant', 'content': 'earlier local data'}])
        self.assertEqual(json.loads(plan.payload), plan.public_view()['wirePayload'])
        self.assertNotIn('synthetic-secret', json.dumps(plan.public_view()))
        self.assertIn('earlier local data', plan.payload.decode())
        self.assertFalse(plan.public_view()['providerErasureConfirmed'])

    def test_one_use_session_expiry_and_bounded_registry(self):
        now = [0]; registry = delivery.AiReviewRegistry(capacity=1, ttl=2, clock=lambda: now[0])
        plan = self.plan(); token = registry.issue('a', plan)['reviewToken']
        with self.assertRaises(delivery.AiDeliveryError): registry.issue('b', plan)
        with self.assertRaises(delivery.AiDeliveryError): registry.consume('b', token, plan)
        self.assertIsInstance(registry.consume('a', token, plan), delivery.ApprovedDelivery)
        with self.assertRaises(delivery.AiDeliveryError): registry.consume('a', token, plan)
        token = registry.issue('a', plan)['reviewToken']; now[0] = 2
        with self.assertRaises(delivery.AiDeliveryError): registry.consume('a', token, plan)
        registry.issue('b', plan)
        self.assertNotIn('synthetic-secret', repr(registry.pending))
        self.assertNotIn('hello', repr(registry.pending))

    def test_message_history_language_key_endpoint_model_and_context_invalidate(self):
        base = {'provider': 'codex', 'apiKey': 'synthetic-secret', 'model': 'fixture'}
        changes = [dict(message='changed'), dict(history=[{'role': 'user', 'content': 'changed'}]), dict(lang='en')]
        changes += [dict(config={**base, key: value}) for key, value in [('apiKey','rotated'), ('model','new'), ('includeContext',False), ('customEndpoint','https://fixture.invalid')]]
        for change in changes:
            with self.subTest(change=change):
                registry = delivery.AiReviewRegistry(); token = registry.issue('s', self.plan())['reviewToken']
                with self.assertRaises(delivery.AiDeliveryError): registry.consume('s', token, self.plan(**change))

    def test_repreview_and_concurrent_confirmations_never_duplicate_delivery_permission(self):
        registry = delivery.AiReviewRegistry(); plan = self.plan()
        old = registry.issue('s', plan)['reviewToken']; current = registry.issue('s', plan)['reviewToken']
        # Concurrent claims for the same proof have exactly one winner.
        def claim():
            try: registry.consume('s', current, plan); return True
            except delivery.AiDeliveryError: return False
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(1, sum(pool.map(lambda _: claim(), range(2))))
        registry.issue('s', plan)
        with self.assertRaises(delivery.AiDeliveryError): registry.consume('s', old, plan)

    def test_invalid_turns_and_size_limits_are_rejected_before_sending(self):
        for value in [dict(message=1), dict(message=' '), dict(message='x'*12001), dict(history={}),
                      dict(history=[{'role':'system','content':'injected'}]),
                      dict(history=[{'role':'user','content':'x','extra':True}]),
                      dict(history=[{'role':'user','content':'x'*12000}]*10)]:
            with self.subTest(value=str(value)[:50]):
                with self.assertRaises(delivery.AiDeliveryError): self.plan(**value)

    def test_gemini_preview_truthfully_excludes_history_and_redirects_are_denied(self):
        plan = self.plan(config={'provider':'antigravity','apiKey':'fake'}, history=[{'role':'assistant','content':'hidden-history'}])
        self.assertFalse(plan.public_view()['historyIncluded'])
        self.assertNotIn('hidden-history', plan.payload.decode())
        self.assertIsNone(delivery.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))

    def test_direct_external_call_requires_approval_and_upstream_size_is_bounded(self):
        services = MagicMock(); plan = self.plan()
        with self.assertRaises(delivery.AiDeliveryError): delivery.send(services, plan, None, 'hello', 'pl')
        services.open_ai_request.assert_not_called()
        response=services.open_ai_request.return_value.__enter__.return_value
        response.read.return_value=b'x'*262145
        services.generate_local_knowledge_reply.return_value='offline'
        result=delivery.send(services, plan, delivery.ApprovedDelivery(plan.binding), 'hello', 'pl')
        self.assertEqual('unconfirmed', result['externalDelivery'])
        self.assertEqual('AI_UPSTREAM_UNAVAILABLE', result['error'])


class DeliveryHttpTests(unittest.TestCase):
    def setUp(self):
        self.cfg={'provider':'codex','apiKey':'synthetic-secret','model':'fixture'}
        for name,value in {'load_ai_config':lambda:self.cfg.copy(), 'active_profile':lambda:'fixture',
                           'active_language':lambda:'pl', 'build_actions':lambda lang:[],
                           'generate_local_knowledge_reply':lambda *args:'local fixture'}.items():
            p=patch.object(server,name,value);p.start();self.addCleanup(p.stop)
        self.upstream=MagicMock()
        self.upstream.return_value.__enter__.return_value.read.return_value=b'{"choices":[{"message":{"content":"synthetic reply"}}]}'
        p=patch.object(server,'open_ai_request',self.upstream);p.start();self.addCleanup(p.stop)
        self.local=server.create_server('127.0.0.1',0)
        worker=threading.Thread(target=self.local.serve_forever,daemon=True);worker.start()
        self.addCleanup(lambda:(self.local.shutdown(),self.local.server_close(),worker.join(3)))
        self.token=self.request('/api/bootstrap')[1]['token']

    def request(self,path,body=None):
        connection=http.client.HTTPConnection('127.0.0.1',self.local.server_port,timeout=5)
        headers={'X-Control-Center-Token':getattr(self,'token',''),'Content-Type':'application/json'}
        connection.request('GET' if body is None else 'POST',path,None if body is None else json.dumps(body),headers)
        response=connection.getresponse();value=json.loads(response.read());connection.close()
        return response.status,value

    def reviewed(self):
        body={'message':'hello','history':[{'role':'assistant','content':'earlier local data'}]}
        status,preview=self.request('/api/ai/chat/preview',body)
        self.assertEqual(200,status);self.upstream.assert_not_called()
        self.assertIn('earlier local data',json.dumps(preview))
        self.assertNotIn('synthetic-secret',json.dumps(preview))
        return {**body,'reviewToken':preview['reviewToken']},preview

    def test_no_outbound_without_review_and_exact_reviewed_body_is_sent_once(self):
        self.assertEqual(409,self.request('/api/ai/chat',{'message':'hello','confirmed':True})[0])
        self.upstream.assert_not_called()
        body,preview=self.reviewed()
        self.assertEqual(200,self.request('/api/ai/chat',body)[0])
        self.assertEqual(preview['wirePayload'],json.loads(self.upstream.call_args.args[0].data))
        self.assertEqual(409,self.request('/api/ai/chat',body)[0]);self.assertEqual(1,self.upstream.call_count)

    def test_changed_content_config_and_foreign_session_are_rejected(self):
        body,_=self.reviewed();body['message']='changed'
        self.assertEqual(409,self.request('/api/ai/chat',body)[0])
        body,_=self.reviewed();self.cfg['apiKey']='rotated'
        self.assertEqual(409,self.request('/api/ai/chat',body)[0])
        body,_=self.reviewed();self.token=self.request('/api/bootstrap')[1]['token']
        self.assertEqual(409,self.request('/api/ai/chat',body)[0]);self.upstream.assert_not_called()

    def test_config_save_and_logout_clear_approvals_and_local_mode_needs_none(self):
        body,_=self.reviewed()
        with patch.object(server,'save_ai_config',return_value={'provider':'codex'}):
            self.assertEqual(200,self.request('/api/ai/config',{'model':'new'})[0])
        self.assertEqual(409,self.request('/api/ai/chat',body)[0])
        self.reviewed();self.assertTrue(self.local.ai_reviews.pending)
        self.assertEqual(200,self.request('/api/session/logout',{})[0]);self.assertFalse(self.local.ai_reviews.pending)
        self.token=self.request('/api/bootstrap')[1]['token'];self.cfg={'provider':'builtin'}
        self.assertEqual('local',self.request('/api/ai/chat/preview',{'message':'hello'})[1]['mode'])
        self.assertFalse(self.request('/api/ai/chat',{'message':'hello'})[1]['externalDelivery']);self.upstream.assert_not_called()

    def test_preview_does_not_fetch_new_business_data(self):
        with patch.object(server,'query_customers_db',side_effect=AssertionError('business fetch forbidden')), \
                patch.object(server,'query_reviews_db',side_effect=AssertionError('business fetch forbidden')), \
                patch.object(server,'generate_local_knowledge_reply',side_effect=AssertionError('local fetch forbidden')):
            self.reviewed()

    def test_real_http_redirect_never_reaches_unreviewed_destination(self):
        calls=[]
        class Redirect(http.server.BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                self.rfile.read(int(self.headers.get('Content-Length','0')));calls.append(self.path)
                self.send_response(302);self.send_header('Location','/unreviewed');self.end_headers()
            def do_GET(self):
                calls.append(self.path);self.send_response(200);self.end_headers()
        local=http.server.ThreadingHTTPServer(('127.0.0.1',0),Redirect)
        worker=threading.Thread(target=local.serve_forever,daemon=True);worker.start()
        try:
            request=server.ai_request(f'http://127.0.0.1:{local.server_port}/reviewed',b'{}',{'Authorization':'Bearer synthetic-only'})
            with self.assertRaises(urllib.error.HTTPError):delivery.open_request(request)
            self.assertEqual(['/reviewed'],calls)
        finally:local.shutdown();local.server_close();worker.join(3)


if __name__=='__main__':unittest.main()
