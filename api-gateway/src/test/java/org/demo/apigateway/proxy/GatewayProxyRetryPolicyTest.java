package org.demo.apigateway.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayProxyRetryPolicyTest {

    @Test
    void retriesOnlyReadOnlyHttpMethodsAutomatically() {
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.GET)).isTrue();
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.HEAD)).isTrue();
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.OPTIONS)).isTrue();

        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.POST)).isFalse();
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.PUT)).isFalse();
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.PATCH)).isFalse();
        assertThat(GatewayProxy.isSafeForAutomaticRetry(HttpMethod.DELETE)).isFalse();
    }
}
