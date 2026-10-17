package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
)

func requireCommandStatusLookup(cfg config, client *http.Client) error {
	request, err := http.NewRequest(http.MethodGet, absoluteURL(cfg.statusBaseURL, "/readyz"), nil)
	if err != nil {
		return fmt.Errorf("command status readiness check failed: %w", err)
	}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("command status readiness check failed: %w", err)
	}
	defer response.Body.Close()
	body, readErr := io.ReadAll(response.Body)
	if readErr != nil {
		return fmt.Errorf("command status readiness check failed: %w", readErr)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("command status readiness check failed: /readyz returned %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	var ready readyzReport
	if err := json.Unmarshal(body, &ready); err != nil {
		return fmt.Errorf("command status readiness check failed: invalid /readyz JSON: %w", err)
	}
	if !ready.Dependencies.CommandStatusLookup && !hasCommandStatusSource(ready.Pipeline.CommandStatusSources) {
		return errors.New("command status lookup unavailable: /readyz dependencies.commandStatusLookup=false")
	}
	return nil
}

func hasCommandStatusSource(sources []string) bool {
	for _, source := range sources {
		switch strings.ToLower(strings.TrimSpace(source)) {
		case "canonical_outcome", "command_log", "stream_reference", "event_batch":
			return true
		}
	}
	return false
}

func assertCommandStatusScenarioScope(report *smokeReport, sequence int, status commandStatusBody) {
	observed := commandStatusScenarioRunID(status)
	if observed == "" && !commandStatusHasCanonicalScopeEvidence(status) {
		return
	}
	assertionID := fmt.Sprintf("command-%03d-scenario-run", sequence)
	if observed == report.ScenarioRunID {
		passAssertion(report, assertionID, report.ScenarioRunID, observed, "GET /api/v1/commands/{commandId}")
		return
	}
	if observed == "" {
		observed = "missing scenario run in canonical command status"
	}
	failAssertion(report, assertionID, "command_status_scope", report.ScenarioRunID, observed, "GET /api/v1/commands/{commandId}")
}

func parseCommandStatus(body string) commandStatusBody {
	var status commandStatusBody
	_ = json.Unmarshal([]byte(body), &status)
	return status
}

func commandStatusScenarioRunID(status commandStatusBody) string {
	for _, payload := range []string{status.ResponsePayloadJSON, status.ResultPayloadJSON} {
		if runID := scenarioRunIDFromJSON(payload); runID != "" {
			return runID
		}
	}
	return ""
}

func commandStatusHasCanonicalScopeEvidence(status commandStatusBody) bool {
	return strings.TrimSpace(status.CommandStream) != "" ||
		strings.TrimSpace(status.EventStream) != "" ||
		strings.TrimSpace(status.ResultPayloadJSON) != ""
}

func scenarioRunIDFromJSON(payload string) string {
	payload = strings.TrimSpace(payload)
	if payload == "" {
		return ""
	}
	var parsed any
	if err := json.Unmarshal([]byte(payload), &parsed); err != nil {
		return ""
	}
	return scenarioRunIDFromValue(parsed)
}

func scenarioRunIDFromValue(value any) string {
	switch typed := value.(type) {
	case map[string]any:
		for _, key := range []string{"scenarioRunId", "runId"} {
			if raw, ok := typed[key]; ok {
				if runID, ok := raw.(string); ok && strings.TrimSpace(runID) != "" {
					return runID
				}
			}
		}
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			if runID := scenarioRunIDFromValue(typed[key]); runID != "" {
				return runID
			}
		}
	case []any:
		for _, item := range typed {
			if runID := scenarioRunIDFromValue(item); runID != "" {
				return runID
			}
		}
	}
	return ""
}

func effectiveCommandResultStatus(status commandStatusBody) string {
	if strings.TrimSpace(status.ResultStatus) != "" {
		return status.ResultStatus
	}
	if status.ResponseStatus >= 200 && status.ResponseStatus < 300 {
		return "accepted"
	}
	return status.ResultStatus
}

func commandIDForResult(requests []smokeRequest, result smokeResult) string {
	for _, request := range requests {
		if request.Sequence == result.Sequence {
			return request.Payload["commandId"]
		}
	}
	return ""
}

func effectiveOwnOrderFilledQuantity(order ownOrderBody) string {
	if strings.TrimSpace(order.FilledQuantityUnits) != "" {
		return order.FilledQuantityUnits
	}
	quantity := parsePositiveInt(order.QuantityUnits)
	remaining := parsePositiveInt(order.RemainingQuantityUnits)
	if quantity == 0 && remaining == 0 {
		return ""
	}
	filled := quantity - remaining
	if filled < 0 {
		filled = 0
	}
	return fmt.Sprint(filled)
}

func normalizedExecutionID(executionID string) string {
	executionID = strings.TrimSpace(executionID)
	executionID = strings.TrimSuffix(executionID, "-buy")
	executionID = strings.TrimSuffix(executionID, "-sell")
	return executionID
}
