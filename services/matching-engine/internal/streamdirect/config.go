package streamdirect

import (
	"os"
	"strconv"
	"strings"
	"time"
)

type RuntimeConfig struct {
	Enabled              bool
	LogProvider          string
	ShardID              string
	NatsURL              string
	KafkaBootstrap       string
	CommandStream        string
	CommandSubjectPrefix string
	EventStream          string
	EventSubjectPrefix   string
	PartitionCount       int
	Partitions           []int
	DurablePrefix        string
	BatchSize            int
	ConnectTimeout       time.Duration
	FetchTimeout         time.Duration
	PollInterval         time.Duration
	AckWait              time.Duration
	MaxAckPending        int
}

func RuntimeConfigFromEnv() RuntimeConfig {
	partitionCount := envInt("STREAM_ACK_PARTITION_COUNT", 64)
	return RuntimeConfig{
		Enabled:              envBool("MATCHING_ENGINE_DIRECT_STREAM_ENABLED", false),
		LogProvider:          streamLogProvider(),
		ShardID:              envString("MATCHING_ENGINE_SHARD_ID", "engine-0"),
		NatsURL:              envString("STREAM_ACK_NATS_URL", "nats://localhost:4222"),
		KafkaBootstrap:       envString("STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
		CommandStream:        envString("STREAM_ACK_COMMAND_STREAM", "REEF_COMMANDS"),
		CommandSubjectPrefix: envString("STREAM_ACK_SUBJECT_PREFIX", "reef.cmd.v1"),
		EventStream:          envString("MATCHING_ENGINE_EVENT_STREAM", "REEF_VENUE_EVENTS"),
		EventSubjectPrefix:   envString("MATCHING_ENGINE_EVENT_SUBJECT_PREFIX", "reef.venue.events.v1"),
		PartitionCount:       partitionCount,
		Partitions:           envPartitions("MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", partitionCount),
		DurablePrefix:        envString("MATCHING_ENGINE_DIRECT_STREAM_DURABLE_PREFIX", "reef-engine-direct"),
		BatchSize:            envInt("MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE", 500),
		ConnectTimeout:       time.Duration(envInt("MATCHING_ENGINE_DIRECT_STREAM_CONNECT_TIMEOUT_MS", 60000)) * time.Millisecond,
		FetchTimeout:         time.Duration(envInt("MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS", 200)) * time.Millisecond,
		PollInterval:         time.Duration(envInt("MATCHING_ENGINE_DIRECT_STREAM_POLL_MS", 5)) * time.Millisecond,
		AckWait:              time.Duration(envInt("MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS", 60000)) * time.Millisecond,
		MaxAckPending:        envInt("MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING", 4000),
	}
}

func streamLogProvider() string {
	value := strings.TrimSpace(strings.ToLower(os.Getenv("STREAM_ACK_LOG_PROVIDER")))
	switch value {
	case "", "jetstream", "nats", "nats-jetstream":
		return "jetstream"
	case "redpanda", "kafka":
		return "redpanda"
	default:
		return value
	}
}

func PartitionToken(partitionCount int, partition int) string {
	width := len(strconv.Itoa(partitionCount - 1))
	if width < 2 {
		width = 2
	}
	return "p" + leftPad(strconv.Itoa(partition), width, "0")
}

func trimDot(value string) string {
	return strings.Trim(value, ".")
}

func envString(name string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envBool(name string, fallback bool) bool {
	value := strings.TrimSpace(strings.ToLower(os.Getenv(name)))
	if value == "" {
		return fallback
	}
	return value == "1" || value == "true" || value == "yes"
}

func envInt(name string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func envPartitions(name string, partitionCount int) []int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		partitions := make([]int, 0, partitionCount)
		for partition := 0; partition < partitionCount; partition++ {
			partitions = append(partitions, partition)
		}
		return partitions
	}
	seen := map[int]bool{}
	partitions := []int{}
	for _, token := range strings.Split(raw, ",") {
		token = strings.TrimSpace(token)
		if token == "" {
			continue
		}
		if strings.Contains(token, "..") {
			parts := strings.SplitN(token, "..", 2)
			start, startErr := strconv.Atoi(strings.TrimSpace(parts[0]))
			end, endErr := strconv.Atoi(strings.TrimSpace(parts[1]))
			if startErr != nil || endErr != nil || end < start {
				continue
			}
			for partition := start; partition <= end; partition++ {
				if partition >= 0 && partition < partitionCount && !seen[partition] {
					seen[partition] = true
					partitions = append(partitions, partition)
				}
			}
			continue
		}
		partition, err := strconv.Atoi(token)
		if err == nil && partition >= 0 && partition < partitionCount && !seen[partition] {
			seen[partition] = true
			partitions = append(partitions, partition)
		}
	}
	return partitions
}

func leftPad(value string, width int, pad string) string {
	for len(value) < width {
		value = pad + value
	}
	return value
}
