package org.demo.apigateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes the public API version visible to clients and marks the legacy route.
 * The unversioned /api path remains available for the existing frontend while
 * new clients can migrate to /api/v1 without a breaking change.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiVersioningHeaderFilter extends OncePerRequestFilter {

    private static final String VERSIONED_PREFIX = "/api/v1";
    private static final String LEGACY_PREFIX = "/api/";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith(VERSIONED_PREFIX)) {
            response.setHeader("API-Version", "v1");
        } else if (path.startsWith(LEGACY_PREFIX)) {
            response.setHeader("API-Version", "v1");
            response.setHeader("Deprecation", "true");
            response.setHeader("Link", "</api/v1>; rel=\"successor-version\"");
        }
        filterChain.doFilter(request, response);
    }
}
