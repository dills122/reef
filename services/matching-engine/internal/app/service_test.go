package app

import (
	"bufio"
	"encoding/json"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

func TestSubmitOrderAcceptsAndRestsFirstOrder(t *testing.T) {
	service := NewService()

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted result, got %#v", result)
	}

	if len(result.Executions) != 0 {
		t.Fatalf("expected no executions for resting order, got %#v", result.Executions)
	}

	if len(result.Trades) != 0 {
		t.Fatalf("expected no trades for resting order, got %#v", result.Trades)
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected one resting buy order")
	}

	state, ok := service.OrderState("ord-1")
	if !ok {
		t.Fatalf("expected order state for ord-1")
	}

	if state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" {
		t.Fatalf("unexpected accepted order state: %#v", state)
	}
}

func TestServiceUsesCommandTimestampForGeneratedEvents(t *testing.T) {
	service := NewService(WithClock(func() time.Time {
		return time.Date(2026, 3, 14, 18, 30, 0, 0, time.UTC)
	}))
	buyTime := "2026-03-14T18:00:00Z"
	sellTime := "2026-03-14T18:00:05Z"

	buy := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-buy",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
		OccurredAt:    buyTime,
	})
	if buy.Accepted == nil || buy.Accepted.OccurredAt != buyTime {
		t.Fatalf("expected buy accepted timestamp %s, got %#v", buyTime, buy.Accepted)
	}

	sell := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-sell",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
		OccurredAt:    sellTime,
	})
	if sell.Accepted == nil || sell.Accepted.OccurredAt != sellTime {
		t.Fatalf("expected sell accepted timestamp %s, got %#v", sellTime, sell.Accepted)
	}
	for _, execution := range sell.Executions {
		if execution.OccurredAt != sellTime {
			t.Fatalf("expected execution timestamp %s, got %#v", sellTime, execution)
		}
	}
	for _, trade := range sell.Trades {
		if trade.OccurredAt != sellTime {
			t.Fatalf("expected trade timestamp %s, got %#v", sellTime, trade)
		}
	}
	buyState, ok := service.OrderState("ord-clock-buy")
	if !ok || buyState.LastUpdatedAt != sellTime {
		t.Fatalf("expected matched buy state timestamp %s, got %#v", sellTime, buyState)
	}

	rejectTime := "2026-03-14T18:00:10Z"
	rejected := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-reject",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		OccurredAt:    rejectTime,
	})
	if rejected.Rejected == nil || rejected.Rejected.OccurredAt != rejectTime {
		t.Fatalf("expected rejection timestamp %s, got %#v", rejectTime, rejected.Rejected)
	}
}

func TestServiceFallsBackToInjectedClockWhenCommandTimestampMissing(t *testing.T) {
	fixed := time.Date(2026, 3, 14, 18, 45, 0, 0, time.UTC)
	service := NewService(WithClock(func() time.Time {
		return fixed
	}))

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-clock-fallback",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if result.Accepted == nil || result.Accepted.OccurredAt != fixed.Format(time.RFC3339) {
		t.Fatalf("expected injected clock timestamp, got %#v", result.Accepted)
	}
}

func TestSubmitOrderMatchesCrossingOrder(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted result, got %#v", result)
	}

	if len(result.Executions) != 2 {
		t.Fatalf("expected two executions, got %#v", result.Executions)
	}

	if len(result.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", result.Trades)
	}

	if result.Trades[0].BuyOrderID != "ord-buy-1" || result.Trades[0].SellOrderID != "ord-sell-1" {
		t.Fatalf("unexpected trade payload: %#v", result.Trades[0])
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting buy orders after full match")
	}

	buyState, ok := service.OrderState("ord-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled buy order state, got %#v", buyState)
	}

	sellState, ok := service.OrderState("ord-sell-1")
	if !ok || sellState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled sell order state, got %#v", sellState)
	}
}

func TestSubmitOrderPartiallyFillsAndLeavesResidualLiquidity(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "150",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	firstMatch := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if len(firstMatch.Trades) != 1 {
		t.Fatalf("expected one trade on first partial fill, got %#v", firstMatch.Trades)
	}

	if firstMatch.Trades[0].QuantityUnits != "100" {
		t.Fatalf("expected 100 units on first trade, got %#v", firstMatch.Trades[0])
	}

	secondMatch := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-2",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "60",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if len(secondMatch.Trades) != 1 {
		t.Fatalf("expected one trade on second partial fill, got %#v", secondMatch.Trades)
	}

	if secondMatch.Trades[0].QuantityUnits != "50" {
		t.Fatalf("expected residual 50 units to match, got %#v", secondMatch.Trades[0])
	}

	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting buy orders after residual fill")
	}

	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected one resting sell order after residual fill")
	}

	buyState, ok := service.OrderState("ord-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected filled buy order state, got %#v", buyState)
	}

	secondSellState, ok := service.OrderState("ord-sell-2")
	if !ok || secondSellState.Status != domain.OrderStatusPartiallyFilled || secondSellState.RemainingQuantity != "10" {
		t.Fatalf("expected partially filled sell order state, got %#v", secondSellState)
	}
}

func TestSubmitOrderMatchesAcrossMultipleRestingOrders(t *testing.T) {
	service := NewService()

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "75",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-2",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "80",
		LimitPrice:    "150100000000",
		Currency:      "USD",
	})

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "140",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if len(result.Executions) != 4 {
		t.Fatalf("expected four executions across two matches, got %#v", result.Executions)
	}

	if len(result.Trades) != 2 {
		t.Fatalf("expected two trades across two resting orders, got %#v", result.Trades)
	}

	if result.Trades[0].QuantityUnits != "75" {
		t.Fatalf("expected first trade to consume best offer, got %#v", result.Trades[0])
	}

	if result.Trades[1].QuantityUnits != "65" {
		t.Fatalf("expected second trade to consume remaining buy quantity, got %#v", result.Trades[1])
	}

	if result.Trades[0].SellOrderID != "ord-sell-1" || result.Trades[1].SellOrderID != "ord-sell-2" {
		t.Fatalf("expected price-time matching order, got %#v", result.Trades)
	}

	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected one remaining resting sell order after sweep")
	}

	restingState, ok := service.OrderState("ord-sell-2")
	if !ok || restingState.Status != domain.OrderStatusPartiallyFilled || restingState.RemainingQuantity != "15" {
		t.Fatalf("expected remaining resting sell state, got %#v", restingState)
	}
}

func TestSubmitOrderPreservesSamePriceFIFOThroughService(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-3", "100", "150250000000")

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-sweep",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "300",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})

	if len(result.Trades) != 3 {
		t.Fatalf("expected three trades, got %#v", result.Trades)
	}
	wantBuyOrderIDs := []string{"ord-buy-1", "ord-buy-2", "ord-buy-3"}
	for i, want := range wantBuyOrderIDs {
		if result.Trades[i].BuyOrderID != want {
			t.Fatalf("expected FIFO buy order %s at trade %d, got %#v", want, i, result.Trades)
		}
	}
}

func TestSubmitOrderPreservesSellSamePriceFIFOThroughService(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-sell-1", "100", "150250000000")
	submitRestingSell(t, service, "AAPL", "ord-sell-2", "100", "150250000000")
	submitRestingSell(t, service, "AAPL", "ord-sell-3", "100", "150250000000")

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-sweep",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "300",
		LimitPrice:    "150500000000",
		Currency:      "USD",
	})

	if len(result.Trades) != 3 {
		t.Fatalf("expected three trades, got %#v", result.Trades)
	}
	wantSellOrderIDs := []string{"ord-sell-1", "ord-sell-2", "ord-sell-3"}
	for i, want := range wantSellOrderIDs {
		if result.Trades[i].SellOrderID != want {
			t.Fatalf("expected FIFO sell order %s at trade %d, got %#v", want, i, result.Trades)
		}
	}
}

func TestSubmitOrderMatchesBestPriceBeforeTime(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-older-worse", "100", "150000000000")
	submitRestingBuy(t, service, "ord-buy-newer-better", "100", "150250000000")

	firstSell := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "149900000000",
		Currency:      "USD",
	})
	if len(firstSell.Trades) != 1 {
		t.Fatalf("expected one trade against best bid, got %#v", firstSell.Trades)
	}
	if firstSell.Trades[0].BuyOrderID != "ord-buy-newer-better" {
		t.Fatalf("expected newer better-priced buy to match before older worse-priced buy, got %#v", firstSell.Trades[0])
	}

	submitRestingSell(t, service, "AAPL", "ord-sell-older-worse", "100", "150500000000")
	submitRestingSell(t, service, "AAPL", "ord-sell-newer-better", "100", "150300000000")

	firstBuy := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150600000000",
		Currency:      "USD",
	})
	if len(firstBuy.Trades) != 1 {
		t.Fatalf("expected one trade against best offer, got %#v", firstBuy.Trades)
	}
	if firstBuy.Trades[0].SellOrderID != "ord-sell-newer-better" {
		t.Fatalf("expected newer better-priced sell to match before older worse-priced sell, got %#v", firstBuy.Trades[0])
	}
}

func TestSubmitOrderDoesNotMatchAcrossInstruments(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-aapl-sell", "100", "150000000000")

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-msft-buy",
		InstrumentID:  "MSFT",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "200000000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted MSFT buy, got %#v", result)
	}
	if len(result.Trades) != 0 {
		t.Fatalf("expected no cross-instrument trades, got %#v", result.Trades)
	}
	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected AAPL sell liquidity to remain")
	}
	if service.RestingOrders("MSFT", domain.SideBuy) != 1 {
		t.Fatalf("expected MSFT buy liquidity to rest separately")
	}
}

func TestSubmitOrderUsesRestingOrderPriceForExecution(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-sell-resting", "100", "150000000000")

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-marketable",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150500000000",
		Currency:      "USD",
	})

	if len(result.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", result.Trades)
	}
	if result.Trades[0].Price != "150000000000" {
		t.Fatalf("expected trade at resting sell price, got %#v", result.Trades[0])
	}
	for _, execution := range result.Executions {
		if execution.ExecutionPrice != "150000000000" {
			t.Fatalf("expected execution at resting sell price, got %#v", execution)
		}
	}
}

func TestSubmitOrderLeavesNonCrossingOrdersResting(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-sell-1", "100", "150300000000")

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	if result.Accepted == nil {
		t.Fatalf("expected accepted non-crossing buy, got %#v", result)
	}
	if len(result.Trades) != 0 {
		t.Fatalf("expected no trades for non-crossing prices, got %#v", result.Trades)
	}
	if service.RestingOrders("AAPL", domain.SideSell) != 1 || service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected both non-crossing orders to rest")
	}
}

func TestSubmitOrderRejectsMissingInstrument(t *testing.T) {
	service := NewService()

	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
	})

	if result.Rejected == nil {
		t.Fatalf("expected rejected result, got %#v", result)
	}

	if result.Rejected.Code != "VALIDATION_ERROR" {
		t.Fatalf("expected validation error, got %s", result.Rejected.Code)
	}
}

func TestSubmitOrderRejectsDuplicateOrderIDWithoutMutatingBook(t *testing.T) {
	service := NewService()

	first := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-dup",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	if first.Accepted == nil {
		t.Fatalf("expected first order to be accepted, got %#v", first)
	}

	duplicate := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-dup",
		InstrumentID:  "MSFT",
		Side:          domain.SideSell,
		QuantityUnits: "999",
		LimitPrice:    "1",
		Currency:      "USD",
	})
	if duplicate.Rejected == nil {
		t.Fatalf("expected duplicate order to be rejected, got %#v", duplicate)
	}
	if duplicate.Rejected.Code != "DUPLICATE_ORDER_ID" {
		t.Fatalf("expected duplicate order code, got %s", duplicate.Rejected.Code)
	}

	state, ok := service.OrderState("ord-dup")
	if !ok {
		t.Fatal("expected original order state to remain")
	}
	if state.InstrumentID != "AAPL" || state.Side != domain.SideBuy || state.RemainingQuantity != "100" || state.LimitPrice != "150250000000" {
		t.Fatalf("duplicate mutated original state: %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected original resting order to remain on AAPL buy book")
	}
	if service.RestingOrders("MSFT", domain.SideSell) != 0 {
		t.Fatalf("expected duplicate to add no MSFT sell liquidity")
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-cross",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if match.Accepted == nil || len(match.Trades) != 1 {
		t.Fatalf("expected original order to match exactly once, got %#v", match)
	}
	if match.Trades[0].BuyOrderID != "ord-dup" || match.Trades[0].SellOrderID != "ord-cross" {
		t.Fatalf("unexpected trade parties after duplicate rejection: %#v", match.Trades[0])
	}
}

func TestSubmitOrderRejectsMarketIntegrityControlBreaches(t *testing.T) {
	service := NewService(WithOrderControls(OrderControls{
		MaxQuantityUnits: 100,
		MaxNotional:      15_100_000_000_000,
		PriceCollars: map[string]PriceCollar{
			"AAPL": {ReferencePrice: 150_000_000_000, BandBps: 100},
		},
	}))

	tests := []domain.SubmitOrder{
		{
			OrderID:       "ord-too-large",
			InstrumentID:  "AAPL",
			Side:          domain.SideBuy,
			QuantityUnits: "101",
			LimitPrice:    "150000000000",
			Currency:      "USD",
		},
		{
			OrderID:       "ord-too-much-notional",
			InstrumentID:  "AAPL",
			Side:          domain.SideBuy,
			QuantityUnits: "100",
			LimitPrice:    "152000000000",
			Currency:      "USD",
		},
		{
			OrderID:       "ord-outside-collar",
			InstrumentID:  "AAPL",
			Side:          domain.SideSell,
			QuantityUnits: "50",
			LimitPrice:    "153000000000",
			Currency:      "USD",
		},
	}

	for _, cmd := range tests {
		t.Run(cmd.OrderID, func(t *testing.T) {
			result := service.SubmitOrder(cmd)
			if result.Rejected == nil {
				t.Fatalf("expected market integrity rejection, got %#v", result)
			}
			if result.Rejected.Code != "MARKET_INTEGRITY_CONTROL" {
				t.Fatalf("expected market integrity code, got %#v", result.Rejected)
			}
			if _, ok := service.OrderState(cmd.OrderID); ok {
				t.Fatalf("expected rejected order %s to avoid order state", cmd.OrderID)
			}
		})
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 || service.RestingOrders("AAPL", domain.SideSell) != 0 {
		t.Fatalf("expected rejected market-control orders to avoid book mutation")
	}
}

func TestSubmitOrderRejectsWhenSessionNotOpen(t *testing.T) {
	service := NewService(WithSessionControls(SessionControls{
		States: map[string]SessionState{
			"session-halted": SessionStateHalted,
			"session-closed": SessionStateClosed,
		},
	}))

	for _, sessionID := range []string{"session-halted", "session-closed"} {
		t.Run(sessionID, func(t *testing.T) {
			result := service.SubmitOrder(domain.SubmitOrder{
				OrderID:        "ord-" + sessionID,
				VenueSessionID: sessionID,
				InstrumentID:   "AAPL",
				Side:           domain.SideBuy,
				QuantityUnits:  "100",
				LimitPrice:     "150250000000",
				Currency:       "USD",
			})
			if result.Rejected == nil {
				t.Fatalf("expected session-state rejection, got %#v", result)
			}
			if result.Rejected.Code != "SESSION_STATE_REJECT" {
				t.Fatalf("expected session-state reject code, got %#v", result.Rejected)
			}
			if _, ok := service.OrderState("ord-" + sessionID); ok {
				t.Fatalf("expected session-rejected order to avoid order state")
			}
		})
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected session-rejected submits to avoid book mutation")
	}
}

func TestSubmitOrderRejectsSelfTradePreventionWithoutMutation(t *testing.T) {
	service := NewService()
	resting := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if resting.Accepted == nil {
		t.Fatalf("expected resting own sell to accept, got %#v", resting)
	}

	cross := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150500000000",
		Currency:      "USD",
	})
	if cross.Rejected == nil {
		t.Fatalf("expected self-trade prevention reject, got %#v", cross)
	}
	if cross.Rejected.Code != "SELF_TRADE_PREVENTION" {
		t.Fatalf("expected self-trade prevention code, got %#v", cross.Rejected)
	}
	if _, ok := service.OrderState("ord-buy-own"); ok {
		t.Fatal("expected rejected self-trade taker to avoid order state")
	}
	if service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatal("expected resting order to remain after self-trade reject")
	}
}

func TestSubmitOrderRejectsSelfTradeReachableAfterOtherLiquidity(t *testing.T) {
	service := NewService()
	other := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-other",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-2",
		AccountID:     "account-2",
		Side:          domain.SideSell,
		QuantityUnits: "50",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if other.Accepted == nil {
		t.Fatalf("expected other resting sell to accept, got %#v", other)
	}
	own := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if own.Accepted == nil {
		t.Fatalf("expected own resting sell to accept, got %#v", own)
	}

	cross := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideBuy,
		QuantityUnits: "120",
		LimitPrice:    "150500000000",
		Currency:      "USD",
	})
	if cross.Rejected == nil || cross.Rejected.Code != "SELF_TRADE_PREVENTION" {
		t.Fatalf("expected depth-reachable self-trade prevention reject, got %#v", cross)
	}
	if len(cross.Trades) != 0 {
		t.Fatalf("expected self-trade reject to avoid partial execution, got %#v", cross.Trades)
	}
	if service.RestingOrders("AAPL", domain.SideSell) != 2 {
		t.Fatal("expected both resting sells to remain after self-trade reject")
	}
}

func TestSubmitOrderAllowsDifferentParticipantCross(t *testing.T) {
	service := NewService()
	resting := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-other",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-2",
		AccountID:     "account-2",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if resting.Accepted == nil {
		t.Fatalf("expected resting sell to accept, got %#v", resting)
	}

	cross := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-new",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150500000000",
		Currency:      "USD",
	})
	if len(cross.Trades) != 1 {
		t.Fatalf("expected different participant cross to trade, got %#v", cross)
	}
}

func TestCancelOrderRemovesRestingOrder(t *testing.T) {
	service := NewService()
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.CancelOrder(domain.CancelOrder{
		OrderID: "ord-1",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted cancel result, got %#v", result)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected no resting order after cancel")
	}
	state, ok := service.OrderState("ord-1")
	if !ok || state.Status != domain.OrderStatusCancelled {
		t.Fatalf("expected cancelled order state, got %#v", state)
	}
}

func TestTerminalOrderRetentionLimitPrunesOldestTerminalState(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-cancel-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	service.CancelOrder(domain.CancelOrder{OrderID: "ord-cancel-1"})

	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-cancel-2",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	service.CancelOrder(domain.CancelOrder{OrderID: "ord-cancel-2"})

	if _, ok := service.OrderState("ord-cancel-1"); ok {
		t.Fatalf("expected oldest terminal order to be pruned")
	}
	state, ok := service.OrderState("ord-cancel-2")
	if !ok || state.Status != domain.OrderStatusCancelled {
		t.Fatalf("expected newest terminal order to remain, got %#v", state)
	}
}

func TestBatchRollbackRestoresTerminalRetentionState(t *testing.T) {
	service := NewService(WithTerminalOrderRetentionLimit(1))
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-resting",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	rollback := service.BeginBatch([]string{"AAPL"})
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-failed-publish",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	if result.Accepted == nil || len(result.Trades) != 1 {
		t.Fatalf("expected crossing command to mutate terminal state before rollback, got %#v", result)
	}
	if tracked := service.terminalRetention.snapshot(); len(tracked.order)-tracked.head == 0 {
		t.Fatal("expected failed batch mutation to touch terminal retention before rollback")
	}

	rollback.Rollback(map[string][]string{"AAPL": {"ord-buy-failed-publish"}})

	tracked := service.terminalRetention.snapshot()
	if len(tracked.order)-tracked.head != 0 {
		t.Fatalf("expected rollback to restore empty terminal retention state, got %+v", tracked)
	}
	if _, ok := service.OrderState("ord-buy-failed-publish"); ok {
		t.Fatal("expected newly reserved order to be removed by rollback")
	}
	state, ok := service.OrderState("ord-sell-resting")
	if !ok || state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" {
		t.Fatalf("expected resting order state to be restored after rollback, got %#v", state)
	}
	if resting := service.RestingOrders("AAPL", domain.SideSell); resting != 1 {
		t.Fatalf("expected restored sell liquidity after rollback, got %d", resting)
	}
}

func TestServiceSnapshotRestorePreservesReplayChecksum(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-sell-1", "100", "150000000000")
	submitRestingSell(t, service, "AAPL", "ord-sell-2", "100", "150100000000")
	firstMatch := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "ord-buy-1",
		VenueSessionID: "session-1",
		InstrumentID:   "AAPL",
		Side:           domain.SideBuy,
		QuantityUnits:  "80",
		LimitPrice:     "150500000000",
		Currency:       "USD",
		OccurredAt:     "2026-03-14T18:00:00Z",
	})
	if len(firstMatch.Trades) != 1 {
		t.Fatalf("expected seed trade, got %#v", firstMatch.Trades)
	}
	submitRestingBuy(t, service, "ord-buy-resting", "100", "149900000000")
	modified := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-resting",
		QuantityUnits: "90",
		LimitPrice:    "149900000000",
		OccurredAt:    "2026-03-14T18:00:01Z",
	})
	if modified.Accepted == nil {
		t.Fatalf("expected seed modify to accept, got %#v", modified)
	}

	snapshot := service.Snapshot()
	if snapshot.Checksum == "" {
		t.Fatal("expected service snapshot checksum")
	}
	if snapshot.Metadata.SnapshotVersion != "matching-service-snapshot-v1" || snapshot.Metadata.EngineVersion == "" {
		t.Fatalf("expected populated snapshot metadata, got %#v", snapshot.Metadata)
	}
	if snapshot.Metadata.BookCount != 1 || snapshot.Metadata.OrderCount != 4 {
		t.Fatalf("unexpected snapshot metadata counts: %#v", snapshot.Metadata)
	}
	if !reflect.DeepEqual(snapshot.Metadata.BookKeys, []string{"AAPL"}) {
		t.Fatalf("unexpected snapshot book keys: %#v", snapshot.Metadata.BookKeys)
	}
	restored, ok := Restore(snapshot)
	if !ok {
		t.Fatal("expected service restore to succeed")
	}
	if restored.Snapshot().Checksum != snapshot.Checksum {
		t.Fatalf("expected restored checksum %s, got %s", snapshot.Checksum, restored.Snapshot().Checksum)
	}

	tail := domain.SubmitOrder{
		OrderID:        "ord-sell-tail",
		VenueSessionID: "session-1",
		InstrumentID:   "AAPL",
		Side:           domain.SideSell,
		QuantityUnits:  "120",
		LimitPrice:     "149800000000",
		Currency:       "USD",
		OccurredAt:     "2026-03-14T18:00:02Z",
	}
	uninterruptedResult := service.SubmitOrder(tail)
	restoredResult := restored.SubmitOrder(tail)
	if !reflect.DeepEqual(restoredResult, uninterruptedResult) {
		t.Fatalf("restored replay result drifted: got %#v want %#v", restoredResult, uninterruptedResult)
	}
	if restored.Snapshot().Checksum != service.Snapshot().Checksum {
		t.Fatalf("restored replay checksum drifted: got %s want %s", restored.Snapshot().Checksum, service.Snapshot().Checksum)
	}
}

func TestServiceRestoreRejectsSnapshotChecksumMismatch(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")

	snapshot := service.Snapshot()
	snapshot.Orders[0].RemainingQuantity = 99

	if _, ok := Restore(snapshot); ok {
		t.Fatal("expected tampered service snapshot restore to fail")
	}
}

func TestServiceRestoreRejectsSnapshotMetadataMismatch(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")

	snapshot := service.Snapshot()
	snapshot.Metadata.BookCount = 2
	snapshot.Checksum = serviceSnapshotChecksum(snapshot.withoutChecksum())

	if _, ok := Restore(snapshot); ok {
		t.Fatal("expected metadata mismatch restore to fail")
	}
}

func TestBookStatsExposeOrderLevelCountsAndChecksum(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-3", "100", "150100000000")
	submitRestingSell(t, service, "AAPL", "ord-sell-1", "100", "150500000000")

	stats := service.BookStats("AAPL")
	if stats.InstrumentID != "AAPL" {
		t.Fatalf("expected AAPL stats, got %#v", stats)
	}
	if stats.BuyOrders != 3 || stats.SellOrders != 1 {
		t.Fatalf("unexpected order counts: %#v", stats)
	}
	if stats.BuyPriceLevels != 2 || stats.SellPriceLevels != 1 {
		t.Fatalf("unexpected price-level counts: %#v", stats)
	}
	if stats.Checksum == "" {
		t.Fatal("expected book stats checksum")
	}
}

func TestGoldenReplayBasicLifecycleCorpus(t *testing.T) {
	service := NewService()

	submitSell1 := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "gold-sell-1",
		VenueSessionID: "gold-session",
		InstrumentID:   "AAPL",
		ParticipantID:  "participant-a",
		AccountID:      "account-a",
		Side:           domain.SideSell,
		QuantityUnits:  "100",
		LimitPrice:     "150100000000",
		Currency:       "USD",
		OccurredAt:     "2026-03-14T18:00:00Z",
	})
	if submitSell1.Accepted == nil || len(submitSell1.Trades) != 0 {
		t.Fatalf("expected first sell to rest, got %#v", submitSell1)
	}

	submitSell2 := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "gold-sell-2",
		VenueSessionID: "gold-session",
		InstrumentID:   "AAPL",
		ParticipantID:  "participant-b",
		AccountID:      "account-b",
		Side:           domain.SideSell,
		QuantityUnits:  "80",
		LimitPrice:     "150000000000",
		Currency:       "USD",
		OccurredAt:     "2026-03-14T18:00:01Z",
	})
	if submitSell2.Accepted == nil || len(submitSell2.Trades) != 0 {
		t.Fatalf("expected second sell to rest, got %#v", submitSell2)
	}

	submitBuy := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "gold-buy-1",
		VenueSessionID: "gold-session",
		InstrumentID:   "AAPL",
		ParticipantID:  "participant-c",
		AccountID:      "account-c",
		Side:           domain.SideBuy,
		QuantityUnits:  "150",
		LimitPrice:     "150200000000",
		Currency:       "USD",
		OccurredAt:     "2026-03-14T18:00:02Z",
	})
	if len(submitBuy.Trades) != 2 {
		t.Fatalf("expected buy to sweep two sell orders, got %#v", submitBuy.Trades)
	}
	if submitBuy.Trades[0].SellOrderID != "gold-sell-2" || submitBuy.Trades[0].QuantityUnits != "80" || submitBuy.Trades[0].Price != "150000000000" {
		t.Fatalf("unexpected first golden trade: %#v", submitBuy.Trades[0])
	}
	if submitBuy.Trades[1].SellOrderID != "gold-sell-1" || submitBuy.Trades[1].QuantityUnits != "70" || submitBuy.Trades[1].Price != "150100000000" {
		t.Fatalf("unexpected second golden trade: %#v", submitBuy.Trades[1])
	}

	modifyResidualSell := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "gold-sell-1",
		QuantityUnits: "90",
		LimitPrice:    "150100000000",
		OccurredAt:    "2026-03-14T18:00:03Z",
	})
	if modifyResidualSell.Accepted == nil {
		t.Fatalf("expected residual sell modify to accept, got %#v", modifyResidualSell)
	}

	cancelResidualSell := service.CancelOrder(domain.CancelOrder{
		OrderID:    "gold-sell-1",
		OccurredAt: "2026-03-14T18:00:04Z",
	})
	if cancelResidualSell.Accepted == nil {
		t.Fatalf("expected residual sell cancel to accept, got %#v", cancelResidualSell)
	}

	stats := service.BookStats("AAPL")
	if stats.BuyOrders != 0 || stats.SellOrders != 0 || stats.BuyPriceLevels != 0 || stats.SellPriceLevels != 0 {
		t.Fatalf("expected golden corpus to end with empty book, got %#v", stats)
	}
	buyState, ok := service.OrderState("gold-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled || buyState.RemainingQuantity != "0" {
		t.Fatalf("unexpected golden buy state: %#v", buyState)
	}
	sellState, ok := service.OrderState("gold-sell-1")
	if !ok || sellState.Status != domain.OrderStatusCancelled || sellState.RemainingQuantity != "0" {
		t.Fatalf("unexpected golden residual sell state: %#v", sellState)
	}
}

func TestGoldenReplayFixtureBasicLifecycle(t *testing.T) {
	service := NewService()
	runGoldenFixture(t, service, "testdata/golden_basic_lifecycle.ndjson")

	stats := service.BookStats("AAPL")
	if stats.BuyOrders != 0 || stats.SellOrders != 0 || stats.BuyPriceLevels != 0 || stats.SellPriceLevels != 0 {
		t.Fatalf("expected golden fixture to end with empty book, got %#v", stats)
	}
	const wantChecksum = "aaa6376c4c84585c9c2799dba5728cbdd26f98fb426709bf75459b8330917932"
	if stats.Checksum != wantChecksum {
		t.Fatalf("unexpected golden fixture checksum: got %s want %s", stats.Checksum, wantChecksum)
	}
}

func TestCancelOrderRemovesMiddleSamePriceOrder(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-3", "100", "150250000000")

	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-2"})
	if cancel.Accepted == nil {
		t.Fatalf("expected accepted cancel result, got %#v", cancel)
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-sweep",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "250",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 2 {
		t.Fatalf("expected two trades after middle cancel, got %#v", match.Trades)
	}
	if match.Trades[0].BuyOrderID != "ord-buy-1" || match.Trades[1].BuyOrderID != "ord-buy-3" {
		t.Fatalf("cancelled order should not match and FIFO should remain, got %#v", match.Trades)
	}
}

func TestCancelOrderRemovesPartiallyFilledResidual(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "150", "150250000000")

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected partial fill trade, got %#v", match.Trades)
	}

	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-1"})
	if cancel.Accepted == nil {
		t.Fatalf("expected accepted cancel result, got %#v", cancel)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected partially filled residual to be removed from book")
	}

	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusCancelled || state.RemainingQuantity != "0" {
		t.Fatalf("expected cancelled residual state, got %#v", state)
	}
}

func TestCancelOrderRejectsFilledOrderWithoutChangingState(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected fill trade, got %#v", match.Trades)
	}

	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-1"})
	if cancel.Rejected == nil {
		t.Fatalf("expected cancel filled order to reject, got %#v", cancel)
	}
	if cancel.Rejected.Code != "INVALID_STATE" {
		t.Fatalf("expected invalid state rejection, got %#v", cancel.Rejected)
	}

	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusFilled || state.RemainingQuantity != "0" {
		t.Fatalf("expected filled state to remain unchanged, got %#v", state)
	}
}

func TestModifyOrderUpdatesPriceAndQuantity(t *testing.T) {
	service := NewService()
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-1",
		QuantityUnits: "120",
		LimitPrice:    "150300000000",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", result)
	}

	state, ok := service.OrderState("ord-1")
	if !ok {
		t.Fatalf("expected order state for ord-1")
	}
	if state.OriginalQuantity != "120" || state.RemainingQuantity != "120" || state.LimitPrice != "150300000000" {
		t.Fatalf("unexpected modified state: %#v", state)
	}
}

func TestModifyOrderMatchesWhenNewPriceCrossesBook(t *testing.T) {
	service := NewService()
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150300000000",
		Currency:      "USD",
	})
	service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})

	result := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "100",
		LimitPrice:    "150300000000",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", result)
	}
	if len(result.Trades) != 1 {
		t.Fatalf("expected marketable modify to trade, got %#v", result.Trades)
	}
	if result.Trades[0].BuyOrderID != "ord-buy-1" || result.Trades[0].SellOrderID != "ord-sell-1" {
		t.Fatalf("unexpected trade parties after marketable modify: %#v", result.Trades[0])
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 || service.RestingOrders("AAPL", domain.SideSell) != 0 {
		t.Fatalf("expected crossed orders to leave no resting liquidity")
	}
	buyState, ok := service.OrderState("ord-buy-1")
	if !ok || buyState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected modified buy filled, got %#v", buyState)
	}
	sellState, ok := service.OrderState("ord-sell-1")
	if !ok || sellState.Status != domain.OrderStatusFilled {
		t.Fatalf("expected resting sell filled, got %#v", sellState)
	}
}

func TestModifyOrderResetsPriorityWhenQuantityIncreases(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")

	modified := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "120",
		LimitPrice:    "150250000000",
	})
	if modified.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", modified)
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", match.Trades)
	}
	if match.Trades[0].BuyOrderID != "ord-buy-2" {
		t.Fatalf("quantity increase should reset same-price priority behind existing order, got %#v", match.Trades[0])
	}
}

func TestModifyOrderPreservesPriorityWhenQuantityDecreases(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "120", "150250000000")
	submitRestingBuy(t, service, "ord-buy-2", "100", "150250000000")

	modified := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
	})
	if modified.Accepted == nil {
		t.Fatalf("expected accepted modify result, got %#v", modified)
	}

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected one trade, got %#v", match.Trades)
	}
	if match.Trades[0].BuyOrderID != "ord-buy-1" {
		t.Fatalf("quantity decrease should preserve same-price priority, got %#v", match.Trades[0])
	}
}

func TestModifyOrderRejectsQuantityAtOrBelowAlreadyFilled(t *testing.T) {
	service := NewService()
	submitRestingBuy(t, service, "ord-buy-1", "150", "150250000000")

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected partial fill trade, got %#v", match.Trades)
	}

	result := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
	})
	if result.Rejected == nil {
		t.Fatalf("expected modify below filled quantity to reject, got %#v", result)
	}
	if result.Rejected.Code != "VALIDATION_ERROR" {
		t.Fatalf("expected validation error, got %#v", result.Rejected)
	}

	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusPartiallyFilled || state.RemainingQuantity != "50" {
		t.Fatalf("expected partially filled state to remain unchanged, got %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected residual buy liquidity to remain")
	}
}

func TestModifyOrderRejectsMarketIntegrityControlWithoutMutation(t *testing.T) {
	service := NewService(WithOrderControls(OrderControls{
		MaxQuantityUnits: 120,
		MaxNotional:      20_000_000_000_000,
		PriceCollars: map[string]PriceCollar{
			"AAPL": {ReferencePrice: 150_000_000_000, BandBps: 100},
		},
	}))
	submitRestingBuy(t, service, "ord-buy-1", "100", "150250000000")

	result := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "121",
		LimitPrice:    "150250000000",
	})
	if result.Rejected == nil {
		t.Fatalf("expected market integrity rejection, got %#v", result)
	}
	if result.Rejected.Code != "MARKET_INTEGRITY_CONTROL" {
		t.Fatalf("expected market integrity code, got %#v", result.Rejected)
	}

	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" || state.LimitPrice != "150250000000" {
		t.Fatalf("expected original order state to remain unchanged, got %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected original buy liquidity to remain")
	}
}

func TestModifyOrderRejectsSelfTradePreventionWithoutMutation(t *testing.T) {
	service := NewService()
	ownSell := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-sell-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideSell,
		QuantityUnits: "100",
		LimitPrice:    "150300000000",
		Currency:      "USD",
	})
	if ownSell.Accepted == nil {
		t.Fatalf("expected own sell to accept, got %#v", ownSell)
	}
	buy := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-own",
		InstrumentID:  "AAPL",
		ParticipantID: "participant-1",
		AccountID:     "account-1",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150000000000",
		Currency:      "USD",
	})
	if buy.Accepted == nil {
		t.Fatalf("expected non-crossing own buy to accept, got %#v", buy)
	}

	modify := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-own",
		QuantityUnits: "100",
		LimitPrice:    "150300000000",
	})
	if modify.Rejected == nil || modify.Rejected.Code != "SELF_TRADE_PREVENTION" {
		t.Fatalf("expected modify self-trade prevention reject, got %#v", modify)
	}
	state, ok := service.OrderState("ord-buy-own")
	if !ok || state.LimitPrice != "150000000000" || state.RemainingQuantity != "100" {
		t.Fatalf("expected rejected modify to preserve buy state, got %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 || service.RestingOrders("AAPL", domain.SideSell) != 1 {
		t.Fatalf("expected rejected modify to preserve both resting orders")
	}
}

func TestHaltedSessionRejectsModifyButAllowsCancel(t *testing.T) {
	states := map[string]SessionState{"session-1": SessionStateOpen}
	service := NewService(WithSessionControls(SessionControls{States: states}))
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "ord-buy-1",
		VenueSessionID: "session-1",
		InstrumentID:   "AAPL",
		Side:           domain.SideBuy,
		QuantityUnits:  "100",
		LimitPrice:     "150250000000",
		Currency:       "USD",
	})
	if result.Accepted == nil {
		t.Fatalf("expected submit in open session to accept, got %#v", result)
	}

	states["session-1"] = SessionStateHalted
	modify := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "90",
		LimitPrice:    "150250000000",
	})
	if modify.Rejected == nil {
		t.Fatalf("expected halted session modify to reject, got %#v", modify)
	}
	if modify.Rejected.Code != "SESSION_STATE_REJECT" {
		t.Fatalf("expected session-state reject code, got %#v", modify.Rejected)
	}

	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" {
		t.Fatalf("expected rejected modify to preserve order state, got %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected halted modify to preserve resting liquidity")
	}

	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-1"})
	if cancel.Accepted == nil {
		t.Fatalf("expected halted session cancel to accept, got %#v", cancel)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 0 {
		t.Fatalf("expected halted cancel to remove resting liquidity")
	}
}

func TestClosedSessionRejectsCancelWithoutMutation(t *testing.T) {
	states := map[string]SessionState{"session-1": SessionStateOpen}
	service := NewService(WithSessionControls(SessionControls{States: states}))
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:        "ord-buy-1",
		VenueSessionID: "session-1",
		InstrumentID:   "AAPL",
		Side:           domain.SideBuy,
		QuantityUnits:  "100",
		LimitPrice:     "150250000000",
		Currency:       "USD",
	})
	if result.Accepted == nil {
		t.Fatalf("expected submit in open session to accept, got %#v", result)
	}

	states["session-1"] = SessionStateClosed
	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-1"})
	if cancel.Rejected == nil {
		t.Fatalf("expected closed session cancel to reject, got %#v", cancel)
	}
	if cancel.Rejected.Code != "SESSION_STATE_REJECT" {
		t.Fatalf("expected session-state reject code, got %#v", cancel.Rejected)
	}
	state, ok := service.OrderState("ord-buy-1")
	if !ok || state.Status != domain.OrderStatusAccepted || state.RemainingQuantity != "100" {
		t.Fatalf("expected rejected cancel to preserve order state, got %#v", state)
	}
	if service.RestingOrders("AAPL", domain.SideBuy) != 1 {
		t.Fatalf("expected closed cancel reject to preserve resting liquidity")
	}
}

func TestModifyOrderRejectsTerminalOrderWithoutChangingState(t *testing.T) {
	service := NewService()
	submitRestingSell(t, service, "AAPL", "ord-sell-1", "100", "150250000000")

	match := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       "ord-buy-1",
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: "100",
		LimitPrice:    "150250000000",
		Currency:      "USD",
	})
	if len(match.Trades) != 1 {
		t.Fatalf("expected fill trade, got %#v", match.Trades)
	}

	modifyFilled := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-1",
		QuantityUnits: "200",
		LimitPrice:    "150500000000",
	})
	if modifyFilled.Rejected == nil {
		t.Fatalf("expected modify filled order to reject, got %#v", modifyFilled)
	}
	if modifyFilled.Rejected.Code != "INVALID_STATE" {
		t.Fatalf("expected invalid state rejection, got %#v", modifyFilled.Rejected)
	}

	submitRestingBuy(t, service, "ord-buy-cancelled", "100", "150000000000")
	cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-cancelled"})
	if cancel.Accepted == nil {
		t.Fatalf("expected cancel to accept, got %#v", cancel)
	}

	modifyCancelled := service.ModifyOrder(domain.ModifyOrder{
		OrderID:       "ord-buy-cancelled",
		QuantityUnits: "200",
		LimitPrice:    "150100000000",
	})
	if modifyCancelled.Rejected == nil {
		t.Fatalf("expected modify cancelled order to reject, got %#v", modifyCancelled)
	}
	if modifyCancelled.Rejected.Code != "INVALID_STATE" {
		t.Fatalf("expected invalid state rejection, got %#v", modifyCancelled.Rejected)
	}

	state, ok := service.OrderState("ord-buy-cancelled")
	if !ok || state.Status != domain.OrderStatusCancelled || state.RemainingQuantity != "0" {
		t.Fatalf("expected cancelled state to remain unchanged, got %#v", state)
	}
}

func TestLifecycleRejectMatrixDoesNotMutateState(t *testing.T) {
	tests := []struct {
		name          string
		setup         func(t *testing.T, service *Service) string
		act           func(service *Service, orderID string) domain.SubmitOrderResult
		wantCode      string
		wantStatus    domain.OrderStatus
		wantRemaining string
		wantBuyRest   int
		wantSellRest  int
	}{
		{
			name: "cancel unknown order",
			setup: func(t *testing.T, service *Service) string {
				return "ord-missing"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.CancelOrder(domain.CancelOrder{OrderID: orderID})
			},
			wantCode: "NOT_FOUND",
		},
		{
			name: "modify unknown order",
			setup: func(t *testing.T, service *Service) string {
				return "ord-missing"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.ModifyOrder(domain.ModifyOrder{OrderID: orderID, QuantityUnits: "100", LimitPrice: "150250000000"})
			},
			wantCode: "NOT_FOUND",
		},
		{
			name: "cancel filled order",
			setup: func(t *testing.T, service *Service) string {
				t.Helper()
				submitRestingBuy(t, service, "ord-buy-filled", "100", "150250000000")
				match := service.SubmitOrder(domain.SubmitOrder{
					OrderID:       "ord-sell-fill",
					InstrumentID:  "AAPL",
					Side:          domain.SideSell,
					QuantityUnits: "100",
					LimitPrice:    "150000000000",
					Currency:      "USD",
				})
				if len(match.Trades) != 1 {
					t.Fatalf("expected fill trade, got %#v", match.Trades)
				}
				return "ord-buy-filled"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.CancelOrder(domain.CancelOrder{OrderID: orderID})
			},
			wantCode:      "INVALID_STATE",
			wantStatus:    domain.OrderStatusFilled,
			wantRemaining: "0",
		},
		{
			name: "modify filled order",
			setup: func(t *testing.T, service *Service) string {
				t.Helper()
				submitRestingBuy(t, service, "ord-buy-filled", "100", "150250000000")
				match := service.SubmitOrder(domain.SubmitOrder{
					OrderID:       "ord-sell-fill",
					InstrumentID:  "AAPL",
					Side:          domain.SideSell,
					QuantityUnits: "100",
					LimitPrice:    "150000000000",
					Currency:      "USD",
				})
				if len(match.Trades) != 1 {
					t.Fatalf("expected fill trade, got %#v", match.Trades)
				}
				return "ord-buy-filled"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.ModifyOrder(domain.ModifyOrder{OrderID: orderID, QuantityUnits: "120", LimitPrice: "150250000000"})
			},
			wantCode:      "INVALID_STATE",
			wantStatus:    domain.OrderStatusFilled,
			wantRemaining: "0",
		},
		{
			name: "cancel already cancelled order",
			setup: func(t *testing.T, service *Service) string {
				t.Helper()
				submitRestingBuy(t, service, "ord-buy-cancelled", "100", "150250000000")
				cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-cancelled"})
				if cancel.Accepted == nil {
					t.Fatalf("expected cancel to accept, got %#v", cancel)
				}
				return "ord-buy-cancelled"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.CancelOrder(domain.CancelOrder{OrderID: orderID})
			},
			wantCode:      "INVALID_STATE",
			wantStatus:    domain.OrderStatusCancelled,
			wantRemaining: "0",
		},
		{
			name: "modify cancelled order",
			setup: func(t *testing.T, service *Service) string {
				t.Helper()
				submitRestingBuy(t, service, "ord-buy-cancelled", "100", "150250000000")
				cancel := service.CancelOrder(domain.CancelOrder{OrderID: "ord-buy-cancelled"})
				if cancel.Accepted == nil {
					t.Fatalf("expected cancel to accept, got %#v", cancel)
				}
				return "ord-buy-cancelled"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.ModifyOrder(domain.ModifyOrder{OrderID: orderID, QuantityUnits: "120", LimitPrice: "150250000000"})
			},
			wantCode:      "INVALID_STATE",
			wantStatus:    domain.OrderStatusCancelled,
			wantRemaining: "0",
		},
		{
			name: "modify quantity at already filled units",
			setup: func(t *testing.T, service *Service) string {
				t.Helper()
				submitRestingBuy(t, service, "ord-buy-partial", "150", "150250000000")
				match := service.SubmitOrder(domain.SubmitOrder{
					OrderID:       "ord-sell-partial",
					InstrumentID:  "AAPL",
					Side:          domain.SideSell,
					QuantityUnits: "100",
					LimitPrice:    "150000000000",
					Currency:      "USD",
				})
				if len(match.Trades) != 1 {
					t.Fatalf("expected partial fill trade, got %#v", match.Trades)
				}
				return "ord-buy-partial"
			},
			act: func(service *Service, orderID string) domain.SubmitOrderResult {
				return service.ModifyOrder(domain.ModifyOrder{OrderID: orderID, QuantityUnits: "100", LimitPrice: "150250000000"})
			},
			wantCode:      "VALIDATION_ERROR",
			wantStatus:    domain.OrderStatusPartiallyFilled,
			wantRemaining: "50",
			wantBuyRest:   1,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service := NewService()
			orderID := tt.setup(t, service)
			beforeBuyRest := service.RestingOrders("AAPL", domain.SideBuy)
			beforeSellRest := service.RestingOrders("AAPL", domain.SideSell)

			result := tt.act(service, orderID)
			if result.Rejected == nil {
				t.Fatalf("expected rejected lifecycle action, got %#v", result)
			}
			if result.Rejected.Code != tt.wantCode {
				t.Fatalf("expected reject code %s, got %#v", tt.wantCode, result.Rejected)
			}
			if service.RestingOrders("AAPL", domain.SideBuy) != beforeBuyRest || service.RestingOrders("AAPL", domain.SideSell) != beforeSellRest {
				t.Fatalf("rejected lifecycle action mutated book state")
			}
			if tt.wantStatus == "" {
				if _, ok := service.OrderState(orderID); ok {
					t.Fatalf("expected unknown order %s to remain absent", orderID)
				}
				return
			}
			state, ok := service.OrderState(orderID)
			if !ok {
				t.Fatalf("expected order state for %s", orderID)
			}
			if state.Status != tt.wantStatus || state.RemainingQuantity != tt.wantRemaining {
				t.Fatalf("rejected lifecycle action mutated order state: %#v", state)
			}
			if tt.wantBuyRest != 0 && service.RestingOrders("AAPL", domain.SideBuy) != tt.wantBuyRest {
				t.Fatalf("expected %d resting buys after reject", tt.wantBuyRest)
			}
			if tt.wantSellRest != 0 && service.RestingOrders("AAPL", domain.SideSell) != tt.wantSellRest {
				t.Fatalf("expected %d resting sells after reject", tt.wantSellRest)
			}
		})
	}
}

func TestParsePositiveInt(t *testing.T) {
	if _, ok := parsePositiveInt(""); ok {
		t.Fatal("expected empty input to fail")
	}
	if _, ok := parsePositiveInt("-2"); ok {
		t.Fatal("expected negative input to fail")
	}
	value, ok := parsePositiveInt("42")
	if !ok || value != 42 {
		t.Fatalf("expected parsed value 42, got value=%d ok=%v", value, ok)
	}
}

func TestEnvInt(t *testing.T) {
	name := "MATCHING_ENGINE_TEST_ENV_INT"
	os.Unsetenv(name)
	if got := envInt(name, 5); got != 5 {
		t.Errorf("envInt fallback = %d, want 5", got)
	}
	os.Setenv(name, "17")
	defer os.Unsetenv(name)
	if got := envInt(name, 5); got != 17 {
		t.Errorf("envInt parsed = %d, want 17", got)
	}
	os.Setenv(name, "-1")
	if got := envInt(name, 5); got != 5 {
		t.Errorf("envInt negative fallback = %d, want 5", got)
	}
	os.Setenv(name, "not-a-number")
	if got := envInt(name, 5); got != 5 {
		t.Errorf("envInt invalid fallback = %d, want 5", got)
	}
}

func TestMarketIntegrityHelpers(t *testing.T) {
	if !exceedsNotional(10, 11, 100) {
		t.Fatal("expected notional over max to be detected")
	}
	if exceedsNotional(10, 10, 100) {
		t.Fatal("expected notional at max to pass")
	}
	if !priceWithinCollar(101, PriceCollar{ReferencePrice: 100, BandBps: 100}) {
		t.Fatal("expected price inside one-percent collar")
	}
	if priceWithinCollar(102, PriceCollar{ReferencePrice: 100, BandBps: 100}) {
		t.Fatal("expected price outside one-percent collar")
	}
}

func submitRestingBuy(t *testing.T, service *Service, orderID string, quantity string, limitPrice string) {
	t.Helper()
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       orderID,
		InstrumentID:  "AAPL",
		Side:          domain.SideBuy,
		QuantityUnits: quantity,
		LimitPrice:    limitPrice,
		Currency:      "USD",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted resting buy %s, got %#v", orderID, result)
	}
	if len(result.Trades) != 0 {
		t.Fatalf("expected resting buy %s to avoid immediate trades, got %#v", orderID, result.Trades)
	}
}

func submitRestingSell(t *testing.T, service *Service, instrumentID string, orderID string, quantity string, limitPrice string) {
	t.Helper()
	result := service.SubmitOrder(domain.SubmitOrder{
		OrderID:       orderID,
		InstrumentID:  instrumentID,
		Side:          domain.SideSell,
		QuantityUnits: quantity,
		LimitPrice:    limitPrice,
		Currency:      "USD",
	})
	if result.Accepted == nil {
		t.Fatalf("expected accepted resting sell %s, got %#v", orderID, result)
	}
	if len(result.Trades) != 0 {
		t.Fatalf("expected resting sell %s to avoid immediate trades, got %#v", orderID, result.Trades)
	}
}

type goldenCommand struct {
	Type   string                   `json:"type"`
	Submit domain.SubmitOrder       `json:"submit"`
	Modify domain.ModifyOrder       `json:"modify"`
	Cancel domain.CancelOrder       `json:"cancel"`
	Want   goldenCommandExpectation `json:"want"`
}

type goldenCommandExpectation struct {
	Status            string   `json:"status"`
	Trades            int      `json:"trades"`
	RejectCode        string   `json:"rejectCode"`
	TradeSellOrderIDs []string `json:"tradeSellOrderIds"`
	TradeQuantities   []string `json:"tradeQuantities"`
	TradePrices       []string `json:"tradePrices"`
}

func runGoldenFixture(t *testing.T, service *Service, path string) {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("open golden fixture: %v", err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	line := 0
	for scanner.Scan() {
		line++
		var command goldenCommand
		if err := json.Unmarshal(scanner.Bytes(), &command); err != nil {
			t.Fatalf("decode golden fixture line %d: %v", line, err)
		}
		var result domain.SubmitOrderResult
		switch command.Type {
		case "SubmitOrder":
			result = service.SubmitOrder(command.Submit)
		case "ModifyOrder":
			result = service.ModifyOrder(command.Modify)
		case "CancelOrder":
			result = service.CancelOrder(command.Cancel)
		default:
			t.Fatalf("unsupported golden command type on line %d: %s", line, command.Type)
		}
		assertGoldenResult(t, line, result, command.Want)
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scan golden fixture: %v", err)
	}
}

func assertGoldenResult(t *testing.T, line int, result domain.SubmitOrderResult, want goldenCommandExpectation) {
	t.Helper()
	switch want.Status {
	case "accepted":
		if result.Accepted == nil {
			t.Fatalf("line %d expected accepted result, got %#v", line, result)
		}
	case "rejected":
		if result.Rejected == nil {
			t.Fatalf("line %d expected rejected result, got %#v", line, result)
		}
		if result.Rejected.Code != want.RejectCode {
			t.Fatalf("line %d expected reject code %s, got %#v", line, want.RejectCode, result.Rejected)
		}
	default:
		t.Fatalf("line %d unsupported expected status %q", line, want.Status)
	}
	if len(result.Trades) != want.Trades {
		t.Fatalf("line %d expected %d trades, got %#v", line, want.Trades, result.Trades)
	}
	for i, sellOrderID := range want.TradeSellOrderIDs {
		if result.Trades[i].SellOrderID != sellOrderID {
			t.Fatalf("line %d trade %d expected sell %s, got %#v", line, i, sellOrderID, result.Trades[i])
		}
	}
	for i, quantity := range want.TradeQuantities {
		if result.Trades[i].QuantityUnits != quantity {
			t.Fatalf("line %d trade %d expected quantity %s, got %#v", line, i, quantity, result.Trades[i])
		}
	}
	for i, price := range want.TradePrices {
		if result.Trades[i].Price != price {
			t.Fatalf("line %d trade %d expected price %s, got %#v", line, i, price, result.Trades[i])
		}
	}
}
