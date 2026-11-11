"""Observation history survives restore without resending any source request."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock,patch
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('observation_restore',ROOT/'scripts/backup/privacy-restore.py')
privacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(privacy)

class ObservationRestoreTests(unittest.TestCase):
    def fixture(self,present='2',batches=('3','0'),remaining='0'):
        restored=object.__new__(privacy.recovery.Restored)
        restored.postgres='fixture-postgres';restored.redis='fixture-redis';restored.containers=[restored.postgres,restored.redis]
        queries=[];values=iter(batches)
        def sql(alias,query):
            self.assertEqual('order',alias);queries.append(query)
            if 'to_regclass' in query:return present
            if 'WITH batch AS' in query:return next(values)
            return remaining
        restored.sql=Mock(side_effect=sql)
        return restored,queries
    def test_unowned_and_partial_schema_are_rejected(self):
        with self.assertRaises(ValueError):privacy.invalidate_observation_rounds(object())
        for value in ('1','3','invalid'):
            restored,_=self.fixture(present=value)
            with self.subTest(value=value),self.assertRaises(ValueError):privacy.invalidate_observation_rounds(restored)
    def test_historical_absence_sends_no_requests(self):
        restored,queries=self.fixture(present='0');result=privacy.invalidate_observation_rounds(restored)
        self.assertEqual('ABSENT_HISTORICAL_SCHEMA',result['status']);self.assertEqual(0,result['sourceRequestsSent']);self.assertFalse(result['releaseAllowed']);self.assertEqual(1,len(queries))
    def test_quarantine_is_bounded_preserves_history_and_is_idempotent(self):
        restored,queries=self.fixture();result=privacy.invalidate_observation_rounds(restored)
        self.assertEqual(3,result['blocked']);self.assertEqual(0,result['sourceRequestsSent'])
        sql=next(q for q in queries if 'WITH batch AS' in q)
        for value in ('LIMIT 100','FOR UPDATE',"lock_timeout='2s'","statement_timeout='5s'",'restore_blocked=TRUE'):self.assertIn(value,sql)
        self.assertNotIn('DELETE',sql);self.assertNotIn('release_confirmed_at',sql);self.assertNotIn('finished_at',sql)
        restored,_=self.fixture(batches=('0',));self.assertEqual(0,privacy.invalidate_observation_rounds(restored)['blocked'])
    def test_invalid_counts_remaining_and_exhausted_budget_block_restore(self):
        for value in ('101','-1','invalid'):
            restored,_=self.fixture(batches=(value,))
            with self.subTest(value=value),self.assertRaises(ValueError):privacy.invalidate_observation_rounds(restored)
        restored,_=self.fixture(remaining='1')
        with self.assertRaises(ValueError):privacy.invalidate_observation_rounds(restored)
        restored,_=self.fixture(batches=('1',))
        with patch.object(privacy,'MAX_BATCHES',1),self.assertRaises(ValueError):privacy.invalidate_observation_rounds(restored)
    def test_failure_stops_before_intents_sessions_and_other_scopes(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}),patch.object(privacy,'invalidate_observation_rounds',side_effect=ValueError('round quarantine')),patch.object(privacy,'invalidate_retention_intents') as later:
            with self.assertRaisesRegex(ValueError,'round quarantine'):privacy.replay(restored,[])
        later.assert_not_called();self.assertTrue(all('UPDATE' not in c.args[1] and 'DELETE' not in c.args[1] for c in restored.sql.call_args_list))
