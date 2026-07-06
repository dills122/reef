package config

import (
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestScenarioPackDefinitionsValidate(t *testing.T) {
	scenarios, err := LoadScenarioDirectory(scenarioDefinitionsRoot(t))
	if err != nil {
		t.Fatalf("LoadScenarioDirectory error: %v", err)
	}
	if len(scenarios) < 2 {
		t.Fatalf("expected at least two scenario definitions, got %d", len(scenarios))
	}
}

func TestFirstWaveScenariosDefineOrderedMonotonicEventTimelines(t *testing.T) {
	tests := map[string]struct {
		file         string
		beforeEvent  string
		afterEvent   string
		beforeEvent2 string
		afterEvent2  string
	}{
		"P1 golden": {
			file:         "P1_GOLDEN_HIDDEN_CROSS_T1.yaml",
			beforeEvent:  "ExecutionCreated",
			afterEvent:   "TradeCreated",
			beforeEvent2: "TradeAffirmed",
			afterEvent2:  "SettlementObligationCreated",
		},
		"P2 settlement break": {
			file:         "P2_SETTLEMENT_BREAK_REPAIR.yaml",
			beforeEvent:  "SettlementFailed",
			afterEvent:   "ExceptionOpened",
			beforeEvent2: "ExceptionRepairApplied",
			afterEvent2:  "SettlementRetried",
		},
	}

	for name, tt := range tests {
		t.Run(name, func(t *testing.T) {
			scenario, err := LoadScenarioFile(filepath.Join(scenarioDefinitionsRoot(t), tt.file))
			if err != nil {
				t.Fatalf("LoadScenarioFile error: %v", err)
			}

			positions := map[string]int{}
			for index, event := range scenario.ExpectedEventTimeline.Events {
				positions[event.EventType] = index
			}

			if positions[tt.beforeEvent] >= positions[tt.afterEvent] {
				t.Fatalf("%s must precede %s", tt.beforeEvent, tt.afterEvent)
			}
			if positions[tt.beforeEvent2] >= positions[tt.afterEvent2] {
				t.Fatalf("%s must precede %s", tt.beforeEvent2, tt.afterEvent2)
			}
		})
	}
}

func TestCompileP1GoldenHiddenCrossScenarioPlan(t *testing.T) {
	scenario, err := LoadScenarioFile(filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"))
	if err != nil {
		t.Fatalf("LoadScenarioFile error: %v", err)
	}
	start := time.Date(2026, 3, 14, 18, 0, 0, 0, time.UTC)

	plan, err := CompileScenarioPlan(scenario, "p1-run-001", start)
	if err != nil {
		t.Fatalf("CompileScenarioPlan error: %v", err)
	}

	if plan.PathID != "P1_GOLDEN_HIDDEN_CROSS_T1" || plan.ScenarioRunID != "p1-run-001" || plan.Seed != 424242 {
		t.Fatalf("unexpected plan identity: %+v", plan)
	}
	if got, want := len(plan.Steps), 10; got != want {
		t.Fatalf("steps: got %d want %d", got, want)
	}
	apiSteps := executableSteps(plan)
	if got, want := len(apiSteps), 2; got != want {
		t.Fatalf("api executable steps: got %d want %d", got, want)
	}

	buy := apiSteps[0]
	sell := apiSteps[1]
	if buy.Route != apiV1SubmitRoute || sell.Route != apiV1SubmitRoute {
		t.Fatalf("submit route mismatch: %s %s", buy.Route, sell.Route)
	}
	assertPayload(t, buy.Payload, map[string]string{
		"commandId":      "p1_golden_hidden_cross_t1-cmd-001",
		"traceId":        "p1_golden_hidden_cross_t1-trace-001",
		"correlationId":  "p1_golden_hidden_cross_t1-corr-001",
		"runId":          "p1-run-001",
		"runKind":        "scenario",
		"scenarioId":     "P1_GOLDEN_HIDDEN_CROSS_T1",
		"scenarioRunId":  "p1-run-001",
		"seed":           "424242",
		"venueSessionId": "P1_GOLDEN_HIDDEN_CROSS_T1",
		"occurredAt":     "2026-03-14T18:00:01Z",
		"orderId":        "p1_golden_hidden_cross_t1-ord-001",
		"clientOrderId":  "p1_golden_hidden_cross_t1-ord-001",
		"instrumentId":   "XYZ",
		"participantId":  "BUY_SIDE_1",
		"accountId":      "BUY_SIDE_1_MAIN",
		"side":           "BUY",
		"orderType":      "LIMIT",
		"quantityUnits":  "1000",
		"limitPrice":     "150000000000",
		"currency":       "USD",
		"timeInForce":    "DAY",
	})
	assertPayload(t, sell.Payload, map[string]string{
		"commandId":     "p1_golden_hidden_cross_t1-cmd-002",
		"occurredAt":    "2026-03-14T18:00:02Z",
		"orderId":       "p1_golden_hidden_cross_t1-ord-002",
		"participantId": "SELL_SIDE_1",
		"accountId":     "SELL_SIDE_1_MAIN",
		"side":          "SELL",
		"quantityUnits": "1000",
	})

	if got, want := plan.ExpectedFinalStates["scenarioRun"], "COMPLETED"; got != want {
		t.Fatalf("scenarioRun final state: got %s want %s", got, want)
	}
	if !containsExact(plan.ExpectedEvents, "SettlementCompleted") {
		t.Fatal("expected SettlementCompleted in P1 event sequence")
	}
	if !containsReplayAssertion(plan.ReplayAssertions, "expectedEvents sequence is identical") {
		t.Fatal("expected replay assertion for stable expectedEvents sequence")
	}
	if len(plan.IdempotencyAssertions) != 3 {
		t.Fatalf("idempotency assertions: got %d want 3", len(plan.IdempotencyAssertions))
	}
}

func TestCompileScenarioPlanRejectsSubmitWithoutReferenceAccount(t *testing.T) {
	scenario, err := LoadScenarioFile(filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"))
	if err != nil {
		t.Fatalf("LoadScenarioFile error: %v", err)
	}
	scenario.Preconditions.ReferenceData.Accounts = nil

	_, err = CompileScenarioPlan(scenario, "p1-run-001", time.Date(2026, 3, 14, 18, 0, 0, 0, time.UTC))
	if err == nil {
		t.Fatal("expected missing account participant mapping to fail")
	}
	if !strings.Contains(err.Error(), "referenceData account participant") {
		t.Fatalf("expected referenceData account participant error, got %v", err)
	}
}

func TestScenarioValidationRejectsFirstWaveScenarioWithoutTimeline(t *testing.T) {
	scenario := validScenarioFile()
	scenario.PathID = "P2_SETTLEMENT_BREAK_REPAIR"
	scenario.ExpectedEventTimeline = ExpectedEventTimeline{}
	err := ValidateScenarioFile(scenario)
	if err == nil {
		t.Fatal("expected missing first-wave timeline to fail validation")
	}
	if !strings.Contains(err.Error(), "first-wave scenario") {
		t.Fatalf("expected first-wave timeline error, got %v", err)
	}
}

func TestScenarioValidationRejectsGoldenScenarioWithoutTimeline(t *testing.T) {
	scenario := validScenarioFile()
	scenario.PathID = "P1_GOLDEN_TEST"
	scenario.ExpectedEventTimeline = ExpectedEventTimeline{}
	err := ValidateScenarioFile(scenario)
	if err == nil {
		t.Fatal("expected missing golden timeline to fail validation")
	}
	if !strings.Contains(err.Error(), "golden scenario") {
		t.Fatalf("expected golden timeline error, got %v", err)
	}
}

func TestScenarioValidationRejectsMissingRequiredMetadataFields(t *testing.T) {
	scenario := validScenarioFile()
	scenario.RunContext.Metadata.ScenarioRunRequiredFields = []string{"scenarioRunId", "seed", "correlationId"}

	err := ValidateScenarioFile(scenario)
	if err == nil {
		t.Fatal("expected missing causationId metadata requirement to fail validation")
	}
	if !strings.Contains(err.Error(), "causationId") {
		t.Fatalf("expected causationId error, got %v", err)
	}
}

func TestScenarioValidationRejectsNonContiguousSteps(t *testing.T) {
	scenario := validScenarioFile()
	scenario.Steps = []ScenarioStep{
		{Sequence: 1, Command: "SubmitOrder"},
		{Sequence: 3, Command: "MatchOrders"},
	}

	err := ValidateScenarioFile(scenario)
	if err == nil {
		t.Fatal("expected non-contiguous steps to fail validation")
	}
	if !strings.Contains(err.Error(), "steps[1].sequence") {
		t.Fatalf("expected step sequence error, got %v", err)
	}
}

func TestScenarioValidationRejectsEmptyStepCommand(t *testing.T) {
	scenario := validScenarioFile()
	scenario.Steps[1].Command = ""

	err := ValidateScenarioFile(scenario)
	if err == nil {
		t.Fatal("expected empty step command to fail validation")
	}
	if !strings.Contains(err.Error(), "steps[1].command") {
		t.Fatalf("expected step command error, got %v", err)
	}
}

func executableSteps(plan ScenarioPlan) []ScenarioPlanStep {
	steps := make([]ScenarioPlanStep, 0)
	for _, step := range plan.Steps {
		if step.APIExecutable {
			steps = append(steps, step)
		}
	}
	return steps
}

func assertPayload(t *testing.T, payload map[string]string, expected map[string]string) {
	t.Helper()
	for key, want := range expected {
		if got := payload[key]; got != want {
			t.Fatalf("payload[%s]: got %q want %q", key, got, want)
		}
	}
}

func scenarioDefinitionsRoot(t *testing.T) string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(currentFile), "../../../../packages/scenario-definitions/scenarios/v1"))
}

func validScenarioFile() ScenarioFile {
	return ScenarioFile{
		SchemaVersion: "1.0",
		PathID:        "P3_TEST",
		Name:          "Valid Test",
		Seed:          1,
		RunContext: ScenarioRunContext{
			Metadata: ScenarioRunMetadata{
				ScenarioRunRequiredFields: []string{
					"scenarioRunId",
					"seed",
					"correlationId",
					"causationId",
				},
			},
		},
		Steps: []ScenarioStep{
			{Sequence: 1, Command: "SubmitOrder"},
			{Sequence: 2, Command: "MatchOrders"},
		},
		ExpectedEvents: []string{
			"ScenarioRunStarted",
			"ScenarioRunCompleted",
		},
		ExpectedEventTimeline: ExpectedEventTimeline{
			Anchor: "scenarioStart",
			Events: []ExpectedTimelineEvent{
				{EventType: "ScenarioRunStarted", OccurredAtOffsetSeconds: 0},
				{EventType: "ScenarioRunCompleted", OccurredAtOffsetSeconds: 1},
			},
		},
		ReplayAssertions: []string{
			"Given the same seed, expectedEventTimeline occurredAt offsets are identical.",
		},
	}
}
