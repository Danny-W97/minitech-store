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

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private org.demo.billingservice.billing.InvoiceCorrectionRepository invoiceCorrectionRepository;

    @Autowired
    private Validator validator;

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

        var first = billingService.issue(request);
        var repeated = billingService.issue(request);

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
                        new BigDecimal("44.00"), new BigDecimal("88.00"))),
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

        var invoice = billingService.issue(request);
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
                    "44,00 PLN",
                    "88,00 PLN",
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
        billingService.issue(request);

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
        var invoice = billingService.issue(request);

        byte[] firstCall = billingService.pdf(invoice.id());
        byte[] secondCall = billingService.pdf(invoice.id());

        // Should return exact same cached array reference
        assertThat(secondCall).isSameAs(firstCall);
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

        var inv1 = billingService.issue(request1);
        var inv2 = billingService.issue(request2);

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
        var invoice = billingService.issue(invoiceReq);

        var corrReq = new org.demo.billingservice.web.IssueCorrectionRequest(
                "Zwrot 1 szt. towaru uszkodzonego",
                new BigDecimal("-123.00")
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
        billingService.issue(invoiceReq);

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
}
