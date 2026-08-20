package org.demo.qatests;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InfrastructureContainersIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:latest")
            .withExposedPorts(8025, 1025)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025).forStatusCode(200));

    @Test
    void startsRedisAndMailpitAsDisposableInfrastructure() throws Exception {
        var redisPing = REDIS.execInContainer("redis-cli", "PING");
        assertThat(redisPing.getExitCode()).isZero();
        assertThat(redisPing.getStdout().trim()).isEqualTo("PONG");

        int mailpitPort = MAILPIT.getMappedPort(8025);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + MAILPIT.getHost() + ":" + mailpitPort + "/api/v1/info"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Version");
    }
}
