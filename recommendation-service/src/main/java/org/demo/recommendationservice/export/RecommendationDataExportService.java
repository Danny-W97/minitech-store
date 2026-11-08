package org.demo.recommendationservice.export;

import org.demo.recommendationservice.telemetry.RecommendationLogProperties;
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
public class RecommendationDataExportService {
    private static final String SQL = """
            SELECT id, source_product_id, strategy, result_count, requested_at FROM recommendation_requests
            WHERE customer_id = :customerId AND id <= :ceiling ORDER BY id DESC LIMIT :limit
            """;
    private static final List<RecommendationDataExportPage.Omission> OMISSIONS = List.of(
            new RecommendationDataExportPage.Omission("unloggedOrPurgedRequests", "not-available"),
            new RecommendationDataExportPage.Omission("anonymousRequests", "not-account-linked"),
            new RecommendationDataExportPage.Omission("otherAccountData", "pending"),
            new RecommendationDataExportPage.Omission("changesDuringPagination", "not-a-snapshot")
    );
    private final NamedParameterJdbcTemplate jdbc;
    private final RecommendationLogProperties properties;

    public RecommendationDataExportService(DataSource source, RecommendationLogProperties properties) {
        var template = new JdbcTemplate(source); template.setQueryTimeout(2);
        jdbc = new NamedParameterJdbcTemplate(template); this.properties = properties;
    }

    public RecommendationDataExportPage export(long customerId, long cursor, int size) {
        if (customerId < 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (cursor < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor must be nonnegative; size must be between 1 and 100");
        long ceiling = cursor == 0 ? Long.MAX_VALUE : cursor - 1;
        List<RecommendationDataExportPage.Item> rows = jdbc.query(SQL,
                Map.of("customerId", customerId, "ceiling", ceiling, "limit", size + 1),
                (rs, row) -> new RecommendationDataExportPage.Item(rs.getLong("id"),
                        rs.getObject("source_product_id", Long.class), rs.getString("strategy"),
                        rs.getInt("result_count"), rs.getTimestamp("requested_at").toInstant()));
        boolean hasMore = rows.size() > size;
        var page = rows.stream().limit(size).toList();
        String nextCursor = hasMore ? Long.toString(page.get(page.size() - 1).requestId()) : "0";
        return new RecommendationDataExportPage(1, "recommendation-log-page", false, Instant.now(),
                properties.enabled(), "optional-retained-log", page, hasMore, nextCursor, OMISSIONS);
    }
}
