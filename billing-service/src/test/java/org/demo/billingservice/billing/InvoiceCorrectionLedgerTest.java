package org.demo.billingservice.billing;

import org.demo.billingservice.web.*;
import org.demo.billingservice.messaging.InvoiceOutboxRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:correction-ledger;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class InvoiceCorrectionLedgerTest {
    @Autowired BillingService billing;
    @Autowired InvoiceIssuanceService issuance;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceCorrectionRepository corrections;
    @Autowired InvoiceOutboxRepository events;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void clear() { corrections.deleteAll();events.deleteAll();invoices.deleteAll();billing.clearPdfCache(); }
    private Long invoice(String gross) {
        var amount=new BigDecimal(gross);
        return issuance.issue(new IssueInvoiceRequest(1001L,42L,"ledger@example.test",amount,"PLN",BigDecimal.ZERO,BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L,"LEDGER","Product",1,amount,amount)),
                new BillingData(false,"Jan Test",null,null,"Street 1","00-001","City","PL"))).id();
    }
    private IssueCorrectionRequest command(String delta,String key,long version) {
        return new IssueCorrectionRequest("Rabat",new BigDecimal(delta),key,version);
    }
    @Test void twoDeltasAndRetriesHaveOneCumulativeBalanceInApiPdfAndOutbox() throws Exception {
        var id=invoice("100.00");var a=command("-10.00","correction-first",0);var b=command("-20.00","correction-second",1);
        var first=billing.issueCorrection(id,a);var second=billing.issueCorrection(id,b);
        assertThat(first.finalGross()).isEqualByComparingTo("90.00");
        assertThat(second.originalGross()).isEqualByComparingTo("90.00");assertThat(second.finalGross()).isEqualByComparingTo("70.00");
        assertThat(second.balanceVersion()).isEqualTo(2L);assertThat(second.balanceModel()).isEqualTo("CUMULATIVE_DELTA");
        assertThat(billing.issueCorrection(id,a).id()).isEqualTo(first.id());assertThat(billing.issueCorrection(id,b).id()).isEqualTo(second.id());
        assertThat(corrections.count()).isEqualTo(2);assertThat(events.count()).isEqualTo(3);
        var balance=billing.correctionBalance(id);assertThat(balance.grossAmount()).isEqualByComparingTo("70.00");
        assertThat(balance.netAmount().add(balance.vatAmount())).isEqualByComparingTo(balance.grossAmount());
        assertThat(billing.correctionTotals(List.of(id)).getFirst().getGrossDelta()).isEqualByComparingTo("-30.00");
        try(var document=Loader.loadPDF(billing.pdfCorrection(second.id()))) {
            var text=new PDFTextStripper().getText(document).replace('\u00a0',' ');
            assertThat(text).contains("90,00 PLN","70,00 PLN","Saldo rejestru po korekcie nr 2","Po tej korekcie");
        }
    }
    @Test void conflictingKeyOrStaleBalanceDoesNotAppendOrPublish() {
        var id=invoice("100.00");billing.issueCorrection(id,command("-10.00","correction-first",0));
        for(var invalid:List.of(command("-11.00","correction-first",0),command("-10.00","correction-first",1),command("-20.00","correction-second",0),
                new IssueCorrectionRequest("Inny powód",new BigDecimal("-10"),"correction-first",0L)))
            assertThatThrownBy(() -> billing.issueCorrection(id,invalid)).hasMessageContaining("409");
        assertThat(corrections.count()).isEqualTo(1);assertThat(events.count()).isEqualTo(2);
    }
    @Test void monetaryPrecisionReasonBoundsAndNegativeFinalBalanceAreRejectedBeforeWriting() {
        var id=invoice("100.00");
        for(var delta:List.of("0.00","-100.01","0.001","100000000000000000.00"))
            assertThatThrownBy(() -> billing.issueCorrection(id,command(delta,"invalid-amount",0))).hasMessageContaining("400");
        for(var reason:List.of(" ","a".repeat(256),"line\nbreak"))
            assertThatThrownBy(() -> billing.issueCorrection(id,new IssueCorrectionRequest(reason,BigDecimal.ONE,"invalid-reason",0L))).hasMessageContaining("400");
        assertThatThrownBy(() -> billing.issueCorrection(id,new IssueCorrectionRequest("Rabat",BigDecimal.ONE,null,0L))).hasMessageContaining("400");
        assertThatThrownBy(() -> billing.issueCorrection(id,new IssueCorrectionRequest("Rabat",BigDecimal.ONE,"missing-version",null))).hasMessageContaining("400");
        assertThat(corrections.count()).isZero();assertThat(events.count()).isEqualTo(1);
    }
    @Test void fullReductionEndsWithZeroNetVatAndGrossWithoutRoundingDrift() {
        var id=invoice("0.03");
        for(int i=0;i<3;i++) billing.issueCorrection(id,command("-0.01","cent-correction-"+i,i));
        var balance=billing.correctionBalance(id);
        assertThat(balance.netAmount()).isZero();assertThat(balance.vatAmount()).isZero();assertThat(balance.grossAmount()).isZero();
    }
    @Test void historicalSnapshotsRemainImmutableButTheirDeltasAreIncludedInTheBalance() {
        var id=invoice("100.00");var base=invoices.findById(id).orElseThrow();
        for(int n:List.of(10,20)) {
            var delta=new BigDecimal(-n);var net=delta.divide(new BigDecimal("1.23"),2,java.math.RoundingMode.HALF_UP);
            corrections.saveAndFlush(new InvoiceCorrection(base,"OLD/"+n,"Historyczna",base.getNetAmount(),base.getVatAmount(),base.getGrossAmount(),
                    net,delta.subtract(net),delta,base.getNetAmount().add(net),base.getVatAmount().add(delta.subtract(net)),base.getGrossAmount().add(delta)));
        }
        var historical=corrections.findAll().getLast();
        assertThat(historical.getFinalGross()).isEqualByComparingTo("80.00");
        var third=billing.issueCorrection(id,command("-5.00","correction-third",2));
        assertThat(third.originalGross()).isEqualByComparingTo("70.00");assertThat(third.finalGross()).isEqualByComparingTo("65.00");
        assertThat(billing.getCorrection(historical.getId()).balanceModel()).isEqualTo("LEGACY_BASE_DELTA");
        assertThat(billing.getCorrection(historical.getId()).finalGross()).isEqualByComparingTo("80.00");
        assertThat(billing.correctionBalance(id).legacyCorrections()).isEqualTo(2);
    }
    @Test void concurrentIdenticalRequestsCreateOneCorrectionAndEvent() throws Exception {
        var id=invoice("100.00");var command=command("-10.00","concurrent-retry",0);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<Long>>();
            for(int n=0;n<6;n++) futures.add(pool.submit(() -> {start.await();return billing.issueCorrection(id,command).id();}));
            start.countDown();var ids=new HashSet<Long>();for(var future:futures) ids.add(future.get(15,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(corrections.count()).isEqualTo(1);assertThat(events.count()).isEqualTo(2);
    }
    @Test void concurrentDistinctCorrectionsRequireTheLoserToReadTheNewBalance() throws Exception {
        var id=invoice("100.00");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Boolean>>();
            for(int n=0;n<2;n++) { final int index=n;results.add(pool.submit(() -> {
                start.await();try {billing.issueCorrection(id,command("-10.00","distinct-key-"+index,0));return true;}
                catch(org.springframework.web.server.ResponseStatusException rejected) {assertThat(rejected.getStatusCode().value()).isEqualTo(409);return false;}
            })); }
            start.countDown();int count=0;for(var future:results) if(future.get(15,TimeUnit.SECONDS)) count++;assertThat(count).isEqualTo(1);
        }
        assertThat(billing.correctionBalance(id).version()).isEqualTo(1);
        assertThat(billing.correctionBalance(id).grossAmount()).isEqualByComparingTo("90.00");
    }
    @Test void rollbackDoesNotConsumeTheCommandOrPublishAnEvent() {
        var id=invoice("100.00");var command=command("-10.00","rollback-correction",0);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            billing.issueCorrection(id,command);throw new IllegalStateException("rollback fixture");
        })).hasMessageContaining("rollback fixture");
        assertThat(corrections.count()).isZero();assertThat(events.count()).isEqualTo(1);
        assertThat(billing.issueCorrection(id,command).balanceVersion()).isEqualTo(1L);
    }
}
