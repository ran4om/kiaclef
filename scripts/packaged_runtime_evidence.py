#!/usr/bin/env python3
"""Check ordered evidence emitted by packaged runtime acceptance scenarios."""

from __future__ import annotations

import csv
import re
import sys
from collections import Counter
from pathlib import Path


CONTAINER_OPERATIONS = (
    ("task", "loot-diamonds", "diamondx3", "VERIFY_LOOT_DIAMONDS"),
    ("command", "deposit-one-diamond", "deposit diamond", "VERIFY_DEPOSIT_ONE_DIAMOND"),
    ("command", "deposit-diamonds", "deposit diamond 2", "VERIFY_DEPOSIT_DIAMONDS"),
    ("task", "loot-emeralds", "emeraldx5", "VERIFY_LOOT_EMERALDS"),
    ("command", "stash-emeralds", "stash emerald 5", "VERIFY_STASH_EMERALDS"),
    ("command", "stash-overflow-oak-planks", "stash oak_planks 8", "VERIFY_STASH_OVERFLOW"),
)

READY_FIELDS = (
    "initializationComplete=true",
    "initializationClientThread=true",
    "clientThread=true",
    "commandExecutor=true",
    "taskRunner=true",
    "storageTracker=true",
    "settings=true",
    "chunkTracker=true",
    "playerChunkClientLoaded=true",
    "playerChunkTracked=true",
)

PLAYER_STATE = {
    "VERIFY_LOOT_DIAMONDS": {"inventory": 3, "diamond": 3, "emerald": 0, "oakLog": 0, "oakPlanks": 0},
    "VERIFY_DEPOSIT_ONE_DIAMOND": {"inventory": 2, "diamond": 2, "emerald": 0, "oakLog": 0, "oakPlanks": 0},
    "VERIFY_DEPOSIT_DIAMONDS": {"inventory": 0, "diamond": 0, "emerald": 0, "oakLog": 0, "oakPlanks": 0},
    "VERIFY_LOOT_EMERALDS": {"inventory": 5, "diamond": 0, "emerald": 5, "oakLog": 0, "oakPlanks": 0},
    "VERIFY_STASH_EMERALDS": {"inventory": 0, "diamond": 0, "emerald": 0, "oakLog": 0, "oakPlanks": 0},
    "VERIFY_STASH_OVERFLOW": {"inventory": 0, "diamond": 0, "emerald": 0, "oakLog": 0, "oakPlanks": 0},
}

TOTAL_DIAMONDS = {
    "VERIFY_LOOT_DIAMONDS": 0,
    "VERIFY_DEPOSIT_ONE_DIAMOND": 1,
    "VERIFY_DEPOSIT_DIAMONDS": 3,
    "VERIFY_LOOT_EMERALDS": 3,
    "VERIFY_STASH_EMERALDS": 3,
    "VERIFY_STASH_OVERFLOW": 3,
}

STASH_EMERALDS = {
    "VERIFY_LOOT_DIAMONDS": 5,
    "VERIFY_DEPOSIT_ONE_DIAMOND": 5,
    "VERIFY_DEPOSIT_DIAMONDS": 5,
    "VERIFY_LOOT_EMERALDS": 0,
    "VERIFY_STASH_EMERALDS": 5,
    "VERIFY_STASH_OVERFLOW": 5,
}

CONTAINER_ITEMS = {
    "minecraft:diamond": "diamond",
    "minecraft:dirt": "dirt",
    "minecraft:emerald": "emerald",
    "minecraft:cobblestone": "cobblestone",
    "minecraft:oak_planks": "oakPlanks",
}


def _find(rows: list[list[str]], start: int, predicate, description: str) -> tuple[int, list[str]]:
    for index in range(start, len(rows)):
        row = rows[index]
        if predicate(row):
            return index, row
    raise ValueError(f"missing or out-of-order {description}")


def _has_fields(row: list[str], expected: tuple[str, ...]) -> bool:
    fields = set(row[1:])
    return all(value in fields for value in expected)


def _value(snapshot: str, key: str) -> str | None:
    """Read one comma-delimited field with exact key and value boundaries."""
    matches = list(re.finditer(rf"(?:^|,){re.escape(key)}=([^,]*)(?=,|$)", snapshot))
    if len(matches) > 1:
        raise ValueError(f"snapshot contains duplicate {key} fields")
    return matches[0].group(1) if matches else None


def _nested_snapshot(snapshot: str, key: str, next_key: str | None) -> str | None:
    marker = lambda name: rf"(?:^|,){re.escape(name)}="
    starts = list(re.finditer(marker(key), snapshot))
    if len(starts) > 1:
        raise ValueError(f"snapshot contains duplicate {key} sections")
    if not starts:
        return None
    if next_key is not None and len(list(re.finditer(marker(next_key), snapshot))) != 1:
        raise ValueError(f"snapshot must contain exactly one {next_key} section after {key}")
    end = rf"(?=,{re.escape(next_key)}=|$)" if next_key else r"$"
    match = re.search(rf"(?:^|,){re.escape(key)}=(.*?){end}", snapshot)
    return match.group(1) if match else None


def _player_state(snapshot: str, phase: str, source: str) -> None:
    # Inventory/UI fields belong to the leading player section; chest/cache
    # sections contain their own diamond and item-count fields.
    snapshot = re.split(r",(?:loot|lootCached)=", snapshot, maxsplit=1)[0]
    expected = PLAYER_STATE[phase]
    for key, value in expected.items():
        actual = _value(snapshot, key)
        if actual != str(value):
            raise ValueError(f"{source} {phase} has {key}={actual!r}; expected {value}")
    for key in ("inventoryMenu", "cursorEmpty", "craftingGridEmpty", "uiClean"):
        if _value(snapshot, key) != "true":
            raise ValueError(f"{source} {phase} omitted exact {key}=true UI state")


def _container_contents(snapshot: str, label: str) -> tuple[dict[str, int], dict[int, tuple[str, int]], int]:
    if snapshot == "missing":
        raise ValueError(f"server snapshot omitted {label} container")
    counts: dict[str, int] = {}
    for key in ("diamond", "dirt", "emerald", "cobblestone", "oakPlanks"):
        raw = _value(snapshot, key)
        if raw is None or not raw.isdigit():
            raise ValueError(f"{label} container omitted a valid {key} count")
        counts[key] = int(raw)

    slots_value = _value(snapshot, "slots")
    if slots_value is None or not slots_value.startswith("[") or not slots_value.endswith("]"):
        raise ValueError(f"{label} container omitted a well-formed slot list")
    slot_contents: dict[int, tuple[str, int]] = {}
    if slots_value != "[]":
        for entry in slots_value[1:-1].split(";"):
            match = re.fullmatch(r"([0-9]+)=(minecraft:[a-z0-9_]+)x([0-9]+)", entry)
            if not match:
                raise ValueError(f"{label} container has malformed slot entry {entry!r}")
            slot, item, count = int(match.group(1)), match.group(2), int(match.group(3))
            if slot > 26 or slot in slot_contents or count < 1 or count > 64:
                raise ValueError(f"{label} container has invalid slot entry {entry!r}")
            if item not in CONTAINER_ITEMS:
                raise ValueError(f"{label} container has unexpected item {item}")
            slot_contents[slot] = (item, count)

    observed = Counter({key: 0 for key in counts})
    for item, count in slot_contents.values():
        observed[CONTAINER_ITEMS[item]] += count
    if any(observed[key] != count for key, count in counts.items()):
        raise ValueError(f"{label} aggregate counts disagree with its exact slot contents")
    empty_slots = _value(snapshot, "emptySlots")
    if empty_slots != str(27 - len(slot_contents)):
        raise ValueError(f"{label} empty-slot count disagrees with its 27-slot contents")
    return counts, slot_contents, int(empty_slots)


def _cache_contents(snapshot: str, label: str) -> tuple[dict[str, int], int] | None:
    if snapshot == "not-yet-observed":
        return None
    counts: dict[str, int] = {}
    for key in ("diamond", "dirt", "emerald", "cobblestone", "oakPlanks"):
        raw = _value(snapshot, key)
        if raw is None or not raw.isdigit():
            raise ValueError(f"{label} cache omitted a valid {key} count")
        counts[key] = int(raw)
    empty_slots = _value(snapshot, "emptySlots")
    if empty_slots is None or not empty_slots.isdigit() or int(empty_slots) > 27:
        raise ValueError(f"{label} cache omitted a valid empty-slot count")
    return counts, int(empty_slots)


def _command_matches(row: list[str], label: str, expected: str) -> bool:
    if len(row) < 3 or row[1] != f"label={label}":
        return False
    command = "\t".join(row[2:])
    if expected.startswith("stash "):
        words = command.split()
        if len(words) != 9 or words[0] != "stash" or words[1:4] != words[4:7]:
            return False
        return " ".join(words[7:]) == expected.removeprefix("stash ")
    return command == expected


def _poll_pair(rows: list[list[str]], start: int, phase: str) -> tuple[int, list[str], list[str]]:
    successful_servers: dict[str, tuple[int, list[str]]] = {}
    for index in range(start, len(rows)):
        row = rows[index]
        if len(row) < 4:
            continue
        if row[0] == "CONTAINER_SERVER_POLL" and row[1] == f"phase={phase}":
            if not row[2].startswith("pollId="):
                continue
            poll_id = row[2][len("pollId="):]
            if re.fullmatch(r"[0-9]+", poll_id) is None:
                continue
            if row[3] == "valid=true":
                successful_servers[poll_id] = (index, row)
        elif row[0] == "CONTAINER_CLIENT_VERIFY" and row[1] == f"phase={phase}":
            if not row[2].startswith("pollId="):
                continue
            poll_id = row[2][len("pollId="):]
            if re.fullmatch(r"[0-9]+", poll_id) is None:
                continue
            server = successful_servers.get(poll_id)
            if server is None or row[3] != "valid=true" or server[0] >= index:
                continue
            return index, server[1], row
    raise ValueError(f"missing successful synchronized server/client poll pair for {phase}")


def _require_poll_details(
    phase: str,
    server_row: list[str],
    client_row: list[str],
    expected_diamond_split: tuple[int, int] | None = None,
) -> tuple[int, int]:
    server_text = "\t".join(server_row[4:])
    client_text = "\t".join(client_row[4:])
    _player_state(server_text, phase, "server poll")
    _player_state(client_text, phase, "client verification")

    loot = _container_contents(_nested_snapshot(server_text, "loot", "stash") or "missing", "loot")
    stash = _container_contents(_nested_snapshot(server_text, "stash", "overflow") or "missing", "stash")
    loot_counts, _, _ = loot
    stash_counts, _, _ = stash
    diamond_split = (loot_counts["diamond"], stash_counts["diamond"])
    if sum(diamond_split) != TOTAL_DIAMONDS[phase]:
        raise ValueError(f"server chests do not preserve the expected diamond total in {phase}")
    if expected_diamond_split is not None and diamond_split != expected_diamond_split:
        raise ValueError(f"server chest diamond allocation changed in {phase}: {diamond_split} != {expected_diamond_split}")
    if loot_counts != {"diamond": loot_counts["diamond"], "dirt": 2, "emerald": 0,
                       "cobblestone": 0, "oakPlanks": 0}:
        raise ValueError(f"server loot chest contents changed unexpectedly in {phase}")
    if stash_counts != {"diamond": stash_counts["diamond"], "dirt": 0,
                        "emerald": STASH_EMERALDS[phase], "cobblestone": 4, "oakPlanks": 0}:
        raise ValueError(f"server stash chest contents changed unexpectedly in {phase}")
    expected_loot_empty = 26 - int(loot_counts["diamond"] > 0)
    expected_stash_empty = 27 - 1 - int(STASH_EMERALDS[phase] > 0) - int(stash_counts["diamond"] > 0)
    if loot[2] != expected_loot_empty or stash[2] != expected_stash_empty:
        raise ValueError(f"server chest slot occupancy changed in {phase}")

    if phase == "VERIFY_STASH_OVERFLOW":
        overflow_text = _nested_snapshot(server_text, "overflow", None)
        if overflow_text is None:
            raise ValueError("final server poll omitted overflow chest state")
        overflow_counts, overflow_slots, overflow_empty = _container_contents(overflow_text, "overflow")
        plank_stacks = sorted(count for item, count in overflow_slots.values()
                              if item == "minecraft:oak_planks")
        if overflow_counts != {"diamond": 0, "dirt": 1, "emerald": 0,
                               "cobblestone": 0, "oakPlanks": 68} \
                or plank_stacks != [4, 64] or overflow_slots.get(5) != ("minecraft:dirt", 1) \
                or overflow_empty != 24:
            raise ValueError("final server poll omitted 64/4 planks, dirt at slot 5, or 24 empty slots")

    client_loot = _nested_snapshot(client_text, "lootCached", "stashCached")
    client_stash = _nested_snapshot(client_text, "stashCached", "overflowCached")
    client_overflow = _nested_snapshot(client_text, "overflowCached", None)
    required_caches = {"lootCached"}
    if phase in ("VERIFY_LOOT_EMERALDS", "VERIFY_STASH_EMERALDS", "VERIFY_STASH_OVERFLOW"):
        required_caches.add("stashCached")
    if stash_counts["diamond"] > 0:
        required_caches.add("stashCached")
    for key, raw, expected in (
        ("lootCached", client_loot, loot),
        ("stashCached", client_stash, stash),
    ):
        if raw is None:
            raise ValueError(f"client verification {phase} omitted {key}")
        observed_cache = _cache_contents(raw, key)
        if key in required_caches and observed_cache is None:
            raise ValueError(f"client verification {phase} did not observe required {key}")
        if observed_cache is not None and (observed_cache[0] != expected[0] or observed_cache[1] != expected[2]):
            raise ValueError(f"client {key} does not preserve synchronized chest contents in {phase}")

    if phase == "VERIFY_STASH_OVERFLOW":
        if client_overflow is None:
            raise ValueError("final client verification omitted overflow cache")
        observed_overflow = _cache_contents(client_overflow, "overflow")
        if observed_overflow != ({"diamond": 0, "dirt": 1, "emerald": 0,
                                  "cobblestone": 0, "oakPlanks": 68}, 24):
            raise ValueError("final client verification omitted synchronized overflow cache totals")
    return diamond_split


def check_container_acceptance(tsv_path: str | Path, runtime_log_path: str | Path) -> None:
    """Raise ValueError unless the container result contains the full ordered contract."""
    with Path(tsv_path).open(encoding="utf-8", newline="") as source:
        rows = list(csv.reader(source, delimiter="\t"))

    start, _ = _find(rows, 0, lambda row: len(row) >= 3 and row[0] == "HARNESS_START"
                     and row[2] == "runtimeStart=containers", "container HARNESS_START")
    world, row = _find(rows, start + 1, lambda row: row and row[0] == "HARNESS_WORLD_READY",
                       "HARNESS_WORLD_READY")
    if not _has_fields(row, ("clientThread=true", "integratedServer=true")):
        raise ValueError("HARNESS_WORLD_READY did not confirm client thread and integrated server")
    ready, row = _find(rows, world + 1, lambda row: row and row[0] == "ALTOCLEF_READY", "ALTOCLEF_READY")
    if not _has_fields(row, READY_FIELDS):
        raise ValueError("ALTOCLEF_READY omitted a required initialized component or loaded/tracked player chunk")
    begun, _ = _find(rows, ready + 1, lambda row: row and row[0] == "START", "START")
    setup, _ = _find(rows, begun + 1, lambda row: len(row) >= 2 and row[0] == "CONTAINER_SETUP"
                      and row[1] == "ok", "CONTAINER_SETUP ok")

    runtime_log = Path(runtime_log_path).read_text(encoding="utf-8", errors="replace")
    if not re.search(r"ALTO CLEF: Global Init\s+clientThread=true", runtime_log):
        raise ValueError("runtime log omitted Global Init with clientThread=true")

    cursor = setup + 1
    deposited_diamond_split: tuple[int, int] | None = None
    for kind, label, expected, phase in CONTAINER_OPERATIONS:
        if kind == "task":
            action, row = _find(rows, cursor,
                                lambda row: len(row) >= 4 and row[0] == "CONTAINER_TASK"
                                and row[1] == f"label={label}" and row[2] == "LootContainerTask"
                                and any(field == f"target={expected}" for field in row[3:]),
                                f"{label} LootContainerTask start")
        else:
            action, row = _find(rows, cursor,
                                lambda row: row and row[0] == "CONTAINER_COMMAND"
                                and _command_matches(row, label, expected),
                                f"{label} command")

        completed, _ = _find(rows, action + 1,
                             lambda row: len(row) >= 3 and row[0] == "CONTAINER_OPERATION_COMPLETE"
                             and row[1] == f"label={label}" and row[2] == f"phase={phase}",
                             f"{label} completion")

        if label == "stash-overflow-oak-planks":
            overflow_setup, overflow_row = _find(
                rows, cursor,
                lambda row: len(row) >= 2 and row[0] == "CONTAINER_OVERFLOW_SETUP" and row[1] == "ok",
                "CONTAINER_OVERFLOW_SETUP ok")
            if overflow_setup >= action:
                raise ValueError("overflow setup did not precede the overflow stash command")
            overflow_fields = set(overflow_row[2:])
            if not {"planks=60", "dirt=1", "dirtSlot=5", "player logs=2"}.issubset(overflow_fields):
                raise ValueError("overflow setup did not expose the seeded 60 planks, dirt sentinel, and two logs")

        verified, server_row, client_row = _poll_pair(rows, completed + 1, phase)
        phase_diamond_split = _require_poll_details(
            phase, server_row, client_row,
            deposited_diamond_split if phase in (
                "VERIFY_LOOT_EMERALDS", "VERIFY_STASH_EMERALDS", "VERIFY_STASH_OVERFLOW"
            ) else None,
        )
        if phase == "VERIFY_DEPOSIT_DIAMONDS":
            deposited_diamond_split = phase_diamond_split
        cursor = verified + 1

    accepted, _ = _find(rows, cursor, lambda row: len(row) >= 2 and row[0] == "CONTAINER_ACCEPTANCE"
                         and row[1] == "PASS", "CONTAINER_ACCEPTANCE PASS")
    if any(len(row) >= 2 and row[0] == "SUMMARY" and row[1] == "FAIL" for row in rows):
        raise ValueError("a SUMMARY FAIL exists in this run, so a later pass cannot supersede it")
    summary, row = _find(rows, accepted + 1, lambda row: len(row) >= 3 and row[0] == "SUMMARY"
                         and row[1] == "PASS"
                         and re.search(r"(?:^|\s)runtimeStart=containers(?:\s|$)", " ".join(row[2:])) is not None,
                         "terminal SUMMARY PASS for containers")
    final_row = max((index for index, candidate in enumerate(rows) if any(field.strip() for field in candidate)),
                    default=-1)
    if summary != final_row:
        raise ValueError("SUMMARY PASS is not the final nonempty runtime evidence row")


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: packaged_runtime_evidence.py <containers.tsv> <runtime.log>", file=sys.stderr)
        return 2
    try:
        check_container_acceptance(argv[1], argv[2])
    except (OSError, ValueError) as error:
        print(f"CONTAINER_EVIDENCE_CHECK\tFAIL\t{error}", file=sys.stderr)
        return 1
    print("CONTAINER_EVIDENCE_CHECK\tPASS\tordered server/client phases, exact overflow slots, and startup evidence")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
