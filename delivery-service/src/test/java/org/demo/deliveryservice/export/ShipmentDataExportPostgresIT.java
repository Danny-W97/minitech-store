package org.demo.deliveryservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ShipmentDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shipment_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static DriverManagerDataSource source;private static JdbcTemplate jdbc;
    private final OrderAccess orders=mock(OrderAccess.class);
    @BeforeAll static void database(){
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE shipments RESTART IDENTITY");}
    @Test void safeProjectionPreservesStoredFieldsAndDoesNotTreatShipmentIdAsOrderId(){
        shipment(99);shipment(100);var result=new ShipmentDataExportService(source,orders).export(99);
        assertThat(result.shipment().shipmentId()).isEqualTo(1L);assertThat(result.shipment().orderId()).isEqualTo(99L);
        assertThat(result.shipment().carrier()).isEqualTo("Zażółć");assertThat(result.shipment().status()).isEqualTo("IN_TRANSIT");
        assertThat(result.shipment().trackingNumber()).isEqualTo("TRACK-99");assertThat(result.complete()).isFalse();
        assertThat(result.omissions()).contains(new ShipmentDataExport.Omission("crossServiceSnapshot","not-a-snapshot"));
        verify(orders).requireCustomerOwner(99L);
    }
    @Test void orderWithoutShipmentReturns404InsteadOfAnEmptySuccessfulFile(){
        assertThatThrownBy(()->new ShipmentDataExportService(source,orders).export(99))
                .isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(404));
        verify(orders).requireCustomerOwner(99L);
    }
    @Test void projectionWorksWithoutSelectRightsOnAnySynchronizationFields(){
        shipment(99);jdbc.execute("CREATE ROLE shipment_export LOGIN PASSWORD 'fixture-projection' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO shipment_export");
        jdbc.execute("GRANT SELECT (id,order_id,tracking_number,carrier,method_code,status,created_at,updated_at) ON shipments TO shipment_export");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"shipment_export","fixture-projection");
        assertThatThrownBy(()->new JdbcTemplate(restricted).queryForList("SELECT sync_token FROM shipments"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(new ShipmentDataExportService(restricted,orders).export(99).shipment().orderId()).isEqualTo(99L);
    }
    @Test void downloadsNeverAdvanceStatusOrAlterSynchronizationState(){
        shipment(99);String before=snapshot();var service=new ShipmentDataExportService(source,orders);
        service.export(99);service.export(99);assertThat(snapshot()).isEqualTo(before);verify(orders,times(2)).requireCustomerOwner(99L);
    }
    @Test void existingOrderUniqueIndexBoundsTheProjectionToOneRow(){
        shipment(99);assertThatThrownBy(()->shipment(99)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(new ShipmentDataExportService(source,orders).export(99).shipment().orderId()).isEqualTo(99L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='shipments' AND indexdef LIKE 'CREATE UNIQUE INDEX% (order_id)'",Integer.class)).isEqualTo(1);
    }
    private void shipment(long id){jdbc.update("""
            INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,created_at,updated_at,
              progress_revision,sync_state,sync_attempts,sync_error,sync_token,sync_until,sync_next_attempt_at)
            VALUES (?,?,'Zażółć','STANDARD_COURIER','IN_TRANSIT',now(),now(),7,'RETRY',3,'fixture-private-error',
              'fixture-private-token',now()+interval '1 hour',now())
            """,id,"TRACK-"+id);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_agg(s ORDER BY id)::text FROM shipments s",String.class);}
}
