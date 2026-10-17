package streamdirect

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"math"
	"math/rand"
	"reflect"
	"strings"
	"testing"
)

// randomValue fills a value of type rt with adversarial but valid content: boundary integers, empty, nil and populated slices, nil and set pointers, and
// strings that exercise JSON escaping (quotes, backslashes, control characters, HTML characters, line separators), multi-byte text and invalid UTF-8.
func randomValue(rnd *rand.Rand, rt reflect.Type, depth int) reflect.Value {
	v := reflect.New(rt).Elem()
	switch rt.Kind() {
	case reflect.String:
		pool := []string{"", "plain", "with \"quotes\" and \\ backslash", "line\nbreak\ttab\x00nul", "<script>&amp;</script>", "   separators", "naive é世界 \U0001F600",
			"bad\xff\xfeutf8", "\xed\xa0\x80 surrogate", "trail\xc3", strings.Repeat("long-", 80), "createdAt", "o0:", "s1:x"}
		v.SetString(pool[rnd.Intn(len(pool))])
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		options := []int64{0, 1, -1, 7, math.MaxInt32, math.MinInt32, math.MaxInt64, math.MinInt64}
		v.SetInt(options[rnd.Intn(len(options))])
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		options := []uint64{0, 1, 255, 65535, math.MaxUint32, math.MaxUint64}
		v.SetUint(options[rnd.Intn(len(options))])
	case reflect.Bool:
		v.SetBool(rnd.Intn(2) == 0)
	case reflect.Slice:
		switch rnd.Intn(4) {
		case 0: // nil
		case 1:
			v.Set(reflect.MakeSlice(rt, 0, 0))
		default:
			if depth > 6 {
				break
			}
			n := 1 + rnd.Intn(3)
			s := reflect.MakeSlice(rt, n, n)
			for i := 0; i < n; i++ {
				s.Index(i).Set(randomValue(rnd, rt.Elem(), depth+1))
			}
			v.Set(s)
		}
	case reflect.Pointer:
		if rnd.Intn(3) != 0 && depth <= 6 {
			p := reflect.New(rt.Elem())
			p.Elem().Set(randomValue(rnd, rt.Elem(), depth+1))
			v.Set(p)
		}
	case reflect.Struct:
		for i := 0; i < rt.NumField(); i++ {
			if rt.Field(i).IsExported() {
				v.Field(i).Set(randomValue(rnd, rt.Field(i).Type, depth+1))
			}
		}
	}
	return v
}

func TestTypedChecksumEqualsTheGeneralRouteAndTheReferenceOnRandomBatches(t *testing.T) {
	plan, ok := venueEventBatchTypedPlan()
	if !ok || plan == nil {
		t.Fatal("the typed plan must cover VenueEventBatch: the fast path is not in use")
	}
	rnd := rand.New(rand.NewSource(20261014))
	for i := 0; i < 6000; i++ {
		batch := randomValue(rnd, reflect.TypeOf(VenueEventBatch{}), 0).Interface().(VenueEventBatch)
		typed, err := venueEventBatchChecksum(batch)
		if err != nil {
			t.Fatalf("sample %d: typed: %v", i, err)
		}
		general, err := venueEventBatchChecksumViaJSON(batch)
		if err != nil {
			t.Fatalf("sample %d: general: %v", i, err)
		}
		reference, err := referenceVenueEventBatchChecksum(batch)
		if err != nil {
			t.Fatalf("sample %d: reference: %v", i, err)
		}
		if typed != general || typed != reference {
			t.Fatalf("sample %d differs: typed=%s general=%s reference=%s\nbatch=%+v", i, typed, general, reference, batch)
		}
	}
}

func TestTypedChecksumEqualsTheGeneralRouteOnTheFixtures(t *testing.T) {
	for name, batch := range map[string]VenueEventBatch{"test batch": checksumTestBatch(), "200 commands": checksumBenchmarkBatch(), "empty": {}} {
		typed, err := venueEventBatchChecksum(batch)
		if err != nil {
			t.Fatal(err)
		}
		general, err := venueEventBatchChecksumViaJSON(batch)
		if err != nil {
			t.Fatal(err)
		}
		if typed != general {
			t.Fatalf("%s: typed %s != general %s", name, typed, general)
		}
	}
}

func TestTypedChecksumIgnoresExactlyTheVolatileTopLevelFields(t *testing.T) {
	batch := checksumTestBatch()
	base, _ := venueEventBatchChecksum(batch)
	batch.CreatedAt, batch.WorkFinishedAt, batch.TimingChecksum, batch.PayloadChecksum, batch.ChecksumAlgo = "x", "y", "z", "w", "v"
	if got, _ := venueEventBatchChecksum(batch); got != base {
		t.Fatal("volatile or checksum fields changed the checksum")
	}
	for name, mutate := range map[string]func(*VenueEventBatch){
		"batch id":        func(b *VenueEventBatch) { b.BatchID += "x" },
		"partition":       func(b *VenueEventBatch) { b.Partition++ },
		"command stream":  func(b *VenueEventBatch) { b.CommandStream += "x" },
		"outcome status":  func(b *VenueEventBatch) { b.Outcomes[0].Status += "x" },
		"stream sequence": func(b *VenueEventBatch) { b.Outcomes[0].StreamSequence++ },
	} {
		changed := checksumTestBatch()
		mutate(&changed)
		if got, _ := venueEventBatchChecksum(changed); got == base {
			t.Fatalf("%s did not change the checksum", name)
		}
	}
}

func TestThePlanRefusesEveryShapeItDoesNotFullyCover(t *testing.T) {
	type withMap struct{ M map[string]string }
	type withFloat struct{ F float64 }
	type withInterface struct{ I any }
	type withBytes struct{ B []byte }
	type withArray struct{ A [2]string }
	type embedded struct{ Inner struct{ X string } }
	type base struct{ X string }
	type withEmbedded struct{ base }
	type withStringOption struct {
		N int `json:"n,string"`
	}
	type withOmitZero struct {
		N int `json:"n,omitzero"`
	}
	// Built dynamically: go vet rejects a duplicated json key in a struct literal, which is exactly the shape the plan must refuse.
	withDuplicate := reflect.New(reflect.StructOf([]reflect.StructField{
		{Name: "A", Type: reflect.TypeOf(""), Tag: `json:"k"`}, {Name: "B", Type: reflect.TypeOf(""), Tag: `json:"k"`},
	})).Elem().Interface()
	type withDash struct {
		A string `json:"-,"`
	}
	type recursive struct{ Next *recursive }
	for name, value := range map[string]any{"map": withMap{}, "float": withFloat{}, "interface": withInterface{}, "bytes": withBytes{}, "array": withArray{},
		"embedded": withEmbedded{}, "string option": withStringOption{}, "omitzero": withOmitZero{}, "duplicate key": withDuplicate, "dash key": withDash{}, "recursive": recursive{}} {
		if _, err := buildCanonicalPlan(reflect.TypeOf(value)); err == nil {
			t.Errorf("%s: the plan accepted a shape it does not cover", name)
		}
	}
	type fine struct {
		A string  `json:"a"`
		B []int64 `json:"b,omitempty"`
		C *bool   `json:"c"`
		d string
		E string `json:"-"`
	}
	plan, err := buildCanonicalPlan(reflect.TypeOf(fine{}))
	if err != nil || len(plan.fields) != 3 {
		t.Fatalf("plain struct: err=%v plan=%+v", err, plan)
	}
	_ = embedded{}
}

func TestOmitEmptyAgreesWithEncodingJSONForEveryCoveredKind(t *testing.T) {
	type probe struct {
		S string  `json:"s,omitempty"`
		I int     `json:"i,omitempty"`
		U uint16  `json:"u,omitempty"`
		B bool    `json:"b,omitempty"`
		L []int   `json:"l,omitempty"`
		P *string `json:"p,omitempty"`
		N struct {
			X int `json:"x"`
		} `json:"n,omitempty"`
	}
	plan, err := buildCanonicalPlan(reflect.TypeOf(probe{}))
	if err != nil {
		t.Fatal(err)
	}
	empty := ""
	for i, value := range []probe{{}, {S: "a"}, {I: -1}, {U: 3}, {B: true}, {L: []int{}}, {L: []int{1}}, {P: &empty}, {S: "a", I: 1, U: 2, B: true, L: []int{4, 5}, P: &empty}} {
		typed, _ := typedCanonicalChecksum(plan, reflect.ValueOf(value))
		generic, err := genericChecksumOf(value)
		if err != nil {
			t.Fatal(err)
		}
		if typed != generic {
			t.Fatalf("case %d: typed %s != generic %s", i, typed, generic)
		}
	}
}

func BenchmarkVenueEventBatchChecksumTyped200(b *testing.B) {
	batch := checksumBenchmarkBatch()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		benchmarkChecksum, _ = venueEventBatchChecksum(batch)
	}
}

func BenchmarkVenueEventBatchChecksumViaJSON200(b *testing.B) {
	batch := checksumBenchmarkBatch()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		benchmarkChecksum, _ = venueEventBatchChecksumViaJSON(batch)
	}
}

// genericChecksumOf hashes any JSON-encodable value by the general route (marshal, decode into generic values, canonical walk), without the batch's
// top-level exclusions. It exists to test the typed walk on small probe types.
func genericChecksumOf(value any) (string, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	var root any
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return "", err
	}
	digest := sha256.New()
	if err := writeCanonicalValue(digest, root); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}
