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
