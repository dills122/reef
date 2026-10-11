package streamdirect

import (
	"os"
	"testing"

	"github.com/IBM/sarama"
)

func TestKafkaConfiguredClientCompression(t *testing.T) {
	cases := []struct {
		name     string
		engine   string
		ambient  string
		ordinary sarama.CompressionCodec
	}{
		{name: "absent defaults", ordinary: sarama.CompressionLZ4},
		{name: "ambient gzip", ambient: "gzip", ordinary: sarama.CompressionGZIP},
		{name: "ambient snappy", ambient: "snappy", ordinary: sarama.CompressionSnappy},
		{name: "ambient zstd", ambient: "zstd", ordinary: sarama.CompressionZSTD},
		{name: "ambient lz4", ambient: "lz4", ordinary: sarama.CompressionLZ4},
		{name: "ambient none", ambient: "none", ordinary: sarama.CompressionNone},
		{name: "engine overrides ambient", engine: "snappy", ambient: "gzip", ordinary: sarama.CompressionSnappy},
		{name: "engine gzip", engine: "gzip", ordinary: sarama.CompressionGZIP},
		{name: "engine none overrides ambient", engine: "none", ambient: "zstd", ordinary: sarama.CompressionNone},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			for key, value := range map[string]string{
				"MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE": tc.engine,
				"STREAM_ACK_KAFKA_COMPRESSION_TYPE":      tc.ambient,
			} {
				t.Setenv(key, value)
				if value == "" {
					if err := os.Unsetenv(key); err != nil {
						t.Fatal(err)
					}
				}
			}
			finite := kafkaConfiguredClient(KafkaConfig{Finite: &FiniteBrokerCredentials{Username: "finite", Password: "test"}}, "finite-codec-test")
			if got := finite.Producer.Compression; got != sarama.CompressionNone {
				t.Errorf("finite compression = %v, want %v", got, sarama.CompressionNone)
			}
			ordinary := kafkaConfiguredClient(KafkaConfig{}, "ordinary-codec-test")
			if got := ordinary.Producer.Compression; got != tc.ordinary {
				t.Errorf("ordinary compression = %v, want %v", got, tc.ordinary)
			}
		})
	}
}
