package org.demo.customerservice.mfa;

import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaKeyMigrationService {
    private final TotpMethodRepository methods;
    private final MfaEncryptionService encryption;
    private final EntityManager entities;
    public MfaKeyMigrationService(TotpMethodRepository methods, MfaEncryptionService encryption, EntityManager entities) {
        this.methods = methods; this.encryption = encryption; this.entities = entities;
    }

    /** One atomic migration: any missing key or damaged row rolls back every changed row. */
    @Transactional
    public Result migrate() {
        long cursor = 0, verified = 0, migrated = 0;
        while (true) {
            var batch = methods.migrationBatch(cursor, PageRequest.of(0, 100));
            if (batch.isEmpty()) return new Result(verified, migrated);
            for (var method : batch) {
                String secret = encryption.decrypt(method.getSecretCiphertext());
                verified++;
                if (encryption.requiresMigration(method.getSecretCiphertext())) {
                    method.reencryptSecret(encryption.encrypt(secret));
                    migrated++;
                }
                cursor = method.getId();
            }
            entities.flush();
            entities.clear();
        }
    }
    public record Result(long verified, long migrated) {}
}
