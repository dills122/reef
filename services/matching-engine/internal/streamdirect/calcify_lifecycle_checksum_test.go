package streamdirect

import (
	"encoding/json"
	"testing"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestCalcifyLifecycleChecksumCoversTypedCommand(t *testing.T) {
	batches, _ := lifecycleFixtureBatches(t, ProcessorConfig{CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	for name, mutate := range map[string]func(*domain.OrderLifecycleCommandV1){
		"schema":    func(f *domain.OrderLifecycleCommandV1) { f.Schema = "different" },
		"profile":   func(f *domain.OrderLifecycleCommandV1) { f.SourceProfileHash = lifecycleFixtureBinding },
		"binding":   func(f *domain.OrderLifecycleCommandV1) { f.FiniteBindingDigest = f.SourceProfileHash },
		"routing":   func(f *domain.OrderLifecycleCommandV1) { f.InstrumentID = "foreign" },
		"ownership": func(f *domain.OrderLifecycleCommandV1) { f.AccountID = "foreign" },
		"metadata":  func(f *domain.OrderLifecycleCommandV1) { f.ActorID = "foreign" },
		"time":      func(f *domain.OrderLifecycleCommandV1) { f.OccurredAt = "2026-10-10T03:00:01Z" },
		"quantity":  func(f *domain.OrderLifecycleCommandV1) { f.Submit.QuantityUnits = "4" },
	} {
		t.Run(name, func(t *testing.T) {
			raw, err := json.Marshal(batches[0])
			if err != nil {
				t.Fatal(err)
			}
			var changed VenueEventBatch
			if err := json.Unmarshal(raw, &changed); err != nil {
				t.Fatal(err)
			}
			mutate(changed.Outcomes[0].LifecycleCommand)
			checksum, err := venueEventBatchChecksum(changed)
			if err != nil {
				t.Fatal(err)
			}
			if checksum == batches[0].PayloadChecksum {
				t.Fatal("typed mutation escaped semantic checksum")
			}
		})
	}
	for _, i := range []int{3, 4} {
		before := batches[i].PayloadChecksum
		if i == 3 {
			batches[i].Outcomes[0].LifecycleCommand.Modify.LimitPrice = "98000000000"
		} else {
			batches[i].Outcomes[0].LifecycleCommand.Cancel.Reason = "different"
		}
		checksum, err := venueEventBatchChecksum(batches[i])
		if err != nil {
			t.Fatal(err)
		}
		if checksum == before {
			t.Fatal("zero-trade lifecycle economics escaped checksum")
		}
	}
}

func TestCalcifyLifecycleAbsentFieldRetainsLegacyChecksumAndBytes(t *testing.T) {
	legacy, _ := lifecycleFixtureBatches(t, ProcessorConfig{})
	typed, _ := lifecycleFixtureBatches(t, ProcessorConfig{CalcifyLifecycleEnabled: true, CalcifyFiniteBindingDigest: lifecycleFixtureBinding})
	for i := range typed {
		if typed[i].PayloadChecksum == legacy[i].PayloadChecksum {
			t.Fatal("new fact did not change semantic checksum")
		}
		typed[i].Outcomes[0].LifecycleCommand = nil
		checksum, err := venueEventBatchChecksum(typed[i])
		if err != nil {
			t.Fatal(err)
		}
		typed[i].PayloadChecksum = checksum
		typed[i].TimingChecksum = venueEventBatchTimingChecksum(typed[i])
		if checksum != legacy[i].PayloadChecksum {
			t.Fatal("absent fact changed legacy semantic checksum")
		}
	}
	if string(lifecycleJSONL(t, typed)) != string(lifecycleJSONL(t, legacy)) {
		t.Fatal("absent fact changed complete legacy source bytes")
	}
}
