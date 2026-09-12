package org.demo.billingservice.billing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InvoiceSequenceGenerator {

    private final InvoiceSequenceRepository repository;

    public InvoiceSequenceGenerator(InvoiceSequenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public synchronized String nextInvoiceNumber(int year) {
        String series = "FV/" + year;
        long val = nextValue(series);
        return String.format("%s/%06d", series, val);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public synchronized String nextCorrectionNumber(int year) {
        String series = "KOR/" + year;
        long val = nextValue(series);
        return String.format("%s/%06d", series, val);
    }

    private long nextValue(String series) {
        return repository.findBySeriesForUpdate(series)
                .map(seq -> {
                    long val = seq.getNextVal();
                    seq.setNextVal(val + 1);
                    repository.save(seq);
                    return val;
                })
                .orElseGet(() -> {
                    InvoiceSequence created = new InvoiceSequence(series, 2L);
                    repository.save(created);
                    return 1L;
                });
    }
}
