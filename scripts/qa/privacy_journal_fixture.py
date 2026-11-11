"""Disposable QA signing/encryption keys; never uses a configured user identity."""
import base64,importlib.util,subprocess,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('qa_privacy_journal',ROOT/'scripts/backup/privacy-journal.py');journal=importlib.util.module_from_spec(spec);spec.loader.exec_module(journal)

class JournalFixture:
    def __init__(self,directory):
        self.directory=Path(directory);self.keys=self.directory/'.journal-test-keys';self.keys.mkdir(mode=0o700)
        self.identity=self.keys/'fixture.agekey';self.signing=self.keys/'fixture-signing.pem';self.public=self.keys/'fixture-public.pem';self.ledger_id=str(uuid.uuid4())
        journal.command('age-keygen','-o',str(self.identity));self.recipient=journal.command('age-keygen','-y',str(self.identity)).decode().strip()
        journal.command('openssl','genpkey','-algorithm','ED25519','-out',str(self.signing));journal.command('openssl','pkey','-in',str(self.signing),'-pubout','-out',str(self.public))
        for path in (self.identity,self.signing,self.public):path.chmod(0o600)
        self.public_base64=base64.b64encode(journal.command('openssl','pkey','-pubin','-in',str(self.public),'-outform','DER')).decode()
        self.store=journal.Journal(self.directory/'independent-journal',self.identity,self.public,self.directory/'separate-backups',self.signing,self.recipient)
        self.pin=self.store.initialize(self.ledger_id)
    def environment(self):return {'PRIVACY_RESTORE_JOURNAL_PUBLIC_KEY_BASE64':self.public_base64,'PRIVACY_RESTORE_JOURNAL_LEDGER_ID':self.ledger_id}
    def acknowledge(self,origin,operator):
        result=journal.sync(self.store,origin,operator);self.pin=result['currentHead'];return result
    def header(self,customer):
        _,entries=self.store.load(self.pin)
        return {'X-Privacy-Journal':journal.receipt(next(entry for entry in entries if entry['capsule']['customerId']==str(customer)))}
