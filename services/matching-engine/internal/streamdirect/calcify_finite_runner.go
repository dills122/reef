package streamdirect

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"reflect"
	"sync"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
)

// Only the startup verifier constructs this proof after real broker/journal
// correspondence and ownership acquisition. A digest/config flag is not proof.
type finiteActivationProof struct {
	opening     FiniteOpening
	digest      string
	ownerEpoch  FiniteDecimal
	assertOwned func() error
}

func (p *finiteActivationProof) check(service *app.Service) error {
	if p == nil || p.assertOwned == nil || service.CalcifyFiniteBindingDigest() != p.digest || service.CalcifySourceProfileHash() != p.opening.ProfileHash {
		return fmt.Errorf("finite matching identity/ownership proof unavailable")
	}
	return p.assertOwned()
}

func (p *finiteActivationProof) validateDelivery(delivery CommandDelivery) error {
	if delivery == nil || delivery.StreamSequence() == 0 || delivery.StreamSequence() > uint64(p.opening.Budget.Publications) || len(delivery.Data()) > int(p.opening.Budget.CommandBytes) {
		return fmt.Errorf("finite delivery exceeds registered scope/budget")
	}
	typeName := commandTypeFromSubject(delivery.Subject())
	if typeName != "SubmitOrder" && typeName != "ModifyOrder" && typeName != "CancelOrder" {
		return fmt.Errorf("finite delivery command type unavailable")
	}
	expected := p.opening.CommandSubjectPrefix + ".p00." + p.opening.VenueSessionID + "." + p.opening.InstrumentID + "." + typeName
	if delivery.Subject() != expected {
		return fmt.Errorf("finite delivery routing differs from opening")
	}
	actual, ok := delivery.(*kafkaDelivery)
	if !ok || actual.finiteMessage == nil || actual.finiteMessage.Partition != 0 || actual.finiteMessage.Topic != p.opening.Topics[0].Name || actual.finiteMessage.Key == nil || len(actual.finiteMessage.Key) > 1024 {
		return fmt.Errorf("finite delivery lacks canonical broker metadata")
	}
	headerCount := 0
	for _, header := range actual.finiteMessage.Headers {
		if string(header.Key) == kafkaStreamSubjectHeader {
			headerCount++
			if string(header.Value) != expected {
				return fmt.Errorf("finite delivery subject header differs")
			}
		}
	}
	if headerCount != 1 {
		return fmt.Errorf("finite delivery requires one routing header")
	}
	var identity struct {
		CommandID string `json:"commandId"`
	}
	if err := json.Unmarshal(delivery.Data(), &identity); err != nil || !finiteText(identity.CommandID, 128) {
		return fmt.Errorf("finite delivery command identity unavailable")
	}
	return validateFiniteCommandScope(p.opening, delivery.Data(), identity.CommandID, typeName)
}

type finiteReadiness struct {
	ctx     context.Context
	service *app.Service
	config  RuntimeConfig
	kafka   KafkaConfig
	token   string
	proof   *finiteActivationProof
	source  *KafkaCommandSource
	mu      sync.Mutex // One bounded cold-path observation at a time.
}

type FiniteEngineReadiness struct {
	Schema                       string        `json:"schema"`
	Nonce                        string        `json:"nonce"`
	BindingDigest                string        `json:"bindingDigest"`
	OwnerEpoch                   FiniteDecimal `json:"ownerEpoch"`
	ActivationEpoch              FiniteDecimal `json:"activationEpoch"`
	CommandGroup                 string        `json:"commandGroup"`
	CommandNextOffset            FiniteDecimal `json:"commandNextOffset"`
	SourceReadCommittedEndOffset FiniteDecimal `json:"sourceReadCommittedEndOffset"`
	MatcherShardID               string        `json:"matcherShardId"`
	TransactionalID              string        `json:"transactionalId"`
	SnapshotChecksum             string        `json:"snapshotChecksum"`
}

// FiniteReadinessHandler exposes only verified finite runners. Ordinary runners
// return nil; callers must keep mutation routes fenced independently.
func (r *Runner) FiniteReadinessHandler() http.Handler {
	if r == nil || r.finite == nil {
		return nil
	}
	return http.HandlerFunc(r.finite.serveHTTP)
}

func (f *finiteReadiness) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+f.token)) != 1 {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	query, err := url.ParseQuery(r.URL.RawQuery)
	values := query["nonce"]
	if err != nil || len(values) != 1 || !finiteNonce(values[0]) || len(query) != 1 {
		w.WriteHeader(http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	// Authorization/nonce checks precede any DB/broker query. Readiness is cold
	// operator traffic, never a per-command synchronous query.
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.ctx.Err() != nil || f.proof.check(f.service) != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	witness, err := fetchFiniteWitness(ctx, f.config.Finite.BindingEndpoint, f.token, f.proof.digest)
	if err != nil || validateFiniteEffectiveConfig(witness, f.config) != nil || witness.Run.OwnerEpoch != f.proof.ownerEpoch {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	actual, err := ObserveFiniteBroker(ctx, witness.Opening, f.kafka)
	if err != nil || verifyFiniteObservation(witness, actual) != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	if actual.Cuts.CommandGroup.NextOffset != actual.Cuts.Ranges[0].ReadCommittedEndOffset || f.source.nextCommandOffset() != int64(actual.Cuts.CommandGroup.NextOffset) || f.proof.check(f.service) != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	snapshotChecksum := f.service.Snapshot().Checksum
	final, err := fetchFiniteWitness(ctx, f.config.Finite.BindingEndpoint, f.token, f.proof.digest)
	if err != nil || final.Run != witness.Run || !reflect.DeepEqual(final.Journal, witness.Journal) || final.Cuts.CommandGroup != actual.Cuts.CommandGroup || final.Cuts.Ranges[0] != actual.Cuts.Ranges[0] || final.Cuts.Ranges[1] != actual.Cuts.Ranges[1] || f.proof.check(f.service) != nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	receipt := FiniteEngineReadiness{
		Schema: "calcify-finite-engine-ready-v1", Nonce: values[0], BindingDigest: f.proof.digest,
		OwnerEpoch: witness.Run.OwnerEpoch, ActivationEpoch: witness.Run.ActivationEpoch,
		CommandGroup: witness.Opening.CommandConsumerGroup, CommandNextOffset: actual.Cuts.CommandGroup.NextOffset,
		SourceReadCommittedEndOffset: actual.Cuts.Ranges[1].ReadCommittedEndOffset,
		MatcherShardID:               witness.Opening.MatcherShardID, TransactionalID: witness.Opening.CommandConsumerGroup + "-venue-events-v1",
		SnapshotChecksum: snapshotChecksum,
	}
	raw, err := json.Marshal(receipt)
	if err != nil || len(raw) > 8192 {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

func (s *KafkaCommandSource) nextCommandOffset() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.currentNextOffsetLocked()
}

func startFiniteRunner(parent context.Context, service *app.Service, config RuntimeConfig) (_ *Runner, err error) {
	if !config.Enabled || config.Finite == nil {
		return nil, fmt.Errorf("Calcify lifecycle live activation requires durable binding and restore configuration")
	}
	if service.CalcifyFiniteBindingDigest() != config.CalcifyFiniteBindingDigest {
		return nil, fmt.Errorf("finite snapshot binding identity differs from startup")
	}
	token, auth, err := config.Finite.credentials()
	if err != nil {
		return nil, err
	}
	timeout := config.RecoveryTimeout
	if timeout <= 0 {
		timeout = 60 * time.Second
	}
	ctx, stop := context.WithTimeout(parent, timeout)
	defer stop()
	witness, err := fetchFiniteWitness(ctx, config.Finite.BindingEndpoint, token, config.CalcifyFiniteBindingDigest)
	if err != nil {
		return nil, err
	}
	if err = validateFiniteEffectiveConfig(witness, config); err != nil {
		return nil, err
	}
	if service.CalcifySourceProfileHash() != witness.Opening.ProfileHash {
		return nil, fmt.Errorf("finite matching P0 profile differs from binding")
	}
	kafka := finiteKafkaConfig(config, auth)
	actual, err := ObserveFiniteBroker(ctx, witness.Opening, kafka)
	if err != nil {
		return nil, err
	}
	if err = verifyFiniteObservation(witness, actual); err != nil {
		return nil, err
	}
	// No topic ensure/create/expand path is reachable in finite startup.
	liveCtx, cancel := context.WithCancel(parent)
	closers := []func() error{}
	runner := &Runner{cancel: cancel, close: func() {
		for _, closeFn := range closers {
			if closeErr := closeFn(); closeErr != nil {
				log.Printf("finite runner close error: %v", closeErr)
			}
		}
	}}
	defer func() {
		if err != nil {
			cancel()
			runner.close()
		}
	}()
	ownership, err := acquireKafkaOwnershipLease(liveCtx, ctx, kafka, config.ShardID, config.Partitions)
	if err != nil {
		return nil, fmt.Errorf("finite ownership unavailable: %w", err)
	}
	// The lease must outlive the startup deadline. Its independent cancellation
	// is handled by Close; use the parent context for the ownership goroutine.
	closers = append(closers, ownership.Close)
	source, err := NewKafkaCommandSource(kafka, 0, witness.Opening.CommandConsumerGroup)
	if err != nil {
		return nil, err
	}
	source.ownership = ownership
	closers = append(closers, source.Close)
	publisher, err := NewKafkaTransactionalEventBatchPublisher(kafka, source)
	if err != nil {
		return nil, err
	}
	closers = append([]func() error{publisher.Close}, closers...)
	// Producer initialization fences/aborts an old source writer. SQL epoch only
	// fences future API authorizations, so re-observe complete resolved command
	// history after acquiring both controls before any matching state mutation.
	fresh, err := fetchFiniteWitness(ctx, config.Finite.BindingEndpoint, token, config.CalcifyFiniteBindingDigest)
	if err != nil {
		return nil, err
	}
	if err = validateFiniteEffectiveConfig(fresh, config); err != nil {
		return nil, err
	}
	if fresh.Run.OwnerEpoch != witness.Run.OwnerEpoch || fresh.Run.ActivationEpoch != witness.Run.ActivationEpoch {
		return nil, fmt.Errorf("finite startup epoch changed while fencing")
	}
	actual, err = ObserveFiniteBroker(ctx, fresh.Opening, kafka)
	if err != nil {
		return nil, err
	}
	if err = verifyFiniteObservation(fresh, actual); err != nil {
		return nil, err
	}
	if source.nextCommandOffset() != int64(actual.Cuts.CommandGroup.NextOffset) {
		return nil, fmt.Errorf("finite source retained group cut changed")
	}
	proof := &finiteActivationProof{opening: fresh.Opening, digest: fresh.BindingDigest, ownerEpoch: fresh.Run.OwnerEpoch, assertOwned: func() error { return ownership.AssertOwned(0) }}
	processor := NewProcessor(service, source, publisher, ProcessorConfig{
		ShardID: config.ShardID, Partition: 0, BatchSize: 1, FetchTimeout: config.FetchTimeout, PollInterval: config.PollInterval,
		CommandStream: config.CommandStream, EventStreamName: config.EventStream, Source: "redpanda",
		CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: config.CalcifyFiniteBindingDigest, finiteProof: proof,
	})
	if err = recoverFiniteLane(ctx, processor, source, int64(actual.Cuts.CommandGroup.NextOffset), int64(actual.Cuts.Ranges[0].ReadCommittedEndOffset), source.nextCommandOffset); err != nil {
		return nil, err
	}
	if err = proof.check(service); err != nil {
		return nil, err
	}
	runner.processors = []*Processor{processor}
	runner.finite = &finiteReadiness{ctx: liveCtx, service: service, config: config, kafka: kafka, token: token, proof: proof, source: source}
	runner.wg.Add(1)
	go func() {
		defer runner.wg.Done()
		if runErr := processor.Run(liveCtx); runErr != nil && liveCtx.Err() == nil {
			log.Printf("finite matching lane stopped: %v", runErr)
			cancel()
		}
	}()
	return runner, nil
}

// Committed prefix is replayed without publication. Only a known PUBLISHED
// suffix is transactionally drained; deadline/error never yields readiness.
func recoverFiniteLane(ctx context.Context, processor *Processor, replayer CommittedCommandReplayer, committed, end int64, next func() int64) error {
	if processor == nil || processor.config.finiteProof == nil || replayer == nil || next == nil {
		return fmt.Errorf("finite recovery proof unavailable")
	}
	if committed < 0 || end < committed || end > int64(processor.config.finiteProof.opening.Budget.Publications) || next() != committed {
		return fmt.Errorf("finite recovery cut unavailable")
	}
	replayed, err := processor.RestoreCommitted(ctx, replayer)
	if err != nil {
		return fmt.Errorf("finite committed replay failed: %w", err)
	}
	if int64(replayed) != committed {
		return fmt.Errorf("finite replay did not restore exact committed prefix")
	}
	for next() < end {
		if err := ctx.Err(); err != nil {
			return fmt.Errorf("finite suffix drain deadline: %w", err)
		}
		before := next()
		count, err := processor.ProcessOnce(ctx)
		if err != nil {
			return fmt.Errorf("finite suffix transaction failed: %w", err)
		}
		if count == 0 {
			continue
		}
		if next() != before+1 || next() > end {
			return fmt.Errorf("finite suffix transaction advanced unexpected cut")
		}
	}
	return nil
}
