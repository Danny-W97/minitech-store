import importlib.util
from pathlib import Path
import sys
import unittest
import urllib.error
from unittest.mock import Mock

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('receipt_proxy',ROOT/'scripts/control-center/web/stock_receipts.py')
proxy=importlib.util.module_from_spec(spec);sys.modules[spec.name]=proxy;spec.loader.exec_module(proxy)
KEY='00000000-0000-4000-8000-000000000001'


class ReceiptProxyTests(unittest.TestCase):
    def committed(self,**changes):
        return dict(status='COMMITTED',operationId=KEY,productId=1,quantity=5,kind='RECEIPT',availableQuantity=15,**changes)
    def test_receipt_sends_delta_and_original_key_without_read_modify_patch(self):
        api=Mock(return_value=self.committed());result=proxy.receive(api,'1',5,KEY)
        self.assertTrue(result['ok']);api.assert_called_once_with('http://127.0.0.1:18083/inventory/items/1/receipts',method='POST',payload={'quantity':5,'operationId':KEY},timeout=10)
    def test_lost_write_reply_is_resolved_only_by_the_same_ledger_key(self):
        api=Mock(side_effect=[TimeoutError('lost'),self.committed()])
        self.assertTrue(proxy.receive(api,1,5,KEY)['ok'])
        self.assertEqual(api.call_args_list[1].args[0],'http://127.0.0.1:18083/inventory/operations/'+KEY)
        self.assertEqual(len([call for call in api.call_args_list if call.kwargs.get('method')=='POST']),1)
    def test_missing_status_is_ambiguous_and_keeps_the_key(self):
        api=Mock(side_effect=[TimeoutError(),urllib.error.HTTPError('local',404,'missing',{},None)])
        with self.assertRaises(proxy.StockOperationError) as failure:proxy.receive(api,1,5,KEY)
        self.assertEqual(failure.exception.status,503);self.assertEqual(failure.exception.operation_id,KEY)
    def test_forbidden_write_never_falls_back_to_another_read_or_write(self):
        api=Mock(side_effect=urllib.error.HTTPError('local',403,'forbidden',{},None))
        with self.assertRaises(proxy.StockOperationError) as failure:proxy.receive(api,1,5,KEY)
        self.assertEqual(failure.exception.status,403);self.assertEqual(api.call_count,1)
    def test_another_payloads_receipt_does_not_confirm_this_delivery(self):
        row=self.committed();row['productId']=2;api=Mock(side_effect=[TimeoutError(),row])
        with self.assertRaises(proxy.StockOperationError) as failure:proxy.receive(api,1,5,KEY)
        self.assertEqual(failure.exception.status,409)
    def test_invalid_numbers_and_operation_keys_never_reach_backend(self):
        for product,quantity,key in [(True,5,KEY),(1,1.5,KEY),(1,0,KEY),('1/../2',5,KEY),(1,5,'new')]:
            api=Mock()
            with self.assertRaises(ValueError):proxy.receive(api,product,quantity,key)
            api.assert_not_called()


if __name__=='__main__':unittest.main()
