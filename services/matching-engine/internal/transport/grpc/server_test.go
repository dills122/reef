package grpc

import (
	"context"
	"testing"
	"time"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	orderv1 "github.com/dills122/reef/services/matching-engine/internal/transport/grpc/pb/contracts/proto"
	gogrpc "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func TestHealthCheck(t *testing.T) {
	server, err := NewServer("127.0.0.1:0", app.NewService())
	if err != nil {
		t.Fatalf("failed to create server: %v", err)
	}

	go func() {
		_ = server.Start()
	}()
	defer server.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	conn, err := gogrpc.NewClient(
		server.Addr(),
		gogrpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		t.Fatalf("failed to connect grpc client: %v", err)
	}
	defer conn.Close()

	client := orderv1.NewOrderExecutionServiceClient(conn)
	response, err := client.HealthCheck(ctx, &orderv1.HealthCheckRequest{})
	if err != nil {
		t.Fatalf("healthcheck rpc failed: %v", err)
	}

	if response.GetStatus() != "ok" {
		t.Fatalf("expected status ok, got %s", response.GetStatus())
	}
}

func TestSubmitOrder(t *testing.T) {
	server, err := NewServer("127.0.0.1:0", app.NewService())
	if err != nil {
		t.Fatalf("failed to create server: %v", err)
	}

	go func() {
		_ = server.Start()
	}()
	defer server.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	conn, err := gogrpc.NewClient(
		server.Addr(),
		gogrpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		t.Fatalf("failed to connect grpc client: %v", err)
	}
	defer conn.Close()

	client := orderv1.NewOrderExecutionServiceClient(conn)
	response, err := client.SubmitOrder(ctx, &orderv1.SubmitOrder{
		Metadata: &orderv1.CommandMetadata{
			CommandId:     "cmd-1",
			CorrelationId: "corr-1",
			ActorId:       "trader-1",
			OccurredAt:    "2026-03-14T18:00:00Z",
		},
		OrderId:       "ord-1",
		InstrumentId:  "AAPL",
		ParticipantId: "participant-1",
		AccountId:     "account-1",
		Side:          orderv1.OrderSide_ORDER_SIDE_BUY,
		OrderType:     orderv1.OrderType_ORDER_TYPE_LIMIT,
		Quantity:      &orderv1.OrderQuantity{Units: "100"},
		LimitPrice:    &orderv1.Price{Nanos: "150250000000", Currency: "USD"},
		TimeInForce:   orderv1.TimeInForce_TIME_IN_FORCE_DAY,
	})
	if err != nil {
		t.Fatalf("submit rpc failed: %v", err)
	}

	if response.GetAccepted() == nil {
		t.Fatalf("expected accepted response, got %#v", response)
	}
}

func TestCommandMetadataMapsTraceAndCausation(t *testing.T) {
	submit := submitOrderFromProto(&orderv1.SubmitOrder{
		Metadata: &orderv1.CommandMetadata{
			CommandId:     "cmd-submit",
			TraceId:       "trace-submit",
			CausationId:   "cause-submit",
			CorrelationId: "corr-submit",
			ActorId:       "trader-1",
			OccurredAt:    "2026-03-14T18:00:00Z",
		},
		OrderId:       "ord-submit",
		InstrumentId:  "AAPL",
		ParticipantId: "participant-1",
		AccountId:     "account-1",
		Side:          orderv1.OrderSide_ORDER_SIDE_BUY,
		OrderType:     orderv1.OrderType_ORDER_TYPE_LIMIT,
		Quantity:      &orderv1.OrderQuantity{Units: "100"},
		LimitPrice:    &orderv1.Price{Nanos: "150250000000", Currency: "USD"},
		TimeInForce:   orderv1.TimeInForce_TIME_IN_FORCE_DAY,
	})
	if submit.TraceID != "trace-submit" || submit.CausationID != "cause-submit" {
		t.Fatalf("submit metadata not mapped: %#v", submit)
	}

	cancel := cancelOrderFromProto(&orderv1.CancelOrder{
		Metadata: &orderv1.CommandMetadata{
			CommandId:     "cmd-cancel",
			TraceId:       "trace-cancel",
			CausationId:   "cause-cancel",
			CorrelationId: "corr-cancel",
			ActorId:       "trader-1",
			OccurredAt:    "2026-03-14T18:00:00Z",
		},
		OrderId: "ord-cancel",
		Reason:  "user requested",
	})
	if cancel.TraceID != "trace-cancel" || cancel.CausationID != "cause-cancel" {
		t.Fatalf("cancel metadata not mapped: %#v", cancel)
	}

	modify := modifyOrderFromProto(&orderv1.ModifyOrder{
		Metadata: &orderv1.CommandMetadata{
			CommandId:     "cmd-modify",
			TraceId:       "trace-modify",
			CausationId:   "cause-modify",
			CorrelationId: "corr-modify",
			ActorId:       "trader-1",
			OccurredAt:    "2026-03-14T18:00:00Z",
		},
		OrderId:    "ord-modify",
		Quantity:   &orderv1.OrderQuantity{Units: "200"},
		LimitPrice: &orderv1.Price{Nanos: "150350000000", Currency: "USD"},
	})
	if modify.TraceID != "trace-modify" || modify.CausationID != "cause-modify" {
		t.Fatalf("modify metadata not mapped: %#v", modify)
	}
}
