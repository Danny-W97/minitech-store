import http.client
import json
import threading
import unittest
import urllib.error
from unittest.mock import patch
from test_platform_credentials import server


ROW = {'id': 7, 'email': 'jan@audit.example', 'firstName': 'Jan', 'lastName': 'Kowalski',
       'city': 'Warszawa', 'createdAt': '2026-10-26T07:00:00Z', 'passwordHash': 'must-not-leak'}
PARAMS = {'cursor': '0', 'size': '50'}


class CustomerListTests(unittest.TestCase):
    def test_operator_page_uses_the_api_and_only_the_display_fields(self):
        page = {'items': [ROW], 'hasMore': True, 'nextCursor': '7', 'size': 50}
        with patch.object(server, 'api_page', return_value=page) as api, patch.object(server, 'psql_query_json') as sql:
            result = server.get_customers_data(PARAMS)
        api.assert_called_once_with(server.GATEWAY_API + '/customers', PARAMS)
        sql.assert_not_called()
        self.assertEqual({'id', 'email', 'first_name', 'last_name', 'city', 'created_at'}, set(result['customers'][0]))
        self.assertEqual('Jan', result['customers'][0]['first_name'])
        self.assertEqual('7', result['nextCursor'])
        self.assertIn('items', page)

    def test_valid_empty_page_is_distinct_from_bad_records(self):
        with patch.object(server, 'api_page', return_value={'items': [], 'hasMore': False, 'nextCursor': '0', 'size': 50}):
            self.assertEqual([], server.get_customers_data(PARAMS)['customers'])
        for row in [None, {'id': True}, dict(ROW, createdAt=None), dict(ROW, email='')]:
            with self.subTest(row=row), patch.object(server, 'api_page', return_value={'items': [row]}):
                with self.assertRaises(server.CustomerListError) as error:
                    server.get_customers_data(PARAMS)
                self.assertEqual(502, error.exception.status)


class CustomerListHttpTests(unittest.TestCase):
    def setUp(self):
        self.local = server.create_server('127.0.0.1', 0)
        self.thread = threading.Thread(target=self.local.serve_forever, daemon=True)
        self.thread.start()
        self.authority = f'127.0.0.1:{self.local.server_port}'
        self.token = self.local.panel_sessions.issue(self.authority)['token']
        self.operator = patch.object(server, 'api_json', return_value={'role': 'OPERATOR'})
        self.operator.start()

    def tearDown(self):
        self.local.shutdown(); self.local.server_close(); self.thread.join(timeout=3)
        self.operator.stop()

    def get(self, query='cursor=0&size=50', authenticated=True):
        connection = http.client.HTTPConnection('127.0.0.1', self.local.server_port, timeout=5)
        headers = {'X-Control-Center-Token': self.token} if authenticated else {}
        connection.request('GET', '/api/data/customers?' + query, headers=headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_api_errors_keep_their_meaning_and_do_not_leak_upstream_details(self):
        for upstream, expected in [(401, 401), (403, 403), (500, 502)]:
            failure = urllib.error.HTTPError('http://fixture', upstream, 'private-upstream-detail', {}, None)
            with self.subTest(status=upstream), patch.object(server, 'api_page', side_effect=failure), patch.object(server, 'psql_query_json') as sql:
                status, payload = self.get()
                self.assertEqual(expected, status)
                self.assertIn('error', payload)
                self.assertNotIn('customers', payload)
                self.assertNotIn('private-upstream-detail', str(payload))
                sql.assert_not_called()
        for failure, expected in [(urllib.error.URLError('private-host'), 503), (ValueError('bad schema'), 502)]:
            with patch.object(server, 'api_page', side_effect=failure):
                status, payload = self.get()
                self.assertEqual(expected, status)
                self.assertNotIn('customers', payload)

    def test_panel_session_and_window_are_checked_before_customer_api(self):
        with patch.object(server, 'api_page') as api:
            self.assertIn(self.get(authenticated=False)[0], (401, 403))
            self.assertEqual(400, self.get('size=201')[0])
            api.assert_not_called()
