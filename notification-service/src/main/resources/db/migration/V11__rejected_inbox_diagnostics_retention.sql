ALTER TABLE event_inbox ADD COLUMN diagnostics_compacted_at TIMESTAMPTZ;
ALTER TABLE event_inbox ADD CONSTRAINT ck_event_inbox_compacted_diagnostics CHECK (
    diagnostics_compacted_at IS NULL
    OR (payload IS NULL AND error IS NULL AND status = 'REJECTED' AND related_id IS NULL)
);
CREATE INDEX idx_rejected_inbox_diagnostics_retention ON event_inbox (received_at, event_id)
    WHERE status = 'REJECTED' AND related_id IS NULL AND diagnostics_compacted_at IS NULL
        AND (payload IS NOT NULL OR error IS NOT NULL);
