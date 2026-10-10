package app

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
)

func TestCalcifySourcePublicIdentityPreservesV1Bytes(t *testing.T) {
	// Exact pre-existing v1 field order and sorted run IDs; no lifecycle fields.
	canonical := `{"schema":"calcify-finite-source-v1","runIds":["p0-other","p0-run"],"venueSessionId":"p0-session","instrumentId":"AAPL","currency":"USD","maxOrderIds":8,"maxQuantityUnits":10,"maxLimitPrice":1000}`
	digest := sha256.Sum256([]byte(canonical))
	service := finiteSourceService(t)
	before := service.Snapshot().Checksum
	if service.CalcifySourceProfileHash() != hex.EncodeToString(digest[:]) || service.CalcifySourceProfileHash() != service.Snapshot().Metadata.CalcifySourceProfileHash {
		t.Fatal("public identity changed v1 profile hash")
	}
	if service.Snapshot().Checksum != before {
		t.Fatal("identity accessor mutated state")
	}
	if NewService().CalcifySourceProfileHash() != "" {
		t.Fatal("disabled source profile fabricated identity")
	}
}
