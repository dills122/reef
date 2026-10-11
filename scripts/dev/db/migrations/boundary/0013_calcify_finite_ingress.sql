-- Opt-in finite ingress. Roles are provisioned by operator, never this migration.
-- Function owner must be trusted; runtime receives only explicitly selected EXECUTE.
CREATE TABLE boundary.calcify_finite_bindings (
  digest TEXT PRIMARY KEY CHECK (digest ~ '^[0-9a-f]{64}$'),
  opening JSONB NOT NULL CHECK (octet_length(opening::text) <= 16384),
  canonical BYTEA NOT NULL CHECK (octet_length(canonical) <= 16384),
  CHECK (encode(sha256(canonical), 'hex') = digest)
);
CREATE TABLE boundary.calcify_finite_runs (
  digest TEXT PRIMARY KEY REFERENCES boundary.calcify_finite_bindings(digest),
  owner_instance TEXT NOT NULL CHECK (octet_length(owner_instance) BETWEEN 1 AND 128),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  activation_epoch BIGINT NOT NULL DEFAULT 0 CHECK (activation_epoch >= 0),
  admission_state TEXT NOT NULL DEFAULT 'STOPPED' CHECK (admission_state IN ('STOPPED','ACTIVE')),
  attempts BIGINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 32),
  publications BIGINT NOT NULL DEFAULT 0 CHECK (publications BETWEEN 0 AND 16),
  payload_bytes BIGINT NOT NULL DEFAULT 0 CHECK (payload_bytes BETWEEN 0 AND 131072),
  attempt_bytes BIGINT NOT NULL DEFAULT 0 CHECK (attempt_bytes BETWEEN 0 AND 131072),
  engine_receipt TEXT, capture_receipt TEXT, receipt_epoch BIGINT, receipt_cuts JSONB
);
CREATE TABLE boundary.calcify_finite_attempts (
  digest TEXT NOT NULL REFERENCES boundary.calcify_finite_bindings(digest),
  ordinal BIGINT NOT NULL CHECK (ordinal BETWEEN 1 AND 32),
  receipt JSONB NOT NULL CHECK (octet_length(receipt::text) <= 4096),
  PRIMARY KEY (digest, ordinal)
);
CREATE TABLE boundary.calcify_finite_publications (
  digest TEXT NOT NULL REFERENCES boundary.calcify_finite_bindings(digest),
  ordinal BIGINT NOT NULL CHECK (ordinal BETWEEN 1 AND 16),
  command_id TEXT NOT NULL CHECK (octet_length(command_id) BETWEEN 1 AND 128),
  owner_epoch BIGINT NOT NULL CHECK (owner_epoch > 0),
  identity JSONB NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('RESERVED','PUBLISHING','UNKNOWN','PUBLISHED','DEFINITELY_NOT_SENT')),
  offset_value BIGINT, stream_sequence BIGINT,
  PRIMARY KEY (digest, ordinal), UNIQUE (digest, command_id),
  CHECK ((state = 'PUBLISHED' AND offset_value IS NOT NULL AND stream_sequence IS NOT NULL AND offset_value BETWEEN 0 AND 281474976710654 AND stream_sequence = offset_value + 1)
      OR (state <> 'PUBLISHED' AND offset_value IS NULL AND stream_sequence IS NULL))
);

CREATE FUNCTION boundary.calcify_finite_register(d TEXT, o JSONB, c BYTEA, instance TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
BEGIN
  IF o->>'schema' <> 'calcify-finite-binding-v1' OR o->>'openingMode' <> 'GENESIS'
     OR (o->>'partition')::INT <> 0 OR (o->>'partitionCount')::INT <> 1 THEN
    RAISE EXCEPTION 'invalid finite opening';
  END IF;
  INSERT INTO boundary.calcify_finite_bindings VALUES (d,o,c);
  INSERT INTO boundary.calcify_finite_runs(digest,owner_instance,owner_epoch) VALUES (d,instance,1);
  RETURN TRUE;
END $$;

CREATE FUNCTION boundary.calcify_finite_claim_owner(d TEXT, epoch BIGINT, instance TEXT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE next_epoch BIGINT;
BEGIN
  UPDATE boundary.calcify_finite_runs SET owner_epoch=owner_epoch+1, owner_instance=instance,
    engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL
    WHERE digest=d AND owner_epoch=epoch AND admission_state='STOPPED' RETURNING owner_epoch INTO next_epoch;
  IF next_epoch IS NULL THEN RAISE EXCEPTION 'finite owner claim refused'; END IF;
  RETURN next_epoch;
END $$;

CREATE FUNCTION boundary.calcify_finite_begin_attempt(d TEXT, epoch BIGINT, request JSONB) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE r boundary.calcify_finite_runs; o JSONB; existing boundary.calcify_finite_publications;
  attempt JSONB; decision TEXT; prior_command TEXT; seq BIGINT; n BIGINT; row_bytes BIGINT; payload JSONB; collision BIGINT;
BEGIN
  SELECT * INTO STRICT r FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  SELECT opening INTO STRICT o FROM boundary.calcify_finite_bindings WHERE digest=d;
  IF jsonb_typeof(request) IS DISTINCT FROM 'object' OR NOT request ?& ARRAY['scope','clientId','idempotencyKey','participantId',
    'commandId','commandType','route','subject','key','payload','payloadHash','keyHash','subjectHash','routeHash','refusal','partition','payloadBytes']
    OR (SELECT count(*) FROM jsonb_object_keys(request))<>17 OR EXISTS(SELECT 1 FROM jsonb_each(request) v
      WHERE (v.key IN ('partition','payloadBytes') AND jsonb_typeof(v.value)<>'number') OR
        (v.key NOT IN ('partition','payloadBytes') AND jsonb_typeof(v.value)<>'string')) THEN RAISE EXCEPTION 'invalid finite request fields'; END IF;
  IF octet_length(request->>'clientId') NOT BETWEEN 1 AND 128 OR octet_length(request->>'idempotencyKey') NOT BETWEEN 1 AND 128
    OR octet_length(request->>'refusal')>128 OR request->>'scope' IS DISTINCT FROM (request->>'clientId')||'|'||(request->>'route')
    OR request->>'key' IS DISTINCT FROM (request->>'scope')||'|'||(request->>'idempotencyKey')
    OR request->>'route' IS DISTINCT FROM (CASE request->>'commandType' WHEN 'SubmitOrder' THEN '/api/v1/orders/submit'
      WHEN 'ModifyOrder' THEN '/api/v1/orders/modify' WHEN 'CancelOrder' THEN '/api/v1/orders/cancel' ELSE NULL END)
    OR request->>'subject' IS DISTINCT FROM (o->>'commandSubjectPrefix')||'.p00.'||(o->>'venueSessionId')||'.'||(o->>'instrumentId')||'.'||(request->>'commandType')
    THEN RAISE EXCEPTION 'invalid finite routing'; END IF;
  IF r.owner_epoch<>epoch THEN RETURN jsonb_build_object('disposition','STOPPED','commandId',request->>'commandId','streamSequence',NULL); END IF;
  -- Resolve same idempotency identity or command before considering a new publication.
  SELECT count(*) INTO collision FROM boundary.calcify_finite_publications WHERE digest=d AND
    (command_id=request->>'commandId' OR (identity->>'scope'=request->>'scope' AND identity->>'idempotencyKey'=request->>'idempotencyKey'));
  SELECT * INTO existing FROM boundary.calcify_finite_publications WHERE digest=d AND
    (command_id=request->>'commandId' OR (identity->>'scope'=request->>'scope' AND identity->>'idempotencyKey'=request->>'idempotencyKey')) ORDER BY ordinal LIMIT 1;
  IF FOUND THEN
    prior_command:=existing.command_id;
    IF collision>1 OR existing.identity->>'payloadHash' <> request->>'payloadHash' OR existing.identity->>'scope' <> request->>'scope'
       OR existing.identity->>'idempotencyKey' <> request->>'idempotencyKey' THEN decision:='CONFLICT';
    ELSIF existing.state='PUBLISHED' THEN decision:='PUBLISHED';seq:=existing.stream_sequence;
    ELSE decision:='UNRESOLVED'; END IF;
  ELSE prior_command:=request->>'commandId'; END IF;
  IF r.admission_state<>'ACTIVE' THEN
    RETURN jsonb_build_object('disposition',CASE WHEN decision='PUBLISHED' THEN decision ELSE 'STOPPED' END,
      'commandId',prior_command,'streamSequence',CASE WHEN decision='PUBLISHED' THEN seq::TEXT ELSE NULL END);
  END IF;
  IF r.attempts >= (o->'budget'->>'attempts')::BIGINT OR r.publications >= (o->'budget'->>'publications')::BIGINT THEN
    UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED',engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
    RETURN jsonb_build_object('disposition','STOPPED','commandId',prior_command,'streamSequence',NULL);
  END IF;
  payload:=(request->>'payload')::JSONB;n:=octet_length(convert_to(request->>'payload','UTF8'));
  IF n NOT BETWEEN 1 AND (o->'budget'->>'commandBytes')::BIGINT OR
    request->>'payloadHash' <> encode(sha256(convert_to(request->>'payload','UTF8')),'hex') OR
    request->>'keyHash' <> encode(sha256(convert_to(request->>'key','UTF8')),'hex') OR
    request->>'subjectHash' <> encode(sha256(convert_to(request->>'subject','UTF8')),'hex') OR
    request->>'routeHash' <> encode(sha256(convert_to(request->>'route','UTF8')),'hex') OR
    (request->>'partition')::INT <> 0 OR (request->>'payloadBytes')::BIGINT <> n OR
    octet_length(request->>'commandId') NOT BETWEEN 1 AND 128 OR
    payload->>'commandId' IS DISTINCT FROM request->>'commandId' OR payload->>'runId' IS DISTINCT FROM o->>'runId' OR
    payload->>'venueSessionId' IS DISTINCT FROM o->>'venueSessionId' OR payload->>'instrumentId' IS DISTINCT FROM o->>'instrumentId' OR
    payload->>'participantId' IS DISTINCT FROM request->>'participantId' THEN
      RAISE EXCEPTION 'finite request identity mismatch';
  END IF;
  IF decision IS NULL THEN
    IF NOT EXISTS (SELECT 1 FROM jsonb_array_elements(o->'parties') p
      WHERE p->>'participantId'=payload->>'participantId' AND p->>'accountId'=payload->>'accountId') THEN RAISE EXCEPTION 'finite party scope denied'; END IF;
    decision:=CASE WHEN request->>'refusal'<>'' THEN 'REFUSED' ELSE 'RESERVED' END;
  END IF;
  attempt:=jsonb_build_object('commandId',request->>'commandId','payloadHash',request->>'payloadHash',
    'routeHash',request->>'routeHash','ownerEpoch',epoch::TEXT,'disposition',decision,'reason',request->>'refusal');
  row_bytes:=octet_length(attempt::TEXT);
  IF row_bytes>(o->'budget'->>'attemptBytes')::BIGINT OR r.attempt_bytes+row_bytes>
      (o->'budget'->>'attempts')::BIGINT*(o->'budget'->>'attemptBytes')::BIGINT THEN
    UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED',engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
    RETURN jsonb_build_object('disposition','STOPPED','commandId',prior_command,'streamSequence',NULL);
  END IF;
  -- Global canonical intake indexes may already own this identity (including another finite binding).
  -- Reserve once without an exception rollback: eligible conflict still consumes its attempt.
  IF decision='RESERVED' THEN
    INSERT INTO boundary.stream_command_intake(scope,client_id,idempotency_key,participant_id,payload_hash,command_id,route,subject,
      stream_name,partition,stream_sequence,published) VALUES(request->>'scope',request->>'clientId',request->>'idempotencyKey',
      request->>'participantId',request->>'payloadHash',request->>'commandId',request->>'route',request->>'subject',
      o->'topics'->0->>'name',0,0,FALSE) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS collision=ROW_COUNT;
    IF collision=0 THEN decision:='CONFLICT'; END IF;
  END IF;
  attempt:=jsonb_set(attempt,'{disposition}',to_jsonb(decision));
  INSERT INTO boundary.calcify_finite_attempts VALUES(d,r.attempts+1,attempt);
  UPDATE boundary.calcify_finite_runs SET attempts=attempts+1,attempt_bytes=attempt_bytes+row_bytes,engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
  IF decision='RESERVED' THEN
    INSERT INTO boundary.calcify_finite_publications VALUES(d,r.publications+1,request->>'commandId',epoch,request,'RESERVED',NULL,NULL);
    UPDATE boundary.calcify_finite_runs SET publications=publications+1,payload_bytes=payload_bytes+n WHERE digest=d;
  END IF;
  UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED' WHERE digest=d AND
    (attempts >= (o->'budget'->>'attempts')::BIGINT OR publications >= (o->'budget'->>'publications')::BIGINT);
  RETURN jsonb_build_object('disposition',decision,'commandId',prior_command,'streamSequence',seq::TEXT);
END $$;

CREATE FUNCTION boundary.calcify_finite_authorize_send(d TEXT, epoch BIGINT, command TEXT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE current_epoch BIGINT; count_changed BIGINT;
BEGIN
  SELECT owner_epoch INTO STRICT current_epoch FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  IF current_epoch<>epoch THEN RETURN FALSE; END IF;
  UPDATE boundary.calcify_finite_runs SET engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
  UPDATE boundary.calcify_finite_publications SET state='PUBLISHING' WHERE digest=d AND command_id=command AND owner_epoch=epoch AND state='RESERVED';
  GET DIAGNOSTICS count_changed=ROW_COUNT;RETURN count_changed=1;
END $$;

CREATE FUNCTION boundary.calcify_finite_ack(d TEXT, command TEXT, epoch BIGINT, off BIGINT, seq BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE p boundary.calcify_finite_publications;
BEGIN
  PERFORM 1 FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  SELECT * INTO STRICT p FROM boundary.calcify_finite_publications WHERE digest=d AND command_id=command;
  IF epoch IS NULL OR off IS NULL OR seq IS NULL OR p.owner_epoch<>epoch OR off NOT BETWEEN 0 AND 281474976710654 OR seq<>off+1 THEN RETURN FALSE; END IF;
  IF p.state='PUBLISHED' THEN
    IF p.offset_value=off AND p.stream_sequence=seq THEN RETURN TRUE; END IF;
    UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED',engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;RETURN FALSE;
  END IF;
  IF p.state NOT IN ('PUBLISHING','UNKNOWN') THEN RETURN FALSE; END IF;
  UPDATE boundary.calcify_finite_runs SET engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
  UPDATE boundary.calcify_finite_publications SET state='PUBLISHED',offset_value=off,stream_sequence=seq WHERE digest=d AND command_id=command;
  UPDATE boundary.stream_command_intake SET published=TRUE,stream_sequence=seq,published_at=COALESCE(published_at,now())
    WHERE command_id=command AND payload_hash=p.identity->>'payloadHash';
  IF NOT FOUND THEN RAISE EXCEPTION 'finite intake ACK marker missing'; END IF;
  RETURN TRUE;
END $$;

CREATE FUNCTION boundary.calcify_finite_unknown(d TEXT, command TEXT, epoch BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE count_changed BIGINT;
BEGIN
  PERFORM 1 FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  UPDATE boundary.calcify_finite_publications SET state=CASE WHEN state='PUBLISHED' THEN state ELSE 'UNKNOWN' END
    WHERE digest=d AND command_id=command AND owner_epoch=epoch AND state IN ('PUBLISHING','UNKNOWN','PUBLISHED');
  GET DIAGNOSTICS count_changed=ROW_COUNT;
  UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED',engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d;
  RETURN count_changed=1;
END $$;

CREATE FUNCTION boundary.calcify_finite_stop(d TEXT, epoch BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE n BIGINT;
BEGIN
  UPDATE boundary.calcify_finite_runs SET admission_state='STOPPED',engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL WHERE digest=d AND owner_epoch=epoch;
  GET DIAGNOSTICS n=ROW_COUNT;RETURN n=1;
END $$;

CREATE FUNCTION boundary.calcify_finite_snapshot(d TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE r boundary.calcify_finite_runs; o JSONB; j JSONB; unresolved BIGINT;
BEGIN
  SELECT * INTO STRICT r FROM boundary.calcify_finite_runs WHERE digest=d FOR SHARE;
  SELECT opening INTO STRICT o FROM boundary.calcify_finite_bindings WHERE digest=d;
  SELECT COALESCE(jsonb_agg(jsonb_build_object('publicationOrdinal',ordinal::TEXT,'commandId',command_id,
    'commandType',identity->>'commandType','payloadHash',identity->>'payloadHash','keyHash',identity->>'keyHash',
    'subjectHash',identity->>'subjectHash','routeHash',identity->>'routeHash','ownerEpoch',owner_epoch::TEXT,'state',state,
    'partition',0,'offset',offset_value::TEXT,'streamSequence',stream_sequence::TEXT,'payloadBytes',identity->>'payloadBytes') ORDER BY ordinal),'[]'::JSONB),
    count(*) FILTER(WHERE state IN ('RESERVED','PUBLISHING','UNKNOWN')) INTO j,unresolved
    FROM boundary.calcify_finite_publications WHERE digest=d;
  RETURN jsonb_build_object('opening',o,'run',jsonb_build_object('ownerInstanceId',r.owner_instance,'ownerEpoch',r.owner_epoch::TEXT,
    'activationEpoch',r.activation_epoch::TEXT,'admissionState',r.admission_state,'recordedAttempts',r.attempts::TEXT,
    'possiblePublications',r.publications::TEXT,'commandPayloadBytes',r.payload_bytes::TEXT,'attemptRecordBytes',r.attempt_bytes::TEXT,
    'unresolvedPublications',unresolved::TEXT),'journal',j);
END $$;

CREATE FUNCTION boundary.calcify_finite_receipt(d TEXT, command TEXT) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE result JSONB;
BEGIN
  SELECT jsonb_build_object('commandId',p.command_id,'streamSequence',p.stream_sequence::TEXT,
    'acceptedAt',to_char(i.published_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')) INTO STRICT result
    FROM boundary.calcify_finite_publications p JOIN boundary.stream_command_intake i ON i.command_id=p.command_id
    WHERE p.digest=d AND p.command_id=command AND p.state='PUBLISHED' AND i.published AND i.stream_sequence=p.stream_sequence
      AND i.payload_hash=p.identity->>'payloadHash' AND i.published_at IS NOT NULL;
  RETURN result;
END $$;

CREATE FUNCTION boundary.calcify_finite_stages(d TEXT, epoch BIGINT, engine TEXT, capture TEXT, cuts JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE r boundary.calcify_finite_runs; j JSONB;
BEGIN
  SELECT * INTO STRICT r FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  IF epoch IS NULL OR epoch<=0 OR r.owner_epoch<>epoch OR r.admission_state<>'STOPPED' OR engine IS NULL OR capture IS NULL OR
      engine !~ '^[0-9a-f]{64}$' OR capture !~ '^[0-9a-f]{64}$' OR cuts IS NULL OR octet_length(cuts::TEXT)>8192 OR
      EXISTS(SELECT 1 FROM boundary.calcify_finite_publications WHERE digest=d AND state IN ('RESERVED','PUBLISHING','UNKNOWN')) THEN RETURN FALSE; END IF;
  IF jsonb_typeof(cuts)<>'object' OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(cuts) k) IS DISTINCT FROM
      ARRAY['activationEpoch','attemptRecordBytes','brokerCuts','commandPayloadBytes','journalHash','possiblePublications','recordedAttempts']::TEXT[] THEN RETURN FALSE; END IF;
  IF cuts->>'activationEpoch' IS DISTINCT FROM r.activation_epoch::TEXT OR cuts->>'recordedAttempts' IS DISTINCT FROM r.attempts::TEXT
      OR cuts->>'possiblePublications' IS DISTINCT FROM r.publications::TEXT OR cuts->>'commandPayloadBytes' IS DISTINCT FROM r.payload_bytes::TEXT
      OR cuts->>'attemptRecordBytes' IS DISTINCT FROM r.attempt_bytes::TEXT OR jsonb_typeof(cuts->'brokerCuts')<>'object' THEN RETURN FALSE; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_each(cuts) v WHERE v.key<>'brokerCuts' AND jsonb_typeof(v.value)<>'string') THEN RETURN FALSE; END IF;
  j:=boundary.calcify_finite_snapshot(d)->'journal';
  IF cuts->>'journalHash' IS DISTINCT FROM encode(sha256(convert_to(j::TEXT,'UTF8')),'hex') THEN RETURN FALSE; END IF;
  UPDATE boundary.calcify_finite_runs SET engine_receipt=engine,capture_receipt=capture,receipt_epoch=epoch,receipt_cuts=cuts WHERE digest=d;
  RETURN TRUE;
END $$;
CREATE FUNCTION boundary.calcify_finite_release(d TEXT, epoch BIGINT, engine TEXT, capture TEXT, cuts JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, boundary AS $$
DECLARE n BIGINT;
BEGIN
  PERFORM 1 FROM boundary.calcify_finite_runs WHERE digest=d FOR UPDATE;
  UPDATE boundary.calcify_finite_runs SET admission_state='ACTIVE',activation_epoch=activation_epoch+1,
      engine_receipt=NULL,capture_receipt=NULL,receipt_epoch=NULL,receipt_cuts=NULL
    WHERE digest=d AND owner_epoch=epoch AND admission_state='STOPPED' AND receipt_epoch=epoch
      AND engine_receipt=engine AND capture_receipt=capture AND receipt_cuts=cuts
      AND cuts->>'activationEpoch'=activation_epoch::TEXT AND cuts->>'recordedAttempts'=attempts::TEXT
      AND cuts->>'possiblePublications'=publications::TEXT AND cuts->>'commandPayloadBytes'=payload_bytes::TEXT
      AND cuts->>'attemptRecordBytes'=attempt_bytes::TEXT
      AND cuts->>'journalHash'=encode(sha256(convert_to((boundary.calcify_finite_snapshot(d)->'journal')::TEXT,'UTF8')),'hex')
      AND NOT EXISTS(SELECT 1 FROM boundary.calcify_finite_publications WHERE digest=d AND state IN ('RESERVED','PUBLISHING','UNKNOWN'))
      AND attempts<(SELECT (opening->'budget'->>'attempts')::BIGINT FROM boundary.calcify_finite_bindings WHERE digest=d)
      AND publications<(SELECT (opening->'budget'->>'publications')::BIGINT FROM boundary.calcify_finite_bindings WHERE digest=d);
  GET DIAGNOSTICS n=ROW_COUNT;RETURN n=1;
END $$;

REVOKE ALL ON boundary.calcify_finite_bindings,boundary.calcify_finite_runs,boundary.calcify_finite_attempts,boundary.calcify_finite_publications FROM PUBLIC;
REVOKE ALL ON FUNCTION boundary.calcify_finite_register(TEXT,JSONB,BYTEA,TEXT),boundary.calcify_finite_claim_owner(TEXT,BIGINT,TEXT),
 boundary.calcify_finite_begin_attempt(TEXT,BIGINT,JSONB),boundary.calcify_finite_authorize_send(TEXT,BIGINT,TEXT),
 boundary.calcify_finite_ack(TEXT,TEXT,BIGINT,BIGINT,BIGINT),boundary.calcify_finite_unknown(TEXT,TEXT,BIGINT),
 boundary.calcify_finite_stop(TEXT,BIGINT),boundary.calcify_finite_snapshot(TEXT),boundary.calcify_finite_receipt(TEXT,TEXT),
 boundary.calcify_finite_stages(TEXT,BIGINT,TEXT,TEXT,JSONB),boundary.calcify_finite_release(TEXT,BIGINT,TEXT,TEXT,JSONB) FROM PUBLIC;
