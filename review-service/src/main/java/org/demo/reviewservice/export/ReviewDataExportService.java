package org.demo.reviewservice.export;

import org.demo.reviewservice.review.ReviewStatus;
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
public class ReviewDataExportService {
    private static final String SQL = """
            SELECT id, product_id, rating, title, content, status, created_at, updated_at
            FROM reviews WHERE customer_id = :customerId AND id <= :ceiling
            ORDER BY id DESC LIMIT :limit
            """;
    private static final List<ReviewDataExportPage.Omission> OMISSIONS = List.of(
            new ReviewDataExportPage.Omission("moderationNotes", "manual-review-and-redaction-procedure"),
            new ReviewDataExportPage.Omission("orderLinks", "excluded"),
            new ReviewDataExportPage.Omission("otherAccountData", "pending"),
            new ReviewDataExportPage.Omission("changesDuringPagination", "not-a-snapshot"),
            new ReviewDataExportPage.Omission("userAuthoredText", "as-stored-no-semantic-redaction")
    );
    private final NamedParameterJdbcTemplate jdbc;

    public ReviewDataExportService(DataSource source) {
        var template = new JdbcTemplate(source);
        template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template);
    }

    public ReviewDataExportPage export(long customerId, long cursor, int size) {
        if (customerId < 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (cursor < 0 || size < 1 || size > 50)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor must be nonnegative; size must be between 1 and 50");
        long ceiling = cursor == 0 ? Long.MAX_VALUE : cursor - 1;
        List<ReviewDataExportPage.Item> rows = jdbc.query(SQL,
                Map.of("customerId", customerId, "ceiling", ceiling, "limit", size + 1),
                (rs, row) -> new ReviewDataExportPage.Item(rs.getLong("id"), rs.getLong("product_id"),
                        rs.getInt("rating"), rs.getString("title"), rs.getString("content"),
                        ReviewStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
        boolean hasMore = rows.size() > size;
        List<ReviewDataExportPage.Item> page = rows.stream().limit(size).toList();
        String nextCursor = hasMore ? Long.toString(page.get(page.size() - 1).reviewId()) : "0";
        return new ReviewDataExportPage(1, "reviews-page", false, Instant.now(), page, hasMore, nextCursor, OMISSIONS);
    }
}
