package org.demo.customerservice.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String rawToken = authorization.substring("Bearer ".length()).trim();
            jwtService.parseAccessToken(rawToken).ifPresent(claims -> {
                CustomerPrincipal principal = new CustomerPrincipal(claims.customerId(), claims.email());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                        );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        if (isProtectedCustomerPath(request.getRequestURI())
                && !(SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof CustomerPrincipal)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtectedCustomerPath(String path) {
        if (!path.startsWith("/customers/") || path.equals("/customers/")) {
            return false;
        }
        Set<String> publicPaths = Set.of(
                "/customers/register",
                "/customers/login",
                "/customers/refresh",
                "/customers/logout",
                "/customers/verify",
                "/customers/verification/resend",
                "/customers/password-reset/request",
                "/customers/password-reset/confirm",
                "/customers/mfa/login/verify",
                "/customers/mfa/webauthn/login/options",
                "/customers/mfa/webauthn/login/verify",
                "/customers/.well-known/jwks.json"
        );
        return !publicPaths.contains(path);
    }
}
