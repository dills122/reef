package grpc

import (
	"context"
	"io"
	"net"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
	orderv1 "github.com/dills122/reef/services/matching-engine/internal/transport/grpc/pb/contracts/proto"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
)

type Server struct {
	grpcServer *grpc.Server
	listener   net.Listener
}

type orderExecutionRPCServer struct {
	orderv1.UnimplementedOrderExecutionServiceServer
	service *app.Service
}

type orderExecutionServiceServer interface {
	SubmitOrder(context.Context, *orderv1.SubmitOrder) (*orderv1.SubmitOrderResult, error)
	SubmitOrders(orderExecutionServiceSubmitOrdersServer) error
	CancelOrder(context.Context, *orderv1.CancelOrder) (*orderv1.SubmitOrderResult, error)
	ModifyOrder(context.Context, *orderv1.ModifyOrder) (*orderv1.SubmitOrderResult, error)
	HealthCheck(context.Context, *orderv1.HealthCheckRequest) (*orderv1.HealthCheckResponse, error)
}

type orderExecutionServiceSubmitOrdersServer interface {
	Send(*orderv1.SubmitOrderResult) error
	Recv() (*orderv1.SubmitOrder, error)
	grpc.ServerStream
}

func NewServer(addr string, service *app.Service) (*Server, error) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}

	grpcServer := grpc.NewServer()
	grpcServer.RegisterService(&orderExecutionServiceDesc, &orderExecutionRPCServer{
		service: service,
	})
	healthServer := health.NewServer()
	healthServer.SetServingStatus("", healthpb.HealthCheckResponse_SERVING)
	healthServer.SetServingStatus("reef.contracts.orderexecution.v1.OrderExecutionService", healthpb.HealthCheckResponse_SERVING)
	healthpb.RegisterHealthServer(grpcServer, healthServer)

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

func (s *orderExecutionRPCServer) SubmitOrders(stream orderExecutionServiceSubmitOrdersServer) error {
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}

		result := s.service.SubmitOrder(submitOrderFromProto(req))
		if err := stream.Send(toProtoResult(result)); err != nil {
			return err
		}
	}
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

func orderExecutionServiceSubmitOrderHandler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(orderv1.SubmitOrder)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(orderExecutionServiceServer).SubmitOrder(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/reef.contracts.orderexecution.v1.OrderExecutionService/SubmitOrder",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(orderExecutionServiceServer).SubmitOrder(ctx, req.(*orderv1.SubmitOrder))
	}
	return interceptor(ctx, in, info, handler)
}

func orderExecutionServiceCancelOrderHandler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(orderv1.CancelOrder)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(orderExecutionServiceServer).CancelOrder(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/reef.contracts.orderexecution.v1.OrderExecutionService/CancelOrder",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(orderExecutionServiceServer).CancelOrder(ctx, req.(*orderv1.CancelOrder))
	}
	return interceptor(ctx, in, info, handler)
}

func orderExecutionServiceModifyOrderHandler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(orderv1.ModifyOrder)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(orderExecutionServiceServer).ModifyOrder(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/reef.contracts.orderexecution.v1.OrderExecutionService/ModifyOrder",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(orderExecutionServiceServer).ModifyOrder(ctx, req.(*orderv1.ModifyOrder))
	}
	return interceptor(ctx, in, info, handler)
}

func orderExecutionServiceHealthCheckHandler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(orderv1.HealthCheckRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(orderExecutionServiceServer).HealthCheck(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/reef.contracts.orderexecution.v1.OrderExecutionService/HealthCheck",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(orderExecutionServiceServer).HealthCheck(ctx, req.(*orderv1.HealthCheckRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func orderExecutionServiceSubmitOrdersHandler(srv interface{}, stream grpc.ServerStream) error {
	return srv.(orderExecutionServiceServer).SubmitOrders(&submitOrdersServer{ServerStream: stream})
}

type submitOrdersServer struct {
	grpc.ServerStream
}

func (s *submitOrdersServer) Send(result *orderv1.SubmitOrderResult) error {
	return s.ServerStream.SendMsg(result)
}

func (s *submitOrdersServer) Recv() (*orderv1.SubmitOrder, error) {
	req := new(orderv1.SubmitOrder)
	if err := s.ServerStream.RecvMsg(req); err != nil {
		return nil, err
	}
	return req, nil
}

var orderExecutionServiceDesc = grpc.ServiceDesc{
	ServiceName: "reef.contracts.orderexecution.v1.OrderExecutionService",
	HandlerType: (*orderExecutionServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "SubmitOrder",
			Handler:    orderExecutionServiceSubmitOrderHandler,
		},
		{
			MethodName: "CancelOrder",
			Handler:    orderExecutionServiceCancelOrderHandler,
		},
		{
			MethodName: "ModifyOrder",
			Handler:    orderExecutionServiceModifyOrderHandler,
		},
		{
			MethodName: "HealthCheck",
			Handler:    orderExecutionServiceHealthCheckHandler,
		},
	},
	Streams: []grpc.StreamDesc{
		{
			StreamName:    "SubmitOrders",
			Handler:       orderExecutionServiceSubmitOrdersHandler,
			ServerStreams: true,
			ClientStreams: true,
		},
	},
	Metadata: "contracts/proto/order_execution.proto",
}
