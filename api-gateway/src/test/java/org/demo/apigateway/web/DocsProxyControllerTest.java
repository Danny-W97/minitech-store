package org.demo.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "services.product.base-url=http://127.0.0.1:1",
        "services.customer.base-url=http://127.0.0.1:1",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
class DocsProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsNotFoundForUnknownService() throws Exception {
        mockMvc.perform(get("/v3/api-docs/unknown-service"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void swaggerConfigContainsAggregatedUrls() throws Exception {
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls").isArray())
                .andExpect(jsonPath("$.urls.length()").value(15))
                .andExpect(jsonPath("$.urls[?(@.url == '/v3/api-docs')].name").value("🌐 00. API Gateway (Publiczne trasy proxy)"))
                .andExpect(jsonPath("$.urls[?(@.url == '/v3/api-docs/customer')].name").value("👤 01. Customer Service (Użytkownicy, Auth & Sesje)"))
                .andExpect(jsonPath("$.urls[?(@.url == '/v3/api-docs/product')].name").value("📦 02. Product Service (Katalog produktów)"))
                .andExpect(jsonPath("$['urls.primaryName']").value("🌐 00. API Gateway (Publiczne trasy proxy)"));
    }

    @Test
    void handlesUnavailableDownstreamGracefully() throws Exception {
        mockMvc.perform(get("/v3/api-docs/customer"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }
}
