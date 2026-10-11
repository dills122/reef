package app

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestCalcifyFiniteSnapshotCompatibility(t *testing.T) {
	profile := `{"schema":"calcify-finite-source-v1","runIds":["p3-run"],"venueSessionId":"p3-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":100000000000}`
	option, err := CalcifySourceProfileFromJSON(profile, "0")
	if err != nil {
		t.Fatal(err)
	}
	options := []Option{WithTerminalOrderRetentionLimit(0), option}
	old := NewService(options...).Snapshot()
	oldRaw, _ := json.Marshal(old)
	if strings.Contains(string(oldRaw), "calcifyFiniteBindingDigest") {
		t.Fatal("OFF metadata bytes changed")
	}
	digest := strings.Repeat("1", 64)
	boundOptions := append(append([]Option(nil), options...), WithCalcifyFiniteBindingDigest(digest))
	bound := NewService(boundOptions...).Snapshot()
	if bound.Metadata.CalcifyFiniteBindingDigest != digest || old.Checksum == bound.Checksum {
		t.Fatal("binding absent from checksum")
	}
	if _, ok := Restore(old, boundOptions...); ok {
		t.Fatal("unbound snapshot enabled")
	}
	if _, ok := Restore(bound, options...); ok {
		t.Fatal("bound snapshot restored OFF")
	}
	wrong := append(append([]Option(nil), options...), WithCalcifyFiniteBindingDigest(strings.Repeat("2", 64)))
	if _, ok := Restore(bound, wrong...); ok {
		t.Fatal("changed binding restored")
	}
	tampered := bound
	tampered.Metadata.CalcifyFiniteBindingDigest = strings.Repeat("2", 64)
	if _, ok := Restore(tampered, wrong...); ok {
		t.Fatal("tampered checksum accepted")
	}
	restored, ok := Restore(bound, boundOptions...)
	if !ok || restored.Snapshot().Checksum != bound.Checksum {
		t.Fatal("bound snapshot parity lost")
	}
	off, ok := Restore(old, options...)
	if !ok {
		t.Fatal("old OFF snapshot refused")
	}
	after, _ := json.Marshal(off.Snapshot())
	if string(after) != string(oldRaw) {
		t.Fatal("old OFF bytes drift")
	}
}
