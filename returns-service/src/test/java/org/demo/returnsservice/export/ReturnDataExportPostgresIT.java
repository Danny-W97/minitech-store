package org.demo.returnsservice.export;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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
class ReturnDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("return_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;private static ReturnDataExportService service;
    @BeforeAll static void database(){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);service=new ReturnDataExportService(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE return_events,return_lines,return_requests RESTART IDENTITY CASCADE");}
    @Test void boundedHeaderPagesIncludeOnlyTheCustomerIdRegardlessOfStoredEmail(){
        long first=request(17),other=request(18);for(int i=0;i<54;i++)request(17);
        List<Long> ids=new ArrayList<>();long cursor=0;
        while(true){var page=service.returns(17,cursor,20);assertThat(page.returns()).hasSizeLessThanOrEqualTo(20);
            page.returns().forEach(row->ids.add(row.returnId()));if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(55).contains(first).doesNotContain(other);assertThat(new HashSet<>(ids)).hasSize(55);
        assertThat(service.returns(99,0,50).returns()).isEmpty();
    }
    @Test void authoredTextAllStatusesAndBothRequestTypesArePreserved(){
        String[] statuses={"REQUESTED","UNDER_REVIEW","APPROVED","REJECTED","COMPLETED"};
        for(int i=0;i<statuses.length;i++){long id=request(17);jdbc.update("UPDATE return_requests SET status=?,request_type=? WHERE id=?",statuses[i],i%2==0?"RETURN":"COMPLAINT",id);}
        var page=service.returns(17,0,50);assertThat(page.returns()).hasSize(5);
        assertThat(page.returns()).extracting(row->row.status().name()).containsExactly("COMPLETED","REJECTED","APPROVED","UNDER_REVIEW","REQUESTED");
        assertThat(page.returns()).extracting(row->row.requestType().name()).contains("RETURN","COMPLAINT");
        assertThat(page.returns().getFirst().description()).isEqualTo("Opis Żółć — zapisany tekst");assertThat(page.complete()).isFalse();
        assertThat(page.omissions()).contains(new ReturnDataExportPage.Omission("submittedText","as-stored-no-semantic-redaction"));
    }
    @Test void itemCursorUsesExistingOrderItemIndexAndNeverLinePrimaryKey(){
        long id=request(17),other=request(17);items(id,105);items(other,2);
        List<Long> lineIds=new ArrayList<>(),orderItemIds=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service.items(17,id,cursor,50);assertThat(page.items()).hasSizeLessThanOrEqualTo(50);pages++;
            page.items().forEach(row->{lineIds.add(row.lineId());orderItemIds.add(row.orderItemId());assertThat(row.quantity()).isEqualTo(2);});
            if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(orderItemIds).hasSize(105).startsWith(4999L,4998L).endsWith(4895L);
        assertThat(lineIds).hasSize(105).startsWith(1L,2L).endsWith(105L);assertThat(new HashSet<>(lineIds)).hasSize(105);
    }
    @Test void statusHistoryIsBoundedWithoutOperatorOrWorkflowFields(){
        long id=request(17),other=request(18);events(id,105);events(other,2);
        List<Long> ids=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service.events(17,id,cursor,50);assertThat(page.events()).hasSizeLessThanOrEqualTo(50);pages++;
            page.events().forEach(row->ids.add(row.eventId()));if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(ids).hasSize(105).startsWith(105L,104L).endsWith(1L);
        var legacy=service.events(17,id,2,1);assertThat(legacy.events().getFirst().action()).isEqualTo("LEGACY_SNAPSHOT");
        assertThat(legacy.events().getFirst().fromStatus()).isNull();
        assertThat(legacy.omissions()).contains(new ReturnDataExportPage.Omission("legacySnapshotTime","migration-snapshot-not-original-event-time"));
    }
    @Test void ownLegacyEmptyOrExhaustedChildPagesSucceed(){
        long id=request(17);jdbc.update("UPDATE return_requests SET request_hash=NULL,order_verified_at=NULL WHERE id=?",id);
        assertThat(service.items(17,id,0,50).items()).isEmpty();assertThat(service.events(17,id,0,50).events()).isEmpty();
        items(id,2);events(id,2);assertThat(service.items(17,id,1,50).items()).isEmpty();assertThat(service.events(17,id,1,50).events()).isEmpty();
    }
    @Test void foreignAndMissingCasesReturn404ForBothChildCollections(){
        long id=request(18);items(id,1);events(id,1);
        for(long candidate:new long[]{id,99999,0,-1}){status(404,()->service.items(17,candidate,0,50));status(404,()->service.events(17,candidate,0,50));}
    }
    @Test void columnRestrictedRoleCannotReadNotesIdentitiesOrWorkflowMetadataButCanExport(){
        long id=request(17);items(id,2);events(id,2);
        jdbc.execute("CREATE ROLE return_export LOGIN PASSWORD 'fixture-projection' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO return_export");
        jdbc.execute("GRANT SELECT (id,customer_id,order_id,customer_email,request_type,reason,description,status,created_at,updated_at) ON return_requests TO return_export");
        jdbc.execute("GRANT SELECT (id,return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) ON return_lines TO return_export");
        jdbc.execute("GRANT SELECT (id,return_id,action,from_status,to_status,created_at) ON return_events TO return_export");
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"return_export","fixture-projection");var limited=new JdbcTemplate(source);
        for(var sql:List.of("SELECT moderation_note FROM return_requests","SELECT request_hash FROM return_requests","SELECT note FROM return_events","SELECT actor_ref FROM return_events","SELECT operation_id FROM return_events"))
            assertThatThrownBy(()->limited.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new ReturnDataExportService(source);assertThat(export.returns(17,0,50).returns()).hasSize(1);
        assertThat(export.items(17,id,0,50).items()).hasSize(2);assertThat(export.events(17,id,0,50).events()).hasSize(2);
    }
    @Test void everyReadValidatesCustomerIdentityAndWindow(){
        long id=request(17);
        for(int size:new int[]{-1,0,51,Integer.MAX_VALUE}){status(400,()->service.returns(17,0,size));status(400,()->service.items(17,id,0,size));status(400,()->service.events(17,id,0,size));}
        status(401,()->service.returns(0,0,1));status(401,()->service.items(0,id,0,1));status(401,()->service.events(0,id,0,1));
        status(400,()->service.returns(17,-1,1));status(400,()->service.items(17,id,-1,1));status(400,()->service.events(17,id,-1,1));
    }
    @Test void separatePagesDoNotClaimOneSnapshotWhenRowsChange(){
        long id=request(17);events(id,3);var first=service.events(17,id,0,1);jdbc.update("DELETE FROM return_events WHERE id=2");
        var second=service.events(17,id,Long.parseLong(first.nextCursor()),50);assertThat(second.events()).extracting(ReturnEventDataExportPage.Event::eventId).containsExactly(1L);
        assertThat(second.omissions()).contains(new ReturnDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    }
    @Test void exportsLeaveEveryCaseLineAndEventUnchanged(){
        long id=request(17);items(id,3);events(id,3);String before=snapshot();service.returns(17,0,50);service.items(17,id,0,50);service.events(17,id,0,50);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='return_lines' AND indexdef LIKE 'CREATE UNIQUE INDEX% (return_id, order_item_id)'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_returns_customer_window','idx_return_events_page')",Integer.class)).isEqualTo(2);
    }
    private long request(long customer){return jdbc.queryForObject("""
            INSERT INTO return_requests(order_id,customer_id,customer_email,request_type,reason,description,status,moderation_note,
              created_at,updated_at,request_hash,order_verified_at)
            VALUES (nextval('return_requests_id_seq')+1000,?,'same@fixture.example','RETURN','Powód Żółć','Opis Żółć — zapisany tekst',
              'REQUESTED','fixture-private-moderation',now(),now(),repeat('a',64),now()) RETURNING id
            """,Long.class,customer);}
    private void items(long id,int count){jdbc.update("INSERT INTO return_lines(return_id,order_item_id,product_id,product_name,ordered_quantity,quantity) SELECT ?,5000-n,n,'Produkt Żółć '||n,3,2 FROM generate_series(1,?) n",id,count);}
    private void events(long id,int count){jdbc.update("""
            INSERT INTO return_events(return_id,revision,expected_version,operation_id,action,from_status,to_status,note,actor_ref,order_status,payment_state,created_at)
            SELECT ?,n-1,n-2,gen_random_uuid(),CASE WHEN n=1 THEN 'LEGACY_SNAPSHOT' ELSE 'STATUS_CHANGED' END,
              CASE WHEN n=1 THEN NULL ELSE 'REQUESTED' END,'UNDER_REVIEW','fixture-private-note','private-operator@fixture.example','COMPLETED','PAID',now()
            FROM generate_series(1,?) n
            """,id,count);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(r ORDER BY id) FROM return_requests r),(SELECT json_agg(l ORDER BY id) FROM return_lines l),(SELECT json_agg(e ORDER BY id) FROM return_events e))::text",String.class);}
    private void status(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(status));}
}
