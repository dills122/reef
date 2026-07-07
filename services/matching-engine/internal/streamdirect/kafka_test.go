package streamdirect

import (
	"errors"
	"testing"

	"github.com/IBM/sarama"
)

func TestKafkaBrokers(t *testing.T) {
	if got := kafkaBrokers(KafkaConfig{BootstrapServers: ""}); len(got) != 1 || got[0] != "localhost:9092" {
		t.Errorf("kafkaBrokers default = %v", got)
	}
	got := kafkaBrokers(KafkaConfig{BootstrapServers: " broker-1:9092 , broker-2:9092,,"})
	want := []string{"broker-1:9092", "broker-2:9092"}
	if len(got) != len(want) {
		t.Fatalf("kafkaBrokers = %v, want %v", got, want)
	}
	for i, w := range want {
		if got[i] != w {
			t.Errorf("kafkaBrokers[%d] = %s, want %s", i, got[i], w)
		}
	}
}

func TestNewKafkaClientConfig(t *testing.T) {
	cfg := newKafkaClientConfig("client-1")
	if cfg.ClientID != "client-1" {
		t.Errorf("ClientID = %s", cfg.ClientID)
	}
	if !cfg.Producer.Idempotent {
		t.Error("expected idempotent producer")
	}
	if cfg.Consumer.Offsets.Initial != sarama.OffsetOldest {
		t.Error("expected OffsetOldest consumer default")
	}
}

func TestKafkaCompression(t *testing.T) {
	cases := map[string]sarama.CompressionCodec{
		"none":          sarama.CompressionNone,
		"off":           sarama.CompressionNone,
		"uncompressed":  sarama.CompressionNone,
		"gzip":          sarama.CompressionGZIP,
		"snappy":        sarama.CompressionSnappy,
		"zstd":          sarama.CompressionZSTD,
		"lz4":           sarama.CompressionLZ4,
		"unknown-value": sarama.CompressionLZ4,
		"  GZIP  ":      sarama.CompressionGZIP,
	}
	for input, want := range cases {
		if got := kafkaCompression(input); got != want {
			t.Errorf("kafkaCompression(%q) = %v, want %v", input, got, want)
		}
	}
}

func TestEngineKafkaCompressionType(t *testing.T) {
	withEnv(t, "MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE", "")
	withEnv(t, "STREAM_ACK_KAFKA_COMPRESSION_TYPE", "")
	if got := engineKafkaCompressionType(); got != "lz4" {
		t.Errorf("engineKafkaCompressionType default = %q", got)
	}

	withEnv(t, "STREAM_ACK_KAFKA_COMPRESSION_TYPE", "gzip")
	if got := engineKafkaCompressionType(); got != "gzip" {
		t.Errorf("engineKafkaCompressionType stream-ack override = %q", got)
	}

	withEnv(t, "MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE", "snappy")
	if got := engineKafkaCompressionType(); got != "snappy" {
		t.Errorf("engineKafkaCompressionType engine override = %q", got)
	}
}

func TestKafkaSubject(t *testing.T) {
	headers := []*sarama.RecordHeader{
		{Key: []byte("other"), Value: []byte("ignored")},
		{Key: []byte(kafkaStreamSubjectHeader), Value: []byte("reef.cmd.v1.p00")},
	}
	if got := kafkaSubject(headers); got != "reef.cmd.v1.p00" {
		t.Errorf("kafkaSubject = %q", got)
	}
	if got := kafkaSubject(nil); got != "" {
		t.Errorf("kafkaSubject empty = %q", got)
	}
}

func TestKafkaStreamSequence(t *testing.T) {
	if got := kafkaStreamSequence(-1, 5); got != 0 {
		t.Errorf("kafkaStreamSequence negative partition = %d", got)
	}
	if got := kafkaStreamSequence(1, -1); got != 0 {
		t.Errorf("kafkaStreamSequence negative offset = %d", got)
	}
	got := kafkaStreamSequence(2, 10)
	want := (uint64(2) << kafkaOffsetSequenceBits) | uint64(11)
	if got != want {
		t.Errorf("kafkaStreamSequence = %d, want %d", got, want)
	}
	if got := kafkaStreamSequence(0, int64(kafkaOffsetSequenceMask)+1); got != 0 {
		t.Errorf("kafkaStreamSequence overflow should be 0, got %d", got)
	}
}

func TestIsKafkaTopicExists(t *testing.T) {
	if isKafkaTopicExists(nil) {
		t.Error("nil error should not be topic exists")
	}
	if !isKafkaTopicExists(errors.New("Topic Already Exists")) {
		t.Error("expected topic-exists error to match")
	}
	if isKafkaTopicExists(errors.New("some other error")) {
		t.Error("unrelated error should not match")
	}
}

func TestIsKafkaAlreadyEnoughPartitions(t *testing.T) {
	if isKafkaAlreadyEnoughPartitions(nil) {
		t.Error("nil error should not match")
	}
	if !isKafkaAlreadyEnoughPartitions(errors.New("Invalid Partitions requested")) {
		t.Error("expected invalid-partitions error to match")
	}
	if !isKafkaAlreadyEnoughPartitions(errors.New("partitions already exist")) {
		t.Error("expected already-exist error to match")
	}
	if isKafkaAlreadyEnoughPartitions(errors.New("unrelated failure")) {
		t.Error("unrelated error should not match")
	}
}

func TestKafkaDeliveryGetters(t *testing.T) {
	d := &kafkaDelivery{
		subject:        "reef.cmd.v1.p00",
		data:           []byte("payload"),
		streamSequence: 42,
	}
	if d.Subject() != "reef.cmd.v1.p00" {
		t.Errorf("Subject() = %s", d.Subject())
	}
	if string(d.Data()) != "payload" {
		t.Errorf("Data() = %s", d.Data())
	}
	if d.StreamSequence() != 42 {
		t.Errorf("StreamSequence() = %d", d.StreamSequence())
	}
	if d.DeliveredCount() != 1 {
		t.Errorf("DeliveredCount() = %d", d.DeliveredCount())
	}
}
