package app

import (
	"fmt"
	"sync"
	"testing"
)

func TestOrderIndexReserveLoadRelease(t *testing.T) {
	idx := newOrderIndex()
	record := &orderRecord{OrderID: "ord-1", InstrumentID: "AAPL"}

	if !idx.reserve(record) {
		t.Fatal("expected first reserve to succeed")
	}
	loaded, ok := idx.load("ord-1")
	if !ok || loaded != record {
		t.Fatalf("expected loaded record to be the reserved pointer, got %#v ok=%v", loaded, ok)
	}

	idx.release("ord-1")
	if _, ok := idx.load("ord-1"); ok {
		t.Fatal("expected order to be gone after release")
	}
}

func TestOrderIndexReserveRejectsDuplicateID(t *testing.T) {
	idx := newOrderIndex()
	first := &orderRecord{OrderID: "ord-dup"}
	second := &orderRecord{OrderID: "ord-dup"}

	if !idx.reserve(first) {
		t.Fatal("expected first reserve to succeed")
	}
	if idx.reserve(second) {
		t.Fatal("expected duplicate order ID reserve to fail")
	}
	loaded, ok := idx.load("ord-dup")
	if !ok || loaded != first {
		t.Fatalf("expected duplicate reserve to leave original record in place, got %#v", loaded)
	}
}

func TestOrderIndexRestoreOverwritesExisting(t *testing.T) {
	idx := newOrderIndex()
	idx.reserve(&orderRecord{OrderID: "ord-1", Status: "ACCEPTED"})

	replacement := &orderRecord{OrderID: "ord-1", Status: "FILLED"}
	idx.restore(replacement)

	loaded, ok := idx.load("ord-1")
	if !ok || loaded != replacement || loaded.Status != "FILLED" {
		t.Fatalf("expected restore to overwrite existing record, got %#v", loaded)
	}
}

func TestOrderIndexRestoreOrDeleteBothBranches(t *testing.T) {
	idx := newOrderIndex()
	idx.reserve(&orderRecord{OrderID: "ord-keep", Status: "ACCEPTED"})
	idx.reserve(&orderRecord{OrderID: "ord-drop", Status: "ACCEPTED"})

	restored := &orderRecord{OrderID: "ord-keep", Status: "CANCELLED"}
	idx.restoreOrDelete("ord-keep", true, restored)
	idx.restoreOrDelete("ord-drop", false, nil)

	loaded, ok := idx.load("ord-keep")
	if !ok || loaded != restored {
		t.Fatalf("expected ord-keep restored to the given record, got %#v ok=%v", loaded, ok)
	}
	if _, ok := idx.load("ord-drop"); ok {
		t.Fatal("expected ord-drop removed by restoreOrDelete(existed=false)")
	}
}

func TestOrderIndexForEachAndLenCoverAllShards(t *testing.T) {
	idx := newOrderIndex()
	const n = 500
	want := make(map[string]bool, n)
	for i := 0; i < n; i++ {
		orderID := fmt.Sprintf("ord-%d", i)
		want[orderID] = true
		if !idx.reserve(&orderRecord{OrderID: orderID}) {
			t.Fatalf("expected reserve to succeed for %s", orderID)
		}
	}

	if got := idx.len(); got != n {
		t.Fatalf("expected len %d, got %d", n, got)
	}

	seen := make(map[string]bool, n)
	idx.forEach(func(record *orderRecord) {
		seen[record.OrderID] = true
	})
	if len(seen) != n {
		t.Fatalf("expected forEach to visit %d records, got %d", n, len(seen))
	}
	for orderID := range want {
		if !seen[orderID] {
			t.Fatalf("expected forEach to visit %s", orderID)
		}
	}
}

// TestOrderIndexShardHashSpreadsAcrossShards guards against a regression
// that collapses every order onto one shard (e.g. a hash bug always
// returning 0), which would silently rebuild the exact global-lock
// bottleneck this type exists to remove.
func TestOrderIndexShardHashSpreadsAcrossShards(t *testing.T) {
	touched := make(map[uint64]bool)
	for i := 0; i < 1000; i++ {
		orderID := fmt.Sprintf("ord-spread-%d", i)
		touched[orderIDShardHash(orderID)%orderIndexShardCount] = true
	}
	if len(touched) < orderIndexShardCount/2 {
		t.Fatalf("expected order IDs to spread across most of the %d shards, only hit %d", orderIndexShardCount, len(touched))
	}
}

// TestOrderIndexConcurrentReserveLoadReleaseRace exercises the index from
// many goroutines across many order IDs (and therefore many shards)
// simultaneously. Run with -race: any shard-selection bug that lets two
// goroutines touch the same map without holding that shard's lock will be
// caught here.
func TestOrderIndexConcurrentReserveLoadReleaseRace(t *testing.T) {
	idx := newOrderIndex()
	const goroutines = 32
	const perGoroutine = 200

	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < perGoroutine; i++ {
				orderID := fmt.Sprintf("ord-g%d-%d", g, i)
				if !idx.reserve(&orderRecord{OrderID: orderID}) {
					t.Errorf("expected reserve to succeed for %s", orderID)
					return
				}
				if _, ok := idx.load(orderID); !ok {
					t.Errorf("expected to load just-reserved %s", orderID)
					return
				}
				idx.release(orderID)
				if _, ok := idx.load(orderID); ok {
					t.Errorf("expected %s to be gone after release", orderID)
					return
				}
			}
		}(g)
	}
	wg.Wait()

	if got := idx.len(); got != 0 {
		t.Fatalf("expected index empty after all goroutines released their orders, got %d entries", got)
	}
}
