package org.demo.promotionservice.export;

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
class CouponDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("coupon_export_fixture").withUsername("fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;private static DriverManagerDataSource source;private OrderAccess orders;private CouponDataExportService service;
    @BeforeAll static void database(){source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE coupon_reservations,promotion_redemptions RESTART IDENTITY");orders=mock(OrderAccess.class);service=new CouponDataExportService(source,orders);}
    @Test void identicalCodeDoesNotConnectDifferentOrderOwners(){
        reservation(7);reservation(8);redemptions(7,1);redemptions(8,1);
        assertThat(service.export(7).reservation().orderId()).isEqualTo(7);
        assertThat(service.redemptions(7,0,50).items()).hasSize(1).allSatisfy(e->assertThat(e.orderId()).isEqualTo(7));
    }
    @Test void largeLegacyRedemptionListIsBoundedAndSourceRechecked(){
        redemptions(7,105);var ids=new ArrayList<Long>();long cursor=0;int pages=0;
        while(true){var page=service.redemptions(7,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);page.items().forEach(e->ids.add(e.redemptionId()));
            if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).containsExactlyElementsOf(java.util.stream.LongStream.iterate(105,n->n-1).limit(105).boxed().toList());
        assertThat(new HashSet<>(ids)).hasSize(105);assertThat(pages).isEqualTo(3);verify(orders,times(3)).requireCustomerOwner(7L);
    }
    @Test void legacyRedemptionsAreExportedWithoutReconstructingMissingReservation(){
        redemptions(7,1);assertThat(service.export(7).reservation()).isNull();assertThat(service.redemptions(7,0,50).items()).hasSize(1);
    }
    @Test void expiredReservedConsumedAndReleasedTombstoneDataRemainUnchanged(){
        reservation(7);for(String state:List.of("RESERVED","CONSUMED","RELEASED")){
            jdbc.update("UPDATE coupon_reservations SET state=? WHERE order_id=7",state);String before=snapshot();var result=service.export(7);
            assertThat(result.reservation().state()).isEqualTo(state);assertThat(result.reservation().subtotal()).isEqualByComparingTo("123.45");
            assertThat(result.reservation().discountAmount()).isEqualByComparingTo("12.34");assertThat(result.reservation().acceptedAt()).isNull();
            assertThat(result.reservation().expiresAt()).isBefore(java.time.Instant.now());assertThat(snapshot()).isEqualTo(before);assertThat(result.complete()).isFalse();}
    }
    @Test void emptyAndExhaustedOwnedPagesRemainEmpty(){
        assertThat(service.export(7).reservation()).isNull();assertThat(service.redemptions(7,0,50).items()).isEmpty();redemptions(7,1);
        assertThat(service.redemptions(7,1,50).items()).isEmpty();verify(orders,times(3)).requireCustomerOwner(7L);
    }
    @Test void downloadsDoNotChangeGlobalDefinitionsCountersOrDedupeEvidence(){
        reservation(7);redemptions(7,2);String before=snapshot();service.export(7);service.redemptions(7,0,50);assertThat(snapshot()).isEqualTo(before);
        assertThatThrownBy(()->redemptions(7,1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void projectionWorksWithoutOperationalVersionAndPromotionDefinitionAccess(){
        reservation(7);redemptions(7,1);jdbc.execute("CREATE ROLE export_reader LOGIN PASSWORD 'fixture-reader' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO export_reader");
        jdbc.execute("GRANT SELECT(order_id,code,subtotal,discount_amount,state,accepted_at,expires_at,created_at,updated_at) ON coupon_reservations TO export_reader");
        jdbc.execute("GRANT SELECT(id,order_id,code,redeemed_at) ON promotion_redemptions TO export_reader");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"export_reader","fixture-reader");var limited=new JdbcTemplate(restricted);
        for(String query:List.of("SELECT version FROM coupon_reservations","SELECT * FROM promotion_codes","SELECT * FROM promotion_campaigns","SELECT * FROM promotion_price_history"))assertThatThrownBy(()->limited.queryForList(query)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new CouponDataExportService(restricted,orders);assertThat(export.export(7).reservation()).isNotNull();assertThat(export.redemptions(7,0,50).items()).hasSize(1);
    }
    @Test void compositeIndexCoversOrderAndDescendingRedemptionWindow(){
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='idx_promotion_redemptions_order_export_window'",String.class)).contains("(order_id, id DESC)");
    }
    private void reservation(long id){jdbc.update("INSERT INTO coupon_reservations(order_id,version,code,subtotal,discount_amount,state,accepted_at,expires_at,created_at,updated_at) VALUES (?,99,'SAME',123.45,12.34,'RESERVED',null,now()-interval '1 day',now()-interval '2 days',now()-interval '2 days')",id);}
    private void redemptions(long id,int count){jdbc.update("INSERT INTO promotion_redemptions(order_id,code,redeemed_at) SELECT ?,concat('SAME',n),now() FROM generate_series(1,?) n",id,count);}
    private String snapshot(){return jdbc.queryForObject("""
            SELECT json_build_array((SELECT json_agg(r ORDER BY order_id) FROM coupon_reservations r),
              (SELECT json_agg(d ORDER BY id) FROM promotion_redemptions d),(SELECT json_agg(c ORDER BY id) FROM promotion_codes c),
              (SELECT json_agg(p ORDER BY id) FROM promotion_campaigns p),(SELECT json_agg(h ORDER BY id) FROM promotion_price_history h))::text
            """,String.class);}
}
