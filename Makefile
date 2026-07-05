GO_MATCHING_ENGINE_DIR := services/matching-engine
PLATFORM_RUNTIME_DIR := services/platform-runtime
SIMULATOR_DIR := services/simulator

DEV_COMPOSE_PROFILES ?=
JS_RUNTIME ?= bun
CMD ?=
ARGS ?=

.PHONY: test test-go test-platform-runtime test-simulator test-bot-sdk fmt-go check-proto-additive bench-matching-engine bench-matching-engine-load bench-matching-engine-check bench-platform-runtime-check dev-up dev-up-runtime-nodb dev-up-captured-ack dev-up-stream-ack dev-up-stream-direct-nodb dev-down dev-reset dev-db-migrate dev-smoke dev-smoke-protective-controls dev-smoke-venue-event-materializer dev-smoke-bot-sdk-live dev-smoke-bot-sdk-hosted-ses-container dev-venue-event-replay-check dev-stress dev-stress-runtime-nodb dev-stress-captured-ack dev-stress-stream-ack dev-stress-stream-direct-nodb dev-stress-diagnostics dev-intake-bench dev-command-log-prune dev-command-log-pin dev-admin dev-sim dev-replay dev-throughput-campaign dev-throughput-compare do-benchmark

test: test-go test-simulator test-platform-runtime test-bot-sdk

check-proto-additive:
	./scripts/check-proto-additive.sh

test-go:
	cd $(GO_MATCHING_ENGINE_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

test-simulator:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

bench-matching-engine:
	cd $(GO_MATCHING_ENGINE_DIR) && go test -run '^$$' -bench 'BenchmarkSubmitOrderResting|BenchmarkSubmitOrderMatchAgainstResting|BenchmarkModifyOrder' -benchmem ./internal/app

bench-matching-engine-load:
	cd $(GO_MATCHING_ENGINE_DIR) && GOCACHE=/tmp/reef-go-build-cache go run ./cmd/matching-engine-load $(ARGS)

bench-matching-engine-check:
	./scripts/ci/check-matching-bench.sh

bench-platform-runtime-check:
	./scripts/ci/check-runtime-bench.sh

test-platform-runtime:
	cd $(PLATFORM_RUNTIME_DIR) && GRADLE_USER_HOME=/tmp/reef-gradle ./gradlew test

test-bot-sdk:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	tsc -p packages/bot-sdk/tsconfig.json --noEmit
	$(JS_RUNTIME) scripts/dev/bot-sdk-contract.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-batch-clients.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-venue-adapter.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-venue-client.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-strategy-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-ses-e2e.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-artifact-build.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-worker.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-sandbox-policy.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-preflight.test.mjs
	node --check scripts/dev/bot-sdk-live-smoke.mjs
	node --check scripts/dev/bot-sdk-hosted-run.mjs
	node --check scripts/dev/bot-sdk-build-hosted-artifact.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-run.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-child.mjs
	node --check scripts/dev/bot-sdk-hosted-ses-container-smoke.mjs

fmt-go:
	cd $(GO_MATCHING_ENGINE_DIR) && gofmt -w ./cmd ./internal

check-js-runtime:
	@command -v $(JS_RUNTIME) >/dev/null 2>&1 || (echo "missing JS runtime: $(JS_RUNTIME). install bun (preferred) or run with JS_RUNTIME=node." && exit 1)

dev-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/up.mjs

dev-up-runtime-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/runtime-nodb-up.mjs

dev-up-captured-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/captured-ack-up.mjs

dev-up-stream-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/stream-ack-up.mjs

dev-up-stream-direct-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/stream-direct-nodb-up.mjs

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

dev-smoke-protective-controls:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/protective-controls-smoke.mjs

dev-smoke-venue-event-materializer:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/venue-event-materializer-smoke.mjs

dev-smoke-bot-sdk-live:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	@if [ -z "$(BOT)" ]; then echo 'usage: make dev-smoke-bot-sdk-live BOT=packages/bot-sdk/examples/simple-market-maker.ts [FIXTURE=packages/bot-sdk/fixtures/aapl-multi-tick.json] [VENUE_URL=http://127.0.0.1:8080] [ARGS=--seed-reference]'; exit 1; fi
	$(JS_RUNTIME) scripts/dev/bot-sdk-live-smoke.mjs $(BOT) $(or $(FIXTURE),packages/bot-sdk/fixtures/aapl-multi-tick.json) --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) $(ARGS)

dev-smoke-bot-sdk-hosted-ses-container:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-ses-container-smoke.mjs

dev-venue-event-replay-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/venue-event-replay-check.mjs

dev-stress:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/stress.mjs

dev-stress-runtime-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/runtime-nodb-stress.mjs

dev-stress-captured-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/captured-ack-stress.mjs

dev-stress-stream-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/stream-ack-stress.mjs

dev-stress-stream-direct-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/stream-direct-nodb-stress.mjs

dev-stress-diagnostics:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1 $(JS_RUNTIME) scripts/dev/stress.mjs

dev-intake-bench:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/intake-bench.mjs

dev-command-log-prune:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-prune.mjs

dev-command-log-pin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-pin.mjs

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

do-benchmark:
	./scripts/dev/do-benchmark-host.sh $(ARGS)
