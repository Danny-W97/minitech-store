package org.demo.wishlistservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Wishlist Service API", version = "v1", description = "Customer wishlist API"),
        servers = @Server(url = "http://127.0.0.1:18090", description = "Local Wishlist Service")
)
public class OpenApiConfig {
}
