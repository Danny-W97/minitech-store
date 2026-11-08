package org.demo.customerservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/customers/privacy-journal")
public final class PrivacyJournalController {
    private final PrivacyRequestStore store;
    public PrivacyJournalController(PrivacyRequestStore store){this.store=store;}
    @GetMapping("/requests") public ResponseEntity<Map<String,Object>> page(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int size){Access.require(Access.actor().operator());return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(store.journalPage(after,size));}
    public record Receipt(String receipt){}
    @PostMapping("/requests/{request}/ack") public ResponseEntity<Map<String,Object>> acknowledge(@PathVariable UUID request,@RequestBody Receipt input){Access.require(Access.actor().operator());store.acknowledgeJournal(request,input==null?null:input.receipt());return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"acknowledged",true));}
}
