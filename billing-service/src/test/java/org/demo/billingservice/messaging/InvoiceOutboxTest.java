package org.demo.billingservice.messaging;

import org.demo.billingservice.billing.*;
import org.demo.billingservice.web.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a32invoice;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","outbox.publisher.enabled=false","messaging.consumer.enabled=false"})
class InvoiceOutboxTest {
    @Autowired InvoiceIssuanceService issuance;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceOutboxRepository events;
    @Autowired InvoiceOutboxStateStore states;
    @Autowired InvoiceEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @BeforeEach void clear() { events.deleteAll();invoices.deleteAll(); }
    private IssueInvoiceRequest request() { return new IssueInvoiceRequest(71L,null,"owner@example.test",BigDecimal.TEN,"PLN",BigDecimal.ZERO,BigDecimal.ZERO,
        List.of(new InvoiceItemRequest(1L,"SKU","Product",1,BigDecimal.TEN,BigDecimal.TEN)),new BillingData(false,"Buyer",null,null,"Street","00-001","City","PL")); }
    @Test void invoiceAndItsEventCommitOrRollBackTogether() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> { issuance.issue(request());status.setRollbackOnly(); });
        assertThat(invoices.count()).isZero();assertThat(events.count()).isZero();
        var invoice=issuance.issue(request());issuance.issue(request());
        assertThat(invoices.count()).isEqualTo(1);assertThat(events.count()).isEqualTo(1);
        var event=events.findAll().getFirst();assertThat(event.fields()).containsEntry("invoiceId",invoice.id().toString());
        assertThat(event.fields()).containsEntry("eventId",event.getEventId());
        assertThatThrownBy(() -> publisher.publishInvoiceIssued(invoices.findById(invoice.id()).orElseThrow())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void publicationClaimsFenceExpiredWorkers() {
        issuance.issue(request());var event=events.findAll().getFirst();var first=states.claim(event.getId());assertThat(states.claim(event.getId())).isNull();
        jdbc.update("update invoice_outbox_events set processing_until=? where id=?",java.sql.Timestamp.from(Instant.EPOCH),event.getId());
        var second=states.claim(event.getId());states.complete(first,true);
        assertThat(events.findById(event.getId()).orElseThrow().getStatus()).isEqualTo(InvoiceOutboxStatus.PENDING);
        states.complete(second,true);assertThat(states.claim(event.getId())).isNull();
    }
}
