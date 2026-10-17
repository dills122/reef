package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type requestResult struct {
	Profile      string
	ActorID      string
	ActorType    string
	Persona      string
	StrategyID   string
	Action       Action
	Success      bool
	StatusCode   int
	Latency      time.Duration
	ErrorText    string
	TraceID      string
	OrderID      string
	CommandID    string
	RejectCode   string
	RejectReason string
}

type workerState struct {
	orders          []trackedOrder
	rejectStreak    int
	submitOnlyTicks int
}

type trackedOrder struct {
	OrderID        string
	RunID          string
	VenueSessionID string
	InstrumentID   string
	ParticipantID  string
	AccountID      string
}

func runWorker(
	ctx context.Context,
	client *http.Client,
	cfg Config,
	sessionID string,
	workerID int,
	profile string,
	rateCh <-chan struct{},
	results chan<- requestResult,
	traceSeen *traceSampler,
) {
	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(workerID)*7919))
	if cfg.Seed != 0 {
		rng = rand.New(rand.NewSource(cfg.Seed + int64(workerID)*7919))
	}
	var stream *streamSubmitter
	if cfg.Transport == transportStream {
		stream = &streamSubmitter{
			address: cfg.StreamAddress,
			timeout: cfg.RequestTimeout,
		}
		defer stream.close()
	}
	state := workerState{orders: make([]trackedOrder, 0, 128)}
	var localSeq int64
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if cfg.RatePerSecond > 0 {
			select {
			case <-ctx.Done():
				return
			case <-rateCh:
			}
		}

		actor := chooseSessionActor(rng, cfg)
		effectiveProfile := profile
		if actor != nil {
			effectiveProfile = actor.ActorType
		}
		action := chooseActionForActor(rng, cfg, hasActionableOrders(cfg, state), effectiveProfile, actor)
		if state.submitOnlyTicks > 0 {
			action = ActionSubmit
			state.submitOnlyTicks--
		} else if action != ActionSubmit && !shouldAllowLifecycleAction(rng, cfg, state) {
			action = ActionSubmit
		}
		localSeq++
		reqID := (localSeq-1)*int64(cfg.Workers) + int64(workerID) + 1
		traceID := fmt.Sprintf("%s-trace-%d-%d", sessionID, workerID, reqID)
		commandID := fmt.Sprintf("%s-cmd-%d-%d", sessionID, workerID, reqID)
		start := time.Now()
		actorID := fmt.Sprintf("bot-%d", workerID)
		actorType := effectiveProfile
		persona := ""
		strategyID := ""
		if actor != nil {
			actorID = actor.ActorID
			actorType = actor.ActorType
			persona = actor.Persona
			strategyID = actor.StrategyID
		}
		result := requestResult{
			Profile:    effectiveProfile,
			ActorID:    actorID,
			ActorType:  actorType,
			Persona:    persona,
			StrategyID: strategyID,
			Action:     action,
			TraceID:    traceID,
			CommandID:  commandID,
		}
		switch action {
		case ActionSubmit:
			orderID := fmt.Sprintf("%s-ord-%d-%d", sessionID, workerID, reqID)
			result.OrderID = orderID
			instrumentID := cfg.InstrumentID
			instrument := chooseInstrumentForActor(rng, cfg, actor)
			if instrument != nil {
				instrumentID = instrument.InstrumentID
			}
			if shouldInjectFault(rng, cfg, "reject_submit", instrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
			participantID, accountID := orderIdentityForActor(cfg, actorID)
			payload["orderId"] = orderID
			payload["instrumentId"] = instrumentID
			payload["participantId"] = participantID
			payload["accountId"] = accountID
			payload["side"] = chooseSideForConfig(rng, cfg)
			payload["orderType"] = "LIMIT"
			payload["quantityUnits"] = fmt.Sprintf("%d", profileQuantity(rng, cfg, profile))
			payload["limitPrice"] = fmt.Sprintf("%d", profilePrice(rng, cfg, effectiveProfile, instrument))
			payload["currency"] = "USD"
			payload["timeInForce"] = "DAY"
			status, body, err := submitCommand(client, stream, cfg, workerID, commandID, traceID, payload, ActionSubmit)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders, trackedOrder{
					OrderID:        orderID,
					RunID:          payload["runId"],
					VenueSessionID: payload["venueSessionId"],
					InstrumentID:   instrumentID,
					ParticipantID:  participantID,
					AccountID:      accountID,
				})
				state.orders = compactTrackedOrders(state.orders, cfg)
				state.rejectStreak = 0
				traceSeen.offer(traceID)
			} else if isTerminalOrderRejection(result.RejectCode) {
				updateRecoveryState(&state, cfg)
			}
		case ActionModify:
			if len(state.orders) == 0 {
				continue
			}
			order := pickOrder(rng, state.orders, cfg.Mode)
			result.OrderID = order.OrderID
			if shouldInjectFault(rng, cfg, "reject_modify", order.InstrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
			payload["orderId"] = order.OrderID
			payload["runId"] = order.RunID
			payload["venueSessionId"] = order.VenueSessionID
			payload["instrumentId"] = order.InstrumentID
			payload["participantId"] = order.ParticipantID
			payload["accountId"] = order.AccountID
			payload["quantityUnits"] = fmt.Sprintf("%d", profileQuantity(rng, cfg, profile))
			payload["limitPrice"] = fmt.Sprintf("%d", profilePrice(rng, cfg, effectiveProfile, instrumentByID(cfg, order.InstrumentID)))
			status, body, err := doPOST(
				client,
				cfg.BaseURL+commandRoute(cfg, ActionModify),
				payload,
				commandHeaders(cfg, workerID, commandID, traceID),
			)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.rejectStreak = 0
				traceSeen.offer(traceID)
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, order.OrderID)
				state.orders = compactTrackedOrders(state.orders, cfg)
				updateRecoveryState(&state, cfg)
			}
		case ActionCancel:
			if len(state.orders) == 0 {
				continue
			}
			idx := pickOrderIndex(rng, state.orders, cfg.Mode)
			order := state.orders[idx]
			result.OrderID = order.OrderID
			if shouldInjectFault(rng, cfg, "reject_cancel", order.InstrumentID) {
				result.StatusCode = 200
				result.ErrorText = "rejected:INJECTED_FAULT"
				result.RejectCode = "INJECTED_FAULT"
				result.RejectReason = "deterministic fault injection"
				results <- result
				continue
			}
			payload := buildCommandPayload(cfg, sessionID, commandID, traceID, actorID, actorType, persona, strategyID, reqID)
			payload["orderId"] = order.OrderID
			payload["runId"] = order.RunID
			payload["venueSessionId"] = order.VenueSessionID
			payload["instrumentId"] = order.InstrumentID
			payload["participantId"] = order.ParticipantID
			payload["accountId"] = order.AccountID
			payload["reason"] = "load test"
			status, body, err := doPOST(
				client,
				cfg.BaseURL+commandRoute(cfg, ActionCancel),
				payload,
				commandHeaders(cfg, workerID, commandID, traceID),
			)
			fillResult(&result, status, body, err, start)
			if result.Success {
				state.orders = append(state.orders[:idx], state.orders[idx+1:]...)
				state.rejectStreak = 0
				traceSeen.offer(traceID)
			} else if shouldPruneTerminalOrder(cfg.Mode) && isTerminalOrderRejection(result.RejectCode) {
				state.orders = removeOrder(state.orders, order.OrderID)
				state.orders = compactTrackedOrders(state.orders, cfg)
				updateRecoveryState(&state, cfg)
			}
		}
		results <- result
	}
}

func submitCommand(
	client *http.Client,
	stream *streamSubmitter,
	cfg Config,
	workerID int,
	commandID string,
	traceID string,
	payload map[string]string,
	action Action,
) (int, []byte, error) {
	if cfg.Transport == transportStream && action == ActionSubmit {
		return stream.submit(payload)
	}
	return doPOST(
		client,
		cfg.BaseURL+commandRoute(cfg, action),
		payload,
		commandHeaders(cfg, workerID, commandID, traceID),
	)
}

type streamSubmitter struct {
	address string
	timeout time.Duration
	conn    net.Conn
	reader  *bufio.Reader
	writer  *bufio.Writer
}

func (s *streamSubmitter) submit(payload map[string]string) (int, []byte, error) {
	if err := s.ensureConnected(); err != nil {
		return 0, nil, err
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	if err := s.conn.SetDeadline(time.Now().Add(s.timeout)); err != nil {
		s.close()
		return 0, nil, err
	}
	if _, err := s.writer.Write(body); err != nil {
		s.close()
		return 0, nil, err
	}
	if err := s.writer.WriteByte('\n'); err != nil {
		s.close()
		return 0, nil, err
	}
	if err := s.writer.Flush(); err != nil {
		s.close()
		return 0, nil, err
	}
	line, err := s.reader.ReadString('\n')
	if err != nil {
		s.close()
		return 0, nil, err
	}
	line = strings.TrimSpace(line)
	statusText, bodyText, hasBody := strings.Cut(line, "\t")
	status, err := strconv.Atoi(statusText)
	if err != nil {
		return 0, nil, err
	}
	if hasBody {
		return status, []byte(bodyText), nil
	}
	return status, nil, nil
}

func (s *streamSubmitter) ensureConnected() error {
	if s.conn != nil {
		return nil
	}
	conn, err := net.DialTimeout("tcp", s.address, s.timeout)
	if err != nil {
		return err
	}
	s.conn = conn
	s.reader = bufio.NewReaderSize(conn, 64*1024)
	s.writer = bufio.NewWriterSize(conn, 64*1024)
	return nil
}

func (s *streamSubmitter) close() {
	if s.conn != nil {
		_ = s.conn.Close()
	}
	s.conn = nil
	s.reader = nil
	s.writer = nil
}

func fillResult(result *requestResult, status int, body []byte, err error, start time.Time) {
	result.StatusCode = status
	result.Latency = time.Since(start)
	if err != nil {
		result.ErrorText = err.Error()
		return
	}
	if status < 200 || status >= 300 {
		var boundaryError boundaryErrorResponse
		if jsonErr := json.Unmarshal(body, &boundaryError); jsonErr == nil && boundaryError.Code != "" {
			result.RejectCode = boundaryError.Code
			result.RejectReason = boundaryError.Message
			result.ErrorText = fmt.Sprintf("http_%d:%s", status, boundaryError.Code)
		} else {
			result.ErrorText = fmt.Sprintf("http_%d", status)
		}
		return
	}
	text := string(body)
	if strings.Contains(text, "\"rejected\"") {
		var parsed runtimeResponse
		if err := json.Unmarshal(body, &parsed); err == nil && parsed.Rejected != nil {
			result.RejectCode = parsed.Rejected.Code
			result.RejectReason = parsed.Rejected.Reason
			if parsed.Rejected.Code != "" {
				result.ErrorText = "rejected:" + parsed.Rejected.Code
			} else {
				result.ErrorText = "rejected"
			}
		} else {
			result.ErrorText = "rejected"
		}
		return
	}
	result.Success = true
}

func commandRoute(cfg Config, action Action) string {
	if cfg.UseApiV1 {
		switch action {
		case ActionModify:
			return "/api/v1/orders/modify"
		case ActionCancel:
			return "/api/v1/orders/cancel"
		default:
			return "/api/v1/orders/submit"
		}
	}
	switch action {
	case ActionModify:
		return "/orders/modify"
	case ActionCancel:
		return "/orders/cancel"
	default:
		return "/orders/submit"
	}
}

func commandHeaders(cfg Config, workerID int, commandID, traceID string) map[string]string {
	if !cfg.UseApiV1 {
		if cfg.LegacyInternalRoute {
			return map[string]string{
				"X-Reef-Internal-Route": "true",
			}
		}
		return nil
	}
	headers := map[string]string{
		"X-Client-Id":      fmt.Sprintf("%s-%d", cfg.ClientIDPrefix, workerID),
		"Idempotency-Key":  commandID,
		"X-Correlation-Id": traceID,
	}
	if token := strings.TrimSpace(cfg.APIBearerToken); token != "" {
		headers["Authorization"] = "Bearer " + token
	}
	return headers
}

func buildCommandPayload(cfg Config, sessionID, commandID, traceID, actorID, actorType, persona, strategyID string, reqID int64) map[string]string {
	payload := map[string]string{
		"commandId":      commandID,
		"traceId":        traceID,
		"causationId":    "",
		"correlationId":  traceID,
		"actorId":        actorID,
		"actorType":      actorType,
		"strategyId":     strategyID,
		"runId":          cfg.RunID,
		"runKind":        cfg.RunKind,
		"scenarioId":     cfg.ScenarioID,
		"venueSessionId": commandVenueSessionID(cfg, sessionID),
		"occurredAt":     commandOccurredAt(cfg, reqID),
	}
	if persona != "" {
		payload["persona"] = persona
	}
	if cfg.ScenarioRunID != "" {
		payload["scenarioRunId"] = cfg.ScenarioRunID
	}
	if cfg.Seed != 0 {
		payload["seed"] = strconv.FormatInt(cfg.Seed, 10)
	}
	return payload
}

func commandVenueSessionID(cfg Config, sessionID string) string {
	if value := strings.TrimSpace(cfg.SessionName); value != "" {
		return value
	}
	if value := strings.TrimSpace(sessionID); value != "" {
		return value
	}
	if value := strings.TrimSpace(cfg.RunID); value != "" {
		return value
	}
	return "load-session"
}

func commandOccurredAt(cfg Config, reqID int64) string {
	if cfg.CommandClockStart == "" {
		return time.Now().UTC().Format(time.RFC3339)
	}
	start, err := time.Parse(time.RFC3339, cfg.CommandClockStart)
	if err != nil {
		return time.Now().UTC().Format(time.RFC3339)
	}
	step := cfg.CommandClockStep
	if step <= 0 {
		step = time.Second
	}
	offset := reqID - 1
	if offset < 0 {
		offset = 0
	}
	return start.Add(time.Duration(offset) * step).UTC().Format(time.RFC3339)
}
