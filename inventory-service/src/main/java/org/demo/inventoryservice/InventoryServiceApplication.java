package org.demo.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Import(org.demo.platformsecurity.privacy.CommerceRetentionSourceConfiguration.class)
@SpringBootApplication
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="bearerAuth",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,scheme="bearer")
@EnableScheduling
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }

}
