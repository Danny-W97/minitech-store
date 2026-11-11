package org.demo.orderservice.export;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OrderDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;private static OrderDataExportService service;
    @BeforeAll static void database(){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);service=new OrderDataExportService(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE order_sagas,order_items,orders RESTART IDENTITY CASCADE");}
    @Test void headerPagesAreBoundedAndDoNotClaimGuestOrdersWithTheSameEmail(){
        long first=order(17L),other=order(18L),guest=order(null);
        for(int i=0;i<54;i++)order(17L);
        List<Long> ids=new ArrayList<>();long cursor=0;
        while(true){var page=service.orders(17,cursor,20);assertThat(page.orders()).hasSizeLessThanOrEqualTo(20);
            page.orders().forEach(row->ids.add(row.orderId()));if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(55).contains(first).doesNotContain(other,guest);assertThat(new HashSet<>(ids)).hasSize(55);
    }
    @Test void headerProjectionPreservesMoneyAndRecipientDataAndNullDates(){
        long id=order(17L);var row=service.orders(17,0,1).orders().getFirst();
        assertThat(row.orderId()).isEqualTo(id);assertThat(row.totalPrice()).isEqualByComparingTo("123.45");
        assertThat(row.deliveryCost()).isEqualByComparingTo("12.34");assertThat(row.discountAmount()).isEqualByComparingTo("2.50");
        assertThat(row.deliveryAddress().lastName()).isEqualTo("Żółć");assertThat(row.deliveryAddress().phone()).isNull();assertThat(row.deliveryDate()).isNull();
        assertThat(service.orders(17,0,1).omissions()).contains(new OrderDataExportPage.Omission("recipientDetails","as-stored-no-semantic-redaction"));
        assertThat(row.legacyLine()).isNull();
    }
    @Test void legacySingleLineFieldsArePreservedWithoutInventingModernItems(){
        long id=order(17L);jdbc.update("UPDATE orders SET product_id=777,quantity=3,unit_price=1.23 WHERE id=?",id);
        var row=service.orders(17,0,1).orders().getFirst();assertThat(row.legacyLine().productId()).isEqualTo(777L);
        assertThat(row.legacyLine().quantity()).isEqualTo(3);assertThat(row.legacyLine().unitPrice()).isEqualByComparingTo("1.23");
        assertThat(service.items(17,id,0,50).items()).isEmpty();
    }
    @Test void itemPagesNeverAggregateTheWholeOrderOrAnotherOrdersItems(){
        long id=order(17L),another=order(17L);items(id,105);items(another,2);
        List<Long> ids=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service.items(17,id,cursor,50);assertThat(page.items()).hasSizeLessThanOrEqualTo(50);assertThat(page.orderId()).isEqualTo(id);
            page.items().forEach(row->ids.add(row.itemId()));pages++;if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(ids).hasSize(105).startsWith(105L,104L).endsWith(2L,1L);assertThat(new HashSet<>(ids)).hasSize(105);
    }
    @Test void ownedEmptyOrdersAndExhaustedItemPagesRemainSuccessful(){
        long id=order(17L);assertThat(service.items(17,id,0,2).items()).isEmpty();
        items(id,2);var page=service.items(17,id,0,2);assertThat(page.items()).hasSize(2);assertThat(page.hasMore()).isFalse();
        assertThat(service.items(17,id,1,2).items()).isEmpty();assertThat(service.orders(18,0,2).orders()).isEmpty();
    }
    @Test void otherCustomersGuestAndUnknownOrdersAllFailClosed(){
        long other=order(18L),guest=order(null);items(other,1);items(guest,1);
        for(long id:new long[]{other,guest,99999,0,-1})assertStatus(404,()->service.items(17,id,0,2));
    }
    @Test void projectionWorksWithNoRightsOnSecretsOrSagaTables(){
        long id=order(17L);items(id,2);
        jdbc.execute("CREATE ROLE a43_order_export LOGIN PASSWORD 'fixture-only-projection' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO a43_order_export");
        jdbc.execute("GRANT SELECT (id,customer_id,total_price,status,customer_email,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,delivery_method,delivery_cost,promotion_code,discount_amount,fulfillment_type,delivery_date,delivery_slot,pickup_location,invoice_requested,created_at,updated_at,product_id,quantity,unit_price) ON orders TO a43_order_export");
        jdbc.execute("GRANT SELECT ON order_items TO a43_order_export");
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"a43_order_export","fixture-only-projection");var restricted=new JdbcTemplate(source);
        for(var sql:List.of("SELECT idempotency_key FROM orders","SELECT guest_access_token_hash FROM orders","SELECT checkout_request_hash FROM orders","SELECT * FROM order_sagas"))
            assertThatThrownBy(()->restricted.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new OrderDataExportService(source);assertThat(export.orders(17,0,50).orders()).hasSize(1);assertThat(export.items(17,id,0,50).items()).hasSize(2);
    }
    @Test void concurrentChangesAreNotPresentedAsOneSnapshot(){
        long id=order(17L);items(id,3);var first=service.items(17,id,0,1);items(id,1);
        jdbc.update("DELETE FROM order_items WHERE id=2");var second=service.items(17,id,Long.parseLong(first.nextCursor()),50);
        assertThat(second.items()).extracting(OrderItemDataExportPage.Item::itemId).containsExactly(1L);
        assertThat(second.omissions()).contains(new OrderDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    }
    @Test void invalidIdentitiesAndWindowsAreRejected(){
        long id=order(17L);for(int size:new int[]{-1,0,51,Integer.MAX_VALUE}){assertStatus(400,()->service.orders(17,0,size));assertStatus(400,()->service.items(17,id,0,size));}
        assertStatus(401,()->service.orders(0,0,1));assertStatus(401,()->service.items(0,id,0,1));assertStatus(400,()->service.orders(17,-1,1));assertStatus(400,()->service.items(17,id,-1,1));
    }
    @Test void exportsDoNotModifyRowsAndTheItemIndexExists(){
        long id=order(17L);items(id,3);String before=snapshot();service.orders(17,0,50);service.items(17,id,0,50);assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='idx_order_items_export_window'",Integer.class)).isEqualTo(1);
    }
    private long order(Long owner){return jdbc.queryForObject("""
            INSERT INTO orders(customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              discount_amount,fulfillment_type,idempotency_key,guest_access_token_hash,checkout_request_hash,checkout_principal,created_at,updated_at)
            VALUES (?,'same@fixture.example',123.45,'PAID','Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,
              2.50,'HOME_DELIVERY',gen_random_uuid()::text,repeat('a',64),repeat('b',64),'fixture-principal',now(),now()) RETURNING id
            """,Long.class,owner);}
    private void items(long order,int count){jdbc.update("INSERT INTO order_items(order_id,product_id,sku,product_name,quantity,unit_price,total_price) SELECT ?,n,'SKU-'||n,'Produkt Żółć '||n,2,12.34,24.68 FROM generate_series(1,?) n",order,count);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(o ORDER BY id) FROM orders o),(SELECT json_agg(i ORDER BY id) FROM order_items i),(SELECT json_agg(s ORDER BY id) FROM order_sagas s))::text",String.class);}
    private void assertStatus(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(status));}
}
