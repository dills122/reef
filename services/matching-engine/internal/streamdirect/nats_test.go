package streamdirect

import (
	"testing"

	nats "github.com/nats-io/nats.go"
)

func TestNewNatsCommandSource(t *testing.T) {
	config := NatsConfig{
		CommandSubjectPrefix: "reef.cmd.v1",
		CommandStream:        "REEF_COMMANDS",
		PartitionCount:       64,
	}
	source := NewNatsCommandSource(nil, config, 7, "reef-engine-direct")

	if source.stream != "REEF_COMMANDS" {
		t.Errorf("stream = %s", source.stream)
	}
	if source.subject != "reef.cmd.v1.p07.>" {
		t.Errorf("subject = %s", source.subject)
	}
	if source.durable != "reef-engine-direct-p07" {
		t.Errorf("durable = %s", source.durable)
	}
	if source.ackWait.Seconds() != 60 {
		t.Errorf("default ackWait = %v", source.ackWait)
	}
	if source.maxPending != 4000 {
		t.Errorf("default maxPending = %d", source.maxPending)
	}
}

func TestNewNatsCommandSourceRespectsExplicitAckSettings(t *testing.T) {
	config := NatsConfig{
		CommandSubjectPrefix: "reef.cmd.v1",
		CommandStream:        "REEF_COMMANDS",
		PartitionCount:       64,
		AckWait:              30_000_000_000,
		MaxAckPending:        250,
	}
	source := NewNatsCommandSource(nil, config, 1, "durable")
	if source.ackWait.Seconds() != 30 {
		t.Errorf("ackWait = %v", source.ackWait)
	}
	if source.maxPending != 250 {
		t.Errorf("maxPending = %d", source.maxPending)
	}
}

func TestNewNatsEventBatchPublisher(t *testing.T) {
	publisher := NewNatsEventBatchPublisher(nil, "REEF_VENUE_EVENTS", "..reef.venue.events.v1..", 64)
	if publisher.stream != "REEF_VENUE_EVENTS" {
		t.Errorf("stream = %s", publisher.stream)
	}
	if publisher.subjectPrefix != "reef.venue.events.v1" {
		t.Errorf("subjectPrefix = %s", publisher.subjectPrefix)
	}
	if publisher.partitionCount != 64 {
		t.Errorf("partitionCount = %d", publisher.partitionCount)
	}
}

func TestNatsDeliveryGetters(t *testing.T) {
	msg := &nats.Msg{Subject: "reef.cmd.v1.p00", Data: []byte("payload")}
	md := &nats.MsgMetadata{
		Sequence:     nats.SequencePair{Stream: 42},
		NumDelivered: 2,
	}
	d := &natsDelivery{msg: msg, md: md}

	if d.Subject() != "reef.cmd.v1.p00" {
		t.Errorf("Subject() = %s", d.Subject())
	}
	if string(d.Data()) != "payload" {
		t.Errorf("Data() = %s", d.Data())
	}
	if d.StreamSequence() != 42 {
		t.Errorf("StreamSequence() = %d", d.StreamSequence())
	}
	if d.DeliveredCount() != 2 {
		t.Errorf("DeliveredCount() = %d", d.DeliveredCount())
	}
}
