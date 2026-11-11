package org.demo.platformsecurity.privacy;

import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/**
 * Two-phase distributed write fence protocol for D02 commerce retention.
 * Fences bind order, generation, order version, phase, epoch, source revision and fence token.
 */
public final class PeerRetentionFenceProtocol {
    private PeerRetentionFenceProtocol() {}
    public static final String PATH = "/internal/privacy/commerce-retention/peer-fence";
    public static final int REQUEST_LIMIT = 4096, REPLY_LIMIT = 2048;

    public enum Action { PREPARE, VERIFY, RELEASE }
    public enum Status { FENCED, STALE, CONFLICT, BLOCKED, RELEASED }

    public record Scope(Participant source, long orderId, UUID generation, long orderVersion,
                        Phase phase, long epoch, long revision, UUID fenceToken) {
        public boolean valid() {
            return source != null && source != Participant.ORDER
                    && orderId > 0 && generation != null && orderVersion >= 0
                    && phase != null && epoch > 0 && revision >= 0 && fenceToken != null;
        }
    }

    public record Command(Action action, Scope scope) {
        public boolean valid() { return action != null && scope != null && scope.valid(); }
    }

    public record Reply(Scope scope, Action action, Status status, Instant fencedAt, Instant expiresAt) {}

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public static Reply reply(Command c, Status status, Instant fencedAt, Instant expiresAt) {
        return new Reply(c.scope(), c.action(), status, fencedAt, expiresAt);
    }

    public static Reply validate(Reply r, Command c) {
        require(c != null && c.valid() && r != null && c.scope().equals(r.scope())
                && c.action() == r.action() && r.status() != null);
        if (r.status() == Status.FENCED) {
            require(r.fencedAt() != null && r.expiresAt() != null && r.expiresAt().isAfter(r.fencedAt())
                    && r.expiresAt().isAfter(Instant.now())
                    && !r.fencedAt().isBefore(Instant.EPOCH) && !r.fencedAt().isAfter(Instant.now().plusSeconds(60)));
        } else {
            require(r.fencedAt() == null && r.expiresAt() == null);
        }
        return r;
    }

    private static void require(boolean b) {
        if (!b) throw new IllegalArgumentException("Invalid peer fence protocol");
    }

    private static long number(JsonNode d, String k) {
        require(d.path(k).isIntegralNumber() && d.path(k).canConvertToLong());
        return d.path(k).longValue();
    }

    private static String text(JsonNode d, String k) {
        require(d.path(k).isTextual());
        return d.path(k).asText();
    }

    private static String nullable(JsonNode d, String k) {
        require(d.has(k));
        return d.path(k).isNull() ? null : text(d, k);
    }

    private static Scope scope(JsonNode d) {
        require(d != null && d.isObject() && d.size() == 8);
        return new Scope(Participant.valueOf(text(d, "source")), number(d, "orderId"),
                UUID.fromString(text(d, "generation")), number(d, "orderVersion"),
                Phase.valueOf(text(d, "phase")), number(d, "epoch"),
                number(d, "revision"), UUID.fromString(text(d, "fenceToken")));
    }

    public static Command parseCommand(byte[] raw) {
        require(raw.length <= REQUEST_LIMIT);
        var d = JSON.readTree(raw);
        require(d != null && d.isObject() && d.size() == 2);
        var c = new Command(Action.valueOf(text(d, "action")), scope(d.path("scope")));
        require(c.valid());
        return c;
    }

    public static Reply parseReply(byte[] raw, Command c) {
        require(raw.length <= REPLY_LIMIT);
        var d = JSON.readTree(raw);
        require(d != null && d.isObject() && d.size() == 5);
        String fencedStr = nullable(d, "fencedAt");
        String expiresStr = nullable(d, "expiresAt");
        return validate(new Reply(scope(d.path("scope")), Action.valueOf(text(d, "action")),
                Status.valueOf(text(d, "status")),
                fencedStr == null ? null : Instant.parse(fencedStr),
                expiresStr == null ? null : Instant.parse(expiresStr)), c);
    }
}
