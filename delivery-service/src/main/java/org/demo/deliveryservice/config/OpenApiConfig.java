package org.demo.deliveryservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Delivery Service API", version = "v1", description = "Delivery methods and quote API"),
        servers = @Server(url = "http://127.0.0.1:18088", description = "Local Delivery Service")
)
public class OpenApiConfig {
}
