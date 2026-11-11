package org.demo.customerservice.auth;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Component
public final class RestoreEpochStore {
    private final JdbcTemplate jdbc;private final boolean postgres;
    public RestoreEpochStore(DataSource source){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);try(var connection=source.getConnection()){postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");}catch(Exception failure){throw new IllegalStateException("Restore epoch storage unavailable");}}
    @org.springframework.beans.factory.annotation.Autowired private org.demo.platformsecurity.privacy.JournalReceiptVerifier journal;
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void bindJournal(){if(!postgres||journal==null||!journal.configured())return;
        jdbc.update("UPDATE restore_security_state SET ledger_id=coalesce(ledger_id,?),verification_key_hash=coalesce(verification_key_hash,?) WHERE id=1",journal.ledgerId(),journal.publicKeyFingerprint());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT ledger_id=? AND verification_key_hash=? FROM restore_security_state WHERE id=1",Boolean.class,journal.ledgerId(),journal.publicKeyFingerprint())))throw new IllegalStateException("Journal identity changed; explicit migration required");
    }
    public boolean permitsLogin(){return !postgres||Boolean.TRUE.equals(jdbc.queryForObject("SELECT phase='NORMAL' FROM restore_security_state WHERE id=1",Boolean.class));}
    public String current(){return postgres?jdbc.queryForObject("SELECT auth_epoch FROM restore_security_state WHERE id=1",String.class):"legacy";}
}
