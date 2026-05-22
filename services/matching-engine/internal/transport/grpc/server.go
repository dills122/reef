package grpc

import (
	"context"
	"net"

	"google.golang.org/grpc"
)

type Server struct {
	grpcServer *grpc.Server
	listener   net.Listener
}

// NewServer creates a gRPC server scaffold. RPC registration is intentionally
// deferred until generated protobuf bindings are wired in.
func NewServer(addr string) (*Server, error) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}

	return &Server{
		grpcServer: grpc.NewServer(),
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

func (s *Server) WaitForShutdown(ctx context.Context) error {
	<-ctx.Done()
	s.Stop()
	return nil
}
