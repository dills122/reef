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
