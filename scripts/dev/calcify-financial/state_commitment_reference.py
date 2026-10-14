#!/usr/bin/env python3
"""Independent reference encoder for the Calcify capacity profile's state commitment C(S)
(docs/work/CALCIFY_FINANCE_STATE_COMMITMENT_DESIGN.md, sections 3 and 4.1). Not shared with the Kotlin implementation: it is written from the specification
text, builds the trie by recursive partition on 256-bit integers (the Kotlin reference compares bytes), and generates the goldens the Kotlin tests assert.

  python3 scripts/dev/calcify-financial/state_commitment_reference.py check   # regenerate in memory and compare with the checked-in goldens
  python3 scripts/dev/calcify-financial/state_commitment_reference.py write   # regenerate services/platform-runtime/src/test/resources/calcify/state-commitment-goldens.json
"""
import hashlib, json, pathlib, struct, sys

GOLDENS = pathlib.Path(__file__).resolve().parents[3] / "services" / "platform-runtime" / "src" / "test" / "resources" / "calcify" / "state-commitment-goldens.json"
LABEL = "calcify-finance-state-v2-patricia"
KEY_LABEL = "calcify-finance-key-v2"

# field types: s = text (int32 length + strict UTF-8), l = int64 big-endian, i = int32 big-endian
SCHEMA = {
    "P": ("ss", "l"),        # position: account, asset -> amount
    "X": ("ssss", "ssll"),   # execution: run, session, instrument, execution -> buyer, seller, quantity, priceNanos
    "O": ("ssss", "sll"),    # obligation: same key -> status, cashResidual, shareResidual
    "A": ("ssssi", "s"),     # attempt: run, session, instrument, execution, attempt -> disposition
    "K": ("sss", "ss"),      # action: namespace, domain, actionId -> input digest (text), disposition
}


def sha(*parts: bytes) -> bytes:
    h = hashlib.sha256()
    for p in parts:
        h.update(p)
    return h.digest()


def frame(text: str) -> bytes:
    raw = text.encode("utf-8")  # strict: a lone surrogate raises
    return struct.pack(">i", len(raw)) + raw


def admissible(text: str) -> bool:
    """The input-side rule of section 3.1: not blank, no ISO control character, at most 128 UTF-8 bytes."""
    if text.strip() == "" or any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in text):
        return False
    return len(text.encode("utf-8")) <= 128


def encode(types: str, fields) -> bytes:
    assert len(types) == len(fields), "field count"
    out = b""
    for t, f in zip(types, fields):
        if t == "s":
            out += frame(f)
        elif t == "l":
            out += struct.pack(">q", f)
        elif t == "i":
            out += struct.pack(">i", f)
        else:
            raise ValueError(t)
    return out


def entity_key_leaf(tag: str, key, value):
    ktypes, vtypes = SCHEMA[tag]
    # Identifiers must be admissible (input-side rule); the action's input digest is validated separately as 64 lowercase hex.
    for idx, (t, f) in enumerate(zip(ktypes + vtypes, list(key) + list(value))):
        if t != "s":
            continue
        if tag == "K" and idx == len(ktypes):
            assert len(f) == 64 and all(c in "0123456789abcdef" for c in f), "action digest is not 64 lowercase hex: %r" % (f,)
        else:
            assert admissible(f), "identifier not admissible: %r" % (f,)
    k = sha(frame(KEY_LABEL), frame(tag), encode(ktypes, key))
    leaf = sha(b"\x00", k, sha(b"\x01", encode(vtypes, value)))
    return k, leaf


def trie_root(pairs):
    """pairs: list of (key32, leaf32). Recursive partition on the first bit at which the key set splits."""
    if not pairs:
        return sha(b"\x03")
    as_int = sorted(((int.from_bytes(k, "big"), leaf) for k, leaf in pairs), key=lambda p: p[0])
    assert len({k for k, _ in as_int}) == len(as_int), "duplicate trie key"

    def build(items):
        if len(items) == 1:
            return items[0][1]
        spread = items[0][0] ^ items[-1][0]
        index = 256 - spread.bit_length()          # absolute bit index from the most significant bit
        shift = 255 - index
        left = [p for p in items if ((p[0] >> shift) & 1) == 0]
        right = [p for p in items if ((p[0] >> shift) & 1) == 1]
        assert left and right and len(left) + len(right) == len(items)
        return sha(b"\x02", struct.pack(">H", index), build(left), build(right))

    return build(as_int)


def commitment(sequence: int, controls: int, trie: bytes) -> bytes:
    return sha(frame(LABEL), struct.pack(">q", sequence), struct.pack(">i", controls), trie)


def case(name, sequence, controls, entities, detail=True):
    pairs = []
    keys, leaves = [], []
    for e in entities:
        k, leaf = entity_key_leaf(e["tag"], e["key"], e["value"])
        pairs.append((k, leaf)); keys.append(k.hex()); leaves.append(leaf.hex())
    t = trie_root(pairs)
    out = {"name": name, "sequence": sequence, "controls": controls, "entities": entities}
    if detail:
        out["entityKeys"] = keys; out["entityLeaves"] = leaves
    out["trie"] = t.hex(); out["commitment"] = commitment(sequence, controls, t).hex()
    return out


def ent(tag, key, value):
    return {"tag": tag, "key": list(key), "value": list(value)}


DIGEST_A = "a" * 64
DIGEST_B = "0123456789abcdef" * 4
EX = ("run-1", "session-1", "AAPL", "exec-1")


def small_cases():
    one = {
        "P": ent("P", ("acct-1", "USD"), (123456789,)),
        "X": ent("X", EX, ("buyer-1", "seller-1", 10, 100000000000)),
        "O": ent("O", EX, ("PENDING", 1000000000000, 10)),
        "A": ent("A", EX + (1,), ("SETTLED",)),
        "K": ent("K", ("n", "cap", "c1"), (DIGEST_A, "CAPTURED")),
    }
    cases = [
        case("empty", 0, 0, []),
        case("empty-with-scalars", 5, 2, []),
    ]
    for tag in "PXOAK":
        cases.append(case("single-%s" % tag, 1, 0, [one[tag]]))
    cases.append(case("one-of-each-class", 7, 1, [one[t] for t in "PXOAK"]))
    cases.append(case("one-of-each-class-reversed-input-order", 7, 1, [one[t] for t in "KAOXP"]))
    cases.append(case("positions-zero-negative-and-nonzero", 12, 3, [
        ent("P", ("acct-1", "USD"), (0,)), ent("P", ("acct-1", "AAPL"), (-5,)), ent("P", ("acct-2", "USD"), (9223372036854775807,)),
        ent("P", ("acct-2", "AAPL"), (-9223372036854775808,)), ent("P", ("acct-3", "USD"), (1,)),
    ]))
    long_multibyte = "日" * 42 + "ab"                    # 126 + 2 = 128 UTF-8 bytes
    cases.append(case("utf8-and-boundary-length-identifiers", 99, 8, [
        ent("P", ("acct-é", "ASSET-日本語"), (7,)),
        ent("P", ("acct-😀", "USD"), (8,)),
        ent("P", (long_multibyte, "USD"), (9,)),
        ent("P", ("a" * 128, "USD"), (10,)),
        ent("X", ("run-é", "session-日本", "AAPL-😀", "e" * 128), ("buyer-é", "seller-日", 3, 4)),
        ent("K", ("ns-é", "domain-日本語", "id-😀"), (DIGEST_B, "SETTLED")),
    ]))
    cases.append(case("attempts-with-numbers", 40, 0, [
        ent("A", EX + (1,), ("INSUFFICIENT_CASH",)), ent("A", EX + (2,), ("SETTLED",)), ent("A", EX + (2147483647,), ("BALANCE_OVERFLOW",)),
    ]))
    return cases


def large_case(n=1000):
    entities = []
    for i in range(n):
        h = hashlib.sha256(b"calcify-state-commitment-golden-%d" % i).digest()
        v = int.from_bytes(h[:8], "big", signed=True)
        w = int.from_bytes(h[8:16], "big", signed=False) % 1000000
        kind = i % 5
        run, session, instr, eid = "run-%d" % (i % 3), "session-%d" % (i % 7), "INS%d" % (i % 11), "exec-%d" % i
        if kind == 0:
            entities.append(ent("P", ("acct-%d" % i, "USD" if i % 2 else "AAPL"), (v,)))
        elif kind == 1:
            entities.append(ent("X", (run, session, instr, eid), ("buyer-%d" % (i % 13), "seller-%d" % (i % 17), w + 1, w * 1000)))
        elif kind == 2:
            entities.append(ent("O", (run, session, instr, eid), ("PENDING" if i % 2 else "SETTLED", 0 if i % 2 == 0 else w, 0 if i % 2 == 0 else w)))
        elif kind == 3:
            entities.append(ent("A", (run, session, instr, eid, 1 + (i % 4)), (["SETTLED", "INSUFFICIENT_CASH", "INSUFFICIENT_SHARES", "BALANCE_OVERFLOW"][i % 4],)))
        else:
            entities.append(ent("K", ("ns-%d" % (i % 5), "dom-%d" % (i % 3), "action-%d" % i), (h.hex(), "FUNDED")))
    return case("large-%d-entities" % n, 123456, 8, entities, detail=False)


def trie_cases():
    def k(prefix_hex, fill="00"):
        return bytes.fromhex((prefix_hex + fill * 32)[:64])

    def pair(key, tag):
        return {"key": key.hex(), "leaf": sha(b"leaf-" + tag).hex()}

    def tcase(name, pairs):
        raw = [(bytes.fromhex(p["key"]), bytes.fromhex(p["leaf"])) for p in pairs]
        return {"name": name, "pairs": pairs, "root": trie_root(raw).hex()}

    last_bit = bytes(31) + b"\x01"
    cases = [
        tcase("empty", []),
        tcase("single-leaf-has-no-branch", [pair(k("80"), b"a")]),
        tcase("split-at-bit-0", [pair(k("00"), b"a"), pair(k("80"), b"b")]),
        tcase("split-at-bit-7", [pair(k("00"), b"a"), pair(k("01"), b"b")]),
        tcase("split-at-bit-8", [pair(k("0000"), b"a"), pair(k("0080"), b"b")]),
        tcase("split-at-bit-255", [pair(bytes(32), b"a"), pair(last_bit, b"b")]),
        tcase("insertion-order-does-not-matter", [pair(k("c0"), b"a"), pair(k("40"), b"b"), pair(k("60"), b"c"), pair(k("e0"), b"d"), pair(k("41"), b"e")]),
    ]
    # a caterpillar: one key diverging from the all-zero key at each bit 0..19
    cat = [pair(bytes(32), b"base")]
    for b in range(20):
        key = bytearray(32); key[b // 8] |= 0x80 >> (b % 8)
        cat.append(pair(bytes(key), b"cat%d" % b))
    cases.append(tcase("caterpillar-depth-20", cat))
    return cases


def build():
    cases = small_cases() + [large_case()]
    return {"label": LABEL, "keyLabel": KEY_LABEL, "cases": cases, "trieCases": trie_cases()}


def main(mode: str) -> int:
    doc = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if mode == "write":
        GOLDENS.parent.mkdir(parents=True, exist_ok=True)
        GOLDENS.write_text(doc, encoding="utf-8")
        print("wrote", GOLDENS)
        return 0
    if mode == "check":
        if not GOLDENS.exists() or GOLDENS.read_text(encoding="utf-8") != doc:
            print("state commitment goldens differ from the independent encoder: run `write` and review the diff", file=sys.stderr)
            return 1
        print("state commitment goldens match the independent encoder")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "check"))
