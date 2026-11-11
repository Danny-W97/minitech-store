"""Local intake quarantine preserves identity/plans and never confirms remote delivery."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock,patch
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('hold_intake_restore',ROOT/'scripts/backup/privacy-restore.py')
privacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(privacy)

class HoldIntakeRestoreTests(unittest.TestCase):
    def fixture(self,present='2',batches=('2','0'),remaining='0',delivery='2',history='0',reconciliation=None,probes='0',planning=None,plans='0',dispatch='0',dispatch_history='0',versions='0',version_history='0',steps='0',step_history='0'):
        if reconciliation is None:reconciliation='2' if delivery=='2' else '0'
        if planning is None:planning='2' if reconciliation=='2' else '0'
        restored=object.__new__(privacy.recovery.Restored);restored.postgres='fixture-pg';restored.redis='fixture-redis';restored.containers=[restored.postgres,restored.redis]
        values=iter(batches);queries=[]
        def sql(alias,query):
            self.assertEqual('order',alias);queries.append(query)
            if 'hold_review_steps' in query and 'to_regclass' in query:return steps
            if 'WITH invalid_review_steps AS' in query:return step_history
            if 'hold_review_versions' in query and 'to_regclass' in query:return versions
            if 'WITH invalid_review_versions AS' in query:return version_history
            if 'hold_restoration_attempts' in query and 'to_regclass' in query:return dispatch
            if 'WITH invalid_dispatch_attempts AS' in query:return dispatch_history
            if 'to_regclass' in query:return planning if 'hold_probe_resolutions' in query else reconciliation if 'hold_probes' in query else delivery if 'hold_delivery' in query else present
            if 'WITH batch AS' in query:return next(values)
            if 'WITH invalid_receipts AS' in query:return history
            if 'WITH invalid_probes AS' in query:return probes
            if 'WITH invalid_resolutions AS' in query:return plans
            return remaining
        restored.sql=Mock(side_effect=sql);return restored,queries
    def test_unowned_and_partial_schema_are_rejected(self):
        with self.assertRaises(ValueError):privacy.invalidate_hold_intake(object())
        for present in ('1','3','invalid'):
            restored,queries=self.fixture(present=present)
            with self.subTest(present=present),self.assertRaises(ValueError):privacy.invalidate_hold_intake(restored)
            self.assertEqual(1,len(queries))
    def test_absent_historical_schema_does_not_invent_intake(self):
        restored,queries=self.fixture(present='0');result=privacy.invalidate_hold_intake(restored)
        self.assertEqual('ABSENT_HISTORICAL_SCHEMA',result['status']);self.assertEqual(0,result['blocked']);self.assertFalse(result['sourceDeliveryConfirmed']);self.assertFalse(result['releaseAllowed']);self.assertEqual(1,len(queries))
    def test_bounded_quarantine_preserves_hold_and_seven_source_identities(self):
        restored,queries=self.fixture();result=privacy.invalidate_hold_intake(restored)
        self.assertEqual(2,result['blocked']);self.assertFalse(result['sourceDeliveryConfirmed']);self.assertFalse(result['releaseAllowed'])
        statement=next(q for q in queries if 'WITH batch AS' in q)
        for value in ('LIMIT 100','FOR UPDATE',"lock_timeout='2s'","statement_timeout='5s'",'SET restore_blocked=TRUE'):self.assertIn(value,statement)
        self.assertNotIn('DELETE',statement);self.assertNotIn('INSERT',statement);self.assertNotIn('source_request_id=',statement)
        self.assertTrue(any('<>7' in q for q in queries));restored,_=self.fixture(batches=('0',));self.assertEqual(0,privacy.invalidate_hold_intake(restored)['blocked'])
    def test_partial_delivery_schema_and_inconsistent_receipt_scope_block_restore(self):
        for delivery in ('1','3','invalid'):
            restored,queries=self.fixture(delivery=delivery)
            with self.subTest(delivery=delivery),self.assertRaisesRegex(ValueError,'delivery history schema'):privacy.invalidate_hold_intake(restored)
            self.assertEqual(2,len(queries))
        restored,_=self.fixture(history='1')
        with self.assertRaisesRegex(ValueError,'Inconsistent hold delivery history'):privacy.invalidate_hold_intake(restored)
    def test_historical_intake_without_delivery_history_is_not_a_delivery_receipt(self):
        restored,_=self.fixture(delivery='0');result=privacy.invalidate_hold_intake(restored)
        self.assertFalse(result['deliveryHistoryVerified']);self.assertFalse(result['sourceDeliveryConfirmed']);self.assertFalse(result['releaseAllowed'])
    def test_invalid_counts_incomplete_plans_and_budget_exhaustion_block_restore(self):
        for value in ('101','-1','invalid'):
            restored,_=self.fixture(batches=(value,))
            with self.subTest(value=value),self.assertRaises(ValueError):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(remaining='1')
        with self.assertRaises(ValueError):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(batches=('1',))
        with patch.object(privacy,'MAX_BATCHES',1),self.assertRaises(ValueError):privacy.invalidate_hold_intake(restored)
    def test_intake_failure_stops_before_other_scopes_and_sessions(self):
        restored,_=self.fixture();restored.sql=Mock(return_value='t')
        with patch.object(privacy,'invalidate_commerce_assessments',return_value={}),patch.object(privacy,'invalidate_observation_rounds',return_value={}),patch.object(privacy,'invalidate_retention_intents',return_value={}),patch.object(privacy,'invalidate_hold_intake',side_effect=ValueError('intake quarantine')),patch.object(privacy,'invalidate_retention_holds') as later:
            with self.assertRaisesRegex(ValueError,'intake quarantine'):privacy.replay(restored,[])
        later.assert_not_called();self.assertTrue(all('UPDATE' not in c.args[1] and 'DELETE' not in c.args[1] for c in restored.sql.call_args_list))
    def test_quarantine_sql_failure_never_returns_a_positive_receipt(self):
        restored,_=self.fixture();restored.sql=Mock(side_effect=['2',RuntimeError('fixture SQL failure')])
        with self.assertRaisesRegex(RuntimeError,'fixture SQL failure'):privacy.invalidate_hold_intake(restored)
        self.assertEqual(2,restored.sql.call_count)
    def test_partial_or_inconsistent_reconciliation_history_blocks_restore(self):
        for value in ('1','3','invalid'):
            restored,_=self.fixture(reconciliation=value)
            with self.subTest(value=value),self.assertRaisesRegex(ValueError,'reconciliation history schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(delivery='0',reconciliation='2')
        with self.assertRaisesRegex(ValueError,'reconciliation history schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(probes='1')
        with self.assertRaisesRegex(ValueError,'Inconsistent hold reconciliation history'):privacy.invalidate_hold_intake(restored)
    def test_reconciliation_history_is_preserved_without_inventing_pending_results(self):
        restored,queries=self.fixture();result=privacy.invalidate_hold_intake(restored)
        self.assertTrue(result['reconciliationHistoryVerified']);self.assertFalse(result['releaseAllowed'])
        statement=next(q for q in queries if 'WITH invalid_probes AS' in q)
        for value in ('source_request_id=p.source_request_id','source_hold_id=p.source_hold_id','count(*)>8','orphan_results'):self.assertIn(value,statement)
        for value in ('INSERT','UPDATE','DELETE'):self.assertNotIn(value,statement)
    def test_historical_schema_without_probe_history_does_not_confirm_reconciliation(self):
        restored,_=self.fixture(reconciliation='0');result=privacy.invalidate_hold_intake(restored)
        self.assertFalse(result['reconciliationHistoryVerified']);self.assertFalse(result['sourceDeliveryConfirmed'])
    def test_partial_or_inconsistent_recovery_planning_blocks_restore(self):
        for value in ('1','3','invalid'):
            restored,_=self.fixture(planning=value)
            with self.subTest(value=value),self.assertRaisesRegex(ValueError,'recovery planning schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(reconciliation='0',planning='2')
        with self.assertRaisesRegex(ValueError,'recovery planning schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(plans='1')
        with self.assertRaisesRegex(ValueError,'Inconsistent hold recovery planning'):privacy.invalidate_hold_intake(restored)
    def test_recovery_plans_preserve_history_without_age_revalidation_or_execution(self):
        restored,queries=self.fixture();result=privacy.invalidate_hold_intake(restored);self.assertTrue(result['recoveryPlanningVerified']);self.assertFalse(result['releaseAllowed'])
        statement=next(q for q in queries if 'WITH invalid_resolutions AS' in q)
        for value in ('n.probe_no>p.probe_no','invalid_repair_intents','MISSING_AT_SOURCE'):self.assertIn(value,statement)
        for value in ('clock_timestamp','INSERT','UPDATE','DELETE'):self.assertNotIn(value,statement)
    def test_historical_schema_without_recovery_plans_does_not_invent_authority(self):
        restored,_=self.fixture(planning='0');result=privacy.invalidate_hold_intake(restored);self.assertFalse(result['recoveryPlanningVerified']);self.assertFalse(result['releaseAllowed'])

    def test_restoration_dispatch_history_preserved_under_quarantine_without_pending_ack(self):
        restored,queries=self.fixture(dispatch='3');result=privacy.invalidate_hold_intake(restored)
        self.assertTrue(result['restorationDispatchVerified']);self.assertFalse(result['releaseAllowed'])
        check=next(q for q in queries if 'WITH invalid_dispatch_attempts AS' in q)
        for term in ('NOT p.restore_blocked',"f.mode<>'RESTORE'","a.mode<>'REPLAY_ONLY'",'count(*)>8','case_authorization_id','source_revision<=b.source_revision','invalid_dispatch_stops'):self.assertIn(term,check)
        self.assertNotIn('INSERT',check);self.assertNotIn('UPDATE',check);self.assertNotIn('DELETE',check)
    def test_partial_or_inconsistent_dispatch_history_blocks_restore(self):
        for value in ('1','2','4','invalid'):
            restored,_=self.fixture(dispatch=value)
            with self.subTest(value=value),self.assertRaisesRegex(ValueError,'dispatch schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(dispatch='3',dispatch_history='1')
        with self.assertRaisesRegex(ValueError,'dispatch history'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(dispatch='3',planning='0')
        with self.assertRaisesRegex(ValueError,'dispatch schema'):privacy.invalidate_hold_intake(restored)
    def test_historical_absent_dispatch_does_not_invent_execution_or_confirmation(self):
        restored,_=self.fixture(dispatch='0');result=privacy.invalidate_hold_intake(restored)
        self.assertFalse(result['restorationDispatchVerified']);self.assertFalse(result['sourceDeliveryConfirmed'])

    def test_review_versions_are_preserved_and_digest_checked_under_quarantine(self):
        restored,queries=self.fixture(versions='1');result=privacy.invalidate_hold_intake(restored)
        self.assertTrue(result['reviewVersionsVerified']);self.assertFalse(result['releaseAllowed'])
        sql=next(q for q in queries if 'WITH invalid_review_versions AS' in q)
        for text in ('NOT p.restore_blocked','sha256(','int4send(64)',"v.protocol||'/version'",'count(*)>8','expires_at>v.reviewed_at'):
            self.assertIn(text,sql)
        for text in ('clock_timestamp','INSERT','UPDATE','DELETE'):self.assertNotIn(text,sql)
    def test_unsupported_or_inconsistent_review_versions_block_restore(self):
        for value in ('2','invalid'):
            restored,_=self.fixture(versions=value)
            with self.subTest(value=value),self.assertRaisesRegex(ValueError,'version schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(versions='1',version_history='1')
        with self.assertRaisesRegex(ValueError,'version history'):privacy.invalidate_hold_intake(restored)
    def test_historical_absence_of_review_versions_is_not_a_validity_receipt(self):
        restored,_=self.fixture(versions='0');result=privacy.invalidate_hold_intake(restored)
        self.assertFalse(result['reviewVersionsVerified']);self.assertFalse(result['releaseAllowed'])

    def test_review_steps_preserved_without_resume_or_lease_renewal(self):
        restored,queries=self.fixture(versions='1',steps='1');r=privacy.invalidate_hold_intake(restored)
        self.assertTrue(r['reviewStepsVerified']);self.assertFalse(r['releaseAllowed'])
        sql=next(q for q in queries if 'WITH invalid_review_steps AS' in q)
        for guard in ('NOT r.restore_blocked','s.step_id<>s.probe_id','i.request_id<>v.request_id','p.request_id<>v.request_id','s.expires_at<>v.expires_at','s.prepared_scope_hash<>v.scope_hash','s.created_at<v.reviewed_at','s.created_at>=s.expires_at'):self.assertIn(guard,sql)
        for mutation in ('INSERT','UPDATE','DELETE','clock_timestamp','SET expires_at'):self.assertNotIn(mutation,sql)
    def test_partial_or_inconsistent_review_step_history_blocks_restore(self):
        for schema in ('2','invalid'):
            restored,_=self.fixture(steps=schema)
            with self.subTest(schema=schema),self.assertRaisesRegex(ValueError,'review step schema'):privacy.invalidate_hold_intake(restored)
        restored,_=self.fixture(steps='1',step_history='1')
        with self.assertRaisesRegex(ValueError,'review step history'):privacy.invalidate_hold_intake(restored)
    def test_absent_step_schema_does_not_invent_a_reservation(self):
        restored,_=self.fixture();r=privacy.invalidate_hold_intake(restored);self.assertFalse(r['reviewStepsVerified'])
