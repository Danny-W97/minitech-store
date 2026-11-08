#!/usr/bin/env python3
"""Read-only D02 field/proof-plan assessment. Does not collect proofs or modify data."""
import hashlib
import importlib.util
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = 'scripts/qa/contracts/commerce-retention.json'
RECEIPT = 'scripts/qa/contracts/retention-decisions.json'
ASSESSOR = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionAssessment.java'
STORE = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionJobs.java'
STORE_MIGRATION = 'order-service/src/main/resources/db/migration/V27__commerce_retention_assessment.sql'
STORE_TABLES = {'order-service/commerce_retention_jobs', 'order-service/commerce_retention_evidence'}
CLAIM_MIGRATION = 'order-service/src/main/resources/db/migration/V33__index_retention_claim_candidates.sql'
CLAIM_VERIFIER = 'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionClaimScalePostgresIT.java'
CLAIM_PLAN = {'store':STORE,'migration':CLAIM_MIGRATION,'verifier':CLAIM_VERIFIER,
    'mode':'indexed-assessment-leases-only','fixtureJobs':100000,'fixtureScopeHeaders':199901,
    'candidateBranches':4,'maxCandidateJobLocks':4,'maxLeasesPerCall':1,'customerWindow':16,'expiryBatch':25,
    'revisionComparison':'monotonic-behind-or-unobserved','selectionClock':'statement-time-index-range',
    'leaseClock':'live-database-time','scopedLease':'exact-subject-version-phase',
    'constantWorkForAllScopeCounts':False,'cleanupImplemented':False,'runtimeWired':False}
COORDINATOR = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionBindings.java'
COORDINATOR_MIGRATION = 'order-service/src/main/resources/db/migration/V28__commerce_retention_bindings.sql'
COORDINATOR_TABLES = {'order-service/commerce_retention_bindings'}
ORDER_EVIDENCE = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionOrderEvidence.java'
ORDER_MIGRATION = 'order-service/src/main/resources/db/migration/V29__trusted_order_retention_clock.sql'
ORDER_TABLES = {'order-service/commerce_retention_order_state'}
WORKFLOW = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionWorkflow.java'
WORKFLOW_MIGRATION = 'order-service/src/main/resources/db/migration/V32__retention_preparation_predecessor.sql'
WORKFLOW_PLAN = {'source':WORKFLOW,'migration':WORKFLOW_MIGRATION,'mode':'targeted-unscheduled-metadata-only',
    'phases':['REDACTION'],'sources':['ORDER','PAYMENT','INVENTORY','RETURNS','PROMOTION','DELIVERY','BILLING','NOTIFICATION'],
    'maxSourceCallsPerStep':1,'generationRetryKey':'requested-generation-and-immutable-predecessor',
    'preparationVersionCheck':True,'identity':'derived-from-current-order-only','scopedAssessmentLease':True,
    'checkpoints':'before-and-after-source-read','callerTransactionAllowed':False,
    'globalGuardWrites':True,'publicApi':False,'runtimeWired':False,'cleanupImplemented':False,
    'distributedWriteFenceImplemented':False,'restoreLineagePreserved':True,'automaticExpiry':False}
REVALIDATION_VERIFIERS = [
    'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionWorkflowPostgresIT.java',
    'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionPeersPostgresIT.java']
REVALIDATION_PLAN = {'workflow':WORKFLOW,'store':STORE,'verifiers':REVALIDATION_VERIFIERS,
    'phases':['REDACTION'],'requiredExistingParticipants':8,'maxSourceReads':8,'maxRemoteCalls':7,
    'order':['ORDER','PAYMENT','INVENTORY','RETURNS','PROMOTION','DELIVERY','BILLING','NOTIFICATION'],
    'checkpoints':'before-after-each-read-and-assessment','proofRevisionCompare':True,'holdRevisionCompare':True,
    'concurrentNewProof':'retry-without-retiring-generation','claim':'exact-scope-and-proof-revision-cas',
    'activeLease':'preserve','attemptBudget':'preserve','failureBackoff':'preserve','automaticRetries':False,
    'globalSnapshot':False,'distributedWriteFence':False,'persistedFreshnessReceipts':False,
    'businessMutationAllowed':False,'runtimeWired':False,'publicApi':False}
HOLD_STORE = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHolds.java'
HOLD_MIGRATION = 'order-service/src/main/resources/db/migration/V31__commerce_retention_holds.sql'
HOLD_TABLES = {'order-service/commerce_retention_hold_scopes','order-service/commerce_retention_holds'}
SOURCE_HOLD_TABLE='returns-service/commerce_retention_source_holds'
HOLD_DELIVERY_PATHS={
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldDeliveryProtocol.java',
    'receiver':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldDeliveryReceiver.java',
    'configuration':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldDeliveryConfiguration.java',
    'autoImports':'platform-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports',
    'client':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionClient.java',
    'routes':'platform-security/src/main/java/org/demo/platformsecurity/RoutePolicy.java',
    'sender':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldDelivery.java',
    'migration':'order-service/src/main/resources/db/migration/V45__retention_hold_delivery_history.sql',
    'unitVerifier':'platform-security/src/test/java/org/demo/platformsecurity/privacy/SourceHoldDeliveryProtocolTest.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java',
    'httpFixture':'order-service/src/test/java/org/demo/orderservice/privacy/RetentionIntentHttpFixture.java'}
HOLD_DELIVERY_TABLES={'order-service/commerce_retention_hold_delivery_attempts','order-service/commerce_retention_hold_delivery_receipts'}
HOLD_DELIVERY_PLAN={**HOLD_DELIVERY_PATHS,'tables':sorted(HOLD_DELIVERY_TABLES),
    'mode':'explicit-one-source-single-RPC','maxAttemptsPerRequestSource':8,'automaticRetries':False,
    'attempt':'immutable-sequential-SQL-commit-before-RPC-no-send-on-unknown-commit',
    'identity':'persisted-source-request-stable-order-guard-reason-exact-response',
    'sourceAcknowledgement':'RECORDED-only-after-source-commit-unknown-conflict-quarantine-not-ack',
    'sourceCallerTransactionAllowed':False,'rootCallerTransactionAllowed':False,'remoteCallsInsideTransaction':False,
    'rootCheckpoints':'parent-and-known-scope-quarantine-before-after-RPC-and-at-receipt-insert',
    'receipt':'immutable-source-plan-attempt-and-hold-ID-historical-not-current-authority',
    'retry':'same-source-request-id-idempotent-no-overwrite-or-budget-reset',
    'unknownSourcePurchase':'remain-pending-no-implicit-binding-or-absence-clearance',
    'sourceFlag':'privacy.commerce-retention-source-hold-delivery.enabled','defaultEnabled':False,
    'sourceRoute':'POST-internal-order-service-only','requestLimit':2048,'replyLimit':2048,
    'restore':'parent-quarantine-preserve-history-budget-and-scope-no-resume',
    'automaticScheduling':False,'rootRuntimeWired':False,'fullCaseCoverage':False,'activationAllowed':False}
HOLD_PROGRESS_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldDeliveryProgress.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldDeliveryProgressPostgresIT.java'}
HOLD_RECONCILIATION_PATHS={
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldInspectionProtocol.java',
    'inspector':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldInspector.java',
    'configuration':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldInspectionConfiguration.java',
    'autoImports':HOLD_DELIVERY_PATHS['autoImports'],'client':HOLD_DELIVERY_PATHS['client'],'routes':HOLD_DELIVERY_PATHS['routes'],
    'coordinator':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldReconciliation.java',
    'unitVerifier':'platform-security/src/test/java/org/demo/platformsecurity/privacy/SourceHoldInspectionProtocolTest.java',
    'httpVerifier':HOLD_DELIVERY_PATHS['verifier'],'httpFixture':HOLD_DELIVERY_PATHS['httpFixture']}
HOLD_RECONCILIATION_PLAN={**HOLD_RECONCILIATION_PATHS,
    'mode':'explicit-one-source-as-of-read-diagnostic',
    'scope':'exact-root-order-request-case-and-persisted-source-request-guard-reason-hold-ID',
    'freshness':'new-random-challenge-per-call-exact-echo-age-15s-future-skew-5s-rechecked-after-root-read',
    'sourceSnapshot':'advisory-436-and-source-state-lock-serialized-with-hold-writes',
    'codes':['MATCH','MISSING_HOLD','CONFLICT','UNKNOWN_PURCHASE','QUARANTINED'],
    'match':'exact-immutable-hold-identity-as-of-source-read-not-global-current-coverage',
    'missing':'diagnostic-no-repair-no-implicit-binding-no-absence-clearance',
    'historicalReceipt':'required-before-RPC-preserved-without-update',
    'rootCheckpoints':'exact-scope-and-quarantine-before-and-after-RPC-discard-late-observation',
    'rpcInsideRootTransaction':False,'callerTransactionsAllowed':False,'maxRpcPerCall':1,
    'requestLimit':2048,'replyLimit':4096,'sourceRoute':'POST-internal-order-service-only',
    'sourceFlag':'privacy.commerce-retention-source-hold-inspection.enabled','defaultEnabled':False,
    'transportFailure':'UNAVAILABLE-never-missing-or-match-no-details',
    'historyOrBudgetWrites':False,'repairPerformed':False,'automaticRetries':False,
    'persistentReconciliationRecorded':False,'automaticScheduling':False,'rootRuntimeWired':False,
    'publicOperatorApi':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_HISTORY_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldReconciliationHistory.java',
    'migration':'order-service/src/main/resources/db/migration/V46__hold_reconciliation_history.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldReconciliationHistoryPostgresIT.java',
    'httpVerifier':HOLD_RECONCILIATION_PATHS['httpVerifier'],'coordinator':HOLD_RECONCILIATION_PATHS['coordinator'],
    'restore':'scripts/backup/privacy-restore.py','restoreUnitVerifier':'scripts/qa/unit/test_retention_hold_intake_restore.py',
    'restoreVerifier':'scripts/qa/privacy-recovery-e2e.py'}
HOLD_HISTORY_TABLES={'order-service/commerce_retention_hold_probes','order-service/commerce_retention_hold_probe_results'}
HOLD_HISTORY_PLAN={**HOLD_HISTORY_PATHS,'tables':sorted(HOLD_HISTORY_TABLES),
    'scope':'exact-order-request-case-source-and-persisted-receipt-identity',
    'probe':'immutable-sequential-SQL-commit-before-RPC-probe-id-is-challenge',
    'maxProbesPerRequestSource':8,'maxSources':7,'maxProbesPerRequest':56,
    'sameProbeRetry':'read-stored-result-or-PENDING-or-SUPERSEDED-never-redispatch',
    'unknownProbeCommit':'UNAVAILABLE-no-RPC-retry-reads-committed-pending',
    'unknownResultCommit':'UNAVAILABLE-retry-reads-result-or-pending-no-RPC',
    'result':'immutable-typed-diagnostic-append-once-after-RPC-not-current-authority',
    'quarantine':'no-new-probe-late-result-only-ROOT_QUARANTINED-history-preserved',
    'inspection':'one-scoped-source-at-most-eight-ordered-entries-no-RPC',
    'restore':'parent-quarantine-preserve-probes-results-budget-no-resume-no-pending-ack',
    'callerTransactionsAllowed':False,'rpcInsideRootTransaction':False,'automaticRetries':False,
    'automaticBudgetReset':False,'automaticExpiry':False,'repairPerformed':False,
    'publicOperatorApi':False,'runtimeWired':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_REPAIR_ASSESSMENT_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRepairAssessment.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRepairAssessmentPostgresIT.java',
    'httpVerifier':HOLD_RECONCILIATION_PATHS['httpVerifier']}
HOLD_REPAIR_ASSESSMENT_PLAN={**HOLD_REPAIR_ASSESSMENT_PATHS,
    'scope':'exact-order-request-case-seven-planned-sources',
    'snapshot':'parent-lock-serializes-receipt-probe-and-result-writers',
    'limits':{'sources':7,'probesPerSource':8,'probesPerRequest':56},
    'pending':'any-unresolved-pending-blocks-candidate-no-implicit-resolution',
    'candidate':'latest-fresh-MISSING_AT_SOURCE-exact-receipt-probe-guard-reason-and-version',
    'freshness':'stored-observation-age-15s-future-tolerance-5s-otherwise-recheck',
    'priorDiscrepancies':'count-preserved-no-implicit-case-closure-on-later-match',
    'quarantine':'all-source-states-ROOT_QUARANTINED-no-candidate-preserve-counters',
    'invalidHistory':'INCONSISTENT_HISTORY-with-empty-sources-no-candidate',
    'candidateRequirements':['fresh-atomic-source-check','verified-case','controlled-execution-protocol'],
    'callerTransactionsAllowed':False,'networkCalls':False,'metadataWrites':False,
    'automaticBudgetReset':False,'repairAllowed':False,'executionAllowed':False,
    'runtimeWired':False,'publicOperatorApi':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_RECOVERY_PLANNING_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRecoveryPlanning.java',
    'migration':'order-service/src/main/resources/db/migration/V47__hold_probe_resolution_and_repair_intents.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRecoveryPlanningPostgresIT.java',
    'history':HOLD_HISTORY_PATHS['store'],'assessment':HOLD_REPAIR_ASSESSMENT_PATHS['store'],
    'httpVerifier':HOLD_RECONCILIATION_PATHS['httpVerifier'],'restore':HOLD_HISTORY_PATHS['restore'],
    'restoreUnitVerifier':HOLD_HISTORY_PATHS['restoreUnitVerifier'],'restoreVerifier':HOLD_HISTORY_PATHS['restoreVerifier']}
HOLD_RECOVERY_PLANNING_TABLES={'order-service/commerce_retention_hold_probe_resolutions','order-service/commerce_retention_hold_repair_intents'}
HOLD_RECOVERY_PLANNING_PLAN={**HOLD_RECOVERY_PLANNING_PATHS,'tables':sorted(HOLD_RECOVERY_PLANNING_TABLES),
    'scope':'exact-order-request-case-source-probe-and-receipt-identities',
    'resolution':'immutable-idempotent-marker-only-for-read-only-pending-probe',
    'replacement':'strictly-newer-latest-same-scope-and-hold-completed-MATCH-or-MISSING-age15-future5',
    'lateResult':'SQL-parent-lock-serializes-result-vs-resolution-superseded-result-rejected-even-old-writer',
    'executorTerminationConfirmed':False,'automaticResolution':False,
    'supersededOutcome':'outcomeUnknown-remains-true-unresolvedPending-false-no-fabricated-reply',
    'intent':'immutable-one-per-basis-latest-fresh-MISSING-no-unresolved-pending-exact-original-IDs',
    'sameIntentRetry':'read-historical-identity-no-rebind-to-new-basis',
    'limits':{'sources':7,'probesPerSource':8,'resolutionsPerSource':8,'intentsPerSource':8},
    'callerTransactionsAllowed':False,'networkCalls':False,'automaticBudgetReset':False,
    'quarantine':'blocks-new-planning-and-idempotent-commands-preserves-history',
    'restore':'preserve-markers-and-intents-under-parent-quarantine-no-age-or-latest-reapproval-no-execution',
    'repairAllowed':False,'repairExecutionImplemented':False,'runtimeWired':False,
    'publicOperatorApi':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_RESTORATION_MIGRATIONS={
    'returns-service':'returns-service/src/main/resources/db/migration/V13__source_hold_restoration_receipts.sql',
    'inventory-service':'inventory-service/src/main/resources/db/migration/V17__source_hold_restoration_receipts.sql',
    'promotion-service':'promotion-service/src/main/resources/db/migration/V15__source_hold_restoration_receipts.sql',
    'delivery-service':'delivery-service/src/main/resources/db/migration/V15__source_hold_restoration_receipts.sql',
    'payment-service':'payment-service/src/main/resources/db/migration/V26__source_hold_restoration_receipts.sql',
    'billing-service':'billing-service/src/main/resources/db/migration/V21__source_hold_restoration_receipts.sql',
    'notification-service':'notification-service/src/main/resources/db/migration/V23__source_hold_restoration_receipts.sql'}
HOLD_RESTORATION_PATHS={
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldRestorationProtocol.java',
    'authorization':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldCaseAuthorization.java',
    'receiver':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldRestorer.java',
    'configuration':'platform-security/src/main/java/org/demo/platformsecurity/privacy/SourceHoldRestorationConfiguration.java',
    'autoImports':HOLD_DELIVERY_PATHS['autoImports'],'routes':HOLD_DELIVERY_PATHS['routes'],'client':HOLD_DELIVERY_PATHS['client'],
    'preparation':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationPreparation.java',
    'unitVerifier':'platform-security/src/test/java/org/demo/platformsecurity/privacy/SourceHoldRestorationProtocolTest.java',
    'preparationVerifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationPreparationPostgresIT.java',
    'httpVerifier':HOLD_DELIVERY_PATHS['verifier'],'httpFixture':HOLD_DELIVERY_PATHS['httpFixture'],
    'syntheticCaseIssuer':'order-service/src/test/java/org/demo/orderservice/privacy/RestorationCaseFixture.java',
    'restore':'scripts/backup/privacy-restore.py','restoreVerifier':'scripts/qa/privacy-recovery-e2e.py','restoreUnitVerifier':'scripts/qa/unit/test_commerce_retention_restore.py'}
HOLD_RESTORATION_TABLES={owner+'/commerce_retention_hold_restoration_receipts' for owner in HOLD_RESTORATION_MIGRATIONS}
HOLD_RESTORATION_PLAN={**HOLD_RESTORATION_PATHS,'migrations':HOLD_RESTORATION_MIGRATIONS,'tables':sorted(HOLD_RESTORATION_TABLES),
    'scope':'source-order-original-request-hold-guard-reason-intent-case-epoch-revision',
    'caseAuthority':'independently-configured-Ed25519-key-signed-exact-scope-authorization-ID-issued-expiry-max15s-future5',
    'caseIssuerImplemented':False,'onlineCaseRevocationImplemented':False,'booleanApprovalAccepted':False,
    'sourceTransaction':'advisory436-and-state-row-lock-CAS-epoch-revision-original-hold-insert-invalidation-receipt-one-commit',
    'replay':'same-intent-scope-hash-case-authorization-and-current-matching-hold-no-counter-advance',
    'expiredAuthorization':'only-matching-committed-history-lookup-never-new-write',
    'replayOnly':'receipt-required-never-create-hold',
    'conflict':'no-overwrite-no-implicit-binding-no-partial-receipt',
    'quarantine':'blocks-new-restoration-and-historical-replay',
    'requestLimit':8192,'replyLimit':2048,'proofInResponseOrSql':False,'proofInCommandToString':False,
    'sourceRoute':'POST-internal-order-service-only','defaultEnabled':False,
    'flag':'privacy.commerce-retention-source-hold-restoration.enabled',
    'authorityKey':'privacy.commerce-retention-source-hold-restoration.case-public-key',
    'enabledWithoutAuthority':'startup-fails-closed','callerTransactionsAllowed':False,
    'rootPreparation':'immutable-intent-and-basis-SQL-exact-scope-no-authorization-claim-no-RPC-no-write',
    'rootDispatcherImplemented':True,'rootDurableAttemptsImplemented':True,'runtimeWired':False,
    'restore':'preserve-seven-source-receipts-validate-hold-identity-require-source-quarantine-no-replay',
    'fullCaseWorkflowAccepted':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_DISPATCH_PATHS={
    'coordinator':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationDispatch.java',
    'migration':'order-service/src/main/resources/db/migration/V48__hold_restoration_dispatch_history.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationDispatchPostgresIT.java',
    'httpVerifier':HOLD_RESTORATION_PATHS['httpVerifier'],'restore':HOLD_RESTORATION_PATHS['restore'],
    'restoreVerifier':HOLD_RESTORATION_PATHS['restoreVerifier'],'restoreUnitVerifier':HOLD_HISTORY_PATHS['restoreUnitVerifier']}
HOLD_DISPATCH_TABLES={'order-service/commerce_retention_hold_restoration_'+name for name in ('attempts','results','stops')}
HOLD_DISPATCH_PLAN={**HOLD_DISPATCH_PATHS,'tables':sorted(HOLD_DISPATCH_TABLES),
    'scope':'exact-order-request-case-source-immutable-intent-original-hold-and-basis-versions',
    'attempt':'immutable-commit-before-RPC-attempt-ID-is-challenge-no-send-on-unknown-commit',
    'firstAttempt':'RESTORE-only-current-missing-basis-and-active-scoped-Ed25519-authorization',
    'sameAttempt':'read-history-or-pending-never-redispatch-no-mode-rebind',
    'recovery':'new-attempt-REPLAY_ONLY-exact-scope-and-authorization-ID-never-resend-RESTORE',
    'replayUnavailable':'historical-negative-only-never-permission-for-new-write',
    'result':'immutable-exact-validated-reply-after-RPC-no-fabricated-pending-ack',
    'maxAttemptsPerIntent':8,'maxIntentsPerSource':8,'automaticRetries':False,'automaticBudgetReset':False,
    'checkpoints':'parent-locked-before-attempt-before-RPC-and-result-context-check',
    'lateContext':'only-ROOT_QUARANTINED-or-ROOT_STOPPED-never-positive-ack',
    'stop':'immutable-idempotent-marker-no-new-dispatch-not-executor-termination',
    'callerTransactionsAllowed':False,'rpcInsideRootTransaction':False,'maxRpcPerCall':1,
    'proofInSqlOrResult':False,'caseIssuerImplemented':False,'currentAuthority':False,
    'inspect':'scoped-parent-locked-at-most-eight-attempts-read-only-under-quarantine',
    'restore':'preserve-attempts-results-stops-under-parent-quarantine-no-pending-ack-no-resume',
    'publicOperatorApi':False,'runtimeWired':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_RESTORATION_PROGRESS_PATHS={
    'assessor':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationProgress.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationProgressPostgresIT.java',
    'httpVerifier':HOLD_RESTORATION_PATHS['httpVerifier'],
    'inspectionHistory':HOLD_HISTORY_PATHS['store'],'inspectionCoordinator':HOLD_RECONCILIATION_PATHS['coordinator'],
    'dispatcher':HOLD_DISPATCH_PATHS['coordinator']}
HOLD_RESTORATION_PROGRESS_PLAN={**HOLD_RESTORATION_PROGRESS_PATHS,
    'scope':'exact-order-request-case-source-intent-original-IDs-and-recomputed-scope-hash',
    'snapshot':'parent-locked-bounded-local-attempts-results-stops-and-probes',
    'dimensions':['durable-delivery-history','as-of-source-inspection'],
    'inspection':'explicit-existing-committed-challenged-probe-protocol-one-RPC-at-most',
    'freshness':'age15-future5-latest-probe-only-after-basis-intent-all-attempts-and-results',
    'version':'MATCH-or-MISSING-requires-basis-epoch-and-at-least-latest-acknowledged-source-revision',
    'pendingWrite':'preserved-count-even-after-match-or-later-replay-ack-no-fabricated-outcome',
    'pendingRead':'preserve-active-and-superseded-counts-no-latest-fallback',
    'limits':{'attemptsPerIntent':8,'probesPerRequestSource':8},
    'sameProbe':'existing-history-or-pending-never-redispatch','budgetReset':False,
    'operationStatus':'probe-operation-result-separate-from-latest-durable-inspection',
    'stopAndQuarantine':'no-new-refresh-when-observed-before-RPC-block-final-progress-after-late-change',
    'lateReadAfterStop':'read-only-history-may-remain-no-positive-progress-no-executor-termination',
    'assessWrites':False,'assessNetworkCalls':False,'newTables':False,
    'refreshWrites':'existing-read-only-inspection-probe-and-result-history-only',
    'callerTransactionsAllowed':False,'rpcInsideRootTransaction':False,'automaticRefresh':False,
    'unknownPurchase':'diagnostic-no-binding-no-absence-clearance',
    'currentAuthority':False,'executorTerminationConfirmed':False,'repairExecutionAllowed':False,
    'runtimeWired':False,'publicOperatorApi':False,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_RESTORATION_REVIEW_PATHS={
    'reviewer':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationReview.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldRestorationReviewPostgresIT.java',
    'progress':HOLD_RESTORATION_PROGRESS_PATHS['assessor'],'progressVerifier':HOLD_RESTORATION_PROGRESS_PATHS['verifier'],
    'httpVerifier':HOLD_RESTORATION_PATHS['httpVerifier']}
HOLD_RESTORATION_REVIEW_PLAN={**HOLD_RESTORATION_REVIEW_PATHS,
    'scope':'exact-order-request-case-seven-persisted-source-plans-and-original-receipts',
    'snapshot':'single-parent-locked-local-transaction-shared-progress-snapshot-no-nested-public-transactions',
    'selection':'all-intents-per-source-ordered-by-basis-never-latest-only',
    'intentLookup':'scoped-probe-primary-key-range-unique-basis-lateral-lookup',
    'limits':{'sources':7,'intentsPerSource':8,'intentsPerRequest':56,'attemptsPerIntent':8,'distinctProbesPerRequest':56},
    'counters':'attempts-and-acks-per-intent-distinct-probes-pending-and-superseded-per-source-no-multiplication',
    'oldHistory':'retain-stopped-intents-and-unknown-writes-even-when-newer-intent-matches',
    'freshness':'all-progress-rechecked-against-one-final-clock-age15-future5-no-distributed-snapshot',
    'quarantine':'final-known-quarantine-check-all-seven-rows-blocked-preserve-history-and-stop-counter',
    'missing':'explicit-no-receipt-or-no-intent-not-absence-clearance-or-repair-unnecessary',
    'failure':'empty-inconsistent-or-unavailable-no-partial-success-or-zero-count-as-absence-proof',
    'reviewReasons':'typed-per-source-gaps-pending-stop-age-version-discrepancy-and-budget',
    'networkCalls':False,'dmlWrites':False,'newTables':False,'automaticRefresh':False,'budgetReset':False,
    'callerTransactionsAllowed':False,'localSnapshot':True,'distributedSnapshot':False,
    'sevenFreshMatchesGrantAuthority':False,'currentAuthority':False,'completeCaseCoverage':False,
    'executionTerminated':False,'repairExecutionAllowed':False,'publicOperatorApi':False,
    'runtimeWired':False,'activationAllowed':False}
HOLD_REVIEW_VERSION_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldReviewVersions.java',
    'fingerprint':'order-service/src/main/java/org/demo/orderservice/privacy/HoldReviewFingerprint.java',
    'reviewer':HOLD_RESTORATION_REVIEW_PATHS['reviewer'],
    'migration':'order-service/src/main/resources/db/migration/V49__hold_review_versions.sql',
    'unitVerifier':'order-service/src/test/java/org/demo/orderservice/privacy/HoldReviewFingerprintTest.java',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldReviewVersionsPostgresIT.java',
    'httpVerifier':HOLD_RESTORATION_PATHS['httpVerifier'],'restore':HOLD_RESTORATION_PATHS['restore'],
    'restoreVerifier':HOLD_RESTORATION_PATHS['restoreVerifier'],'restoreUnitVerifier':HOLD_HISTORY_PATHS['restoreUnitVerifier']}
HOLD_REVIEW_VERSION_TABLES={'order-service/commerce_retention_hold_review_versions'}
HOLD_REVIEW_VERSION_PLAN={**HOLD_REVIEW_VERSION_PATHS,'tables':sorted(HOLD_REVIEW_VERSION_TABLES),
    'protocol':'HOLD_RESTORATION_REVIEW_V1','scope':'exact-order-request-case-server-record-with-scope-history-and-result-digests',
    'fingerprint':'SHA256-domain-separated-typed-length-prefix-map-and-set-order-normalized-UTC-timestamps',
    'history':'bounded-source-plans-receipts-delivery-attempts-probes-results-resolutions-intents-restoration-attempts-results-stops',
    'scopeInputs':'parent-order-customer-ID-order-version-known-hold-revisions-order-state-account-fence',
    'storeRawReview':False,'maxCapturesPerRequest':8,'budgetReset':False,'rowAutomaticTtl':False,
    'maxLifetimeSeconds':15,'expiry':'min-server-issue-plus15-earliest-still-fresh-source-observation-plus15',
    'trustClientClock':False,'clientSuppliedPreviewAccepted':False,'sameIdRetry':'stored-history-only-no-renewal-no-new-preview',
    'check':'same-parent-lock-recompute-all-digests-exact-expected-version-clock-before-and-after-read',
    'clockRollback':'reject-before-server-reviewedAt','unknownCommit':'no-preview-retry-reads-immutable-record',
    'quarantine':'blocks-capture-idempotent-renewal-and-check-after-restore',
    'restore':'preserve-IDs-digests-times-budget-verify-self-digest-and-quarantined-parent-no-current-age-reapproval',
    'restoreRenewsValidity':False,'distributedSnapshot':False,'localCurrentGrantsAuthority':False,
    'checkDoesRpcOrDml':False,'captureWrites':'one-immutable-minimal-protocol-row-only',
    'callerTransactionsAllowed':False,'businessCallbackAccepted':False,'actualExecutionWired':False,
    'publicOperatorApi':False,'runtimeWired':False,'fullCaseWorkflowAccepted':False,'activationAllowed':False}
HOLD_REVIEW_STEP_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionVersionedHoldInspection.java',
    'versions':HOLD_REVIEW_VERSION_PATHS['store'],
    'migration':'order-service/src/main/resources/db/migration/V50__versioned_hold_inspections.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionVersionedHoldInspectionPostgresIT.java',
    'httpVerifier':HOLD_REVIEW_VERSION_PATHS['httpVerifier'],'restore':HOLD_REVIEW_VERSION_PATHS['restore'],
    'restoreVerifier':HOLD_REVIEW_VERSION_PATHS['restoreVerifier'],'restoreUnitVerifier':HOLD_REVIEW_VERSION_PATHS['restoreUnitVerifier']}
HOLD_REVIEW_STEP_TABLES={'order-service/commerce_retention_hold_review_steps'}
HOLD_REVIEW_STEP_PLAN={**HOLD_REVIEW_STEP_PATHS,'tables':sorted(HOLD_REVIEW_STEP_TABLES),
    'operation':'explicit-one-source-read-only-inspection','scope':'exact-order-request-case-review-version-source-intent-step',
    'reservation':'parent-lock-check-version-and-reserve-existing-probe-and-step-in-one-transaction',
    'preparedFrame':'rebase-own-pending-probe-only-no-lease-renewal','commitBeforeRpc':True,
    'unknownCommit':'UNAVAILABLE-no-RPC-retry-history-only','sameIdRetry':'history-only-never-redispatch',
    'beforeAndAfterRpc':'check-scope-history-result-stop-quarantine-lease-and-clock-discard-positive-on-change',
    'maxProbesPerSource':8,'maxStepsPerReviewSource':1,'budgetReset':False,'rowAutomaticTtl':False,
    'callerTransactionsAllowed':False,'rpcInsideTransaction':False,'leaseRenewed':False,
    'restore':'preserve-binding-hashes-times-budget-under-parent-quarantine-no-resume',
    'businessCallbackAccepted':False,'writeAuthority':False,'distributedSnapshot':False,
    'runtimeWired':False,'fullCaseWorkflowAccepted':False,'activationAllowed':False}
HOLD_PROGRESS_PLAN={**HOLD_PROGRESS_PATHS,
    'scope':'exact-stable-order-request-case-reference-no-ownership-proof',
    'snapshot':'parent-lock-serializes-local-attempt-and-receipt-writers',
    'maxSources':7,'maxAttemptsPerSource':8,'unknownScope':'empty-UNKNOWN_REQUEST',
    'unattempted':'NOT_ATTEMPTED','attemptWithoutReceipt':'OUTCOME_UNKNOWN-including-in-flight-and-lost-COMMIT',
    'exhausted':'REVIEW_REQUIRED-no-budget-reset-or-dispatch',
    'receipt':'HISTORICAL_RECEIPT-not-current-source-state-or-global-coverage',
    'quarantine':'read-preserved-history-with-overall-QUARANTINED',
    'failure':'UNAVAILABLE-with-empty-counters-no-partial-result-or-SQL-details',
    'callerTransactionAllowed':False,'remoteCalls':False,'metadataWrites':False,
    'automaticRetries':False,'automaticBudgetReset':False,'publicApi':False,'runtimeWired':False,
    'currentSourceReconciliationRequired':True,'completeCaseCoverage':False,'activationAllowed':False}
HOLD_INTAKE_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionHoldIntake.java',
    'migration':'order-service/src/main/resources/db/migration/V44__retention_hold_request_intake.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionHoldIntakePostgresIT.java',
    'integrationVerifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java',
    'restore':'scripts/backup/privacy-restore.py',
    'restoreUnitVerifier':'scripts/qa/unit/test_retention_hold_intake_restore.py',
    'restoreVerifier':'scripts/qa/privacy-recovery-e2e.py'}
HOLD_INTAKE_TABLES={'order-service/commerce_retention_hold_requests','order-service/commerce_retention_hold_source_requests'}
HOLD_INTAKE_PLAN={**HOLD_INTAKE_PATHS,'tables':sorted(HOLD_INTAKE_TABLES),
    'scope':'stable-order-request-case-reference-guard-reason','caseReference':'opaque-id-not-ownership-or-evidence',
    'localCommit':'negative-root-hold-intake-and-seven-source-identifiers-one-transaction',
    'sourcePlanSize':7,'sourcePlanCompleteness':'deferred-SQL-constraint-before-commit',
    'identity':'immutable-root-hold-and-source-request-IDs-no-legacy-hold-adoption-by-API',
    'requestIdempotency':True,'callerTransactionAllowed':False,'commitAcknowledgementLoss':'unknown-no-success-retry-same-identifiers',
    'inspection':'exact-order-request-case-reference-only','restore':'bounded-parent-quarantine-preserve-identities-and-source-plan',
    'publicApi':False,'runtimeWired':False,'sourceRpcImplemented':False,'sourceDeliveryConfirmed':False,
    'automaticScheduling':False,'releaseImplemented':False,'automaticExpiry':False,'completeCaseWorkflow':False}
SOURCE_HOLD_MIGRATIONS={
    'RETURNS':'returns-service/src/main/resources/db/migration/V12__durable_source_retention_holds.sql',
    'PAYMENT':'payment-service/src/main/resources/db/migration/V25__durable_source_retention_holds.sql',
    'INVENTORY':'inventory-service/src/main/resources/db/migration/V16__durable_source_retention_holds.sql',
    'PROMOTION':'promotion-service/src/main/resources/db/migration/V14__durable_source_retention_holds.sql',
    'DELIVERY':'delivery-service/src/main/resources/db/migration/V14__durable_source_retention_holds.sql',
    'BILLING':'billing-service/src/main/resources/db/migration/V20__durable_source_retention_holds.sql',
    'NOTIFICATION':'notification-service/src/main/resources/db/migration/V22__durable_source_retention_holds.sql'}
SOURCE_HOLD_TABLES={source.lower()+'-service/commerce_retention_source_holds' for source in SOURCE_HOLD_MIGRATIONS}
SOURCE_HOLD_PATHS={
    'store':'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionSourceHolds.java',
    'migration':'returns-service/src/main/resources/db/migration/V12__durable_source_retention_holds.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionPeersPostgresIT.java',
    'restoreVerifier':'scripts/qa/privacy-recovery-e2e.py'}
SOURCE_HOLD_PLAN={**SOURCE_HOLD_PATHS,'tables':sorted(SOURCE_HOLD_TABLES),'sources':list(SOURCE_HOLD_MIGRATIONS),
    'migrations':SOURCE_HOLD_MIGRATIONS,
    'scope':'stable-known-source-purchase-id-across-generations','completeInventory':False,
    'guardStates':'known-blocker-BLOCKED-absence-UNKNOWN-no-positive-clearance',
    'revision':'database-controlled-insert-and-source-invalidation-in-one-transaction',
    'requestIdempotency':True,'history':'append-only-no-free-text-or-contact',
    'observationLease':'negative-invalidation-without-removing-fence-token',
    'restore':'retain-ledger-quarantine-source-no-resume',
    'releaseImplemented':False,'automaticExpiry':False,'publicApi':False,'runtimeWired':False}
HOLD_PLAN = {'store':HOLD_STORE,'migration':HOLD_MIGRATION,'tables':sorted(HOLD_TABLES),
    'scope':'known-order-or-stable-customer-purchases-only','completeInventory':False,
    'absenceIsClearance':False,'positiveGuardWrites':False,'runtimeWired':False,'publicApi':False,
    'releaseImplemented':False,'automaticExpiry':False,'freeText':False,
    'assessment':'fresh-scope-share-locks-through-commit','customerFanoutWrites':False,
    'claimWakeup':'changed-order-or-customer-scope-revision','dependencyGuards':'minimum-phase-only',
    'requestIdempotency':True,'restoreBlocked':True,'historyPreserved':True}
FIELD_SCOPE_PATHS = {
    'definition':'platform-security/src/main/resources/privacy/commerce-retention-field-scope.json',
    'verifier':'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionFieldScope.java',
    'adapter':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionFieldScopeEvidence.java',
    'migration':'order-service/src/main/resources/db/migration/V34__retention_field_scope_evidence.sql',
    'unitVerifier':'platform-security/src/test/java/org/demo/platformsecurity/privacy/CommerceRetentionFieldScopeTest.java'}
FIELD_SCOPE_PLAN = {**FIELD_SCOPE_PATHS,'guard':'FIELD_SCOPE_REVIEWED','meaning':'reviewed-plan-only',
    'phases':['REDACTION','MINIMUM_REMOVAL'],'workflowPhases':['REDACTION'],
    'identity':'current-order-stable-id','hashColumn':'field_scope_hash','history':'immutable-no-backfill',
    'legacyProof':'unknown-without-current-definition-hash','deployedSchemaVerified':False,
    'globalPreservationReviewed':False,'publicApi':False,'runtimeWired':False,'cleanupImplemented':False}
GLOBAL_GUARD_PATHS = {
    'reviewDefinition': 'platform-security/src/main/resources/privacy/commerce-retention-preservation-review.json',
    'reviewVerifier': 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionPreservationReview.java',
    'reviewTest': 'platform-security/src/test/java/org/demo/platformsecurity/privacy/CommerceRetentionPreservationReviewTest.java',
    'guardEvidence': 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionGlobalGuardEvidence.java',
    'guardsTest': 'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionGlobalGuardsPostgresIT.java',
}
SOURCE_GUARD_PATHS = {
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionGuardProtocol.java',
    'sourceAdapter':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionGuardEvidence.java',
    'rootCollector':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionSourceGuardCollector.java',
    'protocolTest':'platform-security/src/test/java/org/demo/platformsecurity/privacy/PeerRetentionGuardProtocolTest.java',
    'httpFixture':'order-service/src/test/java/org/demo/orderservice/privacy/RetentionGuardHttpFixture.java',
    'inventoryController':'inventory-service/src/main/java/org/demo/inventoryservice/privacy/InventoryRetentionController.java',
    'inventoryHttpVerifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java',
    'inventoryHttpFixture':'order-service/src/test/java/org/demo/orderservice/privacy/RetentionIntentHttpFixture.java',
}
SOURCE_GUARD_PLAN = {**SOURCE_GUARD_PATHS,
    'sources':['RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION'],
    'meaning':'as-of-read-negative-observations-only','positiveProofsAccepted':False,
    'completeHoldInventory':False,'globalSnapshot':False,'runtimeWired':False,
    'defaultEnabled':False,'flag':'privacy.commerce-retention-peer-evidence.enabled',
    'inventoryFlag':'privacy.commerce-retention-inventory-evidence.enabled',
    'inventoryCanonicalBlocker':'reservation-status-other-than-CONFIRMED-or-RELEASED',
    'inventoryAbsenceClearance':False,
    'knownPreservationBlocks':'seven-source-ledgers-BLOCKED-absence-UNKNOWN',
    'requestLimit':2048,'replyLimit':2048,'requestIdentity':'exact-binding-scope-policy-random-challenge',
    'maxObservationAgeSeconds':15,'maxFutureSkewSeconds':5,'maxRemoteCallsPerStep':1,
    'transactionAcrossRemoteCall':False,'executionAllowed':False,
    'localSqlFailure':'UNAVAILABLE-no-success-reported','proofWriteAtomicity':'single-local-transaction',
    'commitAcknowledgementLoss':'unknown-outcome-idempotent-retry',
    'automaticRetries':False,'retryIdentity':'fresh-challenge-exact-current-scope',
    'concurrentProofWrite':'revision-check-or-idempotent-no-overwrite'}
OBSERVATION_RECEIPT_PATHS = {
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionSourceReceipts.java',
    'migration':'order-service/src/main/resources/db/migration/V38__source_guard_observation_receipts.sql',
    'inventoryMigration':'order-service/src/main/resources/db/migration/V42__inventory_guard_observation_receipt.sql',
    'holdMigration':'order-service/src/main/resources/db/migration/V43__source_hold_observation_state.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionPeersPostgresIT.java',
    'restoreVerifier':'scripts/qa/privacy-recovery-e2e.py'}
OBSERVATION_RECEIPT_TABLE='order-service/commerce_retention_source_receipts'
OBSERVATION_RECEIPT_PLAN={**OBSERVATION_RECEIPT_PATHS,'table':OBSERVATION_RECEIPT_TABLE,
    'meaning':'historical-observation-only-not-erasure-or-global-clearance',
    'maxRowsPerGeneration':7,'identity':'order-generation-phase-source',
    'preservationState':'UNKNOWN-or-BLOCKED-no-CONFIRMED',
    'fingerprintCompatibility':'v1-when-hold-UNKNOWN-v2-when-hold-BLOCKED',
    'content':'scope-states-semantic-hash-first-observation-time-no-token-or-contact',
    'retry':'fresh-response-compared-by-semantic-scope-states',
    'conflict':'retire-generation-without-overwrite','immutable':True,
    'atomicWithBlocker':True,'affectsAssessmentProofRevision':False,
    'automaticExpiry':False,'currentAuthority':False,'runtimeWired':False}
INTENT_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionIntents.java',
    'migration':'order-service/src/main/resources/db/migration/V39__retention_intent_registry.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentsPostgresIT.java',
    'restore':'scripts/backup/privacy-restore.py',
    'restoreVerifier':'scripts/qa/unit/test_retention_intent_restore.py'}
INTENT_TABLES={'order-service/commerce_retention_intents','order-service/commerce_retention_intent_events'}
ROUND_PATHS={
    'store':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionObservationRounds.java',
    'migration':'order-service/src/main/resources/db/migration/V40__retention_observation_rounds.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionWorkflowPostgresIT.java',
    'restore':'scripts/backup/privacy-restore.py',
    'restoreVerifier':'scripts/qa/unit/test_retention_observation_restore.py',
    'recoveryVerifier':'scripts/qa/privacy-recovery-e2e.py'}
ROUND_TABLES={'order-service/commerce_retention_observation_rounds','order-service/commerce_retention_observation_leases'}
ROUND_PLAN={**ROUND_PATHS,'tables':sorted(ROUND_TABLES),'meaning':'diagnostic-write-ahead-history-not-authorization',
    'maxRoundsPerIntent':8,'maxSourcesPerRound':7,'openRoundBlocksAnother':True,
    'attempt':'commit-before-PREPARE-no-resend-from-retained-record',
    'acknowledgement':'validated-exact-RELEASE-recorded-separately-from-network-result',
    'completion':'all-recorded-attempts-have-persisted-acknowledgements',
    'restore':'monotonic-quarantine-preserve-history-before-intents-and-sessions',
    'inspection':'original-subject-intent-and-round-no-transport',
    'automaticExpiry':False,'ttlIsAcknowledgement':False,'recoverySourceRequestsImplemented':True,
    'latePrepareReplayProtectionImplemented':True,'mutationImplemented':False,'runtimeWired':False}
FENCE_REPLAY_MIGRATIONS={owner:owner+'/src/main/resources/db/migration/V'+str(version)+'__retention_fence_replay_markers.sql'
    for owner,version in {'returns-service':11,'payment-service':24,'inventory-service':15,'promotion-service':13,'delivery-service':13,'billing-service':19,'notification-service':21}.items()}
FENCE_REPLAY_TABLES={owner+'/commerce_retention_fence_tokens' for owner in FENCE_REPLAY_MIGRATIONS}
FENCE_REPLAY_ADAPTER='platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionWriteFence.java'
FENCE_REPLAY_PLAN={
    'adapter':FENCE_REPLAY_ADAPTER,'migrations':FENCE_REPLAY_MIGRATIONS,'tables':sorted(FENCE_REPLAY_TABLES),
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java',
    'meaning':'source-token-replay-denial-only','maxTokensPerOrderAndEpoch':8,
    'releaseBeforePrepare':'persist-released-token-before-acknowledgement',
    'releasedOrExpiredIssuedToken':'never-acquire-again','liveRetry':'same-token-same-expiry',
    'releaseAndLeaseRemoval':'one-source-transaction-under-shared-order-lock',
    'olderRelease':'cannot-clear-new-token','restore':'retain-markers-and-quarantine-source-with-advanced-epoch',
    'automaticExpiry':False,'fullReplicaUpgradeRequired':True,'recoverySourceRequestsImplemented':True,
    'mutationImplemented':False,'runtimeWired':False}
ROUND_RECOVERY_PATHS={
    'adapter':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionRoundRecovery.java',
    'migration':'order-service/src/main/resources/db/migration/V41__retention_round_recovery_claim.sql',
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionWorkflowPostgresIT.java',
    'httpVerifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java'}
ROUND_RECOVERY_PLAN={**ROUND_RECOVERY_PATHS,'mode':'explicit-release-only-single-pass','leaseSeconds':15,'maxClaims':8,
    'takeover':'monotonic-cleanup-only-no-more-attempt-inserts',
    'ownership':'sql-token-live-clock-and-transaction-local-context',
    'originalWorker':'before-after-RPC-and-final-assessment-locked-checkpoint',
    'sourceScope':'original-retained-attempts-only-no-new-PREPARE-or-VERIFY',
    'sourceReplies':'exact-scope-action-RELEASED-persisted-under-current-owner',
    'staleScope':'remain-pending-no-expiry-or-generation-based-acknowledgement',
    'failure':'no-automatic-retry-yield-owned-claim-or-expire-scheduling-lease',
    'restore':'quarantine-clear-ownership-preserve-cleanup-mode-attempts-and-history',
    'faultHandling':{
        'httpTimeout':'headers-or-body-after-source-commit-remains-pending-until-explicit-retry',
        'inFlightQuarantine':'ownership-lost-no-acknowledgement-no-settlement-no-resume',
        'changedBinding':'original-scope-remains-pending-new-source-lease-untouched',
        'settlementRollback':'retain-source-acknowledgements-explicit-retry-without-source-RPC',
        'settlementCommitUnknown':'unavailable-inspect-durable-state-no-assumed-rollback-or-source-replay'},
    'remoteCallsInsideTransaction':False,'automaticScheduling':False,'businessMutationImplemented':False,
    'runtimeWired':False,'stopIsExecutorTermination':False}
INTENT_PLAN={**INTENT_PATHS,'tables':sorted(INTENT_TABLES),'meaning':'preparation-stop-and-unstarted-cancellation-only',
    'states':['WAITING_AUTHORIZATION','STOP_REQUESTED','CANCELLED','QUARANTINED'],
    'maxIntentsPerGeneration':1,'maxEventsPerIntent':4,'eventAppend':'same-database-transaction-trigger',
    'identity':'exact-subject-generation-phase-policy-and-request-id',
    'concurrency':'locked-scope-and-revision-compare','recovery':'idempotent-request-after-uncertain-commit',
    'restore':'monotonic-quarantine-no-resume','stopIsExecutorTermination':False,
    'authorizationImplemented':False,'mutationImplemented':False,'runtimeWired':False,'automaticExpiry':False}
INTENT_REVIEW_PLAN={
    'workflow':WORKFLOW,'store':INTENT_PATHS['store'],
    'verifiers':REVALIDATION_VERIFIERS,
    'meaning':'intent-bound-fresh-assessment-only','participants':8,'maxRemoteCalls':7,
    'preparation':'exact-subject-intent-revision-proof-revision-and-hold-revisions',
    'participation':'seven-current-bindings-and-local-process-revision',
    'checkpoints':'before-and-after-every-source-read',
    'finalAssessment':'intent-check-claim-and-assessment-in-one-local-transaction',
    'remoteCallsInsideTransaction':False,'automaticRetries':False,
    'intentStateChangedByReview':False,'globalSnapshot':False,'authorizationImplemented':False,
    'mutationImplemented':False,'runtimeWired':False,'stopIsExecutorTermination':False}
INVENTORY_EVIDENCE = 'inventory-service/src/main/java/org/demo/inventoryservice/privacy/InventoryRetentionEvidence.java'
INTENT_FENCE_PLAN={
    'workflow':WORKFLOW,'verifiers':REVALIDATION_VERIFIERS,
    'meaning':'intent-bound-observation-leases-only','remoteParticipants':7,'includesInventory':True,
    'maxObservationRemoteCalls':7,'maxPrepareCalls':7,'maxVerifyCalls':7,'maxReleaseCalls':7,
    'checkpoints':'intent-preparation-and-participation-before-and-after-each-read-prepare-verify',
    'token':'fresh-per-round-exact-source-scope','leaseRenewal':False,
    'release':'finally-all-attempted-prepares-including-uncertain-outcomes-original-scope',
    'releaseAcknowledgement':'validated-exact-scope-action-and-RELEASED-status',
    'cleanupUncertainty':'reported-separately-without-masking-stop-or-failure',
    'finalAssessment':'intent-check-claim-assessment-and-expiry-check-in-one-local-transaction',
    'remoteCallsInsideTransaction':False,'automaticRetries':False,'durableLeaseCoordinator':False,
    'globalSnapshot':False,'authorizationImplemented':False,'mutationImplemented':False,
    'runtimeWired':False,'stopIsExecutorTermination':False}
INVENTORY_MIGRATION = 'inventory-service/src/main/resources/db/migration/V13__trusted_inventory_retention_clock.sql'
INTENT_FENCE_HTTP_PATHS={
    'verifier':'order-service/src/test/java/org/demo/orderservice/privacy/CommerceRetentionIntentHttpPostgresIT.java',
    'fixture':'order-service/src/test/java/org/demo/orderservice/privacy/RetentionIntentHttpFixture.java'}
INTENT_FENCE_HTTP_PLAN={
    **INTENT_FENCE_HTTP_PATHS,'remoteParticipants':7,'logicalPostgresDatabases':8,'inventoryTransportSimulated':False,
    'transport':'loopback-http-to-actual-controllers-and-authentication-filters',
    'authenticationIssuer':'fixture-introspection-not-deployed-account-service',
    'inventoryClasses':'compile-actual-sources-no-parent-or-target-classes-fallback',
    'prepareReplyLoss':'source-commit-then-unavailable-503-for-each-of-seven-sources',
    'releaseReplyLoss':'source-commit-then-unavailable-503-remains-unconfirmed-for-each-source',
    'concurrentStop':'inventory-PREPARE-and-VERIFY-with-source-response-in-flight',
    'oversizedFenceRequestStatus':413,
    'scope':'observation-leases-only','automaticRetries':False,'fullApplicationAcceptance':False,
    'authorizationImplemented':False,'mutationImplemented':False,'runtimeWired':False}
PEER_PATHS = {
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionProtocol.java',
    'fenceProtocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionFenceProtocol.java',
    'client':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionClient.java',
    'adapter':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionEvidence.java',
    'sourceConfiguration':'platform-security/src/main/java/org/demo/platformsecurity/privacy/PeerRetentionConfiguration.java',
    'rootCollector':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionPeerCollector.java',
    'rootConfiguration':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionPeerConfiguration.java',
    'rootMigration':'order-service/src/main/resources/db/migration/V30__peer_retention_receipts.sql',
}
PEER_MIGRATIONS = {owner:owner+'/src/main/resources/db/migration/V'+str(version)+'__trusted_peer_retention_clock.sql'
                   for owner,version in {'returns-service':7,'payment-service':22,'promotion-service':11,'delivery-service':10,'billing-service':17,'notification-service':19}.items()}
PEER_TABLES = {'order-service/commerce_retention_payment_evidence'}
PEER_PLAN = {**PEER_PATHS,'sourceMigrations':PEER_MIGRATIONS,'rootTables':sorted(PEER_TABLES),
    'participants':sorted(PEER_MIGRATIONS),'flag':'privacy.commerce-retention-peer-evidence.enabled','defaultEnabled':False,
    'automaticCollection':False,'clock':'source-commit-deferred-no-historical-backfill',
    'receiptBinding':'canonical-payment-event-set-hash-and-count','absenceIsConfirmed':False,
    'externalStripeSupported':False,'completedReturnEffectsSupported':False,'globalGuardsConfirmed':False,
    'redactionProofImplemented':False,'requestMaxBytes':8192,'responseMaxBytes':2048,'caller':'order-service-only',
    'customerIdentity':'stable-id-only-returns-and-billing','proofMeaning':'as-of-source-read-assessment-only',
    'restoreProtection':'retired-root-generation-and-quarantined-source-bindings','automaticExpiry':False}
INVENTORY_HTTP = {
    'protocol':'platform-security/src/main/java/org/demo/platformsecurity/privacy/InventoryRetentionProtocol.java',
    'client':'platform-security/src/main/java/org/demo/platformsecurity/privacy/InventoryRetentionClient.java',
    'sourceController':'inventory-service/src/main/java/org/demo/inventoryservice/privacy/InventoryRetentionController.java',
    'rootCollector':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionInventoryCollector.java',
    'rootConfiguration':'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionInventoryConfiguration.java',
    'flag':'privacy.commerce-retention-inventory-evidence.enabled',
    'defaultEnabled':False,'automaticCollection':False,'requestMaxBytes':8192,'responseMaxBytes':2048,
    'caller':'order-service-only','proofMeaning':'as-of-source-read-assessment-only',
}
SOURCE_OUTBOX = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionSourceOutbox.java'
SOURCE_MIGRATION = 'returns-service/src/main/resources/db/migration/V6__commerce_retention_source_outbox.sql'
PILOT_TABLES = {'returns-service/commerce_retention_source_state', 'returns-service/commerce_retention_change_outbox'}
SOURCE_EXTENSIONS = {
    'inventory-service': ('inventory-service/src/main/resources/db/migration/V12__commerce_retention_source_outbox.sql', ['inventory_reservations','inventory_reservation_items']),
    'promotion-service': ('promotion-service/src/main/resources/db/migration/V10__commerce_retention_source_outbox.sql', ['coupon_reservations','promotion_redemptions']),
    'delivery-service': ('delivery-service/src/main/resources/db/migration/V9__commerce_retention_source_outbox.sql', ['shipments']),
    'payment-service': ('payment-service/src/main/resources/db/migration/V21__commerce_retention_source_outbox.sql', ['payments','payment_refunds','payment_follow_ups','payment_order_gates','payment_outbox_events','stripe_webhook_receipts']),
    'billing-service': ('billing-service/src/main/resources/db/migration/V16__commerce_retention_source_outbox.sql', ['invoices','invoice_items','invoice_corrections','invoice_outbox_events','event_inbox','event_identity_aliases']),
    'notification-service': ('notification-service/src/main/resources/db/migration/V18__commerce_retention_source_outbox.sql', ['notifications','notification_dead_letters','event_inbox','event_identity_aliases']),
}
SOURCE_TABLES = PILOT_TABLES | {owner+'/'+table for owner in SOURCE_EXTENSIONS
                              for table in ('commerce_retention_source_state','commerce_retention_change_outbox')}
INVALIDATION_CONTROLLER = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionInvalidationController.java'
INVALIDATION_CLIENT = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionInvalidationClient.java'
RESTORE = 'scripts/backup/privacy-restore.py'
HTTP_PROTOCOL = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionBindingProtocol.java'
HTTP_CLIENT = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionBindingClient.java'
SOURCE_CONTROLLER = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionSourceController.java'
SOURCE_CONFIGURATION = 'platform-security/src/main/java/org/demo/platformsecurity/privacy/CommerceRetentionSourceConfiguration.java'
BINDING_CONFIGURATION = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionBindingConfiguration.java'
BINDING_PUBLISHER = 'order-service/src/main/java/org/demo/orderservice/privacy/CommerceRetentionBindingPublisher.java'
ROUTING = 'platform-security/src/main/java/org/demo/platformsecurity/RoutePolicy.java'
spec = importlib.util.spec_from_file_location('commerce_classification', Path(__file__).with_name('technical-data-classification-check.py'))
classification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(classification)
require = classification.lifecycle.require

REDACTIONS = {
    'order-service/orders': {'customer_email', 'delivery_first_name', 'delivery_last_name', 'delivery_street',
                             'delivery_postal_code', 'delivery_city', 'delivery_country', 'delivery_phone',
                             'delivery_date', 'delivery_slot', 'pickup_location'},
    'order-service/order_items': {'product_name'},
    'delivery-service/shipments': {'sync_error'},
    'inventory-service/inventory_reservations': set(),
    'inventory-service/inventory_reservation_items': set(),
    'promotion-service/coupon_reservations': set(),
    'promotion-service/promotion_redemptions': set(),
    'returns-service/return_requests': {'customer_email', 'reason', 'description', 'moderation_note'},
    'returns-service/return_events': {'actor_ref', 'note'},
    'returns-service/return_lines': {'product_name'},
}
PROTOCOL = {
    'order-service/orders': {'checkout_principal', 'checkout_request_hash', 'collection_reference',
                             'guest_access_token_hash', 'idempotency_key', 'delivery_tracking_number',
                             'promotion_code', 'inventory_reservation_id', 'refund_payment_id', 'deferred_payment_id'},
    'delivery-service/shipments': {'tracking_number', 'sync_token'},
    'promotion-service/coupon_reservations': {'code'},
    'promotion-service/promotion_redemptions': {'code'},
    'returns-service/return_requests': {'request_hash'},
    'returns-service/return_events': {'operation_id'},
}
REVIEW = {}
FINANCIAL_TABLES = {'billing-service/invoices', 'billing-service/invoice_items',
                    'billing-service/invoice_corrections', 'payment-service/payments', 'payment-service/payment_refunds'}
PARTICIPANTS = {'order-service', 'returns-service', 'payment-service', 'inventory-service',
                'promotion-service', 'delivery-service', 'billing-service', 'notification-service'}
SOURCE_IMPORTS = {owner:owner+'/src/main/java/org/demo/'+owner.removesuffix('-service')+'service/'+owner.removesuffix('-service').capitalize()+'ServiceApplication.java'
                  for owner in PARTICIPANTS-{'order-service'}}
STATE_SOURCES = {
    'order-service': 'order-service/src/main/java/org/demo/orderservice/privacy/OrderPrivacySource.java',
    'returns-service': 'returns-service/src/main/java/org/demo/returnsservice/privacy/ReturnsCommerceCleanup.java',
    'inventory-service': 'inventory-service/src/main/java/org/demo/inventoryservice/privacy/InventoryCommerceCleanup.java',
    'promotion-service': 'promotion-service/src/main/java/org/demo/promotionservice/privacy/PromotionCommerceCleanup.java',
    'delivery-service': 'delivery-service/src/main/java/org/demo/deliveryservice/privacy/DeliveryCommerceCleanup.java',
}


def source(root, name):
    return classification.lifecycle.source(root, name)


def check(root=ROOT, actual=None):
    root = Path(root).resolve()
    doc = classification.lifecycle.document(source(root, CONTRACT))
    receipt = classification.lifecycle.document(source(root, RECEIPT))
    require(doc.get('schemaVersion') == 1 and doc.get('stage') == 'A43.40' and doc.get('decision') == 'D02/P02', 'Invalid commerce plan')
    require(doc.get('mode') == 'assessment-only' and doc.get('runtimeWired') is False
            and doc.get('activationAllowed') is False, 'Commerce assessment cannot enable cleanup')
    require(doc.get('assessor') == ASSESSOR and doc.get('decisionReceipt') == RECEIPT, 'Unreviewed commerce source')
    require(doc.get('accountScope') == receipt['decisions']['D02']['accounts'], 'Active-account scope changed')
    require(doc.get('ages') == {'redactionDays': 365, 'minimumDays': 90}
            and receipt['decisions']['D02']['redactAfterDays'] == 365
            and receipt['decisions']['D02']['minimumAgeDays'] == 90, 'Unreviewed commerce periods')
    require(doc.get('clocks') == {'redaction': 'latest-confirmed-reconciliation-of-entire-chain',
                                  'minimum': 'latest-actual-confirmed-redaction-of-entire-chain'}, 'Commerce clock changed')
    require(set(doc.get('requiredParticipants', [])) == PARTICIPANTS
            and len(doc['requiredParticipants']) == len(PARTICIPANTS), 'Missing/duplicate commerce participant')
    require(doc.get('protocolHasAutomaticExpiry') is False and doc.get('financialAction') == 'protect-D01-no-age-delete',
            'Replay markers or financial records acquired expiry')
    require(set(doc.get('financialTables', [])) == FINANCIAL_TABLES
            and len(doc['financialTables']) == len(FINANCIAL_TABLES), 'Financial exclusion scope changed')
    actual = actual if actual is not None else classification.schema(root)
    tables = doc.get('tables', {})
    require(set(tables) == set(REDACTIONS), 'Missing/new D02 table')
    counts = {'redact': 0, 'minimum': 0, 'protocol': 0, 'review': 0}
    for name, table in tables.items():
        require(table.get('owner') == name.split('/')[0], 'Wrong D02 field owner')
        groups = table.get('fields', {})
        require(set(groups) == set(counts), 'Missing D02 field category')
        flattened = []
        for category, fields in groups.items():
            require(isinstance(fields, list) and all(isinstance(field, str) for field in fields), 'Invalid field list')
            flattened.extend(fields)
            counts[category] += len(fields)
        require(len(flattened) == len(set(flattened)) and set(flattened) == set(actual[name]), 'Missing/duplicate/new D02 column')
        require(set(groups['redact']) == REDACTIONS[name], 'Unreviewed personal-field redaction scope')
        require(set(groups['protocol']) == PROTOCOL.get(name, set()), 'Protected access/replay scope changed')
        require(set(groups['review']) == REVIEW.get(name, set()), 'Field scope requires a reviewed decision')
    for name in FINANCIAL_TABLES:
        require(name in actual and name not in tables, 'Financial data included in D02 mutation plan')
    states = doc.get('stateReview', {})
    require(set(states) == set(STATE_SOURCES), 'Missing business-state review')
    for owner, path in STATE_SOURCES.items():
        require(states[owner].get('source') == path and states[owner].get('positiveReconciliationProofRequired') is True,
                'A business status cannot substitute for reconciliation proof')
    require(states['returns-service'].get('completedWithoutEffectsReconciliation') == 'manual-review-not-eligible',
            'Unimplemented return effects cannot qualify')
    require(doc.get('proofStorage') == 'order-metadata-store-no-source-adapters'
            and doc.get('assessmentExecutor') == 'bounded-unscheduled-metadata-only'
            and doc.get('proofStore') == STORE and doc.get('proofStoreMigration') == STORE_MIGRATION
            and set(doc.get('proofStoreTables', [])) == STORE_TABLES
            and doc.get('mutationWorker') == 'not-implemented', 'Unverified runtime implementation claim')
    require(STORE_TABLES <= actual.keys(), 'Durable assessment tables missing from schema review')
    require(COORDINATOR_TABLES <= actual.keys(), 'Durable coordinator table missing from schema review')
    require(ORDER_TABLES <= actual.keys(), 'Order milestone metadata missing from schema review')
    require(doc.get('orderEvidence') == {'adapter':ORDER_EVIDENCE,'migration':ORDER_MIGRATION,'tables':sorted(ORDER_TABLES),
            'participant':'ORDER-only','clock':'tracked-local-close-only-no-historical-backfill','retentionStart':'latest-of-eight-confirmed-participants',
            'globalGuardsConfirmed':False,'redactionProofImplemented':False,'runtimeWired':False,'restoreBlocked':True,'automaticExpiry':False},
            'Local Order milestone cannot imply a global proof, historical clock or activation')
    require(doc.get('bindingCoordinator') == {'store':COORDINATOR,'migration':COORDINATOR_MIGRATION,
            'tables':sorted(COORDINATOR_TABLES),'mode':'bounded-default-disabled-metadata-only',
            'transport':'authenticated-http-default-disabled','expectedEpoch':'retained-receipts-only',
            'commandsPerGeneration':7,'runtimeWired':False,'automaticPublisherWired':True,
            'positiveProofsAccepted':False,'restoreBlocked':True,'automaticExpiry':False},
            'Coordinator metadata cannot imply positive proofs or runtime activation')
    require(doc.get('bindingHttp') == {'protocol':HTTP_PROTOCOL,'client':HTTP_CLIENT,'controller':SOURCE_CONTROLLER,
            'sourceConfiguration':SOURCE_CONFIGURATION,'rootConfiguration':BINDING_CONFIGURATION,'rootPublisher':BINDING_PUBLISHER,'routing':ROUTING,
            'sourceImports':SOURCE_IMPORTS,'responseMaxBytes':2048,'scopeEchoRequired':True,'serviceAuthentication':True,
            'bindCaller':'order-service-only','invalidationCallers':'seven-sources-only','redirectsAllowed':False,
            'defaultEnabled':False,'deploymentActivated':False,'preparesNewGenerations':False,'positiveProofsAccepted':False},
            'Unreviewed binding HTTP scope or activation claim')
    for path in SOURCE_IMPORTS.values():
        require('org.demo.platformsecurity.privacy.CommerceRetentionSourceConfiguration.class' in source(root,path).read_text(encoding='utf-8'),
                'Source is missing its reviewed opt-in configuration import')
    require(doc.get('inventoryEvidence') == {'adapter':INVENTORY_EVIDENCE,'migration':INVENTORY_MIGRATION,
            'table':'inventory-service/commerce_retention_source_state','clockFields':['local_open_seen','local_reconciled_at'],
            'clock':'tracked-local-close-only-no-historical-backfill','scope':'binding-generation-version-phase-epoch-revision',
            'expectedReservation':'trusted-order-id-terminal-and-exact-lines','maxLines':100,
            'localObservationOnly':True,'rootAcceptanceWired':True,'runtimeWired':False,
            'globalGuardsConfirmed':False,'redactionProofImplemented':False,'restoreBlocked':True,'automaticExpiry':False},
            'Inventory observation cannot imply root proof acceptance, global clearance or historical clocks')
    require(doc.get('inventoryHttp')==INVENTORY_HTTP,'Unreviewed Inventory proof transport, caller, limits or activation')
    require(doc.get('peerEvidence')==PEER_PLAN,'Unreviewed peer proof, clock, receipt dependency or activation scope')
    require(PEER_TABLES <= actual.keys(),'Peer receipt metadata missing from review')
    require(doc.get('knownPurchaseHolds')==HOLD_PLAN,'Unreviewed hold scope, clearance, release or activation claim')
    require(doc.get('knownSourceHolds')==SOURCE_HOLD_PLAN,'Source holds cannot imply complete coverage, release, expiry or uncontrolled revisions')
    require(SOURCE_HOLD_TABLES<=actual.keys(),'Source hold registry missing from review')
    require(doc.get('localHoldIntake')==HOLD_INTAKE_PLAN,'Local hold intake cannot imply verified cases, remote delivery, authorization or partial commit')
    require(doc.get('explicitHoldDelivery')==HOLD_DELIVERY_PLAN,'Hold delivery cannot authorize cleanup, assume acknowledgements, reset budget or bypass scope/restore')
    require(doc.get('holdDeliveryProgress')==HOLD_PROGRESS_PLAN,'Hold progress must remain bounded scoped historical read without dispatch, budget reset or mutation authority')
    require(doc.get('holdReconciliation')==HOLD_RECONCILIATION_PLAN,'Hold reconciliation must be challenged scoped as-of read without history rewrites, repair, quarantine bypass or global authority')
    require(doc.get('holdReconciliationHistory')==HOLD_HISTORY_PLAN,'Hold history must preserve bounded immutable probes, pending uncertainty and quarantine without reset, redispatch or authority')
    require(doc.get('holdRepairAssessment')==HOLD_REPAIR_ASSESSMENT_PLAN,'Repair assessment must preserve pending/discrepancy history and exact bounded scope; candidate is never execution authority')
    require(doc.get('holdRecoveryPlanning')==HOLD_RECOVERY_PLANNING_PLAN,'Recovery planning requires immutable exact scoped supersession/intents, SQL late-result protection and no termination/repair authority')
    require(doc.get('sourceHoldRestoration')==HOLD_RESTORATION_PLAN,'Source restoration requires signed scoped case authority and atomic CAS/original UUID/receipt, without root execution or global authority')
    require(doc.get('rootHoldRestorationDispatch')==HOLD_DISPATCH_PLAN,'Root restoration dispatch requires committed immutable attempts, replay-only recovery, exact scoped authority and no activation')
    require(doc.get('holdRestorationProgress')==HOLD_RESTORATION_PROGRESS_PLAN,'Restoration progress requires independent history/as-of-read dimensions, bounded fresh probes and no pending resolution or authority')
    require(doc.get('holdRestorationReview')==HOLD_RESTORATION_REVIEW_PLAN,'Seven-source review requires all bounded intents, distinct probe counters, final freshness and no distributed clearance')
    require(doc.get('holdReviewSteps')==HOLD_REVIEW_STEP_PLAN,'Unreviewed version-bound inspection plan')
    require(HOLD_REVIEW_STEP_TABLES<=actual.keys(),'Version-bound inspection table missing from review')
    require(doc.get('holdReviewVersions')==HOLD_REVIEW_VERSION_PLAN,'Review versions require immutable server records, scoped canonical digests, bounded lifetime and no execution authority')
    require(HOLD_REVIEW_VERSION_TABLES<=actual.keys(),'Review version registry missing from review')
    require(HOLD_DISPATCH_TABLES<=actual.keys(),'Root restoration dispatch history missing from review')
    require(HOLD_RESTORATION_TABLES<=actual.keys(),'Source hold restoration receipts missing from review')
    restoration_sql=[(root/p).read_bytes() for p in HOLD_RESTORATION_MIGRATIONS.values()]
    require(len(set(restoration_sql))==1,'Source restoration receipt migrations must remain identical')
    require(HOLD_RECOVERY_PLANNING_TABLES<=actual.keys(),'Hold recovery plans missing from review')
    require(HOLD_HISTORY_TABLES<=actual.keys(),'Hold probe history missing from review')
    require(HOLD_DELIVERY_TABLES<=actual.keys(),'Hold delivery history missing from review')
    require(HOLD_INTAKE_TABLES<=actual.keys(),'Local hold intake metadata missing from review')
    template=source(root,SOURCE_HOLD_MIGRATIONS['RETURNS']).read_bytes().replace(b'\r\n',b'\n')
    require(all(source(root,p).read_bytes().replace(b'\r\n',b'\n')==template for p in SOURCE_HOLD_MIGRATIONS.values()),'Unreviewed source hold migration divergence')
    require(doc.get('targetedWorkflow')==WORKFLOW_PLAN,'Unreviewed workflow scope, lineage, lease, transport or activation claim')
    require(doc.get('claimSelection')==CLAIM_PLAN,'Unreviewed claim selection, locks, time, scale or activation claim')
    require(doc.get('fieldScopeReview')==FIELD_SCOPE_PLAN,'Unreviewed field scope, provenance or clearance claim')
    require(doc.get('revalidation')==REVALIDATION_PLAN,'Unreviewed revalidation scope, budget, concurrency or mutation claim')
    canonical=lambda value:json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True)
    definition={'schemaVersion':1,'guard':'FIELD_SCOPE_REVIEWED','policy':'D02-P02-2026-10-30.1',
        'decisionReceiptVersion':receipt['receiptVersion'],
        'decisionD02Sha256':hashlib.sha256(canonical(receipt['decisions']['D02']).encode()).hexdigest(),
        **{k:doc[k] for k in ('accountScope','ages','clocks','requiredParticipants','financialTables','tables')}}
    expected=(canonical(definition)+'\n').encode()
    require(source(root,FIELD_SCOPE_PATHS['definition']).read_bytes().replace(b'\r\n',b'\n')==expected,
            'Packaged field scope differs from reviewed D02 decisions or field map')
    definition_hash=hashlib.sha256(expected).hexdigest()
    require(re.search(r'\bSHA256\s*=\s*"'+definition_hash+'"',source(root,FIELD_SCOPE_PATHS['verifier']).read_text(encoding='utf-8')),
            'Runtime field-scope digest differs from reviewed definition')
    preservation_def = {
        'accounts': doc['accountScope'],
        'decisionD02Sha256': hashlib.sha256(canonical(receipt['decisions']['D02']).encode()).hexdigest(),
        'decisionReceiptVersion': receipt['receiptVersion'],
        'financialExceptionsProtected': True,
        'guard': 'PRESERVATION_POLICY_REVIEWED',
        'minimumDays': doc['ages']['minimumDays'],
        'policy': 'D02-P02-2026-10-30.1',
        'redactionDays': doc['ages']['redactionDays'],
        'schemaVersion': 1,
    }
    expected_preservation = (canonical(preservation_def) + '\n').encode()
    require(source(root, GLOBAL_GUARD_PATHS['reviewDefinition']).read_bytes().replace(b'\r\n', b'\n') == expected_preservation,
            'Packaged preservation review differs from reviewed D02 decisions')
    preservation_hash = hashlib.sha256(expected_preservation).hexdigest()
    require(re.search(r'\bSHA256\s*=\s*"' + preservation_hash + '"', source(root, GLOBAL_GUARD_PATHS['reviewVerifier']).read_text(encoding='utf-8')),
            'Runtime preservation-review digest differs from reviewed definition')
    require(doc.get('globalGuardCollection') == {
        'scope':'order-local-negative-observations-only', 'providerConfirmationsAccepted':False,
        'providerCallsInsideSqlTransaction':False, 'callbackFailure':'UNKNOWN',
        'snapshot':'subject-generation-phase-policy-proof-process-hold-revisions',
        'policyReceiptIsCompleteApproval':False, 'journalInclusionIsCurrentHead':False,
        'historicalPolicyAndJournalConfirmations':'downgrade-to-UNKNOWN-without-rewriting-history',
        'executionAllowed':False}, 'Global guard collection cannot imply complete policy, current journal or global clearance')
    require(doc.get('sourceGuardObservations')==SOURCE_GUARD_PLAN,'Source guard observations cannot imply positive/global clearance, replay acceptance or activation')
    require(doc.get('sourceObservationReceipts')==OBSERVATION_RECEIPT_PLAN,'Historical receipts cannot become current authority, grow per retry, or be overwritten')
    require(OBSERVATION_RECEIPT_TABLE in actual,'Historical observation receipt table missing from review')
    require(doc.get('intentRegistry')==INTENT_PLAN,'Intent registration cannot imply authorization, execution, stop acknowledgement or automatic resume')
    require(INTENT_TABLES<=actual.keys(),'Intent registry metadata missing from schema review')
    require(doc.get('observationRoundHistory')==ROUND_PLAN,'Observation round history cannot expire, authorize execution, resume sources or confuse TTL with acknowledgement')
    require(ROUND_TABLES<=actual.keys(),'Observation round history missing from schema review')
    require(doc.get('sourceFenceReplayProtection')==FENCE_REPLAY_PLAN,'Released source tokens cannot regain leases, expire automatically or imply recovery/mutation authority')
    require(FENCE_REPLAY_TABLES<=actual.keys(),'Source fence replay markers missing from schema review')
    require(doc.get('observationRoundRecovery')==ROUND_RECOVERY_PLAN,'Recovery cannot resume PREPARE, bypass owner/restore checks, infer acknowledgement or execute mutations')
    require(doc.get('intentEvidenceReview')==INTENT_REVIEW_PLAN,'Intent assessment cannot become authorization, ignore stop/scope changes or detach claim from its final check')
    require(doc.get('intentObservationFences')==INTENT_FENCE_PLAN,'Intent observation leases cannot authorize mutation, hide uncertain cleanup or omit releases after failed PREPARE')
    require(doc.get('intentObservationFenceHttp')==INTENT_FENCE_HTTP_PLAN,'Local HTTP fixtures cannot imply deployed acceptance, simulated Inventory or successful release without acknowledgement')
    require(HOLD_TABLES <= actual.keys(),'Purchase hold metadata missing from review')
    for owner in PEER_MIGRATIONS:
        require('org.demo.platformsecurity.privacy.PeerRetentionConfiguration.class' in source(root,SOURCE_IMPORTS[owner]).read_text(encoding='utf-8'),'Missing opt-in peer evidence import')
    require(SOURCE_TABLES <= actual.keys(), 'Durable source tables missing from schema review')
    require(doc.get('sourceOutboxPilot') == {'owner': 'returns-service', 'store': SOURCE_OUTBOX,
            'migration': SOURCE_MIGRATION, 'tables': sorted(PILOT_TABLES),
            'capture': 'transactional-parent-lines-events', 'binding': 'immutable-expected-epoch-cas',
            'publisher': 'bounded-default-disabled', 'runtimeWired': False, 'coordinatorWired': True,
            'positiveProofsAccepted': False, 'restoreBlocked': True, 'automaticExpiry': False},
            'Source outbox pilot cannot imply global integration, proof acceptance or release')
    extensions=doc.get('sourceOutboxExtensions',{})
    require(set(extensions)==set(SOURCE_EXTENSIONS),'Missing or unreviewed source outbox owner')
    protocol=source(root,SOURCE_MIGRATION).read_text(encoding='utf-8').split('CREATE FUNCTION capture_')[0]
    for owner,(migration,captured) in SOURCE_EXTENSIONS.items():
        require(extensions[owner]=={'migration':migration,'capturedTables':captured,'runtimeWired':False,
                'positiveProofsAccepted':False,'restoreBlocked':True,'automaticExpiry':False},
                'Source extension cannot change capture scope or enable cleanup')
        require(source(root,migration).read_text(encoding='utf-8').split('CREATE FUNCTION capture_')[0]==protocol,
                'Source binding/outbox protocol differs from the reviewed implementation')
    require(doc.get('sourceInvalidation') == {'controller': INVALIDATION_CONTROLLER, 'client': INVALIDATION_CLIENT,
            'defaultEnabled': False, 'producersWired': True, 'positiveProofsAccepted': False},
            'Invalidation transport cannot imply source integration or proof acceptance')
    require(doc.get('restoreInvalidation') == {'source': RESTORE, 'scope': 'owned-isolated-restore-only',
            'allAccountsAndGuests': True, 'emptyJournalIncluded': True, 'receiptsPreserved': True, 'releaseAllowed': False},
            'Restore invalidation cannot release an environment or omit restored accounts')
    hashes = doc.get('sourceHashes', {})
    migrations = {p.relative_to(root).as_posix() for owner in PARTICIPANTS
                  for p in (root / owner / 'src/main/resources/db/migration').glob('V*.sql')}
    required = migrations | set(STATE_SOURCES.values()) | {ASSESSOR, RECEIPT, STORE, STORE_MIGRATION,
                                                         INVALIDATION_CONTROLLER, INVALIDATION_CLIENT, RESTORE,
                                                         SOURCE_OUTBOX, SOURCE_MIGRATION}
    required |= {COORDINATOR,COORDINATOR_MIGRATION}
    required |= {ORDER_EVIDENCE,ORDER_MIGRATION}
    required |= {HOLD_STORE,HOLD_MIGRATION}
    required |= {WORKFLOW,WORKFLOW_MIGRATION}
    required |= {CLAIM_MIGRATION,CLAIM_VERIFIER}
    required |= set(FIELD_SCOPE_PATHS.values())
    required |= set(REVALIDATION_VERIFIERS)
    required |= set(INTENT_FENCE_HTTP_PATHS.values())
    required |= set(ROUND_PATHS.values())
    required |= {FENCE_REPLAY_ADAPTER} | set(FENCE_REPLAY_MIGRATIONS.values())
    required |= set(ROUND_RECOVERY_PATHS.values())
    required |= set(SOURCE_HOLD_PATHS.values())
    required |= set(HOLD_INTAKE_PATHS.values())
    required |= set(HOLD_DELIVERY_PATHS.values())
    required |= set(HOLD_PROGRESS_PATHS.values())
    required |= set(HOLD_RECONCILIATION_PATHS.values())
    required |= set(HOLD_HISTORY_PATHS.values())
    required |= set(HOLD_REPAIR_ASSESSMENT_PATHS.values())
    required |= set(HOLD_RECOVERY_PLANNING_PATHS.values())
    required |= set(HOLD_REVIEW_STEP_PATHS.values())
    required |= set(HOLD_REVIEW_VERSION_PATHS.values())
    required |= set(HOLD_RESTORATION_REVIEW_PATHS.values())
    required |= set(HOLD_RESTORATION_PROGRESS_PATHS.values())
    required |= set(HOLD_DISPATCH_PATHS.values())
    required |= set(HOLD_RESTORATION_PATHS.values()) | set(HOLD_RESTORATION_MIGRATIONS.values())
    required |= set(SOURCE_HOLD_MIGRATIONS.values())
    required |= {INVENTORY_EVIDENCE,INVENTORY_MIGRATION}
    required |= {INVENTORY_HTTP[k] for k in ('protocol','client','sourceController','rootCollector','rootConfiguration')}
    required |= set(PEER_PATHS.values()) | set(PEER_MIGRATIONS.values())
    required |= {HTTP_PROTOCOL,HTTP_CLIENT,SOURCE_CONTROLLER,SOURCE_CONFIGURATION,BINDING_CONFIGURATION,BINDING_PUBLISHER,ROUTING} | set(SOURCE_IMPORTS.values())
    required |= {entry[0] for entry in SOURCE_EXTENSIONS.values()}
    required |= set(GLOBAL_GUARD_PATHS.values()) | set(SOURCE_GUARD_PATHS.values()) | set(OBSERVATION_RECEIPT_PATHS.values()) | set(INTENT_PATHS.values())
    require(required <= hashes.keys(), 'Missing reviewed D02 source fingerprint')
    for path, digest in hashes.items():
        require(hashlib.sha256(source(root, path).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == digest, 'Unreviewed D02 source change: ' + path)
    return {'status': 'PASS', 'tables': len(tables), 'columns': sum(counts.values()), 'fields': counts,
            'financialTablesExcluded': len(FINANCIAL_TABLES), 'participantsRequired': len(PARTICIPANTS),
            'mode': 'assessment-only', 'durableMetadataTables': len(STORE_TABLES),
            'coordinatorMetadataTables':len(COORDINATOR_TABLES),'coordinatorMode':'authenticated-http-default-disabled',
            'orderEvidenceMetadataTables':len(ORDER_TABLES),'localOrderEvidenceImplemented':True,'globalProofCollectionWired':False,
            'localInventoryObservationImplemented':True,'inventoryRootAcceptanceWired':True,'inventoryAutomaticCollection':False,
            'peerSourceAdaptersImplemented':6,'peerRootAcceptanceImplemented':True,'peerAutomaticCollection':False,
            'knownPurchaseHoldTables':len(HOLD_TABLES),'knownPurchaseHoldsImplemented':True,'completeHoldInventory':False,
            'knownSourceHoldTables':len(SOURCE_HOLD_TABLES),'knownSourceHoldsImplemented':True,'knownSourceHoldSources':list(SOURCE_HOLD_MIGRATIONS),
            'localHoldIntakeImplemented':True,'localHoldIntakeTables':len(HOLD_INTAKE_TABLES),'holdIntakeSourcePlanSize':7,
            'holdIntakeSourceDeliveryImplemented':False,'completeCaseWorkflowImplemented':False,
            'explicitHoldDeliveryImplemented':True,'holdDeliveryAcceptanceVerified':None,'holdDeliveryAutomaticScheduling':False,
            'holdProgressImplemented':True,'holdProgressAcceptanceVerified':None,'holdProgressCurrentCoverage':False,
            'holdReconciliationImplemented':True,'holdReconciliationAcceptanceVerified':None,'holdRepairImplemented':False,
            'holdReconciliationHistoryImplemented':True,'holdHistoryAcceptanceVerified':None,'holdHistoryAutomaticResume':False,
            'holdRepairAssessmentImplemented':True,'holdRepairAssessmentAcceptanceVerified':None,'holdRepairExecutionImplemented':False,
            'holdRecoveryPlanningImplemented':True,'holdRecoveryPlanningAcceptanceVerified':None,'holdResolutionIsExecutorTermination':False,
            'sourceHoldRestorationImplemented':True,'sourceHoldRestorationAcceptanceVerified':None,'rootHoldRestorationDispatcherImplemented':True,'rootHoldRestorationDispatchAcceptanceVerified':None,'rootHoldRestorationAutomaticResume':False,
            'holdRestorationProgressImplemented':True,'holdRestorationProgressAcceptanceVerified':None,'holdRestorationProgressGrantsAuthority':False,
            'holdRestorationReviewImplemented':True,'holdRestorationReviewAcceptanceVerified':None,'holdRestorationReviewDistributedSnapshot':False,
            'holdReviewStepsImplemented':True,'holdReviewStepsAcceptanceVerified':None,'holdReviewStepsGrantExecution':False,
            'holdReviewVersionsImplemented':True,'holdReviewVersionsAcceptanceVerified':None,'holdReviewVersionsGrantExecution':False,
            'targetedWorkflowImplemented':True,'workflowSourceCallsPerStep':1,'distributedWriteFenceImplemented':False,
            'claimSelectionIndexed':True,'claimScaleFixtureJobs':100000,'constantClaimWorkForAllScopeCounts':False,
            'fieldScopeReviewImplemented':True,'fieldScopeDefinitionSha256':definition_hash,
            'fieldScopeDeployedSchemaVerified':False,'globalPreservationReviewed':False,
            'preservationReviewSha256':preservation_hash,'globalGuardWritesImplemented':True,
            'globalGuardEvidenceSource':GLOBAL_GUARD_PATHS['guardEvidence'],
            'globalGuardCollectionScope':'order-local-negative-observations-only',
            'unscopedProviderConfirmationsAccepted':False,'journalCurrentHeadVerified':False,
            'negativeGuardSourceAdapters':6,'negativeGuardHttpDefaultEnabled':False,
            'negativeGuardGlobalCoverageComplete':False,
            'negativeGuardHttpAcceptanceVerified':None,'negativeGuardSqlRecoveryVerified':None,
            'historicalSourceObservationReceiptTables':1,'historicalObservationIsAuthority':False,
            'intentRegistryTables':2,'intentAuthorizationImplemented':False,'intentStopIsExecutorTermination':False,
            'intentBoundReviewImplemented':True,'intentReviewAtomicityVerified':None,'intentReviewIsAuthorization':False,
            'intentObservationFencesImplemented':True,'intentFenceCleanupAcceptanceVerified':None,'intentFencesAuthorizeMutation':False,
            'intentFenceSevenSourceHttpFixtureImplemented':True,'intentFenceSevenSourceHttpAcceptanceVerified':None,'deployedFenceRoundAccepted':False,
            'observationRoundHistoryTables':2,'observationHistoryAcceptanceVerified':None,'observationRecoverySourceRequestsImplemented':True,
            'releaseOnlyRecoveryImplemented':True,'releaseOnlyRecoveryAcceptanceVerified':None,'automaticRecoveryScheduling':False,
            'sourceFenceReplayMarkerTables':7,'sourceFenceReplayProtectionImplemented':True,'sourceFenceReplayAcceptanceVerified':None,
            'revalidationRoundImplemented':True,'maxRevalidationRemoteCalls':7,'globalSnapshotImplemented':False,
            'sourceOutboxTables': len(SOURCE_TABLES), 'sourceOutboxPilot': 'returns-service-unscheduled',
            'sourceOutboxOwners': sorted({'returns-service',*SOURCE_EXTENSIONS}),
            'sourceAdaptersWired': False, 'runtimeWired': False, 'activationAllowed': False,
            'invalidationTransport': 'default-disabled-source-only', 'invalidationProducersWired': True,
            'bindingHttpImplemented':True,'publisherDeploymentActivated':False,
            'restoreInvalidationScope': 'owned-isolated-restore-only',
            'executedDataOperations': 0,
            'blockers': ['global-guards-and-conservative-unknown-history-provider-and-return-scopes', 'reviewed-generation-preparation-and-deployment-acceptance',
                         'guard-and-journal-evidence-collection', 'redaction-migrations-and-worker',
                         'dependency-release-and-minimum-removal-worker', 'deployment-restore-quarantine-and-fresh-source-evidence',
                         'cross-service-cleanup-concurrency-and-restore-acceptance']}


if __name__ == '__main__':
    # Use the complete parent gate for CLI calls too, including the pinned user
    # decisions, candidate binding, safe errors, and the NOT_READY exit status.
    parent_spec = importlib.util.spec_from_file_location('commerce_parent_gate', Path(__file__).with_name('retention-policy-check.py'))
    parent = importlib.util.module_from_spec(parent_spec)
    parent_spec.loader.exec_module(parent)
    raise SystemExit(parent.main())
