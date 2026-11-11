package org.demo.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Import({org.demo.platformsecurity.privacy.CommerceRetentionSourceConfiguration.class, org.demo.platformsecurity.privacy.PeerRetentionConfiguration.class})
@SpringBootApplication
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="bearerAuth",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,scheme="bearer")
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="chatCapability",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
        in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Chat-Access-Token")
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }

}
