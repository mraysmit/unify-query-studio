-- Which saved query and version a run came from; null for SQL run directly from the designer.
ALTER TABLE audit_event ADD COLUMN query_id uuid;
ALTER TABLE audit_event ADD COLUMN query_version integer;
