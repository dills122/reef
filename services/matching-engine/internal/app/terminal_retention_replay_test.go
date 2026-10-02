package app

import (
	"fmt"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
	"reflect"
	"testing"
)

func TestTerminalRetentionDoesNotChangeCrossRunReplayOutcome(t *testing.T) {
	run := func(interleave bool) domain.SubmitOrderResult {
		s := NewService(WithTerminalOrderRetentionLimit(1))
		submit := func(id, run string) {
			result := s.SubmitOrder(domain.SubmitOrder{CommandID: "submit-" + id, OrderID: id, RunID: run, VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"})
			if result.Accepted == nil {
				t.Fatalf("submit %s: %+v", id, result)
			}
		}
		cancel := func(id, run, command, at string) domain.SubmitOrderResult {
			return s.CancelOrder(domain.CancelOrder{CommandID: command, OrderID: id, RunID: run, VenueSessionID: "session", InstrumentID: "AAPL", OccurredAt: at})
		}
		laneB := func() {
			submit("b", "run-b")
			if r := cancel("b", "run-b", "cancel-b", "2026-09-30T00:00:02Z"); r.Accepted == nil {
				t.Fatalf("cancel b: %+v", r)
			}
		}
		submit("a", "run-a")
		if r := cancel("a", "run-a", "cancel-a", "2026-09-30T00:00:01Z"); r.Accepted == nil {
			t.Fatalf("cancel a: %+v", r)
		}
		if interleave {
			laneB()
		}
		result := cancel("a", "run-a", "cancel-a-again", "2026-09-30T00:00:03Z")
		if !interleave {
			laneB()
		}
		return result
	}
	live, replay := run(true), run(false)
	if live.Rejected == nil || replay.Rejected == nil {
		t.Fatalf("expected rejections: live=%+v replay=%+v", live, replay)
	}
	if *live.Rejected != *replay.Rejected {
		t.Fatalf("same lane commands drift: live=%+v replay=%+v", *live.Rejected, *replay.Rejected)
	}
}

func TestTerminalRetentionLegacySnapshotRequiresUnboundedRestore(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))
	service.SubmitOrder(domain.SubmitOrder{OrderID: "a", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"})
	snapshot := service.Snapshot()
	snapshot.Metadata.SnapshotVersion = "matching-service-snapshot-v3"
	snapshot.Metadata.TerminalRetentionPolicy = ""
	snapshot.Metadata.TerminalRetentionLimit = 0
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(1)); ok {
		t.Fatal("legacy snapshot cannot prove lane-local retention history")
	}
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0)); !ok {
		t.Fatal("legacy snapshot remains readable with retention disabled")
	}
}

func TestTerminalRetentionSnapshotRejectsChangedLimit(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))
	service.SubmitOrder(domain.SubmitOrder{OrderID: "a", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"})
	if _, ok := Restore(service.Snapshot(), WithTerminalOrderRetentionLimit(2)); ok {
		t.Fatal("different retention limit changes future command outcomes")
	}
}

func TestTerminalRetentionOutcomeDoesNotDependOnBatchCut(t *testing.T) {
	run := func(oneBatch bool) domain.SubmitOrderResult {
		s := NewService(WithTerminalOrderRetentionLimit(1))
		rb := s.BeginBatch([]BookScope{{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}})
		for index, id := range []string{"a", "b"} {
			if r := s.SubmitOrderInBatch(rb, domain.SubmitOrder{CommandID: "submit-" + id, OrderID: id, RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"}); r.Accepted == nil {
				t.Fatal(r)
			}
			at := []string{"2026-09-30T00:00:01Z", "2026-09-30T00:00:02Z"}[index]
			if r := s.CancelOrderInBatch(rb, domain.CancelOrder{CommandID: "cancel-" + id, OrderID: id, RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", OccurredAt: at}); r.Accepted == nil {
				t.Fatal(r)
			}
			if !oneBatch {
				rb.Commit()
				rb = s.BeginBatch([]BookScope{{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}})
			}
		}
		result := s.CancelOrderInBatch(rb, domain.CancelOrder{CommandID: "again", OrderID: "a", RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL", OccurredAt: "2026-09-30T00:00:03Z"})
		rb.Commit()
		return result
	}
	one, many := run(true), run(false)
	if one.Rejected == nil || many.Rejected == nil || *one.Rejected != *many.Rejected {
		t.Fatalf("batch cuts changed outcome: one=%+v many=%+v", one.Rejected, many.Rejected)
	}
}

func retentionSubmit(t *testing.T, s *Service, rb *BatchRollback, id string, scope BookScope, side domain.Side, participant string) domain.SubmitOrderResult {
	t.Helper()
	cmd := domain.SubmitOrder{CommandID: "submit-" + id, OrderID: id, RunID: scope.RunID, VenueSessionID: scope.VenueSessionID, InstrumentID: scope.InstrumentID, Side: side, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", ParticipantID: participant, OccurredAt: "2026-09-30T00:00:00Z"}
	result := s.SubmitOrderInBatch(rb, cmd)
	if result.Accepted == nil {
		t.Fatalf("submit %s: %+v", id, result)
	}
	return result
}

func retentionCancel(t *testing.T, s *Service, rb *BatchRollback, id string, scope BookScope, at string) {
	t.Helper()
	result := s.CancelOrderInBatch(rb, domain.CancelOrder{CommandID: "cancel-" + id, OrderID: id, RunID: scope.RunID, VenueSessionID: scope.VenueSessionID, InstrumentID: scope.InstrumentID, OccurredAt: at})
	if result.Accepted == nil {
		t.Fatalf("cancel %s: %+v", id, result)
	}
}

func TestTerminalRetentionIsolatedAcrossEveryBookDimension(t *testing.T) {
	scope := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	for _, other := range []BookScope{{RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}, {RunID: "run-a", VenueSessionID: "other-session", InstrumentID: "AAPL"}, {RunID: "run-a", VenueSessionID: "session", InstrumentID: "MSFT"}} {
		t.Run(other.Key(), func(t *testing.T) {
			s := NewService(WithTerminalOrderRetentionLimit(1))
			retentionSubmit(t, s, nil, "a", scope, domain.SideBuy, "")
			retentionCancel(t, s, nil, "a", scope, "2026-09-30T00:00:01Z")
			retentionSubmit(t, s, nil, "b", other, domain.SideBuy, "")
			retentionCancel(t, s, nil, "b", other, "2026-09-30T00:00:02Z")
			restored, ok := Restore(s.Snapshot(), WithTerminalOrderRetentionLimit(1))
			if !ok {
				t.Fatal("restore failed")
			}
			for _, service := range []*Service{s, restored} {
				r := service.CancelOrder(domain.CancelOrder{CommandID: "again", OrderID: "a", RunID: scope.RunID, VenueSessionID: scope.VenueSessionID, InstrumentID: scope.InstrumentID, OccurredAt: "2026-09-30T00:00:03Z"})
				if r.Rejected == nil || r.Rejected.Code != "INVALID_STATE" {
					t.Fatalf("other lane lost cancel history: %+v", r)
				}
				r = service.ModifyOrder(domain.ModifyOrder{RunID: scope.RunID, VenueSessionID: scope.VenueSessionID, InstrumentID: scope.InstrumentID, OrderID: "a", QuantityUnits: "1", LimitPrice: "101", OccurredAt: "2026-09-30T00:00:03Z"})
				if r.Rejected == nil || r.Rejected.Code != "INVALID_STATE" {
					t.Fatalf("other lane lost modify history: %+v", r)
				}
			}
		})
	}
}

func TestTerminalRetentionBatchRollbackRestoresEvictionsAndReusedID(t *testing.T) {
	for _, at := range []string{"2026-09-30T00:00:02Z", "2026-09-29T00:00:00Z"} {
		t.Run(at, func(t *testing.T) {
			s := NewService(WithTerminalOrderRetentionLimit(1))
			scope := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
			other := BookScope{RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}
			retentionSubmit(t, s, nil, "a", scope, domain.SideBuy, "")
			retentionCancel(t, s, nil, "a", scope, "2026-09-30T00:00:01Z")
			before, _ := s.SnapshotForScope(scope)
			rb := s.BeginBatch([]BookScope{scope})
			retentionSubmit(t, s, rb, "b", scope, domain.SideBuy, "")
			retentionCancel(t, s, rb, "b", scope, at)
			if at == "2026-09-30T00:00:02Z" {
				retentionSubmit(t, s, rb, "a", scope, domain.SideBuy, "")
				retentionCancel(t, s, rb, "a", scope, "2026-09-30T00:00:03Z")
			}
			committed := s.BeginBatch([]BookScope{other})
			retentionSubmit(t, s, committed, "c", other, domain.SideBuy, "")
			retentionCancel(t, s, committed, "c", other, "2026-09-30T00:00:04Z")
			committed.Commit()
			committed.Commit()
			rb.Rollback()
			rb.Rollback()
			after, _ := s.SnapshotForScope(scope)
			if before.Checksum != after.Checksum {
				t.Fatalf("rollback changed lane: before=%s after=%s", before.Checksum, after.Checksum)
			}
			if _, ok := s.OrderState(scope.RunID, "b"); ok {
				t.Fatal("rolled-back order remains")
			}
			if r, ok := s.OrderState(other.RunID, "c"); !ok || r.Status != domain.OrderStatusCancelled {
				t.Fatal("other commit lost")
			}
			// Retry failed publication's exact commands after rollback.
			retry := s.BeginBatch([]BookScope{scope})
			retentionSubmit(t, s, retry, "b", scope, domain.SideBuy, "")
			retentionCancel(t, s, retry, "b", scope, at)
			retry.Commit()
			restored, ok := Restore(s.Snapshot(), WithTerminalOrderRetentionLimit(1))
			if !ok {
				t.Fatal("retry snapshot rejected")
			}
			if s.Snapshot().Checksum != restored.Snapshot().Checksum {
				t.Fatal("retry recovery drift")
			}
		})
	}
}

func TestTerminalRetentionFilledAndSTPRollback(t *testing.T) {
	for _, participant := range []string{"", "same-participant"} {
		t.Run(participant, func(t *testing.T) {
			s := NewService(WithTerminalOrderRetentionLimit(1), WithSelfTradePreventionMode(SelfTradePreventionCancelOldest))
			scope := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
			retentionSubmit(t, s, nil, "previous", scope, domain.SideBuy, "")
			retentionCancel(t, s, nil, "previous", scope, "2026-09-29T00:00:00Z")
			retentionSubmit(t, s, nil, "maker", scope, domain.SideSell, participant)
			before := s.Snapshot()
			rb := s.BeginBatch([]BookScope{scope})
			r := retentionSubmit(t, s, rb, "taker", scope, domain.SideBuy, participant)
			if participant == "" && len(r.Trades) != 1 {
				t.Fatal("fill missing")
			}
			if participant != "" && len(r.Trades) != 0 {
				t.Fatal("self trade occurred")
			}
			rb.Rollback()
			if before.Checksum != s.Snapshot().Checksum {
				t.Fatal("fill/STP rollback lost terminal or resting state")
			}
		})
	}
}

func TestTerminalRetentionProvisionalEvictionKeepsRunIDReservation(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(1))
	a := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	b := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "MSFT"}
	retentionSubmit(t, s, nil, "a", a, domain.SideBuy, "")
	retentionCancel(t, s, nil, "a", a, "2026-09-30T00:00:01Z")
	failed := s.BeginBatch([]BookScope{a})
	retentionSubmit(t, s, failed, "b", a, domain.SideBuy, "")
	retentionCancel(t, s, failed, "b", a, "2026-09-30T00:00:02Z")
	committed := s.BeginBatch([]BookScope{b})
	cmd := domain.SubmitOrder{OrderID: "a", RunID: b.RunID, VenueSessionID: b.VenueSessionID, InstrumentID: b.InstrumentID, Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:00Z"}
	result := s.SubmitOrderInBatch(committed, cmd)
	if result.Rejected == nil || result.Rejected.Code != "DUPLICATE_ORDER_ID" {
		t.Fatalf("provisional eviction released another lane's ID reservation: %+v", result)
	}
	retentionSubmit(t, s, committed, "c", b, domain.SideBuy, "")
	committed.Commit()
	failed.Rollback()
	if restored, ok := s.loadOrder(a.RunID, "a"); !ok || restored.RunID != a.RunID || restored.Status != domain.OrderStatusCancelled {
		t.Fatal("rollback lost original reservation")
	}
	if _, ok := s.OrderState(b.RunID, "c"); !ok {
		t.Fatal("rollback lost other lane commit")
	}
	retry := s.BeginBatch([]BookScope{a})
	retentionSubmit(t, s, retry, "b", a, domain.SideBuy, "")
	retentionCancel(t, s, retry, "b", a, "2026-09-30T00:00:02Z")
	retry.Commit()
	result = s.SubmitOrder(cmd)
	if result.Accepted == nil {
		t.Fatalf("committed eviction did not release ID: %+v", result)
	}
}

func TestTerminalRetentionSnapshotPolicyIsChecksumCoveredAndValidated(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(1))
	retentionSubmit(t, s, nil, "a", BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}, domain.SideBuy, "")
	snapshot := s.Snapshot()
	snapshot.Metadata.TerminalRetentionLimit = 2
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(2)); ok {
		t.Fatal("limit tampering passed checksum")
	}
	snapshot = s.Snapshot()
	snapshot.Metadata.TerminalRetentionPolicy = "unknown-policy"
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(1)); ok {
		t.Fatal("unknown retention policy accepted")
	}
}

func TestTerminalRetentionCrossBookReuseDoesNotDependOnBatchCut(t *testing.T) {
	a := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	b := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "MSFT"}
	run := func(oneBatch bool) domain.SubmitOrderResult {
		s := NewService(WithTerminalOrderRetentionLimit(1))
		retentionSubmit(t, s, nil, "a", a, domain.SideBuy, "")
		rb := s.BeginBatch([]BookScope{a, b})
		retentionCancel(t, s, rb, "a", a, "2026-09-30T00:00:01Z")
		retentionSubmit(t, s, rb, "b", a, domain.SideBuy, "")
		retentionCancel(t, s, rb, "b", a, "2026-09-30T00:00:02Z")
		if !oneBatch {
			rb.Commit()
			rb = s.BeginBatch([]BookScope{b})
		}
		result := s.SubmitOrderInBatch(rb, domain.SubmitOrder{CommandID: "reuse-a", OrderID: "a", RunID: b.RunID, VenueSessionID: b.VenueSessionID, InstrumentID: b.InstrumentID, Side: domain.SideBuy, QuantityUnits: "1", LimitPrice: "100", Currency: "USD", OccurredAt: "2026-09-30T00:00:03Z"})
		rb.Commit()
		return result
	}
	one, many := run(true), run(false)
	if one.Accepted == nil || many.Accepted == nil || !reflect.DeepEqual(one, many) {
		t.Fatalf("cross-book reused ID changed batchcut outcome: one=%+v many=%+v", one, many)
	}
}

func TestTerminalRetentionCrossBookReuseRollbackRestoresRunLocalPreimage(t *testing.T) {
	for _, preexisting := range []bool{true, false} {
		t.Run(fmt.Sprint(preexisting), func(t *testing.T) {
			s := NewService(WithTerminalOrderRetentionLimit(1))
			a := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
			b := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "MSFT"}
			// Establish both empty books before checkpoint so rollback equality checks
			// order/book content rather than journal-created empty book map entries.
			s.bookFor(a.RunID, a.VenueSessionID, a.InstrumentID)
			s.bookFor(b.RunID, b.VenueSessionID, b.InstrumentID)
			if preexisting {
				retentionSubmit(t, s, nil, "a", a, domain.SideBuy, "")
			}
			before := s.Snapshot()
			rb := s.BeginBatch([]BookScope{a, b})
			if !preexisting {
				retentionSubmit(t, s, rb, "a", a, domain.SideBuy, "")
			}
			retentionCancel(t, s, rb, "a", a, "2026-09-30T00:00:01Z")
			retentionSubmit(t, s, rb, "b", a, domain.SideBuy, "")
			retentionCancel(t, s, rb, "b", a, "2026-09-30T00:00:02Z")
			retentionSubmit(t, s, rb, "a", b, domain.SideBuy, "")
			rb.Rollback()
			rb.Rollback()
			rb.Commit()
			if before.Checksum != s.Snapshot().Checksum {
				t.Fatal("cross-book reuse rollback lost run-local preimage")
			}
		})
	}
}

func TestTerminalRetentionSameIDAcrossRunsSurvivesPendingEvictionAndRestore(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(1))
	a := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	b := BookScope{RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}
	retentionSubmit(t, s, nil, "shared", a, domain.SideBuy, "")
	retentionCancel(t, s, nil, "shared", a, "2026-09-30T00:00:01Z")
	failed := s.BeginBatch([]BookScope{a})
	retentionSubmit(t, s, failed, "new-a", a, domain.SideBuy, "")
	retentionCancel(t, s, failed, "new-a", a, "2026-09-30T00:00:02Z")
	committed := s.BeginBatch([]BookScope{b})
	// Run A's provisional reservation must not reserve the same ID in run B.
	retentionSubmit(t, s, committed, "shared", b, domain.SideBuy, "")
	retentionCancel(t, s, committed, "shared", b, "2026-09-30T00:00:03Z")
	committed.Commit()
	failed.Rollback()
	snapshot := s.Snapshot()
	restored, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(1))
	if !ok {
		t.Fatal("V4 restore rejected independent same-ID terminal facts")
	}
	for _, service := range []*Service{s, restored} {
		for _, scope := range []BookScope{a, b} {
			state, ok := service.OrderState(scope.RunID, "shared")
			if !ok || state.Status != domain.OrderStatusCancelled {
				t.Fatalf("run %s lost shared terminal order: %+v", scope.RunID, state)
			}
			result := service.CancelOrder(domain.CancelOrder{CommandID: "again", RunID: scope.RunID, VenueSessionID: scope.VenueSessionID, InstrumentID: scope.InstrumentID, OrderID: "shared", OccurredAt: "2026-09-30T00:00:04Z"})
			if result.Rejected == nil || result.Rejected.Code != "INVALID_STATE" {
				t.Fatalf("run %s lost repeat outcome: %+v", scope.RunID, result)
			}
		}
		if _, ok := service.OrderState(a.RunID, "new-a"); ok {
			t.Fatal("failed run retained new order")
		}
	}
	if snapshot.Checksum != restored.Snapshot().Checksum {
		t.Fatal("run-local V4 snapshot checksum drift")
	}
}

func TestTerminalRetentionSameIDRunLocalPreimagesInOneBatch(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(1))
	a := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	b := BookScope{RunID: "run-b", VenueSessionID: "session", InstrumentID: "AAPL"}
	retentionSubmit(t, s, nil, "shared", a, domain.SideBuy, "")
	retentionSubmit(t, s, nil, "shared", b, domain.SideBuy, "")
	before := s.Snapshot()
	rb := s.BeginBatch([]BookScope{a, b})
	for _, scope := range []BookScope{a, b} {
		retentionCancel(t, s, rb, "shared", scope, "2026-09-30T00:00:01Z")
		retentionSubmit(t, s, rb, "replacement", scope, domain.SideBuy, "")
		retentionCancel(t, s, rb, "replacement", scope, "2026-09-30T00:00:02Z")
	}
	rb.Rollback()
	if before.Checksum != s.Snapshot().Checksum {
		t.Fatal("one batch shared an initial preimage across runs")
	}
	for _, scope := range []BookScope{a, b} {
		if _, ok := s.OrderState(scope.RunID, "replacement"); ok {
			t.Fatal("rollback deleted new ID in wrong run")
		}
	}
	restored, ok := Restore(s.Snapshot(), WithTerminalOrderRetentionLimit(1))
	if !ok {
		t.Fatal("same-ID resting snapshot rejected")
	}
	if before.Checksum != restored.Snapshot().Checksum {
		t.Fatal("same-ID resting restore changed state")
	}
}

func TestTerminalRetentionDirectEvictionAndReuseWithoutBatch(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(1))
	scope := BookScope{RunID: "run-a", VenueSessionID: "session", InstrumentID: "AAPL"}
	retentionSubmit(t, s, nil, "a", scope, domain.SideBuy, "")
	retentionCancel(t, s, nil, "a", scope, "2026-09-30T00:00:01Z")
	retentionSubmit(t, s, nil, "b", scope, domain.SideBuy, "")
	retentionCancel(t, s, nil, "b", scope, "2026-09-30T00:00:02Z")
	if _, ok := s.OrderState(scope.RunID, "a"); ok {
		t.Fatal("direct eviction retained older terminal record")
	}
	retentionSubmit(t, s, nil, "a", scope, domain.SideBuy, "")
	if _, ok := s.OrderState(scope.RunID, "b"); !ok {
		t.Fatal("direct ID reuse removed unrelated retained terminal order")
	}
}

func TestTerminalRetentionLegacySnapshotDefaultRestoreUsesConfiguredLimit(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(0))
	scope := BookScope{RunID: "run-default", VenueSessionID: "session", InstrumentID: "AAPL"}
	retentionSubmit(t, service, nil, "a", scope, domain.SideBuy, "")
	snapshot := service.Snapshot()
	snapshot.Metadata.SnapshotVersion = "matching-service-snapshot-v3"
	snapshot.Metadata.TerminalRetentionPolicy = ""
	snapshot.Metadata.TerminalRetentionLimit = 0
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	for _, tc := range []struct {
		name, limit string
		allowed     bool
	}{
		{"unset-default", "", true}, {"disabled", "0", true}, {"enabled", "3", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", tc.limit)
			restored, ok := Restore(snapshot)
			if ok != tc.allowed {
				t.Fatalf("default restore with limit %q: allowed=%v, want=%v", tc.limit, ok, tc.allowed)
			}
			if ok {
				got := restored.Snapshot()
				if got.Metadata.SnapshotVersion != "matching-service-snapshot-v4" || got.Metadata.TerminalRetentionLimit != 0 {
					t.Fatalf("unbounded legacy restore metadata: %+v", got.Metadata)
				}
				if _, exists := restored.OrderState(scope.RunID, "a"); !exists {
					t.Fatal("legacy order lost")
				}
			}
		})
	}
}

func TestTerminalRetentionRollbackRestoresAfterMultipleHeapReorders(t *testing.T) {
	s := NewService(WithTerminalOrderRetentionLimit(3))
	scope := BookScope{RunID: "run-heap", VenueSessionID: "session", InstrumentID: "AAPL"}
	for i, id := range []string{"a", "b", "c"} {
		retentionSubmit(t, s, nil, id, scope, domain.SideBuy, "")
		retentionCancel(t, s, nil, id, scope, fmt.Sprintf("2026-09-30T00:00:%02dZ", []int{3, 1, 2}[i]))
	}
	before := s.Snapshot()
	rb := s.BeginBatch([]BookScope{scope})
	for i, id := range []string{"d", "e", "f", "g", "h"} {
		retentionSubmit(t, s, rb, id, scope, domain.SideBuy, "")
		retentionCancel(t, s, rb, id, scope, fmt.Sprintf("2026-09-30T00:00:%02dZ", []int{6, 4, 8, 5, 7}[i]))
	}
	rb.Rollback()
	if got := s.Snapshot().Checksum; got != before.Checksum {
		t.Fatalf("rollback after heap reorders: got=%s want=%s", got, before.Checksum)
	}
	for _, id := range []string{"a", "b", "c"} {
		state, exists := s.OrderState(scope.RunID, id)
		if !exists || state.Status != domain.OrderStatusCancelled {
			t.Fatalf("original terminal %s lost: %+v", id, state)
		}
	}
	for _, id := range []string{"d", "e", "f", "g", "h"} {
		if _, exists := s.OrderState(scope.RunID, id); exists {
			t.Fatalf("rolled-back order %s remains", id)
		}
	}
	restored, ok := Restore(s.Snapshot(), WithTerminalOrderRetentionLimit(3))
	if !ok || restored.Snapshot().Checksum != before.Checksum {
		t.Fatal("rolled-back state did not survive restore")
	}
}
