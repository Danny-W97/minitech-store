package org.demo.chatservice.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.demo.chatservice.chat.ChatMessageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/chat/messages")
@Tag(name = "Customer Chat", description = "Customer chat messages and support replies")
public class ChatController {
    private final ChatMessageService service;

    public ChatController(ChatMessageService service,org.demo.chatservice.chat.ChatNotificationStateStore notificationStates) {
        this.service = service;this.notificationStates=notificationStates;
    }

    @PostMapping
    @Operation(summary = "Send a customer chat message")
    public ResponseEntity<ChatMessageResponse> create(@Valid @RequestBody CreateChatMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    @Operation(summary = "List customer chat messages")
    public ResponseEntity<List<ChatMessageResponse>> all(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") long cursor,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
            @org.springframework.web.bind.annotation.RequestParam(required = false) org.demo.chatservice.chat.ChatMessageStatus status,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "") String search) {
        return service.list(status, search, new org.demo.platformsecurity.ListWindow(cursor, size)).response();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one customer chat message")
    public ChatMessageResponse get(@PathVariable Long id) { return service.get(id); }

    @PostMapping("/{id}/reply")
    @Operation(summary = "Reply to a customer chat message")
    public ChatMessageResponse reply(@PathVariable Long id, @Valid @RequestBody ReplyChatMessageRequest request) {
        return service.reply(id, request);
    }

    @GetMapping("/{id}/entries")
    public ChatMessageService.History history(@PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") long before,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="50") int size) {
        return service.history(id,before,size);
    }
    @PostMapping("/{id}/entries")
    public ChatMessageResponse followUp(@PathVariable Long id,@Valid @RequestBody ReplyChatMessageRequest request) {
        return service.followUp(id,request);
    }
    @PostMapping("/{id}/entries/{entryId}/retry-notification")
    public void retryNotification(@PathVariable Long id,@PathVariable Long entryId) {
        notificationStates.replay(id,entryId);
    }
    private final org.demo.chatservice.chat.ChatNotificationStateStore notificationStates;
}
