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
	"payloadChecksum":          {},
	"payloadChecksumAlgorithm": {},
}

// venueEventBatchChecksum hashes the complete semantic batch body. Volatile
// creation time and checksum metadata are excluded; routing, sequence identity,
// outcome status, and the complete result body are included.
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

func writeCanonicalValue(digest hash.Hash, value any) error {
	switch typed := value.(type) {
	case nil:
		writeCanonicalToken(digest, 'n', nil)
	case bool:
		if typed {
			writeCanonicalToken(digest, 'b', []byte{'1'})
		} else {
			writeCanonicalToken(digest, 'b', []byte{'0'})
		}
	case string:
		writeCanonicalToken(digest, 's', []byte(typed))
	case json.Number:
		writeCanonicalToken(digest, 'd', []byte(typed.String()))
	case []any:
		writeCanonicalToken(digest, 'a', []byte(strconv.Itoa(len(typed))))
		for _, entry := range typed {
			if err := writeCanonicalValue(digest, entry); err != nil {
				return err
			}
		}
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		writeCanonicalToken(digest, 'o', []byte(strconv.Itoa(len(keys))))
		for _, key := range keys {
			writeCanonicalToken(digest, 's', []byte(key))
			if err := writeCanonicalValue(digest, typed[key]); err != nil {
				return err
			}
		}
	default:
		return fmt.Errorf("unsupported canonical checksum value %T", value)
	}
	return nil
}

func writeCanonicalToken(digest hash.Hash, kind byte, value []byte) {
	_, _ = digest.Write([]byte{kind})
	_, _ = digest.Write([]byte(strconv.Itoa(len(value))))
	_, _ = digest.Write([]byte{':'})
	_, _ = digest.Write(value)
}
