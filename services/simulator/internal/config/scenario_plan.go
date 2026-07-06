package config

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

const apiV1SubmitRoute = "/api/v1/orders/submit"

type ScenarioPlan struct {
	PathID                string                  `json:"pathId"`
	Name                  string                  `json:"name"`
	ScenarioRunID         string                  `json:"scenarioRunId"`
	Seed                  int64                   `json:"seed"`
	Steps                 []ScenarioPlanStep      `json:"steps"`
	ExpectedEvents        []string                `json:"expectedEvents"`
	ExpectedEventTimeline []ExpectedTimelineEvent `json:"expectedEventTimeline"`
	ExpectedFinalStates   map[string]string       `json:"expectedFinalStates"`
	Invariants            []string                `json:"invariants"`
	ReplayAssertions      []string                `json:"replayAssertions"`
	IdempotencyAssertions []IdempotencyAssertion  `json:"idempotencyAssertions"`
}

type ScenarioPlanStep struct {
	Sequence      int               `json:"sequence"`
	Command       string            `json:"command"`
	Description   string            `json:"description,omitempty"`
	APIExecutable bool              `json:"apiExecutable"`
	Route         string            `json:"route,omitempty"`
	Payload       map[string]string `json:"payload,omitempty"`
}

func CompileScenarioPlan(scenario ScenarioFile, scenarioRunID string, start time.Time) (ScenarioPlan, error) {
	if err := ValidateScenarioFile(scenario); err != nil {
		return ScenarioPlan{}, err
	}
	runID := strings.TrimSpace(scenarioRunID)
	if runID == "" {
		runID = strings.ToLower(scenario.PathID)
	}
	accounts := scenarioAccountParticipants(scenario)
	plan := ScenarioPlan{
		PathID:                scenario.PathID,
		Name:                  scenario.Name,
		ScenarioRunID:         runID,
		Seed:                  scenario.Seed,
		Steps:                 make([]ScenarioPlanStep, 0, len(scenario.Steps)),
		ExpectedEvents:        append([]string(nil), scenario.ExpectedEvents...),
		ExpectedEventTimeline: append([]ExpectedTimelineEvent(nil), scenario.ExpectedEventTimeline.Events...),
		ExpectedFinalStates:   copyStringMap(scenario.ExpectedFinalStates),
		Invariants:            append([]string(nil), scenario.Invariants...),
		ReplayAssertions:      append([]string(nil), scenario.ReplayAssertions...),
		IdempotencyAssertions: append([]IdempotencyAssertion(nil), scenario.IdempotencyAssertions...),
	}
	for _, step := range scenario.Steps {
		compiled := ScenarioPlanStep{
			Sequence:    step.Sequence,
			Command:     step.Command,
			Description: step.Description,
		}
		if step.Command == "SubmitOrder" {
			payload, err := compileSubmitPayload(scenario, step, runID, start, accounts)
			if err != nil {
				return ScenarioPlan{}, err
			}
			compiled.APIExecutable = true
			compiled.Route = apiV1SubmitRoute
			compiled.Payload = payload
		}
		plan.Steps = append(plan.Steps, compiled)
	}
	return plan, nil
}

func compileSubmitPayload(
	scenario ScenarioFile,
	step ScenarioStep,
	scenarioRunID string,
	start time.Time,
	accounts map[string]string,
) (map[string]string, error) {
	accountID := scenarioPayloadString(step.Payload, "accountId")
	participantID := accounts[accountID]
	if participantID == "" {
		return nil, fmt.Errorf("steps[%d].payload.accountId %s has no referenceData account participant", step.Sequence-1, accountID)
	}
	orderID := scenarioStepID(scenario.PathID, "ord", step.Sequence)
	traceID := scenarioStepID(scenario.PathID, "trace", step.Sequence)
	commandID := scenarioStepID(scenario.PathID, "cmd", step.Sequence)
	payload := map[string]string{
		"commandId":      commandID,
		"traceId":        traceID,
		"causationId":    scenarioStepID(scenario.PathID, "cause", maxInt(1, step.Sequence-1)),
		"correlationId":  scenarioStepID(scenario.PathID, "corr", 1),
		"actorId":        accountID,
		"actorType":      "BOT",
		"runId":          scenarioRunID,
		"runKind":        "scenario",
		"scenarioId":     scenario.PathID,
		"scenarioRunId":  scenarioRunID,
		"seed":           strconv.FormatInt(scenario.Seed, 10),
		"venueSessionId": scenario.PathID,
		"occurredAt":     start.Add(time.Duration(step.Sequence) * time.Second).UTC().Format(time.RFC3339),
		"orderId":        orderID,
		"clientOrderId":  orderID,
		"instrumentId":   scenarioPayloadString(step.Payload, "instrumentId"),
		"participantId":  participantID,
		"accountId":      accountID,
		"side":           scenarioPayloadString(step.Payload, "side"),
		"orderType":      executableOrderType(scenarioPayloadString(step.Payload, "orderType")),
		"quantityUnits":  scenarioQuantityUnits(step.Payload),
		"limitPrice":     executableLimitPrice(scenarioPayloadString(step.Payload, "limitPrice")),
		"currency":       "USD",
		"timeInForce":    "DAY",
	}
	for key, value := range payload {
		if strings.TrimSpace(value) == "" {
			return nil, fmt.Errorf("steps[%d] compiled submit payload missing %s", step.Sequence-1, key)
		}
	}
	return payload, nil
}

func scenarioAccountParticipants(scenario ScenarioFile) map[string]string {
	accounts := make(map[string]string, len(scenario.Preconditions.ReferenceData.Accounts))
	for _, account := range scenario.Preconditions.ReferenceData.Accounts {
		accounts[account.AccountID] = account.ParticipantID
	}
	return accounts
}

func scenarioPayloadString(payload map[string]interface{}, key string) string {
	value, ok := payload[key]
	if !ok || value == nil {
		return ""
	}
	switch typed := value.(type) {
	case string:
		return typed
	case int:
		return strconv.Itoa(typed)
	case int64:
		return strconv.FormatInt(typed, 10)
	case float64:
		return strconv.FormatInt(int64(typed), 10)
	default:
		return fmt.Sprint(typed)
	}
}

func scenarioQuantityUnits(payload map[string]interface{}) string {
	if value := scenarioPayloadString(payload, "quantityUnits"); value != "" {
		return value
	}
	return scenarioPayloadString(payload, "quantity")
}

func executableOrderType(orderType string) string {
	if strings.EqualFold(orderType, "LIMIT_HIDDEN") {
		return "LIMIT"
	}
	return strings.ToUpper(orderType)
}

func executableLimitPrice(limitPrice string) string {
	if strings.EqualFold(limitPrice, "MIDPOINT") {
		return "150000000000"
	}
	return limitPrice
}

func scenarioStepID(pathID, prefix string, sequence int) string {
	return fmt.Sprintf("%s-%s-%03d", strings.ToLower(pathID), prefix, sequence)
}

func copyStringMap(values map[string]string) map[string]string {
	if len(values) == 0 {
		return nil
	}
	out := make(map[string]string, len(values))
	for key, value := range values {
		out[key] = value
	}
	return out
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
