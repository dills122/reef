package streamdirect

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/dills122/reef/services/matching-engine/internal/app"
)

func finiteTestService(t *testing.T, w *FiniteStartupWitness) *app.Service {
	t.Helper()
	raw, _ := json.Marshal(w.Opening.Profile)
	profile, err := app.CalcifySourceProfileFromJSON(string(raw), "0")
	if err != nil {
		t.Fatal(err)
	}
	return app.NewService(app.WithTerminalOrderRetentionLimit(0), app.WithInstrumentQuoteCurrencies(map[string]string{w.Opening.InstrumentID: w.Opening.QuoteCurrency}), profile, app.WithCalcifyFiniteBindingDigest(w.BindingDigest))
}

func finiteTestHistory(t *testing.T) (*FiniteStartupWitness, *FiniteBrokerObservation, []CommandDelivery) {
	t.Helper()
	w := finiteGoldenWitness(t)
	w.Journal = nil
	w.Run.PossiblePublications = 12
	w.Run.RecordedAttempts = 12
	w.Run.CommandPayloadBytes = 0
	w.Run.UnresolvedPublications = 0
	w.Run.AdmissionState = "STOPPED"
	w.Cuts.CommandGroup.NextOffset = 0
	w.Cuts.Ranges[0].BeginningOffset = 0
	w.Cuts.Ranges[0].EndOffset = 12
	w.Cuts.Ranges[0].ReadCommittedEndOffset = 12
	actual := &FiniteBrokerObservation{ClusterID: w.Opening.BrokerClusterID, Topics: append([]FiniteTopic(nil), w.Opening.Topics...), Cuts: w.Cuts}
	actual.Cuts.Ranges = append([]FiniteRange(nil), w.Cuts.Ranges...)
	deliveries := make([]CommandDelivery, 0, 12)
	for i, old := range lifecycleFixtureDeliveries() {
		var payload map[string]string
		if err := json.Unmarshal(old.Data(), &payload); err != nil {
			t.Fatal(err)
		}
		role := "BUY"
		if payload["participantId"] == "seller" {
			role = "SELL"
		}
		for _, party := range w.Opening.Parties {
			if party.Side == role {
				payload["participantId"] = party.ParticipantID
				payload["accountId"] = party.AccountID
			}
		}
		payload["runId"], payload["venueSessionId"], payload["instrumentId"] = w.Opening.RunID, w.Opening.VenueSessionID, w.Opening.InstrumentID
		raw, _ := json.Marshal(payload)
		typeName := commandTypeFromSubject(old.Subject())
		subject := w.Opening.CommandSubjectPrefix + ".p00." + w.Opening.VenueSessionID + "." + w.Opening.InstrumentID + "." + typeName
		message := &sarama.ConsumerMessage{Topic: w.Opening.Topics[0].Name, Partition: 0, Offset: int64(i), Key: []byte("fixture-key"), Value: raw, Headers: []*sarama.RecordHeader{{Key: []byte(kafkaStreamSubjectHeader), Value: []byte(subject)}}}
		offset, sequence := FiniteDecimal(i), FiniteDecimal(i+1)
		route := map[string]string{"SubmitOrder": "/api/v1/orders/submit", "ModifyOrder": "/api/v1/orders/modify", "CancelOrder": "/api/v1/orders/cancel"}[typeName]
		w.Journal = append(w.Journal, FiniteJournalEntry{PublicationOrdinal: FiniteDecimal(i + 1), CommandID: payload["commandId"], CommandType: typeName, PayloadHash: finiteHash(raw), KeyHash: finiteHash(message.Key), SubjectHash: finiteHash([]byte(subject)), RouteHash: finiteHash([]byte(route)), OwnerEpoch: w.Run.OwnerEpoch, State: "PUBLISHED", Partition: 0, Offset: &offset, StreamSequence: &sequence, PayloadBytes: FiniteDecimal(len(raw))})
		w.Run.CommandPayloadBytes += FiniteDecimal(len(raw))
		actual.Commands = append(actual.Commands, message)
		source := &KafkaCommandSource{config: KafkaConfig{CommandTopic: w.Opening.Topics[0].Name, CommandSubjectPrefix: w.Opening.CommandSubjectPrefix, PartitionCount: 1}}
		deliveries = append(deliveries, source.deliveryFromMessage(message))
	}
	if err := w.validate(); err != nil {
		t.Fatal(err)
	}
	return w, actual, deliveries
}

func TestFiniteObservationRequiresExactJournalCorrespondence(t *testing.T) {
	w, actual, _ := finiteTestHistory(t)
	if err := verifyFiniteObservation(w, actual); err != nil {
		t.Fatal(err)
	}
	missingW, missing, _ := finiteTestHistory(t)
	missingW.Cuts.Ranges[1].BeginningOffset = 1
	missing.Cuts.Ranges[1].BeginningOffset = 1
	if verifyFiniteObservation(missingW, missing) == nil {
		t.Fatal("declared/observed missing source history accepted")
	}
	wrongRoute, routeActual, _ := finiteTestHistory(t)
	wrongRoute.Journal[0].RouteHash = finiteHash([]byte("/api/v1/orders/cancel"))
	if verifyFiniteObservation(wrongRoute, routeActual) == nil {
		t.Fatal("conflicting canonical route accepted")
	}
	for name, mutate := range map[string]func(*FiniteBrokerObservation){
		"foreign cluster":          func(a *FiniteBrokerObservation) { a.ClusterID = "other" },
		"missing retained command": func(a *FiniteBrokerObservation) { a.Commands = a.Commands[1:] },
		"payload drift":            func(a *FiniteBrokerObservation) { a.Commands[0].Value = append(a.Commands[0].Value, ' ') },
		"key drift":                func(a *FiniteBrokerObservation) { a.Commands[0].Key = []byte("other") },
		"duplicate header": func(a *FiniteBrokerObservation) {
			a.Commands[0].Headers = append(a.Commands[0].Headers, a.Commands[0].Headers[0])
		},
		"UUID drift": func(a *FiniteBrokerObservation) { a.Topics[0].TopicID = a.Topics[1].TopicID },
		"cut drift":  func(a *FiniteBrokerObservation) { a.Cuts.CommandGroup.NextOffset++ },
	} {
		t.Run(name, func(t *testing.T) {
			w, a, _ := finiteTestHistory(t)
			mutate(a)
			if verifyFiniteObservation(w, a) == nil {
				t.Fatal("changed history accepted")
			}
		})
	}
}

type finiteTestLane struct {
	records     []CommandDelivery
	next        int64
	replayCalls int
	fetchCalls  int
}

func (s *finiteTestLane) Fetch(_ context.Context, _ int, _ time.Duration) ([]CommandDelivery, error) {
	s.fetchCalls++
	if s.next >= int64(len(s.records)) {
		return nil, nil
	}
	return []CommandDelivery{s.records[s.next]}, nil
}

func TestFiniteJournalUsesActualOffsetsRatherThanReservationOrdinal(t *testing.T) {
	w, actual, _ := finiteTestHistory(t)
	w.Journal[0].Offset, w.Journal[1].Offset = w.Journal[1].Offset, w.Journal[0].Offset
	w.Journal[0].StreamSequence, w.Journal[1].StreamSequence = w.Journal[1].StreamSequence, w.Journal[0].StreamSequence
	actual.Commands[0], actual.Commands[1] = actual.Commands[1], actual.Commands[0]
	actual.Commands[0].Offset = 0
	actual.Commands[1].Offset = 1
	if err := w.validate(); err != nil {
		t.Fatal(err)
	}
	if err := verifyFiniteObservation(w, actual); err != nil {
		t.Fatal("actual publication positions substituted with reservation ordinal", err)
	}
}

func TestFiniteRunStopsAfterOneAbortedPublication(t *testing.T) {
	w, _, records := finiteTestHistory(t)
	p, lane, pub := finiteTestProcessor(t, w, records, 0)
	// Existing rollback restores allocated books, not their map allocation.
	p.service.BeginBatch([]app.BookScope{{RunID: w.Opening.RunID, VenueSessionID: w.Opening.VenueSessionID, InstrumentID: w.Opening.InstrumentID}}).Commit()
	before := p.service.Snapshot().Checksum
	pub.err = errors.New("test-only aborted transaction")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := p.Run(ctx); err == nil {
		t.Fatal("aborted finite transaction continued worker")
	}
	if lane.fetchCalls != 1 || lane.next != 0 || len(pub.batches) != 0 || p.service.Snapshot().Checksum != before {
		t.Fatal("finite failure retried, advanced cut or retained rolled-back state")
	}
}

func TestFiniteListOffsetsUsesReadCommittedWithoutFetchPrivilege(t *testing.T) {
	mock := sarama.NewMockBroker(t, 0)
	defer mock.Close()
	mock.SetHandlerByMap(map[string]sarama.MockResponse{"OffsetRequest": sarama.NewMockOffsetResponse(t).SetOffset("finite-source", 0, sarama.OffsetNewest, 7)})
	broker := sarama.NewBroker(mock.Addr())
	config := sarama.NewConfig()
	config.Version = sarama.V2_8_0_0
	config.ApiVersionsRequest = false // This unit mock covers ListOffsets only.
	config.Net.ReadTimeout = time.Second
	if err := broker.Open(config); err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	end, err := finiteBrokerStableEnd(broker, "finite-source")
	if err != nil || end != 7 {
		t.Fatal("actual LSO unavailable", end, err)
	}
	history := mock.History()
	if len(history) != 1 {
		t.Fatal("unexpected broker requests")
	}
	request, ok := history[0].Request.(*sarama.OffsetRequest)
	if !ok || request.Version != 6 || request.IsolationLevel != sarama.ReadCommitted {
		t.Fatal("LSO observation used Fetch/uncommitted offset")
	}
}
func (s *finiteTestLane) ReplayCommitted(_ context.Context, batchSize int, apply func([]CommandDelivery) error) (int, error) {
	s.replayCalls++
	r := &fakeCommittedReplayer{deliveries: s.records[:s.next]}
	return r.ReplayCommitted(context.Background(), batchSize, apply)
}

type finiteTestPublisher struct {
	lane    *finiteTestLane
	batches []VenueEventBatch
	err     error
}

func (p *finiteTestPublisher) PublishEventBatch(context.Context, VenueEventBatch) error {
	return errors.New("nontransactional publication forbidden")
}
func (p *finiteTestPublisher) PublishEventBatchAndAck(_ context.Context, b VenueEventBatch, d []CommandDelivery) (int, error) {
	if p.err != nil {
		return 0, p.err
	}
	p.batches = append(p.batches, b)
	p.lane.next += int64(len(d))
	return len(d), nil
}
func finiteTestProcessor(t *testing.T, w *FiniteStartupWitness, records []CommandDelivery, cut int64) (*Processor, *finiteTestLane, *finiteTestPublisher) {
	t.Helper()
	lane := &finiteTestLane{records: records, next: cut}
	publisher := &finiteTestPublisher{lane: lane}
	proof := &finiteActivationProof{opening: w.Opening, digest: w.BindingDigest, ownerEpoch: w.Run.OwnerEpoch, assertOwned: func() error { return nil }}
	p := NewProcessor(finiteTestService(t, w), lane, publisher, ProcessorConfig{ShardID: w.Opening.MatcherShardID, BatchSize: 1, CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: w.BindingDigest, finiteProof: proof})
	return p, lane, publisher
}

func TestFiniteRecoveryReplaysPrefixDrainsSuffixAndRestartsDeterministically(t *testing.T) {
	w, _, records := finiteTestHistory(t)
	full, lane, pub := finiteTestProcessor(t, w, records, 0)
	if err := recoverFiniteLane(context.Background(), full, lane, 0, 12, func() int64 { return lane.next }); err != nil {
		t.Fatal(err)
	}
	if len(pub.batches) != 12 || lane.next != 12 {
		t.Fatal("full suffix not transactionally published")
	}
	expected := full.service.Snapshot()
	restarted, replay, replayPub := finiteTestProcessor(t, w, records, 12)
	restarted.now = func() time.Time { return time.Date(2040, 1, 1, 0, 0, 0, 0, time.UTC) }
	if err := recoverFiniteLane(context.Background(), restarted, replay, 12, 12, func() int64 { return replay.next }); err != nil {
		t.Fatal(err)
	}
	if len(replayPub.batches) != 0 || !reflect.DeepEqual(expected, restarted.service.Snapshot()) || restarted.Stats().Fetched != 0 || restarted.Stats().Published != 0 {
		t.Fatal("committed replay republished or changed deterministic state")
	}
	mixed, mixedLane, mixedPub := finiteTestProcessor(t, w, records, 6)
	if err := recoverFiniteLane(context.Background(), mixed, mixedLane, 6, 12, func() int64 { return mixedLane.next }); err != nil {
		t.Fatal(err)
	}
	if len(mixedPub.batches) != 6 || !reflect.DeepEqual(expected, mixed.service.Snapshot()) {
		t.Fatal("known suffix/replay restart diverged")
	}
	t.Logf("12 command deterministic restart checksum=%s committedReplayPublished=0 knownSuffixPublished=6", expected.Checksum)
}

func TestFiniteProofRefusesBeforeMutationOnOwnershipOrScopeFailure(t *testing.T) {
	for _, name := range []string{"ownership", "binding", "party", "timestamp", "header"} {
		t.Run(name, func(t *testing.T) {
			w, _, records := finiteTestHistory(t)
			p, lane, pub := finiteTestProcessor(t, w, records, 0)
			before := p.service.Snapshot().Checksum
			switch name {
			case "ownership":
				p.config.finiteProof.assertOwned = func() error { return errors.New("fenced") }
			case "binding":
				p.config.finiteProof.digest = lifecycleFixtureBinding
			case "party", "timestamp":
				var data map[string]string
				_ = json.Unmarshal(records[0].Data(), &data)
				if name == "party" {
					data["accountId"] = "foreign"
				} else {
					delete(data, "occurredAt")
				}
				raw, _ := json.Marshal(data)
				records[0].(*kafkaDelivery).data = raw
			case "header":
				records[0].(*kafkaDelivery).finiteMessage.Headers = nil
			}
			if _, err := p.ProcessOnce(context.Background()); err == nil {
				t.Fatal("invalid proof/scope processed")
			}
			if lane.next != 0 || len(pub.batches) != 0 || before != p.service.Snapshot().Checksum {
				t.Fatal("failed finite gate mutated state")
			}
		})
	}
}

func TestFiniteTypedScopePreservesRawEconomicallyRejectedAttempt(t *testing.T) {
	w, _, records := finiteTestHistory(t)
	var body map[string]any
	_ = json.Unmarshal(records[0].Data(), &body)
	commandID := body["commandId"].(string)
	body["quantityUnits"] = "-0001"
	raw, _ := json.Marshal(body)
	if err := validateFiniteCommandScope(w.Opening, raw, commandID, "SubmitOrder"); err != nil {
		t.Fatal("raw business rejection spelling rejected by provenance check", err)
	}
	body["quantityUnits"] = float64(1)
	raw, _ = json.Marshal(body)
	if validateFiniteCommandScope(w.Opening, raw, commandID, "SubmitOrder") == nil {
		t.Fatal("non-string typed units accepted")
	}
}

func TestFiniteTypedScopeMatchesCaptureTextAndSourceOrderPolicy(t *testing.T) {
	w, _, records := finiteTestHistory(t)
	for _, field := range []string{"traceId", "causationId", "correlationId", "actorId", "clientOrderId", "quantityUnits", "limitPrice", "occurredAt"} {
		t.Run(field, func(t *testing.T) {
			var body map[string]any
			_ = json.Unmarshal(records[0].Data(), &body)
			body[field] = strings.Repeat("é", 65) // 130 UTF-8 bytes, 65 characters.
			raw, _ := json.Marshal(body)
			if validateFiniteCommandScope(w.Opening, raw, body["commandId"].(string), "SubmitOrder") == nil {
				t.Fatal("oversized capture text accepted")
			}
		})
	}
	for _, id := range []string{"p0-0", "p0-01", "p0-9", "outside-profile"} {
		var body map[string]any
		_ = json.Unmarshal(records[0].Data(), &body)
		body["orderId"] = id
		raw, _ := json.Marshal(body)
		if validateFiniteCommandScope(w.Opening, raw, body["commandId"].(string), "SubmitOrder") == nil {
			t.Fatalf("source order policy accepted %q", id)
		}
	}
	var body map[string]any
	_ = json.Unmarshal(records[0].Data(), &body)
	for _, field := range []string{"traceId", "causationId", "correlationId", "actorId", "clientOrderId"} {
		delete(body, field)
	}
	raw, _ := json.Marshal(body)
	if err := validateFiniteCommandScope(w.Opening, raw, body["commandId"].(string), "SubmitOrder"); err != nil {
		t.Fatal("absent optional metadata not defaulted safely", err)
	}
	body["actorId"] = nil
	raw, _ = json.Marshal(body)
	if validateFiniteCommandScope(w.Opening, raw, body["commandId"].(string), "SubmitOrder") == nil {
		t.Fatal("null optional metadata accepted")
	}
	body["actorId"] = ""
	body["quantityUnits"] = "   "
	raw, _ = json.Marshal(body)
	if validateFiniteCommandScope(w.Opening, raw, body["commandId"].(string), "SubmitOrder") == nil {
		t.Fatal("blank required economics accepted")
	}
}

func TestFiniteEffectiveConfigRequiresStoppedResolvedCanonicalRouting(t *testing.T) {
	w, _, _ := finiteTestHistory(t)
	o := w.Opening
	config := RuntimeConfig{Enabled: true, CalcifyLifecycleEnabled: true, LogProvider: "redpanda", BatchSize: 1, PartitionCount: 1, Partitions: []int{0}, ShardID: o.MatcherShardID, OwnershipGroup: o.OwnershipGroup, DurablePrefix: strings.TrimSuffix(o.CommandConsumerGroup, "-p00"), CommandSubjectPrefix: o.CommandSubjectPrefix, EventSubjectPrefix: o.SourceSubjectPrefix, CommandStream: o.Topics[0].Name, EventStream: o.Topics[1].Name, OwnershipTopic: o.Topics[3].Name}
	if err := validateFiniteEffectiveConfig(w, config); err != nil {
		t.Fatal(err)
	}
	w.Run.AdmissionState = "ACTIVE"
	if validateFiniteEffectiveConfig(w, config) == nil {
		t.Fatal("active startup allowed")
	}
	w.Run.AdmissionState = "STOPPED"
	w.Run.UnresolvedPublications = 1
	if validateFiniteEffectiveConfig(w, config) == nil {
		t.Fatal("unknown publication activated")
	}
	w.Run.UnresolvedPublications = 0
	config.BatchSize = 2
	if validateFiniteEffectiveConfig(w, config) == nil {
		t.Fatal("batch bound bypassed")
	}
	config.BatchSize = 1
	w.Opening.VenueSessionID = "ambiguous.session"
	if validateFiniteEffectiveConfig(w, config) == nil {
		t.Fatal("unparseable canonical route activated")
	}
}

func TestFiniteReadinessAuthenticatesAndChecksNonceBeforeQuery(t *testing.T) {
	w, _, _ := finiteTestHistory(t)
	f := &finiteReadiness{ctx: context.Background(), token: "test-only-token"}
	for _, c := range []struct {
		method, query, auth string
		status              int
	}{
		{"GET", "?nonce=valid", "", 401}, {"POST", "?nonce=valid", "Bearer test-only-token", 405},
		{"GET", "?nonce=a&nonce=b", "Bearer test-only-token", 400}, {"GET", "?nonce=a&extra=b", "Bearer test-only-token", 400}, {"GET", "?nonce=", "Bearer test-only-token", 400},
		{"GET", "?nonce=valid&bad=%GG", "Bearer test-only-token", 400},
	} {
		req := httptest.NewRequest(c.method, "/internal/calcify/finite-readiness"+c.query, nil)
		req.Header.Set("Authorization", c.auth)
		response := httptest.NewRecorder()
		f.serveHTTP(response, req)
		if response.Code != c.status {
			t.Fatalf("early gate=%d want%d", response.Code, c.status)
		}
	}
	p, _, _ := finiteTestProcessor(t, w, nil, 0)
	p.config.finiteProof.assertOwned = func() error { return errors.New("fenced") }
	f.service = p.service
	f.proof = p.config.finiteProof
	req := httptest.NewRequest("GET", "/internal/calcify/finite-readiness?nonce=valid", nil)
	req.Header.Set("Authorization", "Bearer test-only-token")
	response := httptest.NewRecorder()
	f.serveHTTP(response, req)
	if response.Code != 503 {
		t.Fatal("lost owner served readiness")
	}
}

func TestFiniteWitnessUsesFreshAuthenticatedChallengeAndBoundedResponse(t *testing.T) {
	w := finiteGoldenWitness(t)
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, req *http.Request) {
		calls++
		if req.Header.Get("Authorization") != "Bearer test-only-token" || req.URL.Path != "/internal/calcify/finite-bindings/"+w.BindingDigest {
			t.Error("wrong endpoint/auth")
		}
		w.Nonce = req.URL.Query().Get("nonce")
		response.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(response).Encode(w)
	}))
	defer server.Close()
	first, err := fetchFiniteWitness(context.Background(), server.URL, "test-only-token", w.BindingDigest)
	if err != nil {
		t.Fatal(err)
	}
	second, err := fetchFiniteWitness(context.Background(), server.URL, "test-only-token", w.BindingDigest)
	if err != nil || first.Nonce == second.Nonce || calls != 2 {
		t.Fatal("challenge reused", err)
	}
	oversized := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write(bytes.Repeat([]byte(" "), finiteStartupLimit+1))
	}))
	defer oversized.Close()
	if _, err := fetchFiniteWitness(context.Background(), oversized.URL, "test-only-token", w.BindingDigest); err == nil {
		t.Fatal("oversized authenticated witness accepted")
	}
}

func TestFiniteSecretFilesAreBoundedPrivateAndNeverFallBack(t *testing.T) {
	t.Setenv("MATCHING_ENGINE_CALCIFY_SERVICE_TOKEN", "")
	t.Setenv("MATCHING_ENGINE_CALCIFY_KAFKA_PASSWORD", "")
	path := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(path, []byte("test-only-secret\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if value, err := readFiniteSecret(path); err != nil || value != "test-only-secret" {
		t.Fatal("private file failed", err)
	}
	for _, c := range []struct {
		raw  string
		mode os.FileMode
	}{{"test-only-secret", 0644}, {"bad\nmultiple", 0600}, {string(bytes.Repeat([]byte("x"), 4097)), 0600}} {
		if err := os.Chmod(path, c.mode); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(c.raw), c.mode); err != nil {
			t.Fatal(err)
		}
		if _, err := readFiniteSecret(path); err == nil {
			t.Fatal("unsafe file accepted")
		}
	}
	_ = os.Chmod(path, 0600)
	_ = os.WriteFile(path, []byte("test-only-secret"), 0600)
	link := filepath.Join(t.TempDir(), "symlink")
	_ = os.Symlink(path, link)
	if _, err := readFiniteSecret(link); err == nil {
		t.Fatal("symlink accepted")
	}
	config := &FiniteRuntimeConfig{ServiceTokenFile: path, KafkaPasswordFile: path, KafkaUsername: "finite-matcher"}
	_, auth, err := config.credentials()
	if err != nil {
		t.Fatal(err)
	}
	c := kafkaConfiguredClient(KafkaConfig{Finite: auth}, "test-client")
	if !c.Net.SASL.Enable || c.Net.SASL.Mechanism != sarama.SASLTypePlaintext || c.Metadata.AllowAutoTopicCreation || c.Consumer.IsolationLevel != sarama.ReadCommitted {
		t.Fatal("finite authentication/read isolation drift")
	}
	t.Setenv("MATCHING_ENGINE_CALCIFY_KAFKA_PASSWORD", "forbidden-env")
	if _, _, err := config.credentials(); err == nil {
		t.Fatal("ambiguous secret settings accepted")
	}
	if err := EnsureKafkaTopic(KafkaConfig{Finite: auth}, "any"); err == nil {
		t.Fatal("finite topic mutation reachable")
	}
	if _, err := NewKafkaEventBatchPublisher(KafkaConfig{Finite: auth}); err == nil {
		t.Fatal("finite nontransactional publisher reachable")
	}
}
