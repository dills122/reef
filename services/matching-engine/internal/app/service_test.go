package app

import (
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestSubmitOrderAcceptsAndRestsFirstOrder(t *testing.T) {
	service := NewService()

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted result, got %#v", result)
	}

	if len(result.Executions) != 0 {
		t.Fatalf("expected no executions for resting order, got %#v", result.Executions)
	}

	if len(result.Trades) != 0 {
		t.Fatalf("expected no trades for resting order, got %#v", result.Trades)
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected one resting buy order")
	}

	state, ok := service.OrderState("ord-1")
	if !ok {
		t.Fatalf("expected order state for ord-1")
	}

	if state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" {
		t.Fatalf("unexpected accepted order state: %#v", state)
	}
}

func TestServiceUsesCommandTimestampForGeneratedEvents(t *testing.T) {
	service := NewService(WithClock(func() time.Time {
		return time.Date(2026, 3, 14, 18, 30, 0, 0, time.UTC)
	}))
	buyTime := "2026-03-14T18:00:00Z"
	sellTime := "2026-03-14T18:00:05Z"

	buy := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-buy",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
		OccurredAt:    buyTime,
	})
	if buy.Accepted == nil || buy.Accepted.OccurredAt != buyTime {
		t.Fatalf("expected buy accepted timestamp %s, got %#v", buyTime, buy.Accepted)
	}

	sell := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-sell",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
		OccurredAt:    sellTime,
	})
	if sell.Accepted == nil || sell.Accepted.OccurredAt != sellTime {
		t.Fatalf("expected sell accepted timestamp %s, got %#v", sellTime, sell.Accepted)
	}
	for _, execution := range sell.Executions {
		if execution.OccurredAt != sellTime {
			t.Fatalf("expected execution timestamp %s, got %#v", sellTime, execution)
		}
	}
	for _, trade := range sell.Trades {
		if trade.OccurredAt != sellTime {
			t.Fatalf("expected trade timestamp %s, got %#v", sellTime, trade)
		}
	}
	buyState, ok := service.OrderState("ord-clock-buy")
	if !ok || buyState.LastUpdatedAt != sellTime {
		t.Fatalf("expected matched buy state timestamp %s, got %#v", sellTime, buyState)
	}

	rejectTime := "2026-03-14T18:00:10Z"
	rejected := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-reject",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		OccurredAt:    rejectTime,
	})
	if rejected.Rejected == nil || rejected.Rejected.OccurredAt != rejectTime {
		t.Fatalf("expected rejection timestamp %s, got %#v", rejectTime, rejected.Rejected)
	}
}

func TestServiceFallsBackToInjectedClockWhenCommandTimestampMissing(t *testing.T) {
	fixed := time.Date(2026, 3, 14, 18, 45, 0, 0, time.UTC)
	service := NewService(WithClock(func() time.Time {
		return fixed
	}))

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-fallback",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if result.Accepted == nil || result.Accepted.OccurredAt != fixed.Format(time.RFC3339) {
		t.Fatalf("expected injected clock timestamp, got %#v", result.Accepted)
	}
}

func TestSubmitOrderMatchesCrossingOrder(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted result, got %#v", result)
	}

	if len(result.Executions) != 2 {
		t.Fatalf("expected two executions, got %#v", result.Executions)
	}

	if len(result.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", result.Trades)
	}

	if result.Trades[0].BuyOrderID != "ord-buy-1" || result.Trades[0].SellOrderID != "ord-sell-1" {
		t.Fatalf("unexpected trade payload: %#v", result.Trades[0])
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting buy orders after full match")
	}

	buyState, ok := service.OrderState("ord-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled buy order state, got %#v", buyState)
	}

	sellState, ok := service.OrderState("ord-sell-1")
	if !ok || sellState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled sell order state, got %#v", sellState)
	}
}

func TestSubmitOrderPartiallyFillsAndLeavesResidualLiquidity(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "150",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	firstMatch := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if len(firstMatch.Trades) != 1 {
		t.Fatalf("expected one trade on first partial fill, got %#v", firstMatch.Trades)
	}

	if firstMatch.Trades[0].QuantityUnits != "100" {
		t.Fatalf("expected 100 units on first trade, got %#v", firstMatch.Trades[0])
	}

	secondMatch := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-2",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "60",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if len(secondMatch.Trades) != 1 {
		t.Fatalf("expected one trade on second partial fill, got %#v", secondMatch.Trades)
	}

	if secondMatch.Trades[0].QuantityUnits != "50" {
		t.Fatalf("expected residual 50 units to match, got %#v", secondMatch.Trades[0])
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting buy orders after residual fill")
	}

	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected one resting sell order after residual fill")
	}

	buyState, ok := service.OrderState("ord-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled buy order state, got %#v", buyState)
	}

	secondSellState, ok := service.OrderState("ord-sell-2")
	if !ok || secondSellState.Status != domain.OrderStatusPartiallyFilled || secondSellState.RemainingQuantity != "10" {
		t.Fatalf("expected partially filled sell order state, got %#v", secondSellState)
	}
}

func TestSubmitOrderMatchesAcrossMultipleRestingOrders(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "75",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-2",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "80",
		LimitPrice:    "150100000000",
		Currency:      "USD",
	})

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "140",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if len(result.Executions) != 4 {
		t.Fatalf("expected four executions across two matches, got %#v", result.Executions)
	}

	if len(result.Trades) != 2 {
		t.Fatalf("expected two trades across two resting orders, got %#v", result.Trades)
	}

	if result.Trades[0].QuantityUnits != "75" {
		t.Fatalf("expected first trade to consume best offer, got %#v", result.Trades[0])
	}

	if result.Trades[1].QuantityUnits != "65" {
		t.Fatalf("expected second trade to consume remaining buy quantity, got %#v", result.Trades[1])
	}

	if result.Trades[0].SellOrderID != "ord-sell-1" || result.Trades[1].SellOrderID != "ord-sell-2" {
		t.Fatalf("expected price-time matching order, got %#v", result.Trades)
	}

	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected one remaining resting sell order after sweep")
	}

	restingState, ok := service.OrderState("ord-sell-2")
	if !ok || restingState.Status != domain.OrderStatusPartiallyFilled || restingState.RemainingQuantity != "15" {
		t.Fatalf("expected remaining resting sell state, got %#v", restingState)
	}
}

func TestSubmitOrderRejectsMissingInstrument(t *testing.T) {
	service := NewService()

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
	})

	if result.Rejected == nil {
		t.Fatalf("expected rejected result, got %#v", result)
	}

	if result.Rejected.Code != "VALIDATION_ERROR" {
		t.Fatalf("expected validation error, got %s", result.Rejected.Code)
	}
}

func TestSubmitOrderRejectsDuplicateOrderIDWithoutMutatingBook(t *testing.T) {
	service := NewService()

	first := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-dup",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	if first.Accepted == nil {
		t.Fatalf("expected first order to be accepted, got %#v", first)
	}

	duplicate := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-dup",
		InstrumentID:  "MSFT",
		Side:          domain.SideSell,
		QuantityUnits: "999",
		LimitPrice:    "1",
		Currency:      "USD",
	})
	if duplicate.Rejected == nil {
		t.Fatalf("expected duplicate order to be rejected, got %#v", duplicate)
	}
	if duplicate.Rejected.Code != "DUPLICATE_ORDER_ID" {
		t.Fatalf("expected duplicate order code, got %s", duplicate.Rejected.Code)
	}

	state, ok := service.OrderState("ord-dup")
	if !ok {
		t.Fatal("expected original order state to remain")
	}
	if state.InstrumentID != "AAPL" || state.Side != domain.SideBuy || state.RemainingQuantity != "100" || state.LimitPrice != "150250000000" {
		t.Fatalf("duplicate mutated original state: %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected original resting order to remain on AAPL buy book")
	}
	if service.RestingOrders("MSFT", domain.SideSell) != 0 {
		t.Fatalf("expected duplicate to add no MSFT sell liquidity")
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-cross",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if match.Accepted == nil || len(match.Trades) != 1 {
		t.Fatalf("expected original order to match exactly once, got %#v", match)
	}
	if match.Trades[0].BuyOrderID != "ord-dup" || match.Trades[0].SellOrderID != "ord-cross" {
		t.Fatalf("unexpected trade parties after duplicate rejection: %#v", match.Trades[0])
	}
}

func TestCancelOrderRemovesRestingOrder(t *testing.T) {
	service := NewService()
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.CancelOrder(domain.CancelOrder{
		OrderID: "ord-1",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted cancel result, got %#v", result)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting order after cancel")
	}
	state, ok := service.OrderState("ord-1")
	if !ok || state.Status != domain.OrderStatusCancelled {
		t.Fatalf("expected cancelled order state, got %#v", state)
	}
}

func TestCancelOrderRemovesMiddleSamePriceOrder(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-3", "100", "150250000000")

	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-2"})
	if cancel.Accepted == nil {
		t.Fatalf("expected accepted cancel result, got %#v", cancel)
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-sweep",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "250",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 2 {
		t.Fatalf("expected two trades after middle cancel, got %#v", match.Trades)
	}
	if match.Trades[0].BuyOrderID != "ord-buy-1" || match.Trades[1].BuyOrderID != "ord-buy-3" {
		t.Fatalf("cancelled order should not match and FIFO should remain, got %#v", match.Trades)
	}
}

func TestModifyOrderUpdatesPriceAndQuantity(t *testing.T) {
	service := NewService()
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-1",
		QuantityUnits: "120",
		LimitPrice:    "150300000000",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", result)
	}

	state, ok := service.OrderState("ord-1")
	if !ok {
		t.Fatalf("expected order state for ord-1")
	}
	if state.OriginalQuantity != "120" || state.RemainingQuantity != "120" || state.LimitPrice != "150300000000" {
		t.Fatalf("unexpected modified state: %#v", state)
	}
}

func TestModifyOrderResetsPriorityAtSamePrice(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")

	modified := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
	})
	if modified.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", modified)
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", match.Trades)
	}
	if match.Trades[0].BuyOrderID != "ord-buy-2" {
		t.Fatalf("modify should reset same-price priority behind existing order, got %#v", match.Trades[0])
	}
}

func TestParsePositiveInt(t *testing.T) {
	if _, ok := parsePositiveInt(""); ok {
		t.Fatal("expected empty input to fail")
	}
	if _, ok := parsePositiveInt("-2"); ok {
		t.Fatal("expected negative input to fail")
	}
	value, ok := parsePositiveInt("42")
	if !ok || value != 42 {
		t.Fatalf("expected parsed value 42, got value=%d ok=%v", value, ok)
	}
}

func submitRestingBuy(t *testing.T, service *Service, orderID string, quantity string, limitPrice string) {
	t.Helper()
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       orderID,
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: quantity,
		LimitPrice:    limitPrice,
		Currency:      "USD",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted resting buy %s, got %#v", orderID, result)
	}
	if len(result.Trades) != 0 {
		t.Fatalf("expected resting buy %s to avoid immediate trades, got %#v", orderID, result.Trades)
	}
}
