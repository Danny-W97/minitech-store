package org.demo.customerservice.web;

import org.demo.customerservice.auth.AuthService;
import org.demo.customerservice.customer.CustomerService;
import org.demo.customerservice.mfa.MfaService;
import org.demo.customerservice.mfa.WebAuthnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerPaginationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void operatorGetsThePageContractAndCustomerCannotReadIt() throws Exception {
        var service = mock(CustomerService.class);
        var row = new CustomerResponse(7L, "jan@audit.example", "Jan", "Kowalski", "Testowa 1", "00-001",
                "Warszawa", "Polska", null, java.time.Instant.parse("2026-10-26T07:00:00Z"), null);
        org.mockito.Mockito.when(service.list(org.mockito.ArgumentMatchers.eq("jan"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.demo.platformsecurity.ListWindow.ListPage<>(List.of(row), true, "7", 1));
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerController(service, mock(AuthService.class),
                mock(MfaService.class), mock(WebAuthnService.class), "refresh", "/", false, 60))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator", "", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        mvc.perform(get("/customers?search=jan&size=1"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Next-Cursor", "7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].city").value("Warszawa"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].verificationTokenHash").doesNotExist());
        org.mockito.Mockito.clearInvocations(service);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("customer", "", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        mvc.perform(get("/customers?search=jan&size=1")).andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }
    @Test void invalidWindowsAndOverflowReturn400WithoutErrorRedispatch() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("operator", "", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        var controller = new CustomerController(mock(CustomerService.class), mock(AuthService.class), mock(MfaService.class), mock(WebAuthnService.class), "refresh", "/", false, 60);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String query : List.of("size=0", "size=201", "cursor=-1", "cursor=9223372036854775808", "size=text")) {
            mvc.perform(get("/customers?"+query)).andExpect(status().isBadRequest());
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("customer", "", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        mvc.perform(get("/customers")).andExpect(status().isForbidden());
    }
}
