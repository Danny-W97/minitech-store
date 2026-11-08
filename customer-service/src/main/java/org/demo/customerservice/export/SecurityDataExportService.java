package org.demo.customerservice.export;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.mfa.CustomerWebAuthnUserEntityRepository;
import org.demo.platformsecurity.ListWindow;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

@Service
public class SecurityDataExportService {
    private static final String OWNED="""
            WITH owned AS MATERIALIZED (SELECT id FROM customers WHERE id=:customerId AND email=:email AND enabled=TRUE)
            """;
    private static final String METHODS=OWNED+"""
            SELECT o.id AS owned_id,t.id,t.enabled,t.created_at,t.updated_at,t.enrollment_expires_at
            FROM owned o LEFT JOIN customer_totp_methods t ON t.customer_id=o.id
            """;
    private static final String RECOVERY=OWNED+"""
            SELECT o.id AS owned_id,m.id,m.created_at,m.used_at FROM owned o LEFT JOIN LATERAL (
              SELECT id,created_at,used_at FROM customer_recovery_codes WHERE customer_id=o.id AND id<=:ceiling
              ORDER BY id DESC LIMIT :limit) m ON TRUE ORDER BY m.id DESC
            """;
    private static final String PASSKEYS=OWNED+"""
            SELECT o.id AS owned_id,m.export_id,m.created,m.last_used,m.uv_initialized,m.backup_eligible,m.backup_state
            FROM owned o LEFT JOIN LATERAL (
              SELECT export_id,created,last_used,uv_initialized,backup_eligible,backup_state FROM user_credentials
              WHERE user_entity_user_id=:webauthnUserId AND export_id<=:ceiling ORDER BY export_id DESC LIMIT :limit
            ) m ON TRUE ORDER BY m.export_id DESC
            """;
    // Strict event/resource/outcome allowlist prevents exporting free-form future audit fields.
    private static final String EVENTS=OWNED+"""
            SELECT o.id AS owned_id,m.id,m.event_type,m.outcome,m.occurred_at,m.subject_proof
            FROM owned o LEFT JOIN LATERAL (
              (SELECT id,event_type,outcome,occurred_at,'subject-customer-id' AS subject_proof FROM audit_events
               WHERE subject_customer_id=o.id AND id<=:ceiling AND outcome IN ('SUCCESS','REJECTED','PENDING','ACCEPTED','SENT')
                 AND ((resource='CUSTOMER' AND event_type IN ('LOGIN_BLOCKED','LOGIN_LOCKED','LOGIN_FAILURE','LOGIN_SUCCESS',
                   'CUSTOMER_REGISTERED','VERIFICATION_RESENT','PASSWORD_RESET_REQUESTED','PASSWORD_RESET_CONFIRMED','CUSTOMER_VERIFICATION','CUSTOMER_PROFILE_UPDATED'))
                   OR (resource='MFA' AND event_type IN ('MFA_LOGIN_CHALLENGE','MFA_LOGIN_FAILURE','MFA_LOGIN_SUCCESS','MFA_TOTP_ENROLLMENT_STARTED',
                     'MFA_TOTP_ENABLED','MFA_TOTP_DISABLED','MFA_RECOVERY_CODES_REGENERATED','MFA_WEBAUTHN_REGISTERED','MFA_WEBAUTHN_LOGIN_SUCCESS'))
                   OR (resource='REFRESH_TOKEN' AND event_type IN ('TOKEN_REFRESH','REFRESH_TOKEN_REUSE','LOGOUT'))))
              UNION ALL
              (SELECT id,event_type,outcome,occurred_at,'legacy-stable-id' AS subject_proof FROM audit_events
               WHERE subject_customer_id IS NULL AND actor=:legacyActor AND resource='REFRESH_TOKEN' AND id<=:ceiling
                 AND ((event_type='TOKEN_REFRESH' AND outcome='SUCCESS') OR (event_type='REFRESH_TOKEN_REUSE' AND outcome='REJECTED')))
              ORDER BY id DESC LIMIT :limit
            ) m ON TRUE ORDER BY m.id DESC
            """;
    private static final List<SecurityDataExport.Omission> OMISSIONS=List.of(
            new SecurityDataExport.Omission("authenticationSecretsKeysAndCredentialIdentifiers","excluded"),
            new SecurityDataExport.Omission("freeTextNotesLabelsRawMetadataIpUserAgentAndActor","manual-review-and-redaction-procedure"),
            new SecurityDataExport.Omission("legacyEmailAnonymousAndUnknownAuditEvents","manual-subject-verification-no-email-inference"),
            new SecurityDataExport.Omission("operationalCountersCorrelationAndChallengeState","excluded"),
            new SecurityDataExport.Omission("expiredOrPurgedHistory","not-reconstructed"),
            new SecurityDataExport.Omission("otherDomainsAndCopies","separate-or-pending"),
            new SecurityDataExport.Omission("changesDuringPagination","not-a-snapshot"));
    private final NamedParameterJdbcTemplate jdbc;
    public SecurityDataExportService(DataSource source){var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);}
    public SecurityDataExport methods(CustomerPrincipal principal){
        var rows=jdbc.query(METHODS,parameters(principal,0,50),(rs,n)->new Slot<>(rs.getObject("id")==null?null:
                new SecurityDataExport.Totp(rs.getBoolean("enabled"),instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"enrollment_expires_at"))));
        if(rows.isEmpty())throw unauthorized();return new SecurityDataExport(1,"security-method-metadata",false,Instant.now(),rows.getFirst().item(),OMISSIONS);
    }
    public SecurityDataExportPage<SecurityDataExport.Event> events(CustomerPrincipal principal,long cursor,int size){
        return page(EVENTS,"security-audit-page",principal,cursor,size,"id",rs->new SecurityDataExport.Event(rs.getLong("id"),rs.getString("event_type"),rs.getString("outcome"),instant(rs,"occurred_at"),rs.getString("subject_proof")),SecurityDataExport.Event::eventId);
    }
    public SecurityDataExportPage<SecurityDataExport.Passkey> passkeys(CustomerPrincipal principal,long cursor,int size){
        return page(PASSKEYS,"security-passkey-metadata-page",principal,cursor,size,"export_id",rs->new SecurityDataExport.Passkey(rs.getLong("export_id"),instant(rs,"created"),instant(rs,"last_used"),rs.getBoolean("uv_initialized"),rs.getBoolean("backup_eligible"),rs.getBoolean("backup_state")),SecurityDataExport.Passkey::exportId);
    }
    public SecurityDataExportPage<SecurityDataExport.Recovery> recovery(CustomerPrincipal principal,long cursor,int size){
        return page(RECOVERY,"security-recovery-metadata-page",principal,cursor,size,"id",rs->new SecurityDataExport.Recovery(rs.getLong("id"),instant(rs,"created_at"),instant(rs,"used_at")),SecurityDataExport.Recovery::recordId);
    }
    private <T> SecurityDataExportPage<T> page(String sql,String scope,CustomerPrincipal principal,long cursor,int size,String id,Reader<T> reader,ToLongFunction<T> key){
        var parameters=parameters(principal,cursor,size);var window=new ListWindow(cursor,size);
        List<Slot<T>> rows=jdbc.query(sql,parameters,(rs,n)->new Slot<>(rs.getObject(id)==null?null:reader.read(rs)));
        if(rows.isEmpty())throw unauthorized();var page=window.map(rows.stream().map(Slot::item).filter(Objects::nonNull).toList(),key,Function.identity());
        return new SecurityDataExportPage<>(1,scope,false,Instant.now(),page.items(),page.hasMore(),page.nextCursor(),OMISSIONS);
    }
    private static Map<String,Object> parameters(CustomerPrincipal p,long cursor,int size){
        if(p==null||p.customerId()==null||p.customerId()<1||p.email()==null||p.email().isBlank())throw unauthorized();
        if(cursor<0||size<1||size>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"cursor must be nonnegative; size must be between 1 and 50");
        return Map.of("customerId",p.customerId(),"email",p.email(),"legacyActor",p.customerId().toString(),"webauthnUserId",CustomerWebAuthnUserEntityRepository.userId(p.customerId()).toBase64UrlString(),"ceiling",new ListWindow(cursor,size).ceiling(),"limit",size+1);
    }
    private record Slot<T>(T item){}
    @FunctionalInterface private interface Reader<T>{T read(ResultSet rs)throws SQLException;}
    private static Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name)==null?null:rs.getTimestamp(name).toInstant();}
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");}
}
