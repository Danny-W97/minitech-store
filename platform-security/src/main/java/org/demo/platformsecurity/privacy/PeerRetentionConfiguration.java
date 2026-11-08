package org.demo.platformsecurity.privacy;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.platformsecurity.Access;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.Locale;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="privacy.commerce-retention-peer-evidence.enabled",havingValue="true",matchIfMissing=false)
public class PeerRetentionConfiguration {
    @Bean PeerRetentionEvidence peerRetentionEvidence(@Value("${spring.application.name}")String own,DataSource ds,PlatformTransactionManager manager){return new PeerRetentionEvidence(Participant.valueOf(own.replace("-service","").toUpperCase(Locale.ROOT)),ds,manager);}
    @RestController public static final class Controller {
        private final PeerRetentionEvidence local;public Controller(PeerRetentionEvidence local){this.local=local;}
        @PostMapping(PeerRetentionGuardProtocol.PATH) public ResponseEntity<PeerRetentionGuardProtocol.Reply> guards(HttpServletRequest request){
            var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));final PeerRetentionGuardProtocol.Command c;
            try{var raw=request.getInputStream().readNBytes(PeerRetentionGuardProtocol.REQUEST_LIMIT+1);if(raw.length>PeerRetentionGuardProtocol.REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Guard request too large");c=PeerRetentionGuardProtocol.parseCommand(raw);}
            catch(ResponseStatusException e){throw e;}catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid guard scope");}
            try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(local.guards(c));}
            catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local guard scope");}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Guard observation unavailable");}
        }
        @PostMapping(PATH) public ResponseEntity<Reply> collect(HttpServletRequest request){
            var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));final Command c;
            try{var raw=request.getInputStream().readNBytes(REQUEST_LIMIT+1);if(raw.length>REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Evidence request too large");c=parseCommand(raw);}
            catch(ResponseStatusException e){throw e;}catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid evidence scope");}
            try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(local.collect(c));}
            catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local evidence scope");}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence unavailable");}
        }
        @PostMapping(PeerRetentionFenceProtocol.PATH) public ResponseEntity<PeerRetentionFenceProtocol.Reply> fence(HttpServletRequest request){
            var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));final PeerRetentionFenceProtocol.Command c;
            try{var raw=request.getInputStream().readNBytes(PeerRetentionFenceProtocol.REQUEST_LIMIT+1);if(raw.length>PeerRetentionFenceProtocol.REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Fence request too large");c=PeerRetentionFenceProtocol.parseCommand(raw);}
            catch(ResponseStatusException e){throw e;}catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid fence scope");}
            try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(local.fence(c));}
            catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local fence scope");}
            catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Fence unavailable");}
        }
    }
}
