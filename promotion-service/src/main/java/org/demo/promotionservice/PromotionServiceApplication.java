package org.demo.promotionservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import({org.demo.platformsecurity.privacy.CommerceRetentionSourceConfiguration.class, org.demo.platformsecurity.privacy.PeerRetentionConfiguration.class})
@SpringBootApplication
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="bearerAuth",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,scheme="bearer")
public class PromotionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
