package org.demo.inventoryservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CommerceCleanupPostgresIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("commerce_fixture").withUsername("fixture").withPassword("fixture-only");
    static JdbcTemplate jdbc;static PrivacyFenceStore fences;static TransactionTemplate tx;
    final UUID request=UUID.randomUUID();boolean finance=true;
    CommerceOrderSource source=(customer,id,cursor)->cursor<10?Optional.of(new CommerceOrderSource.Entry(10,"ELIGIBLE",finance,"COMPLETED",20,true,true)):Optional.empty();
    @BeforeAll static void setup(){var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);fences=new PrivacyFenceStore(ds,manager);tx=new TransactionTemplate(manager);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE inventory_reservation_items,inventory_reservations,inventory_items,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    void fence(){fences.fence(17,request);}
    void denied(String sql){assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    void seed(String status){jdbc.update("INSERT INTO inventory_items(product_id,available_quantity,reserved_quantity,sold_quantity,created_at,updated_at) VALUES(1,7,0,2,now(),now())");jdbc.update("INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES(20,10,?,now(),now(),now()+interval '1 hour')",status);jdbc.update("INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES(20,1,2)");}
    @Test void retainsStockQuantitiesAndKeysButSealsBusinessReservation(){seed("CONFIRMED");fence();var adapter=new InventoryCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).nextCursor()).isEqualTo(10);assertThat(jdbc.queryForMap("SELECT available_quantity,reserved_quantity,sold_quantity FROM inventory_items")).containsEntry("available_quantity",7).containsEntry("reserved_quantity",0).containsEntry("sold_quantity",2);denied("UPDATE inventory_reservations SET status='RELEASED' WHERE id=20");denied("DELETE FROM inventory_reservation_items WHERE reservation_id=20");assertThat(adapter.cleanup(17,request,10).outcome()).isEqualTo("END");jdbc.update("UPDATE inventory_items SET available_quantity=8");}
    @Test void activeReservationAndMissingFinancePauseWithoutReleasingStock(){seed("RESERVED");fence();var adapter=new InventoryCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_RESERVATION");jdbc.update("UPDATE inventory_reservations SET status='CONFIRMED'");finance=false;assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_A43_36");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void wrongRequestCannotFreezeAnotherScope(){seed("CONFIRMED");fence();assertThatThrownBy(()->new InventoryCommerceCleanup(fences,source).cleanup(17,UUID.randomUUID(),0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);}

}
