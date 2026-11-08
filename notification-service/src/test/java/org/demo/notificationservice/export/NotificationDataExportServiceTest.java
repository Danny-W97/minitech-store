package org.demo.notificationservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationDataExportServiceTest {
    @Test void sourceIsVerifiedBeforeAnyNotificationRead(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var chat=mock(NotificationChatAccess.class);
        var service=new NotificationDataExportService(source,orders,chat);
        for(var status:new HttpStatus[]{HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){
            doThrow(new ResponseStatusException(status)).when(orders).requireCustomerOwner(7L);
            assertThatThrownBy(()->service.order(7,0,50)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
        }
        verifyNoInteractions(source,chat);
    }
    @Test void invalidWindowsDoNotQuerySourcesOrDatabase(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var chat=mock(NotificationChatAccess.class);
        var service=new NotificationDataExportService(source,orders,chat);
        for(int size:new int[]{0,51})assertThatThrownBy(()->service.order(7,0,size)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.order(7,-1,50)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(source,orders,chat);
    }
    @Test void chatSourceFailureNeverReadsNotifications(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var chat=mock(NotificationChatAccess.class);
        var service=new NotificationDataExportService(source,orders,chat);
        for(var status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){
            doThrow(new ResponseStatusException(status)).when(chat).require("CHAT_REPLY_SENT",7,"C".repeat(43));
            assertThatThrownBy(()->service.chat("CHAT_REPLY_SENT",7,"C".repeat(43),0,50)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
        }verifyNoInteractions(source,orders);
    }
    @Test void supportDirectedAccountAndUnknownTypesAreExcludedBeforeAnySourceOrDatabaseRead(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var chat=mock(NotificationChatAccess.class);
        var service=new NotificationDataExportService(source,orders,chat);
        for(String type:new String[]{"CHAT_MESSAGE_RECEIVED","CHAT_ENTRY_RECEIVED","PASSWORD_RESET_REQUESTED","PAYMENT_SUCCEEDED","UNKNOWN"})
            assertThatThrownBy(()->service.chat(type,7,"C".repeat(43),0,50)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(source,orders,chat);
    }
}
