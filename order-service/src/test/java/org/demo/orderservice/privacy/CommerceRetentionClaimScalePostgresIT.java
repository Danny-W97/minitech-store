package org.demo.orderservice.privacy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommerceRetentionClaimScalePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("claim_scale").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static JdbcTemplate jdbc;static DataSourceTransactionManager manager;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeAll static void setup(){
        ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        // Real account guards take per-customer advisory locks. Keep fixture
        // imports in 500-row transactions rather than disabling those guards.
        for(int first=1;first<=100000;first+=500)jdbc.update("""
          INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
          SELECT n,CASE WHEN n=100000 THEN 17 WHEN n BETWEEN 99801 AND 99900 THEN 18 ELSE n+1000 END,'fixture@example.test',10,'CANCELLED','Fixture','Buyer','Street','00-001','City','PL','COURIER',0,0,'HOME_DELIVERY','scale-'||n,repeat('a',64),'fixture',now(),now() FROM generate_series(?,?)n
          """,first,first+499);
        jdbc.execute("""
          INSERT INTO commerce_retention_jobs(order_id,generation,customer_id,order_version,phase,policy,state,next_attempt_at,order_hold_revision,customer_hold_revision)
          SELECT id,md5('scale-'||id)::uuid,customer_id,0,'REDACTION','D02-P02-2026-10-30.1','DUE_FOR_REVIEW',now()+interval '1 day',0,0 FROM orders;
          INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id) SELECT 'ORDER',id FROM orders;
          INSERT INTO commerce_retention_hold_scopes(scope_type,subject_id) SELECT DISTINCT 'CUSTOMER',customer_id FROM orders;
          ANALYZE orders;ANALYZE commerce_retention_jobs;ANALYZE commerce_retention_hold_scopes;
          """);
    }
    static long rows(JsonNode node){long total=node.path("Actual Rows").asLong()*node.path("Actual Loops").asLong();total+=node.path("Rows Removed by Filter").asLong()*node.path("Actual Loops").asLong();for(var child:node.path("Plans"))total+=rows(child);return total;}
    static JsonNode plan(String sql){return plan(sql,new Object[]{POLICY});}
    static JsonNode plan(String sql,Object...args){return JSON.readTree(jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+sql,String.class,args)).get(0);}
    static JsonNode legacyPlan(String sql,Object...args){
        return new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx->{
            // Exact V32 index set for the counterfactual, in this owned fixture
            // only. Rollback restores all V33 indexes before real claims run.
            jdbc.execute("DROP INDEX idx_retention_changed_scopes,idx_retention_ready_due,idx_retention_customer_revision,idx_retention_legacy_revision,idx_retention_exhausted_lease; CREATE INDEX idx_retention_hold_claim ON commerce_retention_jobs(next_attempt_at,order_id) WHERE current AND state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')");
            var result=plan(sql,args);tx.setRollbackOnly();return result;
        });
    }
    static Path reportPath(){Path root=Path.of("").toAbsolutePath();if(!Files.isDirectory(root.resolve("order-service")))root=root.getParent();return root.resolve("tmp/a43-claim-scale-metrics.json");}
    @Test @Order(1) void reportIdleAndSingleChangedCustomerPlansOnOneHundredThousandJobs()throws Exception{
        var expiration=plan(CommerceRetentionJobs.expirationSql(""),new Object[0]);
        var legacyIdle=legacyPlan(CommerceRetentionJobs.scopedSelectionSql(""),POLICY);
        var idle=plan(CommerceRetentionJobs.selectionSql(""));assertThat(new CommerceRetentionJobs(ds,manager).claim()).isEmpty();
        new CommerceRetentionHolds(ds,manager).placeCustomerPurchases(17,UUID.randomUUID(),Guard.NO_PRESERVATION_HOLD,CommerceRetentionHolds.Reason.PRESERVATION_REQUEST);jdbc.execute("ANALYZE commerce_retention_hold_scopes");
        var legacyChanged=legacyPlan(CommerceRetentionJobs.scopedSelectionSql(""),POLICY);
        var changed=plan(CommerceRetentionJobs.selectionSql(""));
        var work=new CommerceRetentionJobs(ds,manager).claim().orElseThrow();assertThat(work.subject().orderId()).isEqualTo(100000);assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE state='LEASED'",Long.class)).isEqualTo(1);
        var report=new LinkedHashMap<String,Object>();report.put("comparison","same fixture and SQL semantics; transactional exact V32 index set versus V33");report.put("workCounter","sum of actual rows plus rows removed by filter per plan node times actual loops; not distinct rows");report.put("expirationPlan",expiration);report.put("expirationVisitedRows",rows(expiration.path("Plan")));report.put("fixtureJobs",100000);report.put("fixtureScopeHeaders",count("commerce_retention_hold_scopes"));report.put("legacyIdlePlan",legacyIdle);report.put("legacyChangedCustomerPlan",legacyChanged);report.put("legacyIdleVisitedRows",rows(legacyIdle.path("Plan")));report.put("legacyChangedVisitedRows",rows(legacyChanged.path("Plan")));report.put("idlePlan",idle);report.put("changedCustomerPlan",changed);report.put("idleVisitedRows",rows(idle.path("Plan")));report.put("changedVisitedRows",rows(changed.path("Plan")));report.put("leasedJobs",1);report.put("activationAllowed",false);
        Files.createDirectories(reportPath().getParent());Files.writeString(reportPath(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(rows(expiration.path("Plan"))).as("expiration plan %s",expiration).isLessThan(500);
        assertThat(rows(legacyIdle.path("Plan"))).isGreaterThanOrEqualTo(100000);assertThat(rows(legacyChanged.path("Plan"))).isGreaterThanOrEqualTo(100000);
        assertThat(rows(idle.path("Plan"))).as("idle plan %s",idle).isLessThan(500);assertThat(rows(changed.path("Plan"))).as("changed plan %s",changed).isLessThan(500);
        System.out.println("A43 claim scale: "+JSON.writeValueAsString(Map.of("idleVisitedRows",rows(idle.path("Plan")),"changedVisitedRows",rows(changed.path("Plan")),"idleMs",idle.path("Execution Time").asDouble(),"changedMs",changed.path("Execution Time").asDouble())));
    }

    static long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @Test @Order(2) void concurrentWorkersSkipLockedCandidatesForOneChangedCustomer()throws Exception{
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',customer_hold_revision=1,run_token=NULL,run_until=NULL WHERE order_id=100000");
        new CommerceRetentionHolds(ds,manager).placeCustomerPurchases(18,UUID.randomUUID(),Guard.NO_OPEN_OR_REOPENED_CASE,CommerceRetentionHolds.Reason.OPEN_CASE);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);var a=executor.submit(()->{start.await();return new CommerceRetentionJobs(ds,manager).claim().orElseThrow().subject().orderId();});var b=executor.submit(()->{start.await();return new CommerceRetentionJobs(ds,manager).claim().orElseThrow().subject().orderId();});start.countDown();long first=a.get(10,java.util.concurrent.TimeUnit.SECONDS),second=b.get(10,java.util.concurrent.TimeUnit.SECONDS);assertThat(first).isBetween(99801L,99900L);assertThat(second).isBetween(99801L,99900L).isNotEqualTo(first);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE state='LEASED'",Long.class)).isEqualTo(2);
    }
    @Test @Order(3) void alreadyObservedPositiveScopesDoNotRevisitUnchangedPurchases(){
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',customer_hold_revision=1,run_token=NULL,run_until=NULL WHERE customer_id=18");jdbc.execute("ANALYZE commerce_retention_jobs;ANALYZE commerce_retention_hold_scopes");
        var idle=plan(CommerceRetentionJobs.selectionSql(""));assertThat(rows(idle.path("Plan"))).as("acknowledged scopes plan %s",idle).isLessThan(500);assertThat(new CommerceRetentionJobs(ds,manager).claim()).isEmpty();
    }
    @Test @Order(4) void historicalNegativeRevisionsRemainClaimableWithoutOrdinaryDueTime(){
        jdbc.update("UPDATE commerce_retention_jobs SET order_hold_revision=-1,customer_hold_revision=-1 WHERE order_id=99999");var work=new CommerceRetentionJobs(ds,manager).claim().orElseThrow();assertThat(work.subject().orderId()).isEqualTo(99999);
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',order_hold_revision=0,customer_hold_revision=0,run_token=NULL,run_until=NULL WHERE order_id=99999");
    }
    @Test @Order(5) void changedOrderQuarantineIsAssessedOnceAndNeverAuthorizesCleanup(){
        jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE WHERE scope_type='ORDER' AND subject_id=99998");var jobs=new CommerceRetentionJobs(ds,manager);var work=jobs.claim().orElseThrow();assertThat(work.subject().orderId()).isEqualTo(99998);var result=jobs.assess(work).orElseThrow();assertThat(result.outcome()).isEqualTo(Outcome.BLOCKED);assertThat(result.activationAllowed()).isFalse();assertThat(jobs.claim()).isEmpty();assertThat(count("orders")).isEqualTo(100000);
    }
    @Test @Order(6) void scopedClaimStillUsesOnlyTheRequestedPurchase(){
        jdbc.update("UPDATE commerce_retention_jobs SET state='WAITING_PROOF',next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=99997");var generation=jdbc.queryForObject("SELECT generation FROM commerce_retention_jobs WHERE order_id=99997",UUID.class);var jobs=new CommerceRetentionJobs(ds,manager);assertThat(jobs.claim(new Subject(99997,99,0,generation),Phase.REDACTION)).isEmpty();var work=jobs.claim(new Subject(99997,100997,0,generation),Phase.REDACTION).orElseThrow();assertThat(work.subject().orderId()).isEqualTo(99997);
    }
    @Test @Order(7) void futureNormalWaitsUseStatementTimeIndexRange()throws Exception{
        jdbc.update("UPDATE commerce_retention_jobs SET state='WAITING_AGE',run_token=NULL,run_until=NULL,attempts=0,next_attempt_at=clock_timestamp()+interval '1 day' WHERE order_id<>99998");jdbc.execute("ANALYZE commerce_retention_jobs");var idle=plan(CommerceRetentionJobs.selectionSql(""));assertThat(rows(idle.path("Plan"))).as("future waits plan %s",idle).isLessThan(500);assertThat(new CommerceRetentionJobs(ds,manager).claim()).isEmpty();var expiration=plan(CommerceRetentionJobs.expirationSql(""),new Object[0]);var legacyExpiration=legacyPlan(CommerceRetentionJobs.expirationSql(""),new Object[0]);assertThat(rows(expiration.path("Plan"))).isLessThan(500);assertThat(rows(legacyExpiration.path("Plan"))).isGreaterThanOrEqualTo(100000);
        var report=JSON.readTree(Files.readString(reportPath()));var future=JSON.createObjectNode();future.set("selectionPlan",idle);future.set("expirationPlan",expiration);future.set("legacyExpirationPlan",legacyExpiration);future.put("expirationVisitedRows",rows(expiration.path("Plan")));future.put("legacyExpirationVisitedRows",rows(legacyExpiration.path("Plan")));((tools.jackson.databind.node.ObjectNode)report).set("futureNormalWaits",future);Files.writeString(reportPath(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @Test @Order(8) void fourCandidateReasonsLockAtMostFourJobsButLeaseOnlyOne()throws Exception{
        jdbc.update("UPDATE commerce_retention_jobs SET state='WAITING_PROOF',next_attempt_at=clock_timestamp()-interval '1 day' WHERE order_id=99996");
        jdbc.update("UPDATE commerce_retention_jobs SET state='DUE_FOR_REVIEW',order_hold_revision=-1,customer_hold_revision=-1 WHERE order_id=99995");
        jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE WHERE scope_type='CUSTOMER' AND subject_id=100994");
        jdbc.update("UPDATE commerce_retention_hold_scopes SET restore_blocked=TRUE WHERE scope_type='ORDER' AND subject_id=99993");
        String locked="SELECT count(*) FROM (SELECT order_id FROM commerce_retention_jobs WHERE order_id IN (99996,99995,99994,99993) FOR UPDATE SKIP LOCKED) q";
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx->{
                var work=new CommerceRetentionJobs(ds,manager).claim().orElseThrow();assertThat(work.subject().orderId()).isEqualTo(99996);
                try{assertThat(executor.submit(()->new JdbcTemplate(ds).queryForObject(locked,Long.class)).get(5,java.util.concurrent.TimeUnit.SECONDS)).isZero();assertThat(executor.submit(()->new JdbcTemplate(ds).queryForObject("SELECT count(*) FROM (SELECT order_id FROM commerce_retention_jobs WHERE order_id=99992 FOR UPDATE SKIP LOCKED) q",Long.class)).get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);}catch(Exception failed){throw new AssertionError(failed);}
                assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_jobs WHERE state='LEASED'",Long.class)).isEqualTo(1);
            });
        }
        assertThat(jdbc.queryForObject(locked,Long.class)).isEqualTo(4);var report=(tools.jackson.databind.node.ObjectNode)JSON.readTree(Files.readString(reportPath()));report.put("candidateJobLocksObserved",4);report.put("candidateLocksReleasedOnCommit",true);Files.writeString(reportPath(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
