package app

import (
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	hotbook "github.com/dills122/reef/services/matching-engine/internal/book"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct {
	books                  sync.Map // map[string]*orderBook
	orders                 sync.Map // map[string]*orderRecord
	now                    func() time.Time
	terminalRetentionLimit int
	terminalRetentionMu    sync.Mutex
	terminalRetentionOrder []string
	terminalRetentionHead  int
}

type restingOrder = hotbook.RestingOrder

type orderBook struct {
	mu   sync.Mutex
	book *hotbook.Book
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
	terminalTracked   bool
}

type Option func(*Service)

func WithClock(clock func() time.Time) Option {
	return func(s *Service) {
		if clock != nil {
			s.now = clock
		}
	}
}

func WithTerminalOrderRetentionLimit(limit int) Option {
	return func(s *Service) {
		if limit > 0 {
			s.terminalRetentionLimit = limit
		}
	}
}

func NewService(options ...Option) *Service {
	service := &Service{
		terminalRetentionLimit: envInt("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", 0),
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
	if !validOccurredAt(cmd.OccurredAt) {
		return rejectedResult("evt-reject-invalid-occurred-at", cmd.OrderID, "VALIDATION_ERROR", "occurredAt must be RFC3339", s.nowFormatted())
	}
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

	result := acceptedResult("accepted", cmd.OrderID, now)

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
	incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)

	if record.Side == domain.SideBuy {
		s.matchBuy(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.book.Add(record.Side, incoming)
		}
	} else {
		s.matchSell(book, incoming, &result, now)
		if record.RemainingQuantity > 0 {
			book.book.Add(record.Side, incoming)
		}
	}

	s.refreshOrderStatus(record)

	return result
}

func (s *Service) CancelOrder(cmd domain.CancelOrder) domain.SubmitOrderResult {
	if !validOccurredAt(cmd.OccurredAt) {
		return rejectedResult("evt-reject-invalid-occurred-at", cmd.OrderID, "VALIDATION_ERROR", "occurredAt must be RFC3339", s.nowFormatted())
	}
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
	s.trackTerminalOrder(record)

	return acceptedResult("cancelled", cmd.OrderID, now)
}

func (s *Service) ModifyOrder(cmd domain.ModifyOrder) domain.SubmitOrderResult {
	if !validOccurredAt(cmd.OccurredAt) {
		return rejectedResult("evt-reject-invalid-occurred-at", cmd.OrderID, "VALIDATION_ERROR", "occurredAt must be RFC3339", s.nowFormatted())
	}
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

	result := acceptedResult("modified", cmd.OrderID, now)

	if record.RemainingQuantity > 0 {
		incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)
		if record.Side == domain.SideBuy {
			s.matchBuy(book, incoming, &result, now)
		} else {
			s.matchSell(book, incoming, &result, now)
		}
		if record.RemainingQuantity > 0 {
			book.book.Add(record.Side, incoming)
		}
	}

	return result
}

func (s *Service) occurredAt(commandOccurredAt string) string {
	if strings.TrimSpace(commandOccurredAt) != "" {
		return commandOccurredAt
	}
	return s.nowFormatted()
}

func (s *Service) nowFormatted() string {
	if s.now == nil {
		return time.Now().UTC().Format(time.RFC3339)
	}
	return s.now().UTC().Format(time.RFC3339)
}

// validOccurredAt reports whether a caller-supplied occurredAt is either
// blank (the engine will stamp its own clock) or a well-formed RFC3339
// timestamp. A non-blank, malformed value is never coerced or defaulted -
// it must be rejected, otherwise it would silently propagate through
// matches/trades/order state as an unparseable string.
func validOccurredAt(commandOccurredAt string) bool {
	trimmed := strings.TrimSpace(commandOccurredAt)
	if trimmed == "" {
		return true
	}
	_, err := time.Parse(time.RFC3339, trimmed)
	return err == nil
}

func (s *Service) RestingOrders(instrumentID string, side domain.Side) int {
	book := s.bookFor(instrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if side == domain.SideBuy {
		return book.book.Len(domain.SideBuy)
	}

	return book.book.Len(domain.SideSell)
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

// BatchRollback captures a pre-mutation snapshot of the order book(s) and
// order records that a batch of commands is about to touch, so the
// direct-consume pipeline can undo those mutations if the batch's durable
// VenueEventBatch publish subsequently fails. Without this, a publish
// failure would leave the book holding a live reservation or match that was
// never durably recorded, and a redelivered retry of the same command would
// be rejected as a duplicate even though nothing was ever published for it
// (see docs/WORK_PLAN.md crash/restart scenario 3).
//
// Callers must take the snapshot via BeginBatch before processing any
// command in the batch, and must not process commands for the same
// instrument concurrently from another goroutine until the batch is either
// committed (published successfully, snapshot discarded) or rolled back -
// true today since a given instrument's commands are only ever processed by
// one direct-consume shard/partition at a time.
type BatchRollback struct {
	orders      *sync.Map
	instruments map[string]*instrumentRollback
}

type instrumentRollback struct {
	book     *orderBook
	snapshot hotbook.Snapshot
	existing map[string]orderRecord
}

// BeginBatch snapshots the order book and the order records resting in it
// for each distinct instrument ID.
func (s *Service) BeginBatch(instrumentIDs []string) *BatchRollback {
	rollback := &BatchRollback{orders: &s.orders, instruments: make(map[string]*instrumentRollback)}
	for _, instrumentID := range instrumentIDs {
		if instrumentID == "" {
			continue
		}
		if _, ok := rollback.instruments[instrumentID]; ok {
			continue
		}
		book := s.bookFor(instrumentID)
		book.mu.Lock()
		snapshot := book.book.Snapshot()
		existing := make(map[string]orderRecord)
		captureExisting := func(orderID string) {
			if _, already := existing[orderID]; already {
				return
			}
			if record, ok := s.loadOrder(orderID); ok {
				existing[orderID] = *record
			}
		}
		for _, o := range snapshot.Buys {
			captureExisting(o.OrderID)
		}
		for _, o := range snapshot.Sells {
			captureExisting(o.OrderID)
		}
		book.mu.Unlock()
		rollback.instruments[instrumentID] = &instrumentRollback{
			book:     book,
			snapshot: snapshot,
			existing: existing,
		}
	}
	return rollback
}

// Rollback restores every snapshotted instrument's book and order records to
// their pre-batch state, and deletes any order record newly created during
// the batch. touchedOrderIDs maps instrumentID to every orderID a command in
// the batch referenced (including ones ultimately rejected), so newly
// reserved orders that never existed before the batch are removed rather
// than left behind. It is only valid to call once per BeginBatch snapshot.
func (rb *BatchRollback) Rollback(touchedOrderIDs map[string][]string) {
	for instrumentID, snap := range rb.instruments {
		snap.book.mu.Lock()
		if restored, ok := hotbook.Restore(snap.snapshot); ok {
			snap.book.book = restored
		}
		for orderID, record := range snap.existing {
			recordCopy := record
			rb.orders.Store(orderID, &recordCopy)
		}
		for _, orderID := range touchedOrderIDs[instrumentID] {
			if _, existed := snap.existing[orderID]; !existed {
				rb.orders.Delete(orderID)
			}
		}
		snap.book.mu.Unlock()
	}
}

func (s *Service) matchBuy(book *orderBook, incoming restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && book.book.Len(domain.SideSell) > 0 {
		resting, ok := book.book.Best(domain.SideSell)
		if !ok {
			return
		}
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.book.PopBest(domain.SideSell)
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
			book.book.PopBest(domain.SideSell)
		}
	}
}

func (s *Service) matchSell(book *orderBook, incoming restingOrder, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	for incomingRecord.RemainingQuantity > 0 && book.book.Len(domain.SideBuy) > 0 {
		resting, ok := book.book.Best(domain.SideBuy)
		if !ok {
			return
		}
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			book.book.PopBest(domain.SideBuy)
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
			book.book.PopBest(domain.SideBuy)
		}
	}
}

func (s *Service) appendMatch(result *domain.SubmitOrderResult, buyOrder *orderRecord, sellOrder *orderRecord, matchedUnits int64, executionPrice int64, occurredAt string) {
	seq := strconv.Itoa(len(result.Trades) + 1)
	executionID := "exec-" + buyOrder.OrderID + "-" + sellOrder.OrderID + "-" + seq
	tradeID := "trade-" + buyOrder.OrderID + "-" + sellOrder.OrderID + "-" + seq
	matchedUnitsStr := strconv.FormatInt(matchedUnits, 10)
	executionPriceStr := strconv.FormatInt(executionPrice, 10)

	result.Executions = append(result.Executions,
		domain.ExecutionCreated{
			EventID:        "evt-execution-" + executionID + "-buy",
			ExecutionID:    executionID + "-buy",
			OrderID:        buyOrder.OrderID,
			InstrumentID:   buyOrder.InstrumentID,
			QuantityUnits:  matchedUnitsStr,
			ExecutionPrice: executionPriceStr,
			Currency:       buyOrder.Currency,
			OccurredAt:     occurredAt,
		},
		domain.ExecutionCreated{
			EventID:        "evt-execution-" + executionID + "-sell",
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
		EventID:       "evt-trade-" + tradeID,
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
		s.trackTerminalOrder(record)
	default:
		record.Status = domain.OrderStatusPartiallyFilled
	}
}

func (s *Service) trackTerminalOrder(record *orderRecord) {
	if s.terminalRetentionLimit <= 0 || record.terminalTracked {
		return
	}
	if record.Status != domain.OrderStatusFilled && record.Status != domain.OrderStatusCancelled {
		return
	}

	s.terminalRetentionMu.Lock()
	defer s.terminalRetentionMu.Unlock()
	if record.terminalTracked {
		return
	}
	record.terminalTracked = true
	s.terminalRetentionOrder = append(s.terminalRetentionOrder, record.OrderID)
	for len(s.terminalRetentionOrder)-s.terminalRetentionHead > s.terminalRetentionLimit {
		evictID := s.terminalRetentionOrder[s.terminalRetentionHead]
		s.terminalRetentionHead++
		if evictID == record.OrderID {
			continue
		}
		value, ok := s.orders.Load(evictID)
		if !ok {
			continue
		}
		evictRecord, ok := value.(*orderRecord)
		if !ok {
			continue
		}
		if evictRecord.Status == domain.OrderStatusFilled || evictRecord.Status == domain.OrderStatusCancelled {
			s.orders.Delete(evictID)
		}
	}
	if s.terminalRetentionHead > 0 && s.terminalRetentionHead*2 >= len(s.terminalRetentionOrder) {
		s.terminalRetentionOrder = append([]string(nil), s.terminalRetentionOrder[s.terminalRetentionHead:]...)
		s.terminalRetentionHead = 0
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
		book: hotbook.New(),
	}
}

func parsePositiveInt(value string) (int64, bool) {
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, false
	}
	return parsed, true
}

func acceptedResult(verb string, orderID string, occurredAt string) domain.SubmitOrderResult {
	return domain.SubmitOrderResult{
		Accepted: &domain.OrderAccepted{
			EventID:       "evt-order-" + verb + "-" + orderID,
			OrderID:       orderID,
			EngineOrderID: "eng-" + orderID,
			OccurredAt:    occurredAt,
		},
	}
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
	book.book.Remove(record.OrderID)
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

func envInt(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(raw)
	if err != nil || parsed < 0 {
		return fallback
	}
	return parsed
}
