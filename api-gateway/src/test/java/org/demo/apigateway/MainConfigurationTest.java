package org.demo.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

class MainConfigurationTest {
    @Test void productionResourcesParseWithStrictDuplicateKeyChecks() throws Exception {
        var loader=new YamlPropertySourceLoader();
        var gateway=loader.load("gateway-main",new FileSystemResource("src/main/resources/application.yaml"));
        assertThat(gateway.getFirst().getProperty("gateway.export.max-concurrent")).isNotNull();
        assertThat(gateway.getFirst().getProperty("gateway.rate-limit.enabled")).isNotNull();
        var billing=loader.load("billing-main",new FileSystemResource("../billing-service/src/main/resources/application.yaml"));
        assertThat(billing.getFirst().getProperty("billing.pdf.cache.max-bytes")).isNotNull();
        assertThat(billing.getFirst().getProperty("billing.export.page-size")).isNotNull();
    }
}
