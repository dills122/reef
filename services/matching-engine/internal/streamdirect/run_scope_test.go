package streamdirect

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"github.com/dills122/reef/services/matching-engine/internal/app"
	"reflect"
	"testing"
)

func TestRunSourceLaneFixture(t *testing.T) {
	const aligned = true
	route := func(run string) int {
		h := sha256.Sum256([]byte(run + "|session-1|AAPL"))
		return int((binary.BigEndian.Uint64(h[:8]) & 0x7fffffffffffffff) % 4)
	}
	service := app.NewService()
	seq := uint64(0)
	submit := func(run, id, side, qty, price string) CommandDelivery {
		seq++
		return newFakeDelivery(fmt.Sprintf("reef.cmd.v1.p%02d.session-1.AAPL.SubmitOrder", route(run)), seq, map[string]string{"commandId": "cmd-" + id, "orderId": id, "clientOrderId": "client-" + id, "runId": run, "venueSessionId": "session-1", "instrumentId": "AAPL", "participantId": "participant-" + id, "accountId": "account-" + id, "side": side, "orderType": "LIMIT", "quantityUnits": qty, "limitPrice": price, "currency": "USD", "timeInForce": "DAY", "occurredAt": "2026-09-30T20:00:00Z"})
	}
	batch := func(ds []CommandDelivery, part int) VenueEventBatch {
		p := NewProcessor(service, &fakeSource{}, &fakePublisher{}, ProcessorConfig{Partition: part, ShardID: "experiment", EventStreamName: "source"})
		b, _, rb, e := p.buildBatch(ds, "2026-09-30T20:00:00Z")
		if e != nil {
			t.Fatal(e)
		}
		rb.Commit()
		return b
	}
	a := batch([]CommandDelivery{submit("run-a", "cross-a", "BUY", "1", "100")}, route("run-a"))
	b := batch([]CommandDelivery{submit("run-b", "cross-b", "SELL", "1", "100")}, route("run-b"))
	n := len(b.Outcomes[0].Result.Trades)
	if (!aligned && n != 1) || (aligned && n != 0) {
		t.Fatalf("cross-run trades=%d aligned=%v", n, aligned)
	}
	t.Logf("cross-run trades=%d partitions=%d/%d", n, a.Partition, b.Partition)
	// Live partition interleaving versus partition-by-partition replay.
	interleave := func(replay bool) []VenueEventBatch {
		service = app.NewService()
		seq = 1000
		buy := submit("run-a", "replay-buy", "BUY", "1", "100")
		sell := submit("run-b", "replay-sell", "SELL", "1", "100")
		seq++
		cancel := newFakeDelivery("reef.cmd.v1.p00.session-1.AAPL.CancelOrder", seq, map[string]string{"commandId": "cmd-replay-cancel", "orderId": "replay-buy", "runId": "run-a", "venueSessionId": "session-1", "instrumentId": "AAPL", "participantId": "participant-replay-buy", "accountId": "account-replay-buy", "occurredAt": "2026-09-30T20:00:01Z"})
		a := batch([]CommandDelivery{buy}, route("run-a"))
		var b, c VenueEventBatch
		if replay {
			c = batch([]CommandDelivery{cancel}, route("run-a"))
			b = batch([]CommandDelivery{sell}, route("run-b"))
		} else {
			b = batch([]CommandDelivery{sell}, route("run-b"))
			c = batch([]CommandDelivery{cancel}, route("run-a"))
		}
		return []VenueEventBatch{a, b, c}
	}
	live, replayed := interleave(false), interleave(true)
	same := true
	for i := range live {
		same = same && reflect.DeepEqual(live[i].Outcomes, replayed[i].Outcomes)
	}
	if same != aligned {
		t.Fatalf("partition replay parity=%v aligned=%v", same, aligned)
	}
	t.Logf("partition-order replay parity=%v", same)
	if !aligned {
		return
	}
	fixture := func() []VenueEventBatch {
		service = app.NewService()
		seq = 0
		bs := []VenueEventBatch{batch([]CommandDelivery{submit("run-a", "rest-buy", "BUY", "1000", "100")}, route("run-a"))}
		bs = append(bs, batch([]CommandDelivery{submit("run-a", "partial-sell", "SELL", "2", "100"), submit("run-a", "reject", "SELL", "0", "100")}, route("run-a")))
		bs = append(bs, batch([]CommandDelivery{submit("run-a", "modify-sell", "SELL", "3", "200")}, route("run-a")))
		seq++
		m := newFakeDelivery("reef.cmd.v1.p00.session-1.AAPL.ModifyOrder", seq, map[string]string{"commandId": "cmd-modify", "orderId": "modify-sell", "runId": "run-a", "venueSessionId": "session-1", "instrumentId": "AAPL", "participantId": "participant-modify-sell", "accountId": "account-modify-sell", "quantityUnits": "3", "limitPrice": "100", "occurredAt": "2026-09-30T20:00:01Z"})
		bs = append(bs, batch([]CommandDelivery{m}, route("run-a")))
		ds := []CommandDelivery{}
		for i := 0; i < 128; i++ {
			ds = append(ds, submit("run-a", fmt.Sprintf("fan-%03d", i), "SELL", "1", "100"))
		}
		return append(bs, batch(ds, route("run-a")))
	}
	bs := fixture()
	replay := fixture()
	total := 0
	for i, b := range bs {
		if !reflect.DeepEqual(b.Outcomes, replay[i].Outcomes) || b.PayloadChecksum != replay[i].PayloadChecksum {
			t.Fatalf("replay batch %d", i)
		}
		for _, o := range b.Outcomes {
			total += len(o.Result.Trades)
		}
	}
	if total != 130 || len(bs[3].Outcomes[0].Result.Trades) != 1 || bs[1].Outcomes[1].Status != "rejected" || bs[1].Outcomes[1].Result.AcceptedOrder == nil {
		t.Fatal("fixture failed")
	}
	t.Logf("5 batches; %d trades; full replay parity", total)
}

func TestRunBatchFailedPublishRestoresSequenceAndAcceptance(t *testing.T) {
	payload := func(id, side string) map[string]string {
		return map[string]string{"commandId": "cmd-" + id, "orderId": id, "runId": "run-a", "venueSessionId": "session", "instrumentId": "AAPL", "side": side, "quantityUnits": "10", "limitPrice": "100", "currency": "USD", "occurredAt": "2026-09-30T00:00:00Z"}
	}
	service := app.NewService()
	process := func(id, side string, seq uint64, publisher *fakePublisher) error {
		p := NewProcessor(service, &fakeSource{deliveries: []CommandDelivery{newFakeDelivery("reef.cmd.v1.p00.session.AAPL.SubmitOrder", seq, payload(id, side))}}, publisher, ProcessorConfig{Partition: 0, BatchSize: 10})
		_, err := p.ProcessOnce(context.Background())
		return err
	}
	if err := process("buy", "BUY", 1, &fakePublisher{}); err != nil {
		t.Fatal(err)
	}
	before := service.Snapshot().Checksum
	if err := process("sell", "SELL", 2, &fakePublisher{err: errors.New("publish failed")}); err == nil {
		t.Fatal("missing failure")
	}
	if service.Snapshot().Checksum != before {
		t.Fatal("failed publish changed run book checksum")
	}
	publisher := &fakePublisher{}
	if err := process("sell", "SELL", 2, publisher); err != nil {
		t.Fatal(err)
	}
	result := publisher.batches[0].Outcomes[0].Result
	if result.Accepted == nil || len(result.Trades) != 1 || result.Trades[0].TradeID != "trade-buy-sell-1" {
		t.Fatalf("retry drift: %+v", result)
	}
}
