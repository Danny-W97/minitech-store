import copy,hashlib,importlib.util,io,json,os,subprocess,tarfile,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[3]


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


install=module('locked_tool_tests','scripts/deploy/install-supply-chain-tools.py')
supply=module('image_supply_tests','scripts/deploy/image-supply-chain.py')
pins=module('image_pin_tests','scripts/qa/supply-chain-check.py')
refresh=module('image_refresh_tests','scripts/deploy/refresh-supply-chain-lock.py')
IMAGE='sha256:'+'a'*64
COMMIT='b'*40


class LockedToolTests(unittest.TestCase):
    def test_bad_download_never_overwrites_previous_executable(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as directory:
            dest=Path(directory)/'bin';dest.mkdir();(dest/'cosign').write_bytes(b'previous')
            entry={'version':'v1.2.3','url':'https://github.com/sigstore/cosign/releases/download/v1.2.3/cosign-linux-amd64','sha256':'0'*64}
            with patch.object(install.urllib.request,'urlopen',return_value=io.BytesIO(b'changed')):
                with self.assertRaisesRegex(ValueError,'SHA256'):install.install('cosign',entry,dest)
            self.assertEqual(b'previous',(dest/'cosign').read_bytes())

    def test_verified_bytes_restore_a_corrupted_cached_executable(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as directory:
            dest=Path(directory)/'bin';body=b'synthetic executable'
            entry={'version':'v1.2.3','url':'https://github.com/sigstore/cosign/releases/download/v1.2.3/cosign-linux-amd64','sha256':hashlib.sha256(body).hexdigest()}
            with patch.object(install.urllib.request,'urlopen',return_value=io.BytesIO(body)):install.install('cosign',entry,dest)
            (dest/'cosign').write_bytes(b'corrupt')
            with patch.object(install.urllib.request,'urlopen',side_effect=AssertionError('cached archive should be verified locally')):install.install('cosign',entry,dest)
            self.assertEqual(body,(dest/'cosign').read_bytes())

    def test_unreviewed_origin_or_archive_link_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as directory:
            entry={'version':'v1.2.3','url':'https://attacker.invalid/tool','sha256':'a'*64}
            with self.assertRaises(ValueError):install.install('cosign',entry,Path(directory)/'bin')
            body=io.BytesIO()
            with tarfile.open(fileobj=body,mode='w:gz') as archive:
                member=tarfile.TarInfo('trivy');member.type=tarfile.SYMTYPE;member.linkname='/outside';archive.addfile(member)
            raw=body.getvalue();entry={'version':'v1.2.3','url':'https://github.com/aquasecurity/trivy/releases/download/v1.2.3/trivy_1.2.3_Linux-64bit.tar.gz','sha256':hashlib.sha256(raw).hexdigest(),'member':'trivy'}
            with patch.object(install.urllib.request,'urlopen',return_value=io.BytesIO(raw)):
                with self.assertRaises(ValueError):install.install('trivy',entry,Path(directory)/'bin')


class ImageSupplyTests(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory(dir=ROOT/'tmp');self.addCleanup(tmp.cleanup);self.root=Path(tmp.name)
        lock=self.root/'scripts/deploy/supply-chain.lock.json';lock.parent.mkdir(parents=True)
        self.lock={'tools':{'trivy':{'version':'v0.76.1'}},'frontendPackages':{'fixture':{'indexSource':'https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/APKINDEX.tar.gz'}}}
        lock.write_text(json.dumps(self.lock));tool=self.root/'gitIgnoreOwn/tools/supply-chain/bin/trivy';tool.parent.mkdir(parents=True);tool.write_text('synthetic scanner')
        p=patch.object(supply,'ROOT',self.root);p.start();self.addCleanup(p.stop)
        self.directory=self.root/'evidence';self.code=0;self.blocking=False;self.bom=True;self.scan_file=True;self.calls=[]

    def command(self,args):return json.dumps({'Version':'0.76.1'})

    def runner(self,args,**kwargs):
        self.calls.append(args);output=Path(args[args.index('--output')+1]);kind=args[args.index('--format')+1]
        if kind=='json':
            vulnerabilities=[{'Severity':'HIGH','FixedVersion':'2','VulnerabilityID':'SYNTHETIC'}] if self.blocking else []
            if self.scan_file:output.write_text(json.dumps({'SchemaVersion':2,'Metadata':{'ImageID':IMAGE},'Results':[{'Vulnerabilities':vulnerabilities}]}))
            return SimpleNamespace(returncode=self.code)
        output.write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.6','components':[{'name':'synthetic'}] if self.bom else []}));return SimpleNamespace(returncode=0)

    def run_scan(self):
        with patch.object(supply,'inspect',return_value=IMAGE),patch.object(supply,'command',side_effect=self.command):return supply.scan('ghcr.io/fixture/image:tag',IMAGE,self.directory,COMMIT,self.runner)

    def test_scan_and_sbom_use_immutable_image_id_and_bound_hashes(self):
        self.assertEqual(0,self.run_scan());report=supply.read(self.directory/'supply-chain.json')
        self.assertEqual('PASS',report['status']);self.assertEqual(IMAGE,report['localImageId'])
        self.assertTrue(all(args[-1]==IMAGE for args in self.calls))
        self.assertEqual(supply.file_sha(self.directory/'sbom.cdx.json'),report['sbomSha256'])
        args=self.calls[0];self.assertIn('--ignore-unfixed',args);self.assertEqual('HIGH,CRITICAL',args[args.index('--severity')+1]);self.assertEqual('os,library',args[args.index('--pkg-types')+1])

    def test_high_fixable_results_block_even_when_sbom_is_generated(self):
        self.code=1;self.blocking=True;self.assertEqual(1,self.run_scan())
        self.assertTrue((self.directory/'sbom.cdx.json').is_file())
        report=supply.read(self.directory/'supply-chain.json');self.assertEqual('BLOCKED_BY_SCAN',report['status'])
        # Changing only the status must never authorize publication.
        report['status']='PASS';(self.directory/'supply-chain.json').write_text(json.dumps(report))
        with patch.object(supply,'inspect',return_value=IMAGE),self.assertRaises(ValueError):supply.preflight('ghcr.io/fixture/image:tag',self.directory,COMMIT)

    def test_failed_tool_missing_scan_or_empty_sbom_never_approves(self):
        self.code=2;self.scan_file=False
        with self.assertRaises(ValueError):self.run_scan()
        self.directory=self.root/'other';self.code=0;self.scan_file=True;self.bom=False
        with self.assertRaises(ValueError):self.run_scan()

    def test_scanner_exit_and_json_disagreement_is_rejected(self):
        self.code=0;self.blocking=True
        with self.assertRaises(ValueError):self.run_scan()

    def test_changed_tag_or_unexpected_tool_version_is_rejected_before_scan(self):
        with patch.object(supply,'inspect',return_value='sha256:'+'c'*64),self.assertRaises(ValueError):supply.scan('fixture',IMAGE,self.directory,COMMIT,self.runner)
        with patch.object(supply,'inspect',return_value=IMAGE),patch.object(supply,'command',return_value='{"Version":"unexpected"}'),self.assertRaises(ValueError):supply.scan('fixture',IMAGE,self.directory,COMMIT,self.runner)
        self.assertEqual([],self.calls)

    def test_missing_or_altered_sbom_commit_and_lock_block_publication(self):
        self.run_scan()
        with patch.object(supply,'inspect',return_value=IMAGE):
            self.assertEqual('PASS',supply.preflight('fixture',self.directory,COMMIT)['status'])
            with self.assertRaises(ValueError):supply.preflight('fixture',self.directory,'c'*40)
            bom=self.directory/'sbom.cdx.json';before=bom.read_bytes();bom.write_text('{}')
            with self.assertRaises(ValueError):supply.preflight('fixture',self.directory,COMMIT)
            bom.write_bytes(before)
            lock=self.root/'scripts/deploy/supply-chain.lock.json';lock.write_text(lock.read_text()+' ')
            with self.assertRaises(ValueError):supply.preflight('fixture',self.directory,COMMIT)

    def test_registry_digest_capture_is_exact_and_refuses_missing_ambiguous_or_wrong_repo(self):
        self.run_scan();image='ghcr.io/fixture/image:tag';digest='ghcr.io/fixture/image@sha256:'+'c'*64
        with patch.object(supply,'inspect',return_value=IMAGE):
            for refs,ok in [([digest],True),([],False),([digest,digest],False),(['ghcr.io/other/image@sha256:'+'c'*64],False)]:
                with patch.object(supply,'command',return_value=json.dumps(refs)):
                    if ok:self.assertEqual(digest,supply.published(image,self.directory,COMMIT))
                    else:
                        with self.assertRaises(ValueError):supply.published(image,self.directory,COMMIT)


class SupplyPinTests(unittest.TestCase):
    def fixture(self):
        tmp=tempfile.TemporaryDirectory(dir=ROOT/'tmp');self.addCleanup(tmp.cleanup);root=Path(tmp.name)
        paths=['scripts/deploy/supply-chain.lock.json','.mvn/wrapper/maven-wrapper.properties','deploy/docker/frontend.Dockerfile','deploy/docker/service.Dockerfile']
        paths += [p.relative_to(ROOT).as_posix() for p in (ROOT/'.github').rglob('*.yml')]
        for name in paths:
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/name).read_bytes())
        return root

    def test_real_lock_and_source_match_without_network(self):
        with patch('socket.socket',side_effect=AssertionError('network forbidden')):result=pins.check()
        self.assertEqual('PASS',result['status']);self.assertEqual(8,result['directActions']);self.assertFalse(result['automaticMerge'])

    def test_floating_action_base_wrong_apk_hash_and_wrapper_drift_fail(self):
        for name,before,after in [('.github/workflows/ci.yml','actions/checkout@'+json.loads((ROOT/'scripts/deploy/supply-chain.lock.json').read_text())['actions']['actions/checkout']['sha'],'actions/checkout@v4'),
                ('deploy/docker/frontend.Dockerfile','FROM ','# removed\nFROM nginx:latest\n# '),
                ('.mvn/wrapper/maven-wrapper.properties','distributionSha256Sum=','unknownChecksum='),
                ('deploy/docker/frontend.Dockerfile','ADD --checksum=sha256:','ADD --checksum=sha256:0')]:
            root=self.fixture();path=root/name;path.write_text(path.read_text().replace(before,after,1))
            with self.subTest(name=name),self.assertRaises(ValueError):pins.check(root)

    def test_update_patch_is_a_candidate_and_does_not_change_repository(self):
        root=self.fixture();path=root/'scripts/deploy/supply-chain.lock.json';current=json.loads(path.read_text());candidate=copy.deepcopy(current)
        candidate['actions']['actions/checkout']['sha']='f'*40
        candidate['images']['frontend']['reference']='nginx:stable-alpine@sha256:'+'e'*64
        candidate['images']['frontend']['digest']='sha256:'+'e'*64
        candidate['frontendPackages']['curl'].update(version='9.0.0-r0',url='https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/curl-9.0.0-r0.apk',sha256='d'*64)
        before=(root/'.github/workflows/ci.yml').read_bytes()
        proposed=refresh.patch(root,current,candidate)
        self.assertIn('f'*40,proposed);self.assertIn('scripts/deploy/supply-chain.lock.json',proposed)
        self.assertIn('curl-9.0.0-r0.apk',proposed);self.assertIn('sha256:'+'d'*64,proposed)
        candidate_patch=root/'candidate.patch';candidate_patch.write_text(proposed)
        result=subprocess.run(['git','-c','core.fsmonitor=false','apply','--check',str(candidate_patch)],cwd=root,capture_output=True,text=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(before,(root/'.github/workflows/ci.yml').read_bytes())

    def test_update_job_cannot_override_readonly_or_persist_credentials(self):
        for before,after in [('    runs-on: ubuntu-latest','    permissions:\n      contents: write\n    runs-on: ubuntu-latest'),('persist-credentials: false','persist-credentials: true')]:
            root=self.fixture();path=root/'.github/workflows/image-input-updates.yml'
            path.write_text(path.read_text().replace(before,after,1))
            with self.subTest(mutation=after),self.assertRaises(ValueError):pins.check(root)

    def test_same_hash_does_not_allow_unreviewed_apk_origin(self):
        root=self.fixture();path=root/'scripts/deploy/supply-chain.lock.json';lock=json.loads(path.read_text())
        old=lock['frontendPackages']['curl']['url'];new=old.replace('dl-cdn.alpinelinux.org','attacker.invalid')
        lock['frontendPackages']['curl']['url']=new;path.write_text(json.dumps(lock))
        dockerfile=root/'deploy/docker/frontend.Dockerfile';dockerfile.write_text(dockerfile.read_text().replace(old,new))
        with self.assertRaises(ValueError):pins.check(root)


if __name__=='__main__':unittest.main()
