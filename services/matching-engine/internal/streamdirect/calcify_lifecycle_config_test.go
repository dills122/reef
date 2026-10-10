package streamdirect

import (
	"context"
	"strings"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestCalcifyLifecycleConfigurationDefaultsOff(t *testing.T) {
	t.Setenv("MATCHING_ENGINE_CALCIFY_LIFECYCLE_ENABLED", "")
	t.Setenv("MATCHING_ENGINE_CALCIFY_FINITE_BINDING_DIGEST", "")
	config := RuntimeConfigFromEnv()
	if config.CalcifyLifecycleEnabled || config.CalcifyFiniteBindingDigest != "" {
		t.Fatal("lifecycle enabled by default")
	}
	t.Setenv("MATCHING_ENGINE_CALCIFY_LIFECYCLE_ENABLED", "true")
	t.Setenv("MATCHING_ENGINE_CALCIFY_FINITE_BINDING_DIGEST", lifecycleFixtureBinding)
	config = RuntimeConfigFromEnv()
	if !config.CalcifyLifecycleEnabled || config.CalcifyFiniteBindingDigest != lifecycleFixtureBinding {
		t.Fatal("explicit lifecycle config lost")
	}
}

func TestCalcifyLifecycleInvalidConfigStopsBeforeFetch(t *testing.T) {
	for _, c := range []struct {
		name      string
		service   *app.Service
		digest    string
		batchSize int
	}{
		{"missing profile", app.NewService(), lifecycleFixtureBinding, 1},
		{"missing binding", lifecycleFixtureService(t), "", 1},
		{"short binding", lifecycleFixtureService(t), "abcd", 1},
		{"nonhex binding", lifecycleFixtureService(t), strings.Repeat("g", 64), 1},
		{"uppercase binding", lifecycleFixtureService(t), strings.ToUpper(lifecycleFixtureBinding), 1},
		{"large batch", lifecycleFixtureService(t), lifecycleFixtureBinding, 2},
	} {
		t.Run(c.name, func(t *testing.T) {
			source := &fakeSource{deliveries: lifecycleFixtureDeliveries()[:1]}
			publisher := &fakePublisher{}
			before := c.service.Snapshot().Checksum
			p := NewProcessor(c.service, source, publisher, ProcessorConfig{BatchSize: c.batchSize, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: c.digest})
			if n, err := p.ProcessOnce(context.Background()); err == nil || n != 0 {
				t.Fatal("invalid lifecycle config accepted")
			}
			if source.fetchCalls != 0 || len(publisher.batches) != 0 || before != c.service.Snapshot().Checksum {
				t.Fatal("invalid config reached source/matching/publisher")
			}
		})
	}
}

func TestCalcifyLifecycleLiveAndCommittedReplayRemainGated(t *testing.T) {
	service := lifecycleFixtureService(t)
	config := RuntimeConfig{LogProvider: "redpanda", BatchSize: 1, Partitions: []int{0}, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding}
	if runner, err := StartRunner(context.Background(), service, config); runner != nil || err == nil || !strings.Contains(err.Error(), "durable binding and restore") {
		t.Fatalf("live gate=%v %v", runner, err)
	}
	config.LogProvider = "jetstream"
	if _, err := StartRunner(context.Background(), service, config); err == nil || !strings.Contains(err.Error(), "transactional redpanda") {
		t.Fatal("nontransactional provider accepted")
	}
	p := NewProcessor(service, &fakeSource{}, &fakePublisher{}, ProcessorConfig{BatchSize: 1, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	before := service.Snapshot().Checksum
	if count, err := p.RestoreCommitted(context.Background(), &fakeCommittedReplayer{deliveries: lifecycleFixtureDeliveries()}); count != 0 || err == nil || !strings.Contains(err.Error(), "durable bound recovery") {
		t.Fatalf("unbound replay accepted count=%d err=%v", count, err)
	}
	if before != service.Snapshot().Checksum {
		t.Fatal("gated replay mutated state")
	}
}
