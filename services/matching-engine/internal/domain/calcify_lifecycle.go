package domain

const OrderLifecycleCommandSchemaV1 = "calcify-order-lifecycle-command-v1"

// OrderLifecycleCommandV1 preserves the decoded, routed attempted command.
// It is source evidence, not acceptance or financial authority. The separate
// finite binding identifies immutable configuration; activation is gated elsewhere.
type OrderLifecycleCommandV1 struct {
	Schema              string                  `json:"schema"`
	SourceProfileHash   string                  `json:"sourceProfileHash"`
	FiniteBindingDigest string                  `json:"finiteBindingDigest"`
	CommandType         string                  `json:"commandType"`
	CommandID           string                  `json:"commandId"`
	RunID               string                  `json:"runId"`
	VenueSessionID      string                  `json:"venueSessionId"`
	InstrumentID        string                  `json:"instrumentId"`
	OrderID             string                  `json:"orderId"`
	ParticipantID       string                  `json:"participantId"`
	AccountID           string                  `json:"accountId"`
	TraceID             string                  `json:"traceId"`
	CausationID         string                  `json:"causationId"`
	CorrelationID       string                  `json:"correlationId"`
	ActorID             string                  `json:"actorId"`
	OccurredAt          string                  `json:"occurredAt"`
	Submit              *OrderLifecycleSubmitV1 `json:"submit,omitempty"`
	Modify              *OrderLifecycleModifyV1 `json:"modify,omitempty"`
	Cancel              *OrderLifecycleCancelV1 `json:"cancel,omitempty"`
}

type OrderLifecycleSubmitV1 struct {
	ClientOrderID string `json:"clientOrderId"`
	Side          Side   `json:"side"`
	OrderType     string `json:"orderType"`
	QuantityUnits string `json:"quantityUnits"`
	LimitPrice    string `json:"limitPrice"`
	Currency      string `json:"currency"`
	TimeInForce   string `json:"timeInForce"`
}

// QuantityUnits is amended total quantity, including previously filled units.
type OrderLifecycleModifyV1 struct {
	QuantityUnits string `json:"quantityUnits"`
	LimitPrice    string `json:"limitPrice"`
}

type OrderLifecycleCancelV1 struct {
	Reason string `json:"reason"`
}
