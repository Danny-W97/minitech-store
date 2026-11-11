package org.demo.platformsecurity.privacy;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.platformsecurity.Access;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.Locale;
import static org.demo.platformsecurity.privacy.SourceHoldDeliveryProtocol.*;

@AutoConfiguration(afterName="org.demo.platformsecurity.privacy.PrivacyConfiguration")
@ConditionalOnClass(name="org.springframework.jdbc.core.JdbcTemplate")
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name="privacy.commerce-retention-source-hold-delivery.enabled",havingValue="true",matchIfMissing=false)
@Import(SourceHoldDeliveryConfiguration.Controller.class)
public class SourceHoldDeliveryConfiguration {
    @Bean SourceHoldDeliveryReceiver sourceHoldDeliveryReceiver(@Value("${spring.application.name:unknown-service}")String service,DataSource ds,PlatformTransactionManager manager){
        var source=CommerceRetentionAssessment.Participant.valueOf(service.replace("-service","").toUpperCase(Locale.ROOT));
        return new SourceHoldDeliveryReceiver(source,ds,manager);
    }
    @RestController
    @ConditionalOnProperty(name="privacy.commerce-retention-source-hold-delivery.enabled",havingValue="true",matchIfMissing=false)
    public static final class Controller {
        private final SourceHoldDeliveryReceiver receiver;
        public Controller(SourceHoldDeliveryReceiver receiver){this.receiver=receiver;}
        @PostMapping(PATH) public ResponseEntity<Reply> deliver(HttpServletRequest request){
            var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));final Command c;
            try{byte[] raw=request.getInputStream().readNBytes(REQUEST_LIMIT+1);if(raw.length>REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Hold request too large");c=parseCommand(raw);}
            catch(ResponseStatusException failure){throw failure;}catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid hold delivery");}
            try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(receiver.deliver(c));}
            catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local hold delivery");}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Hold delivery unavailable");}
        }
    }
}
