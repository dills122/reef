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

	t.mu.Lock()
	defer t.mu.Unlock()
	if record.terminalTracked {
		return
	}
	record.terminalTracked = true
	t.order = append(t.order, record.OrderID)
	for len(t.order)-t.head > t.limit {
		evictID := t.order[t.head]
		t.head++
		if evictID == record.OrderID {
			continue
		}
		evict(evictID)
	}
	if t.head > 0 && t.head*2 >= len(t.order) {
		t.order = append([]string(nil), t.order[t.head:]...)
		t.head = 0
	}
}
