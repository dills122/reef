package app

import "sync"

// orderIndexShardCount stripes the engine-wide order-record index across
// this many independent mutexes so that submit/cancel/modify traffic for one
// instrument does not serialize behind traffic for a different instrument.
// A single sync.RWMutex here previously undercut the engine's per-book
// sharding story: a mutex profile of BenchmarkSubmitOrderManyInstrumentsParallel
// (64 instruments, 8 cores) showed the write lock taken by reserveOrder
// accounting for ~65% of all measured mutex wait time even though book-level
// contention was spread across 64 distinct orderBook.mu locks. 64 shards
// matches that benchmark's instrument count as a starting point.
const orderIndexShardCount = 64

type orderShard struct {
	mu           sync.RWMutex
	orders       map[string]*orderRecord
	reservations map[string]orderReservation
}

type orderReservation struct {
	owner *BatchRollback
}

// orderIndex is a striped replacement for a flat map[string]*orderRecord
// plus one global RWMutex. Every method routes to exactly one shard by
// hashing the order ID, so unrelated orders (typically on different
// instruments) rarely contend on the same lock. Each shard's map is keyed by
// orderIndexKey(runID, orderID), not orderID alone, so two different runs
// may reuse the same order ID. Shard selection still hashes on the raw
// order ID (not the composite key) so distribution is unaffected by run ID.
type orderIndex struct {
	shards [orderIndexShardCount]orderShard
}

func newOrderIndex() *orderIndex {
	idx := &orderIndex{}
	for i := range idx.shards {
		idx.shards[i].orders = make(map[string]*orderRecord)
	}
	return idx
}

func (idx *orderIndex) shard(orderID string) *orderShard {
	return &idx.shards[orderIDShardHash(orderID)%orderIndexShardCount]
}

func (idx *orderIndex) load(runID string, orderID string) (*orderRecord, bool) {
	shard := idx.shard(orderID)
	shard.mu.RLock()
	defer shard.mu.RUnlock()
	record, ok := shard.orders[orderIndexKey(runID, orderID)]
	return record, ok
}

// reserve inserts record if no order with the same (RunID, OrderID) already
// exists, reporting whether the insert happened.
func (idx *orderIndex) reserve(record *orderRecord) bool {
	return idx.reserveInBatch(record, nil)
}

func (idx *orderIndex) reserveInBatch(record *orderRecord, owner *BatchRollback) bool {
	shard := idx.shard(record.OrderID)
	key := orderIndexKey(record.RunID, record.OrderID)
	shard.mu.Lock()
	defer shard.mu.Unlock()
	if reservation, exists := shard.reservations[key]; exists && reservation.owner != owner {
		return false
	}
	if _, exists := shard.orders[key]; exists {
		return false
	}
	shard.orders[key] = record
	return true
}

func (idx *orderIndex) release(runID string, orderID string) {
	shard := idx.shard(orderID)
	shard.mu.Lock()
	defer shard.mu.Unlock()
	delete(shard.orders, orderIndexKey(runID, orderID))
}

// releaseInBatch hides a terminal record immediately, but retains its run-local
// ID reservation until the batch closes. Another batch in this run must not
// accept this ID and have its committed record overwritten by rollback.
// Same-owner reuse across books shares one (RunID, OrderID) preimage.
func (idx *orderIndex) releaseInBatch(record *orderRecord, owner *BatchRollback) {
	key := orderIndexKey(record.RunID, record.OrderID)
	shard := idx.shard(record.OrderID)
	shard.mu.Lock()
	defer shard.mu.Unlock()
	if shard.reservations == nil {
		shard.reservations = make(map[string]orderReservation)
	}
	shard.reservations[key] = orderReservation{owner: owner}
	delete(shard.orders, key)
}

func (idx *orderIndex) releaseReservation(runID string, orderID string, owner *BatchRollback) {
	key := orderIndexKey(runID, orderID)
	shard := idx.shard(orderID)
	shard.mu.Lock()
	defer shard.mu.Unlock()
	if reservation, ok := shard.reservations[key]; ok && reservation.owner == owner {
		delete(shard.reservations, key)
	}
}

// restore unconditionally inserts record, overwriting any existing entry
// with the same (RunID, OrderID). Used only while building a Service from a
// snapshot, before the service is shared across goroutines.
func (idx *orderIndex) restore(record *orderRecord) {
	shard := idx.shard(record.OrderID)
	shard.mu.Lock()
	shard.orders[orderIndexKey(record.RunID, record.OrderID)] = record
	shard.mu.Unlock()
}

// restoreOrDelete sets or removes a single order's entry in its shard. Used
// by BatchRollback.Rollback to undo mutations one order at a time, rather
// than serializing the whole rollback behind one engine-wide lock.
func (idx *orderIndex) restoreOrDelete(runID string, orderID string, existed bool, record *orderRecord) {
	shard := idx.shard(orderID)
	key := orderIndexKey(runID, orderID)
	shard.mu.Lock()
	if existed {
		shard.orders[key] = record
	} else {
		delete(shard.orders, key)
	}
	shard.mu.Unlock()
}

// forEach calls fn once per stored order record. Each shard is visited
// under its own read lock, one shard at a time, from the calling goroutine;
// fn must not call back into the index or block.
func (idx *orderIndex) forEach(fn func(*orderRecord)) {
	for i := range idx.shards {
		shard := &idx.shards[i]
		shard.mu.RLock()
		for _, record := range shard.orders {
			fn(record)
		}
		shard.mu.RUnlock()
	}
}

func (idx *orderIndex) len() int {
	total := 0
	for i := range idx.shards {
		shard := &idx.shards[i]
		shard.mu.RLock()
		total += len(shard.orders)
		shard.mu.RUnlock()
	}
	return total
}

// orderIDShardHash is FNV-1a over the order ID, computed inline to avoid
// allocating a hash.Hash64 per call on the submit/cancel/modify hot path.
func orderIDShardHash(orderID string) uint64 {
	const offset64 = 14695981039346656037
	const prime64 = 1099511628211
	hash := uint64(offset64)
	for i := 0; i < len(orderID); i++ {
		hash ^= uint64(orderID[i])
		hash *= prime64
	}
	return hash
}
