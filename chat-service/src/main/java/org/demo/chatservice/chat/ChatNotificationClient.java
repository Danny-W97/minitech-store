package org.demo.chatservice.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class ChatNotificationClient {
    private final RestClient restClient;
    private final String supportEmail;

    public ChatNotificationClient(
            @Value("${services.notification.base-url:http://localhost:18086}") String notificationBaseUrl,
            @Value("${chat.support-email:support@minitech.local}") String supportEmail,
            @Value("${resilience.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${resilience.http.read-timeout-ms:3000}") long readTimeoutMs
    ) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build());
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(notificationBaseUrl).requestFactory(factory).build();
        this.supportEmail = supportEmail;
    }

    public void notifyCompany(ChatMessage message) {
        send(new NotificationRequest(
                "CHAT_MESSAGE_RECEIVED", message.getId(), supportEmail,
                "EMAIL", "Nowa wiadomość z czatu MiniTech Store",
                "Od: " + message.getCustomerName() + " <" + message.getCustomerEmail() + ">\n"
                        + "Temat: " + message.getSubject() + "\n\n" + message.getMessage()));
    }

    public void notifyCustomer(ChatMessage message) {
        send(new NotificationRequest(
                "CHAT_REPLY_SENT", message.getId(), message.getCustomerEmail(),
                "EMAIL", "Odpowiedź na Twoją wiadomość — MiniTech Store",
                "Dzień dobry " + message.getCustomerName() + ",\n\n"
                        + message.getReplyMessage() + "\n\nPozdrawiamy,\nMiniTech Store"));
    }

    private void send(NotificationRequest request) {
        try {
            restClient.post().uri("/notifications").body(request).retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            throw new ChatNotificationException(exception);
        }
    }

    private record NotificationRequest(
            String eventType, Long sourceId, String recipient, String channel, String subject, String message,
            boolean simulateFailure
    ) {
        private NotificationRequest(String eventType, Long sourceId, String recipient, String channel, String subject, String message) {
            this(eventType, sourceId, recipient, channel, subject, message, false);
        }
    }

    public static class ChatNotificationException extends RuntimeException {
        public ChatNotificationException(Throwable cause) { super(cause); }
    }
}
