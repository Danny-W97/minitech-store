package org.demo.billingservice.messaging;

import org.demo.platformevents.EventIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service
public class RejectedEventStore {
    private final EventReceiptRepository receipts;
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.PrivacySql privacy;
    public RejectedEventStore(EventReceiptRepository receipts) { this.receipts=receipts; }
    @Transactional public void reject(String stream,String recordId,Map<String,String> fields,String reason) {
        String id="rejected-"+EventIdentity.hash(stream+":"+recordId);
        if(receipts.existsById(id)) return;
        String rawOrder=fields.get("orderId");Long order=null;
        if(rawOrder!=null&&rawOrder.matches("[1-9][0-9]{0,18}")){try{order=Long.valueOf(rawOrder);}catch(NumberFormatException overflow){/* Unqualified legacy payload requires review. */}}
        if(privacy!=null&&order!=null&&privacy.orderSealed(order)){receipts.saveAndFlush(new EventReceipt(id,EventIdentity.fingerprint(fields),"SUPPRESSED",null,null,null).forOrder(order));return;}
        String payload=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(fields);
        receipts.saveAndFlush(new EventReceipt(id,EventIdentity.fingerprint(fields),"REJECTED",null,reason,payload));
    }
}
