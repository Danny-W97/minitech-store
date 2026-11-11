package org.demo.orderservice.export;

import org.demo.orderservice.order.OrderStatus;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class OrderDataExportService {
    private static final String ORDERS = """
            SELECT id,total_price,status,customer_email,delivery_first_name,delivery_last_name,
                   delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_phone,
                   delivery_method,delivery_cost,promotion_code,discount_amount,fulfillment_type,
                   delivery_date,delivery_slot,pickup_location,invoice_requested,created_at,updated_at,
                   product_id,quantity,unit_price
            FROM orders WHERE customer_id=:customerId AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            """;
    // The same statement checks ownership and handles a legitimately empty item page.
    private static final String ITEMS = """
            WITH owned AS MATERIALIZED (SELECT id FROM orders WHERE id=:orderId AND customer_id=:customerId)
            SELECT o.id AS owned_order_id,i.id,i.product_id,i.sku,i.product_name,i.quantity,i.unit_price,i.total_price
            FROM owned o LEFT JOIN LATERAL (
                SELECT id,product_id,sku,product_name,quantity,unit_price,total_price FROM order_items
                WHERE order_id=o.id AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            ) i ON TRUE ORDER BY i.id DESC
            """;
    private static final List<OrderDataExportPage.Omission> ORDER_OMISSIONS=List.of(
            new OrderDataExportPage.Omission("orderItems","separate-endpoint"),
            new OrderDataExportPage.Omission("accessSecretsAndWorkflowInternals","excluded"),
            new OrderDataExportPage.Omission("otherAccountData","pending"),
            new OrderDataExportPage.Omission("changesDuringPagination","not-a-snapshot"),
            new OrderDataExportPage.Omission("recipientDetails","as-stored-no-semantic-redaction"));
    private static final List<OrderDataExportPage.Omission> ITEM_OMISSIONS=List.of(
            new OrderDataExportPage.Omission("orderHeadersAndOtherData","separate-or-pending"),
            new OrderDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    private final NamedParameterJdbcTemplate jdbc;
    public OrderDataExportService(DataSource source) {
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
    }
    public OrderDataExportPage orders(long customerId,long cursor,int size) {
        validate(customerId,cursor,size);
        var rows=jdbc.query(ORDERS,Map.of("customerId",customerId,"ceiling",ceiling(cursor),"limit",size+1),
                (rs,row)->new OrderDataExportPage.OrderData(rs.getLong("id"),rs.getBigDecimal("total_price"),
                        OrderStatus.valueOf(rs.getString("status")),rs.getString("customer_email"),
                        new OrderDataExportPage.Address(rs.getString("delivery_first_name"),rs.getString("delivery_last_name"),
                                rs.getString("delivery_street"),rs.getString("delivery_postal_code"),rs.getString("delivery_city"),
                                rs.getString("delivery_country"),rs.getString("delivery_phone")),
                        rs.getString("delivery_method"),rs.getBigDecimal("delivery_cost"),rs.getString("promotion_code"),
                        rs.getBigDecimal("discount_amount"),rs.getString("fulfillment_type"),rs.getObject("delivery_date",LocalDate.class),
                        rs.getString("delivery_slot"),rs.getString("pickup_location"),rs.getBoolean("invoice_requested"),
                        rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                        rs.getObject("product_id")==null&&rs.getObject("quantity")==null&&rs.getObject("unit_price")==null?null:
                                new OrderDataExportPage.LegacyLine(rs.getObject("product_id",Long.class),
                                        rs.getObject("quantity",Integer.class),rs.getBigDecimal("unit_price"))));
        boolean more=rows.size()>size;var page=rows.stream().limit(size).toList();
        return new OrderDataExportPage(1,"orders-page",false,Instant.now(),page,more,
                more?Long.toString(page.getLast().orderId()):"0",ORDER_OMISSIONS);
    }
    public OrderItemDataExportPage items(long customerId,long orderId,long cursor,int size) {
        validate(customerId,cursor,size);
        if(orderId<1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        List<ItemRow> rows=jdbc.query(ITEMS,Map.of("customerId",customerId,"orderId",orderId,"ceiling",ceiling(cursor),"limit",size+1),
                (rs,row)->rs.getObject("id")==null?new ItemRow(null):new ItemRow(new OrderItemDataExportPage.Item(
                        rs.getLong("id"),rs.getLong("product_id"),rs.getString("sku"),rs.getString("product_name"),
                        rs.getInt("quantity"),rs.getBigDecimal("unit_price"),rs.getBigDecimal("total_price"))));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        var items=rows.stream().map(ItemRow::item).filter(java.util.Objects::nonNull).toList();
        boolean more=items.size()>size;var page=items.stream().limit(size).toList();
        return new OrderItemDataExportPage(1,"order-items-page",false,Instant.now(),orderId,page,more,
                more?Long.toString(page.getLast().itemId()):"0",ITEM_OMISSIONS);
    }
    private record ItemRow(OrderItemDataExportPage.Item item) {}
    private static long ceiling(long cursor){return cursor==0?Long.MAX_VALUE:cursor-1;}
    private static void validate(long customerId,long cursor,int size) {
        if(customerId<1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
    }
}
