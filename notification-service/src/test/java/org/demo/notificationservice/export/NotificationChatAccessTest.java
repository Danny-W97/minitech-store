package org.demo.notificationservice.export;

import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NotificationChatAccessTest {
    private MockRestServiceServer server;private NotificationChatAccess access;
    @BeforeEach void setup(){var builder=RestClient.builder().requestInterceptor(new ServiceAuthenticationInterceptor("own-service"));
        server=MockRestServiceServer.bindTo(builder).build();access=new NotificationChatAccess(builder,"http://fixture");}
    @Test void minimalProofUsesOwnServiceIdentityAndSeparateCapability(){
        server.expect(requestTo("http://fixture/internal/chat/notification-sources/CHAT_ENTRY_REPLIED/4/access"))
                .andExpect(header("Authorization","Bearer own-service")).andExpect(header(Access.CHAT_TOKEN,"C".repeat(43)))
                .andRespond(withSuccess("{\"eventType\":\"CHAT_ENTRY_REPLIED\",\"sourceId\":4,\"conversationId\":9,\"recipientHash\":\""+"a".repeat(64)+"\"}",MediaType.APPLICATION_JSON));
        assertThat(access.require("CHAT_ENTRY_REPLIED",4,"C".repeat(43)).conversationId()).isEqualTo(9);server.verify();
    }
    @Test void malformedCapabilityNeverContactsSource(){
        for(String token:new String[]{null,"","short","/".repeat(43)})status(401,()->access.require("CHAT_REPLY_SENT",4,token));server.verify();
    }
    @Test void missingSourceIsIndistinguishableFromWrongCapability(){
        server.expect(anything()).andRespond(withResourceNotFound());status(404,()->access.require("CHAT_REPLY_SENT",4,"C".repeat(43)));server.verify();
    }
    @Test void sourceFailureFailsClosed(){
        server.expect(anything()).andRespond(withServerError());status(503,()->access.require("CHAT_REPLY_SENT",4,"C".repeat(43)));server.verify();
    }
    @Test void inconsistentProofFailsClosed(){
        for(String body:new String[]{"{\"eventType\":\"CHAT_REPLY_SENT\",\"sourceId\":99,\"conversationId\":9,\"recipientHash\":\""+"a".repeat(64)+"\"}",
                "{\"eventType\":\"CHAT_REPLY_SENT\",\"sourceId\":4,\"conversationId\":9}"})
            server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        for(int i=0;i<2;i++)status(503,()->access.require("CHAT_REPLY_SENT",4,"C".repeat(43)));server.verify();
    }
    private static void status(int code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(code));}
}
