package org.demo.customerservice.mfa;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MfaKeyMigrationConfiguration {
    @Bean
    SmartInitializingSingleton migrateMfaKeysBeforeServingRequests(MfaKeyMigrationService migration, MfaEncryptionService encryption) {
        return () -> {
            var result = migration.migrate();
            LoggerFactory.getLogger(MfaKeyMigrationService.class).info(
                    "MFA key migration complete: verified={}, migrated={}, activeKid={}",
                    result.verified(), result.migrated(), encryption.activeKeyId());
        };
    }
}
