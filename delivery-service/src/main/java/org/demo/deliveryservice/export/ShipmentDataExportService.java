package org.demo.deliveryservice.export;

import org.demo.platformsecurity.OrderAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

@Service
public class ShipmentDataExportService {
    private static final List<ShipmentDataExport.Omission> OMISSIONS = List.of(
            new ShipmentDataExport.Omission("synchronizationInternals", "excluded"),
            new ShipmentDataExport.Omission("pastShipmentStates", "not-stored"),
            new ShipmentDataExport.Omission("otherAccountData", "pending"),
            new ShipmentDataExport.Omission("crossServiceSnapshot", "not-a-snapshot"));
    private final JdbcTemplate jdbc;
    private final OrderAccess orders;

    public ShipmentDataExportService(DataSource source, OrderAccess orders) {
        this.orders = orders;
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        jdbc.setMaxRows(1);
    }

    public ShipmentDataExport export(long orderId) {
        // Verify against Order on every request, before touching shipment data.
        orders.requireCustomerOwner(orderId);
        var rows = jdbc.query("""
                SELECT id,order_id,tracking_number,carrier,method_code,status,created_at,updated_at
                FROM shipments WHERE order_id=?
                """, (rs, row) -> new ShipmentDataExport.ShipmentData(rs.getLong("id"), rs.getLong("order_id"),
                rs.getString("tracking_number"), rs.getString("carrier"), rs.getString("method_code"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), orderId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found");
        return new ShipmentDataExport(1, "shipment-for-order", false, Instant.now(), rows.getFirst(), OMISSIONS);
    }
}
