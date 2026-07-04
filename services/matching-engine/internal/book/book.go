package book

import (
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
