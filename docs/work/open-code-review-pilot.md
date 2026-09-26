# OpenCodeReview pilot scope

Date: 2026-09-26. This is pilot operating evidence, not a standing review policy.

## Configuration choices

- `ocr-pilot` is the only label whose addition starts a review. An opted-in PR
  also runs on later pushes, reopening, or becoming ready for review. Drafts and
  unlabeled PRs remain skipped.
- OCR 1.12.9 normally skips Go `*_test.go` and Kotlin `src/test/**/*.kt` files.
  The project `include` patterns bring changed service tests into the review.
  They do not limit other supported files: OCR's `include` is a bypass of its
  default file filter, not a whitelist. `docs/**` and `reports/**` remain excluded.
  `rules` stays empty so OCR's built-in language-specific prompts still apply.
- Two concurrent review groups reduce possible overshoot of the 500,000-token
  budget. OCR checks that budget between model rounds, can publish partial
  results, and may exit successfully after skipping files. The OpenRouter key
  limit is the separate spend control. The 45-minute job timeout leaves time
  for OCR's default 15-minute task timeout (30 minutes at medium effort) and
  comment posting; it does not guarantee a large PR finishes.
- A completed review records its head in the sticky summary. On a later push,
  `checkpoint_range` reviews only changes since that head. Missing or untrusted
  checkpoints, configuration or base changes, and non-ancestor force-pushes
  cause a full review. Reopening or marking a PR ready also requests a full
  review. Inline comments remain; the sticky summary describes only the latest
  reviewed range.
  The first run after enabling checkpoints must review the full PR to establish
  its marker. A narrowed review can miss interactions with unchanged PR files,
  so request a full review when that context matters. GitHub users with write
  permission can edit the summary marker and are trusted by this mechanism.
- The pinned Action prints its OCR JSON result and stderr into the Actions log
  even when `upload_artifacts` is false. Use PRs whose code and review output
  are acceptable in the repository's workflow logs.

## Evidence and limits

OCR 1.12.9 `review --preview --commit <sha> --format json` ran without an LLM
call, using this worktree's project rules against historical commit diffs:

| Reef commit | Previously selected | With service tests included |
| --- | ---: | ---: |
| `387199d3` (test-only command intake fix) | 0 | 1 |
| `7d66fd13` (SQL/data audit) | 6 | 13 |
| `a38489a9` (order/replay hardening) | 42 | 51 |
| `56b89fda` (large projection change) | 84 | 111 |

Large PRs can now consume more model tokens or finish with a partial review.
Use a modest source-code PR for the pilot; inspect selected/skipped files and
OpenRouter charges before expanding. These previews measure selection, not
review quality or cost.

Two completed runs under the original configuration provide limited live
evidence: [PR #364's run](https://github.com/dills122/reef/actions/runs/36221462205)
reviewed 2 files, used 12,156 tokens, and posted no findings;
[PR #369's run](https://github.com/dills122/reef/actions/runs/36221343008)
reviewed 6 files, used 49,082 tokens, and posted 6 findings. Finding accuracy
and billed cost have not been established from those logs.

PR #372 produced six completed OCR runs on 2026-09-26: one when `ocr-pilot` was
added, then five after successive pushes. Each run reviewed the full selected
PR diff (7 files initially, then 9 after service tests were included), although
the five pushes changed only 1–4 files each. Together the runs reported 855,104
input tokens (598,784 cache reads) and 19,871 output tokens. At the
[published GPT-5.4 mini rates](https://openrouter.ai/openai/gpt-5.4-mini),
that implies roughly $0.33 at the listed standard rates; the OCR logs do not
contain the billed amount. This repeated work motivates cross-push checkpoints.
The 500,000-token budget applies to each run, not to the PR across runs.

## Deferred until more reviews

- Keep `openai/gpt-5.4-mini` and `effort: medium`: the two runs completed, but
  their findings still need human validation before changing quality settings.
- Keep `incremental: 'true'` to avoid duplicate inline comments. It does not
  reduce model work; `checkpoint_range` now narrows later push reviews after a
  complete, trusted run. Check its `range_summary` in the workflow log and use
  `full_review: 'true'` temporarily when a full reread is needed.
- Do not add project `rules` yet. A project rule takes precedence over OCR's
  built-in language rule for matching files; targeted prompts need their own
  quality check before replacing that baseline.

OCR behavior: [review rules](https://github.com/alibaba/open-code-review/blob/v1.12.9/pages/src/content/docs/en/review-rules.md),
[CLI preview](https://github.com/alibaba/open-code-review/blob/v1.12.9/pages/src/content/docs/en/cli-reference.md),
and [pinned Action inputs](https://github.com/alibaba/open-code-review/blob/bccbc15f785269400735d5255540c231e6c02b6d/action.yml).
