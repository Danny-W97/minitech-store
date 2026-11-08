package org.demo.notificationservice.web;

import org.demo.notificationservice.notification.*;
import org.demo.platformsecurity.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSubjectControllerTest {
    final NotificationService service=mock(NotificationService.class);
    final NotificationController controller=new NotificationController(service,mock(NotificationDeadLetterService.class));
    void actor(Actor actor){var request=new MockHttpServletRequest();request.setAttribute(Access.ACTOR,actor);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));}
    CreateNotificationRequest request(NotificationEventType type,Long customer){return new CreateNotificationRequest(type,71L,"owner@fixture.example",NotificationChannel.EMAIL,"Subject","Message",false,null,customer);}
    @BeforeEach void setup(){actor(new Actor("SERVICE","customer-service",null,null));var response=mock(NotificationResponse.class);when(response.status()).thenReturn(NotificationStatus.SENT);lenient().when(service.create(any())).thenReturn(response);}
    @AfterEach void cleanup(){RequestContextHolder.resetRequestAttributes();}
    @Test void onlyCustomerServiceMayAssertAnAccountSubject(){
        for(var identity:java.util.List.of(Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","payment-service",null,null),new Actor("CUSTOMER",null,17L,"owner@fixture.example"))){
            actor(identity);assertThatThrownBy(()->controller.create(request(NotificationEventType.PASSWORD_RESET_REQUESTED,17L))).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(service);
    }
    @Test void resetTokenIdNeverBecomesCustomerId(){
        controller.create(request(NotificationEventType.PASSWORD_RESET_REQUESTED,17L));
        var captor=org.mockito.ArgumentCaptor.forClass(CreateNotificationRequest.class);verify(service).create(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(17L);assertThat(captor.getValue().sourceId()).isEqualTo(71L);
        assertThatThrownBy(()->controller.create(request(NotificationEventType.PASSWORD_RESET_REQUESTED,null))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void legacyRegistrationSourceIsQualifiedButMismatchedSubjectsAreRejected(){
        controller.create(request(NotificationEventType.CUSTOMER_REGISTERED,null));
        var captor=org.mockito.ArgumentCaptor.forClass(CreateNotificationRequest.class);verify(service).create(captor.capture());assertThat(captor.getValue().customerId()).isEqualTo(71L);
        assertThatThrownBy(()->controller.create(request(NotificationEventType.CUSTOMER_REGISTERED,17L))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.create(request(NotificationEventType.PAYMENT_SUCCEEDED,17L))).isInstanceOf(ResponseStatusException.class);
    }
}
