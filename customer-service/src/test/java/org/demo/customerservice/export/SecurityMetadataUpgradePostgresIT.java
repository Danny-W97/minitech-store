package org.demo.customerservice.export;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SecurityMetadataUpgradePostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("security_upgrade_fixture").withUsername("fixture").withPassword("fixture-only");
    @Test void upgradingPopulatedV15PreservesCredentialsAndNeverInfersHistoricalEmailSubjects(){
        var source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("15").load().migrate();var jdbc=new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
                VALUES (1,'owner@fixture.example','private-password','Jan','Test','Testowa 1','00-001','Warszawa','PL',true,now(),now());
                INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,backup_state,public_key_credential_type,created,last_used,label,attestation_object,attestation_client_data_json)
                VALUES ('AQID','MQ',decode('01020304','hex'),123,true,true,false,'public-key',now(),now(),'private-label',decode('beef','hex'),decode('abcd','hex'));
                INSERT INTO audit_events(event_type,actor,resource,outcome,correlation_id,metadata,occurred_at)
                VALUES ('LOGIN_SUCCESS','owner@fixture.example','CUSTOMER','SUCCESS','private-correlation','private',now()),
                       ('TOKEN_REFRESH','1','REFRESH_TOKEN','SUCCESS','private-correlation','private',now())
                """);
        String credentials=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(w) ORDER BY credential_id)::text FROM user_credentials w",String.class);
        String audit=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(a) ORDER BY id)::text FROM audit_events a",String.class);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(w)-'export_id' ORDER BY credential_id)::text FROM user_credentials w",String.class)).isEqualTo(credentials);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(a)-'subject_customer_id' ORDER BY id)::text FROM audit_events a",String.class)).isEqualTo(audit);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE subject_customer_id IS NOT NULL",Integer.class)).isZero();
        // Existing authentication inserts omit the export column and still receive its database default.
        jdbc.update("""
                INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,backup_state,public_key_credential_type,created)
                VALUES ('BAUG','MQ',decode('040506','hex'),0,false,false,false,'public-key',now())
                """);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT export_id) FROM user_credentials WHERE export_id>0",Integer.class)).isEqualTo(2);
    }
}
