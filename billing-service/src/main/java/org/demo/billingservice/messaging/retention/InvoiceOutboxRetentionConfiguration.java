package org.demo.billingservice.messaging.retention;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InvoiceOutboxRetentionProperties.class)
public class InvoiceOutboxRetentionConfiguration {
}
