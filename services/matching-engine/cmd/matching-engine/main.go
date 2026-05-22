package main

import (
	"log"
	"net/http"
	"os"

	"github.com/dills122/reef/services/matching-engine/internal/app"
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

	if enableGRPC {
		grpcServer, err := grpcTransport.NewServer(grpcAddr)
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

	log.Printf("matching-engine listening on %s", addr)
	if err := http.ListenAndServe(addr, server.Routes()); err != nil {
		log.Fatal(err)
	}
}
