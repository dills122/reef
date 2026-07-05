# Bot SDK Package Approval Policy

Hosted bots may only use packages that Reef explicitly approves, pins, bundles, scans, and tests through the hosted artifact pipeline.

V1.5 does not allow arbitrary runtime imports. Approved package use should be bundled into the hosted artifact, then scanned after bundling. Hosted execution still receives no filesystem, network, timer, child process, worker thread, or native module capability.

## V1.5 Approved Packages

These packages are the first exact-version strategy-helper allowlist. Bot source may import these package names, but the hosted artifact builder must bundle them before SES execution. Hosted runtime does not resolve package imports dynamically.

| Package | Version | Status | Reason |
| --- | --- | --- | --- |
| `trading-signals` | `7.4.3` | approved | TypeScript-oriented technical indicators with a focused package surface. |
| `simple-statistics` | `7.9.3` | approved | Pure JavaScript statistics helpers for mean, deviation, regression, and z-score style strategies. |
| `decimal.js` | `10.6.0` | approved | Mature deterministic decimal arithmetic helper. |
| `lodash-es` | `4.18.1` | approved | Utility helpers; should be bundled/tree-shaken and kept out of runtime import resolution. |

The executable allowlist lives in `packages/bot-sdk/src/sandbox-policy.ts` as `reefBotApprovedPackagesV1`. `packages/bot-sdk/approved-packages.v1.json` mirrors that list for review and publishing workflows.

`technicalindicators` is a second-wave candidate because it has broad indicator coverage, but it needs closer audit before hosted approval.

## Explicit V1 Rejections

Do not approve these for hosted v1/v1.5 bots:

- `talib`: native bindings and large runtime surface.
- `tulind`: native bindings, older release surface, and LGPL/native install concerns.
- `@debut/indicators`: GPL-3.0 license is not acceptable for the default hosted allowlist.
- `danfojs` / `danfojs-node`: large dependency graph and runtime/network/native-adjacent package surface.
- `mathjs`: powerful but too broad for v1.5 because of expression parsing and a larger dependency graph.

## Approval Requirements

Before a package becomes usable by bot authors:

1. Pin an exact package version.
2. Confirm license compatibility.
3. Reject native modules and install scripts unless explicitly approved.
4. Bundle the package into the hosted artifact.
5. Scan the final artifact for denied globals, imports, and dynamic module loading.
6. Run the artifact through SES E2E and hosted worker tests.
7. Record the package/version/hash in the bot artifact manifest or registry review record.

`packages/bot-sdk/examples/technical-indicator-strategy-bot.ts` is the first approved-package example. It imports `trading-signals`, then the hosted artifact test verifies the final bundle has no runtime imports and records `trading-signals@7.4.3` in the artifact manifest.
