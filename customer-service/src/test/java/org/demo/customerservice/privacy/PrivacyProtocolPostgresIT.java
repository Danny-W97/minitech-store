package org.demo.customerservice.privacy;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED) @Testcontainers
class PrivacyProtocolPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("privacy_fixture").withUsername("fixture").withPassword("fixture-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);p.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");}
    PrivacyRequestStore store;PrivacyFenceStore fences;JdbcTemplate jdbc,guarded;TransactionTemplate transactions;DataSource wrapped;
    final CustomerPrincipal owner=new CustomerPrincipal(1L,"owner@fixture.example"),other=new CustomerPrincipal(2L,"other@fixture.example");
    final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(4);final String proof="S".repeat(43);
    @BeforeEach void setup(){
        RequestContextHolder.resetRequestAttributes();var raw=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        jdbc=new JdbcTemplate(raw);wrapped=PrivacyDataSources.wrap(raw);var manager=new DataSourceTransactionManager(wrapped);
        store=new PrivacyRequestStore(wrapped,manager);fences=new PrivacyFenceStore(wrapped,manager);guarded=new JdbcTemplate(wrapped);transactions=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS privacy_probe(id BIGSERIAL PRIMARY KEY,customer_id BIGINT,value TEXT)");
        jdbc.execute("TRUNCATE privacy_probe,account_privacy_services,account_privacy_requests,account_privacy_fences,account_export_chunks,account_export_tasks,account_export_jobs,customers,user_credentials CASCADE");
        jdbc.update("INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) SELECT n,CASE n WHEN 1 THEN 'owner@fixture.example' ELSE 'other@fixture.example' END,?,'Jan','Test','Testowa','00-001','Warszawa','PL',true,now(),now() FROM generate_series(1,2)n",passwords.encode("fixture-password"));
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void productionCleanupRequiresSignedDurableJournalReceipt() throws Exception {
        var pair=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var ledger=UUID.randomUUID();
        var verifier=new JournalReceiptVerifier(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),ledger.toString());
        var secured=new PrivacyRequestStore(wrapped,new DataSourceTransactionManager(wrapped),verifier);
        var r=ready();assertThat(secured.claimAccountCleanup()).isNull();assertThat(secured.claimCommerceCleanup()).isNull();assertThat(secured.claimFinance()).isNull();
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var c=json.valueToTree(secured.journalCapsule(r.id()));var e=json.createObjectNode();e.put("schemaVersion",1);e.put("ledgerId",ledger.toString());e.put("sequence",1);e.put("previousHash","0".repeat(64));e.set("capsule",c);
        String frame="A43.37/entry/1\n"+ledger+"\n1\n"+"0".repeat(64)+"\n"+JournalReceiptVerifier.capsuleHash(c)+"\n";
        var signature=java.security.Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update(frame.getBytes(java.nio.charset.StandardCharsets.US_ASCII));e.put("entryHash",Access.hash(frame));e.put("signature",Base64.getEncoder().encodeToString(signature.sign()));
        String receipt=Base64.getUrlEncoder().withoutPadding().encodeToString(e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejects(409,()->secured.acknowledgeJournal(r.id(),"bogus"));assertThat(secured.claimAccountCleanup()).isNull();
        secured.acknowledgeJournal(r.id(),receipt);assertThat(secured.journalHeader(r.id())).isEqualTo(receipt);assertThat(secured.claimAccountCleanup()).isNotNull();
        assertThat(secured.journalStatus(r.id())).containsEntry("acknowledged",true);
        jdbc.update("UPDATE account_privacy_requests SET account_cleanup_policy_hash=NULL WHERE id=?",r.id());rejects(409,()->secured.acknowledgeJournal(r.id(),receipt));
    }
    @Test void restoreEpochInvalidatesOldTokensAndQuarantineBlocksIssuingNewOnes(){
        var epoch=new org.demo.customerservice.auth.RestoreEpochStore(wrapped);
        jdbc.update("UPDATE restore_security_state SET auth_epoch='legacy',phase='NORMAL' WHERE id=1");
        var jwt=new org.demo.customerservice.auth.JwtService(new com.fasterxml.jackson.databind.ObjectMapper(),Base64.getEncoder().encodeToString(new byte[48]),"","","",900);
        org.springframework.test.util.ReflectionTestUtils.setField(jwt,"restoreEpoch",epoch);
        String before=jwt.createAccessToken(owner);assertThat(jwt.parseAccessToken(before)).isPresent();
        jdbc.update("UPDATE restore_security_state SET auth_epoch=? WHERE id=1",UUID.randomUUID().toString());assertThat(jwt.parseAccessToken(before)).isEmpty();assertThat(jwt.parseAccessToken(jwt.createAccessToken(owner))).isPresent();
        jdbc.update("UPDATE restore_security_state SET phase='QUARANTINED' WHERE id=1");assertThat(jwt.parseAccessToken(before)).isEmpty();rejects(503,()->jwt.createAccessToken(owner));
        jdbc.update("UPDATE restore_security_state SET auth_epoch='legacy',phase='NORMAL' WHERE id=1");
    }
    void actor(long id){var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,new Actor("CUSTOMER",null,id,"fixture"));RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    PrivacyRequestStore.Request confirm(PrivacyRequestStore.Request r){return store.confirm(r.id(),owner,r.hash(),"fixture-password",proof,Set.of("pwd"),passwords);}
    void rejects(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(status));}
    @Test void previewIsOwnerBoundIdempotentAndDoesNotDisableTheAccount(){var a=store.preview(owner,UUID.randomUUID());var b=store.preview(owner,UUID.randomUUID());assertThat(a.id()).isEqualTo(b.id());assertThat(a.hash()).isEqualTo(b.hash());assertThat(a.phase()).isEqualTo("PREVIEW");assertThat(jdbc.queryForObject("SELECT enabled FROM customers WHERE id=1",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_fences",Long.class)).isZero();rejects(401,()->store.preview(new CustomerPrincipal(1L,"other@fixture.example"),UUID.randomUUID()));}
    @Test void confirmationCreatesFourteenPendingStepsAndAnIdOnlyFence(){var r=confirm(store.preview(owner,UUID.randomUUID()));assertThat(r.phase()).isEqualTo("FENCING");assertThat(r.statusExpires()).isEqualTo(r.confirmed().plusSeconds(604800));assertThat(jdbc.queryForObject("SELECT enabled FROM customers WHERE id=1",Boolean.class)).isFalse();assertThat(store.services(r.id())).hasSize(14);assertThat(jdbc.queryForMap("SELECT * FROM account_privacy_fences")).containsOnlyKeys("customer_id","request_id","state");String snapshot=jdbc.queryForObject("SELECT row_to_json(r)::text FROM account_privacy_requests r",String.class);assertThat(snapshot).doesNotContain(proof,"fixture-password","owner@fixture.example");assertThat(store.status(r.id(),proof).id()).isEqualTo(r.id());}
    @Test void wrongPasswordPlanOwnerAndExpiredPreviewDoNotMutateTheAccount(){var r=store.preview(owner,UUID.randomUUID());assertThatThrownBy(()->store.confirm(r.id(),owner,r.hash(),"wrong",proof,Set.of(),passwords)).isInstanceOf(org.demo.customerservice.customer.InvalidCredentialsException.class);rejects(409,()->store.confirm(r.id(),owner,"b".repeat(64),"fixture-password",proof,Set.of(),passwords));rejects(404,()->store.confirm(r.id(),other,r.hash(),"fixture-password",proof,Set.of(),passwords));jdbc.update("UPDATE account_privacy_requests SET preview_expires_at=now()-interval '1 second'");rejects(410,()->confirm(r));assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_fences",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT enabled FROM customers WHERE id=1",Boolean.class)).isTrue();}
    @Test void mfaConfigurationRequiresAValidatedMatchingAuthenticationMethod(){var r=store.preview(owner,UUID.randomUUID());jdbc.update("INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,last_used_time_step,created_at,updated_at) VALUES(1,'fixture-private',true,0,now(),now())");rejects(403,()->confirm(r));assertThat(store.confirm(r.id(),owner,r.hash(),"fixture-password",proof,Set.of("pwd","totp"),passwords).phase()).isEqualTo("FENCING");}
    @Test void statusProofIsScopedAndCannotBeRecoveredOrExtended(){var r=confirm(store.preview(owner,UUID.randomUUID()));rejects(401,()->store.status(r.id(),null));rejects(404,()->store.status(r.id(),"X".repeat(43)));rejects(404,()->store.status(UUID.randomUUID(),proof));assertThat(store.retry(r.id()).statusExpires()).isEqualTo(r.statusExpires());jdbc.update("UPDATE account_privacy_requests SET confirmed_at=now()-interval '192 hours',status_expires_at=now()-interval '24 hours'");rejects(404,()->store.status(r.id(),proof));store.cleanup();assertThat(jdbc.queryForObject("SELECT status_token_hash IS NULL FROM account_privacy_requests",Boolean.class)).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_fences",Long.class)).isEqualTo(1);assertThat(store.operator(r.id()).phase()).isEqualTo("FENCING");}
    @Test void statusExpiryIsAbsoluteAcrossDaylightSavingAndDatabaseSessionZones(){
        var r=confirm(store.preview(owner,UUID.randomUUID()));
        transactions.executeWithoutResult(status->{guarded.execute("SET LOCAL TIME ZONE 'Europe/Warsaw'");guarded.update("UPDATE account_privacy_requests SET confirmed_at=TIMESTAMPTZ '2026-10-21 12:00:00+02',status_expires_at=TIMESTAMPTZ '2026-10-28 11:00:00+01' WHERE id=?",r.id());});
        transactions.executeWithoutResult(status->{guarded.execute("SET LOCAL TIME ZONE 'UTC'");guarded.update("UPDATE account_privacy_requests SET status_token_hash=NULL WHERE id=?",r.id());});
        assertThat(store.operator(r.id()).statusExpires()).isEqualTo(store.operator(r.id()).confirmed().plusSeconds(604800));
    }
    @Test void allAcknowledgementsStopAtWaitingAdaptersNeverErased(){var r=confirm(store.preview(owner,UUID.randomUUID()));for(int n=0;n<14;n++){var work=store.claim();assertThat(work).isNotNull();store.finish(work,true,"ignored");}assertThat(store.operator(r.id()).phase()).isEqualTo("WAITING_ADAPTERS");assertThat(store.claim()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM customers",Long.class)).isEqualTo(2);}
    @Test void lateWorkerResponseIsFencedAndRetriesAreIdempotent(){confirm(store.preview(owner,UUID.randomUUID()));var first=store.claim();assertThat(store.claim()).isNull();jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");var second=store.claim();assertThat(second.service()).isEqualTo(first.service());store.finish(first,true,"ignored");assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_services WHERE fence_state='FENCED'",Long.class)).isZero();store.finish(second,true,"ignored");assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_services WHERE fence_state='FENCED'",Long.class)).isEqualTo(1);fences.fence(1,first.request());fences.fence(1,first.request());}
    @Test void failedAndCrashedLastAttemptsNeedAttentionAndOperatorCanRetry(){var r=confirm(store.preview(owner,UUID.randomUUID()));var work=store.claim();store.finish(work,false,"FENCE_UNAVAILABLE");assertThat(store.services(r.id())).anyMatch(s->"FENCE_UNAVAILABLE".equals(s.get("code")));jdbc.update("UPDATE account_privacy_services SET attempts=8");jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");store.cleanup();assertThat(store.operator(r.id()).phase()).isEqualTo("NEEDS_ATTENTION");var retried=store.retry(r.id());assertThat(retried.phase()).isEqualTo("FENCING");assertThat(retried.statusExpires()).isEqualTo(r.statusExpires());}
    @Test void jdbcGuardRequiresATransactionAndRejectsStaleCustomerWrites(){actor(1);assertThatThrownBy(()->guarded.update("INSERT INTO privacy_probe(customer_id,value) VALUES(1,'bad')")).isInstanceOf(org.springframework.dao.DataAccessException.class);RequestContextHolder.resetRequestAttributes();UUID id=UUID.randomUUID();fences.fence(1,id);actor(1);assertThatThrownBy(()->transactions.executeWithoutResult(s->guarded.update("INSERT INTO privacy_probe(customer_id,value) VALUES(1,'bad')"))).isInstanceOf(org.springframework.dao.DataAccessException.class);actor(2);transactions.executeWithoutResult(s->guarded.update("INSERT INTO privacy_probe(customer_id,value) VALUES(2,'allowed')"));assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_probe",Long.class)).isEqualTo(1);}
    @Test void readOnlyCteWorksWithoutTransactionButDataModifyingCteCannotBypassFence(){
        actor(1);assertThat(guarded.queryForObject("WITH owned AS (SELECT id FROM customers WHERE id=1) SELECT count(*) FROM owned",Long.class)).isEqualTo(1);
        String mutation="WITH written AS (INSERT INTO privacy_probe(customer_id,value) VALUES(1,'cte') RETURNING id) SELECT count(*) FROM written";
        assertThatThrownBy(()->guarded.queryForObject(mutation,Long.class)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        RequestContextHolder.resetRequestAttributes();fences.fence(1,UUID.randomUUID());actor(1);
        assertThatThrownBy(()->transactions.execute(status->guarded.queryForObject(mutation,Long.class))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_probe",Long.class)).isZero();
    }
    @Test void localBarrierWaitsForAlreadyAdmittedTransactionAndThenBlocksNewOnes()throws Exception{
        var admitted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var write=pool.submit(()->{actor(1);try{transactions.executeWithoutResult(s->{guarded.update("INSERT INTO privacy_probe(customer_id,value) VALUES(1,'before')");admitted.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}});}finally{RequestContextHolder.resetRequestAttributes();}});
            assertThat(admitted.await(5,TimeUnit.SECONDS)).isTrue();var fence=pool.submit(()->fences.fence(1,UUID.randomUUID()));Thread.sleep(100);assertThat(fence.isDone()).isFalse();release.countDown();write.get(5,TimeUnit.SECONDS);fence.get(5,TimeUnit.SECONDS);
        }
        actor(1);assertThatThrownBy(()->transactions.executeWithoutResult(s->guarded.update("UPDATE privacy_probe SET value='late' WHERE customer_id=1"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        RequestContextHolder.resetRequestAttributes();jdbc.update("UPDATE privacy_probe SET value='workflow-closure' WHERE customer_id=1");assertThat(jdbc.queryForObject("SELECT value FROM privacy_probe",String.class)).isEqualTo("workflow-closure");
    }
    @Test void durableAssociationTriggersAlsoRejectServiceWritesAndReenablement(){confirm(store.preview(owner,UUID.randomUUID()));assertThatThrownBy(()->jdbc.update("UPDATE customers SET enabled=TRUE WHERE id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("UPDATE customers SET first_name='late' WHERE id=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) VALUES(1,'new-private',now())")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_recovery_codes",Long.class)).isZero();}
    @Test void previewCleanupDoesNotRemoveConfirmedRequestsOrMarkers(){var confirmed=confirm(store.preview(owner,UUID.randomUUID()));var draft=store.preview(other,UUID.randomUUID());jdbc.update("UPDATE account_privacy_requests SET preview_expires_at=now()-interval '1 second' WHERE id=?",draft.id());store.cleanup();assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_requests",Long.class)).isEqualTo(1);assertThat(store.operator(confirmed.id()).confirmed()).isNotNull();}
    PrivacyRequestStore.Request ready(){var r=confirm(store.preview(owner,UUID.randomUUID()));acknowledgeFences();return r;}
    void acknowledgeFences(){jdbc.update("UPDATE account_privacy_services SET retry_at=now()-interval '1 hour'");for(int i=0;i<14;i++){var work=store.claim();assertThat(work).isNotNull();store.finish(work,true,"ignored");}}
    org.springframework.data.redis.core.StringRedisTemplate cleanupRedis(){var redis=org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);org.mockito.Mockito.when(redis.delete(org.mockito.ArgumentMatchers.<String>anyCollection())).thenReturn(0L);return redis;}
    @Test void accountCleanupNeedsEveryFenceAndReviewedPolicy(){
        var r=confirm(store.preview(owner,UUID.randomUUID()));assertThat(store.claimAccountCleanup()).isNull();acknowledgeFences();
        jdbc.update("UPDATE account_privacy_requests SET account_cleanup_policy_hash=NULL");assertThat(store.claimAccountCleanup()).isNull();assertThat(store.accountCleanupStatus(r.id())).containsEntry("policyReviewed",false);
        jdbc.update("UPDATE account_privacy_requests SET account_cleanup_policy_hash=?",PrivacyPlan.cleanupPolicyHash());assertThat(store.claimAccountCleanup()).isNotNull();
    }
    @Test void successfulBatchesDoNotExhaustRetriesAndStaleResultsCannotCompleteThem(){
        ready();jdbc.update("UPDATE account_privacy_services SET cleanup_state='DONE' WHERE service<>'wishlist-service'");
        for(int i=0;i<12;i++){var work=store.claimAccountCleanup();assertThat(work).isNotNull();store.finishAccountCleanup(work,AccountCleanupAdapter.Result.more());jdbc.update("UPDATE account_privacy_services SET cleanup_retry_at=now()-interval '1 hour'");}
        var old=store.claimAccountCleanup();jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");var current=store.claimAccountCleanup();store.finishAccountCleanup(old,new AccountCleanupAdapter.Result(AccountCleanupAdapter.Outcome.DONE,"PRIVATE_ROWS_REMOVED"));assertThat(jdbc.queryForObject("SELECT cleanup_state FROM account_privacy_services WHERE service='wishlist-service'",String.class)).isEqualTo("RUNNING");store.finishAccountCleanup(current,new AccountCleanupAdapter.Result(AccountCleanupAdapter.Outcome.DONE,"PRIVATE_ROWS_REMOVED"));
    }
    @Test void crashedLastCleanupAttemptIsVisibleAndOperatorRetryCannotExtendStatusOrApproveLegacyPolicy(){
        var r=ready();var work=store.claimAccountCleanup();jdbc.update("UPDATE account_privacy_services SET cleanup_attempts=8 WHERE service=?",work.service());jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");store.cleanup();assertThat(store.services(r.id())).anyMatch(row->"RETRY_LIMIT".equals(row.get("cleanup_code")));assertThat(store.retry(r.id()).statusExpires()).isEqualTo(r.statusExpires());
        jdbc.update("UPDATE account_privacy_requests SET account_cleanup_policy_hash=NULL");store.retry(r.id());assertThat(store.claimAccountCleanup()).isNull();
    }
    @Test void customerCleanupCannotActBeforeAllFencesOrAfterRedisFailure(){
        var r=confirm(store.preview(owner,UUID.randomUUID()));var redis=cleanupRedis();var adapter=new CustomerAccountCleanup(fences,redis);rejects(409,()->adapter.cleanup(1,r.id()));org.mockito.Mockito.verifyNoInteractions(redis);acknowledgeFences();org.mockito.Mockito.when(redis.delete(org.mockito.ArgumentMatchers.<String>anyCollection())).thenThrow(new IllegalStateException("fixture redis unavailable"));assertThatThrownBy(()->adapter.cleanup(1,r.id())).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id=1",Long.class)).isEqualTo(1);
    }
    @Test void customerCleanupRemovesOnlyOwnedCredentialsInBoundedIdempotentBatchesAndFreesEmail(){
        jdbc.update("INSERT INTO refresh_tokens(customer_id,token_hash,family_id,expires_at,created_at) SELECT 1,'hash-'||n,'family-'||n,now()+interval '1 hour',now() FROM generate_series(1,205)n");
        jdbc.update("INSERT INTO password_reset_tokens(customer_id,token_hash,expires_at,created_at) VALUES(1,'reset-private',now()+interval '1 hour',now())");
        jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) VALUES(1,'recovery-private',now())");
        var r=ready();var redis=cleanupRedis();var adapter=new CustomerAccountCleanup(fences,redis);
        assertThat(adapter.cleanup(1,r.id()).outcome()).isEqualTo(AccountCleanupAdapter.Outcome.MORE);assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens",Long.class)).isEqualTo(105);
        int calls=1;while(adapter.cleanup(1,r.id()).outcome()==AccountCleanupAdapter.Outcome.MORE){assertThat(++calls).isLessThan(20);}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id=1",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id=2",Long.class)).isEqualTo(1);
        assertThat(store.status(r.id(),proof).id()).isEqualTo(r.id());assertThat(adapter.cleanup(1,r.id()).outcome()).isEqualTo(AccountCleanupAdapter.Outcome.DONE);
        jdbc.update("INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) VALUES(3,'owner@fixture.example','new-private','New','Account','Test','00-001','Test','PL',false,now(),now())");
        assertThat(jdbc.queryForObject("SELECT id FROM customers WHERE email='owner@fixture.example'",Long.class)).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM account_privacy_fences WHERE customer_id=1",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at) VALUES(1,'late',now())")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.mockito.Mockito.verify(redis,org.mockito.Mockito.atLeastOnce()).delete(List.of("customer:mfa:enrollment:1","customer:mfa:webauthn:registration:1"));
    }
    @Test void paddedAndCanonicalPasskeysAreDeletedWithoutEmailMatchingOrCrossAccountLoss(){
        jdbc.update("INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,public_key_credential_type,backup_state,created) VALUES('one','MQ',decode('01','hex'),0,true,false,'public-key',false,now()),('padded','MQ==',decode('02','hex'),0,true,false,'public-key',false,now()),('other','Mg',decode('03','hex'),0,true,false,'public-key',false,now()),('legacy','unknown-history',decode('04','hex'),0,true,false,'public-key',false,now())");
        var draft=store.preview(owner,UUID.randomUUID());var r=store.confirm(draft.id(),owner,draft.hash(),"fixture-password",proof,Set.of("pwd","webauthn"),passwords);acknowledgeFences();var adapter=new CustomerAccountCleanup(fences,cleanupRedis());
        while(adapter.cleanup(1,r.id()).outcome()==AccountCleanupAdapter.Outcome.MORE){}
        assertThat(jdbc.queryForList("SELECT credential_id FROM user_credentials ORDER BY credential_id",String.class)).containsExactly("legacy","other");
    }

    @Test void eightFailedCleanupCallsStopUntilOperatorRetry(){
        var r=ready();jdbc.update("UPDATE account_privacy_services SET cleanup_state='DONE' WHERE service<>'wishlist-service'");
        for(int n=0;n<8;n++){var work=store.claimAccountCleanup();assertThat(work).isNotNull();store.finishAccountCleanup(work,null);jdbc.update("UPDATE account_privacy_services SET cleanup_retry_at=now()-interval '1 hour'");}
        assertThat(store.claimAccountCleanup()).isNull();assertThat(store.services(r.id())).anyMatch(row->"RETRY_LIMIT".equals(row.get("cleanup_code")));store.retry(r.id());assertThat(store.claimAccountCleanup()).isNotNull();
    }

    @Test void storedExportCopiesAreCancelledAndPurgedWithoutTouchingOtherAccounts(){
        UUID job=UUID.randomUUID(),task=UUID.randomUUID(),otherJob=UUID.randomUUID();
        jdbc.update("INSERT INTO account_export_jobs(id,customer_id,request_id,state,created_at,expires_at,updated_at,policy_hash,run_token,run_until) VALUES(?,1,?,'ACTIVE',now(),now()+interval '1 hour',now(),'fixture-policy',?,now()+interval '1 hour'),(?,2,?,'READY',now(),now()+interval '1 hour',now(),'other-policy',NULL,NULL)",job,UUID.randomUUID(),UUID.randomUUID(),otherJob,UUID.randomUUID());
        jdbc.update("INSERT INTO account_export_tasks(id,job_id,kind,state) VALUES(?,?,'PROFILE','DONE')",task,job);
        jdbc.update("INSERT INTO account_export_chunks(job_id,sequence_no,task_id,data_json,byte_count,sha256,read_started_at,read_finished_at) VALUES(?,1,?,'private-profile-copy',20,'fixture-digest',now(),now())",job,task);
        var r=ready();var adapter=new CustomerAccountCleanup(fences,cleanupRedis());int loops=0;
        while(adapter.cleanup(1,r.id()).outcome()==AccountCleanupAdapter.Outcome.MORE)assertThat(++loops).isLessThan(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_export_chunks",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM account_export_tasks",Long.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT state,run_token,run_until FROM account_export_jobs WHERE id=?",job)).containsEntry("state","CANCELLED").containsEntry("run_token",null).containsEntry("run_until",null);
        assertThat(jdbc.queryForObject("SELECT state FROM account_export_jobs WHERE id=?",String.class,otherJob)).isEqualTo("READY");
        rejects(404,()->new org.demo.customerservice.export.jobs.ExportJobStore(wrapped,new DataSourceTransactionManager(wrapped)).get(job,owner));
    }


    void commerceDue(){jdbc.update("UPDATE account_privacy_services SET cleanup_retry_at=now()-interval '1 hour' WHERE service IN ('order-service','inventory-service','promotion-service','delivery-service','returns-service')");}
    @Test void commerceNeedsFourteenFencesAndSeparateReviewedPolicy(){var r=confirm(store.preview(owner,UUID.randomUUID()));assertThat(store.claimCommerceCleanup()).isNull();acknowledgeFences();commerceDue();assertThat(store.claimCommerceCleanup()).as("Ready fixture: %s / %s",jdbc.queryForMap("SELECT phase,commerce_cleanup_pending,commerce_cleanup_policy_hash,run_token,run_until FROM account_privacy_requests"),store.services(r.id())).isNotNull();jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL,commerce_cleanup_policy_hash=NULL");store.retry(r.id());assertThat(store.claimCommerceCleanup()).isNull();assertThat(store.commerceCleanupStatus(r.id())).containsEntry("policyReviewed",false).containsEntry("fullErasureComplete",false);}
    @Test void commerceWaitsDoNotConsumeFailureBudgetOrAdvanceCursor(){var r=ready();for(int n=0;n<12;n++){commerceDue();var work=store.claimCommerceCleanup();assertThat(work).isNotNull();assertThat(work.service()).isNotEqualTo("order-service");store.finishCommerceCleanup(work,CommerceCleanupAdapter.Batch.waitFor(work.cursor(),"WAITING_A43_36"));}assertThat(jdbc.queryForObject("SELECT max(cleanup_attempts) FROM account_privacy_services WHERE cleanup_state='WAITING_FINANCE'",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT max(cleanup_cursor) FROM account_privacy_services",Long.class)).isZero();assertThat(store.commerceCleanupStatus(r.id())).containsEntry("financeHandoffPending",true);store.retry(r.id());assertThat(store.claimCommerceCleanup()).isNotNull();}
    @Test void commerceOrderRunsLastAndCorePendingIgnoresCommerceScopes(){var r=ready();jdbc.update("UPDATE account_privacy_services SET cleanup_state='DONE' WHERE service IN ('customer-service','wishlist-service','recommendation-service','review-service','chat-service')");store.retry(r.id());assertThat(jdbc.queryForObject("SELECT account_cleanup_pending FROM account_privacy_requests",Boolean.class)).isFalse();for(int n=0;n<4;n++){commerceDue();var work=store.claimCommerceCleanup();assertThat(work.service()).isNotEqualTo("order-service");store.finishCommerceCleanup(work,CommerceCleanupAdapter.Batch.end(work.cursor()));}commerceDue();var last=store.claimCommerceCleanup();assertThat(last.service()).isEqualTo("order-service");store.finishCommerceCleanup(last,CommerceCleanupAdapter.Batch.end(last.cursor()));assertThat(store.claimCommerceCleanup()).isNull();assertThat(jdbc.queryForObject("SELECT commerce_cleanup_pending FROM account_privacy_requests",Boolean.class)).isFalse();assertThat(store.commerceCleanupStatus(r.id())).containsEntry("completedScopes",5L).containsEntry("fullErasureComplete",false);}
    @Test void commerceProgressIsDurableAndStaleLeaseCannotAdvanceIt(){ready();var first=store.claimCommerceCleanup();jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");var second=store.claimCommerceCleanup();store.finishCommerceCleanup(first,new CommerceCleanupAdapter.Batch(10,"MORE","IDENTIFIERS_RETAINED"));assertThat(jdbc.queryForObject("SELECT cleanup_cursor FROM account_privacy_services WHERE service=?",Long.class,first.service())).isZero();store.finishCommerceCleanup(second,new CommerceCleanupAdapter.Batch(10,"MORE","IDENTIFIERS_RETAINED"));commerceDue();var resumed=store.claimCommerceCleanup();assertThat(resumed.cursor()).isEqualTo(10);for(int n=0;n<10;n++){store.finishCommerceCleanup(resumed,new CommerceCleanupAdapter.Batch(10,"MORE","MORE_ROWS"));commerceDue();resumed=store.claimCommerceCleanup();assertThat(resumed.cursor()).isEqualTo(10);}store.finishCommerceCleanup(resumed,CommerceCleanupAdapter.Batch.end(10));}
    @Test void commerceErrorsExhaustAndRetryNeverChangesConsentOrStatusExpiry(){var r=ready();jdbc.update("UPDATE account_privacy_services SET cleanup_state='DONE' WHERE service IN ('inventory-service','promotion-service','returns-service')");for(int n=0;n<8;n++){commerceDue();var work=store.claimCommerceCleanup();assertThat(work.service()).isEqualTo("delivery-service");store.finishCommerceCleanup(work,null);}assertThat(store.claimCommerceCleanup()).isNull();assertThat(store.services(r.id())).anyMatch(row->"RETRY_LIMIT".equals(row.get("cleanup_code")));assertThat(store.retry(r.id()).statusExpires()).isEqualTo(r.statusExpires());assertThat(store.claimCommerceCleanup()).isNotNull();}

    void financeDue(){jdbc.update("UPDATE account_privacy_requests SET finance_retry_at=now()-interval '1 hour'");}
    @Test void financePolicyNeedsAllFourteenFencesAndCannotBeSilentlyApprovedForLegacy(){
        var r=confirm(store.preview(owner,UUID.randomUUID()));financeDue();assertThat(store.claimFinance()).isNull();acknowledgeFences();financeDue();var work=store.claimFinance();assertThat(work).isNotNull();assertThat(store.claimAccountCleanup()).isNull();assertThat(store.claimCommerceCleanup()).isNull();store.finishFinance(work,new PrivacyRequestStore.FinanceResult("WAIT","WAITING_STREAM_POLICY",1,0));
        jdbc.update("UPDATE account_privacy_requests SET finance_policy_hash=NULL");store.retry(r.id());assertThat(store.claimFinance()).isNull();assertThat(store.financeStatus(r.id())).containsEntry("policyReviewed",false).containsEntry("documentsRetained",true).containsEntry("fullErasureComplete",false);
    }
    @Test void financeWaitsAndStaleResponsesKeepProgressSafe(){
        var r=ready();for(int n=0;n<12;n++){financeDue();var work=store.claimFinance();assertThat(work).isNotNull();store.finishFinance(work,new PrivacyRequestStore.FinanceResult("WAIT","WAITING_PAYMENT_PROCESS",2,1));}
        assertThat(jdbc.queryForObject("SELECT finance_attempts FROM account_privacy_requests",Integer.class)).isZero();financeDue();var stale=store.claimFinance();jdbc.update("UPDATE account_privacy_requests SET run_until=now()-interval '1 second'");var current=store.claimFinance();store.finishFinance(stale,new PrivacyRequestStore.FinanceResult("PREPARED","FINANCE_PREPARED",2,2));assertThat(store.financeStatus(r.id())).containsEntry("state","RUNNING");store.finishFinance(current,new PrivacyRequestStore.FinanceResult("WAIT","WAITING_PAYMENT_PROCESS",2,1));assertThat(store.financeStatus(r.id())).containsEntry("state","WAIT").containsEntry("preparedOrders",1L);
    }
    @Test void financeExhaustionRequiresAnExplicitRetryGenerationAndPreservesStatusExpiry(){
        var r=ready();for(int n=0;n<8;n++){financeDue();store.finishFinance(store.claimFinance(),null);}assertThat(store.claimFinance()).isNull();long generation=jdbc.queryForObject("SELECT finance_retry_generation FROM account_privacy_requests",Long.class);assertThat(store.retry(r.id()).statusExpires()).isEqualTo(r.statusExpires());assertThat(jdbc.queryForObject("SELECT finance_retry_generation FROM account_privacy_requests",Long.class)).isEqualTo(generation+1);assertThat(store.claimFinance()).isNotNull();
    }
    @Test void financeReadinessOnlyCompletesTechnicalActionsAndWakesExistingCommerceWaits(){
        var r=ready();jdbc.update("UPDATE account_privacy_services SET cleanup_state='WAITING_FINANCE',cleanup_code='WAITING_A43_36' WHERE service IN ('order-service','inventory-service','promotion-service','delivery-service','returns-service')");financeDue();store.finishFinance(store.claimFinance(),new PrivacyRequestStore.FinanceResult("PREPARED","FINANCE_PREPARED",1,1));assertThat(store.financeStatus(r.id())).containsEntry("state","DONE").containsEntry("documentsRetained",true).containsEntry("fullErasureComplete",false);assertThat(store.claimFinance()).isNull();assertThat(store.claimCommerceCleanup()).isNotNull();
        assertThat(store.services(r.id())).filteredOn(row->List.of("payment-service","billing-service","notification-service").contains(row.get("service"))).allMatch(row->row.get("cleanup_state").equals("DONE")&&row.get("data_action").equals("KEEP_FINANCE_ERASE_COPIES"));
    }
}
