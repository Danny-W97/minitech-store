package org.demo.billingservice.billing;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceExportServiceTest {
    private final InvoiceExportReader reader = mock(InvoiceExportReader.class);
    private final InvoicePdfService pdf = mock(InvoicePdfService.class);
    private final InvoiceExportReader.Plan plan = new InvoiceExportReader.Plan(null,null, 4, 3);
    private Invoice invoice(long id) {
        var invoice = new Invoice(); ReflectionTestUtils.setField(invoice,"id",id); invoice.assignNumber("FV/2026/"+id); return invoice;
    }
    private InvoiceExportService service() { return new InvoiceExportService(reader,pdf,2,10,1,Duration.ofMinutes(1)); }
    @Test void readsKeysetWindowsAndWritesEveryPdfWithoutUsingCache() throws Exception {
        when(reader.plan(null,null,10)).thenReturn(plan);
        var a=invoice(4); var b=invoice(3); var c=invoice(1);
        when(reader.page(plan,4,2)).thenReturn(List.of(a,b)); when(reader.page(plan,2,2)).thenReturn(List.of(c));
        for (var invoice: List.of(a,b,c)) when(pdf.generate(invoice)).thenReturn("%PDF-document".getBytes());
        var out = new ByteArrayOutputStream(); var service = service();
        try (var session = service.open(null,null)) { session.writeTo(out); }
        int entries=0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            while (zip.getNextEntry()!=null) { assertThat(new String(zip.readAllBytes())).isEqualTo("%PDF-document"); entries++; }
        }
        assertThat(entries).isEqualTo(3);
        verify(reader).plan(null,null,10); verify(reader).page(plan,4,2); verify(reader).page(plan,2,2); verifyNoMoreInteractions(reader);
    }
    @Test void failuresReleaseCapacityAndDoNotFinishAnApparentlyCompletePartialZip() throws Exception {
        when(reader.plan(null,null,10)).thenReturn(plan);
        var a=invoice(4); var b=invoice(3);
        when(reader.page(plan,4,2)).thenReturn(List.of(a,b));
        when(pdf.generate(a)).thenReturn("%PDF-first".getBytes()); when(pdf.generate(b)).thenThrow(new IllegalStateException("Render failed"));
        var service=service(); var out=new ByteArrayOutputStream();
        try (var session=service.open(null,null)) {
            assertThatThrownBy(() -> service.open(null,null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
            assertThatThrownBy(() -> session.writeTo(out)).hasMessageContaining("Render failed");
        }
        byte[] archive=out.toByteArray();
        assertThat(archive.length).isGreaterThan(4);
        assertThat(new String(archive,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PK\u0005\u0006");
        try (var ignored=service.open(null,null)) { /* slot is free after failed export */ }
    }
    @Test void disconnectedOutputStopsBeforeRenderingTheNextDocument() throws Exception {
        when(reader.plan(null,null,10)).thenReturn(plan);
        var a=invoice(4); var b=invoice(3); when(reader.page(plan,4,2)).thenReturn(List.of(a,b));
        when(pdf.generate(a)).thenReturn("%PDF".getBytes());
        try (var session=service().open(null,null)) {
            assertThatThrownBy(() -> session.writeTo(new java.io.OutputStream() {
                public void write(int value) throws IOException { throw new IOException("Disconnected"); }
            })).isInstanceOf(IOException.class);
        }
        verify(pdf,never()).generate(b);
    }
    @Test void preflightFailureDoesNotLeakAnExportSlot() {
        when(reader.plan(null,null,10)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE));
        var service=service();
        for (int i=0;i<3;i++) assertThatThrownBy(() -> service.open(null,null)).hasMessageContaining("413");
    }
}
