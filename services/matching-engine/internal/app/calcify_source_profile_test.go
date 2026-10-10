package app

import (
	"encoding/json"
	"strconv"
	"strings"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func finiteSourceRaw() string {
	return `{"schema":"calcify-finite-source-v1","runIds":["p0-run","p0-other"],"venueSessionId":"p0-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":1000}`
}

func finiteSourceOption(t *testing.T, raw string) Option {
	t.Helper()
	option, err := CalcifySourceProfileFromJSON(raw, "0")
	if err != nil {
		t.Fatal(err)
	}
	return option
}

func finiteSourceService(t *testing.T) *Service {
	return NewService(WithTerminalOrderRetentionLimit(0), finiteSourceOption(t, finiteSourceRaw()))
}

func finiteSubmit(id string) domain.SubmitOrder {
	return domain.SubmitOrder{CommandID: "submit-" + id, RunID: "p0-run", VenueSessionID: "p0-session", InstrumentID: "AAPL", OrderID: id,
		ParticipantID: "buyer", AccountID: "buyer", Side: domain.SideBuy, OrderType: "LIMIT", TimeInForce: "DAY", QuantityUnits: "2", LimitPrice: "90", Currency: "USD", OccurredAt: "2026-10-07T12:00:00Z"}
}

func TestCalcifySourceStartupRefusesUnboundedOrMalformedProfile(t *testing.T) {
	raw := finiteSourceRaw()
	for _, retention := range []string{"", "1", "250000", "-1", "invalid", "00"} {
		if _, err := CalcifySourceProfileFromJSON(raw, retention); err == nil {
			t.Fatalf("accepted retention %q", retention)
		}
	}
	for _, bad := range []string{"null", "{}", raw + ` {}`, strings.Replace(raw, `"maxOrderIds":8`, `"maxOrderIds":0`, 1),
		strings.Replace(raw, `"maxOrderIds":8`, `"maxOrderIds":10001`, 1), strings.Replace(raw, `"maxQuantityUnits":10`, `"maxQuantityUnits":1000001`, 1),
		strings.Replace(raw, `"maxLimitPrice":1000`, `"maxLimitPrice":1000000000001`, 1), strings.Replace(raw, `"p0-other"`, `"p0-run"`, 1),
		strings.Replace(raw, `"p0-other"`, `"a","b"`, 1), strings.Replace(raw, `"schema":`, `"unknown":1,"schema":`, 1),
		strings.Replace(raw, `"AAPL"`, `"`+strings.Repeat("x", 129)+`"`, 1)} {
		if _, err := CalcifySourceProfileFromJSON(bad, "0"); err == nil {
			t.Fatalf("accepted malformed profile: %s", bad)
		}
	}
	option := finiteSourceOption(t, raw)
	if err := NewService(option, WithTerminalOrderRetentionLimit(1)).ValidateCalcifySourceProfile(); err == nil {
		t.Fatal("retention override accepted")
	}
	if err := NewService(WithTerminalOrderRetentionLimit(0), option, WithInstrumentQuoteCurrencies(map[string]string{"AAPL": "EUR"})).ValidateCalcifySourceProfile(); err == nil {
		t.Fatal("quote mismatch accepted")
	}
	if _, err := CalcifySourceProfileFromJSON("", "250000"); err != nil {
		t.Fatal("disabled profile changed legacy startup")
	}
}

func TestCalcifySourceLifecycleAndTerminalIdentitySurviveRestore(t *testing.T) {
	service := finiteSourceService(t)
	sell := finiteSubmit("p0-1")
	sell.Side = domain.SideSell
	sell.AccountID = "seller"
	sell.ParticipantID = "seller"
	sell.LimitPrice = "100"
	if r := service.SubmitOrder(sell); r.Accepted == nil || len(r.Trades) != 0 {
		t.Fatalf("resting sell: %+v", r)
	}
	buy := finiteSubmit("p0-2")
	if r := service.SubmitOrder(buy); r.Accepted == nil || len(r.Trades) != 0 {
		t.Fatalf("resting buy: %+v", r)
	}
	modify := domain.ModifyOrder{CommandID: "modify-fill", RunID: buy.RunID, VenueSessionID: buy.VenueSessionID, InstrumentID: buy.InstrumentID, OrderID: buy.OrderID, ParticipantID: buy.ParticipantID, AccountID: buy.AccountID, QuantityUnits: "2", LimitPrice: "100", OccurredAt: "2026-10-07T12:00:01Z"}
	r := service.ModifyOrder(modify)
	if r.Accepted == nil || len(r.Trades) != 1 || r.Trades[0].QuantityUnits != "2" || r.Trades[0].Price != "100" || r.Trades[0].BuyOrderID != buy.OrderID || r.Trades[0].SellOrderID != sell.OrderID {
		t.Fatalf("amend fill economics: %+v", r)
	}
	zero := finiteSubmit("p0-3")
	if r := service.SubmitOrder(zero); r.Accepted == nil || len(r.Trades) != 0 {
		t.Fatal(r)
	}
	modify.OrderID = zero.OrderID
	modify.CommandID = "modify-zero"
	modify.QuantityUnits = "3"
	modify.LimitPrice = "80"
	if r := service.ModifyOrder(modify); r.Accepted == nil || len(r.Trades) != 0 {
		t.Fatalf("zero-trade amend: %+v", r)
	}
	cancel := domain.CancelOrder{CommandID: "cancel-zero", RunID: zero.RunID, VenueSessionID: zero.VenueSessionID, InstrumentID: zero.InstrumentID, OrderID: zero.OrderID, ParticipantID: zero.ParticipantID, AccountID: zero.AccountID, OccurredAt: "2026-10-07T12:00:02Z"}
	if r := service.CancelOrder(cancel); r.Accepted == nil || len(r.Trades) != 0 {
		t.Fatalf("zero-trade cancel: %+v", r)
	}
	other := finiteSubmit("p0-1")
	other.RunID = "p0-other"
	if r := service.SubmitOrder(other); r.Accepted == nil {
		t.Fatalf("cross-run raw ID reuse: %+v", r)
	}
	if _, ok := service.SnapshotForScope(BookScope{RunID: "p0-run", VenueSessionID: "p0-session", InstrumentID: "AAPL"}); ok {
		t.Fatal("collocated profile exposed incomplete restorable snapshot")
	}
	snapshot := service.Snapshot()
	raw, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	var persisted Snapshot
	if err := json.Unmarshal(raw, &persisted); err != nil {
		t.Fatal(err)
	}
	restored, ok := Restore(persisted, WithTerminalOrderRetentionLimit(0), finiteSourceOption(t, finiteSourceRaw()))
	if !ok || restored.Snapshot().Checksum != snapshot.Checksum {
		t.Fatal("profile snapshot round-trip failed")
	}
	for _, candidate := range []*Service{service, restored} {
		for _, id := range []string{"p0-1", "p0-2", "p0-3"} {
			cmd := finiteSubmit(id)
			if r := candidate.SubmitOrder(cmd); r.Rejected == nil || r.Rejected.Code != "DUPLICATE_ORDER_ID" {
				t.Fatalf("terminal ID reused: %s %+v", id, r)
			}
		}
		if r := candidate.CancelOrder(cancel); r.Rejected == nil || r.Rejected.Code != "INVALID_STATE" {
			t.Fatalf("cancelled lifecycle lost: %+v", r)
		}
	}
}

func TestCalcifySourceEnvelopeRejectsWithoutStateMutation(t *testing.T) {
	service := finiteSourceService(t)
	base := finiteSubmit("p0-1")
	for _, mutate := range []func(*domain.SubmitOrder){
		func(c *domain.SubmitOrder) { c.RunID = "foreign" }, func(c *domain.SubmitOrder) { c.VenueSessionID = "foreign" }, func(c *domain.SubmitOrder) { c.InstrumentID = "foreign" },
		func(c *domain.SubmitOrder) { c.OrderID = "p0-9" }, func(c *domain.SubmitOrder) { c.OrderID = "p0-01" }, func(c *domain.SubmitOrder) { c.QuantityUnits = "11" },
		func(c *domain.SubmitOrder) { c.LimitPrice = "1001" }, func(c *domain.SubmitOrder) { c.ParticipantID = strings.Repeat("x", 129) }, func(c *domain.SubmitOrder) { c.ActorID = strings.Repeat("x", 129) },
		func(c *domain.SubmitOrder) { c.CommandID = "" },
		func(c *domain.SubmitOrder) { c.OccurredAt = "" },
	} {
		c := base
		mutate(&c)
		before := service.Snapshot().Checksum
		rb := service.BeginBatch([]BookScope{{RunID: c.RunID, VenueSessionID: c.VenueSessionID, InstrumentID: c.InstrumentID}})
		r := service.SubmitOrderInBatch(rb, c)
		rb.Rollback()
		if r.Rejected == nil || r.Rejected.Code != "CALCIFY_SOURCE_PROFILE_REJECTED" {
			t.Fatalf("envelope accepted: %+v", r)
		}
		// Valid scopes may create one empty book during BeginBatch, but foreign scopes never do.
		if !service.calcifySourceScope(c.RunID, c.VenueSessionID, c.InstrumentID) && before != service.Snapshot().Checksum {
			t.Fatal("foreign scope allocated book")
		}
		if service.orderIndex.len() != 0 {
			t.Fatal("rejected command retained an order")
		}
	}
	if r := service.SubmitOrder(base); r.Accepted == nil {
		t.Fatal(r)
	}
	for _, r := range []domain.SubmitOrderResult{
		service.ModifyOrder(domain.ModifyOrder{RunID: base.RunID, VenueSessionID: base.VenueSessionID, InstrumentID: base.InstrumentID, OrderID: base.OrderID, QuantityUnits: "11", LimitPrice: "90", OccurredAt: base.OccurredAt}),
		service.CancelOrder(domain.CancelOrder{RunID: "foreign", VenueSessionID: base.VenueSessionID, InstrumentID: base.InstrumentID, OrderID: base.OrderID, OccurredAt: base.OccurredAt}),
	} {
		if r.Rejected == nil || r.Rejected.Code != "CALCIFY_SOURCE_PROFILE_REJECTED" {
			t.Fatal(r)
		}
	}
	// Fill finite namespace, including terminal records; no new identity can exceed bound.
	for i := 2; i <= 8; i++ {
		c := finiteSubmit("p0-" + strconv.Itoa(i))
		if r := service.SubmitOrder(c); r.Accepted == nil {
			t.Fatal(r)
		}
	}
	if got := service.orderIndex.len(); got != 8 {
		t.Fatalf("retained records=%d", got)
	}
}

func TestCalcifySourceRestoreRefusesChangedProfileAndForeignState(t *testing.T) {
	service := finiteSourceService(t)
	service.SubmitOrder(finiteSubmit("p0-1"))
	snapshot := service.Snapshot()
	option := finiteSourceOption(t, finiteSourceRaw())
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0)); ok {
		t.Fatal("profile silently disabled")
	}
	changed := finiteSourceOption(t, strings.Replace(finiteSourceRaw(), `"maxOrderIds":8`, `"maxOrderIds":7`, 1))
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0), changed); ok {
		t.Fatal("changed budget restored")
	}
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(1), option); ok {
		t.Fatal("eviction enabled on restore")
	}
	legacy := NewService(WithTerminalOrderRetentionLimit(0)).Snapshot()
	if _, ok := Restore(legacy, WithTerminalOrderRetentionLimit(0), option); ok {
		t.Fatal("unbound legacy snapshot restored")
	}
	snapshot.Metadata.CalcifySourceProfileHash = strings.Repeat("f", 64)
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0), option); ok {
		t.Fatal("changed checksum-covered profile restored")
	}
	snapshot = service.Snapshot()
	snapshot.Orders[0].OrderID = "p0-9"
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())
	if _, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0), option); ok {
		t.Fatal("out-of-range record restored")
	}
	// Semantic run-list order does not change frozen profile hash.
	reversed := finiteSourceOption(t, strings.Replace(finiteSourceRaw(), `"p0-run","p0-other"`, `"p0-other","p0-run"`, 1))
	if _, ok := Restore(service.Snapshot(), WithTerminalOrderRetentionLimit(0), reversed); !ok {
		t.Fatal("run-list order changed profile")
	}
}

func TestCalcifySourceV1DigestAndRestoreIgnoreInputSerialization(t *testing.T) {
	// Pin existing v1 bytes/hash: producer and restore must not drift together.
	const canonical = `{"schema":"calcify-finite-source-v1","runIds":["p0-other","p0-run"],"venueSessionId":"p0-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":1000}`
	const digest = "162badd14581eb2de857e228b72b9ad1196eb9a60358c99f195ea02f0fadd66c"
	service := finiteSourceService(t)
	if result := service.SubmitOrder(finiteSubmit("p0-1")); result.Accepted == nil {
		t.Fatal(result)
	}
	snapshot := service.Snapshot()
	if snapshot.Metadata.CalcifySourceProfileHash != digest {
		t.Fatal("persisted v1 profile identity changed")
	}
	var object map[string]any
	if err := json.Unmarshal([]byte(finiteSourceRaw()), &object); err != nil {
		t.Fatal(err)
	}
	pretty, err := json.MarshalIndent(object, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	reordered := `{"maxLimitPrice":1000,"currency":"USD","maxQuantityUnits":10,"instrumentId":"AAPL","maxOrderIds":8,"venueSessionId":"p0-session","runIds":["p0-\u0072un","p0-other"],"schema":"calcify-finite-source-v1"}`
	for _, raw := range []string{finiteSourceRaw(), canonical, string(pretty) + "\n", reordered} {
		option := finiteSourceOption(t, raw)
		candidate := NewService(WithTerminalOrderRetentionLimit(0), option)
		bytes, err := json.Marshal(candidate.calcifySourceProfile)
		if err != nil || string(bytes) != canonical || candidate.calcifySourceProfileHash() != digest {
			t.Fatalf("equivalent input changed v1 identity: %s %v", bytes, err)
		}
		restored, ok := Restore(snapshot, WithTerminalOrderRetentionLimit(0), option)
		if !ok || restored.Snapshot().Checksum != snapshot.Checksum {
			t.Fatal("equivalent serialization blocked persisted snapshot restore")
		}
		if result := restored.SubmitOrder(finiteSubmit("p0-1")); result.Rejected == nil || result.Rejected.Code != "DUPLICATE_ORDER_ID" {
			t.Fatal("restored identity reservation lost")
		}
	}
	// Future schemas/extensions need explicit compatibility design, not weaker restore checks.
	for _, raw := range []string{strings.Replace(canonical, "calcify-finite-source-v1", "calcify-finite-source-v2", 1),
		strings.Replace(canonical, `"schema":`, `"futureField":true,"schema":`, 1)} {
		if _, err := CalcifySourceProfileFromJSON(raw, "0"); err == nil {
			t.Fatal("unversioned profile extension accepted")
		}
	}
}
