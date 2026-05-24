package app

import (
	"fmt"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func BenchmarkSubmitOrderResting(b *testing.B) {
	service := NewService()
	instrument := "AAPL"

	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		cmd := domain.SubmitOrder{
			OrderID:       fmt.Sprintf("ord-rest-%d", i),
			InstrumentID:  instrument,
			Side:          domain.SideBuy,
			QuantityUnits: "100",
			LimitPrice:    "150000000000",
			Currency:      "USD",
		}
		result := service.SubmitOrder(cmd)
		if result.Accepted == nil {
			b.Fatalf("expected accepted result, got %#v", result)
		}
	}
}

func BenchmarkSubmitOrderMatchAgainstResting(b *testing.B) {
	service := NewService()
	instrument := "AAPL"

	for i := 0; i < b.N; i++ {
		buyOrderID := fmt.Sprintf("ord-buy-%d", i)
		result := service.SubmitOrder(domain.SubmitOrder{
			OrderID:       buyOrderID,
			InstrumentID:  instrument,
			Side:          domain.SideBuy,
			QuantityUnits: "100",
			LimitPrice:    "150500000000",
			Currency:      "USD",
		})
		if result.Accepted == nil {
			b.Fatalf("expected accepted resting buy, got %#v", result)
		}
	}

	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		cmd := domain.SubmitOrder{
			OrderID:       fmt.Sprintf("ord-sell-%d", i),
			InstrumentID:  instrument,
			Side:          domain.SideSell,
			QuantityUnits: "100",
			LimitPrice:    "150000000000",
			Currency:      "USD",
		}
		result := service.SubmitOrder(cmd)
		if result.Accepted == nil || len(result.Trades) != 1 {
			b.Fatalf("expected accepted match with one trade, got %#v", result)
		}
	}
}

func BenchmarkModifyOrder(b *testing.B) {
	service := NewService()
	instrument := "AAPL"
	orderIDs := make([]string, b.N)

	for i := 0; i < b.N; i++ {
		orderID := fmt.Sprintf("ord-mod-%d", i)
		orderIDs[i] = orderID
		result := service.SubmitOrder(domain.SubmitOrder{
			OrderID:       orderID,
			InstrumentID:  instrument,
			Side:          domain.SideBuy,
			QuantityUnits: "200",
			LimitPrice:    "150000000000",
			Currency:      "USD",
		})
		if result.Accepted == nil {
			b.Fatalf("expected accepted resting order, got %#v", result)
		}
	}

	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		cmd := domain.ModifyOrder{
			OrderID:       orderIDs[i],
			QuantityUnits: "210",
			LimitPrice:    "150100000000",
		}
		result := service.ModifyOrder(cmd)
		if result.Accepted == nil {
			b.Fatalf("expected accepted modify, got %#v", result)
		}
	}
}
