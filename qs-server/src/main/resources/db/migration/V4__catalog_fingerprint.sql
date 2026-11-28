-- The fields each version uses, with their types when saved (technical design, schema drift).
ALTER TABLE query_version ADD COLUMN catalog_refs jsonb;
