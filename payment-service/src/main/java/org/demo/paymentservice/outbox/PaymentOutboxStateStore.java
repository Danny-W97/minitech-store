package org.demo.paymentservice.outbox;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class PaymentOutboxStateStore {
    public record Ticket(PaymentOutboxEvent event,String token) {}
    private final PaymentOutboxRepository repository;
    private final long leaseMillis;
    public PaymentOutboxStateStore(PaymentOutboxRepository repository,@Value("${outbox.publisher.lease-ms:30000}") long lease) {
        if(lease<10000 || lease>3600000) throw new IllegalArgumentException("Invalid outbox lease");
        this.repository=repository;leaseMillis=lease;
    }
    @Transactional(readOnly=true) public List<Long> due() {
        return repository.due(List.of(OutboxStatus.PENDING,OutboxStatus.FAILED),Instant.now(),org.springframework.data.domain.PageRequest.of(0,50));
    }
    @Transactional public Ticket claim(Long id) {
        var event=repository.lockId(id).orElseThrow();
        if(event.getStatus()==OutboxStatus.PUBLISHED || event.processing() || event.getNextAttemptAt().isAfter(Instant.now())) return null;
        String token=UUID.randomUUID().toString();event.claim(token,Instant.now().plusMillis(leaseMillis));repository.save(event);return new Ticket(event,token);
    }
    @Transactional public void complete(Ticket ticket,boolean published) {
        var event=repository.lockId(ticket.event().getId()).orElseThrow();
        if(!ticket.token().equals(event.getProcessingToken())) return;
        if(published) event.markPublished(); else event.markFailed("Event broker is unavailable");
        event.release();repository.save(event);
    }
}
