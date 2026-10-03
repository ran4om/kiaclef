package adris.altoclef.util.schematic;

import baritone.api.BaritoneAPI;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.Pair;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import baritone.utils.schematic.format.defaults.LitematicaSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/** Loads the schematic formats registered by Baritone and snapshots active Litematica placements. */
public final class SchematicLoader {
    private static final String LITEMATICA_HELPER = "baritone.utils.schematic.litematica.LitematicaHelper";
    private static final long MAX_LITEMATIC_BLOCKS = 16_777_216L;
    private static final int MAX_LITEMATIC_REGIONS = 4096;

    private SchematicLoader() {}

    /**
     * Loads validated .litematic files with Baritone's decoder and other supported formats from its registry.
     * The returned origin is zero; callers may supply a different origin when starting a build.
     */
    public static SchematicSnapshot load(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Schematic file does not exist: " + path);
        }

        String fileName = path.getFileName().toString();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (!(lowerName.endsWith(".litematic") || lowerName.endsWith(".schem") || lowerName.endsWith(".schematic"))) {
            throw new IOException("Unsupported schematic extension: " + fileName);
        }

        IStaticSchematic schematic;
        if (lowerName.endsWith(".litematic")) {
            schematic = parseLitematic(path);
        } else {
            Optional<ISchematicFormat> format = BaritoneAPI.getProvider()
                    .getSchematicSystem().getByFile(path.toFile());
            if (format.isEmpty()) {
                throw new IOException("Baritone has no loader registered for " + fileName);
            }
            schematic = parseDefaultSchematic(path, fileName, format.get());
        }
        if (schematic == null) {
            throw new IOException("Baritone could not parse schematic: " + fileName);
        }

        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return new SchematicSnapshot(name, schematic, BlockPos.ZERO);
    }

    /**
     * Baritone's bundled 1.19.0 adapter intentionally rejects Litematic versions 4–6 even though
     * its version 7 decoder uses the same region palette/bit-array layout. Validate the data that
     * decoder otherwise silently degrades (unknown blocks become air, unknown properties vanish),
     * then use that decoder directly for versions 4 through 7.
     */
    private static IStaticSchematic parseLitematic(Path path) throws IOException {
        CompoundTag root;
        try (InputStream input = Files.newInputStream(path)) {
            root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }
        int version = root.getInt("Version").orElse(-1);
        if (version < 4 || version > 7) {
            throw new IOException("Unsupported Litematic version " + version + " (supported versions: 4–7)");
        }
        validateLitematic(root);
        rejectUnsupportedLitematicPayloads(root);

        try {
            LitematicaSchematic schematic = new LitematicaSchematic(root);
            long volume = (long) schematic.widthX() * schematic.heightY() * schematic.lengthZ();
            if (schematic.widthX() <= 0 || schematic.heightY() <= 0 || schematic.lengthZ() <= 0
                    || volume <= 0 || volume > MAX_LITEMATIC_BLOCKS) {
                throw new IOException("Litematic has invalid or implausibly large parsed dimensions");
            }
            return schematic;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Could not decode Litematic version " + version + ": " + exception.getMessage(), exception);
        }
    }

    private static void validateLitematic(CompoundTag root) throws IOException {
        CompoundTag regions = root.getCompound("Regions")
                .orElseThrow(() -> new IOException("Litematic is missing its Regions compound"));
        if (regions.isEmpty() || regions.size() > MAX_LITEMATIC_REGIONS) {
            throw new IOException("Litematic must contain between 1 and " + MAX_LITEMATIC_REGIONS + " regions");
        }

        long totalRegionVolume = 0;
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompound(regionName)
                    .orElseThrow(() -> new IOException("Litematic region is not a compound: " + regionName));
            CompoundTag size = region.getCompound("Size")
                    .orElseThrow(() -> new IOException("Litematic region is missing Size: " + regionName));
            CompoundTag position = region.getCompound("Position")
                    .orElseThrow(() -> new IOException("Litematic region is missing Position: " + regionName));
            int sx = requiredInt(size, "x", regionName);
            int sy = requiredInt(size, "y", regionName);
            int sz = requiredInt(size, "z", regionName);
            int px = requiredInt(position, "x", regionName);
            int py = requiredInt(position, "y", regionName);
            int pz = requiredInt(position, "z", regionName);
            long dx = Math.abs((long) sx), dy = Math.abs((long) sy), dz = Math.abs((long) sz);
            if (dx == 0 || dy == 0 || dz == 0) {
                throw new IOException("Litematic region has an empty dimension: " + regionName);
            }
            if (volumeExceedsLimit(dx, dy, dz)) {
                throw new IOException("Litematic region data exceeds the supported size limit: " + regionName);
            }
            long regionVolume = dx * dy * dz;
            totalRegionVolume += regionVolume;
            if (totalRegionVolume > MAX_LITEMATIC_BLOCKS) {
                throw new IOException("Litematic region data exceeds the supported size limit");
            }

            long endX = (long) px + sx + 1, endY = (long) py + sy + 1, endZ = (long) pz + sz + 1;
            if (endX < Integer.MIN_VALUE || endX > Integer.MAX_VALUE
                    || endY < Integer.MIN_VALUE || endY > Integer.MAX_VALUE
                    || endZ < Integer.MIN_VALUE || endZ > Integer.MAX_VALUE) {
                throw new IOException("Litematic region coordinates overflow: " + regionName);
            }
            long regionMinX = Math.min(px, endX), regionMinY = Math.min(py, endY), regionMinZ = Math.min(pz, endZ);
            minX = Math.min(minX, regionMinX); minY = Math.min(minY, regionMinY); minZ = Math.min(minZ, regionMinZ);
            maxX = Math.max(maxX, regionMinX + dx); maxY = Math.max(maxY, regionMinY + dy); maxZ = Math.max(maxZ, regionMinZ + dz);

            ListTag palette = region.getList("BlockStatePalette")
                    .orElseThrow(() -> new IOException("Litematic region is missing BlockStatePalette: " + regionName));
            if (palette.isEmpty()) {
                throw new IOException("Litematic region has an empty BlockStatePalette: " + regionName);
            }
            validatePalette(palette, regionName);
            long[] blockStates = region.getLongArray("BlockStates")
                    .orElseThrow(() -> new IOException("Litematic region is missing BlockStates: " + regionName));
            int bitsPerBlock = Math.max(2, (int) Math.ceil(Math.log(palette.size()) / Math.log(2)));
            long requiredLongs = (regionVolume * bitsPerBlock + 63) / 64;
            if (blockStates.length < requiredLongs) {
                throw new IOException("Litematic region has truncated BlockStates: " + regionName);
            }
        }

        long boundsX = maxX - minX, boundsY = maxY - minY, boundsZ = maxZ - minZ;
        if (boundsX <= 0 || boundsY <= 0 || boundsZ <= 0
                || volumeExceedsLimit(boundsX, boundsY, boundsZ)) {
            throw new IOException("Litematic region bounds are empty or implausibly large");
        }
    }

    /**
     * Baritone's static schematic adapter preserves block states, not payload NBT. Permit only
     * canonical empty vanilla chest metadata, which carries no contents or custom data.
     */
    private static void rejectUnsupportedLitematicPayloads(CompoundTag root) throws IOException {
        rejectUnsupportedLitematicPayloads(root, null);
    }

    private static void rejectUnsupportedLitematicPayloads(CompoundTag root, Set<String> onlyRegions)
            throws IOException {
        CompoundTag regions = root.getCompound("Regions")
                .orElseThrow(() -> new IOException("Litematic is missing its Regions compound"));
        for (String regionName : regions.keySet()) {
            if (onlyRegions != null && !onlyRegions.contains(regionName)) continue;
            CompoundTag region = regions.getCompound(regionName)
                    .orElseThrow(() -> new IOException("Litematic region is not a compound: " + regionName));
            rejectUnsupportedPayload(region, regionName, "Entities", "entity");
            Set<BlockPos> seenBlockEntities = new HashSet<>();
            validateLitematicBlockEntities(region, regionName, "TileEntities", seenBlockEntities);
            validateLitematicBlockEntities(region, regionName, "BlockEntities", seenBlockEntities);
        }
        if (onlyRegions != null && !regions.keySet().containsAll(onlyRegions)) {
            throw new IOException("Active Litematica placement refers to a missing schematic region");
        }
    }

    private static void validateLitematicBlockEntities(CompoundTag region, String regionName,
                                                       String tagName, Set<BlockPos> seen) throws IOException {
        Tag payload = region.get(tagName);
        if (payload == null) return;
        if (!(payload instanceof ListTag entries)) {
            throw new IOException("Litematic region " + regionName + " has malformed " + tagName
                    + " data; only canonical empty chest block-entity metadata is supported");
        }
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i).orElseThrow(() -> new IOException(
                    "Litematic region " + regionName + " has malformed " + tagName + " entry"));
            BlockPos pos = validateEmptyChestBlockEntity(entry, regionName, tagName);
            if (!seen.add(pos)) {
                throw new IOException("Litematic region " + regionName
                        + " has duplicate block-entity metadata at " + pos);
            }
            validateChestPositionAndState(region, regionName, pos, entry.getString("id").orElse(""));
        }
    }

    private static BlockPos validateEmptyChestBlockEntity(CompoundTag entry, String regionName,
                                                           String tagName) throws IOException {
        Set<String> requiredKeys = Set.of("id", "x", "y", "z");
        Set<String> allowedKeys = Set.of("id", "x", "y", "z", "Items");
        if (!allowedKeys.containsAll(entry.keySet())
                || !entry.keySet().containsAll(requiredKeys)) {
            throw new IOException("Litematic region " + regionName + " has unsupported or incomplete "
                    + tagName + " metadata; only canonical empty chest metadata is supported; "
                    + "other block-entity payloads are not supported");
        }
        String id = entry.getString("id").orElse(null);
        if (!("minecraft:chest".equals(id) || "minecraft:trapped_chest".equals(id))) {
            throw new IOException("Litematic region " + regionName
                    + " has unsupported block-entity id " + id + " in " + tagName);
        }
        int x = requiredInt(entry, "x", regionName);
        int y = requiredInt(entry, "y", regionName);
        int z = requiredInt(entry, "z", regionName);
        Tag itemPayload = entry.get("Items");
        if (itemPayload != null) {
            if (!(itemPayload instanceof ListTag items)) {
                throw new IOException("Litematic region " + regionName + " has malformed chest Items metadata");
            }
            if (!items.isEmpty()) {
                throw new IOException("Litematic region " + regionName
                        + " contains populated chest Items; only empty chests are supported");
            }
        }
        return new BlockPos(x, y, z);
    }

    private static void validateChestPositionAndState(CompoundTag region, String regionName,
                                                      BlockPos pos, String id) throws IOException {
        CompoundTag size = region.getCompound("Size").orElseThrow();
        int sx = Math.abs(requiredInt(size, "x", regionName));
        int sy = Math.abs(requiredInt(size, "y", regionName));
        int sz = Math.abs(requiredInt(size, "z", regionName));
        if (pos.getX() < 0 || pos.getX() >= sx || pos.getY() < 0 || pos.getY() >= sy
                || pos.getZ() < 0 || pos.getZ() >= sz) {
            throw new IOException("Litematic region " + regionName
                    + " has block-entity metadata outside its region at " + pos);
        }

        ListTag palette = region.getList("BlockStatePalette").orElseThrow();
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long index = (long) pos.getY() * sx * sz + (long) pos.getZ() * sx + pos.getX();
        long bitIndex = index * bits;
        long[] packed = region.getLongArray("BlockStates").orElseThrow();
        int word = (int) (bitIndex >>> 6);
        int offset = (int) (bitIndex & 63);
        long value = packed[word] >>> offset;
        if (offset + bits > 64) value |= packed[word + 1] << (64 - offset);
        int paletteIndex = (int) (value & ((1L << bits) - 1));
        if (paletteIndex >= palette.size()) {
            throw new IOException("Litematic region " + regionName
                    + " has invalid palette data at block-entity position " + pos);
        }
        String stateId = palette.getCompound(paletteIndex).orElseThrow().getString("Name").orElse("");
        String expectedState = id;
        if (!expectedState.equals(stateId)) {
            throw new IOException("Litematic region " + regionName + " block-entity id " + id
                    + " does not match block state " + stateId + " at " + pos);
        }
    }

    private static void rejectUnsupportedPayload(CompoundTag region, String regionName,
                                                 String tagName, String payloadName) throws IOException {
        Tag payload = region.get(tagName);
        if (payload == null) {
            return;
        }
        if (!(payload instanceof ListTag entries)) {
            throw new IOException("Litematic region " + regionName + " has malformed " + tagName
                    + " data; " + payloadName + " payloads are not supported by the schematic builder");
        }
        if (!entries.isEmpty()) {
            throw new IOException("Litematic region " + regionName + " contains " + payloadName
                    + " payload in " + tagName + "; " + payloadName
                    + " payloads are not supported by the schematic builder");
        }
    }

    private static CompoundTag readCompressedNbt(Path path, String fileName) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        } catch (RuntimeException exception) {
            throw new IOException("Could not inspect compressed NBT payloads in " + fileName
                    + ": " + exception.getMessage(), exception);
        }
    }

    static IStaticSchematic parseDefaultSchematic(Path path, String fileName,
                                                   ISchematicFormat selectedFormat) throws IOException {
        if (selectedFormat == DefaultSchematicFormats.MCEDIT
                || selectedFormat == DefaultSchematicFormats.SPONGE) {
            CompoundTag root = readCompressedNbt(path, fileName);
            rejectUnsupportedDefaultPayloads(selectedFormat, root);
        }
        try (InputStream input = Files.newInputStream(path)) {
            return selectedFormat.parse(input);
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse schematic " + fileName + ": " + exception.getMessage(), exception);
        }
    }

    static void rejectUnsupportedDefaultPayloads(ISchematicFormat format, CompoundTag root) throws IOException {
        if (format == DefaultSchematicFormats.MCEDIT) {
            rejectUnsupportedNbtPayload(root, "MCEdit .schematic", "root", "Entities", "entity");
            rejectUnsupportedNbtPayload(root, "MCEdit .schematic", "root", "TileEntities", "block-entity");
            rejectUnsupportedNbtPayload(root, "MCEdit .schematic", "root", "BlockEntities", "block-entity");
            return;
        }
        if (format == DefaultSchematicFormats.SPONGE) {
            rejectSpongePayloads(root);
        }
    }

    private static void rejectSpongePayloads(CompoundTag root) throws IOException {
        rejectUnsupportedNbtPayload(root, "Sponge .schem", "root", "Entities", "entity");
        rejectUnsupportedNbtPayload(root, "Sponge .schem", "root", "TileEntities", "block-entity");
        rejectUnsupportedNbtPayload(root, "Sponge .schem", "root", "BlockEntities", "block-entity");

        // Sponge v3 stores content under Schematic.Blocks, but Baritone's default
        // adapter only decodes versions 1 and 2. Inspect metadata here so it cannot
        // silently disappear if another adapter accepts that nested form.
        CompoundTag schematic = root.getCompound("Schematic").orElse(null);
        if (schematic == null) {
            return;
        }
        rejectUnsupportedNbtPayload(schematic, "Sponge .schem", "Schematic", "Entities", "entity");
        CompoundTag blocks = schematic.getCompound("Blocks").orElse(null);
        if (blocks != null) {
            rejectUnsupportedNbtPayload(blocks, "Sponge .schem", "Schematic.Blocks",
                    "BlockEntities", "block-entity");
        }
    }

    private static void rejectUnsupportedNbtPayload(CompoundTag data, String formatName, String location,
                                                    String tagName, String payloadName) throws IOException {
        Tag payload = data.get(tagName);
        if (payload == null) {
            return;
        }
        if (!(payload instanceof ListTag entries)) {
            throw new IOException(formatName + " " + location + " has malformed " + tagName
                    + " data; " + payloadName + " payloads are not supported by the schematic builder");
        }
        if (!entries.isEmpty()) {
            throw new IOException(formatName + " " + location + " contains " + payloadName
                    + " payload in " + tagName + "; " + payloadName
                    + " payloads are not supported by the schematic builder");
        }
    }

    private static boolean volumeExceedsLimit(long x, long y, long z) {
        return x > MAX_LITEMATIC_BLOCKS || y > MAX_LITEMATIC_BLOCKS || z > MAX_LITEMATIC_BLOCKS
                || x > MAX_LITEMATIC_BLOCKS / y
                || x * y > MAX_LITEMATIC_BLOCKS / z;
    }

    private static int requiredInt(CompoundTag compound, String key, String regionName) throws IOException {
        return compound.getInt(key).orElseThrow(
                () -> new IOException("Litematic region " + regionName + " has invalid " + key));
    }

    private static void validatePalette(ListTag palette, String regionName) throws IOException {
        for (int index = 0; index < palette.size(); index++) {
            CompoundTag entry = palette.getCompound(index)
                    .orElseThrow(() -> new IOException("Litematic region " + regionName + " has an invalid palette entry"));
            String name = entry.getString("Name")
                    .orElseThrow(() -> new IOException("Litematic region " + regionName + " has a palette entry without Name"));
            Identifier id = Identifier.tryParse(name);
            Optional<Holder.Reference<Block>> blockHolder = id == null
                    ? Optional.<Holder.Reference<Block>>empty() : BuiltInRegistries.BLOCK.get(id);
            if (blockHolder.isEmpty()) {
                throw new IOException("Litematic region " + regionName + " uses unknown block ID " + name);
            }
            Block block = blockHolder.get().value();
            CompoundTag properties = entry.getCompoundOrEmpty("Properties");
            for (String propertyName : properties.keySet()) {
                Property<?> property = block.getStateDefinition().getProperty(propertyName);
                String value = properties.getString(propertyName).orElse(null);
                if (property == null) {
                    throw new IOException("Litematic block " + name + " has unknown property " + propertyName);
                }
                if (value == null || !hasPropertyValue(property, value)) {
                    throw new IOException("Litematic block " + name + " has invalid value for property " + propertyName);
                }
            }
        }
    }

    private static <T extends Comparable<T>> boolean hasPropertyValue(Property<T> property, String value) {
        return property.getValue(value).isPresent();
    }

    /**
     * Reads a loaded Litematica placement through Baritone's own placement adapter. Reflection
     * keeps Litematica optional at compile time; Baritone only resolves its Litematica types when
     * the mod is installed. The returned schematic already includes placement transforms.
     */
    public static SchematicSnapshot loadActiveLitematica(int placementIndex) throws IOException {
        if (placementIndex < 0) {
            throw new IOException("Litematica placement index must be zero or greater");
        }

        try {
            Class<?> helper = Class.forName(LITEMATICA_HELPER);
            Method isPresent = helper.getMethod("isLitematicaPresent");
            if (!Boolean.TRUE.equals(isPresent.invoke(null))) {
                throw new IOException("Litematica is not installed");
            }

            Method hasPlacement = helper.getMethod("hasLoadedSchematic", int.class);
            if (!Boolean.TRUE.equals(hasPlacement.invoke(null, placementIndex))) {
                throw new IOException("No Litematica placement at index " + placementIndex);
            }

            rejectActiveLitematicaPayloads(placementIndex);

            Method getSchematic = helper.getMethod("getSchematic", int.class);
            Object result = getSchematic.invoke(null, placementIndex);
            if (!(result instanceof Pair<?, ?> pair)
                    || !(pair.first() instanceof IStaticSchematic schematic)
                    || !(pair.second() instanceof Vec3i origin)) {
                throw new IOException("Baritone returned an invalid Litematica placement snapshot");
            }

            return new SchematicSnapshot("Litematica placement " + (placementIndex + 1), schematic,
                    new BlockPos(origin));
        } catch (IOException e) {
            throw e;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IOException("This Baritone build cannot inspect Litematica placements", e);
        } catch (IllegalAccessException e) {
            throw new IOException("Could not access Baritone's Litematica adapter", e);
        } catch (InvocationTargetException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : e;
            throw new IOException("Could not snapshot the active Litematica placement", cause);
        }
    }

    /**
     * Inspect enabled regions from Litematica's native placement API before Baritone's adapter
     * strips the source metadata. Reflection keeps Litematica optional at compile time.
     */
    private static void rejectActiveLitematicaPayloads(int placementIndex) throws IOException {
        try {
            Class<?> dataManager = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object placementManager = dataManager.getMethod("getSchematicPlacementManager").invoke(null);
            Object placementsObject = placementManager.getClass().getMethod("getAllSchematicsPlacements")
                    .invoke(placementManager);
            if (!(placementsObject instanceof List<?> placements) || placementIndex >= placements.size()) {
                throw new IOException("Could not inspect the requested active Litematica placement");
            }
            Object placement = placements.get(placementIndex);
            Object schematic = placement.getClass().getMethod("getSchematic").invoke(placement);
            Object enabledRegionsObject = placement.getClass()
                    .getMethod("getEnabledRelativeSubRegionPlacements").invoke(placement);
            if (!(enabledRegionsObject instanceof Map<?, ?> enabledRegions)) {
                throw new IOException("Could not inspect enabled regions in the active Litematica placement");
            }
            Set<String> enabledRegionNames = new HashSet<>();
            for (Object regionName : enabledRegions.keySet()) {
                if (!(regionName instanceof String name)) {
                    throw new IOException("Active Litematica placement contains an invalid region name");
                }
                enabledRegionNames.add(name);
            }
            Object sourceNbt = schematic.getClass().getMethod("writeToNBT").invoke(schematic);
            if (!(sourceNbt instanceof CompoundTag sourceTag)) {
                throw new IOException("Could not inspect schematic metadata in active Litematica placement");
            }
            // Validate the in-memory serialized representation only for enabled regions, matching
            // the native placement path below. Litematica has already normalized this data to maps.
            rejectUnsupportedLitematicPayloads(sourceTag, enabledRegionNames);

            Method blockEntitiesForRegion = schematic.getClass()
                    .getMethod("getBlockEntityMapForRegion", String.class);
            Method entitiesForRegion = schematic.getClass().getMethod("getEntityListForRegion", String.class);
            Method containerForRegion = schematic.getClass().getMethod("getSubRegionContainer", String.class);
            for (Object regionNameObject : enabledRegions.keySet()) {
                if (!(regionNameObject instanceof String regionName)) {
                    throw new IOException("Active Litematica placement contains an invalid region name");
                }
                Object blockEntities = blockEntitiesForRegion.invoke(schematic, regionName);
                Object entities = entitiesForRegion.invoke(schematic, regionName);
                if (!(blockEntities instanceof Map<?, ?>) || !(entities instanceof List<?>)) {
                    throw new IOException("Could not inspect entity payloads in active Litematica region "
                            + regionName);
                }
                Object container = containerForRegion.invoke(schematic, regionName);
                validateActiveEmptyChests(regionName, (Map<?, ?>) blockEntities, container);
                if (!((List<?>) entities).isEmpty()) {
                    throw new IOException("Active Litematica region " + regionName
                            + " contains entity payload; entity payloads are not supported "
                            + "by the schematic builder");
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            throw new IOException("Could not verify that the active Litematica placement has no unsupported "
                    + "entity or block-entity payload", exception);
        }
    }

    private static void validateActiveEmptyChests(String regionName, Map<?, ?> blockEntities,
                                                  Object container) throws ReflectiveOperationException, IOException {
        Method stateAt = container.getClass().getMethod("get", int.class, int.class, int.class);
        Method getSize = container.getClass().getMethod("getSize");
        Vec3i size = (Vec3i) getSize.invoke(container);
        for (Map.Entry<?, ?> entry : blockEntities.entrySet()) {
            if (!(entry.getKey() instanceof BlockPos pos) || entry.getValue() == null) {
                throw new IOException("Active Litematica region " + regionName
                        + " contains malformed block-entity metadata");
            }
            Object data = entry.getValue();
            Method keysMethod = data.getClass().getMethod("getKeys");
            Object keysObject = keysMethod.invoke(data);
            Set<String> requiredKeys = Set.of("id", "x", "y", "z");
            Set<String> allowedKeys = Set.of("id", "x", "y", "z", "Items");
            if (!(keysObject instanceof Set<?> keys)
                    || !keys.containsAll(requiredKeys) || !allowedKeys.containsAll(keys)) {
                throw new IOException("Active Litematica region " + regionName
                        + " has unsupported or incomplete block-entity metadata");
            }
            Method getString = data.getClass().getMethod("getString", String.class);
            Method getInt = data.getClass().getMethod("getInt", String.class);
            Method getList = data.getClass().getMethod("getList", String.class);
            Method getDataType = data.getClass().getMethod("getDataType", String.class);
            if (!Integer.valueOf(8).equals(((Optional<?>) getDataType.invoke(data, "id")).orElse(null))
                    || !Integer.valueOf(3).equals(((Optional<?>) getDataType.invoke(data, "x")).orElse(null))
                    || !Integer.valueOf(3).equals(((Optional<?>) getDataType.invoke(data, "y")).orElse(null))
                    || !Integer.valueOf(3).equals(((Optional<?>) getDataType.invoke(data, "z")).orElse(null))
                    || (keys.contains("Items") && !Integer.valueOf(9).equals(
                    ((Optional<?>) getDataType.invoke(data, "Items")).orElse(null)))) {
                throw new IOException("Active Litematica region " + regionName
                        + " contains malformed empty-chest metadata");
            }
            String id = (String) getString.invoke(data, "id");
            if (!("minecraft:chest".equals(id) || "minecraft:trapped_chest".equals(id))) {
                throw new IOException("Active Litematica region " + regionName
                        + " has unsupported block-entity id " + id);
            }
            int x = (int) getInt.invoke(data, "x");
            int y = (int) getInt.invoke(data, "y");
            int z = (int) getInt.invoke(data, "z");
            if (x != pos.getX() || y != pos.getY() || z != pos.getZ()) {
                throw new IOException("Active Litematica region " + regionName
                        + " has block-entity coordinates that do not match its indexed position");
            }
            if (keys.contains("Items")) {
                Object items = getList.invoke(data, "Items");
                if (items == null || (int) items.getClass().getMethod("size").invoke(items) != 0) {
                    throw new IOException("Active Litematica region " + regionName
                            + " contains populated or malformed chest Items; only empty chests are supported");
                }
            }
            if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0
                    || pos.getX() >= size.getX() || pos.getY() >= size.getY() || pos.getZ() >= size.getZ()) {
                throw new IOException("Active Litematica region " + regionName
                        + " has block-entity metadata outside its region at " + pos);
            }
            Object state = stateAt.invoke(container, pos.getX(), pos.getY(), pos.getZ());
            Object block = state.getClass().getMethod("getBlock").invoke(state);
            String stateId = BuiltInRegistries.BLOCK.getKey((Block) block).toString();
            String expectedState = id;
            if (!expectedState.equals(stateId)) {
                throw new IOException("Active Litematica region " + regionName + " block-entity id " + id
                        + " does not match block state " + stateId + " at " + pos);
            }
        }
    }
}
