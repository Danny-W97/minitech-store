package org.demo.billingservice.billing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.Color;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Generates a branded, Unicode PDF representation of an issued invoice. */
@Component
public class InvoicePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("pl-PL"))
            .withZone(ZoneId.systemDefault());

    private static final int NAVY_R = 19;
    private static final int NAVY_G = 32;
    private static final int NAVY_B = 51;
    private static final int ORANGE_R = 255;
    private static final int ORANGE_G = 91;
    private static final int ORANGE_B = 61;
    private static final int MUTED_R = 102;
    private static final int MUTED_G = 115;
    private static final int MUTED_B = 137;
    private static final int BORDER_R = 220;
    private static final int BORDER_G = 226;
    private static final int BORDER_B = 234;
    private static final int FIRST_PAGE_ITEM_LIMIT = 4;
    private static final int CONTINUATION_ITEM_LIMIT = 17;

    private final String configuredRegularFont;
    private final String configuredBoldFont;
    private volatile byte[] cachedRegularFont;
    private volatile byte[] cachedBoldFont;

    public InvoicePdfService(
            @Value("${billing.pdf.font.regular:}") String configuredRegularFont,
            @Value("${billing.pdf.font.bold:}") String configuredBoldFont
    ) {
        this.configuredRegularFont = configuredRegularFont;
        this.configuredBoldFont = configuredBoldFont;
    }

    public byte[] generate(Invoice invoice) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadRegularFont(document);
            PDType0Font bold = loadBoldFont(document);

            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle("Faktura " + invoice.getInvoiceNumber());
            information.setAuthor("MiniTech Store");
            information.setSubject("Faktura VAT dla zamówienia #" + invoice.getOrderId());
            information.setCreator("MiniTech Store Billing Service");
            document.setDocumentInformation(information);

            List<InvoiceItem> items = invoice.getItems();
            int remaining = Math.max(0, items.size() - FIRST_PAGE_ITEM_LIMIT);
            int totalPages = 1 + (remaining + CONTINUATION_ITEM_LIMIT - 1) / CONTINUATION_ITEM_LIMIT;
            int itemIndex = 0;

            PDPage firstPage = new PDPage(PDRectangle.A4);
            document.addPage(firstPage);
            try (PDPageContentStream canvas = new PDPageContentStream(document, firstPage)) {
                drawHeader(canvas, regular, bold, invoice);
                drawMetadata(canvas, regular, bold, invoice);
                drawParties(canvas, regular, bold, invoice);
                int firstPageEnd = Math.min(items.size(), FIRST_PAGE_ITEM_LIMIT);
                drawItemsTable(canvas, regular, bold, items, itemIndex, firstPageEnd,
                        invoice.getCurrency(), 414, 378, 360);
                itemIndex = firstPageEnd;
                if (totalPages == 1) {
                    drawTotals(canvas, regular, bold, invoice, 238);
                } else {
                    text(canvas, regular, 8.5f, 48, 238, MUTED_R, MUTED_G, MUTED_B,
                            "Dalsze pozycje znajdują się na kolejnej stronie.");
                }
                drawFooter(canvas, regular, invoice, 1, totalPages);
            }

            for (int pageNumber = 2; pageNumber <= totalPages; pageNumber++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
                    drawContinuationHeader(canvas, regular, bold, invoice);
                    int pageEnd = Math.min(items.size(), itemIndex + CONTINUATION_ITEM_LIMIT);
                    drawItemsTable(canvas, regular, bold, items, itemIndex, pageEnd,
                            invoice.getCurrency(), 728, 690, 672);
                    itemIndex = pageEnd;
                    if (pageNumber == totalPages) {
                        drawTotals(canvas, regular, bold, invoice, 238);
                    } else {
                        text(canvas, regular, 8.5f, 48, 238, MUTED_R, MUTED_G, MUTED_B,
                                "Dalsze pozycje znajdują się na kolejnej stronie.");
                    }
                    drawFooter(canvas, regular, invoice, pageNumber, totalPages);
                }
            }

            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udało się wygenerować PDF dla faktury " + invoice.getInvoiceNumber(), exception);
        }
    }

    public byte[] generateCorrection(InvoiceCorrection correction, Invoice invoice) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadRegularFont(document);
            PDType0Font bold = loadBoldFont(document);

            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle("Korekta " + correction.getCorrectionNumber());
            information.setAuthor("MiniTech Store");
            information.setSubject("Faktura VAT korygująca dla zamówienia #" + correction.getOrderId());
            information.setCreator("MiniTech Store Billing Service");
            document.setDocumentInformation(information);

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
                drawCorrectionHeader(canvas, regular, bold, correction, invoice);
                drawParties(canvas, regular, bold, invoice);
                drawCorrectionDetails(canvas, regular, bold, correction, invoice);
                drawFooter(canvas, regular, invoice, 1, 1);
            }

            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Nie udało się wygenerować PDF faktury korygującej " + correction.getCorrectionNumber(), exception);
        }
    }

    private void drawCorrectionHeader(PDPageContentStream canvas, PDFont regular, PDFont bold,
                                      InvoiceCorrection correction, Invoice invoice) throws IOException {
        fillRect(canvas, 0, 716, PDRectangle.A4.getWidth(), 126, NAVY_R, NAVY_G, NAVY_B);
        fillRect(canvas, 48, 754, 48, 48, ORANGE_R, ORANGE_G, ORANGE_B);
        text(canvas, bold, 13, 56, 771, 255, 255, 255, "KOR");
        text(canvas, bold, 18, 110, 782, 255, 255, 255, "MiniTech Store");
        text(canvas, regular, 8.5f, 110, 763, 196, 205, 217, "Faktura VAT korygująca");

        text(canvas, bold, 19, 310, 783, 255, 255, 255, "FAKTURA KORYGUJĄCA");
        rightText(canvas, regular, 10, 547, 761, 211, 218, 228, correction.getCorrectionNumber());

        fillRect(canvas, 48, 653, 499, 45, 247, 249, 252);
        strokeRect(canvas, 48, 653, 499, 45, BORDER_R, BORDER_G, BORDER_B);

        smallLabel(canvas, bold, 62, 679, "DATA WYSTAWIENIA KOREKTY");
        text(canvas, bold, 10, 62, 663, NAVY_R, NAVY_G, NAVY_B, DATE.format(correction.getCreatedAt()));
        smallLabel(canvas, bold, 230, 679, "DO FAKTURY PIERWOTNEJ");
        text(canvas, bold, 10, 230, 663, NAVY_R, NAVY_G, NAVY_B, invoice.getInvoiceNumber());
        smallLabel(canvas, bold, 395, 679, "ZAMÓWIENIE");
        text(canvas, bold, 10, 395, 663, NAVY_R, NAVY_G, NAVY_B, "#" + correction.getOrderId());

        fillRect(canvas, 48, 616, 499, 23, 255, 242, 237);
        text(canvas, bold, 8.5f, 60, 624, ORANGE_R, ORANGE_G, ORANGE_B,
                "PRZYCZYNA KOREKTY: " + correction.getReason().toUpperCase());
    }

    private void drawCorrectionDetails(PDPageContentStream canvas, PDFont regular, PDFont bold,
                                       InvoiceCorrection c, Invoice invoice) throws IOException {
        float titleY = 414;
        sectionTitle(canvas, bold, 48, titleY, "ROZLICZENIE KWOT KOREKTY");

        float headerY = 378;
        fillRect(canvas, 48, headerY, 499, 24, NAVY_R, NAVY_G, NAVY_B);
        text(canvas, bold, 7.2f, 60, headerY + 8, 255, 255, 255, "POZYCJA ROZLICZENIA");
        rightText(canvas, bold, 7.2f, 260, headerY + 8, 255, 255, 255, "WARTOŚĆ NETTO");
        rightText(canvas, bold, 7.2f, 340, headerY + 8, 255, 255, 255, "STAWKA");
        rightText(canvas, bold, 7.2f, 430, headerY + 8, 255, 255, 255, "KWOTA VAT");
        rightText(canvas, bold, 7.2f, 533, headerY + 8, 255, 255, 255, "WARTOŚĆ BRUTTO");

        String curr = invoice.getCurrency();

        // Row 1: Przed korektą
        float row1Y = 355;
        text(canvas, regular, 8.5f, 60, row1Y, NAVY_R, NAVY_G, NAVY_B, "Przed korektą");
        rightText(canvas, regular, 8.5f, 260, row1Y, NAVY_R, NAVY_G, NAVY_B, money(c.getOriginalNet()) + " " + curr);
        rightText(canvas, regular, 8.5f, 340, row1Y, NAVY_R, NAVY_G, NAVY_B, "23%");
        rightText(canvas, regular, 8.5f, 430, row1Y, NAVY_R, NAVY_G, NAVY_B, money(c.getOriginalVat()) + " " + curr);
        rightText(canvas, regular, 8.5f, 533, row1Y, NAVY_R, NAVY_G, NAVY_B, money(c.getOriginalGross()) + " " + curr);

        // Row 2: Różnica / Korekta
        float row2Y = 330;
        fillRect(canvas, 48, row2Y - 7, 499, 22, 255, 242, 237);
        text(canvas, bold, 8.5f, 60, row2Y, ORANGE_R, ORANGE_G, ORANGE_B, "Korekta (różnica)");
        rightText(canvas, bold, 8.5f, 260, row2Y, ORANGE_R, ORANGE_G, ORANGE_B, money(c.getCorrectionNet()) + " " + curr);
        rightText(canvas, bold, 8.5f, 340, row2Y, ORANGE_R, ORANGE_G, ORANGE_B, "23%");
        rightText(canvas, bold, 8.5f, 430, row2Y, ORANGE_R, ORANGE_G, ORANGE_B, money(c.getCorrectionVat()) + " " + curr);
        rightText(canvas, bold, 8.5f, 533, row2Y, ORANGE_R, ORANGE_G, ORANGE_B, money(c.getCorrectionGross()) + " " + curr);

        // Row 3: Po korekcie
        float row3Y = 305;
        text(canvas, bold, 8.5f, 60, row3Y, NAVY_R, NAVY_G, NAVY_B, "Po korekcie (stan obecny)");
        rightText(canvas, bold, 8.5f, 260, row3Y, NAVY_R, NAVY_G, NAVY_B, money(c.getFinalNet()) + " " + curr);
        rightText(canvas, bold, 8.5f, 340, row3Y, NAVY_R, NAVY_G, NAVY_B, "23%");
        rightText(canvas, bold, 8.5f, 430, row3Y, NAVY_R, NAVY_G, NAVY_B, money(c.getFinalVat()) + " " + curr);
        rightText(canvas, bold, 8.5f, 533, row3Y, NAVY_R, NAVY_G, NAVY_B, money(c.getFinalGross()) + " " + curr);

        // Totals settlement box
        float boxY = 220;
        card(canvas, 48, boxY, 499, 65);
        boolean isRefund = c.getCorrectionGross().compareTo(BigDecimal.ZERO) < 0;
        String settlementLabel = isRefund ? "DO ZWROTU DLA NABYWCY" : "DO ZAPŁATY PRZEZ NABYWCĘ";
        BigDecimal settlementAmount = c.getCorrectionGross().abs();

        smallLabel(canvas, bold, 62, boxY + 45, settlementLabel);
        text(canvas, bold, 18, 62, boxY + 18, isRefund ? 0 : NAVY_R, isRefund ? 133 : NAVY_G, isRefund ? 112 : NAVY_B,
                money(settlementAmount) + " " + curr);

        rightText(canvas, regular, 8.5f, 533, boxY + 45, MUTED_R, MUTED_G, MUTED_B, "Forma rozliczenia: Przelew bankowy");
        rightText(canvas, regular, 8.5f, 533, boxY + 28, MUTED_R, MUTED_G, MUTED_B, "Termin rozliczenia: 14 dni");
    }

    private void drawHeader(PDPageContentStream canvas, PDFont regular, PDFont bold, Invoice invoice) throws IOException {
        fillRect(canvas, 0, 716, PDRectangle.A4.getWidth(), 126, NAVY_R, NAVY_G, NAVY_B);
        fillRect(canvas, 48, 754, 48, 48, ORANGE_R, ORANGE_G, ORANGE_B);
        text(canvas, bold, 15, 60, 771, 255, 255, 255, "MP");
        text(canvas, bold, 18, 110, 782, 255, 255, 255, "MiniTech Store");
        text(canvas, regular, 8.5f, 110, 763, 196, 205, 217, "Mini Order Platform - sklep demonstracyjny");

        text(canvas, bold, 23, 360, 783, 255, 255, 255, "FAKTURA VAT");
        rightText(canvas, regular, 10, 547, 761, 211, 218, 228, invoice.getInvoiceNumber());
    }

    private void drawMetadata(PDPageContentStream canvas, PDFont regular, PDFont bold, Invoice invoice) throws IOException {
        fillRect(canvas, 48, 653, 499, 45, 247, 249, 252);
        strokeRect(canvas, 48, 653, 499, 45, BORDER_R, BORDER_G, BORDER_B);

        smallLabel(canvas, bold, 62, 679, "DATA WYSTAWIENIA");
        text(canvas, bold, 10, 62, 663, NAVY_R, NAVY_G, NAVY_B, DATE.format(invoice.getCreatedAt()));
        smallLabel(canvas, bold, 230, 679, "ZAMÓWIENIE");
        text(canvas, bold, 10, 230, 663, NAVY_R, NAVY_G, NAVY_B, "#" + invoice.getOrderId());
        smallLabel(canvas, bold, 395, 679, "STATUS");
        text(canvas, bold, 10, 395, 663, 0, 133, 112, "WYSTAWIONA");

        fillRect(canvas, 48, 616, 499, 23, 255, 242, 237);
        text(canvas, bold, 8.5f, 60, 624, ORANGE_R, ORANGE_G, ORANGE_B,
                "DOKUMENT DEMONSTRACYJNY - NIE JEST DOKUMENTEM KSIĘGOWYM");
    }

    private void drawContinuationHeader(PDPageContentStream canvas, PDFont regular, PDFont bold, Invoice invoice)
            throws IOException {
        fillRect(canvas, 0, 760, PDRectangle.A4.getWidth(), 82, NAVY_R, NAVY_G, NAVY_B);
        fillRect(canvas, 48, 783, 34, 34, ORANGE_R, ORANGE_G, ORANGE_B);
        text(canvas, bold, 11, 56, 795, 255, 255, 255, "MP");
        text(canvas, bold, 16, 96, 801, 255, 255, 255, "MiniTech Store");
        text(canvas, regular, 8, 96, 784, 196, 205, 217, "Faktura VAT - ciąg dalszy");
        rightText(canvas, bold, 13, 547, 800, 255, 255, 255, invoice.getInvoiceNumber());
    }

    private void drawParties(PDPageContentStream canvas, PDFont regular, PDFont bold, Invoice invoice) throws IOException {
        card(canvas, 48, 437, 239, 155);
        card(canvas, 308, 437, 239, 155);

        sectionTitle(canvas, bold, 62, 566, "SPRZEDAWCA");
        text(canvas, bold, 13, 62, 542, NAVY_R, NAVY_G, NAVY_B, "MiniTech Store");
        text(canvas, regular, 9, 62, 523, MUTED_R, MUTED_G, MUTED_B, "Mini Order Platform");
        text(canvas, regular, 9, 62, 507, MUTED_R, MUTED_G, MUTED_B, "Środowisko demonstracyjne DEV/TEST");
        text(canvas, regular, 9, 62, 480, NAVY_R, NAVY_G, NAVY_B, "E-mail: billing@minitech.local");
        text(canvas, regular, 9, 62, 464, NAVY_R, NAVY_G, NAVY_B, "Waluta rozliczenia: " + invoice.getCurrency());

        sectionTitle(canvas, bold, 322, 566, "NABYWCA");
        float y = 542;
        if (invoice.getBillingKind() == BillingKind.COMPANY && invoice.getCompanyName() != null) {
            y = wrappedText(canvas, bold, 11, 322, y, 210, 14, NAVY_R, NAVY_G, NAVY_B, invoice.getCompanyName());
            text(canvas, regular, 8.5f, 322, y - 2, MUTED_R, MUTED_G, MUTED_B,
                    "Osoba kontaktowa: " + invoice.getBuyerName());
            y -= 18;
            if (invoice.getTaxId() != null) {
                text(canvas, regular, 8.5f, 322, y, NAVY_R, NAVY_G, NAVY_B, "NIP: " + invoice.getTaxId());
                y -= 17;
            }
        } else {
            y = wrappedText(canvas, bold, 11, 322, y, 210, 14, NAVY_R, NAVY_G, NAVY_B, invoice.getBuyerName());
        }
        y = wrappedText(canvas, regular, 8.5f, 322, y - 3, 210, 12, NAVY_R, NAVY_G, NAVY_B,
                invoice.getStreet() + ", " + invoice.getPostalCode() + " " + invoice.getCity());
        text(canvas, regular, 8.5f, 322, y - 2, NAVY_R, NAVY_G, NAVY_B, invoice.getCountry());
        text(canvas, regular, 8.5f, 322, y - 19, MUTED_R, MUTED_G, MUTED_B,
                "E-mail: " + invoice.getCustomerEmail());
    }

    private void drawItemsTable(PDPageContentStream canvas, PDFont regular, PDFont bold, List<InvoiceItem> items,
                                int start, int end, String currency,
                                float titleY, float headerY, float firstRowY) throws IOException {
        sectionTitle(canvas, bold, 48, titleY, "ZAKUPIONE PRODUKTY - CENY BRUTTO");
        fillRect(canvas, 48, headerY, 499, 24, NAVY_R, NAVY_G, NAVY_B);
        text(canvas, bold, 7.2f, 60, headerY + 8, 255, 255, 255, "PRODUKT");
        rightText(canvas, bold, 7.2f, 342, headerY + 8, 255, 255, 255, "ILOŚĆ");
        rightText(canvas, bold, 7.2f, 438, headerY + 8, 255, 255, 255, "CENA JEDN.");
        rightText(canvas, bold, 7.2f, 533, headerY + 8, 255, 255, 255, "WARTOŚĆ");

        if (start == end) {
            text(canvas, regular, 8.5f, 60, firstRowY, MUTED_R, MUTED_G, MUTED_B,
                    "Brak zapisanych pozycji dla tej historycznej faktury.");
            return;
        }

        float rowY = firstRowY;
        for (int index = start; index < end; index++) {
            InvoiceItem item = items.get(index);
            if ((index - start) % 2 == 1) {
                fillRect(canvas, 48, rowY - 9, 499, 22, 247, 249, 252);
            }
            text(canvas, regular, 8.5f, 60, rowY, NAVY_R, NAVY_G, NAVY_B,
                    fitText(regular, 8.5f, item.getProductName(), 220));
            rightText(canvas, bold, 8.5f, 342, rowY, NAVY_R, NAVY_G, NAVY_B,
                    String.valueOf(item.getQuantity()));
            rightText(canvas, regular, 8.5f, 438, rowY, NAVY_R, NAVY_G, NAVY_B,
                    money(item.getUnitPrice()) + " " + currency);
            rightText(canvas, bold, 8.5f, 533, rowY, NAVY_R, NAVY_G, NAVY_B,
                    money(item.getTotalPrice()) + " " + currency);
            line(canvas, 48, rowY - 13, 547, rowY - 13, BORDER_R, BORDER_G, BORDER_B);
            rowY -= 24;
        }
    }

    private void drawTotals(PDPageContentStream canvas, PDFont regular, PDFont bold, Invoice invoice, float top) throws IOException {
        sectionTitle(canvas, bold, 48, top, "PODSUMOWANIE PŁATNOŚCI");
        BigDecimal merchandise = invoice.getItems().stream().map(InvoiceItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String merchandiseLabel = invoice.getItems().isEmpty()
                ? "Towary: brak danych historycznych"
                : "Towary: " + money(merchandise) + " " + invoice.getCurrency();
        text(canvas, regular, 8.5f, 48, top - 24, MUTED_R, MUTED_G, MUTED_B, merchandiseLabel);
        text(canvas, regular, 8.5f, 48, top - 42, MUTED_R, MUTED_G, MUTED_B,
                "Dostawa: " + money(invoice.getDeliveryCost()) + " " + invoice.getCurrency());
        String discountPrefix = invoice.getDiscountAmount().signum() > 0 ? "-" : "";
        text(canvas, regular, 8.5f, 48, top - 60, MUTED_R, MUTED_G, MUTED_B,
                "Rabat: " + discountPrefix + money(invoice.getDiscountAmount()) + " " + invoice.getCurrency());
        text(canvas, regular, 8.5f, 48, top - 82, MUTED_R, MUTED_G, MUTED_B,
                "Kwoty zawierają podstawową stawkę VAT 23%.");

        card(canvas, 308, top - 115, 239, 116);
        amountRow(canvas, regular, bold, 322, top - 28, 533, "Wartość netto", invoice.getNetAmount(), invoice.getCurrency(), false);
        amountRow(canvas, regular, bold, 322, top - 55, 533, "VAT 23%", invoice.getVatAmount(), invoice.getCurrency(), false);
        line(canvas, 322, top - 70, 533, top - 70, BORDER_R, BORDER_G, BORDER_B);
        amountRow(canvas, bold, bold, 322, top - 98, 533, "Do zapłaty brutto", invoice.getGrossAmount(), invoice.getCurrency(), true);
    }

    private void drawFooter(PDPageContentStream canvas, PDFont regular, Invoice invoice, int page, int totalPages) throws IOException {
        line(canvas, 48, 70, 547, 70, BORDER_R, BORDER_G, BORDER_B);
        text(canvas, regular, 7.5f, 48, 51, MUTED_R, MUTED_G, MUTED_B,
                "Wygenerowano przez Billing Service - MiniTech Store");
        rightText(canvas, regular, 7.5f, 547, 51, MUTED_R, MUTED_G, MUTED_B,
                "Zamówienie #" + invoice.getOrderId() + " | Strona " + page + "/" + totalPages);
    }

    private void amountRow(PDPageContentStream canvas, PDFont labelFont, PDFont valueFont,
                           float labelX, float y, float rightX, String label,
                           BigDecimal amount, String currency, boolean emphasized) throws IOException {
        int red = emphasized ? NAVY_R : MUTED_R;
        int green = emphasized ? NAVY_G : MUTED_G;
        int blue = emphasized ? NAVY_B : MUTED_B;
        float labelSize = emphasized ? 10.5f : 9.5f;
        float amountSize = emphasized ? 14 : 10.5f;
        float minimumLabelSize = emphasized ? 8.5f : 8;
        float minimumAmountSize = emphasized ? 10.5f : 8.5f;
        float gap = emphasized ? 14 : 10;
        float rowWidth = rightX - labelX;
        String amountText = money(amount) + " " + currency;

        float minimumLabelWidth = textWidth(labelFont, minimumLabelSize, label);
        amountSize = fitFontSize(valueFont, amountText, amountSize, minimumAmountSize,
                rowWidth - minimumLabelWidth - gap);
        float labelWidth = rowWidth - textWidth(valueFont, amountSize, amountText) - gap;
        labelSize = fitFontSize(labelFont, label, labelSize, minimumLabelSize, labelWidth);
        String fittedLabel = fitText(labelFont, labelSize, label, labelWidth);

        text(canvas, labelFont, labelSize, labelX, y, red, green, blue, fittedLabel);
        rightText(canvas, valueFont, amountSize, rightX, y, NAVY_R, NAVY_G, NAVY_B,
                amountText);
    }

    private void card(PDPageContentStream canvas, float x, float y, float width, float height) throws IOException {
        fillRect(canvas, x, y, width, height, 250, 251, 253);
        strokeRect(canvas, x, y, width, height, BORDER_R, BORDER_G, BORDER_B);
    }

    private void sectionTitle(PDPageContentStream canvas, PDFont bold, float x, float y, String value) throws IOException {
        text(canvas, bold, 8, x, y, ORANGE_R, ORANGE_G, ORANGE_B, value);
    }

    private void smallLabel(PDPageContentStream canvas, PDFont bold, float x, float y, String value) throws IOException {
        text(canvas, bold, 7, x, y, MUTED_R, MUTED_G, MUTED_B, value);
    }

    private float wrappedText(PDPageContentStream canvas, PDFont font, float size, float x, float y,
                              float maxWidth, float lineHeight, int red, int green, int blue,
                              String value) throws IOException {
        for (String line : wrap(font, size, clean(value), maxWidth)) {
            text(canvas, font, size, x, y, red, green, blue, line);
            y -= lineHeight;
        }
        return y;
    }

    private List<String> wrap(PDFont font, float size, String value, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : value.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && textWidth(font, size, candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void text(PDPageContentStream canvas, PDFont font, float size, float x, float y,
                      int red, int green, int blue, String value) throws IOException {
        canvas.beginText();
        canvas.setFont(font, size);
        canvas.setNonStrokingColor(new Color(red, green, blue));
        canvas.newLineAtOffset(x, y);
        canvas.showText(clean(value));
        canvas.endText();
    }

    private void rightText(PDPageContentStream canvas, PDFont font, float size, float rightX, float y,
                           int red, int green, int blue, String value) throws IOException {
        String cleaned = clean(value);
        text(canvas, font, size, rightX - textWidth(font, size, cleaned), y, red, green, blue, cleaned);
    }

    private float textWidth(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private float fitFontSize(PDFont font, String value, float preferredSize,
                              float minimumSize, float maxWidth) throws IOException {
        if (maxWidth <= 0) return minimumSize;
        float size = preferredSize;
        while (size > minimumSize && textWidth(font, size, value) > maxWidth) {
            size = Math.max(minimumSize, size - 0.25f);
        }
        return size;
    }

    private String fitText(PDFont font, float size, String value, float maxWidth) throws IOException {
        String cleaned = clean(value);
        if (textWidth(font, size, cleaned) <= maxWidth) return cleaned;
        String suffix = "...";
        int end = cleaned.length();
        while (end > 1 && textWidth(font, size, cleaned.substring(0, end).stripTrailing() + suffix) > maxWidth) {
            end--;
        }
        return cleaned.substring(0, end).stripTrailing() + suffix;
    }

    private void fillRect(PDPageContentStream canvas, float x, float y, float width, float height,
                          int red, int green, int blue) throws IOException {
        canvas.setNonStrokingColor(new Color(red, green, blue));
        canvas.addRect(x, y, width, height);
        canvas.fill();
    }

    private void strokeRect(PDPageContentStream canvas, float x, float y, float width, float height,
                            int red, int green, int blue) throws IOException {
        canvas.setStrokingColor(new Color(red, green, blue));
        canvas.setLineWidth(0.7f);
        canvas.addRect(x, y, width, height);
        canvas.stroke();
    }

    private void line(PDPageContentStream canvas, float x1, float y1, float x2, float y2,
                      int red, int green, int blue) throws IOException {
        canvas.setStrokingColor(new Color(red, green, blue));
        canvas.setLineWidth(0.7f);
        canvas.moveTo(x1, y1);
        canvas.lineTo(x2, y2);
        canvas.stroke();
    }

    private String money(BigDecimal amount) {
        return String.format(Locale.forLanguageTag("pl-PL"), "%,.2f", amount);
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private PDType0Font loadRegularFont(PDDocument document) throws IOException {
        if (cachedRegularFont == null) {
            synchronized (this) {
                if (cachedRegularFont == null) {
                    cachedRegularFont = getFontBytes(configuredRegularFont, regularFontCandidates(), "regular");
                }
            }
        }
        try (InputStream input = new java.io.ByteArrayInputStream(cachedRegularFont)) {
            return PDType0Font.load(document, input, true);
        }
    }

    private PDType0Font loadBoldFont(PDDocument document) throws IOException {
        if (cachedBoldFont == null) {
            synchronized (this) {
                if (cachedBoldFont == null) {
                    cachedBoldFont = getFontBytes(configuredBoldFont, boldFontCandidates(), "bold");
                }
            }
        }
        try (InputStream input = new java.io.ByteArrayInputStream(cachedBoldFont)) {
            return PDType0Font.load(document, input, true);
        }
    }

    private byte[] getFontBytes(String configuredPath, List<Path> candidates, String variant) {
        List<Path> paths = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            paths.add(Path.of(configuredPath.trim()));
        }
        paths.addAll(candidates);
        for (Path path : paths) {
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                try {
                    return Files.readAllBytes(path);
                } catch (IOException ignored) {
                }
            }
        }
        throw new IllegalStateException("Brak fontu Unicode dla PDF (" + variant
                + "). Ustaw BILLING_PDF_FONT_REGULAR i BILLING_PDF_FONT_BOLD.");
    }

    private List<Path> regularFontCandidates() {
        return List.of(
                Path.of("C:/Windows/Fonts/DejaVuSans.ttf"),
                Path.of("C:/Windows/Fonts/NotoSans-Regular.ttf"),
                Path.of("/mnt/c/Windows/Fonts/DejaVuSans.ttf"),
                Path.of("/mnt/c/Windows/Fonts/NotoSans-Regular.ttf"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf")
        );
    }

    private List<Path> boldFontCandidates() {
        return List.of(
                Path.of("C:/Windows/Fonts/DejaVuSans-Bold.ttf"),
                Path.of("C:/Windows/Fonts/NotoSans-Bold.ttf"),
                Path.of("/mnt/c/Windows/Fonts/DejaVuSans-Bold.ttf"),
                Path.of("/mnt/c/Windows/Fonts/NotoSans-Bold.ttf"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
                Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf")
        );
    }
}
