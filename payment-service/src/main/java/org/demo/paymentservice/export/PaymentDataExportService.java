package org.demo.paymentservice.export;

import org.demo.paymentservice.payment.PaymentStatus;
import org.demo.paymentservice.payment.PaymentRefund;
import org.demo.platformsecurity.OrderAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Service
public class PaymentDataExportService {
    private static final String QUERY="""
            SELECT p.id,p.order_id,p.amount,p.currency,p.payment_method,p.status,p.collected_at,p.created_at,p.updated_at,
                   r.id AS refund_id,r.payment_id AS refund_payment_id,r.order_id AS refund_order_id,
                   r.amount AS refund_amount,r.currency AS refund_currency,r.status AS refund_status,
                   r.submitted_at AS refund_submitted_at,r.created_at AS refund_created_at,r.updated_at AS refund_updated_at
            FROM payments p LEFT JOIN payment_refunds r ON r.payment_id=p.id WHERE p.order_id=?
            """;
    private static final List<PaymentDataExport.Omission> OMISSIONS=List.of(
            new PaymentDataExport.Omission("checkoutSecretsAndProviderReferences","excluded"),
            new PaymentDataExport.Omission("operatorDetailsAndWorkflowInternals","excluded"),
            new PaymentDataExport.Omission("refundAvailability","stored-record-only"),
            new PaymentDataExport.Omission("providerAndDomainReconciliation","not-performed"),
            new PaymentDataExport.Omission("otherAccountData","pending"),
            new PaymentDataExport.Omission("crossServiceSnapshot","not-a-snapshot"));
    private final JdbcTemplate jdbc;
    private final OrderAccess orders;
    public PaymentDataExportService(DataSource source,OrderAccess orders) {
        this.orders=orders;jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);jdbc.setMaxRows(1);
    }
    public PaymentDataExport export(long orderId) {
        // No local lookup or fallback before authoritative customer ownership is established.
        orders.requireCustomerOwner(orderId);
        var rows=jdbc.query(QUERY,(rs,row)->{
            var payment=new PaymentDataExport.PaymentData(rs.getLong("id"),rs.getLong("order_id"),rs.getBigDecimal("amount"),
                    rs.getString("currency"),rs.getString("payment_method"),PaymentStatus.valueOf(rs.getString("status")),
                    instant(rs,"collected_at"),instant(rs,"created_at"),instant(rs,"updated_at"));
            PaymentDataExport.RefundData refund=null;
            if(rs.getObject("refund_id")!=null) {
                if(rs.getLong("refund_order_id")!=orderId)
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment data unavailable");
                refund=new PaymentDataExport.RefundData(rs.getLong("refund_id"),rs.getLong("refund_payment_id"),rs.getLong("refund_order_id"),
                        rs.getBigDecimal("refund_amount"),rs.getString("refund_currency"),PaymentRefund.Status.valueOf(rs.getString("refund_status")),
                        instant(rs,"refund_submitted_at"),instant(rs,"refund_created_at"),instant(rs,"refund_updated_at"));
            }
            return new PaymentDataExport(1,"payment-for-order",false,Instant.now(),payment,refund,OMISSIONS);
        },orderId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment not found");
        return rows.getFirst();
    }
    private static Instant instant(ResultSet rs,String column)throws SQLException {
        var value=rs.getTimestamp(column);return value==null?null:value.toInstant();
    }
}
