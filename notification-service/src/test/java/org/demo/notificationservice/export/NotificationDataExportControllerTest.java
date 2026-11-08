package org.demo.notificationservice.export;

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

class NotificationDataExportControllerTest {
    private final NotificationDataExportService service=mock(NotificationDataExportService.class);
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new NotificationDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void ownerExportUsesFixedScopeAndPrivateHeaders()throws Exception{
        when(service.order(7,5,2)).thenReturn(new NotificationDataExportPage(1,"order-notifications",false,Instant.now(),"customer-order-owner",7,null,List.of(),false,"0",List.of()));
        mvc(new Actor("CUSTOMER",null,1L,"owner@fixture.example")).perform(get("/notifications/me/data-export/orders/7?size=2&cursor=5&customerId=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"notification-data-page.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).order(7,5,2);
    }
    @Test void controllerAlsoRejectsNonCustomerAndMissingIdentity()throws Exception{
        for(var actor:List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","notification-service",null,null),new Actor("CUSTOMER",null,null,"x")))
            mvc(actor).perform(get("/notifications/me/data-export/orders/7")).andExpect(status().is(actor.authenticated()?403:401));verifyNoInteractions(service);
    }
    @Test void overflowDoesNotReachExportService()throws Exception{
        mvc(new Actor("CUSTOMER",null,1L,"x")).perform(get("/notifications/me/data-export/orders/7?cursor=9223372036854775808")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void anonymousChatExportPassesOnlyHeaderProofAndHasPrivateHeaders()throws Exception{
        when(service.chat("CHAT_ENTRY_REPLIED",7,"C".repeat(43),0,50)).thenReturn(new NotificationDataExportPage(1,"chat-source-notifications",false,Instant.now(),"conversation-capability",7,9L,List.of(),false,"0",List.of()));
        mvc(Actor.ANONYMOUS).perform(get("/notifications/chat/data-export/CHAT_ENTRY_REPLIED/7?customerId=2").header(Access.CHAT_TOKEN,"C".repeat(43)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationMode").value("conversation-capability"))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")));
        verify(service).chat("CHAT_ENTRY_REPLIED",7,"C".repeat(43),0,50);
    }
    @Test void chatExportDoesNotGrantPrivilegedRoleBypass()throws Exception{
        for(var actor:List.of(new Actor("OPERATOR",null,null,null),new Actor("SERVICE","notification-service",null,null)))
            mvc(actor).perform(get("/notifications/chat/data-export/CHAT_REPLY_SENT/7").header(Access.CHAT_TOKEN,"C".repeat(43))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
}
