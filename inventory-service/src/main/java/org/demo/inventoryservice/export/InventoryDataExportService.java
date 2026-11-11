package org.demo.inventoryservice.export;

import org.demo.platformsecurity.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class InventoryDataExportService {
    private static final String HEADER="""
            SELECT id,order_id,status,expires_at,created_at,updated_at FROM inventory_reservations WHERE order_id=:orderId
            """;
    private static final String ITEMS="""
            SELECT i.id,i.reservation_id,i.product_id,i.quantity FROM inventory_reservations r
            JOIN inventory_reservation_items i ON i.reservation_id=r.id
            WHERE r.order_id=:orderId AND i.id<=:ceiling ORDER BY i.id DESC LIMIT :limit
            """;
    private static final List<InventoryDataExport.Omission> COMMON=List.of(
            new InventoryDataExport.Omission("globalStockAndStockOperations","excluded-not-account-scoped"),
            new InventoryDataExport.Omission("catalogData","excluded-not-account-scoped"),
            new InventoryDataExport.Omission("operationalKeys","excluded"),
            new InventoryDataExport.Omission("historicalChanges","not-stored-not-reconstructed"),
            new InventoryDataExport.Omission("changesDuringPagination","not-a-snapshot"),
            new InventoryDataExport.Omission("otherAccountData","pending"));
    private final NamedParameterJdbcTemplate jdbc;private final OrderAccess orders;
    public InventoryDataExportService(DataSource source,OrderAccess orders){
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);this.orders=orders;
    }
    public InventoryDataExport export(long orderId){
        orders.requireCustomerOwner(orderId);
        var rows=jdbc.query(HEADER,Map.of("orderId",orderId),(rs,n)->new InventoryDataExport.Reservation(rs.getLong("id"),rs.getLong("order_id"),
                rs.getString("status"),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()));
        return new InventoryDataExport(1,"inventory-reservation",false,Instant.now(),orderId,rows.isEmpty()?null:rows.getFirst(),
                omissions("reservationItems","separate-endpoint"));
    }
    public InventoryItemDataExportPage items(long orderId,long cursor,int size){
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        orders.requireCustomerOwner(orderId);var window=new ListWindow(cursor,size);
        var rows=jdbc.query(ITEMS,Map.of("orderId",orderId,"ceiling",window.ceiling(),"limit",size+1),
                (rs,n)->new InventoryItemDataExportPage.Item(rs.getLong("id"),rs.getLong("reservation_id"),rs.getLong("product_id"),rs.getInt("quantity")));
        var page=window.map(rows,InventoryItemDataExportPage.Item::reservationItemId,Function.identity());
        return new InventoryItemDataExportPage(1,"inventory-reservation-items-page",false,Instant.now(),orderId,page.items(),page.hasMore(),page.nextCursor(),
                omissions("reservationHeader","separate-endpoint"));
    }
    private static List<InventoryDataExport.Omission> omissions(String area,String reason){var result=new ArrayList<>(COMMON);result.add(new InventoryDataExport.Omission(area,reason));return List.copyOf(result);}
}
