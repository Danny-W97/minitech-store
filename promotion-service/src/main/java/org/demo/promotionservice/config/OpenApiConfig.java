package org.demo.promotionservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Promotion Service API", version = "v1", description = "Daily promotions and price history API"),
        servers = @Server(url = "http://127.0.0.1:18089", description = "Local Promotion Service")
)
public class OpenApiConfig {
}
