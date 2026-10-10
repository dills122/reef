package streamdirect

import (
	"context"
	"errors"
	"fmt"
	"github.com/dills122/reef/services/matching-engine/internal/app"
	"testing"
)

func p0SourceService(t *testing.T) *app.Service {
	t.Helper()
	option, err := app.CalcifySourceProfileFromJSON(`{"schema":"calcify-finite-source-v1","runIds":["p0-run","p0-other"],"venueSessionId":"p0-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":1000}`, "0")
	if err != nil {
		t.Fatal(err)
	}
	return app.NewService(app.WithTerminalOrderRetentionLimit(0), option)
}

func p0SourceDeliveries() []CommandDelivery {
	type step struct{ kind, id, run, side, price, quantity, owner string }
	steps := []step{
		{"SubmitOrder", "p0-1", "p0-run", "SELL", "100", "2", "seller"},
		{"SubmitOrder", "p0-2", "p0-run", "BUY", "90", "2", "buyer"},
		{"ModifyOrder", "p0-2", "p0-run", "", "100", "2", "buyer"},
		{"SubmitOrder", "p0-3", "p0-run", "BUY", "90", "2", "buyer"},
		{"ModifyOrder", "p0-3", "p0-run", "", "80", "3", "buyer"},
		{"CancelOrder", "p0-3", "p0-run", "", "", "", "buyer"},
		{"SubmitOrder", "p0-1", "p0-run", "SELL", "100", "2", "seller"},
		{"SubmitOrder", "p0-3", "p0-run", "BUY", "90", "2", "buyer"},
		{"SubmitOrder", "p0-1", "p0-other", "BUY", "90", "2", "buyer"},
	}
	var deliveries []CommandDelivery
	for i, s := range steps {
		payload := map[string]string{"commandId": fmt.Sprintf("p0-cmd-%d", i+1), "runId": s.run, "orderId": s.id, "participantId": s.owner, "accountId": s.owner, "occurredAt": fmt.Sprintf("2026-10-07T12:00:%02dZ", i)}
		if s.kind != "CancelOrder" {
			payload["quantityUnits"] = s.quantity
			payload["limitPrice"] = s.price
		}
		if s.kind == "SubmitOrder" {
			payload["side"] = s.side
			payload["instrumentId"] = "AAPL"
			payload["currency"] = "USD"
			payload["orderType"] = "LIMIT"
			payload["timeInForce"] = "DAY"
		}
		deliveries = append(deliveries, newFakeDelivery("reef.cmd.v1.p00.p0-session.AAPL."+s.kind, uint64(i+1), payload))
	}
	return deliveries
}

func TestCalcifyP0CanonicalCommandPathAndReplay(t *testing.T) {
	var expectedChecksum string
	for _, size := range []int{1, 3, 20} {
		t.Run(fmt.Sprintf("batch-%d", size), func(t *testing.T) {
			live := p0SourceService(t)
			publisher := &fakePublisher{}
			deliveries := p0SourceDeliveries()
			processor := NewProcessor(live, &fakeSource{deliveries: deliveries}, publisher, ProcessorConfig{BatchSize: size, Partition: 0, EventStreamName: "REEF_VENUE_EVENTS"})
			processed := 0
			for processed < len(deliveries) {
				n, err := processor.ProcessOnce(context.Background())
				if err != nil || n == 0 {
					t.Fatalf("source processing: %d %v", n, err)
				}
				processed += n
			}
			var outcomes []CommandOutcomeFact
			for _, batch := range publisher.batches {
				outcomes = append(outcomes, batch.Outcomes...)
			}
			if len(outcomes) != 9 {
				t.Fatalf("source membership=%d", len(outcomes))
			}
			trades := 0
			for i, outcome := range outcomes {
				if outcome.CommandID != fmt.Sprintf("p0-cmd-%d", i+1) || outcome.StreamSequence != uint64(i+1) {
					t.Fatalf("source order lost: %+v", outcome)
				}
				if i == 6 || i == 7 {
					if outcome.Result.Rejected == nil || outcome.Result.Rejected.Code != "DUPLICATE_ORDER_ID" {
						t.Fatalf("terminal reuse: %+v", outcome)
					}
				} else if outcome.Result.Accepted == nil {
					t.Fatalf("expected acceptance: %+v", outcome)
				}
				trades += len(outcome.Result.Trades)
				if i != 2 && len(outcome.Result.Trades) != 0 {
					t.Fatalf("unexpected trade at source member%d", i)
				}
			}
			if trades != 1 || outcomes[2].Result.Trades[0].QuantityUnits != "2" || outcomes[2].Result.Trades[0].Price != "100" {
				t.Fatal("source economics drift")
			}
			checksum := live.Snapshot().Checksum
			if expectedChecksum != "" && checksum != expectedChecksum {
				t.Fatal("batch cuts changed deterministic state")
			}
			expectedChecksum = checksum
			recovered := p0SourceService(t)
			replayPublisher := &fakePublisher{}
			replay := NewProcessor(recovered, &fakeSource{}, replayPublisher, ProcessorConfig{BatchSize: size, Partition: 0})
			count, err := replay.RestoreCommitted(context.Background(), &fakeCommittedReplayer{deliveries: p0SourceDeliveries()})
			if err != nil || count != 9 || recovered.Snapshot().Checksum != checksum || len(replayPublisher.batches) != 0 {
				t.Fatalf("canonical recovery: %d %v", count, err)
			}
		})
	}
}

func TestCalcifyP0FailedPublicationRollsBackIdentityReservations(t *testing.T) {
	service := p0SourceService(t)
	publisher := &fakePublisher{err: errors.New("injected publication failure")}
	processor := NewProcessor(service, &fakeSource{deliveries: p0SourceDeliveries()[:3]}, publisher, ProcessorConfig{BatchSize: 3, Partition: 0})
	if _, err := processor.ProcessOnce(context.Background()); err == nil {
		t.Fatal("expected publication failure")
	}
	if len(service.Snapshot().Orders) != 0 {
		t.Fatal("failed batch leaked identity state")
	}
	fresh := NewProcessor(service, &fakeSource{deliveries: p0SourceDeliveries()[:3]}, &fakePublisher{}, ProcessorConfig{BatchSize: 3, Partition: 0})
	if n, err := fresh.ProcessOnce(context.Background()); err != nil || n != 3 {
		t.Fatalf("retry failed: %d %v", n, err)
	}
	if len(service.Snapshot().Orders) != 2 {
		t.Fatal("retry did not restore complete matched state")
	}
}
