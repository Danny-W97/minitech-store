package org.demo.platformsecurity.privacy;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.platformsecurity.Access;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.Locale;
import static org.demo.platformsecurity.privacy.SourceHoldRestorationProtocol.*;

@AutoConfiguration(afterName="org.demo.platformsecurity.privacy.PrivacyConfiguration")
@ConditionalOnClass(name="org.springframework.jdbc.core.JdbcTemplate")
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name="privacy.commerce-retention-source-hold-restoration.enabled",havingValue="true",matchIfMissing=false)
@Import(SourceHoldRestorationConfiguration.Controller.class)
public class SourceHoldRestorationConfiguration {
    @Bean SourceHoldRestorer sourceHoldRestorer(@Value("${spring.application.name:unknown-service}")String service,@Value("${privacy.commerce-retention-source-hold-restoration.case-public-key:}")String key,DataSource ds,PlatformTransactionManager manager){
        var authority=new SourceHoldCaseAuthorization(key);if(!authority.configured())throw new IllegalStateException("Scoped case authority key required for hold restoration");
        return new SourceHoldRestorer(CommerceRetentionAssessment.Participant.valueOf(service.replace("-service","").toUpperCase(Locale.ROOT)),ds,manager,authority);
    }
    @RestController
    @ConditionalOnProperty(name="privacy.commerce-retention-source-hold-restoration.enabled",havingValue="true",matchIfMissing=false)
    public static final class Controller {
        private final SourceHoldRestorer restorer;
        public Controller(SourceHoldRestorer restorer){this.restorer=restorer;}
        @PostMapping(PATH) public ResponseEntity<Reply> restore(HttpServletRequest request){
            var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));final Command c;
            try{var raw=request.getInputStream().readNBytes(REQUEST_LIMIT+1);if(raw.length>REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Restoration request too large");c=parseCommand(raw);}
            catch(ResponseStatusException failure){throw failure;}catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid hold restoration");}
            try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(restorer.restore(c));}
            catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local hold restoration");}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Hold restoration unavailable");}
        }
    }
}
