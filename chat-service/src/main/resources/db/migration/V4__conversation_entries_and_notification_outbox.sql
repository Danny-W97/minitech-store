CREATE TABLE chat_entries (
 id BIGSERIAL PRIMARY KEY, conversation_id BIGINT NOT NULL REFERENCES chat_messages(id),
 author VARCHAR(10) NOT NULL CHECK(author IN ('CUSTOMER','SUPPORT')),
 initial_message BOOLEAN NOT NULL DEFAULT FALSE,
 request_key VARCHAR(100) NOT NULL, message VARCHAR(4000) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL,
 UNIQUE(conversation_id,author,request_key)
);
CREATE INDEX idx_chat_entries_window ON chat_entries(conversation_id,id DESC);
CREATE TABLE chat_notification_outbox (
 entry_id BIGINT PRIMARY KEY REFERENCES chat_entries(id),
 event_type VARCHAR(30) NOT NULL, source_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','RETRY','ACCEPTED','DEAD_LETTER')),
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ,
 processing_token VARCHAR(36), processing_until TIMESTAMPTZ,
 notification_id BIGINT, delivery_status VARCHAR(20), last_error VARCHAR(60),
 created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_chat_notification_due ON chat_notification_outbox(next_attempt_at,entry_id)
 WHERE status IN ('PENDING','RETRY');
INSERT INTO chat_entries(conversation_id,author,initial_message,request_key,message,created_at)
 SELECT id,'CUSTOMER',true,'initial',message,created_at FROM chat_messages ORDER BY id;
INSERT INTO chat_entries(conversation_id,author,request_key,message,created_at)
 SELECT id,'SUPPORT','legacy-reply',reply_message,COALESCE(replied_at,updated_at)
 FROM chat_messages WHERE reply_message IS NOT NULL ORDER BY id;
INSERT INTO chat_notification_outbox(entry_id,event_type,source_id,status,next_attempt_at,delivery_status,created_at)
 SELECT e.id,CASE WHEN e.initial_message THEN 'CHAT_MESSAGE_RECEIVED' ELSE 'CHAT_REPLY_SENT' END,m.id,
 CASE WHEN (CASE WHEN e.initial_message THEN m.notification_status ELSE m.reply_notification_status END)='SENT' THEN 'ACCEPTED' ELSE 'RETRY' END,
 CASE WHEN (CASE WHEN e.initial_message THEN m.notification_status ELSE m.reply_notification_status END)='SENT' THEN NULL ELSE now() END,
 CASE WHEN (CASE WHEN e.initial_message THEN m.notification_status ELSE m.reply_notification_status END)='SENT' THEN 'SENT' ELSE NULL END,e.created_at
 FROM chat_entries e JOIN chat_messages m ON m.id=e.conversation_id;
ALTER TABLE chat_messages ADD COLUMN last_entry_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_messages ADD COLUMN reply_entry_id BIGINT;
UPDATE chat_messages m SET last_entry_id=(SELECT max(id) FROM chat_entries WHERE conversation_id=m.id),
 reply_entry_id=(SELECT max(id) FROM chat_entries WHERE conversation_id=m.id AND author='SUPPORT');
CREATE FUNCTION chat_entries_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Chat entries are immutable'; END $$;
CREATE TRIGGER chat_entries_immutable BEFORE UPDATE OR DELETE ON chat_entries
 FOR EACH ROW EXECUTE FUNCTION chat_entries_immutable();
