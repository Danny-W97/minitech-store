package org.demo.platformsecurity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AccessControlTest {
    @Test void financePreparationConsentAndCapabilitiesHaveSeparateServiceAuthorities(){
        var root=new Actor("SERVICE","customer-service",null,null);var order=new Actor("SERVICE","order-service",null,null);var payment=new Actor("SERVICE","payment-service",null,null);
        assertTrue(RoutePolicy.allowed("POST","/internal/privacy/finance-prepare/17",order));
        for(String path:List.of("/internal/privacy/finance-consent/17","/internal/privacy/finance-progress/17","/internal/privacy/finance-account/17")){assertTrue(RoutePolicy.allowed("POST",path,root));assertFalse(RoutePolicy.allowed("POST",path,order));assertFalse(RoutePolicy.allowed("GET",path,root));}
        assertTrue(RoutePolicy.allowed("POST","/internal/events/payment-identity-capability",payment));assertFalse(RoutePolicy.allowed("POST","/internal/events/payment-identity-capability",root));
        for(String path:List.of("/internal/privacy/finance-prepare/17","/internal/privacy/finance-consent/17","/internal/privacy/finance-events/17/1","/internal/events/payment-identity-capability"))for(var actor:List.of(CUSTOMER,OPERATOR,Actor.ANONYMOUS))assertFalse(RoutePolicy.allowed("POST",path,actor));
        assertTrue(RoutePolicy.allowed("POST","/internal/privacy/finance-events/17/1",new Actor("SERVICE","billing-service",null,null)));assertFalse(RoutePolicy.allowed("POST","/internal/privacy/finance-events/17/1",order));
    }

    @Test void commerceCleanupAndCanonicalSourceAreStrictPrivatePostOnly(){
        var coordinator=new Actor("SERVICE","customer-service",null,null);
        String cleanup="/internal/privacy/commerce-cleanup/17",source="/internal/privacy/commerce-orders/17";
        assertTrue(RoutePolicy.allowed("POST",cleanup,coordinator));
        for(var actor:List.of(CUSTOMER,OPERATOR,Actor.ANONYMOUS,new Actor("SERVICE","chat-service",null,null))){assertFalse(RoutePolicy.allowed("POST",cleanup,actor));assertFalse(RoutePolicy.allowed("POST",source,actor));}
        for(String service:List.of("customer-service","inventory-service","promotion-service","delivery-service","returns-service","payment-service","billing-service","notification-service")){var actor=new Actor("SERVICE",service,null,null);assertTrue(RoutePolicy.allowed("POST",source,actor));for(String method:List.of("GET","PUT","DELETE","HEAD")){assertFalse(RoutePolicy.allowed(method,source,actor));assertFalse(RoutePolicy.allowed(method,cleanup,actor));}}
    }

    @Test void chatPrivacyIntentIsCapabilityOnlyAndDoesNotAllowErasureMethods(){
        String path="/chat/conversations/17/privacy-request";
        for(var actor:List.of(Actor.ANONYMOUS,CUSTOMER))for(var method:List.of("GET","POST"))assertTrue(RoutePolicy.allowed(method,path,actor));
        for(var actor:List.of(OPERATOR,new Actor("SERVICE","customer-service",null,null),new Actor("CUSTOMER",null,null,"x")))for(var method:List.of("GET","POST"))assertFalse(RoutePolicy.allowed(method,path,actor));
        for(var method:List.of("PUT","PATCH","DELETE","HEAD"))for(var actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR))assertFalse(RoutePolicy.allowed(method,path,actor));
    }
    @Test void onlyCoordinatorCanCallActivatedAccountCleanupAndNoRoleCanUseOtherMethods(){
        var coordinator=new Actor("SERVICE","customer-service",null,null);String path="/internal/privacy/account-cleanup/17";
        assertTrue(RoutePolicy.allowed("POST",path,coordinator));
        for(var actor:List.of(CUSTOMER,OPERATOR,Actor.ANONYMOUS,new Actor("SERVICE","review-service",null,null)))assertFalse(RoutePolicy.allowed("POST",path,actor));
        for(var method:List.of("GET","PUT","DELETE","PATCH","HEAD"))assertFalse(RoutePolicy.allowed(method,path,coordinator));
    }

    @Test void onlyCustomerServiceMayInstallPrivacyFencesAndNoRoleMayReadOrUndoThem(){
        var coordinator=new Actor("SERVICE","customer-service",null,null);
        assertTrue(RoutePolicy.allowed("POST","/internal/privacy/fences/17",coordinator));
        for(var actor:List.of(CUSTOMER,OPERATOR,Actor.ANONYMOUS,new Actor("SERVICE","payment-service",null,null)))assertFalse(RoutePolicy.allowed("POST","/internal/privacy/fences/17",actor));
        for(String method:List.of("GET","PUT","PATCH","DELETE","HEAD"))assertFalse(RoutePolicy.allowed(method,"/internal/privacy/fences/17",coordinator));
    }
    @Test void exportCoordinatorServiceCanOnlyProveOrderOwnershipWithoutReadingCustomerExports() {
        var coordinator=new Actor("SERVICE","customer-service",null,null);
        assertTrue(RoutePolicy.allowed("GET","/internal/orders/123/access",coordinator));
        assertFalse(RoutePolicy.allowed("POST","/internal/orders/123/access",coordinator));
        for(var path:List.of("/orders/me/data-export","/payments/me/data-export/orders/123","/invoices/me/data-export/orders/123","/orders/123"))
            assertFalse(RoutePolicy.allowed("GET",path,coordinator));
    }
    @Test void conversationExportsAllowCapabilityCallersButNeverOperatorOrServiceBypass() {
        for(var path:List.of("/chat/conversations/123/data-export","/chat/conversations/123/data-export/entries")) {
            assertTrue(RoutePolicy.allowed("GET",path,Actor.ANONYMOUS));assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
            for(var actor:List.of(OPERATOR,new Actor("SERVICE","chat-service",17L,"x"),new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
                assertFalse(RoutePolicy.allowed("GET",path,actor));
            for(var actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR,new Actor("SERVICE","chat-service",null,null)))
                for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD"))assertFalse(RoutePolicy.allowed(method,path,actor));
        }
        assertFalse(RoutePolicy.allowed("GET","/chat/messages",CUSTOMER));
        assertTrue(RoutePolicy.allowed("POST","/chat/messages/1/reply",OPERATOR));
    }
    @Test void invoiceExportsIncludingPdfsAreCustomerOnlyForEveryMethod() {
        for(var suffix:List.of("","/items","/corrections","/pdf","/corrections/7/pdf")) {
            String path="/invoices/me/data-export/orders/123"+suffix;
            assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
            for(var actor:List.of(Actor.ANONYMOUS,OPERATOR,new Actor("SERVICE","billing-service",17L,"x"),
                    new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x"))) assertFalse(RoutePolicy.allowed("GET",path,actor));
            for(var actor:List.of(CUSTOMER,OPERATOR,new Actor("SERVICE","billing-service",null,null)))
                for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD")) assertFalse(RoutePolicy.allowed(method,path,actor));
        }
        assertFalse(RoutePolicy.allowed("GET","/invoices/export/zip",CUSTOMER));
    }
    @Test void paymentExportIsCustomerOnlyWithoutOpeningOperatorRefundRoutes() {
        String path="/payments/me/data-export/orders/123";
        assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
        for(var actor:List.of(Actor.ANONYMOUS,OPERATOR,new Actor("SERVICE","payment-service",17L,"one@example.com"),
                new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
            assertFalse(RoutePolicy.allowed("GET",path,actor));
        for(var actor:List.of(CUSTOMER,OPERATOR,new Actor("SERVICE","payment-service",null,null)))
            for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD")) assertFalse(RoutePolicy.allowed(method,path,actor));
        assertFalse(RoutePolicy.allowed("GET","/payments/123/refunds",CUSTOMER));
        assertTrue(RoutePolicy.allowed("GET","/payments/123/refunds",OPERATOR));
    }
    @Test void returnExportsAreCustomerOnlyIncludingTheSafeStatusTimeline() {
        for(var path:List.of("/returns/me/data-export","/returns/me/data-export/123/items","/returns/me/data-export/123/events")) {
            assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
            for(var actor:List.of(Actor.ANONYMOUS,OPERATOR,new Actor("SERVICE","returns-service",17L,"one@example.com"),
                    new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
                assertFalse(RoutePolicy.allowed("GET",path,actor));
            for(var actor:List.of(CUSTOMER,OPERATOR,new Actor("SERVICE","returns-service",null,null)))
                for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD")) assertFalse(RoutePolicy.allowed(method,path,actor));
        }
        assertFalse(RoutePolicy.allowed("GET","/returns/123/history",CUSTOMER));
        assertTrue(RoutePolicy.allowed("GET","/returns/123/history",OPERATOR));
    }
    @Test void shipmentExportIsCustomerOnlyAndDoesNotGrantWritesOrNewServicePrivileges() {
        String path="/deliveries/me/data-export/orders/123";
        assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
        for(var actor:List.of(Actor.ANONYMOUS,OPERATOR,new Actor("SERVICE","delivery-service",17L,"one@example.com"),
                new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
            assertFalse(RoutePolicy.allowed("GET",path,actor));
        for(var actor:List.of(CUSTOMER,OPERATOR,new Actor("SERVICE","delivery-service",null,null)))
            for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD")) assertFalse(RoutePolicy.allowed(method,path,actor));
    }
    @Test void orderExportAndItsItemPagesAreCustomerOnlyEvenForOperatorAndServices() {
        for(var path:List.of("/orders/me/data-export","/orders/me/data-export/1/items")) {
            assertTrue(RoutePolicy.allowed("GET",path,CUSTOMER));
            for(var actor:List.of(Actor.ANONYMOUS,OPERATOR,new Actor("SERVICE","payment-service",null,null),
                    new Actor("CUSTOMER",null,null,"owner@example.test"))) assertFalse(RoutePolicy.allowed("GET",path,actor));
            for(var actor:List.of(CUSTOMER,OPERATOR,new Actor("SERVICE","order-service",null,null)))
                for(var method:List.of("POST","PUT","PATCH","DELETE","HEAD")) assertFalse(RoutePolicy.allowed(method,path,actor));
        }
    }
    @Test void recommendationLogExportIsOwnerOnlyAndDoesNotGrantWrites() {
        String path = "/recommendations/me/data-export";
        assertTrue(RoutePolicy.allowed("GET", path, CUSTOMER));
        for (var actor : List.of(Actor.ANONYMOUS, OPERATOR, new Actor("SERVICE", "recommendation-service", null, null),
                new Actor("CUSTOMER", null, null, "owner@example.test"), new Actor("CUSTOMER", null, 0L, "owner@example.test")))
            assertFalse(RoutePolicy.allowed("GET", path, actor));
        for (var actor : List.of(Actor.ANONYMOUS, CUSTOMER, OPERATOR, new Actor("SERVICE", "recommendation-service", null, null)))
            for (var method : List.of("POST", "PUT", "DELETE", "PATCH", "HEAD"))
                assertFalse(RoutePolicy.allowed(method, path, actor));
    }
    @Test void reviewDataExportIsOwnerOnlyForEveryMethodIncludingOperatorAndServiceCalls() {
        String path = "/reviews/me/data-export";
        assertTrue(RoutePolicy.allowed("GET", path, CUSTOMER));
        for (var actor : List.of(Actor.ANONYMOUS, OPERATOR,
                new Actor("SERVICE", "review-service", null, null),
                new Actor("CUSTOMER", null, null, "owner@example.test"),
                new Actor("CUSTOMER", null, -1L, "owner@example.test"))) {
            assertFalse(RoutePolicy.allowed("GET", path, actor));
        }
        for (var actor : List.of(Actor.ANONYMOUS, CUSTOMER, OPERATOR,
                new Actor("SERVICE", "review-service", null, null))) {
            for (var method : List.of("POST", "PUT", "DELETE", "PATCH", "HEAD"))
                assertFalse(RoutePolicy.allowed(method, path, actor));
        }
    }
    @Test void wishlistDataExportIsAvailableOnlyToAuthenticatedCustomersForGet() {
        String path = "/wishlists/me/data-export";
        assertTrue(RoutePolicy.allowed("GET", path, CUSTOMER));
        for (var actor : List.of(Actor.ANONYMOUS, OPERATOR,
                new Actor("SERVICE", "wishlist-service", null, null),
                new Actor("CUSTOMER", null, null, "x@example.test"),
                new Actor("CUSTOMER", null, 0L, "x@example.test"))) {
            assertFalse(RoutePolicy.allowed("GET", path, actor));
        }
        for (var actor : List.of(Actor.ANONYMOUS, CUSTOMER, OPERATOR,
                new Actor("SERVICE", "wishlist-service", null, null))) {
            for (var method : List.of("POST", "PUT", "DELETE", "PATCH"))
                assertFalse(RoutePolicy.allowed(method, path, actor));
        }
    }
    @Test void chatHistoryNeedsObjectAccessWhileSupportAndReplayStayOperatorOnly() {
        for(var actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR))
            for(var method:List.of("GET","POST")) assertTrue(RoutePolicy.allowed(method,"/chat/messages/1/entries",actor));
        for(var path:List.of("/chat/messages/1/reply","/chat/messages/1/entries/2/retry-notification")) {
            assertTrue(RoutePolicy.allowed("POST",path,OPERATOR));
            for(var actor:List.of(Actor.ANONYMOUS,CUSTOMER,new Actor("SERVICE","chat-service",null,null)))
                assertFalse(RoutePolicy.allowed("POST",path,actor));
        }
        assertFalse(RoutePolicy.allowed("GET","/chat/messages/1/entries",new Actor("SERVICE","chat-service",null,null)));
    }
    @Test void couponReservationWritersAndReadersHaveSeparateServicePermissions() {
        var order=new Actor("SERVICE","order-service",null,null);var payment=new Actor("SERVICE","payment-service",null,null);
        for(String operation:List.of("reserve","release")) {
            var route="/internal/promotions/orders/1/coupon/"+operation;
            assertTrue(RoutePolicy.allowed("POST",route,order));assertFalse(RoutePolicy.allowed("POST",route,payment));
            assertFalse(RoutePolicy.allowed("POST",route,new Actor("OPERATOR",null,null,null)));
        }
        assertTrue(RoutePolicy.allowed("GET","/internal/promotions/orders/1/coupon",payment));
        assertFalse(RoutePolicy.allowed("GET","/internal/promotions/orders/1/coupon",new Actor("CUSTOMER",null,17L,"owner@example.test")));
    }
    @Test void onlyDeliveryServiceCanSubmitShipmentProgressAndItCannotSetPaymentStatus() {
        var delivery=new Actor("SERVICE","delivery-service",null,null);
        assertTrue(RoutePolicy.allowed("POST","/internal/orders/1/shipment-progress",delivery));
        assertFalse(RoutePolicy.allowed("PATCH","/orders/1/status",delivery));
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("CUSTOMER",null,17L,"one@example.com"),
                new Actor("SERVICE","payment-service",null,null),new Actor("SERVICE","review-service",null,null)))
            assertFalse(RoutePolicy.allowed("POST","/internal/orders/1/shipment-progress",actor));
    }
    private static final Actor CUSTOMER = new Actor("CUSTOMER", null, 17L, "one@example.com");
    private static final Actor OPERATOR = new Actor("OPERATOR", null, null, null);
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @TestFactory Stream<DynamicTest> privilegedRoutesRejectAnonymousAndCustomers() {
        return List.of("GET /orders", "GET /orders/sagas", "POST /orders/1/reconcile", "GET /payments", "POST /payments/1/collect", "GET /invoices", "GET /returns", "GET /chat/messages",
                "POST /products", "PATCH /products/1", "POST /inventory/items", "PATCH /inventory/items/1",
                "POST /inventory/items/1/receipts", "GET /inventory/operations", "GET /inventory/operations/00000000-0000-4000-8000-000000000001",
                "POST /inventory/reservations", "POST /inventory/reservations/1/release", "POST /inventory/reservations/1/confirm",
                "GET /inventory/reservations/1", "PATCH /orders/1/status", "GET /orders/1/saga",
                "POST /invoices", "POST /invoices/1/corrections", "GET /invoices/corrections", "GET /invoices/export/zip",
                "POST /promotions/evaluate", "GET /promotions/codes", "POST /promotions/codes", "POST /promotions/codes/redeem",
                "GET /promotions/codes/reservations", "GET /promotions/codes/WELCOME10/capacity", "GET /orders/1/coupon", "POST /orders/1/coupon/reconcile",
                "GET /reviews/moderation/pending", "PATCH /reviews/1/moderation", "PATCH /returns/1/status",
                "POST /deliveries/orders/1/tracking", "PATCH /deliveries/orders/1/tracking",
                "GET /deliveries/orders/1/synchronization", "POST /deliveries/orders/1/synchronization/reconcile",
                "POST /chat/messages/1/reply", "GET /notifications", "POST /notifications", "GET /notifications/1",
                "GET /notifications/source/1", "GET /notifications/dead-letter", "POST /notifications/dead-letter/1/replay")
                .stream().map(route -> DynamicTest.dynamicTest(route, () -> {
                    String[] parts = route.split(" ");
                    assertFalse(RoutePolicy.allowed(parts[0], parts[1], Actor.ANONYMOUS));
                    assertFalse(RoutePolicy.allowed(parts[0], parts[1], CUSTOMER));
                    assertTrue(RoutePolicy.allowed(parts[0], parts[1], OPERATOR));
                }));
    }
    @Test void servicesHaveDifferentPrivileges() {
        Actor payment = new Actor("SERVICE", "payment-service", null, null);
        Actor recommendation = new Actor("SERVICE", "recommendation-service", null, null);
        assertTrue(RoutePolicy.allowed("POST", "/invoices", payment));
        assertFalse(RoutePolicy.allowed("POST", "/invoices", recommendation));
        assertFalse(RoutePolicy.allowed("GET", "/orders/customer/17", recommendation));
        assertTrue(RoutePolicy.allowed("GET", "/internal/orders/customer/17/purchased-products", recommendation));
        assertFalse(RoutePolicy.allowed("GET", "/orders", recommendation));
        assertFalse(RoutePolicy.allowed("POST", "/chat/messages/1/reply", payment));
        assertFalse(RoutePolicy.allowed("POST", "/orders", payment));
        assertFalse(RoutePolicy.allowed("GET", "/internal/orders/1/access", OPERATOR));
        assertFalse(RoutePolicy.allowed("POST", "/new-admin-endpoint", CUSTOMER));
    }
    @Test void onlyPaymentServiceCanAcceptAndSettleDeferredFulfillment() {
        for(String operation:List.of("accept","collect")) {
            String path="/internal/orders/1/deferred/"+operation;
            assertTrue(RoutePolicy.allowed("POST",path,new Actor("SERVICE","payment-service",null,null)));
            for(Actor actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR,new Actor("SERVICE","delivery-service",null,null)))
                assertFalse(RoutePolicy.allowed("POST",path,actor));
        }
    }
    @Test void publicInventoryBatchDoesNotGrantWriteAccess() {
        assertTrue(RoutePolicy.allowed("GET", "/inventory/items/batch", Actor.ANONYMOUS));
        assertFalse(RoutePolicy.allowed("POST", "/inventory/items/batch", Actor.ANONYMOUS));
        assertFalse(RoutePolicy.allowed("PATCH", "/inventory/items/1", CUSTOMER));
    }
    @Test void stripeRefundsAndReconciliationAreOperatorOnlyAndRefundHoldsArePrivate() {
        for(String route:List.of("POST /payments/1/refunds","GET /payments/1/refunds","GET /payments/refunds/1",
                "POST /payments/refunds/1/reconcile","POST /payments/1/reconcile","GET /payments/1/stripe-status")) {
            var parts=route.split(" ");assertTrue(RoutePolicy.allowed(parts[0],parts[1],OPERATOR));
            for(Actor actor:List.of(Actor.ANONYMOUS,CUSTOMER,new Actor("SERVICE","order-service",null,null),new Actor("SERVICE","payment-service",null,null)))
                assertFalse(RoutePolicy.allowed(parts[0],parts[1],actor));
        }
        for(String step:List.of("begin","complete")) {
            String path="/internal/orders/1/stripe-refund/"+step;
            assertTrue(RoutePolicy.allowed("POST",path,new Actor("SERVICE","payment-service",null,null)));
            for(Actor actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR,new Actor("SERVICE","order-service",null,null)))
                assertFalse(RoutePolicy.allowed("POST",path,actor));
        }
        assertTrue(RoutePolicy.allowed("POST","/payments/webhooks/stripe",Actor.ANONYMOUS));
        assertTrue(RoutePolicy.allowed("GET","/payments/config",Actor.ANONYMOUS));
        var returns=new Actor("SERVICE","returns-service",null,null);
        for(String route:List.of("POST /inventory/items/1/receipts","GET /invoices/order/501","GET /invoices/1/correction-balance","POST /invoices/1/corrections","GET /payments/order/501","POST /payments/1/refunds")) {
            var parts=route.split(" ");assertTrue(RoutePolicy.allowed(parts[0],parts[1],returns));
            for(Actor other:List.of(new Actor("SERVICE","review-service",null,null),new Actor("SERVICE","billing-service",null,null)))
                assertFalse(RoutePolicy.allowed(parts[0],parts[1],other));
        }
        for(String denied:List.of("PATCH /inventory/items/1","POST /invoices","GET /invoices/export/zip","GET /payments/1/refunds","POST /payments/refunds/1/reconcile","POST /payments/1/reconcile","GET /payments/1/stripe-status")) {
            var parts=denied.split(" ");assertFalse(RoutePolicy.allowed(parts[0],parts[1],returns));
        }
    }
    @Test void cancellationFenceIsPrivateToOrderService() {
        String path="/internal/payments/orders/1/cancel";
        assertTrue(RoutePolicy.allowed("POST",path,new Actor("SERVICE","order-service",null,null)));
        for(Actor actor:List.of(Actor.ANONYMOUS,CUSTOMER,OPERATOR,new Actor("SERVICE","payment-service",null,null),new Actor("SERVICE","delivery-service",null,null)))
            assertFalse(RoutePolicy.allowed("POST",path,actor));
        assertFalse(RoutePolicy.allowed("GET",path,new Actor("SERVICE","order-service",null,null)));
        assertFalse(RoutePolicy.allowed("GET","/inventory/reservations/order/1",CUSTOMER));
    }
    @Test void purchasedProductAggregateIsPrivateToRecommendationService() {
        for (Actor actor : List.of(Actor.ANONYMOUS, CUSTOMER, OPERATOR, new Actor("SERVICE", "payment-service", null, null)))
            assertFalse(RoutePolicy.allowed("GET", "/internal/orders/customer/17/purchased-products", actor));
        assertFalse(RoutePolicy.allowed("GET", "/invoices/corrections/summary", CUSTOMER));
    }
    @Test void onlyOrderServiceCanReadInternalProductBatch() {
        assertTrue(RoutePolicy.allowed("GET", "/internal/products/batch", new Actor("SERVICE", "order-service", null, null)));
        for (Actor actor : List.of(Actor.ANONYMOUS, CUSTOMER, OPERATOR,
                new Actor("SERVICE", "payment-service", null, null), new Actor("SERVICE", "product-service", null, null))) {
            assertFalse(RoutePolicy.allowed("GET", "/internal/products/batch", actor));
        }
    }
    @Test void customerCannotAccessAnotherCustomerEvenWithGuestCapability() {
        var request = identity(CUSTOMER);
        String token = "x".repeat(43);
        request.addHeader(Access.ORDER_TOKEN, token);
        assertDoesNotThrow(() -> Access.owner(17L, null, Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.owner(18L, Access.hash(token), Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.customer(18L));
        assertThrows(ResponseStatusException.class, () -> Access.submittedCustomer(18L));
        assertDoesNotThrow(() -> Access.submittedCustomer(17L));
    }
    @Test void guestCapabilityIsBoundToOneObjectAndCannotClaimAnAccount() {
        var request = identity(Actor.ANONYMOUS);
        String token = "a".repeat(43);
        request.addHeader(Access.ORDER_TOKEN, token);
        assertDoesNotThrow(() -> Access.owner(null, Access.hash(token), Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.owner(null, Access.hash("b".repeat(43)), Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.owner(17L, Access.hash(token), Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.submittedCustomer(17L));
        assertThrows(ResponseStatusException.class, () -> Access.owner(null, null, Access.ORDER_TOKEN));
        assertThrows(ResponseStatusException.class, () -> Access.capabilityHash(Access.CHAT_TOKEN));
    }
    static MockHttpServletRequest identity(Actor actor) {
        var request = new MockHttpServletRequest();
        request.setAttribute(Access.ACTOR, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
