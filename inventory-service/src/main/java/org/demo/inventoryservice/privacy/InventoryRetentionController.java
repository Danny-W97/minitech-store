package org.demo.inventoryservice.privacy;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.privacy.InventoryRetentionProtocol;
import org.demo.platformsecurity.privacy.PeerRetentionGuardProtocol;
import org.demo.platformsecurity.privacy.PeerRetentionGuardEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.demo.platformsecurity.privacy.InventoryRetentionProtocol.*;

@RestController
@ConditionalOnProperty(name="privacy.commerce-retention-inventory-evidence.enabled",havingValue="true",matchIfMissing=false)
public final class InventoryRetentionController {
    private final InventoryRetentionEvidence collector;
    private final org.demo.platformsecurity.privacy.CommerceRetentionWriteFence writeFence;
    private final PeerRetentionGuardEvidence guards;
    public InventoryRetentionController(DataSource ds,PlatformTransactionManager manager){collector=new InventoryRetentionEvidence(ds,manager);writeFence=new org.demo.platformsecurity.privacy.CommerceRetentionWriteFence(org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant.INVENTORY,ds,manager);guards=new PeerRetentionGuardEvidence(org.demo.platformsecurity.privacy.CommerceRetentionAssessment.Participant.INVENTORY,ds,manager);}

    @PostMapping(PeerRetentionGuardProtocol.PATH)
    public ResponseEntity<PeerRetentionGuardProtocol.Reply> guards(HttpServletRequest request){
        var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));
        final PeerRetentionGuardProtocol.Command command;
        try{
            byte[] raw=request.getInputStream().readNBytes(PeerRetentionGuardProtocol.REQUEST_LIMIT+1);
            if(raw.length>PeerRetentionGuardProtocol.REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Guard request too large");
            command=PeerRetentionGuardProtocol.parseCommand(raw);
        }catch(ResponseStatusException failure){throw failure;}
        catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid guard scope");}
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(guards.collect(command));}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local guard scope");}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Guard unavailable");}
    }

    @PostMapping(org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.PATH)
    public ResponseEntity<org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.Reply> fence(HttpServletRequest request){
        var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));
        final org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.Command c;
        try{
            byte[] raw=request.getInputStream().readNBytes(org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.REQUEST_LIMIT+1);
            if(raw.length>org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.REQUEST_LIMIT)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Fence request too large");
            c=org.demo.platformsecurity.privacy.PeerRetentionFenceProtocol.parseCommand(raw);
        }catch(ResponseStatusException failure){throw failure;}
        catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid fence scope");}
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(writeFence.apply(c));}
        catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid local fence scope");}
        catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Fence unavailable");}
    }
    @PostMapping(PATH)
    public ResponseEntity<Reply> collect(HttpServletRequest request){
        var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));
        final Command command;
        try{byte[] raw=request.getInputStream().readNBytes(REQUEST_LIMIT+1);if(raw.length>REQUEST_LIMIT)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Evidence request too large");command=parseCommand(raw);}
        catch(ResponseStatusException failure){throw failure;}
        catch(java.io.IOException|RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid evidence scope");}
        try{
            var scope=new InventoryRetentionEvidence.Scope(command.orderId(),command.generation(),command.orderVersion(),command.phase(),command.bindingEpoch(),command.sourceRevision());
            var expected=new InventoryRetentionEvidence.Expected(command.reservationId(),InventoryRetentionEvidence.Terminal.valueOf(command.status().name()),command.lines().stream().map(l->new InventoryRetentionEvidence.Line(l.productId(),l.quantity())).toList());
            var result=collector.collect(scope,expected);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(InventoryRetentionProtocol.reply(command,result.state(),Code.valueOf(result.code().name()),result.localReconciledAt()));
        }catch(org.springframework.dao.DataAccessException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence unavailable");}
    }
}
