package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.*;
import org.demo.platformsecurity.ListWindow;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:return-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class ReturnWorkflowTest {
    @Autowired ReturnService service;
    @Autowired ReturnStateStore states;
    @Autowired ReturnRequestRepository cases;
    @MockitoSpyBean ReturnEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ReturnOrderClient orders;
    private ReturnOrderClient.Snapshot snapshot(String status,String payment,Boolean review) {
        return new ReturnOrderClient.Snapshot(501L,42L,"customer@example.test",status,payment,review,
                List.of(new ReturnOrderClient.Line(11L,1L,"Product one",3),new ReturnOrderClient.Line(12L,2L,"Product two",1)));
    }
    @BeforeEach void clear() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        events.deleteAll();cases.deleteAll();
        doAnswer(ignored -> {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return snapshot("COMPLETED","PAID",false);})
                .when(orders).get(anyLong(),anyLong(),anyString());
    }
    private CreateReturnRequest input() { return new CreateReturnRequest(501L,42L,"customer@example.test",ReturnType.RETURN,"Reason","Description"); }
    private ChangeReturnStatusRequest decision(ReturnStatus status,long version,UUID id) { return new ChangeReturnStatusRequest(status,"Decision reason",version,id); }
    @Test void creationBindsCanonicalItemsAndOnlyIdenticalInputCanReplay() {
        var first=service.create(input());var replay=service.create(input());
        assertThat(replay.id()).isEqualTo(first.id());assertThat(first.items()).hasSize(2);assertThat(first.items().getFirst().quantity()).isEqualTo(3);
        assertThat(first.requiresReconciliation()).isFalse();assertThat(events.count()).isEqualTo(1);
        verify(orders,times(1)).get(anyLong(),anyLong(),anyString());
        assertThatThrownBy(() -> service.create(new CreateReturnRequest(501L,42L,"customer@example.test",ReturnType.RETURN,"Changed","Description"))).hasMessageContaining("RETURN_INTENT_CONFLICT");
    }
    @Test void partialIntakeChecksActualOrderItemIdentityAndPurchasedQuantity() {
        var partial=new CreateReturnRequest(501L,42L,"customer@example.test",ReturnType.RETURN,"Reason","Description",List.of(new CreateReturnRequest.Item(11L,2)));
        var result=service.create(partial);assertThat(result.items()).singleElement().satisfies(i -> {assertThat(i.quantity()).isEqualTo(2);assertThat(i.orderedQuantity()).isEqualTo(3);});
        assertThat(events.count()).isEqualTo(1);
    }
    @Test void foreignItemOrExcessQuantityCannotCreateAnyCaseOrHistory() {
        for(var item:List.of(new CreateReturnRequest.Item(99L,1),new CreateReturnRequest.Item(11L,4)))
            assertThatThrownBy(() -> service.create(new CreateReturnRequest(501L,42L,"customer@example.test",ReturnType.RETURN,"Reason","Description",List.of(item))))
                    .hasMessageContaining("RETURN_QUANTITY_OR_ITEM_MISMATCH");
        assertThat(cases.count()).isZero();assertThat(events.count()).isZero();
    }
    @Test void unpaidRefundedHeldOrUndeliveredReturnIsRejected() {
        for(var order:List.of(snapshot("COMPLETED","COLLECTION_DUE",false),snapshot("COMPLETED","REFUNDED",false),snapshot("COMPLETED","PAID",true),snapshot("PAID","PAID",false),snapshot("COMPLETED","PAID",null))) {
            doReturn(order).when(orders).get(anyLong(),anyLong(),anyString());
            assertThatThrownBy(() -> service.create(input())).hasMessageContaining("RETURN_ORDER_NOT_ELIGIBLE");
        }
        assertThat(cases.count()).isZero();
    }
    @Test void complaintCanBeginForPaidPurchaseBeforeDelivery() {
        doReturn(snapshot("SHIPPED","PAID",false)).when(orders).get(anyLong(),anyLong(),anyString());
        assertThat(service.create(new CreateReturnRequest(501L,42L,"customer@example.test",ReturnType.COMPLAINT,"Reason","Description")).status()).isEqualTo(ReturnStatus.REQUESTED);
    }
    @Test void concurrentFirstSubmissionsHaveOneCaseAndOneEvent() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> {start.await();return service.create(input());});var second=pool.submit(() -> {start.await();return service.create(input());});start.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).id()).isEqualTo(second.get(10,TimeUnit.SECONDS).id());
        }
        assertThat(cases.count()).isEqualTo(1);assertThat(events.count()).isEqualTo(1);
    }
    @Test void decisionsAreVersionedIdempotentAndDoNotPretendFinancialCompletion() {
        var first=service.create(input());var command=decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID());
        var reviewed=service.changeStatus(first.id(),command);assertThat(reviewed.version()).isEqualTo(1);
        assertThat(service.changeStatus(first.id(),command).version()).isEqualTo(1);assertThat(events.count()).isEqualTo(2);
        assertThatThrownBy(() -> service.changeStatus(first.id(),decision(ReturnStatus.APPROVED,0,UUID.randomUUID()))).hasMessageContaining("RETURN_VERSION_CONFLICT");
        assertThatThrownBy(() -> service.changeStatus(first.id(),decision(ReturnStatus.REJECTED,0,command.operationId()))).hasMessageContaining("RETURN_OPERATION_CONFLICT");
        var approved=service.changeStatus(first.id(),decision(ReturnStatus.APPROVED,1,UUID.randomUUID()));
        assertThat(approved.effectsState()).isEqualTo("DOMAIN_RECONCILIATION_REQUIRED");
        assertThat(service.changeStatus(first.id(),command).status()).isEqualTo(ReturnStatus.APPROVED);
        assertThatThrownBy(() -> service.changeStatus(first.id(),decision(ReturnStatus.COMPLETED,2,UUID.randomUUID()))).hasMessageContaining("RETURN_DOMAIN_EFFECTS_NOT_RECONCILED");
        assertThat(events.count()).isEqualTo(3);
    }
    @Test void caseAndItemsRollBackIfSubmissionHistoryCannotCommit() {
        doThrow(new IllegalStateException("Journal unavailable")).when(events).saveAndFlush(any(ReturnEvent.class));
        assertThatThrownBy(() -> service.create(input())).hasMessageContaining("Journal unavailable");
        assertThat(cases.count()).isZero();assertThat(events.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from return_lines",Integer.class)).isZero();
    }
    @Test void approvalRechecksFinancialStateAndRejectedCaseCannotBeReopened() {
        var first=service.create(input());service.changeStatus(first.id(),decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID()));
        doReturn(snapshot("COMPLETED","REFUND_PENDING",true)).when(orders).get(anyLong(),anyLong(),anyString());
        assertThatThrownBy(() -> service.changeStatus(first.id(),decision(ReturnStatus.APPROVED,1,UUID.randomUUID()))).hasMessageContaining("RETURN_ORDER_NOT_ELIGIBLE");
        var rejected=service.changeStatus(first.id(),decision(ReturnStatus.REJECTED,1,UUID.randomUUID()));assertThat(rejected.status()).isEqualTo(ReturnStatus.REJECTED);
        assertThatThrownBy(() -> service.changeStatus(first.id(),decision(ReturnStatus.UNDER_REVIEW,2,UUID.randomUUID()))).hasMessageContaining("RETURN_INVALID_TRANSITION");
    }
    @Test void lostDecisionReplyReplaysWhileOrderVerificationIsUnavailable() {
        var first=service.create(input());var command=decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID());service.changeStatus(first.id(),command);
        doThrow(new IllegalStateException("offline")).when(orders).get(anyLong(),anyLong(),anyString());
        assertThat(service.changeStatus(first.id(),command).status()).isEqualTo(ReturnStatus.UNDER_REVIEW);assertThat(service.create(input()).id()).isEqualTo(first.id());
        assertThat(events.count()).isEqualTo(2);
    }
    @Test void legacyCaseNeedsExplicitVerificationBeforeNewDecisions() {
        var old=cases.saveAndFlush(new ReturnRequest(501L,42L,"customer@example.test",ReturnType.RETURN,"Reason","Description"));
        assertThatThrownBy(() -> service.create(input())).hasMessageContaining("RETURN_LEGACY_REVIEW_REQUIRED");
        assertThatThrownBy(() -> service.changeStatus(old.getId(),decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID()))).hasMessageContaining("RETURN_LEGACY_REVIEW_REQUIRED");
        var command=new ReconcileReturnRequest(0L,UUID.randomUUID(),"Verify legacy case");var verified=service.reconcile(old.getId(),command);
        assertThat(verified.requiresReconciliation()).isFalse();assertThat(verified.version()).isEqualTo(1);assertThat(verified.items()).hasSize(2);
        assertThat(service.reconcile(old.getId(),command).version()).isEqualTo(1);
        assertThat(service.changeStatus(old.getId(),decision(ReturnStatus.UNDER_REVIEW,1,UUID.randomUUID())).version()).isEqualTo(2);
    }
    @Test void concurrentDecisionsForOneVersionHaveExactlyOneWinner() throws Exception {
        var first=service.create(input());service.changeStatus(first.id(),decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID()));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> approve(first.id(),start));var b=pool.submit(() -> approve(first.id(),start));start.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
        assertThat(events.count()).isEqualTo(3);
    }
    private boolean approve(Long id,CountDownLatch start) throws Exception {
        start.await();try {service.changeStatus(id,decision(ReturnStatus.APPROVED,1,UUID.randomUUID()));return true;}
        catch(org.springframework.web.server.ResponseStatusException rejected) {assertThat(rejected.getStatusCode().value()).isEqualTo(409);return false;}
    }
    @Test void redactedCaseIsReadableButReplayAndDecisionsCannotRestoreText(){
        var first=service.create(input());var command=decision(ReturnStatus.UNDER_REVIEW,0,UUID.randomUUID());service.changeStatus(first.id(),command);
        jdbc.update("UPDATE return_requests SET privacy_redacted=true,customer_email=NULL,reason=NULL,description=NULL,moderation_note=NULL WHERE id=?",first.id());
        jdbc.update("UPDATE return_events SET note=NULL,actor_ref=NULL WHERE return_id=?",first.id());
        assertThat(states.get(first.id()).description()).isNull();
        assertThatThrownBy(()->service.create(input())).hasMessageContaining("RETURN_PRIVACY_REDACTED_READONLY");
        assertThatThrownBy(()->service.changeStatus(first.id(),command)).hasMessageContaining("RETURN_PRIVACY_REDACTED_READONLY");
        assertThat(events.count()).isEqualTo(2);
    }
}
