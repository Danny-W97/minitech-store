package org.demo.deliveryservice.export;

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

class ShipmentDataExportControllerTest {
    private final ShipmentDataExportService service=mock(ShipmentDataExportService.class);
    private final Actor owner=new Actor("CUSTOMER",null,17L,"owner@fixture.example");
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new ShipmentDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void partialShipmentDownloadHasPrivateHeadersAndExplicitFields()throws Exception{
        var now=Instant.now();when(service.export(99)).thenReturn(new ShipmentDataExport(1,"shipment-for-order",false,now,
                new ShipmentDataExport.ShipmentData(1L,99L,"TRACK-1","Zażółć","COURIER","LABEL_CREATED",now,now),List.of()));
        mvc(owner).perform(get("/deliveries/me/data-export/orders/99?customerId=42").header("X-Customer-Id","42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.scope").value("shipment-for-order")).andExpect(jsonPath("$.shipment.carrier").value("Zażółć"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"shipment-data.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).export(99);
    }
    @Test void everyNonCustomerActorIsRejectedBeforeService()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"x"),new Actor("SERVICE","delivery-service",17L,"x"),
                new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
            mvc(actor).perform(get("/deliveries/me/data-export/orders/99")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
    @Test void invalidIdentifiersDoNotReachService()throws Exception{
        mvc(owner).perform(get("/deliveries/me/data-export/orders/9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void authorizationFailureNeverBecomesSuccessfulDownload()throws Exception{
        when(service.export(99)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order authorization unavailable"));
        mvc(owner).perform(get("/deliveries/me/data-export/orders/99")).andExpect(status().isServiceUnavailable());
    }
}
