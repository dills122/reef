package streamdirect

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestStartRunnerRequiresPartitions(t *testing.T) {
	_, err := StartRunner(context.Background(), app.NewService(), RuntimeConfig{})
	if err == nil {
		t.Fatal("expected error when no partitions are configured")
	}
}

func TestStartRunnerRejectsUnsupportedProvider(t *testing.T) {
	_, err := StartRunner(context.Background(), app.NewService(), RuntimeConfig{
		Partitions:  []int{0},
		LogProvider: "carrier-pigeon",
	})
	if err == nil {
		t.Fatal("expected error for unsupported log provider")
	}
}

func TestStartRunnerWithRetrySucceedsImmediately(t *testing.T) {
	calls := 0
	start := func(context.Context, *app.Service, RuntimeConfig) (*Runner, error) {
		calls++
		return &Runner{}, nil
	}
	runner, err := startRunnerWithRetry(context.Background(), app.NewService(), RuntimeConfig{}, start)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if runner == nil {
		t.Fatal("expected a runner")
	}
	if calls != 1 {
		t.Fatalf("expected exactly one start call, got %d", calls)
	}
}

func TestStartRunnerWithRetryRetriesThenSucceeds(t *testing.T) {
	calls := 0
	start := func(context.Context, *app.Service, RuntimeConfig) (*Runner, error) {
		calls++
		if calls < 3 {
			return nil, errors.New("not ready yet")
		}
		return &Runner{}, nil
	}
	config := RuntimeConfig{ConnectTimeout: 5 * time.Second}
	runner, err := startRunnerWithRetry(context.Background(), app.NewService(), config, start)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if runner == nil {
		t.Fatal("expected a runner after retries")
	}
	if calls != 3 {
		t.Fatalf("expected three start attempts, got %d", calls)
	}
}

func TestStartRunnerWithRetryTimesOut(t *testing.T) {
	start := func(context.Context, *app.Service, RuntimeConfig) (*Runner, error) {
		return nil, errors.New("always fails")
	}
	config := RuntimeConfig{ConnectTimeout: 10 * time.Millisecond}
	_, err := startRunnerWithRetry(context.Background(), app.NewService(), config, start)
	if err == nil {
		t.Fatal("expected timeout error")
	}
}

func TestStartRunnerWithRetryHonorsContextCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	start := func(context.Context, *app.Service, RuntimeConfig) (*Runner, error) {
		cancel()
		return nil, errors.New("still failing")
	}
	config := RuntimeConfig{ConnectTimeout: time.Minute}
	_, err := startRunnerWithRetry(ctx, app.NewService(), config, start)
	if err == nil {
		t.Fatal("expected error when context is cancelled while waiting to retry")
	}
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
}

func TestRunnerStopIsNilSafe(t *testing.T) {
	var runner *Runner
	runner.Stop() // must not panic
}

func TestRunnerSnapshotsIsNilSafe(t *testing.T) {
	var runner *Runner
	if snapshots := runner.Snapshots(); snapshots != nil {
		t.Fatalf("expected nil snapshots for nil runner, got %v", snapshots)
	}
}

func TestRunnerStopCancelsAndClosesResources(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	closed := false
	runner := &Runner{
		cancel: cancel,
		close:  func() { closed = true },
	}

	processor := NewProcessor(app.NewService(), &fakeSource{}, &fakePublisher{}, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: 2,
	})
	runner.processors = append(runner.processors, processor)
	runner.wg.Add(1)
	go func() {
		defer runner.wg.Done()
		<-ctx.Done()
	}()

	runner.Stop()

	if ctx.Err() == nil {
		t.Fatal("expected Stop to cancel the runner context")
	}
	if !closed {
		t.Fatal("expected Stop to invoke close")
	}

	snapshots := runner.Snapshots()
	if len(snapshots) != 1 {
		t.Fatalf("expected one snapshot, got %d", len(snapshots))
	}
	if snapshots[0].ShardID != "engine-test" || snapshots[0].Partition != 2 {
		t.Fatalf("unexpected snapshot %#v", snapshots[0])
	}
}

func TestRunnerStopWithoutCloseFunc(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	runner := &Runner{cancel: cancel}
	_ = ctx
	runner.Stop() // close is nil; must not panic
}
