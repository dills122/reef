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

type config struct {
	scenarioPath  string
	scenarioRunID string
	start         string
	baseURL       string
	live          bool
	assertions    bool
	seedReference bool
	timeout       time.Duration
	statusTimeout time.Duration
	reportOut     string
	pretty        bool
}

type smokeReport struct {
	Mode           string              `json:"mode"`
	Pass           bool                `json:"pass"`
	PathID         string              `json:"pathId"`
	ScenarioRunID  string              `json:"scenarioRunId"`
	Seed           int64               `json:"seed"`
	BaseURL        string              `json:"baseUrl,omitempty"`
	SeedRequests   []smokeRequest      `json:"seedRequests,omitempty"`
	Requests       []smokeRequest      `json:"requests"`
	Results        []smokeResult       `json:"results,omitempty"`
	Commands       []assertionCommand  `json:"commands,omitempty"`
	Reads          []assertionRead     `json:"reads,omitempty"`
	Assertions     []scenarioAssertion `json:"assertions,omitempty"`
	Failures       []scenarioFailure   `json:"failures,omitempty"`
	ProjectionLag  []projectionLag     `json:"projectionLag,omitempty"`
	ReplayChecksum map[string]any      `json:"replayChecksum,omitempty"`
	ArtifactPaths  []string            `json:"artifactPaths,omitempty"`
	Errors         []string            `json:"errors,omitempty"`
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

type commandStatusBody struct {
	CommandID    string `json:"commandId"`
	Status       string `json:"status"`
	ResultStatus string `json:"resultStatus"`
	Source       string `json:"source"`
}

type ownOrdersBody struct {
	Meta struct {
		Source    string `json:"source"`
		Freshness string `json:"freshness"`
	} `json:"meta"`
	Orders []ownOrderBody `json:"orders"`
}

type ownOrderBody struct {
	OrderID string `json:"orderId"`
	Status  string `json:"status"`
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
	fs.BoolVar(&cfg.assertions, "assertions", false, "run first-wave live scenario assertions after command submission")
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
	if cfg.assertions && !cfg.live {
		return cfg, errors.New("--assertions requires --live")
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
	if cfg.assertions && len(report.Errors) == 0 {
		runAssertions(cfg, client, report)
	}
}

func runAssertions(cfg config, client *http.Client, report *smokeReport) {
	for _, result := range report.Results {
		if result.Command == "" {
			continue
		}
		status := parseCommandStatus(result.CommandStatusBody)
		command := assertionCommand{
			Sequence:     result.Sequence,
			Command:      result.Command,
			CommandID:    status.CommandID,
			Route:        result.Path,
			Status:       status.Status,
			ResultStatus: status.ResultStatus,
			Source:       status.Source,
			StatusCode:   result.CommandStatusCode,
			Body:         result.CommandStatusBody,
		}
		if command.CommandID == "" {
			command.CommandID = commandIDForResult(report.Requests, result)
		}
		report.Commands = append(report.Commands, command)
		assertionID := fmt.Sprintf("command-%03d-completed", result.Sequence)
		if strings.EqualFold(status.Status, "COMPLETED") && strings.EqualFold(status.ResultStatus, "accepted") {
			passAssertion(report, assertionID, "COMPLETED/accepted", status.Status+"/"+status.ResultStatus, "GET /api/v1/commands/{commandId}")
		} else {
			failAssertion(report, assertionID, "command_completion", "COMPLETED/accepted", status.Status+"/"+status.ResultStatus, "GET /api/v1/commands/{commandId}")
		}
	}
	if report.PathID == "P1_GOLDEN_HIDDEN_CROSS_T1" {
		runP1OwnOrderAssertions(cfg, client, report)
		runP1MarketDataAssertions(cfg, client, report)
	}
}

func runP1OwnOrderAssertions(cfg config, client *http.Client, report *smokeReport) {
	expected := []struct {
		assertionID   string
		participantID string
		orderID       string
		status        string
	}{
		{
			assertionID:   "p1-hidden-sell-filled",
			participantID: "HIDDEN_SELLER_A",
			orderID:       "p1_golden_hidden_cross_t1-ord-001",
			status:        "FILLED",
		},
		{
			assertionID:   "p1-first-visible-buy-filled",
			participantID: "VISIBLE_BUYER_B",
			orderID:       "p1_golden_hidden_cross_t1-ord-002",
			status:        "FILLED",
		},
		{
			assertionID:   "p1-second-visible-buy-filled",
			participantID: "VISIBLE_BUYER_C",
			orderID:       "p1_golden_hidden_cross_t1-ord-003",
			status:        "FILLED",
		},
	}
	for _, want := range expected {
		read, orders, err := readOwnOrderHistory(cfg, client, want.participantID, "XYZ")
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
		observed := findOwnOrderStatus(orders, want.orderID)
		if observed == want.status {
			passAssertion(report, want.assertionID, want.status, observed, read.Endpoint)
		} else {
			failAssertion(report, want.assertionID, "own_order_lifecycle", want.status, observed, read.Endpoint)
		}
	}
}

func runP1MarketDataAssertions(cfg config, client *http.Client, report *smokeReport) {
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

func assertP1TradeTape(report *smokeReport, endpoint string, body string, trades []tradeTapeEntry) {
	if len(trades) == 2 {
		passAssertion(report, "p1-trade-tape-count", "2", "2", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-count", "trade_tape_count", "2", fmt.Sprint(len(trades)), endpoint)
	}
	totalQuantity := 0
	allExpectedPrice := true
	for _, trade := range trades {
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
	if allExpectedPrice {
		passAssertion(report, "p1-trade-tape-prices", "all 100000000000", "all 100000000000", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-prices", "trade_tape_price", "all 100000000000", tradePrices(trades), endpoint)
	}
	identityLeaks := leakedPublicIdentityFields(body)
	if len(identityLeaks) == 0 {
		passAssertion(report, "p1-trade-tape-public-safe", "no counterparty/order/participant identity", "no identity fields", endpoint)
	} else {
		failAssertion(report, "p1-trade-tape-public-safe", "trade_tape_visibility", "no counterparty/order/participant identity", strings.Join(identityLeaks, ","), endpoint)
	}
}

func readTradeTape(cfg config, client *http.Client, instrumentID string) (assertionRead, []tradeTapeEntry, error) {
	endpoint := "/api/v1/market-data/trades/" + instrumentID
	query := url.Values{}
	query.Set("limit", "50")
	read, body, err := executeRead(client, absoluteURL(cfg.baseURL, endpoint)+"?"+query.Encode(), endpoint, map[string]string{"limit": "50"})
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
	read, body, err := executeRead(client, absoluteURL(cfg.baseURL, endpoint)+"?"+query.Encode(), endpoint, map[string]string{"levels": "5"})
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

func executeRead(client *http.Client, requestURL string, endpoint string, filters map[string]string) (assertionRead, []byte, error) {
	read := assertionRead{
		Endpoint: endpoint,
		Filters:  filters,
	}
	request, err := http.NewRequest(http.MethodGet, requestURL, nil)
	if err != nil {
		return read, nil, err
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

func readOwnOrderHistory(cfg config, client *http.Client, participantID string, instrumentID string) (assertionRead, []ownOrderBody, error) {
	endpoint := "/api/v1/orders/history"
	query := url.Values{}
	query.Set("participantId", participantID)
	query.Set("instrumentId", instrumentID)
	query.Set("limit", "50")
	requestURL := absoluteURL(cfg.baseURL, endpoint) + "?" + query.Encode()
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

func parseCommandStatus(body string) commandStatusBody {
	var status commandStatusBody
	_ = json.Unmarshal([]byte(body), &status)
	return status
}

func commandIDForResult(requests []smokeRequest, result smokeResult) string {
	for _, request := range requests {
		if request.Sequence == result.Sequence {
			return request.Payload["commandId"]
		}
	}
	return ""
}

func findOwnOrderStatus(orders []ownOrderBody, orderID string) string {
	for _, order := range orders {
		if order.OrderID == orderID {
			return order.Status
		}
	}
	return ""
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
