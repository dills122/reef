#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 <module-dir> <coverage-name> [artifact-dir]" >&2
  exit 2
fi

module_dir="$1"
coverage_name="$2"
artifact_dir="${3:-coverage/${coverage_name}}"
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
{
  echo "## ${coverage_name} coverage"
  echo
  echo "| Module | Statement coverage |"
  echo "| --- | ---: |"
  echo "| ${module_dir} | ${total:-unknown} |"
} >"${absolute_artifact_dir}/coverage-summary.md"

echo "${coverage_name} coverage: ${total:-unknown}"
