package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"

	scenarioconfig "github.com/dills122/reef/services/simulator/internal/config"
)

const defaultScenarioStart = "2026-03-14T18:00:00Z"

const (
	p1FirstVisibleBuyTradeID  = "trade-p1_golden_hidden_cross_t1-ord-002-p1_golden_hidden_cross_t1-ord-001-1"
	p1SecondVisibleBuyTradeID = "trade-p1_golden_hidden_cross_t1-ord-003-p1_golden_hidden_cross_t1-ord-001-1"
)

type config struct {
	scenarioPath              string
	scenarioRunID             string
	start                     string
	baseURL                   string
	statusBaseURL             string
	readBaseURL               string
	live                      bool
	assertions                bool
	settlementFactsReportPath string
	replayCheckReportPath     string
	attachReplayToReportPath  string
	requireReplayCheck        bool
	runScopedCommandIDs       bool
	requireZeroProjectionLag  bool
	seedReference             bool
	timeout                   time.Duration
	statusTimeout             time.Duration
	reportOut                 string
	pretty                    bool
}

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

type settlementFactsReport struct {
	ScenarioRunID       string                         `json:"scenarioRunId"`
	Obligations         []settlementObligation         `json:"obligations"`
	Allocations         []settlementAllocation         `json:"allocations"`
	Confirmations       []settlementConfirmation       `json:"confirmations"`
	Affirmations        []settlementAffirmation        `json:"affirmations"`
	ClearingSubmissions []settlementClearingSubmission `json:"clearingSubmissions"`
	ClearingAcceptances []settlementClearingAcceptance `json:"clearingAcceptances"`
	ClearingRejections  []settlementClearingRejection  `json:"clearingRejections"`
	Novations           []settlementNovation           `json:"novations"`
	Instructions        []settlementInstruction        `json:"instructions"`
	Attempts            []settlementAttempt            `json:"attempts"`
	LegOutcomes         []settlementLegOutcome         `json:"legOutcomes"`
	LedgerEntries       []settlementLedgerEntry        `json:"ledgerEntries"`
	Settlements         []settlementSettled            `json:"settlements"`
	Breaks              []settlementBreak              `json:"breaks"`
	Repairs             []settlementRepair             `json:"repairs"`
	Resolutions         []settlementResolution         `json:"resolutions"`
}

type settlementObligation struct {
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	TradeID                string `json:"tradeId"`
	PostTradeProfileID     string `json:"postTradeProfileId"`
	State                  string `json:"state"`
}

type settlementAllocation struct {
	SettlementAllocationID string `json:"settlementAllocationId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	TradeID                string `json:"tradeId"`
	BuyOrderID             string `json:"buyOrderId"`
	SellOrderID            string `json:"sellOrderId"`
	State                  string `json:"state"`
}

type settlementConfirmation struct {
	SettlementConfirmationID string `json:"settlementConfirmationId"`
	SettlementAllocationID   string `json:"settlementAllocationId"`
	SettlementObligationID   string `json:"settlementObligationId"`
	ScenarioRunID            string `json:"scenarioRunId"`
	CorrelationID            string `json:"correlationId"`
	CausationID              string `json:"causationId"`
	TradeID                  string `json:"tradeId"`
	State                    string `json:"state"`
}

type settlementAffirmation struct {
	SettlementAffirmationID  string `json:"settlementAffirmationId"`
	SettlementConfirmationID string `json:"settlementConfirmationId"`
	SettlementAllocationID   string `json:"settlementAllocationId"`
	SettlementObligationID   string `json:"settlementObligationId"`
	ScenarioRunID            string `json:"scenarioRunId"`
	CorrelationID            string `json:"correlationId"`
	CausationID              string `json:"causationId"`
	TradeID                  string `json:"tradeId"`
	State                    string `json:"state"`
}

type settlementClearingSubmission struct {
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	SettlementAffirmationID        string `json:"settlementAffirmationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementClearingAcceptance struct {
	SettlementClearingAcceptanceID string `json:"settlementClearingAcceptanceId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementClearingRejection struct {
	SettlementClearingRejectionID  string `json:"settlementClearingRejectionId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	Reason                         string `json:"reason"`
	State                          string `json:"state"`
}

type settlementNovation struct {
	SettlementNovationID           string `json:"settlementNovationId"`
	SettlementClearingAcceptanceID string `json:"settlementClearingAcceptanceId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementInstruction struct {
	SettlementInstructionID string `json:"settlementInstructionId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	State                   string `json:"state"`
}

type settlementAttempt struct {
	SettlementAttemptID     string `json:"settlementAttemptId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	SettlementInstructionID string `json:"settlementInstructionId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	State                   string `json:"state"`
}

type settlementLegOutcome struct {
	SettlementLegOutcomeID string `json:"settlementLegOutcomeId"`
}

type settlementLedgerEntry struct {
	LedgerEntryID string `json:"ledgerEntryId"`
}

type settlementSettled struct {
	SettlementID            string `json:"settlementId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	SettlementInstructionID string `json:"settlementInstructionId"`
	SettlementAttemptID     string `json:"settlementAttemptId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	SettlementState         string `json:"settlementState"`
}

type settlementBreak struct {
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	Reason                 string `json:"reason"`
	State                  string `json:"state"`
}

type settlementRepair struct {
	SettlementRepairID     string `json:"settlementRepairId"`
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	RepairAction           string `json:"repairAction"`
}

type settlementResolution struct {
	SettlementResolutionID string `json:"settlementResolutionId"`
	SettlementObligationID string `json:"settlementObligationId"`
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementRepairID     string `json:"settlementRepairId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	SettlementState        string `json:"settlementState"`
	ExceptionState         string `json:"exceptionState"`
}

type settlementExceptionQueueReport struct {
	ScenarioRunID           string                `json:"scenarioRunId"`
	ExceptionsCount         int                   `json:"exceptionsCount"`
	OpenCount               int                   `json:"openCount"`
	RepairPostedCount       int                   `json:"repairPostedCount"`
	ResolvedCount           int                   `json:"resolvedCount"`
	ClearingRejectedCount   int                   `json:"clearingRejectedCount"`
	SettlementBreakCount    int                   `json:"settlementBreakCount"`
	SettlementExceptionRows []settlementException `json:"exceptions"`
}

type settlementException struct {
	SettlementExceptionID          string `json:"settlementExceptionId"`
	ExceptionType                  string `json:"exceptionType"`
	ExceptionState                 string `json:"exceptionState"`
	State                          string `json:"state"`
	Severity                       string `json:"severity"`
	OwnerRole                      string `json:"ownerRole"`
	ActionRequired                 string `json:"actionRequired"`
	Reason                         string `json:"reason"`
	SettlementObligationID         string `json:"settlementObligationId"`
	TradeID                        string `json:"tradeId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementClearingRejectionID  string `json:"settlementClearingRejectionId"`
	SettlementBreakID              string `json:"settlementBreakId"`
	SettlementRepairID             string `json:"settlementRepairId"`
	SettlementResolutionID         string `json:"settlementResolutionId"`
	RepairAction                   string `json:"repairAction"`
	ActorID                        string `json:"actorId"`
	CorrelationID                  string `json:"correlationId"`
	OpenedAt                       string `json:"openedAt"`
	LastUpdatedAt                  string `json:"lastUpdatedAt"`
	ResolvedAt                     string `json:"resolvedAt"`
}

type tradeTapeBody struct {
	Meta struct {
		Source    string `json:"source"`
		Freshness string `json:"freshness"`
	} `json:"meta"`
	Trades []tradeTapeEntry `json:"trades"`
}

type tradeTapeEntry struct {
	TradeID       string `json:"tradeId"`
	InstrumentID  string `json:"instrumentId"`
	QuantityUnits string `json:"quantityUnits"`
	Price         string `json:"price"`
}

type marketDepthBody struct {
	Depth marketDepthSnapshot `json:"depth"`
}

type marketDepthSnapshot struct {
	ProjectionName string             `json:"projectionName"`
	BidLevels      []marketDepthLevel `json:"bidLevels"`
	AskLevels      []marketDepthLevel `json:"askLevels"`
}

type marketDepthLevel struct {
	Price    string `json:"price"`
	Quantity string `json:"quantity"`
}

type dataAvailabilityBody struct {
	GeneratedAt string                 `json:"generatedAt"`
	Surfaces    []dataSurfaceStatus    `json:"surfaces"`
	Projections []dataProjectionStatus `json:"projections"`
}

type dataSurfaceStatus struct {
	Name                  string `json:"name"`
	Endpoint              string `json:"endpoint"`
	Source                string `json:"source"`
	Freshness             string `json:"freshness"`
	ProjectionName        string `json:"projectionName"`
	Lag                   int64  `json:"lag"`
	LastPartitionSequence int64  `json:"lastPartitionSequence"`
	LastUpdatedAt         string `json:"lastUpdatedAt"`
}

type dataProjectionStatus struct {
	ProjectionName string `json:"projectionName"`
	Role           string `json:"role"`
	Lag            int64  `json:"lag"`
}

func main() {
	if err := run(os.Args[1:], os.Stdout, http.DefaultClient); err != nil {
		fmt.Fprintf(os.Stderr, "scenario-smoke error: %v\n", err)
		os.Exit(1)
	}
}

func run(args []string, stdout io.Writer, client *http.Client) error {
	cfg, err := parseConfig(args)
	if err != nil {
		return err
	}
	if cfg.attachReplayToReportPath != "" {
		return attachReplayToExistingReport(cfg, stdout)
	}
	scenario, err := scenarioconfig.LoadScenarioFile(cfg.scenarioPath)
	if err != nil {
		return err
	}
	start, err := time.Parse(time.RFC3339, cfg.start)
	if err != nil {
		return fmt.Errorf("start must be RFC3339: %w", err)
	}
	plan, err := scenarioconfig.CompileScenarioPlan(scenario, cfg.scenarioRunID, start)
	if err != nil {
		return err
	}
	report := buildReport(cfg, scenario, plan)
	if cfg.live {
		if client == nil {
			client = http.DefaultClient
		}
		if cfg.assertions {
			if err := requireCommandStatusLookup(cfg, client); err != nil {
				report.Errors = append(report.Errors, err.Error())
			}
		}
		if len(report.Errors) == 0 {
			runLive(cfg, client, &report)
		}
		if cfg.assertions && len(report.Errors) == 0 {
			if cfg.settlementFactsReportPath != "" {
				attachSettlementFactArtifactAssertions(cfg, &report)
			} else if report.PathID == "P2_SETTLEMENT_BREAK_REPAIR" {
				attachSettlementFactAPIAssertions(cfg, client, &report)
				attachSettlementExceptionAPIAssertions(cfg, client, &report)
			}
		}
		if cfg.replayCheckReportPath != "" {
			attachReplayChecksumEvidence(cfg, &report)
		} else if cfg.requireReplayCheck {
			prefix := replayAssertionPrefix(&report)
			failAssertion(
				&report,
				prefix+"-checksum-clean",
				"replay_checksum",
				"clean replay check report attached",
				"missing --replay-check-report",
				"--replay-check-report",
			)
		}
		report.Pass = len(report.Errors) == 0
	}
	redactSensitiveReportHeaders(&report)
	body, err := encodeReport(report, cfg.pretty)
	if err != nil {
		return fmt.Errorf("write smoke json: %w", err)
	}
	if cfg.reportOut != "" {
		if err := os.WriteFile(cfg.reportOut, body, 0o644); err != nil {
			return fmt.Errorf("write report-out: %w", err)
		}
	}
	if _, err := stdout.Write(body); err != nil {
		return fmt.Errorf("write smoke json: %w", err)
	}
	if cfg.live && !report.Pass {
		return errors.New("live scenario smoke failed")
	}
	return nil
}

func parseConfig(args []string) (config, error) {
	fs := flag.NewFlagSet("scenario-smoke", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	cfg := config{
		baseURL:       "http://localhost:8080",
		start:         defaultScenarioStart,
		seedReference: true,
		timeout:       5 * time.Second,
		statusTimeout: 5 * time.Second,
	}
	fs.StringVar(&cfg.scenarioPath, "scenario", "", "scenario YAML path")
	fs.StringVar(&cfg.scenarioRunID, "scenario-run-id", "", "scenario run id stamped into compiled payloads")
	fs.StringVar(&cfg.start, "start", cfg.start, "scenario start time, RFC3339")
	fs.StringVar(&cfg.baseURL, "base-url", cfg.baseURL, "platform runtime base URL for live mode")
	fs.StringVar(&cfg.statusBaseURL, "status-base-url", "", "optional command status base URL; defaults to --base-url")
	fs.StringVar(&cfg.readBaseURL, "read-base-url", "", "optional assertion read base URL; defaults to --base-url")
	fs.BoolVar(&cfg.live, "live", false, "send executable scenario requests to base-url")
	fs.BoolVar(&cfg.assertions, "assertions", false, "run first-wave live scenario assertions after command submission")
	fs.StringVar(&cfg.settlementFactsReportPath, "settlement-facts-report", "", "optional P2 settlement assertion JSON artifact")
	fs.StringVar(&cfg.replayCheckReportPath, "replay-check-report", "", "optional JSON output from dev-venue-event-replay-check to attach to assertion report")
	fs.StringVar(&cfg.attachReplayToReportPath, "attach-replay-to-report", "", "attach --replay-check-report to an existing scenario-smoke report without resubmitting live commands")
	fs.BoolVar(&cfg.requireReplayCheck, "require-replay-check", false, "fail assertion report unless --replay-check-report is attached and clean")
	fs.BoolVar(&cfg.runScopedCommandIDs, "run-scoped-command-ids", false, "prefix executable command ids with the scenario run id to avoid retained-stream collisions")
	fs.BoolVar(&cfg.requireZeroProjectionLag, "require-zero-projection-lag", false, "fail P1 assertions unless required read surfaces report zero projection lag")
	fs.BoolVar(&cfg.seedReference, "seed-reference", cfg.seedReference, "seed scenario reference/auth data in live mode")
	fs.DurationVar(&cfg.timeout, "timeout", cfg.timeout, "HTTP request timeout")
	fs.DurationVar(&cfg.statusTimeout, "status-timeout", cfg.statusTimeout, "command status polling timeout")
	fs.StringVar(&cfg.reportOut, "report-out", "", "optional path to write smoke report JSON")
	fs.BoolVar(&cfg.pretty, "pretty", false, "pretty-print JSON")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if cfg.attachReplayToReportPath != "" && cfg.replayCheckReportPath == "" {
		return cfg, errors.New("--attach-replay-to-report requires --replay-check-report")
	}
	if cfg.attachReplayToReportPath == "" && cfg.scenarioPath == "" {
		return cfg, errors.New("missing --scenario")
	}
	if cfg.attachReplayToReportPath == "" && cfg.assertions && !cfg.live {
		return cfg, errors.New("--assertions requires --live")
	}
	if cfg.settlementFactsReportPath != "" && !cfg.assertions {
		return cfg, errors.New("--settlement-facts-report requires --assertions")
	}
	if cfg.attachReplayToReportPath == "" && cfg.replayCheckReportPath != "" && !cfg.assertions {
		return cfg, errors.New("--replay-check-report requires --assertions")
	}
	if cfg.attachReplayToReportPath == "" && cfg.requireReplayCheck && !cfg.assertions {
		return cfg, errors.New("--require-replay-check requires --assertions")
	}
	if cfg.timeout <= 0 {
		return cfg, errors.New("timeout must be > 0")
	}
	if cfg.statusTimeout <= 0 {
		return cfg, errors.New("status-timeout must be > 0")
	}
	if strings.TrimSpace(cfg.statusBaseURL) == "" {
		cfg.statusBaseURL = cfg.baseURL
	}
	if strings.TrimSpace(cfg.readBaseURL) == "" {
		cfg.readBaseURL = cfg.baseURL
	}
	if fs.NArg() > 0 {
		return cfg, fmt.Errorf("unexpected positional argument: %s", fs.Arg(0))
	}
	return cfg, nil
}

func encodeReport(report smokeReport, pretty bool) ([]byte, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	if pretty {
		encoder.SetIndent("", "  ")
	}
	if err := encoder.Encode(report); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
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

func executableRequests(cfg config, plan scenarioconfig.ScenarioPlan) []smokeRequest {
	out := make([]smokeRequest, 0)
	for _, step := range plan.Steps {
		if !step.APIExecutable {
			continue
		}
		payload := copyStringMap(step.Payload)
		if cfg.runScopedCommandIDs {
			payload["commandId"] = runScopedCommandID(plan.ScenarioRunID, payload["commandId"])
		}
		out = append(out, smokeRequest{
			Sequence: step.Sequence,
			Command:  step.Command,
			Method:   http.MethodPost,
			Path:     step.Route,
			URL:      absoluteURL(cfg.baseURL, step.Route),
			Headers: map[string]string{
				"X-Client-Id":      fmt.Sprintf("scenario-smoke-%d", step.Sequence),
				"Idempotency-Key":  payload["commandId"],
				"X-Correlation-Id": payload["traceId"],
				"X-Participant-Id": payload["participantId"],
				"X-Reef-Actor-Id":  payload["actorId"],
			},
			Payload: payload,
		})
	}
	return out
}

func runScopedCommandID(scenarioRunID string, commandID string) string {
	prefix := safeCommandIDPart(scenarioRunID)
	if prefix == "" {
		return commandID
	}
	return prefix + "-" + commandID
}

func safeCommandIDPart(value string) string {
	var builder strings.Builder
	lastWasDash := false
	for _, char := range strings.TrimSpace(value) {
		allowed := (char >= 'a' && char <= 'z') ||
			(char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') ||
			char == '_' ||
			char == '-' ||
			char == '.'
		if allowed {
			builder.WriteRune(char)
			lastWasDash = false
			continue
		}
		if !lastWasDash {
			builder.WriteByte('-')
			lastWasDash = true
		}
	}
	return strings.Trim(builder.String(), "-")
}

func seedRequests(cfg config, scenario scenarioconfig.ScenarioFile, plan scenarioconfig.ScenarioPlan) []smokeRequest {
	headers := map[string]string{
		"X-Reef-Actor-Id":  "scenario-smoke-seed",
		"X-Correlation-Id": plan.ScenarioRunID + "-reference-seed",
	}
	if token := strings.TrimSpace(os.Getenv("ADMIN_API_TOKEN")); token != "" {
		headers["Authorization"] = "Bearer " + token
	}
	out := make([]smokeRequest, 0)
	for _, instrument := range scenario.Preconditions.ReferenceData.Instruments {
		out = append(out, postRequest(cfg.baseURL, "/admin/v1/reference/instruments", headers, map[string]string{
			"instrumentId": instrument.InstrumentID,
			"symbol":       instrument.Symbol,
		}))
	}
	for _, participant := range scenario.Preconditions.ReferenceData.Participants {
		out = append(out, postRequest(cfg.baseURL, "/admin/v1/reference/participants", headers, map[string]string{
			"participantId": participant.ParticipantID,
			"name":          participant.ParticipantID,
		}))
	}
	for _, account := range scenario.Preconditions.ReferenceData.Accounts {
		out = append(out, postRequest(cfg.baseURL, "/admin/v1/reference/accounts", headers, map[string]string{
			"accountId":     account.AccountID,
			"participantId": account.ParticipantID,
		}))
	}
	out = append(out, postRequest(cfg.baseURL, "/admin/v1/auth/roles", headers, map[string]string{
		"roleId":      "order_trader",
		"permissions": "order.submit,order.cancel,order.modify",
	}))
	for _, actorID := range executableActorIDs(plan) {
		out = append(out, postRequest(cfg.baseURL, "/admin/v1/auth/actor-roles", headers, map[string]string{
			"actorId": actorID,
			"roleId":  "order_trader",
		}))
	}
	return out
}

func postRequest(baseURL string, path string, headers map[string]string, payload map[string]string) smokeRequest {
	return smokeRequest{
		Method:  http.MethodPost,
		Path:    path,
		URL:     absoluteURL(baseURL, path),
		Headers: copyStringMap(headers),
		Payload: copyStringMap(payload),
	}
}

func runLive(cfg config, client *http.Client, report *smokeReport) {
	liveClient := clientWithTimeout(client, cfg.timeout)
	if cfg.seedReference {
		for _, request := range report.SeedRequests {
			result := executeRequest(liveClient, request)
			report.Results = append(report.Results, result)
			if result.Error != "" || result.StatusCode < 200 || result.StatusCode >= 300 {
				report.Errors = append(report.Errors, fmt.Sprintf("seed %s failed", request.Path))
				return
			}
		}
	}
	for _, request := range report.Requests {
		result := executeRequest(liveClient, request)
		if result.Error == "" && result.StatusCode >= 200 && result.StatusCode < 300 {
			statusCode, statusBody, err := waitCommandStatus(liveClient, cfg, request.Payload["commandId"], request.Headers)
			result.CommandStatusCode = statusCode
			result.CommandStatusBody = statusBody
			if err != nil {
				result.Error = err.Error()
			}
		}
		report.Results = append(report.Results, result)
		if result.Error != "" || result.StatusCode < 200 || result.StatusCode >= 300 || result.CommandStatusCode != 200 {
			report.Errors = append(report.Errors, fmt.Sprintf("command %s failed", request.Payload["commandId"]))
		}
		if cfg.assertions && report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" && request.Sequence == 1 &&
			result.Error == "" && result.StatusCode >= 200 && result.StatusCode < 300 && result.CommandStatusCode == 200 {
			captureP1HiddenDepthTimeline(cfg, liveClient, report)
		}
	}
	if cfg.assertions && len(report.Errors) == 0 {
		runAssertions(cfg, liveClient, report)
	}
}

func clientWithTimeout(client *http.Client, timeout time.Duration) *http.Client {
	if client == nil {
		client = http.DefaultClient
	}
	copy := *client
	copy.Timeout = timeout
	return &copy
}

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

func runAssertions(cfg config, client *http.Client, report *smokeReport) {
	for _, result := range report.Results {
		if result.Command == "" {
			continue
		}
		status := parseCommandStatus(result.CommandStatusBody)
		resultStatus := effectiveCommandResultStatus(status)
		command := assertionCommand{
			Sequence:     result.Sequence,
			Command:      result.Command,
			CommandID:    status.CommandID,
			Route:        result.Path,
			Status:       status.Status,
			ResultStatus: resultStatus,
			Source:       status.Source,
			StatusCode:   result.CommandStatusCode,
			Body:         result.CommandStatusBody,
		}
		if command.CommandID == "" {
			command.CommandID = commandIDForResult(report.Requests, result)
		}
		report.Commands = append(report.Commands, command)
		assertionID := fmt.Sprintf("command-%03d-completed", result.Sequence)
		if strings.EqualFold(status.Status, "COMPLETED") && strings.EqualFold(resultStatus, "accepted") {
			passAssertion(report, assertionID, "COMPLETED/accepted", status.Status+"/"+resultStatus, "GET /api/v1/commands/{commandId}")
		} else {
			failAssertion(report, assertionID, "command_completion", "COMPLETED/accepted", status.Status+"/"+resultStatus, "GET /api/v1/commands/{commandId}")
		}
		assertCommandStatusScenarioScope(report, result.Sequence, status)
	}
	if report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		assertP1HistoricalVisibilityTimeline(report)
		runP1OwnOrderAssertions(cfg, client, report)
		runP1MarketDataAssertions(cfg, client, report)
	}
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

func attachSettlementFactArtifactAssertions(cfg config, report *smokeReport) {
	body, err := os.ReadFile(cfg.settlementFactsReportPath)
	read := assertionRead{
		Endpoint:       cfg.settlementFactsReportPath,
		SourceType:     "settlement assertion artifact",
		FreshnessModel: "artifact snapshot",
		Body:           strings.TrimSpace(string(body)),
	}
	if err != nil {
		read.StatusCode = 0
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "readable settlement facts report", err.Error(), cfg.settlementFactsReportPath)
		return
	}
	read.StatusCode = 200
	report.Reads = append(report.Reads, read)
	var facts settlementFactsReport
	if err := json.Unmarshal(body, &facts); err != nil {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "valid settlement facts JSON", err.Error(), cfg.settlementFactsReportPath)
		return
	}
	if report.PathID != "P2_SETTLEMENT_BREAK_REPAIR" {
		passAssertion(report, "settlement-facts-attached", "settlement facts artifact attached", "attached", cfg.settlementFactsReportPath)
		return
	}
	assertP2SettlementFacts(report, facts, cfg.settlementFactsReportPath)
}

func attachSettlementFactAPIAssertions(cfg config, client *http.Client, report *smokeReport) {
	endpoint := "/api/v1/settlement/facts/" + url.PathEscape(report.ScenarioRunID)
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint), endpoint, map[string]string{"scenarioRunId": report.ScenarioRunID})
	read.SourceType = "settlement facts"
	read.FreshnessModel = "append-only settlement fact store"
	if err != nil {
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "readable settlement facts API", err.Error(), endpoint)
		return
	}
	report.Reads = append(report.Reads, read)
	if read.StatusCode != 200 {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "HTTP 200 settlement facts API", fmt.Sprint(read.StatusCode), endpoint)
		return
	}
	var facts settlementFactsReport
	if err := json.Unmarshal(body, &facts); err != nil {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "valid settlement facts JSON", err.Error(), endpoint)
		return
	}
	assertP2SettlementFacts(report, facts, endpoint)
}

func attachSettlementExceptionAPIAssertions(cfg config, client *http.Client, report *smokeReport) {
	endpoint := "/api/v1/settlement/exceptions/" + url.PathEscape(report.ScenarioRunID)
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint), endpoint, map[string]string{"scenarioRunId": report.ScenarioRunID})
	read.SourceType = "settlement exception queue"
	read.FreshnessModel = "rebuildable exception queue over clearing rejections and settlement breaks"
	if err != nil {
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "readable settlement exception queue API", err.Error(), endpoint)
		return
	}
	report.Reads = append(report.Reads, read)
	if read.StatusCode != 200 {
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "HTTP 200 settlement exception queue API", fmt.Sprint(read.StatusCode), endpoint)
		return
	}
	var queue settlementExceptionQueueReport
	if err := json.Unmarshal(body, &queue); err != nil {
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "valid settlement exception queue JSON", err.Error(), endpoint)
		return
	}
	assertP2SettlementExceptionQueue(report, queue, endpoint)
}

func assertP2SettlementExceptionQueue(report *smokeReport, queue settlementExceptionQueueReport, proofSource string) {
	if queue.ScenarioRunID == report.ScenarioRunID {
		passAssertion(report, "p2-exception-queue-scenario-run", report.ScenarioRunID, queue.ScenarioRunID, proofSource)
	} else {
		failAssertion(report, "p2-exception-queue-scenario-run", "settlement_exception_scope", report.ScenarioRunID, queue.ScenarioRunID, proofSource)
	}
	if queue.ExceptionsCount == len(queue.SettlementExceptionRows) {
		passAssertion(report, "p2-exception-queue-count-matches", fmt.Sprint(queue.ExceptionsCount), fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
	} else {
		failAssertion(report, "p2-exception-queue-count-matches", "settlement_exception_count", fmt.Sprint(queue.ExceptionsCount), fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
	}
	if queue.ExceptionsCount == 0 {
		passAssertion(report, "p2-exception-queue-empty", "0 exceptions", "0 exceptions", proofSource)
		return
	}
	assertCount(report, "p2-one-settlement-break-exception", "settlement_exception_count", queue.SettlementBreakCount, 1, proofSource)
	assertCount(report, "p2-zero-clearing-rejected-exceptions", "settlement_exception_count", queue.ClearingRejectedCount, 0, proofSource)
	assertCount(report, "p2-zero-open-exceptions", "settlement_exception_count", queue.OpenCount, 0, proofSource)
	assertCount(report, "p2-zero-repair-posted-exceptions", "settlement_exception_count", queue.RepairPostedCount, 0, proofSource)
	assertCount(report, "p2-one-resolved-exception", "settlement_exception_count", queue.ResolvedCount, 1, proofSource)
	if len(queue.SettlementExceptionRows) != 1 {
		failAssertion(report, "p2-exception-queue-single-row", "settlement_exception_count", "1", fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
		return
	}
	exception := queue.SettlementExceptionRows[0]
	assertSettlementExceptionField(report, "p2-exception-type-settlement-break", exception.ExceptionType, "SETTLEMENT_BREAK", proofSource)
	assertSettlementExceptionField(report, "p2-exception-state-resolved", exception.ExceptionState, "RESOLVED", proofSource)
	assertSettlementExceptionField(report, "p2-exception-owner-settlement-ops", exception.OwnerRole, "SETTLEMENT_OPS", proofSource)
	assertSettlementExceptionField(report, "p2-exception-action-none-after-resolution", exception.ActionRequired, "NONE", proofSource)
	expectedRepairAction := expectedSettlementRepairAction(exception.Reason)
	if expectedRepairAction == "" {
		failAssertion(report, "p2-exception-repair-action-matches-reason", "settlement_exception_repair", "known settlement break reason", exception.Reason, proofSource)
	} else {
		assertSettlementExceptionField(report, "p2-exception-repair-action-matches-reason", exception.RepairAction, expectedRepairAction, proofSource)
	}
	if exception.SettlementBreakID != "" && exception.SettlementRepairID != "" && exception.SettlementResolutionID != "" &&
		exception.ResolvedAt != "" && exception.ActorID != "" && exception.CorrelationID != "" {
		passAssertion(report, "p2-exception-resolution-linked", "break, repair, resolution, actor, correlation, resolvedAt", "all links present", proofSource)
	} else {
		failAssertion(report, "p2-exception-resolution-linked", "settlement_exception_causation", "break, repair, resolution, actor, correlation, resolvedAt", settlementExceptionObserved(exception), proofSource)
	}
}

func assertSettlementExceptionField(report *smokeReport, id string, observed string, expected string, proofSource string) {
	if observed == expected {
		passAssertion(report, id, expected, observed, proofSource)
		return
	}
	failAssertion(report, id, "settlement_exception_field", expected, observed, proofSource)
}

func expectedSettlementRepairAction(reason string) string {
	switch reason {
	case "CASH_LEG_FAILED":
		return "POST_CASH_LEG_REPAIR"
	case "SECURITY_LEG_FAILED":
		return "POST_SECURITY_LEG_REPAIR"
	default:
		return ""
	}
}

func settlementBreakReasonAssertionID(reason string) string {
	switch reason {
	case "CASH_LEG_FAILED":
		return "p2-break-reason-cash-leg-failed"
	case "SECURITY_LEG_FAILED":
		return "p2-break-reason-security-leg-failed"
	default:
		return "p2-break-reason-known"
	}
}

func settlementExceptionObserved(exception settlementException) string {
	return fmt.Sprintf(
		"break=%s repair=%s resolution=%s actor=%s correlation=%s resolvedAt=%s",
		exception.SettlementBreakID,
		exception.SettlementRepairID,
		exception.SettlementResolutionID,
		exception.ActorID,
		exception.CorrelationID,
		exception.ResolvedAt,
	)
}

func assertP2SettlementFacts(report *smokeReport, facts settlementFactsReport, proofSource string) {
	if facts.ScenarioRunID == report.ScenarioRunID {
		passAssertion(report, "p2-settlement-scenario-run", report.ScenarioRunID, facts.ScenarioRunID, proofSource)
	} else {
		failAssertion(report, "p2-settlement-scenario-run", "settlement_fact_scope", report.ScenarioRunID, facts.ScenarioRunID, proofSource)
	}
	if hasPostTradeSettlementChainFacts(facts) {
		assertPostTradeSettlementChain(report, facts, proofSource)
		if settlementFactsHaveCausation(facts) {
			passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
		} else {
			failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
		}
		if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
			passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
		} else {
			failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
		}
		return
	}
	if hasOpsRealisticPendingFacts(facts) {
		assertOpsRealisticPendingSettlementFacts(report, facts, proofSource)
		if settlementFactsHaveCausation(facts) {
			passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
		} else {
			failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
		}
		if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
			passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
		} else {
			failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
		}
		return
	}
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-one-settlement-break", "settlement_fact_count", len(facts.Breaks), 1, proofSource)
	assertCount(report, "p2-one-manual-repair", "settlement_fact_count", len(facts.Repairs), 1, proofSource)
	assertCount(report, "p2-one-settlement-resolution", "settlement_fact_count", len(facts.Resolutions), 1, proofSource)
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && facts.Obligations[0].State == "OBLIGATION_CREATED" {
		passAssertion(report, "p2-obligation-state-created", "OBLIGATION_CREATED", facts.Obligations[0].State, proofSource)
	} else {
		failAssertion(report, "p2-obligation-state-created", "settlement_fact_state", "OBLIGATION_CREATED", obligationStates(facts.Obligations), proofSource)
	}
	expectedRepairAction := ""
	if len(facts.Breaks) == 1 {
		expectedRepairAction = expectedSettlementRepairAction(facts.Breaks[0].Reason)
	}
	if len(facts.Breaks) == 1 && expectedRepairAction != "" {
		passAssertion(report, settlementBreakReasonAssertionID(facts.Breaks[0].Reason), facts.Breaks[0].Reason, facts.Breaks[0].Reason, proofSource)
	} else {
		failAssertion(report, "p2-break-reason-known", "settlement_fact_reason", "CASH_LEG_FAILED or SECURITY_LEG_FAILED", breakReasons(facts.Breaks), proofSource)
	}
	if len(facts.Breaks) == 1 && facts.Breaks[0].State == "BROKEN" {
		passAssertion(report, "p2-break-state-broken", "BROKEN", facts.Breaks[0].State, proofSource)
	} else {
		failAssertion(report, "p2-break-state-broken", "settlement_fact_state", "BROKEN", breakStates(facts.Breaks), proofSource)
	}
	if len(facts.Repairs) == 1 && expectedRepairAction != "" && facts.Repairs[0].RepairAction == expectedRepairAction {
		passAssertion(report, "p2-repair-action-posted", expectedRepairAction, facts.Repairs[0].RepairAction, proofSource)
	} else {
		failAssertion(report, "p2-repair-action-posted", "settlement_fact_repair", expectedRepairAction, repairActions(facts.Repairs), proofSource)
	}
	if len(facts.Resolutions) == 1 && facts.Resolutions[0].SettlementState == "RESOLVED" {
		passAssertion(report, "p2-settlement-final-resolved", "RESOLVED", facts.Resolutions[0].SettlementState, proofSource)
	} else {
		failAssertion(report, "p2-settlement-final-resolved", "settlement_fact_state", "RESOLVED", settlementStates(facts.Resolutions), proofSource)
	}
	if len(facts.Resolutions) == 1 && facts.Resolutions[0].ExceptionState == "RESOLVED" {
		passAssertion(report, "p2-exception-final-resolved", "RESOLVED", facts.Resolutions[0].ExceptionState, proofSource)
	} else {
		failAssertion(report, "p2-exception-final-resolved", "settlement_fact_state", "RESOLVED", exceptionStates(facts.Resolutions), proofSource)
	}
	if p2ResolutionReferencesRepair(facts) {
		passAssertion(report, "p2-no-direct-resolution-without-repair", "resolution references repair", "resolution references repair", proofSource)
	} else {
		failAssertion(report, "p2-no-direct-resolution-without-repair", "settlement_fact_causation", "resolution references repair", "missing repair linkage", proofSource)
	}
	if p2SettlementChainLinked(facts) {
		passAssertion(report, "p2-settlement-chain-linked", "obligation -> break -> repair -> resolution", "all fact references linked", proofSource)
	} else {
		failAssertion(report, "p2-settlement-chain-linked", "settlement_fact_causation", "obligation -> break -> repair -> resolution", p2SettlementChainObserved(facts), proofSource)
	}
	if settlementFactsHaveCausation(facts) {
		passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
	} else {
		failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
	}
	if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
		passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
	} else {
		failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
	}
}

func hasPostTradeSettlementChainFacts(facts settlementFactsReport) bool {
	return len(facts.Allocations) > 0 || len(facts.Confirmations) > 0 || len(facts.Affirmations) > 0 ||
		len(facts.ClearingSubmissions) > 0 || len(facts.ClearingAcceptances) > 0 || len(facts.Novations) > 0 ||
		len(facts.Settlements) > 0
}

func hasOpsRealisticPendingFacts(facts settlementFactsReport) bool {
	return len(facts.Obligations) == 1 &&
		len(facts.Allocations) == 0 &&
		len(facts.Confirmations) == 0 &&
		len(facts.Affirmations) == 0 &&
		len(facts.ClearingSubmissions) == 0 &&
		len(facts.ClearingAcceptances) == 0 &&
		len(facts.ClearingRejections) == 0 &&
		len(facts.Novations) == 0 &&
		len(facts.Instructions) == 0 &&
		len(facts.Attempts) == 0 &&
		len(facts.LegOutcomes) == 0 &&
		len(facts.LedgerEntries) == 0 &&
		len(facts.Settlements) == 0 &&
		len(facts.Breaks) == 0 &&
		len(facts.Repairs) == 0 &&
		len(facts.Resolutions) == 0
}

func assertOpsRealisticPendingSettlementFacts(report *smokeReport, facts settlementFactsReport, proofSource string) {
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-ops-pending-zero-allocations", "settlement_fact_count", len(facts.Allocations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-confirmations", "settlement_fact_count", len(facts.Confirmations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-affirmations", "settlement_fact_count", len(facts.Affirmations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-clearing-submissions", "settlement_fact_count", len(facts.ClearingSubmissions), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-clearing-acceptances", "settlement_fact_count", len(facts.ClearingAcceptances), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-novations", "settlement_fact_count", len(facts.Novations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-instructions", "settlement_fact_count", len(facts.Instructions), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-attempts", "settlement_fact_count", len(facts.Attempts), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-leg-outcomes", "settlement_fact_count", len(facts.LegOutcomes), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-ledger-entries", "settlement_fact_count", len(facts.LedgerEntries), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-settlements", "settlement_fact_count", len(facts.Settlements), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-breaks", "settlement_fact_count", len(facts.Breaks), 0, proofSource)
	if len(facts.Obligations) == 1 && facts.Obligations[0].State == "OBLIGATION_CREATED" {
		passAssertion(report, "p2-ops-pending-obligation-created", "OBLIGATION_CREATED", facts.Obligations[0].State, proofSource)
	} else {
		failAssertion(report, "p2-ops-pending-obligation-created", "settlement_fact_state", "OBLIGATION_CREATED", obligationStates(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && facts.Obligations[0].PostTradeProfileID == "ops-realistic-v1" {
		passAssertion(report, "p2-ops-pending-profile", "ops-realistic-v1", facts.Obligations[0].PostTradeProfileID, proofSource)
	} else {
		failAssertion(report, "p2-ops-pending-profile", "settlement_fact_profile", "ops-realistic-v1", obligationProfiles(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
}

func assertPostTradeSettlementChain(report *smokeReport, facts settlementFactsReport, proofSource string) {
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-one-allocation-proposed", "settlement_fact_count", len(facts.Allocations), 1, proofSource)
	assertCount(report, "p2-one-confirmation-generated", "settlement_fact_count", len(facts.Confirmations), 1, proofSource)
	assertCount(report, "p2-one-affirmation-accepted", "settlement_fact_count", len(facts.Affirmations), 1, proofSource)
	assertCount(report, "p2-one-clearing-submitted", "settlement_fact_count", len(facts.ClearingSubmissions), 1, proofSource)
	assertCount(report, "p2-one-clearing-accepted", "settlement_fact_count", len(facts.ClearingAcceptances), 1, proofSource)
	assertCount(report, "p2-zero-clearing-rejections", "settlement_fact_count", len(facts.ClearingRejections), 0, proofSource)
	assertCount(report, "p2-one-novation-recorded", "settlement_fact_count", len(facts.Novations), 1, proofSource)
	assertCount(report, "p2-one-settlement-instruction", "settlement_fact_count", len(facts.Instructions), 1, proofSource)
	assertCount(report, "p2-one-settlement-attempt", "settlement_fact_count", len(facts.Attempts), 1, proofSource)
	assertCount(report, "p2-one-settled-finality", "settlement_fact_count", len(facts.Settlements), 1, proofSource)
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
	assertSettlementFactState(report, "p2-obligation-state-created", stateFromObligation(facts.Obligations), "OBLIGATION_CREATED", proofSource)
	assertSettlementFactState(report, "p2-allocation-state-proposed", stateFromAllocation(facts.Allocations), "ALLOCATION_PROPOSED", proofSource)
	assertSettlementFactState(report, "p2-confirmation-state-generated", stateFromConfirmation(facts.Confirmations), "CONFIRMATION_GENERATED", proofSource)
	assertSettlementFactState(report, "p2-affirmation-state-accepted", stateFromAffirmation(facts.Affirmations), "AFFIRMATION_ACCEPTED", proofSource)
	assertSettlementFactState(report, "p2-clearing-state-submitted", stateFromClearingSubmission(facts.ClearingSubmissions), "CLEARING_SUBMITTED", proofSource)
	assertSettlementFactState(report, "p2-clearing-state-accepted", stateFromClearingAcceptance(facts.ClearingAcceptances), "CLEARING_ACCEPTED", proofSource)
	assertSettlementFactState(report, "p2-novation-state-recorded", stateFromNovation(facts.Novations), "NOVATION_RECORDED", proofSource)
	assertSettlementFactState(report, "p2-instruction-state-created", stateFromInstruction(facts.Instructions), "INSTRUCTION_CREATED", proofSource)
	assertSettlementFactState(report, "p2-attempt-state-started", stateFromAttempt(facts.Attempts), "ATTEMPT_STARTED", proofSource)
	assertSettlementFactState(report, "p2-settlement-state-settled", stateFromSettlement(facts.Settlements), "SETTLED", proofSource)
	if postTradeSettlementChainLinked(facts) {
		passAssertion(report, "p2-post-trade-chain-linked", "trade -> obligation -> allocation -> confirmation -> affirmation -> clearing -> novation -> settlement", "all fact references linked", proofSource)
	} else {
		failAssertion(report, "p2-post-trade-chain-linked", "settlement_fact_causation", "trade -> obligation -> allocation -> confirmation -> affirmation -> clearing -> novation -> settlement", postTradeSettlementChainObserved(facts), proofSource)
	}
}

func assertSettlementFactState(report *smokeReport, id string, observed string, expected string, proofSource string) {
	if observed == expected {
		passAssertion(report, id, expected, observed, proofSource)
		return
	}
	failAssertion(report, id, "settlement_fact_state", expected, observed, proofSource)
}

func stateFromObligation(facts []settlementObligation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAllocation(facts []settlementAllocation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromConfirmation(facts []settlementConfirmation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAffirmation(facts []settlementAffirmation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromClearingSubmission(facts []settlementClearingSubmission) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromClearingAcceptance(facts []settlementClearingAcceptance) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromNovation(facts []settlementNovation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromInstruction(facts []settlementInstruction) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAttempt(facts []settlementAttempt) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromSettlement(facts []settlementSettled) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].SettlementState
}

func assertCount(report *smokeReport, id string, category string, observed int, expected int, proofSource string) {
	if observed == expected {
		passAssertion(report, id, fmt.Sprint(expected), fmt.Sprint(observed), proofSource)
		return
	}
	failAssertion(report, id, category, fmt.Sprint(expected), fmt.Sprint(observed), proofSource)
}

func p2ResolutionReferencesRepair(facts settlementFactsReport) bool {
	if len(facts.Resolutions) != 1 || len(facts.Repairs) != 1 {
		return false
	}
	resolution := facts.Resolutions[0]
	repair := facts.Repairs[0]
	return resolution.SettlementRepairID != "" &&
		resolution.SettlementRepairID == repair.SettlementRepairID &&
		resolution.SettlementBreakID == repair.SettlementBreakID &&
		resolution.SettlementObligationID == repair.SettlementObligationID
}

func p2SettlementChainLinked(facts settlementFactsReport) bool {
	if len(facts.Obligations) != 1 || len(facts.Breaks) != 1 || len(facts.Repairs) != 1 || len(facts.Resolutions) != 1 {
		return false
	}
	obligation := facts.Obligations[0]
	breakFact := facts.Breaks[0]
	repair := facts.Repairs[0]
	resolution := facts.Resolutions[0]
	return obligation.SettlementObligationID != "" &&
		breakFact.SettlementBreakID != "" &&
		repair.SettlementRepairID != "" &&
		resolution.SettlementResolutionID != "" &&
		breakFact.SettlementObligationID == obligation.SettlementObligationID &&
		repair.SettlementBreakID == breakFact.SettlementBreakID &&
		repair.SettlementObligationID == obligation.SettlementObligationID &&
		resolution.SettlementRepairID == repair.SettlementRepairID &&
		resolution.SettlementBreakID == breakFact.SettlementBreakID &&
		resolution.SettlementObligationID == obligation.SettlementObligationID
}

func p2SettlementChainObserved(facts settlementFactsReport) string {
	if len(facts.Obligations) != 1 || len(facts.Breaks) != 1 || len(facts.Repairs) != 1 || len(facts.Resolutions) != 1 {
		return fmt.Sprintf("counts obligations=%d breaks=%d repairs=%d resolutions=%d", len(facts.Obligations), len(facts.Breaks), len(facts.Repairs), len(facts.Resolutions))
	}
	obligation := facts.Obligations[0]
	breakFact := facts.Breaks[0]
	repair := facts.Repairs[0]
	resolution := facts.Resolutions[0]
	return fmt.Sprintf(
		"obligation=%s break(obligation=%s,id=%s) repair(obligation=%s,break=%s,id=%s) resolution(obligation=%s,break=%s,repair=%s,id=%s)",
		obligation.SettlementObligationID,
		breakFact.SettlementObligationID,
		breakFact.SettlementBreakID,
		repair.SettlementObligationID,
		repair.SettlementBreakID,
		repair.SettlementRepairID,
		resolution.SettlementObligationID,
		resolution.SettlementBreakID,
		resolution.SettlementRepairID,
		resolution.SettlementResolutionID,
	)
}

func postTradeSettlementChainLinked(facts settlementFactsReport) bool {
	if len(facts.Obligations) != 1 || len(facts.Allocations) != 1 || len(facts.Confirmations) != 1 ||
		len(facts.Affirmations) != 1 || len(facts.ClearingSubmissions) != 1 || len(facts.ClearingAcceptances) != 1 ||
		len(facts.ClearingRejections) != 0 || len(facts.Novations) != 1 || len(facts.Instructions) != 1 ||
		len(facts.Attempts) != 1 || len(facts.Settlements) != 1 {
		return false
	}
	obligation := facts.Obligations[0]
	allocation := facts.Allocations[0]
	confirmation := facts.Confirmations[0]
	affirmation := facts.Affirmations[0]
	clearingSubmission := facts.ClearingSubmissions[0]
	clearingAcceptance := facts.ClearingAcceptances[0]
	novation := facts.Novations[0]
	instruction := facts.Instructions[0]
	attempt := facts.Attempts[0]
	settlement := facts.Settlements[0]
	return obligation.SettlementObligationID != "" &&
		obligation.TradeID != "" &&
		allocation.SettlementAllocationID != "" &&
		confirmation.SettlementConfirmationID != "" &&
		affirmation.SettlementAffirmationID != "" &&
		clearingSubmission.SettlementClearingSubmissionID != "" &&
		clearingAcceptance.SettlementClearingAcceptanceID != "" &&
		novation.SettlementNovationID != "" &&
		instruction.SettlementInstructionID != "" &&
		attempt.SettlementAttemptID != "" &&
		settlement.SettlementID != "" &&
		allocation.SettlementObligationID == obligation.SettlementObligationID &&
		allocation.TradeID == obligation.TradeID &&
		allocation.CausationID == obligation.SettlementObligationID &&
		allocation.BuyOrderID != "" &&
		allocation.SellOrderID != "" &&
		confirmation.SettlementAllocationID == allocation.SettlementAllocationID &&
		confirmation.SettlementObligationID == obligation.SettlementObligationID &&
		confirmation.TradeID == obligation.TradeID &&
		confirmation.CausationID == allocation.SettlementAllocationID &&
		affirmation.SettlementConfirmationID == confirmation.SettlementConfirmationID &&
		affirmation.SettlementAllocationID == allocation.SettlementAllocationID &&
		affirmation.SettlementObligationID == obligation.SettlementObligationID &&
		affirmation.TradeID == obligation.TradeID &&
		affirmation.CausationID == confirmation.SettlementConfirmationID &&
		clearingSubmission.SettlementObligationID == obligation.SettlementObligationID &&
		clearingSubmission.SettlementAffirmationID == affirmation.SettlementAffirmationID &&
		clearingSubmission.CausationID == affirmation.SettlementAffirmationID &&
		clearingSubmission.State == "CLEARING_SUBMITTED" &&
		clearingAcceptance.SettlementClearingSubmissionID == clearingSubmission.SettlementClearingSubmissionID &&
		clearingAcceptance.SettlementObligationID == obligation.SettlementObligationID &&
		clearingAcceptance.CausationID == clearingSubmission.SettlementClearingSubmissionID &&
		clearingAcceptance.State == "CLEARING_ACCEPTED" &&
		novation.SettlementClearingAcceptanceID == clearingAcceptance.SettlementClearingAcceptanceID &&
		novation.SettlementObligationID == obligation.SettlementObligationID &&
		novation.CausationID == clearingAcceptance.SettlementClearingAcceptanceID &&
		novation.State == "NOVATION_RECORDED" &&
		instruction.SettlementObligationID == obligation.SettlementObligationID &&
		instruction.CausationID == novation.SettlementNovationID &&
		instruction.State == "INSTRUCTION_CREATED" &&
		attempt.SettlementObligationID == obligation.SettlementObligationID &&
		attempt.SettlementInstructionID == instruction.SettlementInstructionID &&
		attempt.CausationID == instruction.SettlementInstructionID &&
		attempt.State == "ATTEMPT_STARTED" &&
		settlement.SettlementObligationID == obligation.SettlementObligationID &&
		settlement.SettlementInstructionID == instruction.SettlementInstructionID &&
		settlement.SettlementAttemptID == attempt.SettlementAttemptID &&
		settlement.CausationID == attempt.SettlementAttemptID &&
		settlement.SettlementState == "SETTLED"
}

func postTradeSettlementChainObserved(facts settlementFactsReport) string {
	if len(facts.Obligations) != 1 || len(facts.Allocations) != 1 || len(facts.Confirmations) != 1 ||
		len(facts.Affirmations) != 1 || len(facts.ClearingSubmissions) != 1 || len(facts.ClearingAcceptances) != 1 ||
		len(facts.ClearingRejections) != 0 || len(facts.Novations) != 1 || len(facts.Instructions) != 1 ||
		len(facts.Attempts) != 1 || len(facts.Settlements) != 1 {
		return fmt.Sprintf(
			"counts obligations=%d allocations=%d confirmations=%d affirmations=%d clearingSubmissions=%d clearingAcceptances=%d clearingRejections=%d novations=%d instructions=%d attempts=%d settlements=%d",
			len(facts.Obligations),
			len(facts.Allocations),
			len(facts.Confirmations),
			len(facts.Affirmations),
			len(facts.ClearingSubmissions),
			len(facts.ClearingAcceptances),
			len(facts.ClearingRejections),
			len(facts.Novations),
			len(facts.Instructions),
			len(facts.Attempts),
			len(facts.Settlements),
		)
	}
	obligation := facts.Obligations[0]
	allocation := facts.Allocations[0]
	confirmation := facts.Confirmations[0]
	affirmation := facts.Affirmations[0]
	clearingSubmission := facts.ClearingSubmissions[0]
	clearingAcceptance := facts.ClearingAcceptances[0]
	novation := facts.Novations[0]
	instruction := facts.Instructions[0]
	attempt := facts.Attempts[0]
	settlement := facts.Settlements[0]
	return fmt.Sprintf(
		"obligation(id=%s,trade=%s) allocation(id=%s,obligation=%s,trade=%s,cause=%s,buyOrder=%s,sellOrder=%s) confirmation(id=%s,allocation=%s,cause=%s) affirmation(id=%s,confirmation=%s,cause=%s) clearingSubmission(id=%s,affirmation=%s,cause=%s) clearingAcceptance(id=%s,submission=%s,cause=%s) novation(id=%s,acceptance=%s,cause=%s) instruction(id=%s,obligation=%s,cause=%s) attempt(id=%s,instruction=%s,cause=%s) settlement(id=%s,attempt=%s,cause=%s,state=%s)",
		obligation.SettlementObligationID,
		obligation.TradeID,
		allocation.SettlementAllocationID,
		allocation.SettlementObligationID,
		allocation.TradeID,
		allocation.CausationID,
		allocation.BuyOrderID,
		allocation.SellOrderID,
		confirmation.SettlementConfirmationID,
		confirmation.SettlementAllocationID,
		confirmation.CausationID,
		affirmation.SettlementAffirmationID,
		affirmation.SettlementConfirmationID,
		affirmation.CausationID,
		clearingSubmission.SettlementClearingSubmissionID,
		clearingSubmission.SettlementAffirmationID,
		clearingSubmission.CausationID,
		clearingAcceptance.SettlementClearingAcceptanceID,
		clearingAcceptance.SettlementClearingSubmissionID,
		clearingAcceptance.CausationID,
		novation.SettlementNovationID,
		novation.SettlementClearingAcceptanceID,
		novation.CausationID,
		instruction.SettlementInstructionID,
		instruction.SettlementObligationID,
		instruction.CausationID,
		attempt.SettlementAttemptID,
		attempt.SettlementInstructionID,
		attempt.CausationID,
		settlement.SettlementID,
		settlement.SettlementAttemptID,
		settlement.CausationID,
		settlement.SettlementState,
	)
}

func settlementFactsHaveCausation(facts settlementFactsReport) bool {
	for _, obligation := range facts.Obligations {
		if !hasCausationFields(obligation.ScenarioRunID, obligation.CorrelationID, obligation.CausationID) {
			return false
		}
	}
	for _, allocation := range facts.Allocations {
		if !hasCausationFields(allocation.ScenarioRunID, allocation.CorrelationID, allocation.CausationID) {
			return false
		}
	}
	for _, confirmation := range facts.Confirmations {
		if !hasCausationFields(confirmation.ScenarioRunID, confirmation.CorrelationID, confirmation.CausationID) {
			return false
		}
	}
	for _, affirmation := range facts.Affirmations {
		if !hasCausationFields(affirmation.ScenarioRunID, affirmation.CorrelationID, affirmation.CausationID) {
			return false
		}
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		if !hasCausationFields(clearingSubmission.ScenarioRunID, clearingSubmission.CorrelationID, clearingSubmission.CausationID) {
			return false
		}
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		if !hasCausationFields(clearingAcceptance.ScenarioRunID, clearingAcceptance.CorrelationID, clearingAcceptance.CausationID) {
			return false
		}
	}
	for _, clearingRejection := range facts.ClearingRejections {
		if !hasCausationFields(clearingRejection.ScenarioRunID, clearingRejection.CorrelationID, clearingRejection.CausationID) {
			return false
		}
	}
	for _, novation := range facts.Novations {
		if !hasCausationFields(novation.ScenarioRunID, novation.CorrelationID, novation.CausationID) {
			return false
		}
	}
	for _, instruction := range facts.Instructions {
		if !hasCausationFields(instruction.ScenarioRunID, instruction.CorrelationID, instruction.CausationID) {
			return false
		}
	}
	for _, attempt := range facts.Attempts {
		if !hasCausationFields(attempt.ScenarioRunID, attempt.CorrelationID, attempt.CausationID) {
			return false
		}
	}
	for _, settlement := range facts.Settlements {
		if !hasCausationFields(settlement.ScenarioRunID, settlement.CorrelationID, settlement.CausationID) {
			return false
		}
	}
	for _, breakFact := range facts.Breaks {
		if !hasCausationFields(breakFact.ScenarioRunID, breakFact.CorrelationID, breakFact.CausationID) {
			return false
		}
	}
	for _, repair := range facts.Repairs {
		if !hasCausationFields(repair.ScenarioRunID, repair.CorrelationID, repair.CausationID) {
			return false
		}
	}
	for _, resolution := range facts.Resolutions {
		if !hasCausationFields(resolution.ScenarioRunID, resolution.CorrelationID, resolution.CausationID) {
			return false
		}
	}
	return true
}

func hasCausationFields(scenarioRunID string, correlationID string, causationID string) bool {
	return strings.TrimSpace(scenarioRunID) != "" &&
		strings.TrimSpace(correlationID) != "" &&
		strings.TrimSpace(causationID) != ""
}

func settlementFactsScopedToScenarioRun(facts settlementFactsReport, expectedScenarioRunID string) bool {
	if facts.ScenarioRunID != expectedScenarioRunID {
		return false
	}
	for _, obligation := range facts.Obligations {
		if obligation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, allocation := range facts.Allocations {
		if allocation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, confirmation := range facts.Confirmations {
		if confirmation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, affirmation := range facts.Affirmations {
		if affirmation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		if clearingSubmission.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		if clearingAcceptance.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingRejection := range facts.ClearingRejections {
		if clearingRejection.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, novation := range facts.Novations {
		if novation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, instruction := range facts.Instructions {
		if instruction.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, attempt := range facts.Attempts {
		if attempt.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, settlement := range facts.Settlements {
		if settlement.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, breakFact := range facts.Breaks {
		if breakFact.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, repair := range facts.Repairs {
		if repair.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, resolution := range facts.Resolutions {
		if resolution.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	return true
}

func settlementScenarioRunIDs(facts settlementFactsReport) string {
	values := []string{"bundle:" + facts.ScenarioRunID}
	for _, obligation := range facts.Obligations {
		values = append(values, "obligation:"+obligation.ScenarioRunID)
	}
	for _, allocation := range facts.Allocations {
		values = append(values, "allocation:"+allocation.ScenarioRunID)
	}
	for _, confirmation := range facts.Confirmations {
		values = append(values, "confirmation:"+confirmation.ScenarioRunID)
	}
	for _, affirmation := range facts.Affirmations {
		values = append(values, "affirmation:"+affirmation.ScenarioRunID)
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		values = append(values, "clearingSubmission:"+clearingSubmission.ScenarioRunID)
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		values = append(values, "clearingAcceptance:"+clearingAcceptance.ScenarioRunID)
	}
	for _, clearingRejection := range facts.ClearingRejections {
		values = append(values, "clearingRejection:"+clearingRejection.ScenarioRunID)
	}
	for _, novation := range facts.Novations {
		values = append(values, "novation:"+novation.ScenarioRunID)
	}
	for _, instruction := range facts.Instructions {
		values = append(values, "instruction:"+instruction.ScenarioRunID)
	}
	for _, attempt := range facts.Attempts {
		values = append(values, "attempt:"+attempt.ScenarioRunID)
	}
	for _, settlement := range facts.Settlements {
		values = append(values, "settlement:"+settlement.ScenarioRunID)
	}
	for _, breakFact := range facts.Breaks {
		values = append(values, "break:"+breakFact.ScenarioRunID)
	}
	for _, repair := range facts.Repairs {
		values = append(values, "repair:"+repair.ScenarioRunID)
	}
	for _, resolution := range facts.Resolutions {
		values = append(values, "resolution:"+resolution.ScenarioRunID)
	}
	return strings.Join(values, ",")
}

func obligationTradeIDs(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.TradeID)
	}
	return strings.Join(values, ",")
}

func obligationStates(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.State)
	}
	return strings.Join(values, ",")
}

func obligationProfiles(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.PostTradeProfileID)
	}
	return strings.Join(values, ",")
}

func breakReasons(breaks []settlementBreak) string {
	values := make([]string, 0, len(breaks))
	for _, breakFact := range breaks {
		values = append(values, breakFact.Reason)
	}
	return strings.Join(values, ",")
}

func breakStates(breaks []settlementBreak) string {
	values := make([]string, 0, len(breaks))
	for _, breakFact := range breaks {
		values = append(values, breakFact.State)
	}
	return strings.Join(values, ",")
}

func repairActions(repairs []settlementRepair) string {
	values := make([]string, 0, len(repairs))
	for _, repair := range repairs {
		values = append(values, repair.RepairAction)
	}
	return strings.Join(values, ",")
}

func settlementStates(resolutions []settlementResolution) string {
	values := make([]string, 0, len(resolutions))
	for _, resolution := range resolutions {
		values = append(values, resolution.SettlementState)
	}
	return strings.Join(values, ",")
}

func exceptionStates(resolutions []settlementResolution) string {
	values := make([]string, 0, len(resolutions))
	for _, resolution := range resolutions {
		values = append(values, resolution.ExceptionState)
	}
	return strings.Join(values, ",")
}

func attachReplayChecksumEvidence(cfg config, report *smokeReport) {
	prefix := replayAssertionPrefix(report)
	body, err := os.ReadFile(cfg.replayCheckReportPath)
	if err != nil {
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "readable replay check report", err.Error(), cfg.replayCheckReportPath)
		return
	}
	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "valid replay check JSON", err.Error(), cfg.replayCheckReportPath)
		return
	}
	if report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		attachP1VisibilityTimelineToReplayChecksum(report, parsed)
	}
	report.ReplayChecksum = parsed
	report.ArtifactPaths = append(report.ArtifactPaths, cfg.replayCheckReportPath)
	pass, _ := parsed["pass"].(bool)
	failures := replayFailures(parsed)
	if !pass || len(failures) > 0 {
		observed := "pass false"
		if len(failures) > 0 {
			observed = strings.Join(failures, "; ")
		}
		failAssertion(report, prefix+"-checksum-clean", "replay_checksum", "pass true and no replay failures", observed, cfg.replayCheckReportPath)
		return
	}
	passAssertion(report, prefix+"-checksum-clean", "pass true and no replay failures", "pass true and no replay failures", cfg.replayCheckReportPath)
	if cfg.requireReplayCheck {
		assertReplayChecksumCounters(report, parsed, cfg.replayCheckReportPath, prefix, replayCounterMinimums(report))
	}
	if cfg.requireReplayCheck && report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		assertP1HistoricalVisibilityProof(report, parsed, cfg.replayCheckReportPath)
	}
}

func replayAssertionPrefix(report *smokeReport) string {
	switch report.PathID {
	case "P1_GOLDEN_HIDDEN_CROSS_T1":
		return "p1-replay"
	case "P2_SETTLEMENT_BREAK_REPAIR":
		return "p2-replay"
	default:
		return "replay"
	}
}

func replayFailures(parsed map[string]any) []string {
	raw, ok := parsed["failures"].([]any)
	if !ok {
		return nil
	}
	failures := make([]string, 0, len(raw))
	for _, item := range raw {
		if text, ok := item.(string); ok {
			failures = append(failures, text)
		}
	}
	return failures
}

func assertP1HistoricalVisibilityProof(report *smokeReport, parsed map[string]any, proofSource string) {
	visibility, ok := parsed["visibilityTimeline"].(map[string]any)
	if !ok {
		failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "visibilityTimeline.publicDepthHiddenRestingExposed=false", "missing visibilityTimeline object", proofSource)
		return
	}
	exposed, found := visibility["publicDepthHiddenRestingExposed"].(bool)
	if !found {
		failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "visibilityTimeline.publicDepthHiddenRestingExposed=false", "missing publicDepthHiddenRestingExposed boolean", proofSource)
		return
	}
	checkCount := replayVisibilityCheckCount(visibility)
	if !exposed && checkCount > 0 {
		passAssertion(report, "p1-replay-hidden-depth-timeline-proof", "hidden resting size never exposed in replayed public depth checks", fmt.Sprintf("%d public depth checks, exposed=false", checkCount), proofSource)
		return
	}
	observed := fmt.Sprintf("%d public depth checks, exposed=%t", checkCount, exposed)
	failAssertion(report, "p1-replay-hidden-depth-timeline-proof", "replay_visibility_timeline", "hidden resting size never exposed in replayed public depth checks", observed, proofSource)
}

func replayVisibilityCheckCount(visibility map[string]any) int {
	raw, ok := visibility["publicDepthChecks"].([]any)
	if !ok {
		return 0
	}
	return len(raw)
}

func attachP1VisibilityTimelineToReplayChecksum(report *smokeReport, parsed map[string]any) {
	if _, found := parsed["visibilityTimeline"]; found || report.VisibilityTimeline == nil {
		return
	}
	encoded, err := json.Marshal(report.VisibilityTimeline)
	if err != nil {
		return
	}
	var timeline map[string]any
	if err := json.Unmarshal(encoded, &timeline); err != nil {
		return
	}
	parsed["visibilityTimeline"] = timeline
}

func assertP1HistoricalVisibilityTimeline(report *smokeReport) {
	proofSource := "/api/v1/market-data/depth/XYZ"
	if report.VisibilityTimeline == nil {
		failAssertion(report, "p1-hidden-depth-timeline-proof", "public_depth_visibility_timeline", "captured pre-execution public depth check with hidden resting size not visible", "missing visibilityTimeline object", proofSource)
		return
	}
	checks := report.VisibilityTimeline.PublicDepthChecks
	readErrors := p1VisibilityReadErrors(checks)
	if len(checks) > 0 && len(readErrors) == 0 && !report.VisibilityTimeline.PublicDepthHiddenRestingExposed {
		passAssertion(report, "p1-hidden-depth-timeline-proof", "hidden resting size not visible before first execution", fmt.Sprintf("%d public depth checks, exposed=false", len(checks)), proofSource)
		return
	}
	observed := fmt.Sprintf("%d public depth checks, exposed=%t", len(checks), report.VisibilityTimeline.PublicDepthHiddenRestingExposed)
	if len(readErrors) > 0 {
		observed += ", errors=" + strings.Join(readErrors, "; ")
	}
	failAssertion(report, "p1-hidden-depth-timeline-proof", "public_depth_visibility_timeline", "hidden resting size not visible before first execution", observed, proofSource)
}

func p1VisibilityReadErrors(checks []p1PublicDepthCheck) []string {
	out := make([]string, 0)
	for _, check := range checks {
		if strings.TrimSpace(check.Error) != "" {
			out = append(out, check.Error)
		}
	}
	return out
}

func replayCounterMinimums(report *smokeReport) map[string]float64 {
	commandCount := float64(len(report.Requests))
	if commandCount <= 0 {
		commandCount = 1
	}
	return map[string]float64{
		"batchCount":            1,
		"storedCommandCount":    commandCount,
		"payloadOutcomeCount":   commandCount,
		"canonicalOutcomeCount": commandCount,
	}
}

func assertReplayChecksumCounters(report *smokeReport, parsed map[string]any, proofSource string, prefix string, minimums map[string]float64) {
	replayReport, ok := parsed["report"].(map[string]any)
	if !ok {
		failAssertion(report, prefix+"-counter-report-present", "replay_checksum", "replay report counters", "missing report object", proofSource)
		return
	}
	for key, minimum := range minimums {
		value, found := replayCounter(replayReport, key)
		if found && value >= minimum {
			passAssertion(report, prefix+"-"+kebabCase(key), fmt.Sprintf(">= %.0f", minimum), fmt.Sprintf("%.0f", value), proofSource)
		} else {
			observed := "missing"
			if found {
				observed = fmt.Sprintf("%.0f", value)
			}
			failAssertion(report, prefix+"-"+kebabCase(key), "replay_checksum", fmt.Sprintf(">= %.0f", minimum), observed, proofSource)
		}
	}
	zeroCounters := []string{
		"duplicateReplayInserted",
		"checksumMismatchCount",
		"batchCommandCountMismatchCount",
		"payloadHashMismatchCount",
		"missingOutcomeCount",
		"extraOutcomeCount",
		"streamGapCount",
		"streamOverlapCount",
		"watermarkLagCount",
	}
	for _, key := range zeroCounters {
		value, found := replayCounter(replayReport, key)
		if found && value == 0 {
			passAssertion(report, prefix+"-"+kebabCase(key), "0", "0", proofSource)
		} else {
			observed := "missing"
			if found {
				observed = fmt.Sprintf("%.0f", value)
			}
			failAssertion(report, prefix+"-"+kebabCase(key), "replay_checksum", "0", observed, proofSource)
		}
	}
}

func replayCounter(report map[string]any, key string) (float64, bool) {
	value, ok := report[key]
	if !ok {
		return 0, false
	}
	switch typed := value.(type) {
	case float64:
		return typed, true
	case int:
		return float64(typed), true
	case json.Number:
		number, err := typed.Float64()
		return number, err == nil
	default:
		return 0, false
	}
}

func kebabCase(value string) string {
	var out strings.Builder
	for index, char := range value {
		if index > 0 && char >= 'A' && char <= 'Z' {
			out.WriteByte('-')
		}
		out.WriteRune(char)
	}
	return strings.ToLower(out.String())
}

func runP1OwnOrderAssertions(cfg config, client *http.Client, report *smokeReport) {
	expected := []struct {
		assertionID   string
		fillID        string
		participantID string
		orderID       string
		status        string
		filled        string
	}{
		{
			assertionID:   "p1-hidden-sell-filled",
			fillID:        "p1-hidden-sell-fills",
			participantID: "HIDDEN_SELLER_A",
			orderID:       "p1_golden_hidden_cross_t1-ord-001",
			status:        "FILLED",
			filled:        "100",
		},
		{
			assertionID:   "p1-first-visible-buy-filled",
			fillID:        "p1-first-visible-buy-fills",
			participantID: "VISIBLE_BUYER_B",
			orderID:       "p1_golden_hidden_cross_t1-ord-002",
			status:        "FILLED",
			filled:        "40",
		},
		{
			assertionID:   "p1-second-visible-buy-filled",
			fillID:        "p1-second-visible-buy-fills",
			participantID: "VISIBLE_BUYER_C",
			orderID:       "p1_golden_hidden_cross_t1-ord-003",
			status:        "FILLED",
			filled:        "60",
		},
	}
	uniqueExecutionIDs := map[string]struct{}{}
	ownFillIdentityLeaks := map[string]struct{}{}
	allFillPricesExpected := true
	for _, want := range expected {
		read, orders, err := waitOwnOrderHistory(cfg, client, want.participantID, "XYZ", want.orderID, want.status, want.filled)
		report.Reads = append(report.Reads, read)
		if err != nil {
			report.Errors = append(report.Errors, fmt.Sprintf("%s failed", want.assertionID))
			report.Failures = append(report.Failures, scenarioFailure{
				AssertionID: want.assertionID,
				Category:    "own_order_read",
				Message:     err.Error(),
				ProofSource: read.Endpoint,
			})
			continue
		}
		order, found := findOwnOrder(orders, want.orderID)
		observedStatus := ""
		observedFilled := ""
		if found {
			observedStatus = order.Status
			observedFilled = effectiveOwnOrderFilledQuantity(order)
		}
		if observedStatus == want.status {
			passAssertion(report, want.assertionID, want.status, observedStatus, read.Endpoint)
		} else {
			failAssertion(report, want.assertionID, "own_order_lifecycle", want.status, observedStatus, read.Endpoint)
		}
		filledAssertionID := strings.TrimSuffix(want.assertionID, "-filled") + "-filled-quantity"
		if observedFilled == want.filled {
			passAssertion(report, filledAssertionID, want.filled, observedFilled, read.Endpoint)
		} else {
			failAssertion(report, filledAssertionID, "own_order_filled_quantity", want.filled, observedFilled, read.Endpoint)
		}

		fillRead, fills, err := readOwnFills(cfg, client, want.participantID, "XYZ")
		report.Reads = append(report.Reads, fillRead)
		if err != nil {
			report.Errors = append(report.Errors, fmt.Sprintf("%s failed", want.fillID))
			report.Failures = append(report.Failures, scenarioFailure{
				AssertionID: want.fillID,
				Category:    "own_fill_read",
				Message:     err.Error(),
				ProofSource: fillRead.Endpoint,
			})
			continue
		}
		totalFilled := 0
		for _, fill := range fills {
			totalFilled += parsePositiveInt(fill.QuantityUnits)
			if fill.ExecutionID != "" {
				uniqueExecutionIDs[normalizedExecutionID(fill.ExecutionID)] = struct{}{}
			}
			if fill.ExecutionPrice != "100000000000" {
				allFillPricesExpected = false
			}
		}
		if totalFilled == parsePositiveInt(want.filled) {
			passAssertion(report, want.fillID+"-quantity", want.filled, fmt.Sprint(totalFilled), fillRead.Endpoint)
		} else {
			failAssertion(report, want.fillID+"-quantity", "own_fill_quantity", want.filled, fmt.Sprint(totalFilled), fillRead.Endpoint)
		}
		for _, leak := range leakedOwnFillCounterpartyFields(fillRead.Body) {
			ownFillIdentityLeaks[leak] = struct{}{}
		}
	}
	if len(uniqueExecutionIDs) == 2 {
		passAssertion(report, "p1-own-fills-unique-execution-count", "2", "2", "/api/v1/orders/fills")
	} else {
		failAssertion(report, "p1-own-fills-unique-execution-count", "own_fill_execution_count", "2", fmt.Sprint(len(uniqueExecutionIDs)), "/api/v1/orders/fills")
	}
	if allFillPricesExpected {
		passAssertion(report, "p1-own-fills-prices", "all 100000000000", "all 100000000000", "/api/v1/orders/fills")
	} else {
		failAssertion(report, "p1-own-fills-prices", "own_fill_price", "all 100000000000", "unexpected fill price", "/api/v1/orders/fills")
	}
	if len(ownFillIdentityLeaks) == 0 {
		passAssertion(report, "p1-own-fills-counterparty-safe", "no counterparty identity", "no counterparty fields", "/api/v1/orders/fills")
	} else {
		leaks := make([]string, 0, len(ownFillIdentityLeaks))
		for leak := range ownFillIdentityLeaks {
			leaks = append(leaks, leak)
		}
		sort.Strings(leaks)
		failAssertion(report, "p1-own-fills-counterparty-safe", "own_fill_visibility", "no counterparty identity", strings.Join(leaks, ","), "/api/v1/orders/fills")
	}
}

func captureP1HiddenDepthTimeline(cfg config, client *http.Client, report *smokeReport) {
	read, depth, depthFound, err := readMarketDepth(cfg, client, "XYZ")
	report.Reads = append(report.Reads, read)
	if report.VisibilityTimeline == nil {
		report.VisibilityTimeline = &p1VisibilityTimeline{
			ScenarioID:    report.PathID,
			ScenarioRunID: report.ScenarioRunID,
		}
	}
	check := p1PublicDepthCheck{
		Phase:        "after-hidden-resting-before-first-execution",
		InstrumentID: "XYZ",
		Price:        "100000000000",
		StatusCode:   read.StatusCode,
		CheckedAt:    time.Now().UTC().Format(time.RFC3339Nano),
	}
	if err != nil {
		check.Error = err.Error()
		check.Observed = err.Error()
	} else if depthFound && marketDepthContainsLevel(depth.AskLevels, "100000000000", "100") {
		check.HiddenRestingQuantityVisible = true
		check.Observed = "ask level 100000000000/100 visible"
	} else if depthFound {
		check.Observed = fmt.Sprintf("bidLevels=%d askLevels=%d", len(depth.BidLevels), len(depth.AskLevels))
	} else {
		check.Observed = "no public hidden resting sell level"
	}
	report.VisibilityTimeline.PublicDepthHiddenRestingExposed = report.VisibilityTimeline.PublicDepthHiddenRestingExposed || check.HiddenRestingQuantityVisible
	report.VisibilityTimeline.PublicDepthChecks = append(report.VisibilityTimeline.PublicDepthChecks, check)
}

func runP1MarketDataAssertions(cfg config, client *http.Client, report *smokeReport) {
	availabilityRead, availability, err := readDataAvailability(cfg, client)
	if cfg.requireZeroProjectionLag {
		availabilityRead, availability, err = waitP1DataAvailabilityZero(cfg, client)
	}
	report.Reads = append(report.Reads, availabilityRead)
	if err != nil {
		report.Errors = append(report.Errors, "p1-read-surface-inventory failed")
		report.Failures = append(report.Failures, scenarioFailure{
			AssertionID: "p1-read-surface-inventory",
			Category:    "read_surface_inventory",
			Message:     err.Error(),
			ProofSource: availabilityRead.Endpoint,
		})
	} else {
		assertP1DataAvailability(report, availabilityRead.Endpoint, availability, cfg.requireZeroProjectionLag)
	}

	tradeRead, trades, err := readTradeTape(cfg, client, "XYZ")
	report.Reads = append(report.Reads, tradeRead)
	if err != nil {
		report.Errors = append(report.Errors, "p1-trade-tape-read failed")
		report.Failures = append(report.Failures, scenarioFailure{
			AssertionID: "p1-trade-tape-read",
			Category:    "trade_tape_read",
			Message:     err.Error(),
			ProofSource: tradeRead.Endpoint,
		})
	} else {
		assertP1TradeTape(report, tradeRead.Endpoint, tradeRead.Body, trades)
	}

	depthRead, depth, depthFound, err := readMarketDepth(cfg, client, "XYZ")
	report.Reads = append(report.Reads, depthRead)
	if err != nil {
		report.Errors = append(report.Errors, "p1-public-depth-hidden-size-not-visible failed")
		report.Failures = append(report.Failures, scenarioFailure{
			AssertionID: "p1-public-depth-hidden-size-not-visible",
			Category:    "market_depth_read",
			Message:     err.Error(),
			ProofSource: depthRead.Endpoint,
		})
		return
	}
	if !depthFound || !marketDepthContainsLevel(depth.AskLevels, "100000000000", "100") {
		observed := "no public hidden resting sell level"
		if depthFound {
			observed = fmt.Sprintf("bidLevels=%d askLevels=%d", len(depth.BidLevels), len(depth.AskLevels))
		}
		passAssertion(report, "p1-public-depth-hidden-size-not-visible", "no ask level 100000000000/100", observed, depthRead.Endpoint)
	} else {
		failAssertion(report, "p1-public-depth-hidden-size-not-visible", "public_depth_visibility", "no ask level 100000000000/100", "ask level 100000000000/100 visible", depthRead.Endpoint)
	}
}

func assertP1DataAvailability(report *smokeReport, endpoint string, availability dataAvailabilityBody, requireZeroProjectionLag bool) {
	required := map[string]struct {
		assertionID string
		source      string
		freshness   string
	}{
		"orderHistory": {
			assertionID: "p1-order-history-source-declared",
			source:      "runtime.order_lifecycle_state",
			freshness:   "dirty-tracked lifecycle projection",
		},
		"orderFills": {
			assertionID: "p1-order-fills-source-declared",
			source:      "runtime.orders + runtime.executions",
			freshness:   "durable execution rows scoped by participant order ownership",
		},
		"marketDataDepth": {
			assertionID: "p1-market-depth-source-declared",
			source:      "runtime.order_lifecycle_state",
			freshness:   "read-time bounded aggregation",
		},
		"tradeTape": {
			assertionID: "p1-trade-tape-source-declared",
			source:      "runtime.trades",
			freshness:   "durable fact rows",
		},
	}
	surfaces := map[string]dataSurfaceStatus{}
	for _, surface := range availability.Surfaces {
		surfaces[surface.Name] = surface
		if surface.ProjectionName != "" {
			measuredAt := surface.LastUpdatedAt
			if measuredAt == "" {
				measuredAt = availability.GeneratedAt
			}
			report.ProjectionLag = append(report.ProjectionLag, projectionLag{
				Projection: surface.ProjectionName,
				Lag:        fmt.Sprint(surface.Lag),
				Watermark:  fmt.Sprint(surface.LastPartitionSequence),
				MeasuredAt: measuredAt,
			})
		}
	}
	missing := make([]string, 0)
	for name := range required {
		if _, ok := surfaces[name]; !ok {
			missing = append(missing, name)
		}
	}
	sort.Strings(missing)
	if len(missing) == 0 {
		passAssertion(report, "p1-read-surface-inventory", "orderHistory/orderFills/marketDataDepth/tradeTape", "read surfaces available", endpoint)
	} else {
		failAssertion(report, "p1-read-surface-inventory", "read_surface_inventory", "orderHistory/orderFills/marketDataDepth/tradeTape", "missing "+strings.Join(missing, ","), endpoint)
	}
	if requireZeroProjectionLag {
		lagZero, lagSummary := p1RequiredReadSurfaceLagSummary(surfaces, required)
		if lagZero {
			passAssertion(report, "p1-read-surface-projection-lag-zero", "lag 0 on required projected read surfaces", lagSummary, endpoint)
		} else {
			failAssertion(report, "p1-read-surface-projection-lag-zero", "projection_lag", "lag 0 on required projected read surfaces", lagSummary, endpoint)
		}
	}
	for name, want := range required {
		surface, ok := surfaces[name]
		observed := ""
		if ok {
			observed = surface.Source + "|" + surface.Freshness
		}
		expected := want.source + "|" + want.freshness
		if observed == expected || compatibleP1ReadSurfaceSource(name, surface.Source, want.source) && surface.Freshness == want.freshness {
			passAssertion(report, want.assertionID, expected, observed, endpoint)
		} else {
			failAssertion(report, want.assertionID, "read_surface_source", expected, observed, endpoint)
		}
	}
}

func p1RequiredReadSurfaceLagSummary(surfaces map[string]dataSurfaceStatus, required map[string]struct {
	assertionID string
	source      string
	freshness   string
}) (bool, string) {
	names := make([]string, 0, len(required))
	for name := range required {
		names = append(names, name)
	}
	sort.Strings(names)
	parts := make([]string, 0, len(names))
	allZero := true
	for _, name := range names {
		surface, ok := surfaces[name]
		if !ok {
			parts = append(parts, name+":missing")
			allZero = false
			continue
		}
		if surface.ProjectionName == "" {
			parts = append(parts, name+":no projection")
			continue
		}
		parts = append(parts, fmt.Sprintf("%s:%s lag=%d", name, surface.ProjectionName, surface.Lag))
		if surface.Lag != 0 {
			allZero = false
		}
	}
	return allZero, strings.Join(parts, ", ")
}

func compatibleP1ReadSurfaceSource(name string, observed string, expected string) bool {
	return name == "orderHistory" &&
		expected == "runtime.order_lifecycle_state" &&
		observed == "runtime.orders + runtime.order_lifecycle_state"
}

func assertP1TradeTape(report *smokeReport, endpoint string, body string, trades []tradeTapeEntry) {
	scenarioTrades := p1ScenarioTrades(trades)
	if len(scenarioTrades) == 2 {
		passAssertion(report, "p1-trade-tape-count", "2 scenario trades", fmt.Sprintf("2 scenario trades in %d tape rows", len(trades)), endpoint)
	} else {
		observed := tradeIDs(scenarioTrades)
		if observed == "" {
			observed = "no scenario trades; tape IDs: " + tradeIDs(trades)
		}
		failAssertion(report, "p1-trade-tape-count", "trade_tape_count", "2 scenario trades", observed, endpoint)
	}
	totalQuantity := 0
	allExpectedPrice := true
	for _, trade := range scenarioTrades {
		totalQuantity += parsePositiveInt(trade.QuantityUnits)
		if trade.Price != "100000000000" {
			allExpectedPrice = false
		}
	}
	if totalQuantity == 100 {
		passAssertion(report, "p1-trade-tape-total-quantity", "100", "100", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-total-quantity", "trade_tape_quantity", "100", fmt.Sprint(totalQuantity), endpoint)
	}
	if len(scenarioTrades) == 2 && allExpectedPrice {
		passAssertion(report, "p1-trade-tape-prices", "all 100000000000", "all 100000000000", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-prices", "trade_tape_price", "all 100000000000", tradePrices(scenarioTrades), endpoint)
	}
	identityLeaks := leakedPublicIdentityFields(body)
	if len(identityLeaks) == 0 {
		passAssertion(report, "p1-trade-tape-public-safe", "no counterparty/order/participant identity", "no identity fields", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-public-safe", "trade_tape_visibility", "no counterparty/order/participant identity", strings.Join(identityLeaks, ","), endpoint)
	}
}

func p1ScenarioTrades(trades []tradeTapeEntry) []tradeTapeEntry {
	expected := map[string]struct{}{
		p1FirstVisibleBuyTradeID:  {},
		p1SecondVisibleBuyTradeID: {},
	}
	out := make([]tradeTapeEntry, 0, 2)
	for _, trade := range trades {
		if _, found := expected[trade.TradeID]; found {
			out = append(out, trade)
		}
	}
	return out
}

func readTradeTape(cfg config, client *http.Client, instrumentID string) (assertionRead, []tradeTapeEntry, error) {
	endpoint := "/api/v1/market-data/trades/" + instrumentID
	query := url.Values{}
	query.Set("limit", "50")
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint)+"?"+query.Encode(), endpoint, map[string]string{"limit": "50"})
	if err != nil {
		return read, nil, err
	}
	var parsed tradeTapeBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return read, nil, err
	}
	read.SourceType = parsed.Meta.Source
	read.FreshnessModel = parsed.Meta.Freshness
	if read.StatusCode != 200 {
		return read, parsed.Trades, fmt.Errorf("trade tape returned %d", read.StatusCode)
	}
	return read, parsed.Trades, nil
}

func readMarketDepth(cfg config, client *http.Client, instrumentID string) (assertionRead, marketDepthSnapshot, bool, error) {
	endpoint := "/api/v1/market-data/depth/" + instrumentID
	query := url.Values{}
	query.Set("levels", "5")
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint)+"?"+query.Encode(), endpoint, map[string]string{"levels": "5"})
	if err != nil {
		return read, marketDepthSnapshot{}, false, err
	}
	if read.StatusCode == 404 {
		return read, marketDepthSnapshot{}, false, nil
	}
	var parsed marketDepthBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return read, marketDepthSnapshot{}, false, err
	}
	read.SourceType = "runtime.order_lifecycle_state"
	read.FreshnessModel = "read-time bounded aggregation"
	if read.StatusCode != 200 {
		return read, parsed.Depth, false, fmt.Errorf("market depth returned %d", read.StatusCode)
	}
	return read, parsed.Depth, true, nil
}

func readDataAvailability(cfg config, client *http.Client) (assertionRead, dataAvailabilityBody, error) {
	endpoint := "/api/v1/data/availability"
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint), endpoint, nil)
	if err != nil {
		return read, dataAvailabilityBody{}, err
	}
	read.SourceType = "runtime availability report"
	read.FreshnessModel = "live diagnostic snapshot"
	var parsed dataAvailabilityBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return read, dataAvailabilityBody{}, err
	}
	if read.StatusCode != 200 {
		return read, parsed, fmt.Errorf("data availability returned %d", read.StatusCode)
	}
	return read, parsed, nil
}

func waitP1DataAvailabilityZero(cfg config, client *http.Client) (assertionRead, dataAvailabilityBody, error) {
	deadline := time.Now().Add(cfg.statusTimeout)
	var lastRead assertionRead
	var lastAvailability dataAvailabilityBody
	for {
		read, availability, err := readDataAvailability(cfg, client)
		if err != nil {
			return read, availability, err
		}
		lastRead = read
		lastAvailability = availability
		if p1DataAvailabilityLagZero(availability) {
			return read, availability, nil
		}
		if time.Now().After(deadline) {
			return lastRead, lastAvailability, nil
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func p1DataAvailabilityLagZero(availability dataAvailabilityBody) bool {
	required := map[string]struct{}{
		"orderHistory":    {},
		"orderFills":      {},
		"marketDataDepth": {},
		"tradeTape":       {},
	}
	seen := map[string]dataSurfaceStatus{}
	for _, surface := range availability.Surfaces {
		if _, ok := required[surface.Name]; ok {
			seen[surface.Name] = surface
		}
	}
	if len(seen) != len(required) {
		return false
	}
	for _, surface := range seen {
		if surface.ProjectionName != "" && surface.Lag != 0 {
			return false
		}
	}
	return true
}

func executeRead(client *http.Client, requestURL string, endpoint string, filters map[string]string) (assertionRead, []byte, error) {
	read := assertionRead{
		Endpoint: endpoint,
		Filters:  filters,
	}
	request, err := http.NewRequest(http.MethodGet, requestURL, nil)
	if err != nil {
		return read, nil, err
	}
	request.Header.Set("X-Client-Id", "scenario-smoke-read")
	if participantID := strings.TrimSpace(filters["participantId"]); participantID != "" {
		request.Header.Set("X-Participant-Id", participantID)
	}
	response, err := client.Do(request)
	if err != nil {
		return read, nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		return read, nil, err
	}
	read.StatusCode = response.StatusCode
	read.Body = strings.TrimSpace(string(body))
	return read, body, nil
}

func marketDepthContainsLevel(levels []marketDepthLevel, price string, quantity string) bool {
	for _, level := range levels {
		if level.Price == price && level.Quantity == quantity {
			return true
		}
	}
	return false
}

func parsePositiveInt(value string) int {
	total := 0
	for _, ch := range value {
		if ch < '0' || ch > '9' {
			return 0
		}
		total = total*10 + int(ch-'0')
	}
	return total
}

func tradePrices(trades []tradeTapeEntry) string {
	prices := make([]string, 0, len(trades))
	for _, trade := range trades {
		prices = append(prices, trade.Price)
	}
	return strings.Join(prices, ",")
}

func tradeIDs(trades []tradeTapeEntry) string {
	ids := make([]string, 0, len(trades))
	for _, trade := range trades {
		ids = append(ids, trade.TradeID)
	}
	return strings.Join(ids, ",")
}

func leakedPublicIdentityFields(body string) []string {
	fields := []string{"buyOrderId", "sellOrderId", "participantId", "accountId", "orderId"}
	leaks := make([]string, 0)
	for _, field := range fields {
		if strings.Contains(body, `"`+field+`"`) {
			leaks = append(leaks, field)
		}
	}
	return leaks
}

func leakedOwnFillCounterpartyFields(body string) []string {
	fields := []string{"buyOrderId", "sellOrderId", "buyerParticipantId", "sellerParticipantId", "counterparty", "counterpartyParticipantId", "accountId"}
	leaks := make([]string, 0)
	for _, field := range fields {
		if strings.Contains(body, `"`+field+`"`) {
			leaks = append(leaks, field)
		}
	}
	return leaks
}

func readOwnOrderHistory(cfg config, client *http.Client, participantID string, instrumentID string) (assertionRead, []ownOrderBody, error) {
	endpoint := "/api/v1/orders/history"
	query := url.Values{}
	query.Set("participantId", participantID)
	query.Set("instrumentId", instrumentID)
	query.Set("limit", "50")
	requestURL := absoluteURL(cfg.readBaseURL, endpoint) + "?" + query.Encode()
	read := assertionRead{
		Endpoint: endpoint,
		Filters: map[string]string{
			"participantId": participantID,
			"instrumentId":  instrumentID,
			"limit":         "50",
		},
	}
	read, body, err := executeRead(client, requestURL, endpoint, read.Filters)
	if err != nil {
		return read, nil, err
	}
	var parsed ownOrdersBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return read, nil, err
	}
	read.SourceType = parsed.Meta.Source
	read.FreshnessModel = parsed.Meta.Freshness
	if read.StatusCode != 200 {
		return read, parsed.Orders, fmt.Errorf("own-order history returned %d", read.StatusCode)
	}
	return read, parsed.Orders, nil
}

func waitOwnOrderHistory(
	cfg config,
	client *http.Client,
	participantID string,
	instrumentID string,
	orderID string,
	wantStatus string,
	wantFilledQuantity string,
) (assertionRead, []ownOrderBody, error) {
	deadline := time.Now().Add(cfg.statusTimeout)
	var lastRead assertionRead
	var lastOrders []ownOrderBody
	var lastErr error
	for {
		read, orders, err := readOwnOrderHistory(cfg, client, participantID, instrumentID)
		lastRead = read
		lastOrders = orders
		lastErr = err
		if err == nil {
			if order, found := findOwnOrder(orders, orderID); found &&
				order.Status == wantStatus &&
				effectiveOwnOrderFilledQuantity(order) == wantFilledQuantity {
				return read, orders, nil
			}
		}
		if time.Now().After(deadline) {
			return lastRead, lastOrders, lastErr
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func readOwnFills(cfg config, client *http.Client, participantID string, instrumentID string) (assertionRead, []ownExecutionBody, error) {
	endpoint := "/api/v1/orders/fills"
	query := url.Values{}
	query.Set("participantId", participantID)
	query.Set("instrumentId", instrumentID)
	query.Set("limit", "50")
	requestURL := absoluteURL(cfg.readBaseURL, endpoint) + "?" + query.Encode()
	read := assertionRead{
		Endpoint: endpoint,
		Filters: map[string]string{
			"participantId": participantID,
			"instrumentId":  instrumentID,
			"limit":         "50",
		},
	}
	read, body, err := executeRead(client, requestURL, endpoint, read.Filters)
	if err != nil {
		return read, nil, err
	}
	var parsed ownExecutionsBody
	if err := json.Unmarshal(body, &parsed); err != nil {
		return read, nil, err
	}
	read.SourceType = parsed.Meta.Source
	read.FreshnessModel = parsed.Meta.Freshness
	if read.StatusCode != 200 {
		return read, parsed.Fills, fmt.Errorf("own fills returned %d", read.StatusCode)
	}
	return read, parsed.Fills, nil
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

func findOwnOrder(orders []ownOrderBody, orderID string) (ownOrderBody, bool) {
	for _, order := range orders {
		if order.OrderID == orderID {
			return order, true
		}
	}
	return ownOrderBody{}, false
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

func executeRequest(client *http.Client, request smokeRequest) smokeResult {
	body, err := json.Marshal(request.Payload)
	if err != nil {
		return smokeResult{Sequence: request.Sequence, Command: request.Command, Path: request.Path, Error: err.Error()}
	}
	httpRequest, err := http.NewRequest(request.Method, request.URL, bytes.NewReader(body))
	if err != nil {
		return smokeResult{Sequence: request.Sequence, Command: request.Command, Path: request.Path, Error: err.Error()}
	}
	httpRequest.Header.Set("Content-Type", "application/json")
	for key, value := range request.Headers {
		httpRequest.Header.Set(key, value)
	}
	response, err := client.Do(httpRequest)
	if err != nil {
		return smokeResult{Sequence: request.Sequence, Command: request.Command, Path: request.Path, Error: err.Error()}
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		return smokeResult{Sequence: request.Sequence, Command: request.Command, Path: request.Path, StatusCode: response.StatusCode, Error: err.Error()}
	}
	return smokeResult{
		Sequence:   request.Sequence,
		Command:    request.Command,
		Path:       request.Path,
		StatusCode: response.StatusCode,
		Body:       strings.TrimSpace(string(responseBody)),
	}
}

func waitCommandStatus(client *http.Client, cfg config, commandID string, headers map[string]string) (int, string, error) {
	deadline := time.Now().Add(cfg.statusTimeout)
	statusURL := absoluteURL(cfg.statusBaseURL, "/api/v1/commands/"+commandID)
	var lastStatus int
	var lastBody string
	for {
		request, err := http.NewRequest(http.MethodGet, statusURL, nil)
		if err != nil {
			return 0, "", err
		}
		for key, value := range headers {
			if key == "Idempotency-Key" {
				continue
			}
			request.Header.Set(key, value)
		}
		response, err := client.Do(request)
		if err == nil {
			body, readErr := io.ReadAll(response.Body)
			response.Body.Close()
			if readErr != nil {
				return response.StatusCode, "", readErr
			}
			lastStatus = response.StatusCode
			lastBody = strings.TrimSpace(string(body))
			if response.StatusCode == 200 {
				return lastStatus, lastBody, nil
			}
		}
		if time.Now().After(deadline) {
			if err != nil {
				return lastStatus, lastBody, err
			}
			return lastStatus, lastBody, fmt.Errorf("command status not visible before timeout")
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func executableActorIDs(plan scenarioconfig.ScenarioPlan) []string {
	seen := map[string]struct{}{}
	for _, step := range plan.Steps {
		if step.APIExecutable {
			if actorID := strings.TrimSpace(step.Payload["actorId"]); actorID != "" {
				seen[actorID] = struct{}{}
			}
		}
	}
	out := make([]string, 0, len(seen))
	for actorID := range seen {
		out = append(out, actorID)
	}
	sort.Strings(out)
	return out
}

func absoluteURL(baseURL string, path string) string {
	return strings.TrimRight(baseURL, "/") + path
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
