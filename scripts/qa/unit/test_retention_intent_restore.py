"""Intent quarantine is bounded metadata replay, never execution termination."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('intent_restore',ROOT/'scripts/backup/privacy-restore.py')
privacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(privacy)

class RetentionIntentRestoreTests(unittest.TestCase):
    def fixture(self,present='2',batches=('2','0'),remaining='0'):
        restored=object.__new__(privacy.recovery.Restored)
        restored.postgres='fixture-postgres';restored.redis='fixture-redis';restored.containers=[restored.postgres,restored.redis]
        values=iter(batches);queries=[]
        def sql(alias,query):
            self.assertEqual('order',alias);queries.append(query)
            if 'to_regclass' in query:return present
            if 'WITH batch AS' in query:return next(values)
            return remaining
        restored.sql=Mock(side_effect=sql)
        return restored,queries
    def test_unowned_target_and_partial_schema_are_rejected(self):
        with self.assertRaises(ValueError):privacy.invalidate_retention_intents(object())
        for present in ('1','3','invalid'):
            restored,queries=self.fixture(present=present)
            with self.subTest(present=present),self.assertRaises(ValueError):privacy.invalidate_retention_intents(restored)
            self.assertEqual(1,len(queries))
    def test_absent_historical_schema_does_not_invent_operations(self):
        restored,queries=self.fixture(present='0');result=privacy.invalidate_retention_intents(restored)
        self.assertEqual('ABSENT_HISTORICAL_SCHEMA',result['status']);self.assertEqual(0,result['blocked'])
        self.assertFalse(result['releaseAllowed']);self.assertFalse(result['executionTerminated']);self.assertEqual(1,len(queries))
    def test_bounded_replay_preserves_history_and_does_not_resume_or_acknowledge_executor(self):
        restored,queries=self.fixture();result=privacy.invalidate_retention_intents(restored)
        self.assertEqual(2,result['blocked']);self.assertFalse(result['executionTerminated']);self.assertFalse(result['releaseAllowed'])
        statement=next(q for q in queries if 'WITH batch AS' in q)
        for text in ('LIMIT 100','FOR UPDATE',"lock_timeout='2s'","statement_timeout='5s'","state='QUARANTINED'",'revision=revision+1','gen_random_uuid()'):self.assertIn(text,statement)
        self.assertNotIn('DELETE',statement);self.assertNotIn('COMPLETED',statement)
        restored,_=self.fixture(batches=('0',));self.assertEqual(0,privacy.invalidate_retention_intents(restored)['blocked'])
    def test_invalid_counts_remaining_work_and_exhausted_budget_block_restore(self):
        for count in ('101','-1','invalid'):
            restored,_=self.fixture(batches=(count,))
            with self.subTest(count=count),self.assertRaises(ValueError):privacy.invalidate_retention_intents(restored)
        restored,_=self.fixture(remaining='1')
        with self.assertRaises(ValueError):privacy.invalidate_retention_intents(restored)
        from unittest.mock import patch
        restored,_=self.fixture(batches=('1',))
        with patch.object(privacy,'MAX_BATCHES',1),self.assertRaises(ValueError):privacy.invalidate_retention_intents(restored)
    def test_intent_quarantine_failure_stops_before_sessions_and_other_scopes(self):
        from unittest.mock import patch
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}),patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',side_effect=ValueError('intent quarantine')),patch.object(privacy,'invalidate_retention_holds') as later:
            with self.assertRaisesRegex(ValueError,'intent quarantine'):privacy.replay(restored,[])
        later.assert_not_called()
        self.assertTrue(all('UPDATE' not in call.args[1] and 'DELETE' not in call.args[1] for call in restored.sql.call_args_list))
