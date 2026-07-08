# Stock Data Seeding Plan

## Purpose

Define how Reef should seed new simulation/game market state from real stock
market snapshots without introducing external market-data dependencies into
deterministic simulation execution or replay.

The stock-data service is only for game creation. Once a game is seeded, the
exact snapshots used for that game become durable seed facts. Simulation,
matching, replay, audit, and scenario validation must read those persisted
facts rather than calling an external market-data provider.

## Usage Shape

The expected call pattern is low volume:

- a new simulation/game is requested
- the game template resolves the tracked ticker symbols
- the stock-data service fetches one seed snapshot per tracked symbol
- the seed workflow persists the normalized snapshots and provider metadata
- the game starts from those persisted values

The service should not poll continuously, stream quotes, or refresh prices for
an already seeded game unless a future product requirement explicitly adds a
separate live-market synchronization mode.

## Provider Recommendation

Use Tiingo as the first provider.

Reasons:

- free tier is practical for low-volume seed calls
- paid upgrade is simple and predictable
- IEX current data endpoint exposes seed-useful fields such as `tngoLast`,
  `prevClose`, `open`, `high`, `low`, `volume`, and timestamps
- EOD endpoint exposes historical daily OHLCV rows for after-hours fallback
- API shape is simple enough to hide behind a narrow provider adapter

Current reference points from provider docs reviewed on 2026-07-08:

- Tiingo free tier: 500 unique symbols/month, 50 requests/hour, 1,000
  requests/day, 1 GB/month
- Tiingo paid individual tier: $30/month or $300/year
- Tiingo internal commercial tier: $50/month or $499/year
- Tiingo EOD prices are generally available after 5:30 PM ET, with corrections
  possible later in the evening

Licensing needs a final product/legal check before any public or commercial
launch. Treat free/individual tiers as internal-use only until confirmed.

## Alternatives Considered

| Provider | Fit | Notes |
| --- | --- | --- |
| Tiingo | Best first provider | Good low-volume limits, current IEX endpoint, EOD historical endpoint, simple pricing. |
| Twelve Data | Good fallback | Free plan has useful API credits and batch support, but commercial terms need confirmation. |
| Alpha Vantage | Backup only | Free tier is broad but low at 25 requests/day; realtime, delayed, and bulk quote paths require premium. |
| Marketstack | Cheap but limited | Free tier is EOD only and 100 requests/month; low-cost paid tier may work for intraday. |
| Massive/Polygon | Strong but less cost-aligned | Free stock aggregate access is EOD; delayed/realtime snapshots require paid plans. |

## Boundary

Add a stock-data provider boundary inside the platform runtime or simulator
seed orchestration layer. Keep it separate from Reef's internal market-data API.

Reef's market-data API exposes venue-local facts produced by Reef simulations
and matching. The stock-data provider fetches external reference values used to
initialize a game. These are different domains and should not share mutable
projection tables.

Suggested interface:

```kotlin
interface StockDataProvider {
    fun getSeedSnapshots(
        symbols: List<String>,
        asOf: Instant,
    ): StockSeedSnapshotBatch
}
```

Provider adapters can expose smaller private methods, but the seed workflow
should depend on the batch-level interface. That keeps provider-specific request
fanout, batching, retries, and fallback rules out of game-seeding code.

## Seed Snapshot Shape

Persist one normalized row per symbol per game seed.

Minimum fields:

| Field | Purpose |
| --- | --- |
| `gameSeedId` | Durable seed identifier for the game. |
| `symbol` | External ticker symbol requested by the game template. |
| `provider` | Provider used, such as `tiingo`. |
| `sourceType` | `intraday_current`, `historical_eod`, or `cached_fallback`. |
| `asOf` | Seed request time. |
| `sourceTimestamp` | Provider timestamp for the price or bar. |
| `retrievedAt` | Reef retrieval time. |
| `currency` | Expected to be `USD` for the first slice. |
| `price` | Seed price used by the game. |
| `open` | Same-day open when available. |
| `high` | Same-day high when available. |
| `low` | Same-day low when available. |
| `previousClose` | Previous official close when available. |
| `volume` | Provider volume field when available. |
| `rawProviderPayloadHash` | Hash of the relevant provider payload for audit. |
| `selectionReason` | Why this price was selected. |

Use integer minor units or a decimal type consistent with the existing monetary
and price conventions. Do not store seed prices as floating point.

Also persist a batch-level seed hash over the ordered normalized snapshots. This
gives replay and handoff flows a compact way to verify that a game started from
the same external reference state.

## Market Session Rules

Use the New York market calendar and ET session boundaries for US equities.

First-slice rules:

- regular session is 09:30-16:00 ET on trading days
- outside regular session, prefer last historical/EOD close
- holidays and weekends are always historical/EOD fallback
- pre-market and post-market should not seed from extended-hours prices in the
  first slice unless product explicitly asks for extended-hours realism

This keeps initial behavior easy to explain: regular hours use current market
reference data, otherwise games start from the most recent official close.

## Price Selection Rules

During regular market hours:

1. Fetch current data from Tiingo IEX.
2. Prefer `tngoLast` as the seed price.
3. If `tngoLast` is unavailable, use `last` only when the account has the
   required IEX entitlement and the timestamp is fresh.
4. If no current price is fresh enough, fall back to the latest historical/EOD
   close.
5. Persist `sourceType=intraday_current` only when current data passed freshness
   checks.

After hours, weekends, holidays, or stale current data:

1. Fetch Tiingo EOD prices.
2. Select the latest row whose market date is at or before the seed `asOf`.
3. Use raw `close` as the seed price for current-market realism.
4. Persist adjusted close separately only if later analytics need it.
5. Persist `sourceType=historical_eod`.

Suggested freshness thresholds:

| Session | Max current-data age |
| --- | --- |
| Regular market hours | 15 minutes |
| Pre/post-market if enabled later | 60 minutes |
| Closed market | Do not use current endpoint |

## Failure Behavior

Default behavior should fail closed for game creation.

If a required symbol cannot be resolved to a fresh current snapshot or an
acceptable historical/EOD fallback, the seed request should fail with a
structured error identifying the missing symbol and provider failure category.

Allowed failure categories:

- `symbol_not_supported`
- `provider_rate_limited`
- `provider_timeout`
- `provider_unavailable`
- `stale_current_data`
- `historical_fallback_unavailable`
- `invalid_provider_payload`

Cached fallback may be added, but only behind an explicit configuration flag:

```text
STOCK_DATA_ALLOW_STALE_CACHE=false
```

When stale cache is enabled, persisted snapshots must use
`sourceType=cached_fallback` and include the original source timestamp. Cached
fallback should not be silently presented as fresh market data.

## Resilience

Implement:

- explicit provider timeouts
- bounded retries with jitter
- provider-specific rate-limit handling
- per-provider circuit breaker
- short-lived cache keyed by provider, symbol, market date, and source type
- structured logs with seed ID, symbol, provider, source type, and failure code

Because calls only happen at seed time, correctness and auditability matter more
than shaving milliseconds from provider latency.

## Configuration

Initial configuration:

```text
STOCK_DATA_PROVIDER=tiingo
TIINGO_API_TOKEN=
STOCK_DATA_CURRENT_MAX_AGE_SECONDS=900
STOCK_DATA_PROVIDER_TIMEOUT_MS=2500
STOCK_DATA_PROVIDER_MAX_RETRIES=2
STOCK_DATA_ALLOW_STALE_CACHE=false
```

Use environment configuration only for provider credentials and operational
limits. Game templates should declare tracked symbols, not provider endpoints.

## Implementation Plan

1. Add the stock-data provider interface and normalized seed snapshot model.
2. Add a deterministic fake provider for tests and local scenarios.
3. Add the Tiingo adapter for current IEX data and EOD fallback.
4. Add market-session classification for US equities.
5. Persist seed snapshot batches before game creation is considered accepted.
6. Wire game seeding to consume persisted snapshots, not provider responses.
7. Add replay tests proving no provider calls occur after seed persistence.
8. Add failure-path tests for stale current data, missing symbols, provider
   timeout, and EOD fallback.
9. Add operator documentation for API-key setup and quota expectations.

## Open Questions

- Confirm whether the first product slice is internal-only or commercial/public;
  this determines the Tiingo plan and licensing posture.
- Confirm the first supported exchange universe. This plan assumes US equities.
- Decide whether pre-market and post-market seed realism is needed later. The
  first slice deliberately uses official close outside regular market hours.
- Decide where to store raw provider payloads. A hash is required; full payload
  storage is useful but may have licensing and retention implications.
