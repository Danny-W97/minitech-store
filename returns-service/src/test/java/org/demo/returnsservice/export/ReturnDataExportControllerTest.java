package org.demo.returnsservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.Actor;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
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

class ReturnDataExportControllerTest {
    private final ReturnDataExportService service=mock(ReturnDataExportService.class);
    private final Actor owner=new Actor("CUSTOMER",null,17L,"owner@fixture.example");
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new ReturnDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void ownHeadersArePartialAndHavePrivateDownloadHeaders()throws Exception{
        var now=Instant.now();when(service.returns(17,0,2)).thenReturn(new ReturnDataExportPage(1,"returns-page",false,now,
                List.of(new ReturnDataExportPage.ReturnData(1L,99L,"old@fixture.example",ReturnType.COMPLAINT,"Zażółć","Opis",ReturnStatus.REQUESTED,now,now)),false,"0",List.of()));
        mvc(owner).perform(get("/returns/me/data-export?size=2&customerId=42").header("X-Customer-Id","42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false)).andExpect(jsonPath("$.scope").value("returns-page"))
                .andExpect(jsonPath("$.returns[0].reason").value("Zażółć")).andExpect(jsonPath("$.returns[0].customerEmail").value("old@fixture.example"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"returns-data-page.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).returns(17,0,2);
    }
    @Test void itemPagesAreOwnedAndUseExplicitStoredFields()throws Exception{
        when(service.items(17,99,500,2)).thenReturn(new ReturnItemDataExportPage(1,"return-items-page",false,Instant.now(),99L,
                List.of(new ReturnItemDataExportPage.Item(1L,499L,101L,"Żółć",3,2)),false,"0",List.of()));
        mvc(owner).perform(get("/returns/me/data-export/99/items?cursor=500&size=2&customerId=42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].orderItemId").value(499))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"return-items-data-page.json\""));
        verify(service).items(17,99,500,2);
    }
    @Test void eventsHaveOnlySafeStatusFieldsAndTheDownloadContract()throws Exception{
        when(service.events(17,99,0,50)).thenReturn(new ReturnEventDataExportPage(1,"return-events-page",false,Instant.now(),99L,
                List.of(new ReturnEventDataExportPage.Event(1L,"LEGACY_SNAPSHOT",null,"REQUESTED",Instant.now())),false,"0",List.of()));
        mvc(owner).perform(get("/returns/me/data-export/99/events"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events[0].action").value("LEGACY_SNAPSHOT"))
                .andExpect(jsonPath("$.events[0].actorRef").doesNotExist()).andExpect(jsonPath("$.events[0].note").doesNotExist())
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"return-events-data-page.json\""));
        verify(service).events(17,99,0,50);
    }
    @Test void nonCustomerActorsAreDeniedForEveryExportRoute()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,17L,"x"),new Actor("SERVICE","returns-service",17L,"x"),
                new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")))
            for(var path:paths())mvc(actor).perform(get(path)).andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }
    @Test void cursorOverflowIsRejectedBeforeAnyQuery()throws Exception{
        for(var path:paths())mvc(owner).perform(get(path+"?cursor=9223372036854775808")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void missingOrForeignCaseIsNotAnEmptySuccessfulChildExport()throws Exception{
        when(service.items(17,99,0,50)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Return not found"));
        when(service.events(17,99,0,50)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Return not found"));
        mvc(owner).perform(get("/returns/me/data-export/99/items")).andExpect(status().isNotFound());
        mvc(owner).perform(get("/returns/me/data-export/99/events")).andExpect(status().isNotFound());
    }
    private List<String> paths(){return List.of("/returns/me/data-export","/returns/me/data-export/99/items","/returns/me/data-export/99/events");}
}
