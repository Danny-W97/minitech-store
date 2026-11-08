package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

@RestController
public class AccountCleanupController {
    @org.springframework.beans.factory.annotation.Autowired private JournalReceiptVerifier journal=new JournalReceiptVerifier("","");
    private final AccountCleanupAdapter adapter;private final boolean enabled;private final String service;
    public AccountCleanupController(AccountCleanupAdapter adapter,boolean enabled,String service){this.adapter=adapter;this.enabled=enabled;this.service=service;}
    public record Command(UUID requestId){}
    @PostMapping("/internal/privacy/account-cleanup/{customer}")
    public ResponseEntity<Map<String,Object>> cleanup(@PathVariable long customer,@RequestBody Command input){
        Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Account cleanup activation required");
        journal.require(customer,input.requestId(),"core");
        var result=adapter.cleanup(customer,input.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"service",service,"customerId",Long.toString(customer),"requestId",input.requestId(),"outcome",result.outcome(),"code",result.code()));
    }
}
