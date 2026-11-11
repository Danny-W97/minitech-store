package org.demo.platformobservability;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class CorrelationIdClientInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(CorrelationIdContext.HEADER_NAME, CorrelationIdContext.currentOrNew());
        request.getHeaders().set(CorrelationIdContext.TRACEPARENT_HEADER, CorrelationIdContext.childTraceparent());
        return execution.execute(request, body);
    }
}
