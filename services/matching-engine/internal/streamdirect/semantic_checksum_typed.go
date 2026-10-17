package streamdirect

import (
	"crypto/sha256"
	"encoding"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"
)

// The canonical checksum (sha256-reef-canonical-v1) hashes a deterministic walk of the batch's JSON value: objects with sorted keys, arrays, strings, decimal
// numbers, booleans and null. The general route marshals the batch to JSON, decodes it back into generic maps and slices, and walks those; at the engine
// lane's full batch size that round trip was most of the runner thread's CPU and most of its garbage (CAL-FIN-P20). For the plain types a batch is made of
// (strings, integers, booleans, slices, pointers and structs with ordinary json tags) the same walk can be taken directly over the Go values, byte for byte
// the same hash. A plan is built once per type; any type it does not fully understand (a custom marshaler, a map, an interface, a float, an embedded field,
// a tag option other than omitempty, a byte slice) makes the whole type use the general route, so the typed walk can only ever be an exact shortcut.

type canonicalPlanKind uint8

const (
	planString canonicalPlanKind = iota + 1
	planInt
	planUint
	planBool
	planSlice
	planPointer
	planStruct
)

type canonicalPlan struct {
	kind   canonicalPlanKind
	elem   *canonicalPlan   // slice and pointer
	fields []canonicalField // struct, in canonical (byte-wise) key order
}

type canonicalField struct {
	name      string
	index     int
	omitEmpty bool
	plan      *canonicalPlan
}

var (
	jsonMarshalerType = reflect.TypeOf((*json.Marshaler)(nil)).Elem()
	textMarshalerType = reflect.TypeOf((*encoding.TextMarshaler)(nil)).Elem()
)

type planBuilder struct {
	done       map[reflect.Type]*canonicalPlan
	inProgress map[reflect.Type]bool
}

func buildCanonicalPlan(rt reflect.Type) (*canonicalPlan, error) {
	b := planBuilder{done: map[reflect.Type]*canonicalPlan{}, inProgress: map[reflect.Type]bool{}}
	return b.build(rt)
}

func (b *planBuilder) build(rt reflect.Type) (*canonicalPlan, error) {
	if plan, ok := b.done[rt]; ok {
		return plan, nil
	}
	if b.inProgress[rt] {
		return nil, fmt.Errorf("recursive type %v", rt)
	}
	if rt.Implements(jsonMarshalerType) || reflect.PointerTo(rt).Implements(jsonMarshalerType) ||
		rt.Implements(textMarshalerType) || reflect.PointerTo(rt).Implements(textMarshalerType) {
		return nil, fmt.Errorf("%v has a custom marshaler", rt)
	}
	b.inProgress[rt] = true
	defer delete(b.inProgress, rt)
	var plan *canonicalPlan
	switch rt.Kind() {
	case reflect.String:
		plan = &canonicalPlan{kind: planString}
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		plan = &canonicalPlan{kind: planInt}
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		plan = &canonicalPlan{kind: planUint}
	case reflect.Bool:
		plan = &canonicalPlan{kind: planBool}
	case reflect.Slice:
		if rt.Elem().Kind() == reflect.Uint8 {
			return nil, fmt.Errorf("%v is a byte slice (encoded as base64)", rt)
		}
		elem, err := b.build(rt.Elem())
		if err != nil {
			return nil, err
		}
		plan = &canonicalPlan{kind: planSlice, elem: elem}
	case reflect.Pointer:
		elem, err := b.build(rt.Elem())
		if err != nil {
			return nil, err
		}
		plan = &canonicalPlan{kind: planPointer, elem: elem}
	case reflect.Struct:
		fields, err := b.structFields(rt)
		if err != nil {
			return nil, err
		}
		plan = &canonicalPlan{kind: planStruct, fields: fields}
	default:
		return nil, fmt.Errorf("%v (kind %v) is not covered", rt, rt.Kind())
	}
	b.done[rt] = plan
	return plan, nil
}

func (b *planBuilder) structFields(rt reflect.Type) ([]canonicalField, error) {
	var fields []canonicalField
	seen := map[string]bool{}
	for i := 0; i < rt.NumField(); i++ {
		f := rt.Field(i)
		tag, hasTag := f.Tag.Lookup("json")
		if tag == "-" {
			continue
		}
		if f.Anonymous {
			return nil, fmt.Errorf("%v embeds %v", rt, f.Type)
		}
		if !f.IsExported() {
			continue
		}
		name, opts, _ := strings.Cut(tag, ",")
		omit := false
		if opts != "" {
			if opts != "omitempty" {
				return nil, fmt.Errorf("%v.%s has the tag option %q", rt, f.Name, opts)
			}
			omit = true
		}
		if name == "" {
			name = f.Name
		}
		if hasTag && name == "-" {
			return nil, fmt.Errorf("%v.%s has the key %q", rt, f.Name, name)
		}
		if seen[name] {
			return nil, fmt.Errorf("%v repeats the key %q", rt, name)
		}
		seen[name] = true
		plan, err := b.build(f.Type)
		if err != nil {
			return nil, fmt.Errorf("%v.%s: %w", rt, f.Name, err)
		}
		fields = append(fields, canonicalField{name: name, index: i, omitEmpty: omit, plan: plan})
	}
	sort.Slice(fields, func(i, j int) bool { return fields[i].name < fields[j].name })
	return fields, nil
}

var (
	venueEventBatchPlanOnce sync.Once
	venueEventBatchPlan     *canonicalPlan
)

// venueEventBatchTypedPlan is the plan for VenueEventBatch with the volatile and checksum fields of the top-level object left out, or false when the
// batch type is no longer fully covered (then every checksum takes the general route).
func venueEventBatchTypedPlan() (*canonicalPlan, bool) {
	venueEventBatchPlanOnce.Do(func() {
		plan, err := buildCanonicalPlan(reflect.TypeOf(VenueEventBatch{}))
		if err != nil || plan.kind != planStruct {
			return
		}
		root := &canonicalPlan{kind: planStruct}
		for _, f := range plan.fields {
			if _, excluded := venueEventBatchChecksumExcludedFields[f.name]; !excluded {
				root.fields = append(root.fields, f)
			}
		}
		venueEventBatchPlan = root
	})
	return venueEventBatchPlan, venueEventBatchPlan != nil
}

func typedCanonicalChecksum(plan *canonicalPlan, value reflect.Value) (string, error) {
	digest := sha256.New()
	writer := canonicalWriter{digest: digest}
	writer.typed(plan, value)
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func (w *canonicalWriter) typed(plan *canonicalPlan, v reflect.Value) {
	switch plan.kind {
	case planString:
		w.typedString(v.String())
	case planInt:
		w.token('d', strconv.AppendInt(w.count[:0], v.Int(), 10))
	case planUint:
		w.token('d', strconv.AppendUint(w.count[:0], v.Uint(), 10))
	case planBool:
		if v.Bool() {
			w.stringToken('b', "1")
		} else {
			w.stringToken('b', "0")
		}
	case planPointer:
		if v.IsNil() {
			w.token('n', nil)
			return
		}
		w.typed(plan.elem, v.Elem())
	case planSlice:
		if v.IsNil() {
			w.token('n', nil)
			return
		}
		n := v.Len()
		w.token('a', strconv.AppendInt(w.count[:0], int64(n), 10))
		for i := 0; i < n; i++ {
			w.typed(plan.elem, v.Index(i))
		}
	case planStruct:
		present := 0
		for i := range plan.fields {
			if f := &plan.fields[i]; !f.omitEmpty || !isEmptyCanonicalValue(v.Field(f.index)) {
				present++
			}
		}
		w.token('o', strconv.AppendInt(w.count[:0], int64(present), 10))
		for i := range plan.fields {
			f := &plan.fields[i]
			fv := v.Field(f.index)
			if f.omitEmpty && isEmptyCanonicalValue(fv) {
				continue
			}
			w.field(f.name)
			w.typed(f.plan, fv)
		}
	}
}

// typedString writes a string the way the general route sees it: json.Marshal replaces each byte of an invalid UTF-8 sequence with U+FFFD and decoding
// returns the replacement characters, so the bytes hashed are those of the string with every invalid byte replaced individually.
func (w *canonicalWriter) typedString(s string) {
	if utf8.ValidString(s) {
		w.stringToken('s', s)
		return
	}
	fixed := make([]byte, 0, len(s)+8)
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			fixed = append(fixed, "�"...)
		} else {
			fixed = append(fixed, s[i:i+size]...)
		}
		i += size
	}
	w.stringToken('s', string(fixed))
}

// isEmptyCanonicalValue is encoding/json's omitempty test for the kinds a plan covers.
func isEmptyCanonicalValue(v reflect.Value) bool {
	switch v.Kind() {
	case reflect.String, reflect.Slice:
		return v.Len() == 0
	case reflect.Bool:
		return !v.Bool()
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return v.Int() == 0
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return v.Uint() == 0
	case reflect.Pointer:
		return v.IsNil()
	}
	return false
}
