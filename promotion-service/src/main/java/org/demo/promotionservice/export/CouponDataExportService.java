package org.demo.promotionservice.export;

import org.demo.platformsecurity.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

@Service
public class CouponDataExportService {
    private static final String HEADER="""
            SELECT order_id,code,subtotal,discount_amount,state,accepted_at,expires_at,created_at,updated_at
            FROM coupon_reservations WHERE order_id=:orderId
            """;
    private static final String REDEMPTIONS="""
            SELECT id,order_id,code,redeemed_at FROM promotion_redemptions
            WHERE order_id=:orderId AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            """;
    private static final List<CouponDataExport.Omission> COMMON=List.of(
            new CouponDataExport.Omission("promotionDefinitionsAndGlobalUsage","excluded-not-account-scoped"),
            new CouponDataExport.Omission("operationalVersionAndKeys","excluded"),
            new CouponDataExport.Omission("historicalChanges","not-stored-not-reconstructed"),
            new CouponDataExport.Omission("changesDuringPagination","not-a-snapshot"),
            new CouponDataExport.Omission("otherAccountData","pending"));
    private final NamedParameterJdbcTemplate jdbc;private final OrderAccess orders;
    public CouponDataExportService(DataSource source,OrderAccess orders){var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);this.orders=orders;}
    public CouponDataExport export(long orderId){
        orders.requireCustomerOwner(orderId);
        var rows=jdbc.query(HEADER,Map.of("orderId",orderId),(rs,n)->new CouponDataExport.Reservation(rs.getLong("order_id"),rs.getString("code"),
                rs.getBigDecimal("subtotal"),rs.getBigDecimal("discount_amount"),rs.getString("state"),instant(rs,"accepted_at"),instant(rs,"expires_at"),instant(rs,"created_at"),instant(rs,"updated_at")));
        return new CouponDataExport(1,"coupon-reservation",false,Instant.now(),orderId,rows.isEmpty()?null:rows.getFirst(),omissions("redemptions","separate-endpoint"));
    }
    public RedemptionDataExportPage redemptions(long orderId,long cursor,int size){
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        orders.requireCustomerOwner(orderId);var window=new ListWindow(cursor,size);
        var rows=jdbc.query(REDEMPTIONS,Map.of("orderId",orderId,"ceiling",window.ceiling(),"limit",size+1),
                (rs,n)->new RedemptionDataExportPage.Redemption(rs.getLong("id"),rs.getLong("order_id"),rs.getString("code"),instant(rs,"redeemed_at")));
        var page=window.map(rows,RedemptionDataExportPage.Redemption::redemptionId,Function.identity());
        return new RedemptionDataExportPage(1,"coupon-redemptions-page",false,Instant.now(),orderId,page.items(),page.hasMore(),page.nextCursor(),omissions("couponReservation","separate-endpoint"));
    }
    private static Instant instant(ResultSet rs,String field)throws SQLException{return rs.getTimestamp(field)==null?null:rs.getTimestamp(field).toInstant();}
    private static List<CouponDataExport.Omission> omissions(String area,String reason){var result=new ArrayList<>(COMMON);result.add(new CouponDataExport.Omission(area,reason));return List.copyOf(result);}
}
