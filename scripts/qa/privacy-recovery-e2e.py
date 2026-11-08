#!/usr/bin/env python3
"""A43.37: source loss, independent journal and privacy replay in owned isolated resources."""
import base64,hashlib,importlib.util,json,os,secrets,subprocess,time,uuid
from pathlib import Path
from privacy_journal_fixture import JournalFixture
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('privacy_restore_e2e',ROOT/'scripts/backup/privacy-restore.py');privacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(privacy)
recovery=privacy.recovery
SOURCE_ALIASES=('returns','inventory','promotion','delivery','payment','billing','notification')

def main():
    directory=recovery.private_directory(ROOT/'tmp'/('privacy-recovery-'+secrets.token_hex(4)))
    fixture=JournalFixture(directory);run=recovery.run;containers=[];report={'stage':'A43.37','status':'FAILED','releaseAllowed':False,'fullA36Accepted':False,'fullA43Complete':False}
    def container(image,*args,env=None):
        identifier=run('docker','run','-d','--network','none','--label','minitech.privacy-fixture='+directory.name,*args,image,env=env);containers.append(identifier);return identifier
    def sql(alias,query):return run('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','postgres','-d',alias+'_db','-Atqc',query)
    def redis(*args):return json.loads(run('docker','exec',broker,'redis-cli','-e','--json',*map(str,args)))
    try:
        postgres=container(recovery.POSTGRES_IMAGE,'-e','POSTGRES_PASSWORD',env=dict(os.environ,POSTGRES_PASSWORD=secrets.token_urlsafe(32)))
        broker=container(recovery.REDIS_IMAGE)
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-U','postgres'],capture_output=True).returncode==0:break
            time.sleep(.5)
        else:raise AssertionError('Postgres startup')
        sources={'databases':{},'redis':{'container':broker}}
        for alias in recovery.aliases():
            run('docker','exec',postgres,'createdb','-U','postgres',alias+'_db')
            migrations=sorted((ROOT/(alias+'-service')/'src/main/resources/db/migration').glob('V*__*.sql'),key=lambda p:int(p.name.split('__')[0][1:]))
            for path in migrations:sql(alias,'BEGIN;\n'+path.read_text(encoding='utf-8-sig')+'\nCOMMIT;')
            sources['databases'][alias]={'container':postgres,'database':alias+'_db','user':'postgres'}
        sql('customer',"INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) SELECT n,'person-'||n||'@fixture.example','fixture-only','Private','Person','Private street','00-001','Private city','PL',true,now(),now() FROM generate_series(55,56)n")
        sql('customer',"INSERT INTO refresh_tokens(customer_id,token_hash,family_id,expires_at,created_at) SELECT n,'refresh-'||n,'family-'||n,now()+interval '1 hour',now() FROM generate_series(55,56)n; INSERT INTO password_reset_tokens(customer_id,token_hash,expires_at,created_at) SELECT n,'reset-'||n,now()+interval '1 hour',now() FROM generate_series(55,56)n")
        sql('customer',"INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) SELECT n,'recovery-'||n,now() FROM generate_series(55,56)n; INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,public_key_credential_type,backup_state,created) VALUES('deleted','NTU',decode('01','hex'),0,true,false,'public-key',false,now()),('healthy','NTY',decode('02','hex'),0,true,false,'public-key',false,now())")
        sql('customer',"INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,created_at,updated_at) SELECT n,'fixture-ciphertext-'||n,true,now(),now() FROM generate_series(55,56)n")
        sql('wishlist',"INSERT INTO wishlist_items(customer_id,product_id,created_at) SELECT n,1,now() FROM generate_series(55,56)n")
        sql('recommendation',"INSERT INTO recommendation_requests(customer_id,strategy,result_count,requested_at) SELECT n,'fixture',1,now() FROM generate_series(55,56)n")
        sql('review',"INSERT INTO reviews(customer_id,order_id,product_id,rating,title,content,status,created_at,updated_at) SELECT n,n,1,5,'Private title','Private content','PUBLISHED',now(),now() FROM generate_series(55,56)n")
        fingerprint=hashlib.sha256(base64.b64decode(fixture.public_base64)).hexdigest()
        sql('customer',f"UPDATE restore_security_state SET ledger_id='{fixture.ledger_id}',verification_key_hash='{fingerprint}' WHERE id=1")
        # Restored D02 evidence must be invalidated for every account, including
        # the unaffected account and a guest, independently of journal entries.
        generations=[str(uuid.uuid4()) for _ in range(3)]
        sql('order',"""
          INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,
            delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,discount_amount,fulfillment_type,
            idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
          SELECT 7000+n,CASE WHEN n=3 THEN NULL ELSE 54+n END,'fixture-order@example.test',10,'CANCELLED',
            'Fixture','Buyer','Fixture street','00-001','Fixture city','PL','STANDARD_COURIER',0,0,'HOME_DELIVERY',
            'restore-key-'||n,repeat('b',64),'fixture-principal-'||n,now(),now() FROM generate_series(1,3)n
        """)
        sql('order',"""
          INSERT INTO commerce_retention_order_state(order_id,open_seen) SELECT 7000+n,TRUE FROM generate_series(1,3)n;
          INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price)
            SELECT 7000+n,1,'FIXTURE','Fixture',1,10,10 FROM generate_series(1,3)n;
          INSERT INTO order_sagas(order_id,state,created_at,updated_at)
            SELECT 7000+n,'FAILED',now(),now() FROM generate_series(1,3)n;
        """)
        sql('order',"INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id) VALUES('ORDER',7001),('CUSTOMER',56),('ORDER',7003); INSERT INTO commerce_retention_holds(hold_id,scope_type,subject_id,guard,reason_code,request_id) VALUES('11111111-1111-4111-8111-111111111111','ORDER',7001,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'),('22222222-2222-4222-8222-222222222222','CUSTOMER',56,'NO_OPEN_OR_REOPENED_CASE','OPEN_CASE','bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')")
        intake_id=str(uuid.uuid4())
        sql('order',f"BEGIN; INSERT INTO commerce_retention_hold_requests(request_id,order_id,case_reference,guard,reason_code,root_hold_id) VALUES('{intake_id}',7001,'{uuid.uuid4()}','NO_PRESERVATION_HOLD','PRESERVATION_REQUEST','11111111-1111-4111-8111-111111111111'); INSERT INTO commerce_retention_hold_source_requests(request_id,source,source_request_id) SELECT '{intake_id}',s,gen_random_uuid() FROM unnest(ARRAY['RETURNS','PAYMENT','INVENTORY','PROMOTION','DELIVERY','BILLING','NOTIFICATION']) s; COMMIT;")
        intake_identity_digest=sql('order',"SELECT md5(string_agg((to_jsonb(r)-'restore_blocked')::text,',' ORDER BY request_id)) FROM commerce_retention_hold_requests r")
        intake_plan_digest=sql('order',"SELECT md5(string_agg(s::text,',' ORDER BY request_id,source)) FROM commerce_retention_hold_source_requests s")
        hold_history=sql('order',"SELECT string_agg(hold_id::text||':'||scope_type||':'||subject_id::text||':'||guard||':'||request_id::text||':'||created_at::text,',' ORDER BY hold_id) FROM commerce_retention_holds")
        milestone_clocks=sql('order',"SELECT string_agg(order_id::text||':'||reconciled_at::text,',' ORDER BY order_id) FROM commerce_retention_order_state")
        assert sql('order','SELECT count(*) FROM commerce_retention_order_state WHERE reconciled_at IS NOT NULL')=='3'
        for n,generation in enumerate(generations,1):
            state='LEASED' if n==2 else 'DUE_FOR_REVIEW';lease=f"'{uuid.uuid4()}',now()+interval '1 hour'" if n==2 else 'NULL,NULL'
            owner='NULL' if n==3 else str(54+n)
            predecessor='NULL'
            if n==1:
                ancestor=str(uuid.uuid4());predecessor="'"+ancestor+"'"
                sql('order',f"INSERT INTO commerce_retention_jobs(order_id,generation,customer_id,order_version,phase,policy,current,state) VALUES(7001,'{ancestor}',55,0,'REDACTION','D02-P02-2026-10-30.1',FALSE,'STALE')")
            sql('order',f"INSERT INTO commerce_retention_jobs(order_id,generation,customer_id,order_version,phase,policy,state,run_token,run_until,preparation_predecessor) VALUES({7000+n},'{generation}',{owner},0,'REDACTION','D02-P02-2026-10-30.1','{state}',{lease},{predecessor}); INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state) VALUES({7000+n},'{generation}','REDACTION','GUARD','NO_PRESERVATION_HOLD','D02-P02-2026-10-30.1','CONFIRMED')")
        field_scope_hash=hashlib.sha256((ROOT/'platform-security/src/main/resources/privacy/commerce-retention-field-scope.json').read_bytes().replace(b'\r\n',b'\n')).hexdigest()
        sql('order',f"INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state,field_scope_hash) VALUES(7001,'{generations[0]}','REDACTION','GUARD','FIELD_SCOPE_REVIEWED','D02-P02-2026-10-30.1','CONFIRMED','{field_scope_hash}')")
        for n,generation in enumerate(generations,1):
            for source in ('RETURNS','INVENTORY','PROMOTION','DELIVERY','PAYMENT','BILLING','NOTIFICATION'):
                if n==1:
                    tail="'BOUND',1,NULL,NULL,1,0,FALSE"
                elif n==2 and source=='RETURNS':
                    tail=f"'LEASED',8,'{uuid.uuid4()}',now()+interval '1 hour',NULL,NULL,NULL"
                else:tail="'PENDING',0,NULL,NULL,NULL,NULL,NULL"
                sql('order',f"INSERT INTO commerce_retention_bindings(order_id,generation,source,order_version,phase,policy,expected_epoch,state,attempts,run_token,run_until,bound_epoch,source_revision,invalidated) VALUES({7000+n},'{generation}','{source}',0,'REDACTION','D02-P02-2026-10-30.1',0,{tail})")
        # One clean and one dirty source binding, with a leased notification.
        # Both must stay quarantined; the old outbox receipt is preserved.
        assert privacy.COMMERCE_SOURCE_ALIASES==SOURCE_ALIASES
        for alias in SOURCE_ALIASES:
            for n in (1,3):
                sql(alias,f"INSERT INTO commerce_retention_source_state(order_id,binding_epoch,generation,order_version,phase,source_revision,invalidated) VALUES({7000+n},1,'{generations[n-1]}',0,'REDACTION',{1 if n==3 else 0},{'TRUE' if n==3 else 'FALSE'})")
            sql(alias,f"INSERT INTO commerce_retention_change_outbox(order_id,binding_epoch,generation,order_version,phase,source_revision,state,attempts,run_token,run_until) VALUES(7003,1,'{generations[2]}',0,'REDACTION',1,'LEASED',8,'{uuid.uuid4()}',now()+interval '1 hour')")
        # Source-owned clock is captured by the real observed lifecycle, never
        # inserted as a caller-supplied timestamp. Preserve it under quarantine.
        sql('inventory',"INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(7001,7001,'RESERVED',now(),now(),now()+interval '1 hour'); INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES(7001,1,2); UPDATE inventory_reservations SET status='CONFIRMED' WHERE id=7001")
        inventory_clock=sql('inventory',"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001")
        assert inventory_clock
        peer_clocks={}
        peer_seeds={
          'delivery': "INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,progress_revision,created_at,updated_at) VALUES(7001,'PEER-FIXTURE','Fixture','COURIER','CANCELLED','SYNCED',3,now(),now())",
          'promotion': "INSERT INTO coupon_reservations(order_id,code,subtotal,discount_amount,state,created_at,updated_at) VALUES(7001,'FIXTURE',10,0,'RELEASED',now(),now())",
          'returns': "INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,version,request_hash,order_verified_at,created_at,updated_at) VALUES(7001,7001,55,'fixture@example.test','RETURN','Fixture','Fixture','REJECTED',1,repeat('b',64),now(),now(),now()); INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) VALUES(7001,1,1,'Fixture',2,1); INSERT INTO return_events(return_id,revision,action,to_status,actor_ref,created_at) VALUES(7001,1,'REJECT','REJECTED','fixture',now())",
          'payment': "INSERT INTO payments(id,order_id,amount,currency,payment_method,status,idempotency_key,provider_declined,request_fingerprint,result_recorded_at,created_at,updated_at) VALUES(7001,7001,10,'PLN','CARD','FAILED','fixture-payment-key',TRUE,repeat('c',64),now(),now(),now()); INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at,published_at) VALUES('PAYMENT_FAILED:7001:fixture@example.test','PAYMENT_FAILED',7001,10,'fixture@example.test','PUBLISHED',1,now(),now(),now(),now())",
          'billing': "INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at) VALUES(7001,7001,55,'fixture@example.test','FIXTURE-1','PERSON','Fixture','Street','00-001','City','PL',10,0,10,'PLN','CANCELLED',now()); INSERT INTO invoice_items(invoice_id,position_no,product_id,product_name,quantity,unit_price,total_price) VALUES(7001,1,1,'Fixture',2,5,10); INSERT INTO event_inbox(event_id,payload_hash,status,related_id,order_id,received_at) VALUES('payment-'||repeat('d',64),repeat('e',64),'ACCEPTED',7001,7001,now())",
          'notification': "INSERT INTO notifications(id,event_type,source_id,recipient,channel,subject,message,status,sent_at,created_at,updated_at) VALUES(7001,'PAYMENT_FAILED',7001,'fixture@example.test','EMAIL','Fixture','Fixture','SENT',now(),now(),now()); INSERT INTO event_inbox(event_id,payload_hash,status,related_id,order_id,received_at) VALUES('payment-'||repeat('d',64),repeat('e',64),'ACCEPTED',7001,7001,now())"
        }
        for alias,seed in peer_seeds.items():
            sql(alias,seed)
            peer_clocks[alias]=sql(alias,"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001")
            assert peer_clocks[alias],alias
        source_hold_digests={};source_hold_ids={};restoration_receipt_digests={}
        for alias in SOURCE_ALIASES:
            source_request=sql('order',f"SELECT source_request_id FROM commerce_retention_hold_source_requests WHERE request_id='{intake_id}' AND source='{alias.upper()}'")
            source_hold_ids[alias]=str(uuid.uuid4())
            sql(alias,f"INSERT INTO commerce_retention_source_holds(hold_id,order_id,guard,reason_code,request_id) VALUES('{source_hold_ids[alias]}',7001,'NO_PRESERVATION_HOLD','PRESERVATION_REQUEST','{source_request}')")
            source_hold_digests[alias]=sql(alias,"SELECT md5(string_agg(h::text,',' ORDER BY hold_id)) FROM commerce_retention_source_holds h")
            sql(alias,f"INSERT INTO commerce_retention_hold_restoration_receipts(intent_id,case_authorization_id,command_hash,order_id,source_request_id,hold_id,binding_epoch,source_revision) SELECT '{uuid.uuid4()}','{uuid.uuid4()}',repeat('a',64),order_id,'{source_request}','{source_hold_ids[alias]}',binding_epoch,source_revision FROM commerce_retention_source_state WHERE order_id=7001")
            restoration_receipt_digests[alias]=sql(alias,"SELECT md5(string_agg(r::text,',' ORDER BY intent_id)) FROM commerce_retention_hold_restoration_receipts r")
            # Negative evidence advances the source revision, not the completed business milestone.
            current_clock=sql(alias,"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001")
            if alias=='inventory':inventory_clock=current_clock
            else:peer_clocks[alias]=current_clock
        acknowledged_attempt=str(uuid.uuid4());uncertain_attempt=str(uuid.uuid4())
        sql('order',f"INSERT INTO commerce_retention_hold_delivery_attempts(request_id,source,attempt_no,attempt_id) VALUES('{intake_id}','RETURNS',1,'{acknowledged_attempt}'),('{intake_id}','INVENTORY',1,'{uncertain_attempt}'); INSERT INTO commerce_retention_hold_delivery_receipts(request_id,source,source_request_id,source_hold_id,attempt_id) SELECT request_id,source,source_request_id,'{source_hold_ids['returns']}','{acknowledged_attempt}' FROM commerce_retention_hold_source_requests WHERE request_id='{intake_id}' AND source='RETURNS'")
        delivery_attempt_digest=sql('order',"SELECT md5(string_agg(a::text,',' ORDER BY request_id,source,attempt_no)) FROM commerce_retention_hold_delivery_attempts a")
        delivery_receipt_digest=sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY request_id,source)) FROM commerce_retention_hold_delivery_receipts r")
        matched_probe,pending_probe=str(uuid.uuid4()),str(uuid.uuid4())
        epoch,revision=map(int,sql('returns',"SELECT binding_epoch||':'||source_revision FROM commerce_retention_source_state WHERE order_id=7001").split(':'))
        for number,probe in enumerate((matched_probe,pending_probe),1):
            sql('order',f"INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id) SELECT request_id,source,{number},'{probe}',source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id='{intake_id}' AND source='RETURNS'")
        sql('order',f"INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES('{matched_probe}','MATCH_AS_OF_READ',clock_timestamp(),{epoch},{revision})")
        missing_probe,pending_after_intent,resolution_id,repair_intent=[str(uuid.uuid4()) for _ in range(4)]
        dispatch_first,dispatch_replay,dispatch_stop,dispatch_authorization=[str(uuid.uuid4()) for _ in range(4)]
        sql('order',f"""INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id)
          SELECT request_id,source,3,'{missing_probe}',source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id='{intake_id}' AND source='RETURNS';
          INSERT INTO commerce_retention_hold_probe_results(probe_id,code,observed_at,binding_epoch,source_revision) VALUES('{missing_probe}','MISSING_AT_SOURCE',clock_timestamp(),{epoch},{revision});
          INSERT INTO commerce_retention_hold_probe_resolutions(probe_id,resolution_id,replacement_probe_id) VALUES('{pending_probe}','{resolution_id}','{missing_probe}');
          INSERT INTO commerce_retention_hold_repair_intents(intent_id,request_id,source,basis_probe_id,source_request_id,source_hold_id)
           SELECT '{repair_intent}',request_id,source,'{missing_probe}',source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id='{intake_id}' AND source='RETURNS';
          INSERT INTO commerce_retention_hold_restoration_attempts(intent_id,attempt_id,attempt_no,mode,scope_hash,case_authorization_id) VALUES('{repair_intent}','{dispatch_first}',1,'RESTORE',repeat('a',64),'{dispatch_authorization}'),('{repair_intent}','{dispatch_replay}',2,'REPLAY_ONLY',repeat('a',64),'{dispatch_authorization}');
          INSERT INTO commerce_retention_hold_restoration_results(attempt_id,code,observed_at,binding_epoch,source_revision) VALUES('{dispatch_replay}','REPLAYED',clock_timestamp(),{epoch},{revision+1});
          INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id)
           SELECT request_id,source,4,'{pending_after_intent}',source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id='{intake_id}' AND source='RETURNS';""")
        version_parts=['HOLD_RESTORATION_REVIEW_V1/version','a'*64,'b'*64,'c'*64]
        version_hash=hashlib.sha256(b''.join(b'\x04'+len(x.encode()).to_bytes(4,'big')+x.encode() for x in version_parts)).hexdigest()
        review_ids={n:str(uuid.uuid4()) for n in (1,2)}
        for ordinal in (1,2):
            sql('order',f"WITH c AS(SELECT clock_timestamp() AS t) INSERT INTO commerce_retention_hold_review_versions(review_id,request_id,ordinal,protocol,scope_hash,history_hash,result_hash,version_hash,reviewed_at,expires_at) SELECT '{review_ids[ordinal]}','{intake_id}',{ordinal},'HOLD_RESTORATION_REVIEW_V1',repeat('a',64),repeat('b',64),repeat('c',64),'{version_hash}',c.t,c.t+interval '15 seconds' FROM c")
        review_version_digest=sql('order',"SELECT md5(string_agg(v::text,',' ORDER BY ordinal)) FROM commerce_retention_hold_review_versions v")

        review_step=str(uuid.uuid4())
        sql('order',f"""INSERT INTO commerce_retention_hold_probes(request_id,source,probe_no,probe_id,source_request_id,source_hold_id)
          SELECT request_id,source,5,'{review_step}',source_request_id,source_hold_id FROM commerce_retention_hold_delivery_receipts WHERE request_id='{intake_id}' AND source='RETURNS';
          INSERT INTO commerce_retention_hold_review_steps(step_id,review_id,intent_id,source,probe_id,prepared_scope_hash,prepared_history_hash,prepared_result_hash,expires_at)
           SELECT '{review_step}',review_id,'{repair_intent}','RETURNS','{review_step}',scope_hash,repeat('d',64),repeat('e',64),expires_at FROM commerce_retention_hold_review_versions WHERE review_id='{review_ids[2]}';
          INSERT INTO commerce_retention_hold_restoration_stops(intent_id,stop_id) VALUES('{repair_intent}','{dispatch_stop}');""")
        review_step_digest=sql('order',"SELECT md5(string_agg(s::text,',' ORDER BY step_id)) FROM commerce_retention_hold_review_steps s")
        probe_digest=sql('order',"SELECT md5(string_agg(p::text,',' ORDER BY probe_no)) FROM commerce_retention_hold_probes p")
        probe_result_digest=sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY probe_id)) FROM commerce_retention_hold_probe_results r")
        resolution_digest=sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY probe_id)) FROM commerce_retention_hold_probe_resolutions r")
        repair_intent_digest=sql('order',"SELECT md5(string_agg(i::text,',' ORDER BY intent_id)) FROM commerce_retention_hold_repair_intents i")
        dispatch_digests={suffix:sql('order',f"SELECT md5(string_agg(t::text,',' ORDER BY t::text)) FROM commerce_retention_hold_restoration_{suffix} t") for suffix in ('attempts','results','stops')}
        sql('order',f"INSERT INTO commerce_retention_payment_evidence(order_id,generation,phase,binding_epoch,source_revision,event_hash,event_count,expectation_hash,policy) VALUES(7001,'{generations[0]}','REDACTION',1,0,repeat('f',64),1,repeat('a',64),'D02-P02-2026-10-30.1')")
        for generation,source in ((ancestor,'PAYMENT'),(ancestor,'DELIVERY'),(ancestor,'INVENTORY')):
            sql('order',f"INSERT INTO commerce_retention_source_receipts(order_id,generation,phase,source,order_version,policy,binding_epoch,source_revision,receipt_id,observation_hash,case_state,retry_state,observed_at) VALUES(7001,'{generation}','REDACTION','{source}',0,'D02-P02-2026-10-30.1',1,0,'{uuid.uuid4()}',repeat('b',64),'UNKNOWN','UNKNOWN',clock_timestamp())")
        for alias in SOURCE_ALIASES:
            revision=sql(alias,"SELECT source_revision FROM commerce_retention_source_state WHERE order_id=7001")
            sql('order',f"INSERT INTO commerce_retention_source_receipts(order_id,generation,phase,source,order_version,policy,binding_epoch,source_revision,receipt_id,observation_hash,case_state,retry_state,observed_at,hold_state) VALUES(7001,'{generations[0]}','REDACTION','{alias.upper()}',0,'D02-P02-2026-10-30.1',1,{revision},'{uuid.uuid4()}',repeat('c',64),'UNKNOWN','UNKNOWN',clock_timestamp(),'BLOCKED')")
        observation_receipt_digest=sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY generation,source)) FROM commerce_retention_source_receipts r")
        for n,generation in enumerate(generations,1):
            intent=str(uuid.uuid4())
            sql('order',f"INSERT INTO commerce_retention_intents(intent_id,order_id,generation,order_version,phase,policy,prepared_proof_revision,order_hold_revision,customer_hold_revision,last_request_id) VALUES('{intent}',{7000+n},'{generation}',0,'REDACTION','D02-P02-2026-10-30.1',0,0,0,'{intent}')")
            if n==1:
                for ordinal in (1,2,3):
                    observation_round=str(uuid.uuid4())
                    sql('order',f"INSERT INTO commerce_retention_observation_rounds(round_id,intent_id,intent_revision,ordinal) VALUES('{observation_round}','{intent}',0,{ordinal})")
                    if ordinal>=2:
                        source='PAYMENT' if ordinal==2 else 'INVENTORY'
                        sql('order',f"INSERT INTO commerce_retention_observation_leases(round_id,source,binding_epoch,source_revision) VALUES('{observation_round}','{source}',1,0)")
                    if ordinal==2:sql('order',f"UPDATE commerce_retention_observation_leases SET release_confirmed_at=clock_timestamp() WHERE round_id='{observation_round}'")
                    if ordinal<=2:sql('order',f"UPDATE commerce_retention_observation_rounds SET finished_at=clock_timestamp() WHERE round_id='{observation_round}'")
                    if ordinal==3:sql('order',f"UPDATE commerce_retention_observation_rounds SET cleanup_only=TRUE,recovery_token='{uuid.uuid4()}',recovery_until=clock_timestamp()+interval '15 seconds',recovery_attempts=1 WHERE round_id='{observation_round}'")
            if n>=2:sql('order',f"UPDATE commerce_retention_intents SET state='STOP_REQUESTED',revision=1,last_request_id='{uuid.uuid4()}' WHERE intent_id='{intent}'")
            if n==3:sql('order',f"UPDATE commerce_retention_intents SET state='CANCELLED',revision=2,last_request_id='{uuid.uuid4()}' WHERE intent_id='{intent}'")
        intent_history_digest=sql('order',"SELECT md5(string_agg(e::text,',' ORDER BY intent_id,revision)) FROM commerce_retention_intent_events e")
        observation_round_digest=sql('order',"SELECT md5(string_agg((to_jsonb(r)-'restore_blocked'-'recovery_token'-'recovery_until')::text,',' ORDER BY round_id)) FROM commerce_retention_observation_rounds r")
        observation_lease_digest=sql('order',"SELECT md5(string_agg(l::text,',' ORDER BY round_id,source)) FROM commerce_retention_observation_leases l")
        redis('XADD','payment-events','1-0','eventId','fixture-event','orderId',123)
        redis('XGROUP','CREATE','payment-events','notification-service','0')
        redis('XREADGROUP','GROUP','notification-service','fixture-consumer','COUNT',1,'STREAMS','payment-events','>')
        redis('XADD','invoice-events','1-0','eventId','fixture-invoice')
        for key in ('customer:mfa:login:random','customer:mfa:enrollment:56','customer:mfa:webauthn:login:random','customer:mfa:webauthn:registration:56'):redis('SET',key,'fixture','EX',300)
        for key in ('security:login:lock:person-56@fixture.example','dedup:fixture','privacy:stream:payment-events:order:123'):redis('SET',key,'preserved')
        # Capture a live observation lease. Restore must retire it in quarantine,
        # not wait for its old deadline or treat it as authorization to mutate.
        fence_marker_digests={}
        for alias in SOURCE_ALIASES:
            sql(alias,"INSERT INTO commerce_retention_fence_tokens(order_id,binding_epoch,fence_token,released) VALUES(7001,1,'11111111-1111-4111-8111-111111111111',FALSE),(7001,1,'22222222-2222-4222-8222-222222222222',TRUE)")
            fence_marker_digests[alias]=sql(alias,"SELECT md5(string_agg(t::text,',' ORDER BY order_id,binding_epoch,fence_token)) FROM commerce_retention_fence_tokens t")
            sql(alias,"UPDATE commerce_retention_source_state SET retention_fence_token='11111111-1111-4111-8111-111111111111',retention_fence_until=clock_timestamp()+interval '1 day' WHERE order_id=7001")
        bundle=recovery.capture(sources,fixture.recipient,fixture.store.backup_root)
        print('PASS encrypted pre-deletion snapshot: 14 databases + Redis',flush=True)
        capsule={'schemaVersion':1,'customerId':'55','requestId':str(uuid.uuid4()),'confirmedAt':'2026-10-29T00:00:00Z','accountPolicyHash':privacy.policies()['core'],'commercePolicyHash':privacy.policies()['commerce'],'financePolicyHash':None}
        entry,fixture.pin=fixture.store.append(capsule)
        # Lose all source databases: only the separately held journal knows the later request.
        for identifier in containers:run('docker','rm','-f','-v',identifier)
        containers.clear()
        privacy.drill(bundle,fixture.identity,fixture.store,fixture.pin,directory/'drill-report.json')
        accepted=json.loads((directory/'drill-report.json').read_text());assert accepted['status']=='PASS' and not accepted['releaseAllowed']
        with recovery.Restored(bundle,fixture.identity) as restored:
            assert restored.sql('customer','SELECT count(*) FROM customers WHERE id=55')=='1'
            before=restored.redis_json('XRANGE','payment-events','-','+')
            groups=restored.redis_json('XINFO','GROUPS','payment-events')
            pending=restored.redis_json('XPENDING','payment-events','notification-service')
            result=privacy.replay(restored,[entry]);assert result['coreReplayed']==1 and not result['releaseAllowed']
            assert result['commerceAssessments']['invalidated']==3
            assert result['commerceHolds']['blocked']==3
            assert result['commerceHoldIntake']['blocked']==1 and not result['commerceHoldIntake']['sourceDeliveryConfirmed']
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_hold_requests WHERE restore_blocked")=='1'
            assert restored.sql('order',"SELECT md5(string_agg((to_jsonb(r)-'restore_blocked')::text,',' ORDER BY request_id)) FROM commerce_retention_hold_requests r")==intake_identity_digest
            assert restored.sql('order',"SELECT md5(string_agg(s::text,',' ORDER BY request_id,source)) FROM commerce_retention_hold_source_requests s")==intake_plan_digest
            assert privacy.invalidate_hold_intake(restored)['blocked']==0
            assert result['commerceHoldIntake']['deliveryHistoryVerified']
            assert result['commerceHoldIntake']['reconciliationHistoryVerified']
            assert result['commerceHoldIntake']['recoveryPlanningVerified']
            assert result['commerceHoldIntake']['restorationDispatchVerified']
            assert result['commerceHoldIntake']['reviewVersionsVerified']
            assert result['commerceHoldIntake']['reviewStepsVerified']
            assert restored.sql('order',"SELECT md5(string_agg(v::text,',' ORDER BY ordinal)) FROM commerce_retention_hold_review_versions v")==review_version_digest
            assert restored.sql('order',"SELECT md5(string_agg(s::text,',' ORDER BY step_id)) FROM commerce_retention_hold_review_steps s")==review_step_digest
            for suffix,digest in dispatch_digests.items():assert restored.sql('order',f"SELECT md5(string_agg(t::text,',' ORDER BY t::text)) FROM commerce_retention_hold_restoration_{suffix} t")==digest
            assert restored.sql('order',f"SELECT count(*) FROM commerce_retention_hold_restoration_results WHERE attempt_id='{dispatch_first}'")=='0'
            assert restored.sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY probe_id)) FROM commerce_retention_hold_probe_resolutions r")==resolution_digest
            assert restored.sql('order',"SELECT md5(string_agg(i::text,',' ORDER BY intent_id)) FROM commerce_retention_hold_repair_intents i")==repair_intent_digest
            assert restored.sql('order',f"SELECT count(*) FROM commerce_retention_hold_probe_results WHERE probe_id='{pending_after_intent}'")=='0'
            assert restored.sql('order',"SELECT md5(string_agg(p::text,',' ORDER BY probe_no)) FROM commerce_retention_hold_probes p")==probe_digest
            assert restored.sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY probe_id)) FROM commerce_retention_hold_probe_results r")==probe_result_digest
            assert restored.sql('order',f"SELECT count(*) FROM commerce_retention_hold_probe_results WHERE probe_id='{pending_probe}'")=='0'
            assert restored.sql('order',"SELECT md5(string_agg(a::text,',' ORDER BY request_id,source,attempt_no)) FROM commerce_retention_hold_delivery_attempts a")==delivery_attempt_digest
            assert restored.sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY request_id,source)) FROM commerce_retention_hold_delivery_receipts r")==delivery_receipt_digest
            assert restored.sql('order',"SELECT string_agg(hold_id::text||':'||scope_type||':'||subject_id::text||':'||guard||':'||request_id::text||':'||created_at::text,',' ORDER BY hold_id) FROM commerce_retention_holds")==hold_history
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_hold_scopes WHERE restore_blocked")=='3'
            assert result['commerceOrderState']['blocked']==3
            assert restored.sql('order',"SELECT string_agg(order_id::text||':'||reconciled_at::text,',' ORDER BY order_id) FROM commerce_retention_order_state WHERE restore_blocked")==milestone_clocks
            assert result['commerceBindings']['blocked']==21
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_bindings WHERE restore_blocked AND run_token IS NULL AND run_until IS NULL")=='21'
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_bindings WHERE state='BOUND' AND expected_epoch=0 AND bound_epoch=1 AND source_revision=0 AND NOT invalidated")=='7'
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_bindings WHERE state='FAILED' AND attempts=8 AND code='RETRY_LIMIT'")=='1'
            assert result['commerceSources']['bindingsBlocked']==14 and result['commerceSources']['leasesRetired']==7
            assert restored.sql('inventory',"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==inventory_clock
            for alias,clock in peer_clocks.items():
                assert restored.sql(alias,"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==clock,alias
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_payment_evidence p JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE NOT j.current AND p.event_hash=repeat('f',64)")=='1'
            assert restored.sql('order',"SELECT md5(string_agg(r::text,',' ORDER BY generation,source)) FROM commerce_retention_source_receipts r")==observation_receipt_digest
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_source_receipts r JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE NOT j.current AND r.case_state='UNKNOWN' AND r.retry_state='UNKNOWN'")=='10'
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_source_receipts WHERE hold_state='BLOCKED'")=='7'
            for alias,digest in source_hold_digests.items():
                assert restored.sql(alias,"SELECT md5(string_agg(h::text,',' ORDER BY hold_id)) FROM commerce_retention_source_holds h")==digest,alias
                assert restored.sql(alias,"SELECT md5(string_agg(r::text,',' ORDER BY intent_id)) FROM commerce_retention_hold_restoration_receipts r")==restoration_receipt_digests[alias],alias
                assert result['commerceSources']['sources'][alias]['restorationReceiptsVerified'],alias
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_intents WHERE restore_blocked AND state='QUARANTINED'")=='3'
            assert restored.sql('order',"SELECT md5(string_agg(e::text,',' ORDER BY intent_id,revision)) FROM commerce_retention_intent_events e WHERE state<>'QUARANTINED'")==intent_history_digest
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_intent_events WHERE state='QUARANTINED'")=='3'
            assert privacy.invalidate_retention_intents(restored)['blocked']==0
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_intent_events")=='9'
            assert result['commerceObservationRounds']['blocked']==3
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_observation_rounds WHERE restore_blocked")=='3'
            assert restored.sql('order',"SELECT md5(string_agg((to_jsonb(r)-'restore_blocked'-'recovery_token'-'recovery_until')::text,',' ORDER BY round_id)) FROM commerce_retention_observation_rounds r")==observation_round_digest
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_observation_rounds WHERE recovery_token IS NOT NULL OR recovery_until IS NOT NULL")=='0'
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_observation_rounds WHERE cleanup_only AND recovery_attempts=1")=='1'
            assert restored.sql('order',"SELECT md5(string_agg(l::text,',' ORDER BY round_id,source)) FROM commerce_retention_observation_leases l")==observation_lease_digest
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_observation_leases WHERE release_confirmed_at IS NULL")=='1'
            assert privacy.invalidate_observation_rounds(restored)['blocked']==0
            assert restored.sql('order',f"SELECT preparation_predecessor::text FROM commerce_retention_jobs WHERE order_id=7001 AND generation='{generations[0]}' AND NOT current")==ancestor
            assert restored.sql('order',f"SELECT e.field_scope_hash FROM commerce_retention_evidence e JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE e.order_id=7001 AND e.generation='{generations[0]}' AND e.source='FIELD_SCOPE_REVIEWED' AND NOT j.current")==field_scope_hash
            for alias in SOURCE_ALIASES:
                assert result['commerceSources']['sources'][alias]['bindingsBlocked']==2
                assert restored.sql(alias,"SELECT count(*) FROM commerce_retention_source_state WHERE restore_blocked AND invalidated AND binding_epoch=2")=='2'
                assert restored.sql(alias,"SELECT count(*) FROM commerce_retention_source_state WHERE retention_fence_token IS NOT NULL OR retention_fence_until IS NOT NULL")=='0'
                assert restored.sql(alias,"SELECT md5(string_agg(t::text,',' ORDER BY order_id,binding_epoch,fence_token)) FROM commerce_retention_fence_tokens t")==fence_marker_digests[alias]
                assert restored.sql(alias,"SELECT count(*) FROM commerce_retention_change_outbox WHERE state='FAILED' AND attempts=8 AND code='RETRY_LIMIT' AND run_token IS NULL AND run_until IS NULL AND binding_epoch=1")=='1'
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_jobs WHERE NOT current AND state='STALE' AND run_token IS NULL AND run_until IS NULL")=='4'
            assert restored.sql('order','SELECT count(*) FROM commerce_retention_evidence')=='4'
            assert restored.sql('order',"SELECT count(*) FROM orders WHERE customer_email='fixture-order@example.test'")=='3'
            for alias,table in (('customer','customers'),('wishlist','wishlist_items'),('review','reviews'),('recommendation','recommendation_requests')):
                owner='id' if alias=='customer' else 'customer_id'
                assert restored.sql(alias,f'SELECT count(*) FROM {table} WHERE {owner}=55')=='0',alias
                assert restored.sql(alias,f'SELECT count(*) FROM {table} WHERE {owner}=56')=='1',alias
            for alias in recovery.aliases():assert restored.sql(alias,'SELECT count(*) FROM account_privacy_fences WHERE customer_id=55')=='1',alias
            assert restored.sql('review','SELECT count(*) FROM review_privacy_keys WHERE customer_id=55')=='1'
            assert restored.sql('customer',"SELECT credential_id FROM user_credentials")=='healthy'
            assert restored.sql('customer','SELECT customer_id FROM customer_recovery_codes')=='56'
            assert restored.sql('customer',"SELECT customer_id||'|'||secret_ciphertext FROM customer_totp_methods")=='56|fixture-ciphertext-56'
            assert restored.sql('customer','SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL')=='0'
            assert restored.sql('customer','SELECT count(*) FROM password_reset_tokens')=='0'
            assert restored.sql('customer',"SELECT phase='QUARANTINED' AND auth_epoch<>'legacy' FROM restore_security_state")=='t'
            assert restored.sql('customer',"SELECT restore_imported AND status_token_hash IS NULL AND status_expires_at IS NULL AND commerce_cleanup_pending FROM account_privacy_requests WHERE customer_id=55")=='t'
            assert restored.redis_json('XRANGE','payment-events','-','+')==before
            assert restored.redis_json('XINFO','GROUPS','payment-events')==groups
            assert restored.redis_json('XPENDING','payment-events','notification-service')==pending
            assert restored.redis_json('XLEN','invoice-events')==1
            for pattern in ('customer:mfa:login:*','customer:mfa:enrollment:*','customer:mfa:webauthn:login:*','customer:mfa:webauthn:registration:*'):assert not restored.redis_json('KEYS',pattern)
            for key in ('security:login:lock:person-56@fixture.example','dedup:fixture','privacy:stream:payment-events:order:123'):assert restored.redis_json('GET',key)=='preserved'
            again=privacy.replay(restored,[entry]);assert again['coreReplayed']==1 and again['commerceAssessments']['invalidated']==0
            assert again['commerceBindings']['blocked']==0
            assert again['commerceHolds']['blocked']==0
            assert restored.sql('order',"SELECT sum(revision) FROM commerce_retention_hold_scopes")=='5'
            assert again['commerceOrderState']['blocked']==0
            assert again['commerceSources']['bindingsBlocked']==0 and again['commerceSources']['leasesRetired']==0
            assert restored.sql('inventory',"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==inventory_clock
            for alias,clock in peer_clocks.items():
                assert restored.sql(alias,"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==clock,alias
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_payment_evidence p JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE NOT j.current AND p.event_hash=repeat('f',64)")=='1'
            assert restored.sql('order',f"SELECT preparation_predecessor::text FROM commerce_retention_jobs WHERE order_id=7001 AND generation='{generations[0]}' AND NOT current")==ancestor
            assert restored.sql('order',f"SELECT e.field_scope_hash FROM commerce_retention_evidence e JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE e.order_id=7001 AND e.generation='{generations[0]}' AND e.source='FIELD_SCOPE_REVIEWED' AND NOT j.current")==field_scope_hash
            for alias in SOURCE_ALIASES:
                assert restored.sql(alias,"SELECT count(*) FROM commerce_retention_source_state WHERE binding_epoch=2")=='2'
            assert int(restored.sql('customer',"SELECT nextval(pg_get_serial_sequence('customers','id'))"))>56
            # A different stable identity must never overwrite a restored fence.
            restored.sql('review',"UPDATE account_privacy_fences SET request_id='"+str(uuid.uuid4())+"' WHERE customer_id=55")
            try:privacy.replay(restored,[entry])
            except ValueError:pass
            else:raise AssertionError('Identity conflict accepted')
        with recovery.Restored(bundle,fixture.identity) as restored:
            empty=privacy.replay(restored,[])
            assert empty['requests']==0 and empty['commerceAssessments']['invalidated']==3 and not empty['releaseAllowed']
            assert empty['commerceBindings']['blocked']==21
            assert empty['commerceHolds']['blocked']==3
            assert restored.sql('order',"SELECT string_agg(hold_id::text||':'||scope_type||':'||subject_id::text||':'||guard||':'||request_id::text||':'||created_at::text,',' ORDER BY hold_id) FROM commerce_retention_holds")==hold_history
            assert empty['commerceOrderState']['blocked']==3
            assert empty['commerceSources']['bindingsBlocked']==14 and empty['commerceSources']['leasesRetired']==7
            assert restored.sql('inventory',"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==inventory_clock
            for alias,clock in peer_clocks.items():
                assert restored.sql(alias,"SELECT local_reconciled_at::text FROM commerce_retention_source_state WHERE order_id=7001 AND restore_blocked")==clock,alias
            assert restored.sql('order',"SELECT count(*) FROM commerce_retention_payment_evidence p JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE NOT j.current AND p.event_hash=repeat('f',64)")=='1'
            assert restored.sql('order',f"SELECT preparation_predecessor::text FROM commerce_retention_jobs WHERE order_id=7001 AND generation='{generations[0]}' AND NOT current")==ancestor
            assert restored.sql('order',f"SELECT e.field_scope_hash FROM commerce_retention_evidence e JOIN commerce_retention_jobs j USING(order_id,generation,phase) WHERE e.order_id=7001 AND e.generation='{generations[0]}' AND e.source='FIELD_SCOPE_REVIEWED' AND NOT j.current")==field_scope_hash
            assert restored.sql('order','SELECT count(*) FROM commerce_retention_evidence')=='4'
        try:privacy.drill(bundle,fixture.identity,fixture.store,fixture.store.pin(fixture.store.load()[0]).replace(':1:',':0:'),directory/'blocked-report.json')
        except ValueError:pass
        else:raise AssertionError('Stale independent pin accepted')
        assert json.loads((directory/'blocked-report.json').read_text())['status']=='BLOCKED'
        report.update(status='PASS',databases=14,coreErasureReapplied=True,allFencesReplayed=True,otherAccountMfaPreserved=True,allSessionsRevoked=True,authenticationChallengesRemoved=True,businessStreamsGroupsPendingDedupPreserved=True,repeatIdempotent=True,identityConflictBlocked=True,staleHeadBlocked=True,commerceAssessmentsInvalidatedForAllAccountsAndGuests=True,emptyJournalAlsoInvalidatesAssessments=True,assessmentReceiptsAndOrderDataPreserved=True,cleanupComplete=True,commercialReconciliationRequired=True)
        report.update(fieldScopeReceiptPreservedNotCurrent=True,preparationLineagePreserved=True,retentionHoldScopesQuarantined=3,retentionHoldHistoryPreserved=True,retentionHoldsReplayIdempotent=True,peerMilestoneClocksPreserved=True,peerMilestonesQuarantined=6,paymentReceiptPreservedButNotCurrent=True,inventoryMilestonesQuarantined=1,inventoryMilestoneClocksPreserved=True,orderMilestonesQuarantined=3,orderMilestoneClocksPreserved=True,coordinatorCommandsQuarantined=21,coordinatorReceiptsPreserved=7,coordinatorRetryBudgetPreserved=True,sourceAliases=list(SOURCE_ALIASES),allImplementedSourceBindingsQuarantined=True,returnsSourceBindingsQuarantined=True,sourceLeasesRetiredWithoutBudgetReset=True,sourceOutboxReceiptsPreserved=True)
        report.update(sourceObservationReceiptsPreserved=10,inventoryGuardObservationReceiptPreserved=True,sourceObservationReceiptsPreservedButNotCurrent=True,returnsSourceHoldHistoryPreserved=True,sourcePreservationBlockObservationPreserved=True,sourceHoldRegistriesPreserved=7,sourceHoldRegistryOwners=list(SOURCE_ALIASES),sourcePreservationBlockObservationsPreserved=7)
        report.update(holdIntakeRequestsQuarantined=1,holdIntakeIdentitiesPreserved=True,holdIntakeSourcePlansPreserved=7,holdIntakeSourceDeliveryConfirmed=False,holdIntakeAutomaticDispatch=False)
        report.update(holdDeliveryAttemptsPreserved=2,holdDeliveryReceiptsPreserved=1,holdDeliveryUnknownAttemptNotAcknowledged=True,holdDeliveryAutomaticResume=False)
        report.update(holdReconciliationProbesPreserved=5,holdReconciliationResultsPreserved=2,holdReconciliationPendingNotAcknowledged=True,holdReconciliationAutomaticResume=False,holdReconciliationHistoryIsCurrentAuthority=False)
        report.update(holdProbeResolutionsPreserved=1,holdRepairIntentsPreserved=1,holdRecoveryPlanningAutomaticExecution=False,holdProbeResolutionIsExecutorTermination=False)
        report.update(sourceHoldRestorationReceiptsPreserved=7,restoredSourceQuarantineBlocksRestorationAndReplay=True,sourceRestorationHistoryIsCurrentAuthority=False)
        report.update(rootRestorationAttemptsPreserved=2,rootRestorationResultsPreserved=1,rootRestorationStopsPreserved=1,rootRestorationPendingNotAcknowledged=True,rootRestorationAutomaticResume=False,rootRestorationStopIsExecutorTermination=False)
        report.update(reviewStepsPreserved=1,reviewStepsResumed=False,reviewStepLeaseRenewed=False)
        report.update(reviewVersionSnapshotsPreserved=2,reviewVersionsRemainDiagnosticHistory=True,reviewVersionQuarantineBlocksUse=True,reviewVersionsRenewedAfterRestore=False)
        report.update(retentionIntentsQuarantined=3,retentionIntentHistoryPreserved=True,retentionIntentReplayIdempotent=True,retentionIntentQuarantineIsExecutionTermination=False)
        report.update(observationRoundsQuarantined=3,observationLeaseHistoryPreserved=2,observationPendingNotAcknowledged=True,observationRoundReplayIdempotent=True,observationRecoveryRequestsSent=0)
        report.update(sourceFenceReplayMarkersPreserved=14,sourceFenceReplayMarkersOwners=7,sourceFenceMarkerEpochsNotRewritten=True)
        report.update(observationRecoveryOwnershipCleared=True,observationCleanupModeAndBudgetPreserved=True,observationRecoveryAutomaticResume=False)
        print('PASS source loss, signed replay, source binding quarantine, Redis preservation and fail-closed conflicts',flush=True)
    finally:
        for identifier in containers:subprocess.run(['docker','rm','-f','-v',identifier],capture_output=True)
        for path in (fixture.identity,fixture.signing):path.unlink(missing_ok=True)
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n');print('Report: '+str(directory/'report.json'),flush=True)
if __name__=='__main__':main()
