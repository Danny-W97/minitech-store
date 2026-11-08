package org.demo.returnsservice.privacy;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.demo.platformsecurity.privacy.CommerceRetentionExecution;

/** Retired incomplete D02 execution entry point. This API cannot mutate data.
 * A new trusted, durable authorization and complete-chain execution protocol is
 * required before any redaction/minimum removal can be implemented here.
 * Caller clocks, cached CONFIRMED metadata and fixture booleans are not authority. */
public final class ReturnsCommerceRetentionRedactionWorker {
    public enum Outcome { REDACTED, ALREADY_REDACTED, NO_RECORDS, BLOCKED }
    public record RedactionResult(Outcome outcome, String code, Instant redactedAt) {
        public boolean activationAllowed() { return false; }
    }

    public ReturnsCommerceRetentionRedactionWorker(DataSource ds, PlatformTransactionManager manager) {
        Objects.requireNonNull(ds); Objects.requireNonNull(manager);
    }

    public RedactionResult redact(long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("Invalid orderId");
        return new RedactionResult(Outcome.BLOCKED, CommerceRetentionExecution.UNAVAILABLE, null);
    }
}
