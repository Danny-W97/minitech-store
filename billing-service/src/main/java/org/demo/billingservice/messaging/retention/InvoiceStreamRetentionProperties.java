package org.demo.billingservice.messaging.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties("messaging.invoice-stream-retention")
public record InvoiceStreamRetentionProperties(
        @DefaultValue("observe") Mode mode, @DefaultValue("30d") Duration retention,
        @DefaultValue("500") int batchSize, @DefaultValue("4") int maxBatches,
        @DefaultValue("600000") long intervalMs, @DefaultValue("60000") long initialDelayMs,
        @DefaultValue("") String requiredGroups, @DefaultValue("false") boolean allowNoGroups) {
    public enum Mode {OFF, OBSERVE, TRIM}
    public InvoiceStreamRetentionProperties {
        if(mode==null || retention==null || retention.compareTo(Duration.ofDays(1))<0
                || retention.compareTo(Duration.ofDays(36500))>0 || batchSize<1 || batchSize>1000
                || maxBatches<1 || maxBatches>20 || intervalMs<1000 || initialDelayMs<0 || requiredGroups==null)
            throw new IllegalArgumentException("Invalid invoice stream retention settings");
        parseGroups(requiredGroups);
    }
    public List<String> groups() {return parseGroups(requiredGroups);}
    private static List<String> parseGroups(String value) {
        if(value.isBlank()) return List.of();
        var groups=Arrays.stream(value.split(",",-1)).map(String::trim).toList();
        if(groups.size()>32 || groups.stream().anyMatch(g->!g.matches("[A-Za-z0-9._:-]{1,128}"))
                || groups.stream().distinct().count()!=groups.size()) throw new IllegalArgumentException("Invalid required invoice stream groups");
        return groups;
    }
}
