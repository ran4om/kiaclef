#!/usr/bin/env bash
# Generate a fresh, untouched Survival save with the vanilla 26.2 dedicated server,
# then install it into run/productionProbe/saves/<name> with a pre-run manifest.
# Usage: scripts/create-fresh-save.sh <save-name> [seed]
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
NAME="${1:?save name required}"
SEED="${2:-}"
SERVER_JAR="${MINECRAFT_SERVER_JAR:-${HOME}/.gradle/caches/fabric-loom/26.2/minecraft-server.jar}"
DEST="${PROJECT_ROOT}/run/productionProbe/saves/${NAME}"
MANIFEST="${PROJECT_ROOT}/.audit/gameplay/save-${NAME// /_}-manifest.txt"

if [[ -e "${DEST}" ]]; then
  echo "Save already exists: ${DEST}" >&2
  exit 2
fi
if pgrep -f 'net.minecraft.client.main.Main|KnotClient' >/dev/null; then
  echo "A Minecraft client is running; refusing to generate a world concurrently." >&2
  exit 2
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/altoclef-save.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
cd "${WORK}"
echo "eula=true" > eula.txt
cat > server.properties <<EOF
level-name=world
level-seed=${SEED}
gamemode=survival
difficulty=normal
generate-structures=true
level-type=minecraft\:normal
online-mode=false
server-port=0
enable-rcon=false
spawn-protection=0
EOF

# Starting the server generates spawn chunks; SIGTERM runs its save-on-shutdown hook.
java -Xmx2G -jar "${SERVER_JAR}" --nogui < /dev/null > server.log 2>&1 &
SERVER_PID=$!
for _ in $(seq 600); do
  grep -q 'Done (' server.log && break
  kill -0 "${SERVER_PID}" 2>/dev/null || break
  sleep 1
done
if ! grep -q 'Done (' server.log; then
  kill "${SERVER_PID}" 2>/dev/null || true
  tail -30 server.log >&2
  exit 1
fi
kill -TERM "${SERVER_PID}"
wait "${SERVER_PID}" || true
grep -q 'All dimensions are saved' server.log || { tail -30 server.log >&2; exit 1; }
[[ -f world/level.dat ]] || { echo "No level.dat generated" >&2; exit 1; }

mkdir -p "$(dirname "${DEST}")"
cp -a world "${DEST}"
{
  printf 'save_name=%s\n' "${NAME}"
  printf 'created_utc=%s\n' "$(date -u +%FT%TZ)"
  printf 'generator=vanilla dedicated server %s sha256=%s\n' "26.2" "$(sha256sum "${SERVER_JAR}" | cut -d' ' -f1)"
  printf 'requested_seed=%s\n' "${SEED:-random}"
  printf 'reported_seed=%s\n' "$(grep -oE 'seed[^0-9-]*-?[0-9]+' server.log | head -1)"
  printf 'settings=survival normal default-worldgen bonus-chest-disabled structures-enabled\n'
  printf 'server_log_spawn=%s\n' "$(grep -E 'Preparing (spawn|start region)|Time elapsed' server.log | head -2 | tr '\n' ' ')"
  printf 'files_begin\n'
  (cd "${DEST}" && find . -type f -print0 | sort -z | xargs -0 sha256sum)
  printf 'files_end\n'
} > "${MANIFEST}"
echo "Created ${DEST}"
echo "Manifest ${MANIFEST}"
