package streamdirect

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func v2Fixture(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "contracts", "calcify", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestFiniteBindingV2MatchesTheIndependentReferenceBytesAndDigest(t *testing.T) {
	o, err := ParseFiniteBindingV2(v2Fixture(t, "finite-binding-v2.json"))
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := o.CanonicalBytes()
	if err != nil {
		t.Fatal(err)
	}
	if got, want := hexOf(canonical), strings.TrimSpace(string(v2Fixture(t, "finite-binding-v2.hex"))); got != want {
		t.Fatalf("canonical bytes differ from the Python reference\n got %s\nwant %s", got, want)
	}
	var manifest struct {
		Digest, FinanceConfigDigest string
		Bytes                       int
	}
	raw := v2Fixture(t, "finite-binding-v2-manifest.json")
	if err := json.Unmarshal([]byte(strings.NewReplacer("financeConfigDigest", "FinanceConfigDigest").Replace(string(raw))), &manifest); err != nil {
		t.Fatal(err)
	}
	digest, _ := o.Digest()
	if digest != manifest.Digest || len(canonical) != manifest.Bytes {
		t.Fatalf("digest/bytes differ from manifest: %s %d vs %s %d", digest, len(canonical), manifest.Digest, manifest.Bytes)
	}
}

func hexOf(b []byte) string { return hex.EncodeToString(b) }

// mutate applies an edit to the fixture JSON object and returns the re-encoded bytes.
func mutateV2(t *testing.T, edit func(map[string]any)) []byte {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(v2Fixture(t, "finite-binding-v2.json"), &m); err != nil {
		t.Fatal(err)
	}
	edit(m)
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func TestFiniteBindingV2RefusesEveryStructuralViolation(t *testing.T) {
	topics := func(m map[string]any) []any { return m["topics"].([]any) }
	cases := map[string]func(map[string]any){
		"v1 schema":             func(m map[string]any) { m["schema"] = "calcify-finite-binding-v1" },
		"missing finance field": func(m map[string]any) { delete(m, "financeApplicationId") },
		"unknown field":         func(m map[string]any) { m["surprise"] = "x" },
		"five topics":           func(m map[string]any) { m["topics"] = topics(m)[:5] },
		"role order":            func(m map[string]any) { a := topics(m); a[5], a[6] = a[6], a[5] },
		"duplicate topic name":  func(m map[string]any) { topics(m)[6].(map[string]any)["name"] = topics(m)[5].(map[string]any)["name"] },
		"duplicate topic id": func(m map[string]any) {
			topics(m)[6].(map[string]any)["topicId"] = topics(m)[5].(map[string]any)["topicId"]
		},
		"finance changelog name":  func(m map[string]any) { topics(m)[7].(map[string]any)["name"] = "other-changelog" },
		"config digest":           func(m map[string]any) { m["financeConfigDigest"] = strings.Repeat("0", 64) },
		"budget captures":         func(m map[string]any) { m["financeBudget"].(map[string]any)["captures"] = "127" },
		"nonzero genesis offset":  func(m map[string]any) { m["initialFinanceControlNextOffset"] = "1" },
		"opening is a party":      func(m map[string]any) { m["openingAccountId"] = "buyer-account" },
		"non-ascii opening":       func(m map[string]any) { m["openingAccountId"] = "ópening" },
		"assets equal":            func(m map[string]any) { m["baseAssetId"] = m["quoteCurrency"] },
		"store name":              func(m map[string]any) { m["financeStoreName"] = "other" },
		"state version":           func(m map[string]any) { m["financeStateVersion"] = "calcify-finite-finance-state-v2" },
		"finance app prefix":      func(m map[string]any) { m["financeApplicationId"] = "p3-fixture-capture-x" },
		"finance app is prefix":   func(m map[string]any) { m["financeApplicationId"] = "p3-fixture" },
		"control id prefix":       func(m map[string]any) { m["controlTransactionalId"] = "p3-fixture-finance-writer" },
		"control id equals group": func(m map[string]any) { m["controlTransactionalId"] = m["commandConsumerGroup"] },
	}
	for name, edit := range cases {
		if _, err := ParseFiniteBindingV2(mutateV2(t, edit)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestFiniteBindingV2EveryFieldChangesTheDigestAndV1V2NeverConfuse(t *testing.T) {
	base, err := ParseFiniteBindingV2(v2Fixture(t, "finite-binding-v2.json"))
	if err != nil {
		t.Fatal(err)
	}
	want, _ := base.Digest()
	changed := map[string]func(*FiniteOpeningV2){
		"financeApplicationId": func(o *FiniteOpeningV2) {
			o.FinanceApplicationID += "x"
			o.Topics[7].Name = o.FinanceApplicationID + "-finite-finance-changelog"
		},
		"controlTransactionalId": func(o *FiniteOpeningV2) { o.ControlTransactionalID += "x" },
		"openingAccountId": func(o *FiniteOpeningV2) {
			o.OpeningAccountID = "opening2"
			o.FinanceConfigDigest = o.FinanceConfigDigestFor()
		},
		"control topic id":     func(o *FiniteOpeningV2) { o.Topics[5].TopicID = "AAAAAAAAAAAAAAAAAAAACQ" },
		"decisions topic name": func(o *FiniteOpeningV2) { o.Topics[6].Name += "x" },
		"source generation":    func(o *FiniteOpeningV2) { o.SourceGeneration++ },
	}
	for name, edit := range changed {
		copy := *base
		copy.Topics = append([]FiniteTopic(nil), base.Topics...)
		edit(&copy)
		got, err := copy.Digest()
		if err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if got == want {
			t.Errorf("%s: digest did not change", name)
		}
	}
	// v1 and v2 never confuse: the v2 parser refuses the v1 fixture; the v1 digest path refuses a v2 schema.
	if _, err := ParseFiniteBindingV2(v2Fixture(t, "finite-binding-v1.json")); err == nil {
		t.Fatal("v2 parser accepted a v1 opening")
	}
	if _, err := base.FiniteOpening.Digest(); err == nil {
		t.Fatal("v1 digest path accepted a v2 opening")
	}
}
