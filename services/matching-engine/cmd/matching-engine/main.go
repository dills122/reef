package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	"github.com/dills122/reef/services/matching-engine/internal/streamdirect"
	grpcTransport "github.com/dills122/reef/services/matching-engine/internal/transport/grpc"
	transport "github.com/dills122/reef/services/matching-engine/internal/transport/http"
)

func main() {
	addr := os.Getenv("MATCHING_ENGINE_ADDR")
	if addr == "" {
		addr = ":8081"
	}
	grpcAddr := os.Getenv("MATCHING_ENGINE_GRPC_ADDR")
	if grpcAddr == "" {
		grpcAddr = ":9081"
	}
	enableGRPC := os.Getenv("MATCHING_ENGINE_ENABLE_GRPC") == "1"

	service := app.NewService()
	server := transport.NewServer(service)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if enableGRPC {
		grpcServer, err := grpcTransport.NewServer(grpcAddr, service)
		if err != nil {
			log.Fatalf("failed to initialize gRPC server: %v", err)
		}
		go func() {
			log.Printf("matching-engine gRPC scaffold listening on %s", grpcAddr)
			if err := grpcServer.Start(); err != nil {
				log.Fatalf("matching-engine gRPC scaffold failed: %v", err)
			}
		}()
	}

	streamConfig := streamdirect.RuntimeConfigFromEnv()
	if streamConfig.Enabled {
		runner, err := streamdirect.StartRunner(ctx, service, streamConfig)
		if err != nil {
			log.Fatalf("failed to start matching-engine direct stream runner: %v", err)
		}
		defer runner.Stop()
		server.SetStreamDirectStatsProvider(func() any {
			return runner.Snapshots()
		})
		log.Printf(
			"matching-engine direct stream enabled shard=%s commandStream=%s eventStream=%s partitions=%v batchSize=%d",
			streamConfig.ShardID,
			streamConfig.CommandStream,
			streamConfig.EventStream,
			streamConfig.Partitions,
			streamConfig.BatchSize,
		)
	}

	log.Printf("matching-engine listening on %s", addr)
	httpServer := &http.Server{Addr: addr, Handler: server.Routes()}
	go func() {
		<-ctx.Done()
		_ = httpServer.Shutdown(context.Background())
	}()
	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
