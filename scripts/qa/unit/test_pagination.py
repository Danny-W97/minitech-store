import json
import unittest
from unittest.mock import MagicMock, patch
from test_platform_credentials import server


class PaginationTests(unittest.TestCase):
    def test_export_dates_require_timezone_and_an_ordered_range(self):
        result=server.export_params({'from':['2026-01-01T00:00:00Z'],'to':['2026-01-02T01:00:00+01:00']})
        self.assertEqual('2026-01-01T00:00:00+00:00',result['from'])
        for query in [{'from':['2026-01-01']},{'from':['invalid']},{'from':['x'*100]},{'from':['2026-02-01T00:00Z'],'to':['2026-01-01T00:00Z']}]:
            with self.subTest(query=query), self.assertRaises(ValueError): server.export_params(query)
    def test_validation_precedes_sql_and_http_and_preserves_cursor_precision(self):
        for query in [{'size':['0']}, {'size':['201']}, {'cursor':['-1']}, {'cursor':['9223372036854775808']}, {'cursor':["1 OR true"]}, {'search':['x'*201]}]:
            with self.subTest(query=query), self.assertRaises(ValueError): server.list_params(query)
        result = server.list_params({'cursor':['9223372036854775806'], 'size':['200'], 'status':['ALL']})
        self.assertEqual({'cursor':'9223372036854775806', 'size':'200'}, result)

    def test_api_page_relays_cursor_and_never_downloads_next_page(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps([{'id':3},{'id':2}]).encode()
        response.headers = {'X-Has-More':'true','X-Next-Cursor':'9223372036854775806'}
        with patch.object(server, 'platform_request', return_value='authorized') as request, patch.object(server.urllib.request, 'urlopen', return_value=response) as fetch:
            result = server.api_page('http://test/orders', {'cursor':'0','size':'2'})
        self.assertTrue(result['hasMore'])
        self.assertEqual('9223372036854775806',result['nextCursor'])
        self.assertEqual(1, fetch.call_count)
        self.assertIn('cursor=0&size=2',request.call_args.args[0])

    def test_inventory_queries_only_current_window_and_its_product_ids(self):
        with patch.object(server,'psql_query_json',side_effect=[[{'id':9,'product_id':90},{'id':8,'product_id':80},{'id':7,'product_id':70}],[{'id':90,'name':'First'}]]) as query:
            result = server.get_inventory_data({'cursor':'10','size':'2'})
        self.assertEqual(2,len(result['inventory']))
        self.assertTrue(result['hasMore'])
        self.assertEqual('8',result['nextCursor'])
        self.assertIn('id <= 9',query.call_args_list[0].args[1])
        self.assertIn('LIMIT 3',query.call_args_list[0].args[1])
        self.assertIn('IN (90,80)',query.call_args_list[1].args[1])
        with self.assertRaises(ValueError): server.get_inventory_data({'cursor':'0','size':'2','search':'not an ID'})
