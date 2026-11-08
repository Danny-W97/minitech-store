package org.demo.billingservice;

import jakarta.validation.Validator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.demo.billingservice.billing.BillingService;
import org.demo.billingservice.billing.InvoiceRepository;
import org.demo.billingservice.web.BillingData;
import org.demo.billingservice.web.InvoiceItemRequest;
import org.demo.billingservice.web.IssueInvoiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BillingServiceApplicationTests {
    @Autowired
    private BillingService billingService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.demo.billingservice.billing.BillingOrderClient orders;

    private org.demo.billingservice.web.InvoiceResponse issueFromPaidOrder(IssueInvoiceRequest request) {
        org.mockito.Mockito.when(orders.get(request.orderId())).thenReturn(paidOrder(request, "PAID"));
        return billingService.issue(request);
    }

    private org.demo.billingservice.billing.BillingOrderClient.PaidOrder paidOrder(IssueInvoiceRequest request, String status) {
        return new org.demo.billingservice.billing.BillingOrderClient.PaidOrder(request.orderId(), request.customerId(),
                request.customerEmail(), status, request.grossAmount(), request.deliveryCost(), request.discountAmount(),
                request.items().stream().map(item -> new org.demo.billingservice.billing.BillingOrderClient.Item(
                        item.productId(), item.sku(), item.productName(), item.quantity(), item.unitPrice(), item.totalPrice())).toList());
    }

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private org.demo.billingservice.billing.InvoiceCorrectionRepository invoiceCorrectionRepository;

    @Autowired
    private Validator validator;

    @Autowired private org.demo.billingservice.billing.InvoiceExportReader exportReader;
    @Autowired private org.demo.billingservice.billing.PdfDocumentCache documentCache;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void setUp() {
        billingService.clearPdfCache();
        invoiceCorrectionRepository.deleteAll();
        invoiceRepository.deleteAll();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void rejectedVerificationDoesNotPersistAnInvoice() {
        var request = new IssueInvoiceRequest(9001L, null, "verify@example.test", new BigDecimal("12.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "VERIFY", "Produkt", 1, new BigDecimal("12.00"), new BigDecimal("12.00"))),
                new BillingData(false, "Test", null, null, "Ulica 1", "00-001", "Miasto", "Polska"));
        for (String status : List.of("CONFIRMED", "CANCELLED")) {
            org.mockito.Mockito.when(orders.get(9001L)).thenReturn(paidOrder(request, status));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingService.issue(request)).hasMessageContaining("409");
            assertThat(invoiceRepository.count()).isZero();
        }
        var forged = new IssueInvoiceRequest(request.orderId(), request.customerId(), request.customerEmail(),
                new BigDecimal("0.01"), "PLN", BigDecimal.ZERO, BigDecimal.ZERO, request.items(), request.billingData());
        org.mockito.Mockito.when(orders.get(9001L)).thenReturn(paidOrder(request, "PAID"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> billingService.issue(forged)).hasMessageContaining("400");
        assertThat(invoiceRepository.count()).isZero();
    }

    @Test
    void concurrentRetriesReturnOneInvoice() throws Exception {
        var request = new IssueInvoiceRequest(9002L, null, "retry@example.test", new BigDecimal("12.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "RETRY", "Produkt", 1, new BigDecimal("12.00"), new BigDecimal("12.00"))),
                new BillingData(false, "Test", null, null, "Ulica 1", "00-001", "Miasto", "Polska"));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        org.mockito.Mockito.when(orders.get(9002L)).thenAnswer(invocation -> {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return paidOrder(request, "PAID");
        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> billingService.issue(request));
            var second = executor.submit(() -> billingService.issue(request));
            assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(15, java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void calculatesNetVatAndGrossAndIsIdempotentForAnOrder() {
        var request = new IssueInvoiceRequest(
                601L,
                null,
                "guest@example.test",
                new BigDecimal("123.00"),
                "PLN",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "TEST-1", "Produkt testowy", 1,
                        new BigDecimal("123.00"), new BigDecimal("123.00"))),
                new BillingData(
                        false,
                        "Jan Kowalski",
                        null,
                        null,
                        "Testowa 1",
                        "00-001",
                        "Warszawa",
                        "Polska"
                )
        );

        var first = issueFromPaidOrder(request);
        var repeated = issueFromPaidOrder(request);

        assertThat(first.id()).isEqualTo(repeated.id());
        assertThat(first.grossAmount()).isEqualByComparingTo("123.00");
        assertThat(first.netAmount()).isEqualByComparingTo("100.00");
        assertThat(first.vatAmount()).isEqualByComparingTo("23.00");
        assertThat(first.invoiceNumber()).startsWith("FV/");
        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo("Produkt testowy");
            assertThat(item.quantity()).isEqualTo(1);
        });
        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void generatesPdfForIssuedInvoice() throws Exception {
        var request = new IssueInvoiceRequest(
                602L,
                null,
                "pdf@example.test",
                new BigDecimal("12429.55"),
                "PLN",
                new BigDecimal("11.99"),
                BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(2L, "POLSKI-2", "Żółta mysz Łódź", 2,
                        new BigDecimal("6208.78"), new BigDecimal("12417.56"))),
                new BillingData(
                        false,
                        "Łukasz Żółć",
                        null,
                        null,
                        "Żółkiewskiego 2",
                        "00-002",
                        "Łomża",
                        "Polska"
                )
        );

        var invoice = issueFromPaidOrder(request);
        var pdf = billingService.pdf(invoice.id());

        assertThat(pdf).startsWith(new byte[]{'%','P','D','F','-'});
        try (var document = Loader.loadPDF(pdf)) {
            var text = new PDFTextStripper().getText(document).replace('\u00a0', ' ');
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(text).contains(
                    "MiniTech Store",
                    "FAKTURA VAT",
                    "pdf@example.test",
                    "Łukasz Żółć",
                    "Żółkiewskiego 2",
                    "Łomża",
                    "ZAMÓWIENIE",
                    "ZAKUPIONE PRODUKTY",
                    "Żółta mysz Łódź",
                    "ILOŚĆ",
                    "6 208,78 PLN",
                    "12 417,56 PLN",
                    "Dostawa: 11,99 PLN",
                    "Do zapłaty brutto",
                    "12 429,55 PLN"
            );
        }

        String previewPath = System.getProperty("billing.pdf.preview.path");
        if (previewPath != null && !previewPath.isBlank()) {
            Path path = Path.of(previewPath);
            Files.createDirectories(path.getParent());
            Files.write(path, pdf);
        }
    }

    @Test
    void rejectsCompanyInvoiceWithNineDigitTaxId() {
        var data = new BillingData(
                true,
                "Jan Kowalski",
                "MiniTech Test sp. z o.o.",
                "123456789",
                "Testowa 1",
                "00-001",
                "Warszawa",
                "Polska"
        );

        assertThat(validator.validate(data))
                .extracting(violation -> violation.getMessage())
                .anyMatch(message -> message.contains("10 cyfr") || message.contains("10-cyfrowego"));
    }

    @Test
    void acceptsCompanyInvoiceWithTenDigitTaxId() {
        var data = new BillingData(
                true,
                "Jan Kowalski",
                "MiniTech Test sp. z o.o.",
                "1234567890",
                "Testowa 1",
                "00-001",
                "Warszawa",
                "Polska"
        );

        assertThat(validator.validate(data)).isEmpty();
    }

    @Test
    void listsAndSearchesInvoices() {
        var request = new IssueInvoiceRequest(
                701L,
                99L,
                "szukaj@example.test",
                new BigDecimal("250.00"),
                "PLN",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(10L, "SZUKAJ-1", "Klawiatura Mechaniczna", 1,
                        new BigDecimal("250.00"), new BigDecimal("250.00"))),
                new BillingData(
                        true,
                        "Adam Nowak",
                        "Nowak IT Solutions",
                        "5250001099",
                        "Główna 15",
                        "00-123",
                        "Gdańsk",
                        "Polska"
                )
        );
        issueFromPaidOrder(request);

        var all = billingService.list(null, 0, 50);
        assertThat(all).isNotEmpty();
        assertThat(billingService.count(null)).isGreaterThanOrEqualTo(1);

        var byName = billingService.list("Nowak", 0, 10);
        assertThat(byName).anyMatch(inv -> "Adam Nowak".equals(inv.buyerName()));

        var byTaxId = billingService.list("5250001099", 0, 10);
        assertThat(byTaxId).anyMatch(inv -> "5250001099".equals(inv.taxId()));

        var byOrderId = billingService.list("701", 0, 10);
        assertThat(byOrderId).anyMatch(inv -> Long.valueOf(701L).equals(inv.orderId()));
    }

    @Test
    void cachesPdfBytesInRam() {
        var request = new IssueInvoiceRequest(
                702L,
                null,
                "cache@example.test",
                new BigDecimal("99.99"),
                "PLN",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(11L, "CACHE-1", "Podkładka pod mysz", 1,
                        new BigDecimal("99.99"), new BigDecimal("99.99"))),
                new BillingData(
                        false,
                        "Piotr Cache",
                        null,
                        null,
                        "Szybka 1",
                        "01-100",
                        "Kraków",
                        "Polska"
                )
        );
        var invoice = issueFromPaidOrder(request);

        byte[] firstCall = billingService.pdf(invoice.id());
        byte[] secondCall = billingService.pdf(invoice.id());

        // Should return exact same cached array reference
        assertThat(secondCall).containsExactly(firstCall).isNotSameAs(firstCall);
    }

    @Test
    void issuesInvoiceWithContinuousSequentialNumber() {
        var request1 = new IssueInvoiceRequest(
                801L, null, "seq1@example.test", new BigDecimal("100.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "ITEM-1", "Towar 1", 1, new BigDecimal("100.00"), new BigDecimal("100.00"))),
                new BillingData(false, "Klient 1", null, null, "Ulica 1", "00-001", "Miasto", "Polska")
        );
        var request2 = new IssueInvoiceRequest(
                802L, null, "seq2@example.test", new BigDecimal("150.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(2L, "ITEM-2", "Towar 2", 1, new BigDecimal("150.00"), new BigDecimal("150.00"))),
                new BillingData(false, "Klient 2", null, null, "Ulica 2", "00-002", "Miasto", "Polska")
        );

        var inv1 = issueFromPaidOrder(request1);
        var inv2 = issueFromPaidOrder(request2);

        assertThat(inv1.invoiceNumber()).matches("FV/\\d{4}/\\d{6}");
        assertThat(inv2.invoiceNumber()).matches("FV/\\d{4}/\\d{6}");
        assertThat(inv1.invoiceNumber()).isNotEqualTo(inv2.invoiceNumber());
    }

    @Test
    void issuesCorrectionInvoiceAndCalculatesDeltas() throws Exception {
        var invoiceReq = new IssueInvoiceRequest(
                803L, null, "corr@example.test", new BigDecimal("246.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "ITEM-C", "Produkt A", 2, new BigDecimal("123.00"), new BigDecimal("246.00"))),
                new BillingData(true, "Firma ABC", "ABC Sp. z o.o.", "1234567890", "Biznesowa 10", "01-001", "Warszawa", "Polska")
        );
        var invoice = issueFromPaidOrder(invoiceReq);

        var corrReq = new org.demo.billingservice.web.IssueCorrectionRequest(
                "Zwrot 1 szt. towaru uszkodzonego",
                new BigDecimal("-123.00"),"correction-test-803",0L
        );

        var correction = billingService.issueCorrection(invoice.id(), corrReq);

        assertThat(correction.correctionNumber()).matches("KOR/\\d{4}/\\d{6}");
        assertThat(correction.originalGross()).isEqualByComparingTo("246.00");
        assertThat(correction.correctionGross()).isEqualByComparingTo("-123.00");
        assertThat(correction.finalGross()).isEqualByComparingTo("123.00");
        assertThat(correction.reason()).isEqualTo("Zwrot 1 szt. towaru uszkodzonego");

        // Verify PDF can be generated and is valid
        byte[] pdfBytes = billingService.pdfCorrection(correction.id());
        assertThat(pdfBytes).isNotEmpty();
        try (var doc = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("FAKTURA KORYGUJĄCA");
            assertThat(text).contains(correction.correctionNumber());
            assertThat(text).contains("PRZYCZYNA KOREKTY");
            assertThat(text).containsIgnoringCase("Zwrot 1 szt. towaru uszkodzonego");
        }

        // Verify retrieval
        var list = billingService.getCorrections(invoice.id());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(correction.id());
    }

    @Test
    void exportsZipArchiveWithInvoices() throws Exception {
        var invoiceReq = new IssueInvoiceRequest(
                999L, null, "zip@example.test", new BigDecimal("50.00"), "PLN",
                BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new InvoiceItemRequest(1L, "ZIP-1", "Kabel USB", 1, new BigDecimal("50.00"), new BigDecimal("50.00"))),
                new BillingData(false, "Zip Tester", null, null, "Zipowa 1", "00-001", "Miasto", "Polska")
        );
        issueFromPaidOrder(invoiceReq);

        var out = new java.io.ByteArrayOutputStream();
        billingService.exportZip(null, null, out);
        byte[] zipBytes = out.toByteArray();

        assertThat(zipBytes).isNotEmpty();
        int entriesCount = 0;
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entriesCount++;
                assertThat(entry.getName()).endsWith(".pdf");
                byte[] entryBytes = zis.readAllBytes();
                assertThat(new String(entryBytes, 0, Math.min(entryBytes.length, 4))).isEqualTo("%PDF");
                zis.closeEntry();
            }
        }
        assertThat(entriesCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void exportFiltersDatesInDatabaseKeepsTheInteractiveCacheAndLoadsOnlyItsWindow() throws Exception {
        var ids = new java.util.ArrayList<Long>();
        for (long orderId=6000; orderId<6004; orderId++) {
            var request = new IssueInvoiceRequest(orderId, null, "export@example.test", new BigDecimal("50"), "PLN",
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of(new InvoiceItemRequest(1L,"EXPORT","Export",1,new BigDecimal("50"),new BigDecimal("50"))),
                    new BillingData(false,"Export",null,null,"Street","00-001","City","PL"));
            ids.add(issueFromPaidOrder(request).id());
        }
        var from = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var to = java.time.Instant.parse("2026-01-02T00:00:00Z");
        for (int i=0;i<ids.size();i++) jdbc.update("update invoices set created_at=? where id=?", java.sql.Timestamp.from(from.plusSeconds((i-1)*86400L)), ids.get(i));
        billingService.pdf(ids.getFirst()); long cachedBytes = documentCache.bytes();
        var stats=entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        var output=new java.io.ByteArrayOutputStream(); billingService.exportZip(from,to,output);
        assertThat(documentCache.bytes()).isEqualTo(cachedBytes);
        assertThat(stats.getEntityLoadCount()).isEqualTo(4); // two invoices plus two line items
        int count=0;
        try (var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            while (zip.getNextEntry()!=null) { assertThat(zip.readAllBytes()).startsWith("%PDF".getBytes()); count++; }
        }
        assertThat(count).isEqualTo(2);
        assertThat(exportReader.plan(from,to,2).count()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exportReader.plan(from,to,1)).hasMessageContaining("413");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exportReader.plan(to,from,10)).hasMessageContaining("400");
        assertThat(exportReader.plan(to.plusSeconds(100000), null,10).count()).isZero();
        stats.setStatisticsEnabled(false);
    }
}
