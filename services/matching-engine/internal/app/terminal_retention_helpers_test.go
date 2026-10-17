package app

import "sort"

// trackedOrderIDs is a test-only view of retained order IDs in eviction order.
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
