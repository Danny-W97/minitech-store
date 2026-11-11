package org.demo.chatservice.privacy;

import org.demo.platformsecurity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;
import org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatPrivacyRequestControllerTest {
    final ChatPrivacyRequestStore store=mock(ChatPrivacyRequestStore.class);final String cap="C".repeat(43);final UUID key=UUID.randomUUID();
    MockMvc mvc(Actor actor){return MockMvcBuilders.standaloneSetup(new ChatPrivacyRequestController(store)).setHandlerExceptionResolvers(new ResponseStatusExceptionResolver(),new DefaultHandlerExceptionResolver()).addFilters((request,response,chain)->{request.setAttribute(Access.ACTOR,actor);chain.doFilter(request,response);}).build();}
    ChatPrivacyRequestStatus view(){return new ChatPrivacyRequestStatus(1,key,"17","MANUAL_REVIEW",Instant.now(),false,false);}
    String base(){return "/chat/conversations/17/privacy-request";}
    @Test void confirmedCapabilityIntentReturnsAcceptedSafeMetadataAndPrivateHeaders()throws Exception{
        when(store.submit(17,cap,key)).thenReturn(view());mvc(Actor.ANONYMOUS).perform(post(base()).header(Access.CHAT_TOKEN,cap).header("Idempotency-Key",key).contentType("application/json").content("{\"confirmation\":\"REQUEST_CHAT_DELETION_REVIEW\"}"))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(jsonPath("$.state").value("MANUAL_REVIEW")).andExpect(jsonPath("$.contentErased").value(false)).andExpect(jsonPath("$.token").doesNotExist());verify(store).submit(17,cap,key);
    }
    @Test void phraseAndKeyAreRequiredBeforeAnyMutation()throws Exception{
        mvc(Actor.ANONYMOUS).perform(post(base()).header(Access.CHAT_TOKEN,cap).header("Idempotency-Key",key).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc(Actor.ANONYMOUS).perform(post(base()).header(Access.CHAT_TOKEN,cap).contentType("application/json").content("{\"confirmation\":\"REQUEST_CHAT_DELETION_REVIEW\"}")).andExpect(status().isBadRequest());verifyNoInteractions(store);
    }
    @Test void accountCannotChooseConversationByEmailQueryOrIdentityHeader()throws Exception{
        when(store.get(17,cap)).thenReturn(view());mvc(new Actor("CUSTOMER",null,99L,"same@fixture.example")).perform(get(base()+"?email=same@fixture.example&customerId=17").header(Access.CHAT_TOKEN,cap).header("X-Customer-Id","17")).andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false));verify(store).get(17,cap);
    }
    @Test void privilegedRolesNeverBypassCapabilityEvenWhenTheySupplyIt()throws Exception{
        for(var actor:List.of(new Actor("OPERATOR",null,null,null),new Actor("SERVICE","customer-service",null,null),new Actor("CUSTOMER",null,null,"x"))){
            mvc(actor).perform(get(base()).header(Access.CHAT_TOKEN,cap)).andExpect(status().isForbidden());mvc(actor).perform(post(base()).header(Access.CHAT_TOKEN,cap).header("Idempotency-Key",key).contentType("application/json").content("{\"confirmation\":\"REQUEST_CHAT_DELETION_REVIEW\"}")).andExpect(status().isForbidden());}
        verifyNoInteractions(store);
    }
    @Test void queryTokenIsNotAProofAndStatusIsNotFoundWithoutAStoredRequest()throws Exception{
        when(store.get(17,null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));when(store.get(17,cap)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));mvc(Actor.ANONYMOUS).perform(get(base()+"?token="+cap)).andExpect(status().isUnauthorized());mvc(Actor.ANONYMOUS).perform(get(base()).header(Access.CHAT_TOKEN,cap)).andExpect(status().isNotFound());
    }
}
