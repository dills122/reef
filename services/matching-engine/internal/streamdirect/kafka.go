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
	closed            bool
}

type KafkaEventBatchPublisher struct {
	config   KafkaConfig
	client   sarama.Client
	producer sarama.SyncProducer
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
	return &KafkaCommandSource{
		config:           config,
		partition:        int32(partition),
		durableName:      durableName,
		client:           client,
		consumer:         consumer,
		offsetManager:    offsetManager,
		partitionManager: partitionManager,
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

func (s *KafkaCommandSource) Fetch(ctx context.Context, batchSize int, timeout time.Duration) ([]CommandDelivery, error) {
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
			subject := kafkaSubject(msg.Headers)
			if subject == "" {
				subject = fmt.Sprintf("%s.%s._._.SubmitOrder", trimDot(s.config.CommandSubjectPrefix), PartitionToken(s.config.PartitionCount, int(msg.Partition)))
			}
			deliveries = append(deliveries, &kafkaDelivery{
				source:         s,
				subject:        subject,
				data:           msg.Value,
				partition:      msg.Partition,
				offset:         msg.Offset,
				streamSequence: kafkaStreamSequence(msg.Partition, msg.Offset),
			})
		case err, ok := <-partitionConsumer.Errors():
			if ok && err != nil {
				return deliveries, err
			}
		}
	}
	return deliveries, nil
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
	if s.closed {
		return 0, fmt.Errorf("kafka command source %s is closed", s.durableName)
	}
	s.partitionManager.MarkOffset(nextOffset, "")
	s.offsetManager.Commit()
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
	recordErr(s.partitionManager.Close())
	recordErr(s.offsetManager.Close())
	recordErr(s.consumer.Close())
	recordErr(s.client.Close())
	return firstErr
}

func (s *KafkaCommandSource) ack(offset int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("kafka command source %s is closed", s.durableName)
	}
	s.partitionManager.MarkOffset(offset+1, "")
	s.offsetManager.Commit()
	return nil
}

func (s *KafkaCommandSource) nak(offset int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("kafka command source %s is closed", s.durableName)
	}
	if s.rewindOffset == nil || offset < *s.rewindOffset {
		rewind := offset
		s.rewindOffset = &rewind
	}
	return nil
}

func (s *KafkaCommandSource) partitionConsumerLocked() (sarama.PartitionConsumer, error) {
	if s.closed {
		return nil, fmt.Errorf("kafka command source %s is closed", s.durableName)
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
	} else if nextOffset, _ := s.partitionManager.NextOffset(); nextOffset >= 0 {
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
	payload, err := json.Marshal(batch)
	if err != nil {
		return err
	}
	subject := fmt.Sprintf("%s.%s.%s", trimDot(p.config.EventSubjectPrefix), PartitionToken(p.config.PartitionCount, batch.Partition), "VenueEventBatch")
	timestamp := time.Now().UTC()
	if parsed, parseErr := time.Parse(time.RFC3339Nano, batch.CreatedAt); parseErr == nil {
		timestamp = parsed
	}
	_, _, err = p.producer.SendMessage(&sarama.ProducerMessage{
		Topic:     p.config.EventTopic,
		Key:       sarama.StringEncoder(batch.BatchID),
		Value:     sarama.ByteEncoder(payload),
		Partition: int32(batch.Partition),
		Timestamp: timestamp,
		Headers: []sarama.RecordHeader{
			{Key: []byte(kafkaStreamSubjectHeader), Value: []byte(subject)},
		},
	})
	return err
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
	return config
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
