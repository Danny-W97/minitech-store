package org.demo.platformsecurity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReservationExportPolicyTest {
    @Test void reservationExportsRequireAPositiveCustomerIdentityAndGet(){
        for(String path:new String[]{"/inventory/me/data-export/orders/7","/inventory/me/data-export/orders/7/items","/promotions/me/data-export/orders/7","/promotions/me/data-export/orders/7/redemptions"}){
            assertThat(RoutePolicy.allowed("GET",path,new Actor("CUSTOMER",null,1L,"owner@fixture.example"))).isTrue();
            for(var actor:new Actor[]{Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","order-service",null,null),new Actor("CUSTOMER",null,null,"x"),new Actor("CUSTOMER",null,0L,"x")})
                assertThat(RoutePolicy.allowed("GET",path,actor)).isFalse();
            assertThat(RoutePolicy.allowed("POST",path,new Actor("CUSTOMER",null,1L,"x"))).isFalse();
        }
    }
    @Test void sourceProofGrantsOnlyReadOfExactOrderAccessRoute(){
        for(String name:new String[]{"inventory-service","promotion-service"}){
            var actor=new Actor("SERVICE",name,null,null);
            assertThat(RoutePolicy.allowed("GET","/internal/orders/7/access",actor)).isTrue();
            assertThat(RoutePolicy.allowed("POST","/internal/orders/7/access",actor)).isFalse();
            assertThat(RoutePolicy.allowed("GET","/orders/me/data-export",actor)).isFalse();
            assertThat(RoutePolicy.allowed("PATCH","/orders/7/status",actor)).isFalse();
        }
    }
    @Test void exportPermissionDoesNotExposeOperationalReservationAndCouponApis(){
        var actor=new Actor("CUSTOMER",null,1L,"x");
        for(String path:new String[]{"/inventory/reservations/7","/inventory/reservations/order/7","/inventory/operations","/internal/promotions/orders/7/coupon"})
            assertThat(RoutePolicy.allowed("GET",path,actor)).isFalse();
    }
}
