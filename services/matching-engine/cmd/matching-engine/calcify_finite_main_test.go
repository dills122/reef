package main

import (
	"github.com/dills122/reef/services/matching-engine/internal/streamdirect"
	"testing"
)

func TestFiniteListenersRequireRunnerAndFenceGRPC(t *testing.T) {
	for _, c := range []struct {
		enabled, finite, grpc bool
		refuses               bool
	}{
		{false, false, true, false}, {true, false, true, false}, {false, true, false, true}, {true, true, true, true}, {true, true, false, false},
	} {
		err := validateFiniteListeners(streamdirect.RuntimeConfig{Enabled: c.enabled, CalcifyLifecycleEnabled: c.finite}, c.grpc)
		if (err != nil) != c.refuses {
			t.Fatalf("listener gate finite=%v stream=%v grpc=%v err=%v", c.finite, c.enabled, c.grpc, err)
		}
	}
}
