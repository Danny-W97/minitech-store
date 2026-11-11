package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.demo.platformsecurity.privacy.CommerceRetentionBindingProtocol.*;

/** Created only by the opt-in source configuration. Only Order may bind a generation. */
@RestController
public final class CommerceRetentionSourceController {
    private final CommerceRetentionSourceOutbox source;
    public CommerceRetentionSourceController(CommerceRetentionSourceOutbox source){this.source=source;}
    @PostMapping(PATH)
    public ResponseEntity<Reply> bind(@RequestBody Command command){
        var actor=Access.actor();Access.require(actor.internal() && "order-service".equals(actor.service()));
        if(command==null || !command.valid())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid binding scope");
        try {
            var bound=source.bind(command.subject(),command.phase(),command.expectedEpoch());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(new Reply(command.orderId(),command.generation(),
                    command.orderVersion(),command.phase(),command.expectedEpoch(),bound.epoch(),bound.revision(),bound.invalidated()));
        }catch(IllegalStateException conflict){throw new ResponseStatusException(HttpStatus.CONFLICT,"Binding unavailable");}
        catch(org.springframework.dao.DataAccessException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Binding unavailable");}
    }
}
