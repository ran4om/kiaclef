#!/usr/bin/env python3
"""Fast structural checks for the generated vanilla recipe index (no Minecraft runtime)."""
import json
from pathlib import Path

index = Path(__file__).resolve().parents[1] / "src/main/resources/altoclef/recipes26.2.json"
data = json.loads(index.read_text())
assert data["minecraft"] == "26.2"
recipes = data["recipes"]
assert len(recipes) > 900
assert len({recipe["id"] for recipe in recipes}) == len(recipes)
for recipe in recipes:
    assert recipe["id"].startswith("minecraft:")
    assert recipe["output"].startswith("minecraft:")
    assert recipe["count"] > 0
    assert len(recipe["grid"]) in (4, 9)
    assert any(slot for slot in recipe["grid"])
    for slot in recipe["grid"]:
        if slot is not None:
            assert slot and all(item.startswith("minecraft:") for item in slot)

by_output = {recipe["output"]: recipe for recipe in recipes}
pickaxe = by_output["minecraft:diamond_pickaxe"]
assert pickaxe["grid"][0] == ["minecraft:diamond"]
assert pickaxe["grid"][4] == ["minecraft:stick"]
assert pickaxe["grid"][7] == ["minecraft:stick"]
planks = by_output["minecraft:acacia_planks"]
assert planks["count"] == 4
assert {"minecraft:acacia_log", "minecraft:acacia_wood"}.issubset(planks["grid"][0])
assert len(by_output["minecraft:crafting_table"]["grid"]) == 4
assert len(by_output["minecraft:stick"]["grid"]) == 4
print(f"validated {len(recipes)} vanilla crafting recipes")
