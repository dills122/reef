package app

import (
	"strconv"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func FuzzParsePositiveInt(f *testing.F) {
	for _, seed := range []string{"", "0", "1", "42", "-1", "0007", "9223372036854775807", "9223372036854775808"} {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, value string) {
		parsed, ok := parsePositiveInt(value)
		expected, err := strconv.ParseInt(value, 10, 64)
		expectedOK := err == nil && expected > 0

		if ok != expectedOK {
			t.Fatalf("parsePositiveInt(%q) ok=%v want %v", value, ok, expectedOK)
		}
		if ok && parsed != expected {
			t.Fatalf("parsePositiveInt(%q)=%d want %d", value, parsed, expected)
		}
	})
}

func FuzzSubmitOrderSingleOrderInvariants(f *testing.F) {
	seeds := []domain.SubmitOrder{
		{OrderID: "ord-1", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "100", LimitPrice: "150250000000", Currency: "USD"},
		{OrderID: "ord-2", InstrumentID: "AAPL", Side: domain.SideSell, QuantityUnits: "25", LimitPrice: "150000000000", Currency: "USD"},
		{OrderID: "", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "100", LimitPrice: "150250000000", Currency: "USD"},
		{OrderID: "ord-3", InstrumentID: "", Side: domain.SideBuy, QuantityUnits: "100", LimitPrice: "150250000000", Currency: "USD"},
		{OrderID: "ord-4", InstrumentID: "AAPL", Side: "NOPE", QuantityUnits: "100", LimitPrice: "150250000000", Currency: "USD"},
		{OrderID: "ord-5", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "-1", LimitPrice: "150250000000", Currency: "USD"},
		{OrderID: "ord-6", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "100", LimitPrice: "0", Currency: "USD"},
	}
	for _, seed := range seeds {
		f.Add(seed.OrderID, seed.InstrumentID, string(seed.Side), seed.QuantityUnits, seed.LimitPrice, seed.Currency)
	}

	f.Fuzz(func(t *testing.T, orderID, instrumentID, side, quantityUnits, limitPrice, currency string) {
		if tooLarge(orderID, instrumentID, side, quantityUnits, limitPrice, currency) {
			return
		}

		service := NewService()
		result := service.SubmitOrder(domain.SubmitOrder{
			OrderID:       orderID,
			InstrumentID:  instrumentID,
			Side:          domain.Side(side),
			QuantityUnits: quantityUnits,
			LimitPrice:    limitPrice,
			Currency:      currency,
		})

		if (result.Accepted == nil) == (result.Rejected == nil) {
			t.Fatalf("submit result must have exactly one outcome, got %#v", result)
		}
		if result.Rejected != nil {
			if len(result.Executions) != 0 || len(result.Trades) != 0 {
				t.Fatalf("rejected single order emitted executions/trades: %#v", result)
			}
			return
		}

		state, ok := service.OrderState(orderID)
		if !ok {
			t.Fatalf("accepted order has no state: %#v", result)
		}
		original, originalOK := parsePositiveInt(state.OriginalQuantity)
		remaining, remainingOK := parsePositiveInt(state.RemainingQuantity)
		if !originalOK || !remainingOK {
			t.Fatalf("accepted order has invalid quantities: %#v", state)
		}
		if remaining > original {
			t.Fatalf("remaining quantity exceeds original quantity: %#v", state)
		}
		if state.Status != domain.OrderStatusAccepted {
			t.Fatalf("fresh accepted single order should rest as accepted, got %#v", state)
		}
		if service.RestingOrders(instrumentID, domain.Side(side)) != 1 {
			t.Fatalf("accepted single order should add exactly one resting order")
		}
	})
}

func tooLarge(values ...string) bool {
	const maxFuzzStringLen = 256
	for _, value := range values {
		if len(value) > maxFuzzStringLen {
			return true
		}
	}
	return false
}
