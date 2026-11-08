package org.demo.chatservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat/conversations/{conversationId}/data-export")
@SecurityRequirement(name="chatCapability")
public class ChatDataExportController {
    private final ChatDataExportService service;
    public ChatDataExportController(ChatDataExportService service){this.service=service;}
    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download the stored conversation using its access token",description="X-Chat-Access-Token is required. Account login/email never grants access; no account association is created.")
    public ResponseEntity<ChatDataExport> conversation(@PathVariable long conversationId) {
        allowedRole();return response("chat-conversation-data.json",service.conversation(conversationId,Access.header(Access.CHAT_TOKEN)));
    }
    @GetMapping(value="/entries",produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary="Download a bounded page of conversation messages using its access token",description="Token checked in the same SQL statement as the entries. Only stored public CUSTOMER/SUPPORT messages; no notification workflow or request keys.")
    public ResponseEntity<ChatEntryDataExportPage> entries(@PathVariable long conversationId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        allowedRole();return response("chat-entries-data-page.json",service.entries(conversationId,Access.header(Access.CHAT_TOKEN),cursor,size));
    }
    private void allowedRole() {
        var actor=Access.actor();Access.require("ANONYMOUS".equals(actor.role())
                || "CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
    }
    private <T> ResponseEntity<T> response(String filename,T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+filename+"\"")
                .header("X-Content-Type-Options","nosniff").body(body);
    }
}
