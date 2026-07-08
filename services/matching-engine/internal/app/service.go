package app

import (
	"crypto/sha256"
	"encoding/hex"
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
	ordersMu          sync.RWMutex
	orders            map[string]*orderRecord
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
		books:  make(map[string]*orderBook),
		orders: make(map[string]*orderRecord),
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
	if accepted, rejection := s.applySelfTradePrevention(book, record, now); !accepted {
		s.releaseOrder(record.OrderID)
		return rejection
	}
	incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)

	s.match(book, incoming, record.Side, &result, now)
	if record.RemainingQuantity > 0 {
		book.book.Add(record.Side, incoming)
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
		if accepted, rejection := s.applySelfTradePrevention(book, &proposed, now); !accepted {
			return rejection
		}
	}
	if resetPriority {
		s.removeRestingOrder(book, record)
	}

	record.OriginalQuantity = quantityUnits
	record.RemainingQuantity = quantityUnits - alreadyFilled
	record.LimitPrice = limitPrice
	record.LastUpdatedAt = now
	s.refreshOrderStatus(record)

	result := acceptedResult("modified", cmd.OrderID, now)

	if resetPriority && record.RemainingQuantity > 0 {
		incoming := book.book.NewRestingOrder(cmd.OrderID, record.LimitPrice)
		s.match(book, incoming, record.Side, &result, now)
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
	snapshot := Snapshot{
		Books: make(map[string]hotbook.Snapshot),
	}

	s.booksMu.RLock()
	bookIDs := make([]string, 0, len(s.books))
	for instrumentID := range s.books {
		bookIDs = append(bookIDs, instrumentID)
	}
	sort.Strings(bookIDs)
	for _, instrumentID := range bookIDs {
		book := s.books[instrumentID]
		book.mu.Lock()
		snapshot.Books[instrumentID] = book.book.Snapshot()
		book.mu.Unlock()
	}
	s.booksMu.RUnlock()

	s.ordersMu.RLock()
	snapshot.Orders = make([]SnapshotOrderRecord, 0, len(s.orders))
	for _, record := range s.orders {
		snapshot.Orders = append(snapshot.Orders, SnapshotOrderRecord{
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
		})
	}
	s.ordersMu.RUnlock()
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
	for _, key := range bookKeys {
		book := books[key]
		book.mu.Lock()
		snapshot := book.book.Snapshot()
		stats.BuyOrders += book.book.Len(domain.SideBuy)
		stats.SellOrders += book.book.Len(domain.SideSell)
		stats.BuyPriceLevels += book.book.LevelCount(domain.SideBuy)
		stats.SellPriceLevels += book.book.LevelCount(domain.SideSell)
		book.mu.Unlock()
		checksumInput.WriteString(key)
		checksumInput.WriteByte(':')
		checksumInput.WriteString(snapshot.Checksum)
		checksumInput.WriteByte(';')
	}
	if len(bookKeys) == 1 {
		parts := strings.SplitN(checksumInput.String(), ":", 2)
		stats.Checksum = strings.TrimSuffix(parts[1], ";")
		return stats
	}
	sum := sha256.Sum256([]byte(checksumInput.String()))
	stats.Checksum = hex.EncodeToString(sum[:])
	return stats
}

func (s *Service) SnapshotForInstrument(instrumentID string) (Snapshot, bool) {
	s.booksMu.RLock()
	books := make(map[string]*orderBook, len(s.books))
	bookKeys := make([]string, 0)
	for key, book := range s.books {
		if bookKeyMatchesInstrument(key, instrumentID) {
			books[key] = book
			bookKeys = append(bookKeys, key)
		}
	}
	s.booksMu.RUnlock()
	if len(bookKeys) == 0 {
		return Snapshot{}, false
	}
	sort.Strings(bookKeys)

	snapshot := Snapshot{
		Books: make(map[string]hotbook.Snapshot),
	}
	for _, key := range bookKeys {
		book := books[key]
		book.mu.Lock()
		snapshot.Books[key] = book.book.Snapshot()
		book.mu.Unlock()
	}

	s.ordersMu.RLock()
	for _, record := range s.orders {
		if record.InstrumentID != instrumentID {
			continue
		}
		snapshot.Orders = append(snapshot.Orders, SnapshotOrderRecord{
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
		})
	}
	s.ordersMu.RUnlock()
	sort.Slice(snapshot.Orders, func(i, j int) bool {
		return snapshot.Orders[i].OrderID < snapshot.Orders[j].OrderID
	})
	snapshot.Metadata = SnapshotMetadata{
		SnapshotVersion: "matching-service-snapshot-v1",
		EngineVersion:   "matching-engine-app-v1",
		BookCount:       len(bookKeys),
		OrderCount:      len(snapshot.Orders),
		BookKeys:        bookKeys,
	}
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	return snapshot, true
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
		service.orders[record.OrderID] = record
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

func (s *Service) applySelfTradePrevention(book *orderBook, incoming *orderRecord, occurredAt string) (bool, domain.SubmitOrderResult) {
	matches := s.reachableSelfTradeRestingRecords(book, incoming)
	if len(matches) == 0 {
		return true, domain.SubmitOrderResult{}
	}
	if s.selfTradePreventionMode() == SelfTradePreventionCancelOldest {
		for _, restingRecord := range matches {
			s.removeRestingOrder(book, restingRecord)
			restingRecord.RemainingQuantity = 0
			restingRecord.Status = domain.OrderStatusCancelled
			restingRecord.LastUpdatedAt = occurredAt
			s.trackTerminalOrder(restingRecord)
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
	opposite := domain.SideSell
	if incoming.Side == domain.SideSell {
		opposite = domain.SideBuy
	}
	snapshot := book.book.Snapshot()
	orders := snapshot.Sells
	if opposite == domain.SideBuy {
		orders = snapshot.Buys
	}
	matches := make([]*orderRecord, 0)
	for _, resting := range orders {
		if remaining <= 0 {
			return matches
		}
		restingRecord, ok := s.loadOrder(resting.OrderID)
		if !ok || restingRecord.OrderID == incoming.OrderID {
			continue
		}
		if incoming.Side == domain.SideBuy && incoming.LimitPrice < restingRecord.LimitPrice {
			return matches
		}
		if incoming.Side == domain.SideSell && incoming.LimitPrice > restingRecord.LimitPrice {
			return matches
		}
		if sameSelfTradeIdentity(incoming, restingRecord) {
			matches = append(matches, restingRecord)
			continue
		}
		remaining -= restingRecord.RemainingQuantity
	}
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
	service           *Service
	instruments       map[string]*instrumentRollback
	terminalRetention terminalOrderRetentionSnapshot
}

type instrumentRollback struct {
	book     *orderBook
	snapshot hotbook.Snapshot
	existing map[string]orderRecord
}

// BeginBatch snapshots the order book and the order records resting in it
// for each distinct venue-session/instrument scope.
func (s *Service) BeginBatch(scopes []BookScope) *BatchRollback {
	rollback := &BatchRollback{
		service:           s,
		instruments:       make(map[string]*instrumentRollback),
		terminalRetention: s.terminalRetention.snapshot(),
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
		rollback.instruments[key] = &instrumentRollback{
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
	rb.service.terminalRetention.restore(rb.terminalRetention)
	for key, snap := range rb.instruments {
		snap.book.mu.Lock()
		if restored, ok := hotbook.Restore(snap.snapshot); ok {
			snap.book.book = restored
		}
		rb.service.ordersMu.Lock()
		for orderID, record := range snap.existing {
			recordCopy := record
			rb.service.orders[orderID] = &recordCopy
		}
		for _, orderID := range touchedOrderIDs[key] {
			if _, existed := snap.existing[orderID]; !existed {
				delete(rb.service.orders, orderID)
			}
		}
		rb.service.ordersMu.Unlock()
		snap.book.mu.Unlock()
	}
}

// match executes incoming against the resting book on the opposite side.
// Buy and sell only differ in which side of the book they cross and the
// direction of the price-improvement comparison; encoding both sides in one
// function avoids the two implementations drifting when one is patched and
// the other isn't.
func (s *Service) match(book *orderBook, incoming restingOrder, side domain.Side, result *domain.SubmitOrderResult, occurredAt string) {
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

		matchedUnits := minInt64(incomingRecord.RemainingQuantity, restingRecord.RemainingQuantity)
		executionPrice := restingRecord.LimitPrice
		if side == domain.SideBuy {
			s.appendMatch(result, incomingRecord, restingRecord, matchedUnits, executionPrice, occurredAt)
		} else {
			s.appendMatch(result, restingRecord, incomingRecord, matchedUnits, executionPrice, occurredAt)
		}

		incomingRecord.RemainingQuantity -= matchedUnits
		restingRecord.RemainingQuantity -= matchedUnits
		incomingRecord.LastUpdatedAt = occurredAt
		restingRecord.LastUpdatedAt = occurredAt
		s.refreshOrderStatus(incomingRecord)
		s.refreshOrderStatus(restingRecord)
		if restingRecord.RemainingQuantity == 0 {
			book.book.PopBest(opposite)
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
	s.terminalRetention.track(record, func(evictID string) {
		evictRecord, ok := s.loadOrder(evictID)
		if !ok {
			return
		}
		if evictRecord.Status == domain.OrderStatusFilled || evictRecord.Status == domain.OrderStatusCancelled {
			s.ordersMu.Lock()
			delete(s.orders, evictID)
			s.ordersMu.Unlock()
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
	band := (collar.ReferencePrice * collar.BandBps) / 10000
	return limitPrice >= collar.ReferencePrice-band && limitPrice <= collar.ReferencePrice+band
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
	s.ordersMu.RLock()
	defer s.ordersMu.RUnlock()
	record, ok := s.orders[orderID]
	return record, ok
}

func (s *Service) reserveOrder(record *orderRecord) bool {
	s.ordersMu.Lock()
	defer s.ordersMu.Unlock()
	if _, exists := s.orders[record.OrderID]; exists {
		return false
	}
	s.orders[record.OrderID] = record
	return true
}

func (s *Service) releaseOrder(orderID string) {
	s.ordersMu.Lock()
	defer s.ordersMu.Unlock()
	delete(s.orders, orderID)
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
