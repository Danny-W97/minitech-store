package org.demo.chatservice.export;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatDataExportServiceTest {
    @Test void missingMalformedProofOrInvalidWindowDoesNotReadTheDatabase(){
        var source=mock(DataSource.class);var service=new ChatDataExportService(source);
        for(String token:new String[]{null,"","short","!".repeat(43),"C".repeat(129)}){
            status(401,()->service.conversation(99,token));status(401,()->service.entries(99,token,0,50));
        }
        status(404,()->service.conversation(0,"C".repeat(43)));
        for(int size:new int[]{0,51})status(400,()->service.entries(99,"C".repeat(43),0,size));
        status(400,()->service.entries(99,"C".repeat(43),-1,1));verifyNoInteractions(source);
    }
    private void status(int expected,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,error->assertThat(error.getStatusCode().value()).isEqualTo(expected));}
}
