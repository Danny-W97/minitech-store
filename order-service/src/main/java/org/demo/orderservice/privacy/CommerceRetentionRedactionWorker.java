package org.demo.orderservice.privacy;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.demo.platformsecurity.privacy.CommerceRetentionExecution;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Subject;

/** Retired incomplete D02 execution entry point. This API cannot mutate data.
 * A new trusted, durable authorization and complete-chain execution protocol is
 * required before any redaction/minimum removal can be implemented here.
 * Caller clocks, cached CONFIRMED metadata and fixture booleans are not authority. */
public final class CommerceRetentionRedactionWorker {
    public enum Outcome { REDACTED, ALREADY_REDACTED, BLOCKED, WAIT, CONFLICT, STALE }
    public record RedactionResult(Outcome outcome, String code, Instant redactedAt) {
        public boolean activationAllowed() { return false; }
    }

    public CommerceRetentionRedactionWorker(DataSource ds, PlatformTransactionManager manager) {
        Objects.requireNonNull(ds); Objects.requireNonNull(manager);
    }

    public RedactionResult redact(Subject subject, Instant now) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(now, "now");
        return new RedactionResult(Outcome.BLOCKED, CommerceRetentionExecution.UNAVAILABLE, null);
    }
}
