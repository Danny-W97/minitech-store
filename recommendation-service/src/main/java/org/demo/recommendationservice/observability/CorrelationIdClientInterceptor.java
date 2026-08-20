package org.demo.recommendationservice.observability;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class CorrelationIdClientInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(CorrelationIdContext.HEADER_NAME,
                CorrelationIdContext.resolve(MDC.get(CorrelationIdContext.MDC_KEY)));
        return execution.execute(request, body);
    }
}
