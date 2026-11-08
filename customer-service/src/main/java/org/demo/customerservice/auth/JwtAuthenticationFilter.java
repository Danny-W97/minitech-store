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

    @org.springframework.beans.factory.annotation.Autowired(required=false) private RestoreEpochStore restoreEpoch;
    private final InternalAuthController internalAuth;

    public JwtAuthenticationFilter(InternalAuthController internalAuth) {
        this.internalAuth = internalAuth;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        request.setAttribute(org.demo.platformsecurity.Access.ACTOR,org.demo.platformsecurity.Actor.ANONYMOUS);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String rawToken = authorization.substring("Bearer ".length()).trim();
            var actor = internalAuth.resolve(rawToken);
            request.setAttribute(org.demo.platformsecurity.Access.ACTOR,actor);
            if(actor.internal()&&org.demo.platformsecurity.RoutePolicy.allowed(request.getMethod(),request.getRequestURI(),actor)
                    &&(request.getRequestURI().startsWith("/internal/privacy/fences/")||request.getRequestURI().startsWith("/internal/privacy/account-cleanup/"))) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
            }
            if (operatorRead(request) && actor.operator()) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "operator", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
            }
            if ("CUSTOMER".equals(actor.role())) {
                CustomerPrincipal principal = new CustomerPrincipal(actor.customerId(), actor.email());
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                        );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        if(restoreEpoch!=null&&request.getRequestURI().startsWith("/customers")&&!org.demo.platformsecurity.Access.actor().operator()&&!org.demo.platformsecurity.Access.actor().internal()){
            try{if(!restoreEpoch.permitsLogin()){response.sendError(503,"Restored environment requires reconciliation");return;}}
            catch(org.springframework.dao.DataAccessException unavailable){response.sendError(503,"Authentication state unavailable");return;}
        }
        if (isProtectedCustomerPath(request) && !operatorReadAuthenticated(request)
                && !(SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof CustomerPrincipal)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean operatorRead(HttpServletRequest request) {
        return (request.getRequestURI().startsWith("/customers/privacy-journal/")&&(request.getMethod().equals("GET")||request.getMethod().equals("POST")))||"GET".equals(request.getMethod()) && (request.getRequestURI().matches("/customers(/(\\d+|operator-session))?")
                ||request.getRequestURI().matches("/customers/privacy-requests/[0-9a-fA-F-]{36}/operator-status"))
                ||"POST".equals(request.getMethod())&&request.getRequestURI().matches("/customers/privacy-requests/[0-9a-fA-F-]{36}/retry");
    }

    private boolean operatorReadAuthenticated(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return operatorRead(request) && authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_OPERATOR"));
    }

    private boolean isProtectedCustomerPath(HttpServletRequest request) {
        String path=request.getRequestURI();
        if(request.getMethod().equals("GET")&&path.matches("/customers/privacy-requests/[0-9a-fA-F-]{36}/status"))return false;
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
