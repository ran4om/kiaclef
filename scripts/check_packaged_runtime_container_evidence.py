#!/usr/bin/env python3
"""Exercise the ordered Run 141 evidence contract without launching Minecraft."""

from __future__ import annotations

import tempfile
from pathlib import Path

from packaged_runtime_evidence import check_container_acceptance


PHASES = (
    "VERIFY_LOOT_DIAMONDS",
    "VERIFY_DEPOSIT_ONE_DIAMOND",
    "VERIFY_DEPOSIT_DIAMONDS",
    "VERIFY_LOOT_EMERALDS",
    "VERIFY_STASH_EMERALDS",
    "VERIFY_STASH_OVERFLOW",
)

OPS = (
    ("task", "loot-diamonds", "diamondx3", PHASES[0]),
    ("command", "deposit-one-diamond", "deposit diamond", PHASES[1]),
    ("command", "deposit-diamonds", "deposit diamond 2", PHASES[2]),
    ("task", "loot-emeralds", "emeraldx5", PHASES[3]),
    ("command", "stash-emeralds", "stash 10 64 20 10 64 20 emerald 5", PHASES[4]),
    ("command", "stash-overflow-oak-planks", "stash 13 64 20 13 64 20 oak_planks 8", PHASES[5]),
)


def chest(diamond: int = 0, dirt: int = 0, emerald: int = 0, cobblestone: int = 0,
          oak_planks: int = 0, *, overflow: bool = False) -> tuple[str, str]:
    values = {
        "diamond": diamond,
        "dirt": dirt,
        "emerald": emerald,
        "cobblestone": cobblestone,
        "oakPlanks": oak_planks,
    }
    item_ids = {
        "diamond": "minecraft:diamond",
        "dirt": "minecraft:dirt",
        "emerald": "minecraft:emerald",
        "cobblestone": "minecraft:cobblestone",
        "oakPlanks": "minecraft:oak_planks",
    }
    stacks: list[tuple[int, str, int]] = []
    if overflow:
        stacks.extend(((2, item_ids["oakPlanks"], 64), (11, item_ids["oakPlanks"], 4),
                       (5, item_ids["dirt"], dirt)))
    else:
        for slot, key in enumerate(("diamond", "dirt", "emerald", "cobblestone")):
            if values[key]:
                stacks.append((slot, item_ids[key], values[key]))
    slots = ";".join(f"{slot}={item}x{count}" for slot, item, count in stacks)
    body = ",".join(f"{key}={value}" for key, value in values.items())
    return f"{body},slots=[{slots}],emptySlots={27 - len(stacks)}", body + f",emptySlots={27 - len(stacks)}"


def fixture(include_retry: bool = False, stash_diamond_split: int = 0) -> tuple[list[str], str]:
    rows = [
        "HARNESS_START\t26.2\truntimeStart=containers",
        "HARNESS_WORLD_READY\tclientThread=true\tplayer=local\tdimension=overworld\tintegratedServer=true",
        "ALTOCLEF_READY\tinitializationComplete=true\tinitializationClientThread=true\tclientThread=true"
        "\tcommandExecutor=true\ttaskRunner=true\tstorageTracker=true\tsettings=true\tchunkTracker=true"
        "\tplayerChunkClientLoaded=true\tplayerChunkTracked=true\tplayerChunk=0,0",
        "START\t26.2\toverworld\tfixture-origin",
        "CONTAINER_SETUP\tqueued\tfixture",
        "CONTAINER_SETUP\tok\tplayer inventory empty",
    ]
    poll_id = 1
    for index, (kind, label, expected, phase) in enumerate(OPS):
        if label == "stash-overflow-oak-planks":
            rows.append("CONTAINER_OVERFLOW_SETUP\tqueued\tchest=13,64,20")
            rows.append("CONTAINER_OVERFLOW_SETUP\tok\tplanks=60\tdirt=1\tdirtSlot=5\tplayer logs=2")
        if kind == "task":
            target = f"target={expected}"
            rows.append(f"CONTAINER_TASK\tlabel={label}\tLootContainerTask\tchest={index},64,20\t{target}")
        else:
            rows.append(f"CONTAINER_COMMAND\tlabel={label}\t{expected}")
        rows.append(f"CONTAINER_OPERATION_COMPLETE\tlabel={label}\tphase={phase}")
        common = "inventoryMenu=true,cursorEmpty=true,craftingGridEmpty=true,uiClean=true"
        if include_retry and index == 0:
            rows.append(f"CONTAINER_SERVER_POLL\tphase={phase}\tpollId={poll_id}\tvalid=false\t{common}")
            rows.append(f"CONTAINER_CLIENT_VERIFY\tphase={phase}\tpollId={poll_id}\tvalid=false\t{common}")
            poll_id += 1
        phase_index = PHASES.index(phase)
        player_states = (
            {"inventory": 3, "diamond": 3, "emerald": 0},
            {"inventory": 2, "diamond": 2, "emerald": 0},
            {"inventory": 0, "diamond": 0, "emerald": 0},
            {"inventory": 5, "diamond": 0, "emerald": 5},
            {"inventory": 0, "diamond": 0, "emerald": 0},
            {"inventory": 0, "diamond": 0, "emerald": 0},
        )[phase_index]
        loot_diamonds = (0, 1 - stash_diamond_split, 3 - stash_diamond_split,
                         3 - stash_diamond_split, 3 - stash_diamond_split,
                         3 - stash_diamond_split)[phase_index]
        stash_diamonds = (0, stash_diamond_split, stash_diamond_split,
                          stash_diamond_split, stash_diamond_split,
                          stash_diamond_split)[phase_index]
        stash_emeralds = (5, 5, 5, 0, 5, 5)[phase_index]
        loot_server, loot_cache = chest(diamond=loot_diamonds, dirt=2)
        stash_server, stash_cache = chest(diamond=stash_diamonds, emerald=stash_emeralds, cobblestone=4)
        overflow_server, overflow_cache = chest(dirt=1, oak_planks=68, overflow=True)
        player = (f"inventory={player_states['inventory']},diamond={player_states['diamond']}"
                  f",emerald={player_states['emerald']},oakLog=0,oakPlanks=0")
        server_snapshot = (f"{player},{common},loot={loot_server},stash={stash_server},"
                           f"overflow={overflow_server if phase_index == 5 else 'missing'}")
        client_snapshot = (f"{player},{common},lootCached={loot_cache},"
                           f"stashCached={stash_cache if phase_index >= 3 or stash_diamonds > 0 else 'not-yet-observed'},"
                           f"overflowCached={overflow_cache if phase_index == 5 else 'not-yet-observed'}")
        rows.append(f"CONTAINER_SERVER_POLL\tphase={phase}\tpollId={poll_id}\tvalid=true\t{server_snapshot}")
        rows.append(f"CONTAINER_CLIENT_VERIFY\tphase={phase}\tpollId={poll_id}\tvalid=true\t{client_snapshot}")
        poll_id += 1
    rows.extend((
        "CONTAINER_ACCEPTANCE\tPASS\tloot + deposits + stash + two-log crafted overflow",
        "SUMMARY\tPASS\t26.2 runtimeStart=containers verified all phases",
    ))
    return rows, "[Render thread/INFO]: [STDOUT]: ALTO CLEF: Global Init\tclientThread=true\n"


def assert_pass(rows: list[str], log: str, name: str) -> None:
    with tempfile.TemporaryDirectory(prefix="container-evidence-") as temp:
        tsv = Path(temp) / "result.tsv"
        runtime_log = Path(temp) / "runtime.log"
        tsv.write_text("\n".join(rows) + "\n", encoding="utf-8")
        runtime_log.write_text(log, encoding="utf-8")
        check_container_acceptance(tsv, runtime_log)


def assert_fail(rows: list[str], log: str, name: str, expected_error: str | None = None) -> None:
    try:
        assert_pass(rows, log, name)
    except ValueError as error:
        if expected_error is not None and expected_error not in str(error):
            raise AssertionError(f"{name} failed for the wrong reason: {error}") from error
        return
    raise AssertionError(f"{name} incorrectly passed")


def main() -> None:
    rows, log = fixture()
    assert_pass(rows, log, "complete sequence")
    retry_rows, retry_log = fixture(include_retry=True)
    assert_pass(retry_rows, retry_log, "poll retry")
    alternate_split_rows, alternate_split_log = fixture(stash_diamond_split=1)
    assert_pass(alternate_split_rows, alternate_split_log, "diamonds split across both chests")

    assert_fail([
        "CONTAINER_ACCEPTANCE\tPASS\tlone acceptance row",
        "SUMMARY\tPASS\t26.2 runtimeStart=containers",
    ], log, "lone terminal rows")
    assert_fail(rows, "Global Init without clientThread evidence", "runtime init thread gate")

    for index, row in enumerate(rows):
        if "\tqueued\t" in row:
            continue
        assert_fail(rows[:index] + rows[index + 1:], log, f"required row removal {index}: {row.split(chr(9))[0]}")

    wrong_phase = rows.copy()
    client_index = next(i for i, row in enumerate(wrong_phase) if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_DIAMONDS"))
    wrong_phase[client_index] = wrong_phase[client_index].replace("VERIFY_LOOT_DIAMONDS", "VERIFY_DEPOSIT_DIAMONDS", 1)
    assert_fail(wrong_phase, log, "wrong client phase")

    wrong_poll_id = rows.copy()
    client_index = next(i for i, row in enumerate(wrong_poll_id) if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_DIAMONDS"))
    wrong_poll_id[client_index] = wrong_poll_id[client_index].replace("pollId=1", "pollId=77", 1)
    assert_fail(wrong_poll_id, log, "mismatched poll id")

    out_of_order = rows.copy()
    command_index = next(i for i, row in enumerate(out_of_order) if row.startswith("CONTAINER_COMMAND\tlabel=deposit-one-diamond"))
    command = out_of_order.pop(command_index)
    first_poll_index = next(i for i, row in enumerate(out_of_order) if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_LOOT_DIAMONDS"))
    out_of_order.insert(first_poll_index, command)
    assert_fail(out_of_order, log, "command before prior poll")

    bad_slots = rows.copy()
    server_index = next(i for i, row in enumerate(bad_slots) if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_STASH_OVERFLOW"))
    bad_slots[server_index] = bad_slots[server_index].replace("oak_planksx64", "oak_planksx63", 1)
    assert_fail(bad_slots, log, "incorrect overflow stack snapshot")

    missing_poll_prefix = rows.copy()
    client_index = next(i for i, row in enumerate(missing_poll_prefix)
                        if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_DIAMONDS"))
    missing_poll_prefix[client_index] = missing_poll_prefix[client_index].replace("pollId=1", "xpollId=1", 1)
    assert_fail(missing_poll_prefix, log, "poll ID must have the literal field name")

    false_field_boundary = rows.copy()
    server_index = next(i for i, row in enumerate(false_field_boundary)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_LOOT_DIAMONDS"))
    false_field_boundary[server_index] = false_field_boundary[server_index].replace(
        "inventoryMenu=true", "inventoryMenu=trueExtra", 1)
    assert_fail(false_field_boundary, log, "UI state field boundary")

    contradictory_player = rows.copy()
    server_index = next(i for i, row in enumerate(contradictory_player)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_LOOT_DIAMONDS"))
    contradictory_player[server_index] = contradictory_player[server_index].replace(
        "inventory=3,diamond=3", "inventory=0,diamond=3", 1)
    assert_fail(contradictory_player, log, "valid poll with contradictory player inventory")

    duplicate_player_field = rows.copy()
    server_index = next(i for i, row in enumerate(duplicate_player_field)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_LOOT_DIAMONDS"))
    duplicate_player_field[server_index] = duplicate_player_field[server_index].replace(
        ",loot=", ",inventory=0,loot=", 1)
    assert_fail(duplicate_player_field, log, "duplicate player count field")

    changed_old_chest = rows.copy()
    server_index = next(i for i, row in enumerate(changed_old_chest)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_STASH_EMERALDS"))
    changed_old_chest[server_index] = changed_old_chest[server_index].replace(
        "stash=diamond=0,dirt=0,emerald=5", "stash=diamond=0,dirt=0,emerald=4", 1)
    assert_fail(changed_old_chest, log, "later phase changed preserved stash contents")

    bad_client_cache = rows.copy()
    client_index = next(i for i, row in enumerate(bad_client_cache)
                        if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_EMERALDS"))
    bad_client_cache[client_index] = bad_client_cache[client_index].replace(
        "stashCached=diamond=0,dirt=0,emerald=0", "stashCached=diamond=0,dirt=0,emerald=1", 1)
    assert_fail(bad_client_cache, log, "valid poll with contradictory chest cache")

    fragmented_dirt = rows.copy()
    server_index = next(i for i, row in enumerate(fragmented_dirt)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_LOOT_DIAMONDS"))
    fragmented_dirt[server_index] = fragmented_dirt[server_index].replace(
        "slots=[1=minecraft:dirtx2],emptySlots=26",
        "slots=[1=minecraft:dirtx1;2=minecraft:dirtx1],emptySlots=25", 1)
    client_index = next(i for i, row in enumerate(fragmented_dirt)
                        if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_DIAMONDS"))
    fragmented_dirt[client_index] = fragmented_dirt[client_index].replace(
        "lootCached=diamond=0,dirt=2,emerald=0,cobblestone=0,oakPlanks=0,emptySlots=26",
        "lootCached=diamond=0,dirt=2,emerald=0,cobblestone=0,oakPlanks=0,emptySlots=25", 1)
    assert_fail(fragmented_dirt, log, "incorrect original chest occupancy")

    changed_allocation_rows = rows.copy()
    server_index = next(i for i, row in enumerate(changed_allocation_rows)
                        if row.startswith("CONTAINER_SERVER_POLL\tphase=VERIFY_STASH_EMERALDS"))
    original_stash, _ = chest(emerald=5, cobblestone=4)
    moved_loot, _ = chest(dirt=2)
    moved_stash, _ = chest(diamond=3, emerald=5, cobblestone=4)
    changed_allocation_rows[server_index] = (
        changed_allocation_rows[server_index]
        .replace("loot=diamond=3,dirt=2,emerald=0,cobblestone=0,oakPlanks=0,slots=[0=minecraft:diamondx3;1=minecraft:dirtx2],emptySlots=25",
                 f"loot={moved_loot}", 1)
        .replace(f"stash={original_stash}", f"stash={moved_stash}", 1)
    )
    client_index = next(i for i, row in enumerate(changed_allocation_rows)
                        if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_STASH_EMERALDS"))
    _, moved_loot_cache = chest(dirt=2)
    _, moved_stash_cache = chest(diamond=3, emerald=5, cobblestone=4)
    changed_allocation_rows[client_index] = (
        changed_allocation_rows[client_index]
        .replace("lootCached=diamond=3,dirt=2,emerald=0,cobblestone=0,oakPlanks=0,emptySlots=25",
                 f"lootCached={moved_loot_cache}", 1)
        .replace("stashCached=diamond=0,dirt=0,emerald=5,cobblestone=4,oakPlanks=0,emptySlots=25",
                 f"stashCached={moved_stash_cache}", 1)
    )
    assert_fail(changed_allocation_rows, log, "later phase changed the captured diamond allocation",
                "server chest diamond allocation changed")

    duplicate_cache = rows.copy()
    client_index = next(i for i, row in enumerate(duplicate_cache)
                        if row.startswith("CONTAINER_CLIENT_VERIFY\tphase=VERIFY_LOOT_DIAMONDS"))
    duplicate_cache[client_index] = duplicate_cache[client_index].replace(
        ",stashCached=", ",lootCached=not-yet-observed,stashCached=", 1)
    assert_fail(duplicate_cache, log, "duplicate client cache section")

    earlier_failure = rows.copy()
    summary_index = next(i for i, row in enumerate(earlier_failure) if row.startswith("SUMMARY\tPASS"))
    earlier_failure.insert(summary_index, "SUMMARY\tFAIL\tstartup failure before later pass")
    assert_fail(earlier_failure, log, "earlier terminal failure cannot be superseded")

    nonterminal_pass = rows + ["POST_ACCEPTANCE\tunexpected trailing evidence"]
    assert_fail(nonterminal_pass, log, "summary pass must be the final nonempty row")

    wrong_summary_boundary = rows.copy()
    wrong_summary_boundary[-1] = wrong_summary_boundary[-1].replace(
        "runtimeStart=containers", "runtimeStart=containersExtra", 1)
    assert_fail(wrong_summary_boundary, log, "summary runtime mode field boundary")

    print("PACKAGED_RUNTIME_CONTAINER_EVIDENCE_CHECK\tPASS\tphase inventories, synchronized chest/cache totals, flexible plank slots, ordering and failure precedence")


if __name__ == "__main__":
    main()
