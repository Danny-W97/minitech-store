package org.demo.orderservice.order;

import org.demo.orderservice.product.ProductClient;
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
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final PromotionClient promotionClient;
    private final OrderSagaService orderSagaService;

    public OrderService(
            OrderRepository orderRepository,
            ProductClient productClient,
            PromotionClient promotionClient,
            OrderSagaService orderSagaService
    ) {
        this.orderRepository = orderRepository;
        this.productClient = productClient;
        this.promotionClient = promotionClient;
        this.orderSagaService = orderSagaService;
    }

    public OrderResponse create(CreateOrderRequest request) {
        return create(request, null);
    }

    public OrderResponse create(CreateOrderRequest request, String requestedIdempotencyKey) {
        String idempotencyKey = requestedIdempotencyKey == null || requestedIdempotencyKey.isBlank()
                ? "generated-" + UUID.randomUUID()
                : requestedIdempotencyKey.trim();
        Order existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existingOrder != null) {
            log.info("Returning existing order {} for idempotency key", existingOrder.getId());
            return toResponse(existingOrder);
        }
        List<CreateOrderItemRequest> requestedItems = normalizeItems(request);
        log.info("Creating one order with {} product line(s)", requestedItems.size());
        List<ResolvedOrderItem> resolvedItems = requestedItems.stream()
                .map(this::resolveItem)
                .toList();

        BigDecimal totalPrice = resolvedItems.stream()
                .map(ResolvedOrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deliveryCost = request.deliveryCost() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : request.deliveryCost().setScale(2, RoundingMode.HALF_UP);
        if (deliveryCost.signum() < 0) {
            throw new IllegalArgumentException("Delivery cost cannot be negative");
        }
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
                request.deliveryMethod(),
                deliveryCost,
                idempotencyKey,
                coupon == null ? null : coupon.code(),
                discountAmount,
                request.fulfillmentType(),
                request.deliveryDate(),
                request.deliverySlot(),
                request.pickupLocation()
        );
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
        order = orderRepository.save(order);
        orderSagaService.start(order.getId());
        orderSagaService.resume(order.getId());
        return get(order.getId());
    }

    private List<CreateOrderItemRequest> normalizeItems(CreateOrderRequest request) {
        Map<Long, Integer> quantitiesByProduct = new LinkedHashMap<>();
        if (request.items() != null) {
            request.items().forEach(item -> quantitiesByProduct.merge(
                    item.productId(), item.quantity(), Integer::sum));
        }
        if (quantitiesByProduct.isEmpty() && request.productId() != null && request.quantity() != null) {
            quantitiesByProduct.put(request.productId(), request.quantity());
        }
        if (quantitiesByProduct.isEmpty()) {
            throw new IllegalArgumentException("Order must contain at least one product");
        }
        return quantitiesByProduct.entrySet().stream()
                .map(entry -> new CreateOrderItemRequest(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ResolvedOrderItem resolveItem(CreateOrderItemRequest requestedItem) {
        ProductResponse product = productClient.getProduct(requestedItem.productId());
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
                .map(this::toResponse)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAll() {
        return orderRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getForCustomer(Long customerId) {
        return orderRepository.findAllByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(this::toResponse)
                .toList();
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
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));

        if (!isAllowedTransition(order.getStatus(), requestedStatus)) {
            throw new InvalidOrderStatusTransitionException(order.getStatus(), requestedStatus);
        }

        if (requestedStatus == OrderStatus.CANCELLED) {
            orderSagaService.compensate(order.getId());
            return get(id);
        }
        order.changeStatus(requestedStatus);
        OrderResponse response = toResponse(orderRepository.save(order));
        if (requestedStatus == OrderStatus.PAID) {
            orderSagaService.markPaymentConfirmed(order.getId());
        }
        return response;
    }

    private boolean isAllowedTransition(OrderStatus current, OrderStatus requested) {
        if (current == requested) {
            return true;
        }
        return switch (current) {
            case CREATED -> requested == OrderStatus.CONFIRMED || requested == OrderStatus.CANCELLED;
            case CONFIRMED -> requested == OrderStatus.PAID || requested == OrderStatus.CANCELLED;
            case PAID -> requested == OrderStatus.SHIPPED || requested == OrderStatus.CANCELLED;
            case SHIPPED -> requested == OrderStatus.COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    private OrderResponse toResponse(Order order) {
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
                        order.getDeliveryCountry()
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
                order.isInvoiceRequested()
        );
    }
}
