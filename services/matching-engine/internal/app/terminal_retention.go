package app

import (
	"sync"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// terminalOrderRetention bounds how many terminal (filled/cancelled) order
// records the engine keeps around. Without a limit, every order ever
// submitted stays in memory forever; this evicts the oldest terminal orders
// once more than limit have accumulated.
type terminalOrderRetention struct {
	limit int
	mu    sync.Mutex
	order []string
	head  int
}

// track records that record just reached a terminal state and, if that
// pushes the tracked count past limit, calls evict for each order ID that
// falls out of the retention window (skipping record's own ID, which the
// caller already holds a reference to).
func (t *terminalOrderRetention) track(record *orderRecord, evict func(orderID string)) {
	if t.limit <= 0 || record.terminalTracked {
		return
	}
	if record.Status != domain.OrderStatusFilled && record.Status != domain.OrderStatusCancelled {
		return
	}

	record.terminalTracked = true
	t.commit(record.OrderID, evict)
}

// commit adds a terminal order to the global retention queue only after the
// direct-consume batch that produced it has durably published its outcome.
// Delaying this mutation keeps batch rollback local to the orders it changed;
// a failed lane can never restore an old global queue snapshot over another
// lane's successful work.
func (t *terminalOrderRetention) commit(orderID string, evict func(orderID string)) {
	if t.limit <= 0 || orderID == "" {
		return
	}

	t.mu.Lock()
	defer t.mu.Unlock()
	t.order = append(t.order, orderID)
	for len(t.order)-t.head > t.limit {
		evictID := t.order[t.head]
		t.head++
		if evictID == orderID {
			continue
		}
		evict(evictID)
	}
	if t.head > 0 && t.head*2 >= len(t.order) {
		t.order = append([]string(nil), t.order[t.head:]...)
		t.head = 0
	}
}

func (t *terminalOrderRetention) trackedOrderIDs() []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]string(nil), t.order[t.head:]...)
}
