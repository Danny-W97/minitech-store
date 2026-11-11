package org.demo.chatservice.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;


@Component
public class ChatNotificationClient {
    private final RestClient restClient;
    private final String supportEmail;

    public ChatNotificationClient(
            @Value("${services.notification.base-url:http://localhost:18086}") String notificationBaseUrl,
            @Value("${chat.support-email:support@minitech.local}") String supportEmail,
            @Value("${resilience.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${resilience.http.read-timeout-ms:3000}") long readTimeoutMs,
            @Value("${PLATFORM_SERVICE_TOKEN:}") String serviceToken
    ) {
        var factory = new org.demo.platformsecurity.BoundedHttpRequestFactory(connectTimeoutMs,readTimeoutMs);
        this.restClient = RestClient.builder().requestInterceptor(new org.demo.platformsecurity.ServiceAuthenticationInterceptor(serviceToken)).baseUrl(notificationBaseUrl).requestFactory(factory).build();
        this.supportEmail = supportEmail;
    }

    public record Receipt(Long id,String status,String eventType,Long sourceId,String recipient) {
        public Receipt(Long id,String status) { this(id,status,null,null,null); }
    }
    public Receipt send(ChatNotificationStateStore.Ticket ticket) {
        boolean support="SUPPORT".equals(ticket.author());
        var request=new NotificationRequest(ticket.eventType(),ticket.sourceId(),support?ticket.email():supportEmail,"EMAIL",
                support?"Odpowiedź na Twoją wiadomość — MiniTech Store":"Nowa wiadomość z czatu MiniTech Store",
                support?"Dzień dobry "+ticket.name()+",\n\n"+ticket.text()+"\n\nPozdrawiamy,\nMiniTech Store"
                    :"Od: "+ticket.name()+" <"+ticket.email()+">\nTemat: "+ticket.subject()+"\n\n"+ticket.text());
        try {
            // Notification persists failed/manual deliveries too and returns their receipt with 503.
            var receipt=restClient.post().uri("/notifications").body(request).retrieve()
                    .onStatus(status->status.value()==503,(outgoing,incoming)->{}).body(Receipt.class);
            if(receipt==null || receipt.id()==null || receipt.id()<1 || !java.util.Set.of("SENT","PENDING","FAILED").contains(receipt.status())
                    || !ticket.eventType().equals(receipt.eventType()) || !ticket.sourceId().equals(receipt.sourceId())
                    || !request.recipient().equals(receipt.recipient()))
                throw new IllegalStateException("Invalid notification receipt");
            return receipt;
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
