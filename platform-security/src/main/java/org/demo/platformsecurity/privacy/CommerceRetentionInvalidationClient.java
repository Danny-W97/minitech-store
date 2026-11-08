package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.PlatformSecurityConfiguration;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded authenticated callback transport; durable source outboxes are still required. */
public final class CommerceRetentionInvalidationClient {
    public static final String PATH = "/internal/privacy/commerce-retention/invalidate";
    private final String origin;
    private final RestClient client;
    private record Command(long orderId, UUID generation, long orderVersion, CommerceRetentionAssessment.Phase phase) {}

    public CommerceRetentionInvalidationClient(String origin, ServiceAuthenticationInterceptor authentication) {
        try {
            URI uri = URI.create(origin);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")) || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("Invalid retention invalidation origin");
        }
        this.origin = origin.replaceAll("/+$", "");
        client = PlatformSecurityConfiguration.client().requestInterceptor(Objects.requireNonNull(authentication)).build();
    }

    public void invalidate(CommerceRetentionAssessment.Subject subject, CommerceRetentionAssessment.Phase phase) {
        Objects.requireNonNull(subject); Objects.requireNonNull(phase);
        client.post().uri(origin + PATH)
                .body(new Command(subject.orderId(), subject.chainGeneration(), subject.orderVersion(), phase))
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() != 202) throw new IllegalStateException("RETENTION_INVALIDATION_UNAVAILABLE");
                    if (response.getBody().readNBytes(1).length != 0) throw new IllegalStateException("RETENTION_INVALIDATION_PROTOCOL");
                    return true;
                });
    }
}
