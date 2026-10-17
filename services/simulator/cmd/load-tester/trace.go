package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type traceChecks struct {
	Checked       int      `json:"checked"`
	Pass          int      `json:"pass"`
	Fail          int      `json:"fail"`
	FailedTraceID []string `json:"failedTraceIds"`
}

// traceSampler retains at most `limit` trace IDs for later verification via
// runTraceChecks. Without a cap, storing every successful trace ID for the
// full run duration grows unbounded and can exhaust memory on long/high-rate
// load tests, since only a small sample is ever actually checked.
type traceSampler struct {
	limit int
	count int64
	seen  sync.Map
}

func newTraceSampler(limit int) *traceSampler {
	return &traceSampler{limit: limit}
}

func (t *traceSampler) offer(traceID string) {
	if t == nil || t.limit <= 0 || strings.TrimSpace(traceID) == "" {
		return
	}
	if atomic.AddInt64(&t.count, 1) > int64(t.limit) {
		return
	}
	t.seen.Store(traceID, struct{}{})
}

func (t *traceSampler) ids(limit int) []string {
	if t == nil || limit <= 0 {
		return []string{}
	}
	out := make([]string, 0, limit)
	t.seen.Range(func(key, _ any) bool {
		out = append(out, key.(string))
		return len(out) < limit
	})
	return out
}

type runtimeEvent struct {
	TraceID        string `json:"traceId"`
	SequenceNumber int64  `json:"sequenceNumber"`
}

type traceEventsResponse struct {
	Events []runtimeEvent `json:"events"`
}

type trade struct {
	EventID       string `json:"eventId"`
	TradeID       string `json:"tradeId"`
	BuyOrderID    string `json:"buyOrderId"`
	SellOrderID   string `json:"sellOrderId"`
	QuantityUnits string `json:"quantityUnits"`
	Price         string `json:"price"`
	OccurredAt    string `json:"occurredAt"`
}

type tradesResponse struct {
	Trades []trade `json:"trades"`
}

type event struct {
	EventID        string `json:"eventId"`
	EventType      string `json:"eventType"`
	OrderID        string `json:"orderId"`
	TraceID        string `json:"traceId"`
	SequenceNumber int64  `json:"sequenceNumber"`
	OccurredAt     string `json:"occurredAt"`
}

type eventsResponse struct {
	Events []event `json:"events"`
}

type runtimeReject struct {
	Code   string `json:"code"`
	Reason string `json:"reason"`
}

type runtimeResponse struct {
	Rejected *runtimeReject `json:"rejected,omitempty"`
}

type boundaryErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func runTraceChecks(client *http.Client, cfg Config, seen *traceSampler) traceChecks {
	if cfg.TraceCheckLimit <= 0 {
		return traceChecks{Checked: 0, FailedTraceID: make([]string, 0)}
	}
	traceIDs := seen.ids(cfg.TraceCheckLimit)
	sort.Strings(traceIDs)

	checks := traceChecks{Checked: len(traceIDs), FailedTraceID: make([]string, 0, 8)}
	for _, traceID := range traceIDs {
		ok := checkTrace(client, cfg.BaseURL, traceID)
		if ok {
			checks.Pass++
		} else {
			checks.Fail++
			if len(checks.FailedTraceID) < 8 {
				checks.FailedTraceID = append(checks.FailedTraceID, traceID)
			}
		}
	}
	return checks
}

func tailLoop(ctx context.Context, client *http.Client, cfg Config) {
	ticker := time.NewTicker(cfg.TailInterval)
	defer ticker.Stop()
	seenTrades := make(map[string]struct{})
	seenEvents := make(map[string]struct{})

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			trades := fetchNewTrades(client, cfg.BaseURL, seenTrades, cfg.TailLines)
			events := fetchNewEvents(client, cfg.BaseURL, seenEvents, cfg.TailLines)
			if len(trades) == 0 && len(events) == 0 {
				continue
			}
			fmt.Printf("[tail %s] new_trades=%d new_events=%d\n", time.Now().UTC().Format(time.RFC3339), len(trades), len(events))
			for _, t := range trades {
				fmt.Printf("  trade %s buy=%s sell=%s qty=%s px=%s at=%s\n", t.TradeID, t.BuyOrderID, t.SellOrderID, t.QuantityUnits, t.Price, t.OccurredAt)
			}
			for _, e := range events {
				fmt.Printf("  event %s type=%s order=%s trace=%s seq=%d at=%s\n", e.EventID, e.EventType, e.OrderID, e.TraceID, e.SequenceNumber, e.OccurredAt)
			}
		}
	}
}

func fetchNewTrades(client *http.Client, baseURL string, seen map[string]struct{}, limit int) []trade {
	resp, err := client.Get(fmt.Sprintf("%s/trades?limit=%d", baseURL, limit))
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil
	}
	var parsed tradesResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil
	}
	out := make([]trade, 0, limit)
	for i := len(parsed.Trades) - 1; i >= 0 && len(out) < limit; i-- {
		row := parsed.Trades[i]
		if _, ok := seen[row.EventID]; ok {
			continue
		}
		seen[row.EventID] = struct{}{}
		out = append(out, row)
	}
	reverseTrades(out)
	return out
}

func fetchNewEvents(client *http.Client, baseURL string, seen map[string]struct{}, limit int) []event {
	resp, err := client.Get(fmt.Sprintf("%s/events?limit=%d", baseURL, limit))
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil
	}
	var parsed eventsResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil
	}
	out := make([]event, 0, limit)
	for i := len(parsed.Events) - 1; i >= 0 && len(out) < limit; i-- {
		row := parsed.Events[i]
		if _, ok := seen[row.EventID]; ok {
			continue
		}
		seen[row.EventID] = struct{}{}
		out = append(out, row)
	}
	reverseEvents(out)
	return out
}

func checkTrace(client *http.Client, baseURL, traceID string) bool {
	for attempt := 0; attempt < 3; attempt++ {
		if checkTraceOnce(client, baseURL, traceID) {
			return true
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}

func checkTraceOnce(client *http.Client, baseURL, traceID string) bool {
	resp, err := client.Get(baseURL + "/traces/" + traceID + "/events")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return false
	}
	var parsed traceEventsResponse
	if err := json.Unmarshal(body, &parsed); err != nil {
		return false
	}
	if len(parsed.Events) == 0 {
		return false
	}
	sort.Slice(parsed.Events, func(i, j int) bool {
		return parsed.Events[i].SequenceNumber < parsed.Events[j].SequenceNumber
	})
	last := int64(0)
	for _, event := range parsed.Events {
		if event.TraceID != traceID {
			return false
		}
		if event.SequenceNumber <= 0 {
			return false
		}
		// tolerate occasional sequence gaps under high churn, but never allow regressions.
		if event.SequenceNumber < last {
			return false
		}
		last = event.SequenceNumber
	}
	return true
}
