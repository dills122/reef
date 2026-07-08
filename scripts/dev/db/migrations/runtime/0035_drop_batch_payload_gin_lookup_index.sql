-- Command lookup now uses runtime.canonical_command_outcomes by primary key.
-- Keep canonical_venue_event_batches.payload_json for replay/checksum evidence,
-- but remove the large JSON containment index from the hot materializer write path.

DROP INDEX IF EXISTS runtime.idx_canonical_venue_event_batches_payload_json_gin;
