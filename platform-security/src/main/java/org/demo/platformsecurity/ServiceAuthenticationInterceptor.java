package org.demo.platformsecurity;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;
import java.io.IOException;

/** The service's identity replaces caller credentials on internal HTTP calls. */
public record ServiceAuthenticationInterceptor(String token) implements ClientHttpRequestInterceptor {
    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("PLATFORM_SERVICE_TOKEN is required for internal calls");
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return execution.execute(request, body);
    }
}
