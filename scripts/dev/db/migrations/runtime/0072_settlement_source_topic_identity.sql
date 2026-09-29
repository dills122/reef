-- Default-off settlement source proof. Bind a fresh source generation to the
-- exact Kafka command and venue-event topics before either accepts records.
CREATE TABLE runtime.settlement_source_topic_identity (
  source_generation UUID NOT NULL,
  event_stream TEXT NOT NULL CHECK (event_stream <> ''),
  command_topic TEXT NOT NULL CHECK (command_topic <> ''),
  command_topic_id TEXT NOT NULL CHECK (command_topic_id <> ''),
  venue_event_topic TEXT NOT NULL CHECK (venue_event_topic <> ''),
  venue_event_topic_id TEXT NOT NULL CHECK (venue_event_topic_id <> ''),
  bound_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (source_generation, event_stream)
);

CREATE FUNCTION runtime.reject_settlement_source_topic_identity_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'settlement source topic identity is immutable';
END;
$$;

CREATE TRIGGER settlement_source_topic_identity_immutable
BEFORE UPDATE OR DELETE ON runtime.settlement_source_topic_identity
FOR EACH ROW EXECUTE FUNCTION runtime.reject_settlement_source_topic_identity_change();
