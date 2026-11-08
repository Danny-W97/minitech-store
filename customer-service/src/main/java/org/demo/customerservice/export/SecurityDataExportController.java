package org.demo.customerservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customers/me/data-export/security")
@SecurityRequirement(name="bearerAuth")
public class SecurityDataExportController {
    private final SecurityDataExportService service;
    public SecurityDataExportController(SecurityDataExportService service){this.service=service;}
    @GetMapping("/methods") @Operation(summary="Download own stored TOTP method metadata",description="No secrets or counters; free text requires manual subject verification and redaction.")
    public ResponseEntity<SecurityDataExport> methods(@AuthenticationPrincipal CustomerPrincipal p){return response("security-methods.json",service.methods(p));}
    @GetMapping("/events") @Operation(summary="Download a bounded page of own safe security events",description="Explicit subject id or strict legacy stable-id refresh proof. No email inference, raw metadata, actor, IP or User-Agent.")
    public ResponseEntity<SecurityDataExportPage<SecurityDataExport.Event>> events(@AuthenticationPrincipal CustomerPrincipal p,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size){return response("security-events-page.json",service.events(p,cursor,size));}
    @GetMapping("/passkeys") @Operation(summary="Download a bounded page of own passkey metadata",description="Non-credential paging id, dates and fixed booleans only. Labels and credential/public key/attestation/counters excluded.")
    public ResponseEntity<SecurityDataExportPage<SecurityDataExport.Passkey>> passkeys(@AuthenticationPrincipal CustomerPrincipal p,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size){return response("security-passkeys-page.json",service.passkeys(p,cursor,size));}
    @GetMapping("/recovery-codes") @Operation(summary="Download a bounded page of own recovery-code lifecycle metadata",description="Record id, creation and use date only; no raw codes or hashes, no consumption or regeneration.")
    public ResponseEntity<SecurityDataExportPage<SecurityDataExport.Recovery>> recovery(@AuthenticationPrincipal CustomerPrincipal p,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size){return response("security-recovery-metadata-page.json",service.recovery(p,cursor,size));}
    private static <T> ResponseEntity<T> response(String filename,T body){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore().cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"").header("X-Content-Type-Options","nosniff").body(body);}
}
