-- Participant-scoped live reads use the isolated operational store only.
CREATE INDEX idx_postmatch_directory_participant_orders
  ON postmatch.canonical_order_directory
    (event_stream, source_generation, participant_id, accepted_at, order_id);
