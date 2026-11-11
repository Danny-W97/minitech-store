package org.demo.inventoryservice.export;

import org.demo.platformsecurity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InventoryDataExportControllerTest {
    private final InventoryDataExportService service=mock(InventoryDataExportService.class);
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new InventoryDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void downloadsHavePrivateHeadersAndIgnoreUntrustedCustomerIdentity()throws Exception{
        when(service.export(7)).thenReturn(new InventoryDataExport(1,"inventory-reservation",false,Instant.now(),7,null,List.of()));
        when(service.items(7,5,2)).thenReturn(new InventoryItemDataExportPage(1,"inventory-reservation-items-page",false,Instant.now(),7,List.of(),false,"0",List.of()));
        var mvc=mvc(new Actor("CUSTOMER",null,1L,"x"));
        mvc.perform(get(base()+"?customerId=2").header("X-Customer-Id","2")).andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"inventory-reservation-data.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get(base()+"/items?size=2&cursor=5")).andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("0"));
        verify(service).export(7);verify(service).items(7,5,2);
    }
    @Test void controllerHasNoPrivilegedOrAnonymousBypass()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","order-service",null,null),new Actor("CUSTOMER",null,null,"x")))
            for(String suffix:List.of("","/items"))mvc(actor).perform(get(base()+suffix)).andExpect(status().is(actor.authenticated()?403:401));verifyNoInteractions(service);
    }
    @Test void idAndCursorOverflowNeverReachService()throws Exception{
        var mvc=mvc(new Actor("CUSTOMER",null,1L,"x"));mvc.perform(get(base()+"/items?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        mvc.perform(get("/inventory/me/data-export/orders/9223372036854775808")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    private static String base(){return "/inventory/me/data-export/orders/7";}
}
