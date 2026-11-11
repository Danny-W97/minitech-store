import importlib.util
from pathlib import Path
import io
import json
import unittest
import urllib.error
from unittest.mock import Mock

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('correction_proxy',ROOT/'scripts/control-center/web/invoice_corrections.py')
proxy=importlib.util.module_from_spec(spec);spec.loader.exec_module(proxy)
KEY='00000000-0000-4000-8000-000000000001'

class CorrectionProxyTests(unittest.TestCase):
    def result(self):
        return dict(invoiceId=1,idempotencyKey=KEY,balanceVersion=3,balanceModel='CUMULATIVE_DELTA',correctionGross=-10)
    def issue(self,api,**changes):
        args=dict(invoice_id=1,reason='Rabat',delta='-10.00',key=KEY,version=2);args.update(changes)
        return proxy.issue(api,['http://gateway','http://billing'],**args)
    def test_timeout_retries_exact_command_with_the_same_key_and_decimal_amount(self):
        api=Mock(side_effect=[TimeoutError(),self.result()]);self.assertTrue(self.issue(api)['success'])
        self.assertEqual(api.call_args_list[0].kwargs['payload'],api.call_args_list[1].kwargs['payload'])
        self.assertEqual(api.call_args.kwargs['payload']['correctionGrossAmount'],'-10.00')
    def test_authorization_denial_never_falls_back(self):
        api=Mock(side_effect=urllib.error.HTTPError('local',403,'denied',{},io.BytesIO(b'{}')))
        with self.assertRaises(proxy.CorrectionError) as error:self.issue(api)
        self.assertEqual(error.exception.status,403);self.assertFalse(error.exception.definitive);self.assertEqual(api.call_count,1)
    def test_stale_version_is_definitive_and_does_not_reach_another_replica(self):
        api=Mock(side_effect=urllib.error.HTTPError('local',409,'stale',{},io.BytesIO(json.dumps(dict(code='CORRECTION_BALANCE_CHANGED')).encode())))
        with self.assertRaises(proxy.CorrectionError) as error:self.issue(api)
        self.assertTrue(error.exception.definitive);self.assertEqual(api.call_count,1)
    def test_missing_or_mismatched_result_keeps_the_command_for_reconciliation(self):
        row=self.result();row['idempotencyKey']='another-key';api=Mock(return_value=row)
        with self.assertRaises(proxy.CorrectionError) as error:self.issue(api)
        self.assertFalse(error.exception.definitive);self.assertEqual(error.exception.status,503)
    def test_invalid_input_never_reaches_billing(self):
        for changes in [dict(key=None),dict(version=None),dict(delta='0.001'),dict(delta='NaN'),dict(delta='0'),dict(reason='x'*256),dict(invoice_id='1/../2')]:
            api=Mock()
            with self.assertRaises(ValueError):self.issue(api,**changes)
            api.assert_not_called()

if __name__=='__main__':unittest.main()
