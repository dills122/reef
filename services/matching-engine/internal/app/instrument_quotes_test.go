package app

import "testing"

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
