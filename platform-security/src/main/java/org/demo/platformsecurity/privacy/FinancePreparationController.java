package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class FinancePreparationController {
    @org.springframework.beans.factory.annotation.Autowired private JournalReceiptVerifier journal=new JournalReceiptVerifier("","");
    private final FinancePreparationAdapter adapter;private final boolean enabled;private final String service;
    public FinancePreparationController(FinancePreparationAdapter adapter,boolean enabled,String service){this.adapter=adapter;this.enabled=enabled;this.service=service;}
    public record Command(UUID requestId,long orderId,long orderVersion,String cursor){}
    @PostMapping("/internal/privacy/finance-prepare/{customer}")
    public ResponseEntity<Map<String,Object>> prepare(@PathVariable long customer,@RequestBody Command input){
        Access.require(Access.actor().internal()&&"order-service".equals(Access.actor().service()));
        if(!enabled)throw new ResponseStatusException(HttpStatus.CONFLICT,"Finance cleanup activation required");
        if(customer<1||input==null||input.requestId()==null||input.orderId()<1||input.orderVersion()<0||input.cursor()==null||!input.cursor().matches("[0-9]{1,20}-[0-9]{1,20}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid finance scope");
        journal.require(customer,input.requestId(),"finance");
        var result=adapter.prepare(customer,input.requestId(),input.orderId(),input.orderVersion(),input.cursor());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(Map.of("schemaVersion",1,"service",service,"customerId",Long.toString(customer),"requestId",input.requestId(),"orderId",Long.toString(input.orderId()),"orderVersion",Long.toString(input.orderVersion()),"state",result.state(),"code",result.code(),"cursor",result.cursor()));
    }
}
