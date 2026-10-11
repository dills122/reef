# Kotlin/JVM JSON library research — September 30, 2026

Status: research recommendation; no dependency migration or performance qualification.
Decision owner: Reef platform owner. Source checkout: `540c1df199b9ab73527a1c165a67de8cc69c07fc`, branch `codex/calcify-phase2-planning`.

## Recommendation

**Fory JSON is leading challenger for a fast typed Kotlin codec. Keep Jackson as production baseline until Reef benchmarks establish a winner.** Include Fastjson2, DSL-JSON, and kotlinx.serialization in comparison. Benchmark optimized Jackson streaming alongside current tree implementation; library name alone does not determine performance.

Confidence: high in shortlist and need for workload parity; moderate in Fory's potential; unknown in winner for Reef. No local JSON timings, profiles, or allocation measurements were collected. No claim that JSON currently limits platform throughput.

Preferred platform layer: in-process shared library with explicit JSON policies and reusable codecs. Remote JSON service would add transport and queue costs. Keep dynamic-document operations separate from typed hot-path operations. Preserve existing `JsonDocument` behavior during migration; avoid converting candidate trees into Jackson trees on every request, which would obscure benefit.

## Method and scope

Question: which JVM JSON implementation can reduce Kotlin command, event-batch, response, and JSONB processing costs while preserving contracts, validation, replay, and checksums?

Method: inspect current dependencies and representative production code; review official library documentation and executable benchmark descriptions; compare workload, APIs, versions, warmup, concurrency, and integration constraints. Retain documented facts, source observations, inferences, and unknowns separately. Scope excludes binary-protocol changes, Go changes, production dependency swaps, and new load experiments. Stop condition: justified shortlist, recommendation, and measurable adoption gate.

Graph project `reef-main`, generation `2026-09-30T21:14:17Z`; exact-path coverage checked for cited runtime files with no recorded issues. Material behavior also read directly from source. Searches identify representative paths, not an exhaustive inventory.

## Current Reef behavior — source observations

- `services/platform-runtime/build.gradle.kts`: Jackson Databind **2.22.2**, Kotlin **2.4.20**, JVM toolchain **21**. Arena control plane also declares Jackson 2.22.2; stock-data adds Kotlin and JSR-310 modules.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/JsonCodec.kt`: singleton `JsonMapper`, reused correctly. Parsing calls `readTree(String)` and wraps `JsonNode`; writing constructs `ObjectNode`/`ArrayNode` before serialization. Main path is dynamic tree processing, not reflective DTO binding.
- Same file: strict integer accessors check token type and range; permissive accessors have separate fallback/coercion behavior. `raw()` reserializes a subtree. Semantic SHA-256 recursively visits nodes, sorts object fields, and uses textual numeric values. These behaviors constrain replacements.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/api/VenueEventBatchMaterializer.kt`: batch parsing walks outcome documents and serializes nested result payloads for persistence.
- `services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/CalcifyContract.kt`: `CalcifySourceBatch.extract` parses batch once, verifies canonical checksum, then extracts nested trade IDs with strict token checks. Removing unused fields before checksum verification would break required work.
- `services/stock-data/src/main/kotlin/com/reef/stockdata/StockDataHttpServer.kt`: typed `SeedRequest` binding and typed response serialization; a distinct workload from platform trees.

Inference: avoiding unnecessary tree construction, String/UTF-8 conversions, and subtree serialization may matter as much as selecting a new parser. Actual CPU shares and allocation costs remain unmeasured. Do not assume duplicate parsing occurs on every command merely because multiple parsers exist.

## Comparison

| Candidate | Documented mechanism / fit | Recommendation / uncertainty |
| --- | --- | --- |
| Jackson 2.22.2, current trees | Existing production baseline and contract behavior | Mandatory control; already good enough to support recorded workloads, but no JSON-only capacity evidence |
| Jackson streaming / typed readers; Blackbird for DTOs | Streaming avoids whole-tree binding; Blackbird optimizes constructors, getters, setters, and scalar POJO binding | Lowest integration risk challenger. Blackbird is not expected to materially accelerate current `JsonNode` path; measure rather than promise |
| Fory JSON + Kotlin module | Generated codecs, String and UTF-8 APIs, reusable execution state; Kotlin defaults/nullability/value types | Leading typed challenger; tree performance, Kotlin 2.4 metadata compatibility, cold compilation, and concurrency still require checks |
| Fastjson2 | Dynamic `JSONObject`, typed models, byte APIs, Kotlin module | Strong tree/typed challenger; preserve JSON semantics. Benchmark text JSON, not JSONB |
| DSL-JSON | Byte-level processing, streaming, generated compile-time converters | Strong hot DTO candidate; annotation-processing integration and model coverage require proof |
| kotlinx.serialization | Kotlin compiler-generated serializers, Kotlin-first models and multiplatform support | Strong Kotlin integration control; no evidence here that it is fastest. Explicit serializers/policies needed for JVM decimal/time models |
| Moshi codegen | KSP-generated Kotlin adapters | Valid Kotlin option, lower shortlist priority for this throughput objective; no Reef performance rejection implied |
| Jackson 3 | Available major migration with changed packages/configuration/behavior | Optional later challenger; do not conflate major upgrade with guaranteed speedup |

## Fory evidence and limits

**Documented fact:** Fory JSON exchanges ordinary JSON; Fory binary serialization is separate. Generated codecs and asynchronous compilation default on. Fory publishes substantial warmed databinding gains, making it worth testing. [Overview](https://fory.apache.org/docs/json/), [launch benchmark](https://fory.apache.org/blog/fory_json_fastest_java_json_framework/).

Launch table reports **3.54–10.91× Jackson throughput** on 1000 KB Users/Clients objects, comparing Jackson **2.17.1**. This is object mapping, not Reef tree parsing. Large payloads and warmup favor amortized codec work. Small MediaContent report uses Apple M4 Pro/JDK **26.0.1**, one fork/thread, and reports approximately **2.43–2.70× decoding** and **3.60–5.55× encoding** versus Jackson; Reef targets JVM 21. [Java report](https://fory.apache.org/docs/benchmarks/json/java/).

Provenance caveat: launch blog attributes large-payload numbers to Fory **1.7.3**, whereas merged contribution reports **1.6.0** with identical table values. Treat version attribution as unresolved; do not present these as a reproduced 1.7.3 comparison. Wider contribution includes Fastjson2 **2.0.57**: Fory leads it by **1.47–1.58×**, much smaller than Jackson headline. Contribution comes from Fory author; accepted into external harness is not independent reproduction. [Benchmark contribution and full matrix](https://github.com/fabienrenaud/java-json-benchmark/pull/129).

Kotlin benchmark documentation describes immutable MediaContent, four String/byte operations, prebuilt adapters/readers/writers, and pre-timing round-trip/tree-equivalence checks. Useful methodology, but excludes Reef checksums and dynamic tree workload. Linked README fetch failed during review; no numeric Kotlin ranking adopted. [Kotlin benchmark](https://fory.apache.org/docs/benchmarks/json/kotlin/).

Kotlin module documentation uses `fory-json-kotlin:1.7.3`, built with Kotlin 2.3.20, without `kotlin-reflect`. It distinguishes missing fields from explicit null, and defaults to omitting null properties. Verify strict metadata support against Reef Kotlin 2.4.20 before performance work. These documented coordinates are not a verified latest-release selection. [Kotlin guide](https://fory.apache.org/docs/json/kotlin/).

Additional contract and concurrency differences: typed numeric/boolean readers accept quoted tokens; dynamic fractional/exponent numbers use Double; execution states have bounded concurrency, with excess callers waiting; buffers can be retained per pooled writer. Configure and test policies rather than accepting defaults. Thread-safe does not imply contention-free. [Object mapping and configuration](https://fory.apache.org/docs/json/object-mapping/).

## Jackson and other contenders

**Documented fact:** Jackson recommends reusing mapper/factory/readers/writers, direct byte/stream inputs and outputs, and avoiding reserialization/reparsing between phases. Reef already satisfies mapper reuse in core codec. **Inference:** streaming writes and selective typed/token reads deserve a paired comparison before a broad dependency migration. [Jackson performance guidance](https://github.com/FasterXML/jackson-docs/wiki/Presentation:-Jackson-Performance).

Blackbird documentation specifically targets POJO access and construction. **Inference:** adding module to tree codec alone likely provides little gain. Use it in typed comparison, not as a universal parser accelerator. [Blackbird](https://github.com/FasterXML/jackson-modules-base/tree/2.x/blackbird).

Fastjson2 exposes dynamic tree and typed String/byte APIs plus Kotlin extensions; Kotlin constructor/data-class support needs reflection dependency. DSL-JSON supports generated converters, streaming, and Kotlin annotation processing. Both credible challengers; their own speed claims do not establish a universal winner. [Fastjson2](https://github.com/alibaba/fastjson2), [DSL-JSON](https://github.com/ngs-doo/dsl-json).

kotlinx.serialization generates serializers with compiler plugin. Its JSON guide explains preserving arbitrary decimal precision with explicit representation/serializer work. Compiler/plugin integration advantage does not prove throughput leadership. Moshi recommends codegen over reflection for performance. [Kotlin serialization](https://github.com/Kotlin/kotlinx.serialization), [decimal handling](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md), [Moshi](https://github.com/square/moshi).

Jackson 3 requires Java 17+ and includes behavioral changes. JVM 21 meets runtime minimum; source and semantic migration remain separate work. [Jackson 3 release/migration details](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0).

## Existing throughput evidence

Reviewed baseline ledger, performance learnings, active projection plan, and existing library investigation plan. Original H4/H5 JSON inspected locally:

- H4 `do-benchmark-20260820T220557Z`: materialized/projected **749,976**, zero final gap; historical 2.5k/s, 300-second full-projection success.
- H5 `do-benchmark-20260820T222945Z`: materialized **1,500,002**, projected **746,047**, gap **753,955**; historical 5k/s, 300-second full-projection failure.
- Current ledger also separates C5 venue-core 10k/s results from C2 projection failure and CAL-P1-L9 local single-lane Phase 1 5k/s qualification. None isolates JVM JSON cost; none establishes parser causality.

Original locations/hashes: [artifact index](../evidence/throughput-baseline-artifact-index-2026-09-24.json). Governing scope: [baseline ledger](../THROUGHPUT_BASELINES.md). Existing [library plan](../PERFORMANCE_LIBRARY_INVESTIGATION.md) favors kotlinx.serialization as first typed target and DSL-JSON as hot DTO spike. This research adds Fory/Fastjson2 for evaluation; does not silently replace accepted production behavior or historical guidance.

## Next gate — proposed, not executed

1. Profile current JVM 21 path with JFR CPU/allocation evidence. Identify time in JSON parsing, tree creation, writing, checksum sorting/hashing, UTF-8 conversion, and unrelated DB/broker work. Capture source commit, image, hardware, JVM/GC/heap, payload corpus hashes, and concurrency.
2. Run JMH with at least three forks, adequate steady-state warmup, GC allocation profiling, and consumed results. Measure first-use separately in fresh JVMs. Pin compatible released versions; retain failures. Use single-thread and service-representative concurrency, especially Fory pool saturation/retained heap.
3. Separate two comparisons: **same dynamic-tree behavior** and **typed/streaming implementation redesign**. Both use identical inputs and required work; never attribute removing trees/checksums/fields to parser speed alone.
4. Corpus: submit/cancel/modify commands; mixed accepted/rejected responses; actual venue batches at configured counts and sizes; Calcify parse+checksum+trade extraction; response lists; JSONB payload writes; dynamic bot config; malformed inputs. Measure decode, encode, full round trip, and full contract operation. Include String and native UTF-8 boundaries without hidden conversion advantages.
5. Parity first: missing/null/unknown/duplicate fields, quoted numbers, strict integer ranges and >2^53 IDs, decimal/exponent/negative-zero handling, Unicode/escaping, array order, canonical checksum bytes, output inclusion, trailing garbage, malformed input and endpoint rejection mapping. Preserve current strict and permissive APIs deliberately.
6. Suggested adoption bar: reproducible **at least 20% lower CPU per relevant operation or materially lower allocation**, no meaningful p99/concurrency regression, full parity. Threshold is proposed decision criterion, not repository policy. Small gains favor Jackson because migration cost remains.
7. Winner gets one reversible route/batch-path integration; rerun matched end-to-end profile with exact stage counts and replay/checksum checks. Record every attempt in throughput evidence ledger. Expand only after improvement survives actual request/broker/DB boundaries.

Illustrative Amdahl limit: if JSON accounts for 20% of processing time, making JSON 3× faster reduces total time to `0.8 + 0.2/3 = 0.8667`, only **1.154× overall speedup**. Hypothetical, not Reef profile. CPU/allocation savings may still be worthwhile even when DB limits accepted throughput.

Adoption conclusion: evaluate Fory first as typed challenger, Fastjson2 as tree/typed challenger, and DSL-JSON/kotlinx.serialization as generated-code controls. Keep optimized Jackson in final race. Actual winner can differ by operation; prefer one default backend unless measured hot-path gain justifies a second explicit codec. No ADR or dependency change authorized by this research result alone.
