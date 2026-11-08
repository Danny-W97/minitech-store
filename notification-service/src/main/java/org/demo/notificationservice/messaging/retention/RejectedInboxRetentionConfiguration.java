package org.demo.notificationservice.messaging.retention;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RejectedInboxRetentionProperties.class)
public class RejectedInboxRetentionConfiguration {
}
