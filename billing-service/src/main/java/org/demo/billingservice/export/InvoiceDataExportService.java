package org.demo.billingservice.export;

import org.demo.platformsecurity.ListWindow;
import org.demo.platformsecurity.OrderAccess;
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
public class InvoiceDataExportService {
    private static final String HEADER="""
            SELECT id,order_id,customer_email,invoice_number,billing_kind,buyer_name,company_name,tax_id,street,postal_code,city,country,
                   net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at
            FROM invoices WHERE order_id=:orderId
            """;
    private static final String OWNED="""
            WITH owned AS MATERIALIZED (SELECT id FROM invoices WHERE order_id=:orderId)
            """;
    private static final String ITEMS=OWNED+"""
            SELECT o.id AS invoice_id,i.id,i.position_no,i.product_id,i.sku,i.product_name,i.quantity,i.unit_price,i.total_price
            FROM owned o LEFT JOIN LATERAL (
              SELECT id,position_no,product_id,sku,product_name,quantity,unit_price,total_price FROM invoice_items
              WHERE invoice_id=o.id AND position_no<=:ceiling ORDER BY position_no DESC LIMIT :limit
            ) i ON TRUE ORDER BY i.position_no DESC
            """;
    private static final String CORRECTIONS=OWNED+"""
            SELECT o.id AS invoice_id,c.id,c.order_id,c.correction_number,c.reason,c.original_net,c.original_vat,c.original_gross,
                   c.correction_net,c.correction_vat,c.correction_gross,c.final_net,c.final_vat,c.final_gross,c.created_at,c.balance_model
            FROM owned o LEFT JOIN LATERAL (
              SELECT id,order_id,correction_number,reason,original_net,original_vat,original_gross,
                     correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at,
                     CASE WHEN ledger_version IS NULL THEN 'LEGACY_BASE_DELTA' ELSE 'CUMULATIVE_DELTA' END AS balance_model
              FROM invoice_corrections WHERE invoice_id=o.id AND id<=:ceiling ORDER BY id DESC LIMIT :limit
            ) c ON TRUE ORDER BY c.id DESC
            """;
    private static final List<InvoiceDataExport.Omission> OMISSIONS=List.of(
            new InvoiceDataExport.Omission("invoiceItemsAndCorrections","separate-endpoints"),
            new InvoiceDataExport.Omission("buyerAndCorrectionText","as-stored-no-semantic-redaction"),
            new InvoiceDataExport.Omission("workflowInternalsAndLocalCustomerId","excluded"),
            new InvoiceDataExport.Omission("pdfRepresentation","rendered-from-stored-document-not-original-archive"),
            new InvoiceDataExport.Omission("otherAccountData","pending"),
            new InvoiceDataExport.Omission("crossServiceSnapshot","not-a-snapshot"));
    private static final List<InvoiceDataExport.Omission> PAGE_OMISSIONS=List.of(
            new InvoiceDataExport.Omission("invoiceHeaderAndOtherData","separate-or-pending"),
            new InvoiceDataExport.Omission("idempotencyAndRawLedgerVersion","excluded"),
            new InvoiceDataExport.Omission("buyerAndCorrectionText","as-stored-no-semantic-redaction"),
            new InvoiceDataExport.Omission("legacyCorrectionAmounts","stored-snapshots-not-recalculated"),
            new InvoiceDataExport.Omission("pdfRepresentation","rendered-from-stored-document-not-original-archive"),
            new InvoiceDataExport.Omission("changesDuringPaginationAndOwnershipCheck","not-a-snapshot"));
    private final NamedParameterJdbcTemplate jdbc;
    private final OrderAccess orders;
    public InvoiceDataExportService(DataSource source,OrderAccess orders) {
        this.orders=orders;var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);
    }
    public InvoiceDataExport invoice(long orderId) {
        orders.requireCustomerOwner(orderId);
        var rows=jdbc.query(HEADER,Map.of("orderId",orderId),(rs,row)->new InvoiceDataExport.InvoiceData(rs.getLong("id"),rs.getLong("order_id"),
                rs.getString("customer_email"),rs.getString("invoice_number"),rs.getString("billing_kind"),rs.getString("buyer_name"),
                rs.getString("company_name"),rs.getString("tax_id"),rs.getString("street"),rs.getString("postal_code"),rs.getString("city"),rs.getString("country"),
                rs.getBigDecimal("net_amount"),rs.getBigDecimal("vat_amount"),rs.getBigDecimal("gross_amount"),rs.getBigDecimal("delivery_cost"),
                rs.getBigDecimal("discount_amount"),rs.getString("currency"),rs.getString("status"),rs.getTimestamp("created_at").toInstant()));
        if(rows.isEmpty())throw missing();
        return new InvoiceDataExport(1,"invoice-for-order",false,Instant.now(),rows.getFirst(),document(orderId,""),OMISSIONS);
    }
    public InvoiceItemDataExportPage items(long orderId,long cursor,int size) {
        var window=window(cursor,size);orders.requireCustomerOwner(orderId);
        List<ItemRow> rows=jdbc.query(ITEMS,parameters(orderId,window),(rs,row)->new ItemRow(rs.getObject("id")==null?null:
                new InvoiceItemDataExportPage.Item(rs.getLong("id"),rs.getInt("position_no"),rs.getLong("product_id"),rs.getString("sku"),
                        rs.getString("product_name"),rs.getInt("quantity"),rs.getBigDecimal("unit_price"),rs.getBigDecimal("total_price"))));
        if(rows.isEmpty())throw missing();
        var page=window.map(rows.stream().map(ItemRow::item).filter(Objects::nonNull).toList(),InvoiceItemDataExportPage.Item::positionNo,Function.identity());
        return new InvoiceItemDataExportPage(1,"invoice-items-page",false,Instant.now(),orderId,page.items(),page.hasMore(),page.nextCursor(),PAGE_OMISSIONS);
    }
    public InvoiceCorrectionDataExportPage corrections(long orderId,long cursor,int size) {
        var window=window(cursor,size);orders.requireCustomerOwner(orderId);
        List<CorrectionRow> rows=jdbc.query(CORRECTIONS,parameters(orderId,window),(rs,row)->{
            if(rs.getObject("id")==null)return new CorrectionRow(null);
            requireOrder(rs.getLong("order_id"),orderId);
            return new CorrectionRow(new InvoiceCorrectionDataExportPage.Correction(rs.getLong("id"),rs.getLong("invoice_id"),rs.getLong("order_id"),
                    rs.getString("correction_number"),rs.getString("reason"),rs.getBigDecimal("original_net"),rs.getBigDecimal("original_vat"),
                    rs.getBigDecimal("original_gross"),rs.getBigDecimal("correction_net"),rs.getBigDecimal("correction_vat"),rs.getBigDecimal("correction_gross"),
                    rs.getBigDecimal("final_net"),rs.getBigDecimal("final_vat"),rs.getBigDecimal("final_gross"),rs.getTimestamp("created_at").toInstant(),
                    rs.getString("balance_model"),document(orderId,"/corrections/"+rs.getLong("id"))));
        });
        if(rows.isEmpty())throw missing();
        var page=window.map(rows.stream().map(CorrectionRow::correction).filter(Objects::nonNull).toList(),InvoiceCorrectionDataExportPage.Correction::correctionId,Function.identity());
        return new InvoiceCorrectionDataExportPage(1,"invoice-corrections-page",false,Instant.now(),orderId,page.items(),page.hasMore(),page.nextCursor(),PAGE_OMISSIONS);
    }
    public long invoiceId(long orderId) {
        orders.requireCustomerOwner(orderId);
        var rows=jdbc.query("SELECT id FROM invoices WHERE order_id=:orderId",Map.of("orderId",orderId),(rs,row)->rs.getLong("id"));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public long correctionId(long orderId,long correctionId) {
        if(correctionId<1)throw missing();orders.requireCustomerOwner(orderId);
        var rows=jdbc.query("""
                SELECT c.id,c.order_id FROM invoices i JOIN invoice_corrections c ON c.invoice_id=i.id
                WHERE i.order_id=:orderId AND c.id=:correctionId
                """,Map.of("orderId",orderId,"correctionId",correctionId),(rs,row)->{
            requireOrder(rs.getLong("order_id"),orderId);return rs.getLong("id");
        });
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private record ItemRow(InvoiceItemDataExportPage.Item item) {}
    private record CorrectionRow(InvoiceCorrectionDataExportPage.Correction correction) {}
    private static InvoiceDataExport.Document document(long orderId,String suffix) {
        return new InvoiceDataExport.Document("/invoices/me/data-export/orders/"+orderId+suffix+"/pdf","application/pdf","rendered-stored-document");
    }
    private static void requireOrder(long actual,long expected) {
        if(actual!=expected)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invoice data unavailable");
    }
    private static Map<String,Object> parameters(long orderId,ListWindow window){return Map.of("orderId",orderId,"ceiling",window.ceiling(),"limit",window.size()+1);}
    private static ListWindow window(long cursor,int size) {
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        return new ListWindow(cursor,size);
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Invoice document not found");}
}
