package org.demo.customerservice.auth;

import org.demo.platformsecurity.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final InternalAuthController identities = mock(InternalAuthController.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(identities);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void operatorReadAccessDoesNotGrantProfileOrMfaModification() throws Exception {
        when(identities.resolve("operator")).thenReturn(new Actor("OPERATOR", null, null, null));
        assertEquals(200, call("GET", "/customers/17", "operator"));
        SecurityContextHolder.clearContext();
        assertEquals(401, call("PUT", "/customers/17/profile", "operator"));
        assertEquals(401, call("GET", "/customers/mfa/status", "operator"));
    }
    @Test void disabledOrInvalidIdentityCannotAccessItsOwnProfile() throws Exception {
        when(identities.resolve("disabled")).thenReturn(Actor.ANONYMOUS);
        assertEquals(401, call("GET", "/customers/me", "disabled"));
    }
    private int call(String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        return response.getStatus();
    }
}
