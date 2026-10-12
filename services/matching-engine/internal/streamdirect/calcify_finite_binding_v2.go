package streamdirect

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
)

// Binding v2 (docs/work/CALCIFY_FINITE_P3_BINDING_V2_SPEC.md): the v1 opening plus a finance block. v1 is frozen; every v1 rule
// is reused through validateCore and writeCanonicalBody, so v2 can only add constraints.

var finiteRolesV2 = []string{"COMMAND", "SOURCE", "CAPTURE", "OWNERSHIP", "CAPTURE_CHANGELOG", "CONTROL", "FINANCE_DECISIONS", "FINANCE_CHANGELOG"}

var finiteASCIIIdentifier = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

// FiniteFinanceBudget pins the O3a per-class limits; the record bound (296) is derived, never stored.
type FiniteFinanceBudget struct {
	Obligations    FiniteDecimal `json:"obligations"`
	Captures       FiniteDecimal `json:"captures"`
	SettleAttempts FiniteDecimal `json:"settleAttempts"`
	Controls       FiniteDecimal `json:"controls"`
}

type FiniteOpeningV2 struct {
	FiniteOpening
	FinanceApplicationID              string              `json:"financeApplicationId"`
	FinanceStoreName                  string              `json:"financeStoreName"`
	FinanceStateVersion               string              `json:"financeStateVersion"`
	OpeningAccountID                  string              `json:"openingAccountId"`
	ControlTransactionalID            string              `json:"controlTransactionalId"`
	FinanceConfigDigest               string              `json:"financeConfigDigest"`
	FinanceBudget                     FiniteFinanceBudget `json:"financeBudget"`
	InitialControlNextOffset          FiniteDecimal       `json:"initialControlNextOffset"`
	InitialFinanceDecisionsNextOffset FiniteDecimal       `json:"initialFinanceDecisionsNextOffset"`
	InitialFinanceCaptureNextOffset   FiniteDecimal       `json:"initialFinanceCaptureNextOffset"`
	InitialFinanceControlNextOffset   FiniteDecimal       `json:"initialFinanceControlNextOffset"`
}

// ParseFiniteBindingV2 is the strict, bounded v2 opening adapter. It dispatches nothing: callers choose the schema first.
func ParseFiniteBindingV2(raw []byte) (*FiniteOpeningV2, error) {
	if len(raw) > 16384 {
		return nil, fmt.Errorf("finite opening exceeds limit")
	}
	var o FiniteOpeningV2
	if err := finiteStrictJSON(raw, &o); err != nil {
		return nil, err
	}
	if err := o.validate(); err != nil {
		return nil, err
	}
	return &o, nil
}

// FinanceConfigDigestFor recomputes the O3a FinanceConfig digest from the opening (spec section 4).
func (o FiniteOpeningV2) FinanceConfigDigestFor() string {
	accounts := make([]string, 0, len(o.Parties))
	for _, p := range o.Parties {
		accounts = append(accounts, p.AccountID)
	}
	sort.Slice(accounts, func(i, j int) bool { return bytes.Compare([]byte(accounts[i]), []byte(accounts[j])) < 0 })
	var w bytes.Buffer
	finiteFrame(&w, "calcify-finance-config-v1")
	_ = binary.Write(&w, binary.BigEndian, int32(len(accounts)))
	for _, a := range accounts {
		finiteFrame(&w, a)
	}
	finiteFrame(&w, o.QuoteCurrency)
	finiteFrame(&w, o.BaseAssetID)
	finiteFrame(&w, o.OpeningAccountID)
	for _, n := range []FiniteDecimal{o.FinanceBudget.Obligations, o.FinanceBudget.Captures, o.FinanceBudget.SettleAttempts, o.FinanceBudget.Controls} {
		_ = binary.Write(&w, binary.BigEndian, int32(n))
	}
	sum := sha256.Sum256(w.Bytes())
	return hex.EncodeToString(sum[:])
}

func (o FiniteOpeningV2) CanonicalBytes() ([]byte, error) {
	o.Parties = finiteSortedParties(o.Parties)
	if err := o.validate(); err != nil {
		return nil, err
	}
	var w bytes.Buffer
	w.WriteString("calcify-finite-binding-v2\x00")
	o.FiniteOpening.writeCanonicalBody(&w)
	for _, s := range []string{o.FinanceApplicationID, o.FinanceStoreName, o.FinanceStateVersion, o.OpeningAccountID, o.ControlTransactionalID} {
		finiteFrame(&w, s)
	}
	digest, _ := finiteDigest(o.FinanceConfigDigest)
	w.Write(digest)
	for _, n := range []FiniteDecimal{o.FinanceBudget.Obligations, o.FinanceBudget.Captures, o.FinanceBudget.SettleAttempts, o.FinanceBudget.Controls} {
		_ = binary.Write(&w, binary.BigEndian, uint32(n))
	}
	for _, n := range []FiniteDecimal{o.InitialControlNextOffset, o.InitialFinanceDecisionsNextOffset, o.InitialFinanceCaptureNextOffset, o.InitialFinanceControlNextOffset} {
		_ = binary.Write(&w, binary.BigEndian, uint64(n))
	}
	if w.Len() > 16384 {
		return nil, fmt.Errorf("finite opening exceeds limit")
	}
	return w.Bytes(), nil
}

func (o FiniteOpeningV2) Digest() (string, error) {
	b, err := o.CanonicalBytes()
	if err != nil {
		return "", err
	}
	return finiteHash(b), nil
}

func finitePrefixRelated(a, b string) bool { return strings.HasPrefix(a, b) || strings.HasPrefix(b, a) }

func (o FiniteOpeningV2) validate() error {
	if err := o.FiniteOpening.validateCore("calcify-finite-binding-v2", finiteRolesV2); err != nil {
		return err
	}
	if o.FinanceStoreName != "finite-finance" || o.FinanceStateVersion != "calcify-finite-finance-state-v1" {
		return fmt.Errorf("finite finance version mismatch")
	}
	for _, s := range []string{o.FinanceApplicationID, o.ControlTransactionalID} {
		if !finiteText(s, 256) {
			return fmt.Errorf("invalid finite finance identity")
		}
	}
	// v2 tightening: ledger identifiers are printable ASCII so no ordering ambiguity exists across implementations.
	ids := []string{o.BaseAssetID, o.OpeningAccountID}
	for _, p := range o.Parties {
		ids = append(ids, p.AccountID)
	}
	for _, s := range ids {
		if !finiteASCIIIdentifier.MatchString(s) {
			return fmt.Errorf("finite ledger identifier must be 1..128 bytes of [A-Za-z0-9._:-]")
		}
	}
	if o.QuoteCurrency == o.BaseAssetID {
		return fmt.Errorf("finite cash and security assets must differ")
	}
	for _, p := range o.Parties {
		if p.AccountID == o.OpeningAccountID {
			return fmt.Errorf("finite opening account must be distinct from party accounts")
		}
	}
	if o.FinanceBudget.Obligations != 256 || o.FinanceBudget.Captures != 128 || o.FinanceBudget.SettleAttempts != 160 || o.FinanceBudget.Controls != 8 {
		return fmt.Errorf("finite finance budget differs from the compiled O3a limits")
	}
	if _, err := finiteDigest(o.FinanceConfigDigest); err != nil || o.FinanceConfigDigest != o.FinanceConfigDigestFor() {
		return fmt.Errorf("finite finance config digest mismatch")
	}
	for _, n := range []FiniteDecimal{o.InitialControlNextOffset, o.InitialFinanceDecisionsNextOffset, o.InitialFinanceCaptureNextOffset, o.InitialFinanceControlNextOffset} {
		if n != 0 {
			return fmt.Errorf("finite opening must retain genesis zero")
		}
	}
	if o.Topics[7].Name != o.FinanceApplicationID+"-finite-finance-changelog" {
		return fmt.Errorf("finite finance changelog name mismatch")
	}
	// Prefix-distinctness: the finance principal's prefix ACLs must cover only finance resources.
	for _, other := range []string{o.CaptureApplicationID, o.CommandConsumerGroup, o.OwnershipGroup, o.MatcherShardID, o.ControlTransactionalID} {
		if finitePrefixRelated(o.FinanceApplicationID, other) {
			return fmt.Errorf("finite finance application id must not be prefix-related to other identities")
		}
	}
	for i, t := range o.Topics {
		if i != 7 && finitePrefixRelated(o.FinanceApplicationID, t.Name) {
			return fmt.Errorf("finite finance application id must not be prefix-related to topic names")
		}
	}
	for _, other := range []string{o.CaptureApplicationID, o.CommandConsumerGroup, o.OwnershipGroup} {
		if finitePrefixRelated(o.ControlTransactionalID, other) {
			return fmt.Errorf("finite control transactional id must not be prefix-related to other identities")
		}
	}
	openingJSON, err := json.Marshal(o)
	if err != nil || len(openingJSON) > int(o.Budget.OpeningBytes) {
		return fmt.Errorf("finite opening measured byte budget exceeded")
	}
	return nil
}
