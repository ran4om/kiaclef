# End gateway fixture bytecode audit

The opt-in `dimensions` runtime fixture uses Minecraft 26.2's actual `Feature.END_GATEWAY`
implementation to build an outer-island gateway with a configured exact exit. The fixture's
geometry assertion was checked against the mapped 26.2 bytecode, rather than assuming a solid
3-by-3 cap around the portal.

## Evidence source

- Jar: `/home/kiarad/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
- Class: `net.minecraft.world.level.levelgen.feature.EndGatewayFeature`
- Inspection: `javap -classpath <jar> -p -c net.minecraft.world.level.levelgen.feature.EndGatewayFeature`
- Feature bounding loop: bytecode offsets 19–37 iterates from `origin + (-1,-2,-1)` through `origin + (1,2,1)`.
- The three center-coordinate booleans are calculated at offsets 66–121. Offset 123–145 computes whether `abs(y - originY) == 2`.
- Offsets 147–202 place `END_GATEWAY` at the exact center. Offsets 203–221 clear the remaining cells on the center y layer.
- Offsets 224–254 place bedrock only at the center x/z position for the y offsets ±2.
- Offsets 255–283 clear the four corners at y offsets ±1 and clear the non-center cells at y offsets ±2. The final bedrock branch at offsets 286–296 is shared by the y=±1 cross and y=±2 center cells; evaluating its branch conditions confirms the only y=±2 bedrock cells are the center x/z cells.

## Expected feature volume

For `dx,dz ∈ [-1,1]` and `dy ∈ [-2,2]` relative to the gateway origin:

- `(0,0,0)` is `END_GATEWAY`.
- Other cells at `dy == 0` are `AIR`.
- At `dy == ±1`, cells with `dx == 0 || dz == 0` are `BEDROCK`; the four corners are `AIR`.
- At `dy == ±2`, only `(dx,dz) == (0,0)` is `BEDROCK`; other cells are `AIR`.

`RuntimeAcceptanceMod.prepareDimensionFixtures()` validates this whole 3×5×3 feature volume and
also checks that the gateway block entity reports the configured exact central-island exit.
