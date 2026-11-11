package org.demo.customerservice.customer;

import org.demo.platformsecurity.ListWindow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.password=", "spring.datasource.url=jdbc:h2:mem:customerlist;DB_CLOSE_DELAY=-1"})
class CustomerListQueryTest {
    @Autowired CustomerRepository customers;

    @Test void pagesAndFiltersRealCustomerColumnsWithoutDuplicates() {
        var first = customers.saveAndFlush(customer("first@audit.example", "Jan", "Kowalski"));
        var second = customers.saveAndFlush(customer("second@audit.example", "Jan", "Nowak"));
        customers.saveAndFlush(customer("other@audit.example", "Anna", "Inna"));
        var window = new ListWindow(0, 1);
        var page = window.map(customers.page(window.ceiling(), ListWindow.search("JAN"), window.fetch()),
                Customer::getId, Customer::getEmail);
        assertThat(page.items()).containsExactly(second.getEmail());
        assertThat(page.hasMore()).isTrue();
        var next = new ListWindow(Long.parseLong(page.nextCursor()), 1);
        var older = next.map(customers.page(next.ceiling(), ListWindow.search("JAN"), next.fetch()),
                Customer::getId, Customer::getEmail);
        assertThat(older.items()).containsExactly(first.getEmail());
        assertThat(older.hasMore()).isFalse();
        assertThat(customers.page(Long.MAX_VALUE, ListWindow.search("missing"), window.fetch())).isEmpty();
        assertThat(customers.page(Long.MAX_VALUE, ListWindow.search("KOWALSKI"), window.fetch()))
                .extracting(Customer::getId).containsExactly(first.getId());
    }

    private Customer customer(String email, String firstName, String lastName) {
        return new Customer(email, "test-hash", firstName, lastName, "Testowa 1", "00-001", "Warszawa", "Polska");
    }
}
