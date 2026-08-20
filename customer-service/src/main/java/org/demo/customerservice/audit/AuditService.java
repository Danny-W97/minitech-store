package org.demo.customerservice.audit;

import org.demo.customerservice.observability.CorrelationIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String actor, String resource, String outcome, String metadata) {
        try {
            repository.save(new AuditEvent(
                    eventType,
                    safe(actor, "anonymous", 255),
                    safe(resource, "unknown", 120),
                    safe(outcome, "UNKNOWN", 40),
                    safe(CorrelationIdContext.currentOrNew(), "unknown", 100),
                    safe(MDC.get("auditClientIp"), null, 64),
                    safe(MDC.get("auditUserAgent"), null, 500),
                    safe(metadata, null, 1000)
            ));
        } catch (RuntimeException exception) {
            // Security auditing must not make login/reset unavailable, but the failure is visible in logs/metrics.
            log.error("Could not persist security audit event {}", eventType, exception);
        }
    }

    private String safe(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
