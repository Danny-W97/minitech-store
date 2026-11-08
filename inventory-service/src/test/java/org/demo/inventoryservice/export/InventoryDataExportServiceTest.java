package org.demo.inventoryservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryDataExportServiceTest {
    @Test void ownerMustBeConfirmedBeforeHeaderOrItemsCanReadInventory(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var service=new InventoryDataExportService(source,orders);
        for(var status:new HttpStatus[]{HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){
            doThrow(new ResponseStatusException(status)).when(orders).requireCustomerOwner(7L);
            for(Runnable request:new Runnable[]{()->service.export(7),()->service.items(7,0,50)})
                assertThatThrownBy(request::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
        }verifyNoInteractions(source);
    }
    @Test void invalidWindowsNeverContactSourceOrDatabase(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var service=new InventoryDataExportService(source,orders);
        for(Runnable request:new Runnable[]{()->service.items(7,-1,50),()->service.items(7,0,0),()->service.items(7,0,51)})
            assertThatThrownBy(request::run).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(source,orders);
    }
}
