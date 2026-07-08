package book

import (
	"crypto/sha256"
	"encoding/hex"
	"hash"
	"strconv"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
	"github.com/tidwall/btree"
)

type RestingOrder struct {
	OrderID    string
	LimitPrice int64
	Sequence   int64
}

type Book struct {
	nextSequence int64
	buys         sideBook
	sells        sideBook
	orders       map[string]*orderNode
}

type Snapshot struct {
	NextSequence int64           `json:"nextSequence"`
	Buys         []SnapshotOrder `json:"buys"`
	Sells        []SnapshotOrder `json:"sells"`
	Checksum     string          `json:"checksum"`
}

type SnapshotOrder struct {
	OrderID    string      `json:"orderId"`
	Side       domain.Side `json:"side"`
	LimitPrice int64       `json:"limitPrice"`
	Sequence   int64       `json:"sequence"`
}

type sideBook struct {
	side   domain.Side
	levels *btree.Map[int64, *priceLevel]
	total  int
}

type priceLevel struct {
	price int64
	head  *orderNode
	tail  *orderNode
	count int
}

type orderNode struct {
	order RestingOrder
	side  domain.Side
	level *priceLevel
	prev  *orderNode
	next  *orderNode
}

func New() *Book {
	return &Book{
		buys:   newSideBook(domain.SideBuy),
		sells:  newSideBook(domain.SideSell),
		orders: make(map[string]*orderNode),
	}
}

func (b *Book) NewRestingOrder(orderID string, limitPrice int64) RestingOrder {
	sequence := b.nextSequence
	b.nextSequence++
	return RestingOrder{
		OrderID:    orderID,
		LimitPrice: limitPrice,
		Sequence:   sequence,
	}
}

func (b *Book) Add(side domain.Side, order RestingOrder) {
	sideBook := b.side(side)
	level := sideBook.level(order.LimitPrice)
	node := &orderNode{
		order: order,
		side:  side,
		level: level,
	}
	if level.tail == nil {
		level.head = node
		level.tail = node
	} else {
		node.prev = level.tail
		level.tail.next = node
		level.tail = node
	}
	level.count++
	sideBook.total++
	b.orders[order.OrderID] = node
}

func (b *Book) Best(side domain.Side) (RestingOrder, bool) {
	level, ok := b.side(side).bestLevel()
	if !ok || level.head == nil {
		return RestingOrder{}, false
	}
	return level.head.order, true
}

func (b *Book) PopBest(side domain.Side) (RestingOrder, bool) {
	level, ok := b.side(side).bestLevel()
	if !ok || level.head == nil {
		return RestingOrder{}, false
	}
	node := level.head
	b.removeNode(node)
	return node.order, true
}

func (b *Book) Remove(orderID string) bool {
	node, ok := b.orders[orderID]
	if !ok {
		return false
	}
	b.removeNode(node)
	return true
}

func (b *Book) Len(side domain.Side) int {
	return b.side(side).total
}

func (b *Book) LevelCount(side domain.Side) int {
	count := 0
	b.side(side).levels.Scan(func(_ int64, _ *priceLevel) bool {
		count++
		return true
	})
	return count
}

func (b *Book) Snapshot() Snapshot {
	snapshot := Snapshot{
		NextSequence: b.nextSequence,
		Buys:         b.buys.snapshotOrders(),
		Sells:        b.sells.snapshotOrders(),
	}
	snapshot.Checksum = checksum(snapshot)
	return snapshot
}

func Restore(snapshot Snapshot) (*Book, bool) {
	if snapshot.Checksum != "" && snapshot.Checksum != checksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	restored := New()
	restored.nextSequence = snapshot.NextSequence
	seenOrderIDs := make(map[string]bool, len(snapshot.Buys)+len(snapshot.Sells))
	maxSequence := int64(-1)
	for _, order := range snapshot.Buys {
		if !validSnapshotOrder(order, domain.SideBuy, seenOrderIDs, &maxSequence) {
			return nil, false
		}
		restored.Add(order.Side, RestingOrder{
			OrderID:    order.OrderID,
			LimitPrice: order.LimitPrice,
			Sequence:   order.Sequence,
		})
	}
	for _, order := range snapshot.Sells {
		if !validSnapshotOrder(order, domain.SideSell, seenOrderIDs, &maxSequence) {
			return nil, false
		}
		restored.Add(order.Side, RestingOrder{
			OrderID:    order.OrderID,
			LimitPrice: order.LimitPrice,
			Sequence:   order.Sequence,
		})
	}
	if snapshot.NextSequence < 0 || snapshot.NextSequence <= maxSequence {
		return nil, false
	}
	if restored.Snapshot().Checksum != checksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	return restored, true
}

func (b *Book) side(side domain.Side) *sideBook {
	if side == domain.SideBuy {
		return &b.buys
	}
	return &b.sells
}

func (b *Book) removeNode(node *orderNode) {
	if node.prev != nil {
		node.prev.next = node.next
	} else {
		node.level.head = node.next
	}
	if node.next != nil {
		node.next.prev = node.prev
	} else {
		node.level.tail = node.prev
	}

	node.level.count--
	sideBook := b.side(node.side)
	sideBook.total--
	delete(b.orders, node.order.OrderID)
	if node.level.count == 0 {
		sideBook.levels.Delete(node.level.price)
	}
	node.prev = nil
	node.next = nil
	node.level = nil
}

func newSideBook(side domain.Side) sideBook {
	return sideBook{
		side:   side,
		levels: btree.NewMap[int64, *priceLevel](32),
	}
}

func (s *sideBook) level(price int64) *priceLevel {
	level, ok := s.levels.Get(price)
	if ok {
		return level
	}
	level = &priceLevel{price: price}
	s.levels.Set(price, level)
	return level
}

func (s *sideBook) bestLevel() (*priceLevel, bool) {
	if s.side == domain.SideBuy {
		_, level, ok := s.levels.Max()
		return level, ok
	}
	_, level, ok := s.levels.Min()
	return level, ok
}

func (s *sideBook) snapshotOrders() []SnapshotOrder {
	orders := make([]SnapshotOrder, 0, s.total)
	appendLevel := func(_ int64, level *priceLevel) bool {
		for node := level.head; node != nil; node = node.next {
			orders = append(orders, SnapshotOrder{
				OrderID:    node.order.OrderID,
				Side:       node.side,
				LimitPrice: node.order.LimitPrice,
				Sequence:   node.order.Sequence,
			})
		}
		return true
	}
	if s.side == domain.SideBuy {
		s.levels.Reverse(appendLevel)
	} else {
		s.levels.Scan(appendLevel)
	}
	return orders
}

func (s Snapshot) withoutChecksum() Snapshot {
	s.Checksum = ""
	return s
}

func validSnapshotOrder(order SnapshotOrder, side domain.Side, seenOrderIDs map[string]bool, maxSequence *int64) bool {
	if order.Side != side || order.OrderID == "" || order.Sequence < 0 {
		return false
	}
	if seenOrderIDs[order.OrderID] {
		return false
	}
	seenOrderIDs[order.OrderID] = true
	if order.Sequence > *maxSequence {
		*maxSequence = order.Sequence
	}
	return true
}

func checksum(snapshot Snapshot) string {
	h := sha256.New()
	writeChecksumField(h, "next=")
	writeChecksumInt(h, snapshot.NextSequence)
	h.Write(semicolon)
	for _, order := range snapshot.Buys {
		writeChecksumOrder(h, "B:", order)
	}
	for _, order := range snapshot.Sells {
		writeChecksumOrder(h, "S:", order)
	}
	sum := h.Sum(nil)
	return hex.EncodeToString(sum)
}

var semicolon = []byte(";")
var colon = []byte(":")

func writeChecksumField(h hash.Hash, s string) {
	h.Write([]byte(s))
}

func writeChecksumInt(h hash.Hash, v int64) {
	h.Write(strconv.AppendInt(nil, v, 10))
}

func writeChecksumOrder(h hash.Hash, prefix string, order SnapshotOrder) {
	writeChecksumField(h, prefix)
	writeChecksumField(h, order.OrderID)
	h.Write(colon)
	writeChecksumInt(h, order.LimitPrice)
	h.Write(colon)
	writeChecksumInt(h, order.Sequence)
	h.Write(semicolon)
}
