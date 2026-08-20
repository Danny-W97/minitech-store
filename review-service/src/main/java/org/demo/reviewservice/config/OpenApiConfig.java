package org.demo.reviewservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Review Service API", version = "v1", description = "Product reviews with purchase verification"),
        servers = @Server(url = "http://127.0.0.1:18091", description = "Local Review Service")
)
public class OpenApiConfig {
}
