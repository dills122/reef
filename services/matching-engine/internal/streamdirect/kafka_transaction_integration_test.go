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

func TestKafkaTransactionalLaneIntegration(t *testing.T) {
	bootstrap := strings.TrimSpace(os.Getenv("REEF_KAFKA_INTEGRATION_BOOTSTRAP"))
	if bootstrap == "" {
		t.Skip("set REEF_KAFKA_INTEGRATION_BOOTSTRAP to run broker-backed transaction tests")
	}

	token := fmt.Sprintf("%d", time.Now().UnixNano())
	commandTopic := "reef-test-commands-" + token
	eventTopic := "reef-test-events-" + token
	groupID := "reef-test-engine-" + token + "-p00"
	config := KafkaConfig{
		BootstrapServers:     bootstrap,
		CommandTopic:         commandTopic,
		CommandSubjectPrefix: "reef.cmd.v1",
		EventTopic:           eventTopic,
		EventSubjectPrefix:   "reef.venue.events.v1",
		PartitionCount:       1,
		FetchTimeout:         time.Second,
	}
	if err := EnsureKafkaTopic(config, commandTopic); err != nil {
		t.Fatalf("ensure command topic: %v", err)
	}
	if err := EnsureKafkaTopic(config, eventTopic); err != nil {
		t.Fatalf("ensure event topic: %v", err)
	}
	t.Cleanup(func() { deleteKafkaIntegrationTopics(config, commandTopic, eventTopic) })

	produceKafkaIntegrationCommand(t, config, "cmd-1", "ord-1", 0)
	service := app.NewService()
	source, err := NewKafkaCommandSource(config, 0, groupID)
	if err != nil {
		t.Fatalf("open command source: %v", err)
	}
	publisher, err := NewKafkaTransactionalEventBatchPublisher(config, source)
	if err != nil {
		_ = source.Close()
		t.Fatalf("open transactional publisher: %v", err)
	}
	processor := NewProcessor(service, source, publisher, ProcessorConfig{
		ShardID:         "engine-integration",
		Partition:       0,
		BatchSize:       10,
		FetchTimeout:    time.Second,
		CommandStream:   commandTopic,
		EventStreamName: eventTopic,
		Source:          "redpanda",
	})

	if processed, err := processor.ProcessOnce(context.Background()); err != nil || processed != 1 {
		t.Fatalf("commit transaction: processed=%d err=%v", processed, err)
	}
	if offset := kafkaIntegrationCommittedOffset(t, config, groupID); offset != 1 {
		t.Fatalf("committed command offset=%d want=1", offset)
	}
	if count := kafkaIntegrationReadCommittedCount(t, config, 1, time.Second); count != 1 {
		t.Fatalf("read_committed event count=%d want=1", count)
	}

	produceKafkaIntegrationCommand(t, config, "cmd-2", "ord-2", 0)
	publisher.beforeOffsetCommit = func() error { return errors.New("injected integration abort") }
	if _, err := processor.ProcessOnce(context.Background()); err == nil {
		t.Fatal("expected injected transaction abort")
	}
	if _, exists := service.OrderState("ord-2"); exists {
		t.Fatal("aborted transaction retained matching mutation")
	}
	if offset := kafkaIntegrationCommittedOffset(t, config, groupID); offset != 1 {
		t.Fatalf("aborted transaction advanced command offset to %d", offset)
	}
	if count := kafkaIntegrationReadCommittedCount(t, config, 2, 500*time.Millisecond); count != 1 {
		t.Fatalf("read_committed exposed aborted event batch; count=%d", count)
	}

	publisher.beforeOffsetCommit = nil
	if processed, err := processor.ProcessOnce(context.Background()); err != nil || processed != 1 {
		t.Fatalf("retry aborted transaction: processed=%d err=%v", processed, err)
	}
	if offset := kafkaIntegrationCommittedOffset(t, config, groupID); offset != 2 {
		t.Fatalf("retried transaction committed offset=%d want=2", offset)
	}
	wantChecksum := service.Snapshot().Checksum

	if err := publisher.Close(); err != nil {
		t.Fatalf("close first transactional publisher: %v", err)
	}
	if err := source.Close(); err != nil {
		t.Fatalf("close first command source: %v", err)
	}

	recoveryService := app.NewService()
	recoverySource, err := NewKafkaCommandSource(config, 0, groupID)
	if err != nil {
		t.Fatalf("open recovery command source: %v", err)
	}
	t.Cleanup(func() { _ = recoverySource.Close() })
	recoveryPublisher, err := NewKafkaTransactionalEventBatchPublisher(config, recoverySource)
	if err != nil {
		t.Fatalf("open recovery transactional publisher: %v", err)
	}
	t.Cleanup(func() { _ = recoveryPublisher.Close() })
	recoveryProcessor := NewProcessor(recoveryService, recoverySource, recoveryPublisher, ProcessorConfig{
		ShardID:   "engine-integration",
		Partition: 0,
		BatchSize: 1,
	})
	replayed, err := recoveryProcessor.RestoreCommitted(context.Background(), recoverySource)
	if err != nil {
		t.Fatalf("restore committed command prefix: %v", err)
	}
	if replayed != 2 {
		t.Fatalf("replayed commands=%d want=2", replayed)
	}
	if got := recoveryService.Snapshot().Checksum; got != wantChecksum {
		t.Fatalf("recovery checksum=%s want=%s", got, wantChecksum)
	}

}

func TestKafkaOwnershipLeaseIntegration(t *testing.T) {
	bootstrap := strings.TrimSpace(os.Getenv("REEF_KAFKA_INTEGRATION_BOOTSTRAP"))
	if bootstrap == "" {
		t.Skip("set REEF_KAFKA_INTEGRATION_BOOTSTRAP to run broker-backed ownership tests")
	}
	token := fmt.Sprintf("%d", time.Now().UnixNano())
	ownershipTopic := "reef-test-ownership-" + token
	config := KafkaConfig{
		BootstrapServers: bootstrap,
		OwnershipTopic:   ownershipTopic,
		OwnershipGroup:   "reef-test-ownership-group-" + token,
		PartitionCount:   1,
	}
	if err := EnsureKafkaTopic(config, ownershipTopic); err != nil {
		t.Fatalf("ensure ownership topic: %v", err)
	}
	t.Cleanup(func() { deleteKafkaIntegrationTopics(config, ownershipTopic) })

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	shardID := "engine-slot-" + token
	first, err := AcquireKafkaOwnershipLease(ctx, config, shardID, []int{0})
	if err != nil {
		t.Fatalf("acquire first ownership lease: %v", err)
	}
	t.Cleanup(func() { _ = first.Close() })
	if err := first.AssertOwned(0); err != nil {
		t.Fatalf("first owner did not acquire partition 0: %v", err)
	}

	second, err := AcquireKafkaOwnershipLease(ctx, config, shardID, []int{0})
	if err != nil {
		t.Fatalf("replacement owner did not acquire static membership: %v", err)
	}
	t.Cleanup(func() { _ = second.Close() })
	if err := second.AssertOwned(0); err != nil {
		t.Fatalf("replacement owner does not own partition 0: %v", err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for first.AssertOwned(0) == nil && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if err := first.AssertOwned(0); err == nil {
		t.Fatal("old static owner remained active after replacement acquired the same shard identity")
	}
}

func produceKafkaIntegrationCommand(t *testing.T, config KafkaConfig, commandID string, orderID string, partition int32) {
	t.Helper()
	producer, err := sarama.NewSyncProducer(kafkaBrokers(config), newKafkaClientConfig("reef-transaction-integration-command-producer"))
	if err != nil {
		t.Fatalf("open integration command producer: %v", err)
	}
	defer producer.Close()
	payload, err := json.Marshal(map[string]string{
		"commandId":     commandID,
		"occurredAt":    "2026-07-19T18:00:00Z",
		"orderId":       orderID,
		"instrumentId":  "STK001",
		"participantId": "participant-1",
		"accountId":     "account-1",
		"side":          "BUY",
		"orderType":     "LIMIT",
		"quantityUnits": "100",
		"limitPrice":    "100000000000",
		"currency":      "USD",
		"timeInForce":   "DAY",
	})
	if err != nil {
		t.Fatalf("encode integration command: %v", err)
	}
	_, _, err = producer.SendMessage(&sarama.ProducerMessage{
		Topic:     config.CommandTopic,
		Partition: partition,
		Value:     sarama.ByteEncoder(payload),
		Headers: []sarama.RecordHeader{{
			Key:   []byte(kafkaStreamSubjectHeader),
			Value: []byte("reef.cmd.v1.p00.session.STK001.SubmitOrder"),
		}},
	})
	if err != nil {
		t.Fatalf("publish integration command: %v", err)
	}
}

func kafkaIntegrationCommittedOffset(t *testing.T, config KafkaConfig, groupID string) int64 {
	t.Helper()
	admin, err := sarama.NewClusterAdmin(kafkaBrokers(config), newKafkaClientConfig("reef-transaction-integration-offset-reader"))
	if err != nil {
		t.Fatalf("open integration offset admin: %v", err)
	}
	defer admin.Close()
	response, err := admin.ListConsumerGroupOffsets(groupID, map[string][]int32{
		config.CommandTopic: {0},
	})
	if err != nil {
		t.Fatalf("read integration consumer group offset: %v", err)
	}
	block := response.GetBlock(config.CommandTopic, 0)
	if block == nil {
		t.Fatal("integration consumer group offset response omitted partition 0")
	}
	if block.Err != sarama.ErrNoError {
		t.Fatalf("integration consumer group offset error: %v", block.Err)
	}
	return block.Offset
}

func kafkaIntegrationReadCommittedCount(t *testing.T, config KafkaConfig, stopAfter int, timeout time.Duration) int {
	t.Helper()
	consumerConfig := newKafkaClientConfig("reef-transaction-integration-read-committed")
	consumerConfig.Consumer.IsolationLevel = sarama.ReadCommitted
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

	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	count := 0
	for count < stopAfter {
		select {
		case <-deadline.C:
			return count
		case _, ok := <-partitionConsumer.Messages():
			if !ok {
				return count
			}
			count++
		case consumerErr := <-partitionConsumer.Errors():
			if consumerErr != nil {
				t.Fatalf("read_committed consume: %v", consumerErr)
			}
		}
	}
	return count
}

func deleteKafkaIntegrationTopics(config KafkaConfig, topics ...string) {
	admin, err := sarama.NewClusterAdmin(kafkaBrokers(config), newKafkaClientConfig("reef-transaction-integration-cleanup"))
	if err != nil {
		return
	}
	defer admin.Close()
	for _, topic := range topics {
		_ = admin.DeleteTopic(topic)
	}
}
