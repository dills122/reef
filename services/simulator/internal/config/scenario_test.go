package config

import (
	"path/filepath"
	"runtime"
	"strings"
	"testing"
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
