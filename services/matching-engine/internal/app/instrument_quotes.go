package app

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
)

// InstrumentQuoteCurrenciesFromJSON validates startup reference configuration.
// Missing configuration preserves legacy USD specifications; explicit catalogs fail closed.
func InstrumentQuoteCurrenciesFromJSON(raw string) (Option, error) {
	if raw == "" {
		return func(*Service) {}, nil
	}
	var quotes map[string]string
	if err := json.Unmarshal([]byte(raw), &quotes); err != nil || quotes == nil {
		return nil, fmt.Errorf("instrument quotes must be a JSON object")
	}
	for instrument, quote := range quotes {
		if instrument == "" || !validQuoteCurrency(quote) {
			return nil, fmt.Errorf("invalid instrument quote specification for %q", instrument)
		}
	}
	return WithInstrumentQuoteCurrencies(quotes), nil
}

func (s *Service) instrumentQuoteCatalogHash() string {
	if s.instrumentQuotes == nil {
		return ""
	}
	// encoding/json sorts string map keys; explicit empty catalog differs from legacy USD.
	data, _ := json.Marshal(s.instrumentQuotes)
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}
