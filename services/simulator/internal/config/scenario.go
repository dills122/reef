package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

type ScenarioFile struct {
	SchemaVersion         string                `yaml:"schemaVersion"`
	PathID                string                `yaml:"pathId"`
	Name                  string                `yaml:"name"`
	Seed                  int64                 `yaml:"seed"`
	ExpectedEvents        []string              `yaml:"expectedEvents"`
	ExpectedEventTimeline ExpectedEventTimeline `yaml:"expectedEventTimeline"`
	ReplayAssertions      []string              `yaml:"replayAssertions"`
}

type ExpectedEventTimeline struct {
	Anchor string                  `yaml:"anchor"`
	Events []ExpectedTimelineEvent `yaml:"events"`
}

type ExpectedTimelineEvent struct {
	EventType               string `yaml:"eventType"`
	OccurredAtOffsetSeconds int    `yaml:"occurredAtOffsetSeconds"`
}

func LoadScenarioFile(path string) (ScenarioFile, error) {
	var scenario ScenarioFile
	content, err := os.ReadFile(path)
	if err != nil {
		return scenario, fmt.Errorf("read scenario file: %w", err)
	}
	if err := yaml.Unmarshal(content, &scenario); err != nil {
		return scenario, fmt.Errorf("parse scenario file: %w", err)
	}
	if err := ValidateScenarioFile(scenario); err != nil {
		return scenario, fmt.Errorf("%s: %w", path, err)
	}
	return scenario, nil
}

func LoadScenarioDirectory(root string) ([]ScenarioFile, error) {
	scenarios := make([]ScenarioFile, 0)
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || !isScenarioYaml(path) {
			return nil
		}
		scenario, err := LoadScenarioFile(path)
		if err != nil {
			return err
		}
		scenarios = append(scenarios, scenario)
		return nil
	})
	if err != nil {
		return nil, err
	}
	if len(scenarios) == 0 {
		return nil, fmt.Errorf("no scenario files found in %s", root)
	}
	return scenarios, nil
}

func ValidateScenarioFile(scenario ScenarioFile) error {
	if scenario.SchemaVersion == "" {
		return fmt.Errorf("schemaVersion is required")
	}
	if scenario.PathID == "" {
		return fmt.Errorf("pathId is required")
	}
	if scenario.Name == "" {
		return fmt.Errorf("name is required")
	}
	if scenario.Seed == 0 {
		return fmt.Errorf("seed is required and must be non-zero")
	}
	if len(scenario.ExpectedEvents) == 0 {
		return fmt.Errorf("expectedEvents must not be empty")
	}
	if len(scenario.ReplayAssertions) == 0 {
		return fmt.Errorf("replayAssertions must not be empty")
	}
	if isGoldenPath(scenario.PathID) && len(scenario.ExpectedEventTimeline.Events) == 0 {
		return fmt.Errorf("golden scenario %s must define expectedEventTimeline", scenario.PathID)
	}
	if len(scenario.ExpectedEventTimeline.Events) > 0 {
		if err := validateExpectedEventTimeline(scenario); err != nil {
			return err
		}
	}
	return nil
}

func validateExpectedEventTimeline(scenario ScenarioFile) error {
	timeline := scenario.ExpectedEventTimeline
	if timeline.Anchor != "scenarioStart" {
		return fmt.Errorf("expectedEventTimeline.anchor must be scenarioStart")
	}
	if len(timeline.Events) != len(scenario.ExpectedEvents) {
		return fmt.Errorf("expectedEventTimeline length %d does not match expectedEvents length %d", len(timeline.Events), len(scenario.ExpectedEvents))
	}
	previousOffset := -1
	for index, event := range timeline.Events {
		if event.EventType == "" {
			return fmt.Errorf("expectedEventTimeline.events[%d].eventType is required", index)
		}
		if event.EventType != scenario.ExpectedEvents[index] {
			return fmt.Errorf("expectedEventTimeline.events[%d].eventType %s does not match expectedEvents[%d] %s", index, event.EventType, index, scenario.ExpectedEvents[index])
		}
		if event.OccurredAtOffsetSeconds < previousOffset {
			return fmt.Errorf("expectedEventTimeline.events[%d].occurredAtOffsetSeconds regresses from %d to %d", index, previousOffset, event.OccurredAtOffsetSeconds)
		}
		previousOffset = event.OccurredAtOffsetSeconds
	}
	if !containsReplayAssertion(scenario.ReplayAssertions, "expectedEventTimeline occurredAt offsets") {
		return fmt.Errorf("replayAssertions must require stable expectedEventTimeline occurredAt offsets")
	}
	return nil
}

func isScenarioYaml(path string) bool {
	ext := strings.ToLower(filepath.Ext(path))
	return ext == ".yaml" || ext == ".yml"
}

func isGoldenPath(pathID string) bool {
	return strings.Contains(strings.ToUpper(pathID), "GOLDEN")
}

func containsReplayAssertion(assertions []string, needle string) bool {
	for _, assertion := range assertions {
		if strings.Contains(assertion, needle) {
			return true
		}
	}
	return false
}
