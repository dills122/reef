package streamdirect

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"sync/atomic"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type CommandDelivery interface {
	Subject() string
	Data() []byte
	StreamSequence() uint64
	DeliveredCount() uint64
	Ack() error
	Nak() error
	Term() error
}

type CommandSource interface {
	Fetch(ctx context.Context, batchSize int, timeout time.Duration) ([]CommandDelivery, error)
}

type BatchCommandAcker interface {
	AckBatch(deliveries []CommandDelivery) (int, error)
}

type EventBatchPublisher interface {
	PublishEventBatch(ctx context.Context, batch VenueEventBatch) error
}

type Processor struct {
	service   *app.Service
	source    CommandSource
	publisher EventBatchPublisher
	config    ProcessorConfig
	stats     *Stats
}

type ProcessorConfig struct {
	ShardID         string
	Partition       int
	BatchSize       int
	FetchTimeout    time.Duration
	PollInterval    time.Duration
	CommandStream   string
	EventStreamName string
}

type Stats struct {
	Fetched       atomic.Uint64
	Processed     atomic.Uint64
	Published     atomic.Uint64
	Acked         atomic.Uint64
	Nacked        atomic.Uint64
	Termed        atomic.Uint64
	Failed        atomic.Uint64
	Unsupported   atomic.Uint64
	LastError     atomic.Value
	LastFetchedAt atomic.Value
	LastAckedAt   atomic.Value
}

type Snapshot struct {
	ShardID       string `json:"shardId"`
	Partition     int    `json:"partition"`
	Fetched       uint64 `json:"fetched"`
	Processed     uint64 `json:"processed"`
	Published     uint64 `json:"published"`
	Acked         uint64 `json:"acked"`
	Nacked        uint64 `json:"nacked"`
	Termed        uint64 `json:"termed"`
	Failed        uint64 `json:"failed"`
	Unsupported   uint64 `json:"unsupported"`
	LastError     string `json:"lastError"`
	LastFetchedAt string `json:"lastFetchedAt"`
	LastAckedAt   string `json:"lastAckedAt"`
}

type VenueEventBatch struct {
	BatchID         string               `json:"batchId"`
	ShardID         string               `json:"shardId"`
	Partition       int                  `json:"partition"`
	CommandStream   string               `json:"commandStream,omitempty"`
	EventStream     string               `json:"eventStream,omitempty"`
	FirstSequence   uint64               `json:"firstSequence"`
	LastSequence    uint64               `json:"lastSequence"`
	CommandCount    int                  `json:"commandCount"`
	CreatedAt       string               `json:"createdAt"`
	PayloadChecksum string               `json:"payloadChecksum"`
	Outcomes        []CommandOutcomeFact `json:"outcomes"`
}

type CommandOutcomeFact struct {
	CommandID      string                   `json:"commandId"`
	CommandType    string                   `json:"commandType"`
	StreamSequence uint64                   `json:"streamSequence"`
	DeliveredCount uint64                   `json:"deliveredCount"`
	PayloadHash    string                   `json:"payloadHash"`
	InstrumentID   string                   `json:"instrumentId"`
	OrderID        string                   `json:"orderId"`
	Status         string                   `json:"status"`
	Result         domain.SubmitOrderResult `json:"result"`
}

func NewProcessor(service *app.Service, source CommandSource, publisher EventBatchPublisher, config ProcessorConfig) *Processor {
	if config.BatchSize <= 0 {
		config.BatchSize = 100
	}
	if config.FetchTimeout <= 0 {
		config.FetchTimeout = 200 * time.Millisecond
	}
	if config.PollInterval <= 0 {
		config.PollInterval = 25 * time.Millisecond
	}
	if strings.TrimSpace(config.ShardID) == "" {
		config.ShardID = "engine-0"
	}
	stats := &Stats{}
	stats.LastError.Store("")
	stats.LastFetchedAt.Store("")
	stats.LastAckedAt.Store("")
	return &Processor{
		service:   service,
		source:    source,
		publisher: publisher,
		config:    config,
		stats:     stats,
	}
}

func (p *Processor) Stats() Snapshot {
	return Snapshot{
		ShardID:       p.config.ShardID,
		Partition:     p.config.Partition,
		Fetched:       p.stats.Fetched.Load(),
		Processed:     p.stats.Processed.Load(),
		Published:     p.stats.Published.Load(),
		Acked:         p.stats.Acked.Load(),
		Nacked:        p.stats.Nacked.Load(),
		Termed:        p.stats.Termed.Load(),
		Failed:        p.stats.Failed.Load(),
		Unsupported:   p.stats.Unsupported.Load(),
		LastError:     p.stats.LastError.Load().(string),
		LastFetchedAt: p.stats.LastFetchedAt.Load().(string),
		LastAckedAt:   p.stats.LastAckedAt.Load().(string),
	}
}

func (p *Processor) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		processed, err := p.ProcessOnce(ctx)
		if err != nil {
			p.recordFailure(err)
		}
		if processed == 0 {
			timer := time.NewTimer(p.config.PollInterval)
			select {
			case <-ctx.Done():
				timer.Stop()
				return ctx.Err()
			case <-timer.C:
			}
		}
	}
}

func (p *Processor) ProcessOnce(ctx context.Context) (int, error) {
	deliveries, err := p.source.Fetch(ctx, p.config.BatchSize, p.config.FetchTimeout)
	if err != nil {
		return 0, err
	}
	if len(deliveries) == 0 {
		return 0, nil
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	p.stats.Fetched.Add(uint64(len(deliveries)))
	p.stats.LastFetchedAt.Store(now)

	batch, ackable, rollback, touchedOrderIDs, err := p.buildBatch(deliveries, now)
	if err != nil {
		if rollback != nil {
			rollback.Rollback(touchedOrderIDs)
		}
		p.nakAll(ackable)
		return len(deliveries), err
	}
	if err := p.publisher.PublishEventBatch(ctx, batch); err != nil {
		// The batch was never durably published: undo the engine-side
		// mutations (order reservations, matches, cancels/modifies) that
		// processing this batch made, so the book does not diverge from the
		// canonical record and a redelivered retry reprocesses cleanly
		// instead of being rejected as a duplicate (WORK_PLAN.md crash/
		// restart scenario 3).
		rollback.Rollback(touchedOrderIDs)
		p.nakAll(ackable)
		return len(deliveries), err
	}
	p.stats.Published.Add(uint64(len(batch.Outcomes)))
	p.ackAll(ackable)
	return len(deliveries), nil
}

func (p *Processor) buildBatch(deliveries []CommandDelivery, createdAt string) (VenueEventBatch, []CommandDelivery, *app.BatchRollback, map[string][]string, error) {
	ackable := make([]CommandDelivery, 0, len(deliveries))
	outcomes := make([]CommandOutcomeFact, 0, len(deliveries))
	touchedOrderIDs := make(map[string][]string)
	firstSeq := uint64(0)
	lastSeq := uint64(0)
	checksumInput := strings.Builder{}

	instrumentIDs := make([]string, 0, len(deliveries))
	seenInstruments := make(map[string]bool, len(deliveries))
	for _, delivery := range deliveries {
		instrumentID := instrumentIDFromSubject(delivery.Subject())
		if instrumentID == "" || seenInstruments[instrumentID] {
			continue
		}
		seenInstruments[instrumentID] = true
		instrumentIDs = append(instrumentIDs, instrumentID)
	}
	// Snapshot every touched instrument's book/order records *before* any
	// command in this batch mutates them, so a subsequent failed publish can
	// roll the engine's live state back to exactly this point.
	rollback := p.service.BeginBatch(instrumentIDs)

	for _, delivery := range deliveries {
		commandType := commandTypeFromSubject(delivery.Subject())
		outcome, supported := p.processDelivery(delivery, commandType)

		var fact CommandOutcomeFact
		switch {
		case !supported:
			p.stats.Unsupported.Add(1)
			fact = p.poisonOutcomeFact(delivery, commandType, outcome.CommandID, "UNSUPPORTED_COMMAND_TYPE", fmt.Sprintf("unsupported command type: %s", commandType))
		case outcome.DecodeError != "":
			p.stats.Failed.Add(1)
			p.stats.LastError.Store(outcome.DecodeError)
			fact = p.poisonOutcomeFact(delivery, commandType, outcome.CommandID, "POISON_COMMAND_DECODE_ERROR", outcome.DecodeError)
		default:
			status := "rejected"
			if outcome.Result.Accepted != nil {
				status = "accepted"
			}
			fact = CommandOutcomeFact{
				CommandID:      outcome.CommandID,
				CommandType:    commandType,
				StreamSequence: delivery.StreamSequence(),
				DeliveredCount: delivery.DeliveredCount(),
				PayloadHash:    sha256Hex(delivery.Data()),
				InstrumentID:   outcome.InstrumentID,
				OrderID:        outcome.OrderID,
				Status:         status,
				Result:         outcome.Result,
			}
		}

		if firstSeq == 0 || fact.StreamSequence < firstSeq {
			firstSeq = fact.StreamSequence
		}
		if fact.StreamSequence > lastSeq {
			lastSeq = fact.StreamSequence
		}
		checksumInput.WriteString(fmt.Sprintf("%d:%s:%s;", fact.StreamSequence, fact.CommandID, fact.PayloadHash))
		outcomes = append(outcomes, fact)
		ackable = append(ackable, delivery)
		if fact.OrderID != "" {
			touchedOrderIDs[fact.InstrumentID] = append(touchedOrderIDs[fact.InstrumentID], fact.OrderID)
		}
	}

	if len(outcomes) == 0 {
		return VenueEventBatch{}, ackable, rollback, touchedOrderIDs, nil
	}
	batch := VenueEventBatch{
		BatchID:         fmt.Sprintf("%s-p%d-%d-%d", p.config.ShardID, p.config.Partition, firstSeq, lastSeq),
		ShardID:         p.config.ShardID,
		Partition:       p.config.Partition,
		CommandStream:   p.config.CommandStream,
		EventStream:     p.config.EventStreamName,
		FirstSequence:   firstSeq,
		LastSequence:    lastSeq,
		CommandCount:    len(outcomes),
		CreatedAt:       createdAt,
		PayloadChecksum: sha256Hex([]byte(checksumInput.String())),
		Outcomes:        outcomes,
	}
	p.stats.Processed.Add(uint64(len(outcomes)))
	return batch, ackable, rollback, touchedOrderIDs, nil
}

func (p *Processor) poisonOutcomeFact(delivery CommandDelivery, commandType string, commandID string, code string, reason string) CommandOutcomeFact {
	if commandID == "" {
		commandID = bestEffortCommandID(delivery.Data())
	}
	return CommandOutcomeFact{
		CommandID:      commandID,
		CommandType:    commandType,
		StreamSequence: delivery.StreamSequence(),
		DeliveredCount: delivery.DeliveredCount(),
		PayloadHash:    sha256Hex(delivery.Data()),
		InstrumentID:   instrumentIDFromSubject(delivery.Subject()),
		Status:         "failed",
		Result: domain.SubmitOrderResult{
			Rejected: &domain.OrderRejected{
				Code:   code,
				Reason: reason,
			},
		},
	}
}

type commandIdentity struct {
	CommandID string `json:"commandId"`
}

func bestEffortCommandID(data []byte) string {
	var identity commandIdentity
	if err := json.Unmarshal(data, &identity); err == nil {
		return identity.CommandID
	}
	return ""
}

type processedOutcome struct {
	CommandID    string
	InstrumentID string
	OrderID      string
	Result       domain.SubmitOrderResult
	DecodeError  string
}

func (p *Processor) processDelivery(delivery CommandDelivery, commandType string) (processedOutcome, bool) {
	switch commandType {
	case "SubmitOrder":
		var command domain.SubmitOrder
		if err := json.Unmarshal(delivery.Data(), &command); err != nil {
			return processedOutcome{
				CommandID:   bestEffortCommandID(delivery.Data()),
				DecodeError: fmt.Sprintf("decode submit command: %v", err),
			}, true
		}
		result := p.service.SubmitOrder(command)
		result.AcceptedOrder = acceptedOrderFact(command, result)
		return processedOutcome{
			CommandID:    command.CommandID,
			InstrumentID: command.InstrumentID,
			OrderID:      command.OrderID,
			Result:       result,
		}, true
	case "ModifyOrder":
		var command domain.ModifyOrder
		if err := json.Unmarshal(delivery.Data(), &command); err != nil {
			return processedOutcome{
				CommandID:   bestEffortCommandID(delivery.Data()),
				DecodeError: fmt.Sprintf("decode modify command: %v", err),
			}, true
		}
		return processedOutcome{
			CommandID:    command.CommandID,
			InstrumentID: instrumentIDFromSubject(delivery.Subject()),
			OrderID:      command.OrderID,
			Result:       p.service.ModifyOrder(command),
		}, true
	case "CancelOrder":
		var command domain.CancelOrder
		if err := json.Unmarshal(delivery.Data(), &command); err != nil {
			return processedOutcome{
				CommandID:   bestEffortCommandID(delivery.Data()),
				DecodeError: fmt.Sprintf("decode cancel command: %v", err),
			}, true
		}
		return processedOutcome{
			CommandID:    command.CommandID,
			InstrumentID: instrumentIDFromSubject(delivery.Subject()),
			OrderID:      command.OrderID,
			Result:       p.service.CancelOrder(command),
		}, true
	default:
		return processedOutcome{CommandID: bestEffortCommandID(delivery.Data())}, false
	}
}

func acceptedOrderFact(command domain.SubmitOrder, result domain.SubmitOrderResult) *domain.AcceptedOrderFact {
	if strings.TrimSpace(command.OrderID) == "" ||
		strings.TrimSpace(command.InstrumentID) == "" ||
		strings.TrimSpace(command.ParticipantID) == "" ||
		strings.TrimSpace(command.AccountID) == "" {
		return nil
	}

	engineOrderID := ""
	occurredAt := command.OccurredAt
	if result.Accepted != nil {
		engineOrderID = result.Accepted.EngineOrderID
		occurredAt = result.Accepted.OccurredAt
	} else if result.Rejected != nil {
		occurredAt = result.Rejected.OccurredAt
	}

	return &domain.AcceptedOrderFact{
		OrderID:        command.OrderID,
		EngineOrderID:  engineOrderID,
		ClientOrderID:  command.ClientOrderID,
		RunID:          command.RunID,
		VenueSessionID: command.VenueSessionID,
		InstrumentID:   command.InstrumentID,
		ParticipantID:  command.ParticipantID,
		AccountID:      command.AccountID,
		Side:           command.Side,
		OrderType:      command.OrderType,
		QuantityUnits:  command.QuantityUnits,
		LimitPrice:     command.LimitPrice,
		Currency:       command.Currency,
		TimeInForce:    command.TimeInForce,
		AcceptedAt:     occurredAt,
	}
}

func (p *Processor) nakAll(deliveries []CommandDelivery) {
	for _, delivery := range deliveries {
		if err := delivery.Nak(); err != nil {
			p.recordFailure(err)
			continue
		}
		p.stats.Nacked.Add(1)
	}
}

func (p *Processor) ackAll(deliveries []CommandDelivery) {
	if batchAcker, ok := p.source.(BatchCommandAcker); ok {
		acked, err := batchAcker.AckBatch(deliveries)
		p.stats.Acked.Add(uint64(acked))
		if acked > 0 {
			p.stats.LastAckedAt.Store(time.Now().UTC().Format(time.RFC3339Nano))
		}
		if err != nil {
			p.recordFailure(err)
		}
		return
	}
	for _, delivery := range deliveries {
		if err := delivery.Ack(); err != nil {
			p.recordFailure(err)
			continue
		}
		p.stats.Acked.Add(1)
		p.stats.LastAckedAt.Store(time.Now().UTC().Format(time.RFC3339Nano))
	}
}

func (p *Processor) recordFailure(err error) {
	if err == nil {
		return
	}
	p.stats.Failed.Add(1)
	p.stats.LastError.Store(err.Error())
}

func commandTypeFromSubject(subject string) string {
	if index := strings.LastIndex(subject, "."); index >= 0 && index < len(subject)-1 {
		return subject[index+1:]
	}
	return subject
}

func instrumentIDFromSubject(subject string) string {
	parts := strings.Split(subject, ".")
	if len(parts) < 2 {
		return ""
	}
	return parts[len(parts)-2]
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
