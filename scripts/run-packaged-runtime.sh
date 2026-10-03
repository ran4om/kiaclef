#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${PROJECT_ROOT}/run/productionProbe"
GAME_VERSION="26.2"
FABRIC_API_VERSION="0.154.2+26.2"
LOOM_ARGFILE="${PROJECT_ROOT}/build/loom-cache/argFiles/runClient"
ALTOCLEF_JAR="${ALTOCLEF_PRODUCTION_JAR:-${PROJECT_ROOT}/build/libs/altoclef-0.5.0.jar}"
RUNTIME_WORLD="${ALTOCLEF_RUNTIME_WORLD:-}"
RUNTIME_STARTUP="${ALTOCLEF_RUNTIME_STARTUP:-quickplay}"
RUNTIME_START="${ALTOCLEF_RUNTIME_START:-diamond}"
RUNTIME_TIMEOUT_SECONDS="${ALTOCLEF_RUNTIME_TIMEOUT_SECONDS:-2400}"
RUN_STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUNTIME_ARCHIVE_PREFIX="${ALTOCLEF_RUNTIME_ARCHIVE_PREFIX:-runtime-${RUNTIME_START}-${RUN_STAMP}}"

if [[ ! "${RUNTIME_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ALTOCLEF_RUNTIME_TIMEOUT_SECONDS must be a positive integer." >&2
  exit 2
fi

if ! command -v timeout >/dev/null 2>&1; then
  echo "GNU timeout is required to bound the packaged runtime client." >&2
  exit 2
fi

if ! command -v pgrep >/dev/null 2>&1; then
  echo "pgrep is required to prevent launching a second Minecraft client." >&2
  exit 2
fi

if ! command -v ps >/dev/null 2>&1; then
  echo "ps is required to monitor and stop the packaged client process group." >&2
  exit 2
fi

if ! command -v flock >/dev/null 2>&1; then
  echo "flock is required to protect shared packaged-runtime state." >&2
  exit 2
fi

if [[ ! "${RUNTIME_ARCHIVE_PREFIX}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "ALTOCLEF_RUNTIME_ARCHIVE_PREFIX may contain only letters, digits, dot, underscore, and dash." >&2
  exit 2
fi

RUNTIME_LOG="${ALTOCLEF_RUNTIME_LOG:-${PROJECT_ROOT}/.audit/runtime-${RUNTIME_ARCHIVE_PREFIX}.log}"
RUNTIME_LAUNCHER_LOG="${ALTOCLEF_RUNTIME_LAUNCHER_LOG:-${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-launcher.log}"
if [[ "${RUNTIME_LOG}" != /* ]]; then RUNTIME_LOG="${PROJECT_ROOT}/${RUNTIME_LOG}"; fi
if [[ "${RUNTIME_LAUNCHER_LOG}" != /* ]]; then RUNTIME_LAUNCHER_LOG="${PROJECT_ROOT}/${RUNTIME_LAUNCHER_LOG}"; fi
ARCHIVED_TSV="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}.tsv"
ABORTED_TSV="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-aborted.tsv"
ABORTED_LOG="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-aborted.log"
PRESTOP_LOG="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-prestop.log"
PROCESS_MONITOR_LOG="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-process-monitor.tsv"
HOST_PREFLIGHT_LOG="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}-host-preflight.txt"
RUN_REPORT="${PROJECT_ROOT}/.audit/gameplay/${RUNTIME_ARCHIVE_PREFIX}.json"

capture_host_preflight() {
  local label="$1" mem_available swap_free loom_state loom_hash
  mem_available="$(awk '$1 == "MemAvailable:" { print $2; exit }' /proc/meminfo 2>/dev/null || true)"
  swap_free="$(awk '$1 == "SwapFree:" { print $2; exit }' /proc/meminfo 2>/dev/null || true)"
  if [[ -f "${LOOM_ARGFILE}" ]]; then
    loom_state="present"
    loom_hash="$(sha256sum "${LOOM_ARGFILE}" | awk '{print $1}')"
  else
    loom_state="missing"
    loom_hash="unavailable"
  fi
  {
    printf 'sample=%s\n' "${label}"
    printf 'captured_at_utc=%s\n' "$(date -u +%FT%TZ)"
    printf 'branch=%s\n' "$(git -C "${PROJECT_ROOT}" branch --show-current 2>/dev/null || true)"
    printf 'source_head=%s\n' "$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || true)"
    printf 'runtime_start=%s\n' "${RUNTIME_START}"
    printf 'runtime_world=%s\n' "${RUNTIME_WORLD}"
    printf 'loom_argfile=%s\n' "${LOOM_ARGFILE}"
    printf 'loom_argfile_state=%s\n' "${loom_state}"
    printf 'loom_argfile_sha256=%s\n' "${loom_hash}"
    printf 'mem_available_kb=%s\n' "${mem_available:-unavailable}"
    printf 'swap_free_kb=%s\n' "${swap_free:-unavailable}"
    printf 'io_pressure_begin\n'
    cat /proc/pressure/io 2>/dev/null || true
    printf 'io_pressure_end\n'
    printf 'memory_pressure_begin\n'
    cat /proc/pressure/memory 2>/dev/null || true
    printf 'memory_pressure_end\n'
    printf 'blocked_tasks=%s\n' "$(ps -eo stat= | awk '$1 ~ /^D/ { count++ } END { print count+0 }')"
    printf 'gradle_daemon_pids=%s\n' "$(pgrep -f 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' | paste -sd, - || true)"
    printf 'zram_state_begin\n'
    zramctl 2>/dev/null || true
    printf 'zram_state_end\n'
  } >>"${HOST_PREFLIGHT_LOG}"
}

mkdir -p "${PROJECT_ROOT}/.audit/gameplay"
exec 9>"${PROJECT_ROOT}/.audit/packaged-runtime.lock"
if ! flock -n 9; then
  echo "Another packaged runtime runner holds the project runtime lock." >&2
  exit 2
fi
for EVIDENCE_PATH in "${RUNTIME_LOG}" "${RUNTIME_LAUNCHER_LOG}" "${ARCHIVED_TSV}" "${ABORTED_TSV}" "${ABORTED_LOG}" "${PRESTOP_LOG}" "${PROCESS_MONITOR_LOG}" "${HOST_PREFLIGHT_LOG}" "${RUN_REPORT}"; do
  if [[ -e "${EVIDENCE_PATH}" || -L "${EVIDENCE_PATH}" ]]; then
    echo "Refusing to overwrite existing runtime evidence: ${EVIDENCE_PATH}" >&2
    exit 2
  fi
done
python3 - "${RUNTIME_LOG}" "${RUNTIME_LAUNCHER_LOG}" "${ARCHIVED_TSV}" "${ABORTED_TSV}" "${ABORTED_LOG}" "${PRESTOP_LOG}" "${PROCESS_MONITOR_LOG}" "${HOST_PREFLIGHT_LOG}" "${RUN_REPORT}" <<'PATH_CHECK'
from pathlib import Path
import sys

paths = [Path(value).resolve() for value in sys.argv[1:]]
if len(paths) != len(set(paths)):
    raise SystemExit("Runtime evidence paths must resolve to distinct destinations.")
PATH_CHECK
mkdir -p "$(dirname -- "${RUNTIME_LOG}")" "$(dirname -- "${RUNTIME_LAUNCHER_LOG}")"
if ! command -v tee >/dev/null 2>&1; then
  echo "tee is required to archive packaged-runtime launcher output." >&2
  exit 2
fi
exec > >(tee -a "${RUNTIME_LAUNCHER_LOG}") 2>&1

if pgrep -af '[n]et.fabricmc.loader.impl.launch.knot.KnotClient|[n]et.minecraft.client.main.Main' >/dev/null; then
  echo "A Minecraft client process is already running; close it before packaged acceptance." >&2
  pgrep -af '[n]et.fabricmc.loader.impl.launch.knot.KnotClient|[n]et.minecraft.client.main.Main' >&2 || true
  exit 2
fi

capture_host_preflight "before packaged-runtime materialization or launch"
printf 'ts\tevent\tpid\tppid\tpgid\tstate\tcpu_pct\trss_kb\telapsed_seconds\tcomm\tio_some_avg10\tio_full_avg10\tmem_available_kb\tswap_free_kb\tresult_bytes\tresult_mtime_epoch\n' >"${PROCESS_MONITOR_LOG}"

if [[ ! -f "${ALTOCLEF_JAR}" ]]; then
  echo "Build AltoClef first (./gradlew build)." >&2
  exit 2
fi

if [[ ! -f "${LOOM_ARGFILE}" ]]; then
  echo "Preparing Loom's packaged runtime classpath without launching the development client." >&2
  ./gradlew --no-daemon --console=plain \
    --init-script "${PROJECT_ROOT}/scripts/materialize-packaged-runtime-args.gradle" \
    materializePackagedRuntimeArgs
fi

if [[ ! -f "${LOOM_ARGFILE}" ]]; then
  echo "Loom did not create ${LOOM_ARGFILE}." >&2
  exit 2
fi
capture_host_preflight "after packaged runtime prerequisite checks"

if [[ ! -d "${PROJECT_ROOT}/build/classes/java/runtimeTest" || ! -f "${PROJECT_ROOT}/build/resources/runtimeTest/fabric.mod.json" ]]; then
  echo "Compile the opt-in runtime harness first (./gradlew runtimeTestClasses)." >&2
  exit 2
fi

mkdir -p "${RUN_DIR}/mods"
cp -f "${ALTOCLEF_JAR}" "${RUN_DIR}/mods/altoclef-0.5.0.jar"
python3 - "${ALTOCLEF_JAR}" "${RUN_DIR}/mods/altoclef-0.5.0.jar" <<'ARTIFACT_CHECK'
import hashlib
import json
from pathlib import Path
import sys
import zipfile

source, destination = (Path(value).resolve() for value in sys.argv[1:])
with zipfile.ZipFile(source) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
    if metadata.get("id") != "altoclef" or archive.testzip() is not None:
        raise SystemExit("Selected production artifact is not a valid AltoClef mod jar")
for candidate in destination.parent.glob("*.jar"):
    if candidate.resolve() == destination:
        continue
    with zipfile.ZipFile(candidate) as archive:
        if "fabric.mod.json" in archive.namelist():
            if json.loads(archive.read("fabric.mod.json")).get("id") == "altoclef":
                raise SystemExit(f"Duplicate AltoClef mod jar in runtime directory: {candidate}")
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
destination_hash = hashlib.sha256(destination.read_bytes()).hexdigest()
if source_hash != destination_hash:
    raise SystemExit("Copied production artifact hash differs from selected source")
print(f"PACKAGED_ARTIFACT_SOURCE\t{source}\tsha256={source_hash}", flush=True)
print(f"PACKAGED_ARTIFACT_LOADED\t{destination}\tsha256={destination_hash}", flush=True)
ARTIFACT_CHECK

FABRIC_API_JAR="$(find "${HOME}/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/${FABRIC_API_VERSION}" -name "fabric-api-${FABRIC_API_VERSION}.jar" -print -quit)"
if [[ -z "${FABRIC_API_JAR}" ]]; then
  echo "Fabric API ${FABRIC_API_VERSION} is not present in the Gradle cache." >&2
  exit 2
fi
cp -f "${FABRIC_API_JAR}" "${RUN_DIR}/mods/"

for MOD_JAR in "${PROJECT_ROOT}"/run/runtimeTest/mods/litematica-fabric-${GAME_VERSION}-*.jar "${PROJECT_ROOT}"/run/runtimeTest/mods/malilib-fabric-${GAME_VERSION}-*.jar; do
  [[ -f "${MOD_JAR}" ]] && cp -f "${MOD_JAR}" "${RUN_DIR}/mods/"
done

QUICK_PLAY_ARGS=()
if [[ -n "${RUNTIME_WORLD}" ]]; then
  if [[ ! -f "${RUN_DIR}/saves/${RUNTIME_WORLD}/level.dat" ]]; then
    echo "Requested runtime world has no level.dat: ${RUN_DIR}/saves/${RUNTIME_WORLD}" >&2
    exit 2
  fi
  printf 'PACKAGED_RUNTIME_WORLD\t%s\n' "${RUNTIME_WORLD}"
  case "${RUNTIME_STARTUP}" in
    quickplay)
      QUICK_PLAY_ARGS+=(--quickPlaySingleplayer "${RUNTIME_WORLD}" --quickPlayPath "${RUN_DIR}/quickplay-log.json")
      printf 'PACKAGED_RUNTIME_STARTUP\tquickplay\n'
      ;;
    title-screen)
      printf 'PACKAGED_RUNTIME_STARTUP\ttitle-screen\n'
      printf 'PACKAGED_RUNTIME_WORLD_EXPECTED\t%s\n' "${RUNTIME_WORLD}"
      ;;
    *)
      echo "Unsupported packaged runtime startup mode: ${RUNTIME_STARTUP} (use quickplay or title-screen)." >&2
      exit 2
      ;;
  esac
elif [[ "${RUNTIME_STARTUP}" != "quickplay" ]]; then
  echo "ALTOCLEF_RUNTIME_WORLD is required for startup mode ${RUNTIME_STARTUP}." >&2
  exit 2
fi

HARNESS_JAR="${RUN_DIR}/mods/altoclef-runtime-acceptance.jar"
jar --create --file "${HARNESS_JAR}" \
  -C "${PROJECT_ROOT}/build/classes/java/runtimeTest" . \
  -C "${PROJECT_ROOT}/build/resources/runtimeTest" .
HARNESS_HASH="$(sha256sum "${HARNESS_JAR}" | awk '{print $1}')"
printf 'PACKAGED_RUNTIME_HARNESS\t%s\tsha256=%s\n' "${HARNESS_JAR}" "${HARNESS_HASH}"
printf 'PACKAGED_RUNTIME_TIMEOUT_SECONDS\t%s\n' "${RUNTIME_TIMEOUT_SECONDS}"
printf 'PACKAGED_RUNTIME_START_MODE\t%s\n' "${RUNTIME_START}"
printf 'PACKAGED_RUNTIME_ARCHIVE_PREFIX\t%s\n' "${RUNTIME_ARCHIVE_PREFIX}"
PRODUCTION_HASH="$(sha256sum "${RUN_DIR}/mods/altoclef-0.5.0.jar" | awk '{print $1}')"

CLASSPATH_LINE="$(sed -n '2p' "${LOOM_ARGFILE}")"
GAME_CP="$(python3 - "${PROJECT_ROOT}" "${CLASSPATH_LINE}" <<'PY'
import sys

project = sys.argv[1]
entries = sys.argv[2].split(":")
excluded = (
    f"{project}/build/classes/java/main",
    f"{project}/build/resources/main",
    f"{project}/build/classes/java/runtimeTest",
    f"{project}/build/resources/runtimeTest",
    f"{project}/libs/baritone-unoptimized-fabric-1.19.0.jar",
    "/net.fabricmc.fabric-api/",
    "/com.fasterxml.jackson.core/",
    "/net.fabricmc/dev-launch-injector/",
)
print(":".join(path for path in entries if not any(marker in path for marker in excluded)))
PY
)"

RUNTIME_STARTED_UTC="$(date -u +%FT%TZ)"
START_MARKER="$(mktemp "${TMPDIR:-/tmp}/altoclef-runtime-start.XXXXXX")"
RUNTIME_EXIT_STATUS=""
RESULT_FILE=""
RESULT_ARCHIVE=""
SUMMARY_ROW=""
RUNTIME_PID=""
RUNTIME_PGID=""
RUNTIME_OWNER_PID=""
RUNTIME_STARTING="0"
PENDING_SIGNAL=""
VERDICT="ABORTED_DIAGNOSTICS"
VERDICT_REASON="runner exited before collecting a terminal SUMMARY row"
EVIDENCE_ERRORS=""

record_process_sample() {
  local event="$1" stamp io_some io_full mem_available swap_free result_bytes result_mtime
  stamp="$(date -u +%FT%TZ)"
  io_some="$(awk '$1 == "some" { for (i=2; i<=NF; i++) if ($i ~ /^avg10=/) { sub(/^avg10=/, "", $i); print $i; exit } }' /proc/pressure/io 2>/dev/null || true)"
  io_full="$(awk '$1 == "full" { for (i=2; i<=NF; i++) if ($i ~ /^avg10=/) { sub(/^avg10=/, "", $i); print $i; exit } }' /proc/pressure/io 2>/dev/null || true)"
  mem_available="$(awk '$1 == "MemAvailable:" { print $2; exit }' /proc/meminfo 2>/dev/null || true)"
  swap_free="$(awk '$1 == "SwapFree:" { print $2; exit }' /proc/meminfo 2>/dev/null || true)"
  result_bytes="0"
  result_mtime="missing"
  if [[ -n "${RESULT_FILE:-}" && -f "${RESULT_FILE}" ]]; then
    result_bytes="$(stat -c '%s' "${RESULT_FILE}" 2>/dev/null || printf 0)"
    result_mtime="$(stat -c '%Y' "${RESULT_FILE}" 2>/dev/null || printf missing)"
  fi
  printf '%s\thost:%s\t-\t-\t%s\t-\t-\t-\t-\t-\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${stamp}" "${event}" "${RUNTIME_PGID:-}" "${io_some:-unavailable}" "${io_full:-unavailable}" \
    "${mem_available:-unavailable}" "${swap_free:-unavailable}" "${result_bytes}" "${result_mtime}" \
    >>"${PROCESS_MONITOR_LOG}"
  if [[ -n "${RUNTIME_PGID:-}" ]]; then
    while read -r pid ppid pgid state cpu rss elapsed comm; do
      [[ "${pgid}" == "${RUNTIME_PGID}" ]] || continue
      printf '%s\tprocess\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t-\t-\t-\t-\t-\t-\n' \
        "${stamp}" "${pid}" "${ppid}" "${pgid}" "${state}" "${cpu}" "${rss}" "${elapsed}" "${comm}" \
        >>"${PROCESS_MONITOR_LOG}"
    done < <(ps -eo pid=,ppid=,pgid=,stat=,%cpu=,rss=,etimes=,comm=)
  fi
}

find_fresh_result() {
  local result candidate
  result=""
  for candidate in "${RUN_DIR}"/runtime-test-results/altoclef-26.2-*.tsv; do
    [[ -f "${candidate}" ]] || continue
    if [[ "${candidate}" -nt "${START_MARKER}" ]]; then
      result="${candidate}"
    fi
  done
  printf '%s' "${result}"
}

find_summary_row() {
  local result="$1"
  awk -F '\t' '
    $1 == "SUMMARY" && $2 == "FAIL" { failure = $0 }
    $1 == "SUMMARY" && $2 == "PASS" { pass = $0 }
    END {
      if (failure != "") print failure
      else if (pass != "") print pass
    }
  ' "${result}"
}

classify_summary() {
  local row="$1"
  local summary_status required_row container_evidence
  summary_status="$(printf '%s' "${row}" | cut -f2)"
  VERDICT_REASON="${row}"
  if [[ "${summary_status}" == "FAIL" ]]; then
    if [[ "${RUNTIME_START}" == "mobdefense" ]] \
      && awk -F '\t' -v summary_row="${row}" '
        $1 == "MOB_DEFENSE_CAPACITY_ACCEPTANCE" { acceptance_status = $2 }
        $0 == summary_row && $1 == "SUMMARY" && $2 == "FAIL" {
          incomplete = (acceptance_status == "EVIDENCE_INCOMPLETE")
        }
        END { exit !incomplete }
      ' "${RESULT_FILE}"; then
      VERDICT="EVIDENCE_INCOMPLETE"
      VERDICT_REASON="Mob defense capacity acceptance reported EVIDENCE_INCOMPLETE before SUMMARY FAIL"
    else
      VERDICT="FAIL"
    fi
    return
  fi

  required_row=""
  case "${RUNTIME_START}" in
    kelp) required_row="KELP_ACCEPTANCE" ;;
    containers) required_row="CONTAINER_ACCEPTANCE" ;;
    naturalresource) required_row="RESOURCE_LIST_ACCEPTANCE" ;;
    litematicarecovery) required_row="LITEMATICA_RECOVERY_ACCEPTANCE" ;;
    mobdefense) required_row="MOB_DEFENSE_CAPACITY_ACCEPTANCE" ;;
    ui) required_row="UI_SCREENSHOT" ;;
  esac
  if [[ -n "${required_row}" ]] \
    && ! awk -F '\t' -v required="${required_row}" '$1 == required && $2 == "PASS" { found = 1 } END { exit !found }' "${RESULT_FILE}"; then
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="SUMMARY PASS lacked required ${required_row} PASS row"
  elif [[ "${RUNTIME_START}" == "containers" ]]; then
    if container_evidence="$(python3 "${PROJECT_ROOT}/scripts/packaged_runtime_evidence.py" \
      "${RESULT_FILE}" "${RUNTIME_LOG}" 2>&1)"; then
      VERDICT="PASS"
      VERDICT_REASON="${row}; ${container_evidence}"
    else
      VERDICT="EVIDENCE_INCOMPLETE"
      container_evidence="${container_evidence//$'\n'/; }"
      VERDICT_REASON="SUMMARY PASS lacked ordered container evidence: ${container_evidence}"
    fi
  elif [[ "${RUNTIME_START}" == "naturalresource" ]] \
    && ! awk -F '\t' '
      $0 ~ /[^[:space:]]/ { final = NR }
      $1 == "SUMMARY" && $2 == "PASS" && first_pass == 0 { first_pass = NR }
      END { exit !(first_pass != 0 && first_pass == final) }
    ' "${RESULT_FILE}"; then
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="SUMMARY PASS was not the first and final nonempty runtime evidence row"
  elif [[ "${RUNTIME_START}" == "litematicarecovery" ]] \
    && ! awk -F '\t' '
      $0 ~ /[^[:space:]]/ { final = NR }
      $1 == "SUMMARY" && $2 == "PASS" { summary = NR }
      $1 == "LITEMATICA_RECOVERY_ACCEPTANCE" && $2 == "PASS" { acceptance = NR }
      END { exit !(summary != 0 && acceptance > summary && acceptance == final) }
    ' "${RESULT_FILE}"; then
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="Litematica recovery lacked an ordered terminal acceptance row after SUMMARY PASS"
  elif [[ "${RUNTIME_START}" == "mobdefense" ]] \
    && ! awk -F '\t' '
      $0 ~ /[^[:space:]]/ { final = NR }
      $1 == "SUMMARY" && $2 == "PASS" { summary = NR }
      $1 == "MOB_DEFENSE_CAPACITY_ACCEPTANCE" && $2 == "PASS" { acceptance = NR }
      END { exit !(summary != 0 && acceptance > summary && acceptance == final) }
    ' "${RESULT_FILE}"; then
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="Mob defense lacked an ordered terminal capacity acceptance row after SUMMARY PASS"
  else
    VERDICT="PASS"
  fi
}

copy_snapshot_once() {
  local source="$1" destination="$2" temporary
  [[ -f "${source}" ]] || return 1
  if [[ -f "${destination}" ]]; then return 0; fi
  temporary="${destination}.tmp.$$"
  rm -f "${temporary}"
  if cp -p -- "${source}" "${temporary}" && mv -n -- "${temporary}" "${destination}"; then
    [[ -f "${destination}" ]] && return 0
  fi
  rm -f "${temporary}"
  return 1
}

record_evidence_error() {
  local message="$1"
  if [[ -n "${EVIDENCE_ERRORS}" ]]; then EVIDENCE_ERRORS+="; "; fi
  EVIDENCE_ERRORS+="${message}"
  if [[ "${VERDICT}" == "PASS" ]]; then
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="${VERDICT_REASON}; evidence archive incomplete: ${message}"
  fi
}

check_launcher_log() {
  if [[ ! -s "${RUNTIME_LAUNCHER_LOG}" ]]; then
    record_evidence_error "launcher log is empty or unavailable"
  fi
  if [[ ! -s "${HOST_PREFLIGHT_LOG}" ]]; then
    record_evidence_error "host preflight log is empty or unavailable"
  fi
  if [[ ! -s "${PROCESS_MONITOR_LOG}" ]]; then
    record_evidence_error "process monitor archive is empty or unavailable"
  fi
}

write_run_report() {
  local verdict="$1" reason="$2" client_status="$3" tsv_path="$4" pre_stop_path="$5"
  local finished_utc
  finished_utc="$(date -u +%FT%TZ)"
  python3 - "${RUN_REPORT}" "${RUNTIME_ARCHIVE_PREFIX}" "${verdict}" \
    "${RUNTIME_START}" "${RUNTIME_WORLD}" "${RUNTIME_STARTUP}" "${RUNTIME_STARTED_UTC}" \
    "${finished_utc}" "${client_status}" "${tsv_path}" "${pre_stop_path}" "${RUNTIME_LOG}" "${RUNTIME_LAUNCHER_LOG}" "${EVIDENCE_ERRORS}" \
    "${ALTOCLEF_JAR}" "${PRODUCTION_HASH}" "${HARNESS_JAR}" "${HARNESS_HASH}" \
    "${PROCESS_MONITOR_LOG}" "${HOST_PREFLIGHT_LOG}" \
    3< <(printf '%s' "${reason}") <<'RUN_REPORT'
import hashlib
import json
import os
from pathlib import Path
import sys

(destination, run_id, verdict, mode, world, startup, started, finished,
 client_status, tsv_path, pre_stop_path, runtime_log, launcher_log, evidence_errors, production_jar,
 production_hash, harness_jar, harness_hash, process_monitor_log, host_preflight_log) = sys.argv[1:]
reason = os.fdopen(3, encoding="utf-8").read()

def sha256_if_present(path):
    candidate = Path(path)
    if not candidate.is_file():
        return None
    digest = hashlib.sha256()
    with candidate.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

temporary = Path(destination).with_name(Path(destination).name + f".tmp.{os.getpid()}")
payload = {
    "run_id": run_id,
    "verdict": verdict,
    "reason": reason,
    "runtime_start": mode,
    "world": world,
    "startup": startup,
    "started_at_utc": started,
    "finished_at_utc": finished,
    "client_wrapper_exit_status": None if client_status in ("running", "unknown") else int(client_status),
    "client_state_at_report": client_status if client_status in ("running", "unknown") else "exited",
    "result_tsv": tsv_path or None,
    "pre_stop_log": pre_stop_path or None,
    "runtime_log": runtime_log,
    "launcher_log": launcher_log,
    "evidence_errors": evidence_errors or None,
    "production_jar": production_jar,
    "production_sha256": production_hash,
    "runtime_harness_jar": harness_jar,
    "runtime_harness_sha256": harness_hash,
    "process_monitor_log": process_monitor_log,
    "process_monitor_sha256": sha256_if_present(process_monitor_log),
    "host_preflight_log": host_preflight_log,
    "host_preflight_sha256": sha256_if_present(host_preflight_log),
}
temporary.write_text(json.dumps(payload, indent=2) + "\n")
os.replace(temporary, destination)
print(f"PACKAGED_RUNTIME_REPORT\t{destination}")
RUN_REPORT
}

process_group_is_running() {
  [[ -n "${RUNTIME_PGID:-}" && "${RUNTIME_PGID}" == "${RUNTIME_OWNER_PID:-}" ]] || return 1
  ps -eo pgid=,stat= | awk -v group="${RUNTIME_PGID}" '$1 == group && $2 !~ /Z/ { found = 1 } END { exit !found }'
}

pid_is_running() {
  local process_state
  [[ -r "/proc/$1/stat" ]] || return 1
  process_state="$(awk '{print $3}' "/proc/$1/stat" 2>/dev/null)"
  [[ -n "${process_state}" && "${process_state}" != Z ]]
}

signal_runtime_group() {
  local signal="$1"
  if [[ -n "${RUNTIME_PGID:-}" && "${RUNTIME_PGID}" == "${RUNTIME_OWNER_PID:-}" ]]; then
    kill -"${signal}" -- "-${RUNTIME_PGID}" 2>/dev/null || true
  elif [[ -n "${RUNTIME_OWNER_PID:-}" ]]; then
    kill -"${signal}" "${RUNTIME_OWNER_PID}" 2>/dev/null || true
  elif [[ -n "${RUNTIME_PID:-}" ]]; then
    kill -"${signal}" "${RUNTIME_PID}" 2>/dev/null || true
  fi
}

stop_owned_runtime() {
  local index
  if process_group_is_running; then
    signal_runtime_group TERM
    for ((index = 0; index < 20; index++)); do
      process_group_is_running || break
      sleep 1
    done
    if process_group_is_running; then signal_runtime_group KILL; fi
  elif [[ -n "${RUNTIME_OWNER_PID:-}" || -n "${RUNTIME_PID:-}" ]]; then
    signal_runtime_group TERM
    for ((index = 0; index < 20; index++)); do
      pid_is_running "${RUNTIME_OWNER_PID:-${RUNTIME_PID}}" || break
      sleep 1
    done
    if pid_is_running "${RUNTIME_OWNER_PID:-${RUNTIME_PID}}"; then signal_runtime_group KILL; fi
  fi

  if [[ -n "${RUNTIME_PID:-}" ]]; then
    if wait "${RUNTIME_PID}"; then RUNTIME_EXIT_STATUS=0; else RUNTIME_EXIT_STATUS=$?; fi
    RUNTIME_PID=""
  fi
  RUNTIME_PGID=""
  RUNTIME_OWNER_PID=""
  record_process_sample "stopped"
}

cleanup_runtime() {
  local original_status="$?" report_status
  trap - EXIT
  trap '' INT TERM
  set +e
  if [[ -n "${RUNTIME_PID:-}" || -n "${RUNTIME_PGID:-}" || -n "${RUNTIME_OWNER_PID:-}" ]]; then
    record_process_sample "runner-cleanup"
    RESULT_FILE="$(find_fresh_result)"
    SUMMARY_ROW=""
    if [[ -n "${RESULT_FILE}" ]]; then SUMMARY_ROW="$(find_summary_row "${RESULT_FILE}")"; fi
    if [[ -n "${SUMMARY_ROW}" ]]; then
      classify_summary "${SUMMARY_ROW}"
      if copy_snapshot_once "${RESULT_FILE}" "${ARCHIVED_TSV}"; then
        RESULT_ARCHIVE="${ARCHIVED_TSV}"
        RESULT_FILE="${RESULT_ARCHIVE}"
        SUMMARY_ROW="$(find_summary_row "${RESULT_FILE}")"
        if [[ -n "${SUMMARY_ROW}" ]]; then
          classify_summary "${SUMMARY_ROW}"
        else
          VERDICT="EVIDENCE_INCOMPLETE"
          VERDICT_REASON="captured TSV snapshot lacked a complete terminal SUMMARY row"
        fi
      else
        RESULT_ARCHIVE=""
        record_evidence_error "could not archive terminal TSV"
      fi
    else
      VERDICT="ABORTED_DIAGNOSTICS"
      VERDICT_REASON="runner exited with status ${original_status} before a terminal SUMMARY row"
      if [[ -n "${RESULT_FILE}" ]]; then
        if copy_snapshot_once "${RESULT_FILE}" "${ABORTED_TSV}"; then
          RESULT_ARCHIVE="${ABORTED_TSV}"
        else
          RESULT_ARCHIVE=""
          record_evidence_error "could not archive diagnostic TSV"
        fi
      fi
      if ! copy_snapshot_once "${RUNTIME_LOG}" "${ABORTED_LOG}"; then
        record_evidence_error "could not archive aborted runtime log"
      fi
    fi

    PRESTOP_ARCHIVE=""
    if copy_snapshot_once "${RUNTIME_LOG}" "${PRESTOP_LOG}"; then
      PRESTOP_ARCHIVE="${PRESTOP_LOG}"
    else
      record_evidence_error "could not archive pre-stop runtime log"
    fi
    check_launcher_log

    if process_group_is_running; then
      report_status="running"
      write_run_report "${VERDICT}" "${VERDICT_REASON}" "${report_status}" "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}" || true
    fi
    stop_owned_runtime
    report_status="${RUNTIME_EXIT_STATUS:-unknown}"
    write_run_report "${VERDICT}" "${VERDICT_REASON}" "${report_status}" "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}" || true
  fi
  rm -f "${START_MARKER}"
  exit "${original_status}"
}

trap cleanup_runtime EXIT
trap 'if [[ "${RUNTIME_STARTING:-0}" == 1 ]]; then PENDING_SIGNAL=130; else exit 130; fi' INT
trap 'if [[ "${RUNTIME_STARTING:-0}" == 1 ]]; then PENDING_SIGNAL=143; else exit 143; fi' TERM

# Optional extra JVM flags (whitespace-separated), e.g. diagnostics toggles.
read -r -a EXTRA_JVM_ARGS <<<"${ALTOCLEF_EXTRA_JVM_ARGS:-}"

cd "${RUN_DIR}"
RUNTIME_STARTING="1"
set -m
timeout --foreground --signal=TERM --kill-after=15s "${RUNTIME_TIMEOUT_SECONDS}s" java \
  -Dfabric.development=false \
  -Daltoclef.runtimeTest=true \
  "-Daltoclef.builderPlacementDiagnostics=${ALTOCLEF_BUILDER_DIAGNOSTICS:-false}" \
  "-Daltoclef.runtimeStart=${RUNTIME_START}" \
  "-Daltoclef.runtimeCrafterOrientation=${ALTOCLEF_CRAFTER_ORIENTATION:-north_up}" \
  ${EXTRA_JVM_ARGS[@]+"${EXTRA_JVM_ARGS[@]}"} \
  -cp "${GAME_CP}" \
  net.fabricmc.loader.impl.launch.knot.KnotClient \
  --version "${GAME_VERSION}" \
  --assetIndex "26.2-32" \
  --assetsDir "${HOME}/.gradle/caches/fabric-loom/assets" \
  --gameDir "${RUN_DIR}" \
  --username AltoClefTest \
  --uuid 00000000000000000000000000000000 \
  --accessToken FabricMC \
  --versionType release \
  "${QUICK_PLAY_ARGS[@]}" >"${RUNTIME_LOG}" 2>&1 </dev/null &
RUNTIME_OWNER_PID=$!
RUNTIME_PID="${RUNTIME_OWNER_PID}"
RUNTIME_PGID="${RUNTIME_OWNER_PID}"
set +m
RUNTIME_STARTING="0"
if [[ -n "${PENDING_SIGNAL}" ]]; then exit "${PENDING_SIGNAL}"; fi
printf 'PACKAGED_RUNTIME_PID\t%s\n' "${RUNTIME_PID}"
printf 'PACKAGED_RUNTIME_PGID\t%s\n' "${RUNTIME_PGID}"
printf 'PACKAGED_RUNTIME_LOG\t%s\n' "${RUNTIME_LOG}"
printf 'PACKAGED_RUNTIME_LAUNCHER_LOG\t%s\n' "${RUNTIME_LAUNCHER_LOG}"
record_process_sample "launched"

RESULT_FILE=""
SUMMARY_ROW=""
while process_group_is_running; do
  sleep 1
  record_process_sample "watch"
  RESULT_FILE="$(find_fresh_result)"
  if [[ -n "${RESULT_FILE}" ]]; then
    SUMMARY_ROW="$(find_summary_row "${RESULT_FILE}")"
    if [[ -n "${SUMMARY_ROW}" ]]; then
      if [[ "${RUNTIME_START}" == "litematicarecovery" ]] \
        && [[ "$(printf '%s' "${SUMMARY_ROW}" | cut -f2)" == "PASS" ]] \
        && ! awk -F '\t' '
          $1 == "SUMMARY" && $2 == "PASS" { summary = NR }
          $1 == "LITEMATICA_RECOVERY_ACCEPTANCE" && $2 == "PASS" { acceptance = NR }
          END { exit !(summary != 0 && acceptance > summary) }
        ' "${RESULT_FILE}"; then
        SUMMARY_ROW=""
      elif [[ "${RUNTIME_START}" == "mobdefense" ]] \
        && [[ "$(printf '%s' "${SUMMARY_ROW}" | cut -f2)" == "PASS" ]] \
        && ! awk -F '\t' '
          $1 == "SUMMARY" && $2 == "PASS" { summary = NR }
          $1 == "MOB_DEFENSE_CAPACITY_ACCEPTANCE" && $2 == "PASS" { acceptance = NR }
          END { exit !(summary != 0 && acceptance > summary) }
        ' "${RESULT_FILE}"; then
        SUMMARY_ROW=""
      else
        record_process_sample "verdict-observed"
        break
      fi
    fi
  fi
  if [[ -n "${RUNTIME_PID}" ]] && ! pid_is_running "${RUNTIME_PID}"; then
    if wait "${RUNTIME_PID}"; then RUNTIME_EXIT_STATUS=0; else RUNTIME_EXIT_STATUS=$?; fi
    RUNTIME_PID=""
    break
  fi
done
record_process_sample "result-watch-finished"

if [[ -z "${SUMMARY_ROW}" ]]; then
  if [[ -n "${RUNTIME_PID}" ]]; then
    if wait "${RUNTIME_PID}"; then RUNTIME_EXIT_STATUS=0; else RUNTIME_EXIT_STATUS=$?; fi
    RUNTIME_PID=""
  fi
  RESULT_FILE="$(find_fresh_result)"
  if [[ -n "${RESULT_FILE}" ]]; then
    SUMMARY_ROW="$(find_summary_row "${RESULT_FILE}")"
  fi
  if [[ -z "${SUMMARY_ROW}" ]]; then
    if [[ -n "${RESULT_FILE}" ]]; then
      if copy_snapshot_once "${RESULT_FILE}" "${ABORTED_TSV}"; then
        RESULT_ARCHIVE="${ABORTED_TSV}"
      else
        record_evidence_error "could not archive diagnostic TSV"
      fi
    else
      RESULT_ARCHIVE=""
    fi
    VERDICT="ABORTED_DIAGNOSTICS"
    VERDICT_REASON="client exited with status ${RUNTIME_EXIT_STATUS} before a terminal SUMMARY row"
    if ! copy_snapshot_once "${RUNTIME_LOG}" "${ABORTED_LOG}"; then
      record_evidence_error "could not archive aborted runtime log"
    fi
    PRESTOP_ARCHIVE=""
    if copy_snapshot_once "${RUNTIME_LOG}" "${PRESTOP_LOG}"; then
      PRESTOP_ARCHIVE="${PRESTOP_LOG}"
    else
      record_evidence_error "could not archive pre-stop runtime log"
    fi
    record_process_sample "pre-stop"
    check_launcher_log
    printf 'PACKAGED_RUNTIME_VERDICT\t%s\t%s\n' "${VERDICT}" "${VERDICT_REASON}"
    printf 'PACKAGED_RUNTIME_CLIENT_EXIT_STATUS\t%s\n' "${RUNTIME_EXIT_STATUS}"
    if process_group_is_running; then
      write_run_report "${VERDICT}" "${VERDICT_REASON}" running "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}"
    fi
    stop_owned_runtime
    write_run_report "${VERDICT}" "${VERDICT_REASON}" "${RUNTIME_EXIT_STATUS:-unknown}" "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}"
    if [[ "${RUNTIME_EXIT_STATUS:-}" == 124 ]]; then exit 124; fi
    exit 125
  fi
fi

classify_summary "${SUMMARY_ROW}"

if copy_snapshot_once "${RESULT_FILE}" "${ARCHIVED_TSV}"; then
  RESULT_ARCHIVE="${ARCHIVED_TSV}"
  RESULT_FILE="${RESULT_ARCHIVE}"
  SUMMARY_ROW="$(find_summary_row "${RESULT_FILE}")"
  if [[ -n "${SUMMARY_ROW}" ]]; then
    classify_summary "${SUMMARY_ROW}"
  else
    VERDICT="EVIDENCE_INCOMPLETE"
    VERDICT_REASON="captured TSV snapshot lacked a complete terminal SUMMARY row"
  fi
else
  RESULT_ARCHIVE=""
  record_evidence_error "could not archive terminal TSV"
fi
PRESTOP_ARCHIVE=""
if copy_snapshot_once "${RUNTIME_LOG}" "${PRESTOP_LOG}"; then
  PRESTOP_ARCHIVE="${PRESTOP_LOG}"
else
  record_evidence_error "could not archive pre-stop runtime log"
fi
record_process_sample "pre-stop"
check_launcher_log
printf 'PACKAGED_RUNTIME_VERDICT\t%s\t%s\n' "${VERDICT}" "${VERDICT_REASON}"
printf 'PACKAGED_RUNTIME_TSV\t%s\n' "${RESULT_ARCHIVE}"
printf 'PACKAGED_RUNTIME_PRESTOP_LOG\t%s\n' "${PRESTOP_ARCHIVE}"
printf 'PACKAGED_RUNTIME_LAUNCHER_LOG\t%s\n' "${RUNTIME_LAUNCHER_LOG}"
if process_group_is_running; then
  write_run_report "${VERDICT}" "${VERDICT_REASON}" running "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}"
fi
stop_owned_runtime
printf 'PACKAGED_RUNTIME_CLIENT_EXIT_STATUS\t%s\n' "${RUNTIME_EXIT_STATUS:-unknown}"
write_run_report "${VERDICT}" "${VERDICT_REASON}" "${RUNTIME_EXIT_STATUS:-unknown}" "${RESULT_ARCHIVE}" "${PRESTOP_ARCHIVE}"
tail -n 24 "${RUNTIME_LOG}" || true
if [[ "${VERDICT}" == "PASS" ]]; then exit 0; fi
if [[ "${VERDICT}" == "EVIDENCE_INCOMPLETE" ]]; then exit 3; fi
exit 1
