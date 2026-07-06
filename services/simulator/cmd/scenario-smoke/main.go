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
	"sort"
	"strings"
	"time"

	scenarioconfig "github.com/dills122/reef/services/simulator/internal/config"
)

const defaultScenarioStart = "2026-03-14T18:00:00Z"

type config struct {
	scenarioPath  string
	scenarioRunID string
	start         string
	baseURL       string
	live          bool
	seedReference bool
	timeout       time.Duration
	statusTimeout time.Duration
	reportOut     string
	pretty        bool
}

type smokeReport struct {
	Mode          string         `json:"mode"`
	Pass          bool           `json:"pass"`
	PathID        string         `json:"pathId"`
	ScenarioRunID string         `json:"scenarioRunId"`
	Seed          int64          `json:"seed"`
	BaseURL       string         `json:"baseUrl,omitempty"`
	SeedRequests  []smokeRequest `json:"seedRequests,omitempty"`
	Requests      []smokeRequest `json:"requests"`
	Results       []smokeResult  `json:"results,omitempty"`
	Errors        []string       `json:"errors,omitempty"`
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
		runLive(cfg, client, &report)
		report.Pass = len(report.Errors) == 0
	}
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
	fs.BoolVar(&cfg.live, "live", false, "send executable scenario requests to base-url")
	fs.BoolVar(&cfg.seedReference, "seed-reference", cfg.seedReference, "seed scenario reference/auth data in live mode")
	fs.DurationVar(&cfg.timeout, "timeout", cfg.timeout, "HTTP request timeout")
	fs.DurationVar(&cfg.statusTimeout, "status-timeout", cfg.statusTimeout, "command status polling timeout")
	fs.StringVar(&cfg.reportOut, "report-out", "", "optional path to write smoke report JSON")
	fs.BoolVar(&cfg.pretty, "pretty", false, "pretty-print JSON")
	if err := fs.Parse(args); err != nil {
		return cfg, err
	}
	if cfg.scenarioPath == "" {
		return cfg, errors.New("missing --scenario")
	}
	if cfg.timeout <= 0 {
		return cfg, errors.New("timeout must be > 0")
	}
	if cfg.statusTimeout <= 0 {
		return cfg, errors.New("status-timeout must be > 0")
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
		out = append(out, smokeRequest{
			Sequence: step.Sequence,
			Command:  step.Command,
			Method:   http.MethodPost,
			Path:     step.Route,
			URL:      absoluteURL(cfg.baseURL, step.Route),
			Headers: map[string]string{
				"X-Client-Id":      fmt.Sprintf("scenario-smoke-%d", step.Sequence),
				"Idempotency-Key":  step.Payload["commandId"],
				"X-Correlation-Id": step.Payload["traceId"],
			},
			Payload: copyStringMap(step.Payload),
		})
	}
	return out
}

func seedRequests(cfg config, scenario scenarioconfig.ScenarioFile, plan scenarioconfig.ScenarioPlan) []smokeRequest {
	headers := map[string]string{"X-Reef-Internal-Route": "true"}
	out := make([]smokeRequest, 0)
	for _, instrument := range scenario.Preconditions.ReferenceData.Instruments {
		out = append(out, postRequest(cfg.baseURL, "/reference/instruments", headers, map[string]string{
			"instrumentId": instrument.InstrumentID,
			"symbol":       instrument.Symbol,
		}))
	}
	for _, participant := range scenario.Preconditions.ReferenceData.Participants {
		out = append(out, postRequest(cfg.baseURL, "/reference/participants", headers, map[string]string{
			"participantId": participant.ParticipantID,
			"name":          participant.ParticipantID,
		}))
	}
	for _, account := range scenario.Preconditions.ReferenceData.Accounts {
		out = append(out, postRequest(cfg.baseURL, "/reference/accounts", headers, map[string]string{
			"accountId":     account.AccountID,
			"participantId": account.ParticipantID,
		}))
	}
	out = append(out, postRequest(cfg.baseURL, "/auth/roles", headers, map[string]string{
		"roleId":      "order_trader",
		"permissions": "order.submit,order.cancel,order.modify",
	}))
	for _, actorID := range executableActorIDs(plan) {
		out = append(out, postRequest(cfg.baseURL, "/auth/actor-roles", headers, map[string]string{
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
	client.Timeout = cfg.timeout
	if cfg.seedReference {
		for _, request := range report.SeedRequests {
			result := executeRequest(client, request)
			report.Results = append(report.Results, result)
			if result.Error != "" || result.StatusCode < 200 || result.StatusCode >= 300 {
				report.Errors = append(report.Errors, fmt.Sprintf("seed %s failed", request.Path))
				return
			}
		}
	}
	for _, request := range report.Requests {
		result := executeRequest(client, request)
		if result.Error == "" && result.StatusCode >= 200 && result.StatusCode < 300 {
			statusCode, statusBody, err := waitCommandStatus(client, cfg, request.Payload["commandId"])
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
	}
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

func waitCommandStatus(client *http.Client, cfg config, commandID string) (int, string, error) {
	deadline := time.Now().Add(cfg.statusTimeout)
	statusURL := absoluteURL(cfg.baseURL, "/api/v1/commands/"+commandID)
	var lastStatus int
	var lastBody string
	for {
		request, err := http.NewRequest(http.MethodGet, statusURL, nil)
		if err != nil {
			return 0, "", err
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
