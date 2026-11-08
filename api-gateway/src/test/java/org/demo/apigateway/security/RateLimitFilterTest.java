package org.demo.apigateway.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {
    private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    private final ClientAddressResolver addresses = new ClientAddressResolver("", "none");
    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path); request.setRemoteAddr("198.51.100.7"); return request;
    }
    @Test void downstreamRuntimeFailureNeverReexecutesChainInFailOpenMode() throws Exception {
        when(limiter.check(any())).thenReturn(new RedisRateLimiter.Decision(true, 10, 9, 60));
        var chain = mock(FilterChain.class);
        doThrow(new IllegalStateException("downstream failed after a write")).when(chain).doFilter(any(), any());
        assertThatThrownBy(() -> new RateLimitFilter(limiter, addresses, true, true)
                .doFilter(request("POST", "/api/orders"), new MockHttpServletResponse(), chain)).hasMessageContaining("after a write");
        verify(chain, times(1)).doFilter(any(), any());
    }
    @Test void redisFailureRunsChainOnceOrRejectsAccordingToPolicy() throws Exception {
        when(limiter.check(any())).thenThrow(new IllegalStateException("Redis unavailable"));
        var open = mock(FilterChain.class);
        new RateLimitFilter(limiter, addresses, true, true).doFilter(request("POST", "/api/orders"), new MockHttpServletResponse(), open);
        verify(open, times(1)).doFilter(any(), any());
        var closed = mock(FilterChain.class); var response = new MockHttpServletResponse();
        new RateLimitFilter(limiter, addresses, true, false).doFilter(request("POST", "/api/orders"), response, closed);
        assertThat(response.getStatus()).isEqualTo(503); verifyNoInteractions(closed);
    }
    @Test void routeIdMethodAliasAndEncodedPathsShareOneClientBudget() throws Exception {
        when(limiter.check(any())).thenReturn(new RedisRateLimiter.Decision(false, 10, 0, 2));
        for (var path : new String[]{"/api/products/1", "/api/v1/products/2", "/%61pi/products/3", "/unknown"}) {
            var chain = mock(FilterChain.class); var response = new MockHttpServletResponse();
            var request = request("POST", path); request.addHeader("X-Forwarded-For", "203.0.113.9");
            new RateLimitFilter(limiter, addresses, true, false).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("2");
            verifyNoInteractions(chain);
        }
        verify(limiter, times(4)).check("198.51.100.7");
    }
    @Test void preflightHealthAndDisabledModeDoNotUseRedis() throws Exception {
        for (var request : new MockHttpServletRequest[]{request("OPTIONS", "/api/orders"), request("GET", "/actuator/health"),
                request("GET", "/v3/api-docs"), request("GET", "/swagger-ui/index.html")}) {
            new RateLimitFilter(limiter, addresses, true, false).doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        }
        new RateLimitFilter(limiter, addresses, false, false).doFilter(request("POST", "/api/orders"), new MockHttpServletResponse(), mock(FilterChain.class));
        verifyNoInteractions(limiter);
    }
    @Test void malformedTrustedHeaderIsNotTreatedAsAnAvailabilityFailure() throws Exception {
        var request = request("POST", "/api/orders"); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-Forwarded-For", "bad-host");
        var response = new MockHttpServletResponse(); var chain = mock(FilterChain.class);
        new RateLimitFilter(limiter, new ClientAddressResolver("127.0.0.1", "none"), true, true).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400); verifyNoInteractions(limiter, chain);
    }
}
