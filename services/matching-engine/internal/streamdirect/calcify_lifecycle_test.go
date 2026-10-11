package streamdirect

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

const lifecycleFixtureProfile = `{"schema":"calcify-finite-source-v1","runIds":["p3-run"],"venueSessionId":"p3-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":100000000000}`

func lifecycleFixtureService(t *testing.T) *app.Service {
	t.Helper()
	option, err := app.CalcifySourceProfileFromJSON(lifecycleFixtureProfile, "0")
	if err != nil {
		t.Fatal(err)
	}
	return app.NewService(app.WithTerminalOrderRetentionLimit(0), option)
}

func lifecycleFixtureDeliveries() []CommandDelivery {
	type step struct{ kind, id, side, quantity, price, owner string }
	steps := []step{
		{"SubmitOrder", "p0-1", "SELL", "3", "100000000000", "seller"},
		{"SubmitOrder", "p0-2", "SELL", "2", "100000000000", "seller"},
		{"SubmitOrder", "p0-3", "BUY", "4", "100000000000", "buyer"},
		{"ModifyOrder", "p0-2", "", "4", "99000000000", "seller"},
		{"CancelOrder", "p0-2", "", "", "", "seller"},
		{"SubmitOrder", "p0-4", "BUY", "2", "98000000000", "buyer"},
		{"ModifyOrder", "p0-4", "", "2", "100000000000", "buyer"},
		{"SubmitOrder", "p0-5", "SELL", "2", "100000000000", "seller"},
		{"CancelOrder", "p0-1", "", "", "", "seller"},
		{"SubmitOrder", "p0-1", "SELL", "3", "100000000000", "seller"},
		{"ModifyOrder", "p0-8", "", "2", "100000000000", "buyer"},
		{"CancelOrder", "p0-8", "", "", "", "buyer"},
	}
	deliveries := make([]CommandDelivery, 0, len(steps))
	for i, step := range steps {
		payload := map[string]string{
			"commandId": fmt.Sprintf("p3-cmd-%02d", i+1), "runId": "p3-run",
			"venueSessionId": "p3-session", "instrumentId": "AAPL", "orderId": step.id,
			"participantId": step.owner, "accountId": step.owner + "-account",
			"traceId": fmt.Sprintf("p3-trace-%02d", i+1), "causationId": "p3-fixture",
			"correlationId": "p3-fixture", "actorId": step.owner + "-actor",
			"occurredAt": fmt.Sprintf("2026-10-10T03:00:%02dZ", i),
		}
		if step.kind != "CancelOrder" {
			payload["quantityUnits"], payload["limitPrice"] = step.quantity, step.price
		}
		if step.kind == "SubmitOrder" {
			payload["clientOrderId"], payload["side"], payload["orderType"] = "client-"+step.id, step.side, "LIMIT"
			payload["currency"], payload["timeInForce"] = "USD", "DAY"
		}
		if step.kind == "CancelOrder" {
			payload["reason"] = "finite fixture cancel"
		}
		deliveries = append(deliveries, newFakeDelivery("reef.cmd.v1.p00.p3-session.AAPL."+step.kind, uint64(i+1), payload))
	}
	return deliveries
}

func lifecycleFixtureBatches(t *testing.T, config ProcessorConfig) ([]VenueEventBatch, *app.Service) {
	t.Helper()
	service := lifecycleFixtureService(t)
	publisher := &fakePublisher{}
	config.ShardID, config.Partition, config.BatchSize = "p3-engine", 0, 1
	config.CommandStream, config.EventStreamName = "CALCIFY_P3_COMMANDS", "CALCIFY_P3_SOURCE"
	source := &fakeSource{}
	p := NewProcessor(service, source, publisher, config)
	clock := time.Date(2026, 10, 10, 3, 0, 0, 0, time.UTC)
	p.now = func() time.Time { return clock }
	for i, delivery := range lifecycleFixtureDeliveries() {
		source.deliveries = []CommandDelivery{delivery}
		clock = clock.Add(time.Second * time.Duration(i))
		if n, err := p.ProcessOnce(context.Background()); err != nil || n != 1 {
			t.Fatalf("command%d: processed=%d err=%v", i+1, n, err)
		}
		clock = time.Date(2026, 10, 10, 3, 0, 0, 0, time.UTC)
	}
	return publisher.batches, service
}

func lifecycleJSONL(t *testing.T, batches []VenueEventBatch) []byte {
	t.Helper()
	var buffer bytes.Buffer
	for _, batch := range batches {
		raw, err := json.Marshal(batch)
		if err != nil {
			t.Fatal(err)
		}
		buffer.Write(raw)
		buffer.WriteByte('\n')
	}
	return buffer.Bytes()
}

func TestCalcifyLifecycleLegacyFixture(t *testing.T) {
	batches, service := lifecycleFixtureBatches(t, ProcessorConfig{})
	accepted, rejected, trades, units := 0, 0, 0, 0
	for _, batch := range batches {
		outcome := batch.Outcomes[0]
		if outcome.Status == "accepted" {
			accepted++
		} else {
			rejected++
		}
		for _, trade := range outcome.Result.Trades {
			trades++
			var quantity int
			fmt.Sscan(trade.QuantityUnits, &quantity)
			units += quantity
		}
	}
	if accepted != 8 || rejected != 4 || trades != 3 || units != 6 || len(service.Snapshot().Orders) != 5 {
		t.Fatalf("fixture drift accepted=%d rejected=%d trades=%d units=%d identities=%d", accepted, rejected, trades, units, len(service.Snapshot().Orders))
	}
	raw := lifecycleJSONL(t, batches)
	const legacySHA256 = "bdd4df11f562ba2f4dd217168a3e81e60f96fa4c3fa4a25c01e75830dda26506"
	if sha256Hex(raw) != legacySHA256 || len(raw) != 16647 {
		t.Fatal("mode-off fixture changed from pre-implementation bytes")
	}
	t.Logf("legacy fixture SHA256=%s bytes=%d", sha256Hex(raw), len(raw))
	if dir := os.Getenv("CALCIFY_LIFECYCLE_FIXTURE_DIR"); dir != "" {
		if err := os.MkdirAll(dir, 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "legacy-source-v1.jsonl"), raw, 0644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestCalcifyLifecycleTypedFixture(t *testing.T) {
	batches, service := lifecycleFixtureBatches(t, ProcessorConfig{CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	for i, batch := range batches {
		fact := batch.Outcomes[0].LifecycleCommand
		if fact == nil || fact.CommandID != fmt.Sprintf("p3-cmd-%02d", i+1) || fact.FiniteBindingDigest != lifecycleFixtureBinding {
			t.Fatalf("typed fact%d missing or unbound: %+v", i, fact)
		}
		if fact.Schema != "calcify-order-lifecycle-command-v1" || fact.SourceProfileHash != service.CalcifySourceProfileHash() || fact.RunID != "p3-run" || fact.VenueSessionID != "p3-session" || fact.InstrumentID != "AAPL" || fact.AccountID != fact.ParticipantID+"-account" || fact.TraceID != fmt.Sprintf("p3-trace-%02d", i+1) || fact.CausationID != "p3-fixture" || fact.CorrelationID != "p3-fixture" || fact.ActorID != fact.ParticipantID+"-actor" || fact.OccurredAt != fmt.Sprintf("2026-10-10T03:00:%02dZ", i) {
			t.Fatalf("decoded metadata drift%d: %+v", i, fact)
		}
		if fact.CommandType != batch.Outcomes[0].CommandType || fact.OrderID != batch.Outcomes[0].OrderID {
			t.Fatal("typed outer identity drift")
		}
		switch fact.CommandType {
		case "SubmitOrder":
			if fact.Submit == nil || fact.Modify != nil || fact.Cancel != nil || fact.Submit.TimeInForce != "DAY" || fact.Submit.Currency != "USD" {
				t.Fatalf("submit payload%d: %+v", i, fact)
			}
		case "ModifyOrder":
			if fact.Modify == nil || fact.Submit != nil || fact.Cancel != nil {
				t.Fatalf("modify payload%d: %+v", i, fact)
			}
		case "CancelOrder":
			if fact.Cancel == nil || fact.Submit != nil || fact.Modify != nil || fact.Cancel.Reason != "finite fixture cancel" {
				t.Fatalf("cancel payload%d: %+v", i, fact)
			}
		}
	}
	if batches[3].Outcomes[0].LifecycleCommand.Modify.QuantityUnits != "4" || batches[3].Outcomes[0].LifecycleCommand.Modify.LimitPrice != "99000000000" {
		t.Fatal("amendment lost total economics")
	}
	if batches[9].Outcomes[0].Result.AcceptedOrder == nil || batches[9].Outcomes[0].Result.Accepted != nil || batches[9].Outcomes[0].Result.Rejected == nil {
		t.Fatal("rejected submit attempted facts changed")
	}
	if dir := os.Getenv("CALCIFY_LIFECYCLE_FIXTURE_DIR"); dir != "" {
		writeLifecycleFixture(t, dir, batches, service)
	}
}

func writeLifecycleFixture(t *testing.T, dir string, batches []VenueEventBatch, service *app.Service) {
	t.Helper()
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatal(err)
	}
	raw := lifecycleJSONL(t, batches)
	if err := os.WriteFile(filepath.Join(dir, "finite-lifecycle-source-v1.jsonl"), raw, 0644); err != nil {
		t.Fatal(err)
	}
	var lines []map[string]any
	for _, batch := range batches {
		line, err := json.Marshal(batch)
		if err != nil {
			t.Fatal(err)
		}
		lines = append(lines, map[string]any{"batchId": batch.BatchID, "payloadChecksum": batch.PayloadChecksum, "bytes": len(line), "sha256": sha256Hex(line)})
	}
	var commands []json.RawMessage
	for _, delivery := range lifecycleFixtureDeliveries() {
		commands = append(commands, json.RawMessage(delivery.Data()))
	}
	manifest := map[string]any{
		"schema": "calcify-finite-lifecycle-fixture-v1", "profile": json.RawMessage(lifecycleFixtureProfile),
		"sourceProfileHash": service.CalcifySourceProfileHash(), "finiteBindingDigest": lifecycleFixtureBinding,
		"bindingModelOnly": true, "lifecycleCommandSchema": "calcify-order-lifecycle-command-v1",
		"sourcePolicy": "calcify-limit-day-v1", "commandTopic": "CALCIFY_P3_COMMANDS", "sourceTopic": "CALCIFY_P3_SOURCE",
		"partition": 0, "sourceGeneration": 1, "batchSize": 1, "sourceOffsetVector": []int{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
		"gapTestOffsetVector": []int{0, 2, 5, 6, 9, 12, 14, 17, 20, 21, 25, 28}, "commands": commands,
		"accounts": []map[string]string{{"participantId": "buyer", "accountId": "buyer-account", "side": "BUY"}, {"participantId": "seller", "accountId": "seller-account", "side": "SELL"}},
		"expected": map[string]int{"outcomes": 12, "applied": 8, "rejected": 4, "trades": 3, "executedUnits": 6, "members": 15, "retainedIdentities": 5},
		"bytes":    len(raw), "sha256": sha256Hex(raw), "lines": lines,
		"legacySha256": "bdd4df11f562ba2f4dd217168a3e81e60f96fa4c3fa4a25c01e75830dda26506", "legacyBytes": 16647,
	}
	encoded, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "finite-lifecycle-source-v1-manifest.json"), append(encoded, '\n'), 0644); err != nil {
		t.Fatal(err)
	}
}

const lifecycleFixtureBinding = "37ecf149ea1c94e9613b5e23941da3b1ff7fa131d57a77b1875b5b8a1bce7a61"

func TestCalcifyLifecycleRoutingAndAttemptedPayloadArePreserved(t *testing.T) {
	service := lifecycleFixtureService(t)
	seed := lifecycleFixtureDeliveries()[0]
	publisher := &fakePublisher{}
	source := &fakeSource{deliveries: []CommandDelivery{seed}}
	p := NewProcessor(service, source, publisher, ProcessorConfig{BatchSize: 1, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	if _, err := p.ProcessOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	for i, kind := range []string{"ModifyOrder", "CancelOrder"} {
		payload := map[string]string{"commandId": fmt.Sprintf("route-%d", i), "runId": "p3-run", "venueSessionId": "claimed-other", "instrumentId": "claimed-other", "orderId": "p0-1", "participantId": "seller", "accountId": "seller-account", "occurredAt": "2026-10-10T03:00:12Z", "quantityUnits": "+03", "limitPrice": "0100000000000", "reason": ""}
		delivery := newFakeDelivery("reef.cmd.v1.p00.p3-session.AAPL."+kind, uint64(i+20), payload)
		source.deliveries = []CommandDelivery{delivery}
		if _, err := p.ProcessOnce(context.Background()); err != nil {
			t.Fatal(err)
		}
		outcome := publisher.batches[len(publisher.batches)-1].Outcomes[0]
		fact := outcome.LifecycleCommand
		if fact == nil || fact.VenueSessionID != "p3-session" || fact.InstrumentID != "AAPL" || fact.ParticipantID != "seller" || fact.AccountID != "seller-account" || fact.ActorID != "" || fact.TraceID != "" {
			t.Fatal("routed context drift")
		}
		if outcome.PayloadHash != sha256Hex(delivery.Data()) {
			t.Fatal("original request hash lost")
		}
		if kind == "ModifyOrder" && (fact.Modify.QuantityUnits != "+03" || fact.Modify.LimitPrice != "0100000000000") {
			t.Fatal("attempted numeric spelling normalized")
		}
		if kind == "CancelOrder" && fact.Cancel.Reason != "" {
			t.Fatal("empty canonical reason invented")
		}
	}
}

func TestCalcifyLifecyclePoisonHasNoInventedTypedCommand(t *testing.T) {
	for _, delivery := range []CommandDelivery{
		&fakeDelivery{subject: "reef.cmd.v1.p00.p3-session.AAPL.SubmitOrder", seq: 1, data: []byte(`{"commandId":"poison","quantityUnits":[1]}`)},
		newFakeDelivery("reef.cmd.v1.p00.p3-session.AAPL.Unknown", 2, map[string]string{"commandId": "unsupported"}),
	} {
		publisher := &fakePublisher{}
		p := NewProcessor(lifecycleFixtureService(t), &fakeSource{deliveries: []CommandDelivery{delivery}}, publisher, ProcessorConfig{BatchSize: 1, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
		if _, err := p.ProcessOnce(context.Background()); err != nil {
			t.Fatal(err)
		}
		outcome := publisher.batches[0].Outcomes[0]
		if outcome.LifecycleCommand != nil || outcome.Status != "failed" || outcome.Result.Rejected == nil {
			t.Fatal("poison fabricated typed source evidence")
		}
	}
}

func TestCalcifyLifecycleFailedPublishRollbackAndRetryPreserveFact(t *testing.T) {
	service := lifecycleFixtureService(t)
	publisher := &fakeAtomicPublisher{err: errors.New("injected publish failure")}
	source := &fakeSource{deliveries: lifecycleFixtureDeliveries()[:1]}
	p := NewProcessor(service, source, publisher, ProcessorConfig{BatchSize: 1, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	fixed := time.Date(2026, 10, 10, 3, 0, 0, 0, time.UTC)
	p.now = func() time.Time { return fixed }
	if _, err := p.ProcessOnce(context.Background()); err == nil {
		t.Fatal("failed publish accepted")
	}
	if len(service.Snapshot().Orders) != 0 {
		t.Fatal("failed publish retained identity")
	}
	publisher.err = nil
	source.deliveries = lifecycleFixtureDeliveries()[:1]
	if _, err := p.ProcessOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(publisher.batches) != 2 || len(service.Snapshot().Orders) != 1 {
		t.Fatal("retry did not rebuild state")
	}
	first, _ := json.Marshal(publisher.batches[0])
	second, _ := json.Marshal(publisher.batches[1])
	if !bytes.Equal(first, second) {
		t.Fatal("retry changed typed facts/checksum")
	}
}
