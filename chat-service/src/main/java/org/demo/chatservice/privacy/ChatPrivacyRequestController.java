package org.demo.chatservice.privacy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping("/chat/conversations/{conversationId}/privacy-request")
@SecurityRequirement(name="chatCapability")
public class ChatPrivacyRequestController {
    private final ChatPrivacyRequestStore store;
    public ChatPrivacyRequestController(ChatPrivacyRequestStore store){this.store=store;}
    public record Confirmation(String confirmation){}
    private void role(){var actor=Access.actor();Access.require("ANONYMOUS".equals(actor.role())||"CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);}
    private ResponseEntity<ChatPrivacyRequestStatus> response(HttpStatus status,ChatPrivacyRequestStatus body){return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate()).header("X-Content-Type-Options","nosniff").body(body);}
    @PostMapping
    @Operation(summary="Request a manual review of conversation erasure",description="Requires the conversation token, a UUID Idempotency-Key and confirmation REQUEST_CHAT_DELETION_REVIEW. Records intent only; never erases or sends notifications.")
    public ResponseEntity<ChatPrivacyRequestStatus> submit(@PathVariable long conversationId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Confirmation input){
        role();if(input==null||!"REQUEST_CHAT_DELETION_REVIEW".equals(input.confirmation()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit conversation review confirmation required");
        return response(HttpStatus.ACCEPTED,store.submit(conversationId,Access.header(Access.CHAT_TOKEN),key));
    }
    @GetMapping
    @Operation(summary="Read safe status of the conversation's manual erasure request",description="The token of this conversation is required on every read. No account/email inference, conversation text or automatic erasure.")
    public ResponseEntity<ChatPrivacyRequestStatus> get(@PathVariable long conversationId){role();return response(HttpStatus.OK,store.get(conversationId,Access.header(Access.CHAT_TOKEN)));}
}
