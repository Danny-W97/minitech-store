package org.demo.notificationservice.export;

import org.demo.platformsecurity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class NotificationChatAccess {
    public record Proof(String eventType,long sourceId,long conversationId,String recipientHash) {}
    private final RestClient client;
    public NotificationChatAccess(RestClient.Builder builder,@Value("${services.chat.base-url:http://localhost:18093}")String url) {
        client=builder.clone().baseUrl(url).build();
    }
    public Proof require(String type,long id,String capability) {
        if(capability==null||!capability.matches("[A-Za-z0-9_-]{43,128}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Conversation access token required");
        Proof proof;
        try {
            proof=client.get().uri("/internal/chat/notification-sources/{type}/{id}/access",type,id)
                    .header(Access.CHAT_TOKEN,capability).retrieve().body(Proof.class);
        } catch(HttpClientErrorException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification source not found");
        } catch(RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chat authorization unavailable");
        }
        if(proof==null||!type.equals(proof.eventType())||id!=proof.sourceId()||proof.conversationId()<1
                ||proof.recipientHash()==null||!proof.recipientHash().matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Chat authorization unavailable");
        return proof;
    }
}
