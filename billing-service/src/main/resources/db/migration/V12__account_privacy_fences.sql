-- A43.33: durable ID-only fence. No email, email hash or automatic TTL.
CREATE TABLE account_privacy_fences (
 customer_id BIGINT PRIMARY KEY CHECK(customer_id>0),
 request_id UUID NOT NULL UNIQUE,
 state VARCHAR(12) NOT NULL CHECK(state='BLOCKED')
);
