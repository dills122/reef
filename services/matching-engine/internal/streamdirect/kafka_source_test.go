package streamdirect

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/IBM/sarama/mocks"
)

type fakePartitionOffsetManager struct {
	nextOffset   int64
	nextMetadata string
	markedOffset int64
	markedCalls  int
	closeErr     error
	closeCalled  bool
}

func (f *fakePartitionOffsetManager) NextOffset() (int64, string) {
	return f.nextOffset, f.nextMetadata
}

func (f *fakePartitionOffsetManager) MarkOffset(offset int64, metadata string) {
	f.markedOffset = offset
	f.markedCalls++
}

func (f *fakePartitionOffsetManager) ResetOffset(offset int64, metadata string) {}

func (f *fakePartitionOffsetManager) Errors() <-chan *sarama.ConsumerError {
	return make(chan *sarama.ConsumerError)
}

func (f *fakePartitionOffsetManager) AsyncClose() {}

func (f *fakePartitionOffsetManager) Close() error {
	f.closeCalled = true
	return f.closeErr
}

type fakeOffsetManager struct {
	commitCalls int
	closeErr    error
	closeCalled bool
}

func (f *fakeOffsetManager) ManagePartition(topic string, partition int32) (sarama.PartitionOffsetManager, error) {
	return &fakePartitionOffsetManager{}, nil
}

func (f *fakeOffsetManager) Commit() {
	f.commitCalls++
}

func (f *fakeOffsetManager) Close() error {
	f.closeCalled = true
	return f.closeErr
}

// fakeClient satisfies sarama.Client via embedding; only Close() is exercised
// by KafkaCommandSource.Close(), so every other method is left to panic on
// the nil embedded interface if ever (incorrectly) invoked by the code under test.
type fakeClient struct {
	sarama.Client
	closeErr    error
	closeCalled bool
}

type fakeReplayOffsetClient struct {
	sarama.Client
	earliest int64
	latest   int64
}

func (f *fakeReplayOffsetClient) GetOffset(_ string, _ int32, timestamp int64) (int64, error) {
	if timestamp == sarama.OffsetOldest {
		return f.earliest, nil
	}
	return f.latest, nil
}

func (f *fakeClient) Close() error {
	f.closeCalled = true
	return f.closeErr
}

type fakeExternalDelivery struct {
	acked bool
	err   error
}

type recordingTransactionalProducer struct {
	sarama.SyncProducer
	beginCalls  int
	commitCalls int
	abortCalls  int
	groupID     string
	offsets     map[string][]*sarama.PartitionOffsetMetadata
	commitErr   error
}

func (p *recordingTransactionalProducer) BeginTxn() error {
	p.beginCalls++
	return p.SyncProducer.BeginTxn()
}

func (p *recordingTransactionalProducer) CommitTxn() error {
	p.commitCalls++
	if p.commitErr != nil {
		return p.commitErr
	}
	return p.SyncProducer.CommitTxn()
}

func (p *recordingTransactionalProducer) AbortTxn() error {
	p.abortCalls++
	return p.SyncProducer.AbortTxn()
}

func (p *recordingTransactionalProducer) AddOffsetsToTxn(offsets map[string][]*sarama.PartitionOffsetMetadata, groupID string) error {
	p.offsets = offsets
	p.groupID = groupID
	return p.SyncProducer.AddOffsetsToTxn(offsets, groupID)
}

func (d *fakeExternalDelivery) Subject() string        { return "external" }
func (d *fakeExternalDelivery) Data() []byte           { return nil }
func (d *fakeExternalDelivery) StreamSequence() uint64 { return 0 }
func (d *fakeExternalDelivery) DeliveredCount() uint64 { return 1 }
func (d *fakeExternalDelivery) Ack() error {
	d.acked = true
	return d.err
}
func (d *fakeExternalDelivery) Nak() error  { return nil }
func (d *fakeExternalDelivery) Term() error { return nil }

func TestKafkaCommandSourceAckAndNak(t *testing.T) {
	pom := &fakePartitionOffsetManager{}
	om := &fakeOffsetManager{}
	source := &KafkaCommandSource{
		durableName:      "test-durable",
		partitionManager: pom,
		offsetManager:    om,
	}

	if err := source.ack(41); err != nil {
		t.Fatalf("ack failed: %v", err)
	}
	if pom.markedOffset != 42 || pom.markedCalls != 1 {
		t.Errorf("expected MarkOffset(42), got offset=%d calls=%d", pom.markedOffset, pom.markedCalls)
	}
	if om.commitCalls != 1 {
		t.Errorf("expected 1 commit call, got %d", om.commitCalls)
	}

	if err := source.nak(10); err != nil {
		t.Fatalf("nak failed: %v", err)
	}
	if source.rewindOffset == nil || *source.rewindOffset != 10 {
		t.Errorf("expected rewindOffset=10, got %v", source.rewindOffset)
	}

	// A lower nak offset should replace the rewind point; a higher one should not.
	if err := source.nak(5); err != nil {
		t.Fatalf("nak failed: %v", err)
	}
	if *source.rewindOffset != 5 {
		t.Errorf("expected rewindOffset=5 after lower nak, got %d", *source.rewindOffset)
	}
	if err := source.nak(99); err != nil {
		t.Fatalf("nak failed: %v", err)
	}
	if *source.rewindOffset != 5 {
		t.Errorf("expected rewindOffset to stay at 5 after higher nak, got %d", *source.rewindOffset)
	}

	source.closed = true
	if err := source.ack(1); err == nil {
		t.Error("expected ack on closed source to fail")
	}
	if err := source.nak(1); err == nil {
		t.Error("expected nak on closed source to fail")
	}
}

func TestKafkaCommandSourceAckBatch(t *testing.T) {
	pom := &fakePartitionOffsetManager{}
	om := &fakeOffsetManager{}
	source := &KafkaCommandSource{
		durableName:      "test-durable",
		partitionManager: pom,
		offsetManager:    om,
	}

	deliveries := []CommandDelivery{
		&kafkaDelivery{source: source, offset: 5},
		&kafkaDelivery{source: source, offset: 8},
		&kafkaDelivery{source: source, offset: 3},
	}
	acked, err := source.AckBatch(deliveries)
	if err != nil {
		t.Fatalf("AckBatch failed: %v", err)
	}
	if acked != 3 {
		t.Errorf("expected 3 acked, got %d", acked)
	}
	if pom.markedOffset != 9 {
		t.Errorf("expected MarkOffset(9) (max offset + 1), got %d", pom.markedOffset)
	}

	external := &fakeExternalDelivery{}
	acked, err = source.AckBatch([]CommandDelivery{external})
	if err != nil {
		t.Fatalf("AckBatch external delegate failed: %v", err)
	}
	if acked != 0 || !external.acked {
		t.Errorf("expected external delivery to be Ack()'d directly, acked=%d external.acked=%v", acked, external.acked)
	}

	source.closed = true
	if _, err := source.AckBatch(deliveries); err == nil {
		t.Error("expected AckBatch on closed source to fail")
	}
}

func TestKafkaCommandSourceClose(t *testing.T) {
	pom := &fakePartitionOffsetManager{}
	om := &fakeOffsetManager{}
	config := mocks.NewTestConfig()
	consumer := mocks.NewConsumer(t, config)
	t.Cleanup(func() { _ = consumer.Close() })

	client := &fakeClient{}
	source := &KafkaCommandSource{
		durableName:      "test-durable",
		partitionManager: pom,
		offsetManager:    om,
		consumer:         consumer,
		client:           client,
	}

	if err := source.Close(); err != nil {
		t.Fatalf("Close failed: %v", err)
	}
	if !source.closed {
		t.Error("expected closed=true")
	}
	if !pom.closeCalled || !om.closeCalled {
		t.Error("expected partitionManager and offsetManager to be closed")
	}
	if !client.closeCalled {
		t.Error("expected client to be closed")
	}
}

func TestKafkaCommandSourcePartitionConsumerLockedFailsWhenClosed(t *testing.T) {
	source := &KafkaCommandSource{durableName: "test-durable", closed: true}
	if _, err := source.partitionConsumerLocked(); err == nil {
		t.Error("expected error for closed source")
	}
}

func TestKafkaCommandSourceFetchYieldsMessages(t *testing.T) {
	config := mocks.NewTestConfig()
	consumer := mocks.NewConsumer(t, config)
	t.Cleanup(func() { _ = consumer.Close() })

	pc := consumer.ExpectConsumePartition("test-topic", 0, sarama.OffsetOldest)
	pc.YieldMessage(&sarama.ConsumerMessage{
		Value:     []byte(`{"hello":"world"}`),
		Partition: 0,
		Offset:    0,
	})

	pom := &fakePartitionOffsetManager{nextOffset: -1}
	source := &KafkaCommandSource{
		durableName:      "test-durable",
		consumer:         consumer,
		partitionManager: pom,
		config: KafkaConfig{
			CommandTopic:         "test-topic",
			CommandSubjectPrefix: "reef.cmd.v1",
			PartitionCount:       4,
			FetchTimeout:         500 * time.Millisecond,
		},
	}

	deliveries, err := source.Fetch(context.Background(), 1, 500*time.Millisecond)
	if err != nil {
		t.Fatalf("Fetch failed: %v", err)
	}
	if len(deliveries) != 1 {
		t.Fatalf("expected 1 delivery, got %d", len(deliveries))
	}
	if string(deliveries[0].Data()) != `{"hello":"world"}` {
		t.Errorf("unexpected delivery data: %s", deliveries[0].Data())
	}
}

func TestKafkaCommandSourceReplaysCompleteCommittedPrefixInBatches(t *testing.T) {
	config := mocks.NewTestConfig()
	consumer := mocks.NewConsumer(t, config)
	partitionConsumer := consumer.ExpectConsumePartition("test-commands", 0, sarama.OffsetOldest)
	for offset := int64(0); offset < 3; offset++ {
		partitionConsumer.YieldMessage(&sarama.ConsumerMessage{
			Topic:     "test-commands",
			Partition: 0,
			Offset:    offset,
			Value:     []byte(`{"commandId":"cmd"}`),
		})
	}
	source := &KafkaCommandSource{
		config:        KafkaConfig{CommandTopic: "test-commands", CommandSubjectPrefix: "reef.cmd.v1", PartitionCount: 4},
		partition:     0,
		durableName:   "test-durable",
		consumer:      consumer,
		client:        &fakeReplayOffsetClient{earliest: 0, latest: 3},
		nextOffset:    3,
		nextOffsetSet: true,
	}
	batchSizes := []int{}
	replayed, err := source.ReplayCommitted(context.Background(), 2, func(deliveries []CommandDelivery) error {
		batchSizes = append(batchSizes, len(deliveries))
		return nil
	})
	if err != nil {
		t.Fatalf("ReplayCommitted failed: %v", err)
	}
	if replayed != 3 || len(batchSizes) != 2 || batchSizes[0] != 2 || batchSizes[1] != 1 {
		t.Fatalf("unexpected replay count=%d batches=%v", replayed, batchSizes)
	}
	if err := consumer.Close(); err != nil {
		t.Fatalf("consumer close failed: %v", err)
	}
}

func TestKafkaCommandSourceReplayFailsClosedOnTruncatedHistory(t *testing.T) {
	source := &KafkaCommandSource{
		config:        KafkaConfig{CommandTopic: "test-commands", PartitionCount: 1},
		partition:     0,
		durableName:   "test-durable",
		client:        &fakeReplayOffsetClient{earliest: 1, latest: 2},
		nextOffset:    2,
		nextOffsetSet: true,
	}
	_, err := source.ReplayCommitted(context.Background(), 10, func([]CommandDelivery) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "requires complete history") {
		t.Fatalf("expected truncated-history failure, got %v", err)
	}
}

func TestKafkaCommandSourceFetchTimesOutWithNoMessages(t *testing.T) {
	config := mocks.NewTestConfig()
	consumer := mocks.NewConsumer(t, config)
	t.Cleanup(func() { _ = consumer.Close() })
	consumer.ExpectConsumePartition("test-topic", 0, sarama.OffsetOldest)

	pom := &fakePartitionOffsetManager{nextOffset: -1}
	source := &KafkaCommandSource{
		durableName:      "test-durable",
		consumer:         consumer,
		partitionManager: pom,
		config: KafkaConfig{
			CommandTopic:   "test-topic",
			PartitionCount: 4,
		},
	}

	deliveries, err := source.Fetch(context.Background(), 1, 20*time.Millisecond)
	if err != nil {
		t.Fatalf("Fetch failed: %v", err)
	}
	if len(deliveries) != 0 {
		t.Errorf("expected no deliveries on timeout, got %d", len(deliveries))
	}
}

func TestKafkaEventBatchPublisherPublishAndClose(t *testing.T) {
	config := mocks.NewTestConfig()
	producer := mocks.NewSyncProducer(t, config)
	producer.ExpectSendMessageAndSucceed()

	publisher := &KafkaEventBatchPublisher{
		config: KafkaConfig{
			EventTopic:         "test-events",
			EventSubjectPrefix: "reef.venue.events.v1",
			PartitionCount:     4,
		},
		producer: producer,
		client:   nil,
	}

	batch := VenueEventBatch{
		BatchID:   "batch-1",
		Partition: 0,
	}
	if err := publisher.PublishEventBatch(context.Background(), batch); err != nil {
		t.Fatalf("PublishEventBatch failed: %v", err)
	}

	if err := producer.Close(); err != nil {
		t.Fatalf("producer close failed: %v", err)
	}
}

func TestKafkaEventBatchPublisherPublishFails(t *testing.T) {
	config := mocks.NewTestConfig()
	producer := mocks.NewSyncProducer(t, config)
	producer.ExpectSendMessageAndFail(errors.New("boom"))

	publisher := &KafkaEventBatchPublisher{
		config: KafkaConfig{
			EventTopic:         "test-events",
			EventSubjectPrefix: "reef.venue.events.v1",
			PartitionCount:     4,
		},
		producer: producer,
	}

	if err := publisher.PublishEventBatch(context.Background(), VenueEventBatch{BatchID: "batch-1"}); err == nil {
		t.Error("expected publish error to propagate")
	}
	_ = producer.Close()
}

func TestKafkaEventBatchPublisherCommitsOutputAndCommandOffsetInOneTransaction(t *testing.T) {
	config := mocks.NewTestConfig()
	config.Producer.Transaction.ID = "test-lane-transaction"
	config.Producer.Idempotent = true
	config.Producer.RequiredAcks = sarama.WaitForAll
	config.Net.MaxOpenRequests = 1
	config.Version = sarama.V2_0_0_0
	baseProducer := mocks.NewSyncProducer(t, config)
	baseProducer.ExpectSendMessageAndSucceed()
	producer := &recordingTransactionalProducer{SyncProducer: baseProducer}
	pom := &fakePartitionOffsetManager{nextOffset: 40}
	source := &KafkaCommandSource{
		config:           KafkaConfig{CommandTopic: "test-commands"},
		partition:        2,
		durableName:      "reef-engine-direct-p02",
		partitionManager: pom,
		nextOffset:       40,
		nextOffsetSet:    true,
	}
	publisher := &KafkaEventBatchPublisher{
		config: KafkaConfig{
			CommandTopic:       "test-commands",
			EventTopic:         "test-events",
			EventSubjectPrefix: "reef.venue.events.v1",
			PartitionCount:     4,
		},
		producer: producer,
		source:   source,
	}
	deliveries := []CommandDelivery{
		&kafkaDelivery{source: source, partition: 2, offset: 40, streamSequence: kafkaStreamSequence(2, 40)},
		&kafkaDelivery{source: source, partition: 2, offset: 41, streamSequence: kafkaStreamSequence(2, 41)},
	}

	acked, err := publisher.PublishEventBatchAndAck(context.Background(), VenueEventBatch{
		BatchID:   "batch-atomic",
		Partition: 2,
	}, deliveries)
	if err != nil {
		t.Fatalf("PublishEventBatchAndAck failed: %v", err)
	}
	if acked != 2 || producer.beginCalls != 1 || producer.commitCalls != 1 || producer.abortCalls != 0 {
		t.Fatalf("unexpected transaction calls acked=%d begin=%d commit=%d abort=%d", acked, producer.beginCalls, producer.commitCalls, producer.abortCalls)
	}
	if producer.groupID != source.durableName {
		t.Fatalf("unexpected transaction group %q", producer.groupID)
	}
	metadata := producer.offsets["test-commands"]
	if len(metadata) != 1 || metadata[0].Partition != 2 || metadata[0].Offset != 42 {
		t.Fatalf("unexpected transactional offsets %#v", metadata)
	}
	if source.nextOffset != 42 || pom.markedCalls != 0 {
		t.Fatalf("expected transaction-only local offset 42, next=%d nontransactionalMarks=%d", source.nextOffset, pom.markedCalls)
	}
	if err := baseProducer.Close(); err != nil {
		t.Fatalf("producer close failed: %v", err)
	}
}

func TestKafkaEventBatchPublisherAbortsBeforeOffsetCommitFailure(t *testing.T) {
	config := mocks.NewTestConfig()
	config.Producer.Transaction.ID = "test-lane-transaction-abort"
	config.Producer.Idempotent = true
	config.Producer.RequiredAcks = sarama.WaitForAll
	config.Net.MaxOpenRequests = 1
	config.Version = sarama.V2_0_0_0
	baseProducer := mocks.NewSyncProducer(t, config)
	baseProducer.ExpectSendMessageAndSucceed()
	producer := &recordingTransactionalProducer{SyncProducer: baseProducer}
	source := &KafkaCommandSource{
		config:        KafkaConfig{CommandTopic: "test-commands"},
		partition:     0,
		durableName:   "reef-engine-direct-p00",
		nextOffset:    7,
		nextOffsetSet: true,
	}
	publisher := &KafkaEventBatchPublisher{
		config: KafkaConfig{
			CommandTopic:       "test-commands",
			EventTopic:         "test-events",
			EventSubjectPrefix: "reef.venue.events.v1",
			PartitionCount:     4,
		},
		producer: producer,
		source:   source,
		beforeOffsetCommit: func() error {
			return errors.New("injected offset failure")
		},
	}
	delivery := &kafkaDelivery{source: source, partition: 0, offset: 7, streamSequence: kafkaStreamSequence(0, 7)}

	if _, err := publisher.PublishEventBatchAndAck(context.Background(), VenueEventBatch{BatchID: "batch-abort"}, []CommandDelivery{delivery}); err == nil {
		t.Fatal("expected transaction failure")
	}
	if producer.beginCalls != 1 || producer.commitCalls != 0 || producer.abortCalls != 1 {
		t.Fatalf("unexpected transaction calls begin=%d commit=%d abort=%d", producer.beginCalls, producer.commitCalls, producer.abortCalls)
	}
	if source.nextOffset != 7 {
		t.Fatalf("aborted transaction advanced local offset to %d", source.nextOffset)
	}
	if err := baseProducer.Close(); err != nil {
		t.Fatalf("producer close failed: %v", err)
	}
}

func TestKafkaTransactionalClientConfigFencesStableLaneAndDisablesAutoCommit(t *testing.T) {
	cfg := newKafkaTransactionalClientConfig("test-client", "reef-engine-direct-p03-venue-events-v1")
	if cfg.Producer.Transaction.ID != "reef-engine-direct-p03-venue-events-v1" {
		t.Fatalf("unexpected transactional id %q", cfg.Producer.Transaction.ID)
	}
	if cfg.Consumer.Offsets.AutoCommit.Enable {
		t.Fatal("transactional command consumer must not auto-commit offsets")
	}
	if !cfg.Producer.Idempotent || cfg.Net.MaxOpenRequests != 1 {
		t.Fatal("transactional producer must retain idempotent ordering configuration")
	}
}

func TestKafkaEventBatchPublisherClose(t *testing.T) {
	config := mocks.NewTestConfig()
	producer := mocks.NewSyncProducer(t, config)
	client := &fakeClient{}

	publisher := &KafkaEventBatchPublisher{
		producer: producer,
		client:   client,
	}

	if err := publisher.Close(); err != nil {
		t.Fatalf("Close failed: %v", err)
	}
	if !client.closeCalled {
		t.Error("expected client to be closed")
	}
}
