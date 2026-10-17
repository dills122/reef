package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

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

func findOwnOrder(orders []ownOrderBody, orderID string) (ownOrderBody, bool) {
	for _, order := range orders {
		if order.OrderID == orderID {
			return order, true
		}
	}
	return ownOrderBody{}, false
}
