package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Always enforces installed PostgreSQL markers, regardless of activation switches. */
public final class PrivacySql {
    private final JdbcTemplate jdbc;private final boolean postgres;
    public PrivacySql(DataSource source){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);try(var connection=source.getConnection()){postgres=connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");}catch(java.sql.SQLException error){throw new IllegalStateException("Privacy database unavailable",error);}}
    public boolean orderSealed(long order){return postgres&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM commerce_privacy_locks WHERE order_id=?)",Boolean.class,order));}
    public boolean accountFenced(long customer){return postgres&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_privacy_fences WHERE customer_id=?)",Boolean.class,customer));}
    public boolean postgres(){return postgres;}
    public JdbcTemplate jdbc(){return jdbc;}
}
