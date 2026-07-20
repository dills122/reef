package streamdirect

import (
	"os"
	"testing"
	"time"
)

func withEnv(t *testing.T, key, value string) {
	t.Helper()
	original, had := os.LookupEnv(key)
	if value == "" {
		os.Unsetenv(key)
	} else {
		os.Setenv(key, value)
	}
	t.Cleanup(func() {
		if had {
			os.Setenv(key, original)
		} else {
			os.Unsetenv(key)
		}
	})
}

func TestStreamLogProvider(t *testing.T) {
	cases := map[string]string{
		"":               "jetstream",
		"jetstream":      "jetstream",
		"nats":           "jetstream",
		"nats-jetstream": "jetstream",
		"redpanda":       "redpanda",
		"kafka":          "redpanda",
		"KAFKA":          "redpanda",
		"  redpanda  ":   "redpanda",
		"something-else": "something-else",
	}
	for input, want := range cases {
		withEnv(t, "STREAM_ACK_LOG_PROVIDER", input)
		if got := streamLogProvider(); got != want {
			t.Errorf("streamLogProvider(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestPartitionToken(t *testing.T) {
	cases := []struct {
		count, partition int
		want             string
	}{
		{64, 0, "p00"},
		{64, 7, "p07"},
		{64, 63, "p63"},
		{128, 5, "p005"},
		{2, 1, "p01"},
	}
	for _, c := range cases {
		if got := PartitionToken(c.count, c.partition); got != c.want {
			t.Errorf("PartitionToken(%d, %d) = %q, want %q", c.count, c.partition, got, c.want)
		}
	}
}

func TestTrimDot(t *testing.T) {
	if got := trimDot("...reef.cmd..."); got != "reef.cmd" {
		t.Errorf("trimDot = %q", got)
	}
	if got := trimDot("clean"); got != "clean" {
		t.Errorf("trimDot(no dots) = %q", got)
	}
}

func TestEnvString(t *testing.T) {
	withEnv(t, "SD_TEST_STRING", "")
	if got := envString("SD_TEST_STRING", "fallback"); got != "fallback" {
		t.Errorf("envString fallback = %q", got)
	}
	withEnv(t, "SD_TEST_STRING", "  value  ")
	if got := envString("SD_TEST_STRING", "fallback"); got != "value" {
		t.Errorf("envString set = %q", got)
	}
}

func TestEnvBool(t *testing.T) {
	cases := map[string]bool{
		"":      false,
		"1":     true,
		"true":  true,
		"TRUE":  true,
		"yes":   true,
		"0":     false,
		"false": false,
		"nope":  false,
	}
	for input, want := range cases {
		withEnv(t, "SD_TEST_BOOL", input)
		if got := envBool("SD_TEST_BOOL", false); got != want {
			t.Errorf("envBool(%q) = %v, want %v", input, got, want)
		}
	}
	withEnv(t, "SD_TEST_BOOL", "")
	if got := envBool("SD_TEST_BOOL", true); got != true {
		t.Errorf("envBool fallback true = %v", got)
	}
}

func TestEnvInt(t *testing.T) {
	withEnv(t, "SD_TEST_INT", "")
	if got := envInt("SD_TEST_INT", 42); got != 42 {
		t.Errorf("envInt fallback = %d", got)
	}
	withEnv(t, "SD_TEST_INT", "17")
	if got := envInt("SD_TEST_INT", 42); got != 17 {
		t.Errorf("envInt parsed = %d", got)
	}
	withEnv(t, "SD_TEST_INT", "not-a-number")
	if got := envInt("SD_TEST_INT", 42); got != 42 {
		t.Errorf("envInt invalid fallback = %d", got)
	}
	withEnv(t, "SD_TEST_INT", "-5")
	if got := envInt("SD_TEST_INT", 42); got != 42 {
		t.Errorf("envInt non-positive fallback = %d", got)
	}
}

func TestEnvPartitions(t *testing.T) {
	withEnv(t, "SD_TEST_PARTITIONS", "")
	got := envPartitions("SD_TEST_PARTITIONS", 4)
	want := []int{0, 1, 2, 3}
	if len(got) != len(want) {
		t.Fatalf("envPartitions default len = %d, want %d", len(got), len(want))
	}
	for i, v := range want {
		if got[i] != v {
			t.Errorf("envPartitions default[%d] = %d, want %d", i, got[i], v)
		}
	}

	withEnv(t, "SD_TEST_PARTITIONS", "0,2,5..7,2,99,-1")
	got = envPartitions("SD_TEST_PARTITIONS", 8)
	want = []int{0, 2, 5, 6, 7}
	if len(got) != len(want) {
		t.Fatalf("envPartitions custom len = %d, want %d (%v)", len(got), len(want), got)
	}
	for i, v := range want {
		if got[i] != v {
			t.Errorf("envPartitions custom[%d] = %d, want %d", i, got[i], v)
		}
	}

	withEnv(t, "SD_TEST_PARTITIONS", "3..1")
	got = envPartitions("SD_TEST_PARTITIONS", 8)
	if len(got) != 0 {
		t.Errorf("envPartitions reversed range should be empty, got %v", got)
	}

	withEnv(t, "SD_TEST_PARTITIONS", "abc..def")
	got = envPartitions("SD_TEST_PARTITIONS", 8)
	if len(got) != 0 {
		t.Errorf("envPartitions invalid range should be empty, got %v", got)
	}
}

func TestLeftPad(t *testing.T) {
	if got := leftPad("7", 3, "0"); got != "007" {
		t.Errorf("leftPad = %q", got)
	}
	if got := leftPad("700", 2, "0"); got != "700" {
		t.Errorf("leftPad already wide = %q", got)
	}
}

func TestRuntimeConfigFromEnv(t *testing.T) {
	withEnv(t, "MATCHING_ENGINE_DIRECT_STREAM_ENABLED", "true")
	withEnv(t, "MATCHING_ENGINE_SHARD_ID", "engine-3")
	withEnv(t, "STREAM_ACK_PARTITION_COUNT", "4")
	withEnv(t, "MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS", "")
	withEnv(t, "STREAM_ACK_LOG_PROVIDER", "kafka")

	cfg := RuntimeConfigFromEnv()
	if !cfg.Enabled {
		t.Error("expected Enabled = true")
	}
	if cfg.ShardID != "engine-3" {
		t.Errorf("ShardID = %q", cfg.ShardID)
	}
	if cfg.PartitionCount != 4 {
		t.Errorf("PartitionCount = %d", cfg.PartitionCount)
	}
	if len(cfg.Partitions) != 4 {
		t.Errorf("Partitions = %v", cfg.Partitions)
	}
	if cfg.LogProvider != "redpanda" {
		t.Errorf("LogProvider = %q", cfg.LogProvider)
	}
	if cfg.ConnectTimeout != 60000*time.Millisecond {
		t.Errorf("ConnectTimeout = %v", cfg.ConnectTimeout)
	}
	if cfg.RecoveryTimeout != 900000*time.Millisecond {
		t.Errorf("RecoveryTimeout = %v", cfg.RecoveryTimeout)
	}
	if cfg.OwnershipTopic != "REEF_ENGINE_OWNERSHIP" || cfg.OwnershipGroup != "reef-matching-engine-ownership-v1" {
		t.Errorf("unexpected ownership config topic=%q group=%q", cfg.OwnershipTopic, cfg.OwnershipGroup)
	}
}
