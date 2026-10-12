#!/usr/bin/env python3
"""Independent reference encoder for calcify-finite-binding-v1/v2 canonical bytes (docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md).

Not shared with the Go or Kotlin implementations. `check` verifies the v1 fixture against its checked-in hex and digest (so the
encoder is validated against known-good bytes), then verifies or regenerates the v2 hex and manifest.
  python3 scripts/dev/calcify-financial/binding_v2_reference.py check
  python3 scripts/dev/calcify-financial/binding_v2_reference.py write      # regenerates contracts/calcify/finite-binding-v2.{hex,manifest.json}
"""
import hashlib, json, struct, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3] / "contracts" / "calcify"


def frame(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack(">I", len(b)) + b


def utf16_key(s: str):
    data = s.encode("utf-16-be")
    return [struct.unpack(">H", data[i:i + 2])[0] for i in range(0, len(data), 2)]


def parties_bytes(parties) -> bytes:
    ordered = sorted(parties, key=lambda p: (utf16_key(p["participantId"]), utf16_key(p["accountId"])))
    out = struct.pack(">I", len(ordered))
    for p in ordered:
        out += frame(p["participantId"]) + frame(p["accountId"]) + bytes([1 if p["side"] == "BUY" else 2])
    return out


def body(o: dict) -> bytes:
    out = bytes.fromhex(o["profileHash"]) + bytes.fromhex(o["budgetHash"])
    for k in ("lifecycleSchema", "policyId", "runId", "venueSessionId", "instrumentId", "quoteCurrency", "baseAssetId"):
        out += frame(o[k])
    out += parties_bytes(o["parties"])
    for t in o["topics"]:
        out += frame(t["name"]) + frame(t["topicId"])
    for k in ("sourceGeneration", "partition", "partitionCount"):
        out += struct.pack(">I", o[k])
    for k in ("commandConsumerGroup", "ownershipGroup", "matcherShardId", "commandSubjectPrefix", "sourceSubjectPrefix",
              "brokerClusterId", "captureApplicationId", "storeName", "stateVersion"):
        out += frame(o[k])
    out += bytes([1])
    for k in ("initialCommandNextOffset", "initialSourceNextOffset", "initialCaptureNextOffset", "initialCaptureSourceGroupNextOffset"):
        out += struct.pack(">Q", int(o[k]))
    return out


def canonical_v1(o: dict) -> bytes:
    return b"calcify-finite-binding-v1\x00" + body(o)


def canonical_v2(o: dict) -> bytes:
    out = b"calcify-finite-binding-v2\x00" + body(o)
    for k in ("financeApplicationId", "financeStoreName", "financeStateVersion", "openingAccountId", "controlTransactionalId"):
        out += frame(o[k])
    out += bytes.fromhex(o["financeConfigDigest"])
    b = o["financeBudget"]
    for k in ("obligations", "captures", "settleAttempts", "controls"):
        out += struct.pack(">I", int(b[k]))
    for k in ("initialControlNextOffset", "initialFinanceDecisionsNextOffset", "initialFinanceCaptureNextOffset", "initialFinanceControlNextOffset"):
        out += struct.pack(">Q", int(o[k]))
    return out


def finance_config_digest(o: dict) -> str:
    accounts = sorted((p["accountId"] for p in o["parties"]), key=lambda s: s.encode("utf-8"))
    b = o["financeBudget"]
    raw = frame("calcify-finance-config-v1") + struct.pack(">i", len(accounts)) + b"".join(frame(a) for a in accounts)
    raw += frame(o["quoteCurrency"]) + frame(o["baseAssetId"]) + frame(o["openingAccountId"])
    raw += b"".join(struct.pack(">i", int(b[k])) for k in ("obligations", "captures", "settleAttempts", "controls"))
    return hashlib.sha256(raw).hexdigest()


def main(mode: str) -> int:
    v1 = json.loads((ROOT / "finite-binding-v1.json").read_text())
    v1_bytes = canonical_v1(v1)
    assert v1_bytes.hex() == (ROOT / "finite-binding-v1.hex").read_text().strip(), "reference encoder disagrees with the checked-in v1 hex"
    v2 = json.loads((ROOT / "finite-binding-v2.json").read_text())
    assert v2["financeConfigDigest"] == finance_config_digest(v2), "financeConfigDigest does not match the derived FinanceConfig"
    v2_bytes = canonical_v2(v2)
    digest = hashlib.sha256(v2_bytes).hexdigest()
    manifest = {"schema": "calcify-finite-binding-v2", "bytes": len(v2_bytes), "digest": digest,
                "financeConfigDigest": v2["financeConfigDigest"], "v1Digest": hashlib.sha256(v1_bytes).hexdigest()}
    if mode == "write":
        (ROOT / "finite-binding-v2.hex").write_text(v2_bytes.hex() + "\n")
        (ROOT / "finite-binding-v2-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    else:
        assert (ROOT / "finite-binding-v2.hex").read_text().strip() == v2_bytes.hex(), "v2 hex is stale"
        assert json.loads((ROOT / "finite-binding-v2-manifest.json").read_text()) == manifest, "v2 manifest is stale"
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "check"))
