package streamdirect

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func startupV2Challenge(t *testing.T, raw []byte) (string, string) {
	t.Helper()
	var c struct {
		Nonce         string `json:"nonce"`
		BindingDigest string `json:"bindingDigest"`
	}
	if err := json.Unmarshal(raw, &c); err != nil {
		t.Fatal(err)
	}
	return c.Nonce, c.BindingDigest
}

func mutateStartupV2(t *testing.T, edit func(map[string]any)) []byte {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(v2Fixture(t, "finite-startup-v2.json"), &m); err != nil {
		t.Fatal(err)
	}
	edit(m)
	out, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestFiniteStartupV2ParsesTheGoldenWitnessAndRefusesChallengeMismatch(t *testing.T) {
	raw := v2Fixture(t, "finite-startup-v2.json")
	nonce, digest := startupV2Challenge(t, raw)
	w, err := ParseFiniteStartupV2(raw, nonce, digest)
	if err != nil {
		t.Fatal(err)
	}
	if len(w.Cuts.Ranges) != 8 || w.Cuts.FinanceGroup.GroupID != "p3-fixture-finance" {
		t.Fatalf("unexpected cuts %+v", w.Cuts)
	}
	manifest := v2Fixture(t, "finite-binding-v2-manifest.json")
	if !bytes.Contains(manifest, []byte(digest)) {
		t.Fatal("startup binding digest differs from the registered v2 manifest")
	}
	if _, err := ParseFiniteStartupV2(raw, "other-nonce", digest); err == nil {
		t.Fatal("wrong nonce accepted")
	}
	if _, err := ParseFiniteStartupV2(raw, nonce, strings.Repeat("0", 64)); err == nil {
		t.Fatal("wrong digest accepted")
	}
	if _, err := ParseFiniteStartup(raw, nonce, digest); err == nil {
		t.Fatal("v1 parser accepted a v2 witness")
	}
	v1 := v2Fixture(t, "finite-startup-v1.json")
	n1, d1 := startupV2Challenge(t, v1)
	if _, err := ParseFiniteStartupV2(v1, n1, d1); err == nil {
		t.Fatal("v2 parser accepted a v1 witness")
	}
}

func TestFiniteStartupV2RefusesEveryStructuralViolation(t *testing.T) {
	cuts := func(m map[string]any) map[string]any { return m["cuts"].(map[string]any) }
	ranges := func(m map[string]any) []any { return cuts(m)["ranges"].([]any) }
	finance := func(m map[string]any) map[string]any { return cuts(m)["financeGroup"].(map[string]any) }
	mutations := map[string]func(map[string]any){
		"five ranges":              func(m map[string]any) { cuts(m)["ranges"] = ranges(m)[:5] },
		"nine ranges":              func(m map[string]any) { cuts(m)["ranges"] = append(ranges(m), ranges(m)[7]) },
		"swapped role order":       func(m map[string]any) { r := ranges(m); r[5], r[6] = r[6], r[5] },
		"range begin above commit": func(m map[string]any) { r := ranges(m)[6].(map[string]any); r["beginningOffset"] = "1" },
		"commit above end":         func(m map[string]any) { r := ranges(m)[7].(map[string]any); r["readCommittedEndOffset"] = "1" },
		"nonzero partition":        func(m map[string]any) { ranges(m)[5].(map[string]any)["partition"] = 1 },
		"finance group id":         func(m map[string]any) { finance(m)["groupId"] = "p3-fixture-other" },
		"finance capture past end": func(m map[string]any) { finance(m)["captureNextOffset"] = "1" },
		"finance control past end": func(m map[string]any) { finance(m)["controlNextOffset"] = "1" },
		"finance offset negative":  func(m map[string]any) { finance(m)["controlNextOffset"] = "-1" },
		"finance offset number":    func(m map[string]any) { finance(m)["captureNextOffset"] = 0 },
		"missing finance group":    func(m map[string]any) { delete(cuts(m), "financeGroup") },
		"unknown finance field":    func(m map[string]any) { finance(m)["extra"] = "0" },
		"v1 command group beyond":  func(m map[string]any) { cuts(m)["commandGroup"].(map[string]any)["nextOffset"] = "99" },
		"unresolved accounting":    func(m map[string]any) { m["run"].(map[string]any)["unresolvedPublications"] = "1" },
		"schema v1":                func(m map[string]any) { m["schema"] = "calcify-finite-startup-v1" },
	}
	for name, edit := range mutations {
		t.Run(name, func(t *testing.T) {
			raw := mutateStartupV2(t, edit)
			nonce, digest := startupV2Challenge(t, raw)
			if _, err := ParseFiniteStartupV2(raw, nonce, digest); err == nil {
				t.Fatal("mutated witness accepted")
			}
		})
	}
}

func TestFiniteStartupV2StaysWithinTheWireAndHeaderBudgets(t *testing.T) {
	raw := v2Fixture(t, "finite-startup-v2.json")
	if len(raw) > finiteStartupLimit {
		t.Fatalf("v2 witness %d exceeds wire limit", len(raw))
	}
	nonce, digest := startupV2Challenge(t, raw)
	w, err := ParseFiniteStartupV2(raw, nonce, digest)
	if err != nil {
		t.Fatal(err)
	}
	w.Journal = []FiniteJournalEntry{}
	header, _ := json.Marshal(w)
	if len(header) > 8192 {
		t.Fatalf("v2 witness header %d exceeds 8192", len(header))
	}
	t.Logf("v2 witness wire=%d header=%d", len(raw), len(header))
	if _, err := ParseFiniteStartupV2(bytes.Repeat([]byte(" "), finiteStartupLimit+1), nonce, digest); err == nil {
		t.Fatal("oversized witness accepted")
	}
}

// v1 keeps its strictness: a v1 witness with an extra range or a finance group is not a v1 witness.
func TestFiniteStartupV1StaysStrictAfterV2(t *testing.T) {
	raw := v2Fixture(t, "finite-startup-v1.json")
	nonce, digest := startupV2Challenge(t, raw)
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	cuts := m["cuts"].(map[string]any)
	cuts["financeGroup"] = map[string]any{"groupId": "x", "captureNextOffset": "0", "controlNextOffset": "0"}
	changed, _ := json.Marshal(m)
	if _, err := ParseFiniteStartup(changed, nonce, digest); err == nil {
		t.Fatal("v1 accepted a finance group")
	}
	cuts = m["cuts"].(map[string]any)
	delete(cuts, "financeGroup")
	cuts["ranges"] = append(cuts["ranges"].([]any), cuts["ranges"].([]any)[0])
	changed, _ = json.Marshal(m)
	if _, err := ParseFiniteStartup(changed, nonce, digest); err == nil {
		t.Fatal("v1 accepted six ranges")
	}
}
