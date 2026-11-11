package org.demo.customerservice.export.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class ExportDocumentGuard {
    private record Shape(Set<String> fields,Map<String,Shape> children){}
    private static Shape shape(String fields){return new Shape(Set.of(fields.split(" ")),Map.of());}
    private static Shape shape(String fields,Map<String,Shape> children){return new Shape(Set.of(fields.split(" ")),children);}
    private static final Shape OMIT=shape("scope status"),AREA=shape("area reason"),PDF=shape("path mediaType representation");
    private static final Map<ExportKind,Shape> ROWS=new EnumMap<>(ExportKind.class);
    private static final Map<ExportKind,String> CURSOR_IDS=new EnumMap<>(ExportKind.class);
    static {
        ROWS.put(ExportKind.EVENTS,shape("eventId eventType outcome occurredAt subjectProof"));
        ROWS.put(ExportKind.PASSKEYS,shape("exportId createdAt lastUsedAt userVerificationInitialized backupEligible backupState"));
        ROWS.put(ExportKind.RECOVERY,shape("recordId createdAt usedAt"));
        ROWS.put(ExportKind.WISHLIST,shape("productId createdAt"));
        ROWS.put(ExportKind.REVIEWS,shape("reviewId productId rating title content status createdAt updatedAt"));
        ROWS.put(ExportKind.RECOMMENDATIONS,shape("requestId sourceProductId strategy resultCount requestedAt"));
        ROWS.put(ExportKind.ORDERS,shape("orderId totalPrice status customerEmail deliveryAddress deliveryMethod deliveryCost promotionCode discountAmount fulfillmentType deliveryDate deliverySlot pickupLocation invoiceRequested createdAt updatedAt legacyLine",
                Map.of("deliveryAddress",shape("firstName lastName street postalCode city country phone"),"legacyLine",shape("productId quantity unitPrice"))));
        ROWS.put(ExportKind.RETURNS,shape("returnId orderId customerEmail requestType reason description status createdAt updatedAt"));
        ROWS.put(ExportKind.ORDER_ITEMS,shape("itemId productId sku productName quantity unitPrice totalPrice"));
        ROWS.put(ExportKind.INVENTORY_ITEMS,shape("reservationItemId reservationId productId quantity"));
        ROWS.put(ExportKind.REDEMPTIONS,shape("redemptionId orderId code redeemedAt"));
        ROWS.put(ExportKind.INVOICE_ITEMS,shape("itemId positionNo productId sku productName quantity unitPrice totalPrice"));
        ROWS.put(ExportKind.CORRECTIONS,shape("correctionId invoiceId orderId correctionNumber reason originalNet originalVat originalGross correctionNet correctionVat correctionGross finalNet finalVat finalGross createdAt balanceModel pdf",Map.of("pdf",PDF)));
        ROWS.put(ExportKind.NOTIFICATIONS,shape("notificationId eventType sourceId recipient channel subject message status contentDisposition contentCompactedAt createdAt updatedAt sentAt"));
        ROWS.put(ExportKind.RETURN_ITEMS,shape("lineId orderItemId productId productName orderedQuantity quantity"));
        ROWS.put(ExportKind.RETURN_EVENTS,shape("eventId action fromStatus toStatus createdAt"));
        ExportKind[] kinds={ExportKind.EVENTS,ExportKind.PASSKEYS,ExportKind.RECOVERY,ExportKind.REVIEWS,ExportKind.RECOMMENDATIONS,ExportKind.ORDERS,ExportKind.RETURNS,ExportKind.ORDER_ITEMS,ExportKind.INVENTORY_ITEMS,ExportKind.REDEMPTIONS,ExportKind.INVOICE_ITEMS,ExportKind.CORRECTIONS,ExportKind.NOTIFICATIONS,ExportKind.RETURN_ITEMS,ExportKind.RETURN_EVENTS};
        String[] ids={"eventId","exportId","recordId","reviewId","requestId","orderId","returnId","itemId","reservationItemId","redemptionId","positionNo","correctionId","notificationId","orderItemId","eventId"};
        for(int i=0;i<kinds.length;i++)CURSOR_IDS.put(kinds[i],ids[i]);
    }
    private final ExportJson json;
    public ExportDocumentGuard(ExportJson json){this.json=json;}
    public List<ExportTask.Plan> inspect(ExportTask task,ExportTask.Result result,CustomerPrincipal owner){
        if(result.missing())return List.of();
        JsonNode doc=json.parse(result.data().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(task.kind().pdf()){object(doc,shape("mediaType representation base64"));return List.of();}
        var kind=task.kind();String fields="schemaVersion scope complete generatedAt omissions";
        Map<String,Shape> children=new HashMap<>();children.put("omissions",kind.local()&&kind!=ExportKind.PROFILE||kind==ExportKind.NOTIFICATIONS||kind==ExportKind.INVENTORY||kind==ExportKind.INVENTORY_ITEMS||kind==ExportKind.COUPON||kind==ExportKind.REDEMPTIONS?AREA:OMIT);
        if(kind.collection!=null){fields+=" "+kind.collection+" hasMore nextCursor";children.put(kind.collection,ROWS.get(kind));}
        switch(kind){
            case PROFILE -> {fields+=" profile security";children.put("profile",shape("id email firstName lastName street postalCode city country phoneNumber enabled createdAt updatedAt"));children.put("security",shape("totpEnabled passkeyCount unusedRecoveryCodeCount"));}
            case METHODS -> {fields+=" totp";children.put("totp",shape("enabled createdAt updatedAt enrollmentExpiresAt"));}
            case RECOMMENDATIONS -> fields+=" recordingEnabled historyCoverage";
            case INVENTORY -> {fields+=" orderId reservation";children.put("reservation",shape("reservationId orderId status expiresAt createdAt updatedAt"));}
            case COUPON -> {fields+=" orderId reservation";children.put("reservation",shape("orderId code subtotal discountAmount state acceptedAt expiresAt createdAt updatedAt"));}
            case PAYMENT -> {fields+=" payment refund";children.put("payment",shape("paymentId orderId amount currency paymentMethod status collectedAt createdAt updatedAt"));children.put("refund",shape("refundId paymentId orderId amount currency status submittedAt createdAt updatedAt"));}
            case SHIPMENT -> {fields+=" shipment";children.put("shipment",shape("shipmentId orderId trackingNumber carrier methodCode status createdAt updatedAt"));}
            case INVOICE -> {fields+=" invoice pdf";children.put("invoice",shape("invoiceId orderId customerEmail invoiceNumber billingKind buyerName companyName taxId street postalCode city country netAmount vatAmount grossAmount deliveryCost discountAmount currency status createdAt"));children.put("pdf",PDF);}
            case ORDER_ITEMS,INVENTORY_ITEMS,REDEMPTIONS,INVOICE_ITEMS,CORRECTIONS -> fields+=" orderId";
            case RETURN_ITEMS,RETURN_EVENTS -> fields+=" returnId";
            case NOTIFICATIONS -> fields=fields.replace("generatedAt","exportedAt")+" authorizationMode sourceId conversationId";
            default -> {}
        }
        object(doc,shape(fields,children));
        require(doc.path("schemaVersion").isInt()&&doc.path("schemaVersion").intValue()==1&&doc.path("scope").asText().equals(kind.scope)&&doc.path("complete").isBoolean()&&!doc.path("complete").booleanValue());
        try{Instant.parse(doc.path(kind==ExportKind.NOTIFICATIONS?"exportedAt":"generatedAt").asText());}catch(RuntimeException invalid){throw new ExportFailure("SOURCE_FORMAT");}
        if(kind==ExportKind.PROFILE){require(number(doc.path("profile"),"id")==owner.customerId());require(doc.path("profile").path("email").asText().equals(owner.email()));}
        for(String field:List.of("payment","refund","shipment","invoice","reservation"))if(doc.has(field)&&!doc.path(field).isNull())require(number(doc.path(field),"orderId")==task.parent());
        if(Set.of(ExportKind.PAYMENT,ExportKind.SHIPMENT,ExportKind.INVOICE).contains(kind))require(doc.path(kind==ExportKind.PAYMENT?"payment":kind==ExportKind.SHIPMENT?"shipment":"invoice").isObject());
        if(doc.has("orderId"))require(number(doc,"orderId")==task.parent());
        if(doc.has("returnId"))require(number(doc,"returnId")==task.parent());
        if(kind==ExportKind.NOTIFICATIONS)require(number(doc,"sourceId")==task.parent()&&doc.path("authorizationMode").asText().equals("customer-order-owner")&&doc.path("conversationId").isNull());
        List<ExportTask.Plan> childrenTasks=new ArrayList<>();
        if(kind.collection!=null){
            var rows=doc.path(kind.collection);require(rows.isArray()&&rows.size()<=50&&doc.path("hasMore").isBoolean()&&doc.path("nextCursor").isTextual());
            long previous=task.cursor()==0?Long.MAX_VALUE:task.cursor();String id=CURSOR_IDS.get(kind);
            boolean first=true;
            for(var row:rows){
                if(id!=null){long value=number(row,id);require((first&&task.cursor()==0)||value<previous);previous=value;}
                first=false;
                if(row.has("orderId")&&task.parent()>0)require(number(row,"orderId")==task.parent());
                if(kind==ExportKind.NOTIFICATIONS)require(number(row,"sourceId")==task.parent()&&Set.of("ORDER_CREATED","ORDER_STATUS_CHANGED","PAYMENT_PENDING","PAYMENT_SUCCEEDED","PAYMENT_FAILED").contains(row.path("eventType").asText()));
                if(kind==ExportKind.ORDERS)for(var child:List.of(ExportKind.ORDER_ITEMS,ExportKind.INVENTORY,ExportKind.INVENTORY_ITEMS,ExportKind.COUPON,ExportKind.REDEMPTIONS,ExportKind.PAYMENT,ExportKind.SHIPMENT,ExportKind.INVOICE,ExportKind.NOTIFICATIONS))childrenTasks.add(new ExportTask.Plan(child,number(row,"orderId"),0,0));
                if(kind==ExportKind.RETURNS)for(var child:List.of(ExportKind.RETURN_ITEMS,ExportKind.RETURN_EVENTS))childrenTasks.add(new ExportTask.Plan(child,number(row,"returnId"),0,0));
                if(kind==ExportKind.CORRECTIONS){long correction=number(row,"correctionId");pdfPath(row.path("pdf"),task.parent(),correction);childrenTasks.add(new ExportTask.Plan(ExportKind.CORRECTION_PDF,task.parent(),correction,0));}
            }
            String raw=doc.path("nextCursor").asText();require(raw.matches("0|[1-9][0-9]{0,18}"));long next;
            try{next=Long.parseLong(raw);}catch(NumberFormatException invalid){throw new ExportFailure("SOURCE_FORMAT");}
            if(doc.path("hasMore").booleanValue()){
                require(rows.size()==50&&next>0&&(task.cursor()==0||next<task.cursor()));
                if(id!=null)require(next==previous);
                childrenTasks.add(new ExportTask.Plan(kind,task.parent(),task.detail(),next));
            }else require(next==0);
        }
        if(kind==ExportKind.INVOICE){pdfPath(doc.path("pdf"),task.parent(),0);for(var child:List.of(ExportKind.INVOICE_ITEMS,ExportKind.CORRECTIONS,ExportKind.INVOICE_PDF))childrenTasks.add(new ExportTask.Plan(child,task.parent(),0,0));}
        return List.copyOf(childrenTasks);
    }
    private static void pdfPath(JsonNode node,long order,long correction){require(node.path("path").asText().equals("/invoices/me/data-export/orders/"+order+(correction>0?"/corrections/"+correction:"")+"/pdf")&&node.path("mediaType").asText().equals("application/pdf")&&node.path("representation").asText().equals("rendered-stored-document"));}
    private static long number(JsonNode row,String field){var value=row.path(field);require(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>0);return value.longValue();}
    private static void object(JsonNode node,Shape shape){
        require(node!=null&&node.isObject());Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(shape.fields()));
        node.fields().forEachRemaining(entry->{var value=entry.getValue();var child=shape.children().get(entry.getKey());
            if(child!=null){if(value.isArray()){require(value.size()<=50);for(var item:value)object(item,child);}else if(!value.isNull())object(value,child);}
            else require(!value.isContainerNode());});
    }
    private static void require(boolean condition){if(!condition)throw new ExportFailure("SOURCE_FORMAT");}
}
