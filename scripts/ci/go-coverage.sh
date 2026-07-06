#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 5 ]; then
  echo "usage: $0 <module-dir> <coverage-name> [artifact-dir] [min-pct] [exclude-regex]" >&2
  exit 2
fi

module_dir="$1"
coverage_name="$2"
artifact_dir="${3:-coverage/${coverage_name}}"
min_pct="${4:-}"
exclude_regex="${5:-}"
repo_root="$(git rev-parse --show-toplevel)"
if [[ "${artifact_dir}" = /* ]]; then
  absolute_artifact_dir="${artifact_dir}"
else
  absolute_artifact_dir="${repo_root}/${artifact_dir}"
fi

mkdir -p "${absolute_artifact_dir}"

pushd "${repo_root}/${module_dir}" >/dev/null
go test ./... -covermode=atomic -coverprofile="${absolute_artifact_dir}/coverage.out"
go tool cover -func="${absolute_artifact_dir}/coverage.out" | tee "${absolute_artifact_dir}/coverage.txt"
popd >/dev/null

total="$(awk '/^total:/ { print $3 }' "${absolute_artifact_dir}/coverage.txt")"

gated_pct="${total%\%}"
gated_label="${module_dir}"
if [ -n "${exclude_regex}" ]; then
  gated_pct="$(awk -F'[: ,]' -v excl="${exclude_regex}" '
    NR == 1 { next }
    $1 ~ excl { next }
    { stmt = $(NF-1); cnt = $NF; total += stmt; if (cnt > 0) covered += stmt }
    END { if (total == 0) { print "0.0" } else { printf "%.1f", covered * 100 / total } }
  ' "${absolute_artifact_dir}/coverage.out")"
  gated_label="${module_dir} (excl. generated/entrypoint code)"
fi

{
  echo "## ${coverage_name} coverage"
  echo
  echo "| Module | Statement coverage |"
  echo "| --- | ---: |"
  echo "| ${module_dir} | ${total:-unknown} |"
  if [ -n "${exclude_regex}" ]; then
    echo "| ${gated_label} | ${gated_pct}% |"
  fi
} >"${absolute_artifact_dir}/coverage-summary.md"

echo "${coverage_name} coverage: ${total:-unknown} (raw), ${gated_pct}% (gated)"

if [ -n "${min_pct}" ]; then
  below="$(awk -v got="${gated_pct}" -v min="${min_pct}" 'BEGIN { print (got < min) ? 1 : 0 }')"
  if [ "${below}" = "1" ]; then
    echo "FAIL: ${coverage_name} gated coverage ${gated_pct}% is below required minimum ${min_pct}%" >&2
    exit 1
  fi
fi
