package org.demo.customerservice.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.customer.Customer;
import org.demo.customerservice.customer.CustomerRepository;
import org.demo.customerservice.mfa.CustomerWebAuthnUserEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AccountDataExportService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AccountDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("a43_export_fixture").withUsername("fixture").withPassword("fixture-only-export-db");

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AccountDataExportService service;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    private Customer owner, other;

    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE customers,user_credentials RESTART IDENTITY CASCADE");
        owner = customer("owner@fixture.example");
        other = customer("other@fixture.example");
    }

    @Test void projectionWorksEvenWhenDatabaseDeniesReadingAllCredentialColumns() throws Exception {
        security(owner, true, 2);
        security(other, true, 3);
        jdbc.execute("DROP ROLE IF EXISTS a43_export_projection");
        jdbc.execute("CREATE ROLE a43_export_projection LOGIN PASSWORD 'fixture-only-projection' NOSUPERUSER");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO a43_export_projection");
        jdbc.execute("GRANT SELECT (id,email,first_name,last_name,street,postal_code,city,country,phone_number,enabled,created_at,updated_at) ON customers TO a43_export_projection");
        jdbc.execute("GRANT SELECT (customer_id,enabled) ON customer_totp_methods TO a43_export_projection");
        jdbc.execute("GRANT SELECT (customer_id,used_at) ON customer_recovery_codes TO a43_export_projection");
        jdbc.execute("GRANT SELECT (user_entity_user_id) ON user_credentials TO a43_export_projection");
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "a43_export_projection", "fixture-only-projection");
        var restricted = new JdbcTemplate(dataSource);
        assertThatThrownBy(() -> restricted.queryForList("SELECT password_hash FROM customers"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> restricted.queryForList("SELECT secret_ciphertext FROM customer_totp_methods"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var exported = new AccountDataExportService(dataSource).export(principal(owner));
        assertThat(exported.profile().id()).isEqualTo(owner.getId());
        assertThat(exported.security()).isEqualTo(new AccountDataExport.SecuritySummary(true, 2, 1));
        assertThat(exported.profile().lastName()).isEqualTo("Żółć");
        assertThat(exported.profile().phoneNumber()).isEqualTo("+48 123 456 789");
        assertThat(exported.generatedAt()).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(1));
        assertThat(exported.complete()).isFalse();
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.valueToTree(exported);
        assertThat(json.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "schemaVersion", "scope", "complete", "generatedAt", "profile", "security", "omissions");
        assertThat(json.get("profile").properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "id", "email", "firstName", "lastName", "street", "postalCode", "city", "country", "phoneNumber", "enabled", "createdAt", "updatedAt");
        assertThat(json.get("security").properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "totpEnabled", "passkeyCount", "unusedRecoveryCodeCount");
        assertThat(json.toString()).doesNotContain("fixture-password-hash", "fixture-verification-hash", "fixture-totp-secret",
                "credential-owner-", "fixture-attestation", "other@fixture.example", "passwordHash", "secretCiphertext", "codeHash");
        assertThat(exported.omissions()).extracting(AccountDataExport.Omission::scope)
                .contains("auditEvents", "otherServices", "securityHistory", "backupsAndExternalCopies", "authenticationSecrets");
    }

    @Test void noMfaAndNullablePhoneHaveExplicitEmptySummary() {
        jdbc.update("UPDATE customers SET phone_number = NULL WHERE id = ?", owner.getId());
        security(other, true, 2);
        var exported = service.export(principal(owner));
        assertThat(exported.security()).isEqualTo(new AccountDataExport.SecuritySummary(false, 0, 0));
        assertThat(exported.profile().phoneNumber()).isNull();
    }

    @Test void usesEncodedWebAuthnSubjectAndOnlyUnusedRecoveryCodes() {
        security(owner, false, 1);
        jdbc.update("INSERT INTO user_credentials SELECT 'misleading-plain-id', ?, public_key, signature_count, uv_initialized, backup_eligible, authenticator_transports, public_key_credential_type, backup_state, attestation_object, attestation_client_data_json, created, last_used, label FROM user_credentials LIMIT 1", owner.getId().toString());
        assertThat(service.export(principal(owner)).security()).isEqualTo(new AccountDataExport.SecuritySummary(false, 1, 1));
    }

    @Test void disabledDeletedOrChangedIdentityFailsClosedEvenAfterAuthentication() {
        jdbc.update("UPDATE customers SET enabled = FALSE WHERE id = ?", owner.getId());
        assertUnauthorized(principal(owner));
        jdbc.update("UPDATE customers SET enabled = TRUE, email = 'changed@fixture.example' WHERE id = ?", owner.getId());
        assertUnauthorized(principal(owner));
        jdbc.update("DELETE FROM customers WHERE id = ?", owner.getId());
        assertUnauthorized(principal(owner));
    }

    @Test void mismatchedEmailDoesNotSubstituteAnotherCustomer() {
        assertUnauthorized(new CustomerPrincipal(owner.getId(), other.getEmail()));
        assertUnauthorized(new CustomerPrincipal(99999L, owner.getEmail()));
        assertUnauthorized(new CustomerPrincipal(owner.getId(), "' OR 1=1 --"));
    }

    @Test void missingOrMalformedPrincipalCannotQueryData() {
        assertUnauthorized(null);
        for (var principal : List.of(new CustomerPrincipal(null, owner.getEmail()),
                new CustomerPrincipal(-1L, owner.getEmail()), new CustomerPrincipal(0L, owner.getEmail()),
                new CustomerPrincipal(owner.getId(), null), new CustomerPrincipal(owner.getId(), " "))) {
            assertUnauthorized(principal);
        }
    }

    @Test void exportDoesNotChangeAccountMfaOrCredentialRows() {
        security(owner, true, 2);
        String before = snapshot();
        service.export(principal(owner));
        service.export(principal(owner));
        assertThat(snapshot()).isEqualTo(before);
    }

    private Customer customer(String email) {
        var customer = new Customer(email, "fixture-password-hash", "Jan", "Żółć", "Testowa 1", "00-001", "Warszawa", "Polska");
        customer.confirmVerification();
        customer.updateProfile("Jan", "Żółć", "Testowa 1", "00-001", "Warszawa", "Polska", "+48 123 456 789");
        customer = customers.saveAndFlush(customer);
        jdbc.update("UPDATE customers SET verification_token_hash = ? WHERE id = ?", "fixture-verification-hash-" + customer.getId(), customer.getId());
        return customer;
    }

    private void security(Customer customer, boolean enabled, int passkeys) {
        jdbc.update("INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,created_at,updated_at) VALUES (?, 'fixture-totp-secret', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", customer.getId(), enabled);
        jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,created_at,used_at) VALUES (?, ?, CURRENT_TIMESTAMP, NULL), (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                customer.getId(), customer.getId() + "a".repeat(60), customer.getId(), customer.getId() + "b".repeat(60));
        for (int i = 0; i < passkeys; i++) {
            jdbc.update("""
                    INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,
                        uv_initialized,backup_eligible,public_key_credential_type,backup_state,attestation_object,created,label)
                    VALUES (?, ?, ?, 0, FALSE, FALSE, 'public-key', FALSE, ?, CURRENT_TIMESTAMP, 'fixture label')
                    """, "credential-owner-" + customer.getId() + "-" + i,
                    CustomerWebAuthnUserEntityRepository.userId(customer.getId()).toBase64UrlString(),
                    new byte[]{1}, "fixture-attestation".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private String snapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_array(
                    (SELECT json_agg(c ORDER BY id) FROM customers c),
                    (SELECT json_agg(t ORDER BY id) FROM customer_totp_methods t),
                    (SELECT json_agg(r ORDER BY id) FROM customer_recovery_codes r),
                    (SELECT json_agg(w ORDER BY credential_id) FROM user_credentials w)
                )::text
                """, String.class);
    }

    private CustomerPrincipal principal(Customer customer) { return new CustomerPrincipal(customer.getId(), customer.getEmail()); }
    private void assertUnauthorized(CustomerPrincipal principal) {
        assertThatThrownBy(() -> service.export(principal)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
    }
}
