package org.demo.billingservice.export;

import org.demo.billingservice.billing.BillingService;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvoiceDataExportControllerTest {
    private final InvoiceDataExportService service=mock(InvoiceDataExportService.class);
    private final BillingService billing=mock(BillingService.class);
    private final Actor owner=new Actor("CUSTOMER",null,17L,"owner@fixture.example");
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new InvoiceDataExportController(service,billing))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void headerIsPartialAndHasPrivateDownloadHeaders()throws Exception{
        when(service.invoice(99)).thenReturn(new InvoiceDataExport(1,"invoice-for-order",false,Instant.now(),null,
                new InvoiceDataExport.Document("/invoices/me/data-export/orders/99/pdf","application/pdf","rendered-stored-document"),List.of()));
        mvc(owner).perform(get(base()+"?customerId=42").header("X-Customer-Id","42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.pdf.representation").value("rendered-stored-document"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"invoice-data.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).invoice(99);
    }
    @Test void childPagesUseTheRequestedWindowWithStaticAttachments()throws Exception{
        when(service.items(99,5,2)).thenReturn(new InvoiceItemDataExportPage(1,"invoice-items-page",false,Instant.now(),99L,List.of(),false,"0",List.of()));
        when(service.corrections(99,5,2)).thenReturn(new InvoiceCorrectionDataExportPage(1,"invoice-corrections-page",false,Instant.now(),99L,List.of(),false,"0",List.of()));
        mvc(owner).perform(get(base()+"/items?cursor=5&size=2&customerId=42")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("invoice-items-page")).andExpect(header().string("Content-Disposition","attachment; filename=\"invoice-items-data-page.json\""));
        mvc(owner).perform(get(base()+"/corrections?cursor=5&size=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("invoice-corrections-page")).andExpect(header().string("Content-Disposition","attachment; filename=\"invoice-corrections-data-page.json\""));
        verify(service).items(99,5,2);verify(service).corrections(99,5,2);verifyNoInteractions(billing);
    }
    @Test void everyPdfRequestRechecksScopeBeforeReusingExistingBillingRendering()throws Exception{
        when(service.invoiceId(99)).thenReturn(11L);when(service.correctionId(99,7)).thenReturn(7L);
        when(billing.pdf(11L)).thenReturn("%PDF-invoice".getBytes());when(billing.pdfCorrection(7L)).thenReturn("%PDF-correction".getBytes());
        for(int i=0;i<2;i++){
            mvc(owner).perform(get(base()+"/pdf")).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                    .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(header().string("Content-Disposition","attachment; filename=\"invoice.pdf\""));
            mvc(owner).perform(get(base()+"/corrections/7/pdf")).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
        var calls=inOrder(service,billing);
        for(int i=0;i<2;i++){calls.verify(service).invoiceId(99);calls.verify(billing).pdf(11L);calls.verify(service).correctionId(99,7);calls.verify(billing).pdfCorrection(7L);}
    }
    @Test void nonCustomerActorsAreDeniedForJsonAndPdfBeforeAnyService()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"x"),new Actor("SERVICE","billing-service",17L,"x"),new Actor("CUSTOMER",null,null,"x")))
            for(var suffix:List.of("","/items","/corrections","/pdf","/corrections/7/pdf"))mvc(actor).perform(get(base()+suffix)).andExpect(status().is4xxClientError());
        verifyNoInteractions(service,billing);
    }
    @Test void overflowingIdentifiersAndCursorsAreRejectedBeforeServices()throws Exception{
        mvc(owner).perform(get("/invoices/me/data-export/orders/9223372036854775808")).andExpect(status().isBadRequest());
        for(var suffix:List.of("/items","/corrections"))mvc(owner).perform(get(base()+suffix+"?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        mvc(owner).perform(get(base()+"/corrections/9223372036854775808/pdf")).andExpect(status().isBadRequest());verifyNoInteractions(service,billing);
    }
    @Test void unavailableAuthorityPreventsAccessToBothPdfCaches()throws Exception{
        when(service.invoiceId(99)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order authorization unavailable"));
        when(service.correctionId(99,7)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order authorization unavailable"));
        mvc(owner).perform(get(base()+"/pdf")).andExpect(status().isServiceUnavailable());
        mvc(owner).perform(get(base()+"/corrections/7/pdf")).andExpect(status().isServiceUnavailable());verifyNoInteractions(billing);
    }
    @Test void foreignCorrectionNeverReachesThePdfCache()throws Exception{
        when(service.correctionId(99,8)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice document not found"));
        mvc(owner).perform(get(base()+"/corrections/8/pdf")).andExpect(status().isNotFound());verifyNoInteractions(billing);
    }
    private String base(){return "/invoices/me/data-export/orders/99";}
}
