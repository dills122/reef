package com.reef.platform.infrastructure.persistence

import java.sql.Statement

/** Installs the migration-equivalent claim primitives used by local compat bootstrap mode. */
internal object ProjectionBatchClaimBootstrap {
    fun install(statement: Statement, names: PostgresRuntimeSqlNames) {
        statements(names).forEach(statement::execute)
    }

    private fun statements(names: PostgresRuntimeSqlNames): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ${names.projectionBatchClaims} (
          batch_identity TEXT PRIMARY KEY,
          identity_version SMALLINT NOT NULL CHECK (identity_version = 1),
          projection_name TEXT NOT NULL,
          event_stream TEXT NOT NULL,
          projection_stage TEXT NOT NULL CHECK (projection_stage IN ('full', 'command-status', 'timeline')),
          include_fills BOOLEAN NOT NULL,
          candidate_count INTEGER NOT NULL CHECK (candidate_count > 0),
          status TEXT NOT NULL CHECK (status IN ('in-progress', 'completed')),
          result_count BIGINT,
          created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
          retry_deadline_at TIMESTAMPTZ NOT NULL,
          retry_horizon_ms BIGINT NOT NULL CHECK (retry_horizon_ms > 0),
          completed_at TIMESTAMPTZ,
          retain_until TIMESTAMPTZ,
          CHECK (batch_identity ~ '^[0-9a-f]{64}${'$'}'),
          CHECK (
            (status = 'in-progress' AND result_count IS NULL AND completed_at IS NULL)
            OR (status = 'completed' AND result_count IS NOT NULL AND completed_at IS NOT NULL)
          ),
          CONSTRAINT projection_batch_claims_result_count_matches_candidates
            CHECK (result_count IS NULL OR result_count = candidate_count)
        )
        """.trimIndent(),
        """
        ALTER TABLE ${names.projectionBatchClaims}
          ADD COLUMN IF NOT EXISTS retry_horizon_ms BIGINT
        """.trimIndent(),
        """
        UPDATE ${names.projectionBatchClaims}
        SET retry_horizon_ms = GREATEST(
          1,
          CEIL(EXTRACT(EPOCH FROM (retry_deadline_at - created_at)) * 1000)::BIGINT
        )
        WHERE retry_horizon_ms IS NULL
        """.trimIndent(),
        """
        ALTER TABLE ${names.projectionBatchClaims}
          ALTER COLUMN retry_horizon_ms SET NOT NULL
        """.trimIndent(),
        """
        DO ${'$'}${'$'}
        BEGIN
          IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'projection_batch_claims_retry_horizon_positive'
              AND conrelid = '${names.projectionBatchClaims}'::regclass
          ) THEN
            ALTER TABLE ${names.projectionBatchClaims}
              ADD CONSTRAINT projection_batch_claims_retry_horizon_positive
              CHECK (retry_horizon_ms > 0);
          END IF;
        END;
        ${'$'}${'$'}
        """.trimIndent(),
        """
        UPDATE ${names.projectionBatchClaims}
        SET retain_until = completed_at + (retry_horizon_ms * INTERVAL '1 millisecond')
        WHERE status = 'completed'
          AND retain_until IS NULL
        """.trimIndent(),
        """
        DO ${'$'}${'$'}
        BEGIN
          IF NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'projection_batch_claims_result_count_matches_candidates'
              AND conrelid = '${names.projectionBatchClaims}'::regclass
          ) THEN
            ALTER TABLE ${names.projectionBatchClaims}
              ADD CONSTRAINT projection_batch_claims_result_count_matches_candidates
              CHECK (result_count IS NULL OR result_count = candidate_count);
          END IF;
        END;
        ${'$'}${'$'}
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${names.projectionBatchClaimFrontiers} (
          batch_identity TEXT NOT NULL REFERENCES ${names.projectionBatchClaims}(batch_identity) ON DELETE CASCADE,
          partition_id INTEGER NOT NULL,
          max_stream_sequence BIGINT NOT NULL,
          PRIMARY KEY (batch_identity, partition_id)
        )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS idx_projection_batch_claims_cleanup
        ON ${names.projectionBatchClaims}(retry_deadline_at, batch_identity)
        WHERE status = 'completed'
        """.trimIndent(),
        """
        CREATE OR REPLACE FUNCTION ${names.projectionBatchIdentityV1Function}(
          p_projection_name TEXT,
          p_event_stream TEXT,
          p_projection_stage TEXT,
          p_include_fills BOOLEAN,
          p_candidates JSONB
        )
        RETURNS TEXT
        LANGUAGE SQL
        IMMUTABLE
        STRICT
        AS ${'$'}${'$'}
          SELECT encode(
            sha256(
              int4send(1)
              || int4send(octet_length(convert_to(p_projection_name, 'UTF8')))
              || convert_to(p_projection_name, 'UTF8')
              || int4send(octet_length(convert_to(p_event_stream, 'UTF8')))
              || convert_to(p_event_stream, 'UTF8')
              || int4send(octet_length(convert_to(p_projection_stage, 'UTF8')))
              || convert_to(p_projection_stage, 'UTF8')
              || CASE WHEN p_include_fills THEN decode('01', 'hex') ELSE decode('00', 'hex') END
              || int4send(jsonb_array_length(p_candidates))
              || COALESCE(
                (
                  SELECT string_agg(
                    int4send((candidate->>'partitionId')::INTEGER)
                    || int8send((candidate->>'streamSequence')::BIGINT)
                    || int4send(octet_length(convert_to(candidate->>'commandId', 'UTF8')))
                    || convert_to(candidate->>'commandId', 'UTF8')
                    || int4send(octet_length(convert_to(candidate->>'canonicalBatchId', 'UTF8')))
                    || convert_to(candidate->>'canonicalBatchId', 'UTF8')
                    || int4send(octet_length(convert_to(candidate->>'commandType', 'UTF8')))
                    || convert_to(candidate->>'commandType', 'UTF8')
                    || int4send(octet_length(convert_to(candidate->>'payloadHash', 'UTF8')))
                    || convert_to(candidate->>'payloadHash', 'UTF8'),
                    ''::BYTEA ORDER BY ordinality
                  )
                  FROM jsonb_array_elements(p_candidates) WITH ORDINALITY AS members(candidate, ordinality)
                ),
                ''::BYTEA
              )
            ),
            'hex'
          )
        ${'$'}${'$'}
        """.trimIndent(),
        claimFunction(names),
        """
        CREATE OR REPLACE FUNCTION ${names.completeProjectionBatchV1Function}(
          p_batch_identity TEXT,
          p_result_count BIGINT
        )
        RETURNS BIGINT
        LANGUAGE plpgsql
        AS ${'$'}${'$'}
        DECLARE
          completed_count BIGINT;
          expected_count INTEGER;
          completion_time TIMESTAMPTZ;
        BEGIN
          SELECT candidate_count INTO expected_count
          FROM ${names.projectionBatchClaims}
          WHERE batch_identity = p_batch_identity
            AND status = 'in-progress'
          FOR UPDATE;

          IF NOT FOUND THEN
            RAISE EXCEPTION 'projection batch claim cannot be completed from its current state';
          END IF;

          IF p_result_count IS DISTINCT FROM expected_count THEN
            RAISE EXCEPTION 'projection batch result count does not match claimed membership';
          END IF;

          completion_time := clock_timestamp();

          UPDATE ${names.projectionBatchClaims}
          SET
            status = 'completed',
            result_count = p_result_count,
            completed_at = completion_time,
            retain_until = completion_time + (retry_horizon_ms * INTERVAL '1 millisecond')
          WHERE batch_identity = p_batch_identity
            AND status = 'in-progress'
          RETURNING result_count INTO completed_count;

          RETURN completed_count;
        END;
        ${'$'}${'$'}
        """.trimIndent(),
        cleanupFunction(names)
    )

    private fun claimFunction(names: PostgresRuntimeSqlNames): String =
        """
        CREATE OR REPLACE FUNCTION ${names.claimProjectionBatchV1Function}(
          p_batch_identity TEXT,
          p_projection_name TEXT,
          p_event_stream TEXT,
          p_projection_stage TEXT,
          p_include_fills BOOLEAN,
          p_candidates JSONB,
          p_retry_deadline_at TIMESTAMPTZ,
          p_retry_horizon_ms BIGINT
        )
        RETURNS TABLE(is_new BOOLEAN, stored_result_count BIGINT)
        LANGUAGE plpgsql
        AS ${'$'}${'$'}
        DECLARE
          inserted_identity TEXT;
          existing_claim ${names.projectionBatchClaims}%ROWTYPE;
          expected_count INTEGER;
        BEGIN
          IF p_candidates IS NULL OR jsonb_typeof(p_candidates) <> 'array' THEN
            RAISE EXCEPTION 'projection batch candidates must be a JSON array';
          END IF;

          expected_count := jsonb_array_length(p_candidates);
          IF expected_count <= 0 THEN
            RAISE EXCEPTION 'projection batch claim requires at least one candidate';
          END IF;

          IF p_retry_deadline_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'projection batch retry deadline has expired';
          END IF;

          IF p_retry_horizon_ms IS NULL OR p_retry_horizon_ms <= 0 THEN
            RAISE EXCEPTION 'projection batch retry horizon must be positive';
          END IF;

          IF p_retry_deadline_at > clock_timestamp() + (p_retry_horizon_ms * INTERVAL '1 millisecond') THEN
            RAISE EXCEPTION 'projection batch retry deadline exceeds its configured horizon';
          END IF;

          IF p_batch_identity IS DISTINCT FROM ${names.projectionBatchIdentityV1Function}(
            p_projection_name, p_event_stream, p_projection_stage, p_include_fills, p_candidates
          ) THEN
            RAISE EXCEPTION 'projection batch identity does not match canonical membership encoding';
          END IF;

          INSERT INTO ${names.projectionBatchClaims}(
            batch_identity, identity_version, projection_name, event_stream, projection_stage,
            include_fills, candidate_count, status, retry_deadline_at, retry_horizon_ms
          )
          VALUES (
            p_batch_identity, 1, p_projection_name, p_event_stream, p_projection_stage,
            p_include_fills, expected_count, 'in-progress', p_retry_deadline_at, p_retry_horizon_ms
          )
          ON CONFLICT (batch_identity) DO NOTHING
          RETURNING batch_identity INTO inserted_identity;

          IF inserted_identity IS NOT NULL THEN
            INSERT INTO ${names.projectionBatchClaimFrontiers}(batch_identity, partition_id, max_stream_sequence)
            SELECT
              p_batch_identity,
              (candidate->>'partitionId')::INTEGER,
              MAX((candidate->>'streamSequence')::BIGINT)
            FROM jsonb_array_elements(p_candidates) AS members(candidate)
            GROUP BY (candidate->>'partitionId')::INTEGER;

            RETURN QUERY SELECT TRUE, NULL::BIGINT;
            RETURN;
          END IF;

          SELECT * INTO existing_claim
          FROM ${names.projectionBatchClaims}
          WHERE batch_identity = p_batch_identity;

          IF NOT FOUND THEN
            RAISE EXCEPTION 'projection batch claim disappeared after identity conflict';
          END IF;

          IF existing_claim.identity_version <> 1
             OR existing_claim.projection_name IS DISTINCT FROM p_projection_name
             OR existing_claim.event_stream IS DISTINCT FROM p_event_stream
             OR existing_claim.projection_stage IS DISTINCT FROM p_projection_stage
             OR existing_claim.include_fills IS DISTINCT FROM p_include_fills
             OR existing_claim.candidate_count IS DISTINCT FROM expected_count
             OR existing_claim.retry_horizon_ms IS DISTINCT FROM p_retry_horizon_ms THEN
            RAISE EXCEPTION 'projection batch claim conflicts with immutable configuration';
          END IF;

          IF EXISTS (
            WITH expected_frontiers AS (
              SELECT
                (candidate->>'partitionId')::INTEGER AS partition_id,
                MAX((candidate->>'streamSequence')::BIGINT) AS max_stream_sequence
              FROM jsonb_array_elements(p_candidates) AS members(candidate)
              GROUP BY (candidate->>'partitionId')::INTEGER
            )
            SELECT 1
            FROM expected_frontiers expected
            FULL JOIN (
              SELECT partition_id, max_stream_sequence
              FROM ${names.projectionBatchClaimFrontiers}
              WHERE batch_identity = p_batch_identity
            ) stored
              ON stored.partition_id = expected.partition_id
            WHERE expected.partition_id IS NULL
               OR stored.partition_id IS NULL
               OR stored.max_stream_sequence IS DISTINCT FROM expected.max_stream_sequence
          ) THEN
            RAISE EXCEPTION 'projection batch claim conflicts with immutable member frontiers';
          END IF;

          IF existing_claim.status <> 'completed'
             OR existing_claim.result_count IS DISTINCT FROM existing_claim.candidate_count THEN
            RAISE EXCEPTION 'projection batch claim is incomplete or has an inconsistent result count';
          END IF;

          UPDATE ${names.projectionBatchClaims}
          SET retain_until = GREATEST(retain_until, p_retry_deadline_at)
          WHERE batch_identity = p_batch_identity
          RETURNING * INTO existing_claim;

          RETURN QUERY SELECT FALSE, existing_claim.result_count;
        END;
        ${'$'}${'$'}
        """.trimIndent()

    private fun cleanupFunction(names: PostgresRuntimeSqlNames): String =
        """
        CREATE OR REPLACE FUNCTION ${names.cleanupProjectionBatchClaimsFunction}(
          p_limit INTEGER DEFAULT 1000
        )
        RETURNS BIGINT
        LANGUAGE plpgsql
        AS ${'$'}${'$'}
        DECLARE
          deleted_count BIGINT := 0;
        BEGIN
          WITH eligible AS (
            SELECT claim.batch_identity
            FROM ${names.projectionBatchClaims} claim
            WHERE claim.status = 'completed'
              AND claim.retry_deadline_at < clock_timestamp()
              AND claim.retain_until < clock_timestamp()
              AND NOT EXISTS (
                SELECT 1
                FROM ${names.projectionBatchClaimFrontiers} frontier
                LEFT JOIN ${names.projectionWatermarks} watermark
                  ON watermark.projection_name = claim.projection_name
                 AND watermark.partition_id = frontier.partition_id
                WHERE frontier.batch_identity = claim.batch_identity
                  AND (
                    watermark.partition_id IS NULL
                    OR watermark.last_partition_seq <= frontier.max_stream_sequence
                  )
              )
            ORDER BY claim.retry_deadline_at, claim.batch_identity
            LIMIT GREATEST(COALESCE(p_limit, 0), 0)
          ),
          deleted AS (
            DELETE FROM ${names.projectionBatchClaims} claim
            USING eligible
            WHERE claim.batch_identity = eligible.batch_identity
            RETURNING 1
          )
          SELECT COUNT(*) INTO deleted_count FROM deleted;

          RETURN deleted_count;
        END;
        ${'$'}${'$'}
        """.trimIndent()
}
