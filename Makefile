GO_MATCHING_ENGINE_DIR := services/matching-engine
PLATFORM_RUNTIME_DIR := services/platform-runtime
SIMULATOR_DIR := services/simulator

DEV_COMPOSE_PROFILES ?=
JS_RUNTIME ?= bun
CMD ?=
ARGS ?=
SCENARIO ?= ../../packages/scenario-definitions/scenarios/v1/P1_GOLDEN_HIDDEN_CROSS_T1.yaml
SCENARIO_RUN_ID ?= p1-golden-hidden-cross-local
SCENARIO_START ?= 2026-03-14T18:00:00Z

.PHONY: test lint check-scripts check-js-runtime
.PHONY: test-go test-platform-runtime test-simulator test-simulator-go test-bot-sdk fmt-go check-proto-additive
.PHONY: bench-matching-engine bench-matching-engine-load bench-matching-engine-check bench-platform-runtime-check
.PHONY: dev-up dev-up-runtime-nodb dev-up-captured-ack dev-up-stream-ack dev-up-stream-direct-nodb
.PHONY: dev-compose-config dev-compose-parity dev-validate-stream-profile dev-down dev-reset dev-db-migrate
.PHONY: dev-smoke dev-smoke-protective-controls dev-smoke-arena-bot-risk dev-smoke-arena-run-results
.PHONY: dev-smoke-bot-arena-local dev-smoke-bot-arena-local-persist dev-smoke-bot-arena-local-negative
.PHONY: dev-render-bot-arena-report dev-render-bot-arena-report-index
.PHONY: dev-smoke-venue-event-materializer dev-smoke-venue-event-crash-gate dev-smoke-projection-proof
.PHONY: dev-smoke-bot-sdk-live dev-smoke-bot-sdk-hosted-ses-container dev-venue-event-replay-check
.PHONY: dev-read-surface-availability-check dev-gate-local-durable
.PHONY: dev-stress dev-stress-runtime-nodb dev-stress-captured-ack dev-stress-stream-ack dev-stress-stream-direct-nodb
.PHONY: dev-stress-diagnostics dev-export-simulation-run dev-intake-bench
.PHONY: dev-command-log-integrity-check dev-command-log-archive dev-command-log-prune dev-command-log-pin dev-admin
.PHONY: dev-seed-p2-settlement-facts dev-sim dev-sim-batch
.PHONY: dev-scenario-plan dev-scenario-smoke dev-scenario-golden-check dev-scenario-drift-check dev-replay
.PHONY: dev-throughput-campaign dev-throughput-compare
.PHONY: kube-up kube-apply kube-reset kube-down kube-status kube-smoke kube-materializer-up
.PHONY: kube-smoke-venue-event-materializer kube-port-forward
.PHONY: do-benchmark do-materializer-10k-gate simulation-run docs-site-dev docs-site-build hetzner-core hetzner-core-tofu

test: test-go test-simulator test-platform-runtime test-bot-sdk

lint:
	cd $(GO_MATCHING_ENGINE_DIR) && go vet ./...
	cd $(SIMULATOR_DIR) && go vet ./...
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew compileKotlin compileTestKotlin
	bunx tsc -p packages/bot-sdk/tsconfig.json --noEmit
	node scripts/dev/script-surface-check.mjs

check-scripts:
	node scripts/dev/script-surface-check.mjs

check-proto-additive:
	./scripts/check-proto-additive.sh

test-go:
	cd $(GO_MATCHING_ENGINE_DIR) && GOCACHE=/tmp/reef-go-build-cache go test ./...

test-simulator: test-simulator-go dev-scenario-golden-check

test-simulator-go:
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
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew test

test-bot-sdk:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	bunx tsc -p packages/bot-sdk/tsconfig.json --noEmit
	$(JS_RUNTIME) scripts/dev/bot-sdk-contract.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-batch-clients.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-venue-adapter.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-venue-client.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-live-client.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-strategy-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-runner.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-ses-e2e.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-artifact-build.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-worker.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-sandbox-policy.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-preflight.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-runtime-config.test.mjs
	$(JS_RUNTIME) scripts/dev/openbao-runtime-config.test.mjs
	$(JS_RUNTIME) scripts/dev/bot-sdk-test-bot.test.mjs
	$(JS_RUNTIME) scripts/dev/arena-ingest-bot-run-result.test.mjs
	$(JS_RUNTIME) scripts/dev/arena-persist-report-local.test.mjs
	$(JS_RUNTIME) scripts/dev/arena-render-report-index.test.mjs
	$(JS_RUNTIME) scripts/dev/arena-render-report.test.mjs
	node --check scripts/dev/bot-sdk-live-smoke.mjs
	node --check scripts/dev/bot-sdk-hosted-run.mjs
	node --check scripts/dev/bot-sdk-build-hosted-artifact.mjs
	node --check scripts/dev/bot-sdk-test-bot.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-run.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-child.mjs
	node --check scripts/dev/bot-sdk-hosted-ses-container-smoke.mjs
	node --check scripts/dev/arena-ingest-bot-run-result.mjs
	node --check scripts/dev/arena-persist-report-local.mjs
	node --check scripts/dev/arena-render-report-index.mjs
	node --check scripts/dev/arena-render-report.mjs
	node --check scripts/dev/arena-run-result-ingestion-smoke.mjs
	node --check scripts/dev/arena-bot-risk-smoke.mjs
	node scripts/dev/report-taxonomy.test.mjs
	node scripts/dev/stream-partition-spread.test.mjs
	node scripts/dev/do-benchmark-check.test.mjs
	node scripts/dev/do-materializer-10k-gate.test.mjs
	node scripts/dev/scenario-drift.test.mjs
	node scripts/dev/scenario-golden.test.mjs

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

dev-compose-config:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/compose-config.mjs $(ARGS)

dev-compose-parity:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/compose-config-parity.mjs

dev-validate-stream-profile:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/stream-profile-validate.mjs $(PROFILE)

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

kube-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs up

kube-apply:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs apply

kube-reset:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs reset

kube-down:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs down

kube-status:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs status

kube-smoke:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs smoke

kube-materializer-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs materializer-up

kube-smoke-venue-event-materializer:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs materializer-smoke

kube-port-forward:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs port-forward

dev-smoke-protective-controls:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/protective-controls-smoke.mjs

dev-smoke-arena-bot-risk:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-bot-risk-smoke.mjs

dev-smoke-arena-run-results:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-run-result-ingestion-smoke.mjs

dev-smoke-bot-arena-local:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-local-tick-run.mjs --submit-mode=live --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --seed-reference --compartment=ses --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),30000) --require-projection-drain --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-live.json) $(ARGS)

dev-smoke-bot-arena-local-persist:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-local-tick-run.mjs --submit-mode=live --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --seed-reference --compartment=ses --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),30000) --require-projection-drain --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json) $(ARGS)
	$(JS_RUNTIME) scripts/dev/arena-persist-report-local.mjs --report=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json)

dev-smoke-bot-arena-local-negative:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-local-tick-run.mjs --submit-mode=live --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --seed-reference --compartment=ses --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),30000) --require-projection-drain --extra-bots=custom-too-many-orders --expect-freeze-bots=custom-too-many-orders --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json) $(ARGS)
	$(JS_RUNTIME) scripts/dev/arena-persist-report-local.mjs --report=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json)

dev-render-bot-arena-report:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-render-report.mjs --report=$(or $(REPORT),/tmp/reef-arena-local-tick-run-persist.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.html) $(ARGS)

dev-render-bot-arena-report-index:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-render-report-index.mjs --reports=$(or $(REPORTS),/tmp/reef-arena-local-tick-run-persist.json,/tmp/reef-arena-local-tick-run-negative.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-index.html) $(ARGS)

dev-smoke-venue-event-materializer:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/venue-event-materializer-smoke.mjs

dev-smoke-venue-event-crash-gate:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/venue-event-crash-gate.mjs

dev-smoke-projection-proof: dev-smoke-venue-event-materializer

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

dev-read-surface-availability-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/read-surface-availability-check.mjs

dev-gate-local-durable:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/local-durable-gate.mjs

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

dev-stream-publish-bench:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/stream-publish-bench.mjs

dev-stress-diagnostics:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_STRESS_CAPTURE_DB_DIAGNOSTICS=1 $(JS_RUNTIME) scripts/dev/stress.mjs

dev-export-simulation-run:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	@if [ -z "$(REPORT)" ]; then echo 'usage: make dev-export-simulation-run REPORT=reports/path/report.json [ARTIFACT_ROOT=reports/path] [ARGS="--post --api-url=http://127.0.0.1:8080"]'; exit 1; fi
	$(JS_RUNTIME) scripts/dev/export-simulation-run.mjs --report "$(REPORT)" $(if $(ARTIFACT_ROOT),--artifact-root "$(ARTIFACT_ROOT)") $(ARGS)

dev-stress-venue-event-materializer:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/venue-event-materializer-stress.mjs

dev-ablation-ladder:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/ablation-ladder.mjs

dev-intake-bench:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/intake-bench.mjs

dev-command-log-integrity-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-integrity-check.mjs

dev-command-log-prune:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-prune.mjs

dev-command-log-archive:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-archive.mjs

dev-command-log-pin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-pin.mjs

dev-admin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	@if [ -z "$(CMD)" ]; then echo 'usage: make dev-admin CMD="instrument-upsert AAPL AAPL"'; exit 1; fi
	$(JS_RUNTIME) scripts/dev/admin.mjs $(CMD)

dev-seed-p2-settlement-facts:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	SCENARIO_RUN_ID="$(SCENARIO_RUN_ID)" $(JS_RUNTIME) scripts/dev/seed-p2-settlement-facts.mjs $(ARGS)

dev-sim:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/sim-run.mjs $(ARGS)

dev-sim-batch:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/sim-batch.mjs $(ARGS)

dev-scenario-plan:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go run ./cmd/scenario-plan --scenario $(SCENARIO) --scenario-run-id $(SCENARIO_RUN_ID) --start $(SCENARIO_START) $(ARGS)

dev-scenario-smoke:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go run ./cmd/scenario-smoke --scenario $(SCENARIO) --scenario-run-id $(SCENARIO_RUN_ID) --start $(SCENARIO_START) $(ARGS)

dev-scenario-golden-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/scenario-golden-check.mjs $(ARGS)

docs-site-dev:
	cd apps/docs-site && bun install && bun run dev

docs-site-build:
	cd apps/docs-site && bun install && bun run build

dev-scenario-drift-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/scenario-drift-check.mjs $(ARGS)

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

do-materializer-10k-gate:
	./scripts/dev/do-materializer-10k-gate.sh $(or $(ARGS),plan)

simulation-run:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/simulation-run.mjs $(ARGS)

hetzner-core:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/hetzner-core.mjs $(ARGS)

hetzner-core-tofu:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/hetzner-core-tofu.mjs $(ARGS)
