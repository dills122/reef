# Bot SDK Package Approval Policy

Hosted bots may only use packages that Reef explicitly approves, pins, bundles, scans, and tests through the hosted artifact pipeline.

V1.5 does not allow arbitrary runtime imports. Approved package use should be bundled into the hosted artifact, then scanned after bundling. Hosted execution still receives no filesystem, network, timer, child process, worker thread, or native module capability.

## Initial Approval Candidates

These packages are good candidates for the first strategy-helper allowlist after audit:

| Package | Version | Status | Reason |
| --- | --- | --- | --- |
| `trading-signals` | `7.4.3` | candidate | TypeScript-oriented technical indicators with a focused package surface. |
| `simple-statistics` | `7.9.3` | candidate | Pure JavaScript statistics helpers for mean, deviation, regression, and z-score style strategies. |
| `decimal.js` | `10.6.0` | candidate | Mature deterministic decimal arithmetic helper. |
| `lodash-es` | `4.18.1` | candidate | Utility helpers; should be bundled/tree-shaken and kept out of runtime import resolution. |

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
