package org.demo.paymentservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class PaymentFinanceEventsController {
    private final PaymentFinancePreparation preparation;
    public PaymentFinanceEventsController(PaymentFinancePreparation preparation){this.preparation=preparation;}
    public record Query(UUID requestId,long orderVersion){}
    @PostMapping("/internal/privacy/finance-events/{customer}/{order}")
    public ResponseEntity<Map<String,Object>> events(@PathVariable long customer,@PathVariable long order,@RequestBody Query query){
        Access.require(Access.actor().internal()&&Set.of("billing-service","notification-service").contains(Access.actor().service()));
        if(customer<1||order<1||query==null||query.requestId()==null||query.orderVersion()<0)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid finance scope");
        var ids=preparation.events(customer,query.requestId(),order,query.orderVersion());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"customerId",Long.toString(customer),"requestId",query.requestId(),"orderId",Long.toString(order),"orderVersion",Long.toString(query.orderVersion()),"state","PREPARED","eventIds",ids));
    }
}
