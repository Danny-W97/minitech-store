package org.demo.billingservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvoiceDataExportServiceTest {
    @Test void everyReadWaitsForAuthoritativeOrderOwnership(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var service=new InvoiceDataExportService(source,orders);
        for(var status:new HttpStatus[]{HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}) {
            doThrow(new ResponseStatusException(status,"fixture")).when(orders).requireCustomerOwner(99L);
            for(Runnable read:new Runnable[]{()->service.invoice(99),()->service.items(99,0,50),()->service.corrections(99,0,50),()->service.invoiceId(99),()->service.correctionId(99,7)})
                assertThatThrownBy(read::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode()).isEqualTo(status));
        }
        verifyNoInteractions(source);
    }
}
