package app

import (
	"reflect"
	"testing"

	hotbook "github.com/dills122/reef/services/matching-engine/internal/book"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func runOrder(id, run string, side domain.Side) domain.SubmitOrder {
	return domain.SubmitOrder{CommandID: "cmd-" + id, OrderID: id, RunID: run, VenueSessionID: "session", InstrumentID: "AAPL", Side: side, QuantityUnits: "10", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"}
}

func TestRunBooksIsolateMatchingAndLifecycle(t *testing.T) {
	s := NewService()
	s.SubmitOrder(runOrder("buy-a", "run-a", domain.SideBuy))
	other := s.SubmitOrder(runOrder("sell-b", "run-b", domain.SideSell))
	if other.Accepted == nil || len(other.Trades) != 0 {
		t.Fatalf("cross-run match: %+v", other)
	}
	modified := s.ModifyOrder(domain.ModifyOrder{OrderID: "sell-b", QuantityUnits: "10", LimitPrice: "99", OccurredAt: "2026-09-30T00:00:01Z"})
	if modified.Accepted == nil || len(modified.Trades) != 0 {
		t.Fatalf("modify crossed runs: %+v", modified)
	}
	if result := s.CancelOrder(domain.CancelOrder{OrderID: "sell-b"}); result.Accepted == nil {
		t.Fatalf("cancel failed: %+v", result)
	}
	matched := s.SubmitOrder(runOrder("sell-a", "run-a", domain.SideSell))
	if len(matched.Trades) != 1 || matched.Trades[0].BuyOrderID != "buy-a" {
		t.Fatalf("same-run match missing: %+v", matched)
	}
}

func TestRunPartitionReplayIndependentOfLaneInterleave(t *testing.T) {
	a := runOrder("buy-a", "run-a", domain.SideBuy)
	b := runOrder("sell-b", "run-b", domain.SideSell)
	apply := func(reverse bool) ([]domain.SubmitOrderResult, string) {
		s := NewService()
		results := make([]domain.SubmitOrderResult, 3)
		results[0] = s.SubmitOrder(a)
		if reverse {
			results[2] = s.CancelOrder(domain.CancelOrder{OrderID: a.OrderID, OccurredAt: "2026-09-30T00:00:01Z"})
			results[1] = s.SubmitOrder(b)
		} else {
			results[1] = s.SubmitOrder(b)
			results[2] = s.CancelOrder(domain.CancelOrder{OrderID: a.OrderID, OccurredAt: "2026-09-30T00:00:01Z"})
		}
		return results, s.Snapshot().Checksum
	}
	live, liveChecksum := apply(false)
	replay, replayChecksum := apply(true)
	if !reflect.DeepEqual(live, replay) || liveChecksum != replayChecksum {
		t.Fatalf("partition replay drift: live=%+v replay=%+v checksums=%s/%s", live, replay, liveChecksum, replayChecksum)
	}
}

func TestRestoreRejectsLegacyRunScopedSnapshot(t *testing.T) {
	s := NewService()
	s.SubmitOrder(runOrder("buy-a", "run-a", domain.SideBuy))
	snap := s.Snapshot()
	snap.Metadata.SnapshotVersion = "matching-service-snapshot-v2"
	snap.Checksum = serviceSnapshotChecksum(snap.withoutChecksum())
	if _, ok := Restore(snap); ok {
		t.Fatal("legacy shared-book snapshot must rebuild from command log")
	}
}

func TestRunScopeQueriesAndSnapshotRestore(t *testing.T) {
	s := NewService()
	for _, run := range []string{"", "run-a", "run-b"} {
		s.SubmitOrder(runOrder("buy-"+run, run, domain.SideBuy))
	}
	if n := s.RestingOrdersInSession("session", "AAPL", domain.SideBuy); n != 3 {
		t.Fatalf("session aggregation=%d", n)
	}
	if n := s.RestingOrders("AAPL", domain.SideBuy); n != 3 {
		t.Fatalf("instrument aggregation=%d", n)
	}
	if n := s.BookStats("AAPL").BuyOrders; n != 3 {
		t.Fatalf("book stats aggregation=%d", n)
	}
	scope := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	if n := s.RestingOrdersInScope(scope, domain.SideBuy); n != 1 {
		t.Fatalf("scope count=%d", n)
	}
	filtered, ok := s.SnapshotForScope(scope)
	if !ok || len(filtered.Books) != 1 || len(filtered.Orders) != 1 || filtered.Orders[0].RunID != "run-a" {
		t.Fatalf("scope snapshot=%+v", filtered)
	}
	if restored, ok := Restore(filtered); !ok || restored.Snapshot().Checksum != filtered.Checksum {
		t.Fatal("scope restore failed")
	}
	snapshot := s.Snapshot()
	restored, ok := Restore(snapshot)
	if !ok || restored.Snapshot().Checksum != snapshot.Checksum {
		t.Fatal("multi-run restore failed")
	}
	if match := restored.SubmitOrder(runOrder("sell-a", "run-a", domain.SideSell)); len(match.Trades) != 1 || match.Trades[0].BuyOrderID != "buy-run-a" {
		t.Fatalf("restored match=%+v", match)
	}
}

func TestRunRollbackPreservesOtherRunCommit(t *testing.T) {
	s := NewService()
	for _, run := range []string{"run-a", "run-b"} {
		s.SubmitOrder(runOrder("buy-"+run, run, domain.SideBuy))
	}
	failed := s.BeginBatch([]BookScope{{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}})
	s.SubmitOrderInBatch(failed, runOrder("sell-a", "run-a", domain.SideSell))
	committed := s.BeginBatch([]BookScope{{RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}})
	s.SubmitOrderInBatch(committed, runOrder("sell-b", "run-b", domain.SideSell))
	committed.Commit()
	failed.Rollback()
	state, _ := s.OrderState("buy-run-a")
	if state.RemainingQuantity != "10" || state.Status != domain.OrderStatusAccepted {
		t.Fatalf("rollback state=%+v", state)
	}
	state, _ = s.OrderState("buy-run-b")
	if state.Status != domain.OrderStatusFilled {
		t.Fatalf("other run lost commit=%+v", state)
	}
	if _, ok := s.OrderState("sell-a"); ok {
		t.Fatal("failed reservation retained")
	}
	first := s.SubmitOrder(runOrder("sell-a", "run-a", domain.SideSell))
	fresh := NewService()
	fresh.SubmitOrder(runOrder("buy-run-a", "run-a", domain.SideBuy))
	want := fresh.SubmitOrder(runOrder("sell-a", "run-a", domain.SideSell))
	if !reflect.DeepEqual(first, want) {
		t.Fatalf("retry sequence drift: %+v/%+v", first, want)
	}
}

func TestBookScopeKeysAreUnambiguous(t *testing.T) {
	scopes := []BookScope{{RunID: "a|b", VenueSessionID: "c", InstrumentID: "d"}, {RunID: "a", VenueSessionID: "b|c", InstrumentID: "d"}, {InstrumentID: "a|b|c|d"}, {RunID: "é:|", VenueSessionID: "", InstrumentID: "A|B"}}
	seen := map[string]bool{}
	for _, scope := range scopes {
		key := scope.Key()
		if seen[key] {
			t.Fatalf("collision: %q", key)
		}
		seen[key] = true
		decoded, ok := parseBookKey(key)
		if !ok || decoded != scope {
			t.Fatalf("scope decode: %+v/%+v", scope, decoded)
		}
		if bookKeyMatchesInstrument(key, "B") {
			t.Fatal("instrument suffix matched wrong scope")
		}
	}
}

func TestLegacyEmptyRunSnapshotMigration(t *testing.T) {
	s := NewService()
	s.SubmitOrder(runOrder("buy", "", domain.SideBuy))
	snap := s.Snapshot()
	book := snap.Books[bookKey("", "session", "AAPL")]
	snap.Books = map[string]hotbook.Snapshot{"session|AAPL": book}
	snap.Metadata.SnapshotVersion = "matching-service-snapshot-v2"
	snap.Metadata.BookKeys = []string{"session|AAPL"}
	snap.Checksum = serviceSnapshotChecksum(snap.withoutChecksum())
	restored, ok := Restore(snap)
	if !ok {
		t.Fatal("empty-run legacy snapshot rejected")
	}
	if result := restored.SubmitOrder(runOrder("sell", "", domain.SideSell)); len(result.Trades) != 1 {
		t.Fatalf("legacy migration lost book=%+v", result)
	}
}

func TestRestoreRejectsMismatchedBookOrderScope(t *testing.T) {
	s := NewService()
	s.SubmitOrder(runOrder("buy", "run-a", domain.SideBuy))
	snap := s.Snapshot()
	snap.Orders[0].RunID = "run-b"
	snap.Checksum = serviceSnapshotChecksum(snap.withoutChecksum())
	if _, ok := Restore(snap); ok {
		t.Fatal("snapshot scope contradiction restored")
	}
}
