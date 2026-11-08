package org.demo.customerservice.auth;

import org.demo.customerservice.export.jobs.*;
import org.demo.customerservice.web.GlobalExceptionHandler;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExportJobControllerTest {
    ExportCoordinator service=mock(ExportCoordinator.class);InternalAuthController identities=mock(InternalAuthController.class);MockMvc mvc;UUID id=UUID.randomUUID();
    @BeforeEach void setup(){when(identities.resolve(anyString())).thenReturn(Actor.ANONYMOUS);when(identities.resolve("owner")).thenReturn(new Actor("CUSTOMER",null,1L,"owner@fixture.example"));when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR",null,null,null));when(identities.resolve("service")).thenReturn(new Actor("SERVICE","customer-service",null,null));when(service.status(any(),any())).thenReturn(Map.of("complete",false,"jobId",id));when(service.create(any(),any())).thenReturn(Map.of("complete",false,"jobId",id));mvc=MockMvcBuilders.standaloneSetup(new ExportJobController(service,new ExportJson())).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setControllerAdvice(new GlobalExceptionHandler()).addFilters(new JwtAuthenticationFilter(identities)).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void privateJsonUsesVerifiedSubjectAndIgnoresClaimedCustomer()throws Exception{mvc.perform(get("/customers/me/data-export/jobs/"+id+"?customerId=2").header("Authorization","Bearer owner").header("X-Customer-Id","2")).andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false)).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(header().string("X-Content-Type-Options","nosniff"));verify(service).status(id,new CustomerPrincipal(1L,"owner@fixture.example"));}
    @Test void everyRouteRejectsOperatorServiceAndAnonymous()throws Exception{for(String credential:List.of("operator","service","bad")){for(String suffix:List.of("","/chunks","/manifest"))mvc.perform(get("/customers/me/data-export/jobs/"+id+suffix).header("Authorization","Bearer "+credential)).andExpect(status().isUnauthorized());for(String suffix:List.of("/advance","/cancel","/retry"))mvc.perform(post("/customers/me/data-export/jobs/"+id+suffix).header("Authorization","Bearer "+credential).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isUnauthorized());mvc.perform(post("/customers/me/data-export/jobs").header("Authorization","Bearer "+credential).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isUnauthorized());}verifyNoInteractions(service);}
    @Test void creationRequiresValidUuidKey()throws Exception{for(String value:List.of("invalid","12"))mvc.perform(post("/customers/me/data-export/jobs").header("Authorization","Bearer owner").header("Idempotency-Key",value)).andExpect(status().isBadRequest());verifyNoInteractions(service);UUID key=UUID.randomUUID();mvc.perform(post("/customers/me/data-export/jobs").header("Authorization","Bearer owner").header("Idempotency-Key",key)).andExpect(status().isOk());verify(service).create(new CustomerPrincipal(1L,"owner@fixture.example"),key);}
}
