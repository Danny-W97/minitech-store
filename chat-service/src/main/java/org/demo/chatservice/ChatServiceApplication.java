package org.demo.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@io.swagger.v3.oas.annotations.security.SecurityScheme(name="chatCapability",type=io.swagger.v3.oas.annotations.enums.SecuritySchemeType.APIKEY,
        in=io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,paramName="X-Chat-Access-Token")
@org.springframework.scheduling.annotation.EnableScheduling
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
