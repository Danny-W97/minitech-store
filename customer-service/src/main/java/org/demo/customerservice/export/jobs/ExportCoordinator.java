package org.demo.customerservice.export.jobs;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.platformsecurity.RequestBudget;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ExportCoordinator {
    private final ExportJobStore store;private final ExportSourceClient source;private final ExportDocumentGuard guard;private final ExportPolicy policy;private final ExportJson json;
    public ExportCoordinator(ExportJobStore store,ExportSourceClient source,ExportDocumentGuard guard,ExportPolicy policy,ExportJson json){this.store=store;this.source=source;this.guard=guard;this.policy=policy;this.json=json;}
    private ExportJobStore.Job current(UUID id,CustomerPrincipal owner){var job=store.get(id,owner);if(!job.policyHash().equals(policy.hash()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Export policy changed; cancel and create a new job");return job;}
    public Map<String,Object> create(CustomerPrincipal owner,UUID request){return view(store.create(owner,request,policy.hash()));}
    public Map<String,Object> recent(CustomerPrincipal owner){return Map.of("schemaVersion",1,"jobs",store.recent(owner).stream().map(id->view(store.get(id,owner))).toList(),"complete",false);}
    public Map<String,Object> status(UUID id,CustomerPrincipal owner){return view(current(id,owner));}
    public Map<String,Object> advance(UUID id,CustomerPrincipal owner,UUID request,String bearer){
        current(id,owner);var task=store.claim(id,owner,request);
        if(task!=null){
            try(var budget=RequestBudget.open(8000)){
                var result=source.fetch(task,owner,bearer);var children=guard.inspect(task,result,owner);
                RequestBudget.check();store.commit(task,owner,result,children,json);
            }catch(ExportFailure failure){store.fail(task,owner,failure.code);}
             catch(org.springframework.web.client.RestClientException failure){store.fail(task,owner,"SOURCE_UNAVAILABLE");}
        }
        return status(id,owner);
    }
    public Map<String,Object> retry(UUID id,CustomerPrincipal owner){current(id,owner);return view(store.retry(id,owner));}
    // Cancellation must remain possible after a policy upgrade.
    public Map<String,Object> cancel(UUID id,CustomerPrincipal owner){return view(store.cancel(id,owner));}
    private Map<String,Object> view(ExportJobStore.Job job){
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",1);result.put("jobId",job.id());result.put("state",job.state());
        result.put("ownerId",Long.toString(job.customerId()));
        result.put("createdAt",job.createdAt());result.put("expiresAt",job.expiresAt());result.put("updatedAt",job.updatedAt());result.put("policyHash",job.policyHash());
        result.put("recordedBytes",job.recordedBytes());result.put("partCount",job.partCount());result.put("maxBytes",ExportJobStore.MAX_BYTES);result.put("maxPartBytes",ExportSourceClient.MAX_PART_BYTES);result.put("maxTasks",ExportJobStore.MAX_TASKS);
        result.put("progress",store.counts(job.id()));result.put("automaticScopeComplete",job.state().equals("READY"));result.put("complete",false);return result;
    }
    public Map<String,Object> chunks(UUID id,CustomerPrincipal owner,int after,int size){
        return chunks(id,owner,after,size,false);
    }
    public Map<String,Object> chunks(UUID id,CustomerPrincipal owner,int after,int size,boolean raw){
        var job=current(id,owner);var parts=store.parts(id,owner,after,size);List<Map<String,Object>> rows=new ArrayList<>();
        for(var part:parts){var row=new LinkedHashMap<String,Object>();row.put("sequence",part.sequence());row.put("scope",ExportKind.valueOf(part.source()).scope);row.put("parentId",raw?Long.toString(part.parentId()):part.parentId());row.put("detailId",raw?Long.toString(part.detailId()):part.detailId());row.put("cursor",Long.toString(part.cursor()));row.put("bytes",part.bytes());row.put("sha256",part.sha256());row.put("readStartedAt",part.startedAt());row.put("readFinishedAt",part.finishedAt());row.put(raw?"dataJson":"data",raw?part.data():json.parse(part.data().getBytes(java.nio.charset.StandardCharsets.UTF_8)));rows.add(row);}
        int next=parts.isEmpty()?after:parts.getLast().sequence();
        return Map.of("schemaVersion",1,"jobId",id,"ownerId",Long.toString(job.customerId()),"expiresAt",job.expiresAt(),"parts",rows,"nextSequence",next,"hasMore",next<job.partCount(),"complete",false);
    }
    public Map<String,Object> manifest(UUID id,CustomerPrincipal owner){
        var job=current(id,owner);var result=new LinkedHashMap<>(view(job));
        result.put("scope","account-export-manifest");result.put("sources",store.taskSummary(id));result.put("serviceClassification",policy.coverage());result.put("copyBoundaries",policy.copyBoundaries());
        result.put("reviewedOmissions",policy.reviewedOmissions());
        result.put("sourceDefinitions",Arrays.stream(ExportKind.values()).map(kind->Map.of("kind",kind.name(),"service",kind.service+"-service","scope",kind.scope,"optionalAbsence",kind.optional())).toList());
        result.put("separateProofRequired",List.of("chat-conversation-capability","chat-recipient-notifications"));
        result.put("manualReviewRequired",List.of("free-text-notes-labels-raw-metadata-ip-user-agent-other-identities","unprojected-personal-data-in-technical-copies-and-security-notifications"));
        result.put("excluded",List.of("authentication-secrets-and-deduplication-keys","global-product-and-configuration-data"));
        result.put("limitations",List.of("No cross-service point-in-time snapshot; timestamps are recorded per part.","Only current stored business projections are collected; compacted or deleted history is not reconstructed.","READY confirms automatic scope only; manual review, separate proof and external copies remain separate.","Availability ends one hour after creation; physical cleanup is asynchronous and bounded.","Advance processes one part with the current caller JWT; credentials are never persisted."));
        return result;
    }
}
