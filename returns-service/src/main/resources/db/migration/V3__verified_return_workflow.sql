ALTER TABLE return_requests ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE return_requests ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE return_requests ADD COLUMN order_verified_at TIMESTAMPTZ;
ALTER TABLE return_requests ADD CONSTRAINT chk_return_verification CHECK (
 (request_hash IS NULL AND order_verified_at IS NULL) OR (request_hash IS NOT NULL AND order_verified_at IS NOT NULL));
CREATE TABLE return_lines (
 id BIGSERIAL PRIMARY KEY, return_id BIGINT NOT NULL REFERENCES return_requests(id),
 order_item_id BIGINT NOT NULL, product_id BIGINT NOT NULL, product_name VARCHAR(255) NOT NULL,
 ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity>0),
 quantity INTEGER NOT NULL CHECK(quantity>0 AND quantity<=ordered_quantity),
 UNIQUE(return_id,order_item_id)
);
CREATE TABLE return_events (
 id BIGSERIAL PRIMARY KEY, return_id BIGINT NOT NULL REFERENCES return_requests(id),
 revision BIGINT NOT NULL, expected_version BIGINT, operation_id UUID,
 action VARCHAR(32) NOT NULL, from_status VARCHAR(20), to_status VARCHAR(20) NOT NULL,
 note VARCHAR(1000), actor_ref VARCHAR(320) NOT NULL, order_status VARCHAR(30), payment_state VARCHAR(30),
 created_at TIMESTAMPTZ NOT NULL,
 UNIQUE(return_id,revision), UNIQUE(return_id,operation_id)
);
CREATE INDEX idx_return_events_page ON return_events(return_id,id DESC);
-- Preserve history without attributing old decisions to an invented actor or time.
INSERT INTO return_events(return_id,revision,action,to_status,note,actor_ref,created_at)
 SELECT id,0,'LEGACY_SNAPSHOT',status,moderation_note,'unknown-legacy-actor',CURRENT_TIMESTAMP FROM return_requests;
