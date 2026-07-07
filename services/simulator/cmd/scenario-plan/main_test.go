package main

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	scenarioconfig "github.com/dills122/reef/services/simulator/internal/config"
)

func TestScenarioPlanCommandPrintsP1Json(t *testing.T) {
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-run-cli",
		"--start", "2026-03-14T18:00:00Z",
	}, &stdout)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}

	var plan scenarioconfig.ScenarioPlan
	if err := json.Unmarshal(stdout.Bytes(), &plan); err != nil {
		t.Fatalf("plan json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if plan.PathID != "P1_GOLDEN_HIDDEN_CROSS_T1" || plan.ScenarioRunID != "p1-run-cli" {
		t.Fatalf("unexpected plan identity: %+v", plan)
	}
	apiSteps := 0
	for _, step := range plan.Steps {
		if step.APIExecutable {
			apiSteps++
			if step.Route != "/api/v1/orders/submit" {
				t.Fatalf("unexpected route: %s", step.Route)
			}
			if step.Payload["scenarioRunId"] != "p1-run-cli" {
				t.Fatalf("missing scenarioRunId in payload: %+v", step.Payload)
			}
		}
	}
	if apiSteps != 3 {
		t.Fatalf("api executable steps: got %d want 3", apiSteps)
	}
	if !strings.Contains(stdout.String(), `"pathId":"P1_GOLDEN_HIDDEN_CROSS_T1"`) {
		t.Fatalf("expected compact lower-camel JSON, got %s", stdout.String())
	}
}

func TestScenarioPlanCommandPrettyPrintsJson(t *testing.T) {
	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--pretty",
	}, &stdout)
	if err != nil {
		t.Fatalf("run error: %v", err)
	}
	if !strings.Contains(stdout.String(), "\n  \"pathId\"") {
		t.Fatalf("expected pretty JSON, got %s", stdout.String())
	}
}

func TestScenarioPlanCommandRequiresScenario(t *testing.T) {
	err := run(nil, &bytes.Buffer{})
	if err == nil {
		t.Fatal("expected missing scenario to fail")
	}
	if !strings.Contains(err.Error(), "missing --scenario") {
		t.Fatalf("expected missing --scenario error, got %v", err)
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
