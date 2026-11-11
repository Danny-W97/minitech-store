package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.FinancePolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class VerifiedCommerceFinanceGate implements CommerceFinanceGate {
    public boolean cleared(JdbcTemplate jdbc,long order,long version){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_privacy_jobs j JOIN finance_privacy_consents c ON c.customer_id=j.customer_id AND c.request_id=j.request_id WHERE j.order_id=? AND j.order_version=? AND j.state='READY' AND c.policy_hash=?)",Boolean.class,order,version,FinancePolicy.hash()));}
}
