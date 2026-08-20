package app

import (
	"container/heap"
	"sort"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// terminalOrderRetention bounds how many terminal (filled/cancelled) order
// records the engine keeps around. Without a limit, every order ever
// submitted stays in memory forever. With a limit, it retains the terminal
// records with the greatest deterministic (terminal time, order id) keys so
// cross-book commit scheduling cannot change the retained state.
type terminalOrderRetention struct {
	limit   int
	mu      sync.Mutex
	entries terminalRetentionHeap
}

type terminalRetentionEntry struct {
	orderID    string
	terminalAt string
}

type terminalRetentionHeap []terminalRetentionEntry

func (h terminalRetentionHeap) Len() int { return len(h) }

func (h terminalRetentionHeap) Less(i int, j int) bool {
	if h[i].terminalAt != h[j].terminalAt {
		return h[i].terminalAt < h[j].terminalAt
	}
	return h[i].orderID < h[j].orderID
}

func (h terminalRetentionHeap) Swap(i int, j int) { h[i], h[j] = h[j], h[i] }

func (h *terminalRetentionHeap) Push(value any) {
	*h = append(*h, value.(terminalRetentionEntry))
}

func (h *terminalRetentionHeap) Pop() any {
	old := *h
	last := len(old) - 1
	entry := old[last]
	old[last] = terminalRetentionEntry{}
	*h = old[:last]
	return entry
}

// track records that record just reached a terminal state and, if that
// pushes the tracked count past limit, calls evict for the order ID with the
// smallest deterministic retention key.
func (t *terminalOrderRetention) track(record *orderRecord, evict func(orderID string)) {
	if t.limit <= 0 || record.terminalTracked {
		return
	}
	if record.Status != domain.OrderStatusFilled && record.Status != domain.OrderStatusCancelled {
		return
	}

	record.terminalTracked = true
	t.commit(record, evict)
}

// commit adds a terminal order to the deterministic retained set only after
// the direct-consume batch that produced it has durably published its outcome.
// Delaying this mutation keeps batch rollback local to the orders it changed;
// a failed lane can never restore an old global queue snapshot over another
// lane's successful work.
func (t *terminalOrderRetention) commit(record *orderRecord, evict func(orderID string)) {
	if t.limit <= 0 || record == nil || record.OrderID == "" {
		return
	}

	t.mu.Lock()
	defer t.mu.Unlock()
	heap.Push(&t.entries, terminalRetentionEntry{
		orderID:    record.OrderID,
		terminalAt: normalizedTerminalTime(record.LastUpdatedAt),
	})
	if t.entries.Len() > t.limit {
		evicted := heap.Pop(&t.entries).(terminalRetentionEntry)
		evict(evicted.orderID)
	}
}

func (t *terminalOrderRetention) trackedOrderIDs() []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	entries := append([]terminalRetentionEntry(nil), t.entries...)
	sort.Slice(entries, func(i int, j int) bool {
		if entries[i].terminalAt != entries[j].terminalAt {
			return entries[i].terminalAt < entries[j].terminalAt
		}
		return entries[i].orderID < entries[j].orderID
	})
	orderIDs := make([]string, 0, len(entries))
	for _, entry := range entries {
		orderIDs = append(orderIDs, entry.orderID)
	}
	return orderIDs
}

func normalizedTerminalTime(raw string) string {
	parsed, err := time.Parse(time.RFC3339, raw)
	if err != nil {
		return "0:" + raw
	}
	return "1:" + parsed.UTC().Format("2006-01-02T15:04:05.000000000Z07:00")
}
