package org.demo.returnsservice.returns;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

@Component
public class ReturnOrderClient {
    public record Line(Long id,Long productId,String productName,Integer quantity,java.math.BigDecimal unitPrice) {
        public Line(Long id,Long productId,String productName,Integer quantity) { this(id,productId,productName,quantity,null); }
    }
    public record Snapshot(Long id,Long customerId,String customerEmail,String status,String paymentState,Boolean lifecycleReviewRequired,java.math.BigDecimal totalPrice,List<Line> items) {
        public Snapshot(Long id,Long customerId,String customerEmail,String status,String paymentState,Boolean lifecycleReviewRequired,List<Line> items) {
            this(id,customerId,customerEmail,status,paymentState,lifecycleReviewRequired,null,items);
        }
    }
    private final RestClient client;
    public ReturnOrderClient(RestClient.Builder builder,@Value("${services.order.base-url:${ORDER_SERVICE_URL:${PLATFORM_ORDER_URL:http://localhost:18082}}}") String base) {
        client=builder.baseUrl(base).build();
    }
    public Snapshot get(Long id,Long customer,String email) {
        Snapshot order;
        try { order=client.get().uri("/orders/{id}",id).retrieve().body(Snapshot.class); }
        catch(RestClientResponseException error) {
            if(error.getStatusCode().value()==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"RETURN_ORDER_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RETURN_ORDER_VERIFICATION_UNAVAILABLE");
        } catch(RestClientException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RETURN_ORDER_VERIFICATION_UNAVAILABLE"); }
        if(order==null || !id.equals(order.id())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"RETURN_ORDER_UNVERIFIED");
        if(!Objects.equals(customer,order.customerId()) || order.customerEmail()==null || !order.customerEmail().equalsIgnoreCase(email))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RETURN_ORDER_OWNER_MISMATCH");
        return order;
    }
    public void eligible(Snapshot order,ReturnType type) {
        boolean state=type==ReturnType.RETURN?"COMPLETED".equals(order.status()):Set.of("PAID","SHIPPED","COMPLETED").contains(order.status()==null?"":order.status());
        if(!state || !"PAID".equals(order.paymentState()) || !Boolean.FALSE.equals(order.lifecycleReviewRequired()))
            throw ReturnIntent.conflict("RETURN_ORDER_NOT_ELIGIBLE");
    }
    public List<ReturnLine> lines(ReturnRequest request,Snapshot order,ReturnIntent intent) {
        if(order.items()==null || order.items().isEmpty() || order.items().size()>100) throw ReturnIntent.conflict("RETURN_ORDER_ITEMS_UNVERIFIED");
        var source=new TreeMap<Long,Line>();
        for(var line:order.items()) {
            if(line==null || line.id()==null || line.id()<=0 || line.productId()==null || line.productId()<=0 || line.quantity()==null || line.quantity()<=0
                    || line.productName()==null || line.productName().isBlank() || line.productName().length()>255 || source.put(line.id(),line)!=null)
                throw ReturnIntent.conflict("RETURN_ORDER_ITEMS_UNVERIFIED");
        }
        Map<Long,Integer> selected=intent.selection();
        if(selected==null) { selected=new TreeMap<>();for(var line:source.values()) selected.put(line.id(),line.quantity()); }
        var result=new ArrayList<ReturnLine>();
        for(var entry:new TreeMap<>(selected).entrySet()) {
            var line=source.get(entry.getKey());
            if(line==null || entry.getValue()>line.quantity()) throw ReturnIntent.conflict("RETURN_QUANTITY_OR_ITEM_MISMATCH");
            result.add(new ReturnLine(request,line,entry.getValue()));
        }
        return List.copyOf(result);
    }
}
