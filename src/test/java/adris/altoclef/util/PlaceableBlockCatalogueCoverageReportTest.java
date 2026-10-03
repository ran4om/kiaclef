package adris.altoclef.util;

import adris.altoclef.TaskCatalogue;
import adris.altoclef.testing.MinecraftTestBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writes a reviewable inventory of placeable block items with and without collection-task wiring. */
class PlaceableBlockCatalogueCoverageReportTest {
    private static final Path REPORT = Path.of(".audit", "gameplay", "placeable-block-coverage-26.2.md");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void writesRegistryWideReportWithoutTreatingUnsupportedItemsAsTestFailures() throws IOException {
        Set<String> covered = new TreeSet<>();
        Set<String> unsupported = new TreeSet<>();

        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = block.asItem();
            if (item == Items.AIR) continue;

            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            (TaskCatalogue.taskExists(item) ? covered : unsupported).add(id);
        }

        assertFalse(covered.isEmpty(), "the vanilla block-item registry should not be empty");

        String report = renderReport(covered, unsupported);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);

        assertTrue(Files.isRegularFile(REPORT), "write the registry report to " + REPORT);
        assertTrue(Files.readString(REPORT, StandardCharsets.UTF_8).contains("## No registered collection task"));
    }

    private static String renderReport(Set<String> covered, Set<String> unsupported) {
        StringBuilder report = new StringBuilder()
                .append("# Minecraft 26.2 placeable block item coverage\n\n")
                .append("Generated from `BuiltInRegistries.BLOCK` by `PlaceableBlockCatalogueCoverageReportTest`.\n\n")
                .append("This is a current-registry breadth inventory. It does not establish feature parity with the original 1.18.2 AltoClef release. A registered task proves catalogue wiring (including recursive recipe-fallback entries), not successful runtime gathering or placement.\n\n")
                .append("Unique block items: ").append(covered.size() + unsupported.size()).append("\n\n")
                .append("## Registered collection task (catalogue or recipe fallback) — ").append(covered.size()).append("\n\n");
        appendItems(report, covered);
        report.append("\n## No registered collection task — ").append(unsupported.size()).append("\n\n");
        appendItems(report, unsupported);
        return report.toString();
    }

    private static void appendItems(StringBuilder report, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            report.append("(none)\n");
            return;
        }
        for (String id : itemIds) report.append("- `").append(id).append("`\n");
    }
}
