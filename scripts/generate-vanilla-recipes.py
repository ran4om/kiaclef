#!/usr/bin/env python3
"""Generate AltoClef's normalized vanilla crafting recipe index from a Minecraft jar.

Usage: python3 scripts/generate-vanilla-recipes.py PATH_TO_MINECRAFT_26.2_MERGED.jar
The output is committed at src/main/resources/altoclef/recipes26.2.json.
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/altoclef/recipes26.2.json"
PREFIX = "data/"


def load_json(zf: zipfile.ZipFile, name: str):
    try:
        return json.loads(zf.read(name))
    except KeyError:
        return None


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    jar = Path(sys.argv[1])
    with zipfile.ZipFile(jar) as zf:
        tags = {}
        for name in zf.namelist():
            if not name.startswith("data/") or "/tags/item/" not in name or not name.endswith(".json"):
                continue
            namespace, path = name[5:].split("/tags/item/", 1)
            tags[f"{namespace}:{path[:-5]}"] = load_json(zf, name)

        def expand(ref: str, visiting=()):
            if not ref.startswith("#"):
                return [ref if ":" in ref else "minecraft:" + ref]
            key = ref[1:]
            if key in visiting:
                return []
            tag = tags.get(key)
            if not tag:
                return []
            found = []
            for value in tag.get("values", []):
                if isinstance(value, str):
                    found.extend(expand(value, visiting + (key,)))
                elif isinstance(value, dict) and not value.get("required", True):
                    # Optional tag additions may be absent in vanilla. Ignore them when absent;
                    # if present, preserve their entries as candidates.
                    found.extend(expand(value["id"], visiting + (key,)))
                elif isinstance(value, dict):
                    found.extend(expand(value["id"], visiting + (key,)))
            return sorted(set(found))

        recipes = []
        for name in zf.namelist():
            if not name.startswith("data/") or "/recipe/" not in name or not name.endswith(".json"):
                continue
            namespace, path = name[5:].split("/recipe/", 1)
            data = load_json(zf, name)
            if not isinstance(data, dict):
                continue
            typ = data.get("type", "")
            # Crafting only. Exclude special recipes, component-dependent results, and
            # recipes with unsupported serializer semantics.
            if typ not in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless"):
                continue
            result = data.get("result")
            if not isinstance(result, dict) or not isinstance(result.get("id"), str):
                continue
            output = result["id"]
            if not output.startswith("minecraft:") or set(result) - {"id", "count"}:
                continue
            count = result.get("count", 1)
            if not isinstance(count, int) or count < 1:
                continue
            ingredients = []
            grid = [None] * 9
            grid_size = 9
            if typ.endswith("crafting_shaped"):
                pattern = data.get("pattern")
                key = data.get("key")
                if not isinstance(pattern, list) or not pattern or len(pattern) > 3 or not isinstance(key, dict):
                    continue
                if any(not isinstance(row, str) or len(row) > 3 for row in pattern):
                    continue
                # Vanilla shaped recipes use leading/trailing spaces only as padding.
                width = max(map(len, pattern))
                height = len(pattern)
                if width > 3:
                    continue
                left = min((len(row) - len(row.lstrip()) for row in pattern), default=0)
                top = 0
                valid = True
                for y, row in enumerate(pattern):
                    for x in range(width):
                        char = row[x] if x < len(row) else " "
                        if char == " ":
                            continue
                        ref = key.get(char)
                        if ref is None:
                            valid = False
                            break
                        values = expand(ref) if isinstance(ref, str) else []
                        if not values:
                            valid = False
                            break
                        slot = (y + top) * 3 + (x - left)
                        if not 0 <= slot < 9:
                            valid = False
                            break
                        grid[slot] = values
                    if not valid:
                        break
                if not valid or not any(grid):
                    continue
                # Reject malformed/unreferenced key symbols.
                used = {ch for row in pattern for ch in row if ch != " "}
                if used != set(key):
                    continue
                # AltoClef distinguishes inventory crafting from table crafting by slot count.
                # Preserve a true 2x2 recipe as four slots rather than padded 3x3.
                if all(grid[y * 3 + x] is None for y in range(2) for x in range(2, 3)) and all(
                    grid[y * 3 + x] is None for y in range(2, 3) for x in range(3)
                ):
                    grid = [grid[0], grid[1], grid[3], grid[4]]
                    grid_size = 4
            else:
                raw = data.get("ingredients")
                if not isinstance(raw, list) or not 1 <= len(raw) <= 9:
                    continue
                slots = []
                for ingredient in raw:
                    refs = ingredient if isinstance(ingredient, list) else [ingredient]
                    values = sorted(set(item for ref in refs if isinstance(ref, str) for item in expand(ref)))
                    if not values:
                        slots = []
                        break
                    slots.append(values)
                if not slots:
                    continue
                # Deterministic row-major placement; 2x2 grid if possible, else 3x3.
                size = 4 if len(slots) <= 4 else 9
                for i, values in enumerate(slots):
                    grid[i] = values
                grid_size = size
            if not ingredients:
                ingredients = [slot for slot in grid if slot]
            if not ingredients or any(not slot for slot in ingredients):
                continue
            recipes.append({
                "id": f"{namespace}:{path[:-5]}",
                "output": output,
                "count": count,
                "grid": grid[:grid_size],
                "ingredients": ingredients,
            })
    recipes.sort(key=lambda r: (r["output"], r["id"]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"minecraft": "26.2", "recipes": recipes}, indent=2) + "\n")
    print(f"wrote {len(recipes)} recipes to {OUT}")


if __name__ == "__main__":
    main()
