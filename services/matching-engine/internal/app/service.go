package app

import (
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct {
	mu    sync.Mutex
	books map[string]*orderBook
}

type restingOrder struct {
	OrderID       string
	InstrumentID  string
	Side          domain.Side
	QuantityUnits int64
	LimitPrice    int64
	Currency      string
}

type orderBook struct {
	buys  []*restingOrder
	sells []*restingOrder
}

func NewService() *Service {
	return &Service{
		books: make(map[string]*orderBook),
	}
}

func (s *Service) SubmitOrder(cmd domain.SubmitOrder) domain.SubmitOrderResult {
	s.mu.Lock()
	defer s.mu.Unlock()

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
	incoming := &restingOrder{
		OrderID:       cmd.OrderID,
		InstrumentID:  cmd.InstrumentID,
		Side:          cmd.Side,
		QuantityUnits: quantityUnits,
		LimitPrice:    limitPrice,
		Currency:      cmd.Currency,
	}

	if incoming.Side == domain.SideBuy {
		s.matchBuy(book, incoming, &result, now)
		if incoming.QuantityUnits > 0 {
			book.buys = insertBuy(book.buys, incoming)
		}
	} else {
		s.matchSell(book, incoming, &result, now)
		if incoming.QuantityUnits > 0 {
			book.sells = insertSell(book.sells, incoming)
		}
	}

	return result
}

func (s *Service) RestingOrders(instrumentID string, side domain.Side) int {
	s.mu.Lock()
	defer s.mu.Unlock()

	book := s.books[instrumentID]
	if book == nil {
		return 0
	}

	if side == domain.SideBuy {
		return len(book.buys)
	}

	return len(book.sells)
}

func (s *Service) bookFor(instrumentID string) *orderBook {
	book := s.books[instrumentID]
	if book == nil {
		book = &orderBook{}
		s.books[instrumentID] = book
	}
	return book
}

func (s *Service) matchBuy(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	for incoming.QuantityUnits > 0 && len(book.sells) > 0 {
		resting := book.sells[0]
		if incoming.LimitPrice < resting.LimitPrice {
			return
		}

		matchedUnits := minInt64(incoming.QuantityUnits, resting.QuantityUnits)
		executionPrice := resting.LimitPrice
		s.appendMatch(result, incoming, resting, matchedUnits, executionPrice, occurredAt)

		incoming.QuantityUnits -= matchedUnits
		resting.QuantityUnits -= matchedUnits
		if resting.QuantityUnits == 0 {
			book.sells = book.sells[1:]
		}
	}
}

func (s *Service) matchSell(book *orderBook, incoming *restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	for incoming.QuantityUnits > 0 && len(book.buys) > 0 {
		resting := book.buys[0]
		if incoming.LimitPrice > resting.LimitPrice {
			return
		}

		matchedUnits := minInt64(incoming.QuantityUnits, resting.QuantityUnits)
		executionPrice := resting.LimitPrice
		s.appendMatch(result, resting, incoming, matchedUnits, executionPrice, occurredAt)

		incoming.QuantityUnits -= matchedUnits
		resting.QuantityUnits -= matchedUnits
		if resting.QuantityUnits == 0 {
			book.buys = book.buys[1:]
		}
	}
}

func (s *Service) appendMatch(result *domain.SubmitOrderResult, buyOrder *restingOrder, sellOrder *restingOrder, matchedUnits int64, executionPrice int64, occurredAt string) {
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

func minInt64(a int64, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func insertBuy(existing []*restingOrder, incoming *restingOrder) []*restingOrder {
	for idx, order := range existing {
		if incoming.LimitPrice > order.LimitPrice {
			return insertAt(existing, incoming, idx)
		}
	}

	return append(existing, incoming)
}

func insertSell(existing []*restingOrder, incoming *restingOrder) []*restingOrder {
	for idx, order := range existing {
		if incoming.LimitPrice < order.LimitPrice {
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
