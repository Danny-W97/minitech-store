package org.demo.orderservice.privacy;

import org.springframework.jdbc.core.JdbcTemplate;

/** A43.36 must provide durable, order-version-bound proof that finance and all domain
 * workflows are reconciled and quiescent. No network call may run inside this callback.
 * No operator acknowledgement or runtime switch can stand in for that evidence. */
public interface CommerceFinanceGate {
    boolean cleared(JdbcTemplate jdbc,long orderId,long orderVersion);
}
