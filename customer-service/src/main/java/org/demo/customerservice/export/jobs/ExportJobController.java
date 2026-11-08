package org.demo.customerservice.export.jobs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping(value="/customers/me/data-export/jobs",produces=MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Private JSON object; see the versioned export coordinator contract",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",schema=@io.swagger.v3.oas.annotations.media.Schema(type="object")))
public class ExportJobController {
    private final ExportCoordinator coordinator;private final ExportJson json;
    public ExportJobController(ExportCoordinator coordinator,ExportJson json){this.coordinator=coordinator;this.json=json;}
    private ResponseEntity<String> response(Object body){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options","nosniff").body(json.write(body));}
    @GetMapping @Operation(summary="Find own unexpired export jobs",description="At most three jobs; allows resuming after losing the local job ID. Never returns another account's jobs or request/lease tokens.")
    public ResponseEntity<String> recent(@AuthenticationPrincipal CustomerPrincipal owner){return response(coordinator.recent(owner));}
    @PostMapping @Operation(summary="Create own temporary account export job",description="Idempotency-Key UUID required. One hour from creation, bounded Customer database chunks, no Redis or filesystem. A43.31; overall export remains partial.")
    public ResponseEntity<String> create(@AuthenticationPrincipal CustomerPrincipal owner,@RequestHeader("Idempotency-Key") UUID key){return response(coordinator.create(owner,key));}
    @GetMapping("/{id}") @Operation(summary="Read own export progress")
    public ResponseEntity<String> status(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id){return response(coordinator.status(id,owner));}
    @PostMapping("/{id}/advance") @Operation(summary="Advance one bounded export part",description="Current customer Bearer forwarded only to fixed source endpoints. Reuse Idempotency-Key to replay the same step; use a new UUID for the next step.")
    public ResponseEntity<String> advance(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestHeader("Authorization") String authorization){return response(coordinator.advance(id,owner,key,authorization));}
    @PostMapping("/{id}/retry") @Operation(summary="Requeue failed own export parts",description="At most eight attempts per part, no extension of expiry. New advance keys required after an acknowledged failure.")
    public ResponseEntity<String> retry(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id){return response(coordinator.retry(id,owner));}
    @PostMapping("/{id}/cancel") @Operation(summary="Cancel own export and revoke chunk access")
    public ResponseEntity<String> cancel(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id){return response(coordinator.cancel(id,owner));}
    @GetMapping("/{id}/chunks") @Operation(summary="Download bounded own export chunks",description="Stable sequence cursor, 1..3 chunks per request. raw=true returns exact stored UTF-8 JSON as dataJson, with string parent/detail IDs, for lossless assembly and SHA-256 verification. Default data object remains supported.")
    public ResponseEntity<String> chunks(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id,@RequestParam(defaultValue="0") int after,@RequestParam(defaultValue="1") int size,@RequestParam(defaultValue="false") boolean raw){return response(coordinator.chunks(id,owner,after,size,raw));}
    @GetMapping("/{id}/manifest") @Operation(summary="Download own export completeness manifest",description="READY is completion of automatic scope only. Manual review, separate Chat capability and external copies are explicitly listed; complete remains false.")
    public ResponseEntity<String> manifest(@AuthenticationPrincipal CustomerPrincipal owner,@PathVariable UUID id){return response(coordinator.manifest(id,owner));}
}
