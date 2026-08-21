package streamdirect

import (
	"context"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func TestMultiPartitionRecoveryMatchesLiveBoundedTerminalState(t *testing.T) {
	partitionZero := []CommandDelivery{
		terminalSubmitDelivery("p00", "AAPL", "ord-a", 1),
		terminalCancelDelivery("p00", "AAPL", "ord-a", "2026-08-20T12:00:01Z", 2),
		terminalSubmitDelivery("p00", "NVDA", "ord-c", 3),
		terminalCancelDelivery("p00", "NVDA", "ord-c", "2026-08-20T12:00:03Z", 4),
	}
	partitionOne := []CommandDelivery{
		terminalSubmitDelivery("p01", "MSFT", "ord-b", 1),
		terminalCancelDelivery("p01", "MSFT", "ord-b", "2026-08-20T12:00:02Z", 2),
	}

	live := app.NewService(app.WithTerminalOrderRetentionLimit(2))
	processLiveTerminalLane(t, live, 1, partitionOne)
	processLiveTerminalLane(t, live, 0, partitionZero)

	recovered := app.NewService(app.WithTerminalOrderRetentionLimit(2))
	restoreTerminalLane(t, recovered, 0, partitionZero)
	restoreTerminalLane(t, recovered, 1, partitionOne)

	if _, ok := live.OrderState("ord-a"); ok {
		t.Fatal("live bounded state retained chronologically oldest terminal order")
	}
	if _, ok := recovered.OrderState("ord-a"); ok {
		t.Fatal("recovered bounded state retained chronologically oldest terminal order")
	}
	for _, orderID := range []string{"ord-b", "ord-c"} {
		if _, ok := live.OrderState(orderID); !ok {
			t.Fatalf("live bounded state lost %s", orderID)
		}
		if _, ok := recovered.OrderState(orderID); !ok {
			t.Fatalf("recovered bounded state lost %s", orderID)
		}
	}
	if got, want := recovered.Snapshot().Checksum, live.Snapshot().Checksum; got != want {
		t.Fatalf("sequential partition recovery checksum drifted: got %s want %s", got, want)
	}
}

func processLiveTerminalLane(t *testing.T, service *app.Service, partition int, deliveries []CommandDelivery) {
	t.Helper()
	processor := NewProcessor(
		service,
		&fakeSource{deliveries: deliveries},
		&fakePublisher{},
		ProcessorConfig{
			ShardID:         "engine-test",
			Partition:       partition,
			BatchSize:       10,
			FetchTimeout:    time.Millisecond,
			EventStreamName: "REEF_VENUE_EVENTS",
		},
	)
	processed, err := processor.ProcessOnce(context.Background())
	if err != nil {
		t.Fatalf("process live partition %d: %v", partition, err)
	}
	if processed != len(deliveries) {
		t.Fatalf("processed partition %d commands=%d want=%d", partition, processed, len(deliveries))
	}
}

func restoreTerminalLane(t *testing.T, service *app.Service, partition int, deliveries []CommandDelivery) {
	t.Helper()
	processor := NewProcessor(service, &fakeSource{}, &fakePublisher{}, ProcessorConfig{
		ShardID:   "engine-test",
		Partition: partition,
		BatchSize: 10,
	})
	replayed, err := processor.RestoreCommitted(context.Background(), &fakeCommittedReplayer{deliveries: deliveries})
	if err != nil {
		t.Fatalf("restore partition %d: %v", partition, err)
	}
	if replayed != len(deliveries) {
		t.Fatalf("replayed partition %d commands=%d want=%d", partition, replayed, len(deliveries))
	}
}

func terminalSubmitDelivery(partition string, instrumentID string, orderID string, sequence uint64) CommandDelivery {
	return newFakeDelivery("reef.cmd.v1."+partition+".session-1."+instrumentID+".SubmitOrder", sequence, map[string]string{
		"commandId":      "cmd-submit-" + orderID,
		"runId":          "run-1",
		"venueSessionId": "session-1",
		"occurredAt":     "2026-08-20T12:00:00Z",
		"orderId":        orderID,
		"instrumentId":   instrumentID,
		"participantId":  "participant-1",
		"accountId":      "account-1",
		"actorId":        "actor-1",
		"side":           "BUY",
		"orderType":      "LIMIT",
		"quantityUnits":  "100",
		"limitPrice":     "100000000000",
		"currency":       "USD",
		"timeInForce":    "DAY",
	})
}

func terminalCancelDelivery(partition string, instrumentID string, orderID string, occurredAt string, sequence uint64) CommandDelivery {
	return newFakeDelivery("reef.cmd.v1."+partition+".session-1."+instrumentID+".CancelOrder", sequence, map[string]string{
		"commandId":     "cmd-cancel-" + orderID,
		"runId":         "run-1",
		"occurredAt":    occurredAt,
		"orderId":       orderID,
		"participantId": "participant-1",
		"accountId":     "account-1",
		"reason":        "retention-test",
	})
}
