package org.demo.orderservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class OrderPrivacyController {
    private final OrderPrivacySource source;
    public OrderPrivacyController(OrderPrivacySource source){this.source=source;}
    public record Cursor(UUID requestId,long cursor){}
    @PostMapping("/internal/privacy/commerce-orders/{customer}")
    public ResponseEntity<Map<String,Object>> next(@PathVariable long customer,@RequestBody Cursor input){
        Access.require(Access.actor().internal()&&Set.of("customer-service","inventory-service","promotion-service","delivery-service","returns-service","payment-service","billing-service","notification-service").contains(Access.actor().service()));
        if(customer<1||input==null||input.requestId()==null||input.cursor()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid commerce cursor");
        var row=source.next(customer,input.requestId(),input.cursor());var body=new LinkedHashMap<String,Object>();body.put("schemaVersion",1);body.put("customerId",Long.toString(customer));body.put("requestId",input.requestId());
        body.put("order",row.map(r->{var data=new LinkedHashMap<String,Object>(Map.of("orderId",Long.toString(r.orderId()),"eligibility",r.eligibility(),"financeCleared",r.financeCleared(),"status",r.status(),"reservationId",Long.toString(r.reservationId()),"shipmentExpected",r.shipmentExpected(),"couponExpected",r.couponExpected()));data.put("orderVersion",Long.toString(r.orderVersion()));data.put("amount",r.amount().toPlainString());data.put("paymentState",r.paymentState());data.put("invoiceRequested",r.invoiceRequested());return data;}).orElse(null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}
