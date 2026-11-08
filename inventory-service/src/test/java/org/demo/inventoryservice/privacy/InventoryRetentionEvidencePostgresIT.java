package org.demo.inventoryservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.demo.inventoryservice.privacy.InventoryRetentionEvidence.Code;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.inventoryservice.privacy.InventoryRetentionEvidence.*;

@Testcontainers
class InventoryRetentionEvidencePostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("inventory_evidence").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc;static DriverManagerDataSource ds;static DataSourceTransactionManager manager;
    CommerceRetentionSourceOutbox outbox;InventoryRetentionEvidence collector;
    @BeforeAll static void setup(){ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE inventory_reservations,inventory_reservation_items,commerce_retention_source_state,commerce_retention_change_outbox,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");outbox=new CommerceRetentionSourceOutbox(ds,manager);collector=new InventoryRetentionEvidence(ds,manager);seed(10);}
    void seed(long order){jdbc.update("INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(?,?,'RESERVED',now(),now(),now()+interval '1 hour')",order,order);jdbc.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES(?,1,2)",order);}
    Scope bind(long order,Phase phase,long epoch){var result=outbox.bind(new Subject(order,0,0,UUID.randomUUID()),phase,epoch);return new Scope(order,result.subject().chainGeneration(),0,phase,result.epoch(),result.revision());}
    Expected expected(){return new Expected(10,Terminal.CONFIRMED,List.of(new Line(1,2)));}
    Timestamp clock(){return jdbc.queryForObject("SELECT local_reconciled_at FROM commerce_retention_source_state WHERE order_id=10",Timestamp.class);}
    void close(){jdbc.update("UPDATE inventory_reservations SET status='CONFIRMED' WHERE order_id=10");}
    Scope closed(){bind(10,Phase.REDACTION,0);close();return bind(10,Phase.REDACTION,1);}
    @Test void inventoryFenceIsSharedByReplicasAndRejectsItemWrites() {
        var s=closed();var token=UUID.randomUUID();
        var scope=new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,s.generation(),s.orderVersion(),Phase.REDACTION,s.epoch(),s.revision(),token);
        var command=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,scope);
        var first=new CommerceRetentionWriteFence(Participant.INVENTORY,ds,manager);
        assertThat(first.apply(command).status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
        var second=new CommerceRetentionWriteFence(Participant.INVENTORY,ds,manager);
        var other=new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,s.generation(),s.orderVersion(),Phase.REDACTION,s.epoch(),s.revision(),UUID.randomUUID());
        assertThat(second.apply(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,other)).status())
            .isEqualTo(PeerRetentionFenceProtocol.Status.CONFLICT);
        assertThatThrownBy(()->jdbc.update("UPDATE inventory_reservation_items SET quantity=3 WHERE reservation_id=10"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(second.apply(new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,scope)).status())
            .isEqualTo(PeerRetentionFenceProtocol.Status.RELEASED);
        assertThat(jdbc.update("UPDATE inventory_reservation_items SET quantity=3 WHERE reservation_id=10")).isOne();
    }
    @Test void observedCloseProducesOnlyALocalSnapshotWithTheDurableClock(){var scope=closed();var result=collector.collect(scope,expected());assertThat(result.state()).isEqualTo(State.CONFIRMED);assertThat(result.localReconciledAt()).isEqualTo(clock().toInstant());assertThat(result.scope()).isEqualTo(scope);}
    @Test void historicalTerminalAndTimestampChangesDoNotInventAMilestone(){close();var scope=bind(10,Phase.REDACTION,0);jdbc.update("UPDATE inventory_reservations SET created_at=now()-interval '1000 days',updated_at=now()-interval '999 days'");scope=bind(10,Phase.REDACTION,1);assertThat(clock()).isNull();assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.MISSING_MILESTONE);}
    @Test void activeOrAbsentReservationNeverConfirmsAbsence(){var scope=bind(10,Phase.REDACTION,0);assertThat(collector.collect(scope,expected()).state()).isEqualTo(State.BLOCKED);var absent=bind(11,Phase.REDACTION,0);assertThat(collector.collect(absent,new Expected(11,Terminal.RELEASED,List.of(new Line(1,2)))).state()).isEqualTo(State.UNKNOWN);}
    @Test void unboundChangesDoNotCreateTrackingOrAClock(){close();assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_state",Long.class)).isZero();}
    @Test void restartGenerationAndTimestampOnlyChangesPreserveClock(){var scope=closed();var original=clock();jdbc.update("UPDATE inventory_reservations SET updated_at=now()+interval '1 day'");assertThat(collector.collect(scope,expected()).state()).isEqualTo(State.CONFIRMED);scope=bind(10,Phase.REDACTION,2);assertThat(new InventoryRetentionEvidence(ds,manager).collect(scope,expected()).localReconciledAt()).isEqualTo(original.toInstant());}
    @Test void dirtyBindingCannotBeClearedByAcknowledgementOrSameGeneration(){var scope=bind(10,Phase.REDACTION,0);close();assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.STALE);var work=outbox.claim().orElseThrow();assertThat(outbox.acknowledge(work)).isTrue();outbox.bind(new Subject(10,0,0,scope.generation()),Phase.REDACTION,0);assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.STALE);}
    @Test void everyScopeFieldMustMatchTheCleanBinding(){var s=closed();for(var forged:List.of(new Scope(11,s.generation(),0,s.phase(),s.epoch(),s.revision()),new Scope(10,UUID.randomUUID(),0,s.phase(),s.epoch(),s.revision()),new Scope(10,s.generation(),1,s.phase(),s.epoch(),s.revision()),new Scope(10,s.generation(),0,Phase.MINIMUM_REMOVAL,s.epoch(),s.revision()),new Scope(10,s.generation(),0,s.phase(),s.epoch()+1,s.revision()),new Scope(10,s.generation(),0,s.phase(),s.epoch(),s.revision()+1)))assertThat(collector.collect(forged,expected()).code()).isEqualTo(Code.STALE);}
    @Test void reservationIdentityStatusAndExactLinesMustMatch(){var scope=closed();for(var wrong:List.of(new Expected(11,Terminal.CONFIRMED,List.of(new Line(1,2))),new Expected(10,Terminal.RELEASED,List.of(new Line(1,2))),new Expected(10,Terminal.CONFIRMED,List.of(new Line(1,1))),new Expected(10,Terminal.CONFIRMED,List.of(new Line(2,2)))))assertThat(collector.collect(scope,wrong).code()).isEqualTo(Code.REVIEW_REQUIRED);}
    @Test void reopeningAndClosedBusinessChangesRemoveTheOldClock(){closed();jdbc.update("UPDATE inventory_reservations SET status='RESERVED'");assertThat(clock()).isNull();close();assertThat(clock()).isNotNull();jdbc.update("UPDATE inventory_reservation_items SET quantity=3");assertThat(clock()).isNull();var scope=bind(10,Phase.REDACTION,2);assertThat(collector.collect(scope,new Expected(10,Terminal.CONFIRMED,List.of(new Line(1,3)))).code()).isEqualTo(Code.MISSING_MILESTONE);}
    @Test void duplicateAndOversizedLocalLinesRequireReview(){closed();jdbc.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES(10,1,2)");assertThat(clock()).isNull();assertThat(collector.collect(bind(10,Phase.REDACTION,2),expected()).code()).isEqualTo(Code.REVIEW_REQUIRED);jdbc.update("DELETE FROM inventory_reservation_items");jdbc.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) SELECT 10,n,2 FROM generate_series(1,101) n");assertThat(collector.collect(bind(10,Phase.REDACTION,3),expected()).code()).isEqualTo(Code.REVIEW_REQUIRED);}
    @Test void movingAChildInvalidatesBothOrderMilestones(){closed();seed(11);bind(11,Phase.REDACTION,0);jdbc.update("UPDATE inventory_reservations SET status='CONFIRMED' WHERE order_id=11");jdbc.update("UPDATE inventory_reservation_items SET reservation_id=11 WHERE reservation_id=10");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_retention_source_state WHERE local_reconciled_at IS NOT NULL",Long.class)).isZero();}
    @Test void rollbackKeepsTheOriginalClockAndActiveState(){var scope=bind(10,Phase.REDACTION,0);new TransactionTemplate(manager).executeWithoutResult(status->{close();assertThat(clock()).isNotNull();status.setRollbackOnly();});assertThat(clock()).isNull();assertThat(collector.collect(scope,expected()).state()).isEqualTo(State.BLOCKED);}
    @Test void restoreRetainsClockButDeniesObservationAndCannotBeUndone(){var scope=closed();var time=clock();jdbc.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE,invalidated=TRUE,binding_epoch=binding_epoch+1,source_revision=source_revision+1");assertThat(clock()).isEqualTo(time);assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.QUARANTINED);assertThatThrownBy(()->jdbc.update("UPDATE commerce_retention_source_state SET restore_blocked=FALSE")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    @Test void rawClockEditsDeletionAndInvalidExpectationsAreRejected(){closed();for(String sql:List.of("UPDATE commerce_retention_source_state SET local_reconciled_at=now()-interval '1000 days'","UPDATE commerce_retention_source_state SET local_open_seen=TRUE,local_reconciled_at=NULL","DELETE FROM commerce_retention_source_state"))assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->new Expected(10,Terminal.CONFIRMED,List.of())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->new Expected(10,Terminal.CONFIRMED,List.of(new Line(1,1),new Line(1,2)))).isInstanceOf(IllegalArgumentException.class);}
    @Test void minimumRemovalCannotUseTheProcessClockAsARedactionDate(){closed();var scope=bind(10,Phase.MINIMUM_REMOVAL,2);assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.MISSING_REDACTION);assertThat(collector.collect(scope,expected()).localReconciledAt()).isNull();}
    @Test void uncommittedWriterCannotYieldAPrematureConfirmation()throws Exception{
        var scope=bind(10,Phase.REDACTION,0);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()){
            var writer=executor.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{close();entered.countDown();try{if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Fixture writer timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();try{assertThatThrownBy(()->collector.collect(scope,expected())).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{release.countDown();}writer.get(5,TimeUnit.SECONDS);
        }
        assertThat(collector.collect(scope,expected()).code()).isEqualTo(Code.STALE);assertThat(collector.collect(bind(10,Phase.REDACTION,1),expected()).state()).isEqualTo(State.CONFIRMED);
    }
    @Test void authenticatedHttpControllerAndClientReadTheActualSourceClock()throws Exception{
        var scope=closed();var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var issuer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        try{
            issuer.createContext("/internal/auth/introspect",e->{
                assertThat(e.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo("fixture-source-own");String token=json.readTree(e.getRequestBody().readAllBytes()).path("token").asText();
                var actor=switch(token){case "order"->new org.demo.platformsecurity.Actor("SERVICE","order-service",null,null);case "other"->new org.demo.platformsecurity.Actor("SERVICE","returns-service",null,null);case "customer"->new org.demo.platformsecurity.Actor("CUSTOMER",null,17L,null);case "operator"->new org.demo.platformsecurity.Actor("OPERATOR",null,null,null);default->org.demo.platformsecurity.Actor.ANONYMOUS;};
                byte[] raw=json.writeValueAsBytes(actor);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,raw.length);e.getResponseBody().write(raw);e.close();
            });issuer.start();
            org.springframework.boot.web.servlet.FilterRegistrationBean<?> registration=org.springframework.test.util.ReflectionTestUtils.invokeMethod(new org.demo.platformsecurity.PlatformSecurityConfiguration(),"platformAuthenticationFilter","inventory-service","http://127.0.0.1:"+issuer.getAddress().getPort(),"fixture-source-own");
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new InventoryRetentionController(ds,manager))
                .setHandlerExceptionResolvers(new org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver(),new org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver()).addFilters(registration.getFilter()).build();
            String path=InventoryRetentionProtocol.PATH;
            var command=new InventoryRetentionProtocol.Command(10L,scope.generation(),0L,Phase.REDACTION,scope.epoch(),scope.revision(),10L,InventoryRetentionProtocol.Terminal.CONFIRMED,List.of(new InventoryRetentionProtocol.Line(1L,2)));
            byte[] request=json.writeValueAsBytes(command);
            server.createContext(path,e->{try{
                var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",e.getRequestHeaders().getFirst("Authorization")).contentType("application/json").content(e.getRequestBody().readNBytes(8193))).andReturn().getResponse();
                byte[] raw=response.getContentAsByteArray();e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(response.getStatus(),raw.length==0?-1:raw.length);if(raw.length>0)e.getResponseBody().write(raw);
            }catch(Exception failure){throw new java.io.IOException("Fixture HTTP failed",failure);}finally{e.close();}});server.start();
            var client=new InventoryRetentionClient("http://127.0.0.1:"+server.getAddress().getPort(),new org.demo.platformsecurity.ServiceAuthenticationInterceptor("order"));
            assertThat(client.collect(command).reconciledAt()).isEqualTo(clock().toInstant());
            for(String token:List.of("customer","operator","other")){
                var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer "+token).header("X-Service-Name","order-service").contentType("application/json").content(request)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(403);
            }
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("X-Service-Name","order-service").contentType("application/json").content(request)).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization","Bearer order")).andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer order").contentType("application/json").content("x".repeat(8193))).andReturn().getResponse().getStatus()).isEqualTo(413);
            for(String bad:List.of("{}",new String(request,java.nio.charset.StandardCharsets.UTF_8).replace("\"quantity\":2","\"quantity\":2.0")))assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization","Bearer order").contentType("application/json").content(bad)).andReturn().getResponse().getStatus()).isEqualTo(400);
            var fenceScope=new PeerRetentionFenceProtocol.Scope(Participant.INVENTORY,10,scope.generation(),0,Phase.REDACTION,scope.epoch(),scope.revision(),UUID.randomUUID());
            var fenceCommand=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.PREPARE,fenceScope);
            String fencePath=PeerRetentionFenceProtocol.PATH;byte[] fenceBody=json.writeValueAsBytes(fenceCommand);
            for(String token:List.of("customer","operator","other"))
                assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fencePath).header("Authorization","Bearer "+token).contentType("application/json").content(fenceBody)).andReturn().getResponse().getStatus()).isEqualTo(403);
            var fenced=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fencePath).header("Authorization","Bearer order").contentType("application/json").content(fenceBody)).andReturn().getResponse();
            assertThat(fenced.getStatus()).isEqualTo(200);
            assertThat(PeerRetentionFenceProtocol.parseReply(fenced.getContentAsByteArray(),fenceCommand).status()).isEqualTo(PeerRetentionFenceProtocol.Status.FENCED);
            assertThatThrownBy(()->jdbc.update("UPDATE inventory_reservation_items SET quantity=3 WHERE reservation_id=10")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            var release=new PeerRetentionFenceProtocol.Command(PeerRetentionFenceProtocol.Action.RELEASE,fenceScope);
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fencePath).header("Authorization","Bearer order").contentType("application/json").content(json.writeValueAsBytes(release))).andReturn().getResponse().getStatus()).isEqualTo(200);
            jdbc.update("UPDATE commerce_retention_source_state SET restore_blocked=TRUE");assertThat(client.collect(command).code()).isEqualTo(InventoryRetentionProtocol.Code.QUARANTINED);
        }finally{server.stop(0);issuer.stop(0);}
    }
}
