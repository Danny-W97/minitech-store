package org.demo.customerservice.mfa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({MfaKeyMigrationService.class, MfaKeyMigrationTest.Keys.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MfaKeyMigrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean MfaEncryptionService encryption() throws Exception {
            return new MfaEncryptionService(MfaEncryptionServiceTest.KEY, "current", "", MfaEncryptionServiceTest.legacyKey("old-material"));
        }
    }
    @Autowired TotpMethodRepository methods;
    @Autowired MfaKeyMigrationService migration;
    @Autowired MfaEncryptionService encryption;
    @BeforeEach void clean() { methods.deleteAll(); }

    @Test void migrationPreservesEnabledStateAndConsumedTimeStepAndIsIdempotent() throws Exception {
        var method = new TotpMethod(9001L, MfaEncryptionServiceTest.legacyCipher("old-material", "SECRET"));
        method.enable(); method.markUsed(12345L);
        method = methods.saveAndFlush(method);
        var result = migration.migrate();
        assertThat(result.migrated()).isEqualTo(1);
        var saved = methods.findById(method.getId()).orElseThrow();
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getLastUsedTimeStep()).isEqualTo(12345L);
        assertThat(saved.getSecretCiphertext()).startsWith("v1:current:");
        assertThat(encryption.decrypt(saved.getSecretCiphertext())).isEqualTo("SECRET");
        assertThat(migration.migrate().migrated()).isZero();
    }
    @Test void migrationRollsBackEarlierBatchesIfAnyLaterRowCannotBeDecrypted() throws Exception {
        String legacy = MfaEncryptionServiceTest.legacyCipher("old-material", "SECRET");
        for (long id = 10000; id < 10101; id++) methods.saveAndFlush(new TotpMethod(id, legacy));
        methods.saveAndFlush(new TotpMethod(20000L, "v1:missing-key:AAAA"));
        assertThatThrownBy(() -> migration.migrate()).hasMessageContaining("Could not decrypt");
        assertThat(methods.findByCustomerId(10000L).orElseThrow().getSecretCiphertext()).isEqualTo(legacy);
        assertThat(methods.findByCustomerId(10100L).orElseThrow().getSecretCiphertext()).isEqualTo(legacy);
        assertThat(methods.count()).isEqualTo(102);
    }
}
