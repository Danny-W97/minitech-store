package org.demo.platformsecurity.privacy;

import org.demo.platformsecurity.Access;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;

/** Transaction-local admission. No remote calls and no transaction is opened around HTTP. */
public final class PrivacyDataSources {
    private static final java.util.regex.Pattern CTE_MUTATION=java.util.regex.Pattern.compile("\\b(insert|update|delete|merge|call|copy|create|alter|drop|truncate|grant|revoke|do|into|refresh|lock)\\b");
    private PrivacyDataSources() {}
    public static DataSource wrap(DataSource source) {
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class,AutoCloseable.class},(proxy,method,args)->{
            if(method.getName().equals("close")&&!(source instanceof AutoCloseable))return null;
            Object result=invoke(source,method,args);
            return method.getName().equals("getConnection")&&result instanceof Connection connection?connection(connection):result;
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    private static Connection connection(Connection target)throws SQLException {
        boolean postgres=target.getMetaData().getDatabaseProductName().equals("PostgreSQL");
        final Long[] admitted={null};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("commit")||name.equals("rollback")||name.equals("close")||name.equals("setAutoCommit"))admitted[0]=null;
            Object result=invoke(target,method,args);
            if(!postgres||!(result instanceof Statement statement))return result;
            String prepared=args!=null&&args.length>0&&args[0] instanceof String sql?sql:null;
            Class<?> type=statement instanceof CallableStatement?CallableStatement.class:statement instanceof PreparedStatement?PreparedStatement.class:Statement.class;
            return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
                String sql=prepared!=null?prepared:a!=null&&a.length>0&&a[0] instanceof String text?text:"";
                if(m.getName().startsWith("execute")&&write(sql)) {
                    var actor=Access.actor();Long customer=actor.customerId();
                    if("CUSTOMER".equals(actor.role())&&customer!=null&&customer>0&&!customer.equals(admitted[0])) {
                        if(target.getAutoCommit())throw new SQLException("Customer mutation requires a transaction","25000");
                        try(var lock=target.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {lock.setQueryTimeout(2);lock.setLong(1,-customer);lock.execute();}
                        try(var check=target.prepareStatement("SELECT 1 FROM account_privacy_fences WHERE customer_id=? AND state='BLOCKED'")) {
                            check.setQueryTimeout(2);check.setLong(1,customer);
                            try(var rows=check.executeQuery()){if(rows.next())throw new SQLException("Account privacy fence active","P0001");}
                        }
                        admitted[0]=customer;
                    }
                }
                return invoke(statement,m,a);
            });
        });
    }
    private static boolean write(String sql) {
        // Read-only CTE projections are used by the account exports without a transaction.
        // Any mutation/locking keyword anywhere in a CTE remains guarded (including comments
        // or literals: false positives are safer than admitting an uncertain statement).
        String value=sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
        if(value.startsWith("with")&&value.length()>4&&Character.isWhitespace(value.charAt(4)))
            return CTE_MUTATION.matcher(value).find();
        return !(value.startsWith("select ")||value.startsWith("show ")||value.startsWith("explain ")||value.startsWith("set "));
    }
}
