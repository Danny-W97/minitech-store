package org.demo.customerservice.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditSubjectTest {
    @Test void explicitSubjectIsSeparateFromActorAndNeverInferredFromText(){
        var repository=mock(AuditEventRepository.class);var service=new AuditService(repository);
        service.recordForCustomer(7L,"LOGIN_SUCCESS","other@fixture.example","CUSTOMER","SUCCESS","private-other-person");
        var captured=ArgumentCaptor.forClass(AuditEvent.class);verify(repository).save(captured.capture());
        assertThat(captured.getValue().getSubjectCustomerId()).isEqualTo(7);assertThat(captured.getValue().getActor()).isEqualTo("other@fixture.example");
    }
    @Test void legacyAndInvalidSubjectEventsRemainUnbound(){
        var repository=mock(AuditEventRepository.class);var service=new AuditService(repository);
        service.record("LOGIN_FAILURE","7","CUSTOMER","REJECTED","");service.recordForCustomer(-1L,"LOGIN_FAILURE","owner@fixture.example","CUSTOMER","REJECTED","");
        var captured=ArgumentCaptor.forClass(AuditEvent.class);verify(repository,times(2)).save(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(e->assertThat(e.getSubjectCustomerId()).isNull());
    }
}
