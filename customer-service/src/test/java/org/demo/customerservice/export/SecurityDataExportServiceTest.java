package org.demo.customerservice.export;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SecurityDataExportServiceTest {
    @Test void missingOrMalformedPrincipalNeverOpensDatabase(){
        var source=mock(DataSource.class);var service=new SecurityDataExportService(source);
        for(var p:new CustomerPrincipal[]{null,new CustomerPrincipal(null,"x"),new CustomerPrincipal(0L,"x"),new CustomerPrincipal(1L,"")})
            for(Runnable call:new Runnable[]{()->service.methods(p),()->service.events(p,0,50),()->service.passkeys(p,0,50),()->service.recovery(p,0,50)})
                assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(source);
    }
    @Test void invalidWindowsNeverReadSecretsOrHistory(){
        var source=mock(DataSource.class);var service=new SecurityDataExportService(source);var p=new CustomerPrincipal(1L,"x");
        for(Runnable call:new Runnable[]{()->service.events(p,-1,50),()->service.passkeys(p,0,51),()->service.recovery(p,0,0)})
            assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(source);
    }
}
