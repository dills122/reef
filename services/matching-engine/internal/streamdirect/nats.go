package streamdirect

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	nats "github.com/nats-io/nats.go"
)

type NatsConfig struct {
	URL                  string
	CommandStream        string
	CommandSubjectPrefix string
	EventStream          string
	EventSubjectPrefix   string
	PartitionCount       int
	AckWait              time.Duration
	MaxAckPending        int
}

type NatsCommandSource struct {
	js         nats.JetStreamContext
	stream     string
	subject    string
	durable    string
	ackWait    time.Duration
	maxPending int
	sub        *nats.Subscription
}

type NatsEventBatchPublisher struct {
	js             nats.JetStreamContext
	stream         string
	subjectPrefix  string
	partitionCount int
}

type natsDelivery struct {
	msg *nats.Msg
	md  *nats.MsgMetadata
}

func ConnectNats(config NatsConfig) (*nats.Conn, nats.JetStreamContext, error) {
	if config.URL == "" {
		config.URL = "nats://localhost:4222"
	}
	nc, err := nats.Connect(config.URL)
	if err != nil {
		return nil, nil, err
	}
	js, err := nc.JetStream()
	if err != nil {
		nc.Close()
		return nil, nil, err
	}
	return nc, js, nil
}

func EnsureEventStream(js nats.JetStreamContext, eventStream string, subjectPrefix string) error {
	if eventStream == "" {
		return nil
	}
	if _, err := js.StreamInfo(eventStream); err == nil {
		return nil
	} else if !errors.Is(err, nats.ErrStreamNotFound) {
		return err
	}
	_, err := js.AddStream(&nats.StreamConfig{
		Name:      eventStream,
		Subjects:  []string{trimDot(subjectPrefix) + ".>"},
		Storage:   nats.FileStorage,
		Retention: nats.LimitsPolicy,
		Discard:   nats.DiscardNew,
	})
	return err
}

func NewNatsCommandSource(js nats.JetStreamContext, config NatsConfig, partition int, durablePrefix string) *NatsCommandSource {
	if config.AckWait <= 0 {
		config.AckWait = 60 * time.Second
	}
	if config.MaxAckPending <= 0 {
		config.MaxAckPending = 4000
	}
	subject := fmt.Sprintf("%s.%s.>", trimDot(config.CommandSubjectPrefix), PartitionToken(config.PartitionCount, partition))
	return &NatsCommandSource{
		js:         js,
		stream:     config.CommandStream,
		subject:    subject,
		durable:    fmt.Sprintf("%s-%s", durablePrefix, PartitionToken(config.PartitionCount, partition)),
		ackWait:    config.AckWait,
		maxPending: config.MaxAckPending,
	}
}

func (s *NatsCommandSource) Fetch(_ context.Context, batchSize int, timeout time.Duration) ([]CommandDelivery, error) {
	if s.sub == nil {
		sub, err := s.js.PullSubscribe(
			s.subject,
			s.durable,
			nats.BindStream(s.stream),
			nats.ManualAck(),
			nats.AckWait(s.ackWait),
			nats.MaxAckPending(s.maxPending),
		)
		if err != nil {
			return nil, err
		}
		s.sub = sub
	}
	msgs, err := s.sub.Fetch(batchSize, nats.MaxWait(timeout))
	if err != nil {
		if err == nats.ErrTimeout {
			return nil, nil
		}
		// Only drop the subscription when it is no longer usable (invalid or
		// the underlying consumer is gone); transient errors like a closed or
		// reconnecting connection resolve on their own once the client
		// reconnects, and the same subscription remains valid to Fetch from.
		if errors.Is(err, nats.ErrBadSubscription) || errors.Is(err, nats.ErrConsumerNotFound) {
			s.sub = nil
		}
		return nil, err
	}
	deliveries := make([]CommandDelivery, 0, len(msgs))
	for _, msg := range msgs {
		md, err := msg.Metadata()
		if err != nil {
			return nil, err
		}
		deliveries = append(deliveries, &natsDelivery{msg: msg, md: md})
	}
	return deliveries, nil
}

func NewNatsEventBatchPublisher(js nats.JetStreamContext, eventStream string, subjectPrefix string, partitionCount int) *NatsEventBatchPublisher {
	return &NatsEventBatchPublisher{
		js:             js,
		stream:         eventStream,
		subjectPrefix:  trimDot(subjectPrefix),
		partitionCount: partitionCount,
	}
}

func (p *NatsEventBatchPublisher) PublishEventBatch(_ context.Context, batch VenueEventBatch) error {
	payload, err := json.Marshal(batch)
	if err != nil {
		return err
	}
	subject := fmt.Sprintf("%s.%s.%s", p.subjectPrefix, PartitionToken(p.partitionCount, batch.Partition), "VenueEventBatch")
	_, err = p.js.PublishMsg(&nats.Msg{
		Subject: subject,
		Header: nats.Header{
			"Nats-Msg-Id": []string{batch.BatchID},
		},
		Data: payload,
	})
	return err
}

func (d *natsDelivery) Subject() string {
	return d.msg.Subject
}

func (d *natsDelivery) Data() []byte {
	return d.msg.Data
}

func (d *natsDelivery) StreamSequence() uint64 {
	return d.md.Sequence.Stream
}

func (d *natsDelivery) DeliveredCount() uint64 {
	return uint64(d.md.NumDelivered)
}

func (d *natsDelivery) Ack() error {
	return d.msg.Ack()
}

func (d *natsDelivery) Nak() error {
	return d.msg.Nak()
}

func (d *natsDelivery) Term() error {
	return d.msg.Term()
}
