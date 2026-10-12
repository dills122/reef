package streamdirect

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

const finiteStartupLimit = 32768
const finiteOffsetLimit = uint64(1<<48 - 1)

// FiniteDecimal preserves the witness's canonical unsigned decimal string type.
type FiniteDecimal uint64

func (n FiniteDecimal) MarshalJSON() ([]byte, error) {
	return json.Marshal(strconv.FormatUint(uint64(n), 10))
}
func (n *FiniteDecimal) UnmarshalJSON(raw []byte) error {
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		return fmt.Errorf("finite integer must be decimal string")
	}
	if value == "" || (len(value) > 1 && value[0] == '0') {
		return fmt.Errorf("noncanonical finite decimal")
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return fmt.Errorf("noncanonical finite decimal")
		}
	}
	v, err := strconv.ParseUint(value, 10, 63)
	if err != nil {
		return fmt.Errorf("finite integer overflow")
	}
	*n = FiniteDecimal(v)
	return nil
}

type FiniteBudget struct {
	Attempts     FiniteDecimal `json:"attempts"`
	Publications FiniteDecimal `json:"publications"`
	CommandBytes FiniteDecimal `json:"commandBytes"`
	Outcomes     FiniteDecimal `json:"outcomes"`
	Trades       FiniteDecimal `json:"trades"`
	OpenWindows  FiniteDecimal `json:"openWindows"`
	SourceBytes  FiniteDecimal `json:"sourceBytes"`
	CaptureBytes FiniteDecimal `json:"captureBytes"`
	AttemptBytes FiniteDecimal `json:"attemptBytes"`
	OpeningBytes FiniteDecimal `json:"openingBytes"`
}

func (b FiniteBudget) values() []FiniteDecimal {
	return []FiniteDecimal{b.Attempts, b.Publications, b.CommandBytes, b.Outcomes, b.Trades, b.OpenWindows, b.SourceBytes, b.CaptureBytes, b.AttemptBytes, b.OpeningBytes}
}

type FiniteParty struct {
	ParticipantID string `json:"participantId"`
	AccountID     string `json:"accountId"`
	Side          string `json:"side"`
}
type FiniteTopic struct {
	Role    string `json:"role"`
	Name    string `json:"name"`
	TopicID string `json:"topicId"`
}
type FiniteOpening struct {
	Schema                              string                   `json:"schema"`
	Profile                             app.CalcifySourceProfile `json:"profile"`
	ProfileHash                         string                   `json:"profileHash"`
	Budget                              FiniteBudget             `json:"budget"`
	BudgetHash                          string                   `json:"budgetHash"`
	LifecycleSchema                     string                   `json:"lifecycleSchema"`
	PolicyID                            string                   `json:"policyId"`
	RunID                               string                   `json:"runId"`
	VenueSessionID                      string                   `json:"venueSessionId"`
	InstrumentID                        string                   `json:"instrumentId"`
	QuoteCurrency                       string                   `json:"quoteCurrency"`
	BaseAssetID                         string                   `json:"baseAssetId"`
	Parties                             []FiniteParty            `json:"parties"`
	Topics                              []FiniteTopic            `json:"topics"`
	SourceGeneration                    int                      `json:"sourceGeneration"`
	Partition                           int                      `json:"partition"`
	PartitionCount                      int                      `json:"partitionCount"`
	CommandConsumerGroup                string                   `json:"commandConsumerGroup"`
	OwnershipGroup                      string                   `json:"ownershipGroup"`
	MatcherShardID                      string                   `json:"matcherShardId"`
	CommandSubjectPrefix                string                   `json:"commandSubjectPrefix"`
	SourceSubjectPrefix                 string                   `json:"sourceSubjectPrefix"`
	BrokerClusterID                     string                   `json:"brokerClusterId"`
	CaptureApplicationID                string                   `json:"captureApplicationId"`
	StoreName                           string                   `json:"storeName"`
	StateVersion                        string                   `json:"stateVersion"`
	OpeningMode                         string                   `json:"openingMode"`
	InitialCommandNextOffset            FiniteDecimal            `json:"initialCommandNextOffset"`
	InitialSourceNextOffset             FiniteDecimal            `json:"initialSourceNextOffset"`
	InitialCaptureNextOffset            FiniteDecimal            `json:"initialCaptureNextOffset"`
	InitialCaptureSourceGroupNextOffset FiniteDecimal            `json:"initialCaptureSourceGroupNextOffset"`
}
type FiniteRun struct {
	OwnerInstanceID        string        `json:"ownerInstanceId"`
	OwnerEpoch             FiniteDecimal `json:"ownerEpoch"`
	ActivationEpoch        FiniteDecimal `json:"activationEpoch"`
	AdmissionState         string        `json:"admissionState"`
	RecordedAttempts       FiniteDecimal `json:"recordedAttempts"`
	PossiblePublications   FiniteDecimal `json:"possiblePublications"`
	CommandPayloadBytes    FiniteDecimal `json:"commandPayloadBytes"`
	AttemptRecordBytes     FiniteDecimal `json:"attemptRecordBytes"`
	UnresolvedPublications FiniteDecimal `json:"unresolvedPublications"`
}
type FiniteGroupCut struct {
	GroupID    string        `json:"groupId"`
	NextOffset FiniteDecimal `json:"nextOffset"`
}
type FiniteRange struct {
	Role                   string        `json:"role"`
	Partition              int           `json:"partition"`
	BeginningOffset        FiniteDecimal `json:"beginningOffset"`
	EndOffset              FiniteDecimal `json:"endOffset"`
	ReadCommittedEndOffset FiniteDecimal `json:"readCommittedEndOffset"`
}
type FiniteCuts struct {
	CommandGroup       FiniteGroupCut `json:"commandGroup"`
	CaptureSourceGroup FiniteGroupCut `json:"captureSourceGroup"`
	Ranges             []FiniteRange  `json:"ranges"`
}
type FiniteJournalEntry struct {
	PublicationOrdinal FiniteDecimal  `json:"publicationOrdinal"`
	CommandID          string         `json:"commandId"`
	CommandType        string         `json:"commandType"`
	PayloadHash        string         `json:"payloadHash"`
	KeyHash            string         `json:"keyHash"`
	SubjectHash        string         `json:"subjectHash"`
	RouteHash          string         `json:"routeHash"`
	OwnerEpoch         FiniteDecimal  `json:"ownerEpoch"`
	State              string         `json:"state"`
	Partition          int            `json:"partition"`
	Offset             *FiniteDecimal `json:"offset"`
	StreamSequence     *FiniteDecimal `json:"streamSequence"`
	PayloadBytes       FiniteDecimal  `json:"payloadBytes"`
}
type FiniteStartupWitness struct {
	Schema        string               `json:"schema"`
	Nonce         string               `json:"nonce"`
	BindingDigest string               `json:"bindingDigest"`
	Opening       FiniteOpening        `json:"opening"`
	Run           FiniteRun            `json:"run"`
	Cuts          FiniteCuts           `json:"cuts"`
	Journal       []FiniteJournalEntry `json:"journal"`
}

func finiteHash(raw []byte) string { sum := sha256.Sum256(raw); return hex.EncodeToString(sum[:]) }
func finiteDigest(value string) ([]byte, error) {
	raw, err := hex.DecodeString(value)
	if err != nil || len(raw) != 32 || len(value) != 64 || value != strings.ToLower(value) {
		return nil, fmt.Errorf("invalid finite SHA256 digest")
	}
	return raw, nil
}
func finiteFrame(w *bytes.Buffer, value string) {
	_ = binary.Write(w, binary.BigEndian, uint32(len([]byte(value))))
	w.WriteString(value)
}
func finiteUTF16Less(a, b string) bool {
	x, y := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(x) && i < len(y); i++ {
		if x[i] != y[i] {
			return x[i] < y[i]
		}
	}
	return len(x) < len(y)
}
func finiteSortedParties(parties []FiniteParty) []FiniteParty {
	p := append([]FiniteParty(nil), parties...)
	sort.Slice(p, func(i, j int) bool {
		if p[i].ParticipantID != p[j].ParticipantID {
			return finiteUTF16Less(p[i].ParticipantID, p[j].ParticipantID)
		}
		return finiteUTF16Less(p[i].AccountID, p[j].AccountID)
	})
	return p
}
func finiteWriteParties(w *bytes.Buffer, parties []FiniteParty) {
	_ = binary.Write(w, binary.BigEndian, uint32(len(parties)))
	for _, p := range finiteSortedParties(parties) {
		finiteFrame(w, p.ParticipantID)
		finiteFrame(w, p.AccountID)
		if p.Side == "BUY" {
			w.WriteByte(1)
		} else {
			w.WriteByte(2)
		}
	}
}
func (o FiniteOpening) BudgetCanonical() ([]byte, error) {
	profile, err := finiteDigest(o.ProfileHash)
	if err != nil {
		return nil, err
	}
	var w bytes.Buffer
	w.WriteString("calcify-finite-budget-v1\x00")
	w.Write(profile)
	for _, s := range []string{o.PolicyID, o.RunID, o.VenueSessionID, o.InstrumentID, o.QuoteCurrency} {
		finiteFrame(&w, s)
	}
	finiteWriteParties(&w, o.Parties)
	for _, n := range o.Budget.values() {
		_ = binary.Write(&w, binary.BigEndian, uint64(n))
	}
	return w.Bytes(), nil
}

// CanonicalBytes excludes the binding digest itself; it does not attest registration.
func (o FiniteOpening) CanonicalBytes() ([]byte, error) {
	o.Parties = finiteSortedParties(o.Parties)
	if err := o.validate(); err != nil {
		return nil, err
	}
	var w bytes.Buffer
	w.WriteString("calcify-finite-binding-v1\x00")
	o.writeCanonicalBody(&w)
	if w.Len() > 16384 {
		return nil, fmt.Errorf("finite opening exceeds limit")
	}
	return w.Bytes(), nil
}

// writeCanonicalBody writes everything after the schema prefix; v1 and v2 share it unchanged (v2 appends a finance suffix).
func (o FiniteOpening) writeCanonicalBody(w *bytes.Buffer) {
	profile, _ := finiteDigest(o.ProfileHash)
	budget, _ := finiteDigest(o.BudgetHash)
	w.Write(profile)
	w.Write(budget)
	for _, s := range []string{o.LifecycleSchema, o.PolicyID, o.RunID, o.VenueSessionID, o.InstrumentID, o.QuoteCurrency, o.BaseAssetID} {
		finiteFrame(w, s)
	}
	finiteWriteParties(w, o.Parties)
	for _, t := range o.Topics {
		finiteFrame(w, t.Name)
		finiteFrame(w, t.TopicID)
	}
	for _, n := range []int{o.SourceGeneration, o.Partition, o.PartitionCount} {
		_ = binary.Write(w, binary.BigEndian, uint32(n))
	}
	for _, s := range []string{o.CommandConsumerGroup, o.OwnershipGroup, o.MatcherShardID, o.CommandSubjectPrefix, o.SourceSubjectPrefix, o.BrokerClusterID, o.CaptureApplicationID, o.StoreName, o.StateVersion} {
		finiteFrame(w, s)
	}
	w.WriteByte(1)
	for _, n := range []FiniteDecimal{o.InitialCommandNextOffset, o.InitialSourceNextOffset, o.InitialCaptureNextOffset, o.InitialCaptureSourceGroupNextOffset} {
		_ = binary.Write(w, binary.BigEndian, uint64(n))
	}
}
func (o FiniteOpening) Digest() (string, error) {
	b, err := o.CanonicalBytes()
	if err != nil {
		return "", err
	}
	return finiteHash(b), nil
}
func finiteText(value string, limit int) bool {
	if value == "" || !utf8.ValidString(value) || len(value) > limit || strings.TrimSpace(value) != value {
		return false
	}
	for _, c := range value {
		if c < 32 || c == 127 {
			return false
		}
	}
	return true
}

var finiteRoles = []string{"COMMAND", "SOURCE", "CAPTURE", "OWNERSHIP", "CAPTURE_CHANGELOG"}

func (o FiniteOpening) validate() error {
	return o.validateCore("calcify-finite-binding-v1", finiteRoles)
}

// validateCore is the v1 rule set parameterized by schema id and expected topic roles, so v2 reuses every v1 check unchanged.
func (o FiniteOpening) validateCore(schema string, roles []string) error {
	if o.Schema != schema || o.LifecycleSchema != "calcify-order-lifecycle-command-v1" || o.PolicyID != "calcify-limit-day-v1" || o.StateVersion != "calcify-finite-capture-state-v1" || o.StoreName != "finite-lifecycle" || o.OpeningMode != "GENESIS" {
		return fmt.Errorf("finite opening version/policy mismatch")
	}
	if o.SourceGeneration < 1 || o.SourceGeneration > 2147483647 || o.Partition != 0 || o.PartitionCount != 1 {
		return fmt.Errorf("finite routing mismatch")
	}
	for _, n := range []FiniteDecimal{o.InitialCommandNextOffset, o.InitialSourceNextOffset, o.InitialCaptureNextOffset, o.InitialCaptureSourceGroupNextOffset} {
		if n != 0 {
			return fmt.Errorf("finite opening must retain genesis zero")
		}
	}
	for _, s := range []string{o.RunID, o.VenueSessionID, o.InstrumentID} {
		if !finiteText(s, 128) {
			return fmt.Errorf("invalid finite scope")
		}
	}
	for _, s := range []string{o.BaseAssetID, o.CommandConsumerGroup, o.OwnershipGroup, o.MatcherShardID, o.CommandSubjectPrefix, o.SourceSubjectPrefix, o.BrokerClusterID, o.CaptureApplicationID} {
		if !finiteText(s, 256) {
			return fmt.Errorf("invalid finite operational identity")
		}
	}
	if len(o.QuoteCurrency) != 3 {
		return fmt.Errorf("invalid finite quote")
	}
	for _, c := range o.QuoteCurrency {
		if c < 'A' || c > 'Z' {
			return fmt.Errorf("invalid finite quote")
		}
	}
	p := o.Profile
	if p.Schema != "calcify-finite-source-v1" || len(p.RunIDs) != 1 || p.RunIDs[0] != o.RunID || p.VenueSessionID != o.VenueSessionID || p.InstrumentID != o.InstrumentID || p.Currency != o.QuoteCurrency || p.MaxOrderIDs < 1 || p.MaxOrderIDs > 8 || p.MaxQuantityUnits < 1 || p.MaxQuantityUnits > 10 || p.MaxLimitPrice < 1 || p.MaxLimitPrice > 100000000000 {
		return fmt.Errorf("finite profile scope/caps mismatch")
	}
	raw, _ := json.Marshal(p)
	if finiteHash(raw) != o.ProfileHash {
		return fmt.Errorf("finite profile digest mismatch")
	}
	if len(o.Parties) != 2 || o.Parties[0].ParticipantID == o.Parties[1].ParticipantID || o.Parties[0].AccountID == o.Parties[1].AccountID || o.Parties[0].Side == o.Parties[1].Side {
		return fmt.Errorf("finite parties mismatch")
	}
	for _, p := range o.Parties {
		if !finiteText(p.ParticipantID, 128) || !finiteText(p.AccountID, 128) || (p.Side != "BUY" && p.Side != "SELL") {
			return fmt.Errorf("invalid finite party")
		}
	}
	if !reflect.DeepEqual(o.Parties, finiteSortedParties(o.Parties)) {
		return fmt.Errorf("finite parties must be comparator-sorted")
	}
	if len(o.Topics) != len(roles) {
		return fmt.Errorf("finite topic roles missing")
	}
	names, ids := map[string]bool{}, map[string]bool{}
	for i, t := range o.Topics {
		id, err := base64.RawURLEncoding.DecodeString(t.TopicID)
		if t.Role != roles[i] || !finiteText(t.Name, 256) || err != nil || len(id) != 16 || bytes.Equal(id, make([]byte, 16)) || base64.RawURLEncoding.EncodeToString(id) != t.TopicID || names[t.Name] || ids[t.TopicID] {
			return fmt.Errorf("invalid finite topic identity")
		}
		names[t.Name], ids[t.TopicID] = true, true
	}
	if o.Topics[4].Name != o.CaptureApplicationID+"-finite-lifecycle-changelog" || trimDot(o.CommandSubjectPrefix) != o.CommandSubjectPrefix || trimDot(o.SourceSubjectPrefix) != o.SourceSubjectPrefix {
		return fmt.Errorf("finite derived identity mismatch")
	}
	limits := []uint64{32, 16, 8192, 1, 8, 1, 65536, 131072, 4096, 16384}
	for i, v := range o.Budget.values() {
		if v < 1 || uint64(v) > limits[i] {
			return fmt.Errorf("finite budget outside cap")
		}
	}
	b, err := o.BudgetCanonical()
	if err != nil || finiteHash(b) != o.BudgetHash {
		return fmt.Errorf("finite budget digest mismatch")
	}
	openingJSON, err := json.Marshal(o)
	if err != nil || len(openingJSON) > int(o.Budget.OpeningBytes) {
		return fmt.Errorf("finite opening measured byte budget exceeded")
	}
	return nil
}

// ParseFiniteStartup is a strict bounded adapter, not a live activation capability.
func ParseFiniteStartup(raw []byte, nonce, digest string) (*FiniteStartupWitness, error) {
	if len(raw) > finiteStartupLimit {
		return nil, fmt.Errorf("finite witness exceeds wire limit")
	}
	var w FiniteStartupWitness
	if err := finiteStrictJSON(raw, &w); err != nil {
		return nil, err
	}
	if w.Schema != "calcify-finite-startup-v1" || !finiteNonce(nonce) || w.Nonce != nonce || w.BindingDigest != digest {
		return nil, fmt.Errorf("finite witness challenge/identity mismatch")
	}
	if !reflect.DeepEqual(w.Opening.Parties, finiteSortedParties(w.Opening.Parties)) {
		return nil, fmt.Errorf("finite witness parties must be comparator-sorted")
	}
	binding, err := w.Opening.Digest()
	if err != nil || binding != digest {
		return nil, fmt.Errorf("finite witness canonical binding mismatch: %v", err)
	}
	if err := w.validate(); err != nil {
		return nil, err
	}
	return &w, nil
}
func finiteNonce(value string) bool {
	if len(value) < 1 || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
func (w FiniteStartupWitness) validate() error {
	b := w.Opening.Budget
	r := w.Run
	if !finiteText(r.OwnerInstanceID, 128) || r.OwnerEpoch < 1 || (r.AdmissionState != "STOPPED" && r.AdmissionState != "ACTIVE") || r.RecordedAttempts > b.Attempts || r.PossiblePublications > b.Publications || r.CommandPayloadBytes > b.Publications*b.CommandBytes || r.AttemptRecordBytes > b.Attempts*b.AttemptBytes || r.UnresolvedPublications > b.Publications || uint64(len(w.Journal)) != uint64(r.PossiblePublications) {
		return fmt.Errorf("finite accounting mismatch")
	}
	if w.Cuts.CommandGroup.GroupID != w.Opening.CommandConsumerGroup || w.Cuts.CaptureSourceGroup.GroupID != w.Opening.CaptureApplicationID || uint64(w.Cuts.CommandGroup.NextOffset) > finiteOffsetLimit || uint64(w.Cuts.CaptureSourceGroup.NextOffset) > finiteOffsetLimit || len(w.Cuts.Ranges) != 5 {
		return fmt.Errorf("finite cuts mismatch")
	}
	for i, r := range w.Cuts.Ranges {
		if r.Role != finiteRoles[i] || r.Partition != 0 || r.BeginningOffset > r.ReadCommittedEndOffset || r.ReadCommittedEndOffset > r.EndOffset || uint64(r.EndOffset) > finiteOffsetLimit {
			return fmt.Errorf("invalid finite range")
		}
	}
	if w.Cuts.CommandGroup.NextOffset > w.Cuts.Ranges[0].ReadCommittedEndOffset || w.Cuts.CaptureSourceGroup.NextOffset > w.Cuts.Ranges[1].ReadCommittedEndOffset {
		return fmt.Errorf("finite group cut beyond history")
	}
	ids := map[string]bool{}
	var unresolved FiniteDecimal
	var payloadBytes FiniteDecimal
	for i, j := range w.Journal {
		raw, _ := json.Marshal(j)
		if len(raw) > 1024 || j.PublicationOrdinal != FiniteDecimal(i+1) || !finiteText(j.CommandID, 128) || ids[j.CommandID] || j.OwnerEpoch < 1 || j.OwnerEpoch > r.OwnerEpoch || j.Partition != 0 || j.PayloadBytes < 1 || j.PayloadBytes > b.CommandBytes {
			return fmt.Errorf("invalid finite journal entry")
		}
		ids[j.CommandID] = true
		payloadBytes += j.PayloadBytes
		if j.CommandType != "SubmitOrder" && j.CommandType != "ModifyOrder" && j.CommandType != "CancelOrder" {
			return fmt.Errorf("invalid finite command type")
		}
		for _, v := range []string{j.PayloadHash, j.KeyHash, j.SubjectHash, j.RouteHash} {
			if _, err := finiteDigest(v); err != nil {
				return err
			}
		}
		switch j.State {
		case "PUBLISHED":
			if j.Offset == nil || j.StreamSequence == nil || uint64(*j.Offset) >= finiteOffsetLimit || *j.StreamSequence != *j.Offset+1 {
				return fmt.Errorf("finite published position mismatch")
			}
		case "RESERVED", "PUBLISHING", "UNKNOWN":
			unresolved++
			if j.Offset != nil || j.StreamSequence != nil {
				return fmt.Errorf("uncertain finite entry has position")
			}
		case "DEFINITELY_NOT_SENT":
			if j.Offset != nil || j.StreamSequence != nil {
				return fmt.Errorf("unsent finite entry has position")
			}
		default:
			return fmt.Errorf("invalid finite journal state")
		}
	}
	if unresolved != r.UnresolvedPublications || payloadBytes != r.CommandPayloadBytes || r.RecordedAttempts < r.PossiblePublications {
		return fmt.Errorf("finite ledger totals mismatch")
	}
	w.Journal = []FiniteJournalEntry{}
	raw, _ := json.Marshal(w)
	if len(raw) > 8192 {
		return fmt.Errorf("finite nonjournal witness exceeds limit")
	}
	return nil
}

func finiteStrictJSON(raw []byte, target any) error {
	if !utf8.Valid(raw) {
		return fmt.Errorf("invalid finite UTF8")
	}
	// encoding/json replaces lone surrogates; reject them before token decoding.
	for i := 0; i < len(raw); i++ {
		if raw[i] != '"' {
			continue
		}
		i++
		for i < len(raw) && raw[i] != '"' {
			if raw[i] == '\\' {
				i++
				if i < len(raw) && raw[i] == 'u' {
					if i+4 >= len(raw) {
						return fmt.Errorf("invalid finite Unicode escape")
					}
					v, err := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
					if err != nil {
						return err
					}
					i += 4
					if v >= 0xD800 && v <= 0xDBFF {
						if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
							return fmt.Errorf("unpaired finite surrogate")
						}
						low, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
						if err != nil || low < 0xDC00 || low > 0xDFFF {
							return fmt.Errorf("unpaired finite surrogate")
						}
						i += 6
					} else if v >= 0xDC00 && v <= 0xDFFF {
						return fmt.Errorf("unpaired finite surrogate")
					}
				}
			}
			i++
		}
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	var walk func() error
	walk = func() error {
		token, err := d.Token()
		if err != nil {
			return err
		}
		if x, ok := token.(json.Delim); ok {
			switch x {
			case '{':
				keys := map[string]bool{}
				for d.More() {
					k, err := d.Token()
					if err != nil {
						return err
					}
					key, ok := k.(string)
					if !ok || keys[key] {
						return fmt.Errorf("duplicate finite JSON key")
					}
					keys[key] = true
					if err := walk(); err != nil {
						return err
					}
				}
				_, err = d.Token()
				return err
			case '[':
				for d.More() {
					if err := walk(); err != nil {
						return err
					}
				}
				_, err = d.Token()
				return err
			default:
				return fmt.Errorf("invalid finite JSON delimiter")
			}
		}
		return nil
	}
	if err := walk(); err != nil {
		return err
	}
	if _, err := d.Token(); err != io.EOF {
		return fmt.Errorf("finite JSON trailing value")
	}
	var shape any
	if err := json.Unmarshal(raw, &shape); err != nil {
		return err
	}
	if err := finiteRequired(shape, reflect.TypeOf(target).Elem()); err != nil {
		return err
	}
	d = json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if err := d.Decode(target); err != nil {
		return err
	}
	return nil
}
func finiteRequired(value any, t reflect.Type) error {
	if t.Kind() == reflect.Pointer {
		if value == nil {
			return nil
		}
		return finiteRequired(value, t.Elem())
	}
	if t.Kind() == reflect.Struct {
		object, ok := value.(map[string]any)
		if !ok {
			return fmt.Errorf("finite JSON object required")
		}
		for i := 0; i < t.NumField(); i++ {
			f := t.Field(i)
			if f.Anonymous && f.Tag.Get("json") == "" {
				if err := finiteRequired(value, f.Type); err != nil {
					return err
				}
				continue
			}
			key := strings.Split(f.Tag.Get("json"), ",")[0]
			v, ok := object[key]
			if !ok {
				return fmt.Errorf("missing finite field %s", key)
			}
			if err := finiteRequired(v, f.Type); err != nil {
				return err
			}
		}
	} else if t.Kind() == reflect.Slice {
		list, ok := value.([]any)
		if !ok {
			return fmt.Errorf("finite JSON array required")
		}
		for _, v := range list {
			if err := finiteRequired(v, t.Elem()); err != nil {
				return err
			}
		}
	} else if value == nil {
		return fmt.Errorf("null finite scalar")
	}
	return nil
}
