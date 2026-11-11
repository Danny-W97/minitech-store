package org.demo.billingservice.messaging;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class PaymentIdentityCapabilityController {
    private final org.demo.platformsecurity.privacy.PrivacySql privacy;private final boolean enabled;
    public PaymentIdentityCapabilityController(org.demo.platformsecurity.privacy.PrivacySql privacy,@org.springframework.beans.factory.annotation.Value("${messaging.consumer.enabled:true}") boolean enabled){this.privacy=privacy;this.enabled=enabled;}
    @PostMapping("/internal/events/payment-identity-capability")
    public ResponseEntity<Map<String,Object>> capability(){
        Access.require(Access.actor().internal()&&"payment-service".equals(Access.actor().service()));
        if(!enabled||!privacy.postgres()||!Boolean.TRUE.equals(privacy.jdbc().queryForObject("SELECT to_regclass('public.event_identity_aliases') IS NOT NULL AND to_regclass('public.commerce_privacy_locks') IS NOT NULL AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='event_inbox' AND column_name='order_id')",Boolean.class)))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity reader is not ready");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("schemaVersion",1,"service","billing-service","identityVersion",2,"dualReceipts",true));
    }
}
