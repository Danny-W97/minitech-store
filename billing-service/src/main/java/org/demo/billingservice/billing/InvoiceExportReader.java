package org.demo.billingservice.billing;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

/** Short transactions return detached windows; no persistence context spans the ZIP transfer. */
@Service
public class InvoiceExportReader {
    public record Plan(Instant from, Instant to, long ceiling, long count) {}
    private final EntityManager entityManager;
    public InvoiceExportReader(EntityManager entityManager) { this.entityManager = entityManager; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan plan(Instant from, Instant to, int maxDocuments) {
        if (from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        var builder=entityManager.getCriteriaBuilder();
        var maximum=builder.createQuery(Long.class); var invoice=maximum.from(Invoice.class);
        maximum.select(builder.max(invoice.<Long>get("id"))).where(scope(builder,invoice,from,to,null));
        Long ceiling=entityManager.createQuery(maximum).getSingleResult();
        long count=0;
        if (ceiling!=null) {
            var countQuery=builder.createQuery(Long.class); var row=countQuery.from(Invoice.class);
            countQuery.select(builder.count(row)).where(scope(builder,row,from,to,ceiling));
            count=entityManager.createQuery(countQuery).getSingleResult();
        }
        if (count > maxDocuments) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Export exceeds " + maxDocuments + " invoices; narrow the from/to date range");
        return new Plan(from, to, ceiling == null ? 0 : ceiling, count);
    }

    @Transactional(readOnly = true)
    public List<Invoice> page(Plan plan, long ceiling, int size) {
        var builder=entityManager.getCriteriaBuilder();
        var query=builder.createQuery(Invoice.class); var invoice=query.from(Invoice.class);
        query.select(invoice).where(scope(builder,invoice,plan.from(),plan.to(),ceiling)).orderBy(builder.desc(invoice.get("id")));
        var invoices=entityManager.createQuery(query).setMaxResults(size).getResultList();
        invoices.forEach(row -> row.getItems().size());
        return invoices;
    }

    private Predicate[] scope(CriteriaBuilder builder, Root<Invoice> invoice, Instant from, Instant to, Long ceiling) {
        var conditions=new java.util.ArrayList<Predicate>();
        if (from!=null) conditions.add(builder.greaterThanOrEqualTo(invoice.get("createdAt"),from));
        if (to!=null) conditions.add(builder.lessThanOrEqualTo(invoice.get("createdAt"),to));
        if (ceiling!=null) conditions.add(builder.lessThanOrEqualTo(invoice.get("id"),ceiling));
        return conditions.toArray(Predicate[]::new);
    }
}
