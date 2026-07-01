GO_MATCHING_ENGINE_DIR := services/matching-engine
PLATFORM_RUNTIME_DIR := services/platform-runtime
SIMULATOR_DIR := services/simulator

DEV_COMPOSE_PROFILES ?=
JS_RUNTIME ?= bun
CMD ?=
ARGS ?=

.PHONY: test test-go test-platform-runtime test-simulator fmt-go check-proto-additive bench-matching-engine bench-matching-engine-check bench-platform-runtime-check dev-up dev-down dev-reset dev-db-migrate dev-smoke dev-stress dev-stress-diagnostics dev-intake-bench dev-admin dev-sim dev-replay dev-throughput-campaign dev-throughput-compare

test: test-go test-simulator test-platform-runtime

check-proto-additive:
	./scripts/check-proto-additive.sh

test-go:
	cd $(GO_MATCHING_ENGINE_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

test-simulator:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

bench-matching-engine:
	cd $(GO_MATCHING_ENGINE_DIR) && go test -run '^$$' -bench 'BenchmarkSubmitOrderResting|BenchmarkSubmitOrderMatchAgainstResting|BenchmarkModifyOrder' -benchmem ./internal/app

bench-matching-engine-check:
	./scripts/ci/check-matching-bench.sh

bench-platform-runtime-check:
	./scripts/ci/check-runtime-bench.sh

test-platform-runtime:
	cd $(PLATFORM_RUNTIME_DIR) && GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test

fmt-go:
	cd $(GO_MATCHING_ENGINE_DIR) && gofmt -w ./cmd/matching-engine/main.go ./internal/app/service.go ./internal/app/service_test.go ./internal/domain/order.go ./internal/transport/http/server.go ./internal/transport/http/server_test.go

check-js-runtime:
	@command -v $(JS_RUNTIME) >/dev/null 2>&1 || (echo "missing JS runtime: $(JS_RUNTIME). install bun (preferred) or run with JS_RUNTIME=node." && exit 1)

dev-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/up.mjs

dev-down:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/down.mjs

dev-reset:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reset.mjs

dev-db-migrate:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/db/migrate.mjs

dev-smoke:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/smoke.mjs

dev-stress:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/stress.mjs

dev-stress-diagnostics:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1 $(JS_RUNTIME) scripts/dev/stress.mjs

dev-intake-bench:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/intake-bench.mjs

dev-admin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	@if [ -z "$(CMD)" ]; then echo 'usage: make dev-admin CMD="instrument-upsert AAPL AAPL"'; exit 1; fi
	$(JS_RUNTIME) scripts/dev/admin.mjs $(CMD)

dev-sim:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/sim-run.mjs $(ARGS)

dev-replay:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/replay-pack.mjs

dev-throughput-campaign:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/throughput-campaign.mjs

dev-throughput-compare:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/throughput-compare.mjs
