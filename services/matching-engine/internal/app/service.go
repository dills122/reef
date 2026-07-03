package app

import (
	"container/heap"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct {
	books  sync.Map // map[string]*orderBook
	orders sync.Map // map[string]*orderRecord
	now    func() time.Time
}

type restingOrder struct {
	OrderID    string
	LimitPrice int64
	Sequence   int64
}

type orderBook struct {
	mu           sync.Mutex
	nextSequence int64
	buys         orderQueue
	sells        orderQueue
}

type orderRecord struct {
	OrderID           string
	InstrumentID      string
	Side              domain.Side
	OriginalQuantity  int64
	RemainingQuantity int64
	LimitPrice        int64
	Currency          string
	Status            domain.OrderStatus
	LastUpdatedAt     string
}

type orderQueue struct {
	orders []*restingOrder
	side   domain.Side
}

type Option func(*Service)

func WithClock(clock func() time.Time) Option {
	return func(s *Service) {
		if clock != nil {
			s.now = clock
		}
	}
}

func NewService(options ...Option) *Service {
	service := &Service{
		now: func() time.Time {
			return time.Now().UTC()
		},
	}
	for _, option := range options {
		option(service)
	}
	return service
}

func (s *Service) SubmitOrder(cmd domain.SubmitOrder) domain.SubmitOrderResult {
	now := s.occurredAt(cmd.OccurredAt)

	if cmd.OrderID == "" {
		return rejectedResult("evt-reject-missing-order-id", cmd.OrderID, "VALIDATION_ERROR", "orderId is required", now)
	}

	if cmd.InstrumentID == "" {
		return rejectedResult("evt-reject-missing-instrument", cmd.OrderID, "VALIDATION_ERROR", "instrumentId is required", now)
	}

	if cmd.Side != domain.SideBuy && cmd.Side != domain.SideSell {
		return rejectedResult("evt-reject-invalid-side", cmd.OrderID, "VALIDATION_ERROR", "side must be BUY or SELL", now)
	}

	quantityUnits, ok := parsePositiveInt(cmd.QuantityUnits)
	if !ok {
		return rejectedResult("evt-reject-invalid-quantity", cmd.OrderID, "VALIDATION_ERROR", "quantityUnits must be a positive integer", now)
	}

	limitPrice, ok := parsePositiveInt(cmd.LimitPrice)
	if !ok {
		return rejectedResult("evt-reject-invalid-price", cmd.OrderID, "VALIDATION_ERROR", "limitPrice must be a positive integer", now)
	}

	result := domain.SubmitOrderResult{
		Accepted: &domain.OrderAccepted{
			EventID:       fmt.Sprintf("evt-order-accepted-%s", cmd.OrderID),
			OrderID:       cmd.OrderID,
			EngineOrderID: fmt.Sprintf("eng-%s", cmd.OrderID),
			OccurredAt:    now,
		},
	}

	book := s.bookFor(cmd.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	record := &orderRecord{
		OrderID:           cmd.OrderID,
		InstrumentID:      cmd.InstrumentID,
		Side:              cmd.Side,
		OriginalQuantity:  quantityUnits,
		RemainingQuantity: quantityUnits,
		LimitPrice:        limitPrice,
		Currency:          cmd.Currency,
		Status:            domain.OrderStatusAccepted,
		LastUpdatedAt:     now,
	}
	if !s.reserveOrder(record) {
		return rejectedResult("evt-reject-duplicate-order-id", cmd.OrderID, "DUPLICATE_ORDER_ID", "orderId already exists", now)
	}
	incoming := book.newRestingOrder(cmd.OrderID, record.LimitPrice)

	if record.Side == domain.SideBuy {
		s.matchBuy(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.buys.push(incoming)
		}
	} else {
		s.matchSell(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.sells.push(incoming)
		}
	}

	s.refreshOrderStatus(record)

	return result
}

func (s *Service) CancelOrder(cmd domain.CancelOrder) domain.SubmitOrderResult {
	now := s.occurredAt(cmd.OccurredAt)
	if cmd.OrderID == "" {
		return rejectedResult("evt-reject-missing-order-id", cmd.OrderID, "VALIDATION_ERROR", "orderId is required", now)
	}

	record, ok := s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}
	book := s.bookFor(record.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if record.Status == domain.OrderStatusFilled {
		return rejectedResult("evt-reject-order-filled", cmd.OrderID, "INVALID_STATE", "filled order cannot be cancelled", now)
	}
	if record.Status == domain.OrderStatusCancelled {
		return rejectedResult("evt-reject-order-cancelled", cmd.OrderID, "INVALID_STATE", "order already cancelled", now)
	}

	s.removeRestingOrder(book, record)
	record.RemainingQuantity = 0
	record.Status = domain.OrderStatusCancelled
	record.LastUpdatedAt = now

	return domain.SubmitOrderResult{
		Accepted: &domain.OrderAccepted{
			EventID:       fmt.Sprintf("evt-order-cancelled-%s", cmd.OrderID),
			OrderID:       cmd.OrderID,
			EngineOrderID: fmt.Sprintf("eng-%s", cmd.OrderID),
			OccurredAt:    now,
		},
	}
}

func (s *Service) ModifyOrder(cmd domain.ModifyOrder) domain.SubmitOrderResult {
	now := s.occurredAt(cmd.OccurredAt)
	if cmd.OrderID == "" {
		return rejectedResult("evt-reject-missing-order-id", cmd.OrderID, "VALIDATION_ERROR", "orderId is required", now)
	}

	record, ok := s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}
	book := s.bookFor(record.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if record.Status == domain.OrderStatusFilled || record.Status == domain.OrderStatusCancelled {
		return rejectedResult("evt-reject-order-not-modifiable", cmd.OrderID, "INVALID_STATE", "order is not modifiable", now)
	}

	quantityUnits, ok := parsePositiveInt(cmd.QuantityUnits)
	if !ok {
		return rejectedResult("evt-reject-invalid-quantity", cmd.OrderID, "VALIDATION_ERROR", "quantityUnits must be a positive integer", now)
	}

	limitPrice, ok := parsePositiveInt(cmd.LimitPrice)
	if !ok {
		return rejectedResult("evt-reject-invalid-price", cmd.OrderID, "VALIDATION_ERROR", "limitPrice must be a positive integer", now)
	}

	alreadyFilled := record.OriginalQuantity - record.RemainingQuantity
	if quantityUnits <= alreadyFilled {
		return rejectedResult("evt-reject-invalid-modify-quantity", cmd.OrderID, "VALIDATION_ERROR", "quantityUnits must remain above already filled quantity", now)
	}

	s.removeRestingOrder(book, record)

	record.OriginalQuantity = quantityUnits
	record.RemainingQuantity = quantityUnits - alreadyFilled
	record.LimitPrice = limitPrice
	record.LastUpdatedAt = now
	s.refreshOrderStatus(record)

	if record.RemainingQuantity > 0 {
		incoming := book.newRestingOrder(cmd.OrderID, record.LimitPrice)
		if record.Side == domain.SideBuy {
			book.buys.push(incoming)
		} else {
			book.sells.push(incoming)
		}
	}

	return domain.SubmitOrderResult{
		Accepted: &domain.OrderAccepted{
			EventID:       fmt.Sprintf("evt-order-modified-%s", cmd.OrderID),
			OrderID:       cmd.OrderID,
			EngineOrderID: fmt.Sprintf("eng-%s", cmd.OrderID),
			OccurredAt:    now,
		},
	}
}

func (s *Service) occurredAt(commandOccurredAt string) string {
	if strings.TrimSpace(commandOccurredAt) != "" {
		return commandOccurredAt
	}
	if s.now == nil {
		return time.Now().UTC().Format(time.RFC3339)
	}
	return s.now().UTC().Format(time.RFC3339)
}

func (s *Service) RestingOrders(instrumentID string, side domain.Side) int {
	book := s.bookFor(instrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if side == domain.SideBuy {
		return book.buys.len()
	}

	return book.sells.len()
}

func (s *Service) OrderState(orderID string) (domain.OrderState, bool) {
	record, ok := s.loadOrder(orderID)
	if !ok {
		return domain.OrderState{}, false
	}
	book := s.bookFor(record.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	return domain.OrderState{
		OrderID:           record.OrderID,
		InstrumentID:      record.InstrumentID,
		Side:              record.Side,
		Status:            record.Status,
		OriginalQuantity:  strconv.FormatInt(record.OriginalQuantity, 10),
		RemainingQuantity: strconv.FormatInt(record.RemainingQuantity, 10),
		LimitPrice:        strconv.FormatInt(record.LimitPrice, 10),
		Currency:          record.Currency,
		LastUpdatedAt:     record.LastUpdatedAt,
	}, true
}

func (s *Service) bookFor(instrumentID string) *orderBook {
	if existing, ok := s.books.Load(instrumentID); ok {
		return existing.(*orderBook)
	}
	book := newOrderBook()
	actual, _ := s.books.LoadOrStore(instrumentID, book)
	return actual.(*orderBook)
}

func (s *Service) matchBuy(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && book.sells.len() > 0 {
		resting := book.sells.peek()
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.sells.pop()
			continue
		}
		if incomingRecord.LimitPrice < restingRecord.LimitPrice {
			return
		}

		matchedUnits := minInt64(incomingRecord.RemainingQuantity, restingRecord.RemainingQuantity)
		executionPrice := restingRecord.LimitPrice
		s.appendMatch(result, incomingRecord, restingRecord, matchedUnits, executionPrice, occurredAt)

		incomingRecord.RemainingQuantity -= matchedUnits
		restingRecord.RemainingQuantity -= matchedUnits
		incomingRecord.LastUpdatedAt = occurredAt
		restingRecord.LastUpdatedAt = occurredAt
		s.refreshOrderStatus(incomingRecord)
		s.refreshOrderStatus(restingRecord)
		if restingRecord.RemainingQuantity == 0 {
			book.sells.pop()
		}
	}
}

func (s *Service) matchSell(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && book.buys.len() > 0 {
		resting := book.buys.peek()
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.buys.pop()
			continue
		}
		if incomingRecord.LimitPrice > restingRecord.LimitPrice {
			return
		}

		matchedUnits := minInt64(incomingRecord.RemainingQuantity, restingRecord.RemainingQuantity)
		executionPrice := restingRecord.LimitPrice
		s.appendMatch(result, restingRecord, incomingRecord, matchedUnits, executionPrice, occurredAt)

		incomingRecord.RemainingQuantity -= matchedUnits
		restingRecord.RemainingQuantity -= matchedUnits
		incomingRecord.LastUpdatedAt = occurredAt
		restingRecord.LastUpdatedAt = occurredAt
		s.refreshOrderStatus(incomingRecord)
		s.refreshOrderStatus(restingRecord)
		if restingRecord.RemainingQuantity == 0 {
			book.buys.pop()
		}
	}
}

func (s *Service) appendMatch(result *domain.SubmitOrderResult, buyOrder *orderRecord, sellOrder *orderRecord, matchedUnits int64, executionPrice int64, occurredAt string) {
	executionID := fmt.Sprintf("exec-%s-%s-%d", buyOrder.OrderID, sellOrder.OrderID, len(result.Trades)+1)
	tradeID := fmt.Sprintf("trade-%s-%s-%d", buyOrder.OrderID, sellOrder.OrderID, len(result.Trades)+1)
	matchedUnitsStr := strconv.FormatInt(matchedUnits, 10)
	executionPriceStr := strconv.FormatInt(executionPrice, 10)

	result.Executions = append(result.Executions,
		domain.ExecutionCreated{
			EventID:        fmt.Sprintf("evt-execution-%s-buy", executionID),
			ExecutionID:    executionID + "-buy",
			OrderID:        buyOrder.OrderID,
			InstrumentID:   buyOrder.InstrumentID,
			QuantityUnits:  matchedUnitsStr,
			ExecutionPrice: executionPriceStr,
			Currency:       buyOrder.Currency,
			OccurredAt:     occurredAt,
		},
		domain.ExecutionCreated{
			EventID:        fmt.Sprintf("evt-execution-%s-sell", executionID),
			ExecutionID:    executionID + "-sell",
			OrderID:        sellOrder.OrderID,
			InstrumentID:   sellOrder.InstrumentID,
			QuantityUnits:  matchedUnitsStr,
			ExecutionPrice: executionPriceStr,
			Currency:       sellOrder.Currency,
			OccurredAt:     occurredAt,
		},
	)

	result.Trades = append(result.Trades, domain.TradeCreated{
		EventID:       fmt.Sprintf("evt-trade-%s", tradeID),
		TradeID:       tradeID,
		ExecutionID:   executionID,
		BuyOrderID:    buyOrder.OrderID,
		SellOrderID:   sellOrder.OrderID,
		InstrumentID:  buyOrder.InstrumentID,
		QuantityUnits: matchedUnitsStr,
		Price:         executionPriceStr,
		Currency:      buyOrder.Currency,
		OccurredAt:    occurredAt,
	})
}

func (s *Service) refreshOrderStatus(record *orderRecord) {
	switch {
	case record.RemainingQuantity == record.OriginalQuantity:
		record.Status = domain.OrderStatusAccepted
	case record.RemainingQuantity == 0:
		record.Status = domain.OrderStatusFilled
	default:
		record.Status = domain.OrderStatusPartiallyFilled
	}
}

func minInt64(a int64, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func newOrderBook() *orderBook {
	return &orderBook{
		buys:  newOrderQueue(domain.SideBuy),
		sells: newOrderQueue(domain.SideSell),
	}
}

func (book *orderBook) newRestingOrder(orderID string, limitPrice int64) *restingOrder {
	sequence := book.nextSequence
	book.nextSequence++
	return &restingOrder{
		OrderID:    orderID,
		LimitPrice: limitPrice,
		Sequence:   sequence,
	}
}

func newOrderQueue(side domain.Side) orderQueue {
	queue := orderQueue{
		orders: make([]*restingOrder, 0),
		side:   side,
	}
	heap.Init(&queue)
	return queue
}

func (q orderQueue) Len() int {
	return len(q.orders)
}

func (q orderQueue) Less(i int, j int) bool {
	left := q.orders[i]
	right := q.orders[j]
	if left.LimitPrice == right.LimitPrice {
		return left.Sequence < right.Sequence
	}
	if q.side == domain.SideBuy {
		return left.LimitPrice > right.LimitPrice
	}
	return left.LimitPrice < right.LimitPrice
}

func (q orderQueue) Swap(i int, j int) {
	q.orders[i], q.orders[j] = q.orders[j], q.orders[i]
}

func (q *orderQueue) Push(value any) {
	q.orders = append(q.orders, value.(*restingOrder))
}

func (q *orderQueue) Pop() any {
	n := len(q.orders)
	item := q.orders[n-1]
	q.orders[n-1] = nil
	q.orders = q.orders[:n-1]
	return item
}

func (q *orderQueue) push(order *restingOrder) {
	heap.Push(q, order)
}

func (q *orderQueue) pop() *restingOrder {
	if len(q.orders) == 0 {
		return nil
	}
	return heap.Pop(q).(*restingOrder)
}

func (q *orderQueue) peek() *restingOrder {
	if len(q.orders) == 0 {
		return nil
	}
	return q.orders[0]
}

func (q *orderQueue) len() int {
	return len(q.orders)
}

func (q *orderQueue) removeOrderByID(orderID string) {
	for idx, order := range q.orders {
		if order.OrderID == orderID {
			heap.Remove(q, idx)
			return
		}
	}
}

func parsePositiveInt(value string) (int64, bool) {
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, false
	}
	return parsed, true
}

func rejectedResult(eventID string, orderID string, code string, reason string, occurredAt string) domain.SubmitOrderResult {
	return domain.SubmitOrderResult{
		Rejected: &domain.OrderRejected{
			EventID:    eventID,
			OrderID:    orderID,
			Code:       code,
			Reason:     reason,
			OccurredAt: occurredAt,
		},
	}
}

func (s *Service) removeRestingOrder(book *orderBook, record *orderRecord) {
	if record.Side == domain.SideBuy {
		book.buys.removeOrderByID(record.OrderID)
		return
	}
	book.sells.removeOrderByID(record.OrderID)
}

func (s *Service) loadOrder(orderID string) (*orderRecord, bool) {
	value, ok := s.orders.Load(orderID)
	if !ok {
		return nil, false
	}
	record, castOk := value.(*orderRecord)
	return record, castOk
}

func (s *Service) reserveOrder(record *orderRecord) bool {
	_, loaded := s.orders.LoadOrStore(record.OrderID, record)
	return !loaded
}
