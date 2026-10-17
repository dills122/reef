package main

import (
	"sync/atomic"
	"time"

	reporting "github.com/dills122/reef/services/simulator/internal/report"
)

type summary struct {
	SessionID              string                    `json:"sessionId"`
	StartedAt              time.Time                 `json:"startedAt"`
	FinishedAt             time.Time                 `json:"finishedAt"`
	DurationSeconds        float64                   `json:"durationSeconds"`
	Config                 Config                    `json:"config"`
	ThroughputRPS          float64                   `json:"throughputRps"`
	AcceptedBusinessOpsRPS float64                   `json:"acceptedBusinessOpsRps"`
	Throughput             throughputSummary         `json:"throughput"`
	TotalRequests          int64                     `json:"totalRequests"`
	TotalSuccess           int64                     `json:"totalSuccess"`
	TotalFailures          int64                     `json:"totalFailures"`
	ByAction               map[Action]actionSummary  `json:"byAction"`
	ByProfile              map[string]profileSummary `json:"byProfile"`
	ByActor                map[string]profileSummary `json:"byActor"`
	ByPersona              map[string]profileSummary `json:"byPersona"`
	ByStrategy             map[string]profileSummary `json:"byStrategy"`
	StatusCodes            map[int]int64             `json:"statusCodes"`
	TopErrors              []errorSummary            `json:"topErrors"`
	RejectReasons          []errorSummary            `json:"rejectReasons"`
	RejectTaxonomy         []rejectTaxonomySummary   `json:"rejectTaxonomy"`
	Quality                qualitySummary            `json:"quality"`
	LatencyMs              latencySummary            `json:"latencyMs"`
	TraceChecks            traceChecks               `json:"traceChecks"`
	LoadSchedule           loadScheduleSummary       `json:"loadSchedule"`
}

type loadScheduleSummary struct {
	Mode                  string  `json:"mode"`
	TargetRatePerSecond   int     `json:"targetRatePerSecond"`
	TargetRequests        int64   `json:"targetRequests"`
	Scheduled             int64   `json:"scheduled"`
	Enqueued              int64   `json:"enqueued"`
	Dropped               int64   `json:"dropped"`
	Completed             int64   `json:"completed"`
	ScheduleDeficit       int64   `json:"scheduleDeficit"`
	CompletionDeficit     int64   `json:"completionDeficit"`
	EnqueuedPerSecond     float64 `json:"enqueuedPerSecond"`
	CompletedPerSecond    float64 `json:"completedPerSecond"`
	CompletionToTargetPct float64 `json:"completionToTargetPct"`
}

type throughputSummary = reporting.ThroughputSummary

type loadScheduleCounters struct {
	scheduled int64
	enqueued  int64
	dropped   int64
}

type profileSummary struct {
	Requests int64                    `json:"requests"`
	Success  int64                    `json:"success"`
	Failures int64                    `json:"failures"`
	ByAction map[Action]actionSummary `json:"byAction"`
	Latency  latencySummary           `json:"latencyMs"`
}

type actionSummary struct {
	Requests int64          `json:"requests"`
	Success  int64          `json:"success"`
	Failures int64          `json:"failures"`
	Latency  latencySummary `json:"latencyMs"`
}

type latencySummary = reporting.LatencySummary

type errorSummary = reporting.ErrorSummary

type rejectTaxonomySummary = reporting.RejectTaxonomySummary

type qualitySummary = reporting.QualitySummary

func (c *loadScheduleCounters) summary(cfg Config, completed int, durationSeconds float64) loadScheduleSummary {
	targetRequests := int64(cfg.Duration.Seconds() * float64(cfg.RatePerSecond))
	if cfg.RatePerSecond <= 0 {
		targetRequests = int64(completed)
	}
	scheduled := int64(0)
	enqueued := int64(completed)
	dropped := int64(0)
	if c != nil {
		scheduled = atomic.LoadInt64(&c.scheduled)
		enqueued = atomic.LoadInt64(&c.enqueued)
		dropped = atomic.LoadInt64(&c.dropped)
	}
	if cfg.RatePerSecond <= 0 {
		scheduled = int64(completed)
		enqueued = int64(completed)
	}
	completedCount := int64(completed)
	scheduleDeficit := targetRequests - scheduled
	if scheduleDeficit < 0 {
		scheduleDeficit = 0
	}
	completionDeficit := targetRequests - completedCount
	if completionDeficit < 0 {
		completionDeficit = 0
	}
	out := loadScheduleSummary{
		Mode:                cfg.RateSchedule,
		TargetRatePerSecond: cfg.RatePerSecond,
		TargetRequests:      targetRequests,
		Scheduled:           scheduled,
		Enqueued:            enqueued,
		Dropped:             dropped,
		Completed:           completedCount,
		ScheduleDeficit:     scheduleDeficit,
		CompletionDeficit:   completionDeficit,
	}
	if durationSeconds > 0 {
		out.EnqueuedPerSecond = float64(enqueued) / durationSeconds
		out.CompletedPerSecond = float64(completedCount) / durationSeconds
	}
	if targetRequests > 0 {
		out.CompletionToTargetPct = (float64(completedCount) / float64(targetRequests)) * 100
	}
	return out
}

type summaryAccumulator struct {
	report                  summary
	errorCounts             map[string]int64
	rejectReasons           map[string]int64
	rejectCodes             map[string]int64
	totalRejects            int64
	allLatencies            []float64
	actionLatencies         map[Action][]float64
	profileLatencies        map[string][]float64
	actorLatencies          map[string][]float64
	personaLatencies        map[string][]float64
	strategyLatencies       map[string][]float64
	profileActionLatencies  map[string]map[Action][]float64
	actorActionLatencies    map[string]map[Action][]float64
	personaActionLatencies  map[string]map[Action][]float64
	strategyActionLatencies map[string]map[Action][]float64
}

func newSummaryAccumulator(initialCapacity int) *summaryAccumulator {
	if initialCapacity < 0 {
		initialCapacity = 0
	}
	return &summaryAccumulator{
		report: summary{
			ByAction: map[Action]actionSummary{
				ActionSubmit: {},
				ActionModify: {},
				ActionCancel: {},
			},
			ByProfile:   map[string]profileSummary{},
			ByActor:     map[string]profileSummary{},
			ByPersona:   map[string]profileSummary{},
			ByStrategy:  map[string]profileSummary{},
			StatusCodes: make(map[int]int64),
		},
		errorCounts:   make(map[string]int64),
		rejectReasons: make(map[string]int64),
		rejectCodes:   make(map[string]int64),
		allLatencies:  make([]float64, 0, initialCapacity),
		actionLatencies: map[Action][]float64{
			ActionSubmit: {},
			ActionModify: {},
			ActionCancel: {},
		},
		profileLatencies:        map[string][]float64{},
		actorLatencies:          map[string][]float64{},
		personaLatencies:        map[string][]float64{},
		strategyLatencies:       map[string][]float64{},
		profileActionLatencies:  map[string]map[Action][]float64{},
		actorActionLatencies:    map[string]map[Action][]float64{},
		personaActionLatencies:  map[string]map[Action][]float64{},
		strategyActionLatencies: map[string]map[Action][]float64{},
	}
}

func (a *summaryAccumulator) add(r requestResult) {
	latencyMs := r.Latency.Seconds() * 1000
	a.report.TotalRequests++
	a.report.StatusCodes[r.StatusCode]++
	a.allLatencies = append(a.allLatencies, latencyMs)
	a.actionLatencies[r.Action] = append(a.actionLatencies[r.Action], latencyMs)
	a.profileLatencies[r.Profile] = append(a.profileLatencies[r.Profile], latencyMs)
	appendActionLatency(a.profileActionLatencies, r.Profile, r.Action, latencyMs)
	if r.ActorID != "" {
		a.actorLatencies[r.ActorID] = append(a.actorLatencies[r.ActorID], latencyMs)
		appendActionLatency(a.actorActionLatencies, r.ActorID, r.Action, latencyMs)
	}
	if r.Persona != "" {
		a.personaLatencies[r.Persona] = append(a.personaLatencies[r.Persona], latencyMs)
		appendActionLatency(a.personaActionLatencies, r.Persona, r.Action, latencyMs)
	}
	if r.StrategyID != "" {
		a.strategyLatencies[r.StrategyID] = append(a.strategyLatencies[r.StrategyID], latencyMs)
		appendActionLatency(a.strategyActionLatencies, r.StrategyID, r.Action, latencyMs)
	}

	current := a.report.ByAction[r.Action]
	current.Requests++
	if r.Success {
		a.report.TotalSuccess++
		current.Success++
	} else {
		a.report.TotalFailures++
		current.Failures++
		if r.ErrorText != "" {
			a.errorCounts[r.ErrorText]++
		}
		if r.RejectCode != "" {
			a.rejectCodes[r.RejectCode]++
			a.totalRejects++
			key := r.RejectCode
			if r.RejectReason != "" {
				key = key + ": " + r.RejectReason
			}
			a.rejectReasons[key]++
		}
	}
	a.report.ByAction[r.Action] = current

	pCurrent, ok := a.report.ByProfile[r.Profile]
	if !ok {
		pCurrent = profileSummary{
			ByAction: map[Action]actionSummary{
				ActionSubmit: {},
				ActionModify: {},
				ActionCancel: {},
			},
		}
	}
	pCurrent.Requests++
	pAction := pCurrent.ByAction[r.Action]
	pAction.Requests++
	if r.Success {
		pCurrent.Success++
		pAction.Success++
	} else {
		pCurrent.Failures++
		pAction.Failures++
	}
	pCurrent.ByAction[r.Action] = pAction
	a.report.ByProfile[r.Profile] = pCurrent
	updateDimensionSummary(a.report.ByActor, r.ActorID, r.Action, r.Success)
	updateDimensionSummary(a.report.ByPersona, r.Persona, r.Action, r.Success)
	updateDimensionSummary(a.report.ByStrategy, r.StrategyID, r.Action, r.Success)
}

func (a *summaryAccumulator) build(sessionID string, started, finished time.Time, cfg Config, loadSchedule loadScheduleSummary) summary {
	report := a.report
	report.SessionID = sessionID
	report.StartedAt = started
	report.FinishedAt = finished
	report.DurationSeconds = finished.Sub(started).Seconds()
	report.Config = cfg
	report.LoadSchedule = loadSchedule
	if report.DurationSeconds > 0 {
		report.ThroughputRPS = float64(report.TotalRequests) / report.DurationSeconds
		report.AcceptedBusinessOpsRPS = float64(report.TotalSuccess) / report.DurationSeconds
	}
	report.Throughput = reporting.ComputeThroughput(report.TotalRequests, report.TotalSuccess, 0, report.DurationSeconds)
	report.LatencyMs = computeLatency(a.allLatencies)
	for action, values := range a.actionLatencies {
		current := report.ByAction[action]
		current.Latency = computeLatency(values)
		report.ByAction[action] = current
	}
	for profile, values := range a.profileLatencies {
		pCurrent := report.ByProfile[profile]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.profileActionLatencies[profile])
		report.ByProfile[profile] = pCurrent
	}
	for actorID, values := range a.actorLatencies {
		pCurrent := report.ByActor[actorID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.actorActionLatencies[actorID])
		report.ByActor[actorID] = pCurrent
	}
	for persona, values := range a.personaLatencies {
		pCurrent := report.ByPersona[persona]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.personaActionLatencies[persona])
		report.ByPersona[persona] = pCurrent
	}
	for strategyID, values := range a.strategyLatencies {
		pCurrent := report.ByStrategy[strategyID]
		pCurrent.Latency = computeLatency(values)
		applyActionLatencyBuckets(&pCurrent, a.strategyActionLatencies[strategyID])
		report.ByStrategy[strategyID] = pCurrent
	}
	report.TopErrors = topErrors(a.errorCounts, 8)
	report.RejectReasons = topErrors(a.rejectReasons, 12)
	report.RejectTaxonomy = summarizeRejectTaxonomy(a.rejectCodes, report.TotalFailures, a.totalRejects, 12)
	report.Quality = computeQuality(report.TotalRequests, report.TotalSuccess, report.TotalFailures, a.rejectCodes, defaultInvalidIntentRejectCodes)
	return report
}

func summarizeRejectTaxonomy(rejectCodes map[string]int64, totalFailures int64, totalRejects int64, limit int) []rejectTaxonomySummary {
	return reporting.SummarizeRejectTaxonomy(rejectCodes, totalFailures, totalRejects, limit)
}

func computeQuality(totalRequests, totalSuccess, totalFailures int64, rejectCodes map[string]int64, invalidIntentCodes []string) qualitySummary {
	return reporting.ComputeQuality(totalRequests, totalSuccess, totalFailures, rejectCodes, invalidIntentCodes)
}

func updateDimensionSummary(target map[string]profileSummary, key string, action Action, success bool) {
	if key == "" {
		return
	}
	current, ok := target[key]
	if !ok {
		current = profileSummary{
			ByAction: map[Action]actionSummary{
				ActionSubmit: {},
				ActionModify: {},
				ActionCancel: {},
			},
		}
	}
	current.Requests++
	actionRow := current.ByAction[action]
	actionRow.Requests++
	if success {
		current.Success++
		actionRow.Success++
	} else {
		current.Failures++
		actionRow.Failures++
	}
	current.ByAction[action] = actionRow
	target[key] = current
}

func appendActionLatency(target map[string]map[Action][]float64, key string, action Action, latencyMs float64) {
	byAction := target[key]
	if byAction == nil {
		byAction = map[Action][]float64{}
		target[key] = byAction
	}
	byAction[action] = append(byAction[action], latencyMs)
}

func applyActionLatencyBuckets(current *profileSummary, buckets map[Action][]float64) {
	for action, actionSummary := range current.ByAction {
		actionSummary.Latency = computeLatency(buckets[action])
		current.ByAction[action] = actionSummary
	}
}

func computeLatency(values []float64) latencySummary {
	return reporting.ComputeLatency(values)
}

func topErrors(m map[string]int64, limit int) []errorSummary {
	return reporting.TopErrors(m, limit)
}
