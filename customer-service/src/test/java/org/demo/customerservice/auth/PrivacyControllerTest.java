package org.demo.customerservice.auth;

import org.demo.customerservice.privacy.*;
import org.demo.customerservice.export.jobs.ExportJson;
import org.demo.customerservice.web.GlobalExceptionHandler;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivacyControllerTest {
    PrivacyRequestStore store=mock(PrivacyRequestStore.class);JwtService jwt=mock(JwtService.class);InternalAuthController identities=mock(InternalAuthController.class);
    LoginProtectionService protection=mock(LoginProtectionService.class);PasswordEncoder passwords=mock(PasswordEncoder.class);MockMvc mvc;UUID id=UUID.randomUUID();String proof="S".repeat(43);
    PrivacyRequestStore.Request record(){var now=Instant.now();return new PrivacyRequestStore.Request(id,17L,UUID.randomUUID(),"a".repeat(64),now,now.plusSeconds(600),now,now.plusSeconds(604800),"FENCING");}
    @BeforeEach void setup(){when(identities.resolve(anyString())).thenReturn(Actor.ANONYMOUS);when(identities.resolve("owner")).thenReturn(new Actor("CUSTOMER",null,17L,"owner@fixture.example"));when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR",null,null,null));when(identities.resolve("service")).thenReturn(new Actor("SERVICE","customer-service",null,null));when(store.preview(any(),any())).thenReturn(record());when(store.confirm(any(),any(),anyString(),anyString(),anyString(),anySet(),any())).thenReturn(record());when(jwt.authenticationMethods("owner")).thenReturn(Set.of("pwd","totp"));when(store.status(id,proof)).thenReturn(record());when(store.status(id,null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Privacy status proof required"));when(store.operator(id)).thenReturn(record());when(store.retry(id)).thenReturn(record());mvc=MockMvcBuilders.standaloneSetup(new PrivacyController(store,jwt,passwords,protection,new ExportJson(),"fixture-refresh","/api/customers",false)).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).setControllerAdvice(new GlobalExceptionHandler()).addFilters((jakarta.servlet.Filter)(request,response,chain)->{SecurityContextHolder.clearContext();try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}},new JwtAuthenticationFilter(identities)).build();}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void previewUsesVerifiedSubjectAndRejectsPrivilegedRoles()throws Exception{UUID key=UUID.randomUUID();mvc.perform(post("/customers/me/privacy-requests/preview?customerId=99").header("Authorization","Bearer owner").header("Idempotency-Key",key)).andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value("17")).andExpect(jsonPath("$.dataErased").value(false));verify(store).preview(new CustomerPrincipal(17L,"owner@fixture.example"),key);for(String role:List.of("operator","service","bad"))mvc.perform(post("/customers/me/privacy-requests/preview").header("Authorization","Bearer "+role).header("Idempotency-Key",key)).andExpect(status().isUnauthorized());}
    @Test void explicitConfirmationClearsCookieAndNeverReturnsTheCapabilityOrCustomerData()throws Exception{String body=new ExportJson().write(Map.of("planHash","a".repeat(64),"password","fixture-password","statusToken",proof,"confirmation","DELETE_MY_ACCOUNT"));mvc.perform(post("/customers/me/privacy-requests/"+id+"/confirm").header("Authorization","Bearer owner").contentType("application/json").content(body)).andExpect(status().isAccepted()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0"))).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.statusToken").doesNotExist()).andExpect(jsonPath("$.ownerId").doesNotExist()).andExpect(jsonPath("$.complete").value(false));verify(jwt).authenticationMethods("owner");verify(store).confirm(eq(id),eq(new CustomerPrincipal(17L,"owner@fixture.example")),anyString(),eq("fixture-password"),eq(proof),eq(Set.of("pwd","totp")),eq(passwords));}
    @Test void missingExplicitPhraseDoesNotStartAnyStoreMutation()throws Exception{mvc.perform(post("/customers/me/privacy-requests/"+id+"/confirm").header("Authorization","Bearer owner").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());verifyNoInteractions(store);}
    @Test void capabilityStatusRequiresItsOwnProofEvenForOperatorOrService()throws Exception{mvc.perform(get("/customers/privacy-requests/"+id+"/status").header("X-Privacy-Status-Token",proof)).andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value(id.toString())).andExpect(jsonPath("$.dataErased").value(false)).andExpect(jsonPath("$.planHash").doesNotExist()).andExpect(jsonPath("$.ownerId").doesNotExist());for(String role:List.of("operator","service","owner"))mvc.perform(get("/customers/privacy-requests/"+id+"/status").header("Authorization","Bearer "+role)).andExpect(status().isUnauthorized());}
    @Test void inspectionAndRetryAreNarrowOperatorPermissions()throws Exception{mvc.perform(get("/customers/privacy-requests/"+id+"/operator-status").header("Authorization","Bearer operator")).andExpect(status().isOk());mvc.perform(post("/customers/privacy-requests/"+id+"/retry").header("Authorization","Bearer operator")).andExpect(status().isOk());mvc.perform(post("/customers/privacy-requests/"+id+"/retry").header("Authorization","Bearer owner")).andExpect(status().isForbidden());mvc.perform(post("/customers/privacy-requests/"+id+"/retry").header("Authorization","Bearer service")).andExpect(status().isUnauthorized());verify(store,times(1)).retry(id);}
}
