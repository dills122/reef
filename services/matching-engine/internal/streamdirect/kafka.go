package streamdirect

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/IBM/sarama"
)

const kafkaStreamSubjectHeader = "reef-stream-subject"
const kafkaOffsetSequenceBits = 48
const kafkaOffsetSequenceMask = (uint64(1) << kafkaOffsetSequenceBits) - 1

type KafkaConfig struct {
	BootstrapServers     string
	CommandTopic         string
	CommandSubjectPrefix string
	EventTopic           string
	EventSubjectPrefix   string
	OwnershipTopic       string
	OwnershipGroup       string
	PartitionCount       int
	FetchTimeout         time.Duration
}

type KafkaCommandSource struct {
	config            KafkaConfig
	partition         int32
	durableName       string
	client            sarama.Client
	consumer          sarama.Consumer
	offsetManager     sarama.OffsetManager
	partitionManager  sarama.PartitionOffsetManager
	mu                sync.Mutex
	partitionConsumer sarama.PartitionConsumer
	rewindOffset      *int64
	nextOffset        int64
	nextOffsetSet     bool
	closed            bool
	ownership         *KafkaOwnershipLease
}

type KafkaEventBatchPublisher struct {
	config   KafkaConfig
	client   sarama.Client
	producer sarama.SyncProducer
	source   *KafkaCommandSource

	beforeOffsetCommit func() error
}

type kafkaDelivery struct {
	source         *KafkaCommandSource
	subject        string
	data           []byte
	partition      int32
	offset         int64
	streamSequence uint64
}

func EnsureKafkaTopic(config KafkaConfig, topic string) error {
	topic = strings.TrimSpace(topic)
	if topic == "" {
		return nil
	}
	admin, err := sarama.NewClusterAdmin(kafkaBrokers(config), newKafkaClientConfig("reef-matching-engine-admin"))
	if err != nil {
		return err
	}
	defer admin.Close()

	metadata, err := admin.DescribeTopics([]string{topic})
	if err == nil && len(metadata) == 1 && metadata[0].Err == sarama.ErrNoError {
		existing := int32(len(metadata[0].Partitions))
		target := int32(config.PartitionCount)
		if target > existing {
			if err := admin.CreatePartitions(topic, target, nil, false); err != nil && !isKafkaAlreadyEnoughPartitions(err) {
				return err
			}
		}
		return nil
	}

	err = admin.CreateTopic(topic, &sarama.TopicDetail{
		NumPartitions:     int32(config.PartitionCount),
		ReplicationFactor: 1,
	}, false)
	if err != nil && !isKafkaTopicExists(err) {
		return err
	}
	return nil
}

func NewKafkaCommandSource(config KafkaConfig, partition int, durableName string) (*KafkaCommandSource, error) {
	client, err := sarama.NewClient(kafkaBrokers(config), newKafkaClientConfig("reef-matching-engine-direct-consumer"))
	if err != nil {
		return nil, err
	}
	consumer, err := sarama.NewConsumerFromClient(client)
	if err != nil {
		client.Close()
		return nil, err
	}
	offsetManager, err := sarama.NewOffsetManagerFromClient(durableName, client)
	if err != nil {
		consumer.Close()
		client.Close()
		return nil, err
	}
	partitionManager, err := offsetManager.ManagePartition(config.CommandTopic, int32(partition))
	if err != nil {
		offsetManager.Close()
		consumer.Close()
		client.Close()
		return nil, err
	}
	nextOffset, _ := partitionManager.NextOffset()
	return &KafkaCommandSource{
		config:           config,
		partition:        int32(partition),
		durableName:      durableName,
		client:           client,
		consumer:         consumer,
		offsetManager:    offsetManager,
		partitionManager: partitionManager,
		nextOffset:       nextOffset,
		nextOffsetSet:    true,
	}, nil
}

func NewKafkaEventBatchPublisher(config KafkaConfig) (*KafkaEventBatchPublisher, error) {
	client, err := sarama.NewClient(kafkaBrokers(config), newKafkaClientConfig("reef-matching-engine-direct-producer"))
	if err != nil {
		return nil, err
	}
	producer, err := sarama.NewSyncProducerFromClient(client)
	if err != nil {
		client.Close()
		return nil, err
	}
	return &KafkaEventBatchPublisher{
		config:   config,
		client:   client,
		producer: producer,
	}, nil
}

func NewKafkaTransactionalEventBatchPublisher(config KafkaConfig, source *KafkaCommandSource) (*KafkaEventBatchPublisher, error) {
	if source == nil {
		return nil, fmt.Errorf("kafka transactional event publisher requires a command source")
	}
	transactionalID := kafkaTransactionalID(source.durableName)
	clientConfig := newKafkaTransactionalClientConfig(
		fmt.Sprintf("reef-matching-engine-transactional-%s", PartitionToken(config.PartitionCount, int(source.partition))),
		transactionalID,
	)
	client, err := sarama.NewClient(kafkaBrokers(config), clientConfig)
	if err != nil {
		return nil, err
	}
	producer, err := sarama.NewSyncProducerFromClient(client)
	if err != nil {
		client.Close()
		return nil, err
	}
	if !producer.IsTransactional() {
		producer.Close()
		client.Close()
		return nil, fmt.Errorf("kafka producer %s is not transactional", transactionalID)
	}
	return &KafkaEventBatchPublisher{
		config:   config,
		client:   client,
		producer: producer,
		source:   source,
	}, nil
}

// assertOwned checks the source's ownership lease (if any) for its
// partition, returning an error if ownership no longer holds.
func (s *KafkaCommandSource) assertOwned() error {
	if s.ownership != nil {
		if err := s.ownership.AssertOwned(s.partition); err != nil {
			return err
		}
	}
	return nil
}

// errIfClosedLocked returns an error if the source has been closed. Callers
// must hold s.mu.
func (s *KafkaCommandSource) errIfClosedLocked() error {
	if s.closed {
		return fmt.Errorf("kafka command source %s is closed", s.durableName)
	}
	return nil
}

func (s *KafkaCommandSource) Fetch(ctx context.Context, batchSize int, timeout time.Duration) ([]CommandDelivery, error) {
	if err := s.assertOwned(); err != nil {
		return nil, err
	}
	if batchSize <= 0 {
		batchSize = 1
	}
	if timeout <= 0 {
		timeout = s.config.FetchTimeout
	}
	if timeout <= 0 {
		timeout = 200 * time.Millisecond
	}

	s.mu.Lock()
	partitionConsumer, err := s.partitionConsumerLocked()
	s.mu.Unlock()
	if err != nil {
		return nil, err
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	deliveries := make([]CommandDelivery, 0, batchSize)
	for len(deliveries) < batchSize {
		select {
		case <-ctx.Done():
			return deliveries, ctx.Err()
		case <-timer.C:
			return deliveries, nil
		case msg, ok := <-partitionConsumer.Messages():
			if !ok {
				s.mu.Lock()
				s.closePartitionConsumerLocked()
				s.mu.Unlock()
				return deliveries, nil
			}
			deliveries = append(deliveries, s.deliveryFromMessage(msg))
		case err, ok := <-partitionConsumer.Errors():
			if ok && err != nil {
				return deliveries, err
			}
		}
	}
	return deliveries, nil
}

func (s *KafkaCommandSource) ReplayCommitted(ctx context.Context, batchSize int, apply func([]CommandDelivery) error) (int, error) {
	if err := s.assertOwned(); err != nil {
		return 0, err
	}
	if batchSize <= 0 {
		batchSize = 1
	}
	s.mu.Lock()
	if err := s.errIfClosedLocked(); err != nil {
		s.mu.Unlock()
		return 0, err
	}
	if s.partitionConsumer != nil {
		s.mu.Unlock()
		return 0, fmt.Errorf("kafka command source %s cannot replay after live consumption starts", s.durableName)
	}
	targetOffset := s.currentNextOffsetLocked()
	s.mu.Unlock()
	if targetOffset <= 0 {
		return 0, nil
	}
	earliestOffset, err := s.client.GetOffset(s.config.CommandTopic, s.partition, sarama.OffsetOldest)
	if err != nil {
		return 0, fmt.Errorf("read Kafka recovery earliest offset partition=%d: %w", s.partition, err)
	}
	if earliestOffset != 0 {
		return 0, fmt.Errorf("Kafka recovery partition=%d requires complete history from offset 0; earliest available offset is %d", s.partition, earliestOffset)
	}
	latestOffset, err := s.client.GetOffset(s.config.CommandTopic, s.partition, sarama.OffsetNewest)
	if err != nil {
		return 0, fmt.Errorf("read Kafka recovery latest offset partition=%d: %w", s.partition, err)
	}
	if targetOffset > latestOffset {
		return 0, fmt.Errorf("Kafka recovery partition=%d committed offset %d exceeds latest offset %d", s.partition, targetOffset, latestOffset)
	}

	replayConsumer, err := s.consumer.ConsumePartition(s.config.CommandTopic, s.partition, sarama.OffsetOldest)
	if err != nil {
		return 0, fmt.Errorf("open Kafka recovery consumer partition=%d: %w", s.partition, err)
	}
	defer replayConsumer.Close()

	expectedOffset := int64(0)
	replayed := 0
	batch := make([]CommandDelivery, 0, batchSize)
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		if err := s.assertOwned(); err != nil {
			return err
		}
		if err := apply(batch); err != nil {
			return err
		}
		replayed += len(batch)
		batch = make([]CommandDelivery, 0, batchSize)
		return nil
	}

	for expectedOffset < targetOffset {
		select {
		case <-ctx.Done():
			return replayed, fmt.Errorf("Kafka recovery partition=%d stopped at offset %d of %d: %w", s.partition, expectedOffset, targetOffset, ctx.Err())
		case message, ok := <-replayConsumer.Messages():
			if !ok {
				return replayed, fmt.Errorf("Kafka recovery partition=%d ended at offset %d before committed offset %d", s.partition, expectedOffset, targetOffset)
			}
			if message.Offset != expectedOffset {
				return replayed, fmt.Errorf("Kafka recovery partition=%d requires complete history: expected offset %d, received %d", s.partition, expectedOffset, message.Offset)
			}
			batch = append(batch, s.deliveryFromMessage(message))
			expectedOffset++
			if len(batch) == batchSize {
				if err := flush(); err != nil {
					return replayed, fmt.Errorf("apply Kafka recovery partition=%d ending offset=%d: %w", s.partition, expectedOffset-1, err)
				}
			}
		case consumerErr, ok := <-replayConsumer.Errors():
			if ok && consumerErr != nil {
				return replayed, fmt.Errorf("Kafka recovery partition=%d: %w", s.partition, consumerErr)
			}
		}
	}
	if err := flush(); err != nil {
		return replayed, fmt.Errorf("apply Kafka recovery partition=%d ending offset=%d: %w", s.partition, expectedOffset-1, err)
	}
	return replayed, nil
}

func (s *KafkaCommandSource) deliveryFromMessage(message *sarama.ConsumerMessage) CommandDelivery {
	subject := kafkaSubject(message.Headers)
	if subject == "" {
		subject = fmt.Sprintf("%s.%s._._.SubmitOrder", trimDot(s.config.CommandSubjectPrefix), PartitionToken(s.config.PartitionCount, int(message.Partition)))
	}
	return &kafkaDelivery{
		source:         s,
		subject:        subject,
		data:           message.Value,
		partition:      message.Partition,
		offset:         message.Offset,
		streamSequence: kafkaStreamSequence(message.Partition, message.Offset),
	}
}

func (s *KafkaCommandSource) AckBatch(deliveries []CommandDelivery) (int, error) {
	offsets := make([]int64, 0, len(deliveries))
	for _, delivery := range deliveries {
		kafkaMsg, ok := delivery.(*kafkaDelivery)
		if !ok || kafkaMsg.source != s {
			if err := delivery.Ack(); err != nil {
				return len(offsets), err
			}
			continue
		}
		offsets = append(offsets, kafkaMsg.offset)
	}
	if len(offsets) == 0 {
		return 0, nil
	}
	sort.Slice(offsets, func(i, j int) bool { return offsets[i] < offsets[j] })
	nextOffset := offsets[len(offsets)-1] + 1
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.errIfClosedLocked(); err != nil {
		return 0, err
	}
	s.partitionManager.MarkOffset(nextOffset, "")
	s.offsetManager.Commit()
	s.nextOffset = nextOffset
	s.nextOffsetSet = true
	return len(offsets), nil
}

func (s *KafkaCommandSource) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	var firstErr error
	recordErr := func(err error) {
		if err != nil && firstErr == nil {
			firstErr = err
		}
	}
	recordErr(s.closePartitionConsumerLocked())
	// With Sarama auto-commit disabled, PartitionOffsetManager.Close waits for
	// its errors channel to be closed by the parent OffsetManager. Mark the
	// partition done first, close the parent, then drain the partition errors.
	s.partitionManager.AsyncClose()
	recordErr(s.offsetManager.Close())
	recordErr(s.partitionManager.Close())
	recordErr(s.consumer.Close())
	recordErr(s.client.Close())
	return firstErr
}

func (s *KafkaCommandSource) ack(offset int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.errIfClosedLocked(); err != nil {
		return err
	}
	s.partitionManager.MarkOffset(offset+1, "")
	s.offsetManager.Commit()
	s.nextOffset = offset + 1
	s.nextOffsetSet = true
	return nil
}

func (s *KafkaCommandSource) nak(offset int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.errIfClosedLocked(); err != nil {
		return err
	}
	if s.rewindOffset == nil || offset < *s.rewindOffset {
		rewind := offset
		s.rewindOffset = &rewind
	}
	return nil
}

func (s *KafkaCommandSource) partitionConsumerLocked() (sarama.PartitionConsumer, error) {
	if err := s.errIfClosedLocked(); err != nil {
		return nil, err
	}
	if s.partitionConsumer != nil && s.rewindOffset == nil {
		return s.partitionConsumer, nil
	}
	if err := s.closePartitionConsumerLocked(); err != nil {
		return nil, err
	}
	offset := sarama.OffsetOldest
	if s.rewindOffset != nil {
		offset = *s.rewindOffset
		s.rewindOffset = nil
	} else if nextOffset := s.currentNextOffsetLocked(); nextOffset >= 0 {
		offset = nextOffset
	}
	partitionConsumer, err := s.consumer.ConsumePartition(s.config.CommandTopic, s.partition, offset)
	if err != nil {
		return nil, err
	}
	s.partitionConsumer = partitionConsumer
	return partitionConsumer, nil
}

func (s *KafkaCommandSource) closePartitionConsumerLocked() error {
	if s.partitionConsumer == nil {
		return nil
	}
	err := s.partitionConsumer.Close()
	s.partitionConsumer = nil
	return err
}

func (p *KafkaEventBatchPublisher) PublishEventBatch(ctx context.Context, batch VenueEventBatch) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}
	message, err := p.eventBatchMessage(batch)
	if err != nil {
		return err
	}
	_, _, err = p.producer.SendMessage(message)
	return err
}

func (p *KafkaEventBatchPublisher) PublishEventBatchAndAck(ctx context.Context, batch VenueEventBatch, deliveries []CommandDelivery) (int, error) {
	if p.source == nil || !p.producer.IsTransactional() {
		return 0, &fatalProcessorError{
			cause: fmt.Errorf("kafka event publisher is not configured for atomic publish and offset commit"),
		}
	}
	select {
	case <-ctx.Done():
		return 0, ctx.Err()
	default:
	}
	if err := p.source.assertOwned(); err != nil {
		return 0, err
	}

	nextOffset, err := p.source.transactionNextOffset(deliveries)
	if err != nil {
		return 0, &fatalProcessorError{cause: err}
	}
	message, err := p.eventBatchMessage(batch)
	if err != nil {
		return 0, err
	}
	if err := p.producer.BeginTxn(); err != nil {
		return 0, &fatalProcessorError{cause: fmt.Errorf("begin Kafka transaction: %w", err)}
	}
	abort := func(cause error) error {
		if abortErr := p.producer.AbortTxn(); abortErr != nil {
			return &fatalProcessorError{
				cause: fmt.Errorf("%v; abort Kafka transaction: %w", cause, abortErr),
			}
		}
		return cause
	}

	if _, _, err := p.producer.SendMessage(message); err != nil {
		return 0, abort(fmt.Errorf("publish venue event batch in Kafka transaction: %w", err))
	}
	if p.beforeOffsetCommit != nil {
		if err := p.beforeOffsetCommit(); err != nil {
			return 0, abort(err)
		}
	}
	offsets := map[string][]*sarama.PartitionOffsetMetadata{
		p.config.CommandTopic: {
			{
				Partition: p.source.partition,
				Offset:    nextOffset,
			},
		},
	}
	if err := p.producer.AddOffsetsToTxn(offsets, p.source.durableName); err != nil {
		return 0, abort(fmt.Errorf("add command offsets to Kafka transaction: %w", err))
	}
	if err := p.source.assertOwned(); err != nil {
		return 0, abort(err)
	}
	if err := p.producer.CommitTxn(); err != nil {
		return 0, &fatalProcessorError{
			cause:             fmt.Errorf("Kafka transaction commit outcome is indeterminate: %w", err),
			preserveMutations: true,
		}
	}
	p.source.recordTransactionCommitted(nextOffset)
	return len(deliveries), nil
}

func (p *KafkaEventBatchPublisher) eventBatchMessage(batch VenueEventBatch) (*sarama.ProducerMessage, error) {
	payload, err := json.Marshal(batch)
	if err != nil {
		return nil, err
	}
	subject := fmt.Sprintf("%s.%s.%s", trimDot(p.config.EventSubjectPrefix), PartitionToken(p.config.PartitionCount, batch.Partition), "VenueEventBatch")
	timestamp := time.Now().UTC()
	if parsed, parseErr := time.Parse(time.RFC3339Nano, batch.CreatedAt); parseErr == nil {
		timestamp = parsed
	}
	return &sarama.ProducerMessage{
		Topic:     p.config.EventTopic,
		Key:       sarama.StringEncoder(batch.BatchID),
		Value:     sarama.ByteEncoder(payload),
		Partition: int32(batch.Partition),
		Timestamp: timestamp,
		Headers: []sarama.RecordHeader{
			{Key: []byte(kafkaStreamSubjectHeader), Value: []byte(subject)},
		},
	}, nil
}

func (p *KafkaEventBatchPublisher) Close() error {
	var firstErr error
	if err := p.producer.Close(); err != nil {
		firstErr = err
	}
	if err := p.client.Close(); err != nil && firstErr == nil {
		firstErr = err
	}
	return firstErr
}

func (d *kafkaDelivery) Subject() string {
	return d.subject
}

func (d *kafkaDelivery) Data() []byte {
	return d.data
}

func (d *kafkaDelivery) StreamSequence() uint64 {
	return d.streamSequence
}

func (d *kafkaDelivery) DeliveredCount() uint64 {
	return 1
}

func (d *kafkaDelivery) Ack() error {
	return d.source.ack(d.offset)
}

func (d *kafkaDelivery) Nak() error {
	return d.source.nak(d.offset)
}

func (d *kafkaDelivery) Term() error {
	return d.Ack()
}

func (s *KafkaCommandSource) transactionNextOffset(deliveries []CommandDelivery) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.errIfClosedLocked(); err != nil {
		return 0, err
	}
	if len(deliveries) == 0 {
		return 0, fmt.Errorf("Kafka transaction requires at least one command delivery")
	}
	offsets := make([]int64, 0, len(deliveries))
	for _, delivery := range deliveries {
		kafkaMessage, ok := delivery.(*kafkaDelivery)
		if !ok || kafkaMessage.source != s || kafkaMessage.partition != s.partition {
			return 0, fmt.Errorf("Kafka transaction received a delivery outside partition %d ownership", s.partition)
		}
		offsets = append(offsets, kafkaMessage.offset)
	}
	sort.Slice(offsets, func(i, j int) bool { return offsets[i] < offsets[j] })
	for index := 1; index < len(offsets); index++ {
		if offsets[index] != offsets[index-1]+1 {
			return 0, fmt.Errorf("Kafka transaction command offsets are not contiguous: %d then %d", offsets[index-1], offsets[index])
		}
	}
	expectedOffset := s.currentNextOffsetLocked()
	if expectedOffset >= 0 && offsets[0] != expectedOffset {
		return 0, fmt.Errorf("Kafka transaction expected command offset %d, received %d", expectedOffset, offsets[0])
	}
	return offsets[len(offsets)-1] + 1, nil
}

func (s *KafkaCommandSource) recordTransactionCommitted(nextOffset int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nextOffset = nextOffset
	s.nextOffsetSet = true
}

func (s *KafkaCommandSource) currentNextOffsetLocked() int64 {
	if s.nextOffsetSet {
		return s.nextOffset
	}
	if s.partitionManager == nil {
		return sarama.OffsetOldest
	}
	nextOffset, _ := s.partitionManager.NextOffset()
	s.nextOffset = nextOffset
	s.nextOffsetSet = true
	return nextOffset
}

func kafkaBrokers(config KafkaConfig) []string {
	raw := strings.TrimSpace(config.BootstrapServers)
	if raw == "" {
		raw = "localhost:9092"
	}
	parts := strings.Split(raw, ",")
	brokers := make([]string, 0, len(parts))
	for _, part := range parts {
		if broker := strings.TrimSpace(part); broker != "" {
			brokers = append(brokers, broker)
		}
	}
	return brokers
}

func newKafkaClientConfig(clientID string) *sarama.Config {
	config := sarama.NewConfig()
	config.ClientID = clientID
	config.Version = sarama.V2_0_0_0
	config.Net.MaxOpenRequests = 1
	config.Producer.RequiredAcks = sarama.WaitForAll
	config.Producer.Idempotent = true
	config.Producer.Return.Successes = true
	config.Producer.Return.Errors = true
	config.Producer.Retry.Max = envInt("STREAM_ACK_KAFKA_RETRIES", 10)
	config.Producer.Flush.Frequency = time.Duration(envInt("STREAM_ACK_KAFKA_LINGER_MS", 1)) * time.Millisecond
	config.Producer.Flush.Bytes = envInt("STREAM_ACK_KAFKA_BATCH_SIZE", 65536)
	config.Producer.Compression = kafkaCompression(engineKafkaCompressionType())
	config.Consumer.Return.Errors = true
	config.Consumer.Offsets.Initial = sarama.OffsetOldest
	config.Consumer.Offsets.AutoCommit.Enable = false
	return config
}

func newKafkaTransactionalClientConfig(clientID string, transactionalID string) *sarama.Config {
	config := newKafkaClientConfig(clientID)
	config.Producer.Transaction.ID = transactionalID
	config.Producer.Transaction.Timeout = time.Duration(envInt("MATCHING_ENGINE_KAFKA_TRANSACTION_TIMEOUT_MS", 60000)) * time.Millisecond
	return config
}

func kafkaTransactionalID(durableName string) string {
	return strings.TrimSpace(durableName) + "-venue-events-v1"
}

func kafkaCompression(value string) sarama.CompressionCodec {
	switch strings.TrimSpace(strings.ToLower(value)) {
	case "none", "off", "uncompressed":
		return sarama.CompressionNone
	case "gzip":
		return sarama.CompressionGZIP
	case "snappy":
		return sarama.CompressionSnappy
	case "zstd":
		return sarama.CompressionZSTD
	default:
		return sarama.CompressionLZ4
	}
}

func engineKafkaCompressionType() string {
	if value := envString("MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE", ""); value != "" {
		return value
	}
	return envString("STREAM_ACK_KAFKA_COMPRESSION_TYPE", "lz4")
}

func kafkaSubject(headers []*sarama.RecordHeader) string {
	for i := len(headers) - 1; i >= 0; i-- {
		if string(headers[i].Key) == kafkaStreamSubjectHeader {
			return string(headers[i].Value)
		}
	}
	return ""
}

func kafkaStreamSequence(partition int32, offset int64) uint64 {
	if partition < 0 || offset < 0 {
		return 0
	}
	logicalOffset := uint64(offset + 1)
	if logicalOffset > kafkaOffsetSequenceMask {
		return 0
	}
	return (uint64(partition) << kafkaOffsetSequenceBits) | logicalOffset
}

func isKafkaTopicExists(err error) bool {
	if err == nil {
		return false
	}
	return strings.Contains(strings.ToLower(err.Error()), "topic already exists")
}

func isKafkaAlreadyEnoughPartitions(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "invalid partitions") || strings.Contains(message, "already")
}
