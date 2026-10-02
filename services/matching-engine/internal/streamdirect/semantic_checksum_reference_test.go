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

// Reference freezes the pre-experiment writer for differential control. Volatile
// wall-clock timing and checksum metadata are excluded; routing,
// sequence identity, outcome status, and the complete result body are included.
func referenceVenueEventBatchChecksum(batch VenueEventBatch) (string, error) {
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
	if err := referenceWriteCanonicalValue(digest, object); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func referenceWriteCanonicalValue(digest hash.Hash, value any) error {
	switch typed := value.(type) {
	case nil:
		referenceWriteCanonicalToken(digest, 'n', nil)
	case bool:
		if typed {
			referenceWriteCanonicalToken(digest, 'b', []byte{'1'})
		} else {
			referenceWriteCanonicalToken(digest, 'b', []byte{'0'})
		}
	case string:
		referenceWriteCanonicalToken(digest, 's', []byte(typed))
	case json.Number:
		referenceWriteCanonicalToken(digest, 'd', []byte(typed.String()))
	case []any:
		referenceWriteCanonicalToken(digest, 'a', []byte(strconv.Itoa(len(typed))))
		for _, entry := range typed {
			if err := referenceWriteCanonicalValue(digest, entry); err != nil {
				return err
			}
		}
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		referenceWriteCanonicalToken(digest, 'o', []byte(strconv.Itoa(len(keys))))
		for _, key := range keys {
			referenceWriteCanonicalToken(digest, 's', []byte(key))
			if err := referenceWriteCanonicalValue(digest, typed[key]); err != nil {
				return err
			}
		}
	default:
		return fmt.Errorf("unsupported canonical checksum value %T", value)
	}
	return nil
}

func referenceWriteCanonicalToken(digest hash.Hash, kind byte, value []byte) {
	_, _ = digest.Write([]byte{kind})
	_, _ = digest.Write([]byte(strconv.Itoa(len(value))))
	_, _ = digest.Write([]byte{':'})
	_, _ = digest.Write(value)
}
