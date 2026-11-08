import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
import test_data_lifecycle as fixtures

spec=importlib.util.spec_from_file_location('technical_classification',fixtures.ROOT/'scripts/qa/technical-data-classification-check.py')
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class FieldParserTests(unittest.TestCase):
    def test_nested_constraints_and_quoted_columns_are_not_split_as_fields(self):
        result=gate.apply_columns({},'CREATE TABLE x ("id" BIGINT PRIMARY KEY, amount NUMERIC(19,2) CHECK(amount IN (1,2)), note TEXT DEFAULT \'a,b;\', CONSTRAINT u UNIQUE(id,amount));')
        self.assertEqual({'x':{'id','amount','note'}},result)
    def test_alter_rename_and_drop_preserve_only_current_columns(self):
        sql='CREATE TABLE x(id INT,a TEXT); ALTER TABLE x ADD COLUMN b INT, ADD CONSTRAINT u UNIQUE(id,b); ALTER TABLE x RENAME COLUMN a TO title; ALTER TABLE x ALTER COLUMN b TYPE BIGINT; ALTER TABLE x DROP COLUMN title; ALTER TABLE x RENAME TO y;'
        self.assertEqual({'y':{'id','b'}},gate.apply_columns({},sql))
    def test_optional_add_is_supported_without_duplicate_classification(self):
        self.assertEqual({'x':{'id'}},gate.apply_columns({},'CREATE TABLE x(id INT); ALTER TABLE x ADD COLUMN IF NOT EXISTS id BIGINT;'))
    def test_opaque_table_creation_and_like_require_parser_review(self):
        for sql in ['CREATE TABLE x AS SELECT 1;','CREATE TABLE x (LIKE y);',"DO $$ BEGIN CREATE TABLE x(id INT); END $$;"]:
            with self.subTest(sql=sql),self.assertRaises(ValueError):gate.apply_columns({},sql)
    def test_unknown_actions_and_columns_fail_closed(self):
        for sql in ['ALTER TABLE x ENABLE ROW LEVEL SECURITY;','ALTER TABLE x ALTER COLUMN missing TYPE INT;','ALTER TABLE x ADD COLUMN id INT;']:
            with self.subTest(sql=sql),self.assertRaises(ValueError):gate.apply_columns({'x':{'id'}},sql)
    def test_literals_cannot_smuggle_storage_into_the_inventory(self):
        self.assertEqual({'x':{'id'}},gate.apply_columns({},"SELECT 'CREATE TABLE secret(id INT)'; -- CREATE TABLE ignored(id INT)\n CREATE TABLE x(id INT);"))

class ClassificationContractTests(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.DataLifecycleTests('test_minimal_complete_inventory_passes');self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.root=self.fixture.root;self.fixture.write('docs/procedure.md','Reviewed fixture-only procedure.')
        self.path='sample-service/src/main/java/example/export/Export.java'
        self.fixture.write(self.path,'class Export { Object x = new Export.Omission("privateFields","excluded"); }')
        rules={}
        for key,decision in [('export','exported'),('secret','excluded-secret'),('manual','manual-review'),('operational','excluded-operational')]:
            rules[key]={'decision':decision,'reason':'Reviewed fixture reason','ownership':'Verified fixture subject','sources':['sources/evidence.txt'],'procedure':'docs/procedure.md'}
        identities={}
        for n in range(4):
            name=f'sources/identity{n}.java';self.fixture.write(name,'fixture identity protocol');identities[name]=hashlib.sha256((self.root/name).read_bytes()).hexdigest()
        self.contract={'schemaVersion':1,'stage':'A43.30','decision':'existing-business-projections-plus-manual-review-no-technical-state-export','rules':rules,
            'tables':{'sample-service/customers':{'owner':'sample-service','ownership':'Verified id','sources':[self.fixture.migration],'columns':{'id':'export','password_hash':'secret'}}},
            'redis':{'redis-fixture':{'owners':['sample-service'],'rule':'secret','ownership':'Verified subject','reason':'No raw challenge export'}},
            'copies':{kind:{'owners':['sample-service'],'rule':'secret','ownership':'Not a raw customer dump','reason':'Fixture secret protection'} for kind in ('backup','runtime','browser','external')},
            'omissions':{self.path:[{'pair':['privateFields','excluded'],'rule':'secret','reason':'Credential boundary'}]},
            'projectionSources':gate.projection_sources(self.root),
            'identityMigration':{'stage':'A43.36','implemented':False,'plan':'docs/procedure.md'},'identitySources':identities,
            'deduplicationConstraints':{'sample-service/customers':[['id']]}}
        self.save()
    def save(self):self.fixture.write(gate.CONTRACT,json.dumps(self.contract))
    def reject(self,message):
        self.save()
        with self.assertRaisesRegex(ValueError,message):gate.check(self.root)
    def test_minimal_reviewed_contract_passes_without_data_operations(self):
        result=gate.check(self.root);self.assertEqual(2,result['columns']);self.assertEqual(0,result['executedDataOperations']);self.assertFalse(result['fullA43Complete'])
    def test_restore_policy_source_drift_requires_review_and_cannot_authorize_release(self):
        name='sources/restore-policy.sql';self.fixture.write(name,'reviewed bounded cleanup')
        self.contract['restoreReplay']={'stage':'A43.37','releaseAllowed':False,'retentionDefault':'observe','sources':{name:hashlib.sha256((self.root/name).read_bytes()).hexdigest()}};self.save();gate.check(self.root)
        self.fixture.write(name,'changed cleanup');self.reject('Unreviewed restore policy change')
        self.contract['restoreReplay']['releaseAllowed']=True;self.reject('Premature restore release')
    def test_real_contract_covers_every_current_column_and_omission(self):
        result=gate.check(fixtures.ROOT);self.assertEqual(145,result['tables']);self.assertEqual(1307,result['columns']);self.assertEqual(93,result['omissions'])
    def test_unclassified_column_is_rejected(self):
        del self.contract['tables']['sample-service/customers']['columns']['id'];self.reject('Unclassified/stale column')
    def test_stale_column_is_rejected(self):
        self.contract['tables']['sample-service/customers']['columns']['ghost']='export';self.reject('Unclassified/stale column')
    def test_missing_table_is_rejected(self):
        self.contract['tables']={};self.reject('Unclassified/stale table')
    def test_unknown_field_rule_is_rejected(self):
        self.contract['tables']['sample-service/customers']['columns']['id']='pending';self.reject('Unknown field rule')
    def test_secret_cannot_be_reclassified_as_exportable(self):
        self.contract['tables']['sample-service/customers']['columns']['password_hash']='export';self.reject('Authentication material cannot')
    def test_free_text_cannot_be_reclassified_as_automatically_exportable(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT,metadata TEXT);');self.fixture.review();self.fixture.save()
        self.contract['tables']['sample-service/customers']['columns']['metadata']='export';self.reject('Free text requires manual')
    def test_missing_redis_or_copy_decision_is_rejected(self):
        for key in ('redis','copies'):
            original=copy.deepcopy(self.contract[key]);self.contract[key]={}
            self.reject('Unclassified '+key);self.contract[key]=original
    def test_wrong_resource_owners_are_rejected(self):
        self.contract['redis']['redis-fixture']['owners']=['other-service'];self.reject('Wrong redis owners')
    def test_missing_procedure_is_rejected(self):
        self.contract['rules']['manual']['procedure']='docs/missing.md';self.reject('Missing or unsafe source')
    def test_new_literal_omission_requires_a_decision(self):
        self.fixture.write(self.path,'class Export { Object x = new Export.Omission("newScope","pending"); }');self.reject('Changed/unclassified omission')
    def test_dynamic_omission_cannot_bypass_the_review(self):
        self.fixture.write(self.path,'class Export { Object x = new Export.Omission(userField,reason); }');self.reject('Dynamic omission needs')
    def test_dynamic_factory_call_cannot_bypass_the_review(self):
        self.fixture.write(self.path,'class Export { Object x = omissions(userField,reason); private Object omissions(String area, String reason) { return new Export.Omission(area,reason); } }')
        self.reject('Dynamic omission factory call needs')
    def test_changed_deduplication_identity_source_is_rejected(self):
        self.fixture.write('sources/identity0.java','changed identity algorithm');self.reject('Unreviewed deduplication identity change')
    def test_projection_change_without_new_literal_omission_still_requires_review(self):
        self.fixture.write(self.path,(self.root/self.path).read_text()+'\n// changed projection implementation\n')
        self.reject('Unreviewed export projection source change')
    def test_classification_cannot_claim_identity_migration_implemented(self):
        self.contract['identityMigration']['implemented']=True;self.reject('Premature identity migration')
    def compatibility(self):
        self.contract['identityMigration'].update(implemented=True,compatibility={'producerDefaultEnabled':False,'backlogProtocol':'unchanged-v1','fingerprints':'exact-v1-and-v2-wire','aliasRetention':'pending-A43.40','tests':list(self.contract['identitySources'])})
    def test_reviewed_identity_migration_keeps_backlog_and_default_off(self):
        self.compatibility();self.save();self.assertTrue(gate.check(self.root)['identityMigrationImplemented'])
    def test_migration_cannot_enable_producer_or_expire_aliases(self):
        for key,value in [('producerDefaultEnabled',True),('backlogProtocol','rewrite'),('fingerprints','v2-only'),('aliasRetention','1h')]:
            self.compatibility();self.contract['identityMigration']['compatibility'][key]=value;self.reject('Premature identity migration')
    def test_migration_evidence_must_exist(self):
        self.compatibility();self.contract['identityMigration']['compatibility']['tests'][0]='missing/test.java';self.reject('Missing or unsafe source')
    def test_normalized_identity_expression_requires_a_real_unique_index(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT); CREATE UNIQUE INDEX normalized_identity ON customers((coalesce(password_hash,'')));')
        self.fixture.review();self.fixture.save()
        self.contract['deduplicationExpressions']={'sample-service/customers':{'name':'normalized_identity','sources':[self.fixture.migration]}};self.save();self.assertEqual(1,gate.check(self.root)['tables'])
        self.contract['deduplicationExpressions']['sample-service/customers']['name']='absent_identity';self.reject('Missing identity expression index')
    def test_deduplication_obligations_cannot_be_omitted(self):
        self.contract['deduplicationConstraints']={};self.reject('Missing deduplication obligations')
    def test_privacy_status_hash_cannot_be_exposed_as_workflow_metadata(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT,status_token_hash TEXT);');self.fixture.review();self.fixture.save()
        self.contract['tables']['sample-service/customers']['columns']['status_token_hash']='export'
        self.reject('Authentication material cannot')
    def test_minimal_privacy_marker_must_keep_both_identity_constraints(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT); CREATE TABLE account_privacy_fences(customer_id BIGINT PRIMARY KEY,request_id UUID UNIQUE,state TEXT);')
        self.fixture.catalog['services'][0]['tables'].append({'name':'account_privacy_fences','classification':['personal','idempotency'],'subject':'Stable fixture ID','mechanisms':[],'limitation':'Fixture durable marker','export':'excluded-with-reviewed-procedure','erase':'pending-fence-design'})
        self.fixture.review();self.fixture.save()
        self.contract['tables']['sample-service/account_privacy_fences']={'owner':'sample-service','ownership':'Verified fixture ID','sources':[self.fixture.migration],'columns':{'customer_id':'operational','request_id':'operational','state':'operational'}}
        self.reject('Privacy fence identity obligation missing')
        self.contract['deduplicationConstraints']['sample-service/account_privacy_fences']=[['customer_id'],['request_id']];self.save()
        self.assertEqual(2,gate.check(self.root)['tables'])
    def test_chat_privacy_request_requires_conversation_and_request_identity(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT); CREATE TABLE chat_privacy_requests(conversation_id BIGINT PRIMARY KEY,request_id UUID UNIQUE,state TEXT,created_at TIMESTAMPTZ);')
        self.fixture.catalog['services'][0]['tables'].append({'name':'chat_privacy_requests','classification':['personal','idempotency'],'subject':'Fixture capability','mechanisms':[],'limitation':'Fixture request without message text','export':'excluded-with-reviewed-procedure','erase':'pending-reviewed-plan'})
        self.fixture.review();self.fixture.save()
        self.contract['tables']['sample-service/chat_privacy_requests']={'owner':'sample-service','ownership':'Verified fixture capability','sources':[self.fixture.migration],'columns':{'conversation_id':'operational','request_id':'operational','state':'operational','created_at':'operational'}}
        self.reject('Chat privacy identity obligation missing')
        self.contract['deduplicationConstraints']['sample-service/chat_privacy_requests']=[['conversation_id']];self.reject('Chat privacy identity obligation missing')
        self.contract['deduplicationConstraints']['sample-service/chat_privacy_requests']=[['conversation_id'],['request_id']];self.save();self.assertEqual(2,gate.check(self.root)['tables'])
    def test_commerce_receipt_cannot_lose_order_identity(self):
        self.fixture.write(self.fixture.migration,'CREATE TABLE customers(id BIGINT,password_hash TEXT); CREATE TABLE commerce_privacy_locks(order_id BIGINT PRIMARY KEY,customer_id BIGINT,request_id UUID);')
        self.fixture.catalog['services'][0]['tables'].append({'name':'commerce_privacy_locks','classification':['personal','idempotency'],'subject':'Canonical fixture order and stable account ID','mechanisms':[],'limitation':'No automatic TTL','export':'excluded-with-reviewed-procedure','erase':'pending-fence-design'})
        self.fixture.review();self.fixture.save()
        self.contract['tables']['sample-service/commerce_privacy_locks']={'owner':'sample-service','ownership':'Canonical fixture order','sources':[self.fixture.migration],'columns':{'order_id':'operational','customer_id':'operational','request_id':'operational'}}
        self.reject('Commerce privacy identity obligation missing')
        self.contract['deduplicationConstraints']['sample-service/commerce_privacy_locks']=[['order_id']];self.save();self.assertEqual(2,gate.check(self.root)['tables'])
    def test_source_paths_cannot_escape_workspace(self):
        self.contract['rules']['export']['procedure']='../private.txt';self.reject('escapes workspace')
    def test_empty_ownership_proof_is_rejected(self):
        self.contract['tables']['sample-service/customers']['ownership']='';self.reject('Wrong table owner/proof')
    def test_cli_runs_with_network_and_child_processes_blocked(self):
        self.fixture.write('blocker/sitecustomize.py',"import socket,subprocess,os\ndef blocked(*a,**k): raise RuntimeError('I/O forbidden')\nsocket.socket=blocked\nsocket.create_connection=blocked\nsubprocess.Popen=blocked\nos.system=blocked\n")
        env=dict(os.environ,PYTHONPATH=str(self.root/'blocker'),PYTHONDONTWRITEBYTECODE='1')
        result=subprocess.run([sys.executable,str(fixtures.ROOT/'scripts/qa/technical-data-classification-check.py'),'--root',str(self.root)],env=env,capture_output=True,text=True,timeout=15)
        self.assertEqual(0,result.returncode,result.stderr);self.assertEqual(0,json.loads(result.stdout)['executedDataOperations'])
    def test_gate_and_postgres_oracle_are_registered_in_ci(self):
        workflow=(fixtures.ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('run: python3 scripts/qa/technical-data-classification-check.py',workflow)
        self.assertIn('run: python3 scripts/qa/technical-classification-postgres-e2e.py',workflow)
        self.assertIn('run: python3 scripts/qa/finance-privacy-e2e.py',workflow)

if __name__=='__main__':unittest.main()
