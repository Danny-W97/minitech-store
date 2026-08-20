package org.demo.chatservice.chat;

import org.demo.chatservice.chat.ChatNotificationClient.ChatNotificationException;
import org.demo.chatservice.web.ChatException;
import org.demo.chatservice.web.ChatMessageResponse;
import org.demo.chatservice.web.CreateChatMessageRequest;
import org.demo.chatservice.web.ReplyChatMessageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChatMessageService {
    private final ChatMessageRepository repository;
    private final ChatNotificationClient notificationClient;

    public ChatMessageService(ChatMessageRepository repository, ChatNotificationClient notificationClient) {
        this.repository = repository;
        this.notificationClient = notificationClient;
    }

    @Transactional
    public ChatMessageResponse create(CreateChatMessageRequest request) {
        ChatMessage message = repository.save(new ChatMessage(
                request.customerName(), request.customerEmail(), request.subject(), request.message()));
        try {
            notificationClient.notifyCompany(message);
            message.markNotification("SENT");
        } catch (ChatNotificationException exception) {
            message.markNotification("FAILED");
        }
        return ChatMessageResponse.from(repository.save(message));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> all() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(ChatMessageResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ChatMessageResponse get(Long id) {
        return ChatMessageResponse.from(find(id));
    }

    @Transactional
    public ChatMessageResponse reply(Long id, ReplyChatMessageRequest request) {
        ChatMessage message = find(id);
        message.addReply(request.message());
        try {
            notificationClient.notifyCustomer(message);
            message.markReplyNotification("SENT");
        } catch (ChatNotificationException exception) {
            message.markReplyNotification("FAILED");
        }
        return ChatMessageResponse.from(repository.save(message));
    }

    private ChatMessage find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, "Nie znaleziono wiadomości czatu."));
    }
}
