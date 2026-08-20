package app

import (
	"reflect"
	"sort"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestTerminalRetentionIsIndependentOfCrossBookCompletionOrder(t *testing.T) {
	first := NewService(WithTerminalOrderRetentionLimit(2))
	second := NewService(WithTerminalOrderRetentionLimit(2))

	for _, service := range []*Service{first, second} {
		for _, fixture := range []struct {
			orderID      string
			instrumentID string
		}{
			{orderID: "ord-a", instrumentID: "AAPL"},
			{orderID: "ord-b", instrumentID: "MSFT"},
			{orderID: "ord-c", instrumentID: "NVDA"},
		} {
			result := service.SubmitOrder(domain.SubmitOrder{
				OrderID:        fixture.orderID,
				VenueSessionID: "session-1",
				InstrumentID:   fixture.instrumentID,
				Side:           domain.SideBuy,
				QuantityUnits:  "100",
				LimitPrice:     "100000000000",
				Currency:       "USD",
				OccurredAt:     "2026-08-20T12:00:00Z",
			})
			if result.Accepted == nil {
				t.Fatalf("submit %s failed: %#v", fixture.orderID, result)
			}
		}
	}

	cancelTerminalOrders(t, first, []terminalCancellation{
		{orderID: "ord-c", occurredAt: "2026-08-20T12:00:03Z"},
		{orderID: "ord-a", occurredAt: "2026-08-20T12:00:01Z"},
		{orderID: "ord-b", occurredAt: "2026-08-20T12:00:02Z"},
	})
	cancelTerminalOrders(t, second, []terminalCancellation{
		{orderID: "ord-a", occurredAt: "2026-08-20T12:00:01Z"},
		{orderID: "ord-b", occurredAt: "2026-08-20T12:00:02Z"},
		{orderID: "ord-c", occurredAt: "2026-08-20T12:00:03Z"},
	})

	wantRetained := []string{"ord-b", "ord-c"}
	if got := retainedOrderIDs(first); !reflect.DeepEqual(got, wantRetained) {
		t.Fatalf("first completion order retained %v, want %v", got, wantRetained)
	}
	if got := retainedOrderIDs(second); !reflect.DeepEqual(got, wantRetained) {
		t.Fatalf("second completion order retained %v, want %v", got, wantRetained)
	}
	if first.Snapshot().Checksum != second.Snapshot().Checksum {
		t.Fatalf("cross-book completion order changed bounded-state checksum: first=%s second=%s", first.Snapshot().Checksum, second.Snapshot().Checksum)
	}
}

func TestSnapshotRestorePreservesTerminalRetentionEvictionBoundary(t *testing.T) {
	original := NewService(WithTerminalOrderRetentionLimit(2))
	for index, orderID := range []string{"ord-a", "ord-b"} {
		instrumentID := []string{"AAPL", "MSFT"}[index]
		result := original.SubmitOrder(domain.SubmitOrder{
			OrderID:        orderID,
			VenueSessionID: "session-1",
			InstrumentID:   instrumentID,
			Side:           domain.SideBuy,
			QuantityUnits:  "100",
			LimitPrice:     "100000000000",
			Currency:       "USD",
			OccurredAt:     "2026-08-20T12:00:00Z",
		})
		if result.Accepted == nil {
			t.Fatalf("submit %s failed: %#v", orderID, result)
		}
	}
	cancelTerminalOrders(t, original, []terminalCancellation{
		{orderID: "ord-a", occurredAt: "2026-08-20T12:00:01Z"},
		{orderID: "ord-b", occurredAt: "2026-08-20T12:00:02Z"},
	})

	restored, ok := Restore(original.Snapshot(), WithTerminalOrderRetentionLimit(2))
	if !ok {
		t.Fatal("restore bounded terminal state failed")
	}

	for _, service := range []*Service{original, restored} {
		result := service.SubmitOrder(domain.SubmitOrder{
			OrderID:        "ord-c",
			VenueSessionID: "session-1",
			InstrumentID:   "NVDA",
			Side:           domain.SideBuy,
			QuantityUnits:  "100",
			LimitPrice:     "100000000000",
			Currency:       "USD",
			OccurredAt:     "2026-08-20T12:00:00Z",
		})
		if result.Accepted == nil {
			t.Fatalf("submit ord-c failed: %#v", result)
		}
	}
	cancelTerminalOrders(t, original, []terminalCancellation{{orderID: "ord-c", occurredAt: "2026-08-20T12:00:03Z"}})
	cancelTerminalOrders(t, restored, []terminalCancellation{{orderID: "ord-c", occurredAt: "2026-08-20T12:00:03Z"}})

	if got, want := retainedOrderIDs(restored), retainedOrderIDs(original); !reflect.DeepEqual(got, want) {
		t.Fatalf("restored retention boundary drifted: got %v want %v", got, want)
	}
	if restored.Snapshot().Checksum != original.Snapshot().Checksum {
		t.Fatalf("restored bounded-state checksum drifted: got %s want %s", restored.Snapshot().Checksum, original.Snapshot().Checksum)
	}
}

func TestTerminalRetentionOrdersFractionalRFC3339TimestampsChronologically(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))
	for _, orderID := range []string{"ord-exact", "ord-fractional"} {
		result := service.SubmitOrder(domain.SubmitOrder{
			OrderID:        orderID,
			VenueSessionID: "session-1",
			InstrumentID:   "AAPL-" + orderID,
			Side:           domain.SideBuy,
			QuantityUnits:  "100",
			LimitPrice:     "100000000000",
			Currency:       "USD",
			OccurredAt:     "2026-08-20T12:00:00Z",
		})
		if result.Accepted == nil {
			t.Fatalf("submit %s failed: %#v", orderID, result)
		}
	}

	cancelTerminalOrders(t, service, []terminalCancellation{
		{orderID: "ord-fractional", occurredAt: "2026-08-20T12:00:00.1Z"},
		{orderID: "ord-exact", occurredAt: "2026-08-20T12:00:00Z"},
	})

	if got, want := retainedOrderIDs(service), []string{"ord-fractional"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("retained %v, want chronologically newest %v", got, want)
	}
}

type terminalCancellation struct {
	orderID    string
	occurredAt string
}

func cancelTerminalOrders(t *testing.T, service *Service, cancellations []terminalCancellation) {
	t.Helper()
	for _, cancellation := range cancellations {
		result := service.CancelOrder(domain.CancelOrder{
			OrderID:    cancellation.orderID,
			OccurredAt: cancellation.occurredAt,
		})
		if result.Accepted == nil {
			t.Fatalf("cancel %s failed: %#v", cancellation.orderID, result)
		}
	}
}

func retainedOrderIDs(service *Service) []string {
	ids := make([]string, 0)
	service.orderIndex.forEach(func(record *orderRecord) {
		if record.Status == domain.OrderStatusFilled || record.Status == domain.OrderStatusCancelled {
			ids = append(ids, record.OrderID)
		}
	})
	sort.Strings(ids)
	return ids
}
