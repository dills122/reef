package streamdirect

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func finiteGolden(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "contracts", "calcify", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}
func finiteGoldenWitness(t *testing.T) *FiniteStartupWitness {
	t.Helper()
	raw := finiteGolden(t, "finite-startup-v1.json")
	var challenge struct {
		Nonce         string `json:"nonce"`
		BindingDigest string `json:"bindingDigest"`
	}
	if err := json.Unmarshal(raw, &challenge); err != nil {
		t.Fatal(err)
	}
	w, err := ParseFiniteStartup(raw, challenge.Nonce, challenge.BindingDigest)
	if err != nil {
		t.Fatal(err)
	}
	return w
}
func TestFiniteBindingManagerGoldens(t *testing.T) {
	w := finiteGoldenWitness(t)
	b, err := w.Opening.CanonicalBytes()
	if err != nil {
		t.Fatal(err)
	}
	gold, err := hex.DecodeString(strings.TrimSpace(string(finiteGolden(t, "finite-binding-v1.hex"))))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(b, gold) || len(b) != 786 || finiteHash(b) != "1fe1749dba2b35803eabb41fc1f9106477147a2ad9966ecf2087eb5f78dc2c8a" {
		t.Fatal("frozen binding drift")
	}
	budget, err := w.Opening.BudgetCanonical()
	if err != nil || len(budget) != 260 || finiteHash(budget) != w.Opening.BudgetHash {
		t.Fatal("budget drift", err)
	}
	raw, _ := json.Marshal(w)
	if len(raw) != 11131 || finiteHash(raw) != "0d73c1d4718062d0664222cf0e2d64fcebc22edcd48df15e4d3549faf07cca4c" {
		t.Fatal("wire witness drift")
	}
}
func TestFiniteUTF16Comparator(t *testing.T) {
	if !finiteUTF16Less("\U00010000", "\uE000") {
		t.Fatal("Go scalar comparison substituted for UTF16")
	}
	p := []FiniteParty{{ParticipantID: "same", AccountID: "\uE000", Side: "SELL"}, {ParticipantID: "same", AccountID: "\U00010000", Side: "BUY"}}
	if finiteSortedParties(p)[0].Side != "BUY" {
		t.Fatal("account tie comparator drift")
	}
	w := finiteGoldenWitness(t)
	a, _ := w.Opening.BudgetCanonical()
	w.Opening.Parties[0], w.Opening.Parties[1] = w.Opening.Parties[1], w.Opening.Parties[0]
	b, _ := w.Opening.BudgetCanonical()
	if !bytes.Equal(a, b) {
		t.Fatal("party input order changes canonical budget")
	}
	if digest, err := w.Opening.Digest(); err != nil || digest != w.BindingDigest {
		t.Fatal("party input order changes canonical opening", err)
	}
	unsorted, _ := json.Marshal(w)
	if _, err := ParseFiniteStartup(unsorted, w.Nonce, w.BindingDigest); err == nil {
		t.Fatal("unsorted wire parties accepted")
	}
}

func TestFiniteOpeningEnforcesMeasuredOpeningByteBudget(t *testing.T) {
	w := finiteGoldenWitness(t)
	w.Opening.Budget.OpeningBytes = 1
	b, _ := w.Opening.BudgetCanonical()
	w.Opening.BudgetHash = finiteHash(b)
	if _, err := w.Opening.Digest(); err == nil {
		t.Fatal("opening exceeds its own measured byte budget")
	}
}
func TestFiniteStartupRejectsAmbiguousJSON(t *testing.T) {
	w := finiteGoldenWitness(t)
	raw := finiteGolden(t, "finite-startup-v1.json")
	for name, changed := range map[string][]byte{
		"duplicate root":     bytes.Replace(raw, []byte(`"schema":`), []byte(`"nonce":"duplicate","schema":`), 1),
		"unknown field":      append([]byte(`{"extra":1,`), raw[1:]...),
		"trailing":           append(append([]byte(nil), raw...), []byte(` {}`)...),
		"missing":            bytes.Replace(raw, []byte(`"schema":"calcify-finite-startup-v1",`), nil, 1),
		"unpaired surrogate": bytes.Replace(raw, []byte(`"ownerInstanceId":"`), []byte(`"ownerInstanceId":"\ud800`), 1),
		"invalid utf8":       append([]byte{0xff}, raw...),
		"oversized":          bytes.Repeat([]byte(" "), finiteStartupLimit+1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := ParseFiniteStartup(changed, w.Nonce, w.BindingDigest); err == nil {
				t.Fatal("ambiguous witness accepted")
			}
		})
	}
	for _, value := range []string{`1`, `"01"`, `"-1"`, `"1e1"`, `"9223372036854775808"`, `null`} {
		var n FiniteDecimal
		if json.Unmarshal([]byte(value), &n) == nil {
			t.Fatalf("decimal accepted %s", value)
		}
	}
}
func TestFiniteStartupRejectsInvalidMembershipAndAccounting(t *testing.T) {
	mutations := map[string]func(*FiniteStartupWitness){
		"extra journal":           func(w *FiniteStartupWitness) { w.Journal = append(w.Journal, w.Journal[0]) },
		"duplicate command":       func(w *FiniteStartupWitness) { w.Journal[1].CommandID = w.Journal[0].CommandID },
		"wrong sequence":          func(w *FiniteStartupWitness) { n := FiniteDecimal(2); w.Journal[0].StreamSequence = &n },
		"lost payload accounting": func(w *FiniteStartupWitness) { w.Run.CommandPayloadBytes++ },
		"uncertainty mismatch":    func(w *FiniteStartupWitness) { w.Run.UnresolvedPublications++ },
		"group outside range":     func(w *FiniteStartupWitness) { w.Cuts.CommandGroup.NextOffset = 100 },
		"uuid alias":              func(w *FiniteStartupWitness) { w.Opening.Topics[1].TopicID = w.Opening.Topics[0].TopicID },
		"zero uuid":               func(w *FiniteStartupWitness) { w.Opening.Topics[1].TopicID = "AAAAAAAAAAAAAAAAAAAAAA" },
		"oversized entry":         func(w *FiniteStartupWitness) { w.Journal[0].CommandID = strings.Repeat("x", 1025) },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			w := finiteGoldenWitness(t)
			mutate(w)
			raw, _ := json.Marshal(w)
			if _, err := ParseFiniteStartup(raw, w.Nonce, w.BindingDigest); err == nil {
				t.Fatal("invalid witness accepted")
			}
		})
	}
}
