package org.demo.customerservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.customerservice.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Customer authentication uses JWT");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (request, response, exception) -> response.sendError(401, "Authentication required")
                ).accessDeniedHandler(
                        (request, response, exception) -> response.sendError(401, "Authentication required")
                ))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/customers/register", "/customers/login", "/customers/refresh",
                                "/customers/logout", "/customers/verify", "/customers/verification/resend",
                                "/customers/password-reset/request", "/customers/password-reset/confirm",
                                "/customers/mfa/login/verify",
                                "/customers/mfa/webauthn/login/options", "/customers/mfa/webauthn/login/verify",
                                "/customers/.well-known/jwks.json").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
