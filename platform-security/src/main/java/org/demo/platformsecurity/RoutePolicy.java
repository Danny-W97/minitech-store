package org.demo.platformsecurity;

import java.util.Set;

/** Complete allow list; new endpoints remain private until deliberately classified. */
public final class RoutePolicy {
    private RoutePolicy() {}
    public static boolean allowed(String method, String path, Actor actor) {
        String route = method + " " + path;
        if(path.equals("/internal/privacy/commerce-retention/source-hold-restoration"))return method.equals("POST")&&actor.internal()&&"order-service".equals(actor.service());
        if(path.equals("/internal/privacy/commerce-retention/source-hold-inspection"))return method.equals("POST")&&actor.internal()&&"order-service".equals(actor.service());
        if(path.equals("/internal/privacy/commerce-retention/source-holds"))return method.equals("POST")&&actor.internal()&&"order-service".equals(actor.service());
        if(path.equals("/internal/privacy/commerce-retention/bind") || path.equals("/internal/privacy/commerce-retention/inventory-evidence") || path.equals("/internal/privacy/commerce-retention/peer-evidence") || path.equals("/internal/privacy/commerce-retention/peer-fence") || path.equals("/internal/privacy/commerce-retention/peer-guards"))return method.equals("POST")&&actor.internal()&&"order-service".equals(actor.service());
        if(path.equals("/internal/privacy/commerce-retention/invalidate"))return method.equals("POST")&&actor.internal()&&actor.service()!=null
                &&Set.of("returns-service","payment-service","inventory-service","promotion-service","delivery-service","billing-service","notification-service").contains(actor.service());
        if(path.startsWith("/customers/privacy-journal"))return actor.operator()&&(method.equals("GET")||method.equals("POST"));
        if(path.equals("/internal/events/payment-identity-capability"))return method.equals("POST")&&actor.internal()&&"payment-service".equals(actor.service());
        if(path.matches("/internal/privacy/finance-prepare/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&"order-service".equals(actor.service());
        if(path.matches("/internal/privacy/finance-(consent|progress|account)/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&"customer-service".equals(actor.service());
        if(path.matches("/internal/privacy/finance-events/[1-9]\\d*/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&java.util.Set.of("billing-service","notification-service").contains(actor.service());

        if(path.matches("/internal/privacy/fences/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&"customer-service".equals(actor.service());
        if(path.matches("/internal/privacy/account-cleanup/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&"customer-service".equals(actor.service());
        if(path.matches("/internal/privacy/commerce-cleanup/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&"customer-service".equals(actor.service());
        if(path.matches("/internal/privacy/commerce-orders/[1-9]\\d*"))return method.equals("POST")&&actor.internal()&&Set.of("customer-service","inventory-service","promotion-service","delivery-service","returns-service","payment-service","billing-service","notification-service").contains(actor.service());
        if(path.matches("/chat/conversations/[1-9]\\d*/privacy-request"))return Set.of("GET","POST").contains(method)&&("ANONYMOUS".equals(actor.role())||"CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
        if (path.matches("/customers/me/data-export/security/(methods|events|passkeys|recovery-codes)"))
            return method.equals("GET") && "CUSTOMER".equals(actor.role()) && actor.customerId()!=null && actor.customerId()>0;
        if (path.matches("/notifications/chat/data-export/[A-Z_]+/\\d+")) {
            return method.equals("GET") && ("ANONYMOUS".equals(actor.role())
                    || "CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        }
        if (path.matches("/notifications/me/data-export/orders/\\d+")) {
            return method.equals("GET") && "CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0;
        }
        if (route.matches("GET /actuator/health(/(liveness|readiness))?")) return true;
        // Preserve the existing public monitoring/documentation contract.
        if (route.matches("GET /actuator/(info|prometheus)") || route.matches("GET /v3/api-docs(/.*)?")
                || route.matches("GET /swagger-ui(\\.html|/.*)")) return true;
        // Conversation exports retain capability access; account roles never bypass that proof.
        if (path.matches("/chat/conversations/\\d+/data-export(/entries)?")) {
            return method.equals("GET") && ("ANONYMOUS".equals(actor.role())
                    || "CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        }
        // Account exports are strictly owner-only, including for operators and service tokens.
        if (Set.of("/wishlists/me/data-export", "/reviews/me/data-export", "/recommendations/me/data-export",
                "/orders/me/data-export", "/returns/me/data-export").contains(path)
                || path.matches("/returns/me/data-export/\\d+/(items|events)")
                || path.matches("/orders/me/data-export/\\d+/items")
                || path.matches("/deliveries/me/data-export/orders/\\d+")
                || path.matches("/payments/me/data-export/orders/\\d+")
                || path.matches("/inventory/me/data-export/orders/\\d+(/items)?")
                || path.matches("/promotions/me/data-export/orders/\\d+(/redemptions)?")
                || path.matches("/invoices/me/data-export/orders/\\d+(/(items|corrections|pdf)|/corrections/\\d+/pdf)?")) {
            return method.equals("GET") && "CUSTOMER".equals(actor.role())
                    && actor.customerId() != null && actor.customerId() > 0;
        }
        if (route.matches("GET /products(/\\d+)?")
                || route.matches("GET /inventory/items/\\d+")
                || route.equals("GET /inventory/items/batch")
                || route.equals("GET /orders/bestsellers")
                || route.equals("GET /deliveries/methods") || route.equals("GET /deliveries/pickup-locations") || route.equals("POST /deliveries/quote")
                || route.equals("GET /promotions/active") || route.matches("GET /promotions/history/\\d+(/lowest)?")
                || route.equals("POST /promotions/codes/validate")
                || route.matches("GET /reviews/products/\\d+(/summary)?")
                || route.matches("GET /recommendations/products/\\d+")) return true;
        if (actor.operator()) return !path.startsWith("/internal/");
        if (actor.internal()) {
            return switch (actor.service() == null ? "" : actor.service()) {
                case "product-service" -> route.equals("POST /promotions/evaluate");
                case "order-service" -> route.matches("POST /inventory/reservations(/\\d+/release)?")
                        || route.matches("POST /internal/promotions/orders/\\d+/coupon/(reserve|release)")
                        || route.matches("GET /internal/promotions/orders/\\d+/coupon")
                        || route.matches("POST /internal/payments/orders/\\d+/cancel")
                        || route.matches("GET /inventory/reservations/order/\\d+")
                        || route.equals("GET /internal/products/batch")
                        || route.matches("GET /inventory/reservations/\\d+");
                case "payment-service" -> route.matches("GET /orders/\\d+") || route.matches("PATCH /orders/\\d+/status")
                        || route.matches("GET /internal/promotions/orders/\\d+/coupon")
                        || route.matches("POST /internal/orders/\\d+/deferred/(accept|collect)")
                        || route.matches("POST /internal/orders/\\d+/stripe-refund/(begin|complete)")
                        || route.matches("GET /deliveries/orders/\\d+/tracking")
                        || route.matches("POST /inventory/reservations/\\d+/(confirm|release|checkout-extension|stripe-compensation)")
                        || route.equals("POST /promotions/codes/redeem") || route.equals("POST /invoices")
                        || route.matches("POST /deliveries/orders/\\d+/tracking") || route.matches("GET /internal/orders/\\d+/access");
                case "delivery-service" -> route.matches("GET /orders/\\d+") || route.matches("GET /internal/orders/\\d+/access")
                        || route.matches("POST /internal/orders/\\d+/shipment-progress");
                case "promotion-service" -> route.matches("GET /orders/\\d+")
                        || route.matches("GET /internal/orders/\\d+/access")
                        || route.matches("GET /internal/products/price-history/\\d+(/lowest)?");
                case "inventory-service" -> route.matches("GET /internal/orders/\\d+/access");
                case "review-service", "billing-service" -> route.matches("GET /orders/\\d+") || route.matches("GET /internal/orders/\\d+/access");
                case "returns-service" -> route.matches("GET /orders/\\d+") || route.matches("GET /internal/orders/\\d+/access") || route.matches("POST /inventory/items/\\d+/receipts") || route.matches("GET /invoices/order/\\d+") || route.matches("GET /invoices/\\d+/correction-balance") || route.matches("POST /invoices/\\d+/corrections") || route.matches("GET /payments/order/\\d+") || route.matches("POST /payments/\\d+/refunds");

                case "recommendation-service" -> route.matches("GET /internal/orders/customer/\\d+/purchased-products");
                case "notification-service" -> route.matches("GET /invoices/\\d+/pdf")
                        || route.matches("GET /internal/orders/\\d+/access")
                        || route.matches("GET /internal/chat/notification-sources/[A-Z_]+/\\d+/access");
                case "customer-service" -> route.equals("POST /notifications") || route.matches("GET /internal/orders/\\d+/access");
                case "chat-service" -> route.equals("POST /notifications");
                default -> false;
            };
        }
        // These routes additionally require a per-object ownership/capability check.
        if (route.equals("POST /orders") || route.matches("GET /orders/\\d+")
                || route.equals("POST /payments") || route.equals("GET /payments/config")
                || route.matches("POST /payments/webhooks/stripe") || route.matches("GET /payments/(order/)?\\d+")
                || route.matches("GET /deliveries/orders/\\d+/tracking")
                || route.matches("GET /invoices/(order/)?\\d+(/pdf|/corrections)?")
                || route.matches("GET /invoices/corrections/\\d+/pdf")
                || route.equals("POST /chat/messages") || route.matches("GET /chat/messages/\\d+")
                || route.matches("(GET|POST) /chat/messages/\\d+/entries")) return true;
        if (actor.customerId() != null) {
            return route.matches("GET /(orders|invoices|returns|reviews|recommendations)/customer/\\d+")
                    || route.matches("(GET|PUT|DELETE) /wishlists/customer/\\d+(/products/\\d+)?")
                    || Set.of("POST /reviews", "POST /returns").contains(route) || route.matches("GET /returns/\\d+");
        }
        return false;
    }
}
