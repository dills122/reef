package app

import (
	"fmt"
	"sync/atomic"
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

// BenchmarkSubmitOrderManyInstrumentsParallel spreads concurrent submits
// across many distinct instruments (distinct per-book mutexes, per the
// engine's per-book/per-shard design) so each goroutine rarely contends on
// the same orderBook.mu. Any remaining scaling ceiling under -cpu>1 is
// attributable to the single engine-wide Service.ordersMu (and
// terminalOrderRetention.mu), which every submitOrder call hits regardless
// of which instrument/shard it targets. Compare ns/op here against
// BenchmarkSubmitOrderSingleInstrumentParallel, which additionally contends
// on one shared book.mu, to see how much of the cost is the shared order
// index versus shared book access.
func BenchmarkSubmitOrderManyInstrumentsParallel(b *testing.B) {
	const numInstruments = 64
	service := NewService()
	var counter atomic.Int64

	b.ReportAllocs()
	b.ResetTimer()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			id := counter.Add(1)
			cmd := domain.SubmitOrder{
				OrderID:       fmt.Sprintf("ord-many-%d", id),
				InstrumentID:  fmt.Sprintf("INST-%d", id%numInstruments),
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
	})
}

// BenchmarkSubmitOrderSingleInstrumentParallel is the same concurrent load
// as BenchmarkSubmitOrderManyInstrumentsParallel but pinned to one
// instrument, so every goroutine also contends on one shared orderBook.mu in
// addition to Service.ordersMu. The gap between this and the many-instrument
// benchmark upper-bounds book.mu's share of any parallel slowdown; whatever
// gap remains between the many-instrument benchmark and single-goroutine
// throughput is the ordersMu/terminalRetention.mu tax.
func BenchmarkSubmitOrderSingleInstrumentParallel(b *testing.B) {
	service := NewService()
	instrument := "AAPL"
	var counter atomic.Int64

	b.ReportAllocs()
	b.ResetTimer()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			id := counter.Add(1)
			cmd := domain.SubmitOrder{
				OrderID:       fmt.Sprintf("ord-single-%d", id),
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
	})
}
