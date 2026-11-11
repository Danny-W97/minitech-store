package org.demo.deliveryservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShipmentDataExportServiceTest {
    @Test void shipmentDatabaseIsNeverReadWhenOwnershipCannotBeVerified(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);
        var service=new ShipmentDataExportService(source,orders);
        for(var status:new HttpStatus[]{HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){
            doThrow(new ResponseStatusException(status,"fixture")).when(orders).requireCustomerOwner(99L);
            assertThatThrownBy(()->service.export(99)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error->assertThat(error.getStatusCode()).isEqualTo(status));
        }
        verifyNoInteractions(source);
    }
}
