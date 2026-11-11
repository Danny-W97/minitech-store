package org.demo.customerservice.auth;

import org.demo.customerservice.privacy.*;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivacyJournalControllerTest {
    final PrivacyRequestStore store=mock(PrivacyRequestStore.class);
    final InternalAuthController identities=mock(InternalAuthController.class);
    final RestoreEpochStore epoch=mock(RestoreEpochStore.class);
    MockMvc mvc;
    @BeforeEach void setup(){
        when(identities.resolve(anyString())).thenReturn(Actor.ANONYMOUS);
        when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR",null,null,null));
        when(identities.resolve("customer")).thenReturn(new Actor("CUSTOMER",null,17L,"fixture@example.test"));
        when(identities.resolve("service")).thenReturn(new Actor("SERVICE","order-service",null,null));
        when(epoch.permitsLogin()).thenReturn(true);
        when(store.journalPage(0,50)).thenReturn(Map.of("schemaVersion",1,"requests",List.of(),"nextCustomerId","0","end",true));
        var filter=new JwtAuthenticationFilter(identities);org.springframework.test.util.ReflectionTestUtils.setField(filter,"restoreEpoch",epoch);
        mvc=MockMvcBuilders.standaloneSetup(new PrivacyJournalController(store))
            .addFilters((jakarta.servlet.Filter)(request,response,chain)->{SecurityContextHolder.clearContext();try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}},filter).build();
    }
    @Test void onlyOperatorMayReadOrAcknowledgeJournal() throws Exception {
        String path="/customers/privacy-journal/requests";
        mvc.perform(get(path).header("Authorization","Bearer operator")).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        for(String actor:List.of("customer","service","unknown"))mvc.perform(get(path).header("Authorization","Bearer "+actor)).andExpect(status().is4xxClientError());
        UUID request=UUID.randomUUID();mvc.perform(post(path+"/"+request+"/ack").header("Authorization","Bearer operator").contentType("application/json").content("{\"receipt\":\"fixture\"}")).andExpect(status().isOk());
        verify(store).acknowledgeJournal(request,"fixture");
        mvc.perform(post(path+"/"+request+"/ack").header("Authorization","Bearer customer").contentType("application/json").content("{\"receipt\":\"fixture\"}")).andExpect(status().is4xxClientError());
        verify(store,times(1)).acknowledgeJournal(any(),any());
    }
    @Test void quarantineBlocksPublicCustomerFlowsWhileOperatorCanInspect() throws Exception {
        when(epoch.permitsLogin()).thenReturn(false);
        for(String path:List.of("register","login","refresh","password-reset/request","mfa/login/verify"))mvc.perform(post("/customers/"+path).contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/customers/privacy-journal/requests").header("Authorization","Bearer operator")).andExpect(status().isOk());
        verify(store).journalPage(0,50);verifyNoMoreInteractions(store);
    }
}
