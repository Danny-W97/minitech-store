package org.demo.inventoryservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class InventoryDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("inventory_export_fixture").withUsername("fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;private static DriverManagerDataSource source;private OrderAccess orders;private InventoryDataExportService service;
    @BeforeAll static void database(){source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE inventory_reservation_items,inventory_reservations RESTART IDENTITY CASCADE");orders=mock(OrderAccess.class);service=new InventoryDataExportService(source,orders);}
    @Test void itemsAreQualifiedByReservationOrderRatherThanNumericalReservationId(){
        long own=reservation(7),other=reservation(8);items(own,2);items(other,3);
        assertThat(service.export(7).reservation().reservationId()).isEqualTo(own);
        assertThat(service.items(7,0,50).items()).hasSize(2).allSatisfy(e->assertThat(e.reservationId()).isEqualTo(own));
        assertThat(service.export(1).reservation()).isNull();assertThat(service.items(1,0,50).items()).isEmpty();
    }
    @Test void largeItemListIsBoundedWithoutDuplicatesAndRechecksOrderForEachPage(){
        items(reservation(7),105);var ids=new ArrayList<Long>();long cursor=0;int pages=0;
        while(true){var page=service.items(7,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);page.items().forEach(e->ids.add(e.reservationItemId()));
            if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).containsExactlyElementsOf(java.util.stream.LongStream.iterate(105,n->n-1).limit(105).boxed().toList());
        assertThat(new HashSet<>(ids)).hasSize(105);assertThat(pages).isEqualTo(3);verify(orders,times(3)).requireCustomerOwner(7L);
    }
    @Test void emptyAndExhaustedPagesDoNotInventReservationData(){
        assertThat(service.export(7).reservation()).isNull();assertThat(service.items(7,0,50).items()).isEmpty();
        items(reservation(7),2);assertThat(service.items(7,1,50).items()).isEmpty();verify(orders,times(3)).requireCustomerOwner(7L);
    }
    @Test void expiredReservationRemainsStoredReservedAndGlobalStockIsUnchanged(){
        long id=reservation(7);items(id,2);String before=snapshot();var result=service.export(7);service.items(7,0,50);
        assertThat(result.reservation().status()).isEqualTo("RESERVED");assertThat(result.reservation().expiresAt()).isBefore(java.time.Instant.now());
        assertThat(snapshot()).isEqualTo(before);assertThat(result.complete()).isFalse();
    }
    @Test void confirmedAndReleasedStatesAreKeptWithoutRunningBusinessTransitions(){
        long id=reservation(7);for(String status:List.of("CONFIRMED","RELEASED")){
            jdbc.update("UPDATE inventory_reservations SET status=? WHERE id=?",status,id);String before=snapshot();
            assertThat(service.export(7).reservation().status()).isEqualTo(status);assertThat(snapshot()).isEqualTo(before);}
    }
    @Test void laterItemsDoNotEnterAnAlreadyStartedKeysetWindow(){
        long id=reservation(7);items(id,2);var first=service.items(7,0,1);items(id,1);
        assertThat(service.items(7,Long.parseLong(first.nextCursor()),50).items()).extracting(InventoryItemDataExportPage.Item::reservationItemId).containsExactly(1L);
    }
    @Test void restrictedProjectionNeedsNoGlobalStockOrOperationAccess(){
        long id=reservation(7);items(id,2);jdbc.execute("CREATE ROLE export_reader LOGIN PASSWORD 'fixture-reader' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO export_reader");
        jdbc.execute("GRANT SELECT(id,order_id,status,expires_at,created_at,updated_at) ON inventory_reservations TO export_reader");
        jdbc.execute("GRANT SELECT(id,reservation_id,product_id,quantity) ON inventory_reservation_items TO export_reader");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"export_reader","fixture-reader");var limited=new JdbcTemplate(restricted);
        for(String query:List.of("SELECT * FROM inventory_items","SELECT * FROM inventory_stock_operations"))assertThatThrownBy(()->limited.queryForList(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new InventoryDataExportService(restricted,orders);assertThat(export.export(7).reservation()).isNotNull();assertThat(export.items(7,0,50).items()).hasSize(2);
    }
    @Test void compositeIndexCoversReservationAndDescendingItemWindow(){
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='idx_inventory_reservation_items_export_window'",String.class)).contains("(reservation_id, id DESC)");
    }
    private long reservation(long orderId){return jdbc.queryForObject("INSERT INTO inventory_reservations(order_id,status,expires_at,created_at,updated_at) VALUES (?,'RESERVED',now()-interval '1 day',now()-interval '2 days',now()-interval '2 days') RETURNING id",Long.class,orderId);}
    private void items(long reservation,int count){jdbc.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) SELECT ?,n,2 FROM generate_series(1,?) n",reservation,count);}
    private String snapshot(){return jdbc.queryForObject("""
            SELECT json_build_array((SELECT json_agg(r ORDER BY id) FROM inventory_reservations r),
              (SELECT json_agg(i ORDER BY id) FROM inventory_reservation_items i),(SELECT json_agg(s ORDER BY id) FROM inventory_items s),
              (SELECT json_agg(o ORDER BY id) FROM inventory_stock_operations o))::text
            """,String.class);}
}
