package domain

type Side string

const (
	SideBuy  Side = "BUY"
	SideSell Side = "SELL"
)

type OrderStatus string

const (
	OrderStatusAccepted        OrderStatus = "ACCEPTED"
	OrderStatusPartiallyFilled OrderStatus = "PARTIALLY_FILLED"
	OrderStatusFilled          OrderStatus = "FILLED"
	OrderStatusRejected        OrderStatus = "REJECTED"
)

type SubmitOrder struct {
	CommandID     string `json:"commandId"`
	CorrelationID string `json:"correlationId"`
	ActorID       string `json:"actorId"`
	OccurredAt    string `json:"occurredAt"`
	OrderID       string `json:"orderId"`
	InstrumentID  string `json:"instrumentId"`
	ParticipantID string `json:"participantId"`
	AccountID     string `json:"accountId"`
	Side          Side   `json:"side"`
	OrderType     string `json:"orderType"`
	QuantityUnits string `json:"quantityUnits"`
	LimitPrice    string `json:"limitPrice"`
	Currency      string `json:"currency"`
	TimeInForce   string `json:"timeInForce"`
}

type OrderAccepted struct {
	EventID       string `json:"eventId"`
	OrderID       string `json:"orderId"`
	EngineOrderID string `json:"engineOrderId"`
	OccurredAt    string `json:"occurredAt"`
}

type OrderRejected struct {
	EventID    string `json:"eventId"`
	OrderID    string `json:"orderId"`
	Code       string `json:"code"`
	Reason     string `json:"reason"`
	OccurredAt string `json:"occurredAt"`
}

type OrderState struct {
	OrderID           string      `json:"orderId"`
	InstrumentID      string      `json:"instrumentId"`
	Side              Side        `json:"side"`
	Status            OrderStatus `json:"status"`
	OriginalQuantity  string      `json:"originalQuantity"`
	RemainingQuantity string      `json:"remainingQuantity"`
	LimitPrice        string      `json:"limitPrice"`
	Currency          string      `json:"currency"`
	LastUpdatedAt     string      `json:"lastUpdatedAt"`
}

type ExecutionCreated struct {
	EventID        string `json:"eventId"`
	ExecutionID    string `json:"executionId"`
	OrderID        string `json:"orderId"`
	InstrumentID   string `json:"instrumentId"`
	QuantityUnits  string `json:"quantityUnits"`
	ExecutionPrice string `json:"executionPrice"`
	Currency       string `json:"currency"`
	OccurredAt     string `json:"occurredAt"`
}

type TradeCreated struct {
	EventID       string `json:"eventId"`
	TradeID       string `json:"tradeId"`
	ExecutionID   string `json:"executionId"`
	BuyOrderID    string `json:"buyOrderId"`
	SellOrderID   string `json:"sellOrderId"`
	InstrumentID  string `json:"instrumentId"`
	QuantityUnits string `json:"quantityUnits"`
	Price         string `json:"price"`
	Currency      string `json:"currency"`
	OccurredAt    string `json:"occurredAt"`
}

type SubmitOrderResult struct {
	Accepted   *OrderAccepted     `json:"accepted,omitempty"`
	Rejected   *OrderRejected     `json:"rejected,omitempty"`
	Executions []ExecutionCreated `json:"executions,omitempty"`
	Trades     []TradeCreated     `json:"trades,omitempty"`
}
