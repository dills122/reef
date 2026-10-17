package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
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
