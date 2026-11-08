package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.orderservice.privacy.CommerceRetentionBindings.*;

@Testcontainers
class CommerceRetentionBindingsPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("coord_fixture").withUsername("fixture").withPassword("fixture-only");
    static DriverManagerDataSource ds;static DataSourceTransactionManager manager;static JdbcTemplate jdbc;
    static final Map<Source,CommerceRetentionSourceOutbox> sources=new EnumMap<>(Source.class);
    static final Map<Source,JdbcTemplate> sourceJdbc=new EnumMap<>(Source.class);
    CommerceRetentionJobs jobs;CommerceRetentionBindings coordinator;
    @BeforeAll static void setup(){
        ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());manager=new DataSourceTransactionManager(ds);jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        Path root=Path.of("").toAbsolutePath();if(!Files.isDirectory(root.resolve("returns-service")))root=root.getParent();
        assertThat(Files.isDirectory(root.resolve("returns-service"))).isTrue();
        for(var source:Source.values()){
            String alias=source.name().toLowerCase(Locale.ROOT);jdbc.execute("CREATE DATABASE source_"+alias);
            var local=new DriverManagerDataSource(DB.getJdbcUrl().replace("/coord_fixture","/source_"+alias),DB.getUsername(),DB.getPassword());
            Flyway.configure().dataSource(local).locations("filesystem:"+root.resolve(alias+"-service/src/main/resources/db/migration")).load().migrate();
            sources.put(source,new CommerceRetentionSourceOutbox(local,new DataSourceTransactionManager(local)));sourceJdbc.put(source,new JdbcTemplate(local));
        }
    }
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE commerce_retention_bindings,commerce_retention_evidence,commerce_retention_jobs,orders RESTART IDENTITY CASCADE");
        for(var local:sourceJdbc.values())local.execute("TRUNCATE commerce_retention_change_outbox,commerce_retention_source_state CASCADE");
        sourceJdbc.get(Source.RETURNS).execute("TRUNCATE return_events,return_lines,return_requests CASCADE");
        jobs=new CommerceRetentionJobs(ds,manager);coordinator=new CommerceRetentionBindings(ds,manager);order(10,17L);
    }
    void order(long id, Long customer) {
        jdbc.update("""
                INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
                delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,
                discount_amount,fulfillment_type,idempotency_key,checkout_request_hash,checkout_principal,created_at,updated_at)
                VALUES(?,?,'fixture@example.test',123.45,'CANCELLED','Fixture','Buyer','Fixture street','00-001','Fixture city','PL',
                '123456789','STANDARD_COURIER',0,0,'HOME_DELIVERY',?,?,?,now(),now())
                """, id, customer, "fixture-key-" + id, "b".repeat(64), customer == null ? "guest:fixture-" + id : "customer:" + customer);
    }
    Subject prepare(){var subject=jobs.prepare(10,Phase.REDACTION);assertThat(coordinator.prepare(subject,Phase.REDACTION)).isEqualTo(Preparation.PREPARED);due();return subject;}
    Acknowledgement bind(Source source,Subject scope,Phase phase,long epoch){
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var bound=sources.get(source).bind(scope,phase,epoch);return new Acknowledgement(bound.epoch(),bound.revision(),bound.invalidated());
    }
    long count(String where){return jdbc.queryForObject("SELECT count(*) FROM commerce_retention_bindings "+where,Long.class);}
    // This fixture explicitly makes commands due; do not depend on wall-clock
    // monotonicity. The WSL/container clock was observed moving backwards >1 s.
    // Production claim/lease checks stay unchanged and remain fail-closed.
    void due(){jdbc.update("UPDATE commerce_retention_bindings SET retry_at=clock_timestamp()-interval '1 day' WHERE state<>'BOUND'");}
    void finishAll(){
        var failures=new ArrayList<String>();
        for(int n=0;n<8 && coordinator.publishOne((source,scope,phase,epoch)->{
            try{return bind(source,scope,phase,epoch);}catch(RuntimeException failure){failures.add(source+":"+failure.getClass().getSimpleName()+":"+failure.getMessage());throw failure;}
        });n++){}
        assertThat(count("WHERE state='BOUND'")).as("binding states %s; adapter failures %s",jdbc.queryForList("SELECT source,state,attempts,code FROM commerce_retention_bindings"),failures).isEqualTo(7);
    }
    void isolate(Work work){jdbc.update("UPDATE commerce_retention_bindings SET retry_at=clock_timestamp()+interval '1 day' WHERE source<>?",work.source().name());}

    @Test void preparationIsAtomicScopedAndIdempotentWithoutResettingAttempts(){
        var subject=prepare();var work=coordinator.claim().orElseThrow();coordinator.failed(work);
        assertThat(coordinator.prepare(subject,Phase.REDACTION)).isEqualTo(Preparation.IDEMPOTENT);assertThat(count("")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT attempts FROM commerce_retention_bindings WHERE source=?",Integer.class,work.source().name())).isEqualTo(1);
        assertThat(coordinator.prepare(new Subject(10,99,subject.orderVersion(),subject.chainGeneration()),Phase.REDACTION)).isEqualTo(Preparation.STALE);
        assertThat(coordinator.prepare(subject,Phase.MINIMUM_REMOVAL)).isEqualTo(Preparation.STALE);
    }
    @Test void sevenActualSourceDatabasesBindWithoutManufacturingReconciliationEvidence(){
        var subject=prepare();finishAll();assertThat(count("WHERE expected_epoch=0 AND bound_epoch=1 AND source_revision=0 AND NOT invalidated")).isEqualTo(7);
        for(var local:sourceJdbc.values())assertThat(local.queryForObject("SELECT binding_epoch FROM commerce_retention_source_state",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM commerce_retention_jobs WHERE generation=?",String.class,subject.chainGeneration())).isEqualTo("WAITING_PROOF");
    }
    @Test void lostAcknowledgementReplaysTheIdenticalCommandAfterLeaseExpiry(){
        prepare();var first=coordinator.claim().orElseThrow();isolate(first);var receipt=bind(first.source(),first.subject(),first.phase(),first.expectedEpoch());
        jdbc.update("UPDATE commerce_retention_bindings SET run_until=clock_timestamp()-interval '1 second' WHERE source=?",first.source().name());
        var retry=new CommerceRetentionBindings(ds,manager).claim().orElseThrow();assertThat(retry.subject()).isEqualTo(first.subject());assertThat(retry.expectedEpoch()).isZero();assertThat(retry.token()).isNotEqualTo(first.token());
        var again=bind(retry.source(),retry.subject(),retry.phase(),retry.expectedEpoch());assertThat(again).isEqualTo(receipt);
        assertThat(coordinator.acknowledge(first,receipt)).isFalse();assertThat(coordinator.acknowledge(retry,again)).isTrue();
        assertThat(sourceJdbc.get(first.source()).queryForObject("SELECT binding_epoch FROM commerce_retention_source_state",Long.class)).isEqualTo(1);
    }
    @Test void nextGenerationWaitsForUnknownOldEffectsAndUsesOnlyRetainedReceipts(){
        var first=prepare();jobs.invalidate(10,first.chainGeneration());var second=jobs.prepare(10,Phase.REDACTION);
        assertThat(coordinator.prepare(second,Phase.REDACTION)).isEqualTo(Preparation.PREDECESSOR_PENDING);
        finishAll();assertThat(coordinator.prepare(second,Phase.REDACTION)).isEqualTo(Preparation.PREPARED);
        due();
        for(int n=0;n<7;n++)assertThat(coordinator.publishOne(this::bind)).as("next generation source %s; states %s; DB now %s",n,jdbc.queryForList("SELECT source,state,retry_at,run_until,attempts,code FROM commerce_retention_bindings WHERE generation=?",second.chainGeneration()),jdbc.queryForObject("SELECT clock_timestamp()::text",String.class)).isTrue();
        assertThat(count("WHERE bound_epoch=2 AND expected_epoch=1")).isEqualTo(7);
        assertThatThrownBy(()->sources.get(Source.RETURNS).bind(first,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_CONFLICT");
        assertThat(jobs.invalidate(10,first.chainGeneration(),first.orderVersion(),Phase.REDACTION)).isFalse();
    }
    @RepeatedTest(10) void generationReconciliationIsStableAcrossFreshDatabaseResets(){
        nextGenerationWaitsForUnknownOldEffectsAndUsesOnlyRetainedReceipts();
    }
    @Test void dirtySourceReceiptRetiresTheMatchingRootAndNegativePublicationNeverClearsIt(){
        prepare();var work=coordinator.claim().orElseThrow();bind(work.source(),work.subject(),work.phase(),work.expectedEpoch());
        sourceJdbc.get(work.source()).execute("SELECT queue_commerce_retention_change(10)");
        var dirty=bind(work.source(),work.subject(),work.phase(),work.expectedEpoch());assertThat(dirty.invalidated()).isTrue();assertThat(coordinator.acknowledge(work,dirty)).isTrue();
        assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();
        var client=mock(CommerceRetentionInvalidationClient.class);doAnswer(call->{jobs.invalidate(10,work.subject().chainGeneration(),work.subject().orderVersion(),work.phase());return null;}).when(client).invalidate(any(),any());
        sourceJdbc.get(work.source()).update("UPDATE commerce_retention_change_outbox SET retry_at=clock_timestamp()-interval '1 second'");
        assertThat(sources.get(work.source()).publishOne(client)).isTrue();assertThat(sourceJdbc.get(work.source()).queryForObject("SELECT invalidated FROM commerce_retention_source_state",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
    }
    @Test void transportFailureAndRestartDoNotRefreshExpectedEpochOrRetryBudget(){
        prepare();var initial=coordinator.claim().orElseThrow();isolate(initial);assertThat(coordinator.failed(initial)).isTrue();
        for(int n=1;n<8;n++){due();jdbc.update("UPDATE commerce_retention_bindings SET retry_at=clock_timestamp()+interval '1 day' WHERE source<>?",initial.source().name());var work=new CommerceRetentionBindings(ds,manager).claim().orElseThrow();assertThat(work.expectedEpoch()).isZero();assertThat(coordinator.failed(work)).isTrue();}
        assertThat(coordinator.claim()).isEmpty();assertThat(jdbc.queryForMap("SELECT attempts,code FROM commerce_retention_bindings WHERE source=?",initial.source().name())).containsEntry("attempts",8).containsEntry("code","RETRY_LIMIT");
    }
    @Test void abandonedEighthLeaseIsRetiredWithoutResending(){
        prepare();var first=coordinator.claim().orElseThrow();isolate(first);
        jdbc.update("UPDATE commerce_retention_bindings SET attempts=8,run_until=clock_timestamp()-interval '1 second' WHERE source=?",first.source().name());
        assertThat(coordinator.claim()).isEmpty();assertThat(count("WHERE state='FAILED' AND code='RETRY_LIMIT' AND run_token IS NULL")).isEqualTo(1);
    }
    @Test void scopeTokenEpochAndExpiryCannotBeForged(){
        prepare();var work=coordinator.claim().orElseThrow();var ack=new Acknowledgement(1,0,false);
        assertThat(coordinator.acknowledge(work,new Acknowledgement(2,0,false))).isFalse();
        assertThat(coordinator.acknowledge(new Work(work.subject(),work.phase(),work.source(),0,UUID.randomUUID()),ack)).isFalse();
        assertThat(coordinator.acknowledge(new Work(work.subject(),Phase.MINIMUM_REMOVAL,work.source(),0,work.token()),ack)).isFalse();
        jdbc.update("UPDATE commerce_retention_bindings SET run_until=clock_timestamp()-interval '1 second' WHERE source=?",work.source().name());assertThat(coordinator.acknowledge(work,ack)).isFalse();
    }
    @Test void twoPreparationsAndClaimsDoNotDuplicateSevenCommands() throws Exception{
        var scope=jobs.prepare(10,Phase.REDACTION);var pool=Executors.newFixedThreadPool(2);
        try{var one=pool.submit(()->coordinator.prepare(scope,Phase.REDACTION));var two=pool.submit(()->coordinator.prepare(scope,Phase.REDACTION));assertThat(List.of(one.get(5,TimeUnit.SECONDS),two.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(Preparation.PREPARED,Preparation.IDEMPOTENT);
            due();var a=pool.submit(coordinator::claim);var b=pool.submit(coordinator::claim);assertThat(a.get(5,TimeUnit.SECONDS).orElseThrow().source()).isNotEqualTo(b.get(5,TimeUnit.SECONDS).orElseThrow().source());assertThat(count("")).isEqualTo(7);
        }finally{pool.shutdownNow();}
    }
    @Test void failedPreparationRollsBackAllSevenCommands(){
        var scope=jobs.prepare(10,Phase.REDACTION);
        jdbc.execute("CREATE FUNCTION fixture_abort_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source='NOTIFICATION' THEN RAISE EXCEPTION 'fixture rollback'; END IF;RETURN NEW;END $$");jdbc.execute("CREATE TRIGGER fixture_abort BEFORE INSERT ON commerce_retention_bindings FOR EACH ROW EXECUTE FUNCTION fixture_abort_binding()");
        try{assertThatThrownBy(()->coordinator.prepare(scope,Phase.REDACTION)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("")).isZero();}
        finally{jdbc.execute("DROP TRIGGER fixture_abort ON commerce_retention_bindings");jdbc.execute("DROP FUNCTION fixture_abort_binding()");}
        assertThat(coordinator.prepare(scope,Phase.REDACTION)).isEqualTo(Preparation.PREPARED);
    }
    @Test void commandReceiptsAndRestoreQuarantineCannotBeRewritten(){
        var scope=prepare();finishAll();
        for(String sql:List.of("UPDATE commerce_retention_bindings SET expected_epoch=1","DELETE FROM commerce_retention_bindings","UPDATE commerce_retention_bindings SET source_revision=1"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=TRUE");assertThat(coordinator.prepare(scope,Phase.REDACTION)).isEqualTo(Preparation.QUARANTINED);assertThat(coordinator.claim()).isEmpty();
        assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_bindings SET restore_blocked=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jobs.invalidate(10,scope.chainGeneration());var next=jobs.prepare(10,Phase.REDACTION);assertThat(coordinator.prepare(next,Phase.REDACTION)).isEqualTo(Preparation.QUARANTINED);
    }
    @Test void aRemoteCommitThenExceptionIsRetriedOutsideTheRootTransaction(){
        prepare();assertThat(coordinator.publishOne((source,scope,phase,epoch)->{bind(source,scope,phase,epoch);throw new IllegalStateException("fixture lost response");})).isTrue();
        assertThat(count("WHERE state='FAILED' AND expected_epoch=0")).isEqualTo(1);due();
        // Retry the failed source first; the same remote command must not advance its epoch.
        jdbc.update("UPDATE commerce_retention_bindings SET retry_at=clock_timestamp()+interval '1 day' WHERE state='PENDING'");
        assertThat(coordinator.publishOne(this::bind)).isTrue();assertThat(count("WHERE state='BOUND' AND expected_epoch=0 AND bound_epoch=1 AND attempts=2")).isEqualTo(1);
    }
    @Test void callerTransactionCannotStartTransportOrConsumeAnAttempt(){
        prepare();
        new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThatThrownBy(()->coordinator.publishOne((source,scope,phase,epoch)->{throw new AssertionError("transport must not run");}))
                    .hasMessage("RETENTION_BINDING_CALLER_TRANSACTION");
        });
        assertThat(count("WHERE attempts=0 AND state='PENDING'")).isEqualTo(7);
    }
    /** Real HTTP on random fixture ports, real introspection filter, MVC controller and source SQL. */
    final class HttpFixture implements AutoCloseable {
        final List<com.sun.net.httpserver.HttpServer> servers=new ArrayList<>();
        final Map<Source,CommerceRetentionBindingClient> clients=new EnumMap<>(Source.class);
        final Map<Source,String> origins=new EnumMap<>(Source.class);
        final java.util.concurrent.atomic.AtomicBoolean loseReply;
        String issuer,orderOrigin;
        HttpFixture(boolean loseReply)throws Exception{
            this.loseReply=new java.util.concurrent.atomic.AtomicBoolean(loseReply);
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            issuer=start("/internal/auth/introspect",exchange->{
                var known=new HashSet<String>();known.add("order-service");for(var s:Source.values())known.add(s.name().toLowerCase(Locale.ROOT)+"-service");
                String own=exchange.getRequestHeaders().getFirst("X-Service-Token");String token=json.readTree(exchange.getRequestBody().readAllBytes()).path("token").asText();
                String service=token.startsWith("fixture-")?token.substring(8):"";
                var actor=known.contains(service) && known.stream().anyMatch(name->("fixture-"+name).equals(own))?new org.demo.platformsecurity.Actor("SERVICE",service,null,null):org.demo.platformsecurity.Actor.ANONYMOUS;
                byte[] raw=json.writeValueAsBytes(actor);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();
            });
            orderOrigin=node("order-service",new CommerceRetentionInvalidationController(jobs),CommerceRetentionInvalidationClient.PATH,false);
            for(var source:Source.values()){
                String name=source.name().toLowerCase(Locale.ROOT)+"-service";
                String origin=node(name,new CommerceRetentionSourceController(sources.get(source)),CommerceRetentionBindingProtocol.PATH,true);
                origins.put(source,origin);clients.put(source,new CommerceRetentionBindingClient(origin,new org.demo.platformsecurity.ServiceAuthenticationInterceptor("fixture-order-service")));
            }
        }
        String start(String path,com.sun.net.httpserver.HttpHandler handler)throws java.io.IOException{
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext(path,handler);server.start();servers.add(server);return "http://127.0.0.1:"+server.getAddress().getPort();
        }
        String node(String name,Object controller,String path,boolean binding)throws Exception{
            org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration=org.springframework.test.util.ReflectionTestUtils.invokeMethod(new org.demo.platformsecurity.PlatformSecurityConfiguration(),"platformAuthenticationFilter",name,issuer,"fixture-"+name);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setHandlerExceptionResolvers(new org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver(),new org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver()).addFilters(registration.getFilter()).build();
            return start(path,exchange->{
                try{
                    byte[] body=exchange.getRequestBody().readNBytes(4097);
                    if(body.length>4096){exchange.sendResponseHeaders(413,-1);return;}
                    var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(body);
                    var authorization=exchange.getRequestHeaders().getFirst("Authorization");if(authorization!=null)request.header("Authorization",authorization);
                    var response=mvc.perform(request).andReturn().getResponse();byte[] raw=response.getContentAsByteArray();int status=response.getStatus();
                    if(binding && status==200 && this.loseReply.compareAndSet(true,false)){status=503;raw=new byte[0];}
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(status,raw.length==0?-1:raw.length);if(raw.length>0)exchange.getResponseBody().write(raw);
                }catch(Exception failure){throw new java.io.IOException("fixture HTTP bridge failed",failure);}finally{exchange.close();}
            });
        }
        CommerceRetentionBindingPublisher publisher(){return new CommerceRetentionBindingPublisher(coordinator,clients,1000);}
        @Override public void close(){for(var server:servers)server.stop(0);}
    }
    @Test void authenticatedHttpBindsSevenSourcesAndPublishesAtomicBusinessInvalidation()throws Exception{
        var first=prepare();try(var http=new HttpFixture(false)){
            var publisher=http.publisher();for(int n=0;n<7;n++)assertThat(publisher.publishOne()).isTrue();assertThat(count("WHERE state='BOUND'")).isEqualTo(7);
            sourceJdbc.get(Source.RETURNS).update("INSERT INTO return_requests(order_id,customer_id,customer_email,request_type,reason,description,status,created_at,updated_at) VALUES(10,17,'fixture@example.test','RETURN','Fixture','Fixture','REQUESTED',now(),now())");
            sourceJdbc.get(Source.RETURNS).update("UPDATE commerce_retention_change_outbox SET retry_at=clock_timestamp()-interval '1 second'");
            assertThatThrownBy(()->new CommerceRetentionInvalidationClient(http.orderOrigin,new org.demo.platformsecurity.ServiceAuthenticationInterceptor("fixture-order-service")).invalidate(first,Phase.REDACTION)).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isTrue();
            var client=new CommerceRetentionInvalidationClient(http.orderOrigin,new org.demo.platformsecurity.ServiceAuthenticationInterceptor("fixture-returns-service"));
            new CommerceRetentionSourceConfiguration.SourcePublisher(sources.get(Source.RETURNS),client,1000).tick();
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs",Boolean.class)).isFalse();assertThat(sourceJdbc.get(Source.RETURNS).queryForObject("SELECT state FROM commerce_retention_change_outbox",String.class)).isEqualTo("SENT");
            var next=jobs.prepare(10,Phase.REDACTION);assertThat(coordinator.prepare(next,Phase.REDACTION)).isEqualTo(Preparation.PREPARED);due();
            for(int n=0;n<7;n++)assertThat(publisher.publishOne()).isTrue();assertThat(count("WHERE expected_epoch=1 AND bound_epoch=2")).isEqualTo(7);
            assertThatThrownBy(()->http.clients.get(Source.RETURNS).bind(first,Phase.REDACTION,0)).hasMessage("RETENTION_BINDING_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT current FROM commerce_retention_jobs WHERE generation=?",Boolean.class,next.chainGeneration())).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_evidence",Long.class)).isZero();
        }
    }
    @Test void httpResponseLostAfterRemoteCommitReplaysTheSameEpoch()throws Exception{
        prepare();try(var http=new HttpFixture(true)){
            var publisher=http.publisher();assertThat(publisher.publishOne()).isTrue();assertThat(count("WHERE state='FAILED' AND attempts=1")).isEqualTo(1);
            String name=jdbc.queryForObject("SELECT source FROM commerce_retention_bindings WHERE state='FAILED'",String.class);
            due();jdbc.update("UPDATE commerce_retention_bindings SET retry_at=clock_timestamp()+interval '1 day' WHERE state='PENDING'");
            assertThat(publisher.publishOne()).isTrue();assertThat(count("WHERE state='BOUND' AND expected_epoch=0 AND bound_epoch=1 AND attempts=2")).isEqualTo(1);
            assertThat(sourceJdbc.get(Source.valueOf(name)).queryForObject("SELECT binding_epoch FROM commerce_retention_source_state",Long.class)).isEqualTo(1);
        }
    }
}
