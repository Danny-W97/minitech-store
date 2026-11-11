package org.demo.orderservice.order;

import org.demo.orderservice.web.CreateOrderItemRequest;
import org.demo.orderservice.web.CreateOrderRequest;
import org.demo.platformsecurity.Access;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Versioned intent, independent of current prices, stock and automatically chosen dates. */
public record OrderCommand(String key,String principal,String fingerprint,List<CreateOrderItemRequest> items) {
    public static OrderCommand from(CreateOrderRequest request,String suppliedKey) {
        String key=suppliedKey==null?"generated-"+UUID.randomUUID():suppliedKey;
        if(key.isEmpty() || key.length()>100 || key.chars().anyMatch(c -> c<33 || c>126))
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 100 visible ASCII characters without spaces");
        var quantities=new TreeMap<Long,Integer>();
        if(request.items()!=null) for(var item:request.items()) add(quantities,item);
        if(quantities.isEmpty() && request.productId()!=null && request.quantity()!=null)
            add(quantities,new CreateOrderItemRequest(request.productId(),request.quantity()));
        if(quantities.isEmpty() || quantities.size()>100) throw new IllegalArgumentException("Provide 1 to 100 products");
        quantities.values().stream().reduce(0,Math::addExact);
        var items=quantities.entrySet().stream().map(e -> new CreateOrderItemRequest(e.getKey(),e.getValue())).toList();
        String principal=request.customerId()!=null?"customer:"+request.customerId():
                "guest:"+(Access.request()==null?"local-test":Access.capabilityHash(Access.ORDER_TOKEN));
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            value(out,"order-command-v1");value(out,principal);
            out.writeInt(items.size());for(var item:items) { out.writeLong(item.productId());out.writeInt(item.quantity()); }
            value(out,request.customerEmail().toLowerCase(Locale.ROOT));
            var address=Objects.requireNonNull(request.deliveryAddress(),"Delivery address required");
            for(String field:new String[]{address.firstName(),address.lastName(),address.street(),address.postalCode(),address.city(),address.country(),optional(address.phone())}) value(out,field);
            String method=upper(request.deliveryMethod());if(method==null) method="STANDARD_COURIER";
            value(out,method);value(out,upper(request.promotionCode()));
            String fulfillment=upper(request.fulfillmentType());
            value(out,fulfillment==null?("STORE_PICKUP".equals(method)?"STORE_PICKUP":"HOME_DELIVERY"):fulfillment);
            value(out,request.deliveryDate()==null?null:request.deliveryDate().toString());
            value(out,optional(request.deliverySlot()));value(out,optional(request.pickupLocation()));
            out.writeBoolean(Boolean.TRUE.equals(request.invoiceRequested()));
            return new OrderCommand(key,principal,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())),items);
        } catch(IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public void requireSame(Order order) {
        // Ownership is checked before this method, including for legacy records.
        if(order.getCheckoutRequestHash()==null || order.getCheckoutPrincipal()==null)
            throw new OrderIdempotencyException("ORDER_IDEMPOTENCY_LEGACY_REVIEW",order.getId());
        if(!principal.equals(order.getCheckoutPrincipal()) || !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),order.getCheckoutRequestHash().getBytes(StandardCharsets.US_ASCII)))
            throw new OrderIdempotencyException("ORDER_IDEMPOTENCY_CONFLICT",order.getId());
    }
    private static void add(Map<Long,Integer> quantities,CreateOrderItemRequest item) {
        if(item==null || item.productId()==null || item.productId()<=0 || item.quantity()==null || item.quantity()<=0)
            throw new IllegalArgumentException("Product IDs and quantities must be positive");
        quantities.merge(item.productId(),item.quantity(),Math::addExact);
    }
    private static void value(DataOutputStream out,String value) throws IOException {
        if(value==null) out.writeInt(-1);
        else { byte[] encoded=value.getBytes(StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded); }
    }
    private static String optional(String value) { return value==null || value.isBlank()?null:value; }
    private static String upper(String value) { return value==null || value.isBlank()?null:value.trim().toUpperCase(Locale.ROOT); }
}
