package org.demo.orderservice.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCreationService {
    private final OrderRepository orderRepository;
    private final OrderSagaRepository sagaRepository;
    @org.springframework.beans.factory.annotation.Value("${order.coupons.hold-ttl-seconds:2400}") private long couponTtl=2400;

    public OrderCreationService(OrderRepository orderRepository, OrderSagaRepository sagaRepository) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
    }

    // This boundary commits both records before any remote inventory operation.
    @Transactional
    public Order create(Order order) {
        order.armCouponExpiry(java.time.Instant.now().plusSeconds(couponTtl));
        Order saved = orderRepository.save(order);
        sagaRepository.save(new OrderSaga(saved.getId()));
        return saved;
    }
}
