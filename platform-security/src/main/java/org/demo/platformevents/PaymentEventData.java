package org.demo.platformevents;

import java.math.BigDecimal;
import java.util.*;
public record PaymentEventData(String type,long orderId,BigDecimal amount,String recipient) {
    public static PaymentEventData parse(Map<String,String> fields) {
        if(fields.size()>32 || fields.values().stream().anyMatch(value -> value.length()>4000)) throw new IllegalArgumentException("Event exceeds schema limits");
        String type=required(fields,"eventType");
        if(!Set.of("PAYMENT_SUCCEEDED","PAYMENT_FAILED","PAYMENT_PENDING").contains(type)) throw new IllegalArgumentException("Unsupported payment event type");
        long orderId=Long.parseLong(required(fields,"orderId"));
        var amount=new BigDecimal(required(fields,"amount"));
        String recipient=required(fields,"recipient");
        if(orderId<=0 || amount.signum()<0 || amount.precision()>19 || Math.abs((long)amount.scale())>2
            || recipient.length()>255 || !recipient.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Invalid payment event fields");
        return new PaymentEventData(type,orderId,amount,recipient);
    }
    private static String required(Map<String,String> fields,String name) {
        String value=fields.get(name);if(value==null || value.isBlank()) throw new IllegalArgumentException("Missing event field: "+name);return value;
    }
}
