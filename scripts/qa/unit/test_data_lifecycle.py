import copy
import fnmatch
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
SCRIPT=ROOT/'scripts/qa/data-lifecycle-check.py'
spec=importlib.util.spec_from_file_location('data_lifecycle',SCRIPT);gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

class DataLifecycleTests(unittest.TestCase):
    def setUp(self):
        (ROOT/'tmp').mkdir(exist_ok=True)
        self.directory=tempfile.TemporaryDirectory(prefix='a43-15-fixture-',dir=ROOT/'tmp');self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name)/'workspace';self.root.mkdir()
        self.migration='sample-service/src/main/resources/db/migration/V1__initial.sql'
        self.java='sample-service/src/main/java/Store.java'
        self.write(self.migration,'CREATE TABLE customers (id BIGINT, password_hash TEXT);')
        self.write(self.java,'class Store { StringRedisTemplate redis; }')
        self.write('sources/evidence.txt','fixture source metadata, no real credentials')
        self.components=[{'name':'sample-service','runtime':'jvm','database':'sample','redis':True}]
        self.write('deploy/components.json',json.dumps({'components':self.components}))
        base={'classification':['personal','credential'],'subject':'verified customer ID','mechanisms':[],
              'limitation':'Fixture plan is incomplete.','export':'pending-safe-projection','erase':'pending-reviewed-plan'}
        self.catalog={'schemaVersion':1,'capabilities':{'customerExport':'pending','customerErase':'pending','backupErasure':'pending','retentionActivation':'operator-decision'},
            'mechanisms':{'A43.1':{'scope':'fixture mechanism','sources':['sources/evidence.txt']}},
            'services':[{'owner':'sample-service','database':'sample','tables':[dict(base,name='customers')]}],
            'redis':{'stores':[dict(base,id='redis-fixture',owners=['sample-service'],patterns=['fixture:*'])]},
            'copies':[dict(base,id=kind,kind=kind,owners=['sample-service'],locations=['fixture location'],sources=['sources/evidence.txt'])
                      for kind in ('backup','runtime','browser','external')]}
        self.review();self.save()
    def write(self,name,text):
        path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8')
    def save(self):self.write(gate.CATALOG,json.dumps(self.catalog))
    def review(self):
        _,self.catalog['schemaReview'],self.catalog['redis']['javaReferences']=gate.discover(self.root,self.components)
    def reject(self,message):
        self.save()
        with self.assertRaisesRegex(ValueError,message):gate.check(self.root)
    def cli(self,*args,env=None):return subprocess.run([sys.executable,str(SCRIPT),'--root',str(self.root),*args],capture_output=True,text=True,timeout=15,env=env)

    def test_real_inventory_covers_current_project_without_claiming_completed_erasure(self):
        result=gate.check(ROOT);self.assertGreaterEqual(result['tables'],49);self.assertGreaterEqual(result['databaseOwners'],14)
        self.assertEqual(0,result['executedDataOperations']);self.assertFalse(result['fullA43Complete']);self.assertEqual('pending',result['capabilities']['customerErase'])
    def test_minimal_complete_inventory_passes(self):
        result=gate.check(self.root);self.assertEqual(1,result['tables']);self.assertEqual(4,result['copyStores'])
    def test_partial_safe_projection_does_not_complete_account_export(self):
        self.catalog['services'][0]['tables'][0]['export']='partial-safe-projection';self.save()
        result=gate.check(self.root)
        self.assertEqual('pending',result['capabilities']['customerExport']);self.assertFalse(result['fullA43Complete'])
    def test_new_table_without_review_is_rejected(self):
        self.write(self.migration,'CREATE TABLE customers(id BIGINT); CREATE TABLE extra(id BIGINT);');self.reject('Migration review changed')
    def test_hash_update_alone_cannot_hide_an_unlisted_table(self):
        self.write(self.migration,'CREATE TABLE customers(id BIGINT); CREATE TABLE extra(id BIGINT);');self.review();self.reject('Uncovered or stale table')
    def test_alter_column_requires_review_even_without_a_new_table(self):
        self.write(self.migration,'CREATE TABLE customers(id BIGINT); ALTER TABLE customers ADD COLUMN email TEXT;');self.reject('Migration review changed')
    def test_removed_table_cannot_stay_in_the_inventory(self):
        self.write(self.migration,'CREATE TABLE customers(id BIGINT); DROP TABLE customers;');self.review();self.reject('Uncovered or stale table')
    def test_sql_comments_strings_and_quoted_identifiers_do_not_create_fake_tables(self):
        sql="""-- CREATE TABLE hidden_a(id int);
        /* outer /* CREATE TABLE hidden_b(id int); */ comment */
        SELECT 'it''s CREATE TABLE hidden_c(id int)';
        CREATE TABLE public."Customers" (id BIGINT);
        ALTER TABLE public."Customers" RENAME TO renamed;
        CREATE TABLE IF NOT EXISTS renamed(id BIGINT);
        """
        self.assertEqual({'renamed'},gate.apply_tables(set(),sql))
    def test_drop_lists_and_if_exists_preserve_final_schema(self):
        self.assertEqual(set(),gate.apply_tables(set(),'CREATE TABLE a(id int); CREATE TABLE b(id int); DROP TABLE a,b; DROP TABLE IF EXISTS absent;'))
    def test_dynamic_foreign_materialized_and_repeatable_storage_fail_closed(self):
        for sql in ["DO $$ BEGIN EXECUTE 'CREATE TABLE hidden(id int)'; END $$;",'CREATE FOREIGN TABLE hidden(id int);','CREATE MATERIALIZED VIEW hidden AS SELECT 1;']:
            with self.assertRaises(ValueError):gate.apply_tables(set(),sql)
        self.write('sample-service/src/main/resources/db/migration/R__unreviewed.sql','CREATE TABLE hidden(id int);')
        self.reject('Unsupported migration name')
    def test_unterminated_sql_blocks_are_not_silently_ignored(self):
        for sql in ['/* missing end',"SELECT 'missing end",'DO $tag$ missing end', 'CREATE TABLE "missing end']:
            with self.assertRaises(ValueError):gate.apply_tables(set(),sql)
    def test_duplicate_table_or_missing_database_owner_is_rejected(self):
        self.catalog['services'][0]['tables']*=2;self.reject('Uncovered or stale table')
        self.catalog['services']=[];self.reject('Database inventory owners')
    def test_wrong_database_alias_is_rejected(self):
        self.catalog['services'][0]['database']='different';self.reject('Wrong database alias')
    def test_credentials_cannot_be_marked_as_unfiltered_pending_export(self):
        self.catalog['services'][0]['tables'][0]['export']='pending';self.reject('Credentials require')
    def test_missing_subject_and_unknown_retention_mechanism_are_rejected(self):
        entry=self.catalog['services'][0]['tables'][0];entry['subject']='';self.reject('Missing account relationship')
        entry['subject']='verified owner';entry['mechanisms']=['A99'];self.reject('Unknown retention mechanism')
    def test_inventory_cannot_claim_that_account_operations_are_implemented(self):
        self.catalog['capabilities']['customerErase']='implemented';self.reject('not proof of completed account operations')
    def test_new_and_changed_redis_sources_require_review(self):
        self.write('sample-service/src/main/java/NewStore.java','class NewStore { io.lettuce.core.RedisClient client; }');self.reject('Redis usage review changed')
        (self.root/'sample-service/src/main/java/NewStore.java').unlink()
        self.write(self.java,'class Store { StringRedisTemplate redis; String newKey="sensitive:*"; }');self.reject('Redis usage review changed')
    def test_java_migrations_require_explicit_support(self):
        self.write('sample-service/src/main/java/JavaSchema.java','class JavaSchema extends BaseJavaMigration {}');self.reject('Java migration needs explicit')
    def test_redis_source_cannot_hide_behind_a_false_component_declaration(self):
        self.components[0]['redis']=False;self.write('deploy/components.json',json.dumps({'components':self.components}));self.reject('Redis source owner')
    def test_new_redis_component_needs_a_store_owner_entry(self):
        self.components.append({'name':'extra-service','runtime':'jvm','database':None,'redis':True})
        self.write('deploy/components.json',json.dumps({'components':self.components}));self.reject('Uncovered Redis component')
    def test_missing_copy_category_duplicate_id_and_empty_namespace_are_rejected(self):
        original=copy.deepcopy(self.catalog)
        self.catalog['copies']=[e for e in self.catalog['copies'] if e['kind']!='backup'];self.reject('Missing copy category')
        self.catalog=copy.deepcopy(original);self.catalog['copies'][1]['id']=self.catalog['copies'][0]['id'];self.reject('Duplicate/missing data copy')
        self.catalog=copy.deepcopy(original);self.catalog['redis']['stores'][0]['patterns']=[''];self.reject('Missing Redis namespace')
    def test_source_references_cannot_escape_the_workspace_or_exceed_budget(self):
        for name in ('../outside.agekey','C:/outside.agekey','/outside.agekey'):
            with self.assertRaisesRegex(ValueError,'escapes workspace'):gate.source(self.root,name)
        self.write('sources/large.txt','x'*(gate.MAX_BYTES+1))
        with self.assertRaisesRegex(ValueError,'budget'):gate.source(self.root,'sources/large.txt')
    @unittest.skipIf(os.name=='nt','POSIX fixture symlink is tested in WSL/CI')
    def test_symlink_source_cannot_read_outside_the_fixture_workspace(self):
        outside=Path(self.directory.name)/'outside.txt';outside.write_text('fixture-only')
        (self.root/'sources/link.txt').symlink_to(outside)
        with self.assertRaisesRegex(ValueError,'unsafe source'):gate.source(self.root,'sources/link.txt')
    def test_duplicate_json_keys_are_rejected(self):
        self.write(gate.CATALOG,'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaisesRegex(ValueError,'Duplicate JSON key'):gate.check(self.root)
    def test_cli_runs_with_network_and_child_process_launch_disabled(self):
        self.write('blocker/sitecustomize.py',"import socket, subprocess, os\ndef blocked(*a,**k): raise RuntimeError('fixture forbids I/O')\nsocket.socket=blocked\nsocket.create_connection=blocked\nsubprocess.Popen=blocked\nos.system=blocked\n")
        environment=dict(os.environ,PYTHONPATH=str(self.root/'blocker'),PYTHONDONTWRITEBYTECODE='1')
        before={p.relative_to(self.root).as_posix():gate.digest(p) for p in self.root.rglob('*') if p.is_file()}
        result=self.cli(env=environment);self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(0,json.loads(result.stdout)['executedDataOperations'])
        self.assertEqual(before,{p.relative_to(self.root).as_posix():gate.digest(p) for p in self.root.rglob('*') if p.is_file()})
    def test_cli_reports_only_to_a_new_file_inside_workspace(self):
        output=self.root/'reports/result.json';result=self.cli('--output',str(output));self.assertEqual(0,result.returncode,result.stderr)
        original=output.read_bytes();self.assertEqual(1,self.cli('--output',str(output)).returncode);self.assertEqual(original,output.read_bytes())
        outside=Path(self.directory.name)/'outside-report.json';self.assertEqual(1,self.cli('--output',str(outside)).returncode);self.assertFalse(outside.exists())
    def test_catalog_changes_trigger_ci_and_the_gate_is_registered(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('run: python3 scripts/qa/data-lifecycle-check.py',workflow)
        blocks=re.findall(r'paths-ignore:\n((?:[ \t]+- [^\n]+\n)+)',workflow)
        # Push still has a docs filter. PR is deliberately unfiltered so its stable
        # image gate cannot remain pending; test_ci_images checks that boundary.
        self.assertGreaterEqual(len(blocks),1)
        for block in blocks:
            for line in block.splitlines():
                pattern=line.strip().removeprefix('- ').strip("'\"")
                self.assertFalse(fnmatch.fnmatchcase(gate.CATALOG,pattern),pattern)

if __name__=='__main__':unittest.main()
