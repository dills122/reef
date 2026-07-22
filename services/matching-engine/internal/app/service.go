package app

import (
	"crypto/sha256"
	"encoding/hex"
	"math"
	"math/bits"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	hotbook "github.com/dills122/reef/services/matching-engine/internal/book"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type Service struct {
	booksMu           sync.RWMutex
	books             map[string]*orderBook
	orderIndex        *orderIndex
	now               func() time.Time
	orderControls     OrderControls
	sessionControls   SessionControls
	matchingProfiles  MatchingProfiles
	stpMode           SelfTradePreventionMode
	terminalRetention terminalOrderRetention
}

type restingOrder = hotbook.RestingOrder

type orderBook struct {
	mu   sync.Mutex
	book *hotbook.Book
}

type orderRecord struct {
	OrderID           string
	InstrumentID      string
	VenueSessionID    string
	ParticipantID     string
	AccountID         string
	Side              domain.Side
	OriginalQuantity  int64
	RemainingQuantity int64
	LimitPrice        int64
	Currency          string
	Status            domain.OrderStatus
	LastUpdatedAt     string
	terminalTracked   bool
}

type OrderControls struct {
	MaxQuantityUnits int64
	MaxNotional      int64
	PriceCollars     map[string]PriceCollar
}

type PriceCollar struct {
	ReferencePrice int64
	BandBps        int64
}

type Snapshot struct {
	Metadata SnapshotMetadata            `json:"metadata"`
	Books    map[string]hotbook.Snapshot `json:"books"`
	Orders   []SnapshotOrderRecord       `json:"orders"`
	Checksum string                      `json:"checksum"`
}

type SnapshotMetadata struct {
	SnapshotVersion string   `json:"snapshotVersion"`
	EngineVersion   string   `json:"engineVersion"`
	BookCount       int      `json:"bookCount"`
	OrderCount      int      `json:"orderCount"`
	BookKeys        []string `json:"bookKeys"`
}

type SnapshotOrderRecord struct {
	OrderID           string             `json:"orderId"`
	InstrumentID      string             `json:"instrumentId"`
	VenueSessionID    string             `json:"venueSessionId"`
	ParticipantID     string             `json:"participantId"`
	AccountID         string             `json:"accountId"`
	Side              domain.Side        `json:"side"`
	OriginalQuantity  int64              `json:"originalQuantity"`
	RemainingQuantity int64              `json:"remainingQuantity"`
	LimitPrice        int64              `json:"limitPrice"`
	Currency          string             `json:"currency"`
	Status            domain.OrderStatus `json:"status"`
	LastUpdatedAt     string             `json:"lastUpdatedAt"`
}

type BookStats struct {
	InstrumentID    string `json:"instrumentId"`
	BuyOrders       int    `json:"buyOrders"`
	SellOrders      int    `json:"sellOrders"`
	BuyPriceLevels  int    `json:"buyPriceLevels"`
	SellPriceLevels int    `json:"sellPriceLevels"`
	Checksum        string `json:"checksum"`
}

type BookScope struct {
	VenueSessionID string
	InstrumentID   string
}

func (s BookScope) Key() string {
	return bookKey(s.VenueSessionID, s.InstrumentID)
}

type MatchAlgorithm string

const (
	MatchAlgorithmFIFO MatchAlgorithm = "FIFO"
)

type MatchingProfiles struct {
	DefaultAlgorithm MatchAlgorithm
	Instruments      map[string]MatchAlgorithm
}

type SelfTradePreventionMode string

const (
	SelfTradePreventionCancelNewest SelfTradePreventionMode = "CANCEL_NEWEST"
	SelfTradePreventionCancelOldest SelfTradePreventionMode = "CANCEL_OLDEST"
)

type SessionState string

const (
	SessionStateOpen   SessionState = "OPEN"
	SessionStateHalted SessionState = "HALTED"
	SessionStateClosed SessionState = "CLOSED"
)

type SessionControls struct {
	DefaultState SessionState
	States       map[string]SessionState
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
			s.terminalRetention.limit = limit
		}
	}
}

func WithOrderControls(controls OrderControls) Option {
	return func(s *Service) {
		s.orderControls = controls
	}
}

func WithSessionControls(controls SessionControls) Option {
	return func(s *Service) {
		s.sessionControls = controls
	}
}

func WithMatchingProfiles(profiles MatchingProfiles) Option {
	return func(s *Service) {
		s.matchingProfiles = profiles
	}
}

func WithSelfTradePreventionMode(mode SelfTradePreventionMode) Option {
	return func(s *Service) {
		s.stpMode = mode
	}
}

func NewService(options ...Option) *Service {
	service := &Service{
		books:      make(map[string]*orderBook),
		orderIndex: newOrderIndex(),
		terminalRetention: terminalOrderRetention{
			limit: envInt("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT", 0),
		},
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
	return s.submitOrder(cmd, nil)
}

func (s *Service) SubmitOrderInBatch(rollback *BatchRollback, cmd domain.SubmitOrder) domain.SubmitOrderResult {
	return s.submitOrder(cmd, rollback)
}

func (s *Service) submitOrder(cmd domain.SubmitOrder, rollback *BatchRollback) domain.SubmitOrderResult {
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
	if rejection := s.validateMatchingProfile(cmd.OrderID, cmd.InstrumentID, now); rejection != nil {
		return *rejection
	}
	if rejection := s.validateSessionForSubmit(cmd.OrderID, cmd.VenueSessionID, now); rejection != nil {
		return *rejection
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
	if rejection := s.validateOrderControls(cmd.OrderID, cmd.InstrumentID, quantityUnits, limitPrice, now); rejection != nil {
		return *rejection
	}

	result := acceptedResult("accepted", cmd.OrderID, now)

	book := s.bookFor(cmd.VenueSessionID, cmd.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	record := &orderRecord{
		OrderID:           cmd.OrderID,
		InstrumentID:      cmd.InstrumentID,
		VenueSessionID:    cmd.VenueSessionID,
		ParticipantID:     cmd.ParticipantID,
		AccountID:         cmd.AccountID,
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
	if rollback != nil {
		rollback.trackCreatedOrder(book, record)
	}
	if accepted, rejection := s.applySelfTradePrevention(rollback, book, record, now); !accepted {
		s.releaseOrder(record.OrderID)
		return rejection
	}
	incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)

	s.match(rollback, book, incoming, record.Side, &result, now)
	if record.RemainingQuantity > 0 {
		book.book.Add(record.Side, incoming)
	}

	s.refreshOrderStatus(rollback, record)

	return result
}

func (s *Service) CancelOrder(cmd domain.CancelOrder) domain.SubmitOrderResult {
	return s.cancelOrder(cmd, nil)
}

func (s *Service) CancelOrderInBatch(rollback *BatchRollback, cmd domain.CancelOrder) domain.SubmitOrderResult {
	return s.cancelOrder(cmd, rollback)
}

func (s *Service) cancelOrder(cmd domain.CancelOrder, rollback *BatchRollback) domain.SubmitOrderResult {
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
	if rejection := s.validateSessionForCancel(cmd.OrderID, record.VenueSessionID, now); rejection != nil {
		return *rejection
	}
	book := s.bookFor(record.VenueSessionID, record.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	if record.Status == domain.OrderStatusFilled {
		return rejectedResult("evt-reject-order-filled", cmd.OrderID, "INVALID_STATE", "filled order cannot be cancelled", now)
	}
	if record.Status == domain.OrderStatusCancelled {
		return rejectedResult("evt-reject-order-cancelled", cmd.OrderID, "INVALID_STATE", "order already cancelled", now)
	}

	s.removeRestingOrder(rollback, book, record)
	record.RemainingQuantity = 0
	record.Status = domain.OrderStatusCancelled
	record.LastUpdatedAt = now
	s.trackTerminalOrder(rollback, record)

	return acceptedResult("cancelled", cmd.OrderID, now)
}

func (s *Service) ModifyOrder(cmd domain.ModifyOrder) domain.SubmitOrderResult {
	return s.modifyOrder(cmd, nil)
}

func (s *Service) ModifyOrderInBatch(rollback *BatchRollback, cmd domain.ModifyOrder) domain.SubmitOrderResult {
	return s.modifyOrder(cmd, rollback)
}

func (s *Service) modifyOrder(cmd domain.ModifyOrder, rollback *BatchRollback) domain.SubmitOrderResult {
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
	if rejection := s.validateMatchingProfile(cmd.OrderID, record.InstrumentID, now); rejection != nil {
		return *rejection
	}
	if rejection := s.validateSessionForModify(cmd.OrderID, record.VenueSessionID, now); rejection != nil {
		return *rejection
	}
	book := s.bookFor(record.VenueSessionID, record.InstrumentID)
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
	if rejection := s.validateOrderControls(cmd.OrderID, record.InstrumentID, quantityUnits, limitPrice, now); rejection != nil {
		return *rejection
	}

	alreadyFilled := record.OriginalQuantity - record.RemainingQuantity
	if quantityUnits <= alreadyFilled {
		return rejectedResult("evt-reject-invalid-modify-quantity", cmd.OrderID, "VALIDATION_ERROR", "quantityUnits must remain above already filled quantity", now)
	}

	resetPriority := limitPrice != record.LimitPrice || quantityUnits > record.OriginalQuantity
	if resetPriority {
		proposed := *record
		proposed.OriginalQuantity = quantityUnits
		proposed.RemainingQuantity = quantityUnits - alreadyFilled
		proposed.LimitPrice = limitPrice
		if accepted, rejection := s.applySelfTradePrevention(rollback, book, &proposed, now); !accepted {
			return rejection
		}
	}
	if resetPriority {
		s.removeRestingOrder(rollback, book, record)
	}

	if rollback != nil {
		rollback.trackOrder(book, record)
	}
	record.OriginalQuantity = quantityUnits
	record.RemainingQuantity = quantityUnits - alreadyFilled
	record.LimitPrice = limitPrice
	record.LastUpdatedAt = now
	s.refreshOrderStatus(rollback, record)

	result := acceptedResult("modified", cmd.OrderID, now)

	if resetPriority && record.RemainingQuantity > 0 {
		incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)
		s.match(rollback, book, incoming, record.Side, &result, now)
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

func (s *Service) validateOrderControls(orderID string, instrumentID string, quantityUnits int64, limitPrice int64, occurredAt string) *domain.SubmitOrderResult {
	if s.orderControls.MaxQuantityUnits > 0 && quantityUnits > s.orderControls.MaxQuantityUnits {
		result := rejectedResult("evt-reject-max-quantity-"+orderID, orderID, "MARKET_INTEGRITY_CONTROL", "quantityUnits exceeds configured maximum", occurredAt)
		return &result
	}
	if s.orderControls.MaxNotional > 0 && exceedsNotional(quantityUnits, limitPrice, s.orderControls.MaxNotional) {
		result := rejectedResult("evt-reject-max-notional-"+orderID, orderID, "MARKET_INTEGRITY_CONTROL", "order notional exceeds configured maximum", occurredAt)
		return &result
	}
	if collar, ok := s.orderControls.PriceCollars[instrumentID]; ok && !priceWithinCollar(limitPrice, collar) {
		result := rejectedResult("evt-reject-price-collar-"+orderID, orderID, "MARKET_INTEGRITY_CONTROL", "limitPrice is outside configured price collar", occurredAt)
		return &result
	}
	return nil
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
	s.booksMu.RLock()
	books := make([]*orderBook, 0, len(s.books))
	for key, book := range s.books {
		if bookKeyMatchesInstrument(key, instrumentID) {
			books = append(books, book)
		}
	}
	s.booksMu.RUnlock()

	total := 0
	for _, book := range books {
		book.mu.Lock()
		total += restingOrdersInBook(book, side)
		book.mu.Unlock()
	}
	return total
}

func (s *Service) RestingOrdersInSession(venueSessionID string, instrumentID string, side domain.Side) int {
	book, ok := s.loadBook(venueSessionID, instrumentID)
	if !ok {
		return 0
	}
	book.mu.Lock()
	defer book.mu.Unlock()
	return restingOrdersInBook(book, side)
}

func restingOrdersInBook(book *orderBook, side domain.Side) int {
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
	book := s.bookFor(record.VenueSessionID, record.InstrumentID)
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

func (s *Service) Snapshot() Snapshot {
	bookIDs, books, unlock := s.lockSnapshotBooks(nil)
	defer unlock()
	return s.buildSnapshot(bookIDs, books, nil)
}

func (s *Service) BookStats(instrumentID string) BookStats {
	s.booksMu.RLock()
	books := make(map[string]*orderBook, len(s.books))
	bookKeys := make([]string, 0, len(s.books))
	for key, book := range s.books {
		if bookKeyMatchesInstrument(key, instrumentID) {
			books[key] = book
			bookKeys = append(bookKeys, key)
		}
	}
	s.booksMu.RUnlock()
	sort.Strings(bookKeys)

	stats := BookStats{
		InstrumentID: instrumentID,
	}
	if len(bookKeys) == 0 {
		stats.Checksum = hotbook.New().Snapshot().Checksum
		return stats
	}

	checksumInput := strings.Builder{}
	var singleBookChecksum string
	for _, key := range bookKeys {
		book := books[key]
		book.mu.Lock()
		snapshot := book.book.Snapshot()
		stats.BuyOrders += book.book.Len(domain.SideBuy)
		stats.SellOrders += book.book.Len(domain.SideSell)
		stats.BuyPriceLevels += book.book.LevelCount(domain.SideBuy)
		stats.SellPriceLevels += book.book.LevelCount(domain.SideSell)
		book.mu.Unlock()
		singleBookChecksum = snapshot.Checksum
		checksumInput.WriteString(key)
		checksumInput.WriteByte(':')
		checksumInput.WriteString(snapshot.Checksum)
		checksumInput.WriteByte(';')
	}
	if len(bookKeys) == 1 {
		stats.Checksum = singleBookChecksum
		return stats
	}
	sum := sha256.Sum256([]byte(checksumInput.String()))
	stats.Checksum = hex.EncodeToString(sum[:])
	return stats
}

func (s *Service) SnapshotForInstrument(instrumentID string) (Snapshot, bool) {
	bookKeys, books, unlock := s.lockSnapshotBooks(func(key string) bool {
		return bookKeyMatchesInstrument(key, instrumentID)
	})
	defer unlock()
	if len(bookKeys) == 0 {
		return Snapshot{}, false
	}

	return s.buildSnapshot(bookKeys, books, func(record *orderRecord) bool {
		return record.InstrumentID == instrumentID
	}), true
}

// buildSnapshot assembles a Snapshot from the given (already locked) book set
// and order filter. bookIDs must already be sorted and locked by the caller;
// unlocking is the caller's responsibility.
func (s *Service) buildSnapshot(bookIDs []string, books map[string]*orderBook, includeOrder func(*orderRecord) bool) Snapshot {
	snapshot := Snapshot{
		Books: make(map[string]hotbook.Snapshot),
	}
	for _, instrumentID := range bookIDs {
		snapshot.Books[instrumentID] = books[instrumentID].book.Snapshot()
	}

	s.orderIndex.forEach(func(record *orderRecord) {
		if includeOrder != nil && !includeOrder(record) {
			return
		}
		snapshot.Orders = append(snapshot.Orders, snapshotOrderRecord(record))
	})
	sort.Slice(snapshot.Orders, func(i, j int) bool {
		return snapshot.Orders[i].OrderID < snapshot.Orders[j].OrderID
	})
	snapshot.Metadata = SnapshotMetadata{
		SnapshotVersion: "matching-service-snapshot-v1",
		EngineVersion:   "matching-engine-app-v1",
		BookCount:       len(snapshot.Books),
		OrderCount:      len(snapshot.Orders),
		BookKeys:        bookIDs,
	}
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	return snapshot
}

func (s *Service) lockSnapshotBooks(include func(string) bool) ([]string, map[string]*orderBook, func()) {
	s.booksMu.RLock()
	bookIDs := make([]string, 0, len(s.books))
	books := make(map[string]*orderBook, len(s.books))
	for instrumentID, book := range s.books {
		if include != nil && !include(instrumentID) {
			continue
		}
		bookIDs = append(bookIDs, instrumentID)
		books[instrumentID] = book
	}
	sort.Strings(bookIDs)
	for _, instrumentID := range bookIDs {
		books[instrumentID].mu.Lock()
	}
	return bookIDs, books, func() {
		for i := len(bookIDs) - 1; i >= 0; i-- {
			books[bookIDs[i]].mu.Unlock()
		}
		s.booksMu.RUnlock()
	}
}

func snapshotOrderRecord(record *orderRecord) SnapshotOrderRecord {
	return SnapshotOrderRecord{
		OrderID:           record.OrderID,
		InstrumentID:      record.InstrumentID,
		VenueSessionID:    record.VenueSessionID,
		ParticipantID:     record.ParticipantID,
		AccountID:         record.AccountID,
		Side:              record.Side,
		OriginalQuantity:  record.OriginalQuantity,
		RemainingQuantity: record.RemainingQuantity,
		LimitPrice:        record.LimitPrice,
		Currency:          record.Currency,
		Status:            record.Status,
		LastUpdatedAt:     record.LastUpdatedAt,
	}
}

func (s *Service) MatchAlgorithm(instrumentID string) MatchAlgorithm {
	if algorithm, ok := s.matchingProfiles.Instruments[instrumentID]; ok && algorithm != "" {
		return algorithm
	}
	if s.matchingProfiles.DefaultAlgorithm != "" {
		return s.matchingProfiles.DefaultAlgorithm
	}
	return MatchAlgorithmFIFO
}

func Restore(snapshot Snapshot, options ...Option) (*Service, bool) {
	if !validSnapshotMetadata(snapshot) {
		return nil, false
	}
	if snapshot.Checksum != "" && snapshot.Checksum != serviceSnapshotChecksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	service := NewService(options...)
	for instrumentID, bookSnapshot := range snapshot.Books {
		restored, ok := hotbook.Restore(bookSnapshot)
		if !ok {
			return nil, false
		}
		service.books[instrumentID] = &orderBook{book: restored}
	}
	seenOrderIDs := make(map[string]bool, len(snapshot.Orders))
	for _, order := range snapshot.Orders {
		if order.OrderID == "" || seenOrderIDs[order.OrderID] {
			return nil, false
		}
		seenOrderIDs[order.OrderID] = true
		record := &orderRecord{
			OrderID:           order.OrderID,
			InstrumentID:      order.InstrumentID,
			VenueSessionID:    order.VenueSessionID,
			ParticipantID:     order.ParticipantID,
			AccountID:         order.AccountID,
			Side:              order.Side,
			OriginalQuantity:  order.OriginalQuantity,
			RemainingQuantity: order.RemainingQuantity,
			LimitPrice:        order.LimitPrice,
			Currency:          order.Currency,
			Status:            order.Status,
			LastUpdatedAt:     order.LastUpdatedAt,
		}
		service.orderIndex.restore(record)
	}
	if service.Snapshot().Checksum != serviceSnapshotChecksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	return service, true
}

func (s *Service) loadBook(venueSessionID string, instrumentID string) (*orderBook, bool) {
	s.booksMu.RLock()
	existing, ok := s.books[bookKey(venueSessionID, instrumentID)]
	s.booksMu.RUnlock()
	return existing, ok
}

func (s *Service) bookFor(venueSessionID string, instrumentID string) *orderBook {
	existing, ok := s.loadBook(venueSessionID, instrumentID)
	if ok {
		return existing
	}

	s.booksMu.Lock()
	defer s.booksMu.Unlock()
	key := bookKey(venueSessionID, instrumentID)
	if existing, ok := s.books[key]; ok {
		return existing
	}
	book := newOrderBook()
	s.books[key] = book
	return book
}

func bookKey(venueSessionID string, instrumentID string) string {
	if venueSessionID == "" {
		return instrumentID
	}
	return venueSessionID + "|" + instrumentID
}

func bookKeyMatchesInstrument(key string, instrumentID string) bool {
	return key == instrumentID || strings.HasSuffix(key, "|"+instrumentID)
}

func (s *Service) validateSessionForSubmit(orderID string, venueSessionID string, occurredAt string) *domain.SubmitOrderResult {
	state := s.sessionState(venueSessionID)
	if state == SessionStateOpen {
		return nil
	}
	result := rejectedResult("evt-reject-session-state-"+orderID, orderID, "SESSION_STATE_REJECT", "venue session is not open for submit", occurredAt)
	return &result
}

func (s *Service) validateSessionForModify(orderID string, venueSessionID string, occurredAt string) *domain.SubmitOrderResult {
	state := s.sessionState(venueSessionID)
	if state == SessionStateOpen {
		return nil
	}
	result := rejectedResult("evt-reject-session-state-"+orderID, orderID, "SESSION_STATE_REJECT", "venue session is not open for modify", occurredAt)
	return &result
}

func (s *Service) validateSessionForCancel(orderID string, venueSessionID string, occurredAt string) *domain.SubmitOrderResult {
	state := s.sessionState(venueSessionID)
	if state != SessionStateClosed {
		return nil
	}
	result := rejectedResult("evt-reject-session-state-"+orderID, orderID, "SESSION_STATE_REJECT", "venue session is closed for cancel", occurredAt)
	return &result
}

func (s *Service) sessionState(venueSessionID string) SessionState {
	if state, ok := s.sessionControls.States[venueSessionID]; ok && state != "" {
		return state
	}
	if s.sessionControls.DefaultState != "" {
		return s.sessionControls.DefaultState
	}
	return SessionStateOpen
}

func (s *Service) validateMatchingProfile(orderID string, instrumentID string, occurredAt string) *domain.SubmitOrderResult {
	algorithm := s.MatchAlgorithm(instrumentID)
	if algorithm == MatchAlgorithmFIFO {
		return nil
	}
	result := rejectedResult("evt-reject-match-algorithm-"+orderID, orderID, "UNSUPPORTED_MATCH_ALGORITHM", "matching algorithm is not supported", occurredAt)
	return &result
}

func (s *Service) applySelfTradePrevention(rollback *BatchRollback, book *orderBook, incoming *orderRecord, occurredAt string) (bool, domain.SubmitOrderResult) {
	matches := s.reachableSelfTradeRestingRecords(book, incoming)
	if len(matches) == 0 {
		return true, domain.SubmitOrderResult{}
	}
	if s.selfTradePreventionMode() == SelfTradePreventionCancelOldest {
		for _, restingRecord := range matches {
			s.removeRestingOrder(rollback, book, restingRecord)
			restingRecord.RemainingQuantity = 0
			restingRecord.Status = domain.OrderStatusCancelled
			restingRecord.LastUpdatedAt = occurredAt
			s.trackTerminalOrder(rollback, restingRecord)
		}
		return true, domain.SubmitOrderResult{}
	}
	return false, rejectedResult("evt-reject-self-trade-"+incoming.OrderID, incoming.OrderID, "SELF_TRADE_PREVENTION", "order would trade with resting order from same participant or account", occurredAt)
}

func (s *Service) selfTradePreventionMode() SelfTradePreventionMode {
	if s.stpMode != "" {
		return s.stpMode
	}
	return SelfTradePreventionCancelNewest
}

func (s *Service) reachableSelfTradeRestingRecords(book *orderBook, incoming *orderRecord) []*orderRecord {
	if incoming == nil || !hasSelfTradeIdentity(incoming) {
		return nil
	}
	remaining := incoming.RemainingQuantity
	matches := make([]*orderRecord, 0)
	book.book.ForEachCrossingResting(incoming.Side, incoming.LimitPrice, func(resting hotbook.RestingOrder) bool {
		if remaining <= 0 {
			return false
		}
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok || restingRecord.OrderID == incoming.OrderID {
			return true
		}
		if sameSelfTradeIdentity(incoming, restingRecord) {
			matches = append(matches, restingRecord)
			return true
		}
		remaining -= restingRecord.RemainingQuantity
		return remaining > 0
	})
	return matches
}

func hasSelfTradeIdentity(record *orderRecord) bool {
	return strings.TrimSpace(record.ParticipantID) != "" || strings.TrimSpace(record.AccountID) != ""
}

func sameSelfTradeIdentity(a *orderRecord, b *orderRecord) bool {
	if strings.TrimSpace(a.ParticipantID) != "" && a.ParticipantID == b.ParticipantID {
		return true
	}
	if strings.TrimSpace(a.AccountID) != "" && a.AccountID == b.AccountID {
		return true
	}
	return false
}

func (s Snapshot) withoutChecksum() Snapshot {
	s.Checksum = ""
	return s
}

func validSnapshotMetadata(snapshot Snapshot) bool {
	if snapshot.Metadata.SnapshotVersion == "" && snapshot.Metadata.EngineVersion == "" {
		return true
	}
	if snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v1" || snapshot.Metadata.EngineVersion == "" {
		return false
	}
	if snapshot.Metadata.BookCount != len(snapshot.Books) || snapshot.Metadata.OrderCount != len(snapshot.Orders) {
		return false
	}
	if len(snapshot.Metadata.BookKeys) != len(snapshot.Books) {
		return false
	}
	keys := make([]string, 0, len(snapshot.Books))
	for key := range snapshot.Books {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for i, key := range keys {
		if snapshot.Metadata.BookKeys[i] != key {
			return false
		}
	}
	return true
}

func serviceSnapshotChecksum(snapshot Snapshot) string {
	var builder strings.Builder
	builder.WriteString("metadata:")
	builder.WriteString(snapshot.Metadata.SnapshotVersion)
	builder.WriteByte(':')
	builder.WriteString(snapshot.Metadata.EngineVersion)
	builder.WriteByte(':')
	builder.WriteString(strconv.Itoa(snapshot.Metadata.BookCount))
	builder.WriteByte(':')
	builder.WriteString(strconv.Itoa(snapshot.Metadata.OrderCount))
	builder.WriteByte(':')
	builder.WriteString(strings.Join(snapshot.Metadata.BookKeys, ","))
	builder.WriteByte(';')
	bookIDs := make([]string, 0, len(snapshot.Books))
	for instrumentID := range snapshot.Books {
		bookIDs = append(bookIDs, instrumentID)
	}
	sort.Strings(bookIDs)
	for _, instrumentID := range bookIDs {
		book := snapshot.Books[instrumentID]
		builder.WriteString("book:")
		builder.WriteString(instrumentID)
		builder.WriteByte(':')
		builder.WriteString(book.Checksum)
		builder.WriteByte(':')
		builder.WriteString(strconv.FormatInt(book.NextSequence, 10))
		builder.WriteByte(';')
	}
	orders := append([]SnapshotOrderRecord(nil), snapshot.Orders...)
	sort.Slice(orders, func(i, j int) bool {
		return orders[i].OrderID < orders[j].OrderID
	})
	for _, order := range orders {
		builder.WriteString("order:")
		builder.WriteString(order.OrderID)
		builder.WriteByte(':')
		builder.WriteString(order.InstrumentID)
		builder.WriteByte(':')
		builder.WriteString(order.VenueSessionID)
		builder.WriteByte(':')
		builder.WriteString(order.ParticipantID)
		builder.WriteByte(':')
		builder.WriteString(order.AccountID)
		builder.WriteByte(':')
		builder.WriteString(string(order.Side))
		builder.WriteByte(':')
		builder.WriteString(strconv.FormatInt(order.OriginalQuantity, 10))
		builder.WriteByte(':')
		builder.WriteString(strconv.FormatInt(order.RemainingQuantity, 10))
		builder.WriteByte(':')
		builder.WriteString(strconv.FormatInt(order.LimitPrice, 10))
		builder.WriteByte(':')
		builder.WriteString(order.Currency)
		builder.WriteByte(':')
		builder.WriteString(string(order.Status))
		builder.WriteByte(':')
		builder.WriteString(order.LastUpdatedAt)
		builder.WriteByte(';')
	}
	sum := sha256.Sum256([]byte(builder.String()))
	return hex.EncodeToString(sum[:])
}

// BatchRollback journals the pre-mutation state for orders touched by a
// direct-consume batch, so a failed durable VenueEventBatch publish can undo
// live engine mutations without snapshotting an entire hot book.
type BatchRollback struct {
	service          *Service
	instruments      map[string]*instrumentRollback
	records          map[string]*orderRollback
	terminalOrderIDs []string
	committed        bool
}

type instrumentRollback struct {
	book         *orderBook
	nextSequence int64
	orders       map[string]*orderRollback
}

type orderRollback struct {
	existed      bool
	record       orderRecord
	resting      bool
	restingSide  domain.Side
	restingOrder hotbook.RestingOrder
}

// BeginBatch captures each distinct venue-session/instrument book's sequence
// watermark before processing starts. Individual order/book entries are
// journaled lazily on first mutation.
func (s *Service) BeginBatch(scopes []BookScope) *BatchRollback {
	rollback := &BatchRollback{
		service:     s,
		instruments: make(map[string]*instrumentRollback),
		records:     make(map[string]*orderRollback),
	}
	for _, scope := range scopes {
		if scope.InstrumentID == "" {
			continue
		}
		key := bookKey(scope.VenueSessionID, scope.InstrumentID)
		if _, ok := rollback.instruments[key]; ok {
			continue
		}
		book := s.bookFor(scope.VenueSessionID, scope.InstrumentID)
		book.mu.Lock()
		nextSequence := book.book.NextSequence()
		book.mu.Unlock()
		rollback.instruments[key] = &instrumentRollback{
			book:         book,
			nextSequence: nextSequence,
			orders:       make(map[string]*orderRollback),
		}
	}
	return rollback
}

// Rollback restores journaled book entries and order records to their
// pre-batch state.
func (rb *BatchRollback) Rollback() {
	if rb == nil || rb.committed {
		return
	}
	for _, snap := range rb.instruments {
		snap.book.mu.Lock()
		for orderID, entry := range snap.orders {
			snap.book.book.Remove(orderID)
			if entry.resting {
				snap.book.book.RestoreRestingOrder(entry.restingSide, entry.restingOrder)
			}
		}
		snap.book.book.SetNextSequence(snap.nextSequence)
		snap.book.mu.Unlock()
	}

	for _, snap := range rb.instruments {
		for orderID, entry := range snap.orders {
			var record *orderRecord
			if entry.existed {
				recordCopy := entry.record
				record = &recordCopy
			}
			rb.service.orderIndex.restoreOrDelete(orderID, entry.existed, record)
		}
	}
	for orderID, entry := range rb.records {
		var record *orderRecord
		if entry.existed {
			recordCopy := entry.record
			record = &recordCopy
		}
		rb.service.orderIndex.restoreOrDelete(orderID, entry.existed, record)
	}
}

// Commit applies deferred global retention mutations after the batch outcome
// is durable. Book/order mutations are already live and need no commit work.
func (rb *BatchRollback) Commit() {
	if rb == nil || rb.committed {
		return
	}
	rb.committed = true
	for _, orderID := range rb.terminalOrderIDs {
		rb.service.terminalRetention.commit(orderID, func(evictID string) {
			evictRecord, ok := rb.service.loadOrder(evictID)
			if !ok {
				return
			}
			if evictRecord.Status == domain.OrderStatusFilled || evictRecord.Status == domain.OrderStatusCancelled {
				rb.service.orderIndex.release(evictID)
			}
		})
	}
}

func (rb *BatchRollback) trackTerminalOrder(orderID string) {
	if rb == nil || orderID == "" {
		return
	}
	rb.terminalOrderIDs = append(rb.terminalOrderIDs, orderID)
}

func (rb *BatchRollback) trackCreatedOrder(book *orderBook, record *orderRecord) {
	if rb == nil || record == nil {
		return
	}
	snap := rb.instrument(bookKey(record.VenueSessionID, record.InstrumentID), book)
	if snap == nil {
		return
	}
	if _, ok := snap.orders[record.OrderID]; ok {
		return
	}
	snap.orders[record.OrderID] = &orderRollback{}
}

func (rb *BatchRollback) trackOrder(book *orderBook, record *orderRecord) {
	if rb == nil || record == nil {
		return
	}
	snap := rb.instrument(bookKey(record.VenueSessionID, record.InstrumentID), book)
	if snap == nil {
		return
	}
	rb.trackOrderInInstrument(snap, record.OrderID, record)
}

func (rb *BatchRollback) trackRestingOrder(book *orderBook, orderID string) {
	if rb == nil || orderID == "" {
		return
	}
	snap := rb.instrumentForBook(book)
	if snap == nil {
		return
	}
	rb.trackOrderInInstrument(snap, orderID, nil)
}

func (rb *BatchRollback) trackOrderInInstrument(snap *instrumentRollback, orderID string, record *orderRecord) {
	if _, ok := snap.orders[orderID]; ok {
		return
	}
	entry := &orderRollback{}
	if record != nil {
		entry.existed = true
		entry.record = *record
	} else if current, ok := rb.service.loadOrder(orderID); ok {
		entry.existed = true
		entry.record = *current
	}
	if side, resting, ok := snap.book.book.RestingOrder(orderID); ok {
		entry.resting = true
		entry.restingSide = side
		entry.restingOrder = resting
	}
	snap.orders[orderID] = entry
}

func (rb *BatchRollback) trackOrderRecord(record *orderRecord) {
	if rb == nil || record == nil {
		return
	}
	if rb.hasInstrumentOrder(record.OrderID) {
		return
	}
	if _, ok := rb.records[record.OrderID]; ok {
		return
	}
	rb.records[record.OrderID] = &orderRollback{
		existed: true,
		record:  *record,
	}
}

func (rb *BatchRollback) instrument(key string, book *orderBook) *instrumentRollback {
	if snap, ok := rb.instruments[key]; ok {
		return snap
	}
	if book == nil {
		return nil
	}
	nextSequence := book.book.NextSequence()
	snap := &instrumentRollback{
		book:         book,
		nextSequence: nextSequence,
		orders:       make(map[string]*orderRollback),
	}
	rb.instruments[key] = snap
	return snap
}

func (rb *BatchRollback) instrumentForBook(book *orderBook) *instrumentRollback {
	for _, snap := range rb.instruments {
		if snap.book == book {
			return snap
		}
	}
	return nil
}

func (rb *BatchRollback) hasInstrumentOrder(orderID string) bool {
	for _, snap := range rb.instruments {
		if _, ok := snap.orders[orderID]; ok {
			return true
		}
	}
	return false
}

// match executes incoming against the resting book on the opposite side.
// Buy and sell only differ in which side of the book they cross and the
// direction of the price-improvement comparison; encoding both sides in one
// function avoids the two implementations drifting when one is patched and
// the other isn't.
func (s *Service) match(rollback *BatchRollback, book *orderBook, incoming restingOrder, side domain.Side, result *domain.SubmitOrderResult, occurredAt string) {
	incomingRecord, ok := s.loadOrder(incoming.OrderID)
	if !ok {
		return
	}

	opposite := domain.SideSell
	if side == domain.SideSell {
		opposite = domain.SideBuy
	}

	for incomingRecord.RemainingQuantity > 0 && book.book.Len(opposite) > 0 {
		resting, ok := book.book.Best(opposite)
		if !ok {
			return
		}
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok {
			if rollback != nil {
				rollback.trackRestingOrder(book, resting.OrderID)
			}
			book.book.PopBest(opposite)
			continue
		}
		if side == domain.SideBuy {
			if incomingRecord.LimitPrice < restingRecord.LimitPrice {
				return
			}
		} else if incomingRecord.LimitPrice > restingRecord.LimitPrice {
			return
		}

		if rollback != nil {
			rollback.trackOrder(book, incomingRecord)
			rollback.trackOrder(book, restingRecord)
		}
		matchedUnits := minInt64(incomingRecord.RemainingQuantity, restingRecord.RemainingQuantity)
		executionPrice := restingRecord.LimitPrice
		if side == domain.SideBuy {
			s.appendMatch(result, incomingRecord, restingRecord, incomingRecord.OrderID, matchedUnits, executionPrice, occurredAt)
		} else {
			s.appendMatch(result, restingRecord, incomingRecord, incomingRecord.OrderID, matchedUnits, executionPrice, occurredAt)
		}

		incomingRecord.RemainingQuantity -= matchedUnits
		restingRecord.RemainingQuantity -= matchedUnits
		incomingRecord.LastUpdatedAt = occurredAt
		restingRecord.LastUpdatedAt = occurredAt
		s.refreshOrderStatus(rollback, incomingRecord)
		s.refreshOrderStatus(rollback, restingRecord)
		if restingRecord.RemainingQuantity == 0 {
			book.book.PopBest(opposite)
		}
	}
}

func (s *Service) appendMatch(result *domain.SubmitOrderResult, buyOrder *orderRecord, sellOrder *orderRecord, incomingOrderID string, matchedUnits int64, executionPrice int64, occurredAt string) {
	seq := strconv.Itoa(len(result.Trades) + 1)
	executionID := "exec-" + buyOrder.OrderID + "-" + sellOrder.OrderID + "-" + seq
	tradeID := "trade-" + buyOrder.OrderID + "-" + sellOrder.OrderID + "-" + seq
	matchedUnitsStr := strconv.FormatInt(matchedUnits, 10)
	executionPriceStr := strconv.FormatInt(executionPrice, 10)
	buyLiquidityRole := "MAKER"
	sellLiquidityRole := "MAKER"
	if buyOrder.OrderID == incomingOrderID {
		buyLiquidityRole = "TAKER"
	}
	if sellOrder.OrderID == incomingOrderID {
		sellLiquidityRole = "TAKER"
	}

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
			LiquidityRole:  buyLiquidityRole,
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
			LiquidityRole:  sellLiquidityRole,
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

func (s *Service) refreshOrderStatus(rollback *BatchRollback, record *orderRecord) {
	switch {
	case record.RemainingQuantity == record.OriginalQuantity:
		record.Status = domain.OrderStatusAccepted
	case record.RemainingQuantity == 0:
		record.Status = domain.OrderStatusFilled
		s.trackTerminalOrder(rollback, record)
	default:
		record.Status = domain.OrderStatusPartiallyFilled
	}
}

func (s *Service) trackTerminalOrder(rollback *BatchRollback, record *orderRecord) {
	if rollback != nil {
		if s.terminalRetention.limit <= 0 || record.terminalTracked {
			return
		}
		if record.Status != domain.OrderStatusFilled && record.Status != domain.OrderStatusCancelled {
			return
		}
		record.terminalTracked = true
		rollback.trackTerminalOrder(record.OrderID)
		return
	}
	s.terminalRetention.track(record, func(evictID string) {
		evictRecord, ok := s.loadOrder(evictID)
		if !ok {
			return
		}
		if evictRecord.Status == domain.OrderStatusFilled || evictRecord.Status == domain.OrderStatusCancelled {
			s.orderIndex.release(evictID)
		}
	})
}

func minInt64(a int64, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func exceedsNotional(quantityUnits int64, limitPrice int64, maxNotional int64) bool {
	if quantityUnits <= 0 || limitPrice <= 0 || maxNotional <= 0 {
		return false
	}
	return limitPrice > maxNotional/quantityUnits
}

func priceWithinCollar(limitPrice int64, collar PriceCollar) bool {
	if collar.ReferencePrice <= 0 || collar.BandBps < 0 {
		return true
	}
	band := priceCollarBand(collar.ReferencePrice, collar.BandBps)
	lower := collar.ReferencePrice - band
	upper := int64(math.MaxInt64)
	if band <= int64(math.MaxInt64)-collar.ReferencePrice {
		upper = collar.ReferencePrice + band
	}
	return limitPrice >= lower && limitPrice <= upper
}

func priceCollarBand(referencePrice int64, bandBps int64) int64 {
	if bandBps == 0 {
		return 0
	}
	const bpsScale = int64(10000)
	if referencePrice <= int64(math.MaxInt64)/bandBps {
		return (referencePrice * bandBps) / bpsScale
	}

	hi, lo := bits.Mul64(uint64(referencePrice), uint64(bandBps))
	if hi >= uint64(bpsScale) {
		return int64(math.MaxInt64)
	}
	quotient, _ := bits.Div64(hi, lo, uint64(bpsScale))
	if quotient > uint64(math.MaxInt64) {
		return int64(math.MaxInt64)
	}
	return int64(quotient)
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

func (s *Service) removeRestingOrder(rollback *BatchRollback, book *orderBook, record *orderRecord) {
	if rollback != nil {
		rollback.trackOrder(book, record)
	}
	book.book.Remove(record.OrderID)
}

func (s *Service) loadOrder(orderID string) (*orderRecord, bool) {
	return s.orderIndex.load(orderID)
}

func (s *Service) reserveOrder(record *orderRecord) bool {
	return s.orderIndex.reserve(record)
}

func (s *Service) releaseOrder(orderID string) {
	s.orderIndex.release(orderID)
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
