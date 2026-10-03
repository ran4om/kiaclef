package adris.altoclef.util.schematic;

import adris.altoclef.testing.MinecraftTestBootstrap;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.utils.schematic.format.DefaultSchematicFormats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicLoaderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7})
    void loadsCompressedLitematicVersionsFourThroughSeven(int version) throws Exception {
        Path file = Files.createTempFile("altoclef-loader-v" + version + "-", ".litematic");
        try {
            NbtIo.writeCompressed(fixture(version), file);

            SchematicSnapshot snapshot = SchematicLoader.load(file);

            assertEquals(2, snapshot.schematic().widthX());
            assertEquals(1, snapshot.schematic().heightY());
            assertEquals(1, snapshot.schematic().lengthZ());
            assertTrue(snapshot.schematic().getDirect(0, 0, 0).isAir());
            assertEquals(Blocks.OAK_LOG.defaultBlockState(), snapshot.schematic().getDirect(1, 0, 0));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsUnknownBlockIdentifiersInsteadOfTurningThemIntoAir() throws Exception {
        CompoundTag fixture = fixture(7);
        palette(fixture).getCompound(1).orElseThrow().putString("Name", "altoclef:not_a_block");

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("unknown block ID"));
    }

    @Test
    void rejectsUnknownPropertyValuesInsteadOfUsingTheDefaultState() throws Exception {
        CompoundTag fixture = fixture(7);
        palette(fixture).getCompound(1).orElseThrow()
                .getCompound("Properties").orElseThrow().putString("axis", "sideways");

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("invalid value for property axis"));
    }

    @Test
    void rejectsUnknownBlockProperties() throws Exception {
        CompoundTag fixture = fixture(7);
        palette(fixture).getCompound(1).orElseThrow()
                .getCompound("Properties").orElseThrow().putString("legacy_property", "value");

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("unknown property legacy_property"));
    }

    @Test
    void rejectsFutureLitematicVersions() throws Exception {
        CompoundTag fixture = fixture(8);

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("Unsupported Litematic version 8"));
    }

    @Test
    void rejectsTruncatedPaletteIndexData() throws Exception {
        CompoundTag fixture = fixture(7);
        region(fixture).putLongArray("BlockStates", new long[0]);

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("truncated BlockStates"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entities", "TileEntities", "BlockEntities"})
    void rejectsNonEmptyEntityAndBlockEntityPayloads(String payloadTag) throws Exception {
        CompoundTag fixture = fixture(7);
        ListTag payload = new ListTag();
        payload.add(new CompoundTag());
        region(fixture).put(payloadTag, payload);

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains(payloadTag));
        assertTrue(failure.getMessage().contains("not supported"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entities", "TileEntities", "BlockEntities"})
    void rejectsMalformedEntityAndBlockEntityPayloads(String payloadTag) throws Exception {
        CompoundTag fixture = fixture(7);
        region(fixture).putString(payloadTag, "not a list");

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("malformed " + payloadTag));
    }

    @Test
    void acceptsCanonicalEmptyChestMetadataWhenPositionAndStateMatch() throws Exception {
        CompoundTag fixture = withChestAtZero(fixture(7));
        ListTag entries = new ListTag();
        entries.add(emptyChest(0, 0, 0, "minecraft:chest"));
        region(fixture).put("TileEntities", entries);

        SchematicSnapshot snapshot = load(fixture);

        assertEquals(Blocks.CHEST.defaultBlockState(), snapshot.schematic().getDirect(0, 0, 0));
    }

    @Test
    void acceptsCanonicalEmptyTrappedChestMetadata() throws Exception {
        CompoundTag fixture = withChestAtZero(fixture(7));
        palette(fixture).getCompound(2).orElseThrow().putString("Name", "minecraft:trapped_chest");
        ListTag entries = new ListTag();
        entries.add(emptyChest(0, 0, 0, "minecraft:trapped_chest"));
        region(fixture).put("BlockEntities", entries);

        assertEquals(Blocks.TRAPPED_CHEST.defaultBlockState(), load(fixture).schematic().getDirect(0, 0, 0));
    }

    @Test
    void acceptsEmptyChestMetadataWhenVanillaOmitsTheEmptyItemsList() throws Exception {
        CompoundTag fixture = withChestAtZero(fixture(7));
        CompoundTag chest = emptyChest(0, 0, 0, "minecraft:chest");
        chest.remove("Items");
        region(fixture).getList("TileEntities").orElseThrow().add(chest);

        assertEquals(Blocks.CHEST.defaultBlockState(), load(fixture).schematic().getDirect(0, 0, 0));
    }

    @Test
    void rejectsPopulatedChestItems() throws Exception {
        CompoundTag fixture = withChestAtZero(fixture(7));
        CompoundTag chest = emptyChest(0, 0, 0, "minecraft:chest");
        ListTag items = new ListTag();
        items.add(new CompoundTag());
        chest.put("Items", items);
        region(fixture).getList("TileEntities").orElseThrow().add(chest);

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("populated chest Items"));
    }

    @Test
    void rejectsLootTablesCustomNamesAndUnknownBlockEntityIds() throws Exception {
        CompoundTag loot = withChestAtZero(fixture(7));
        CompoundTag lootChest = emptyChest(0, 0, 0, "minecraft:chest");
        lootChest.putString("LootTable", "minecraft:chests/simple_dungeon");
        region(loot).getList("TileEntities").orElseThrow().add(lootChest);
        IOException lootFailure = assertThrows(IOException.class, () -> load(loot));
        assertTrue(lootFailure.getMessage().contains("canonical empty chest metadata"));

        CompoundTag custom = withChestAtZero(fixture(7));
        CompoundTag namedChest = emptyChest(0, 0, 0, "minecraft:chest");
        namedChest.putString("CustomName", "{\"text\":\"custom\"}");
        region(custom).getList("TileEntities").orElseThrow().add(namedChest);
        IOException customFailure = assertThrows(IOException.class, () -> load(custom));
        assertTrue(customFailure.getMessage().contains("canonical empty chest metadata"));

        CompoundTag sign = withChestAtZero(fixture(7));
        CompoundTag signEntity = emptyChest(0, 0, 0, "minecraft:sign");
        region(sign).getList("TileEntities").orElseThrow().add(signEntity);
        IOException signFailure = assertThrows(IOException.class, () -> load(sign));
        assertTrue(signFailure.getMessage().contains("unsupported block-entity id"));
    }

    @Test
    void rejectsDuplicateOutOfRegionAndIdStateMismatchChestMetadata() throws Exception {
        CompoundTag duplicate = withChestAtZero(fixture(7));
        region(duplicate).getList("TileEntities").orElseThrow()
                .add(emptyChest(0, 0, 0, "minecraft:chest"));
        region(duplicate).put("BlockEntities", payloadWith(emptyChest(0, 0, 0, "minecraft:chest")));
        IOException duplicateFailure = assertThrows(IOException.class, () -> load(duplicate));
        assertTrue(duplicateFailure.getMessage().contains("duplicate block-entity"));

        CompoundTag outside = withChestAtZero(fixture(7));
        region(outside).getList("TileEntities").orElseThrow()
                .add(emptyChest(2, 0, 0, "minecraft:chest"));
        IOException outsideFailure = assertThrows(IOException.class, () -> load(outside));
        assertTrue(outsideFailure.getMessage().contains("outside its region"));

        CompoundTag mismatch = withChestAtZero(fixture(7));
        palette(mismatch).getCompound(2).orElseThrow().putString("Name", "minecraft:barrel");
        region(mismatch).getList("TileEntities").orElseThrow()
                .add(emptyChest(0, 0, 0, "minecraft:chest"));
        IOException mismatchFailure = assertThrows(IOException.class, () -> load(mismatch));
        assertTrue(mismatchFailure.getMessage().contains("does not match block state"));

        CompoundTag reverseMismatch = withChestAtZero(fixture(7));
        reverseMismatch.getCompound("Regions").orElseThrow().getCompound("RegressionFixture").orElseThrow()
                .getList("TileEntities").orElseThrow()
                .add(emptyChest(0, 0, 0, "minecraft:trapped_chest"));
        IOException reverseMismatchFailure = assertThrows(IOException.class, () -> load(reverseMismatch));
        assertTrue(reverseMismatchFailure.getMessage().contains("does not match block state"));
    }

    @Test
    void rejectsMalformedChestEntryAndMalformedItemsList() throws Exception {
        CompoundTag malformedEntry = withChestAtZero(fixture(7));
        region(malformedEntry).getList("TileEntities").orElseThrow().add(StringTag.valueOf("bad"));
        IOException entryFailure = assertThrows(IOException.class, () -> load(malformedEntry));
        assertTrue(entryFailure.getMessage().contains("malformed TileEntities entry"));

        CompoundTag malformedItems = withChestAtZero(fixture(7));
        CompoundTag chest = emptyChest(0, 0, 0, "minecraft:chest");
        chest.putString("Items", "not a list");
        region(malformedItems).getList("TileEntities").orElseThrow().add(chest);
        IOException itemsFailure = assertThrows(IOException.class, () -> load(malformedItems));
        assertTrue(itemsFailure.getMessage().contains("malformed chest Items"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entities", "TileEntities", "BlockEntities"})
    void rejectsNonEmptyMceditPayloads(String payloadTag) {
        CompoundTag root = new CompoundTag();
        root.put(payloadTag, payload());

        IOException failure = assertThrows(IOException.class,
                () -> loadDefault(root, ".schematic", DefaultSchematicFormats.MCEDIT));

        assertTrue(failure.getMessage().contains("MCEdit .schematic"));
        assertTrue(failure.getMessage().contains(payloadTag));
        assertTrue(failure.getMessage().contains("not supported"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entities", "TileEntities", "BlockEntities"})
    void rejectsNonEmptySpongeRootPayloads(String payloadTag) {
        CompoundTag root = new CompoundTag();
        root.put(payloadTag, payload());

        IOException failure = assertThrows(IOException.class,
                () -> loadDefault(spongeFixture(root), ".schem", DefaultSchematicFormats.SPONGE));

        assertTrue(failure.getMessage().contains("Sponge .schem"));
        assertTrue(failure.getMessage().contains(payloadTag));
        assertTrue(failure.getMessage().contains("not supported"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Entities", "TileEntities", "BlockEntities"})
    void rejectsMalformedDefaultAdapterPayloads(String payloadTag) throws Exception {
        CompoundTag mcedit = mceditFixture();
        mcedit.putString(payloadTag, "not a list");
        IOException mceditFailure = assertThrows(IOException.class,
                () -> loadDefault(mcedit, ".schematic", DefaultSchematicFormats.MCEDIT));
        assertTrue(mceditFailure.getMessage().contains("malformed " + payloadTag));

        CompoundTag sponge = spongeFixture(new CompoundTag());
        sponge.putString(payloadTag, "not a list");
        IOException spongeFailure = assertThrows(IOException.class,
                () -> loadDefault(sponge, ".schem", DefaultSchematicFormats.SPONGE));
        assertTrue(spongeFailure.getMessage().contains("malformed " + payloadTag));
    }

    @Test
    void rejectsSpongeV3NestedBlockEntityPayloadBeforeTheUnsupportedVersionDecode() {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        CompoundTag schematic = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        blocks.put("BlockEntities", payload());
        schematic.put("Blocks", blocks);
        root.put("Schematic", schematic);

        IOException failure = assertThrows(IOException.class,
                () -> loadDefault(root, ".schem", DefaultSchematicFormats.SPONGE));

        assertTrue(failure.getMessage().contains("Schematic.Blocks"));
        assertTrue(failure.getMessage().contains("BlockEntities"));
        assertTrue(failure.getMessage().contains("not supported"));
    }

    @Test
    void defaultSpongeAdapterStillRejectsVersionThreeWhenPayloadListsAreEmpty() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 3);
        CompoundTag schematic = new CompoundTag();
        CompoundTag blocks = new CompoundTag();
        blocks.put("BlockEntities", new ListTag());
        schematic.put("Blocks", blocks);
        schematic.put("Entities", new ListTag());
        root.put("Schematic", schematic);

        IOException failure = assertThrows(IOException.class,
                () -> loadDefault(root, ".schem", DefaultSchematicFormats.SPONGE));

        assertTrue(failure.getMessage().contains("Unsupported Version of a Sponge Schematic"));
    }

    @Test
    void emptyMceditPayloadListsContinueThroughTheDefaultParser() throws Exception {
        CompoundTag root = mceditFixture();
        root.put("Entities", new ListTag());
        root.put("TileEntities", new ListTag());
        root.put("BlockEntities", new ListTag());

        IStaticSchematic schematic = loadDefault(root, ".schematic", DefaultSchematicFormats.MCEDIT);

        assertEquals(1, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
    }

    @Test
    void emptySpongePayloadListsContinueThroughTheDefaultParser() throws Exception {
        CompoundTag root = spongeFixture(new CompoundTag());
        root.put("Entities", new ListTag());
        root.put("TileEntities", new ListTag());
        root.put("BlockEntities", new ListTag());

        IStaticSchematic schematic = loadDefault(root, ".schem", DefaultSchematicFormats.SPONGE);

        assertEquals(1, schematic.widthX());
        assertEquals(1, schematic.heightY());
        assertEquals(1, schematic.lengthZ());
    }

    private static ListTag payload() {
        ListTag payload = new ListTag();
        payload.add(new CompoundTag());
        return payload;
    }

    private static ListTag payloadWith(CompoundTag entry) {
        ListTag result = new ListTag();
        result.add(entry);
        return result;
    }

    private static CompoundTag emptyChest(int x, int y, int z, String id) {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", id);
        chest.putInt("x", x);
        chest.putInt("y", y);
        chest.putInt("z", z);
        chest.put("Items", new ListTag());
        return chest;
    }

    private static CompoundTag withChestAtZero(CompoundTag root) {
        CompoundTag chest = new CompoundTag();
        chest.putString("Name", "minecraft:chest");
        palette(root).add(chest);
        // x=0 is chest (palette index 2), x=1 remains the oak log (palette index 1).
        region(root).putLongArray("BlockStates", new long[]{6L});
        ListTag blockEntities = new ListTag();
        region(root).put("TileEntities", blockEntities);
        return root;
    }

    private static IStaticSchematic loadDefault(CompoundTag fixture, String extension,
                                                ISchematicFormat format) throws Exception {
        Path file = Files.createTempFile("altoclef-default-loader-fixture-", extension);
        try {
            NbtIo.writeCompressed(fixture, file);
            return SchematicLoader.parseDefaultSchematic(file, file.getFileName().toString(), format);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static CompoundTag mceditFixture() {
        CompoundTag root = new CompoundTag();
        root.putString("Materials", "Alpha");
        root.putInt("Width", 1);
        root.putInt("Height", 1);
        root.putInt("Length", 1);
        root.putByteArray("Blocks", new byte[]{0});
        return root;
    }

    private static CompoundTag spongeFixture(CompoundTag root) {
        root.putInt("Version", 2);
        root.putInt("Width", 1);
        root.putInt("Height", 1);
        root.putInt("Length", 1);
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        root.put("Palette", palette);
        root.putByteArray("BlockData", new byte[]{0});
        return root;
    }

    @Test
    void rejectsNegativeDimensionThatWouldOverflowRegionAllocation() throws Exception {
        CompoundTag fixture = fixture(7);
        region(fixture).getCompound("Size").orElseThrow().putInt("x", Integer.MIN_VALUE);

        IOException failure = assertThrows(IOException.class, () -> load(fixture));

        assertTrue(failure.getMessage().contains("size limit"));
    }

    private static SchematicSnapshot load(CompoundTag fixture) throws Exception {
        Path file = Files.createTempFile("altoclef-loader-fixture-", ".litematic");
        try {
            NbtIo.writeCompressed(fixture, file);
            return SchematicLoader.load(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static CompoundTag fixture(int version) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", version);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef loader version regression fixture");
        metadata.putString("Author", "Unit test");
        metadata.putString("Description", "Compressed two-cell Litematica fixture.");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", 0L);
        metadata.putLong("TimeModified", 0L);
        metadata.putLong("TotalBlocks", 1L);
        metadata.putLong("TotalVolume", 2L);
        CompoundTag enclosingSize = new CompoundTag();
        enclosingSize.putInt("x", 2);
        enclosingSize.putInt("y", 1);
        enclosingSize.putInt("z", 1);
        metadata.put("EnclosingSize", enclosingSize);
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", 2);
        size.putInt("y", 1);
        size.putInt("z", 1);
        region.put("Size", size);

        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        CompoundTag oakLog = new CompoundTag();
        oakLog.putString("Name", "minecraft:oak_log");
        CompoundTag properties = new CompoundTag();
        properties.putString("axis", "y");
        oakLog.put("Properties", properties);
        palette.add(oakLog);
        region.put("BlockStatePalette", palette);
        // Litematica packs palette indices contiguously, least-significant bits first.
        // Index 0 is air; index 1 (local x=1) is oak_log with a two-bit palette width.
        region.putLongArray("BlockStates", new long[]{1L << 2});
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());

        CompoundTag regions = new CompoundTag();
        regions.put("RegressionFixture", region);
        root.put("Regions", regions);
        return root;
    }

    private static CompoundTag region(CompoundTag root) {
        return root.getCompound("Regions").orElseThrow().getCompound("RegressionFixture").orElseThrow();
    }

    private static ListTag palette(CompoundTag root) {
        return region(root).getList("BlockStatePalette").orElseThrow();
    }
}
