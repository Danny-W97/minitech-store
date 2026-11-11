package org.demo.billingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.context.annotation.Import({org.demo.platformsecurity.privacy.CommerceRetentionSourceConfiguration.class, org.demo.platformsecurity.privacy.PeerRetentionConfiguration.class})
@SpringBootApplication
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="bearerAuth", type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP, scheme="bearer", bearerFormat="JWT")
public class BillingServiceApplication {
    public static void main(String[] args) { SpringApplication.run(BillingServiceApplication.class, args); }
}
