package org.demo.billingservice.web;

import java.math.BigDecimal;

/** Invoice plus every immutable delta, rather than the last historical snapshot. */
public record InvoiceBalanceResponse(Long invoiceId,long version,BigDecimal netAmount,BigDecimal vatAmount,
        BigDecimal grossAmount,String currency,long legacyCorrections) {}
