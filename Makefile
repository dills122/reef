GO_MATCHING_ENGINE_DIR := services/matching-engine
PLATFORM_RUNTIME_DIR := services/platform-runtime

.PHONY: test test-go test-platform-runtime fmt-go

test: test-go test-platform-runtime

test-go:
	cd $(GO_MATCHING_ENGINE_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

test-platform-runtime:
	cd $(PLATFORM_RUNTIME_DIR) && GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test

fmt-go:
	cd $(GO_MATCHING_ENGINE_DIR) && gofmt -w ./cmd/matching-engine/main.go ./internal/app/service.go ./internal/app/service_test.go ./internal/domain/order.go ./internal/transport/http/server.go ./internal/transport/http/server_test.go
