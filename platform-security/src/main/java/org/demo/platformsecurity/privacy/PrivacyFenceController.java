package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class PrivacyFenceController {
    private final PrivacyFenceStore store;private final String service;
    public PrivacyFenceController(PrivacyFenceStore store,String service){this.store=store;this.service=service;}
    public record Command(UUID requestId){}
    @PostMapping("/internal/privacy/fences/{customer}")
    public ResponseEntity<Map<String,Object>> fence(@PathVariable long customer,@RequestBody Command command) {
        Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));
        store.fence(customer,command.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"service",service,"customerId",Long.toString(customer),"requestId",command.requestId(),"state","BLOCKED"));
    }
}
