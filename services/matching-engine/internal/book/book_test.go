package book

import (
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestBookPreservesBuyPriceTimePriority(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 100))
	book.Add(domain.SideBuy, book.NewRestingOrder("b3", 102))
	book.Add(domain.SideBuy, book.NewRestingOrder("b4", 100))

	ids := popIDs(t, book, domain.SideBuy, 4)
	expected := []string{"b3", "b1", "b2", "b4"}
	assertIDs(t, ids, expected)
}

func TestBookPreservesSellPriceTimePriority(t *testing.T) {
	book := New()
	book.Add(domain.SideSell, book.NewRestingOrder("s1", 100))
	book.Add(domain.SideSell, book.NewRestingOrder("s2", 101))
	book.Add(domain.SideSell, book.NewRestingOrder("s3", 99))
	book.Add(domain.SideSell, book.NewRestingOrder("s4", 101))

	ids := popIDs(t, book, domain.SideSell, 4)
	expected := []string{"s3", "s1", "s2", "s4"}
	assertIDs(t, ids, expected)
}

func TestBookRemoveUnlinksOrderWithoutScanningPricePriority(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b3", 101))

	if !book.Remove("b2") {
		t.Fatal("expected remove to find b2")
	}
	if book.Remove("b2") {
		t.Fatal("expected second remove to miss b2")
	}
	if book.Len(domain.SideBuy) != 2 {
		t.Fatalf("expected 2 resting buys after remove, got %d", book.Len(domain.SideBuy))
	}

	ids := popIDs(t, book, domain.SideBuy, 2)
	expected := []string{"b1", "b3"}
	assertIDs(t, ids, expected)
}

func TestBookDeletesEmptyPriceLevelAndExposesNextBest(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 100))

	if !book.Remove("b1") {
		t.Fatal("expected remove to find best price order")
	}

	best, ok := book.Best(domain.SideBuy)
	if !ok {
		t.Fatal("expected remaining buy order")
	}
	if best.OrderID != "b2" {
		t.Fatalf("expected b2 to become best after empty level delete, got %#v", best)
	}
}

func TestBookLevelCountTracksDistinctPrices(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b3", 100))

	if got := book.LevelCount(domain.SideBuy); got != 2 {
		t.Fatalf("expected two buy price levels, got %d", got)
	}
	if !book.Remove("b1") {
		t.Fatal("expected b1 remove")
	}
	if got := book.LevelCount(domain.SideBuy); got != 2 {
		t.Fatalf("expected price level with b2 to remain, got %d", got)
	}
	if !book.Remove("b2") {
		t.Fatal("expected b2 remove")
	}
	if got := book.LevelCount(domain.SideBuy); got != 1 {
		t.Fatalf("expected empty price level to be deleted, got %d", got)
	}
}

func TestBookForEachCrossingRestingScansOnlyReachableSellLevels(t *testing.T) {
	book := New()
	book.Add(domain.SideSell, book.NewRestingOrder("s1", 99))
	book.Add(domain.SideSell, book.NewRestingOrder("s2", 100))
	book.Add(domain.SideSell, book.NewRestingOrder("s3", 101))
	book.Add(domain.SideSell, book.NewRestingOrder("s4", 100))

	ids := make([]string, 0, 3)
	book.ForEachCrossingResting(domain.SideBuy, 100, func(order RestingOrder) bool {
		ids = append(ids, order.OrderID)
		return true
	})

	assertIDs(t, ids, []string{"s1", "s2", "s4"})
}

func TestBookForEachCrossingRestingScansOnlyReachableBuyLevels(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 100))
	book.Add(domain.SideBuy, book.NewRestingOrder("b3", 99))
	book.Add(domain.SideBuy, book.NewRestingOrder("b4", 101))

	ids := make([]string, 0, 3)
	book.ForEachCrossingResting(domain.SideSell, 100, func(order RestingOrder) bool {
		ids = append(ids, order.OrderID)
		return true
	})

	assertIDs(t, ids, []string{"b1", "b4", "b2"})
}

func TestBookForEachCrossingRestingStopsWhenVisitorStops(t *testing.T) {
	book := New()
	book.Add(domain.SideSell, book.NewRestingOrder("s1", 99))
	book.Add(domain.SideSell, book.NewRestingOrder("s2", 100))
	book.Add(domain.SideSell, book.NewRestingOrder("s3", 100))

	ids := make([]string, 0, 1)
	book.ForEachCrossingResting(domain.SideBuy, 100, func(order RestingOrder) bool {
		ids = append(ids, order.OrderID)
		return false
	})

	assertIDs(t, ids, []string{"s1"})
}

func TestBookSnapshotRestorePreservesPriorityAndChecksum(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))
	book.Add(domain.SideBuy, book.NewRestingOrder("b2", 100))
	book.Add(domain.SideBuy, book.NewRestingOrder("b3", 101))
	book.Add(domain.SideSell, book.NewRestingOrder("s1", 103))
	book.Add(domain.SideSell, book.NewRestingOrder("s2", 102))

	snapshot := book.Snapshot()
	if snapshot.Checksum == "" {
		t.Fatal("expected snapshot checksum")
	}

	restored, ok := Restore(snapshot)
	if !ok {
		t.Fatalf("expected snapshot restore to succeed")
	}
	if restored.Snapshot().Checksum != snapshot.Checksum {
		t.Fatalf("expected restored checksum %s, got %s", snapshot.Checksum, restored.Snapshot().Checksum)
	}

	assertIDs(t, popIDs(t, restored, domain.SideBuy, 3), []string{"b1", "b3", "b2"})
	assertIDs(t, popIDs(t, restored, domain.SideSell, 2), []string{"s2", "s1"})
}

func TestBookRestoreRejectsChecksumMismatch(t *testing.T) {
	book := New()
	book.Add(domain.SideBuy, book.NewRestingOrder("b1", 101))

	snapshot := book.Snapshot()
	snapshot.Buys[0].LimitPrice = 102

	if _, ok := Restore(snapshot); ok {
		t.Fatal("expected tampered snapshot restore to fail")
	}
}

func TestBookRestoreRejectsDuplicateOrderID(t *testing.T) {
	snapshot := Snapshot{
		NextSequence: 2,
		Buys: []SnapshotOrder{
			{OrderID: "dup", Side: domain.SideBuy, LimitPrice: 101, Sequence: 0},
		},
		Sells: []SnapshotOrder{
			{OrderID: "dup", Side: domain.SideSell, LimitPrice: 102, Sequence: 1},
		},
	}

	if _, ok := Restore(snapshot); ok {
		t.Fatal("expected duplicate order ID snapshot restore to fail")
	}
}

func TestBookRestoreRejectsReusedNextSequence(t *testing.T) {
	snapshot := Snapshot{
		NextSequence: 1,
		Buys: []SnapshotOrder{
			{OrderID: "b1", Side: domain.SideBuy, LimitPrice: 101, Sequence: 0},
			{OrderID: "b2", Side: domain.SideBuy, LimitPrice: 100, Sequence: 1},
		},
	}

	if _, ok := Restore(snapshot); ok {
		t.Fatal("expected snapshot restore to fail when next sequence is already used")
	}
}

func popIDs(t *testing.T, book *Book, side domain.Side, count int) []string {
	t.Helper()
	ids := make([]string, 0, count)
	for i := 0; i < count; i++ {
		order, ok := book.PopBest(side)
		if !ok {
			t.Fatalf("expected order %d on %s side", i, side)
		}
		ids = append(ids, order.OrderID)
	}
	return ids
}

func assertIDs(t *testing.T, got []string, want []string) {
	t.Helper()
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("unexpected order sequence: got %#v want %#v", got, want)
		}
	}
}
