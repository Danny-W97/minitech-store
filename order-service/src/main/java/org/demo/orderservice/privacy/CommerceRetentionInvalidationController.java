package org.demo.orderservice.privacy;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Phase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.Set;
import java.util.UUID;

/** Invalidation only; never accepts a positive proof or a hold-release request. */
@RestController
@ConditionalOnProperty(name="privacy.commerce-retention-invalidation.enabled", havingValue="true", matchIfMissing=false)
public final class CommerceRetentionInvalidationController {
    private static final Set<String> SOURCES = Set.of("returns-service", "payment-service", "inventory-service",
            "promotion-service", "delivery-service", "billing-service", "notification-service");
    private final CommerceRetentionJobs jobs;

    @Autowired
    public CommerceRetentionInvalidationController(DataSource source, PlatformTransactionManager manager) {
        this(new CommerceRetentionJobs(source, manager));
    }
    CommerceRetentionInvalidationController(CommerceRetentionJobs jobs) { this.jobs = jobs; }

    public record Command(long orderId, UUID generation, long orderVersion, Phase phase) {}

    @PostMapping("/internal/privacy/commerce-retention/invalidate")
    public ResponseEntity<Void> invalidate(@RequestBody Command command) {
        var actor = Access.actor();
        Access.require(actor.internal() && actor.service() != null && SOURCES.contains(actor.service()));
        if (command == null || command.orderId() < 1 || command.generation() == null
                || command.orderVersion() < 0 || command.phase() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid retention scope");
        }
        jobs.invalidate(command.orderId(), command.generation(), command.orderVersion(), command.phase());
        // Same empty response for a recorded, duplicate, stale or unknown scope.
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore().cachePrivate()).build();
    }
}
