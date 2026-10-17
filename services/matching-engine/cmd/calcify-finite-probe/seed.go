package main

import (
	"context"
	"errors"
	"time"

	"github.com/IBM/sarama"
)

// Genesis seeding is a one-time mutation; only coordinator READ discovery is retried.
// Timed-out reads may finish in their bounded client call, but cannot authorize a commit.
type seedOperations struct{ bounds, fetch, commit func() (any, error) }

type seedPhaseError struct {
	phase           string
	commitAttempted bool
	cause           error
}

func (e *seedPhaseError) Error() string { return "seed phase refused" }

func (e *seedPhaseError) Unwrap() error { return e.cause }

func seedCall(ctx context.Context, call func() (any, error)) (any, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	type result struct {
		value any
		err   error
	}
	done := make(chan result, 1)
	go func() {
		if e := ctx.Err(); e != nil {
			done <- result{nil, e}
			return
		}
		v, e := call()
		done <- result{v, e}
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case r := <-done:
		if e := ctx.Err(); e != nil {
			return nil, e
		}
		return r.value, r.err
	}
}

func seedRead(ctx context.Context, call func() (any, error), interval time.Duration) (any, int, error) {
	attempts := 0
	for {
		attempts++
		v, e := seedCall(ctx, call)
		if e == nil {
			return v, attempts, nil
		}
		if !errors.Is(e, sarama.ErrConsumerCoordinatorNotAvailable) && !errors.Is(e, sarama.ErrNotCoordinatorForConsumer) {
			return nil, attempts, e
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, attempts, ctx.Err()
		case <-timer.C:
		}
	}
}

func seedOffset(value any, topic string) (int64, error) {
	response, ok := value.(*sarama.OffsetFetchResponse)
	if !ok || response == nil {
		return 0, errors.New("missing offset response")
	}
	if response.Err != sarama.ErrNoError {
		return 0, response.Err
	}
	block := response.GetBlock(topic, 0)
	if block == nil {
		return 0, errors.New("missing offset block")
	}
	if block.Err != sarama.ErrNoError {
		return 0, block.Err
	}
	return block.Offset, nil
}

func seedGenesis(ctx context.Context, q request, ops seedOperations, interval time.Duration) (any, error) {
	committed := false
	fail := func(phase string, e error) (any, error) { return nil, &seedPhaseError{phase, committed, e} }
	raw, boundAttempts, e := seedRead(ctx, ops.bounds, interval)
	if e != nil {
		return fail("topic-bounds", e)
	}
	bounds, ok := raw.([2]int64)
	if !ok || bounds != [2]int64{0, 0} {
		return fail("topic-bounds", errors.New("nonempty genesis"))
	}
	fetch := func() (any, error) {
		v, e := ops.fetch()
		if e != nil {
			return nil, e
		}
		offset, e := seedOffset(v, q.Topic)
		return offset, e
	}
	raw, beforeAttempts, e := seedRead(ctx, fetch, interval)
	if e != nil {
		return fail("read-before", e)
	}
	if raw.(int64) != -1 {
		return fail("read-before", errors.New("existing cut refuses reseed"))
	}
	// Check topic remains empty immediately before first and only commit.
	raw, finalBoundsAttempts, e := seedRead(ctx, ops.bounds, interval)
	if e != nil {
		return fail("precommit-bounds", e)
	}
	if b, ok := raw.([2]int64); !ok || b != [2]int64{0, 0} {
		return fail("precommit-bounds", errors.New("changed genesis"))
	}
	if e = ctx.Err(); e != nil {
		return fail("commit", e)
	}
	committed = true
	raw, e = seedCall(ctx, ops.commit)
	if e != nil {
		return fail("commit", e)
	}
	response, ok := raw.(*sarama.OffsetCommitResponse)
	if !ok || response == nil {
		return fail("commit", errors.New("missing commit response"))
	}
	partitions, ok := response.Errors[q.Topic]
	if !ok {
		return fail("commit", errors.New("missing commit topic"))
	}
	code, ok := partitions[0]
	if !ok {
		return fail("commit", errors.New("missing commit partition"))
	}
	if code != sarama.ErrNoError {
		return fail("commit", code)
	}
	raw, afterAttempts, e := seedRead(ctx, fetch, interval)
	if e != nil {
		return fail("read-after", e)
	}
	if raw.(int64) != 0 {
		return fail("read-after", errors.New("commit readback mismatch"))
	}
	return map[string]any{"group": q.Group, "topic": q.Topic, "offset": "0", "previousOffset": "-1", "beginningOffset": "0", "endOffset": "0", "commitAttempts": 1, "boundsReadAttempts": boundAttempts + finalBoundsAttempts, "beforeReadAttempts": beforeAttempts, "afterReadAttempts": afterAttempts, "scope": "Observed empty topic, explicit absent cut, single acknowledged commit, actual OffsetFetch0 readback"}, nil
}
