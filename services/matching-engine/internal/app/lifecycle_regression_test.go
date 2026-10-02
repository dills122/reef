package app

import (
	"github.com/dills122/reef/services/matching-engine/internal/domain"
	"reflect"
	"strconv"
	"testing"
)

func regressionOrder(id string, side domain.Side, quantity string) domain.SubmitOrder {
	return domain.SubmitOrder{CommandID: "cmd-" + id, OrderID: id, RunID: "run", VenueSessionID: "session", InstrumentID: "AAPL", ParticipantID: id, Side: side, QuantityUnits: quantity, LimitPrice: "100", Currency: "USD", TimeInForce: "DAY", OccurredAt: "2026-10-02T01:00:00Z"}
}

func TestIOCResidualNeverRests(t *testing.T) {
	for _, tc := range []struct {
		name, liquidity, fills string
		status                 domain.OrderStatus
	}{
		{"zero", "0", "0", domain.OrderStatusCancelled},
		{"partial", "4", "4", domain.OrderStatusCancelled},
		{"full", "10", "10", domain.OrderStatusFilled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service := NewService()
			if tc.liquidity != "0" {
				service.SubmitOrder(regressionOrder("maker", domain.SideSell, tc.liquidity))
			}
			incoming := regressionOrder("ioc", domain.SideBuy, "10")
			incoming.TimeInForce = "IOC"
			result := service.SubmitOrder(incoming)
			filled, _ := strconv.Atoi(tc.fills)
			if tc.status == domain.OrderStatusCancelled {
				if result.Cancelled == nil || result.Cancelled.CancelledQuantityUnits != strconv.Itoa(10-filled) || result.Cancelled.Reason != "IOC_RESIDUAL" || result.Cancelled.OccurredAt != incoming.OccurredAt {
					t.Fatalf("wrong terminal fact: %#v", result.Cancelled)
				}
			} else if result.Cancelled != nil {
				t.Fatalf("full fill must not cancel: %#v", result.Cancelled)
			}
			if result.Accepted == nil {
				t.Fatalf("IOC not accepted: %#v", result)
			}
			state, ok := service.OrderState("run", "ioc")
			if !ok || state.Status != tc.status || state.RemainingQuantity != "0" {
				t.Fatalf("IOC terminal state: %#v", state)
			}
			if tc.fills == "0" {
				if len(result.Trades) != 0 {
					t.Fatalf("unexpected trades: %#v", result.Trades)
				}
			} else if len(result.Trades) != 1 || result.Trades[0].QuantityUnits != tc.fills {
				t.Fatalf("wrong IOC fills: %#v", result.Trades)
			}
			restored, ok := Restore(service.Snapshot())
			if !ok {
				t.Fatal("snapshot restore failed")
			}
			later := regressionOrder("later", domain.SideSell, "10")
			originalResult := service.SubmitOrder(later)
			restoredResult := restored.SubmitOrder(later)
			if len(originalResult.Trades) != 0 {
				t.Fatalf("later order filled IOC residual: %#v", originalResult.Trades)
			}
			if !reflect.DeepEqual(originalResult, restoredResult) || service.Snapshot().Checksum != restored.Snapshot().Checksum {
				t.Fatal("snapshot continuation differs")
			}
		})
	}
}

func TestTradeIdentityIsUnambiguousAndRunScoped(t *testing.T) {
	service := NewService()
	match := func(buyID, sellID, run string) domain.SubmitOrderResult {
		buy := regressionOrder(buyID, domain.SideBuy, "1")
		buy.RunID = run
		sell := regressionOrder(sellID, domain.SideSell, "1")
		sell.RunID = run
		service.SubmitOrder(buy)
		return service.SubmitOrder(sell)
	}
	first := match("a-b", "c", "run")
	second := match("a", "b-c", "run")
	third := match("a-b", "c", "other-run")
	seen := map[string]bool{}
	for _, result := range []domain.SubmitOrderResult{first, second, third} {
		if len(result.Trades) != 1 || len(result.Executions) != 2 {
			t.Fatalf("unexpected match: %#v", result)
		}
		ids := []string{result.Trades[0].TradeID, result.Trades[0].EventID, result.Trades[0].ExecutionID}
		for _, execution := range result.Executions {
			ids = append(ids, execution.ExecutionID, execution.EventID)
		}
		for _, id := range ids {
			if seen[id] {
				t.Errorf("canonical identity collision: %s", id)
			}
			seen[id] = true
		}
	}
}

func TestMatchFactsReplaySnapshotAndRollbackExactly(t *testing.T) {
	service := NewService()
	maker := regressionOrder("maker", domain.SideSell, "10")
	service.SubmitOrder(maker)
	before := service.Snapshot()
	restored, ok := Restore(before)
	if !ok {
		t.Fatal("restore failed")
	}
	taker := regressionOrder("taker", domain.SideBuy, "4")
	taker.TimeInForce = "IOC"
	batch := service.BeginBatch([]BookScope{{RunID: "run", VenueSessionID: "session", InstrumentID: "AAPL"}})
	provisional := service.SubmitOrderInBatch(batch, taker)
	batch.Rollback()
	if before.Checksum != service.Snapshot().Checksum {
		t.Fatal("rollback changed state")
	}
	retry := service.SubmitOrder(taker)
	resumed := restored.SubmitOrder(taker)
	replay := NewService()
	replay.SubmitOrder(maker)
	replayed := replay.SubmitOrder(taker)
	for _, result := range []domain.SubmitOrderResult{retry, resumed, replayed} {
		if !reflect.DeepEqual(provisional, result) {
			t.Fatalf("fact identity or economics changed: %#v vs %#v", provisional, result)
		}
	}
	if service.Snapshot().Checksum != restored.Snapshot().Checksum || service.Snapshot().Checksum != replay.Snapshot().Checksum {
		t.Fatal("continuation differs")
	}
}

func TestTradeIdentitySurvivesMatcherOrderIDReuse(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))
	match := func() domain.SubmitOrderResult {
		service.SubmitOrder(regressionOrder("buy", domain.SideBuy, "1"))
		return service.SubmitOrder(regressionOrder("sell", domain.SideSell, "1"))
	}
	first := match()
	// Evict both earlier terminal records from this lane before ID reuse.
	sweeper := regressionOrder("sweeper", domain.SideBuy, "1")
	sweeper.TimeInForce = "IOC"
	service.SubmitOrder(sweeper)
	second := match()
	if len(first.Trades) != 1 || len(second.Trades) != 1 || first.Trades[0].TradeID == second.Trades[0].TradeID {
		t.Fatalf("reused IDs collided: %#v %#v", first, second)
	}
}

func TestCoreRejectsInvalidAndMixedCurrencyBeforeMatching(t *testing.T) {
	for _, quote := range []string{"", " ", "ZZZ", "XXX", "usd", "CAD"} {
		t.Run(quote, func(t *testing.T) {
			service := NewService()
			service.SubmitOrder(regressionOrder("maker", domain.SideBuy, "10"))
			before := service.Snapshot().Checksum
			incoming := regressionOrder("wrong-currency", domain.SideSell, "4")
			incoming.Currency = quote
			result := service.SubmitOrder(incoming)
			if result.Rejected == nil || result.Rejected.Code != "CURRENCY_MISMATCH" || result.Accepted != nil || len(result.Trades) != 0 || len(result.Executions) != 0 {
				t.Fatalf("invalid currency crossed: %#v", result)
			}
			if service.Snapshot().Checksum != before {
				t.Fatal("currency rejection mutated state")
			}
		})
	}
}

func TestRestoreRejectsMixedCurrencyBook(t *testing.T) {
	service := NewService()
	service.SubmitOrder(regressionOrder("best", domain.SideSell, "1"))
	lower := regressionOrder("deeper", domain.SideSell, "1")
	lower.LimitPrice = "110"
	service.SubmitOrder(lower)
	snapshot := service.Snapshot()
	for i := range snapshot.Orders {
		if snapshot.Orders[i].OrderID == "deeper" {
			snapshot.Orders[i].Currency = "CAD"
		}
	}
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	if _, ok := Restore(snapshot); ok {
		t.Fatal("restored mixed currency book can produce contradictory execution facts")
	}
}

func TestIOCResidualRollbackRestoresExactFactsAndState(t *testing.T) {
	for _, liquidity := range []string{"0", "4"} {
		t.Run(liquidity, func(t *testing.T) {
			service := NewService(WithTerminalOrderRetentionLimit(1))
			seed := regressionOrder("seed", domain.SideBuy, "1")
			seed.LimitPrice = "90"
			service.SubmitOrder(seed)
			if liquidity != "0" {
				service.SubmitOrder(regressionOrder("maker", domain.SideSell, liquidity))
			}
			before := service.Snapshot().Checksum
			incoming := regressionOrder("ioc", domain.SideBuy, "10")
			incoming.TimeInForce = "IOC"
			batch := service.BeginBatch([]BookScope{{RunID: "run", VenueSessionID: "session", InstrumentID: "AAPL"}})
			provisional := service.SubmitOrderInBatch(batch, incoming)
			if provisional.Cancelled == nil {
				t.Fatal("missing IOC residual fact")
			}
			batch.Rollback()
			if before != service.Snapshot().Checksum {
				t.Fatal("IOC rollback changed book, terminal queue or records")
			}
			retry := service.SubmitOrder(incoming)
			if !reflect.DeepEqual(provisional, retry) {
				t.Fatalf("IOC retry changed exact facts: %#v %#v", provisional, retry)
			}
			later := service.SubmitOrder(regressionOrder("later", domain.SideSell, "10"))
			for _, trade := range later.Trades {
				if trade.BuyOrderID == "ioc" {
					t.Fatal("retry left IOC residual resting")
				}
			}
		})
	}
}
