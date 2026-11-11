package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.CreateReturnRequest;
import org.demo.platformsecurity.Access;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** The request's intent is independent of later fulfillment/refund changes. */
public record ReturnIntent(String hash,Map<Long,Integer> selection) {
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    public static ReturnIntent from(CreateReturnRequest request) {
        Map<Long,Integer> lines=null;
        if(request.items()!=null) {
            if(request.items().isEmpty() || request.items().size()>100) throw bad("RETURN_ITEMS_REQUIRED");
            lines=new TreeMap<>();
            for(var item:request.items()) {
                if(item==null || item.orderItemId()==null || item.orderItemId()<=0 || item.quantity()==null || item.quantity()<=0) throw bad("RETURN_ITEMS_INVALID");
                lines.merge(item.orderItemId(),item.quantity(),Math::addExact);
            }
        }
        var payload=Arrays.asList("return-intent-v1",request.orderId(),request.customerId(),request.customerEmail().trim().toLowerCase(Locale.ROOT),
                request.requestType(),request.reason().trim(),request.description().trim(),lines==null?null:lines.entrySet().stream().map(e -> List.of(e.getKey(),e.getValue())).toList());
        return new ReturnIntent(Access.hash(JSON.writeValueAsString(payload)),lines==null?null:Map.copyOf(lines));
    }
    static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,code); }
    static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT,code); }
}
