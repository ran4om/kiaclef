#!/usr/bin/env bash
set -euo pipefail

PROJECT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="${PROJECT}"
RUNNER="${PROJECT}/scripts/run-packaged-runtime.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

FUNCTION_SOURCE="$(sed -n '/^find_summary_row() {/,/^}/p' "${RUNNER}")"
if [[ -z "${FUNCTION_SOURCE}" ]]; then
  echo "Could not find find_summary_row() in ${RUNNER}" >&2
  exit 1
fi
CLASSIFIER_SOURCE="$(sed -n '/^classify_summary() {/,/^}/p' "${RUNNER}")"
if [[ -z "${CLASSIFIER_SOURCE}" ]]; then
  echo "Could not find classify_summary() in ${RUNNER}" >&2
  exit 1
fi
eval "${FUNCTION_SOURCE}"
eval "${CLASSIFIER_SOURCE}"

assert_summary() {
  local name="$1" expected="$2" actual
  actual="$(find_summary_row "${TEMP_DIR}/${name}.tsv")"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'FAIL %s\nexpected: %s\nactual:   %s\n' "${name}" "${expected}" "${actual}" >&2
    exit 1
  fi
}

printf 'SUMMARY\tFAIL\toriginal failure\nSUMMARY\tPASS\tlate success\n' >"${TEMP_DIR}/failure-before-pass.tsv"
assert_summary failure-before-pass $'SUMMARY\tFAIL\toriginal failure'

printf 'SUMMARY\tPASS\tearly pass\nSUMMARY\tFAIL\tlater failure\n' >"${TEMP_DIR}/pass-before-failure.tsv"
assert_summary pass-before-failure $'SUMMARY\tFAIL\tlater failure'

printf 'SUMMARY\tFAIL\tfirst failure\nSUMMARY\tFAIL\tlast failure\nSUMMARY\tPASS\tlate success\n' >"${TEMP_DIR}/multiple-failures.tsv"
assert_summary multiple-failures $'SUMMARY\tFAIL\tlast failure'

printf 'SUMMARY\tPASS\tearly pass\nSUMMARY\tPASS\tlast pass\n' >"${TEMP_DIR}/passes-only.tsv"
assert_summary passes-only $'SUMMARY\tPASS\tlast pass'

: >"${TEMP_DIR}/no-summary.tsv"
assert_summary no-summary ""

PYTHONPATH="${PROJECT}/scripts" python3 - "${TEMP_DIR}" <<'PY'
from pathlib import Path
import sys

from check_packaged_runtime_container_evidence import fixture

root = Path(sys.argv[1])
rows, log = fixture()
(root / "complete-containers.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
(root / "complete-runtime.log").write_text(log, encoding="utf-8")
PY

RUNTIME_START="containers"
RESULT_FILE="${TEMP_DIR}/complete-containers.tsv"
RUNTIME_LOG="${TEMP_DIR}/complete-runtime.log"
VERDICT="ABORTED_DIAGNOSTICS"
classify_summary $'SUMMARY\tPASS\t26.2 runtimeStart=containers'
if [[ "${VERDICT}" != "PASS" ]]; then
  printf 'FAIL full container classifier: %s\n' "${VERDICT_REASON}" >&2
  exit 1
fi

printf 'CONTAINER_ACCEPTANCE\tPASS\nSUMMARY\tPASS\t26.2 runtimeStart=containers\n' >"${TEMP_DIR}/lone-container-pass.tsv"
RESULT_FILE="${TEMP_DIR}/lone-container-pass.tsv"
VERDICT="ABORTED_DIAGNOSTICS"
classify_summary $'SUMMARY\tPASS\t26.2 runtimeStart=containers'
if [[ "${VERDICT}" != "EVIDENCE_INCOMPLETE" ]]; then
  printf 'FAIL lone container terminal rows classified as %s\n' "${VERDICT}" >&2
  exit 1
fi

RESULT_FILE="${TEMP_DIR}/lone-container-pass.tsv"
VERDICT="ABORTED_DIAGNOSTICS"
classify_summary $'SUMMARY\tFAIL\tfixture or gameplay failure'
if [[ "${VERDICT}" != "FAIL" ]]; then
  printf 'FAIL terminal SUMMARY FAIL classified as %s\n' "${VERDICT}" >&2
  exit 1
fi

assert_naturalresource_verdict() {
  local name="$1" expected="$2" summary
  RESULT_FILE="${TEMP_DIR}/${name}.tsv"
  summary="$(find_summary_row "${RESULT_FILE}")"
  VERDICT="ABORTED_DIAGNOSTICS"
  VERDICT_REASON=""
  classify_summary "${summary}"
  if [[ "${VERDICT}" != "${expected}" ]]; then
    printf 'FAIL naturalresource %s: expected %s, got %s (%s)\n' \
      "${name}" "${expected}" "${VERDICT}" "${VERDICT_REASON}" >&2
    exit 1
  fi
}

RUNTIME_START="naturalresource"
printf 'RESOURCE_LIST_ACCEPTANCE\tPASS\nSUMMARY\tPASS\t26.2 runtimeStart=naturalresource\n' \
  >"${TEMP_DIR}/naturalresource-terminal.tsv"
assert_naturalresource_verdict naturalresource-terminal PASS

printf 'RESOURCE_LIST_ACCEPTANCE\tPASS\nSUMMARY\tPASS\t26.2 runtimeStart=naturalresource\nLATE_EVIDENCE\tunexpected\n' \
  >"${TEMP_DIR}/naturalresource-trailing-evidence.tsv"
assert_naturalresource_verdict naturalresource-trailing-evidence EVIDENCE_INCOMPLETE

printf 'RESOURCE_LIST_ACCEPTANCE\tPASS\nSUMMARY\tPASS\t26.2 runtimeStart=naturalresource\n\n   \t\n' \
  >"${TEMP_DIR}/naturalresource-blank-tail.tsv"
assert_naturalresource_verdict naturalresource-blank-tail PASS

printf 'RESOURCE_LIST_ACCEPTANCE\tPASS\nSUMMARY\tPASS\tfirst\nSUMMARY\tPASS\tsecond\n' \
  >"${TEMP_DIR}/naturalresource-duplicate-summary.tsv"
assert_naturalresource_verdict naturalresource-duplicate-summary EVIDENCE_INCOMPLETE

printf 'SUMMARY\tPASS\t26.2 runtimeStart=naturalresource\n' \
  >"${TEMP_DIR}/naturalresource-missing-acceptance.tsv"
assert_naturalresource_verdict naturalresource-missing-acceptance EVIDENCE_INCOMPLETE

printf 'RESOURCE_LIST_ACCEPTANCE\tPASS\nSUMMARY\tPASS\t26.2 runtimeStart=naturalresource\nSUMMARY\tFAIL\tlate failure\n' \
  >"${TEMP_DIR}/naturalresource-late-failure.tsv"
assert_naturalresource_verdict naturalresource-late-failure FAIL

assert_mobdefense_verdict() {
  local name="$1" expected="$2" summary
  RESULT_FILE="${TEMP_DIR}/${name}.tsv"
  summary="$(find_summary_row "${RESULT_FILE}")"
  VERDICT="ABORTED_DIAGNOSTICS"
  VERDICT_REASON=""
  classify_summary "${summary}"
  if [[ "${VERDICT}" != "${expected}" ]]; then
    printf 'FAIL mobdefense %s: expected %s, got %s (%s)\n' \
      "${name}" "${expected}" "${VERDICT}" "${VERDICT_REASON}" >&2
    exit 1
  fi
}

RUNTIME_START="mobdefense"
printf 'SUMMARY\tPASS\t26.2 runtimeStart=mobdefense\nMOB_DEFENSE_CAPACITY_ACCEPTANCE\tPASS\tterminal\n' \
  >"${TEMP_DIR}/mobdefense-terminal.tsv"
assert_mobdefense_verdict mobdefense-terminal PASS

printf 'MOB_DEFENSE_CAPACITY_ACCEPTANCE\tEVIDENCE_INCOMPLETE\tmissing scheduler data\nSUMMARY\tFAIL\tEVIDENCE_INCOMPLETE details\n' \
  >"${TEMP_DIR}/mobdefense-incomplete.tsv"
assert_mobdefense_verdict mobdefense-incomplete EVIDENCE_INCOMPLETE

printf 'MOB_DEFENSE_CAPACITY_ACCEPTANCE\tFAIL\tpriority mismatch\nSUMMARY\tFAIL\tpriority mismatch\n' \
  >"${TEMP_DIR}/mobdefense-failure.tsv"
assert_mobdefense_verdict mobdefense-failure FAIL

printf 'SUMMARY\tPASS\t26.2 runtimeStart=mobdefense\nMOB_DEFENSE_CAPACITY_ACCEPTANCE\tPASS\tterminal\nLATE_EVIDENCE\tunexpected\n' \
  >"${TEMP_DIR}/mobdefense-trailing-evidence.tsv"
assert_mobdefense_verdict mobdefense-trailing-evidence EVIDENCE_INCOMPLETE

printf 'PACKAGED_RUNTIME_SUMMARY_VERDICT_CHECK\tPASS\t5 summary cases + 3 container + 6 naturalresource + 4 mobdefense classifier cases\n'
