package app

import (
	"crypto/sha256"
	"encoding/hex"
	hotbook "github.com/dills122/reef/services/matching-engine/internal/book"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
	"sort"
	"strconv"
	"strings"
)

type Snapshot struct {
	Metadata SnapshotMetadata            `json:"metadata"`
	Books    map[string]hotbook.Snapshot `json:"books"`
	Orders   []SnapshotOrderRecord       `json:"orders"`
	Checksum string                      `json:"checksum"`
}

const terminalRetentionPolicy = "book-scoped-v1"

type SnapshotMetadata struct {
	SnapshotVersion         string   `json:"snapshotVersion"`
	EngineVersion           string   `json:"engineVersion"`
	BookCount               int      `json:"bookCount"`
	OrderCount              int      `json:"orderCount"`
	BookKeys                []string `json:"bookKeys"`
	TerminalRetentionPolicy string   `json:"terminalRetentionPolicy,omitempty"`
	TerminalRetentionLimit  int      `json:"terminalRetentionLimit,omitempty"`
}

type SnapshotOrderRecord struct {
	OrderID           string             `json:"orderId"`
	RunID             string             `json:"runId"`
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

func (s *Service) Snapshot() Snapshot {
	bookIDs, books, unlock := s.lockSnapshotBooks(nil)
	defer unlock()
	return s.buildSnapshot(bookIDs, books, nil)
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

func (s *Service) SnapshotForScope(scope BookScope) (Snapshot, bool) {
	keys, books, unlock := s.lockSnapshotBooks(func(key string) bool { return key == scope.Key() })
	defer unlock()
	if len(keys) == 0 {
		return Snapshot{}, false
	}
	return s.buildSnapshot(keys, books, func(record *orderRecord) bool {
		return bookKey(record.RunID, record.VenueSessionID, record.InstrumentID) == scope.Key()
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
		if snapshot.Orders[i].OrderID != snapshot.Orders[j].OrderID {
			return snapshot.Orders[i].OrderID < snapshot.Orders[j].OrderID
		}
		return snapshot.Orders[i].RunID < snapshot.Orders[j].RunID
	})
	snapshot.Metadata = SnapshotMetadata{
		SnapshotVersion:         "matching-service-snapshot-v4",
		TerminalRetentionPolicy: terminalRetentionPolicy,
		TerminalRetentionLimit:  s.terminalRetention.limit,
		EngineVersion:           "matching-engine-app-v1",
		BookCount:               len(snapshot.Books),
		OrderCount:              len(snapshot.Orders),
		BookKeys:                bookIDs,
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
		RunID:             record.RunID,
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

func Restore(snapshot Snapshot, options ...Option) (*Service, bool) {
	if !validSnapshotMetadata(snapshot) {
		return nil, false
	}
	if snapshot.Checksum != "" && snapshot.Checksum != serviceSnapshotChecksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	service := NewService(options...)
	if snapshot.Metadata.SnapshotVersion == "matching-service-snapshot-v4" {
		if snapshot.Metadata.TerminalRetentionLimit != service.terminalRetention.limit {
			return nil, false
		}
	} else if service.terminalRetention.limit > 0 {
		// Old snapshots cannot prove that the global queue never evicted another
		// lane's terminal outcomes. Rebuild from canonical commands to enable v4.
		return nil, false
	}
	var ok bool
	snapshot, ok = normalizeSnapshotScope(snapshot)
	if !ok {
		return nil, false
	}
	snapshot.Metadata.SnapshotVersion = "matching-service-snapshot-v4"
	snapshot.Metadata.TerminalRetentionPolicy = terminalRetentionPolicy
	snapshot.Metadata.TerminalRetentionLimit = service.terminalRetention.limit
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	for instrumentID, bookSnapshot := range snapshot.Books {
		restored, ok := hotbook.Restore(bookSnapshot)
		if !ok {
			return nil, false
		}
		scope, ok := parseBookKey(instrumentID)
		if !ok {
			return nil, false
		}
		service.books[instrumentID] = &orderBook{RunID: scope.RunID, book: restored}
	}
	// Order IDs are only unique within one run, so the dedup/scope checks
	// below key on (RunID, OrderID), not OrderID alone.
	seenOrderIDs := make(map[string]bool, len(snapshot.Orders))
	terminalRecords := make([]*orderRecord, 0)
	restingCurrencies := make(map[string]string)
	for _, order := range snapshot.Orders {
		if order.RemainingQuantity > 0 {
			lane := bookKey(order.RunID, order.VenueSessionID, order.InstrumentID)
			if quote, exists := restingCurrencies[lane]; exists && quote != order.Currency {
				return nil, false
			}
			if !validQuoteCurrency(order.Currency) {
				return nil, false
			}
			restingCurrencies[lane] = order.Currency
		}
		key := orderIndexKey(order.RunID, order.OrderID)
		if order.OrderID == "" || seenOrderIDs[key] {
			return nil, false
		}
		seenOrderIDs[key] = true
		record := &orderRecord{
			OrderID:           order.OrderID,
			RunID:             order.RunID,
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
		if record.Status == domain.OrderStatusFilled || record.Status == domain.OrderStatusCancelled {
			terminalRecords = append(terminalRecords, record)
		}
	}
	if !validSnapshotOrderScopes(snapshot) {
		return nil, false
	}
	for _, record := range terminalRecords {
		service.trackTerminalOrder(nil, record)
	}
	if service.Snapshot().Checksum != serviceSnapshotChecksum(snapshot.withoutChecksum()) {
		return nil, false
	}
	return service, true
}

func (s Snapshot) withoutChecksum() Snapshot {
	s.Checksum = ""
	return s
}

func validSnapshotMetadata(snapshot Snapshot) bool {
	if snapshot.Metadata.SnapshotVersion == "matching-service-snapshot-v4" {
		if snapshot.Metadata.TerminalRetentionPolicy != terminalRetentionPolicy || snapshot.Metadata.TerminalRetentionLimit < 0 {
			return false
		}
	} else if snapshot.Metadata.TerminalRetentionPolicy != "" || snapshot.Metadata.TerminalRetentionLimit != 0 {
		return false
	}
	if snapshot.Metadata.SnapshotVersion == "" && snapshot.Metadata.EngineVersion == "" {
		return true
	}
	if (snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v4" && snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v3" && snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v2") || snapshot.Metadata.EngineVersion == "" {
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
	if snapshot.Metadata.SnapshotVersion == "matching-service-snapshot-v4" {
		builder.WriteString("retention:")
		builder.WriteString(snapshot.Metadata.TerminalRetentionPolicy)
		builder.WriteByte(':')
		builder.WriteString(strconv.Itoa(snapshot.Metadata.TerminalRetentionLimit))
		builder.WriteByte(';')
	}
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
	// Tie-break on RunID too: order IDs are only unique within one run, and
	// this must stay deterministic even when two runs reuse an ID. This
	// tie-break only ever activates on that collision - it reproduces the
	// exact same byte order (and checksum) as before for any snapshot where
	// every OrderID was already unique, which was true of every snapshot
	// ever produced by the prior, globally-unique order index.
	sort.Slice(orders, func(i, j int) bool {
		if orders[i].OrderID != orders[j].OrderID {
			return orders[i].OrderID < orders[j].OrderID
		}
		return orders[i].RunID < orders[j].RunID
	})
	for _, order := range orders {
		builder.WriteString("order:")
		builder.WriteString(order.OrderID)
		builder.WriteByte(':')
		builder.WriteString(order.RunID)
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

// Shared-run legacy books cannot safely become run-local recovery state. Use
// command-log replay instead. Legacy snapshots without run IDs can migrate.
func normalizeSnapshotScope(snapshot Snapshot) (Snapshot, bool) {
	if snapshot.Metadata.SnapshotVersion == "matching-service-snapshot-v3" || snapshot.Metadata.SnapshotVersion == "matching-service-snapshot-v4" {
		for key := range snapshot.Books {
			if _, ok := parseBookKey(key); !ok {
				return Snapshot{}, false
			}
		}
		return snapshot, true
	}
	for _, order := range snapshot.Orders {
		if order.RunID != "" {
			return Snapshot{}, false
		}
	}
	books := make(map[string]hotbook.Snapshot, len(snapshot.Books))
	keys := make([]string, 0, len(snapshot.Books))
	for key, book := range snapshot.Books {
		session, instrument := "", key
		if sep := strings.LastIndexByte(key, '|'); sep >= 0 {
			session, instrument = key[:sep], key[sep+1:]
		}
		next := bookKey("", session, instrument)
		if _, exists := books[next]; exists {
			return Snapshot{}, false
		}
		books[next] = book
		keys = append(keys, next)
	}
	sort.Strings(keys)
	snapshot.Books = books
	snapshot.Metadata = SnapshotMetadata{SnapshotVersion: "matching-service-snapshot-v3", EngineVersion: "matching-engine-app-v1", BookCount: len(books), OrderCount: len(snapshot.Orders), BookKeys: keys}
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	return snapshot, true
}

// validSnapshotOrderScopes keys its lookup maps by orderIndexKey(RunID,
// OrderID), not OrderID alone: order IDs are only unique within one run,
// and this runs on snapshots that may legitimately contain the same order
// ID reused by two different runs.
func validSnapshotOrderScopes(snapshot Snapshot) bool {
	orders := make(map[string]SnapshotOrderRecord, len(snapshot.Orders))
	for _, order := range snapshot.Orders {
		if _, ok := snapshot.Books[bookKey(order.RunID, order.VenueSessionID, order.InstrumentID)]; !ok {
			return false
		}
		key := orderIndexKey(order.RunID, order.OrderID)
		if _, exists := orders[key]; exists {
			return false
		}
		orders[key] = order
	}
	resting := make(map[string]bool)
	for key, book := range snapshot.Books {
		scope, ok := parseBookKey(key)
		if !ok {
			return false
		}
		for _, entry := range append(append([]hotbook.SnapshotOrder(nil), book.Buys...), book.Sells...) {
			restingKey := orderIndexKey(scope.RunID, entry.OrderID)
			order, ok := orders[restingKey]
			if !ok || resting[restingKey] || bookKey(order.RunID, order.VenueSessionID, order.InstrumentID) != key || order.Side != entry.Side || order.LimitPrice != entry.LimitPrice || order.RemainingQuantity <= 0 || (order.Status != domain.OrderStatusAccepted && order.Status != domain.OrderStatusPartiallyFilled) {
				return false
			}
			resting[restingKey] = true
		}
	}
	for _, order := range snapshot.Orders {
		if (order.Status == domain.OrderStatusAccepted || order.Status == domain.OrderStatusPartiallyFilled) && !resting[orderIndexKey(order.RunID, order.OrderID)] {
			return false
		}
	}
	return true
}
