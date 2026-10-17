package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestScenarioSmokeLiveAssertionsAttachP2SettlementFacts(t *testing.T) {
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN","occurredAt":"2026-03-14T18:00:05Z"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR","actorType":"USER","actorId":"ops-1","occurredAt":"2026-03-14T18:00:06Z"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED","occurredAt":"2026-03-14T18:00:07Z"}]
	}`
	server := p2SettlementServer(t, settlementJSON)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if !hasAssertion(report, "p2-no-direct-resolution-without-repair", "pass") {
		t.Fatalf("missing settlement causation assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-causation-fields", "pass") {
		t.Fatalf("missing settlement causation field assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-chain-linked", "pass") {
		t.Fatalf("missing settlement chain assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-settlement-scope-consistent", "pass") {
		t.Fatalf("missing settlement scope assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-state-resolved", "pass") {
		t.Fatalf("missing exception queue resolved assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-repair-action-matches-reason", "pass") {
		t.Fatalf("missing exception queue repair action assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsAttachP2SecuritySettlementFacts(t *testing.T) {
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"SECURITY_LEG_FAILED","state":"BROKEN","occurredAt":"2026-03-14T18:00:05Z"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_SECURITY_LEG_REPAIR","actorType":"USER","actorId":"ops-1","occurredAt":"2026-03-14T18:00:06Z"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED","occurredAt":"2026-03-14T18:00:07Z"}]
	}`
	server := p2SettlementServerWithExceptionQueue(
		t,
		settlementJSON,
		p2ResolvedSettlementExceptionQueueJSON("SECURITY_LEG_FAILED", "POST_SECURITY_LEG_REPAIR"),
	)
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if !hasAssertion(report, "p2-break-reason-security-leg-failed", "pass") {
		t.Fatalf("missing security break reason assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-repair-action-posted", "pass") {
		t.Fatalf("missing security repair action assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-exception-repair-action-matches-reason", "pass") {
		t.Fatalf("missing security exception queue repair action assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsAttachP2OpsRealisticPendingFacts(t *testing.T) {
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","postTradeProfileId":"ops-realistic-v1","postTradePolicyVersion":1,"correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","buyerParticipantId":"buyer-1","sellerParticipantId":"seller-1","instrumentId":"XYZ","quantity":"100","cashAmount":"10000.00","currency":"USD","state":"OBLIGATION_CREATED","occurredAt":"2026-03-14T18:00:04Z"}],
		"allocations":[],
		"confirmations":[],
		"affirmations":[],
		"clearingSubmissions":[],
		"clearingAcceptances":[],
		"clearingRejections":[],
		"novations":[],
		"instructions":[],
		"attempts":[],
		"legOutcomes":[],
		"ledgerEntries":[],
		"settlements":[],
		"breaks":[],
		"repairs":[],
		"resolutions":[]
	}`
	server := p2SettlementServerWithExceptionQueue(t, settlementJSON, p2EmptySettlementExceptionQueueJSON())
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	for _, assertionID := range []string{
		"p2-ops-pending-obligation-created",
		"p2-ops-pending-profile",
		"p2-ops-pending-zero-clearing-submissions",
		"p2-ops-pending-zero-clearing-acceptances",
		"p2-ops-pending-zero-novations",
		"p2-ops-pending-zero-leg-outcomes",
		"p2-ops-pending-zero-ledger-entries",
		"p2-ops-pending-zero-settlements",
		"p2-exception-queue-empty",
	} {
		if !hasAssertion(report, assertionID, "pass") {
			t.Fatalf("missing ops pending assertion %s: %+v", assertionID, report.Assertions)
		}
	}
}

func TestScenarioSmokeLiveAssertionsAttachPostTradeSettlementChain(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"trade-event-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"allocations":[{"settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"obl-1","tradeId":"trade-1","buyOrderId":"buy-order-1","sellOrderId":"sell-order-1","state":"ALLOCATION_PROPOSED"}],
		"confirmations":[{"settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"allocation-1","tradeId":"trade-1","state":"CONFIRMATION_GENERATED"}],
		"affirmations":[{"settlementAffirmationId":"affirmation-1","settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"confirmation-1","tradeId":"trade-1","state":"AFFIRMATION_ACCEPTED"}],
		"clearingSubmissions":[{"settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","settlementAffirmationId":"affirmation-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"affirmation-1","state":"CLEARING_SUBMITTED"}],
		"clearingAcceptances":[{"settlementClearingAcceptanceId":"clearing-acceptance-1","settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-submission-1","state":"CLEARING_ACCEPTED"}],
		"clearingRejections":[],
		"novations":[{"settlementNovationId":"novation-1","settlementClearingAcceptanceId":"clearing-acceptance-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-acceptance-1","state":"NOVATION_RECORDED"}],
		"instructions":[{"settlementInstructionId":"instruction-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"novation-1","state":"INSTRUCTION_CREATED"}],
		"attempts":[{"settlementAttemptId":"attempt-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"instruction-1","state":"ATTEMPT_STARTED"}],
		"settlements":[{"settlementId":"settlement-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"attempt-1","settlementState":"SETTLED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p2-post-trade-chain-linked", "pass") {
		t.Fatalf("missing post-trade chain assertion: %+v", report.Assertions)
	}
	for _, assertionID := range []string{
		"p2-obligation-state-created",
		"p2-allocation-state-proposed",
		"p2-confirmation-state-generated",
		"p2-affirmation-state-accepted",
		"p2-clearing-state-submitted",
		"p2-clearing-state-accepted",
		"p2-novation-state-recorded",
		"p2-instruction-state-created",
		"p2-attempt-state-started",
		"p2-settlement-state-settled",
	} {
		if !hasAssertion(report, assertionID, "pass") {
			t.Fatalf("missing post-trade state assertion %s: %+v", assertionID, report.Assertions)
		}
	}
}

func TestScenarioSmokeLiveAssertionsFailOnPostTradeSettlementChainWrongState(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"trade-event-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"allocations":[{"settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"obl-1","tradeId":"trade-1","buyOrderId":"buy-order-1","sellOrderId":"sell-order-1","state":"ALLOCATION_SKIPPED"}],
		"confirmations":[{"settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"allocation-1","tradeId":"trade-1","state":"CONFIRMATION_GENERATED"}],
		"affirmations":[{"settlementAffirmationId":"affirmation-1","settlementConfirmationId":"confirmation-1","settlementAllocationId":"allocation-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"confirmation-1","tradeId":"trade-1","state":"AFFIRMATION_ACCEPTED"}],
		"clearingSubmissions":[{"settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","settlementAffirmationId":"affirmation-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"affirmation-1","state":"CLEARING_SUBMITTED"}],
		"clearingAcceptances":[{"settlementClearingAcceptanceId":"clearing-acceptance-1","settlementClearingSubmissionId":"clearing-submission-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-submission-1","state":"CLEARING_ACCEPTED"}],
		"clearingRejections":[],
		"novations":[{"settlementNovationId":"novation-1","settlementClearingAcceptanceId":"clearing-acceptance-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"clearing-acceptance-1","state":"NOVATION_RECORDED"}],
		"instructions":[{"settlementInstructionId":"instruction-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"novation-1","state":"INSTRUCTION_CREATED"}],
		"attempts":[{"settlementAttemptId":"attempt-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"instruction-1","state":"ATTEMPT_STARTED"}],
		"settlements":[{"settlementId":"settlement-1","settlementObligationId":"obl-1","settlementInstructionId":"instruction-1","settlementAttemptId":"attempt-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"attempt-1","settlementState":"SETTLED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected post-trade settlement state failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-allocation-state-proposed") {
		t.Fatalf("expected allocation state failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementFactsWithoutRepairLink(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected settlement fact causation failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-no-direct-resolution-without-repair") {
		t.Fatalf("expected repair linkage failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementBrokenChain(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"other-obligation","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected settlement fact chain failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-settlement-chain-linked") {
		t.Fatalf("expected full chain linkage failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnP2SettlementCrossRunFact(t *testing.T) {
	settlementPath := filepath.Join(t.TempDir(), "settlement-facts.json")
	settlementJSON := `{
		"scenarioRunId":"p2-settlement-live",
		"obligations":[{"settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-1","tradeId":"trade-1","state":"OBLIGATION_CREATED"}],
		"breaks":[{"settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"other-run","correlationId":"corr-1","causationId":"cause-2","reason":"CASH_LEG_FAILED","state":"BROKEN"}],
		"repairs":[{"settlementRepairId":"repair-1","settlementBreakId":"break-1","settlementObligationId":"obl-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-3","repairAction":"POST_CASH_LEG_REPAIR"}],
		"resolutions":[{"settlementResolutionId":"resolution-1","settlementObligationId":"obl-1","settlementBreakId":"break-1","settlementRepairId":"repair-1","scenarioRunId":"p2-settlement-live","correlationId":"corr-1","causationId":"cause-4","settlementState":"RESOLVED","exceptionState":"RESOLVED"}]
	}`
	if err := os.WriteFile(settlementPath, []byte(settlementJSON), 0o644); err != nil {
		t.Fatalf("write settlement facts: %v", err)
	}
	server := p2SettlementServer(t, "")
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P2_SETTLEMENT_BREAK_REPAIR.yaml"),
		"--scenario-run-id", "p2-settlement-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--settlement-facts-report", settlementPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected settlement fact scope failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p2-settlement-scope-consistent") {
		t.Fatalf("expected scenario scope failure: %+v", report.Failures)
	}
}
