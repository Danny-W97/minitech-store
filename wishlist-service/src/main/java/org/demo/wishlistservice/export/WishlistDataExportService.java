package org.demo.wishlistservice.export;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class WishlistDataExportService {
    private static final String SQL = """
            SELECT id, product_id, created_at FROM wishlist_items
            WHERE customer_id = :customerId AND id <= :ceiling
            ORDER BY id DESC LIMIT :limit
            """;
    private static final List<WishlistDataExportPage.Omission> OMISSIONS = List.of(
            new WishlistDataExportPage.Omission("otherAccountData", "pending"),
            new WishlistDataExportPage.Omission("changesDuringPagination", "not-a-snapshot")
    );
    private final NamedParameterJdbcTemplate jdbc;

    public WishlistDataExportService(DataSource source) {
        var template = new JdbcTemplate(source);
        template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
    }

    public WishlistDataExportPage export(long customerId, long cursor, int size) {
        if (customerId < 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (cursor < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor must be nonnegative; size must be between 1 and 100");
        long ceiling = cursor == 0 ? Long.MAX_VALUE : cursor - 1;
        List<Row> rows = jdbc.query(SQL, Map.of("customerId", customerId, "ceiling", ceiling, "limit", size + 1),
                (rs, row) -> new Row(rs.getLong("id"), rs.getLong("product_id"), rs.getTimestamp("created_at").toInstant()));
        boolean hasMore = rows.size() > size;
        List<Row> page = rows.stream().limit(size).toList();
        String nextCursor = hasMore ? Long.toString(page.get(page.size() - 1).id()) : "0";
        return new WishlistDataExportPage(1, "wishlist-page", false, Instant.now(),
                page.stream().map(item -> new WishlistDataExportPage.Item(item.productId(), item.createdAt())).toList(),
                hasMore, nextCursor, OMISSIONS);
    }

    private record Row(long id, long productId, Instant createdAt) {}
}
