package app

import (
	"container/heap"
	"sort"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// terminalOrderRetention bounds terminal records per matching book/lane.
// A different run, session or instrument must never evict history used by
// this lane's next command. Each lane keeps the greatest (terminal time,
// order ID) keys; lane count itself is not bounded by this option.
type terminalOrderRetention struct {
	limit int
	mu    sync.Mutex
	lanes map[string]*terminalRetentionHeap
}

type terminalRetentionEntry struct {
	runID      string
	orderID    string
	terminalAt string
	index      int
}

// A batch journals heap deltas, never a whole retained lane. Entry indices
// let rollback undo each terminal transition in O(log limit), in reverse order.
type terminalRetentionMutation struct {
	lane    string
	added   *terminalRetentionEntry
	evicted *terminalRetentionEntry
}

type terminalRetentionHeap []*terminalRetentionEntry

func (h terminalRetentionHeap) Len() int { return len(h) }

// Less breaks ties on orderID with runID so ordering stays deterministic
// even when two different runs reach a terminal state for the same order
// ID at the same terminalAt - order IDs are only unique within one run.
func (h terminalRetentionHeap) Less(i int, j int) bool {
	if h[i].terminalAt != h[j].terminalAt {
		return h[i].terminalAt < h[j].terminalAt
	}
	if h[i].orderID != h[j].orderID {
		return h[i].orderID < h[j].orderID
	}
	return h[i].runID < h[j].runID
}

func (h terminalRetentionHeap) Swap(i int, j int) {
	h[i], h[j] = h[j], h[i]
	h[i].index, h[j].index = i, j
}

func (h *terminalRetentionHeap) Push(value any) {
	entry := value.(*terminalRetentionEntry)
	entry.index = len(*h)
	*h = append(*h, entry)
}

func (h *terminalRetentionHeap) Pop() any {
	old := *h
	last := len(old) - 1
	entry := old[last]
	old[last] = nil
	entry.index = -1
	*h = old[:last]
	return entry
}

// track applies retention at the terminal transition, even inside a batch:
// changing a publication batch cut must not change subsequent command outcomes.
func (t *terminalOrderRetention) track(record *orderRecord, evict func(runID string, orderID string)) *terminalRetentionMutation {
	if t.limit <= 0 || record.terminalTracked {
		return nil
	}
	if record.Status != domain.OrderStatusFilled && record.Status != domain.OrderStatusCancelled {
		return nil
	}
	record.terminalTracked = true
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.lanes == nil {
		t.lanes = make(map[string]*terminalRetentionHeap)
	}
	lane := bookKey(record.RunID, record.VenueSessionID, record.InstrumentID)
	entries := t.lanes[lane]
	if entries == nil {
		entries = &terminalRetentionHeap{}
		t.lanes[lane] = entries
	}
	entry := &terminalRetentionEntry{runID: record.RunID, orderID: record.OrderID, terminalAt: normalizedTerminalTime(record.LastUpdatedAt), index: -1}
	mutation := &terminalRetentionMutation{lane: lane, added: entry}
	heap.Push(entries, entry)
	if entries.Len() > t.limit {
		mutation.evicted = heap.Pop(entries).(*terminalRetentionEntry)
		evict(mutation.evicted.runID, mutation.evicted.orderID)
	}
	return mutation
}

// rollback requires the same exclusive lane ownership as book/order rollback.
// Other lanes can commit concurrently; their heaps are never restored here.
func (t *terminalOrderRetention) rollback(mutations []terminalRetentionMutation) {
	t.mu.Lock()
	defer t.mu.Unlock()
	for i := len(mutations) - 1; i >= 0; i-- {
		mutation := mutations[i]
		if mutation.added == mutation.evicted {
			continue
		}
		entries := t.lanes[mutation.lane]
		heap.Remove(entries, mutation.added.index)
		if mutation.evicted != nil {
			heap.Push(entries, mutation.evicted)
		}
		if entries.Len() == 0 {
			delete(t.lanes, mutation.lane)
		}
	}
}

func (t *terminalOrderRetention) trackedOrderIDs() []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	entries := make(terminalRetentionHeap, 0)
	for _, lane := range t.lanes {
		entries = append(entries, (*lane)...)
	}
	sort.Slice(entries, func(i, j int) bool { return entries.Less(i, j) })
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
