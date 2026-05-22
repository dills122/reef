package app

import (
	"testing"

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
