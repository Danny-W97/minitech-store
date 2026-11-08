package org.demo.orderservice.order;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.OrderAccess;

import org.demo.orderservice.product.ProductClient;
import org.demo.orderservice.delivery.DeliveryClient;
import org.demo.orderservice.delivery.DeliveryQuoteResponse;
import org.demo.orderservice.delivery.InvalidDeliverySelectionException;
import org.demo.orderservice.product.ProductResponse;
import org.demo.orderservice.promotion.CouponValidationResponse;
import org.demo.orderservice.promotion.PromotionClient;
import org.demo.orderservice.web.CreateOrderRequest;
import org.demo.orderservice.web.CreateOrderItemRequest;
import org.demo.orderservice.web.OrderItemResponse;
import org.demo.orderservice.web.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;

@Service
public class OrderService {

    private static final BigDecimal GUEST_SUBTOTAL_LIMIT = new BigDecimal("1000.00");

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final PromotionClient promotionClient;
    private final OrderSagaService orderSagaService;
    private final DeliveryClient deliveryClient;
    private final OrderCreationService orderCreationService;
    @org.springframework.beans.factory.annotation.Autowired private DeferredFulfillmentService fulfillment;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            PromotionClient promotionClient,
            OrderSagaService orderSagaService,
            DeliveryClient deliveryClient,
            OrderCreationService orderCreationService
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.promotionClient = promotionClient;
        this.orderSagaService = orderSagaService;
        this.deliveryClient = deliveryClient;
        this.orderCreationService = orderCreationService;
    }

    public OrderResponse create(CreateOrderRequest request) {
        return create(request, null);
    }

    public OrderResponse create(CreateOrderRequest request, String requestedIdempotencyKey) {
        if (Access.request() != null) {
            Access.submittedCustomer(request.customerId());
            if (request.customerId() != null) Access.require(request.customerEmail().equalsIgnoreCase(Access.actor().email()));
            else Access.capabilityHash(Access.ORDER_TOKEN);
        }
        var command=OrderCommand.from(request,requestedIdempotencyKey);
        Order existingOrder = orderRepository.findByIdempotencyKey(command.key()).orElse(null);
        if (existingOrder != null) return replay(existingOrder,command);
        Order order;
        try { order=resolveAndPersist(request,command); }
        catch(RuntimeException failure) {
            // A concurrent winner may have committed while this request was quoting
            // or inserting. Re-read only after the creation transaction rolls back.
            var winner=orderRepository.findByIdempotencyKey(command.key()).orElse(null);
            if(winner==null) throw failure;
            return replay(winner,command);
        }
        return continueCheckout(order);
    }

    private OrderResponse replay(Order existing,OrderCommand command) {
        if(Access.request()!=null) Access.owner(existing.getCustomerId(),existing.getGuestAccessTokenHash(),Access.ORDER_TOKEN);
        command.requireSame(existing);
        return continueCheckout(existing);
    }

    private OrderResponse continueCheckout(Order order) {
        if(order.getStatus()==OrderStatus.CREATED) orderSagaService.resume(order.getId());
        var result=get(order.getId());
        if(result.status()==OrderStatus.CREATED) throw new OrderIdempotencyException("ORDER_IN_PROGRESS",order.getId());
        return result;
    }

    private Order resolveAndPersist(CreateOrderRequest request,OrderCommand command) {
        List<CreateOrderItemRequest> requestedItems = command.items();
        log.info("Creating one order with {} product line(s)", requestedItems.size());
        Map<Long, ProductResponse> products = productClient.getProducts(requestedItems.stream()
                .map(CreateOrderItemRequest::productId).toList());
        List<ResolvedOrderItem> resolvedItems = requestedItems.stream()
                .map(item -> resolveItem(item, products.get(item.productId())))
                .toList();

        BigDecimal totalPrice = resolvedItems.stream()
                .map(ResolvedOrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (request.customerId() == null && totalPrice.compareTo(GUEST_SUBTOTAL_LIMIT) > 0) {
            throw new AccountRequiredException();
        }
        int itemCount = requestedItems.stream().mapToInt(CreateOrderItemRequest::quantity)
                .reduce(0, Math::addExact);
        DeliveryQuoteResponse delivery = deliveryClient.quote(request.deliveryMethod(),
                request.deliveryAddress().postalCode(), itemCount, totalPrice, request.deliveryDate(), request.pickupLocation());
        if (request.fulfillmentType() != null && !request.fulfillmentType().isBlank()
                && !delivery.fulfillmentType().equalsIgnoreCase(request.fulfillmentType().trim())) {
            throw new InvalidDeliverySelectionException("Fulfillment type does not match the delivery method");
        }
        if (request.deliverySlot() != null && !request.deliverySlot().isBlank()) {
            throw new InvalidDeliverySelectionException("Delivery time slots are not supported");
        }
        BigDecimal deliveryCost = delivery.deliveryCost().setScale(2, RoundingMode.HALF_UP);
        CouponValidationResponse coupon = promotionClient.validateCoupon(request.promotionCode(), totalPrice);
        BigDecimal discountAmount = coupon == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : coupon.discountAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal orderTotal = totalPrice.add(deliveryCost).subtract(discountAmount)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        Order order = new Order(
                orderTotal,
                request.customerId(),
                request.customerEmail(),
                request.deliveryAddress(),
                delivery.methodCode(),
                deliveryCost,
                command.key(),
                coupon == null ? null : coupon.code(),
                discountAmount,
                delivery.fulfillmentType(),
                delivery.selectedDate(),
                null,
                delivery.pickupLocation()
        );
        order.bindCheckout(command);
        if (Access.request() != null && request.customerId() == null) order.setGuestAccessTokenHash(Access.capabilityHash(Access.ORDER_TOKEN));
        order.markInvoiceRequested(Boolean.TRUE.equals(request.invoiceRequested()));
        for (ResolvedOrderItem item : resolvedItems) {
            order.addItem(
                    item.product().id(),
                    item.product().sku(),
                    item.product().name(),
                    item.quantity(),
                    item.unitPrice(),
                    item.lineTotal()
            );
        }
        return orderCreationService.create(order);
    }

    @Transactional(readOnly = true)
    public void requireOwner(Long id) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        Access.owner(order.getCustomerId(), order.getGuestAccessTokenHash(), Access.ORDER_TOKEN);
    }

    @Transactional(readOnly = true)
    public OrderAccess.Ownership ownership(Long id, String capability) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        return new OrderAccess.Ownership(order.getCustomerId(), order.getCustomerEmail(),
                order.getCustomerId() == null && Access.matches(order.getGuestAccessTokenHash(), capability));
    }

    private ResolvedOrderItem resolveItem(CreateOrderItemRequest requestedItem, ProductResponse product) {
        if (!product.active()) {
            throw new ProductInactiveException(requestedItem.productId());
        }
        BigDecimal unitPrice = product.price().setScale(2, RoundingMode.HALF_UP);
        BigDecimal lineTotal = unitPrice
                .multiply(BigDecimal.valueOf(requestedItem.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        return new ResolvedOrderItem(product, requestedItem.quantity(), unitPrice, lineTotal);
    }

    private record ResolvedOrderItem(
            ProductResponse product,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return orderRepository.findById(id)
                .map(order -> toResponse(order,order.isLifecycleReviewRequired() || orderSagaService.requiresReconciliation(id)))
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAll() {
        return list(null, null, null, "", org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getForCustomer(Long customerId) {
        return list(customerId, null, null, "", org.demo.platformsecurity.ListWindow.FIRST).items();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public org.demo.platformsecurity.ListWindow.ListPage<OrderResponse> list(Long customerId, OrderStatus status,
            Long productId, String search, org.demo.platformsecurity.ListWindow window) {
        if (customerId != null && Access.request() != null) Access.customer(customerId);
        var ids = orderRepository.findPageIds(window.ceiling(), customerId, status, productId,
                org.demo.platformsecurity.ListWindow.search(search), window.fetch());
        var found = new java.util.HashMap<Long, Order>();
        if (!ids.isEmpty()) orderRepository.findPageWithItems(ids.stream().limit(window.size()).toList())
                .forEach(order -> found.put(order.getId(), order));
        return window.map(ids, Long::longValue, id -> toResponse(found.get(id)));
    }

    @Transactional(readOnly = true)
    public List<Long> purchasedProducts(Long customerId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100
                || productIds.stream().anyMatch(id -> id == null || id <= 0)) throw new IllegalArgumentException("Provide 1 to 100 positive product IDs");
        return orderRepository.findPurchasedIds(customerId, productIds.stream().distinct().toList());
    }

    @Transactional(readOnly = true)
    public List<org.demo.orderservice.web.BestSellerResponse> bestSellers(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return orderRepository.findBestSellingProductIds(PageRequest.of(0, safeLimit)).stream()
                .map(row -> new org.demo.orderservice.web.BestSellerResponse(
                        ((Number) row[0]).longValue(), ((Number) row[1]).longValue()))
                .toList();
    }

    public OrderResponse changeStatus(Long id, OrderStatus requestedStatus) {
        if(requestedStatus!=OrderStatus.CANCELLED) return fulfillment.transition(id,requestedStatus);
        orderSagaService.compensate(id);
        return get(id);
    }

    static OrderResponse toResponse(Order order) {
        return toResponse(order,order.isLifecycleReviewRequired());
    }
    private static OrderResponse toResponse(Order order,boolean lifecycleReviewRequired) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getSku(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getTotalPrice()
                ))
                .toList();
        return new OrderResponse(
                order.getId(),
                items,
                order.getTotalPrice(),
                order.getStatus(),
                order.getInventoryReservationId(),
                order.getCustomerId(),
                order.getCustomerEmail(),
                new org.demo.orderservice.web.DeliveryAddressResponse(
                        order.getDeliveryFirstName(),
                        order.getDeliveryLastName(),
                        order.getDeliveryStreet(),
                        order.getDeliveryPostalCode(),
                        order.getDeliveryCity(),
                        order.getDeliveryCountry(),
                        order.getDeliveryPhone()
                ),
                order.getDeliveryMethod(),
                order.getDeliveryCost(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getPromotionCode(),
                order.getDiscountAmount(),
                order.getFulfillmentType(),
                order.getDeliveryDate(),
                order.getDeliverySlot(),
                order.getPickupLocation(),
                order.isInvoiceRequested(),order.getPaymentState(),order.getDeferredPaymentMethod(),order.getDeferredPaymentId(),lifecycleReviewRequired,order.isCouponReservationVerified()
        );
    }
}
