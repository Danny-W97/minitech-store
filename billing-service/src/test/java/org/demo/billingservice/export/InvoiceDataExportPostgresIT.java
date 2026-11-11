package org.demo.billingservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class InvoiceDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("invoice_export_fixture").withUsername("fixture").withPassword("fixture-only-export");
    private static JdbcTemplate jdbc;private static DriverManagerDataSource source;
    private final OrderAccess orders=mock(OrderAccess.class);
    @BeforeAll static void database(){
        source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);
    }
    @BeforeEach void reset(){jdbc.execute("TRUNCATE invoice_corrections,invoice_items,invoices RESTART IDENTITY CASCADE");}
    private InvoiceDataExportService service(){return new InvoiceDataExportService(source,orders);}
    @Test void orderOwnershipOverridesNullAndStaleLocalCustomerIdsWithoutReassigningDocuments(){
        long id=invoice(99);for(Long owner:new Long[]{null,17L,18L}){
            jdbc.update("UPDATE invoices SET customer_id=? WHERE id=?",owner,id);var row=service().invoice(99);
            assertThat(row.invoice().invoiceId()).isEqualTo(id);assertThat(row.invoice().buyerName()).isEqualTo("Jan Żółć");
            assertThat(jdbc.queryForObject("SELECT customer_id FROM invoices WHERE id=?",Long.class,id)).isEqualTo(owner);
        }
        verify(orders,times(3)).requireCustomerOwner(99L);
    }
    @Test void headerPreservesAmountsRecipientAndNullFieldsWithoutLoadingChildren(){
        long id=invoice(99);items(id,105);corrections(id,99,105);var row=service().invoice(99);
        assertThat(row.invoice().grossAmount()).isEqualByComparingTo("123.45");assertThat(row.invoice().netAmount()).isEqualByComparingTo("100.37");
        assertThat(row.invoice().companyName()).isNull();assertThat(row.invoice().taxId()).isNull();assertThat(row.complete()).isFalse();
        assertThat(row.pdf().path()).isEqualTo("/invoices/me/data-export/orders/99/pdf");
    }
    @Test void itemPagesUsePositionCursorRatherThanTheRowId(){
        long id=invoice(99),other=invoice(100);items(id,105);items(other,2);List<Long> ids=new ArrayList<>();List<Integer> positions=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service().items(99,cursor,50);assertThat(page.items()).hasSizeLessThanOrEqualTo(50);pages++;
            page.items().forEach(row->{ids.add(row.itemId());positions.add(row.positionNo());});if(!page.hasMore()){assertThat(page.nextCursor()).isEqualTo("0");break;}cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(ids).hasSize(105).startsWith(1L,2L).endsWith(105L);assertThat(positions).startsWith(999,998).endsWith(895);
    }
    @Test void correctionPagesKeepLegacyAndCumulativeSnapshotsWithoutRecalculation(){
        long id=invoice(99);corrections(id,99,105);List<Long> ids=new ArrayList<>();long cursor=0;int pages=0;
        while(true){var page=service().corrections(99,cursor,50);assertThat(page.corrections()).hasSizeLessThanOrEqualTo(50);pages++;
            page.corrections().forEach(row->ids.add(row.correctionId()));if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(pages).isEqualTo(3);assertThat(ids).hasSize(105).startsWith(105L,104L).endsWith(1L);
        var legacy=service().corrections(99,2,1).corrections().getFirst();assertThat(legacy.balanceModel()).isEqualTo("LEGACY_BASE_DELTA");
        assertThat(legacy.correctionGross()).isEqualByComparingTo("-1.23");assertThat(legacy.finalGross()).isEqualByComparingTo("122.22");
        assertThat(service().corrections(99,0,1).corrections().getFirst().balanceModel()).isEqualTo("CUMULATIVE_DELTA");
    }
    @Test void ownEmptyAndExhaustedChildPagesSucceedButMissingInvoiceIs404(){
        long id=invoice(99);assertThat(service().items(99,0,50).items()).isEmpty();assertThat(service().corrections(99,0,50).corrections()).isEmpty();
        items(id,2);corrections(id,99,2);assertThat(service().items(99,1,50).items()).isEmpty();assertThat(service().corrections(99,1,50).corrections()).isEmpty();
        for(Runnable read:new Runnable[]{()->service().invoice(100),()->service().items(100,0,50),()->service().corrections(100,0,50),()->service().invoiceId(100),()->service().correctionId(100,1)})status(404,read);
    }
    @Test void mismatchedCorrectionOrderIsRejectedForJsonAndPdfGuard(){
        long id=invoice(99);corrections(id,100,1);status(503,()->service().corrections(99,0,50));status(503,()->service().correctionId(99,1));
    }
    @Test void pdfIdsMustBelongToTheAuthorizedInvoiceAndUnknownIdsDoNotPass(){
        long id=invoice(99),other=invoice(100);corrections(id,99,1);corrections(other,100,1);
        assertThat(service().invoiceId(99)).isEqualTo(id);assertThat(service().correctionId(99,1)).isEqualTo(1L);
        for(long candidate:new long[]{2,99999,0,-1})status(404,()->service().correctionId(99,candidate));
    }
    @Test void restrictedProjectionNeverSelectsLocalCustomerIdIdempotencyOrInboxOutbox(){
        long id=invoice(99);items(id,2);corrections(id,99,2);jdbc.execute("CREATE ROLE invoice_export LOGIN PASSWORD 'fixture-projection' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO invoice_export");
        jdbc.execute("GRANT SELECT (id,order_id,customer_email,invoice_number,billing_kind,buyer_name,company_name,tax_id,street,postal_code,city,country,net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at) ON invoices TO invoice_export");
        jdbc.execute("GRANT SELECT ON invoice_items TO invoice_export");
        jdbc.execute("GRANT SELECT (id,invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at,ledger_version) ON invoice_corrections TO invoice_export");
        var limitedSource=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"invoice_export","fixture-projection");var limited=new JdbcTemplate(limitedSource);
        for(var sql:List.of("SELECT customer_id FROM invoices","SELECT idempotency_key FROM invoice_corrections","SELECT * FROM invoice_outbox_events","SELECT * FROM event_inbox","SELECT * FROM invoice_sequences"))
            assertThatThrownBy(()->limited.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new InvoiceDataExportService(limitedSource,orders);assertThat(export.invoice(99).invoice().invoiceId()).isEqualTo(id);
        assertThat(export.items(99,0,50).items()).hasSize(2);assertThat(export.corrections(99,0,50).corrections()).hasSize(2);
        assertThat(export.invoiceId(99)).isEqualTo(id);assertThat(export.correctionId(99,1)).isEqualTo(1L);
    }
    @Test void invalidWindowsAreRejectedBeforeAuthorizationOrDatabaseQueries(){
        for(int size:new int[]{-1,0,51,Integer.MAX_VALUE}){status(400,()->service().items(99,0,size));status(400,()->service().corrections(99,0,size));}
        status(400,()->service().items(99,-1,1));status(400,()->service().corrections(99,-1,1));verifyNoInteractions(orders);
    }
    @Test void pagesDoNotClaimOneSnapshotWhenStoredRowsChange(){
        long id=invoice(99);corrections(id,99,3);var first=service().corrections(99,0,1);jdbc.update("DELETE FROM invoice_corrections WHERE id=2");
        var next=service().corrections(99,Long.parseLong(first.nextCursor()),50);assertThat(next.corrections()).extracting(InvoiceCorrectionDataExportPage.Correction::correctionId).containsExactly(1L);
        assertThat(next.omissions()).contains(new InvoiceDataExport.Omission("changesDuringPaginationAndOwnershipCheck","not-a-snapshot"));
    }
    @Test void everyProjectionLeavesDocumentsNumberingAndMessagesUnchanged(){
        long id=invoice(99);items(id,3);corrections(id,99,3);String before=snapshot();var export=service();export.invoice(99);export.items(99,0,50);export.corrections(99,0,50);export.invoiceId(99);export.correctionId(99,1);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_invoice_items_invoice','idx_invoice_corrections_window')",Integer.class)).isEqualTo(2);
    }
    private long invoice(long order){return jdbc.queryForObject("""
            INSERT INTO invoices(order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,
              net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at)
            VALUES (?,18,'same@fixture.example',?,'PERSON','Jan Żółć','Testowa 1','00-001','Warszawa','PL',100.37,23.08,123.45,12.34,2.50,'PLN','ISSUED',now()) RETURNING id
            """,Long.class,order,"FV/FIXTURE/"+order);}
    private void items(long id,int count){jdbc.update("INSERT INTO invoice_items(invoice_id,position_no,product_id,sku,product_name,quantity,unit_price,total_price) SELECT ?,1000-n,n,'SKU-'||n,'Produkt Żółć '||n,2,12.34,24.68 FROM generate_series(1,?) n",id,count);}
    private void corrections(long invoice,long order,int count){jdbc.update("""
            INSERT INTO invoice_corrections(invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,
              correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at,idempotency_key,ledger_version)
            SELECT ?,?,gen_random_uuid()::text,'Powód Żółć',100.37,23.08,123.45,-1.00,-0.23,-1.23,99.37,22.85,122.22,now(),
              CASE WHEN n=1 THEN NULL ELSE gen_random_uuid()::text END,CASE WHEN n=1 THEN NULL ELSE n END FROM generate_series(1,?) n
            """,invoice,order,count);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(i ORDER BY id) FROM invoices i),(SELECT json_agg(l ORDER BY id) FROM invoice_items l),(SELECT json_agg(c ORDER BY id) FROM invoice_corrections c),(SELECT json_agg(s ORDER BY series) FROM invoice_sequences s),(SELECT count(*) FROM invoice_outbox_events),(SELECT count(*) FROM event_inbox))::text",String.class);}
    private void status(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(expected));}
}
