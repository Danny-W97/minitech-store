package org.demo.customerservice.privacy;

import org.demo.customerservice.auth.*;
import org.demo.customerservice.export.jobs.ExportJson;
import org.demo.platformsecurity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class PrivacyController {
    private final PrivacyRequestStore store;private final JwtService jwt;private final PasswordEncoder passwords;private final LoginProtectionService protection;private final ExportJson json;
    private final String cookie,cookiePath;private final boolean secure;
    @Value("${privacy.account-cleanup.enabled:false}") private boolean accountCleanupEnabled;
    @Value("${privacy.commerce-cleanup.enabled:false}") private boolean commerceCleanupEnabled;
    @Value("${privacy.finance-cleanup.enabled:false}") private boolean financeCleanupEnabled;
    public PrivacyController(PrivacyRequestStore store,JwtService jwt,PasswordEncoder passwords,LoginProtectionService protection,ExportJson json,
            @Value("${customer.auth.refresh-cookie-name:MINITECH_REFRESH_TOKEN}") String cookie,
            @Value("${customer.auth.refresh-cookie-path:/api/customers}") String cookiePath,@Value("${customer.auth.refresh-cookie-secure:false}") boolean secure){this.store=store;this.jwt=jwt;this.passwords=passwords;this.protection=protection;this.json=json;this.cookie=cookie;this.cookiePath=cookiePath;this.secure=secure;}
    public record Confirmation(String planHash,String password,String statusToken,String confirmation){}
    private ResponseEntity<String> response(Object data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options","nosniff").body(json.write(data));}
    private Map<String,Object> view(PrivacyRequestStore.Request r,boolean preview){
        var data=new LinkedHashMap<String,Object>();data.put("schemaVersion",1);data.put("requestId",r.id());data.put("phase",r.phase());data.put("createdAt",r.created());data.put("confirmedAt",r.confirmed());data.put("statusAvailableUntil",r.statusExpires());data.put("complete",false);data.put("dataErased",false);
        if(preview){data.put("ownerId",Long.toString(r.customer()));data.put("planHash",r.hash());data.put("confirmBefore",r.previewExpires());data.put("plannedScope",PrivacyPlan.SCOPES);
            data.put("effects",List.of("Confirming immediately revokes sign-in and new account operations; there is no self-service undo.","Fencing drains admitted customer writes before subsequent erasure adapters may run.","When separately activated, the reviewed account cleanup removes profile/credentials, Wishlist, recommendation logs and review content/rating; deduplication IDs remain. It does not cancel/refund purchases.","Commerce cleanup clears contact/address and verified closed return text only after local workflow and finance reconciliation; stable business IDs, quantities, amounts, tracking, coupon codes and hashes remain pseudonymous. Active or ambiguous processes wait, without cancelling purchases or freeing limits. Activation and retention require A43.40.","Financial documents and registers remain until A43.40. Separately activated A43.36 removes reconciled technical contact/free text, preserves pseudonymous identity aliases/hashes without automatic TTL, and waits on active, ambiguous, provider and unresolved DLQ work. Old pending events retain their protocol.","Chat requires separate capability proof; no email linking.","Cleanup waits for a signed write receipt from an independent recovery journal before it can begin.","Only customer ID/request ID/block state remain in durable fences; their retention is pending A43.40.","Use a new random status token before confirmation; only its hash is stored, valid for seven days. Lost tokens cannot be recovered by email.","Random login challenges expire with their existing five-minute TTL and cannot log in; email rate limits remain. Documents, audit, Chat and other copies need separate reviewed procedures."));
        }else{data.put("services",store.services(r.id()));data.put("accountCleanupEnabled",accountCleanupEnabled);data.put("accountCleanup",store.accountCleanupStatus(r.id()));data.put("commerceCleanupEnabled",commerceCleanupEnabled);data.put("commerceCleanup",store.commerceCleanupStatus(r.id()));data.put("financeCleanupEnabled",financeCleanupEnabled);data.put("financeCleanup",store.financeStatus(r.id()));data.put("journal",store.journalStatus(r.id()));data.put("remainingScope",List.of("A43.38","A43.39","A43.40","A43.41"));}
        return data;
    }
    @PostMapping("/customers/me/privacy-requests/preview")
    public ResponseEntity<String> preview(@AuthenticationPrincipal CustomerPrincipal p,@RequestHeader("Idempotency-Key") UUID key){return response(view(store.preview(p,key),true));}
    @PostMapping("/customers/me/privacy-requests/{id}/confirm")
    public ResponseEntity<String> confirm(@AuthenticationPrincipal CustomerPrincipal p,@PathVariable UUID id,@RequestHeader("Authorization") String bearer,@RequestBody Confirmation input){
        if(input==null||!"DELETE_MY_ACCOUNT".equals(input.confirmation())||input.planHash()==null||!input.planHash().matches("[0-9a-f]{64}")||input.password()==null||input.password().isEmpty()||input.password().length()>256||input.statusToken()==null||!input.statusToken().matches("[A-Za-z0-9_-]{43,128}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit privacy confirmation, current password, plan hash and random status token are required");
        if(p==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Customer authentication required");
        protection.assertNotLocked(p.email());PrivacyRequestStore.Request result;
        try{result=store.confirm(id,p,input.planHash(),input.password(),input.statusToken(),jwt.authenticationMethods(bearer.startsWith("Bearer ")?bearer.substring(7):""),passwords);}
        catch(org.demo.customerservice.customer.InvalidCredentialsException failure){protection.recordFailure(p.email());throw failure;}
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.SET_COOKIE,ResponseCookie.from(cookie,"").httpOnly(true).secure(secure).path(cookiePath).maxAge(0).sameSite("Lax").build().toString()).body(json.write(view(result,false)));
    }
    @GetMapping("/customers/privacy-requests/{id}/status")
    public ResponseEntity<String> status(@PathVariable UUID id,@RequestHeader(value="X-Privacy-Status-Token",required=false) String capability){return response(view(store.status(id,capability),false));}
    private void operator(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_OPERATOR")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Operator authentication required");}
    @GetMapping("/customers/privacy-requests/{id}/operator-status")
    public ResponseEntity<String> operatorStatus(@PathVariable UUID id){operator();return response(view(store.operator(id),false));}
    @PostMapping("/customers/privacy-requests/{id}/retry")
    public ResponseEntity<String> retry(@PathVariable UUID id){operator();return response(view(store.retry(id),false));}
}
