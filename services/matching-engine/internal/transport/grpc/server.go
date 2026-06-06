package grpc

import (
	"context"
	"net"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
	orderv1 "github.com/dills122/reef/services/matching-engine/internal/transport/grpc/pb/contracts/proto"
	"google.golang.org/grpc"
)

type Server struct {
	grpcServer *grpc.Server
	listener   net.Listener
}

type orderExecutionRPCServer struct {
	orderv1.UnimplementedOrderExecutionServiceServer
	service *app.Service
}

func NewServer(addr string, service *app.Service) (*Server, error) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}

	grpcServer := grpc.NewServer()
	orderv1.RegisterOrderExecutionServiceServer(grpcServer, &orderExecutionRPCServer{
		service: service,
	})

	return &Server{
		grpcServer: grpcServer,
		listener:   listener,
	}, nil
}

func (s *Server) Start() error {
	return s.grpcServer.Serve(s.listener)
}

func (s *Server) Stop() {
	s.grpcServer.GracefulStop()
}

func (s *Server) Addr() string {
	return s.listener.Addr().String()
}

func (s *orderExecutionRPCServer) SubmitOrder(_ context.Context, req *orderv1.SubmitOrder) (*orderv1.SubmitOrderResult, error) {
	result := s.service.SubmitOrder(submitOrderFromProto(req))
	return toProtoResult(result), nil
}

func (s *orderExecutionRPCServer) CancelOrder(_ context.Context, req *orderv1.CancelOrder) (*orderv1.SubmitOrderResult, error) {
	result := s.service.CancelOrder(cancelOrderFromProto(req))
	return toProtoResult(result), nil
}

func (s *orderExecutionRPCServer) ModifyOrder(_ context.Context, req *orderv1.ModifyOrder) (*orderv1.SubmitOrderResult, error) {
	result := s.service.ModifyOrder(modifyOrderFromProto(req))
	return toProtoResult(result), nil
}

func (s *orderExecutionRPCServer) HealthCheck(_ context.Context, _ *orderv1.HealthCheckRequest) (*orderv1.HealthCheckResponse, error) {
	return &orderv1.HealthCheckResponse{
		Service: "matching-engine",
		Status:  "ok",
	}, nil
}

func toDomainSide(side orderv1.OrderSide) domain.Side {
	if side == orderv1.OrderSide_ORDER_SIDE_SELL {
		return domain.SideSell
	}
	return domain.SideBuy
}

func submitOrderFromProto(req *orderv1.SubmitOrder) domain.SubmitOrder {
	metadata := req.GetMetadata()
	return domain.SubmitOrder{
		CommandID:     metadata.GetCommandId(),
		TraceID:       metadata.GetTraceId(),
		CausationID:   metadata.GetCausationId(),
		CorrelationID: metadata.GetCorrelationId(),
		ActorID:       metadata.GetActorId(),
		OccurredAt:    metadata.GetOccurredAt(),
		OrderID:       req.GetOrderId(),
		InstrumentID:  req.GetInstrumentId(),
		ParticipantID: req.GetParticipantId(),
		AccountID:     req.GetAccountId(),
		Side:          toDomainSide(req.GetSide()),
		OrderType:     req.GetOrderType().String(),
		QuantityUnits: req.GetQuantity().GetUnits(),
		LimitPrice:    req.GetLimitPrice().GetNanos(),
		Currency:      req.GetLimitPrice().GetCurrency(),
		TimeInForce:   req.GetTimeInForce().String(),
	}
}

func cancelOrderFromProto(req *orderv1.CancelOrder) domain.CancelOrder {
	metadata := req.GetMetadata()
	return domain.CancelOrder{
		CommandID:     metadata.GetCommandId(),
		TraceID:       metadata.GetTraceId(),
		CausationID:   metadata.GetCausationId(),
		CorrelationID: metadata.GetCorrelationId(),
		ActorID:       metadata.GetActorId(),
		OccurredAt:    metadata.GetOccurredAt(),
		OrderID:       req.GetOrderId(),
		Reason:        req.GetReason(),
	}
}

func modifyOrderFromProto(req *orderv1.ModifyOrder) domain.ModifyOrder {
	metadata := req.GetMetadata()
	return domain.ModifyOrder{
		CommandID:     metadata.GetCommandId(),
		TraceID:       metadata.GetTraceId(),
		CausationID:   metadata.GetCausationId(),
		CorrelationID: metadata.GetCorrelationId(),
		ActorID:       metadata.GetActorId(),
		OccurredAt:    metadata.GetOccurredAt(),
		OrderID:       req.GetOrderId(),
		QuantityUnits: req.GetQuantity().GetUnits(),
		LimitPrice:    req.GetLimitPrice().GetNanos(),
	}
}

func toProtoResult(result domain.SubmitOrderResult) *orderv1.SubmitOrderResult {
	out := &orderv1.SubmitOrderResult{
		Executions: make([]*orderv1.ExecutionCreated, 0, len(result.Executions)),
		Trades:     make([]*orderv1.TradeCreated, 0, len(result.Trades)),
	}
	if result.Accepted != nil {
		out.Outcome = &orderv1.SubmitOrderResult_Accepted{
			Accepted: &orderv1.OrderAccepted{
				EventId:       result.Accepted.EventID,
				OrderId:       result.Accepted.OrderID,
				EngineOrderId: result.Accepted.EngineOrderID,
				OccurredAt:    result.Accepted.OccurredAt,
			},
		}
	}
	if result.Rejected != nil {
		out.Outcome = &orderv1.SubmitOrderResult_Rejected{
			Rejected: &orderv1.OrderRejected{
				EventId:    result.Rejected.EventID,
				OrderId:    result.Rejected.OrderID,
				Code:       result.Rejected.Code,
				Reason:     result.Rejected.Reason,
				OccurredAt: result.Rejected.OccurredAt,
			},
		}
	}
	for _, execution := range result.Executions {
		out.Executions = append(out.Executions, &orderv1.ExecutionCreated{
			EventId:      execution.EventID,
			ExecutionId:  execution.ExecutionID,
			OrderId:      execution.OrderID,
			InstrumentId: execution.InstrumentID,
			Quantity: &orderv1.OrderQuantity{
				Units: execution.QuantityUnits,
			},
			ExecutionPrice: &orderv1.Price{
				Nanos:    execution.ExecutionPrice,
				Currency: execution.Currency,
			},
			OccurredAt: execution.OccurredAt,
		})
	}
	for _, trade := range result.Trades {
		out.Trades = append(out.Trades, &orderv1.TradeCreated{
			EventId:      trade.EventID,
			TradeId:      trade.TradeID,
			ExecutionId:  trade.ExecutionID,
			BuyOrderId:   trade.BuyOrderID,
			SellOrderId:  trade.SellOrderID,
			InstrumentId: trade.InstrumentID,
			Quantity: &orderv1.OrderQuantity{
				Units: trade.QuantityUnits,
			},
			Price: &orderv1.Price{
				Nanos:    trade.Price,
				Currency: trade.Currency,
			},
			OccurredAt: trade.OccurredAt,
		})
	}
	return out
}
