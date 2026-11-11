package org.demo.customerservice.export;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.mfa.CustomerWebAuthnUserEntityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Service
public class AccountDataExportService {
    // One statement/snapshot and one profile; no unbounded entity lists or credential columns.
    private static final String SQL = """
            SELECT CURRENT_TIMESTAMP AS generated_at,
                   c.id, c.email, c.first_name, c.last_name, c.street, c.postal_code,
                   c.city, c.country, c.phone_number, c.enabled, c.created_at, c.updated_at,
                   EXISTS (SELECT 1 FROM customer_totp_methods t
                           WHERE t.customer_id = c.id AND t.enabled = TRUE) AS totp_enabled,
                   (SELECT COUNT(*) FROM user_credentials w
                    WHERE w.user_entity_user_id = :webauthnUserId) AS passkey_count,
                   (SELECT COUNT(*) FROM customer_recovery_codes r
                    WHERE r.customer_id = c.id AND r.used_at IS NULL) AS unused_recovery_code_count
            FROM customers c
            WHERE c.id = :customerId AND c.email = :email AND c.enabled = TRUE
            """;

    private static final List<AccountDataExport.Omission> OMISSIONS = List.of(
            new AccountDataExport.Omission("authenticationSecrets", "excluded"),
            new AccountDataExport.Omission("securityHistory", "separate-security-endpoints-and-manual-review"),
            new AccountDataExport.Omission("auditEvents", "separate-security-endpoints-and-manual-review"),
            new AccountDataExport.Omission("otherServices", "pending"),
            new AccountDataExport.Omission("redisAndRuntime", "reviewed-exclusions-and-manual-procedure"),
            new AccountDataExport.Omission("backupsAndExternalCopies", "separate-copy-procedures-A43.37-A43.39")
    );

    private final NamedParameterJdbcTemplate jdbc;

    public AccountDataExportService(DataSource dataSource) {
        var template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(2);
        template.setMaxRows(1);
        jdbc = new NamedParameterJdbcTemplate(template);
    }

    public AccountDataExport export(CustomerPrincipal principal) {
        if (principal == null || principal.customerId() == null || principal.customerId() <= 0
                || principal.email() == null || principal.email().isBlank()) {
            throw unauthorized();
        }
        var parameters = Map.of("customerId", principal.customerId(), "email", principal.email(),
                "webauthnUserId", CustomerWebAuthnUserEntityRepository.userId(principal.customerId()).toBase64UrlString());
        return jdbc.query(SQL, parameters, (rs, row) -> new AccountDataExport(
                1, "customer-profile-and-security-summary", false,
                rs.getTimestamp("generated_at").toInstant(),
                new AccountDataExport.Profile(rs.getLong("id"), rs.getString("email"),
                        rs.getString("first_name"), rs.getString("last_name"), rs.getString("street"),
                        rs.getString("postal_code"), rs.getString("city"), rs.getString("country"),
                        rs.getString("phone_number"), rs.getBoolean("enabled"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                new AccountDataExport.SecuritySummary(rs.getBoolean("totp_enabled"),
                        rs.getLong("passkey_count"), rs.getLong("unused_recovery_code_count")), OMISSIONS
        )).stream().findFirst().orElseThrow(AccountDataExportService::unauthorized);
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}
