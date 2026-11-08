"""D02 restore invalidation uses only owned drill resources and metadata counts."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('commerce_privacy_restore',ROOT/'scripts/backup/privacy-restore.py')
privacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(privacy)


class CommerceRetentionRestoreTests(unittest.TestCase):
    def fixture(self,present='2',counts=('3','0'),remaining='0'):
        restored=object.__new__(privacy.recovery.Restored)
        restored.postgres='fixture-postgres';restored.redis='fixture-redis'
        restored.containers=[restored.postgres,restored.redis]
        queries=[];batches=iter(counts)
        def sql(alias,query):
            self.assertEqual('order',alias);queries.append(query)
            if 'to_regclass' in query:return present
            if 'WITH batch AS' in query:return next(batches)
            return remaining
        restored.sql=Mock(side_effect=sql)
        return restored,queries

    def test_rejects_raw_or_unowned_targets_before_any_sql(self):
        with self.assertRaises(ValueError):privacy.invalidate_commerce_assessments(object())
        restored,_=self.fixture();restored.containers=[]
        with self.assertRaises(ValueError):privacy.invalidate_commerce_assessments(restored)
        restored.sql.assert_not_called()

    def test_both_absent_historical_tables_do_not_invent_evidence_or_release(self):
        restored,queries=self.fixture(present='0')
        result=privacy.invalidate_commerce_assessments(restored)
        self.assertEqual('ABSENT_HISTORICAL_SCHEMA',result['status']);self.assertEqual(0,result['invalidated'])
        self.assertFalse(result['releaseAllowed']);self.assertEqual(1,len(queries))

    def test_partial_or_unknown_schema_stays_in_quarantine(self):
        for present in ('1','3','unrecognized'):
            restored,queries=self.fixture(present=present)
            with self.subTest(present=present),self.assertRaisesRegex(ValueError,'schema'):
                privacy.invalidate_commerce_assessments(restored)
            self.assertEqual(1,len(queries))

    def test_bounded_invalidation_retains_receipts_business_rows_and_old_generations(self):
        restored,queries=self.fixture(counts=('100','17','0'))
        result=privacy.invalidate_commerce_assessments(restored)
        self.assertEqual(117,result['invalidated']);self.assertFalse(result['releaseAllowed'])
        updates=[q for q in queries if 'WITH batch AS' in q]
        self.assertEqual(3,len(updates))
        for query in updates:
            self.assertIn('LIMIT 100 FOR UPDATE',query);self.assertIn("state='STALE'",query)
            self.assertIn('run_token=NULL,run_until=NULL',query)
            self.assertIn("lock_timeout='2s'",query);self.assertIn("statement_timeout='5s'",query)
            self.assertNotIn('DELETE',query);self.assertNotIn('SKIP LOCKED',query)
            self.assertNotIn('customer_id=',query);self.assertNotIn('commerce_retention_evidence',query)

    def test_bad_count_is_not_echoed_or_accepted(self):
        for count in ('101','-1','true','0\n1','synthetic-never-echo'):
            restored,_=self.fixture(counts=(count,))
            with self.subTest(count=count),self.assertRaises(ValueError) as rejected:
                privacy.invalidate_commerce_assessments(restored)
            self.assertNotIn('synthetic-never-echo',str(rejected.exception))

    def test_batch_limit_or_remaining_active_rows_prevent_success(self):
        restored,_=self.fixture(counts=('100','100'))
        with patch.object(privacy,'MAX_BATCHES',2),self.assertRaisesRegex(ValueError,'limit'):
            privacy.invalidate_commerce_assessments(restored)
        restored,_=self.fixture(counts=('0',),remaining='1')
        with self.assertRaisesRegex(ValueError,'active'):privacy.invalidate_commerce_assessments(restored)

    def test_replay_always_invalidates_assessments_before_sessions_even_with_no_entries(self):
        restored,_=self.fixture();events=[]
        def sql(alias,query):
            events.append(query)
            if 'to_regclass' in query:return 't'
            if "phase='QUARANTINED'" in query and 'SELECT count' in query:return '1'
            return ''
        restored.sql=Mock(side_effect=sql);restored.redis_json=Mock(return_value=('0',[]))
        def invalidate(target):
            self.assertIs(restored,target);events.append('D02_INVALIDATED')
            return {'status':'INVALIDATED_IN_QUARANTINE','invalidated':3,'releaseAllowed':False}
        def quarantine_intents(target):
            self.assertIs(restored,target);events.append('D02_INTENTS_QUARANTINED')
            return {'blocked':0,'executionTerminated':False}
        def quarantine_intake(target):
            self.assertIs(restored,target);events.append('D02_INTAKE_QUARANTINED')
            return {'blocked':0,'sourceDeliveryConfirmed':False}
        with patch.object(privacy,'invalidate_commerce_assessments',side_effect=invalidate), patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',side_effect=quarantine_intents), patch.object(privacy,'invalidate_hold_intake',side_effect=quarantine_intake), patch.object(privacy,'invalidate_retention_holds',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_order_state',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_bindings',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_sources',return_value={'releaseAllowed':False}):
            result=privacy.replay(restored,[])
        self.assertEqual(0,result['requests']);self.assertEqual(3,result['commerceAssessments']['invalidated'])
        self.assertFalse(result['releaseAllowed'])
        session=next(n for n,q in enumerate(events) if 'UPDATE restore_security_state' in q)
        self.assertLess(events.index('D02_INVALIDATED'),events.index('D02_INTENTS_QUARANTINED'))
        self.assertLess(events.index('D02_INTENTS_QUARANTINED'),events.index('D02_INTAKE_QUARANTINED'))
        self.assertLess(events.index('D02_INTAKE_QUARANTINED'),session)

    def test_invalidation_failure_stops_replay_before_session_or_account_operations(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',side_effect=ValueError('keep quarantine')):
            with self.assertRaisesRegex(ValueError,'quarantine'):privacy.replay(restored,[])
        self.assertTrue(all('UPDATE' not in call.args[1] and 'DELETE' not in call.args[1] for call in restored.sql.call_args_list))

    def source_fixture(self,present='2',counts=('2','0','1','0'),remaining='0',receipts='f'):
        restored,_=self.fixture();queries=[];batches={alias:iter(counts) for alias in ('returns','inventory','promotion','delivery','payment','billing','notification')}
        def sql(alias,query):
            self.assertIn(alias,batches);queries.append(query)
            if 'to_regclass' in query:return receipts if 'hold_restoration_receipts' in query else present
            if 'WITH batch AS' in query:return next(batches[alias])
            return remaining
        restored.sql=Mock(side_effect=sql)
        return restored,queries

    def test_source_restore_refuses_unowned_and_partial_schema(self):
        with self.assertRaises(ValueError):privacy.invalidate_commerce_sources(object())
        for present in ('1','3','unknown'):
            restored,queries=self.source_fixture(present=present)
            with self.subTest(present=present),self.assertRaisesRegex(ValueError,'schema'):
                privacy.invalidate_commerce_sources(restored)
            self.assertEqual(1,len(queries))
        restored,_=self.source_fixture(present='0')
        self.assertEqual('ABSENT_HISTORICAL_SCHEMA',privacy.invalidate_commerce_sources(restored)['status'])

    def test_source_restore_blocks_bindings_and_retires_leases_without_clearing_budget(self):
        restored,queries=self.source_fixture();result=privacy.invalidate_commerce_sources(restored)
        self.assertEqual(14,result['bindingsBlocked']);self.assertEqual(7,result['leasesRetired']);self.assertFalse(result['releaseAllowed'])
        self.assertEqual({'returns','inventory','promotion','delivery','payment','billing','notification'},set(result['sources']))
        statements=[q for q in queries if 'WITH batch AS' in q]
        for query in statements:
            self.assertIn('LIMIT 100 FOR UPDATE',query);self.assertNotIn('DELETE',query)
            self.assertNotIn('SKIP LOCKED',query);self.assertNotIn('attempts=0',query)
            self.assertIn("lock_timeout='2s'",query);self.assertIn("statement_timeout='5s'",query)
        self.assertIn('restore_blocked=TRUE,invalidated=TRUE,binding_epoch=binding_epoch+1',statements[0])
        self.assertIn("WHEN attempts=8 THEN 'FAILED'",statements[-1])

    def test_source_bad_counts_limit_and_remaining_state_block_success(self):
        for count in ('101','-1','bad-value'):
            restored,_=self.source_fixture(counts=(count,))
            with self.subTest(count=count),self.assertRaises(ValueError) as caught:privacy.invalidate_commerce_sources(restored)
            self.assertNotIn('bad-value',str(caught.exception))
        restored,_=self.source_fixture(counts=('100','100'))
        with patch.object(privacy,'MAX_BATCHES',2),self.assertRaisesRegex(ValueError,'limit'):privacy.invalidate_commerce_sources(restored)
        restored,_=self.source_fixture(counts=('0','0'),remaining='1')
        with self.assertRaisesRegex(ValueError,'active'):privacy.invalidate_commerce_sources(restored)

    def test_source_failure_prevents_session_and_account_replay(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}), patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',return_value={'blocked':0}), patch.object(privacy,'invalidate_hold_intake',return_value={'blocked':0}), patch.object(privacy,'invalidate_retention_holds',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_order_state',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_bindings',return_value={}), patch.object(privacy,'invalidate_commerce_sources',side_effect=ValueError('source quarantine')):
            with self.assertRaisesRegex(ValueError,'source quarantine'):privacy.replay(restored,[])
        self.assertTrue(all('UPDATE' not in call.args[1] for call in restored.sql.call_args_list))

    def test_unreviewed_source_is_rejected_before_sql(self):
        restored,_=self.source_fixture()
        with self.assertRaisesRegex(ValueError,'Unreviewed'):privacy.invalidate_commerce_source(restored,'customer')
        restored.sql.assert_not_called()

    def test_partial_schema_in_a_later_source_stops_the_whole_pass(self):
        for broken in ('inventory','notification'):
            with self.subTest(source=broken):
                restored,_=self.source_fixture();original=restored.sql.side_effect
                def sql(alias,query):
                    if alias==broken and 'to_regclass' in query:return '1'
                    return original(alias,query)
                restored.sql.side_effect=sql
                with self.assertRaisesRegex(ValueError,'schema'):privacy.invalidate_commerce_sources(restored)
                aliases=[call.args[0] for call in restored.sql.call_args_list]
                self.assertIn('returns',aliases);self.assertEqual(broken,aliases[-1])
                if broken=='inventory':self.assertNotIn('promotion',aliases)
                else:self.assertEqual(set(privacy.COMMERCE_SOURCE_ALIASES),set(aliases))


    def test_coordinator_restore_preserves_epoch_receipts_and_attempt_budget(self):
        restored,queries=self.fixture(present='1',counts=('21','0'))
        result=privacy.invalidate_commerce_bindings(restored)
        self.assertEqual(21,result['blocked']);self.assertFalse(result['releaseAllowed'])
        for query in [q for q in queries if 'WITH batch AS' in q]:
            self.assertIn('restore_blocked=TRUE',query);self.assertIn('LIMIT 100 FOR UPDATE',query)
            self.assertIn("lock_timeout='2s'",query);self.assertIn("statement_timeout='5s'",query)
            self.assertIn("WHEN attempts=8 THEN 'FAILED'",query)
            for forbidden in ('DELETE','expected_epoch=','bound_epoch=','attempts=0','SKIP LOCKED'):
                self.assertNotIn(forbidden,query)

    def test_coordinator_restore_rejects_unowned_unknown_schema_and_bad_counts(self):
        with self.assertRaises(ValueError):privacy.invalidate_commerce_bindings(object())
        restored,_=self.fixture(present='0');self.assertEqual('ABSENT_HISTORICAL_SCHEMA',privacy.invalidate_commerce_bindings(restored)['status'])
        restored,_=self.fixture(present='2')
        with self.assertRaisesRegex(ValueError,'schema'):privacy.invalidate_commerce_bindings(restored)
        for count in ('101','-1','synthetic-never-echo'):
            restored,_=self.fixture(present='1',counts=(count,))
            with self.subTest(count=count),self.assertRaises(ValueError) as caught:privacy.invalidate_commerce_bindings(restored)
            self.assertNotIn('synthetic-never-echo',str(caught.exception))

    def test_coordinator_restore_limits_and_active_rows_keep_quarantine(self):
        restored,_=self.fixture(present='1',counts=('100','100'))
        with patch.object(privacy,'MAX_BATCHES',2),self.assertRaisesRegex(ValueError,'limit'):privacy.invalidate_commerce_bindings(restored)
        restored,_=self.fixture(present='1',counts=('0',),remaining='1')
        with self.assertRaisesRegex(ValueError,'active'):privacy.invalidate_commerce_bindings(restored)

    def test_coordinator_restore_failure_stops_sources_sessions_and_accounts(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}), patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',return_value={'blocked':0}), patch.object(privacy,'invalidate_hold_intake',return_value={'blocked':0}), patch.object(privacy,'invalidate_retention_holds',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_order_state',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_bindings',side_effect=ValueError('binding quarantine')), patch.object(privacy,'invalidate_commerce_sources') as sources:
            with self.assertRaisesRegex(ValueError,'binding quarantine'):privacy.replay(restored,[])
            sources.assert_not_called()
        self.assertTrue(all('UPDATE' not in call.args[1] and 'DELETE' not in call.args[1] for call in restored.sql.call_args_list))

    def test_order_milestones_are_quarantined_without_rewriting_their_clock(self):
        restored,queries=self.fixture(present='1',counts=('3','0'))
        result=privacy.invalidate_commerce_order_state(restored);self.assertEqual(3,result['blocked']);self.assertFalse(result['releaseAllowed'])
        for query in [q for q in queries if 'WITH batch AS' in q]:
            self.assertIn('restore_blocked=TRUE',query);self.assertIn('LIMIT 100 FOR UPDATE',query)
            for forbidden in ('reconciled_at=','open_seen=','process_revision=','DELETE','SKIP LOCKED'):self.assertNotIn(forbidden,query)

    def test_order_milestone_schema_counts_and_limits_fail_closed(self):
        with self.assertRaises(ValueError):privacy.invalidate_commerce_order_state(object())
        restored,_=self.fixture(present='0');self.assertEqual('ABSENT_HISTORICAL_SCHEMA',privacy.invalidate_commerce_order_state(restored)['status'])
        restored,_=self.fixture(present='2')
        with self.assertRaisesRegex(ValueError,'schema'):privacy.invalidate_commerce_order_state(restored)
        restored,_=self.fixture(present='1',counts=('101',))
        with self.assertRaises(ValueError):privacy.invalidate_commerce_order_state(restored)
        restored,_=self.fixture(present='1',counts=('100','100'))
        with patch.object(privacy,'MAX_BATCHES',2),self.assertRaisesRegex(ValueError,'limit'):privacy.invalidate_commerce_order_state(restored)
        restored,_=self.fixture(present='1',counts=('0',),remaining='1')
        with self.assertRaisesRegex(ValueError,'active'):privacy.invalidate_commerce_order_state(restored)

    def test_order_milestone_failure_prevents_binding_source_and_account_replay(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}), patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',return_value={'blocked':0}), patch.object(privacy,'invalidate_hold_intake',return_value={'blocked':0}), patch.object(privacy,'invalidate_retention_holds',return_value={'blocked':0}), patch.object(privacy,'invalidate_commerce_order_state',side_effect=ValueError('milestone quarantine')), patch.object(privacy,'invalidate_commerce_bindings') as bindings:
            with self.assertRaisesRegex(ValueError,'milestone quarantine'):privacy.replay(restored,[])
            bindings.assert_not_called()
        self.assertTrue(all('UPDATE' not in call.args[1] for call in restored.sql.call_args_list))


    def test_hold_quarantine_preserves_history_and_requires_owned_complete_schema(self):
        with self.assertRaises(ValueError):privacy.invalidate_retention_holds(object())
        restored,queries=self.fixture();result=privacy.invalidate_retention_holds(restored)
        self.assertEqual(3,result['blocked']);self.assertFalse(result['releaseAllowed'])
        batch=next(q for q in queries if 'WITH batch AS' in q)
        self.assertIn('LIMIT 100',batch);self.assertIn("lock_timeout='2s'",batch)
        self.assertNotIn('DELETE',batch);self.assertNotIn('revision=',batch)
        restored,_=self.fixture(present='0');self.assertEqual('ABSENT_HISTORICAL_SCHEMA',privacy.invalidate_retention_holds(restored)['status'])
        restored,_=self.fixture(present='1')
        with self.assertRaisesRegex(ValueError,'schema'):privacy.invalidate_retention_holds(restored)

    def test_hold_quarantine_bad_counts_limits_and_remaining_scope_stop_replay(self):
        for counts,remaining in [(('101',),'0'),(('-1',),'0'),(('0',),'1')]:
            restored,_=self.fixture(counts=counts,remaining=remaining)
            with self.assertRaises(ValueError):privacy.invalidate_retention_holds(restored)
        restored,_=self.fixture(counts=('100','100'))
        with patch.object(privacy,'MAX_BATCHES',2),self.assertRaisesRegex(ValueError,'limit'):privacy.invalidate_retention_holds(restored)

    def test_hold_quarantine_failure_stops_before_later_scopes(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}),patch.object(privacy,'invalidate_observation_rounds',return_value={}), patch.object(privacy,'invalidate_retention_intents',return_value={'blocked':0}), patch.object(privacy,'invalidate_hold_intake',return_value={'blocked':0}), patch.object(privacy,'invalidate_retention_holds',side_effect=ValueError('hold quarantine')),patch.object(privacy,'invalidate_commerce_order_state') as later:
            with self.assertRaisesRegex(ValueError,'hold quarantine'):privacy.replay(restored,[])
        later.assert_not_called()

    def test_restoration_receipts_are_preserved_only_under_source_quarantine(self):
        restored,queries=self.source_fixture(receipts='t');result=privacy.invalidate_commerce_sources(restored)
        self.assertTrue(all(v['restorationReceiptsVerified'] for v in result['sources'].values()));self.assertFalse(result['releaseAllowed'])
        statement=next(q for q in queries if 'LEFT JOIN commerce_retention_source_holds' in q)
        for field in ('s.restore_blocked','h.request_id=r.source_request_id','h.hold_id=r.hold_id'):self.assertIn(field,statement)
        for action in ('INSERT','UPDATE','DELETE'):self.assertNotIn(action,statement)
    def test_restoration_receipt_bad_schema_and_missing_hold_block_restore(self):
        restored,_=self.source_fixture(receipts='unknown')
        with self.assertRaisesRegex(ValueError,'receipt schema'):privacy.invalidate_commerce_sources(restored)
        restored,_=self.source_fixture(receipts='t',remaining='1')
        with self.assertRaises(ValueError):privacy.invalidate_commerce_sources(restored)
        with self.assertRaises(ValueError):privacy.verify_hold_restoration_receipts(object(),'returns')
    def test_historical_source_without_restoration_receipt_does_not_claim_authority(self):
        restored,_=self.source_fixture(receipts='f');result=privacy.invalidate_commerce_sources(restored)
        self.assertTrue(all(v['restorationReceiptsVerified'] is False for v in result['sources'].values()));self.assertFalse(result['releaseAllowed'])

if __name__=='__main__':unittest.main()
