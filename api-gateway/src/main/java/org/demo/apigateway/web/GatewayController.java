package org.demo.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.demo.apigateway.proxy.GatewayProxy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api", "/api/v1"})
@Tag(name = "Public API Gateway", description = "Versioned public entry point for the MiniTech Store")
public class GatewayController {

    private final GatewayProxy gatewayProxy;
    private final String productServiceBaseUrl;
    private final String orderServiceBaseUrl;
    private final String inventoryServiceBaseUrl;
    private final String paymentServiceBaseUrl;
    private final String notificationServiceBaseUrl;
    private final String customerServiceBaseUrl;
    private final String deliveryServiceBaseUrl;
    private final String promotionServiceBaseUrl;
    private final String wishlistServiceBaseUrl;
    private final String reviewServiceBaseUrl;
    private final String recommendationServiceBaseUrl;
    private final String chatServiceBaseUrl;
    private final String returnsServiceBaseUrl;
    private final String billingServiceBaseUrl;

    public GatewayController(
            GatewayProxy gatewayProxy,
            @Value("${services.product.base-url}") String productServiceBaseUrl,
            @Value("${services.order.base-url}") String orderServiceBaseUrl,
            @Value("${services.inventory.base-url}") String inventoryServiceBaseUrl,
            @Value("${services.payment.base-url}") String paymentServiceBaseUrl,
            @Value("${services.notification.base-url}") String notificationServiceBaseUrl
            , @Value("${services.customer.base-url:http://localhost:18087}") String customerServiceBaseUrl
            , @Value("${services.delivery.base-url:http://localhost:18088}") String deliveryServiceBaseUrl
            , @Value("${services.promotion.base-url:http://localhost:18089}") String promotionServiceBaseUrl
            , @Value("${services.wishlist.base-url:http://localhost:18090}") String wishlistServiceBaseUrl
            , @Value("${services.review.base-url:http://localhost:18091}") String reviewServiceBaseUrl
            , @Value("${services.recommendation.base-url:http://localhost:18092}") String recommendationServiceBaseUrl
            , @Value("${services.chat.base-url:http://localhost:18093}") String chatServiceBaseUrl
            , @Value("${services.returns.base-url:http://localhost:18094}") String returnsServiceBaseUrl
            , @Value("${services.billing.base-url:http://localhost:18095}") String billingServiceBaseUrl
    ) {
        this.gatewayProxy = gatewayProxy;
        this.productServiceBaseUrl = productServiceBaseUrl;
        this.orderServiceBaseUrl = orderServiceBaseUrl;
        this.inventoryServiceBaseUrl = inventoryServiceBaseUrl;
        this.paymentServiceBaseUrl = paymentServiceBaseUrl;
        this.notificationServiceBaseUrl = notificationServiceBaseUrl;
        this.customerServiceBaseUrl = customerServiceBaseUrl;
        this.deliveryServiceBaseUrl = deliveryServiceBaseUrl;
        this.promotionServiceBaseUrl = promotionServiceBaseUrl;
        this.wishlistServiceBaseUrl = wishlistServiceBaseUrl;
        this.reviewServiceBaseUrl = reviewServiceBaseUrl;
        this.recommendationServiceBaseUrl = recommendationServiceBaseUrl;
        this.chatServiceBaseUrl = chatServiceBaseUrl;
        this.returnsServiceBaseUrl = returnsServiceBaseUrl;
        this.billingServiceBaseUrl = billingServiceBaseUrl;
    }

    @RequestMapping(value = {"/products", "/products/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Product catalog", description = "Forwards catalog requests to Product Service")
    public ResponseEntity<byte[]> products(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("product", productServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/orders", "/orders/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Orders", description = "Forwards order and checkout requests to Order Service")
    public ResponseEntity<byte[]> orders(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("order", orderServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/inventory", "/inventory/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Inventory", description = "Forwards stock and reservation requests to Inventory Service")
    public ResponseEntity<byte[]> inventory(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("inventory", inventoryServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/payments", "/payments/**"}, method = {
            RequestMethod.GET, RequestMethod.POST
    })
    @Operation(summary = "Payments", description = "Forwards payment requests to Payment Service")
    public ResponseEntity<byte[]> payments(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("payment", paymentServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/notifications", "/notifications/**"}, method = {
            RequestMethod.GET, RequestMethod.POST
    })
    @Operation(summary = "Notifications", description = "Forwards notification requests to Notification Service")
    public ResponseEntity<byte[]> notifications(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("notification", notificationServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/customers", "/customers/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT
    })
    @Operation(summary = "Customers", description = "Forwards registration, authentication and customer requests to Customer Service")
    public ResponseEntity<byte[]> customers(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("customer", customerServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/deliveries", "/deliveries/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Deliveries", description = "Forwards delivery methods and quote requests to Delivery Service")
    public ResponseEntity<byte[]> deliveries(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("delivery", deliveryServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/promotions", "/promotions/**"}, method = {
            RequestMethod.GET, RequestMethod.POST
    })
    @Operation(summary = "Promotions", description = "Forwards daily promotions and price history requests to Promotion Service")
    public ResponseEntity<byte[]> promotions(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("promotion", promotionServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/wishlists", "/wishlists/**"}, method = {
            RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE
    })
    @Operation(summary = "Wishlists", description = "Forwards saved-product requests to Wishlist Service")
    public ResponseEntity<byte[]> wishlists(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("wishlist", wishlistServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/reviews", "/reviews/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Reviews", description = "Forwards product reviews and moderation requests to Review Service")
    public ResponseEntity<byte[]> reviews(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("review", reviewServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/recommendations", "/recommendations/**"}, method = {
            RequestMethod.GET
    })
    @Operation(summary = "Recommendations", description = "Forwards catalog and customer recommendations to Recommendation Service")
    public ResponseEntity<byte[]> recommendations(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("recommendation", recommendationServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/chat", "/chat/**"}, method = {
            RequestMethod.GET, RequestMethod.POST
    })
    @Operation(summary = "Customer chat", description = "Forwards customer chat messages to Chat Service")
    public ResponseEntity<byte[]> chat(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("chat", chatServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/returns", "/returns/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH
    })
    @Operation(summary = "Returns and complaints", description = "Forwards return and complaint requests to Returns Service")
    public ResponseEntity<byte[]> returns(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("returns", returnsServiceBaseUrl, request, body);
    }

    @RequestMapping(value = {"/invoices", "/invoices/**"}, method = {
            RequestMethod.GET, RequestMethod.POST
    })
    @Operation(summary = "Invoices", description = "Forwards invoice requests to Billing Service")
    public ResponseEntity<byte[]> invoices(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body
    ) {
        return gatewayProxy.forward("billing", billingServiceBaseUrl, request, body);
    }
}
