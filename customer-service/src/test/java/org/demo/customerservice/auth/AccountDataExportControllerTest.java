package org.demo.customerservice.auth;

import org.demo.customerservice.export.AccountDataExport;
import org.demo.customerservice.export.AccountDataExportController;
import org.demo.customerservice.export.AccountDataExportService;
import org.demo.customerservice.web.GlobalExceptionHandler;
import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountDataExportControllerTest {
    private final AccountDataExportService service = mock(AccountDataExportService.class);
    private final InternalAuthController identities = mock(InternalAuthController.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(identities.resolve(anyString())).thenReturn(Actor.ANONYMOUS);
        when(identities.resolve("owner")).thenReturn(new Actor("CUSTOMER", null, 17L, "owner@fixture.example"));
        // The filter intentionally supports operators only on its existing list/detail routes.
        when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR", null, null, null));
        when(identities.resolve("service")).thenReturn(new Actor("SERVICE", "billing-service", null, null));
        when(service.export(any())).thenReturn(fixture());
        mvc = MockMvcBuilders.standaloneSetup(new AccountDataExportController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtAuthenticationFilter(identities)).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void ownerDownloadsExplicitlyPartialJsonWithPrivateNoStoreHeaders() throws Exception {
        mvc.perform(get("/customers/me/data-export").header(HttpHeaders.AUTHORIZATION, "Bearer owner"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("private")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-profile-export.json\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.profile.id").value(17))
                .andExpect(jsonPath("$.profile.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.security.totpEnabled").value(true))
                .andExpect(jsonPath("$.omissions[0].status").value("excluded"));
        verify(service).export(new CustomerPrincipal(17L, "owner@fixture.example"));
    }

    @Test void callerCannotChooseAnotherSubjectThroughQueryOrHeaders() throws Exception {
        mvc.perform(get("/customers/me/data-export?customerId=99&id=99&email=other@fixture.example")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer owner").header("X-Customer-Id", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.id").value(17));
        verify(service).export(new CustomerPrincipal(17L, "owner@fixture.example"));
    }

    @Test void anonymousCannotDownload() throws Exception {
        mvc.perform(get("/customers/me/data-export")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void operatorCannotDownload() throws Exception { denied("operator"); }
    @Test void serviceCannotDownload() throws Exception { denied("service"); }
    @Test void invalidExpiredOrDisabledIdentityCannotDownload() throws Exception { denied("expired-or-disabled"); }

    @Test void noExportRouteAcceptsAnArbitraryCustomerPath() throws Exception {
        mvc.perform(get("/customers/99/data-export").header(HttpHeaders.AUTHORIZATION, "Bearer owner"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test void exportOnlySupportsGet() throws Exception {
        mvc.perform(post("/customers/me/data-export").header(HttpHeaders.AUTHORIZATION, "Bearer owner"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    private void denied(String token) throws Exception {
        mvc.perform(get("/customers/me/data-export").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    static AccountDataExport fixture() {
        Instant time = Instant.parse("2026-10-28T10:00:00Z");
        return new AccountDataExport(1, "customer-profile-and-security-summary", false, time,
                new AccountDataExport.Profile(17L, "owner@fixture.example", "Jan", "Żółć", "Testowa 1",
                        "00-001", "Warszawa", "Polska", null, true, time, time),
                new AccountDataExport.SecuritySummary(true, 2, 8),
                List.of(new AccountDataExport.Omission("authenticationSecrets", "excluded")));
    }
}
