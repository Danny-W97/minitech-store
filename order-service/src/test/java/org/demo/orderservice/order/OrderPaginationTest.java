package org.demo.orderservice.order;

import jakarta.persistence.EntityManager;
import org.demo.platformsecurity.ListWindow;
import org.demo.orderservice.web.DeliveryAddressRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true")
class OrderPaginationTest {
    @Autowired OrderRepository repository;
    @Autowired EntityManager entityManager;
    @Test void pagesIdsBeforeFetchingAllItemsForTheSelectedOrders() {
        for (int i = 0; i < 7; i++) {
            var order = new Order(BigDecimal.TEN, 29L, "page@example.com", new DeliveryAddressRequest("Jan", "Nowak", "Test 1", "00-001", "Warszawa", "Polska", null));
            order.changeStatus(OrderStatus.PAID);
            for (long product = 1; product <= 3; product++) order.addItem(product, "SKU"+product, "Product", 1, BigDecimal.TEN, BigDecimal.TEN);
            repository.save(order);
        }
        entityManager.flush(); entityManager.clear();
        var window = new ListWindow(0, 2);
        var ids = repository.findPageIds(window.ceiling(), 29L, OrderStatus.PAID, 2L, "%", window.fetch());
        assertThat(ids).hasSize(3);
        var orders = repository.findPageWithItems(ids.subList(0, 2));
        assertThat(orders).hasSize(2).allSatisfy(order -> assertThat(order.getItems()).hasSize(3));
        var next = repository.findPageIds(ids.get(1)-1, 29L, OrderStatus.PAID, 2L, "%", window.fetch());
        assertThat(next).hasSize(3).noneMatch(ids.subList(0,2)::contains);
        assertThat(repository.findPurchasedIds(29L, java.util.List.of(2L, 99L))).containsExactly(2L);
        assertThat(repository.findPurchasedIds(30L, java.util.List.of(2L))).isEmpty();
    }
}
