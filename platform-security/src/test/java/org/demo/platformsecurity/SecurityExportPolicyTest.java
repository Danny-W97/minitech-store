package org.demo.platformsecurity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SecurityExportPolicyTest {
    @Test void gatewayAllowsOnlyPositiveCustomerGetForAllSecurityProjections(){
        for(String suffix:new String[]{"methods","events","passkeys","recovery-codes"}){
            String path="/customers/me/data-export/security/"+suffix;
            assertThat(RoutePolicy.allowed("GET",path,new Actor("CUSTOMER",null,1L,"x"))).isTrue();
            for(var actor:new Actor[]{Actor.ANONYMOUS,new Actor("OPERATOR",null,null,null),new Actor("SERVICE","customer-service",null,null),new Actor("CUSTOMER",null,0L,"x")})
                assertThat(RoutePolicy.allowed("GET",path,actor)).isFalse();
            assertThat(RoutePolicy.allowed("POST",path,new Actor("CUSTOMER",null,1L,"x"))).isFalse();
        }
    }
}
