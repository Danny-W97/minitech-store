package org.demo.promotionservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE promotion_redemptions,coupon_reservations,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    void fence(){fences.fence(17,request);}
    void denied(String sql){assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    void seed(String state){jdbc.update("INSERT INTO coupon_reservations(order_id,code,subtotal,discount_amount,state,created_at,updated_at) VALUES(10,'KEEP-CODE',123.45,5,?,now(),now())",state);if(state.equals("CONSUMED"))jdbc.update("INSERT INTO promotion_redemptions(code,order_id,redeemed_at) VALUES('KEEP-CODE',10,now())");}
    @Test void retainsConsumedCodeAndRedemptionWithoutFreeingLimits(){seed("CONSUMED");fence();var adapter=new PromotionCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).nextCursor()).isEqualTo(10);assertThat(jdbc.queryForObject("SELECT code FROM coupon_reservations",String.class)).isEqualTo("KEEP-CODE");assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion_redemptions",Long.class)).isEqualTo(1);denied("UPDATE coupon_reservations SET state='RELEASED'");denied("DELETE FROM promotion_redemptions");assertThat(adapter.cleanup(17,request,10).outcome()).isEqualTo("END");}
    @Test void activeAndAmbiguousRedemptionWaitWithoutMutation(){seed("RESERVED");fence();var adapter=new PromotionCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_COUPON");jdbc.update("UPDATE coupon_reservations SET state='CONSUMED'");assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("ORDER_REVIEW_REQUIRED");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void financeProofIsMandatory(){seed("CONSUMED");fence();finance=false;assertThat(new PromotionCommerceCleanup(fences,source).cleanup(17,request,0).code()).isEqualTo("WAITING_A43_36");}

}
