package org.demo.notificationservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class NotificationAccountPrivacyController {
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.JournalReceiptVerifier journal;
    private final NotificationFinancePreparation preparation;private final boolean enabled;
    public NotificationAccountPrivacyController(NotificationFinancePreparation preparation,@Value("${privacy.finance-cleanup.enabled:false}") boolean enabled){this.preparation=preparation;this.enabled=enabled;}
    public record Command(UUID requestId){}
    @PostMapping("/internal/privacy/finance-account/{customer}")
    public ResponseEntity<Map<String,Object>> account(@PathVariable long customer,@RequestBody Command command){
        Access.require(Access.actor().internal()&&"customer-service".equals(Access.actor().service()));
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Finance cleanup activation required");
        journal.require(customer,command.requestId(),"finance");
        var result=preparation.account(customer,command.requestId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"customerId",Long.toString(customer),"requestId",command.requestId(),"state",result.state(),"code",result.code()));
    }
}
