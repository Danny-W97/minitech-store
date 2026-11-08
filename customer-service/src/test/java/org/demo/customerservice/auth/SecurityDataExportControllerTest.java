package org.demo.customerservice.auth;

import org.demo.customerservice.export.*;
import org.demo.customerservice.web.GlobalExceptionHandler;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;

class SecurityDataExportControllerTest {
    private final SecurityDataExportService service=mock(SecurityDataExportService.class);private final InternalAuthController identities=mock(InternalAuthController.class);private MockMvc mvc;
    @BeforeEach void setup(){when(identities.resolve(anyString())).thenReturn(Actor.ANONYMOUS);when(identities.resolve("owner")).thenReturn(new Actor("CUSTOMER",null,1L,"owner@fixture.example"));
        when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR",null,null,null));when(identities.resolve("service")).thenReturn(new Actor("SERVICE","chat-service",null,null));
        mvc=MockMvcBuilders.standaloneSetup(new SecurityDataExportController(service)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new JwtAuthenticationFilter(identities)).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void methodsUseAuthenticatedPrincipalAndPrivateAttachment()throws Exception{
        var p=new CustomerPrincipal(1L,"owner@fixture.example");when(service.methods(p)).thenReturn(new SecurityDataExport(1,"security-method-metadata",false,Instant.now(),null,List.of()));
        mvc.perform(get(base()+"methods?customerId=2").header("Authorization","Bearer owner").header("X-Customer-Id","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"security-methods.json\""))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).methods(p);
    }
    @Test void anonymousInvalidOperatorAndServiceHaveNoBypass()throws Exception{
        for(String credential:new String[]{"invalid","operator","service"})for(String suffix:new String[]{"methods","events","passkeys","recovery-codes"}){
            SecurityContextHolder.clearContext();mvc.perform(get(base()+suffix).header("Authorization","Bearer "+credential)).andExpect(status().isUnauthorized());}
        mvc.perform(get(base()+"events")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void cursorOverflowNeverReachesService()throws Exception{
        mvc.perform(get(base()+"events?cursor=9223372036854775808").header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    private static String base(){return "/customers/me/data-export/security/";}
}
