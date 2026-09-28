-- V2 contains resumable writer state. V1 remains proof-only and cannot hydrate an evaluator.
ALTER TABLE settlement.settlement_journal_snapshots
  DROP CONSTRAINT settlement_journal_snapshots_state_version_check;
ALTER TABLE settlement.settlement_journal_snapshots
  ADD CONSTRAINT settlement_journal_snapshots_state_version_check
  CHECK (state_version IN (1, 2));
