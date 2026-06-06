package config

import (
	"path/filepath"
	"runtime"
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

func TestP1GoldenScenarioDefinesOrderedMonotonicEventTimeline(t *testing.T) {
	scenario, err := LoadScenarioFile(filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"))
	if err != nil {
		t.Fatalf("LoadScenarioFile error: %v", err)
	}

	positions := map[string]int{}
	for index, event := range scenario.ExpectedEventTimeline.Events {
		positions[event.EventType] = index
	}

	if positions["ExecutionCreated"] >= positions["TradeCreated"] {
		t.Fatal("ExecutionCreated must precede TradeCreated")
	}
	if positions["TradeAffirmed"] >= positions["SettlementObligationCreated"] {
		t.Fatal("TradeAffirmed must precede SettlementObligationCreated")
	}
}

func TestScenarioValidationRejectsGoldenScenarioWithoutTimeline(t *testing.T) {
	err := ValidateScenarioFile(
		ScenarioFile{
			SchemaVersion:    "1.0",
			PathID:           "P1_GOLDEN_TEST",
			Name:             "Golden Test",
			Seed:             1,
			ExpectedEvents:   []string{"ScenarioRunStarted"},
			ReplayAssertions: []string{"Expected event order remains stable across replay."},
		},
	)
	if err == nil {
		t.Fatal("expected missing golden timeline to fail validation")
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
