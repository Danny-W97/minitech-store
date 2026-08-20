package org.demo.billingservice;

import jakarta.validation.Validator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.demo.billingservice.billing.BillingService;
import org.demo.billingservice.billing.InvoiceRepository;
import org.demo.billingservice.web.BillingData;
import org.demo.billingservice.web.IssueInvoiceRequest;
import org.demo.billingservice.web.InvoiceItemRequest;
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
    private Validator validator;

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
}
