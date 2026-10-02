package streamdirect

import (
	"encoding/json"
	"github.com/dills122/reef/services/matching-engine/internal/app"
	"os"
	"testing"
)

// Calcify needs validated command run scope even when modify creates trades
// without an acceptedOrder fact. LIMIT_HIDDEN remains a supported wire alias.
func TestCalcifyOutcomeRunScopeAndHiddenLimitSource(t *testing.T) {
	service := app.NewService()
	p := NewProcessor(service, &fakeSource{}, &fakePublisher{}, ProcessorConfig{Partition: 0, ShardID: "calcify-contract", EventStreamName: "source"})
	submit := func(id, side, price string) map[string]string {
		return map[string]string{"commandId": "cmd-" + id, "orderId": id, "runId": "run-a", "venueSessionId": "session-1", "instrumentId": "AAPL", "participantId": "participant-" + id, "accountId": "account-" + id, "side": side, "orderType": "LIMIT_HIDDEN", "quantityUnits": "2", "limitPrice": price, "currency": "USD", "timeInForce": "DAY", "occurredAt": "2026-09-30T20:00:00Z"}
	}
	deliveries := []CommandDelivery{
		newFakeDelivery("reef.cmd.v1.p00.session-1.AAPL.SubmitOrder", 1, submit("buy", "BUY", "100")),
		newFakeDelivery("reef.cmd.v1.p00.session-1.AAPL.SubmitOrder", 2, submit("sell", "SELL", "200")),
		newFakeDelivery("reef.cmd.v1.p00.session-1.AAPL.ModifyOrder", 3, map[string]string{"commandId": "cmd-modify", "orderId": "sell", "runId": "run-a", "venueSessionId": "session-1", "instrumentId": "AAPL", "participantId": "participant-sell", "accountId": "account-sell", "quantityUnits": "2", "limitPrice": "100", "occurredAt": "2026-09-30T20:00:01Z"}),
	}
	batch, _, rollback, err := p.buildBatch(deliveries, "2026-09-30T20:00:00Z")
	if err != nil {
		t.Fatal(err)
	}
	defer rollback.Rollback()
	encoded, err := json.Marshal(batch)
	if err != nil {
		t.Fatal(err)
	}
	var wire struct {
		Outcomes []struct {
			RunID string `json:"runId"`
		} `json:"outcomes"`
	}
	if err := json.Unmarshal(encoded, &wire); err != nil {
		t.Fatal(err)
	}
	for i, outcome := range wire.Outcomes {
		if outcome.RunID != "run-a" {
			t.Fatalf("outcome %d runId=%q; Calcify cannot disambiguate trade lookup", i, outcome.RunID)
		}
	}
	if len(batch.Outcomes[2].Result.Trades) != 1 {
		t.Fatal("modify must produce one trade")
	}
	if batch.Outcomes[0].Result.AcceptedOrder.OrderType != "LIMIT_HIDDEN" {
		t.Fatal("source must preserve public alias")
	}
	if path := os.Getenv("CALCIFY_CONTRACT_FIXTURE_OUT"); path != "" {
		if err := os.WriteFile(path, append(encoded, '\n'), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
