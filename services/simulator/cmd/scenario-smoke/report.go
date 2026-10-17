package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	scenarioconfig "github.com/dills122/reef/services/simulator/internal/config"
)

type smokeReport struct {
	Mode               string                `json:"mode"`
	Pass               bool                  `json:"pass"`
	PathID             string                `json:"pathId"`
	ScenarioRunID      string                `json:"scenarioRunId"`
	Seed               int64                 `json:"seed"`
	BaseURL            string                `json:"baseUrl,omitempty"`
	SeedRequests       []smokeRequest        `json:"seedRequests,omitempty"`
	Requests           []smokeRequest        `json:"requests"`
	Results            []smokeResult         `json:"results,omitempty"`
	Commands           []assertionCommand    `json:"commands,omitempty"`
	Reads              []assertionRead       `json:"reads,omitempty"`
	Assertions         []scenarioAssertion   `json:"assertions,omitempty"`
	Failures           []scenarioFailure     `json:"failures,omitempty"`
	ProjectionLag      []projectionLag       `json:"projectionLag,omitempty"`
	VisibilityTimeline *p1VisibilityTimeline `json:"visibilityTimeline,omitempty"`
	ReplayChecksum     map[string]any        `json:"replayChecksum,omitempty"`
	ArtifactPaths      []string              `json:"artifactPaths,omitempty"`
	Errors             []string              `json:"errors,omitempty"`
}

type smokeRequest struct {
	Sequence int               `json:"sequence,omitempty"`
	Command  string            `json:"command,omitempty"`
	Method   string            `json:"method"`
	Path     string            `json:"path"`
	URL      string            `json:"url,omitempty"`
	Headers  map[string]string `json:"headers,omitempty"`
	Payload  map[string]string `json:"payload"`
}

type smokeResult struct {
	Sequence          int    `json:"sequence,omitempty"`
	Command           string `json:"command,omitempty"`
	Path              string `json:"path"`
	StatusCode        int    `json:"statusCode"`
	Body              string `json:"body,omitempty"`
	CommandStatusCode int    `json:"commandStatusCode,omitempty"`
	CommandStatusBody string `json:"commandStatusBody,omitempty"`
	Error             string `json:"error,omitempty"`
}

type readyzReport struct {
	Pipeline struct {
		CommandStatusSources []string `json:"commandStatusSources"`
	} `json:"pipeline"`
	Dependencies struct {
		CommandStatusLookup bool `json:"commandStatusLookup"`
	} `json:"dependencies"`
}

type assertionCommand struct {
	Sequence     int    `json:"sequence"`
	Command      string `json:"command"`
	CommandID    string `json:"commandId"`
	Route        string `json:"route"`
	Status       string `json:"status"`
	ResultStatus string `json:"resultStatus,omitempty"`
	Source       string `json:"source,omitempty"`
	StatusCode   int    `json:"statusCode"`
	Body         string `json:"body,omitempty"`
}

type assertionRead struct {
	Endpoint       string            `json:"endpoint"`
	Filters        map[string]string `json:"filters,omitempty"`
	SourceType     string            `json:"sourceType,omitempty"`
	FreshnessModel string            `json:"freshnessModel,omitempty"`
	StatusCode     int               `json:"statusCode"`
	Body           string            `json:"body,omitempty"`
}

type scenarioAssertion struct {
	ID          string `json:"id"`
	Status      string `json:"status"`
	Expected    string `json:"expected,omitempty"`
	Observed    string `json:"observed,omitempty"`
	ProofSource string `json:"proofSource"`
}

type scenarioFailure struct {
	AssertionID string `json:"assertionId"`
	Category    string `json:"category"`
	Message     string `json:"message"`
	ProofSource string `json:"proofSource"`
}

type projectionLag struct {
	Projection string `json:"projection"`
	Lag        string `json:"lag,omitempty"`
	Watermark  string `json:"watermark,omitempty"`
	MeasuredAt string `json:"measuredAt,omitempty"`
}

type p1VisibilityTimeline struct {
	ScenarioID                      string               `json:"scenarioId"`
	ScenarioRunID                   string               `json:"scenarioRunId,omitempty"`
	PublicDepthHiddenRestingExposed bool                 `json:"publicDepthHiddenRestingExposed"`
	PublicDepthChecks               []p1PublicDepthCheck `json:"publicDepthChecks"`
}

type p1PublicDepthCheck struct {
	Phase                        string `json:"phase"`
	InstrumentID                 string `json:"instrumentId"`
	Price                        string `json:"price"`
	HiddenRestingQuantityVisible bool   `json:"hiddenRestingQuantityVisible"`
	StatusCode                   int    `json:"statusCode,omitempty"`
	Observed                     string `json:"observed,omitempty"`
	CheckedAt                    string `json:"checkedAt,omitempty"`
	Error                        string `json:"error,omitempty"`
}

type commandStatusBody struct {
	CommandID           string `json:"commandId"`
	Status              string `json:"status"`
	ResultStatus        string `json:"resultStatus"`
	ResponseStatus      int    `json:"responseStatus"`
	Source              string `json:"source"`
	CommandStream       string `json:"commandStream"`
	EventStream         string `json:"eventStream"`
	ResponsePayloadJSON string `json:"responsePayloadJson"`
	ResultPayloadJSON   string `json:"resultPayloadJson"`
}

type ownOrdersBody struct {
	Meta struct {
		Source    string `json:"source"`
		Freshness string `json:"freshness"`
	} `json:"meta"`
	Orders []ownOrderBody `json:"orders"`
}

type ownOrderBody struct {
	OrderID                string `json:"orderId"`
	QuantityUnits          string `json:"quantityUnits"`
	Status                 string `json:"status"`
	FilledQuantityUnits    string `json:"filledQuantityUnits"`
	RemainingQuantityUnits string `json:"remainingQuantityUnits"`
}

type ownExecutionsBody struct {
	Meta struct {
		Source    string `json:"source"`
		Freshness string `json:"freshness"`
	} `json:"meta"`
	Fills []ownExecutionBody `json:"fills"`
}

type ownExecutionBody struct {
	ExecutionID    string `json:"executionId"`
	OrderID        string `json:"orderId"`
	InstrumentID   string `json:"instrumentId"`
	Side           string `json:"side"`
	QuantityUnits  string `json:"quantityUnits"`
	ExecutionPrice string `json:"executionPrice"`
	Currency       string `json:"currency"`
	OccurredAt     string `json:"occurredAt"`
}

func attachReplayToExistingReport(cfg config, stdout io.Writer) error {
	body, err := os.ReadFile(cfg.attachReplayToReportPath)
	if err != nil {
		return fmt.Errorf("read report for replay attachment: %w", err)
	}
	var report smokeReport
	if err := json.Unmarshal(body, &report); err != nil {
		return fmt.Errorf("parse report for replay attachment: %w", err)
	}
	removeReplayEvidence(&report)
	attachReplayChecksumEvidence(cfg, &report)
	report.Pass = len(report.Errors) == 0 && len(report.Failures) == 0
	redactSensitiveReportHeaders(&report)
	encoded, err := encodeReport(report, cfg.pretty)
	if err != nil {
		return fmt.Errorf("write smoke json: %w", err)
	}
	outPath := cfg.reportOut
	if outPath == "" {
		outPath = cfg.attachReplayToReportPath
	}
	if err := os.WriteFile(outPath, encoded, 0o644); err != nil {
		return fmt.Errorf("write report-out: %w", err)
	}
	if _, err := stdout.Write(encoded); err != nil {
		return fmt.Errorf("write smoke json: %w", err)
	}
	if !report.Pass {
		return errors.New("replay attachment failed")
	}
	return nil
}

func removeReplayEvidence(report *smokeReport) {
	filteredAssertions := report.Assertions[:0]
	for _, assertion := range report.Assertions {
		if isReplayAssertionID(assertion.ID) {
			continue
		}
		filteredAssertions = append(filteredAssertions, assertion)
	}
	report.Assertions = filteredAssertions
	filteredFailures := report.Failures[:0]
	for _, failure := range report.Failures {
		if isReplayAssertionID(failure.AssertionID) {
			continue
		}
		filteredFailures = append(filteredFailures, failure)
	}
	report.Failures = filteredFailures
	report.ReplayChecksum = nil
	report.ArtifactPaths = nil
}

func isReplayAssertionID(id string) bool {
	return strings.HasPrefix(id, "p1-replay-") ||
		strings.HasPrefix(id, "p2-replay-") ||
		strings.HasPrefix(id, "replay-")
}

func redactSensitiveReportHeaders(report *smokeReport) {
	for i := range report.SeedRequests {
		redactSensitiveHeaders(report.SeedRequests[i].Headers)
	}
	for i := range report.Requests {
		redactSensitiveHeaders(report.Requests[i].Headers)
	}
}

func redactSensitiveHeaders(headers map[string]string) {
	for key := range headers {
		if strings.EqualFold(key, "Authorization") {
			delete(headers, key)
		}
	}
}

func buildReport(cfg config, scenario scenarioconfig.ScenarioFile, plan scenarioconfig.ScenarioPlan) smokeReport {
	mode := "dry-run"
	if cfg.live {
		mode = "live"
	}
	report := smokeReport{
		Mode:          mode,
		Pass:          true,
		PathID:        plan.PathID,
		ScenarioRunID: plan.ScenarioRunID,
		Seed:          plan.Seed,
		BaseURL:       strings.TrimRight(cfg.baseURL, "/"),
		Requests:      executableRequests(cfg, plan),
	}
	if cfg.seedReference {
		report.SeedRequests = seedRequests(cfg, scenario, plan)
	}
	return report
}

func passAssertion(report *smokeReport, id string, expected string, observed string, proofSource string) {
	report.Assertions = append(report.Assertions, scenarioAssertion{
		ID:          id,
		Status:      "pass",
		Expected:    expected,
		Observed:    observed,
		ProofSource: proofSource,
	})
}

func failAssertion(report *smokeReport, id string, category string, expected string, observed string, proofSource string) {
	report.Assertions = append(report.Assertions, scenarioAssertion{
		ID:          id,
		Status:      "fail",
		Expected:    expected,
		Observed:    observed,
		ProofSource: proofSource,
	})
	report.Failures = append(report.Failures, scenarioFailure{
		AssertionID: id,
		Category:    category,
		Message:     fmt.Sprintf("expected %s, observed %s", expected, observed),
		ProofSource: proofSource,
	})
	report.Errors = append(report.Errors, fmt.Sprintf("%s failed", id))
}
