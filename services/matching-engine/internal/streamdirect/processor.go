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

	batch, ackable, terminal, err := p.buildBatch(deliveries, now)
	if err != nil {
		p.nakAll(ackable)
		return len(deliveries), err
	}
	if len(ackable) == 0 {
		p.termAll(terminal)
		return len(deliveries), nil
	}
	if err := p.publisher.PublishEventBatch(ctx, batch); err != nil {
		p.nakAll(ackable)
		return len(deliveries), err
	}
	p.stats.Published.Add(uint64(len(batch.Outcomes)))
	p.ackAll(ackable)
	p.termAll(terminal)
	return len(deliveries), nil
}

func (p *Processor) buildBatch(deliveries []CommandDelivery, createdAt string) (VenueEventBatch, []CommandDelivery, []CommandDelivery, error) {
	ackable := make([]CommandDelivery, 0, len(deliveries))
	terminal := make([]CommandDelivery, 0)
	outcomes := make([]CommandOutcomeFact, 0, len(deliveries))
	firstSeq := uint64(0)
	lastSeq := uint64(0)
	checksumInput := strings.Builder{}

	for _, delivery := range deliveries {
		commandType := commandTypeFromSubject(delivery.Subject())
		if commandType != "SubmitOrder" {
			terminal = append(terminal, delivery)
			p.stats.Unsupported.Add(1)
			continue
		}

		var command domain.SubmitOrder
		if err := json.Unmarshal(delivery.Data(), &command); err != nil {
			terminal = append(terminal, delivery)
			p.stats.Failed.Add(1)
			p.stats.LastError.Store(fmt.Sprintf("decode command: %v", err))
			continue
		}

		result := p.service.SubmitOrder(command)
		status := "rejected"
		if result.Accepted != nil {
			status = "accepted"
		}
		seq := delivery.StreamSequence()
		if firstSeq == 0 || seq < firstSeq {
			firstSeq = seq
		}
		if seq > lastSeq {
			lastSeq = seq
		}
		payloadHash := sha256Hex(delivery.Data())
		checksumInput.WriteString(fmt.Sprintf("%d:%s:%s;", seq, command.CommandID, payloadHash))
		outcomes = append(outcomes, CommandOutcomeFact{
			CommandID:      command.CommandID,
			CommandType:    commandType,
			StreamSequence: seq,
			DeliveredCount: delivery.DeliveredCount(),
			PayloadHash:    payloadHash,
			InstrumentID:   command.InstrumentID,
			OrderID:        command.OrderID,
			Status:         status,
			Result:         result,
		})
		ackable = append(ackable, delivery)
	}

	if len(outcomes) == 0 {
		return VenueEventBatch{}, ackable, terminal, nil
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
	return batch, ackable, terminal, nil
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

func (p *Processor) termAll(deliveries []CommandDelivery) {
	for _, delivery := range deliveries {
		if termErr := delivery.Term(); termErr != nil {
			p.recordFailure(termErr)
		}
		p.stats.Termed.Add(1)
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

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
