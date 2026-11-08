package org.demo.paymentservice.payment;

import org.demo.paymentservice.followup.*;
import org.demo.paymentservice.inventory.*;
import org.demo.paymentservice.order.*;
import org.demo.paymentservice.outbox.PaymentOutboxRepository;
import org.demo.paymentservice.web.*;
import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import org.springframework.web.context.request.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:stripe-a05;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "payment.provider=disabled","payment.stripe.recovery-enabled=false","payment.stripe.webhook-secret=whsec_test_fixture_secret"})
@Import(StripeSettlementRecoveryTest.Config.class)
class StripeSettlementRecoveryTest {
    static final String SESSION="cs_test_session0001", INTENT="pi_test_intent0001";
    static final String API="https://api.stripe.com";
    static final BigDecimal AMOUNT=new BigDecimal("10.00");
    static final class Fixture {
        final MockRestServiceServer server;
        final StripePaymentGateway gateway;
        Fixture() {
            var builder=RestClient.builder().baseUrl(API);
            server=MockRestServiceServer.bindTo(builder).build();
            gateway=new StripePaymentGateway("sk_test_fixture","http://127.0.0.1:18070/index.html",API,false,builder.build());
        }
    }
    @TestConfiguration static class Config {
        @Bean Fixture fixture() { return new Fixture(); }
        @Bean @Primary PaymentGateway testStripe(Fixture fixture) { return fixture.gateway; }
    }
    @Autowired Fixture fixture;
    @Autowired PaymentRepository payments;
    @Autowired PaymentStateStore states;
    @Autowired PaymentRefundRepository refundRows;
    @Autowired PaymentRefundStateStore refundStates;
    @Autowired PaymentRefundService refunds;
    @Autowired StripeCheckoutService checkout;
    @Autowired StripeExternalRefundService externalRefunds;
    @Autowired PaymentService paymentService;
    @Autowired StripeWebhookService webhook;
    @Autowired PaymentFollowUpRepository tasks;
    @Autowired FollowUpStateStore followUpStates;
    @Autowired StripeWebhookReceiptRepository receipts;
    @Autowired PaymentOutboxRepository events;
    @Autowired PaymentOrderGateRepository gates;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OrderClient orders;
    @MockitoBean InventoryClient inventory;
    @MockitoBean FollowUpRunner runner;
    @BeforeEach void clear() {
        fixture.server.reset();reset(orders,inventory,runner);
        receipts.deleteAll();refundRows.deleteAll();tasks.deleteAll();events.deleteAll();payments.deleteAll();gates.deleteAll();
        when(orders.getOrder(101L)).thenReturn(order("CONFIRMED","UNPAID",false));
        when(orders.markPaid(101L)).thenReturn(order("PAID","PAID",false));
        when(orders.refund(eq(101L),eq("begin"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false)))
                .thenReturn(order("CONFIRMED","REFUND_PENDING",true));
        when(orders.refund(eq(101L),eq("complete"),anyLong(),eq(AMOUNT),eq("PLN"),eq(true)))
                .thenReturn(order("CANCELLED","REFUNDED",false));
        when(orders.refund(eq(101L),eq("complete"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false)))
                .thenReturn(order("PAID","REFUNDED",true));
        when(orders.refund(eq(101L),eq("begin"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false),eq(true)))
                .thenReturn(order("PAID","REFUND_PENDING",true));
        when(orders.refund(eq(101L),eq("complete"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false),eq(true)))
                .thenReturn(order("PAID","REFUNDED",true));
    }
    @AfterEach void verified() { fixture.server.verify();RequestContextHolder.resetRequestAttributes(); }
    private Payment seed() {
        var ticket=states.claim(new Payment(101L,AMOUNT,"CARD","checkout-request-0001"));
        states.recordStripeCheckout(ticket,SESSION,"https://checkout.stripe.com/c/pay/test",List.of(
                new PaymentFollowUp(101L,FollowUpKind.SHIPMENT_TRACKING,"{}",true)));
        states.release(ticket);return payments.findById(ticket.payment().getId()).orElseThrow();
    }
    private OrderResponse order(String status,String financial,boolean review) {
        return new OrderResponse(101L,List.of(),AMOUNT,status,42L,null,"STANDARD_COURIER",BigDecimal.ZERO,
                null,BigDecimal.ZERO,"HOME_DELIVERY",null,null,null,false,17L,"owner@example.test",financial,null,null,review);
    }
    private String session(String paymentStatus,String status) {
        return """
            {"id":"%s","object":"checkout.session","mode":"payment","livemode":false,"amount_total":1000,
             "currency":"pln","payment_intent":"%s","payment_status":"%s","status":"%s",
             "client_reference_id":"101","metadata":{"order_id":"101"}}
            """.formatted(SESSION,INTENT,paymentStatus,status);
    }
    private void expectSession(String financial,String status) {
        fixture.server.expect(requestTo(API+"/v1/checkout/sessions/"+SESSION)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(session(financial,status),MediaType.APPLICATION_JSON));
    }
    private String refundJson(String status) {
        var row=refundRows.findAll().getFirst();
        return """
            {"id":"re_test_refund0001","object":"refund","amount":1000,"currency":"pln","payment_intent":"%s",
             "status":"%s","metadata":{"operation_id":"%s"}}
            """.formatted(INTENT,status,row.getOperationId());
    }
    private void expectRefundList(boolean found,String status) {
        fixture.server.expect(requestTo(API+"/v1/refunds?payment_intent="+INTENT+"&limit=100"))
                .andRespond(request -> withSuccess("{\"data\":["+(found ? refundJson(status) : "")+"],\"has_more\":false}",MediaType.APPLICATION_JSON).createResponse(request));
    }
    private void expectRefundPost(String status) {
        fixture.server.expect(requestTo(API+"/v1/refunds")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("Idempotency-Key")).startsWith("minitech-refund-");
                    assertThat(request.getHeaders().getFirst("X-Service-Token")).isNull();
                }).andRespond(request -> withSuccess(refundJson(status),MediaType.APPLICATION_JSON).createResponse(request));
    }
    private void stockReleased() {
        doThrow(new InventoryServiceException("released",new HttpClientErrorException(HttpStatus.CONFLICT)))
                .when(inventory).confirmDeferred(101L,42L);
    }
    private Payment current(Payment p) { return payments.findById(p.getId()).orElseThrow(); }
    private void nextProviderPhase() { fixture.server.verify();fixture.server.reset(); }

    private String externalRefund(int amount) {
        return "{\"id\":\"re_external0001\",\"object\":\"refund\",\"amount\":"+amount+
                ",\"currency\":\"pln\",\"payment_intent\":\""+INTENT+"\",\"status\":\"succeeded\",\"metadata\":{}}";
    }
    private void expectExternalRefund(int amount) {
        fixture.server.expect(requestTo(API+"/v1/refunds/re_external0001")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(externalRefund(amount),MediaType.APPLICATION_JSON));
    }
    @Test void dashboardFullRefundIsObservedWithoutCreatingAnotherMoneyTransfer() {
        var p=seed();expectSession("paid","complete");checkout.reconcile(p.getId());nextProviderPhase();
        expectExternalRefund(1000);expectSession("paid","complete");
        assertThat(externalRefunds.observe("re_external0001")).isEqualTo(p.getId());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(current(p).stripeExternalHold()).isTrue();assertThat(refundRows.count()).isZero();
        assertThat(current(p).getStripeError()).isEqualTo("EXTERNAL_REFUND_ORDER_REVIEW");
        verify(orders).refund(101L,"complete",p.getId(),AMOUNT,"PLN",false,true);
        assertThat(followUpStates.claim(tasks.findAll().getFirst().getId())).isNull();
    }
    @Test void dashboardPartialRefundFencesFulfillmentAndRequiresReview() {
        var p=seed();expectSession("paid","complete");checkout.reconcile(p.getId());nextProviderPhase();
        expectExternalRefund(500);expectSession("paid","complete");externalRefunds.observe("re_external0001");
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(current(p).getStripeError()).isEqualTo("EXTERNAL_PARTIAL_REFUND_REVIEW");
        assertThat(current(p).stripeExternalHold()).isTrue();assertThat(refundRows.count()).isZero();
        verify(orders,never()).refund(eq(101L),eq("complete"),anyLong(),any(),anyString(),anyBoolean(),anyBoolean());
        assertThat(followUpStates.claim(tasks.findAll().getFirst().getId())).isNull();
    }
    @Test void pendingExternalRefundRecordsLaterFinancialSuccessBeforeRetryingOrder() {
        var p=seed();expectSession("paid","complete");checkout.reconcile(p.getId());nextProviderPhase();
        fixture.server.expect(requestTo(API+"/v1/refunds/re_external0001"))
                .andRespond(withSuccess(externalRefund(1000).replace("succeeded","pending"),MediaType.APPLICATION_JSON));
        expectSession("paid","complete");externalRefunds.observe("re_external0001");
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        nextProviderPhase();expectExternalRefund(1000);
        when(orders.refund(eq(101L),eq("complete"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false),eq(true)))
                .thenThrow(new OrderServiceException("temporary failure")).thenReturn(order("PAID","REFUNDED",true));
        assertThatThrownBy(() -> checkout.reconcile(p.getId())).isInstanceOf(OrderServiceException.class);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payments.stripeDue(Instant.now().plusSeconds(60),org.springframework.data.domain.PageRequest.of(0,20))).contains(p.getId());
        nextProviderPhase();expectExternalRefund(1000);checkout.reconcile(p.getId());
        assertThat(current(p).getStripeError()).isEqualTo("EXTERNAL_REFUND_ORDER_REVIEW");assertThat(refundRows.count()).isZero();
    }
    @Test void refundArrivingBeforePaidWebhookNeverStartsFulfillmentAndCanRecoverOrderHold() {
        var p=seed();expectExternalRefund(1000);
        fixture.server.expect(requestTo(API+"/v1/payment_intents/"+INTENT)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\""+INTENT+"\",\"object\":\"payment_intent\",\"livemode\":false,\"metadata\":{\"order_id\":\"101\"}}",MediaType.APPLICATION_JSON));
        expectSession("paid","complete");
        when(orders.refund(eq(101L),eq("begin"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false),eq(true)))
                .thenThrow(new OrderServiceException("temporary failure")).thenReturn(order("CONFIRMED","REFUND_PENDING",true));
        assertThatThrownBy(() -> externalRefunds.observe("re_external0001")).isInstanceOf(OrderServiceException.class);
        assertThat(current(p).stripeExternalHold()).isTrue();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payments.stripeDue(Instant.now().plusSeconds(60),org.springframework.data.domain.PageRequest.of(0,20))).contains(p.getId());
        nextProviderPhase();expectExternalRefund(1000);checkout.reconcile(p.getId());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(orders,never()).markPaid(anyLong());verify(inventory,never()).confirmDeferred(anyLong(),anyLong());
        verifyNoInteractions(runner);assertThat(refundRows.count()).isZero();
    }

    @Test void signedPaidWebhookIsIdempotentAndNotificationStillWaitsForInvoice() throws Exception {
        var p=seed();tasks.saveAllAndFlush(List.of(new PaymentFollowUp(101L,FollowUpKind.INVOICE_ISSUE,"{}",true),
                new PaymentFollowUp(101L,FollowUpKind.PAYMENT_NOTIFICATION,"{}",true)));
        expectSession("paid","complete");
        String body="{\"id\":\"evt_event00001\",\"type\":\"checkout.session.completed\",\"livemode\":false,\"data\":{\"object\":"+session("paid","complete")+"}}";
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);long time=Instant.now().getEpochSecond();
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("whsec_test_fixture_secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((time+".").getBytes(StandardCharsets.US_ASCII));String signature="t="+time+",v1="+HexFormat.of().formatHex(mac.doFinal(bytes));
        webhook.receive(bytes,signature);webhook.receive(bytes,signature);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(receipts.count()).isEqualTo(1);
        assertThat(tasks.findByOrderIdAndKind(101L,FollowUpKind.PAYMENT_NOTIFICATION).orElseThrow().getStatus()).isEqualTo(FollowUpStatus.WAITING);
        assertThat(events.count()).isZero();verify(orders).markPaid(101L);verify(inventory).confirmDeferred(101L,42L);
    }
    @Test void lostOrderPaidResponseRecoversWithoutAnotherChargeOrAnotherStatusChange() {
        var p=seed();expectSession("paid","complete");
        when(orders.markPaid(101L)).thenAnswer(call -> {
            when(orders.getOrder(101L)).thenReturn(order("PAID","PAID",false));
            throw new OrderServiceException("response lost");
        });
        assertThatThrownBy(() -> checkout.reconcile(p.getId())).isInstanceOf(OrderServiceException.class);
        assertThat(current(p).stripePaid()).isTrue();assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING);
        nextProviderPhase();expectSession("paid","complete");checkout.reconcile(p.getId());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);verify(orders,times(1)).markPaid(101L);
    }
    @Test void latePaidCheckoutRefundsAndCompensatesInsteadOfBecomingPaid() {
        var p=seed();stockReleased();expectSession("paid","complete");expectRefundList(false,null);expectRefundPost("succeeded");
        checkout.reconcile(p.getId());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRows.findAll()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.SUCCEEDED);assertThat(r.getBusinessOutcome()).isEqualTo("CANCELLED_AFTER_REFUND");
        });
        verify(inventory).compensateStripe(101L,42L);verify(orders,never()).markPaid(anyLong());verifyNoInteractions(runner);
    }
    @Test void lostRefundResponseIsReconciledByMetadataAndNeverPostsASecondRefund() {
        var p=seed();stockReleased();expectSession("paid","complete");expectRefundList(false,null);
        fixture.server.expect(requestTo(API+"/v1/refunds")).andExpect(method(HttpMethod.POST)).andRespond(request -> { throw new java.io.IOException("lost response"); });
        checkout.reconcile(p.getId());var r=refundRows.findAll().getFirst();
        assertThat(r.getSubmittedAt()).isNotNull();assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.REQUESTED);
        nextProviderPhase();expectRefundList(true,"succeeded");refunds.process(r.getId());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);assertThat(refundStates.get(r.getId()).isBusinessCompleted()).isTrue();
    }
    @Test void providerPendingDoesNotClaimRefundSuccessAndCanonicalReadCompletesIt() {
        var p=seed();stockReleased();expectSession("paid","complete");expectRefundList(false,null);expectRefundPost("pending");
        checkout.reconcile(p.getId());var r=refundRows.findAll().getFirst();
        assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.PENDING);assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(inventory,never()).compensateStripe(anyLong(),anyLong());
        nextProviderPhase();fixture.server.expect(requestTo(API+"/v1/refunds/re_test_refund0001"))
                .andRespond(withSuccess(refundJson("succeeded"),MediaType.APPLICATION_JSON));
        refunds.process(r.getId());assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
    @Test void crashAfterRefundBeforeOrderCancellationRetriesOnlyBusinessCompensation() {
        var p=seed();stockReleased();expectSession("paid","complete");expectRefundList(false,null);expectRefundPost("succeeded");
        when(orders.refund(eq(101L),eq("complete"),anyLong(),any(),eq("PLN"),eq(true)))
                .thenThrow(new OrderServiceException("down")).thenReturn(order("CANCELLED","REFUNDED",false));
        checkout.reconcile(p.getId());var r=refundRows.findAll().getFirst();
        assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.SUCCEEDED);assertThat(r.isBusinessCompleted()).isFalse();
        nextProviderPhase();refunds.process(r.getId());
        assertThat(refundStates.get(r.getId()).isBusinessCompleted()).isTrue();verify(inventory,times(2)).compensateStripe(101L,42L);
    }
    @Test void expiredOrderCancellationIsRecoveredWithoutReopeningCheckout() {
        var p=seed();expectSession("unpaid","expired");when(orders.cancel(101L)).thenThrow(new OrderServiceException("lost"))
                .thenReturn(order("CANCELLED","UNPAID",false));
        assertThatThrownBy(() -> checkout.reconcile(p.getId())).isInstanceOf(OrderServiceException.class);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        nextProviderPhase();expectSession("unpaid","expired");checkout.reconcile(p.getId());
        assertThat(payments.stripeDue(Instant.now().plusSeconds(60),org.springframework.data.domain.PageRequest.of(0,10))).isEmpty();
    }
    @Test void wrongCanonicalAmountCannotChangeOrderOrPayment() {
        var p=seed();fixture.server.expect(requestTo(API+"/v1/checkout/sessions/"+SESSION))
                .andRespond(withSuccess(session("paid","complete").replace("1000","999"),MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> checkout.reconcile(p.getId())).isInstanceOf(StripeReviewRequiredException.class);
        assertThat(current(p).stripePaid()).isFalse();verifyNoInteractions(inventory,runner);verify(orders,never()).markPaid(anyLong());
        assertThat(current(p).getStripeError()).isEqualTo("SESSION_AMOUNT_CONFLICT");
    }
    @Test void operatorRefundIsFullIdempotentAndKeepsOrderUnderFinancialReview() {
        var p=seed();expectSession("paid","complete");checkout.reconcile(p.getId());nextProviderPhase();
        expectSession("paid","complete");expectRefundList(false,null);expectRefundPost("succeeded");
        var r=refunds.request(p.getId(),"operator-key-0001",AMOUNT,"CUSTOMER_REQUEST","operator@example.test");
        assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.SUCCEEDED);assertThat(r.getBusinessOutcome()).isEqualTo("REFUNDED_ORDER_REVIEW_REQUIRED");
        verify(inventory,never()).compensateStripe(anyLong(),anyLong());nextProviderPhase();expectSession("paid","complete");
        assertThat(refunds.request(p.getId(),"operator-key-0001",AMOUNT,"CUSTOMER_REQUEST","operator@example.test").getId()).isEqualTo(r.getId());
        assertThat(refundRows.count()).isEqualTo(1);
        nextProviderPhase();refunds.reconcile(r.getId());assertThat(refundStates.due()).isEmpty();
    }
    @Test void ineligibleRefundDoesNotCancelUnfinishedPurchaseTasksOrMoveMoney() {
        var p=seed();expectSession("paid","complete");checkout.reconcile(p.getId());nextProviderPhase();
        when(orders.refund(eq(101L),eq("begin"),anyLong(),eq(AMOUNT),eq("PLN"),eq(false)))
                .thenThrow(new OrderServiceException("already shipped",new HttpClientErrorException(HttpStatus.CONFLICT)));
        expectSession("paid","complete");var r=refunds.request(p.getId(),"operator-key-0001",AMOUNT,"CUSTOMER_REQUEST","operator");
        assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.FAILED);assertThat(r.rejectedWithoutMoney()).isTrue();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        var task=tasks.findByOrderIdAndKind(101L,FollowUpKind.SHIPMENT_TRACKING).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(FollowUpStatus.PENDING);
        var claim=followUpStates.claim(task.getId());assertThat(claim).isNotNull();followUpStates.fail(claim,"TEST_RELEASE",false);
    }
    @Test void businessConflictCannotEraseAConfirmedFinancialRefund() {
        var p=seed();stockReleased();expectSession("paid","complete");expectRefundList(false,null);expectRefundPost("succeeded");
        when(orders.refund(eq(101L),eq("complete"),anyLong(),any(),eq("PLN"),eq(true))).thenReturn(order("PAID","REFUNDED",true));
        checkout.reconcile(p.getId());var r=refundRows.findAll().getFirst();
        assertThat(r.getStatus()).isEqualTo(PaymentRefund.Status.SUCCEEDED);assertThat(r.getBusinessOutcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
    @Test void partialAmountOrConflictingRequestCannotStartARefund() {
        var p=seed();expectSession("paid","complete");
        assertThatThrownBy(() -> refunds.request(p.getId(),"operator-key-0001",BigDecimal.ONE,"CUSTOMER_REQUEST","operator"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(refundRows.count()).isZero();
    }
    @Test void ambiguousOperationOlderThanTwentyHoursIsNotSentAgain() {
        var p=seed();var ticket=states.claimStripeRecovery(p.getId(),true);states.recordStripePaid(ticket,INTENT,null,null);
        var r=refundStates.request(p.getId(),"manual-key-00001",AMOUNT,true,"INVENTORY_UNFULFILLABLE","payment-service",
                new StripePaymentGateway.Session(SESSION,101L,1000,"pln",INTENT,"paid","complete"),ticket.token());states.release(ticket);
        jdbc.update("update payment_refunds set submitted_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(21*3600)),r.getId());
        expectRefundList(false,null);refunds.process(r.getId());
        assertThat(refundStates.get(r.getId()).getStatus()).isEqualTo(PaymentRefund.Status.REVIEW_REQUIRED);
    }
    @Test void aLeaseRejectsASecondReplicaAndCustomerCannotUseOperatorRefundApi() {
        var p=seed();var first=states.claimStripeRecovery(p.getId(),true);
        assertThatThrownBy(() -> states.claimStripeRecovery(p.getId(),true)).isInstanceOf(PaymentInProgressException.class);states.release(first);
        var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,new Actor("CUSTOMER",null,17L,"owner@example.test"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var controller=new PaymentRefundController(refunds,refundStates);
        assertThatThrownBy(() -> controller.request(p.getId(),new PaymentRefundController.Request(AMOUNT,"operator-key-0001","CUSTOMER_REQUEST")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        assertThat(refundRows.count()).isZero();
    }
    @Test void unsupportedProviderMethodIsRejectedBeforeAnOrderCallOrPaymentInsert() {
        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(101L,"BLIK",false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
        assertThat(payments.count()).isZero();verifyNoInteractions(orders,inventory,runner);
    }
}
