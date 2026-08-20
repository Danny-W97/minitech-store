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

    public InvoicePdfService(
            @Value("${billing.pdf.font.regular:}") String configuredRegularFont,
            @Value("${billing.pdf.font.bold:}") String configuredBoldFont
    ) {
        this.configuredRegularFont = configuredRegularFont;
        this.configuredBoldFont = configuredBoldFont;
    }

    public byte[] generate(Invoice invoice) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadFont(document, configuredRegularFont, regularFontCandidates(), "regular");
            PDType0Font bold = loadFont(document, configuredBoldFont, boldFontCandidates(), "bold");

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
            throw new IllegalStateException("Nie udało się wygenerować faktury PDF", exception);
        }
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

    private PDType0Font loadFont(PDDocument document, String configuredPath, List<Path> candidates, String variant)
            throws IOException {
        List<Path> paths = new ArrayList<>();
        if (configuredPath != null && !configuredPath.isBlank()) {
            paths.add(Path.of(configuredPath.trim()));
        }
        paths.addAll(candidates);
        for (Path path : paths) {
            if (Files.isRegularFile(path) && Files.isReadable(path)) {
                try (InputStream input = Files.newInputStream(path)) {
                    return PDType0Font.load(document, input, true);
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
