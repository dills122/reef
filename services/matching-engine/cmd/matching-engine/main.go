package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

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
	streamConfig := streamdirect.RuntimeConfigFromEnv()
	if err := validateFiniteListeners(streamConfig, enableGRPC); err != nil {
		log.Fatal(err)
	}

	quotes, err := app.InstrumentQuoteCurrenciesFromJSON(os.Getenv("MATCHING_ENGINE_INSTRUMENT_QUOTES"))
	if err != nil {
		log.Fatalf("invalid matching instrument quotes: %v", err)
	}
	sourceProfile, err := app.CalcifySourceProfileFromJSON(os.Getenv("MATCHING_ENGINE_CALCIFY_SOURCE_PROFILE"), os.Getenv("MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT"))
	if err != nil {
		log.Fatalf("invalid Calcify source profile: %v", err)
	}
	options := []app.Option{quotes, sourceProfile}
	if streamConfig.CalcifyLifecycleEnabled {
		options = append(options, app.WithCalcifyFiniteBindingDigest(streamConfig.CalcifyFiniteBindingDigest))
	}
	service := app.NewService(options...)
	if err := service.ValidateCalcifySourceProfile(); err != nil {
		log.Fatalf("invalid Calcify source profile: %v", err)
	}
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

	var finiteReadiness http.Handler
	if streamConfig.Enabled {
		runner, err := streamdirect.StartRunner(ctx, service, streamConfig)
		if err != nil {
			log.Fatalf("failed to start matching-engine direct stream runner: %v", err)
		}
		defer runner.Stop()
		finiteReadiness = runner.FiniteReadinessHandler()
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
	handler := server.Routes()
	if streamConfig.CalcifyLifecycleEnabled {
		if finiteReadiness == nil {
			log.Fatal("finite startup lacks verified readiness")
		}
		handler = server.FiniteRoutes(finiteReadiness)
	}
	httpServer := &http.Server{Addr: addr, Handler: handler}
	if streamConfig.CalcifyLifecycleEnabled {
		httpServer.ReadHeaderTimeout = 5 * time.Second
	}
	go func() {
		<-ctx.Done()
		_ = httpServer.Shutdown(context.Background())
	}()
	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func validateFiniteListeners(config streamdirect.RuntimeConfig, enableGRPC bool) error {
	if !config.CalcifyLifecycleEnabled {
		return nil
	}
	if !config.Enabled {
		return fmt.Errorf("finite lifecycle requires direct stream runner before listeners")
	}
	if enableGRPC {
		return fmt.Errorf("finite matching refuses direct gRPC mutations")
	}
	return nil
}
