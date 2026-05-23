package app

import (
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct {
	books  sync.Map // map[string]*orderBook
	orders sync.Map // map[string]*orderRecord
}

type restingOrder struct {
	OrderID string
}

type orderBook struct {
	mu    sync.Mutex
	buys  []*restingOrder
	sells []*restingOrder
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

func NewService() *Service {
	return &Service{}
}

func (s *Service) SubmitOrder(cmd domain.SubmitOrder) domain.SubmitOrderResult {
	now := time.Now().UTC().Format(time.RFC3339)

	if cmd.OrderID == "" {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-missing-order-id",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "orderId is required",
				OccurredAt: now,
			},
		}
	}

	if cmd.InstrumentID == "" {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-missing-instrument",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "instrumentId is required",
				OccurredAt: now,
			},
		}
	}

	if cmd.Side != domain.SideBuy && cmd.Side != domain.SideSell {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-invalid-side",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "side must be BUY or SELL",
				OccurredAt: now,
			},
		}
	}

	quantityUnits, err := strconv.ParseInt(cmd.QuantityUnits, 10, 64)
	if err != nil || quantityUnits <= 0 {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-invalid-quantity",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "quantityUnits must be a positive integer",
				OccurredAt: now,
			},
		}
	}

	limitPrice, err := strconv.ParseInt(cmd.LimitPrice, 10, 64)
	if err != nil || limitPrice <= 0 {
		return domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				EventID:    "evt-reject-invalid-price",
				OrderID:    cmd.OrderID,
				Code:       "VALIDATION_ERROR",
				Reason:     "limitPrice must be a positive integer",
				OccurredAt: now,
			},
		}
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
	s.storeOrder(record)
	incoming := &restingOrder{OrderID: cmd.OrderID}

	if record.Side == domain.SideBuy {
		s.matchBuy(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.buys = s.insertBuy(book.buys, incoming)
		}
	} else {
		s.matchSell(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.sells = s.insertSell(book.sells, incoming)
		}
	}

	s.refreshOrderStatus(record)

	return result
}

func (s *Service) CancelOrder(cmd domain.CancelOrder) domain.SubmitOrderResult {
	now := time.Now().UTC().Format(time.RFC3339)
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

	record, ok = s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}

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
	now := time.Now().UTC().Format(time.RFC3339)
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

	record, ok = s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}

	if record.Status == domain.OrderStatusFilled || record.Status == domain.OrderStatusCancelled {
		return rejectedResult("evt-reject-order-not-modifiable", cmd.OrderID, "INVALID_STATE", "order is not modifiable", now)
	}

	quantityUnits, err := strconv.ParseInt(cmd.QuantityUnits, 10, 64)
	if err != nil || quantityUnits <= 0 {
		return rejectedResult("evt-reject-invalid-quantity", cmd.OrderID, "VALIDATION_ERROR", "quantityUnits must be a positive integer", now)
	}

	limitPrice, err := strconv.ParseInt(cmd.LimitPrice, 10, 64)
	if err != nil || limitPrice <= 0 {
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
		incoming := &restingOrder{OrderID: cmd.OrderID}
		if record.Side == domain.SideBuy {
			book.buys = s.insertBuy(book.buys, incoming)
		} else {
			book.sells = s.insertSell(book.sells, incoming)
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

func (s *Service) RestingOrders(instrumentID string, side domain.Side) int {
	book := s.bookFor(instrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if side == domain.SideBuy {
		return len(book.buys)
	}

	return len(book.sells)
}

func (s *Service) OrderState(orderID string) (domain.OrderState, bool) {
	record, ok := s.loadOrder(orderID)
	if !ok {
		return domain.OrderState{}, false
	}
	book := s.bookFor(record.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	record, ok = s.loadOrder(orderID)
	if !ok {
		return domain.OrderState{}, false
	}

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
	book := &orderBook{}
	actual, _ := s.books.LoadOrStore(instrumentID, book)
	return actual.(*orderBook)
}

func (s *Service) matchBuy(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && len(book.sells) > 0 {
		resting := book.sells[0]
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.sells = book.sells[1:]
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
			book.sells = book.sells[1:]
		}
	}
}

func (s *Service) matchSell(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && len(book.buys) > 0 {
		resting := book.buys[0]
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.buys = book.buys[1:]
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
			book.buys = book.buys[1:]
		}
	}
}

func (s *Service) appendMatch(result *domain.SubmitOrderResult, buyOrder *orderRecord, sellOrder *orderRecord, matchedUnits int64, executionPrice int64, occurredAt string) {
	executionID := fmt.Sprintf("exec-%s-%s-%d", buyOrder.OrderID, sellOrder.OrderID, len(result.Trades)+1)
	tradeID := fmt.Sprintf("trade-%s-%s-%d", buyOrder.OrderID, sellOrder.OrderID, len(result.Trades)+1)

	result.Executions = append(result.Executions,
		domain.ExecutionCreated{
			EventID:        fmt.Sprintf("evt-execution-%s-buy", executionID),
			ExecutionID:    executionID + "-buy",
			OrderID:        buyOrder.OrderID,
			InstrumentID:   buyOrder.InstrumentID,
			QuantityUnits:  strconv.FormatInt(matchedUnits, 10),
			ExecutionPrice: strconv.FormatInt(executionPrice, 10),
			Currency:       buyOrder.Currency,
			OccurredAt:     occurredAt,
		},
		domain.ExecutionCreated{
			EventID:        fmt.Sprintf("evt-execution-%s-sell", executionID),
			ExecutionID:    executionID + "-sell",
			OrderID:        sellOrder.OrderID,
			InstrumentID:   sellOrder.InstrumentID,
			QuantityUnits:  strconv.FormatInt(matchedUnits, 10),
			ExecutionPrice: strconv.FormatInt(executionPrice, 10),
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
		QuantityUnits: strconv.FormatInt(matchedUnits, 10),
		Price:         strconv.FormatInt(executionPrice, 10),
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

func (s *Service) insertBuy(existing []*restingOrder, incoming *restingOrder) []*restingOrder {
	for idx, order := range existing {
		incomingRecord, okIncoming := s.loadOrder(incoming.OrderID)
		currentRecord, okCurrent := s.loadOrder(order.OrderID)
		if !okIncoming || !okCurrent {
			continue
		}
		if incomingRecord.LimitPrice > currentRecord.LimitPrice {
			return insertAt(existing, incoming, idx)
		}
	}

	return append(existing, incoming)
}

func (s *Service) insertSell(existing []*restingOrder, incoming *restingOrder) []*restingOrder {
	for idx, order := range existing {
		incomingRecord, okIncoming := s.loadOrder(incoming.OrderID)
		currentRecord, okCurrent := s.loadOrder(order.OrderID)
		if !okIncoming || !okCurrent {
			continue
		}
		if incomingRecord.LimitPrice < currentRecord.LimitPrice {
			return insertAt(existing, incoming, idx)
		}
	}

	return append(existing, incoming)
}

func insertAt(existing []*restingOrder, incoming *restingOrder, idx int) []*restingOrder {
	existing = append(existing, nil)
	copy(existing[idx+1:], existing[idx:])
	existing[idx] = incoming
	return existing
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
		book.buys = removeOrderByID(book.buys, record.OrderID)
		return
	}
	book.sells = removeOrderByID(book.sells, record.OrderID)
}

func removeOrderByID(existing []*restingOrder, orderID string) []*restingOrder {
	for idx, order := range existing {
		if order.OrderID == orderID {
			return append(existing[:idx], existing[idx+1:]...)
		}
	}
	return existing
}

func (s *Service) loadOrder(orderID string) (*orderRecord, bool) {
	value, ok := s.orders.Load(orderID)
	if !ok {
		return nil, false
	}
	record, castOk := value.(*orderRecord)
	return record, castOk
}

func (s *Service) storeOrder(record *orderRecord) {
	s.orders.Store(record.OrderID, record)
}
