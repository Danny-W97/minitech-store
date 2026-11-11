package org.demo.orderservice.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("select o.id from Order o where o.couponNextAttemptAt<=:now and o.couponExpiresAt<=:now and o.status in (org.demo.orderservice.order.OrderStatus.CREATED,org.demo.orderservice.order.OrderStatus.CONFIRMED) and o.deferredPaymentMethod is null and o.refundPaymentId is null and o.lifecycleReviewRequired=false order by o.couponNextAttemptAt,o.id")
    java.util.List<Long> expiredCouponOrders(@org.springframework.data.repository.query.Param("now") java.time.Instant now,Pageable page);
    String PURCHASED="o.status in (org.demo.orderservice.order.OrderStatus.PAID,org.demo.orderservice.order.OrderStatus.SHIPPED,org.demo.orderservice.order.OrderStatus.COMPLETED) "
            +"and o.lifecycleReviewRequired=false and o.refundPaymentId is null and (o.deferredPaymentMethod is null or o.collectionReference is not null) ";
    @Query("select o.id from Order o where o.id > :after order by o.id")
    java.util.List<Long> scanIdsAfter(@org.springframework.data.repository.query.Param("after") long after, Pageable page);
    @Query("select o.id from Order o where o.id in :ids and not exists (select s.id from OrderSaga s where s.orderId=o.id)")
    java.util.List<Long> missingSagaIds(@org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id=:id")
    java.util.Optional<Order> lockId(Long id);

    @Override
    @EntityGraph(attributePaths = "items")
    java.util.Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = "items")
    java.util.Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Override
    @EntityGraph(attributePaths = "items")
    java.util.List<Order> findAll();

    @EntityGraph(attributePaths = "items")
    java.util.List<Order> findAllByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @Query("select o.id from Order o where o.id <= :ceiling and (:customerId is null or o.customerId = :customerId) "
            + "and (:status is null or o.status = :status) "
            + "and (:productId is null or exists (select i.id from OrderItem i where i.order = o and i.productId = :productId)) "
            + "and (lower(o.customerEmail) like :search or str(o.id) like :search) order by o.id desc")
    java.util.List<Long> findPageIds(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("status") OrderStatus status,
            @org.springframework.data.repository.query.Param("productId") Long productId,
            @org.springframework.data.repository.query.Param("search") String search, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.id in :ids")
    java.util.List<Order> findPageWithItems(@org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids);

    @Query("select distinct i.productId from OrderItem i join i.order o where o.customerId = :customerId "
            + "and " + PURCHASED + "and i.productId in :productIds")
    java.util.List<Long> findPurchasedIds(@org.springframework.data.repository.query.Param("customerId") Long customerId,
            @org.springframework.data.repository.query.Param("productIds") java.util.List<Long> productIds);

    @Query("select i.productId, sum(i.quantity) from OrderItem i join i.order o "
            + "where " + PURCHASED
            + "group by i.productId order by sum(i.quantity) desc")
    java.util.List<Object[]> findBestSellingProductIds(Pageable pageable);
}
