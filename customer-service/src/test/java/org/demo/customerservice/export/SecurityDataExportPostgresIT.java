package org.demo.customerservice.export;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class SecurityDataExportPostgresIT {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("security_export_fixture").withUsername("fixture").withPassword("fixture-only");
    private static JdbcTemplate jdbc;private static DriverManagerDataSource source;private static SecurityDataExportService service;
    private static final CustomerPrincipal OWNER=new CustomerPrincipal(1L,"owner@fixture.example"),OTHER=new CustomerPrincipal(2L,"other@fixture.example");
    @BeforeAll static void database(){source=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);service=new SecurityDataExportService(source);}
    @BeforeEach void reset(){jdbc.execute("TRUNCATE customers,user_credentials,audit_events RESTART IDENTITY CASCADE");jdbc.update("""
            INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            SELECT n,CASE WHEN n=1 THEN 'owner@fixture.example' WHEN n=2 THEN 'other@fixture.example' ELSE 'disabled@fixture.example' END,
              'private-password','Jan','Test','Testowa 1','00-001','Warszawa','PL',n<>3,now(),now() FROM generate_series(1,3) n
            """);}
    @Test void eventSubjectIsNotInferredFromEmailActorOrUntrustedMetadata(){
        event(1L,"LOGIN_SUCCESS","other@fixture.example","CUSTOMER","SUCCESS");
        event(2L,"LOGIN_SUCCESS","1","CUSTOMER","SUCCESS");event(null,"LOGIN_SUCCESS","owner@fixture.example","CUSTOMER","SUCCESS");
        event(null,"TOKEN_REFRESH","1","REFRESH_TOKEN","SUCCESS");event(null,"REFRESH_TOKEN_REUSE","1","REFRESH_TOKEN","REJECTED");
        event(null,"TOKEN_REFRESH","01","REFRESH_TOKEN","SUCCESS");event(null,"TOKEN_REFRESH","1","CUSTOMER","SUCCESS");
        event(1L,"private-unknown","1","CUSTOMER","SUCCESS");event(1L,"LOGIN_SUCCESS","1","CUSTOMER","private-outcome");
        var page=service.events(OWNER,0,50);assertThat(page.items()).extracting(SecurityDataExport.Event::eventId).containsExactly(5L,4L,1L);
        assertThat(page.items()).extracting(SecurityDataExport.Event::subjectProof).containsExactly("legacy-stable-id","legacy-stable-id","subject-customer-id");
        assertThat(service.events(OTHER,0,50).items()).extracting(SecurityDataExport.Event::eventId).containsExactly(2L);
    }
    @Test void largeMixedSubjectAndLegacyAuditIsBoundedWithoutDuplicates(){
        for(int n=0;n<105;n++)event(n%2==0?1L:null,n%2==0?"LOGIN_SUCCESS":"TOKEN_REFRESH","1",n%2==0?"CUSTOMER":"REFRESH_TOKEN","SUCCESS");
        var ids=new HashSet<Long>();long cursor=0;int pages=0;
        while(true){var page=service.events(OWNER,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);page.items().forEach(e->assertThat(ids.add(e.eventId())).isTrue());if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(105);assertThat(pages).isEqualTo(3);
    }
    @Test void passkeyAndRecoveryMetadataUseOwnCanonicalSubjectAndNonCredentialCursor(){
        credentials(1,105);credentials(2,1);recovery(1,105);recovery(2,1);
        var ids=new HashSet<Long>();long cursor=0;int pages=0;
        while(true){var page=service.passkeys(OWNER,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);page.items().forEach(e->assertThat(ids.add(e.exportId())).isTrue());if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(105);assertThat(pages).isEqualTo(3);assertThat(service.passkeys(OTHER,0,50).items()).hasSize(1);
        ids.clear();cursor=0;pages=0;
        while(true){var page=service.recovery(OWNER,cursor,50);pages++;assertThat(page.items()).hasSizeLessThanOrEqualTo(50);page.items().forEach(e->assertThat(ids.add(e.recordId())).isTrue());if(!page.hasMore())break;cursor=Long.parseLong(page.nextCursor());}
        assertThat(ids).hasSize(105);assertThat(pages).isEqualTo(3);assertThat(service.recovery(OTHER,0,50).items()).hasSize(1);
    }
    @Test void databaseRechecksActiveMatchingPrincipalEvenForEmptyAndExhaustedPages(){
        for(var principal:new CustomerPrincipal[]{new CustomerPrincipal(1L,"other@fixture.example"),new CustomerPrincipal(3L,"disabled@fixture.example"),new CustomerPrincipal(999L,"owner@fixture.example")})
            for(Runnable call:new Runnable[]{()->service.methods(principal),()->service.events(principal,1,50),()->service.passkeys(principal,1,50),()->service.recovery(principal,1,50)})unauthorized(call);
        assertThat(service.methods(OWNER).totp()).isNull();assertThat(service.events(OWNER,0,50).items()).isEmpty();
        assertThat(service.passkeys(OWNER,0,50).items()).isEmpty();assertThat(service.recovery(OWNER,0,50).items()).isEmpty();
    }
    @Test void disablingCustomerBetweenPagesRevokesAllMetadataReads(){
        credentials(1,2);recovery(1,2);event(1L,"LOGIN_SUCCESS","private","CUSTOMER","SUCCESS");
        service.passkeys(OWNER,0,1);service.recovery(OWNER,0,1);service.events(OWNER,0,1);jdbc.update("UPDATE customers SET enabled=false WHERE id=1");
        unauthorized(()->service.passkeys(OWNER,2,50));unauthorized(()->service.recovery(OWNER,2,50));unauthorized(()->service.events(OWNER,2,50));
    }
    @Test void totpDatesAndStoredEnabledStateArePreservedWithoutDecryption(){
        jdbc.update("INSERT INTO customer_totp_methods(customer_id,secret_ciphertext,enabled,last_used_time_step,created_at,updated_at,enrollment_expires_at) VALUES(1,'private-ciphertext',false,123,now()-interval '1 day',now(),now()+interval '5 minutes')");
        var data=service.methods(OWNER);assertThat(data.totp().enabled()).isFalse();assertThat(data.totp().enrollmentExpiresAt()).isAfter(java.time.Instant.now());assertThat(data.complete()).isFalse();
    }
    @Test void restrictedDatabaseRoleCannotReadAnySecretOrFreeTextAndExportsStillWork(){
        credentials(1,1);recovery(1,1);event(1L,"LOGIN_SUCCESS","private","CUSTOMER","SUCCESS");
        jdbc.execute("CREATE ROLE projection_reader LOGIN PASSWORD 'fixture-reader' NOSUPERUSER");jdbc.execute("GRANT USAGE ON SCHEMA public TO projection_reader");
        jdbc.execute("GRANT SELECT(id,email,enabled) ON customers TO projection_reader");
        jdbc.execute("GRANT SELECT(id,customer_id,enabled,created_at,updated_at,enrollment_expires_at) ON customer_totp_methods TO projection_reader");
        jdbc.execute("GRANT SELECT(id,customer_id,created_at,used_at) ON customer_recovery_codes TO projection_reader");
        jdbc.execute("GRANT SELECT(export_id,user_entity_user_id,created,last_used,uv_initialized,backup_eligible,backup_state) ON user_credentials TO projection_reader");
        jdbc.execute("GRANT SELECT(id,subject_customer_id,actor,resource,event_type,outcome,occurred_at) ON audit_events TO projection_reader");
        var restricted=new DriverManagerDataSource(POSTGRES.getJdbcUrl(),"projection_reader","fixture-reader");var limited=new JdbcTemplate(restricted);
        for(String sql:List.of("SELECT metadata FROM audit_events","SELECT client_ip FROM audit_events","SELECT user_agent FROM audit_events","SELECT correlation_id FROM audit_events", "SELECT secret_ciphertext FROM customer_totp_methods","SELECT code_hash FROM customer_recovery_codes","SELECT credential_id FROM user_credentials","SELECT public_key FROM user_credentials","SELECT label FROM user_credentials","SELECT attestation_object FROM user_credentials","SELECT signature_count FROM user_credentials"))
            assertThatThrownBy(()->limited.queryForList(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var export=new SecurityDataExportService(restricted);assertThat(export.events(OWNER,0,50).items()).hasSize(1);assertThat(export.passkeys(OWNER,0,50).items()).hasSize(1);assertThat(export.recovery(OWNER,0,50).items()).hasSize(1);assertThat(export.methods(OWNER).totp()).isNull();
    }
    @Test void exportsNeverConsumeRegenerateOrModifyAuditAndCredentialData(){
        credentials(1,2);recovery(1,2);event(1L,"LOGIN_SUCCESS","private","CUSTOMER","SUCCESS");String before=snapshot();
        service.methods(OWNER);service.events(OWNER,0,50);service.passkeys(OWNER,0,50);service.recovery(OWNER,0,50);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void expectedPagingIndexesExistWithoutRewritingOldAuditSubjects(){
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_audit_subject_export','idx_audit_legacy_refresh_export','idx_user_credentials_export_window','idx_recovery_metadata_export_window')",Integer.class)).isEqualTo(4);
        event(null,"LOGIN_SUCCESS","owner@fixture.example","CUSTOMER","SUCCESS");assertThat(jdbc.queryForObject("SELECT subject_customer_id FROM audit_events LIMIT 1",Long.class)).isNull();
    }
    private void event(Long subject,String type,String actor,String resource,String outcome){jdbc.update("INSERT INTO audit_events(subject_customer_id,event_type,actor,resource,outcome,correlation_id,client_ip,user_agent,metadata,occurred_at) VALUES (?,?,?,?,?,'private-correlation','private-ip','private-agent','private-other-person',now())",subject,type,actor,resource,outcome);}
    private void credentials(long customer,int count){String id=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(customer).getBytes(java.nio.charset.StandardCharsets.UTF_8));jdbc.update("""
            INSERT INTO user_credentials(credential_id,user_entity_user_id,public_key,signature_count,uv_initialized,backup_eligible,authenticator_transports,public_key_credential_type,backup_state,attestation_object,attestation_client_data_json,created,last_used,label)
            SELECT concat('private-credential-',?,n),?,decode('deadbeef','hex'),99,true,true,'private-transport','public-key',false,decode('beef','hex'),decode('abcd','hex'),now(),null,'private-other-person-label' FROM generate_series(1,?) n
            """,customer,id,count);}
    private void recovery(long customer,int count){jdbc.update("INSERT INTO customer_recovery_codes(customer_id,code_hash,used_at,created_at) SELECT ?,md5(concat(?,':',n)),CASE WHEN n%2=0 THEN now() ELSE null END,now() FROM generate_series(1,?) n",customer,customer,count);}
    private String snapshot(){return jdbc.queryForObject("SELECT json_build_array((SELECT json_agg(a ORDER BY id) FROM audit_events a),(SELECT json_agg(t ORDER BY id) FROM customer_totp_methods t),(SELECT json_agg(r ORDER BY id) FROM customer_recovery_codes r),(SELECT json_agg(w ORDER BY export_id) FROM user_credentials w))::text",String.class);}
    private static void unauthorized(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));}
}
