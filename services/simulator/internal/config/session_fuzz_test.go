package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func FuzzLoadSessionFile(f *testing.F) {
	f.Add("session.yaml", validYAML)
	f.Add("session.json", `{"session":{"seed":42,"mode":"chaos"},"runtime":{"baseUrl":"http://localhost:8080","duration":"30s","workers":2,"ratePerSecond":0,"timeout":"5s","traceCheckLimit":10},"market":{"equities":[{"symbol":"AAPL","instrumentId":"AAPL","startingPriceNanos":190000000000,"volatilityBps":100,"spreadBps":5}]},"actors":[{"actorId":"a-1","actorType":"retail","strategyId":"dip_buyer","weight":100}],"mix":{"actions":{"submitPct":60,"modifyPct":30,"cancelPct":10},"sideBias":{"buyPct":50,"sellPct":50}}}`)
	f.Add("bad.yaml", "session:\n  seed: nope\n")
	f.Add("bad.json", `{"session":`)
	f.Add("unknown.txt", validYAML)

	f.Fuzz(func(t *testing.T, name, content string) {
		if len(name) > 64 || len(content) > 4096 {
			return
		}

		path := filepath.Join(t.TempDir(), "session"+extensionForFuzzName(name))
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatalf("write fuzz config: %v", err)
		}

		cfg, err := LoadSessionFile(path)
		if err != nil {
			return
		}
		if err := ValidateSessionFile(cfg); err != nil {
			t.Fatalf("loaded invalid session config: %v", err)
		}
		runtime, err := ToRuntimeConfig(cfg)
		if err != nil {
			t.Fatalf("loaded session did not convert to runtime config: %v", err)
		}
		if runtime.Seed == 0 || runtime.BaseURL == "" || len(runtime.Equities) == 0 || len(runtime.Actors) == 0 {
			t.Fatalf("runtime config missing required normalized fields: %+v", runtime)
		}
	})
}

func extensionForFuzzName(name string) string {
	lower := strings.ToLower(name)
	switch {
	case strings.HasSuffix(lower, ".json"):
		return ".json"
	case strings.HasSuffix(lower, ".txt"):
		return ".txt"
	default:
		return ".yaml"
	}
}
