package main

import (
	"log"
	"net/http"
	"os"

	"github.com/dills122/reef/services/matching-engine/internal/app"
	transport "github.com/dills122/reef/services/matching-engine/internal/transport/http"
)

func main() {
	addr := os.Getenv("MATCHING_ENGINE_ADDR")
	if addr == "" {
		addr = ":8081"
	}

	service := app.NewService()
	server := transport.NewServer(service)

	log.Printf("matching-engine listening on %s", addr)
	if err := http.ListenAndServe(addr, server.Routes()); err != nil {
		log.Fatal(err)
	}
}
