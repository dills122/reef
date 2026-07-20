package streamdirect

import (
	"context"
	"errors"
	"fmt"
	"log"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

type Runner struct {
	cancel     context.CancelFunc
	processors []*Processor
	close      func()
	wg         sync.WaitGroup
}

type fatalStartupError struct{ cause error }

func (e *fatalStartupError) Error() string { return e.cause.Error() }
func (e *fatalStartupError) Unwrap() error { return e.cause }

type kafkaRunnerLane struct {
	partition int
	source    *KafkaCommandSource
	processor *Processor
}

func StartRunner(parent context.Context, service *app.Service, config RuntimeConfig) (*Runner, error) {
	if len(config.Partitions) == 0 {
		return nil, fmt.Errorf("matching-engine direct stream requires at least one partition")
	}
	switch config.LogProvider {
	case "", "jetstream":
		return startRunnerWithRetry(parent, service, config, startNatsRunner)
	case "redpanda":
		return startRunnerWithRetry(parent, service, config, startKafkaRunner)
	default:
		return nil, fmt.Errorf("unsupported direct stream provider %q", config.LogProvider)
	}
}

func startRunnerWithRetry(parent context.Context, service *app.Service, config RuntimeConfig, start func(context.Context, *app.Service, RuntimeConfig) (*Runner, error)) (*Runner, error) {
	timeout := config.ConnectTimeout
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	deadline := time.Now().Add(timeout)
	var lastErr error
	for {
		runner, err := start(parent, service, config)
		if err == nil {
			return runner, nil
		}
		var fatal *fatalStartupError
		if errors.As(err, &fatal) {
			return nil, err
		}
		lastErr = err
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("matching-engine direct stream startup failed after %s: %w", timeout, lastErr)
		}
		log.Printf("matching-engine direct stream startup waiting for provider=%s: %v", config.LogProvider, err)
		timer := time.NewTimer(time.Second)
		select {
		case <-parent.Done():
			timer.Stop()
			return nil, parent.Err()
		case <-timer.C:
		}
	}
}

func startNatsRunner(parent context.Context, service *app.Service, config RuntimeConfig) (*Runner, error) {
	natsConfig := NatsConfig{
		URL:                  config.NatsURL,
		CommandStream:        config.CommandStream,
		CommandSubjectPrefix: config.CommandSubjectPrefix,
		EventStream:          config.EventStream,
		EventSubjectPrefix:   config.EventSubjectPrefix,
		PartitionCount:       config.PartitionCount,
		AckWait:              config.AckWait,
		MaxAckPending:        config.MaxAckPending,
	}
	nc, js, err := ConnectNats(natsConfig)
	if err != nil {
		return nil, err
	}
	if err := EnsureEventStream(js, config.EventStream, config.EventSubjectPrefix); err != nil {
		nc.Close()
		return nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	runner := &Runner{
		cancel: cancel,
		close:  nc.Close,
	}
	for _, partition := range config.Partitions {
		source := NewNatsCommandSource(js, natsConfig, partition, config.DurablePrefix)
		commandSource := wrapCommandSourceWithLocalFaultHooks(source, config)
		publisher := wrapEventBatchPublisherWithLocalFaultHooks(
			NewNatsEventBatchPublisher(js, config.EventStream, config.EventSubjectPrefix, config.PartitionCount),
			config,
		)
		processor := NewProcessor(service, commandSource, publisher, ProcessorConfig{
			ShardID:              config.ShardID,
			Partition:            partition,
			BatchSize:            config.BatchSize,
			FetchTimeout:         config.FetchTimeout,
			PollInterval:         config.PollInterval,
			CommandStream:        config.CommandStream,
			EventStreamName:      config.EventStream,
			Source:               "jetstream",
			StopAfterAckFail:     config.TestStopAfterAckFail,
			StopAfterPublishFail: config.TestStopAfterPublishFail,
		})
		runner.processors = append(runner.processors, processor)
		runner.wg.Add(1)
		go func(partition int, processor *Processor) {
			defer runner.wg.Done()
			log.Printf("matching-engine direct stream partition=%d started", partition)
			if err := processor.Run(ctx); err != nil && ctx.Err() == nil {
				log.Printf("matching-engine direct stream partition=%d stopped with error: %v", partition, err)
			}
		}(partition, processor)
	}
	return runner, nil
}

func startKafkaRunner(parent context.Context, service *app.Service, config RuntimeConfig) (*Runner, error) {
	kafkaConfig := KafkaConfig{
		BootstrapServers:     config.KafkaBootstrap,
		CommandTopic:         config.CommandStream,
		CommandSubjectPrefix: config.CommandSubjectPrefix,
		EventTopic:           config.EventStream,
		EventSubjectPrefix:   config.EventSubjectPrefix,
		OwnershipTopic:       config.OwnershipTopic,
		OwnershipGroup:       config.OwnershipGroup,
		PartitionCount:       config.PartitionCount,
		FetchTimeout:         config.FetchTimeout,
	}
	if err := EnsureKafkaTopic(kafkaConfig, config.CommandStream); err != nil {
		return nil, err
	}
	if err := EnsureKafkaTopic(kafkaConfig, config.EventStream); err != nil {
		return nil, err
	}
	if err := EnsureKafkaTopic(kafkaConfig, config.OwnershipTopic); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	closers := []func() error{}
	runner := &Runner{
		cancel: cancel,
		close: func() {
			for _, closeFn := range closers {
				if err := closeFn(); err != nil {
					log.Printf("matching-engine direct stream close error: %v", err)
				}
			}
		},
	}
	ownership, err := AcquireKafkaOwnershipLease(ctx, kafkaConfig, config.ShardID, config.Partitions)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("acquire matching-engine Kafka ownership: %w", err)
	}
	closers = append(closers, ownership.Close)
	lanes := make([]kafkaRunnerLane, 0, len(config.Partitions))
	for _, partition := range config.Partitions {
		source, err := NewKafkaCommandSource(kafkaConfig, partition, fmt.Sprintf("%s-%s", config.DurablePrefix, PartitionToken(config.PartitionCount, partition)))
		if err != nil {
			cancel()
			runner.close()
			return nil, err
		}
		source.ownership = ownership
		publisher, err := NewKafkaTransactionalEventBatchPublisher(kafkaConfig, source)
		if err != nil {
			source.Close()
			cancel()
			runner.close()
			return nil, err
		}
		if config.TestFailAckOnce {
			injectedFailureCount := &atomic.Uint64{}
			publisher.beforeOffsetCommit = func() error {
				if injectedFailureCount.CompareAndSwap(0, 1) {
					return errors.New("injected matching-engine transaction offset failure before commit")
				}
				return nil
			}
		}
		closers = append(closers, publisher.Close, source.Close)
		commandSource := CommandSource(source)
		eventPublisher := wrapEventBatchPublisherWithLocalFaultHooks(publisher, config)
		processor := NewProcessor(service, commandSource, eventPublisher, ProcessorConfig{
			ShardID:              config.ShardID,
			Partition:            partition,
			BatchSize:            config.BatchSize,
			FetchTimeout:         config.FetchTimeout,
			PollInterval:         config.PollInterval,
			CommandStream:        config.CommandStream,
			EventStreamName:      config.EventStream,
			Source:               "redpanda",
			StopAfterAckFail:     config.TestStopAfterAckFail,
			StopAfterPublishFail: config.TestStopAfterPublishFail,
		})
		runner.processors = append(runner.processors, processor)
		lanes = append(lanes, kafkaRunnerLane{
			partition: partition,
			source:    source,
			processor: processor,
		})
	}

	recoveryTimeout := config.RecoveryTimeout
	if recoveryTimeout <= 0 {
		recoveryTimeout = 15 * time.Minute
	}
	recoveryCtx, stopRecovery := context.WithTimeout(parent, recoveryTimeout)
	defer stopRecovery()
	for _, lane := range lanes {
		replayed, err := lane.processor.RestoreCommitted(recoveryCtx, lane.source)
		if err != nil {
			cancel()
			runner.close()
			return nil, &fatalStartupError{cause: fmt.Errorf("matching-engine recovery failed: %w", err)}
		}
		if replayed > 0 {
			log.Printf("matching-engine direct redpanda partition=%d restored commands=%d", lane.partition, replayed)
		}
	}
	restoredSnapshot := service.Snapshot()
	log.Printf(
		"matching-engine direct redpanda recovery complete partitions=%v books=%d orders=%d checksum=%s",
		config.Partitions,
		restoredSnapshot.Metadata.BookCount,
		restoredSnapshot.Metadata.OrderCount,
		restoredSnapshot.Checksum,
	)

	for _, lane := range lanes {
		runner.wg.Add(1)
		go func(partition int, processor *Processor) {
			defer runner.wg.Done()
			log.Printf("matching-engine direct redpanda partition=%d started", partition)
			if err := processor.Run(ctx); err != nil && ctx.Err() == nil {
				log.Printf("matching-engine direct redpanda partition=%d stopped with error: %v", partition, err)
				var fatal *fatalProcessorError
				if errors.As(err, &fatal) {
					// A fenced owner or indeterminate commit cannot keep serving a
					// partial shard safely. Stop every lane so orchestration can
					// start one fenced replacement and replay the committed truth.
					cancel()
				}
			}
		}(lane.partition, lane.processor)
	}
	return runner, nil
}

func wrapCommandSourceWithLocalFaultHooks(source CommandSource, config RuntimeConfig) CommandSource {
	if !config.TestFailAckOnce {
		return source
	}
	return &localAckFailureSource{
		delegate:             source,
		injectedFailureCount: &atomic.Uint64{},
	}
}

type localAckFailureSource struct {
	delegate             CommandSource
	injectedFailureCount *atomic.Uint64
}

func (s *localAckFailureSource) Fetch(ctx context.Context, batchSize int, timeout time.Duration) ([]CommandDelivery, error) {
	return s.delegate.Fetch(ctx, batchSize, timeout)
}

func (s *localAckFailureSource) AckBatch(deliveries []CommandDelivery) (int, error) {
	if s.injectedFailureCount.CompareAndSwap(0, 1) {
		return 0, errors.New("injected matching-engine ack failure before command offset commit")
	}
	if batchAcker, ok := s.delegate.(BatchCommandAcker); ok {
		return batchAcker.AckBatch(deliveries)
	}
	acked := 0
	for _, delivery := range deliveries {
		if err := delivery.Ack(); err != nil {
			return acked, err
		}
		acked++
	}
	return acked, nil
}

func wrapEventBatchPublisherWithLocalFaultHooks(publisher EventBatchPublisher, config RuntimeConfig) EventBatchPublisher {
	if !config.TestFailPublishOnce {
		return publisher
	}
	return &localPublishFailurePublisher{
		delegate:             publisher,
		injectedFailureCount: &atomic.Uint64{},
	}
}

type localPublishFailurePublisher struct {
	delegate             EventBatchPublisher
	injectedFailureCount *atomic.Uint64
}

func (p *localPublishFailurePublisher) PublishEventBatch(ctx context.Context, batch VenueEventBatch) error {
	if p.injectedFailureCount.CompareAndSwap(0, 1) {
		return errors.New("injected matching-engine event-batch publish failure before command offset commit")
	}
	return p.delegate.PublishEventBatch(ctx, batch)
}

func (p *localPublishFailurePublisher) PublishEventBatchAndAck(ctx context.Context, batch VenueEventBatch, deliveries []CommandDelivery) (int, error) {
	if p.injectedFailureCount.CompareAndSwap(0, 1) {
		return 0, errors.New("injected matching-engine event-batch publish failure before Kafka transaction")
	}
	atomicPublisher, ok := p.delegate.(AtomicEventBatchPublisher)
	if !ok {
		return 0, &fatalProcessorError{cause: errors.New("wrapped event publisher does not support atomic Kafka transactions")}
	}
	return atomicPublisher.PublishEventBatchAndAck(ctx, batch, deliveries)
}

func (r *Runner) Stop() {
	if r == nil {
		return
	}
	r.cancel()
	r.wg.Wait()
	if r.close != nil {
		r.close()
	}
}

func (r *Runner) Snapshots() []Snapshot {
	if r == nil {
		return nil
	}
	out := make([]Snapshot, 0, len(r.processors))
	for _, processor := range r.processors {
		out = append(out, processor.Stats())
	}
	return out
}
