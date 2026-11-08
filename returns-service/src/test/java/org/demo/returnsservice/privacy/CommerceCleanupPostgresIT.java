package org.demo.returnsservice.privacy;

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
    @BeforeEach void reset(){jdbc.execute("TRUNCATE return_events,return_lines,return_requests,commerce_privacy_locks,account_privacy_fences RESTART IDENTITY CASCADE");}
    void fence(){fences.fence(17,request);}
    void denied(String sql){assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    void seed(String status,boolean verified){jdbc.update("INSERT INTO return_requests(id,order_id,customer_id,customer_email,request_type,reason,description,status,moderation_note,request_hash,order_verified_at,created_at,updated_at) VALUES(20,10,17,'owner@fixture.example','RETURN','Reason','Private description',?,'Operator note',?,CASE WHEN ? THEN now() ELSE NULL END,now(),now())",status,verified?"a".repeat(64):null,verified);jdbc.update("INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) VALUES(20,1,1,'Product',3,2)");jdbc.update("INSERT INTO return_events(return_id,revision,operation_id,action,to_status,note,actor_ref,created_at) SELECT 20,n,gen_random_uuid(),'STATUS_CHANGED',?,'private note','operator@fixture.example',now() FROM generate_series(1,205)n",status);}
    @Test void verifiedClosedTextIsClearedInBoundedBatchesButIdentifiersAndQuantitiesRemain(){seed("REJECTED",true);fence();var adapter=new ReturnsCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).nextCursor()).isZero();assertThat(jdbc.queryForMap("SELECT reason,description,customer_email,moderation_note,privacy_redacted FROM return_requests")).containsEntry("reason",null).containsEntry("description",null).containsEntry("customer_email",null).containsEntry("moderation_note",null).containsEntry("privacy_redacted",true);assertThat(adapter.cleanup(17,request,0).nextCursor()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM return_events WHERE actor_ref IS NOT NULL",Long.class)).isEqualTo(105);adapter.cleanup(17,request,0);adapter.cleanup(17,request,0);assertThat(adapter.cleanup(17,request,0).nextCursor()).isEqualTo(10);assertThat(adapter.cleanup(17,request,10).outcome()).isEqualTo("END");assertThat(jdbc.queryForObject("SELECT count(*) FROM return_events",Long.class)).isEqualTo(205);assertThat(jdbc.queryForObject("SELECT count(*) FROM return_events WHERE note IS NOT NULL OR actor_ref IS NOT NULL",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT quantity FROM return_lines",Integer.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT request_hash FROM return_requests",String.class)).isEqualTo("a".repeat(64));denied("UPDATE return_requests SET description='restored'");denied("UPDATE return_events SET note='restored' WHERE revision=1");denied("DELETE FROM return_lines");denied("INSERT INTO return_events(return_id,revision,action,to_status,created_at) VALUES(20,999,'NEW','REJECTED',now())");}
    @Test void activeOrUnreconciledCompletedCaseCannotLoseText(){seed("APPROVED",true);fence();var adapter=new ReturnsCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_RETURN_PROCESS");jdbc.update("UPDATE return_requests SET status='COMPLETED'");assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("RETURN_REVIEW_REQUIRED");assertThat(jdbc.queryForObject("SELECT description FROM return_requests",String.class)).isEqualTo("Private description");}
    @Test void ambiguousLegacyAndMissingFinanceWait(){seed("REJECTED",false);denied("UPDATE return_requests SET reason=NULL WHERE id=20");denied("UPDATE return_events SET actor_ref=NULL");fence();var adapter=new ReturnsCommerceCleanup(fences,source);assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("RETURN_REVIEW_REQUIRED");jdbc.update("UPDATE return_requests SET request_hash=repeat('a',64),order_verified_at=now()");finance=false;assertThat(adapter.cleanup(17,request,0).code()).isEqualTo("WAITING_A43_36");assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();}
    @Test void unlinkedOwnedHistoryRequiresReviewInsteadOfFalseCompletion(){seed("REJECTED",true);fence();assertThat(new ReturnsCommerceCleanup(fences,(c,id,after)->Optional.empty()).cleanup(17,request,0).code()).isEqualTo("RETURN_REVIEW_REQUIRED");}
    @Test void failedRedactionRollsBackReceiptAndTextAndCanBeRetried(){
        seed("REJECTED",true);fence();
        jdbc.execute("CREATE FUNCTION fixture_abort_redaction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture rollback'; END $$");
        jdbc.execute("CREATE TRIGGER fixture_abort AFTER UPDATE ON return_requests FOR EACH ROW EXECUTE FUNCTION fixture_abort_redaction()");
        var adapter=new ReturnsCommerceCleanup(fences,source);
        try{assertThatThrownBy(()->adapter.cleanup(17,request,0)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM commerce_privacy_locks",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT description FROM return_requests",String.class)).isEqualTo("Private description");
        }finally{jdbc.execute("DROP TRIGGER fixture_abort ON return_requests");jdbc.execute("DROP FUNCTION fixture_abort_redaction()");}
        assertThat(adapter.cleanup(17,request,0).outcome()).isEqualTo("MORE");
        assertThat(jdbc.queryForObject("SELECT description FROM return_requests",String.class)).isNull();
    }

}
