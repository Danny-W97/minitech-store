package org.demo.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "services.product.base-url=http://127.0.0.1:1",
        "management.health.redis.enabled=false"
})
@AutoConfigureMockMvc
class ApiGatewayApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesOnlyMinimalHealthStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void exposesVersionedOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("MiniTech Store Public API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    void exposesStableV1ContractForAllGatewayDomains() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/products'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/inventory'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/notifications'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/customers'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/deliveries'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/deliveries'].post").exists());
    }

    @Test
    void keepsLegacyApiAndMarksItForMigration() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().is5xxServerError())
                .andExpect(header().string("API-Version", "v1"))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", "</api/v1>; rel=\"successor-version\""));
    }

    @Test
    void marksVersionedApiWithoutDeprecation() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().is5xxServerError())
                .andExpect(header().string("API-Version", "v1"))
                .andExpect(header().doesNotExist("Deprecation"));
    }

}
