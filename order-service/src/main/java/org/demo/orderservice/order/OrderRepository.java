package org.demo.orderservice.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

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

    @Query("select i.productId, sum(i.quantity) from OrderItem i join i.order o "
            + "where o.status = org.demo.orderservice.order.OrderStatus.PAID "
            + "group by i.productId order by sum(i.quantity) desc")
    java.util.List<Object[]> findBestSellingProductIds(Pageable pageable);
}
