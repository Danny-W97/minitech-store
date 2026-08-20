package org.demo.recommendationservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI recommendationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Recommendation Service API")
                .version("v1")
                .description("Recommendations based on catalog similarity and customer purchase history."));
    }
}
