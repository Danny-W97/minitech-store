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

    public ChatController(ChatMessageService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Send a customer chat message")
    public ResponseEntity<ChatMessageResponse> create(@Valid @RequestBody CreateChatMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    @Operation(summary = "List customer chat messages")
    public List<ChatMessageResponse> all() { return service.all(); }

    @GetMapping("/{id}")
    @Operation(summary = "Get one customer chat message")
    public ChatMessageResponse get(@PathVariable Long id) { return service.get(id); }

    @PostMapping("/{id}/reply")
    @Operation(summary = "Reply to a customer chat message")
    public ChatMessageResponse reply(@PathVariable Long id, @Valid @RequestBody ReplyChatMessageRequest request) {
        return service.reply(id, request);
    }
}
