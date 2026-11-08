package org.demo.customerservice.export.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.demo.platformsecurity.Access;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Packaged, reviewed classification; never returns repository paths or raw policy documents. */
@Component
public class ExportPolicy {
    private final String hash;
    private final Map<String,Map<String,Long>> coverage;
    private final Set<String> copyBoundaries;
    private final Map<String,List<JsonNode>> omissions;
    public ExportPolicy(ExportJson json) {
        try(var stream=new ClassPathResource("export-policy/technical-data-classification.json").getInputStream()) {
            byte[] bytes=stream.readNBytes(1048577);
            if(bytes.length>1048576)throw new IllegalStateException("Export policy too large");
            hash=Access.hash(new String(bytes,StandardCharsets.UTF_8));
            JsonNode doc=json.parse(bytes);
            if(doc.path("schemaVersion").asInt()!=1||!doc.path("tables").isObject()||!doc.path("copies").isObject())throw new IllegalStateException("Invalid export policy");
            Map<String,Map<String,Long>> services=new TreeMap<>();
            doc.path("tables").fields().forEachRemaining(table->{
                String service=table.getValue().path("owner").asText();
                var decisions=services.computeIfAbsent(service,k->new TreeMap<>());
                table.getValue().path("columns").elements().forEachRemaining(rule->{
                    String decision=doc.path("rules").path(rule.asText()).path("decision").asText();
                    if(decision.isBlank())throw new IllegalStateException("Missing export decision");
                    decisions.merge(decision,1L,Long::sum);
                });
            });
            if(services.size()!=14)throw new IllegalStateException("Missing export service coverage");
            coverage=Collections.unmodifiableMap(services);
            Set<String> copies=new TreeSet<>();doc.path("copies").fieldNames().forEachRemaining(copies::add);copyBoundaries=Collections.unmodifiableSet(copies);
            Map<String,List<JsonNode>> reviewed=new TreeMap<>();
            doc.path("omissions").fields().forEachRemaining(source->{
                String service=source.getKey().substring(0,source.getKey().indexOf('/'));
                var pairs=reviewed.computeIfAbsent(service,k->new ArrayList<>());
                source.getValue().forEach(entry->pairs.add(entry.path("pair")));
            });
            omissions=Collections.unmodifiableMap(reviewed);
        }catch(java.io.IOException failure){throw new IllegalStateException("Export policy unavailable");}
    }
    public String hash(){return hash;}
    public Map<String,Map<String,Long>> coverage(){return coverage;}
    public Set<String> copyBoundaries(){return copyBoundaries;}
    public Map<String,List<JsonNode>> reviewedOmissions(){return omissions;}
}
