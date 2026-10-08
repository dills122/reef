package app

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"

	hotbook "github.com/dills122/reef/services/matching-engine/internal/book"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

// CalcifySourceProfile bounds retained identities without a second ID set or scans.
// Explicit configuration opts into source acceptance, not financial authority.
type CalcifySourceProfile struct {
	Schema           string   `json:"schema"`
	RunIDs           []string `json:"runIds"`
	VenueSessionID   string   `json:"venueSessionId"`
	InstrumentID     string   `json:"instrumentId"`
	Currency         string   `json:"currency"`
	MaxOrderIDs      int      `json:"maxOrderIds"`
	MaxQuantityUnits int64    `json:"maxQuantityUnits"`
	MaxLimitPrice    int64    `json:"maxLimitPrice"`
}

const calcifySourceIdentifierBytes = 128

func CalcifySourceProfileFromJSON(raw, retention string) (Option, error) {
	if raw == "" {
		return func(*Service) {}, nil
	}
	if retention != "0" {
		return nil, fmt.Errorf("Calcify source profile requires explicit terminal retention 0")
	}
	if len(raw) > 4096 {
		return nil, fmt.Errorf("Calcify source profile exceeds 4096 bytes")
	}
	var p CalcifySourceProfile
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&p); err != nil {
		return nil, fmt.Errorf("invalid Calcify source profile: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		return nil, fmt.Errorf("Calcify source profile requires one JSON object")
	}
	if p.Schema != "calcify-finite-source-v1" || len(p.RunIDs) < 1 || len(p.RunIDs) > 2 ||
		!sourceIdentifier(p.VenueSessionID) || !sourceIdentifier(p.InstrumentID) || !validQuoteCurrency(p.Currency) ||
		p.MaxOrderIDs < 1 || p.MaxOrderIDs > 10000 || p.MaxQuantityUnits < 1 || p.MaxQuantityUnits > 1000000 ||
		p.MaxLimitPrice < 1 || p.MaxLimitPrice > 1000000000000 {
		return nil, fmt.Errorf("invalid Calcify source scope or finite limits")
	}
	sort.Strings(p.RunIDs)
	for i, run := range p.RunIDs {
		if !sourceIdentifier(run) || (i > 0 && run == p.RunIDs[i-1]) {
			return nil, fmt.Errorf("invalid Calcify source run IDs")
		}
	}
	return func(s *Service) {
		copy := p
		copy.RunIDs = append([]string(nil), p.RunIDs...)
		s.calcifySourceProfile = &copy
	}, nil
}

func sourceIdentifier(value string) bool {
	return value != "" && len(value) <= calcifySourceIdentifierBytes
}

// ValidateCalcifySourceProfile runs before listeners and stream recovery start.
func (s *Service) ValidateCalcifySourceProfile() error {
	p := s.calcifySourceProfile
	if p != nil && (s.terminalRetention.limit != 0 || s.instrumentQuote(p.InstrumentID) != p.Currency) {
		return fmt.Errorf("Calcify source profile requires retention0 and matching instrument quote")
	}
	return nil
}

func (s *Service) calcifySourceProfileHash() string {
	if s.calcifySourceProfile == nil {
		return ""
	}
	raw, _ := json.Marshal(s.calcifySourceProfile)
	digest := sha256.Sum256(raw)
	return hex.EncodeToString(digest[:])
}

func (s *Service) calcifySourceScope(run, session, instrument string) bool {
	p := s.calcifySourceProfile
	if p == nil {
		return true
	}
	if session != p.VenueSessionID || instrument != p.InstrumentID {
		return false
	}
	for _, allowed := range p.RunIDs {
		if run == allowed {
			return true
		}
	}
	return false
}

func (s *Service) calcifySourceOrder(run, session, instrument, id string, fields ...string) bool {
	p := s.calcifySourceProfile
	if p == nil {
		return true
	}
	if s.ValidateCalcifySourceProfile() != nil || !s.calcifySourceScope(run, session, instrument) || len(id) > calcifySourceIdentifierBytes || !strings.HasPrefix(id, "p0-") {
		return false
	}
	ordinal, err := strconv.Atoi(strings.TrimPrefix(id, "p0-"))
	if err != nil || ordinal < 1 || ordinal > p.MaxOrderIDs || id != "p0-"+strconv.Itoa(ordinal) {
		return false
	}
	for _, field := range fields {
		if len(field) > calcifySourceIdentifierBytes {
			return false
		}
	}
	return true
}

func (s *Service) calcifySourceAmounts(quantity, price string) bool {
	p := s.calcifySourceProfile
	if p == nil {
		return true
	}
	q, qok := parsePositiveInt(quantity)
	v, vok := parsePositiveInt(price)
	return qok && vok && q <= p.MaxQuantityUnits && v <= p.MaxLimitPrice
}

func (s *Service) calcifySourceSubmitOrder(cmd domain.SubmitOrder) bool {
	return s.calcifySourceCommand(cmd.CommandID, cmd.OccurredAt) && s.calcifySourceOrder(cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID, cmd.OrderID,
		cmd.CommandID, cmd.TraceID, cmd.CausationID, cmd.CorrelationID, cmd.ActorID, cmd.ParticipantID, cmd.AccountID,
		cmd.OccurredAt, cmd.ClientOrderID, string(cmd.Side), cmd.OrderType, cmd.QuantityUnits, cmd.LimitPrice, cmd.Currency, cmd.TimeInForce) &&
		s.calcifySourceAmounts(cmd.QuantityUnits, cmd.LimitPrice)
}

func (s *Service) calcifySourceCancelOrder(cmd domain.CancelOrder) bool {
	return s.calcifySourceCommand(cmd.CommandID, cmd.OccurredAt) && s.calcifySourceOrder(cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID, cmd.OrderID,
		cmd.CommandID, cmd.TraceID, cmd.CausationID, cmd.CorrelationID, cmd.ActorID, cmd.ParticipantID, cmd.AccountID, cmd.OccurredAt, cmd.Reason)
}

func (s *Service) calcifySourceModifyOrder(cmd domain.ModifyOrder) bool {
	return s.calcifySourceCommand(cmd.CommandID, cmd.OccurredAt) && s.calcifySourceOrder(cmd.RunID, cmd.VenueSessionID, cmd.InstrumentID, cmd.OrderID,
		cmd.CommandID, cmd.TraceID, cmd.CausationID, cmd.CorrelationID, cmd.ActorID, cmd.ParticipantID, cmd.AccountID,
		cmd.OccurredAt, cmd.QuantityUnits, cmd.LimitPrice) && s.calcifySourceAmounts(cmd.QuantityUnits, cmd.LimitPrice)
}

func (s *Service) calcifySourceCommand(command, at string) bool {
	return s.calcifySourceProfile == nil || (sourceIdentifier(command) && sourceIdentifier(at))
}

func sourceProfileRejection(command, order, at string) domain.SubmitOrderResult {
	return withCommandOutcomeEventID(rejectedResult("evt-source-profile", order, "CALCIFY_SOURCE_PROFILE_REJECTED", "command exceeds finite Calcify source profile", at), command)
}

func (s *Service) validCalcifySourceSnapshot(snapshot Snapshot) bool {
	p := s.calcifySourceProfile
	if snapshot.Metadata.CalcifySourceProfileHash != s.calcifySourceProfileHash() || s.ValidateCalcifySourceProfile() != nil {
		return false
	}
	if p == nil {
		return true
	}
	// Legacy snapshots cannot prove profile lifetime or complete terminal retention.
	if snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v4" || snapshot.Metadata.TerminalRetentionLimit != 0 ||
		len(snapshot.Books) > len(p.RunIDs) || len(snapshot.Orders) > len(p.RunIDs)*p.MaxOrderIDs {
		return false
	}
	for key, book := range snapshot.Books {
		scope, ok := parseBookKey(key)
		if !ok || !s.calcifySourceScope(scope.RunID, scope.VenueSessionID, scope.InstrumentID) || len(book.Buys)+len(book.Sells) > p.MaxOrderIDs {
			return false
		}
		for _, side := range [][]hotbook.SnapshotOrder{book.Buys, book.Sells} {
			for _, order := range side {
				if !s.calcifySourceOrder(scope.RunID, scope.VenueSessionID, scope.InstrumentID, order.OrderID) || order.LimitPrice < 1 || order.LimitPrice > p.MaxLimitPrice {
					return false
				}
			}
		}
	}
	for _, order := range snapshot.Orders {
		if !s.calcifySourceOrder(order.RunID, order.VenueSessionID, order.InstrumentID, order.OrderID, order.ParticipantID, order.AccountID, order.LastUpdatedAt) ||
			order.OriginalQuantity < 1 || order.OriginalQuantity > p.MaxQuantityUnits || order.RemainingQuantity < 0 || order.RemainingQuantity > order.OriginalQuantity ||
			order.LimitPrice < 1 || order.LimitPrice > p.MaxLimitPrice || order.Currency != p.Currency {
			return false
		}
	}
	return true
}
