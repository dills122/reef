package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/dills122/reef/services/matching-engine/internal/app"
)

// The whole oversize path against a real broker: an event topic whose max.message.bytes is far below what a full
// batch needs (the limit applies to the compressed batch, which the producer's own uncompressed check cannot see),
// the real transactional publisher, and the real command source (nak rewinds the consumer offset). The broker
// rejects inside the open transaction; the lane must abort it, halve its fetch size, finish every command exactly
// once, and leave no aborted batch visible to read_committed consumers.
func TestKafkaOversizeEventBatchHalvesTheLaneAgainstARealBroker(t *testing.T) {
	bootstrap := strings.TrimSpace(os.Getenv("REEF_KAFKA_INTEGRATION_BOOTSTRAP"))
	if bootstrap == "" {
		t.Skip("set REEF_KAFKA_INTEGRATION_BOOTSTRAP to run broker-backed transaction tests")
	}
	token := fmt.Sprintf("%d", time.Now().UnixNano())
	commandTopic, eventTopic := "reef-test-oversize-commands-"+token, "reef-test-oversize-events-"+token
	groupID := "reef-test-oversize-" + token + "-p00"
	config := KafkaConfig{BootstrapServers: bootstrap, CommandTopic: commandTopic, CommandSubjectPrefix: "reef.cmd.v1", EventTopic: eventTopic, EventSubjectPrefix: "reef.venue.events.v1", PartitionCount: 1, FetchTimeout: time.Second}
	if err := EnsureKafkaTopic(config, commandTopic); err != nil {
		t.Fatalf("ensure command topic: %v", err)
	}
	const topicLimit = "2500"
	admin, err := sarama.NewClusterAdmin(kafkaBrokers(config), newKafkaClientConfig("reef-oversize-integration-admin"))
	if err != nil {
		t.Fatalf("open admin: %v", err)
	}
	limit := topicLimit
	if err := admin.CreateTopic(eventTopic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1, ConfigEntries: map[string]*string{"max.message.bytes": &limit}}, false); err != nil {
		t.Fatalf("create event topic: %v", err)
	}
	_ = admin.Close()
	t.Cleanup(func() { deleteKafkaIntegrationTopics(config, commandTopic, eventTopic) })

	const commands = 60
	for i := 0; i < commands; i++ {
		produceKafkaIntegrationCommand(t, config, fmt.Sprintf("cmd-os-%03d", i), fmt.Sprintf("ord-os-%03d", i), 0)
	}
	source, err := NewKafkaCommandSource(config, 0, groupID)
	if err != nil {
		t.Fatalf("open command source: %v", err)
	}
	t.Cleanup(func() { _ = source.Close() })
	publisher, err := NewKafkaTransactionalEventBatchPublisher(config, source)
	if err != nil {
		t.Fatalf("open transactional publisher: %v", err)
	}
	t.Cleanup(func() { _ = publisher.Close() })
	service := app.NewService()
	processor := NewProcessor(service, source, publisher, ProcessorConfig{ShardID: "engine-integration", Partition: 0, BatchSize: commands, FetchTimeout: time.Second, CommandStream: commandTopic, EventStreamName: eventTopic, Source: "redpanda"})

	tooLarge := 0
	for call := 0; kafkaIntegrationCommittedOffset(t, config, groupID) < commands; call++ {
		if call > 100 {
			t.Fatalf("lane did not finish: committed %d of %d after %d calls (stats %#v)", kafkaIntegrationCommittedOffset(t, config, groupID), commands, call, processor.Stats())
		}
		_, err := processor.ProcessOnce(context.Background())
		if err == nil {
			continue
		}
		var fatal *fatalProcessorError
		if errors.As(err, &fatal) || !isEventBatchTooLarge(err) {
			t.Fatalf("unexpected error: %v", err)
		}
		tooLarge++
	}
	if tooLarge == 0 || processor.Stats().BatchSplits == 0 {
		t.Fatalf("the topic limit never bit (tooLarge=%d stats=%#v)", tooLarge, processor.Stats())
	}
	for i := 0; i < commands; i++ {
		if _, ok := service.OrderState("", fmt.Sprintf("ord-os-%03d", i)); !ok {
			t.Fatalf("order %d missing from the engine", i)
		}
	}
	ids := kafkaIntegrationReadCommittedCommandIDs(t, config, 5*time.Second)
	if len(ids) != commands {
		t.Fatalf("read_committed events carry %d commands, want %d (an aborted or repeated batch leaked): %v", len(ids), commands, ids)
	}
	for i, id := range ids {
		if want := fmt.Sprintf("cmd-os-%03d", i); id != want {
			t.Fatalf("event %d is %s, want %s", i, id, want)
		}
	}
}

func kafkaIntegrationReadCommittedCommandIDs(t *testing.T, config KafkaConfig, quiet time.Duration) []string {
	t.Helper()
	consumerConfig := newKafkaClientConfig("reef-oversize-integration-read-committed")
	consumerConfig.Consumer.IsolationLevel = sarama.ReadCommitted
	consumerConfig.Consumer.Fetch.Default = 4 << 20
	consumer, err := sarama.NewConsumer(kafkaBrokers(config), consumerConfig)
	if err != nil {
		t.Fatalf("open read_committed consumer: %v", err)
	}
	defer consumer.Close()
	partitionConsumer, err := consumer.ConsumePartition(config.EventTopic, 0, sarama.OffsetOldest)
	if err != nil {
		t.Fatalf("open read_committed partition: %v", err)
	}
	defer partitionConsumer.Close()
	var ids []string
	timer := time.NewTimer(quiet)
	defer timer.Stop()
	for {
		select {
		case <-timer.C:
			return ids
		case message, ok := <-partitionConsumer.Messages():
			if !ok {
				return ids
			}
			var batch VenueEventBatch
			if err := json.Unmarshal(message.Value, &batch); err != nil {
				t.Fatalf("decode event batch: %v", err)
			}
			for _, outcome := range batch.Outcomes {
				ids = append(ids, outcome.CommandID)
			}
			if !timer.Stop() {
				<-timer.C
			}
			timer.Reset(quiet)
		case consumerErr := <-partitionConsumer.Errors():
			if consumerErr != nil {
				t.Fatalf("read_committed consume: %v", consumerErr)
			}
		}
	}
}
