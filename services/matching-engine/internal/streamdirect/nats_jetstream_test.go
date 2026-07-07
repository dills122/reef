package streamdirect

import (
	"context"
	"errors"
	"testing"

	nats "github.com/nats-io/nats.go"
)

// fakeJetStream satisfies nats.JetStreamContext via embedding; only
// StreamInfo and AddStream are exercised by EnsureEventStream, so every
// other method is left to panic on the nil embedded interface if ever
// (incorrectly) invoked by the code under test.
type fakeJetStream struct {
	nats.JetStreamContext

	streamInfoErr    error
	streamInfoResult *nats.StreamInfo

	addStreamCfg *nats.StreamConfig
	addStreamErr error

	publishedMsg *nats.Msg
	publishErr   error
}

func (f *fakeJetStream) PublishMsg(m *nats.Msg, opts ...nats.PubOpt) (*nats.PubAck, error) {
	f.publishedMsg = m
	if f.publishErr != nil {
		return nil, f.publishErr
	}
	return &nats.PubAck{}, nil
}

func (f *fakeJetStream) StreamInfo(stream string, opts ...nats.JSOpt) (*nats.StreamInfo, error) {
	if f.streamInfoErr != nil {
		return nil, f.streamInfoErr
	}
	return f.streamInfoResult, nil
}

func (f *fakeJetStream) AddStream(cfg *nats.StreamConfig, opts ...nats.JSOpt) (*nats.StreamInfo, error) {
	f.addStreamCfg = cfg
	if f.addStreamErr != nil {
		return nil, f.addStreamErr
	}
	return &nats.StreamInfo{Config: *cfg}, nil
}

func TestEnsureEventStreamNoopWhenEventStreamEmpty(t *testing.T) {
	if err := EnsureEventStream(nil, "", "reef.venue.events.v1"); err != nil {
		t.Fatalf("expected no-op for empty stream name, got %v", err)
	}
}

func TestEnsureEventStreamSkipsCreationWhenStreamExists(t *testing.T) {
	js := &fakeJetStream{streamInfoResult: &nats.StreamInfo{}}
	if err := EnsureEventStream(js, "REEF_VENUE_EVENTS", "reef.venue.events.v1"); err != nil {
		t.Fatalf("EnsureEventStream failed: %v", err)
	}
	if js.addStreamCfg != nil {
		t.Error("expected AddStream not to be called when stream already exists")
	}
}

func TestEnsureEventStreamCreatesStreamWhenMissing(t *testing.T) {
	js := &fakeJetStream{streamInfoErr: nats.ErrStreamNotFound}
	if err := EnsureEventStream(js, "REEF_VENUE_EVENTS", "..reef.venue.events.v1.."); err != nil {
		t.Fatalf("EnsureEventStream failed: %v", err)
	}
	if js.addStreamCfg == nil {
		t.Fatal("expected AddStream to be called")
	}
	if js.addStreamCfg.Name != "REEF_VENUE_EVENTS" {
		t.Errorf("stream name = %s", js.addStreamCfg.Name)
	}
	if len(js.addStreamCfg.Subjects) != 1 || js.addStreamCfg.Subjects[0] != "reef.venue.events.v1.>" {
		t.Errorf("subjects = %v", js.addStreamCfg.Subjects)
	}
}

func TestEnsureEventStreamPropagatesStreamInfoError(t *testing.T) {
	js := &fakeJetStream{streamInfoErr: errors.New("boom")}
	if err := EnsureEventStream(js, "REEF_VENUE_EVENTS", "reef.venue.events.v1"); err == nil {
		t.Error("expected StreamInfo error to propagate")
	}
	if js.addStreamCfg != nil {
		t.Error("expected AddStream not to be called on unrelated StreamInfo error")
	}
}

func TestEnsureEventStreamPropagatesAddStreamError(t *testing.T) {
	js := &fakeJetStream{streamInfoErr: nats.ErrStreamNotFound, addStreamErr: errors.New("create failed")}
	if err := EnsureEventStream(js, "REEF_VENUE_EVENTS", "reef.venue.events.v1"); err == nil {
		t.Error("expected AddStream error to propagate")
	}
}

func TestNatsEventBatchPublisherPublishEventBatch(t *testing.T) {
	js := &fakeJetStream{}
	publisher := NewNatsEventBatchPublisher(js, "REEF_VENUE_EVENTS", "reef.venue.events.v1", 64)

	batch := VenueEventBatch{BatchID: "batch-1", Partition: 3}
	if err := publisher.PublishEventBatch(context.Background(), batch); err != nil {
		t.Fatalf("PublishEventBatch failed: %v", err)
	}
	if js.publishedMsg == nil {
		t.Fatal("expected a message to be published")
	}
	if js.publishedMsg.Subject != "reef.venue.events.v1.p03.VenueEventBatch" {
		t.Errorf("subject = %s", js.publishedMsg.Subject)
	}
	if js.publishedMsg.Header.Get("Nats-Msg-Id") != "batch-1" {
		t.Errorf("Nats-Msg-Id header = %s", js.publishedMsg.Header.Get("Nats-Msg-Id"))
	}
}

func TestNatsEventBatchPublisherPropagatesPublishError(t *testing.T) {
	js := &fakeJetStream{publishErr: errors.New("publish failed")}
	publisher := NewNatsEventBatchPublisher(js, "REEF_VENUE_EVENTS", "reef.venue.events.v1", 64)

	if err := publisher.PublishEventBatch(context.Background(), VenueEventBatch{BatchID: "batch-1"}); err == nil {
		t.Error("expected publish error to propagate")
	}
}
