"""Policy boundaries and public candidate assessment; no real data or settings."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('retention_policy_tests', ROOT / 'scripts/qa/retention-policy-check.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class RetentionPolicyTests(unittest.TestCase):
    def fixture(self):
        temp = tempfile.TemporaryDirectory(prefix='retention-policy-', dir=ROOT/'tmp')
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        doc = gate.load(ROOT, gate.CONTRACT)
        sources = set(doc['sourceHashes']) | {gate.CONTRACT, doc['stopProcedure'], 'deploy/components.json'}
        for path in ROOT.glob('*-service/src/main/resources/db/migration/V*.sql'):
            sources.add(path.relative_to(ROOT).as_posix())
        for name in sources:
            target = root/name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT/name).read_bytes())
        return root, doc

    def write(self, root, doc):
        (root/gate.CONTRACT).write_text(json.dumps(doc, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def test_real_matrix_covers_every_field_and_reports_unresolved_decisions_without_io(self):
        with patch('socket.socket', side_effect=AssertionError('network forbidden')), patch('subprocess.run', side_effect=AssertionError('child forbidden')):
            result = gate.check()
        self.assertEqual((145,1307,6,20,26), tuple(result[k] for k in ('tables','columns','redisStores','copyStores','policies')))
        self.assertEqual('PASS',result['status']);self.assertFalse(result['policyReady']);self.assertFalse(result['activationAllowed'])
        self.assertEqual(0,result['executedDataOperations']);self.assertFalse(result['runtimeConfigurationInspected'])
        for name in ('commercial-records','payment-stream','invoice-stream'):
            self.assertIn('policy:'+name,result['blockers'])
        self.assertNotIn('policy:security-sql',result['blockers'])
        self.assertEqual(18,result['decisionReceipt']['entries'])
        self.assertIn('decision-plan:D07-D08-D14-D15-P09-unresolved-preservation-exceptions',result['blockers'])
        self.assertEqual((10,127,5,8),tuple(result['commerceAssessment'][key] for key in
                                         ('tables','columns','financialTablesExcluded','participantsRequired')))
        self.assertFalse(result['commerceAssessment']['runtimeWired'])
        self.assertEqual(2,result['commerceAssessment']['durableMetadataTables'])
        self.assertEqual(1,result['commerceAssessment']['coordinatorMetadataTables'])
        self.assertEqual(1,result['commerceAssessment']['orderEvidenceMetadataTables'])
        self.assertEqual(14,result['commerceAssessment']['sourceOutboxTables'])
        self.assertEqual(['billing-service','delivery-service','inventory-service','notification-service','payment-service','promotion-service','returns-service'],result['commerceAssessment']['sourceOutboxOwners'])
        self.assertFalse(result['commerceAssessment']['sourceAdaptersWired'])
        self.assertEqual(20,result['commerceAssessment']['fields']['redact'])
        self.assertEqual(0,result['commerceAssessment']['fields']['review'])

    def test_missing_table_column_namespace_and_copy_fail_closed(self):
        root, original = self.fixture()
        for kind in ('table','column','redis','copy'):
            doc=copy.deepcopy(original)
            if kind=='table':del doc['tables']['order-service/orders']
            elif kind=='column':doc['tables']['order-service/orders']['columns'].remove('customer_email')
            elif kind=='redis':del doc['redis']['payment-stream']
            else:del doc['copies']['independent-privacy-journal']
            self.write(root,doc)
            with self.subTest(kind=kind),self.assertRaises(ValueError):gate.check(root)

    def test_added_ddl_field_is_not_silently_covered_by_default_policy(self):
        root,doc=self.fixture()
        path=root/'order-service/src/main/resources/db/migration/V9999__policy_fixture.sql'
        path.write_text('ALTER TABLE orders ADD COLUMN unreviewed_personal_note TEXT;',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'retention field'):gate.check(root)

    def test_changed_approved_period_and_false_completion_are_rejected(self):
        root,original=self.fixture()
        changes=[('period',True), *[(name,True) for name in ('stageComplete','runtimeActivation','fullA43Complete','fullErasureComplete')]]
        for key,value in changes:
            doc=copy.deepcopy(original)
            if key=='period':doc['policies']['audit']['retention']['seconds']=86400
            else:doc[key]=value
            self.write(root,doc)
            with self.subTest(key=key),self.assertRaises(ValueError):gate.check(root)

    def test_user_deferred_decisions_cannot_be_promoted_or_given_ttl(self):
        root,original=self.fixture()
        for decision in gate.PENDING_DECISIONS:
            doc=copy.deepcopy(original);doc['decisions'][decision]['status']='approved';self.write(root,doc)
            with self.subTest(decision=decision),self.assertRaises(ValueError):gate.check(root)
        doc=copy.deepcopy(original);doc['policies']['commercial-records']['retention'].update(kind='duration',seconds=90*86400);self.write(root,doc)
        with self.assertRaises(ValueError):gate.check(root)

    def test_security_period_approval_preserves_clocks_guards_and_field_scope(self):
        root,original=self.fixture()
        for mutation in ('age','clock','guard','action','field','decision'):
            doc=copy.deepcopy(original)
            policy=doc['policies']['security-sql']
            if mutation=='age':policy['retention']['seconds']=86400
            elif mutation=='clock':policy['retention']['startsAt']='created_at'
            elif mutation=='guard':policy['guards'].remove('whole-refresh-family-protection')
            elif mutation=='action':policy['action']='existing-purge'
            elif mutation=='field':doc['tables']['customer-service/refresh_tokens']['fieldPolicies']['token_hash']='runtime-limits'
            else:doc['decisions']['security-sql']['status']='pending'
            self.write(root,doc)
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):gate.check(root)

    def test_receipt_edits_cannot_override_user_answers_even_with_refreshed_source_hash(self):
        root,original=self.fixture()
        receipt=gate.load(ROOT,gate.DECISION_RECEIPT)
        for mutation in ('financial-delete','minimum-clock','local-copy','owner-only','exceptions','activation'):
            edited=copy.deepcopy(receipt)
            decisions=edited['decisions']
            if mutation=='financial-delete':decisions['D01']['action']='delete-after-30-days'
            elif mutation=='minimum-clock':decisions['D02']['minimumStartsAt']='chain-closure'
            elif mutation=='local-copy':decisions['D09']['independentCopyLocation']='E:\\Klucze\\Kopia3'
            elif mutation=='owner-only':decisions['D15']['additionalReviewer']='deputy'
            elif mutation=='exceptions':decisions['D07']['exceptions']='none'
            else:edited['activationAllowed']=True
            source=root/gate.DECISION_RECEIPT
            source.write_text(json.dumps(edited)+'\n',encoding='utf-8')
            doc=copy.deepcopy(original)
            doc['sourceHashes'][gate.DECISION_RECEIPT]=hashlib.sha256(source.read_bytes()).hexdigest()
            self.write(root,doc)
            with self.subTest(mutation=mutation),self.assertRaisesRegex(ValueError,'decision change'):gate.check(root)

    def test_receipt_cannot_be_omitted_or_redirected_and_old_matrix_revision_is_rejected(self):
        root,original=self.fixture()
        for mutation in ('missing','redirected','old-version'):
            doc=copy.deepcopy(original)
            if mutation=='missing':del doc['decisionReceipt']
            elif mutation=='redirected':doc['decisionReceipt']='tmp/unreviewed.json'
            else:doc['policyVersion']='a43.40-2026-10-30.4'
            self.write(root,doc)
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):gate.check(root)

    def write_commerce(self, root, parent, plan):
        path=root/gate.commerce.CONTRACT
        path.write_text(json.dumps(plan)+'\n',encoding='utf-8')
        doc=copy.deepcopy(parent)
        doc['sourceHashes'][gate.commerce.CONTRACT]=hashlib.sha256(path.read_bytes()).hexdigest()
        self.write(root,doc)

    def test_commerce_mapping_cannot_delete_ids_expire_protocols_or_change_approved_delivery_scope(self):
        root,parent=self.fixture()
        original=gate.load(ROOT,gate.commerce.CONTRACT)
        for mutation in ('id','protocol','review','missing','duplicate','financial'):
            plan=copy.deepcopy(original)
            fields=plan['tables']['order-service/orders']['fields']
            if mutation=='id':fields['minimum'].remove('id');fields['redact'].append('id')
            elif mutation=='protocol':fields['protocol'].remove('checkout_request_hash');fields['minimum'].append('checkout_request_hash')
            elif mutation=='review':fields['redact'].remove('pickup_location');fields['minimum'].append('pickup_location')
            elif mutation=='missing':fields['minimum'].remove('customer_id')
            elif mutation=='duplicate':fields['minimum'].append('customer_id')
            else:plan['tables']['billing-service/invoices']=copy.deepcopy(plan['tables']['order-service/orders'])
            self.write_commerce(root,parent,plan)
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):gate.check(root)

    def test_commerce_clock_proof_requirements_and_runtime_claims_fail_closed(self):
        root,parent=self.fixture()
        original=gate.load(ROOT,gate.commerce.CONTRACT)
        for mutation in ('clock','participant','returns','worker','runtime','markers','financial','sources','store','store-table',
                         'endpoint-enabled','proof-acceptance','producer-wired','restore-release','restore-guests',
                         'pilot-runtime','pilot-proof','pilot-ttl','pilot-coordinator','pilot-restore',
                         'extension-scope','extension-runtime','binding-runtime','binding-transport','binding-epochs','binding-count',
                         'http-activate','http-caller','http-limit','http-prepare','order-history','order-global','order-redaction',
                         'inventory-history','inventory-root','inventory-scope','inventory-lines',
                         'guard-global','guard-positive','guard-transaction','guard-policy','guard-journal','guard-history','guard-missing',
                         'source-guard-activation','source-guard-positive','source-guard-replay','source-guard-coverage','source-guard-transaction','source-guard-missing',
                         'source-guard-inventory','source-guard-inventory-clearance','source-guard-inventory-flag','source-receipt-limit',
                         'source-hold-ttl','source-hold-release','source-hold-coverage','source-hold-revision','source-hold-public','source-hold-missing','source-receipt-hold-fingerprint',
                         'source-hold-source-missing','source-hold-table-missing',
                         'hold-intake-missing','hold-intake-rpc','hold-intake-delivery','hold-intake-partial','hold-intake-caller-transaction','hold-intake-case-proof',
                         'hold-delivery-missing','hold-delivery-budget','hold-delivery-auto','hold-delivery-transaction','hold-delivery-auth','hold-delivery-authority',
                         'hold-progress-missing','hold-progress-unbounded','hold-progress-reset','hold-progress-rpc','hold-progress-authority','hold-progress-coverage',
                         'hold-reconciliation-missing','hold-reconciliation-freshness','hold-reconciliation-repair','hold-reconciliation-history','hold-reconciliation-transaction','hold-reconciliation-auth','hold-reconciliation-default','hold-reconciliation-authority',
                         'hold-history-missing','hold-history-resend','hold-history-budget','hold-history-expiry','hold-history-transaction','hold-history-authority',
                         'repair-assessment-missing','repair-assessment-pending','repair-assessment-freshness','repair-assessment-rpc','repair-assessment-budget','repair-assessment-authority',
                         'recovery-planning-missing','recovery-planning-termination','recovery-planning-repair','recovery-planning-reset','recovery-planning-late-result','recovery-planning-authority',
                         'recovery-planning-outcome',
                         'review-step-missing','review-step-resend','review-step-atomic','review-step-lease','review-step-transaction','review-step-budget','review-step-write','review-step-activation',
                          'review-version-missing','review-version-clock','review-version-expiry','review-version-raw','review-version-authority','review-version-budget','review-version-restore','review-version-distributed',
                         'restoration-review-missing','restoration-review-newest','restoration-review-budget','restoration-review-counts','restoration-review-clock','restoration-review-authority','restoration-review-distributed','restoration-review-rpc',
                         'restoration-progress-missing','restoration-progress-pending','restoration-progress-version','restoration-progress-freshness','restoration-progress-budget','restoration-progress-authority','restoration-progress-auto',
                         'root-restoration-missing','root-restoration-resend','root-restoration-budget','root-restoration-transaction','root-restoration-stop','root-restoration-activation','root-restoration-proof',
                         'source-restoration-missing','source-restoration-boolean','source-restoration-cas','source-restoration-expiry','source-restoration-default','source-restoration-proof','source-restoration-dispatch',
                         'source-guard-sql-failure','source-guard-auto-retry','source-guard-proof-write',
                         'source-guard-commit-loss','source-guard-atomicity',
                         'observation-authority','observation-unbounded','observation-overwrite','observation-expiry','observation-proof','observation-missing',
                         'intent-authority','intent-stop','intent-resume','intent-unbounded','intent-expiry','intent-missing',
                         'intent-review-authority','intent-review-transaction','intent-review-scope','intent-review-participants','intent-review-stop','intent-review-missing',
                         'intent-fence-authority','intent-fence-release','intent-fence-ack','intent-fence-uncertainty','intent-fence-transaction','intent-fence-renew','intent-fence-inventory','intent-fence-missing',
                         'intent-http-authority','intent-http-inventory','intent-http-deployment','intent-http-loss','intent-http-missing',
                         'rounds-expiry','rounds-resume','rounds-ttl','rounds-limit','rounds-record','rounds-missing',
                         'fence-replay-expiry','fence-replay-resume','fence-replay-release','fence-replay-budget','fence-replay-missing',
                         'round-recovery-prepare','round-recovery-owner','round-recovery-transaction','round-recovery-schedule','round-recovery-mutation','round-recovery-missing',
                         'round-recovery-timeout','round-recovery-quarantine','round-recovery-binding','round-recovery-settlement-rollback','round-recovery-settlement-unknown',
                         'inventory-http-activation','inventory-http-caller','inventory-http-meaning',
                         'peer-activation','peer-provider','peer-absence','peer-receipts','peer-ttl','peer-global','hold-coverage','hold-clearance','hold-release','hold-ttl','hold-api','hold-scope','hold-lock','hold-wakeup','hold-missing','workflow-runtime','workflow-steps','workflow-phase','workflow-lease','workflow-guards','workflow-fence','workflow-lineage','workflow-missing','claim-leases','claim-locks','claim-expiry','claim-cleanup','claim-constant','claim-scope','claim-clock','claim-missing','field-legacy','field-schema','field-preservation','field-runtime','field-history','field-missing','revalidation-global','revalidation-calls','revalidation-backoff','revalidation-active','revalidation-proof','revalidation-mutation','revalidation-retry','revalidation-missing'):
            plan=copy.deepcopy(original)
            if mutation=='clock':plan['clocks']['minimum']='created_at'
            elif mutation=='participant':plan['requiredParticipants'].remove('returns-service')
            elif mutation=='returns':plan['stateReview']['returns-service']['completedWithoutEffectsReconciliation']='eligible'
            elif mutation=='worker':plan['mutationWorker']='implemented'
            elif mutation=='runtime':plan['runtimeWired']=True
            elif mutation=='markers':plan['protocolHasAutomaticExpiry']=True
            elif mutation=='financial':plan['financialAction']='delete-after-30-days'
            elif mutation=='store':plan['proofStorage']='fully-integrated-trusted-sources'
            elif mutation=='store-table':plan['proofStoreTables']=[]
            elif mutation=='endpoint-enabled':plan['sourceInvalidation']['defaultEnabled']=True
            elif mutation=='proof-acceptance':plan['sourceInvalidation']['positiveProofsAccepted']=True
            elif mutation=='producer-wired':plan['sourceInvalidation']['producersWired']=False
            elif mutation=='restore-release':plan['restoreInvalidation']['releaseAllowed']=True
            elif mutation=='restore-guests':plan['restoreInvalidation']['allAccountsAndGuests']=False
            elif mutation=='pilot-runtime':plan['sourceOutboxPilot']['runtimeWired']=True
            elif mutation=='pilot-proof':plan['sourceOutboxPilot']['positiveProofsAccepted']=True
            elif mutation=='pilot-ttl':plan['sourceOutboxPilot']['automaticExpiry']=True
            elif mutation=='pilot-coordinator':plan['sourceOutboxPilot']['coordinatorWired']=False
            elif mutation=='pilot-restore':plan['sourceOutboxPilot']['restoreBlocked']=False
            elif mutation=='extension-scope':plan['sourceOutboxExtensions']['inventory-service']['capturedTables']=[]
            elif mutation=='extension-runtime':plan['sourceOutboxExtensions']['delivery-service']['runtimeWired']=True
            elif mutation=='binding-runtime':plan['bindingCoordinator']['runtimeWired']=True
            elif mutation=='binding-transport':plan['bindingCoordinator']['transport']='authenticated-http-implemented'
            elif mutation=='binding-epochs':plan['bindingCoordinator']['expectedEpoch']='fetch-current-before-every-retry'
            elif mutation=='binding-count':plan['bindingCoordinator']['commandsPerGeneration']=6
            elif mutation=='http-activate':plan['bindingHttp']['deploymentActivated']=True
            elif mutation=='http-caller':plan['bindingHttp']['bindCaller']='any-service'
            elif mutation=='http-limit':plan['bindingHttp']['responseMaxBytes']=1048576
            elif mutation=='http-prepare':plan['bindingHttp']['preparesNewGenerations']=True
            elif mutation=='order-history':plan['orderEvidence']['clock']='updated_at'
            elif mutation=='order-global':plan['orderEvidence']['globalGuardsConfirmed']=True
            elif mutation=='order-redaction':plan['orderEvidence']['redactionProofImplemented']=True
            elif mutation=='inventory-history':plan['inventoryEvidence']['clock']='updated_at'
            elif mutation=='inventory-root':plan['inventoryEvidence']['rootAcceptanceWired']=False
            elif mutation=='inventory-scope':plan['inventoryEvidence']['scope']='order-id-only'
            elif mutation=='inventory-lines':plan['inventoryEvidence']['maxLines']=1000
            elif mutation=='inventory-http-activation':plan['inventoryHttp']['defaultEnabled']=True
            elif mutation=='inventory-http-caller':plan['inventoryHttp']['caller']='customer'
            elif mutation=='inventory-http-meaning':plan['inventoryHttp']['proofMeaning']='cleanup-permission'
            elif mutation=='peer-activation':plan['peerEvidence']['defaultEnabled']=True
            elif mutation=='peer-provider':plan['peerEvidence']['externalStripeSupported']=True
            elif mutation=='peer-absence':plan['peerEvidence']['absenceIsConfirmed']=True
            elif mutation=='peer-receipts':plan['peerEvidence']['receiptBinding']='row-count-only'
            elif mutation=='peer-ttl':plan['peerEvidence']['automaticExpiry']=True
            elif mutation=='peer-global':plan['peerEvidence']['globalGuardsConfirmed']=True
            elif mutation=='hold-coverage':plan['knownPurchaseHolds']['completeInventory']=True
            elif mutation=='hold-clearance':plan['knownPurchaseHolds']['absenceIsClearance']=True
            elif mutation=='hold-release':plan['knownPurchaseHolds']['releaseImplemented']=True
            elif mutation=='hold-ttl':plan['knownPurchaseHolds']['automaticExpiry']=True
            elif mutation=='hold-api':plan['knownPurchaseHolds']['publicApi']=True
            elif mutation=='hold-scope':plan['knownPurchaseHolds']['scope']='all-customer-data'
            elif mutation=='hold-lock':plan['knownPurchaseHolds']['assessment']='unlocked-snapshot'
            elif mutation=='hold-wakeup':plan['knownPurchaseHolds']['claimWakeup']='ignore-customer-revision'
            elif mutation=='hold-missing':plan.pop('knownPurchaseHolds')
            elif mutation=='workflow-runtime':plan['targetedWorkflow']['runtimeWired']=True
            elif mutation=='workflow-steps':plan['targetedWorkflow']['maxSourceCallsPerStep']=8
            elif mutation=='workflow-phase':plan['targetedWorkflow']['phases'].append('MINIMUM_REMOVAL')
            elif mutation=='workflow-lease':plan['targetedWorkflow']['scopedAssessmentLease']=False
            elif mutation=='workflow-guards':plan['targetedWorkflow']['globalGuardWrites']=False
            elif mutation=='workflow-fence':plan['targetedWorkflow']['distributedWriteFenceImplemented']=True
            elif mutation=='workflow-lineage':plan['targetedWorkflow']['generationRetryKey']='order-id-only'
            elif mutation=='workflow-missing':plan.pop('targetedWorkflow')
            elif mutation=='claim-leases':plan['claimSelection']['maxLeasesPerCall']=100
            elif mutation=='claim-locks':plan['claimSelection']['maxCandidateJobLocks']=100
            elif mutation=='claim-expiry':plan['claimSelection']['expiryBatch']=100000
            elif mutation=='claim-cleanup':plan['claimSelection']['cleanupImplemented']=True
            elif mutation=='claim-constant':plan['claimSelection']['constantWorkForAllScopeCounts']=True
            elif mutation=='claim-scope':plan['claimSelection']['scopedLease']='any-purchase'
            elif mutation=='claim-clock':plan['claimSelection']['leaseClock']='statement-time'
            elif mutation=='claim-missing':plan.pop('claimSelection')
            elif mutation=='guard-global':plan['globalGuardCollection']['scope']='complete-global-clearance'
            elif mutation=='guard-positive':plan['globalGuardCollection']['providerConfirmationsAccepted']=True
            elif mutation=='guard-transaction':plan['globalGuardCollection']['providerCallsInsideSqlTransaction']=True
            elif mutation=='guard-policy':plan['globalGuardCollection']['policyReceiptIsCompleteApproval']=True
            elif mutation=='guard-journal':plan['globalGuardCollection']['journalInclusionIsCurrentHead']=True
            elif mutation=='guard-history':plan['globalGuardCollection']['historicalPolicyAndJournalConfirmations']='trust-state'
            elif mutation=='guard-missing':plan.pop('globalGuardCollection')
            elif mutation=='source-guard-activation':plan['sourceGuardObservations']['defaultEnabled']=True
            elif mutation=='source-guard-positive':plan['sourceGuardObservations']['positiveProofsAccepted']=True
            elif mutation=='source-guard-replay':plan['sourceGuardObservations']['requestIdentity']='bare-order-id'
            elif mutation=='source-guard-coverage':plan['sourceGuardObservations']['completeHoldInventory']=True
            elif mutation=='source-guard-transaction':plan['sourceGuardObservations']['transactionAcrossRemoteCall']=True
            elif mutation=='source-guard-missing':plan.pop('sourceGuardObservations')
            elif mutation=='source-guard-inventory':plan['sourceGuardObservations']['sources'].remove('INVENTORY')
            elif mutation=='source-guard-inventory-clearance':plan['sourceGuardObservations']['inventoryAbsenceClearance']=True
            elif mutation=='source-guard-inventory-flag':plan['sourceGuardObservations']['inventoryFlag']='always-on'
            elif mutation=='source-receipt-limit':plan['sourceObservationReceipts']['maxRowsPerGeneration']=1000
            elif mutation=='source-hold-ttl':plan['knownSourceHolds']['automaticExpiry']=True
            elif mutation=='source-hold-release':plan['knownSourceHolds']['releaseImplemented']=True
            elif mutation=='source-hold-coverage':plan['knownSourceHolds']['completeInventory']=True
            elif mutation=='source-hold-revision':plan['knownSourceHolds']['revision']='caller-supplied'
            elif mutation=='source-hold-public':plan['knownSourceHolds']['publicApi']=True
            elif mutation=='source-hold-missing':plan.pop('knownSourceHolds')
            elif mutation=='source-hold-source-missing':plan['knownSourceHolds']['sources'].remove('INVENTORY')
            elif mutation=='source-hold-table-missing':plan['knownSourceHolds']['tables'].remove('inventory-service/commerce_retention_source_holds')
            elif mutation=='hold-intake-missing':plan.pop('localHoldIntake')
            elif mutation=='hold-intake-rpc':plan['localHoldIntake']['sourceRpcImplemented']=True
            elif mutation=='hold-intake-delivery':plan['localHoldIntake']['sourceDeliveryConfirmed']=True
            elif mutation=='hold-intake-partial':plan['localHoldIntake']['sourcePlanCompleteness']='partial-plan-accepted'
            elif mutation=='hold-intake-caller-transaction':plan['localHoldIntake']['callerTransactionAllowed']=True
            elif mutation=='hold-intake-case-proof':plan['localHoldIntake']['caseReference']='proof-of-ownership'
            elif mutation=='hold-delivery-missing':plan.pop('explicitHoldDelivery')
            elif mutation=='hold-delivery-budget':plan['explicitHoldDelivery']['maxAttemptsPerRequestSource']=1000
            elif mutation=='hold-delivery-auto':plan['explicitHoldDelivery']['automaticScheduling']=True
            elif mutation=='hold-delivery-transaction':plan['explicitHoldDelivery']['remoteCallsInsideTransaction']=True
            elif mutation=='hold-delivery-auth':plan['explicitHoldDelivery']['sourceRoute']='public-POST'
            elif mutation=='hold-delivery-authority':plan['explicitHoldDelivery']['activationAllowed']=True
            elif mutation=='hold-progress-missing':plan.pop('holdDeliveryProgress')
            elif mutation=='hold-progress-unbounded':plan['holdDeliveryProgress']['maxSources']=1000
            elif mutation=='hold-progress-reset':plan['holdDeliveryProgress']['automaticBudgetReset']=True
            elif mutation=='hold-progress-rpc':plan['holdDeliveryProgress']['remoteCalls']=True
            elif mutation=='hold-progress-authority':plan['holdDeliveryProgress']['activationAllowed']=True
            elif mutation=='hold-progress-coverage':plan['holdDeliveryProgress']['completeCaseCoverage']=True
            elif mutation=='hold-reconciliation-missing':plan.pop('holdReconciliation')
            elif mutation=='hold-reconciliation-freshness':plan['holdReconciliation']['freshness']='reuse-cached-reply'
            elif mutation=='hold-reconciliation-repair':plan['holdReconciliation']['repairPerformed']=True
            elif mutation=='hold-reconciliation-history':plan['holdReconciliation']['historyOrBudgetWrites']=True
            elif mutation=='hold-reconciliation-transaction':plan['holdReconciliation']['rpcInsideRootTransaction']=True
            elif mutation=='hold-reconciliation-auth':plan['holdReconciliation']['sourceRoute']='public-POST'
            elif mutation=='hold-reconciliation-default':plan['holdReconciliation']['defaultEnabled']=True
            elif mutation=='hold-reconciliation-authority':plan['holdReconciliation']['completeCaseCoverage']=True
            elif mutation=='hold-history-missing':plan.pop('holdReconciliationHistory')
            elif mutation=='hold-history-resend':plan['holdReconciliationHistory']['sameProbeRetry']='redispatch'
            elif mutation=='hold-history-budget':plan['holdReconciliationHistory']['automaticBudgetReset']=True
            elif mutation=='hold-history-expiry':plan['holdReconciliationHistory']['automaticExpiry']=True
            elif mutation=='hold-history-transaction':plan['holdReconciliationHistory']['rpcInsideRootTransaction']=True
            elif mutation=='hold-history-authority':plan['holdReconciliationHistory']['activationAllowed']=True
            elif mutation=='repair-assessment-missing':plan.pop('holdRepairAssessment')
            elif mutation=='repair-assessment-pending':plan['holdRepairAssessment']['pending']='ignore-older-pending'
            elif mutation=='repair-assessment-freshness':plan['holdRepairAssessment']['freshness']='trust-any-age'
            elif mutation=='repair-assessment-rpc':plan['holdRepairAssessment']['networkCalls']=True
            elif mutation=='repair-assessment-budget':plan['holdRepairAssessment']['automaticBudgetReset']=True
            elif mutation=='repair-assessment-authority':plan['holdRepairAssessment']['executionAllowed']=True
            elif mutation=='recovery-planning-missing':plan.pop('holdRecoveryPlanning')
            elif mutation=='recovery-planning-termination':plan['holdRecoveryPlanning']['executorTerminationConfirmed']=True
            elif mutation=='recovery-planning-repair':plan['holdRecoveryPlanning']['repairExecutionImplemented']=True
            elif mutation=='recovery-planning-reset':plan['holdRecoveryPlanning']['automaticBudgetReset']=True
            elif mutation=='recovery-planning-late-result':plan['holdRecoveryPlanning']['lateResult']='accept-late-result'
            elif mutation=='recovery-planning-authority':plan['holdRecoveryPlanning']['activationAllowed']=True
            elif mutation=='recovery-planning-outcome':plan['holdRecoveryPlanning']['supersededOutcome']='pretend-original-outcome-known'
            elif mutation=='review-step-missing':plan.pop('holdReviewSteps')
            elif mutation=='review-step-resend':plan['holdReviewSteps']['sameIdRetry']='redispatch'
            elif mutation=='review-step-atomic':plan['holdReviewSteps']['reservation']='separate-check-and-reserve'
            elif mutation=='review-step-lease':plan['holdReviewSteps']['leaseRenewed']=True
            elif mutation=='review-step-transaction':plan['holdReviewSteps']['rpcInsideTransaction']=True
            elif mutation=='review-step-budget':plan['holdReviewSteps']['budgetReset']=True
            elif mutation=='review-step-write':plan['holdReviewSteps']['writeAuthority']=True
            elif mutation=='review-step-activation':plan['holdReviewSteps']['activationAllowed']=True
            elif mutation=='review-version-missing':plan.pop('holdReviewVersions')
            elif mutation=='review-version-clock':plan['holdReviewVersions']['trustClientClock']=True
            elif mutation=='review-version-expiry':plan['holdReviewVersions']['maxLifetimeSeconds']=3600
            elif mutation=='review-version-raw':plan['holdReviewVersions']['storeRawReview']=True
            elif mutation=='review-version-authority':plan['holdReviewVersions']['localCurrentGrantsAuthority']=True
            elif mutation=='review-version-budget':plan['holdReviewVersions']['budgetReset']=True
            elif mutation=='review-version-restore':plan['holdReviewVersions']['restoreRenewsValidity']=True
            elif mutation=='review-version-distributed':plan['holdReviewVersions']['distributedSnapshot']=True
            elif mutation=='restoration-review-missing':plan.pop('holdRestorationReview')
            elif mutation=='restoration-review-newest':plan['holdRestorationReview']['selection']='latest-intent-only'
            elif mutation=='restoration-review-budget':plan['holdRestorationReview']['budgetReset']=True
            elif mutation=='restoration-review-counts':plan['holdRestorationReview']['counters']='sum-shared-probes-per-intent'
            elif mutation=='restoration-review-clock':plan['holdRestorationReview']['freshness']='trust-early-match'
            elif mutation=='restoration-review-authority':plan['holdRestorationReview']['sevenFreshMatchesGrantAuthority']=True
            elif mutation=='restoration-review-distributed':plan['holdRestorationReview']['distributedSnapshot']=True
            elif mutation=='restoration-review-rpc':plan['holdRestorationReview']['networkCalls']=True
            elif mutation=='restoration-progress-missing':plan.pop('holdRestorationProgress')
            elif mutation=='restoration-progress-pending':plan['holdRestorationProgress']['pendingWrite']='resolve-by-match'
            elif mutation=='restoration-progress-version':plan['holdRestorationProgress']['version']='ignore-source-version'
            elif mutation=='restoration-progress-freshness':plan['holdRestorationProgress']['freshness']='use-old-match'
            elif mutation=='restoration-progress-budget':plan['holdRestorationProgress']['budgetReset']=True
            elif mutation=='restoration-progress-authority':plan['holdRestorationProgress']['currentAuthority']=True
            elif mutation=='restoration-progress-auto':plan['holdRestorationProgress']['automaticRefresh']=True
            elif mutation=='root-restoration-missing':plan.pop('rootHoldRestorationDispatch')
            elif mutation=='root-restoration-resend':plan['rootHoldRestorationDispatch']['recovery']='resend-restore'
            elif mutation=='root-restoration-budget':plan['rootHoldRestorationDispatch']['automaticBudgetReset']=True
            elif mutation=='root-restoration-transaction':plan['rootHoldRestorationDispatch']['rpcInsideRootTransaction']=True
            elif mutation=='root-restoration-stop':plan['rootHoldRestorationDispatch']['stop']='pretend-executor-terminated'
            elif mutation=='root-restoration-activation':plan['rootHoldRestorationDispatch']['activationAllowed']=True
            elif mutation=='root-restoration-proof':plan['rootHoldRestorationDispatch']['proofInSqlOrResult']=True
            elif mutation=='source-restoration-missing':plan.pop('sourceHoldRestoration')
            elif mutation=='source-restoration-boolean':plan['sourceHoldRestoration']['booleanApprovalAccepted']=True
            elif mutation=='source-restoration-cas':plan['sourceHoldRestoration']['sourceTransaction']='detached-check-then-write'
            elif mutation=='source-restoration-expiry':plan['sourceHoldRestoration']['expiredAuthorization']='allow-new-write'
            elif mutation=='source-restoration-default':plan['sourceHoldRestoration']['defaultEnabled']=True
            elif mutation=='source-restoration-proof':plan['sourceHoldRestoration']['proofInResponseOrSql']=True
            elif mutation=='source-restoration-dispatch':plan['sourceHoldRestoration']['rootDispatcherImplemented']=False
            elif mutation=='source-receipt-hold-fingerprint':plan['sourceObservationReceipts']['fingerprintCompatibility']='ignore-hold-state'
            elif mutation=='source-guard-sql-failure':plan['sourceGuardObservations']['localSqlFailure']='success'
            elif mutation=='source-guard-auto-retry':plan['sourceGuardObservations']['automaticRetries']=True
            elif mutation=='source-guard-proof-write':plan['sourceGuardObservations']['concurrentProofWrite']='overwrite'
            elif mutation=='source-guard-commit-loss':plan['sourceGuardObservations']['commitAcknowledgementLoss']='assume-rollback'
            elif mutation=='source-guard-atomicity':plan['sourceGuardObservations']['proofWriteAtomicity']='independent-inserts'
            elif mutation=='observation-authority':plan['sourceObservationReceipts']['currentAuthority']=True
            elif mutation=='observation-unbounded':plan['sourceObservationReceipts']['identity']='one-row-per-request'
            elif mutation=='observation-overwrite':plan['sourceObservationReceipts']['immutable']=False
            elif mutation=='observation-expiry':plan['sourceObservationReceipts']['automaticExpiry']=True
            elif mutation=='observation-proof':plan['sourceObservationReceipts']['affectsAssessmentProofRevision']=True
            elif mutation=='observation-missing':plan.pop('sourceObservationReceipts')
            elif mutation=='intent-authority':plan['intentRegistry']['authorizationImplemented']=True
            elif mutation=='intent-stop':plan['intentRegistry']['stopIsExecutorTermination']=True
            elif mutation=='intent-resume':plan['intentRegistry']['restore']='automatic-resume'
            elif mutation=='intent-unbounded':plan['intentRegistry']['maxEventsPerIntent']=100
            elif mutation=='intent-expiry':plan['intentRegistry']['automaticExpiry']=True
            elif mutation=='intent-missing':plan.pop('intentRegistry')
            elif mutation=='intent-review-authority':plan['intentEvidenceReview']['authorizationImplemented']=True
            elif mutation=='intent-review-transaction':plan['intentEvidenceReview']['finalAssessment']='detached-claim-then-assessment'
            elif mutation=='intent-review-scope':plan['intentEvidenceReview']['preparation']='intent-id-only'
            elif mutation=='intent-review-participants':plan['intentEvidenceReview']['participants']=6
            elif mutation=='intent-review-stop':plan['intentEvidenceReview']['stopIsExecutorTermination']=True
            elif mutation=='intent-review-missing':plan.pop('intentEvidenceReview')
            elif mutation=='intent-fence-authority':plan['intentObservationFences']['authorizationImplemented']=True
            elif mutation=='intent-fence-release':plan['intentObservationFences']['release']='successful-prepares-only'
            elif mutation=='intent-fence-ack':plan['intentObservationFences']['releaseAcknowledgement']='assume-success'
            elif mutation=='intent-fence-uncertainty':plan['intentObservationFences']['cleanupUncertainty']='hide'
            elif mutation=='intent-fence-transaction':plan['intentObservationFences']['remoteCallsInsideTransaction']=True
            elif mutation=='intent-fence-renew':plan['intentObservationFences']['leaseRenewal']=True
            elif mutation=='intent-fence-inventory':plan['intentObservationFences']['includesInventory']=False
            elif mutation=='intent-fence-missing':plan.pop('intentObservationFences')
            elif mutation=='intent-http-authority':plan['intentObservationFenceHttp']['authorizationImplemented']=True
            elif mutation=='intent-http-inventory':plan['intentObservationFenceHttp']['inventoryTransportSimulated']=True
            elif mutation=='intent-http-deployment':plan['intentObservationFenceHttp']['fullApplicationAcceptance']=True
            elif mutation=='intent-http-loss':plan['intentObservationFenceHttp']['releaseReplyLoss']='assume-released'
            elif mutation=='intent-http-missing':plan.pop('intentObservationFenceHttp')
            elif mutation=='rounds-expiry':plan['observationRoundHistory']['automaticExpiry']=True
            elif mutation=='rounds-resume':plan['observationRoundHistory']['recoverySourceRequestsImplemented']=False
            elif mutation=='rounds-ttl':plan['observationRoundHistory']['ttlIsAcknowledgement']=True
            elif mutation=='rounds-limit':plan['observationRoundHistory']['maxRoundsPerIntent']=10000
            elif mutation=='rounds-record':plan['observationRoundHistory']['attempt']='record-after-PREPARE'
            elif mutation=='rounds-missing':plan.pop('observationRoundHistory')
            elif mutation=='fence-replay-expiry':plan['sourceFenceReplayProtection']['automaticExpiry']=True
            elif mutation=='fence-replay-resume':plan['sourceFenceReplayProtection']['recoverySourceRequestsImplemented']=False
            elif mutation=='fence-replay-release':plan['sourceFenceReplayProtection']['releaseBeforePrepare']='acknowledge-without-marker'
            elif mutation=='fence-replay-budget':plan['sourceFenceReplayProtection']['maxTokensPerOrderAndEpoch']=100000
            elif mutation=='fence-replay-missing':plan.pop('sourceFenceReplayProtection')
            elif mutation=='round-recovery-prepare':plan['observationRoundRecovery']['sourceScope']='new-PREPARE'
            elif mutation=='round-recovery-owner':plan['observationRoundRecovery']['ownership']='uuid-only-without-expiry'
            elif mutation=='round-recovery-transaction':plan['observationRoundRecovery']['remoteCallsInsideTransaction']=True
            elif mutation=='round-recovery-schedule':plan['observationRoundRecovery']['automaticScheduling']=True
            elif mutation=='round-recovery-mutation':plan['observationRoundRecovery']['businessMutationImplemented']=True
            elif mutation=='round-recovery-missing':plan.pop('observationRoundRecovery')
            elif mutation=='round-recovery-timeout':plan['observationRoundRecovery']['faultHandling']['httpTimeout']='assume-acknowledged'
            elif mutation=='round-recovery-quarantine':plan['observationRoundRecovery']['faultHandling']['inFlightQuarantine']='persist-late-reply'
            elif mutation=='round-recovery-binding':plan['observationRoundRecovery']['faultHandling']['changedBinding']='use-current-epoch'
            elif mutation=='round-recovery-settlement-rollback':plan['observationRoundRecovery']['faultHandling']['settlementRollback']='repeat-all-source-RPC'
            elif mutation=='round-recovery-settlement-unknown':plan['observationRoundRecovery']['faultHandling']['settlementCommitUnknown']='assume-rollback'
            elif mutation=='field-legacy':plan['fieldScopeReview']['legacyProof']='trust-state-without-digest'
            elif mutation=='field-schema':plan['fieldScopeReview']['deployedSchemaVerified']=True
            elif mutation=='field-preservation':plan['fieldScopeReview']['globalPreservationReviewed']=True
            elif mutation=='field-runtime':plan['fieldScopeReview']['runtimeWired']=True
            elif mutation=='field-history':plan['fieldScopeReview']['history']='backfill-current-digest'
            elif mutation=='field-missing':plan.pop('fieldScopeReview')
            elif mutation=='revalidation-global':plan['revalidation']['globalSnapshot']=True
            elif mutation=='revalidation-calls':plan['revalidation']['maxRemoteCalls']=100
            elif mutation=='revalidation-backoff':plan['revalidation']['failureBackoff']='reset'
            elif mutation=='revalidation-active':plan['revalidation']['activeLease']='replace'
            elif mutation=='revalidation-proof':plan['revalidation']['proofRevisionCompare']=False
            elif mutation=='revalidation-mutation':plan['revalidation']['businessMutationAllowed']=True
            elif mutation=='revalidation-retry':plan['revalidation']['automaticRetries']=True
            elif mutation=='revalidation-missing':plan.pop('revalidation')
            else:plan['sourceHashes']={}
            self.write_commerce(root,parent,plan)
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):gate.check(root)

    def test_financial_fields_and_preserved_markers_cannot_acquire_age_based_cleanup(self):
        root,original=self.fixture()
        for table,policy in [('billing-service/invoices','audit'),('order-service/orders','export-result'),('order-service/orders','reference-lifecycle'),('customer-service/account_privacy_fences','audit'),('order-service/commerce_retention_jobs','audit'),('order-service/commerce_retention_evidence','audit'),('returns-service/commerce_retention_source_state','audit'),('returns-service/commerce_retention_change_outbox','audit')]:
            doc=copy.deepcopy(original);doc['tables'][table]['defaultPolicy']=policy;self.write(root,doc)
            with self.subTest(table=table),self.assertRaises(ValueError):gate.check(root)
        for table in gate.commerce.SOURCE_TABLES | gate.commerce.COORDINATOR_TABLES | gate.commerce.ORDER_TABLES | gate.commerce.PEER_TABLES | gate.commerce.HOLD_TABLES:
            doc=copy.deepcopy(original);doc['tables'][table]['defaultPolicy']='audit';self.write(root,doc)
            with self.subTest(source=table),self.assertRaises(ValueError):gate.check(root)
        doc=copy.deepcopy(original);doc['policies']['replay-markers']['retention'].update(kind='duration',seconds=1209600);self.write(root,doc)
        with self.assertRaises(ValueError):gate.check(root)

    def test_compaction_cannot_expand_to_recipient_or_identity(self):
        root,original=self.fixture()
        for column in ('recipient','recipient_hash','id'):
            doc=copy.deepcopy(original);doc['tables']['notification-service/notifications']['fieldPolicies'][column]='sent-notification-content';self.write(root,doc)
            with self.subTest(column=column),self.assertRaisesRegex(ValueError,'field scope'):gate.check(root)

    def test_active_redis_journal_and_streams_do_not_inherit_backup_age(self):
        root,original=self.fixture()
        for key in ('redis-durability','independent-privacy-journal','monitoring-storage','browser-downloads'):
            doc=copy.deepcopy(original);doc['copies'][key]['policy']='backup-sets';self.write(root,doc)
            with self.subTest(key=key),self.assertRaises(ValueError):gate.check(root)
        doc=copy.deepcopy(original);doc['redis']['payment-stream']['policy']='runtime-limits';self.write(root,doc)
        with self.assertRaises(ValueError):gate.check(root)

    def test_owner_source_hash_and_runtime_evidence_drift_are_detected(self):
        root,original=self.fixture()
        doc=copy.deepcopy(original);doc['tables']['order-service/orders']['owner']='billing-service';self.write(root,doc)
        with self.assertRaises(ValueError):gate.check(root)
        self.write(root,original);source=root/'billing-service/src/main/resources/application.yaml';source.write_text(source.read_text(encoding='utf-8')+'\n# unreviewed change\n',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'source change'):gate.check(root)

    def example(self):
        return gate.load(ROOT,'scripts/qa/contracts/retention-observe-example.json')

    def test_observe_example_is_current_public_metadata_and_is_never_applied(self):
        result=gate.check();candidate=gate.candidate(ROOT,self.example(),result)
        self.assertEqual('PASS',candidate['status']);self.assertFalse(candidate['applied']);self.assertFalse(candidate['activationAllowed'])

    def test_candidate_cannot_inject_secret_setting_change_interval_or_request_activation(self):
        result=gate.check();original=self.example()
        for mutation in ('key','period','mode','activation','hash','boolean','old-version','security-period'):
            doc=copy.deepcopy(original)
            if mutation=='key':doc['settings']['STRIPE_SECRET_KEY']='synthetic-never-echo'
            elif mutation=='period':doc['settings']['CUSTOMER_AUDIT_RETENTION_AGE']='1d'
            elif mutation=='mode':doc['settings']['CUSTOMER_AUDIT_RETENTION_MODE']='purge'
            elif mutation=='activation':doc['intent']='activate'
            elif mutation=='hash':doc['policySha256']='0'*64
            elif mutation=='boolean':doc['settings']['CC_JOB_LOG_RETENTION_DAYS']=True
            elif mutation=='old-version':doc['policyVersion']='a43.40-2026-10-30.4'
            else:doc['settings']['CUSTOMER_SECURITY_RETENTION_AGE']='1d'
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):gate.candidate(ROOT,doc,result)

    def test_ready_gate_returns_not_ready_and_safe_cli_error_never_echoes_input(self):
        run=subprocess.run([sys.executable,str(ROOT/'scripts/qa/retention-policy-check.py'),'--require-ready'],capture_output=True,text=True,timeout=30)
        self.assertEqual(2,run.returncode);self.assertEqual('NOT_READY',json.loads(run.stdout)['status'])
        with tempfile.TemporaryDirectory(prefix='retention-cli-',dir=ROOT/'tmp') as directory:
            path=Path(directory)/'candidate.json';path.write_text('{"private":"synthetic-never-echo"}',encoding='utf-8')
            run=subprocess.run([sys.executable,str(ROOT/'scripts/qa/retention-policy-check.py'),'--candidate',path.relative_to(ROOT).as_posix()],capture_output=True,text=True,timeout=30)
        self.assertEqual(1,run.returncode);self.assertNotIn('synthetic-never-echo',run.stdout+run.stderr)


    def test_packaged_field_definition_cannot_change_with_refreshed_file_hashes(self):
        root,parent=self.fixture()
        path=gate.commerce.FIELD_SCOPE_PATHS['definition'];target=root/path
        target.write_bytes(target.read_bytes().replace(b'delivery_date',b'unreviewed_field'))
        child=gate.load(root,gate.commerce.CONTRACT);child['sourceHashes'][path]=hashlib.sha256(target.read_bytes().replace(b'\r\n', b'\n')).hexdigest()
        parent['sourceHashes'][path]=child['sourceHashes'][path];self.write_commerce(root,parent,child)
        with self.assertRaisesRegex(ValueError,'Packaged field scope'):gate.check(root)

if __name__=='__main__':unittest.main()
