package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class CommerceCleanupController {
    @org.springframework.beans.factory.annotation.Autowired private JournalReceiptVerifier journal=new JournalReceiptVerifier("","");
    private final CommerceCleanupAdapter adapter;private final boolean enabled;private final String service;
    public CommerceCleanupController(CommerceCleanupAdapter adapter,boolean enabled,String service){this.adapter=adapter;this.enabled=enabled;this.service=service;}
    public record Command(UUID requestId,long cursor){}
    @PostMapping("/internal/privacy/commerce-cleanup/{customer}")
    public ResponseEntity<Map<String,Object>> cleanup(@PathVariable long customer,@RequestBody Command input){
        Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Commerce cleanup activation required");
        if(customer<1||input==null||input.requestId()==null||input.cursor()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid commerce cursor");
        journal.require(customer,input.requestId(),"commerce");
        var result=adapter.cleanup(customer,input.requestId(),input.cursor());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"service",service,"customerId",Long.toString(customer),"requestId",input.requestId(),"outcome",result.outcome(),"code",result.code(),"nextCursor",Long.toString(result.nextCursor())));
    }
}
