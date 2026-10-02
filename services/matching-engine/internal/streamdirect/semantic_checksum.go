package streamdirect

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"hash"
	"sort"
	"strconv"
)

const venueEventBatchChecksumAlgorithm = "sha256-reef-canonical-v1"

var venueEventBatchChecksumExcludedFields = map[string]struct{}{
	"createdAt":                {},
	"workFinishedAt":           {},
	"timingChecksum":           {},
	"payloadChecksum":          {},
	"payloadChecksumAlgorithm": {},
}

// venueEventBatchChecksum hashes the complete semantic batch body. Volatile
// wall-clock timing and checksum metadata are excluded; routing,
// sequence identity, outcome status, and the complete result body are included.
func venueEventBatchChecksum(batch VenueEventBatch) (string, error) {
	payload, err := json.Marshal(batch)
	if err != nil {
		return "", err
	}
	var root any
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return "", err
	}
	object, ok := root.(map[string]any)
	if !ok {
		return "", fmt.Errorf("venue event batch checksum root must be an object")
	}
	for field := range venueEventBatchChecksumExcludedFields {
		delete(object, field)
	}
	digest := sha256.New()
	if err := writeCanonicalValue(digest, object); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

// Scratch and memo belong to one checksum call: concurrent batches share no
// mutable writer state. Cache only short field names and cap cardinality so
// dynamic JSON cannot turn this optimization into an unbounded dictionary.
const canonicalFieldMemoLimit = 128
const canonicalFieldMemoMaxBytes = 128

type canonicalWriter struct {
	digest hash.Hash
	header [32]byte
	count  [24]byte
	text   [256]byte
	fields map[string][]byte
}

func writeCanonicalValue(digest hash.Hash, value any) error {
	writer := canonicalWriter{digest: digest}
	return writer.value(value)
}

func (w *canonicalWriter) value(value any) error {
	switch typed := value.(type) {
	case nil:
		w.token('n', nil)
	case bool:
		if typed {
			w.stringToken('b', "1")
		} else {
			w.stringToken('b', "0")
		}
	case string:
		w.stringToken('s', typed)
	case json.Number:
		w.stringToken('d', typed.String())
	case []any:
		w.token('a', strconv.AppendInt(w.count[:0], int64(len(typed)), 10))
		for _, entry := range typed {
			if err := w.value(entry); err != nil {
				return err
			}
		}
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		w.token('o', strconv.AppendInt(w.count[:0], int64(len(keys)), 10))
		for _, key := range keys {
			w.field(key)
			if err := w.value(typed[key]); err != nil {
				return err
			}
		}
	default:
		return fmt.Errorf("unsupported canonical checksum value %T", value)
	}
	return nil
}

func (w *canonicalWriter) field(name string) {
	if cached, ok := w.fields[name]; ok {
		_, _ = w.digest.Write(cached)
		return
	}
	if len(name) <= canonicalFieldMemoMaxBytes && len(w.fields) < canonicalFieldMemoLimit {
		if w.fields == nil {
			w.fields = make(map[string][]byte)
		}
		token := make([]byte, 1, len(name)+24)
		token[0] = 's'
		token = strconv.AppendInt(token, int64(len(name)), 10)
		token = append(token, ':')
		token = append(token, name...)
		w.fields[name] = token
		_, _ = w.digest.Write(token)
		return
	}
	w.stringToken('s', name)
}

func (w *canonicalWriter) writeHeader(kind byte, length int) {
	w.header[0] = kind
	header := strconv.AppendInt(w.header[:1], int64(length), 10)
	header = append(header, ':')
	_, _ = w.digest.Write(header)
}

func (w *canonicalWriter) token(kind byte, value []byte) {
	w.writeHeader(kind, len(value))
	if len(value) > 0 {
		_, _ = w.digest.Write(value)
	}
}

func (w *canonicalWriter) stringToken(kind byte, value string) {
	w.writeHeader(kind, len(value))
	// Copy bounded chunks into reusable scratch rather than allocating a byte
	// slice for every string. Hash observes exactly the original UTF-8 bytes.
	for len(value) > 0 {
		size := len(value)
		if size > len(w.text) {
			size = len(w.text)
		}
		copy(w.text[:size], value[:size])
		_, _ = w.digest.Write(w.text[:size])
		value = value[size:]
	}
}

// Timing is integrity-bound to semantic membership without changing retry identity.
func venueEventBatchTimingChecksum(batch VenueEventBatch) string {
	digest := sha256.Sum256([]byte("reef-venue-batch-timing-v1\n" + batch.PayloadChecksum + "\n" + batch.WorkFinishedAt))
	return hex.EncodeToString(digest[:])
}
