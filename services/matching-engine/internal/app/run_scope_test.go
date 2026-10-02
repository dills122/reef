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
	modified := s.ModifyOrder(domain.ModifyOrder{OrderID: "sell-b", RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL", QuantityUnits: "10", LimitPrice: "99", OccurredAt: "2026-09-30T00:00:01Z"})
	if modified.Accepted == nil || len(modified.Trades) != 0 {
		t.Fatalf("modify crossed runs: %+v", modified)
	}
	if result := s.CancelOrder(domain.CancelOrder{OrderID: "sell-b", RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}); result.Accepted == nil {
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
	snap.Metadata.TerminalRetentionPolicy = ""
	snap.Metadata.TerminalRetentionLimit = 0
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
	state, _ := s.OrderState("run-a", "buy-run-a")
	if state.RemainingQuantity != "10" || state.Status != domain.OrderStatusAccepted {
		t.Fatalf("rollback state=%+v", state)
	}
	state, _ = s.OrderState("run-b", "buy-run-b")
	if state.Status != domain.OrderStatusFilled {
		t.Fatalf("other run lost commit=%+v", state)
	}
	if _, ok := s.OrderState("run-a", "sell-a"); ok {
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
	snap.Metadata.TerminalRetentionPolicy = ""
	snap.Metadata.TerminalRetentionLimit = 0
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

// TestValidSnapshotOrderScopesRejectsDuplicateRunOrderPair calls
// validSnapshotOrderScopes directly, bypassing Restore's own earlier
// seenOrderIDs dedup, so the function must reject the duplicate on its
// own rather than relying on its only current caller having already
// checked for it.
func TestValidSnapshotOrderScopesRejectsDuplicateRunOrderPair(t *testing.T) {
	s := NewService()
	s.SubmitOrder(runOrder("ord-dup", "run-a", domain.SideBuy))
	snap := s.Snapshot()
	if len(snap.Orders) != 1 {
		t.Fatalf("expected one order in snapshot, got %+v", snap.Orders)
	}
	snap.Orders = append(snap.Orders, snap.Orders[0])
	if validSnapshotOrderScopes(snap) {
		t.Fatal("expected duplicate (RunID, OrderID) pair to be rejected")
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

// TestReusedOrderIDAcrossRunsStaysIsolated proves the order index's actual
// isolation guarantee: two different runs may legitimately reuse the same
// order ID, each sees only its own order, and a blank-context lifecycle
// command can no longer reach either one by ID alone. Before this fix, the
// order index was keyed on OrderID alone (engine-wide), so the second
// SubmitOrder below would have been wrongly rejected as DUPLICATE_ORDER_ID,
// and a blank-context cancel/modify could reach whichever run's order
// happened to hold that ID.
func TestReusedOrderIDAcrossRunsStaysIsolated(t *testing.T) {
	s := NewService()
	a := s.SubmitOrder(domain.SubmitOrder{CommandID: "cmd-a", OrderID: "ord-shared", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "10", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"})
	if a.Accepted == nil {
		t.Fatalf("run-a submit rejected: %+v", a)
	}
	b := s.SubmitOrder(domain.SubmitOrder{CommandID: "cmd-b", OrderID: "ord-shared", RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "20", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:01Z"})
	if b.Accepted == nil {
		t.Fatalf("run-b submit with the same order ID as run-a was rejected: %+v", b)
	}

	stateA, ok := s.OrderState("run-a", "ord-shared")
	if !ok || stateA.RemainingQuantity != "10" {
		t.Fatalf("run-a order state=%+v ok=%v", stateA, ok)
	}
	stateB, ok := s.OrderState("run-b", "ord-shared")
	if !ok || stateB.RemainingQuantity != "20" {
		t.Fatalf("run-b order state=%+v ok=%v", stateB, ok)
	}

	// A blank-context cancel can no longer reach either run's order: the
	// lookup itself is scoped by the caller's claimed run, and "" is its
	// own namespace rather than a wildcard that matches any run.
	if result := s.CancelOrder(domain.CancelOrder{OrderID: "ord-shared"}); result.Rejected == nil || result.Rejected.Code != "NOT_FOUND" {
		t.Fatalf("expected blank-context cancel to miss both runs' order, got %+v", result)
	}

	if result := s.CancelOrder(domain.CancelOrder{OrderID: "ord-shared", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}); result.Accepted == nil {
		t.Fatalf("run-a cancel rejected: %+v", result)
	}
	stateA, ok = s.OrderState("run-a", "ord-shared")
	if !ok || stateA.Status != domain.OrderStatusCancelled {
		t.Fatalf("run-a order should be cancelled, got %+v ok=%v", stateA, ok)
	}
	stateB, ok = s.OrderState("run-b", "ord-shared")
	if !ok || stateB.Status != domain.OrderStatusAccepted {
		t.Fatalf("run-b order should be untouched by run-a's cancel, got %+v ok=%v", stateB, ok)
	}
}

// TestSnapshotRestoresReusedOrderIDAcrossRuns proves the snapshot path
// (dedup check, validSnapshotOrderScopes, and the checksum's order sort)
// handles two runs legitimately reusing the same order ID, rather than
// treating the collision as structural corruption.
func TestSnapshotRestoresReusedOrderIDAcrossRuns(t *testing.T) {
	s := NewService()
	s.SubmitOrder(domain.SubmitOrder{CommandID: "cmd-a", OrderID: "ord-shared", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "10", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"})
	s.SubmitOrder(domain.SubmitOrder{CommandID: "cmd-b", OrderID: "ord-shared", RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideSell, QuantityUnits: "20", LimitPrice: "101", Currency: "USD", OccurredAt: "2026-09-30T00:00:01Z"})

	snapshot := s.Snapshot()
	restored, ok := Restore(snapshot)
	if !ok || restored.Snapshot().Checksum != snapshot.Checksum {
		t.Fatal("snapshot with reused order ID across runs failed to restore")
	}
	stateA, ok := restored.OrderState("run-a", "ord-shared")
	if !ok || stateA.RemainingQuantity != "10" {
		t.Fatalf("restored run-a state=%+v ok=%v", stateA, ok)
	}
	stateB, ok := restored.OrderState("run-b", "ord-shared")
	if !ok || stateB.RemainingQuantity != "20" {
		t.Fatalf("restored run-b state=%+v ok=%v", stateB, ok)
	}
}
