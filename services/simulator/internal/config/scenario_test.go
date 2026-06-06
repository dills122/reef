package config

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

type scenarioFile struct {
	PathID                string                `yaml:"pathId"`
	ExpectedEvents        []string              `yaml:"expectedEvents"`
	ExpectedEventTimeline expectedEventTimeline `yaml:"expectedEventTimeline"`
	ReplayAssertions      []string              `yaml:"replayAssertions"`
}

type expectedEventTimeline struct {
	Anchor string                  `yaml:"anchor"`
	Events []expectedTimelineEvent `yaml:"events"`
}

type expectedTimelineEvent struct {
	EventType               string `yaml:"eventType"`
	OccurredAtOffsetSeconds int    `yaml:"occurredAtOffsetSeconds"`
}

func TestP1GoldenScenarioDefinesOrderedMonotonicEventTimeline(t *testing.T) {
	scenario := loadScenarioFixture(t, "P1_GOLDEN_HIDDEN_CROSS_T1.yaml")

	if scenario.PathID != "P1_GOLDEN_HIDDEN_CROSS_T1" {
		t.Fatalf("unexpected pathId: %s", scenario.PathID)
	}
	if scenario.ExpectedEventTimeline.Anchor != "scenarioStart" {
		t.Fatalf("unexpected timeline anchor: %s", scenario.ExpectedEventTimeline.Anchor)
	}
	if len(scenario.ExpectedEventTimeline.Events) != len(scenario.ExpectedEvents) {
		t.Fatalf("timeline/event length mismatch: timeline=%d expectedEvents=%d", len(scenario.ExpectedEventTimeline.Events), len(scenario.ExpectedEvents))
	}

	previousOffset := -1
	positions := map[string]int{}
	for index, event := range scenario.ExpectedEventTimeline.Events {
		if event.EventType != scenario.ExpectedEvents[index] {
			t.Fatalf("timeline event %d = %s, expected %s", index, event.EventType, scenario.ExpectedEvents[index])
		}
		if event.OccurredAtOffsetSeconds < previousOffset {
			t.Fatalf("timeline offset regressed at %s: %d < %d", event.EventType, event.OccurredAtOffsetSeconds, previousOffset)
		}
		previousOffset = event.OccurredAtOffsetSeconds
		positions[event.EventType] = index
	}

	if positions["ExecutionCreated"] >= positions["TradeCreated"] {
		t.Fatal("ExecutionCreated must precede TradeCreated")
	}
	if positions["TradeAffirmed"] >= positions["SettlementObligationCreated"] {
		t.Fatal("TradeAffirmed must precede SettlementObligationCreated")
	}
	if !containsAssertion(scenario.ReplayAssertions, "expectedEventTimeline occurredAt offsets") {
		t.Fatal("replayAssertions must require stable expectedEventTimeline occurredAt offsets")
	}
}

func loadScenarioFixture(t *testing.T, name string) scenarioFile {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	path := filepath.Clean(filepath.Join(filepath.Dir(currentFile), "../../../../packages/scenario-definitions/scenarios/v1", name))
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read scenario fixture: %v", err)
	}
	var scenario scenarioFile
	if err := yaml.Unmarshal(content, &scenario); err != nil {
		t.Fatalf("parse scenario fixture: %v", err)
	}
	return scenario
}

func containsAssertion(assertions []string, needle string) bool {
	for _, assertion := range assertions {
		if strings.Contains(assertion, needle) {
			return true
		}
	}
	return false
}
