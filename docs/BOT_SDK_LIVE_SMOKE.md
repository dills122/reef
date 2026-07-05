# Bot SDK Live Smoke

The live smoke path submits Bot SDK runner output through the approved venue HTTP client against a local Reef runtime. Bot code still receives only SDK context objects; network access belongs to the runner/orchestrator.

## Prerequisites

Start the local stack first:

```bash
make dev-up
```

The smoke fixture must include `runId`, `venueSessionId`, `actorId`, `participantId`, `accountId`, `botId`, `botVersion`, `correlationId`, and at least one market snapshot instrument. The wrapper prints a preflight report before submitting commands.

Venue command requests use `X-Client-Id: bot:<botId>` by default so they pass the same external API boundary checks as user-submitted commands. Add `clientId` to the fixture when a hosted run needs a configured platform client identity.

## Run

For the default fixture and example bot:

```bash
bun scripts/dev/bot-sdk-live-smoke.mjs packages/bot-sdk/examples/simple-market-maker.ts packages/bot-sdk/fixtures/aapl-multi-tick.json --venue-url=http://127.0.0.1:8080 --seed-reference
```

Equivalent Make target:

```bash
make dev-smoke-bot-sdk-live BOT=packages/bot-sdk/examples/simple-market-maker.ts ARGS=--seed-reference
```

`--seed-reference` creates local reference/auth state inferred from the fixture:

- instruments from fixture market snapshots and initial orders
- participant and account from fixture identity fields
- `order_trader` role
- actor-role binding for the fixture actor

Omit `--seed-reference` when the stack is already seeded by a scenario or an operator setup step.

## Expected Result

A passing run prints:

- the venue preflight report
- runner tick reports
- venue command requests
- venue responses from `/api/v1/orders/*`
- final `status: "completed"`

If the report is `do_not_merge`, inspect `issues` and `denials`. Fixture preflight failures mean the bot never submitted live commands. Venue send denials mean the runner built valid commands but the local runtime rejected or failed them.
