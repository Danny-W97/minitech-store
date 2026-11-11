-- A request contains no conversation text, email, account ID or capability hash.
-- Its proof remains the independently held conversation token.
CREATE TABLE chat_privacy_requests (
 conversation_id BIGINT PRIMARY KEY CHECK(conversation_id>0),
 request_id UUID NOT NULL UNIQUE,
 state VARCHAR(24) NOT NULL CHECK(state='MANUAL_REVIEW'),
 created_at TIMESTAMPTZ NOT NULL
);
