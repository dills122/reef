package streamdirect

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/dills122/reef/services/matching-engine/internal/app"
)

// TestKafkaLaneDrainRate is a measurement, not a regression test: it pre-fills a command topic with crossing pairs on one
// instrument and drains it through the real source, the real transactional publisher and the real processor, for each batch
// size in KAFKA_DRAIN_BATCHES. It answers what one lane costs per batch (CPU plus the transaction's broker round trips),
// which is what bounds a hot lane when the batch is the unit of work. Skipped unless REEF_KAFKA_INTEGRATION_BOOTSTRAP is
// set; KAFKA_DRAIN_PAIRS (default 20000) and KAFKA_DRAIN_BATCHES (default 500,1000,2000) tune it.
func TestKafkaLaneDrainRate(t *testing.T) {
	bootstrap := strings.TrimSpace(os.Getenv("REEF_KAFKA_INTEGRATION_BOOTSTRAP"))
	if bootstrap == "" || os.Getenv("KAFKA_DRAIN") == "" {
		t.Skip("set REEF_KAFKA_INTEGRATION_BOOTSTRAP and KAFKA_DRAIN=1 to measure the lane's drain rate")
	}
	pairs := 20000
	if v, err := strconv.Atoi(os.Getenv("KAFKA_DRAIN_PAIRS")); err == nil && v > 0 {
		pairs = v
	}
	sizes := []int{500, 1000, 2000}
	if v := os.Getenv("KAFKA_DRAIN_BATCHES"); v != "" {
		sizes = nil
		for _, f := range strings.Split(v, ",") {
			n, err := strconv.Atoi(strings.TrimSpace(f))
			if err != nil {
				t.Fatalf("KAFKA_DRAIN_BATCHES: %v", err)
			}
			sizes = append(sizes, n)
		}
	}
	t.Setenv("MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES", strconv.Itoa(32<<20))
	for _, size := range sizes {
		token := fmt.Sprintf("%d", time.Now().UnixNano())
		commandTopic, eventTopic := "reef-drain-commands-"+token, "reef-drain-events-"+token
		groupID := "reef-drain-" + token + "-p00"
		config := KafkaConfig{BootstrapServers: bootstrap, CommandTopic: commandTopic, CommandSubjectPrefix: "reef.cmd.v1", EventTopic: eventTopic, EventSubjectPrefix: "reef.venue.events.v1", PartitionCount: 1, FetchTimeout: time.Second}
		if err := EnsureKafkaTopic(config, commandTopic); err != nil {
			t.Fatal(err)
		}
		admin, err := sarama.NewClusterAdmin(kafkaBrokers(config), newKafkaClientConfig("reef-drain-admin"))
		if err != nil {
			t.Fatal(err)
		}
		limit := strconv.Itoa(32 << 20)
		if err := admin.CreateTopic(eventTopic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1, ConfigEntries: map[string]*string{"max.message.bytes": &limit}}, false); err != nil {
			t.Fatal(err)
		}
		_ = admin.Close()
		produceDrainBacklog(t, config, pairs)

		source, err := NewKafkaCommandSource(config, 0, groupID)
		if err != nil {
			t.Fatal(err)
		}
		publisher, err := NewKafkaTransactionalEventBatchPublisher(config, source)
		if err != nil {
			t.Fatal(err)
		}
		timedSrc := &drainTimedSource{inner: source}
		timedPub := &drainTimedPublisher{inner: publisher}
		processor := NewProcessor(app.NewService(), timedSrc, timedPub, ProcessorConfig{ShardID: "engine-drain", Partition: 0, BatchSize: size, FetchTimeout: 200 * time.Millisecond, CommandStream: commandTopic, EventStreamName: eventTopic, Source: "redpanda"})
		start := time.Now()
		total, calls := 0, 0
		for total < 2*pairs {
			n, err := processor.ProcessOnce(context.Background())
			if err != nil {
				t.Fatalf("batch %d: %v", size, err)
			}
			total += n
			if n > 0 {
				calls++
			}
		}
		elapsed := time.Since(start)
		fmt.Printf("LANE-DRAIN split: fetch=%.2fs publish=%.2fs build+other=%.2fs\n", timedSrc.nanos.Seconds(), timedPub.nanos.Seconds(), (elapsed - timedSrc.nanos - timedPub.nanos).Seconds())
		fmt.Printf("LANE-DRAIN batch=%d commands=%d batches=%d elapsed=%.2fs commands/s=%.0f pairs/s=%.0f ms/batch=%.1f \n", size, total, calls, elapsed.Seconds(), float64(total)/elapsed.Seconds(), float64(total)/2/elapsed.Seconds(), float64(elapsed.Milliseconds())/float64(calls))
		_ = publisher.Close()
		_ = source.Close()
		deleteKafkaIntegrationTopics(config, commandTopic, eventTopic)
	}
}

func produceDrainBacklog(t *testing.T, config KafkaConfig, pairs int) {
	t.Helper()
	cfg := newKafkaClientConfig("reef-drain-producer")
	cfg.Producer.Idempotent = false
	cfg.Net.MaxOpenRequests = 5
	cfg.Producer.RequiredAcks = sarama.WaitForLocal
	cfg.Producer.Flush.Messages = 1000
	cfg.Producer.Flush.Frequency = 20 * time.Millisecond
	producer, err := sarama.NewAsyncProducer(kafkaBrokers(config), cfg)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	sent, want := 0, 2*pairs
	go func() {
		for sent < want {
			select {
			case <-producer.Successes():
				sent++
			case err := <-producer.Errors():
				t.Errorf("produce: %v", err)
				sent++
			}
		}
		close(done)
	}()
	for i := 0; i < pairs; i++ {
		for k, side := range []string{"BUY", "SELL"} {
			payload, _ := json.Marshal(map[string]string{
				"commandId": fmt.Sprintf("cmd-d-%07d-%d", i, k), "occurredAt": "2026-07-19T18:00:00Z", "orderId": fmt.Sprintf("ord-d-%07d-%d", i, k), "instrumentId": "STK001",
				"participantId": fmt.Sprintf("participant-%d", (i+k)%50), "accountId": fmt.Sprintf("account-%d", (i+k)%50), "side": side, "orderType": "LIMIT",
				"quantityUnits": "100", "limitPrice": "100000000000", "currency": "USD", "timeInForce": "DAY",
			})
			producer.Input() <- &sarama.ProducerMessage{Topic: config.CommandTopic, Partition: 0, Value: sarama.ByteEncoder(payload),
				Headers: []sarama.RecordHeader{{Key: []byte(kafkaStreamSubjectHeader), Value: []byte("reef.cmd.v1.p00.session.STK001.SubmitOrder")}}}
		}
	}
	<-done
	_ = producer.Close()
}

type drainTimedSource struct {
	inner *KafkaCommandSource
	nanos time.Duration
}

func (s *drainTimedSource) Fetch(ctx context.Context, n int, timeout time.Duration) ([]CommandDelivery, error) {
	start := time.Now()
	out, err := s.inner.Fetch(ctx, n, timeout)
	s.nanos += time.Since(start)
	return out, err
}

type drainTimedPublisher struct {
	inner *KafkaEventBatchPublisher
	nanos time.Duration
}

func (p *drainTimedPublisher) PublishEventBatch(ctx context.Context, batch VenueEventBatch) error {
	return p.inner.PublishEventBatch(ctx, batch)
}

func (p *drainTimedPublisher) PublishEventBatchAndAck(ctx context.Context, batch VenueEventBatch, deliveries []CommandDelivery) (int, error) {
	start := time.Now()
	n, err := p.inner.PublishEventBatchAndAck(ctx, batch, deliveries)
	p.nanos += time.Since(start)
	return n, err
}
