package org.demo.qatests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.availability.*;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Real Boot 4.1 Actuator/HTTP/PostgreSQL using only each app's public health properties. */
@Testcontainers
class ReadinessHttpPostgresIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("readiness_fixture").withUsername("fixture").withPassword("fixture-only");
    static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    static final List<String> SERVICES = List.of("api-gateway", "product-service", "order-service", "inventory-service",
            "payment-service", "notification-service", "customer-service", "delivery-service", "promotion-service",
            "wishlist-service", "review-service", "recommendation-service", "chat-service", "returns-service", "billing-service");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ProbeApplication {
        @Bean HealthIndicator redisHealthIndicator() { return () -> Health.down().withDetail("fixture", "unavailable").build(); }
        @Bean HealthIndicator mailHealthIndicator() { return () -> Health.down().withDetail("fixture", "unavailable").build(); }
    }
    static Path config(String service, String profile) {
        return ROOT.resolve(service+"/src/main/resources/application"+(profile.equals("base")?"":"-"+profile)+".yaml");
    }
    static Stream<Arguments> configurations() {
        return SERVICES.stream().flatMap(service -> Stream.of("base", "dev", "test", "prod")
                .filter(profile -> Files.isRegularFile(config(service, profile))).map(profile -> Arguments.of(service, profile)));
    }
    static Map<String,Object> healthProperties(String service, String profile) throws Exception {
        var sources = new MutablePropertySources();
        var loader = new YamlPropertySourceLoader();
        for (var path : profile.equals("base")?List.of(config(service,"base")):List.of(config(service,"base"),config(service,profile))) {
            for (var source : loader.load(path.toString(),new FileSystemResource(path))) sources.addFirst(source);
        }
        var resolver = new PropertySourcesPropertyResolver(sources);
        var result = new LinkedHashMap<String,Object>();
        for (String key : List.of("management.endpoint.health.probes.enabled", "management.health.livenessstate.enabled",
                "management.health.readinessstate.enabled", "management.endpoint.health.show-details",
                "management.endpoint.health.group.liveness.include", "management.endpoint.health.group.readiness.include")) {
            String value = resolver.getProperty(key);
            if (value!=null) result.put(key,value);
        }
        return result;
    }
    static ConfigurableApplicationContext start(String service, String profile) throws Exception {
        var properties = healthProperties(service,profile);
        properties.putAll(Map.ofEntries(
                Map.entry("spring.config.location", "optional:classpath:/a35-fixture-no-application.properties"),
                Map.entry("spring.main.banner-mode", "off"), Map.entry("logging.level.root", "ERROR"),
                Map.entry("server.address", "127.0.0.1"), Map.entry("server.port", "0"),
                Map.entry("management.endpoints.web.exposure.include", "health"),
                Map.entry("spring.datasource.url", DB.getJdbcUrl()+"?connectTimeout=1&socketTimeout=1"),
                Map.entry("spring.datasource.username", DB.getUsername()), Map.entry("spring.datasource.password", DB.getPassword()),
                Map.entry("spring.datasource.hikari.maximum-pool-size", "1"), Map.entry("spring.datasource.hikari.minimum-idle", "0"),
                Map.entry("spring.datasource.hikari.connection-timeout", "750"), Map.entry("spring.datasource.hikari.validation-timeout", "500")));
        // Do not inherit runtime configuration, credentials or locations from the user's environment.
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("owned-readiness-fixture",properties));
        return new SpringApplicationBuilder(ProbeApplication.class).environment(environment).run();
    }
    static HttpResponse<String> get(ConfigurableApplicationContext ctx,String group) throws Exception {
        int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/"+group))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static void status(ConfigurableApplicationContext ctx,String group,int code,String value) throws Exception {
        var response = get(ctx,group);
        assertThat(response.statusCode()).isEqualTo(code);
        assertThat(response.body()).isEqualTo("{\"status\":\""+value+"\"}");
        assertThat(response.body()).doesNotContain("components","details","password","jdbc:");
    }
    static void recovered(ConfigurableApplicationContext ctx) throws Exception {
        long deadline = System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (get(ctx,"readiness").statusCode()!=200) {
            assertThat(System.nanoTime()).as("Owned SQL fixture did not recover").isLessThan(deadline);
            Thread.sleep(100);
        }
        status(ctx,"readiness",200,"UP");
    }

    @ParameterizedTest(name="{0}/{1}") @MethodSource("configurations")
    void databaseLossGatesSqlReadinessWithoutChangingLiveness(String service,String profile) throws Exception {
        try (var ctx = start(service,profile)) {
            status(ctx,"readiness",200,"UP"); status(ctx,"liveness",200,"UP");
            boolean paused=false;
            try {
                DB.getDockerClient().pauseContainerCmd(DB.getContainerId()).exec();paused=true;
                status(ctx,"liveness",200,"UP");
                if (service.equals("api-gateway")) status(ctx,"readiness",200,"UP");
                else status(ctx,"readiness",503,"DOWN");
                status(ctx,"liveness",200,"UP");
            } finally {if(paused) DB.getDockerClient().unpauseContainerCmd(DB.getContainerId()).exec();}
            recovered(ctx);
        }
    }
    @Test void acceptingRefusingAndBrokenAvailabilityStatesRemainEffective() throws Exception {
        try (var ctx = start("inventory-service","base")) {
            AvailabilityChangeEvent.publish(ctx,ReadinessState.REFUSING_TRAFFIC);
            status(ctx,"readiness",503,"OUT_OF_SERVICE");status(ctx,"liveness",200,"UP");
            AvailabilityChangeEvent.publish(ctx,ReadinessState.ACCEPTING_TRAFFIC);
            status(ctx,"readiness",200,"UP");
            AvailabilityChangeEvent.publish(ctx,LivenessState.BROKEN);
            status(ctx,"liveness",503,"DOWN");
        }
    }
}
