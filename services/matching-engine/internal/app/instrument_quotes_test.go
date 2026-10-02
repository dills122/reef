package app

import (
	"encoding/json"
	"testing"
)

func TestInstrumentQuoteConfigurationRejectsInvalidStartupCatalog(t *testing.T) {
	for _, raw := range []string{`null`, `[]`, `{"AAPL":"usd"}`, `{"AAPL":"ZZZ"}`, `{"AAPL":"XXX"}`, `{"":"USD"}`} {
		if _, err := InstrumentQuoteCurrenciesFromJSON(raw); err == nil {
			t.Fatalf("invalid catalog accepted: %s", raw)
		}
	}
	for _, raw := range []string{"", `{}`, `{"AAPL":"USD","CAD-EQUITY":"CAD"}`} {
		if _, err := InstrumentQuoteCurrenciesFromJSON(raw); err != nil {
			t.Fatalf("valid catalog rejected: %v", err)
		}
	}
}

func TestInstrumentQuoteSnapshotDefaultAndReorderedCatalogCompatibility(t *testing.T) {
	legacy := NewService().Snapshot()
	data, err := json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	var decoded Snapshot
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Metadata.InstrumentQuoteCatalogHash != "" {
		t.Fatal("legacy/default snapshot acquired explicit catalog hash")
	}
	if _, ok := Restore(decoded); !ok {
		t.Fatal("default snapshot without catalog hash rejected")
	}
	first, _ := InstrumentQuoteCurrenciesFromJSON(`{"AAPL":"USD","CAD-EQUITY":"CAD"}`)
	reordered, _ := InstrumentQuoteCurrenciesFromJSON(` { "CAD-EQUITY": "CAD", "AAPL": "USD" } `)
	if _, ok := Restore(NewService(first).Snapshot(), reordered); !ok {
		t.Fatal("equivalent reordered catalog rejected")
	}
}
