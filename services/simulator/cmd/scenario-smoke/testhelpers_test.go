package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func p1OwnOrderHistoryJSON(orderID string) string {
	return p1OwnOrderHistoryJSONWithFilled(orderID, "")
}

func p1CleanReplayCheckJSON() string {
	return `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"visibilityTimeline":{"scenarioId":"P1_GOLDEN_HIDDEN_CROSS_T1","publicDepthHiddenRestingExposed":false,"publicDepthChecks":[{"phase":"before-first-execution","instrumentId":"XYZ","price":"100000000000","hiddenRestingQuantityVisible":false}]},"failures":[]}`
}

func p1OwnOrderHistoryJSONWithFilled(orderID string, filledOverride string) string {
	filled := map[string]string{
		"p1_golden_hidden_cross_t1-ord-001": "100",
		"p1_golden_hidden_cross_t1-ord-002": "40",
		"p1_golden_hidden_cross_t1-ord-003": "60",
	}[orderID]
	if filledOverride != "" {
		filled = filledOverride
	}
	return `{"meta":{"source":"runtime.orders + runtime.order_lifecycle_state","freshness":"dirty-tracked lifecycle projection"},"orders":[{"orderId":"` + orderID + `","status":"FILLED","filledQuantityUnits":"` + filled + `"}]}`
}

func p1OwnFillsJSON(participantID string) string {
	fills := map[string]string{
		"HIDDEN_SELLER_A": `{"executionId":"exec-p1-001","orderId":"p1_golden_hidden_cross_t1-ord-001","instrumentId":"XYZ","side":"SELL","quantityUnits":"40","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"},{"executionId":"exec-p1-002","orderId":"p1_golden_hidden_cross_t1-ord-001","instrumentId":"XYZ","side":"SELL","quantityUnits":"60","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"}`,
		"VISIBLE_BUYER_B": `{"executionId":"exec-p1-001","orderId":"p1_golden_hidden_cross_t1-ord-002","instrumentId":"XYZ","side":"BUY","quantityUnits":"40","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"}`,
		"VISIBLE_BUYER_C": `{"executionId":"exec-p1-002","orderId":"p1_golden_hidden_cross_t1-ord-003","instrumentId":"XYZ","side":"BUY","quantityUnits":"60","executionPrice":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"}`,
	}[participantID]
	if fills == "" {
		fills = ""
	}
	return `{"meta":{"source":"runtime.orders + runtime.executions","freshness":"durable execution rows scoped by participant order ownership"},"fills":[` + fills + `]}`
}

func p1TradeTapeJSON() string {
	return `{"instrumentId":"XYZ","meta":{"source":"runtime.trades","freshness":"durable fact rows"},"trades":[{"sequence":3,"tradeId":"trade-p2_settlement_break_repair-ord-001-p2_settlement_break_repair-ord-002-1","instrumentId":"XYZ","quantityUnits":"1000","price":"150000000000","currency":"USD","occurredAt":"2026-03-14T18:00:02Z"},{"sequence":2,"tradeId":"` + p1SecondVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"60","price":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:07Z"},{"sequence":1,"tradeId":"` + p1FirstVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"40","price":"100000000000","currency":"USD","occurredAt":"2026-03-14T18:00:03Z"}]}`
}

func p1TradeTapeWithPublicIdentityLeakJSON() string {
	return `{"instrumentId":"XYZ","meta":{"source":"runtime.trades","freshness":"durable fact rows"},"trades":[{"tradeId":"` + p1FirstVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"40","price":"100000000000","buyOrderId":"p1_golden_hidden_cross_t1-ord-002"},{"tradeId":"` + p1SecondVisibleBuyTradeID + `","instrumentId":"XYZ","quantityUnits":"60","price":"100000000000"}]}`
}

func p1DataAvailabilityJSON() string {
	return p1DataAvailabilityJSONWithOrderHistorySource("runtime.orders + runtime.order_lifecycle_state")
}

func p1DataAvailabilityJSONWithOrderHistorySource(orderHistorySource string) string {
	return `{
		"generatedAt":"2026-03-14T18:00:30Z",
		"source":"venue-event-batch",
		"projections":[
			{"projectionName":"runtime-normalized-venue-outcomes","role":"canonical venue outcome projection","projectedCount":3,"lag":0,"watermarks":[{"lastPartitionSequence":3,"lag":0,"updatedAt":"2026-03-14T18:00:30Z"}]},
			{"projectionName":"market-data-top-of-book","role":"top-of-book market data projection","projectedCount":1,"lag":0,"watermarks":[{"lastPartitionSequence":3,"lag":0,"updatedAt":"2026-03-14T18:00:30Z"}]}
		],
		"surfaces":[
			{"name":"marketDataDepth","endpoint":"/api/v1/market-data/depth/{instrumentId}","source":"runtime.order_lifecycle_state","freshness":"read-time bounded aggregation","scope":"public-market-data","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"tradeTape","endpoint":"/api/v1/market-data/trades/{instrumentId}","source":"runtime.trades","freshness":"durable fact rows","scope":"public-market-data","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"orderHistory","endpoint":"/api/v1/orders/history","source":"` + orderHistorySource + `","freshness":"dirty-tracked lifecycle projection","scope":"participant-own-orders","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"},
			{"name":"orderFills","endpoint":"/api/v1/orders/fills","source":"runtime.orders + runtime.executions","freshness":"durable execution rows scoped by participant order ownership","scope":"participant-own-orders","projectionName":"runtime-normalized-venue-outcomes","lag":0,"lastPartitionSequence":3,"lastUpdatedAt":"2026-03-14T18:00:30Z"}
		]
	}`
}

type p1AssertionServerOptions struct {
	filledQuantityByOrderID   map[string]string
	ownFillsJSONByParticipant map[string]string
	dataAvailabilityJSON      string
}

func p1AssertionServer(t *testing.T, options p1AssertionServerOptions) *httptest.Server {
	t.Helper()
	availabilityJSON := options.dataAvailabilityJSON
	if availabilityJSON == "" {
		availabilityJSON = p1DataAvailabilityJSON()
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet &&
			r.URL.Path != "/readyz" &&
			!strings.HasPrefix(r.URL.Path, "/api/v1/commands/") &&
			r.Header.Get("X-Client-Id") == "" {
			http.Error(w, "missing X-Client-Id", http.StatusUnauthorized)
			return
		}
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			participantID := r.URL.Query().Get("participantId")
			if r.Header.Get("X-Participant-Id") != participantID {
				http.Error(w, "missing participant scope", http.StatusForbidden)
				return
			}
			orderID := map[string]string{
				"HIDDEN_SELLER_A": "p1_golden_hidden_cross_t1-ord-001",
				"VISIBLE_BUYER_B": "p1_golden_hidden_cross_t1-ord-002",
				"VISIBLE_BUYER_C": "p1_golden_hidden_cross_t1-ord-003",
			}[participantID]
			if orderID == "" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSONWithFilled(orderID, options.filledQuantityByOrderID[orderID])))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			participantID := r.URL.Query().Get("participantId")
			if r.Header.Get("X-Participant-Id") != participantID {
				http.Error(w, "missing participant scope", http.StatusForbidden)
				return
			}
			responseJSON := p1OwnFillsJSON(participantID)
			if options.ownFillsJSONByParticipant != nil && options.ownFillsJSONByParticipant[participantID] != "" {
				responseJSON = options.ownFillsJSONByParticipant[participantID]
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(responseJSON))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(availabilityJSON))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
}

func p2SettlementServer(t *testing.T, settlementFacts string) *httptest.Server {
	return p2SettlementServerWithExceptionQueue(
		t,
		settlementFacts,
		p2ResolvedSettlementExceptionQueueJSON("CASH_LEG_FAILED", "POST_CASH_LEG_REPAIR"),
	)
}

func p2SettlementServerWithExceptionQueue(t *testing.T, settlementFacts string, exceptionQueue string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/settlement/facts/p2-settlement-live" && settlementFacts != "":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(settlementFacts))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/settlement/exceptions/p2-settlement-live" && settlementFacts != "":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(exceptionQueue))
		default:
			http.NotFound(w, r)
		}
	}))
}

func p2ResolvedSettlementExceptionQueueJSON(reason string, repairAction string) string {
	return fmt.Sprintf(`{
		"scenarioRunId":"p2-settlement-live",
		"exceptionsCount":1,
		"openCount":0,
		"repairPostedCount":0,
		"resolvedCount":1,
		"clearingRejectedCount":0,
		"settlementBreakCount":1,
		"exceptions":[{
			"settlementExceptionId":"break-1",
			"exceptionType":"SETTLEMENT_BREAK",
			"exceptionState":"RESOLVED",
			"state":"RESOLVED",
			"severity":"MEDIUM",
			"ownerRole":"SETTLEMENT_OPS",
			"actionRequired":"NONE",
			"reason":"%s",
			"settlementObligationId":"obl-1",
			"tradeId":"trade-1",
			"buyerParticipantId":"buyer-1",
			"sellerParticipantId":"seller-1",
			"instrumentId":"XYZ",
			"quantity":"100",
			"cashAmount":"10000.00",
			"currency":"USD",
			"settlementClearingSubmissionId":"",
			"settlementClearingRejectionId":"",
			"settlementBreakId":"break-1",
			"settlementRepairId":"repair-1",
			"settlementResolutionId":"resolution-1",
			"repairAction":"%s",
			"actorId":"ops-1",
			"correlationId":"corr-1",
			"postTradeProfileId":"ops-realistic-v1",
			"postTradePolicyVersion":1,
			"openedAt":"2026-03-14T18:00:05Z",
			"lastUpdatedAt":"2026-03-14T18:00:07Z",
			"resolvedAt":"2026-03-14T18:00:07Z",
			"occurredAt":"2026-03-14T18:00:05Z",
			"updatedAt":"2026-03-14T18:00:07Z"
		}]
	}`, reason, repairAction)
}

func p2EmptySettlementExceptionQueueJSON() string {
	return `{
		"scenarioRunId":"p2-settlement-live",
		"exceptionsCount":0,
		"openCount":0,
		"repairPostedCount":0,
		"resolvedCount":0,
		"clearingRejectedCount":0,
		"settlementBreakCount":0,
		"exceptions":[]
	}`
}

func writeReadyzOK(w http.ResponseWriter) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok","dependencies":{"commandStatusLookup":true}}`))
}

func hasAssertion(report smokeReport, assertionID string, status string) bool {
	for _, assertion := range report.Assertions {
		if assertion.ID == assertionID && assertion.Status == status {
			return true
		}
	}
	return false
}

func hasFailure(report smokeReport, assertionID string) bool {
	for _, failure := range report.Failures {
		if failure.AssertionID == assertionID {
			return true
		}
	}
	return false
}

func scenarioDefinitionsRoot(t *testing.T) string {
	return filepath.Join(simulatorRoot(t), "../../packages/scenario-definitions/scenarios/v1")
}

func simulatorRoot(t *testing.T) string {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	return filepath.Clean(filepath.Join(filepath.Dir(currentFile), "../.."))
}
