package org.demo.returnsservice.export;

import org.demo.platformsecurity.ListWindow;
import org.demo.returnsservice.returns.ReturnStatus;
import org.demo.returnsservice.returns.ReturnType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
public class ReturnDataExportService {
    private static final String HEADERS="""
            SELECT id,order_id,customer_email,request_type,reason,description,status,created_at,updated_at
            FROM return_requests WHERE customer_id=:customerId AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            """;
    // Ownership and child rows are read in the same statement, including owned empty cases.
    private static final String OWNED="""
            WITH owned AS MATERIALIZED (SELECT id FROM return_requests WHERE id=:returnId AND customer_id=:customerId)
            """;
    private static final String ITEMS=OWNED+"""
            SELECT o.id AS owned_return_id,i.id,i.order_item_id,i.product_id,i.product_name,i.ordered_quantity,i.quantity
            FROM owned o LEFT JOIN LATERAL (
              SELECT id,order_item_id,product_id,product_name,ordered_quantity,quantity FROM return_lines
              WHERE return_id=o.id AND order_item_id<=:ceiling ORDER BY order_item_id DESC LIMIT :limit
            ) i ON TRUE ORDER BY i.order_item_id DESC
            """;
    private static final String EVENTS=OWNED+"""
            SELECT o.id AS owned_return_id,e.id,e.action,e.from_status,e.to_status,e.created_at
            FROM owned o LEFT JOIN LATERAL (
              SELECT id,action,from_status,to_status,created_at FROM return_events
              WHERE return_id=o.id AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            ) e ON TRUE ORDER BY e.id DESC
            """;
    private static final List<ReturnDataExportPage.Omission> OMISSIONS=List.of(
            new ReturnDataExportPage.Omission("caseItemsAndStatusEvents","separate-endpoints"),
            new ReturnDataExportPage.Omission("operatorNotesAndIdentities","manual-review-and-redaction-procedure"),
            new ReturnDataExportPage.Omission("workflowInternals","excluded"),
            new ReturnDataExportPage.Omission("submittedText","as-stored-no-semantic-redaction"),
            new ReturnDataExportPage.Omission("legacyCases","items-and-history-may-be-incomplete"),
            new ReturnDataExportPage.Omission("otherAccountData","pending"),
            new ReturnDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    private static final List<ReturnDataExportPage.Omission> CHILD_OMISSIONS=List.of(
            new ReturnDataExportPage.Omission("caseHeadersAndOtherData","separate-or-pending"),
            new ReturnDataExportPage.Omission("operatorNotesAndIdentities","manual-review-and-redaction-procedure"),
            new ReturnDataExportPage.Omission("workflowInternals","excluded"),
            new ReturnDataExportPage.Omission("legacySnapshotTime","migration-snapshot-not-original-event-time"),
            new ReturnDataExportPage.Omission("legacyCases","items-and-history-may-be-incomplete"),
            new ReturnDataExportPage.Omission("changesDuringPagination","not-a-snapshot"));
    private final NamedParameterJdbcTemplate jdbc;
    public ReturnDataExportService(DataSource source) {
        var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
    }
    public ReturnDataExportPage returns(long customerId,long cursor,int size) {
        var window=window(customerId,cursor,size);
        var rows=jdbc.query(HEADERS,parameters(customerId,0,window),
                (rs,row)->new ReturnDataExportPage.ReturnData(rs.getLong("id"),rs.getLong("order_id"),rs.getString("customer_email"),
                        ReturnType.valueOf(rs.getString("request_type")),rs.getString("reason"),rs.getString("description"),
                        ReturnStatus.valueOf(rs.getString("status")),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()));
        var page=window.map(rows,ReturnDataExportPage.ReturnData::returnId,Function.identity());
        return new ReturnDataExportPage(1,"returns-page",false,Instant.now(),page.items(),page.hasMore(),page.nextCursor(),OMISSIONS);
    }
    public ReturnItemDataExportPage items(long customerId,long returnId,long cursor,int size) {
        var window=window(customerId,cursor,size);requireId(returnId);
        List<ItemRow> rows=jdbc.query(ITEMS,parameters(customerId,returnId,window),
                (rs,row)->new ItemRow(rs.getObject("id")==null?null:new ReturnItemDataExportPage.Item(rs.getLong("id"),rs.getLong("order_item_id"),
                        rs.getLong("product_id"),rs.getString("product_name"),rs.getInt("ordered_quantity"),rs.getInt("quantity"))));
        if(rows.isEmpty())throw missing();
        var page=window.map(rows.stream().map(ItemRow::item).filter(Objects::nonNull).toList(),ReturnItemDataExportPage.Item::orderItemId,Function.identity());
        return new ReturnItemDataExportPage(1,"return-items-page",false,Instant.now(),returnId,page.items(),page.hasMore(),page.nextCursor(),CHILD_OMISSIONS);
    }
    public ReturnEventDataExportPage events(long customerId,long returnId,long cursor,int size) {
        var window=window(customerId,cursor,size);requireId(returnId);
        List<EventRow> rows=jdbc.query(EVENTS,parameters(customerId,returnId,window),
                (rs,row)->new EventRow(rs.getObject("id")==null?null:new ReturnEventDataExportPage.Event(rs.getLong("id"),rs.getString("action"),
                        rs.getString("from_status"),rs.getString("to_status"),rs.getTimestamp("created_at").toInstant())));
        if(rows.isEmpty())throw missing();
        var page=window.map(rows.stream().map(EventRow::event).filter(Objects::nonNull).toList(),ReturnEventDataExportPage.Event::eventId,Function.identity());
        return new ReturnEventDataExportPage(1,"return-events-page",false,Instant.now(),returnId,page.items(),page.hasMore(),page.nextCursor(),CHILD_OMISSIONS);
    }
    private record ItemRow(ReturnItemDataExportPage.Item item) {}
    private record EventRow(ReturnEventDataExportPage.Event event) {}
    private static Map<String,Object> parameters(long customerId,long returnId,ListWindow window) {
        return Map.of("customerId",customerId,"returnId",returnId,"ceiling",window.ceiling(),"limit",window.size()+1);
    }
    private static ListWindow window(long customerId,long cursor,int size) {
        if(customerId<1)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        return new ListWindow(cursor,size);
    }
    private static void requireId(long id){if(id<1)throw missing();}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Return not found");}
}
