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
	RunID             string
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

type BookStats struct {
	InstrumentID    string `json:"instrumentId"`
	BuyOrders       int    `json:"buyOrders"`
	SellOrders      int    `json:"sellOrders"`
	BuyPriceLevels  int    `json:"buyPriceLevels"`
	SellPriceLevels int    `json:"sellPriceLevels"`
	Checksum        string `json:"checksum"`
}

type BookScope struct {
	RunID          string
	VenueSessionID string
	InstrumentID   string
}

func (s BookScope) Key() string {
	return bookKey(s.RunID, s.VenueSessionID, s.InstrumentID)
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

// WithTerminalOrderRetentionLimit sets the terminal record limit per
// run/venue-session/instrument lane; total retention scales with lane count.
func WithTerminalOrderRetentionLimit(limit int) Option {
	return func(s *Service) {
		if limit >= 0 {
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
	return withCommandOutcomeEventID(s.submitOrder(cmd, nil), cmd.CommandID)
}

func (s *Service) SubmitOrderInBatch(rollback *BatchRollback, cmd domain.SubmitOrder) domain.SubmitOrderResult {
	return withCommandOutcomeEventID(s.submitOrder(cmd, rollback), cmd.CommandID)
}

func (s *Service) submitOrder(cmd domain.SubmitOrder, rollback *BatchRollback) domain.SubmitOrderResult {
	if !validOccurredAt(cmd.OccurredAt) {
		return invalidOccurredAtResult(cmd.CommandID, cmd.OrderID)
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

	book := s.bookFor(cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID)
	book.mu.Lock()
	defer book.mu.Unlock()

	record := &orderRecord{
		OrderID:           cmd.OrderID,
		RunID:             cmd.RunID,
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
	if !s.reserveOrder(record, rollback) {
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
	return withCommandOutcomeEventID(s.cancelOrder(cmd, nil), cmd.CommandID)
}

func (s *Service) CancelOrderInBatch(rollback *BatchRollback, cmd domain.CancelOrder) domain.SubmitOrderResult {
	return withCommandOutcomeEventID(s.cancelOrder(cmd, rollback), cmd.CommandID)
}

func (s *Service) cancelOrder(cmd domain.CancelOrder, rollback *BatchRollback) domain.SubmitOrderResult {
	if !validOccurredAt(cmd.OccurredAt) {
		return invalidOccurredAtResult(cmd.CommandID, cmd.OrderID)
	}
	now := s.occurredAt(cmd.OccurredAt)
	if cmd.OrderID == "" {
		return rejectedResult("evt-reject-missing-order-id", cmd.OrderID, "VALIDATION_ERROR", "orderId is required", now)
	}

	record, ok := s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}
	if !matchesOrderContext(record, cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID, cmd.ParticipantID, cmd.AccountID) {
		return rejectedResult(orderContextEventID(cmd.CommandID, cmd.OrderID), cmd.OrderID, "ORDER_CONTEXT_MISMATCH", "routing or ownership context does not match target order", now)
	}
	if rejection := s.validateSessionForCancel(cmd.OrderID, record.VenueSessionID, now); rejection != nil {
		return *rejection
	}
	book := s.bookFor(record.RunID, record.VenueSessionID, record.InstrumentID)
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
	return withCommandOutcomeEventID(s.modifyOrder(cmd, nil), cmd.CommandID)
}

func (s *Service) ModifyOrderInBatch(rollback *BatchRollback, cmd domain.ModifyOrder) domain.SubmitOrderResult {
	return withCommandOutcomeEventID(s.modifyOrder(cmd, rollback), cmd.CommandID)
}

func withCommandOutcomeEventID(result domain.SubmitOrderResult, commandID string) domain.SubmitOrderResult {
	if commandID == "" {
		return result
	}
	// One accepted or rejected outcome belongs to each durable command. The
	// command identity stays stable on replay and distinguishes repeated
	// modifications or rejections of the same order in one projection batch.
	eventID := "evt-command-outcome-" + commandID
	if result.Accepted != nil {
		result.Accepted.EventID = eventID
	}
	if result.Rejected != nil {
		result.Rejected.EventID = eventID
	}
	return result
}

func (s *Service) modifyOrder(cmd domain.ModifyOrder, rollback *BatchRollback) domain.SubmitOrderResult {
	if !validOccurredAt(cmd.OccurredAt) {
		return invalidOccurredAtResult(cmd.CommandID, cmd.OrderID)
	}
	now := s.occurredAt(cmd.OccurredAt)
	if cmd.OrderID == "" {
		return rejectedResult("evt-reject-missing-order-id", cmd.OrderID, "VALIDATION_ERROR", "orderId is required", now)
	}

	record, ok := s.loadOrder(cmd.OrderID)
	if !ok {
		return rejectedResult("evt-reject-order-not-found", cmd.OrderID, "NOT_FOUND", "order not found", now)
	}
	if !matchesOrderContext(record, cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID, cmd.ParticipantID, cmd.AccountID) {
		return rejectedResult(orderContextEventID(cmd.CommandID, cmd.OrderID), cmd.OrderID, "ORDER_CONTEXT_MISMATCH", "routing or ownership context does not match target order", now)
	}
	if rejection := s.validateMatchingProfile(cmd.OrderID, record.InstrumentID, now); rejection != nil {
		return *rejection
	}
	if rejection := s.validateSessionForModify(cmd.OrderID, record.VenueSessionID, now); rejection != nil {
		return *rejection
	}
	book := s.bookFor(record.RunID, record.VenueSessionID, record.InstrumentID)
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

func invalidOccurredAtResult(commandID string, orderID string) domain.SubmitOrderResult {
	identity := strings.TrimSpace(commandID)
	if identity == "" {
		identity = strings.TrimSpace(orderID)
	}
	if identity == "" {
		identity = "unknown-command"
	}
	return rejectedResult(
		"evt-reject-invalid-occurred-at-"+identity,
		orderID,
		"VALIDATION_ERROR",
		"occurredAt must be RFC3339",
		time.Unix(0, 0).UTC().Format(time.RFC3339),
	)
}

func matchesOrderContext(record *orderRecord, runID string, venueSessionID string, instrumentID string, participantID string, accountID string) bool {
	if strings.TrimSpace(runID) == "" &&
		strings.TrimSpace(venueSessionID) == "" &&
		strings.TrimSpace(instrumentID) == "" &&
		strings.TrimSpace(participantID) == "" &&
		strings.TrimSpace(accountID) == "" {
		return true
	}
	return runID == record.RunID &&
		venueSessionID == record.VenueSessionID &&
		instrumentID == record.InstrumentID &&
		participantID == record.ParticipantID &&
		accountID == record.AccountID
}

func orderContextEventID(commandID string, orderID string) string {
	identity := strings.TrimSpace(commandID)
	if identity == "" {
		identity = strings.TrimSpace(orderID)
	}
	if identity == "" {
		identity = "unknown-command"
	}
	return "evt-reject-order-context-" + identity
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

// RestingOrdersInSession aggregates all run books in this session.
func (s *Service) RestingOrdersInSession(venueSessionID string, instrumentID string, side domain.Side) int {
	s.booksMu.RLock()
	books := make([]*orderBook, 0)
	for key, book := range s.books {
		scope, ok := parseBookKey(key)
		if ok && scope.VenueSessionID == venueSessionID && scope.InstrumentID == instrumentID {
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

func (s *Service) RestingOrdersInScope(scope BookScope, side domain.Side) int {
	book, ok := s.loadBook(scope.RunID, scope.VenueSessionID, scope.InstrumentID)
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
	book := s.bookFor(record.RunID, record.VenueSessionID, record.InstrumentID)
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

func (s *Service) MatchAlgorithm(instrumentID string) MatchAlgorithm {
	if algorithm, ok := s.matchingProfiles.Instruments[instrumentID]; ok && algorithm != "" {
		return algorithm
	}
	if s.matchingProfiles.DefaultAlgorithm != "" {
		return s.matchingProfiles.DefaultAlgorithm
	}
	return MatchAlgorithmFIFO
}

func (s *Service) loadBook(runID string, venueSessionID string, instrumentID string) (*orderBook, bool) {
	s.booksMu.RLock()
	existing, ok := s.books[bookKey(runID, venueSessionID, instrumentID)]
	s.booksMu.RUnlock()
	return existing, ok
}

func (s *Service) bookFor(runID string, venueSessionID string, instrumentID string) *orderBook {
	existing, ok := s.loadBook(runID, venueSessionID, instrumentID)
	if ok {
		return existing
	}

	s.booksMu.Lock()
	defer s.booksMu.Unlock()
	key := bookKey(runID, venueSessionID, instrumentID)
	if existing, ok := s.books[key]; ok {
		return existing
	}
	book := newOrderBook()
	s.books[key] = book
	return book
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

// BatchRollback journals the pre-mutation state for orders touched by a
// direct-consume batch, so a failed durable VenueEventBatch publish can undo
// live engine mutations without snapshotting an entire hot book.
type BatchRollback struct {
	service           *Service
	instruments       map[string]*instrumentRollback
	records           map[string]*orderRollback
	terminalMutations []terminalRetentionMutation
	closed            bool
	reservedEvictions map[string]struct{}
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

// BeginBatch captures each distinct run/venue-session/instrument book's sequence
// watermark before processing starts. Individual order/book entries are
// journaled lazily on first mutation. Caller must retain exclusive ownership
// of every touched lane until Commit or Rollback; this journal does not fence
// competing owners of the same lane.
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
		key := scope.Key()
		if _, ok := rollback.instruments[key]; ok {
			continue
		}
		book := s.bookFor(scope.RunID, scope.VenueSessionID, scope.InstrumentID)
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
	if rb == nil || rb.closed {
		return
	}
	rb.service.terminalRetention.rollback(rb.terminalMutations)
	rb.terminalMutations = nil
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

	// Restore the global index once per ID, after per-book undo. Reuse across
	// two books in one batch must not make restoration depend on map iteration.

	for orderID, entry := range rb.records {
		var record *orderRecord
		if entry.existed {
			recordCopy := entry.record
			record = &recordCopy
		}
		rb.service.orderIndex.restoreOrDelete(orderID, entry.existed, record)
	}
	rb.releaseReservations()
	rb.closed = true
}

// Commit seals mutations after the outcome is durable. Retention, like book
// mutations, is already live so command semantics do not depend on batch cuts.
func (rb *BatchRollback) Commit() {
	if rb == nil || rb.closed {
		return
	}
	rb.closed = true
	rb.releaseReservations()
	rb.terminalMutations = nil
}

func (rb *BatchRollback) releaseReservations() {
	for orderID := range rb.reservedEvictions {
		rb.service.orderIndex.releaseReservation(orderID, rb)
	}
	rb.reservedEvictions = nil
}

func (rb *BatchRollback) trackCreatedOrder(book *orderBook, record *orderRecord) {
	if rb == nil || record == nil {
		return
	}
	if _, ok := rb.records[record.OrderID]; !ok {
		rb.records[record.OrderID] = &orderRollback{}
	}
	snap := rb.instrument(bookKey(record.RunID, record.VenueSessionID, record.InstrumentID), book)
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
	snap := rb.instrument(bookKey(record.RunID, record.VenueSessionID, record.InstrumentID), book)
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
	if _, ok := rb.records[orderID]; !ok {
		rb.records[orderID] = &orderRollback{existed: entry.existed, record: entry.record}
	}
	snap.orders[orderID] = entry
}

func (rb *BatchRollback) trackOrderRecord(record *orderRecord) {
	if rb == nil || record == nil {
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
	mutation := s.terminalRetention.track(record, func(evictID string) {
		evictRecord, ok := s.loadOrder(evictID)
		if !ok {
			return
		}
		if evictRecord.Status == domain.OrderStatusFilled || evictRecord.Status == domain.OrderStatusCancelled {
			rollback.trackOrderRecord(evictRecord)
			if rollback != nil {
				if rollback.reservedEvictions == nil {
					rollback.reservedEvictions = make(map[string]struct{})
				}
				rollback.reservedEvictions[evictID] = struct{}{}
				s.orderIndex.releaseInBatch(evictRecord, rollback)
			} else {
				s.orderIndex.release(evictID)
			}
		}
	})
	if rollback != nil && mutation != nil {
		rollback.terminalMutations = append(rollback.terminalMutations, *mutation)
	}
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

func (s *Service) reserveOrder(record *orderRecord, owner *BatchRollback) bool {
	return s.orderIndex.reserveInBatch(record, owner)
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
