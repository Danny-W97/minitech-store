CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    customer_name VARCHAR(100) NOT NULL,
    customer_email VARCHAR(255) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    message VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    notification_status VARCHAR(20) NOT NULL,
    reply_message VARCHAR(4000),
    reply_notification_status VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    replied_at TIMESTAMPTZ
);

CREATE INDEX idx_chat_messages_status_created
    ON chat_messages (status, created_at DESC);
CREATE INDEX idx_chat_messages_customer_email
    ON chat_messages (customer_email, created_at DESC);
