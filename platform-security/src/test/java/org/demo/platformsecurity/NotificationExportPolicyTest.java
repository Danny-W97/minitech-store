package org.demo.platformsecurity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NotificationExportPolicyTest {
    private final Actor owner=new Actor("CUSTOMER",null,1L,"same@fixture.example");
    @Test void exportsNeverGrantOperatorOrServiceBypass(){
        for(String path:new String[]{"/notifications/me/data-export/orders/1","/notifications/chat/data-export/CHAT_REPLY_SENT/1"}){
            assertThat(RoutePolicy.allowed("GET",path,owner)).isTrue();
            for(var actor:new Actor[]{new Actor("OPERATOR",null,null,null),new Actor("SERVICE","notification-service",null,null)})assertThat(RoutePolicy.allowed("GET",path,actor)).isFalse();
            assertThat(RoutePolicy.allowed("POST",path,owner)).isFalse();
        }
        assertThat(RoutePolicy.allowed("GET","/notifications/me/data-export/orders/1",Actor.ANONYMOUS)).isFalse();
        assertThat(RoutePolicy.allowed("GET","/notifications/chat/data-export/CHAT_REPLY_SENT/1",Actor.ANONYMOUS)).isTrue();
    }
    @Test void internalProofPermissionIsSpecificToNotificationServiceAndGet(){
        String path="/internal/chat/notification-sources/CHAT_ENTRY_REPLIED/1/access";
        assertThat(RoutePolicy.allowed("GET",path,new Actor("SERVICE","notification-service",null,null))).isTrue();
        assertThat(RoutePolicy.allowed("GET","/internal/orders/1/access",new Actor("SERVICE","notification-service",null,null))).isTrue();
        for(var actor:new Actor[]{owner,Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","chat-service",null,null)})assertThat(RoutePolicy.allowed("GET",path,actor)).isFalse();
        assertThat(RoutePolicy.allowed("POST",path,new Actor("SERVICE","notification-service",null,null))).isFalse();
    }
    @Test void newExportsDoNotOpenOperationalNotificationApisToCustomers(){
        for(String path:new String[]{"/notifications","/notifications/1","/notifications/source/1","/notifications/dead-letter","/notifications/dead-letter/1/replay"})
            assertThat(RoutePolicy.allowed("GET",path,owner)).isFalse();
    }
}
