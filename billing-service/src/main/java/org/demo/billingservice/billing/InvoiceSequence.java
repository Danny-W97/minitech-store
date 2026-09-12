package org.demo.billingservice.billing;

import jakarta.persistence.*;

@Entity
@Table(name = "invoice_sequences")
public class InvoiceSequence {

    @Id
    @Column(name = "series", length = 32)
    private String series;

    @Column(name = "next_val", nullable = false)
    private Long nextVal;

    public InvoiceSequence() {}

    public InvoiceSequence(String series, Long nextVal) {
        this.series = series;
        this.nextVal = nextVal;
    }

    public String getSeries() {
        return series;
    }

    public void setSeries(String series) {
        this.series = series;
    }

    public Long getNextVal() {
        return nextVal;
    }

    public void setNextVal(Long nextVal) {
        this.nextVal = nextVal;
    }
}
