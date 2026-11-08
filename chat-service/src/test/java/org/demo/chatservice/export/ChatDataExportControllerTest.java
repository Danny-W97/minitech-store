package org.demo.chatservice.export;

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

class ChatDataExportControllerTest {
    private final ChatDataExportService service=mock(ChatDataExportService.class);
    private final String capability="C".repeat(43);
    private MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new ChatDataExportController(service))
            .setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver())
            .addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    @Test void anonymousCapabilityDownloadHasExplicitScopeAndPrivateHeaders()throws Exception{
        when(service.conversation(99,capability)).thenReturn(new ChatDataExport(1,"chat-conversation",false,Instant.now(),"conversation-capability",null,List.of()));
        mvc(Actor.ANONYMOUS).perform(get(base()+"?customerId=42").header(Access.CHAT_TOKEN,capability))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorizationMode").value("conversation-capability"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"chat-conversation-data.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).conversation(99,capability);
    }
    @Test void accountIdentityDoesNotReplaceOrChangeTheConversationProof()throws Exception{
        when(service.entries(99,capability,5,2)).thenReturn(new ChatEntryDataExportPage(1,"chat-entries-page",false,Instant.now(),"conversation-capability",99L,List.of(),false,"0",List.of()));
        mvc(new Actor("CUSTOMER",null,17L,"different@fixture.example")).perform(get(base()+"/entries?cursor=5&size=2&customerId=42")
                        .header(Access.CHAT_TOKEN,capability).header("X-Customer-Id","42"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"chat-entries-data-page.json\""));
        verify(service).entries(99,capability,5,2);
    }
    @Test void privilegedActorsAreRejectedEvenWhenTheyHaveACapability()throws Exception{
        for(var actor:List.of(new Actor("OPERATOR",null,17L,"x"),new Actor("SERVICE","chat-service",17L,"x"),new Actor("CUSTOMER",null,null,"x")))
            for(var suffix:List.of("","/entries"))mvc(actor).perform(get(base()+suffix).header(Access.CHAT_TOKEN,capability)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void queryTokenIsNotUsedAsConversationCredential()throws Exception{
        when(service.conversation(99,null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Conversation access token required"));
        mvc(Actor.ANONYMOUS).perform(get(base()+"?accessToken="+capability)).andExpect(status().isUnauthorized());verify(service).conversation(99,null);
    }
    @Test void unknownOrUnownedConversationDoesNotBecomeAnEmptyDownload()throws Exception{
        when(service.entries(99,capability,0,50)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation not found"));
        mvc(Actor.ANONYMOUS).perform(get(base()+"/entries").header(Access.CHAT_TOKEN,capability)).andExpect(status().isNotFound());
    }
    @Test void cursorAndIdentifierOverflowNeverReachService()throws Exception{
        mvc(Actor.ANONYMOUS).perform(get(base()+"/entries?cursor=9223372036854775808").header(Access.CHAT_TOKEN,capability)).andExpect(status().isBadRequest());
        mvc(Actor.ANONYMOUS).perform(get("/chat/conversations/9223372036854775808/data-export").header(Access.CHAT_TOKEN,capability)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    private String base(){return "/chat/conversations/99/data-export";}
}
