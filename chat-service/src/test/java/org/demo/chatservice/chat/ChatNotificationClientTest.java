package org.demo.chatservice.chat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ChatNotificationClientTest {
    @Test void failedPersistedReceiptIsAcceptedButGenericOrMismatched503IsNot() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var response=new java.util.concurrent.atomic.AtomicReference<>("{\"id\":7,\"status\":\"FAILED\",\"eventType\":\"CHAT_ENTRY_REPLIED\",\"sourceId\":12,\"recipient\":\"guest@example.test\"}");
        server.createContext("/notifications",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(503,body.length);
            try(var stream=exchange.getResponseBody()){stream.write(body);}
        });
        server.start();
        try {
            var client=new ChatNotificationClient("http://127.0.0.1:"+server.getAddress().getPort(),"support@example.test",1000,1000,"fixture-"+java.util.UUID.randomUUID());
            var ticket=new ChatNotificationStateStore.Ticket(12L,1L,"lease","CHAT_ENTRY_REPLIED",12L,"SUPPORT","Answer","Guest","guest@example.test","Question");
            assertEquals("FAILED",client.send(ticket).status());
            response.set("{\"error\":\"Unavailable\"}");
            assertThrows(RuntimeException.class,()->client.send(ticket));
            response.set("{\"id\":7,\"status\":\"SENT\",\"eventType\":\"CHAT_ENTRY_REPLIED\",\"sourceId\":99,\"recipient\":\"guest@example.test\"}");
            assertThrows(RuntimeException.class,()->client.send(ticket));
        } finally { server.stop(0); }
    }
}
