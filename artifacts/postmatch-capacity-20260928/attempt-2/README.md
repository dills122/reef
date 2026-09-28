# PM-S3 attempt 2 — source connection ceiling

One disposable `sfo3` `c-32` droplet (`604195292`). Both initial arms used six
materializers, sixteen projector owners, 384 load workers, a 10,000/s target
for 300 seconds, and source PostgreSQL `max_connections=200`.

| Run | Result | Evidence |
| --- | --- | --- |
| `postmatch-capacity-control-20260927T235615Z` | 2,999,950 accepted and materialized; 9,999.67/s; stage observer passed | `control-*` |
| `postmatch-capacity-treatment-20260927T235615Z` | 2,750,574 accepted; 2,589,916 materialized; source connections exhausted; stage and exact checks failed | `treatment-*` |
| `postmatch-capacity-treatment-pool2-20260928T0047Z` | Two-connection dedicated source pools; settlement seed failed before traffic because source server still had no free connection | `pool2-startup.log.gz` |

`e9b907fc` sets source PostgreSQL `max_connections=320` in both matched arms
for the next clean pair. No post-match capacity claim follows from this attempt.
`evidence.sha256` lists checksums for retained files.
