package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/IBM/sarama"
)

type fakeOwnershipConsumerGroup struct {
	consume  func(context.Context, []string, sarama.ConsumerGroupHandler) error
	closeErr error
}

func (g *fakeOwnershipConsumerGroup) Consume(ctx context.Context, topics []string, handler sarama.ConsumerGroupHandler) error {
	if g.consume == nil {
		return nil
	}
	return g.consume(ctx, topics, handler)
}

func (*fakeOwnershipConsumerGroup) Errors() <-chan error      { return nil }
func (g *fakeOwnershipConsumerGroup) Close() error            { return g.closeErr }
func (*fakeOwnershipConsumerGroup) Pause(map[string][]int32)  {}
func (*fakeOwnershipConsumerGroup) Resume(map[string][]int32) {}
func (*fakeOwnershipConsumerGroup) PauseAll()                 {}
func (*fakeOwnershipConsumerGroup) ResumeAll()                {}

type fakeOwnershipSession struct {
	claims map[string][]int32
	ctx    context.Context
}

func (s *fakeOwnershipSession) Claims() map[string][]int32                { return s.claims }
func (*fakeOwnershipSession) MemberID() string                            { return "member-a" }
func (*fakeOwnershipSession) GenerationID() int32                         { return 1 }
func (*fakeOwnershipSession) MarkOffset(string, int32, int64, string)     {}
func (*fakeOwnershipSession) Commit()                                     {}
func (*fakeOwnershipSession) ResetOffset(string, int32, int64, string)    {}
func (*fakeOwnershipSession) MarkMessage(*sarama.ConsumerMessage, string) {}
func (s *fakeOwnershipSession) Context() context.Context {
	if s.ctx == nil {
		return context.Background()
	}
	return s.ctx
}

type fakeOwnershipClaim struct {
	messages <-chan *sarama.ConsumerMessage
}

func (*fakeOwnershipClaim) Topic() string              { return "ownership" }
func (*fakeOwnershipClaim) Partition() int32           { return 0 }
func (*fakeOwnershipClaim) InitialOffset() int64       { return 0 }
func (*fakeOwnershipClaim) HighWaterMarkOffset() int64 { return 0 }
func (c *fakeOwnershipClaim) Messages() <-chan *sarama.ConsumerMessage {
	return c.messages
}

func newTestKafkaOwnershipLease(partitions ...int32) *KafkaOwnershipLease {
	requested := make(map[int32]bool, len(partitions))
	for _, partition := range partitions {
		requested[partition] = true
	}
	return &KafkaOwnershipLease{
		cancel:     func() {},
		done:       make(chan struct{}),
		state:      make(chan struct{}, 1),
		partitions: requested,
		owned:      make(map[int32]bool),
	}
}

func TestKafkaOwnershipLeaseSetupCleanupAndAssertions(t *testing.T) {
	lease := newTestKafkaOwnershipLease(0, 2)
	session := &fakeOwnershipSession{claims: map[string][]int32{"ownership": {0, 1, 2}}}
	if err := lease.Setup(session); err != nil {
		t.Fatalf("Setup failed: %v", err)
	}
	if !lease.ownsAllRequested() {
		t.Fatal("expected every configured partition to be owned")
	}
	for _, partition := range []int32{0, 2} {
		if err := lease.AssertOwned(partition); err != nil {
			t.Fatalf("partition %d should be owned: %v", partition, err)
		}
	}
	select {
	case <-lease.state:
	default:
		t.Fatal("Setup did not signal ownership state")
	}

	if err := lease.Cleanup(session); err != nil {
		t.Fatalf("Cleanup failed: %v", err)
	}
	if lease.ownsAllRequested() {
		t.Fatal("cleanup retained configured ownership")
	}
	if err := lease.AssertOwned(0); err == nil || strings.Contains(err.Error(), "ended") {
		t.Fatalf("expected temporary ownership error after cleanup, got %v", err)
	}
	select {
	case <-lease.state:
	default:
		t.Fatal("Cleanup did not signal ownership state")
	}

	if err := lease.Setup(&fakeOwnershipSession{claims: map[string][]int32{"ownership": {0}}}); err == nil || !strings.Contains(err.Error(), "partition 2") {
		t.Fatalf("expected missing configured partition failure, got %v", err)
	}
}

func TestKafkaOwnershipLeaseAssertOwnedFailureModes(t *testing.T) {
	fenced := errors.New("static member fenced")
	terminal := newTestKafkaOwnershipLease(0)
	terminal.terminal = fenced
	err := terminal.AssertOwned(0)
	var fatal *fatalProcessorError
	if !errors.As(err, &fatal) || !errors.Is(err, fenced) {
		t.Fatalf("terminal ownership failure must be fatal and preserve cause, got %v", err)
	}
	if !errors.Is(terminal.terminalError(), fenced) {
		t.Fatalf("terminalError did not preserve cause: %v", terminal.terminalError())
	}

	ended := newTestKafkaOwnershipLease(0)
	ended.owned[0] = true
	close(ended.done)
	err = ended.AssertOwned(0)
	if !errors.As(err, &fatal) || !strings.Contains(err.Error(), "ended") {
		t.Fatalf("ended lease must return fatal ownership error, got %v", err)
	}
	if err := ended.terminalError(); err == nil || !strings.Contains(err.Error(), "closed") {
		t.Fatalf("expected generic closed terminal error, got %v", err)
	}
}

func TestKafkaOwnershipLeaseConsumeClaim(t *testing.T) {
	messages := make(chan *sarama.ConsumerMessage, 1)
	messages <- &sarama.ConsumerMessage{}
	close(messages)
	lease := newTestKafkaOwnershipLease(0)
	if err := lease.ConsumeClaim(&fakeOwnershipSession{}, &fakeOwnershipClaim{messages: messages}); err != nil {
		t.Fatalf("closed claim failed: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	openMessages := make(chan *sarama.ConsumerMessage)
	err := lease.ConsumeClaim(&fakeOwnershipSession{ctx: ctx}, &fakeOwnershipClaim{messages: openMessages})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled claim error=%v want context.Canceled", err)
	}
}

func TestKafkaOwnershipLeaseRunRecordsTerminalFailure(t *testing.T) {
	terminal := errors.New("consumer group fenced")
	calls := 0
	group := &fakeOwnershipConsumerGroup{consume: func(_ context.Context, topics []string, handler sarama.ConsumerGroupHandler) error {
		calls++
		if len(topics) != 1 || topics[0] != "ownership" {
			t.Fatalf("unexpected ownership topics %v", topics)
		}
		if _, ok := handler.(*KafkaOwnershipLease); !ok {
			t.Fatalf("unexpected ownership handler %T", handler)
		}
		if calls == 1 {
			return nil
		}
		return terminal
	}}
	lease := newTestKafkaOwnershipLease(0)
	lease.group = group
	lease.owned[0] = true
	lease.run(context.Background(), "ownership")
	if calls != 2 {
		t.Fatalf("Consume calls=%d want=2", calls)
	}
	if !errors.Is(lease.terminalError(), terminal) {
		t.Fatalf("terminal failure=%v want %v", lease.terminalError(), terminal)
	}
	if lease.ownsAllRequested() {
		t.Fatal("terminal failure retained ownership")
	}
	select {
	case <-lease.done:
	default:
		t.Fatal("terminal run did not close done")
	}
	select {
	case <-lease.state:
	default:
		t.Fatal("terminal run did not signal state")
	}
}

func TestKafkaOwnershipLeaseCloseCancelsRun(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	closeErr := errors.New("close failed")
	group := &fakeOwnershipConsumerGroup{
		consume: func(ctx context.Context, _ []string, _ sarama.ConsumerGroupHandler) error {
			<-ctx.Done()
			return nil
		},
		closeErr: closeErr,
	}
	lease := newTestKafkaOwnershipLease(0)
	lease.cancel = cancel
	lease.group = group
	go lease.run(ctx, "ownership")
	if err := lease.Close(); !errors.Is(err, closeErr) {
		t.Fatalf("Close error=%v want %v", err, closeErr)
	}
	var nilLease *KafkaOwnershipLease
	if err := nilLease.Close(); err != nil {
		t.Fatalf("nil lease Close failed: %v", err)
	}
}

func TestKafkaOwnershipLeaseHelpersCoalesceState(t *testing.T) {
	lease := newTestKafkaOwnershipLease(0)
	lease.signalState()
	lease.signalState()
	if got := len(lease.state); got != 1 {
		t.Fatalf("coalesced state signals=%d want=1", got)
	}
	if got := (&kafkaOwnershipBalanceStrategy{}).Name(); got != kafkaOwnershipStrategyName {
		t.Fatalf("strategy name=%q", got)
	}
	data, err := (&kafkaOwnershipBalanceStrategy{}).AssignmentData("member-a", nil, 1)
	if err != nil || data != nil {
		t.Fatalf("AssignmentData=%v, %v want nil, nil", data, err)
	}
}

func TestKafkaOwnershipBalanceStrategyRejectsOverlappingLaneClaims(t *testing.T) {
	first, _ := json.Marshal(kafkaOwnershipMember{ShardID: "engine-a", Partitions: []int32{0, 1}})
	second, _ := json.Marshal(kafkaOwnershipMember{ShardID: "engine-b", Partitions: []int32{1, 2}})
	plan, err := (&kafkaOwnershipBalanceStrategy{}).Plan(
		map[string]sarama.ConsumerGroupMemberMetadata{
			"member-a": {Topics: []string{"ownership"}, UserData: first},
			"member-b": {Topics: []string{"ownership"}, UserData: second},
		},
		map[string][]int32{"ownership": {0, 1, 2}},
	)
	if err != nil {
		t.Fatalf("Plan failed: %v", err)
	}
	if got := plan["member-a"]["ownership"]; len(got) != 2 || got[0] != 0 || got[1] != 1 {
		t.Fatalf("unexpected first-owner assignment %v", got)
	}
	if got := plan["member-b"]["ownership"]; len(got) != 1 || got[0] != 2 {
		t.Fatalf("overlapping partition must not be assigned twice, got %v", got)
	}
}

func TestKafkaOwnershipBalanceStrategyRejectsMalformedMemberData(t *testing.T) {
	_, err := (&kafkaOwnershipBalanceStrategy{}).Plan(
		map[string]sarama.ConsumerGroupMemberMetadata{
			"member-a": {Topics: []string{"ownership"}, UserData: []byte("not-json")},
		},
		map[string][]int32{"ownership": {0}},
	)
	if err == nil {
		t.Fatal("expected malformed ownership metadata to fail")
	}
}
