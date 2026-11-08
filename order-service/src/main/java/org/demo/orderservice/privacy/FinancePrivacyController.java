package org.demo.orderservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class FinancePrivacyController {
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.JournalReceiptVerifier journal;
    private final FinancePrivacyJobs jobs;private final boolean enabled;
    public FinancePrivacyController(FinancePrivacyJobs jobs,@Value("${privacy.finance-cleanup.enabled:false}") boolean enabled){this.jobs=jobs;this.enabled=enabled;}
    public record Command(UUID requestId,String policyHash,long retryGeneration){}
    @PostMapping("/internal/privacy/finance-consent/{customer}")
    public ResponseEntity<Map<String,Object>> consent(@PathVariable long customer,@RequestBody Command command){
        Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Finance cleanup activation required");
        if(customer<1||command==null||command.requestId()==null||command.policyHash()==null||command.retryGeneration()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid finance scope");
        journal.require(customer,command.requestId(),"finance");
        jobs.consent(customer,command.requestId(),command.policyHash(),command.retryGeneration());jobs.attachJournal(customer,command.requestId(),org.demo.platformsecurity.Access.header(org.demo.platformsecurity.privacy.JournalReceiptVerifier.HEADER));return response(jobs.progress(customer,command.requestId()));
    }
    public record Query(UUID requestId){}
    @PostMapping("/internal/privacy/finance-progress/{customer}")
    public ResponseEntity<Map<String,Object>> progress(@PathVariable long customer,@RequestBody Query command){Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));if(customer<1||command==null||command.requestId()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid finance scope");return response(jobs.progress(customer,command.requestId()));}
    private ResponseEntity<Map<String,Object>> response(Map<String,Object> data){return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(data);}
}
