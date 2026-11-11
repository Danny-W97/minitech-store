package org.demo.promotionservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponDataExportServiceTest {
    @Test void sourceMustConfirmOwnershipBeforeAnyCouponRead(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var service=new CouponDataExportService(source,orders);
        for(var status:new HttpStatus[]{HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){
            doThrow(new ResponseStatusException(status)).when(orders).requireCustomerOwner(7L);
            for(Runnable request:new Runnable[]{()->service.export(7),()->service.redemptions(7,0,50)})
                assertThatThrownBy(request::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(status));
        }verifyNoInteractions(source);
    }
    @Test void invalidWindowsNeverQueryOrChangeCoupons(){
        var source=mock(DataSource.class);var orders=mock(OrderAccess.class);var service=new CouponDataExportService(source,orders);
        for(Runnable request:new Runnable[]{()->service.redemptions(7,-1,50),()->service.redemptions(7,0,0),()->service.redemptions(7,0,51)})
            assertThatThrownBy(request::run).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(source,orders);
    }
}
