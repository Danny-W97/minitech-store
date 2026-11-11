package org.demo.deliveryservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE shipments,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    void fence(){fences.fence(17,request);}
    void denied(String sql){assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    void seed(String state){jdbc.update("INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,sync_state,created_at,updated_at) VALUES(10,'KEEP-TRACKING','Carrier','COURIER',?,'SYNCED',now(),now())",state);}
    @Test void trackingAndProgressRemainButLateBusinessUpdatesCannotReopenShipment(){seed("DELIVERED");fence();var adapter=new DeliveryCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).nextCursor()).isEqualTo(10);assertThat(jdbc.queryForObject("SELECT tracking_number FROM shipments",String.class)).isEqualTo("KEEP-TRACKING");denied("UPDATE shipments SET status='IN_TRANSIT'");denied("DELETE FROM shipments");jdbc.update("UPDATE shipments SET sync_error=NULL");assertThat(adapter.cleanup(17,request,10).outcome()).isEqualTo("END");}
    @Test void activeShipmentOrSyncLeaseWaitsWithoutReceipt(){seed("IN_TRANSIT");fence();var adapter=new DeliveryCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_SHIPMENT");jdbc.update("UPDATE shipments SET status='DELIVERED',sync_token='lease',sync_until=now()+interval '1 hour'");assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_SHIPMENT");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void missingFinanceAndReviewStateCannotBeOverridden(){seed("DELIVERED");fence();finance=false;var adapter=new DeliveryCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_A43_36");jdbc.update("UPDATE shipments SET sync_state='REVIEW_REQUIRED'");assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("SHIPMENT_REVIEW_REQUIRED");}

}
