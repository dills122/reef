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

.PHONY: test test-dev-tooling lint check-scripts check-dependency-alignment check-reef-arena-boundaries check-js-runtime check-bun-runtime check-bot-sdk-js-deps
.PHONY: test-go test-platform-runtime test-reef-core build-reef-core build-arena-control-plane test-arena-control-plane test-simulator test-simulator-go test-bot-sdk fmt-go check-proto-additive
.PHONY: bench-matching-engine bench-matching-engine-load bench-matching-engine-check bench-platform-runtime-check
.PHONY: dev-up dev-up-reef dev-up-arena dev-up-runtime-nodb dev-up-captured-ack dev-up-stream-ack dev-up-stream-direct-nodb
.PHONY: dev-compose-config dev-validate-stream-profile dev-down dev-down-arena dev-reset dev-reset-arena dev-db-migrate dev-smoke-reef dev-smoke-arena
.PHONY: dev-smoke dev-smoke-protective-controls dev-smoke-arena-bot-risk dev-smoke-arena-run-results dev-smoke-arena-isolation
.PHONY: dev-smoke-bot-arena-local dev-smoke-bot-arena-local-persist dev-smoke-bot-arena-local-negative dev-hardening-bot-arena-local
.PHONY: dev-render-bot-arena-report dev-render-bot-arena-report-index
.PHONY: dev-smoke-venue-event-materializer dev-smoke-venue-event-crash-gate dev-smoke-projection-proof
.PHONY: dev-smoke-bot-sdk-live dev-smoke-bot-sdk-hosted-ses-container dev-smoke-bot-sdk-hosted-live-container dev-venue-event-replay-check
.PHONY: dev-read-surface-availability-check dev-gate-local-durable
.PHONY: dev-stress dev-stress-runtime-nodb dev-stress-accepted-async-jfr dev-stress-captured-ack dev-stress-stream-ack dev-stress-stream-direct-nodb
.PHONY: dev-stress-diagnostics dev-export-simulation-run dev-intake-bench
.PHONY: dev-command-log-integrity-check dev-command-log-archive dev-command-log-archive-partitions dev-command-log-prune dev-command-log-pin dev-admin dev-admin-auth-local-seed dev-admin-owned-bot-local-seed dev-smoke-admin-auth-local dev-control-room
.PHONY: dev-bootstrap dev-doctor dev-seed-p2-settlement-facts dev-sim dev-sim-batch
.PHONY: dev-scenario-plan dev-scenario-smoke dev-scenario-golden-check dev-scenario-drift-check dev-compare-reef-arena-separation dev-replay
.PHONY: dev-throughput-campaign dev-throughput-compare
.PHONY: kube-up kube-apply kube-reset kube-down kube-status kube-smoke kube-stream-ack-up kube-smoke-stream-ack kube-materializer-up
.PHONY: kube-materializer-scale kube-autoscale-apply kube-smoke-venue-event-materializer kube-port-forward
.PHONY: backbone-local-up backbone-local-up-infra backbone-local-init-openbao backbone-local-migrate backbone-local-verify backbone-local-status backbone-local-logs backbone-local-down
.PHONY: do-benchmark do-materializer-10k-gate do-materializer-scaling-gate do-projection-freshness-gate do-arena-pacing-lag-gate simulation-run docs-site-dev docs-site-build hetzner-core hetzner-core-tofu

test: test-go test-simulator test-platform-runtime test-bot-sdk test-dev-tooling

test-dev-tooling:
	node scripts/dev/doctor.test.mjs
	node scripts/dev/onboarding-links.test.mjs
	node scripts/dev/smoke-identity.test.mjs

lint:
	cd $(GO_MATCHING_ENGINE_DIR) && go vet ./...
	cd $(SIMULATOR_DIR) && go vet ./...
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew compileKotlin compileTestKotlin
	bunx --bun tsc -p packages/bot-sdk/tsconfig.json --noEmit
	node scripts/dev/script-surface-check.mjs
	node scripts/ci/check-dependency-alignment.mjs

check-scripts:
	node scripts/dev/script-surface-check.mjs

check-dependency-alignment:
	node scripts/ci/check-dependency-alignment.mjs

check-reef-arena-boundaries:
	node scripts/dev/reef-arena-boundary-check.mjs

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

build-reef-core:
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew clean jar
	@! jar tf $(PLATFORM_RUNTIME_DIR)/build/libs/platform-runtime.jar | grep -i 'arena' >/dev/null

test-reef-core:
	@$(MAKE) check-reef-arena-boundaries
	$(JS_RUNTIME) scripts/dev/compare-reef-arena-separation-reports.test.mjs
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew test

build-arena-control-plane: build-reef-core
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew -p ../arena-control-plane jar

test-arena-control-plane: build-arena-control-plane
	cd $(PLATFORM_RUNTIME_DIR) && ./gradlew -p ../arena-control-plane test jacocoTestCoverageVerification

test-bot-sdk:
	@$(MAKE) check-bun-runtime
	@$(MAKE) check-bot-sdk-js-deps
	bunx --bun tsc -p packages/bot-sdk/tsconfig.json --noEmit
	bun scripts/dev/bot-sdk-contract.test.mjs
	bun scripts/dev/bot-sdk-batch-clients.test.mjs
	bun scripts/dev/bot-sdk-venue-adapter.test.mjs
	bun scripts/dev/bot-sdk-venue-client.test.mjs
	bun scripts/dev/bot-sdk-live-client.test.mjs
	bun scripts/dev/bot-sdk-runner.test.mjs
	bun scripts/dev/bot-sdk-strategy-runner.test.mjs
	bun scripts/dev/bot-sdk-hosted-runner.test.mjs
	bun scripts/dev/bot-sdk-hosted-ses-e2e.test.mjs
	bun scripts/dev/bot-isolation.test.mjs
	bun scripts/dev/bot-sdk-hosted-artifact-build.test.mjs
	bun scripts/dev/bot-sdk-hosted-worker.test.mjs
	bun scripts/dev/bot-sdk-sandbox-policy.test.mjs
	bun scripts/dev/bot-sdk-preflight.test.mjs
	bun scripts/dev/bot-sdk-runtime-config.test.mjs
	bun scripts/dev/openbao-runtime-config.test.mjs
	bun scripts/dev/bot-submission-validate.test.mjs
	bun scripts/dev/bot-submission-commit-status.test.mjs
	bun scripts/dev/bot-submission-record-admission.test.mjs
	bun scripts/dev/bot-submission-invite-workflow.test.mjs
	bun scripts/dev/bot-submission-pr-comment.test.mjs
	bun scripts/dev/arena-admin-access-normalize.test.mjs
	bun scripts/dev/dependabot-automation-workflow.test.mjs
	bun scripts/dev/bot-submission-provision-openbao.test.mjs
	bun scripts/dev/bot-sdk-test-bot.test.mjs
	bun scripts/dev/arena-ingest-bot-run-result.test.mjs
	bun scripts/dev/arena-persist-report-local.test.mjs
	bun scripts/dev/arena-render-report-index.test.mjs
	bun scripts/dev/arena-render-report.test.mjs
	bun scripts/dev/arena-execution-diagnostics.test.mjs
	bun scripts/dev/arena-policy-resolver.test.mjs
	bun scripts/dev/arena-score-breakdown.test.mjs
	bun scripts/dev/arena-economic-reconciliation.test.mjs
	bun scripts/dev/run-arena-economic-policy-matrix.test.mjs
	bun scripts/dev/arena-actor-profile-behavior.test.mjs
	bun scripts/dev/arena-local-hardening-summary.test.mjs
	bun scripts/dev/arena-runner-isolation-failure.test.mjs
	bun scripts/dev/arena-runner-output-limit.test.mjs
	bun scripts/dev/large-json-writer.test.mjs
	bun scripts/dev/arena-local-tick-report-writer.test.mjs
	bun scripts/dev/compare-arena-score-v1-proof.test.mjs
	node --check scripts/dev/bot-sdk-live-smoke.mjs
	node --check scripts/dev/bot-sdk-hosted-run.mjs
	node --check scripts/dev/bot-sdk-build-hosted-artifact.mjs
	node --check scripts/dev/bot-sdk-test-bot.mjs
	node --check scripts/dev/bot-submission-provision-openbao.mjs
	node --check scripts/dev/bot-submission-record-admission.mjs
	node --check scripts/dev/bot-submission-invite-workflow.test.mjs
	node --check scripts/dev/bot-submission-pr-comment.mjs
	node --check scripts/dev/bot-submission-pr-comment.test.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-run.mjs
	node --check scripts/dev/bot-sdk-hosted-worker-child.mjs
	node --check scripts/dev/bot-sdk-hosted-ses-container-smoke.mjs
	node --check scripts/dev/bot-sdk-hosted-live-container-smoke.mjs
	node --check scripts/dev/arena-runner-isolation-failure.test.mjs
	node --check scripts/dev/arena-ingest-bot-run-result.mjs
	node --check scripts/dev/arena-persist-report-local.mjs
	node --check scripts/dev/arena-render-report-index.mjs
	node --check scripts/dev/arena-render-report.mjs
	node --check scripts/dev/arena-run-result-ingestion-smoke.mjs
	node --check scripts/dev/arena-bot-risk-smoke.mjs
	bun scripts/dev/report-taxonomy.test.mjs
	bun scripts/dev/stream-partition-spread.test.mjs
	bun scripts/dev/lib/stress-run-guard.test.mjs
	bun scripts/dev/lib/dev-profiles.test.mjs
	bun scripts/dev/do-benchmark-check.test.mjs
	bun scripts/dev/do-materializer-10k-gate.test.mjs
	bun scripts/dev/do-materializer-scaling-gate.test.mjs
	bun scripts/dev/do-projection-freshness-gate.test.mjs
	bun test scripts/dev/projection-replay-proof.test.mjs
	bun scripts/dev/scenario-drift.test.mjs
	bun scripts/dev/scenario-golden.test.mjs

fmt-go:
	cd $(GO_MATCHING_ENGINE_DIR) && gofmt -w ./cmd ./internal

check-js-runtime:
	@command -v $(JS_RUNTIME) >/dev/null 2>&1 || (echo "missing JS runtime: $(JS_RUNTIME). install Bun for repo automation; JS_RUNTIME=node is only supported for plain Node-compatible dev scripts." && exit 1)

check-bun-runtime:
	@command -v bun >/dev/null 2>&1 || (echo "missing Bun runtime. Install Bun before running bot SDK and arena tests." && exit 1)

check-bot-sdk-js-deps:
	@test -d node_modules/typescript -a -d node_modules/trading-signals -a -d node_modules/ses || (echo "missing JS dependencies for bot SDK and arena tests. Run: bun install --frozen-lockfile" && exit 1)

dev-bootstrap:
	@$(MAKE) check-bun-runtime
	bun scripts/dev/bootstrap.mjs $(ARGS)

dev-doctor:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/doctor.mjs $(ARGS)

dev-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up

dev-up-reef: dev-up

dev-up-arena:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" REEF_ARENA_POSTGRES_MIGRATIONS=1 DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up

backbone-local-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs up

backbone-local-up-infra:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs up-infra

backbone-local-init-openbao:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs init-openbao

backbone-local-migrate:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs migrate

backbone-local-verify:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs verify

backbone-local-status:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs status

backbone-local-logs:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs logs $(ARGS)

backbone-local-down:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/backbone-local.mjs down

dev-up-runtime-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up runtime-nodb

dev-up-captured-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up captured-ack

dev-up-stream-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up stream-ack

dev-up-stream-direct-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack up stream-direct-nodb

dev-compose-config:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/reef-dev.mjs stack compose-config $(ARGS)

dev-validate-stream-profile:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/reef-dev.mjs stream validate $(PROFILE)

dev-down:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack down

dev-down-arena:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack down

dev-reset:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack reset

dev-reset-arena:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" REEF_ARENA_POSTGRES_MIGRATIONS=1 DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stack reset

dev-db-migrate:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/db/migrate.mjs

dev-smoke:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/smoke.mjs

dev-smoke-reef:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/smoke.mjs
	DEV_OPTIONAL_PRODUCT_PROFILE=reef $(JS_RUNTIME) scripts/dev/optional-product-route-smoke.mjs

dev-smoke-arena:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" $(JS_RUNTIME) scripts/dev/smoke.mjs
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" DEV_OPTIONAL_PRODUCT_PROFILE=arena $(JS_RUNTIME) scripts/dev/optional-product-route-smoke.mjs

dev-smoke-arena-isolation:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_FILES="compose.base.yml,compose.local.yml,compose.arena.yml" $(JS_RUNTIME) scripts/dev/arena-failure-isolation-smoke.mjs

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

kube-stream-ack-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs stream-ack-up

kube-smoke-stream-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs stream-ack-smoke

kube-materializer-up:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs materializer-up

kube-materializer-scale:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs materializer-scale

kube-autoscale-apply:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/kube.mjs autoscale-apply

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
	$(JS_RUNTIME) scripts/dev/arena-local-tick-run.mjs --submit-mode=live --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --seed-reference --compartment=ses --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),30000) --require-projection-drain --require-roster-binding --require-economic-reconciliation --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json) $(ARGS)
	$(JS_RUNTIME) scripts/dev/arena-persist-report-local.mjs --report=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-persist.json)

dev-smoke-bot-arena-local-negative:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-local-tick-run.mjs --submit-mode=live --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --seed-reference --compartment=ses --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),30000) --require-projection-drain --extra-bots=custom-too-many-orders --expect-freeze-bots=custom-too-many-orders --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json) $(ARGS)
	$(JS_RUNTIME) scripts/dev/arena-persist-report-local.mjs --report=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json) --out=$(or $(OUT),/tmp/reef-arena-local-tick-run-negative.json)

dev-hardening-bot-arena-local:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-local-hardening-run.mjs --venue-url=$(or $(VENUE_URL),http://127.0.0.1:8080) --arena-admin-url=$(or $(ARENA_ADMIN_URL),$(or $(VENUE_URL),http://127.0.0.1:8080)) --duration-seconds=$(or $(DURATION_SECONDS),180) --projection-drain-timeout-ms=$(or $(PROJECTION_DRAIN_TIMEOUT_MS),60000) --out=$(or $(OUT),/tmp/reef-arena-local-hardening.json) --summary-out=$(or $(SUMMARY_OUT),/tmp/reef-arena-local-hardening.summary.json) $(ARGS)

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

dev-smoke-bot-sdk-hosted-live-container:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/bot-sdk-hosted-live-container-smoke.mjs

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
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stress run runtime-nodb

dev-stress-accepted-async-jfr:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/accepted-async-jfr-stress.mjs

dev-stress-captured-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stress run captured-ack

dev-stress-stream-ack:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stress run stream-ack

dev-stress-stream-direct-nodb:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	DEV_COMPOSE_PROFILES="$(DEV_COMPOSE_PROFILES)" $(JS_RUNTIME) scripts/dev/reef-dev.mjs stress run stream-direct-nodb

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

dev-command-log-archive-partitions:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-archive-partitions.mjs

dev-command-log-pin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/command-log-pin.mjs

dev-admin:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	@if [ -z "$(CMD)" ]; then echo 'usage: make dev-admin CMD="instrument-upsert AAPL AAPL"'; exit 1; fi
	$(JS_RUNTIME) scripts/dev/admin.mjs $(CMD)

dev-admin-auth-local-seed:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/admin-auth-local-seed.mjs $(ARGS)

dev-admin-owned-bot-local-seed:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/admin-owned-bot-local-seed.mjs $(ARGS)

dev-smoke-admin-auth-local:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/arena-admin-auth-local-smoke.mjs

dev-control-room:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) apps/control-room/server.mjs

dev-seed-p2-settlement-facts:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	SCENARIO_RUN_ID="$(SCENARIO_RUN_ID)" $(JS_RUNTIME) scripts/dev/seed-p2-settlement-facts.mjs $(ARGS)

dev-sim:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/reef-dev.mjs sim run $(ARGS)

dev-sim-batch:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/reef-dev.mjs sim batch $(ARGS)

dev-scenario-plan:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go run ./cmd/scenario-plan --scenario $(SCENARIO) --scenario-run-id $(SCENARIO_RUN_ID) --start $(SCENARIO_START) $(ARGS)

dev-scenario-smoke:
	cd $(SIMULATOR_DIR) && GOCACHE=/tmp/reef-go-build-cache go run ./cmd/scenario-smoke --scenario $(SCENARIO) --scenario-run-id $(SCENARIO_RUN_ID) --start $(SCENARIO_START) $(ARGS)

dev-scenario-golden-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/scenario-golden-check.mjs $(ARGS)

docs-site-dev:
	@$(MAKE) check-bun-runtime
	bun scripts/dev/docs-site-tool.mjs install
	bun scripts/dev/docs-site-tool.mjs dev

docs-site-build:
	@$(MAKE) check-bun-runtime
	bun scripts/dev/docs-site-tool.mjs install
	bun scripts/dev/docs-site-tool.mjs build

dev-scenario-drift-check:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/scenario-drift-check.mjs $(ARGS)

dev-compare-reef-arena-separation:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/dev/compare-reef-arena-separation-reports.mjs $(ARGS)

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

do-materializer-scaling-gate:
	./scripts/dev/do-materializer-scaling-gate.sh $(or $(ARGS),plan)

do-projection-freshness-gate:
	./scripts/dev/do-projection-freshness-gate.sh $(or $(ARGS),plan)

do-arena-pacing-lag-gate:
	./scripts/dev/do-arena-pacing-lag-gate.sh $(or $(ARGS),plan)

simulation-run:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/simulation-run.mjs $(ARGS)

hetzner-core:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/hetzner-core.mjs $(ARGS)

hetzner-core-tofu:
	@$(MAKE) check-js-runtime JS_RUNTIME=$(JS_RUNTIME)
	$(JS_RUNTIME) scripts/deploy/hetzner-core-tofu.mjs $(ARGS)
