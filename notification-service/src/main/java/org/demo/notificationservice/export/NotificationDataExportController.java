package org.demo.notificationservice.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationDataExportController {
    private final NotificationDataExportService service;
    public NotificationDataExportController(NotificationDataExportService service){this.service=service;}
    @GetMapping(value="/notifications/me/data-export/orders/{orderId}",produces=MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name="bearerAuth")
    @Operation(summary="Download a bounded page of stored notifications for your own order",description="Order ownership checked on every request. No email matching, sends or replay. Partial export; security notification links and delivery diagnostics excluded.")
    public ResponseEntity<NotificationDataExportPage> order(@PathVariable long orderId,@RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        var actor=Access.actor();Access.require("CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
        return response(service.order(orderId,cursor,size));
    }
    @GetMapping(value="/notifications/chat/data-export/{eventType}/{sourceId}",produces=MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name="chatCapability")
    @Operation(summary="Download stored customer-directed notifications using the conversation token",description="Only CHAT_REPLY_SENT (conversation id) and CHAT_ENTRY_REPLIED (SUPPORT entry id). X-Chat-Access-Token required; login/email never replaces it. Support-directed notifications excluded by user decision.")
    public ResponseEntity<NotificationDataExportPage> chat(@PathVariable String eventType,@PathVariable long sourceId,
            @RequestParam(defaultValue="0")long cursor,@RequestParam(defaultValue="50")int size) {
        var actor=Access.actor();Access.require("ANONYMOUS".equals(actor.role())
                || "CUSTOMER".equals(actor.role())&&actor.customerId()!=null&&actor.customerId()>0);
        return response(service.chat(eventType,sourceId,Access.header(Access.CHAT_TOKEN),cursor,size));
    }
    private static ResponseEntity<NotificationDataExportPage> response(NotificationDataExportPage body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"notification-data-page.json\"")
                .header("X-Content-Type-Options","nosniff").body(body);
    }
}
