package streamdirect

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

type Runner struct {
	cancel     context.CancelFunc
	processors []*Processor
	close      func()
	wg         sync.WaitGroup
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
		publisher := NewNatsEventBatchPublisher(js, config.EventStream, config.EventSubjectPrefix, config.PartitionCount)
		processor := NewProcessor(service, source, publisher, ProcessorConfig{
			ShardID:         config.ShardID,
			Partition:       partition,
			BatchSize:       config.BatchSize,
			FetchTimeout:    config.FetchTimeout,
			PollInterval:    config.PollInterval,
			CommandStream:   config.CommandStream,
			EventStreamName: config.EventStream,
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
		PartitionCount:       config.PartitionCount,
		FetchTimeout:         config.FetchTimeout,
	}
	if err := EnsureKafkaTopic(kafkaConfig, config.CommandStream); err != nil {
		return nil, err
	}
	if err := EnsureKafkaTopic(kafkaConfig, config.EventStream); err != nil {
		return nil, err
	}
	publisher, err := NewKafkaEventBatchPublisher(kafkaConfig)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	closers := []func() error{publisher.Close}
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
	for _, partition := range config.Partitions {
		source, err := NewKafkaCommandSource(kafkaConfig, partition, fmt.Sprintf("%s-%s", config.DurablePrefix, PartitionToken(config.PartitionCount, partition)))
		if err != nil {
			cancel()
			runner.close()
			return nil, err
		}
		closers = append(closers, source.Close)
		processor := NewProcessor(service, source, publisher, ProcessorConfig{
			ShardID:         config.ShardID,
			Partition:       partition,
			BatchSize:       config.BatchSize,
			FetchTimeout:    config.FetchTimeout,
			PollInterval:    config.PollInterval,
			CommandStream:   config.CommandStream,
			EventStreamName: config.EventStream,
		})
		runner.processors = append(runner.processors, processor)
		runner.wg.Add(1)
		go func(partition int, processor *Processor) {
			defer runner.wg.Done()
			log.Printf("matching-engine direct redpanda partition=%d started", partition)
			if err := processor.Run(ctx); err != nil && ctx.Err() == nil {
				log.Printf("matching-engine direct redpanda partition=%d stopped with error: %v", partition, err)
			}
		}(partition, processor)
	}
	return runner, nil
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
