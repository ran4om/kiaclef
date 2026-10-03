package adris.altoclef.runtimetest;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.SendChatEvent;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.tasksystem.TaskChain;
import adris.altoclef.tasksystem.TaskRunner;
import adris.altoclef.tasks.construction.BuildSchematicTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.SmeltInFurnaceTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.resources.CollectPlanksTask;
import adris.altoclef.tasks.resources.CollectSticksTask;
import adris.altoclef.tasks.resources.MineAndCollectTask;
import adris.altoclef.tasks.squashed.CataloguedResourceTask;
import adris.altoclef.mixins.PersistentProjectileEntityAccessor;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.SmeltTarget;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StorageHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.phys.AABB;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.GameType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import baritone.api.schematic.IStaticSchematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Opt-in client integration harness. It only runs with -Daltoclef.runtimeTest=true
 * and intentionally edits the currently open integrated-server world. Use a new
 * disposable single-player world for each run.
 */
public final class RuntimeAcceptanceMod implements ClientModInitializer {
    private static final int PHASE_TIMEOUT_TICKS = 60 * 60 * 20;
    private static final int NATURAL_TIMEOUT_TICKS = 60 * 60 * 20;
    private static final int NATURAL_RESOURCE_SYNC_TIMEOUT_TICKS = 200;
    private static final long NATURAL_RESOURCE_ACCEPTANCE_SEED = -1829465260651582346L;
    private static final int STARTUP_WORLD_TIMEOUT_TICKS = 5 * 60 * 20;
    private static final int STARTUP_COMPONENT_TIMEOUT_TICKS = 10 * 20;
    private static final int STARTUP_CHUNK_TIMEOUT_TICKS = 60 * 20;
    private static final int MIXED_FIXTURE_WIDTH = 18;
    private static final int NATURAL_BUILD_SITE_SEARCH_RADIUS = 32;
    private static final int NATURAL_BUILD_SITE_APPROACH_TIMEOUT_TICKS = 600;

    private final Set<String> taskTrace = new LinkedHashSet<>();
    private final AtomicBoolean arenaReady = new AtomicBoolean();
    private final AtomicBoolean buildInventoryCleared = new AtomicBoolean();
    private final AtomicBoolean smithingSeeded = new AtomicBoolean();
    private final AtomicBoolean smeltSeeded = new AtomicBoolean();
    private final AtomicBoolean fallbackSeeded = new AtomicBoolean();
    private final AtomicBoolean naturalPrepared = new AtomicBoolean();
    private static volatile NaturalLogObservationSession naturalLogObservationSession;
    private volatile int naturalResourceServerNaturalLogBreakCount;
    private volatile String naturalResourceServerNaturalLogBreaks = "not-polled";
    private volatile int naturalResourceServerChestCraftedBefore;
    private volatile int naturalResourceServerChestCraftedAfter;
    private volatile int naturalResourceServerChestCraftedDelta;
    private final AtomicBoolean manualSeeded = new AtomicBoolean();
    private final AtomicBoolean repairSeeded = new AtomicBoolean();
    private final AtomicBoolean doorPairSeeded = new AtomicBoolean();
    private final AtomicBoolean batchSeeded = new AtomicBoolean();
    private final AtomicBoolean projectileSeeded = new AtomicBoolean();
    private final AtomicBoolean concreteSeeded = new AtomicBoolean();
    private final AtomicBoolean saplingPrepared = new AtomicBoolean();
    private final AtomicBoolean mixedFixtureSeeded = new AtomicBoolean();
    private volatile String manualServerSeedState = "not-seeded";
    private volatile boolean manualServerSeedValid;
    private volatile boolean smeltServerSeedValid;
    private volatile String smeltServerSeedState = "not-seeded";
    private boolean smeltFurnaceTrackedForAcceptance;
    private boolean smeltFurnaceCacheGateLogged;
    private volatile boolean projectileServerSeedValid;
    private volatile String projectileServerSeedState = "not-seeded";
    private volatile UUID projectileId;
    private volatile boolean projectileServerCheckOutstanding;
    private volatile boolean projectileServerCheckReady;
    private volatile boolean projectileServerCheckPassed;
    private volatile String projectileServerCheckState = "not-checked";
    private volatile boolean projectileFlightStarted;
    private volatile boolean projectileFlightStartFailed;
    private volatile String projectileFlightState = "not-started";
    private volatile boolean fallbackServerSeedValid;
    private volatile String fallbackServerSeedState = "not-seeded";
    private volatile boolean fallbackPollOutstanding;
    private volatile boolean fallbackPollReady;
    private volatile boolean fallbackPollPassed;
    private volatile String fallbackPollState = "not-polled";
    private boolean crafterBuildMode;
    private boolean crafterPlacementOnlyMode;
    private boolean crafterMatrixMode;
    private int crafterMatrixIndex;
    private final List<String> crafterMatrixPassedOrientations = new ArrayList<>();
    private volatile boolean crafterMatrixServerSetupValid;
    private volatile BlockPos crafterMatrixServerPlayerPosition;
    private volatile String crafterMatrixServerSetupSnapshot = "not-prepared";
    private long crafterMatrixLastSyncLogTick = -20;
    private long crafterMatrixResetSettleUntilTick;
    private boolean crafterBuildGatherStartedEmpty;
    private boolean crafterBuildServerPollOutstanding;
    private boolean crafterBuildServerPollReady;
    private boolean crafterBuildServerPollPassed;
    private String crafterBuildServerPollState = "not-polled";
    private BlockPos crafterBuildOrigin;
    private long crafterBuildVerifyStarted;
    private BuildSchematicTask crafterBuildTask;
    private volatile boolean naturalServerSeedValid;
    private volatile String naturalServerSeedState = "not-prepared";
    private volatile boolean naturalResourceServerPollOutstanding;
    private volatile boolean naturalResourceServerPollReady;
    private volatile boolean naturalResourceServerPollPassed;
    private volatile String naturalResourceServerPollState = "not-polled";
    private volatile String naturalResourceServerCraftingResult = "not-polled";
    private volatile List<ItemStack> naturalResourceServerInventory = List.of();
    private volatile int naturalResourceServerChestCount;
    private volatile int naturalResourceServerStickCount;
    private boolean naturalResourceMode;
    private CataloguedResourceTask naturalResourceCommandRoot;
    private boolean naturalResourceRootSeen;
    private boolean naturalResourceLogsSeen;
    private boolean naturalResourcePlanksSeen;
    private boolean naturalResourceChestCraftSeen;
    private boolean naturalResourceStickTaskSeen;
    private boolean naturalResourceStickPathSeen;
    private boolean naturalResourceContainerLootSeen;
    private volatile boolean naturalCheckpointOutstanding;
    private volatile boolean naturalCheckpointReady;
    private volatile boolean naturalCheckpointPassed;
    private volatile String naturalCheckpointState = "not-polled";
    private volatile int naturalCheckpointStickCount;
    private volatile boolean naturalBuildSiteCheckOutstanding;
    private volatile boolean naturalBuildSiteCheckReady;
    private volatile boolean naturalBuildSiteCheckPassed;
    private volatile String naturalBuildSiteCheckState = "not-checked";
    private volatile boolean naturalBuildVerifyOutstanding;
    private volatile boolean naturalBuildVerifyReady;
    private volatile boolean naturalBuildVerifyPassed;
    private volatile String naturalBuildVerifyState = "not-verified";
    private volatile int naturalBuildFinalStickCount;
    private volatile int naturalBuildFinalTorchCount;
    private volatile String concreteSeedState = "not-seeded";
    private volatile boolean concreteSeedValid;
    private volatile boolean concreteVerifyOutstanding;
    private volatile boolean concreteVerifyReady;
    private volatile boolean concreteVerifyPassed;
    private volatile String concreteVerifyState = "not-polled";
    private boolean concreteResourceTaskSeen;
    private boolean concretePlacementTaskSeen;
    private boolean concreteMiningTaskSeen;
    private boolean concreteSourceWaterSeen;
    private int concretePowderItemsConsumed;
    private int concreteLastPowderCount = 2;
    private int concretePowderStatesObserved;
    private int concreteHardeningTransitions;
    private int concreteMinedTransitions;
    private int concretePlacementTaskCount;
    private boolean concretePowderUseLogged;
    private BlockPos concretePoolOrigin;
    private MultiBuildAcceptanceScenario multiBuildScenario;
    private final Map<BlockPos, Block> concreteLastObservedBlocks = new java.util.HashMap<>();
    private final Set<BlockPos> concreteMiningTargets = new LinkedHashSet<>();
    private final Set<BlockPos> concretePowderCoordinates = new LinkedHashSet<>();
    private final Set<BlockPos> concreteHardenedCoordinates = new LinkedHashSet<>();
    private final Set<BlockPos> concreteMinedCoordinates = new LinkedHashSet<>();
    private volatile boolean saplingSeedValid;
    private volatile String saplingSeedState = "not-prepared";
    private volatile boolean saplingPollOutstanding;
    private volatile boolean saplingPollReady;
    private volatile boolean saplingPollPassed;
    private volatile String saplingPollState = "not-polled";
    private volatile int saplingPollCount;
    private volatile boolean saplingOutputResetReady;
    private volatile String saplingOutputResetState = "not-reset";
    private volatile boolean propagulePollOutstanding;
    private volatile boolean propagulePollReady;
    private volatile boolean propagulePollPassed;
    private volatile String propagulePollState = "not-polled";
    private volatile int propagulePollCount;
    private int saplingOriginalRandomTickSpeed = -1;
    private BlockPos saplingLeafOrigin;
    private BlockPos saplingMaturePropagule;
    private BlockPos saplingImmaturePropagule;
    private boolean saplingShearsSafeHandSeen;
    private boolean saplingSilkSafeHandSeen;
    private boolean saplingCollectorTraceSeen;
    private boolean saplingShearsTraceSeen;
    private boolean saplingSilkTraceSeen;
    private boolean propaguleCollectorTraceSeen;
    private boolean propaguleMatureDestroySeen;
    private boolean propaguleImmatureDestroySeen;
    private int saplingPollStep;
    private boolean fallbackVerificationPending;
    private long fallbackVerificationStarted;
    private ContainerAcceptanceScenario containerScenario;
    private CropAcceptanceScenario cropScenario;
    private AnimalFoodAcceptanceScenario animalFoodScenario;
    private FoodRangeAcceptanceScenario foodRangeScenario;
    private FoodBlockRangeAcceptanceScenario foodBlockRangeScenario;
    private MaterialLeafAcceptanceScenario materialLeafScenario;
    private CombatLootAcceptanceScenario combatLootScenario;
    private MudRootAcceptanceScenario mudRootScenario;
    private KelpAcceptanceScenario kelpScenario;
    private BuildCleanupCapacityAcceptanceScenario buildCleanupCapacityScenario;
    private AzaleaLeavesAcceptanceScenario azaleaLeavesScenario;
    private HangingRootsAcceptanceScenario hangingRootsScenario;
    private SmallDripleafAcceptanceScenario smallDripleafScenario;
    private DefaultFoodChooserAcceptanceScenario defaultFoodChooserScenario;
    private BuildInventoryCleanupAcceptanceScenario buildInventoryCleanupScenario;
    private MobDefenseCombatCapacityAcceptanceScenario mobDefenseCombatCapacityScenario;
    private boolean naturalMode;
    private volatile int naturalDiamondCount;
    private BlockPos naturalInitialPosition;
    private long naturalWorldSeed;
    private String naturalDimension = "unknown";
    private boolean litematicaRecoveryMode;
    private LitematicaRecoveryAcceptanceScenario litematicaRecoveryScenario;
    private boolean naturalDiamondComplete;
    private boolean naturalListComplete;
    private boolean naturalResourceListComplete;
    private boolean naturalWoodTraceSeen;
    private boolean naturalToolTraceSeen;
    private boolean naturalIronTraceSeen;
    private boolean naturalSmeltTraceSeen;
    private boolean naturalFuelTraceSeen;
    private boolean naturalDiamondTraceSeen;
    private boolean naturalBuildTaskSeen;
    private boolean naturalBuildMaterialGatherSeen;
    private BlockPos naturalBuildOrigin;
    private BlockPos naturalBuildTorchTarget;
    private int naturalBuildTorchOffset;
    private Direction naturalBuildTorchDirection = Direction.EAST;
    private List<NaturalTorchSite> naturalBuildSiteCandidates = List.of();
    private Path naturalBuildGameSchematic;
    private BlockState naturalBuildSupportState;
    private volatile boolean manualServerPollOutstanding;
    private volatile boolean manualServerPollReady;
    private volatile boolean manualServerPollValid;
    private volatile String manualServerPollState = "not-polled";
    private String lastManualServerPollLogged = "";
    private boolean manualVerificationPending;
    private long manualVerificationStarted;
    private String manualVerificationLabel;
    private Item[] manualVerificationItems;
    private int[] manualVerificationCounts;
    private Runnable manualVerificationContinuation;
    private BlockPos repairOrigin;
    private BlockPos repairDoorAnchor;
    private BlockPos repairBedAnchor;
    private boolean repairDoorDestroySeen;
    private boolean repairBedDestroySeen;
    private boolean repairInterruptCheckInFlight;
    private boolean repairInterruptionTriggered;
    private boolean repairInterruptionResumed;
    private boolean repairServerCheckStarted;
    private volatile boolean repairServerCheckReady;
    private volatile boolean repairServerCheckPassed;
    private volatile String repairServerCheckState = "not-checked";
    private volatile String repairSetupFailure;
    private BuildSchematicTask repairTask;
    private IStaticSchematic repairSchematic;
    private BlockPos doorPairOrigin;
    private BlockPos doorPairWestAnchor;
    private BlockPos doorPairEastAnchor;
    private IStaticSchematic doorPairSchematic;
    private BuildSchematicTask doorPairTask;
    private volatile boolean doorPairServerSeedValid;
    private volatile String doorPairServerSeedState = "not-seeded";
    private volatile String doorPairSetupFailure;
    private boolean doorPairServerCheckStarted;
    private volatile boolean doorPairServerCheckReady;
    private volatile boolean doorPairServerCheckPassed;
    private volatile String doorPairServerCheckState = "not-checked";
    private IStaticSchematic batchSchematic;
    private BuildSchematicTask batchBuildTask;
    private List<Block> batchBlocks = List.of();
    private BlockPos batchOrigin;
    private volatile String batchServerSeedState = "not-seeded";
    private volatile boolean batchServerSeedValid;
    private volatile boolean batchServerCheckStarted;
    private volatile boolean batchServerCheckReady;
    private volatile boolean batchServerCheckPassed;
    private volatile String batchServerCheckState = "not-checked";
    private boolean batchGatherTaskSeen;
    private boolean batchFirstGatherObservedEmptyInventory;
    private int batchNumberLastLogged;
    private Phase phase = Phase.WAITING;
    private long phaseStarted;
    private long ticks;
    private long worldWaitStartedTick;
    private long clientWorldReadyTick = -1;
    private long chunkTrackerWaitStartedTick = -1;
    private boolean clientWorldReadyLogged;
    private boolean chunkTrackerWaitLogged;
    private Path output;
    private Path gameSchematic;
    private Path mixedGameSchematic;
    private Path crafterBuildGameSchematic;
    private BlockPos floorOrigin;
    private BlockPos buildOrigin;
    private volatile UUID testPlayer;
    private String prefix = "@";
    private boolean resultWritten;
    private boolean setupQueued;
    private boolean chatSmokeDone;
    private boolean testingPlacement;
    private boolean testingMixedSchematic;
    private boolean mixedMaterialGatherSeen;
    private boolean hingeRepairMode;
    private boolean hingeFailureMode;
    private boolean hingeRemovalTaskSeen;
    private boolean hingeRemovalObserved;
    private boolean hingeRestorationTracked;
    private boolean hingeRestorationObserved;
    private boolean hingeFailureInjected;
    private boolean hingeFailureServerAirOutstanding;
    private volatile boolean hingeFailureServerAirReady;
    private volatile boolean hingeFailureServerAirConfirmed;
    private volatile String hingeFailureServerAirState = "not-checked";
    private long hingeFailureVerificationStarted;
    private volatile boolean hingeFailurePollOutstanding;
    private volatile boolean hingeFailurePollReady;
    private volatile boolean hingeFailurePollPassed;
    private volatile String hingeFailurePollState = "not-polled";
    private BuildSchematicTask hingeFailureBuildTask;
    private static final String HINGE_FAILURE_REASON =
            "Runtime acceptance controlled failure after authored neighbor removal";
    private volatile boolean mixedServerPollOutstanding;
    private volatile boolean mixedServerPollReady;
    private volatile boolean mixedServerPollPassed;
    private volatile String mixedServerPollState = "not-polled";
    private long mixedLastServerPollTick;
    private boolean manualStairsTaskSeen;
    private boolean manualTemplateTaskSeen;
    private boolean manualSmallRecipeTaskSeen;
    private boolean manualSmallRecipeTableSeen;
    private boolean manualCraftingSettingChanged;
    private boolean originalCraftingBookSetting;
    private boolean plankFuelSettingsChanged;
    private boolean originalFuelLimitSetting;
    private List<Item> originalSupportedFuels;
    private boolean smeltTaskSeen;
    private boolean multiSmeltMode;
    private boolean plankFuelMode;
    private boolean overstackSmeltMode;
    private boolean projectileClientSawMotion;
    private boolean projectileClientSawGrounded;
    private boolean projectileClientHasLastPosition;
    private boolean projectileClientRequestedFlight;
    private boolean projectileClientLoggedFlight;
    private double projectileClientLastX;
    private double projectileClientLastY;
    private double projectileClientLastZ;
    private long projectileLastServerCheckTick;
    private boolean fallbackSmeltSeen;
    private boolean fallbackCraftingTableSeen;
    private boolean fallbackDropperSeen;
    private boolean fallbackCrafterSeen;
    private BlockPos placementTarget;
    private BlockPos smithingTablePos;
    private BlockPos smeltingFurnacePos;
    private BlockPos manualTablePos;
    private adris.altoclef.util.schematic.SchematicSnapshot placementSnapshot;
    private String lastTaskSignature = "";

    private enum Phase {
        WAITING, DIAMOND, LIST, RESOURCE_LIST, BUILD_SETUP, BUILD, VERIFY,
        SAPLING_SETUP, SAPLING_SHEARS, SAPLING_SILK, SAPLING_PROPAGULE,
        SAPLING_VERIFY, SAPLING_PROPAGULE_VERIFY,
        STRIPPED_LOGS, MIXED_BUILD_SETUP, MIXED_BUILD, MIXED_VERIFY,
        SMITHING_SETUP, SMITHING, SMELT_SETUP, SMELT, FALLBACK_SETUP, FALLBACK,
        CONTAINERS, CROPS, ANIMAL_FOOD, FOOD_RANGE, FOOD_BLOCK_RANGE, MATERIAL_LEAF, MUD_ROOT, KELP, BUILD_CLEANUP_CAPACITY, AZALEA_LEAVES, HANGING_ROOTS, SMALL_DRIPLEAF, DEFAULT_FOOD_CHOOSER, BUILD_INVENTORY_CLEANUP, COMBAT_LOOT, MOB_DEFENSE_CAPACITY, MULTIBUILD, LITEMATICA_RECOVERY, NATURAL_CHECKPOINT, NATURAL_RESOURCE_LIST, NATURAL_RESOURCE_VERIFY, NATURAL_BUILD_TRAVEL, NATURAL_BUILD_SETUP, NATURAL_BUILD, NATURAL_BUILD_VERIFY,
        CONCRETE_SETUP, CONCRETE, CONCRETE_VERIFY, HINGE_FAILURE_VERIFY, CRAFTER_BUILD_VERIFY,
        MANUAL_STAIRS_SETUP, MANUAL_STAIRS,
        MANUAL_TEMPLATE_SETUP, MANUAL_TEMPLATE, MANUAL_GRID_SETUP,
        MANUAL_GRID_CRAFT, REPAIR_SETUP, REPAIR_BUILD, REPAIR_VERIFY,
        DOORPAIR_SETUP, DOORPAIR_BUILD, DOORPAIR_VERIFY,
        BATCH_SETUP, BATCH_BUILD, BATCH_VERIFY, PROJECTILE_SETUP, PROJECTILE,
        DIMENSIONS, DIMENSIONS_VERIFY, DONE, FAILED
    }

    private record NaturalTorchSite(BlockPos origin, Direction direction, int offset, BlockState support,
                                    int searchDistance) { }

    private record NaturalLogBreakReceipt(BlockPos position, String sourceBlock, long serverGameTime,
                                          String immediatePostBreakBlock) { }

    private static final class NaturalLogObservationSession {
        private final MinecraftServer server;
        private final UUID playerId;
        private final ResourceKey<Level> dimension;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ConcurrentMap<BlockPos, NaturalLogBreakReceipt> receipts = new ConcurrentHashMap<>();

        private NaturalLogObservationSession(MinecraftServer server, UUID playerId, ResourceKey<Level> dimension) {
            this.server = server;
            this.playerId = playerId;
            this.dimension = dimension;
        }
    }

    private final class NaturalBuildSiteApproachTask extends Task {
        private final List<NaturalTorchSite> candidates;
        private int candidateIndex;
        private long candidateStartedAt;
        private GetToBlockTask navigation;
        private boolean behaviorPushed;

        private NaturalBuildSiteApproachTask(List<NaturalTorchSite> candidates) {
            this.candidates = candidates;
        }

        @Override
        protected void onStart(AltoClef mod) {
            mod.getBehaviour().push();
            mod.getBehaviour().avoidBlockBreaking(position -> true);
            mod.getBehaviour().avoidBlockPlacing(position -> true);
            behaviorPushed = true;
            candidateIndex = 0;
            navigation = null;
            candidateStartedAt = ticks;
        }

        @Override
        protected Task onTick(AltoClef mod) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || client.level == null
                    || !client.level.dimension().identifier().toString().equals(naturalDimension)) {
                fail("Natural torch-site navigation lost its Overworld player/world");
                return null;
            }

            if (navigation != null && (navigation.getFailureSnapshot() != null
                    || ticks - candidateStartedAt > NATURAL_BUILD_SITE_APPROACH_TIMEOUT_TICKS)) {
                append("NATURAL_BUILD_SITE_REJECTED\torigin=" + candidates.get(candidateIndex).origin()
                        + "\treason=" + (navigation.getFailureSnapshot() == null
                        ? "safe navigation timed out" : navigation.getFailureSnapshot().reason()));
                candidateIndex++;
                navigation = null;
                return null;
            }

            if (candidateIndex >= candidates.size()) {
                fail("Could not navigate to any verified natural torch lane within "
                        + NATURAL_BUILD_SITE_SEARCH_RADIUS + " loaded horizontal blocks; no terrain was modified");
                return null;
            }

            NaturalTorchSite candidate = candidates.get(candidateIndex);
            if (client.player.blockPosition().equals(candidate.origin())) {
                if (!naturalTorchSiteMatches(client.level, candidate.origin(), candidate.offset(),
                        candidate.support(), candidate.direction())) {
                    append("NATURAL_BUILD_SITE_REJECTED\torigin=" + candidate.origin()
                            + "\tdirection=" + candidate.direction() + "\treason=site changed before arrival");
                    candidateIndex++;
                    navigation = null;
                    return null;
                }
                activateNaturalTorchSite(candidate);
                return null;
            }

            if (navigation == null) {
                candidateStartedAt = ticks;
                navigation = new GetToBlockTask(candidate.origin());
                append("NATURAL_BUILD_SITE_APPROACH\torigin=" + candidate.origin()
                        + "\tdirection=" + candidate.direction()
                        + "\toffset=" + candidate.offset()
                        + "\tsearchDistance=" + candidate.searchDistance()
                        + "\tblockBreaking=false\tblockPlacing=false");
            }
            return navigation;
        }

        @Override
        public boolean isFinished(AltoClef mod) {
            return phase != Phase.NATURAL_BUILD_TRAVEL;
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {
            if (behaviorPushed) {
                behaviorPushed = false;
                mod.getBehaviour().pop();
            }
        }

        @Override
        protected boolean isEqual(Task other) {
            return other instanceof RuntimeAcceptanceMod.NaturalBuildSiteApproachTask task
                    && task.candidates.equals(candidates);
        }

        @Override
        protected String toDebugString() {
            return "Find reachable natural torch lane (" + candidateIndex + "/" + candidates.size() + ")";
        }
    }

    private enum DimensionStage {
        NETHER_TO_NETHER,
        NETHER_TO_OVERWORLD,
        END_CENTRAL_TO_OVERWORLD,
        END_OUTER_TO_OVERWORLD,
        COMPLETE
    }

    private static final BlockPos DIMENSIONS_OVERWORLD_PORTAL = new BlockPos(64, 65, 0);
    private static final BlockPos DIMENSIONS_NETHER_PORTAL = new BlockPos(8, 65, 0);
    private static final BlockPos DIMENSIONS_END_EXIT_PORTAL = new BlockPos(0, 65, 0);
    private static final BlockPos DIMENSIONS_END_CENTRAL_LANDING = new BlockPos(7, 65, 7);
    private static final BlockPos DIMENSIONS_END_OUTER_GATEWAY = new BlockPos(900, 75, 0);

    private volatile boolean dimensionsFixtureReady;
    private volatile String dimensionsFixtureState = "not-prepared";
    private volatile boolean dimensionsFixturePassed;
    private DimensionStage dimensionStage = DimensionStage.COMPLETE;
    private ResourceKey<Level> dimensionExpected;
    private Runnable dimensionContinuation;
    private volatile boolean dimensionServerPollReady;
    private volatile boolean dimensionServerPollPassed;
    private volatile String dimensionServerPollState = "not-polled";
    private boolean dimensionTaskRunning;
    private boolean dimensionNetherOutTaskSeen;
    private boolean dimensionNetherBackTaskSeen;
    private boolean dimensionCentralRouteTaskSeen;
    private boolean dimensionOuterEntryTaskSeen;
    private boolean dimensionPearlInteractionSeen;
    private volatile boolean dimensionOuterGatewayCenterObserved;
    private boolean dimensionSetupSeedPearlKit;
    private boolean dimensionPearlConsumedVerified;
    private long dimensionGatewayCentralSyncStarted = -1;
    private volatile boolean dimensionExitPortalAfterGatewayMatches;
    private volatile boolean dimensionGatewayObservationOutstanding;
    private volatile boolean dimensionSetupReady;
    private volatile boolean dimensionSetupOutstanding;
    private volatile String dimensionSetupState = "not-started";
    private ResourceKey<Level> dimensionSetupExpected;
    private BlockPos dimensionSetupDestination;
    private long dimensionSetupStarted;
    private long dimensionInitialSyncStarted = -1;
    private boolean dimensionInitialSyncLogged;
    private Runnable dimensionSetupContinuation;
    private boolean dimensionOuterCommandActive;
    private int dimensionPearlsBeforeOuterCommand;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("altoclef.runtimeTest")) return;
        ensureOutput(Minecraft.getInstance());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    public static void recordConfirmedNaturalLogBreak(ServerLevel level, ServerPlayer player,
                                                       BlockPos position, BlockState brokenState) {
        NaturalLogObservationSession observation = naturalLogObservationSession;
        if (observation == null || !observation.active.get()
                || level.getServer() != observation.server
                || player.getUUID() == null || !player.getUUID().equals(observation.playerId)
                || !level.dimension().equals(observation.dimension)
                || player.gameMode() != GameType.SURVIVAL) return;

        Item brokenItem = brokenState.getBlock().asItem();
        boolean isLog = false;
        for (Item logItem : ItemHelper.LOG) {
            if (brokenItem == logItem) {
                isLog = true;
                break;
            }
        }
        if (!isLog) return;

        BlockPos immutablePosition = position.immutable();
        String sourceBlock = BuiltInRegistries.BLOCK.getKey(brokenState.getBlock()).toString();
        String immediatePostBreakBlock = BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(immutablePosition).getBlock()).toString();
        observation.receipts.putIfAbsent(immutablePosition, new NaturalLogBreakReceipt(
                immutablePosition, sourceBlock, level.getGameTime(), immediatePostBreakBlock));
    }

    private void clearNaturalLogObservation() {
        NaturalLogObservationSession observation = naturalLogObservationSession;
        if (observation != null && observation.playerId.equals(testPlayer)) {
            observation.active.set(false);
            naturalLogObservationSession = null;
        }
    }

    private void tick() {
        ticks++;
        if (phase == Phase.WAITING) {
            if (arenaReady.get()) {
                if (crafterMatrixMode && ticks < crafterMatrixResetSettleUntilTick) return;
                String requestedStartMode = System.getProperty("altoclef.runtimeStart", "full");
                if (!"mobdefense".equalsIgnoreCase(requestedStartMode) && !chatSmokeDone) {
                    runChatInputSmoke();
                    if (phase == Phase.FAILED) return;
                }
                String startMode = requestedStartMode;
                if ("containers".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=containers\tresource gathering/build phases skipped; prepared chests test loot/deposit/stash transactions");
                    containerScenario = new ContainerAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=containers verified loot, single and multi-item deposits, stash, and two-log overflow crafting: 60 oak planks became exact 64/4 stacks, dirt slot 5 remained, client cache reported 68 planks and 24 empty slots, server/client inventories matched, and cursor/grid/menu were clean");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.CONTAINERS;
                    phaseStarted = ticks;
                } else if ("animalfood".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=animalfood\tprepared adult cows, furnace, sword and coal; natural animal and prerequisite discovery skipped");
                    animalFoodScenario = new AnimalFoodAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=animalfood verified normal cooked beef gathering from prepared cows and smelting with exact server/client output and clean UI; furnace, sword and fuel were provided");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.ANIMAL_FOOD;
                    phaseStarted = ticks;
                } else if ("buildcleanup".equalsIgnoreCase(startMode)) {
                    buildInventoryCleanupScenario = new BuildInventoryCleanupAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=buildcleanup verified already-matching schematic completion waits for carried and crafting-grid item conservation and clean synchronized inventory; seeded inputs; no gathering or placement claim");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.BUILD_INVENTORY_CLEANUP;
                    phaseStarted = ticks;
                } else if ("foodchooser".equalsIgnoreCase(startMode)) {
                    defaultFoodChooserScenario = new DefaultFoodChooserAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=foodchooser verified actual chat @food 1 invokes the default food chooser and collects vanilla carrot drops from a prepared mature crop; auto-eat disabled for observation and restored; natural source discovery skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.DEFAULT_FOOD_CHOOSER;
                    phaseStarted = ticks;
                } else if ("buildcapacity".equalsIgnoreCase(startMode)) {
                    buildCleanupCapacityScenario = new BuildCleanupCapacityAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=buildcapacity verified successful build cleanup waits with full inventory without dropping the carried named stone, then resumes after a real chest transfer frees capacity; prepared inputs and already-matching schematic");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.BUILD_CLEANUP_CAPACITY;
                    phaseStarted = ticks;
                } else if ("azalealeaves".equalsIgnoreCase(startMode)) {
                    azaleaLeavesScenario = new AzaleaLeavesAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=azalealeaves verified normal resource-list gathering of both prepared azalea leaf blocks using supplied shears; server/client outputs and source states match; natural discovery and shears prerequisites skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.AZALEA_LEAVES;
                    phaseStarted = ticks;
                } else if ("hangingroots".equalsIgnoreCase(startMode)) {
                    hangingRootsScenario = new HangingRootsAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=hangingroots verified normal gathering of a prepared hanging-roots block using supplied shears; exact client/server item components, durability, source and anchor states, drops and UI; natural discovery and shears prerequisites skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.HANGING_ROOTS;
                    phaseStarted = ticks;
                } else if ("smalldripleaf".equalsIgnoreCase(startMode)) {
                    smallDripleafScenario = new SmallDripleafAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=smalldripleaf verified normal @get gathering of one prepared small-dripleaf using supplied shears; exact client/server item components and durability, both source halves, support, drops and UI; natural discovery and shears prerequisites skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.SMALL_DRIPLEAF;
                    phaseStarted = ticks;
                } else if ("kelp".equalsIgnoreCase(startMode)) {
                    kelpScenario = new KelpAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=kelp verified normal recursive @get dried_kelp_block 1 from empty inventory and prepared logs, coal ore and shallow-water kelp; no tools or outputs seeded; exact synchronized ItemStacks and clean survival/UI; natural discovery and diving skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.KELP;
                    phaseStarted = ticks;
                } else if ("mudroot".equalsIgnoreCase(startMode)) {
                    mudRootScenario = new MudRootAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=mudroot verified normal @get mud 2 then recursive @get muddy_mangrove_roots 2 from empty inventory and prepared raw sources; no tools or outputs seeded; exact client/server ItemStacks and clean survival/UI; natural discovery skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.MUD_ROOT;
                    phaseStarted = ticks;
                } else if ("combatloot".equalsIgnoreCase(startMode)) {
                    combatLootScenario = new CombatLootAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=combatloot verified normal @get rotten_flesh 1 against a prepared AI zombie with supplied iron sword; actual damage death vanilla loot and durability changes; synchronized inventories and clean UI; natural discovery and weapon prerequisites skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.COMBAT_LOOT;
                    phaseStarted = ticks;
                } else if ("mobdefense".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=mobdefense\tdiamond/list/build/command-dispatch phases skipped; prepared fixed two-zombie arena measures the production MobDefense scheduler choice only");
                    mobDefenseCombatCapacityScenario = new MobDefenseCombatCapacityAcceptanceScenario(
                            Debug.jankModInstance, this::append, this::failMobDefense, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=mobdefense preserved the upstream wooden-sword combat-capacity tier: in a fixed prepared arena, two synchronized ordinary zombies produced Mob Defense priority 80 and the assigned RunAwayFromHostilesTask root; the runner selection was recorded before task execution, then fixture state and full inventory/equipment synchronization were checked; natural combat and resource gathering skipped");
                        append("MOB_DEFENSE_CAPACITY_ACCEPTANCE\tPASS\tstrict terminal scheduler, branch, synchronization, and clean-state assertions passed");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.MOB_DEFENSE_CAPACITY;
                    phaseStarted = ticks;
                } else if ("materialleaf".equalsIgnoreCase(startMode)) {
                    materialLeafScenario = new MaterialLeafAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=materialleaf verified normal @list and recursive @get cinnabar_bricks 4 and sulfur_bricks 4 from empty inventory and supplied raw source blocks; exact synchronized inventories and clean UI; natural discovery skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.MATERIAL_LEAF;
                    phaseStarted = ticks;
                } else if ("foodblockrange".equalsIgnoreCase(startMode)) {
                    foodBlockRangeScenario = new FoodBlockRangeAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=foodblockrange verified actual private CollectFoodTask bounded block helper harvest, outside crop preservation, idle and resumed selection; default chooser and navigation path boundaries skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.FOOD_BLOCK_RANGE;
                    phaseStarted = ticks;
                } else if ("foodrange".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=foodrange\tdiamond/list/build and natural resource discovery skipped; direct real CollectFoodTask helper/wrapper proof uses radius 8, while default CollectFoodTask chooser/radius 100 is not exercised");
                    foodRangeScenario = new FoodRangeAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=foodrange verified the real private CollectFoodTask helper at radius 8, wrapper acceptance and physical pickup for two in-range berries, preserved a client-tracked out-of-range drop for 100 ticks, exact server/client counts and clean UI; the default CollectFoodTask chooser/radius 100 was skipped");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.FOOD_RANGE;
                    phaseStarted = ticks;
                } else if ("crops".equalsIgnoreCase(startMode) || "cropslist".equalsIgnoreCase(startMode)
                        || "cropslistcontainer".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=" + startMode + "\tprepared mature and immature wheat test harvesting and configured replanting; natural farm discovery skipped");
                    cropScenario = new CropAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        append("SUMMARY\tPASS\t26.2 runtimeStart=" + startMode + " verified mature wheat harvest, immature preservation, configured replanting, exact server/client output and clean inventory UI in a prepared fixture"
                                + ("cropslistcontainer".equalsIgnoreCase(startMode)
                                ? "; satisfied wheat-seed target ignored its warmed chest cache, preserving exact server chest and client cache counts" : ""));
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.CROPS;
                    phaseStarted = ticks;
                } else if ("sapling".equalsIgnoreCase(startMode)) {
                    if (!TaskCatalogue.taskExists("oak_sapling")
                            || !TaskCatalogue.taskExists("mangrove_propagule")) {
                        fail("Sapling runtime acceptance requires oak_sapling and mangrove_propagule catalog entries");
                        return;
                    }
                    append("SKIP\truntimeStart=sapling\tdiamond/list/build phases skipped; empty resource inventory with real shears and a Silk Touch axe tests sapling tool safety, followed by mature-versus-immature propagule selection");
                    runSaplingSetup();
                } else if ("build".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=build\tdiamond/list/resource-list phases skipped explicitly");
                    runBuildCommand();
                } else if ("multibuild".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=multibuild\tdiamond/list/resource-list phases skipped; active two-region Litematica build starts with empty inventory and only oak logs, stone floor, and coal ore fixtures");
                    multiBuildScenario = new MultiBuildAcceptanceScenario(Debug.jankModInstance,
                            this::append, this::fail, () -> {
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.MULTIBUILD;
                    phaseStarted = ticks;
                } else if ("litematicarecovery".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=litematicarecovery\tdiamond/list phases skipped; fresh natural world and pinned rotated active Litematica placement required; no terrain or material fixtures are seeded");
                    litematicaRecoveryScenario = new LitematicaRecoveryAcceptanceScenario(
                            Debug.jankModInstance, this::append, this::fail, () -> {
                        append("LITEMATICA_RECOVERY_ACCEPTANCE\tPASS\tcompleted natural rotated active-placement gather/build with one production MobDefense interruption, same-root recovery, synchronized final state, and provenance gates");
                        phase = Phase.DONE;
                        writeResult();
                    });
                    phase = Phase.LITEMATICA_RECOVERY;
                    phaseStarted = ticks;
                    litematicaRecoveryScenario.begin(ticks);
                } else if ("manual".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=manual\tdiamond/list/resource-list/build/placement/stripped-log/mixed-build/smithing phases skipped explicitly");
                    try {
                        forceManualCraftingMode(Debug.jankModInstance);
                        runManualStairsSetup();
                    } catch (Exception error) {
                        fail("Could not disable recipe-book crafting for manual runtime acceptance: " + error);
                    }
                } else if ("repair".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=repair\tdiamond/list/resource-list/full gathering phases skipped; exact door and bed items are seeded; direct BuildSchematicTask paired-block repair only");
                    runRepairSetup();
                } else if ("doorpair".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=doorpair\tdiamond/list/resource-list phases skipped; exact two oak doors seeded; direct BuildSchematicTask adjacent-door hinge placement only");
                    runDoorPairSetup();
                } else if ("mixed".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=mixed\tdiamond/list/resource-list, file build, active placement, and stripped-log phases skipped explicitly");
                    runMixedBuildCommand();
                } else if ("hingerepair".equalsIgnoreCase(startMode)) {
                    hingeRepairMode = true;
                    append("SKIP\truntimeStart=hingerepair\tdiamond/list/resource-list, file build, active placement, stripped-log, and smithing phases skipped; exact authored double slab is seeded in the 36-cell build fixture and inventory remains empty");
                    runMixedBuildCommand();
                } else if ("hingefailure".equalsIgnoreCase(startMode)) {
                    hingeFailureMode = true;
                    append("SKIP\truntimeStart=hingefailure\tdiamond/list/resource-list/file-build/active-placement/stripped-log/smithing phases skipped; empty-inventory mixed build fixture seeds its exact authored double slab; controlled failure is injected only after client/server confirm AIR and the live BuildSchematicTask tracks local cell (12,0,0)");
                    runMixedBuildCommand();
                } else if ("smithing".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=smithing\tworld gathering/build phases skipped; two successive upgrades from exact seeded inputs");
                    runSmithingSetup();
                } else if ("smelt".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=smelt\tworld gathering/build phases skipped; furnace smelting from exact seeded raw iron and coal");
                    multiSmeltMode = false;
                    plankFuelMode = false;
                    overstackSmeltMode = false;
                    runSmeltSetup();
                } else if ("smeltoverstack".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=smeltoverstack\tworld gathering/build phases skipped; regression checks 3 iron ingots from 4 raw iron and 1 coal, retaining exactly 1 raw iron");
                    multiSmeltMode = false;
                    plankFuelMode = false;
                    overstackSmeltMode = true;
                    runSmeltSetup();
                } else if ("multismelt".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=multismelt\tworld gathering/build phases skipped; direct multi-target furnace task from exact seeded raw iron, raw copper, and coal");
                    multiSmeltMode = true;
                    plankFuelMode = false;
                    overstackSmeltMode = false;
                    runSmeltSetup();
                } else if ("plankfuel".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=plankfuel\tworld gathering/build phases skipped; iron smelting from exact seeded raw iron and oak planks with supported fuels restricted to oak planks");
                    multiSmeltMode = false;
                    plankFuelMode = true;
                    overstackSmeltMode = false;
                    try {
                        forcePlankFuelMode(Debug.jankModInstance);
                        runSmeltSetup();
                    } catch (Exception error) {
                        fail("Could not restrict runtime smelting fuel to oak planks: " + error);
                    }
                } else if ("projectile".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=projectile\tdiamond/list/resource-list/build phases skipped; real integrated-server arrow ticks through moving and embedded states");
                    runProjectileSetup();
                } else if ("fallback".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=fallback\tdiamond/list/resource-list/build phases skipped; full crafter dependency chain from empty inventory");
                    runFallbackSetup();
                } else if ("crafterbuild".equalsIgnoreCase(startMode)) {
                    crafterBuildMode = true;
                    append("SKIP\truntimeStart=crafterbuild\tdiamond/list/resource-list/non-build phases skipped; player inventory begins empty; prepared arena supplies ordinary wood/stone and redstone ore; no crafted outputs are seeded");
                    runFallbackSetup();
                } else if ("crafterplacement".equalsIgnoreCase(startMode)) {
                    crafterBuildMode = true;
                    crafterPlacementOnlyMode = true;
                    append("SKIP\truntimeStart=crafterplacement\tdiamond/list/resource-gathering/non-build phases skipped; seed exactly one stone support and one crafter; test normal @build placement only");
                    runFallbackSetup();
                } else if ("craftermatrix".equalsIgnoreCase(startMode)) {
                    crafterBuildMode = true;
                    crafterPlacementOnlyMode = true;
                    crafterMatrixMode = true;
                    append("SKIP\truntimeStart=craftermatrix\tdiamond/list/resource-gathering/non-build phases skipped; run normal @build with exactly one stone support and one crafter for all 12 explicit FrontAndTop orientations, resetting the prepared arena between runs");
                    startCrafterMatrixIteration();
                } else if ("natural".equalsIgnoreCase(startMode)) {
                    naturalMode = true;
                    append("NATURAL_WORLD\tmode=natural\tsetup spawned no terrain/resources/buildings and did not teleport\tseed="
                            + naturalWorldSeed + "\tdimension=" + naturalDimension + "\tstart=" + naturalInitialPosition);
                    append("COMMAND_PLAN\t@get diamond -> @list -> @get [chest, stick] -> exact server/client resource checkpoint -> @build natural-runtime-torch.litematic; build material collection and placement run on unmodified natural terrain");
                    startNaturalAcceptance();
                } else if ("naturalresource".equalsIgnoreCase(startMode)) {
                    naturalResourceMode = true;
                    append("NATURAL_RESOURCE_WORLD\tseed=" + naturalWorldSeed
                            + "\tdimension=" + naturalDimension + "\tstart=" + naturalInitialPosition
                            + "\tsetup changed inventory/player state only; no terrain edits or teleport");
                    startNaturalResourceListAcceptance();
                } else if ("batch".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=batch\tdiamond/list/resource-list/full mode skipped; exact 29-item batch fixture supplied as dropped entities");
                    runBatchSetup();
                } else if ("dimensions".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=dimensions\tdiamond/list/resource-list/build phases skipped; real linked portals and a vanilla-feature End gateway fixture exercise normal dimension commands");
                    startDimensionAcceptance();
                } else if ("concrete".equalsIgnoreCase(startMode)) {
                    append("SKIP\truntimeStart=concrete\tdiamond/list/build and recursive powder/source ingredient gathering skipped; exact white concrete powder and stone pickaxe are seeded; the contained source-water pool is fixture-only");
                    runConcreteSetup();
                } else {
                    startDiamondTask();
                }
            }
            else if (!setupQueued) beginWhenReady();
            else if (ticks - phaseStarted > PHASE_TIMEOUT_TICKS) fail("Timed out waiting for integrated-server arena setup");
            return;
        }
        if (phase == Phase.DONE || phase == Phase.FAILED) return;
        if (phase == Phase.CONTAINERS) {
            containerScenario.tick();
            return;
        }
        if (phase == Phase.CROPS) {
            cropScenario.tick();
            return;
        }
        if (phase == Phase.ANIMAL_FOOD) {
            animalFoodScenario.tick();
            return;
        }
        if (phase == Phase.BUILD_INVENTORY_CLEANUP) {
            buildInventoryCleanupScenario.tick();
            return;
        }
        if (phase == Phase.DEFAULT_FOOD_CHOOSER) {
            defaultFoodChooserScenario.tick();
            return;
        }
        if (phase == Phase.BUILD_CLEANUP_CAPACITY) {
            buildCleanupCapacityScenario.tick();
            return;
        }
        if (phase == Phase.HANGING_ROOTS) {
            hangingRootsScenario.tick();
            return;
        }
        if (phase == Phase.SMALL_DRIPLEAF) {
            smallDripleafScenario.tick();
            return;
        }
        if (phase == Phase.AZALEA_LEAVES) {
            azaleaLeavesScenario.tick();
            return;
        }
        if (phase == Phase.KELP) {
            kelpScenario.tick();
            return;
        }
        if (phase == Phase.MUD_ROOT) {
            mudRootScenario.tick();
            return;
        }
        if (phase == Phase.COMBAT_LOOT) {
            combatLootScenario.tick();
            return;
        }
        if (phase == Phase.MOB_DEFENSE_CAPACITY) {
            mobDefenseCombatCapacityScenario.tick();
            return;
        }
        if (phase == Phase.MATERIAL_LEAF) {
            materialLeafScenario.tick();
            return;
        }
        if (phase == Phase.FOOD_BLOCK_RANGE) {
            foodBlockRangeScenario.tick();
            return;
        }
        if (phase == Phase.FOOD_RANGE) {
            foodRangeScenario.tick();
            return;
        }
        if (phase == Phase.MULTIBUILD) {
            observeMultiBuildTaskTrace();
            multiBuildScenario.tick(ticks);
            return;
        }
        if (phase == Phase.LITEMATICA_RECOVERY) {
            observeLitematicaRecoveryTaskTrace();
            litematicaRecoveryScenario.tick(ticks);
            return;
        }
        if (phase == Phase.DIMENSIONS_VERIFY) {
            pollDimensionVerification();
            return;
        }
        if (phase == Phase.CONCRETE) observeConcreteProgress();
        if (phase == Phase.CONCRETE_VERIFY) {
            observeConcreteProgress();
            pollConcreteVerification();
            return;
        }
        if (phase == Phase.HINGE_FAILURE_VERIFY) {
            pollHingeFailureVerification();
            return;
        }
        if (phase == Phase.CRAFTER_BUILD_VERIFY) {
            pollCrafterBuildVerification();
            return;
        }
        if (phase == Phase.NATURAL_CHECKPOINT) {
            pollNaturalResourceCheckpoint();
            return;
        }
        if (phase == Phase.NATURAL_RESOURCE_VERIFY) {
            pollNaturalResourceListResult();
            return;
        }
        if (phase == Phase.NATURAL_BUILD_SETUP) {
            pollNaturalBuildSite();
            return;
        }
        if (phase == Phase.NATURAL_BUILD_VERIFY) {
            pollNaturalBuildVerification();
            return;
        }
        if (phase == Phase.SAPLING_SETUP && saplingPrepared.get()) {
            startSaplingShearsCommand();
        }
        if (phase == Phase.SAPLING_VERIFY) {
            pollSaplingVerification();
            return;
        }
        if (phase == Phase.SAPLING_PROPAGULE_VERIFY) {
            pollPropaguleVerification();
            return;
        }
        if (phase == Phase.DIMENSIONS) {
            observeOuterGatewayTransition();
            if (ticks - phaseStarted > PHASE_TIMEOUT_TICKS) {
                fail("Dimension runtime acceptance timed out at stage " + dimensionStage);
            }
        }
        if (manualVerificationPending) pollManualServerResult();
        if (fallbackVerificationPending) pollFallbackServerResult();

        if (phase == Phase.REPAIR_SETUP && repairSetupFailure != null) {
            fail("Paired-block repair setup failed: " + repairSetupFailure);
        } else if (phase == Phase.REPAIR_SETUP && repairSeeded.get()) startRepairTask();
        if (phase == Phase.DOORPAIR_SETUP && doorPairSetupFailure != null) {
            fail("Door-pair setup failed: " + doorPairSetupFailure);
        } else if (phase == Phase.DOORPAIR_SETUP && doorPairSeeded.get()) startDoorPairTask();
        if (phase == Phase.DOORPAIR_VERIFY) verifyDoorPairBuild();
        if (phase == Phase.REPAIR_BUILD) maybeInterruptAndResumeRepairTask();
        if (phase == Phase.REPAIR_VERIFY) verifyRepairBuild();
        if (phase == Phase.BATCH_SETUP && batchSeeded.get()) startBatchTask();
        if (phase == Phase.BATCH_BUILD) observeBatchProgress();
        if (phase == Phase.BATCH_VERIFY) verifyBatchBuild();
        observeBuildProgress();
        if (crafterPlacementOnlyMode && phase == Phase.FALLBACK && ticks - phaseStarted > 500) {
            fail("Crafter placement-only regression exceeded its 25-second limit: "
                    + (Minecraft.getInstance().player == null ? "player=null" : clientInventoryState()));
            return;
        }

        AltoClef mod = Debug.jankModInstance;
        if (phase == Phase.MANUAL_GRID_CRAFT && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof CraftingMenu) {
            manualSmallRecipeTableSeen = true;
        }
        if (!naturalMode && phase == Phase.DIAMOND && ticks % 100 == 0 && mod != null
                && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null) {
            var player = Minecraft.getInstance().player;
            BlockPos log = floorOrigin.offset(1, 1, 1);
            append("OBSERVE\t" + ticks + "\tposition=" + player.blockPosition()
                    + "\tfixtureLog=" + Minecraft.getInstance().level.getBlockState(log)
                    + "\tcachedOakLogs=" + mod.getBlockTracker().getKnownLocations(Blocks.OAK_LOG).size()
                    + "\theldLogs=" + count(player, Items.OAK_LOG)
                    + "\theldIron=" + count(player, Items.IRON_INGOT)
                    + "\theldDiamond=" + count(player, Items.DIAMOND));
        }
        if ((naturalMode && isNaturalCommandPhase(phase)
                || naturalResourceMode && phase == Phase.NATURAL_RESOURCE_LIST)
                && ticks % 100 == 0 && mod != null
                && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null) {
            appendNaturalObservation(mod);
        }
        if (mod != null && mod.getTaskRunner() != null) {
            var chain = mod.getTaskRunner().getCurrentTaskChain();
            if (chain != null) {
                List<String> sequence = new ArrayList<>();
                for (Task task : chain.getTasks()) {
                    String entry = task.getClass().getSimpleName() + " :: " + task;
                    sequence.add(entry);
                }
                if (phase == Phase.MULTIBUILD && multiBuildScenario != null
                        && Minecraft.getInstance().player != null) {
                    multiBuildScenario.observeTaskTrace(sequence,
                            inventoryCount(Minecraft.getInstance().player.getInventory()));
                }
                String signature = String.join(" -> ", sequence);
                observeHingeRepairProgress(signature, chain.getTasks());
                if (!signature.isEmpty() && !signature.equals(lastTaskSignature)) {
                    taskTrace.add(signature);
                    lastTaskSignature = signature;
                    append("TASK\t" + ticks + "\t" + signature);
                    if (testingMixedSchematic && phase == Phase.MIXED_BUILD
                            && signature.contains("CataloguedResourceTask")) {
                        mixedMaterialGatherSeen = true;
                        append("ASSERT\tmixed schematic material collection task observed before build completion");
                    }
                    if (phase == Phase.MANUAL_STAIRS && signature.contains("CraftGenericManuallyTask")) {
                        manualStairsTaskSeen = true;
                        append("ASSERT\tmanual oak-stairs crafting task observed");
                    }
                    if (phase == Phase.MANUAL_TEMPLATE && signature.contains("CraftGenericManuallyTask")) {
                        manualTemplateTaskSeen = true;
                        append("ASSERT\tmanual template-duplication crafting task observed");
                    }
                    if (phase == Phase.MANUAL_GRID_CRAFT && signature.contains("CraftGenericManuallyTask")) {
                        manualSmallRecipeTaskSeen = true;
                        append("ASSERT\tmanual 2x2 recipe task observed while the 3x3 table screen was open");
                    }
                    if (phase == Phase.REPAIR_BUILD && signature.contains("DestroyBlockTask")) {
                        if (!repairDoorDestroySeen && signature.contains(repairDoorAnchor.toShortString())) {
                            repairDoorDestroySeen = true;
                            append("ASSERT\tDestroyBlockTask observed at partial door lower-half anchor "
                                    + repairDoorAnchor.toShortString());
                        }
                        if (!repairBedDestroySeen && signature.contains(repairBedAnchor.toShortString())) {
                            repairBedDestroySeen = true;
                            append("ASSERT\tDestroyBlockTask observed at partial bed foot anchor "
                                    + repairBedAnchor.toShortString());
                        }
                    }
                    observeDimensionTaskTrace(signature);
                    observeConcreteTaskTrace(signature, chain.getTasks());
                    if (phase == Phase.BATCH_BUILD && signature.contains("CataloguedResourceTask")) {
                        batchGatherTaskSeen = true;
                        if (Minecraft.getInstance().player != null
                                && !batchFirstGatherObservedEmptyInventory
                                && inventoryCount(Minecraft.getInstance().player.getInventory()) == 0) {
                            batchFirstGatherObservedEmptyInventory = true;
                            append("ASSERT\tfirst CataloguedResourceTask began while player inventory was empty");
                        }
                    }
                }
                observeSaplingTaskTrace(signature);
                if (naturalResourceMode && phase == Phase.NATURAL_RESOURCE_LIST) {
                    observeNaturalResourceListTrace(chain.getTasks());
                }
                if (phase == Phase.SMELT && !smeltTaskSeen
                        && sequence.stream().anyMatch(entry -> entry.contains("SmeltInFurnaceTask"))) {
                    smeltTaskSeen = true;
                    taskTrace.add(signature);
                    if (!signature.equals(lastTaskSignature)) {
                        lastTaskSignature = signature;
                        append("TASK\t" + ticks + "\t" + signature);
                    }
                    append("ASSERT\tSmeltInFurnaceTask observed in active task trace");
                }
                if (phase == Phase.FALLBACK) {
                    observeFallbackTaskTrace(sequence);
                    for (Task task : chain.getTasks()) {
                        if (task instanceof BuildSchematicTask buildTask) crafterBuildTask = buildTask;
                    }
                    if (crafterBuildMode && !crafterPlacementOnlyMode && !crafterBuildGatherStartedEmpty
                            && sequence.stream().anyMatch(entry -> entry.contains("CataloguedResourceTask"))
                            && Minecraft.getInstance().player != null
                            && inventoryCount(Minecraft.getInstance().player.getInventory()) == 0) {
                        crafterBuildGatherStartedEmpty = true;
                        append("ASSERT\tcrafter schematic's first CataloguedResourceTask started while player inventory was empty");
                    }
                }
                if (naturalMode) observeNaturalTaskTrace(sequence);
            }
            if (repairInterruptionTriggered && !repairInterruptionResumed && chain != null
                    && "User Tasks".equals(chain.getName())
                    && chain.getTasks().stream().anyMatch(BuildSchematicTask.class::isInstance)) {
                repairInterruptionResumed = true;
                append("ASSERT\tBuildSchematicTask resumed in User Tasks after controlled one-tick chain interruption");
            }
        }

        int timeoutTicks = naturalMode || naturalResourceMode ? NATURAL_TIMEOUT_TICKS : PHASE_TIMEOUT_TICKS;
        if (ticks - phaseStarted > timeoutTicks) {
            fail("Timed out in phase " + phase);
            return;
        }
        if (phase == Phase.BUILD_SETUP && buildInventoryCleared.get()
                && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.getInventory().isEmpty()) {
            phase = Phase.BUILD;
            phaseStarted = ticks;
            String source = testingPlacement ? "placement" : "runtime-acceptance.litematic";
            buildOrigin = Minecraft.getInstance().player.blockPosition();
            append("COMMAND\t@build " + source + "\tfixture=" + gameSchematic);
            execute("build " + source, () -> {
                phase = Phase.VERIFY;
                phaseStarted = ticks;
                append("ASSERT\tbuild task completed; checking placed block");
            });
        }
        if (phase == Phase.MIXED_BUILD_SETUP && mixedFixtureSeeded.get()
                && Minecraft.getInstance().player != null && Minecraft.getInstance().level != null) {
            BlockPos fixtureOrigin = buildOrigin;
            boolean clientPrepared = buildInventoryCleared.get()
                    && Minecraft.getInstance().player.getInventory().isEmpty()
                    && mixedFixtureIsPrepared(Minecraft.getInstance().level, fixtureOrigin,
                            hingeRepairMode || hingeFailureMode);
            if (clientPrepared) {
                phase = Phase.MIXED_BUILD;
                phaseStarted = ticks;
                append("MIXED_FIXTURE_READY\tclient confirmed expected AIR region and optional authored slab seed before build\torigin=" + fixtureOrigin);
                append("COMMAND\t@build mixed-runtime-acceptance.litematic\tfixture=" + mixedGameSchematic);
                execute("build mixed-runtime-acceptance.litematic", () -> {
                    if (hingeFailureMode) {
                        phase = Phase.HINGE_FAILURE_VERIFY;
                        hingeFailureVerificationStarted = ticks;
                        append("ASSERT\tnormal @build command completed after the injected failure path; verifying exact server/client restoration and original failure reason");
                    } else {
                        phase = Phase.MIXED_VERIFY;
                    }
                    phaseStarted = ticks;
                    if (!hingeFailureMode) append("ASSERT\tmixed schematic build task completed; checking full block states");
                });
            } else if (ticks - phaseStarted > 100) {
                fail("Mixed fixture setup did not synchronize within 100 ticks; inventory="
                        + clientInventoryState() + ", fixture="
                        + mixedFixtureSnapshot(Minecraft.getInstance().level, fixtureOrigin));
            }
        }
        if (phase == Phase.VERIFY) verifyBuild();
        if (phase == Phase.MIXED_VERIFY) verifyMixedBuild();
        if (phase == Phase.HINGE_FAILURE_VERIFY) pollHingeFailureVerification();
        if (phase == Phase.SMITHING_SETUP && smithingSeeded.get()) startSmithingCommand();
        if (phase == Phase.SMELT_SETUP && smeltSeeded.get()) {
            if (multiSmeltMode) startMultiSmeltTask();
            else startSmeltCommand();
        }
        if (phase == Phase.PROJECTILE_SETUP && projectileSeeded.get()) startProjectileAcceptance();
        if (phase == Phase.PROJECTILE) observeProjectileAcceptance();
        if (phase == Phase.FALLBACK_SETUP && fallbackSeeded.get()) startFallbackCommand();
        if (phase == Phase.CONCRETE_SETUP && concreteSeeded.get()) startConcreteCommand();
        if (phase == Phase.MANUAL_STAIRS_SETUP && manualSeeded.get()) startManualStairsCommand();
        if (phase == Phase.MANUAL_TEMPLATE_SETUP && manualSeeded.get()) startManualTemplateCommand();
        if (phase == Phase.MANUAL_GRID_SETUP && manualSeeded.get()) startManualSmallRecipe();
    }

    /** The multibuild phase returns before the generic task-trace observer below. */
    private void observeMultiBuildTaskTrace() {
        AltoClef mod = Debug.jankModInstance;
        Minecraft client = Minecraft.getInstance();
        if (mod == null || mod.getTaskRunner() == null || client.player == null) return;
        var chain = mod.getTaskRunner().getCurrentTaskChain();
        if (chain == null) return;
        List<String> sequence = new ArrayList<>();
        for (Task task : chain.getTasks()) {
            sequence.add(task.getClass().getSimpleName() + " :: " + task);
        }
        multiBuildScenario.observeTaskTrace(sequence, inventoryCount(client.player.getInventory()));
        String signature = String.join(" -> ", sequence);
        if (!signature.isEmpty() && !signature.equals(lastTaskSignature)) {
            taskTrace.add(signature);
            lastTaskSignature = signature;
            append("TASK\t" + ticks + "\t" + signature);
        }
    }

    /** The recovery phase returns early, so collect its active task tree before ticking the verifier. */
    private void observeLitematicaRecoveryTaskTrace() {
        if (litematicaRecoveryScenario == null || Debug.jankModInstance == null
                || Debug.jankModInstance.getTaskRunner() == null) return;
        var chain = Debug.jankModInstance.getTaskRunner().getCurrentTaskChain();
        if (chain == null) return;
        List<String> sequence = new ArrayList<>();
        for (Task task : chain.getTasks()) {
            sequence.add(task.getClass().getSimpleName() + " :: " + task);
        }
        litematicaRecoveryScenario.observeTaskTrace(sequence);
        String signature = String.join(" -> ", sequence);
        if (!signature.isEmpty() && !signature.equals(lastTaskSignature)) {
            taskTrace.add(signature);
            lastTaskSignature = signature;
            append("TASK\t" + ticks + "\t" + signature);
        }
    }

    private void observeHingeRepairProgress(String signature, List<Task> activeTasks) {
        if ((!hingeRepairMode && !hingeFailureMode) || phase != Phase.MIXED_BUILD) return;
        BlockPos hingeNeighbor = buildOrigin.offset(12, 0, 0);
        if (signature.contains("DestroyBlockTask") && signature.contains(hingeNeighbor.toShortString())
                && !hingeRemovalTaskSeen) {
            hingeRemovalTaskSeen = true;
            append("ASSERT\tactive task trace shows DestroyBlockTask clearing the authored double-slab hinge neighbor at "
                    + hingeNeighbor.toShortString());
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level != null && hingeRemovalTaskSeen) {
            BlockState actual = client.level.getBlockState(hingeNeighbor);
                if (actual.isAir() && !hingeRemovalObserved) {
                    hingeRemovalObserved = true;
                    append("ASSERT\tclient observed the authored slab become air during special door placement");
            } else if (hingeRemovalObserved && actual.equals(mixedExpectedState(12, 0))
                    && !hingeRestorationObserved) {
                hingeRestorationObserved = true;
                append("ASSERT\tclient observed the authored double slab restored after door placement");
            }
        }

        if (!hingeRestorationTracked) {
            for (Task task : activeTasks) {
                if (!(task instanceof BuildSchematicTask buildTask)) continue;
                try {
                    java.lang.reflect.Field field = BuildSchematicTask.class
                            .getDeclaredField("_temporarilyRemovedPositions");
                    field.setAccessible(true);
                    Object value = field.get(buildTask);
                    if (value instanceof Set<?> positions && positions.contains(new BlockPos(12, 0, 0))) {
                        hingeRestorationTracked = true;
                        append("ASSERT\tBuildSchematicTask tracked authored local cell (12,0,0) for schematic restoration");
                    }
                } catch (ReflectiveOperationException error) {
                    fail("Could not inspect BuildSchematicTask authored-hinge restoration state: " + error);
                    return;
                }
            }
        }

        if (hingeFailureMode && !hingeFailureInjected && hingeRemovalObserved
                && hingeRestorationTracked) {
            observeAndInjectHingeFailure(activeTasks, hingeNeighbor);
        }
    }

    private void observeAndInjectHingeFailure(List<Task> activeTasks, BlockPos worldNeighbor) {
        for (Task task : activeTasks) {
            if (task instanceof BuildSchematicTask buildTask) hingeFailureBuildTask = buildTask;
        }
        if (hingeFailureServerAirReady) {
            hingeFailureServerAirReady = false;
            append("HINGE_FAILURE_SERVER_AIR\t" + hingeFailureServerAirState);
            if (!hingeFailureServerAirConfirmed) {
                fail("Controlled failure-path fixture refused injection because server did not confirm the authored neighbor was AIR: "
                        + hingeFailureServerAirState);
                return;
            }
            if (hingeFailureBuildTask == null) {
                fail("Controlled failure-path fixture confirmed server AIR but could not locate the active BuildSchematicTask");
                return;
            }
            try {
                java.lang.reflect.Method failMethod = BuildSchematicTask.class.getDeclaredMethod(
                        "fail", AltoClef.class, String.class);
                failMethod.setAccessible(true);
                failMethod.invoke(hingeFailureBuildTask, Debug.jankModInstance, HINGE_FAILURE_REASON);
                hingeFailureInjected = true;
                append("ASSERT\tcontrolled failure injected through BuildSchematicTask.fail after client/server AIR observation and tracked local cell (12,0,0); harness did not restore blocks");
            } catch (ReflectiveOperationException error) {
                fail("Could not inject controlled failure into the active BuildSchematicTask: " + error);
            }
            return;
        }
        if (hingeFailureServerAirOutstanding) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before controlled hinge failure injection");
            return;
        }
        hingeFailureServerAirOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean confirmed = level != null && level.getBlockState(worldNeighbor).isAir();
            hingeFailureServerAirState = player == null ? "player=null"
                    : "position=" + worldNeighbor + ",state=" + level.getBlockState(worldNeighbor)
                    + ",air=" + confirmed;
            hingeFailureServerAirConfirmed = confirmed;
            hingeFailureServerAirOutstanding = false;
            hingeFailureServerAirReady = true;
        });
    }

    private void pollHingeFailureVerification() {
        Minecraft client = Minecraft.getInstance();
        BlockPos worldSlab = buildOrigin.offset(12, 0, 0);
        BlockState expectedSlab = mixedExpectedState(12, 0);
        if (hingeFailurePollReady) {
            hingeFailurePollReady = false;
            append("HINGE_FAILURE_SERVER_FINAL\t" + hingeFailurePollState);
            boolean clientRestored = client.level != null && client.player != null
                    && client.level.getBlockState(worldSlab).equals(expectedSlab)
                    && client.player.containerMenu == client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(client.player.containerMenu).equals("[]")
                    && client.player.isAlive() && client.player.getHealth() > 0
                    && isSurvivalMode(client.player)
                    && !(client.gui.screen() instanceof DeathScreen);
            String reason = hingeFailureBuildTask == null ? null : hingeFailureBuildTask.getFailureReason();
            Set<?> unresolved = readTrackedAuthoredCells(hingeFailureBuildTask);
            boolean failureReasonPreserved = hingeFailureBuildTask != null
                    && hingeFailureBuildTask.hasFailed()
                    && reason != null && reason.contains(HINGE_FAILURE_REASON)
                    && reason.contains("Temporary-neighbor cleanup restored 1 of 1 tracked authored cells.");
            boolean restorationTrackedAndCleared = unresolved != null && unresolved.isEmpty();
            append("HINGE_FAILURE_REASON\t" + reason);
            append("HINGE_FAILURE_CLIENT_FINAL\t" + currentClientDimensionState()
                    + "\tlocalCell=(12,0,0)\tworld=" + worldSlab
                    + "\tactual=" + (client.level == null ? "level=null" : client.level.getBlockState(worldSlab))
                    + "\texpected=" + expectedSlab
                    + "\tunresolved=" + unresolved);
            if (hingeFailurePollPassed && clientRestored && failureReasonPreserved
                    && restorationTrackedAndCleared && hingeFailureInjected
                    && hingeRemovalObserved && hingeRestorationTracked && hingeRemovalTaskSeen) {
                append("ASSERT\tserver and client both contain the exact authored double slab after production failure cleanup; original injected failure reason reports Restored 1 of 1; tracked set is empty; harness made no restoration edits");
                append("SUMMARY\tPASS\t26.2 runtimeStart=hingefailure used the normal @build command and live BuildSchematicTask; after exact authored hinge neighbor became AIR on client/server and was registered for restoration, harness injected one controlled failure via BuildSchematicTask.fail; production cleanup restored the exact double slab before reporting the original failure reason");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - hingeFailureVerificationStarted > 200) {
            fail("Controlled hinge failure cleanup did not prove exact restoration and preserved failure reason: injected="
                    + hingeFailureInjected + ", removalTask=" + hingeRemovalTaskSeen
                    + ", observedAir=" + hingeRemovalObserved + ", tracked=" + hingeRestorationTracked
                    + ", server=" + hingeFailurePollState + ", reason="
                    + (hingeFailureBuildTask == null ? "build task missing" : hingeFailureBuildTask.getFailureReason())
                    + ", unresolved=" + readTrackedAuthoredCells(hingeFailureBuildTask)
                    + ", client=" + (client.level == null ? "level=null" : client.level.getBlockState(worldSlab)));
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || hingeFailurePollOutstanding) return;
        hingeFailurePollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean restored = level != null && level.getBlockState(worldSlab).equals(expectedSlab);
            hingeFailurePollPassed = restored;
            hingeFailurePollState = player == null ? "player=null"
                    : "position=" + worldSlab + ",actual=" + level.getBlockState(worldSlab)
                    + ",expected=" + expectedSlab + ",restored=" + restored;
            hingeFailurePollOutstanding = false;
            hingeFailurePollReady = true;
        });
    }

    private Set<?> readTrackedAuthoredCells(BuildSchematicTask task) {
        if (task == null) return null;
        try {
            java.lang.reflect.Field tracked = BuildSchematicTask.class
                    .getDeclaredField("_temporarilyRemovedPositions");
            tracked.setAccessible(true);
            Object value = tracked.get(task);
            return value instanceof Set<?> positions ? Set.copyOf(positions) : null;
        } catch (ReflectiveOperationException error) {
            return Set.of("inspection-failed:" + error.getClass().getSimpleName());
        }
    }

    private void beginWhenReady() {
        Minecraft client = Minecraft.getInstance();
        ensureOutput(client);
        AltoClef mod = Debug.jankModInstance;
        MinecraftServer server = client.getSingleplayerServer();
        if (client.player == null || client.level == null) {
            if (clientWorldReadyLogged) {
                append("CLIENT_WORLD_LEFT\ttick=" + ticks);
                clientWorldReadyTick = -1;
                chunkTrackerWaitStartedTick = -1;
                clientWorldReadyLogged = false;
                chunkTrackerWaitLogged = false;
                worldWaitStartedTick = ticks;
            }
            if (ticks - worldWaitStartedTick >= STARTUP_WORLD_TIMEOUT_TICKS) {
                fail("Quick Play did not enter a client world within " + STARTUP_WORLD_TIMEOUT_TICKS
                        + " client ticks; runtimeStart="
                        + System.getProperty("altoclef.runtimeStart", "full"));
            }
            return;
        }

        if (!clientWorldReadyLogged) {
            clientWorldReadyTick = ticks;
            clientWorldReadyLogged = true;
            append("HARNESS_WORLD_READY\tclientThread=" + client.isSameThread()
                    + "\tplayer=" + client.player.getUUID()
                    + "\tdimension=" + client.level.dimension()
                    + "\tintegratedServer=" + (server != null));
        }

        if (!client.isSameThread()) {
            fail("Runtime readiness check did not run on the Minecraft client thread");
            return;
        }
        if (server == null) {
            if (ticks - clientWorldReadyTick >= STARTUP_COMPONENT_TIMEOUT_TICKS) {
                fail("Client world loaded but its integrated singleplayer server did not become ready");
            }
            return;
        }
        if (mod == null) {
            if (ticks - clientWorldReadyTick >= STARTUP_COMPONENT_TIMEOUT_TICKS) {
                fail("Client joined a world but AltoClef was unavailable after "
                        + STARTUP_COMPONENT_TIMEOUT_TICKS + " client ticks");
            }
            return;
        }
        if (mod.isLoadInitializationComplete() && !mod.wasLoadInitializationOnClientThread()) {
            fail("AltoClef initialization completed outside the Minecraft client thread");
            return;
        }

        boolean componentsReady = mod.isLoadInitializationComplete()
                && mod.wasLoadInitializationOnClientThread()
                && AltoClef.getCommandExecutor() != null
                && mod.getTaskRunner() != null
                && mod.getItemStorage() != null
                && mod.getModSettings() != null
                && mod.getChunkTracker() != null;
        if (!componentsReady) {
            if (ticks - clientWorldReadyTick >= STARTUP_COMPONENT_TIMEOUT_TICKS) {
                fail("AltoClef initialization or command, task, storage, settings, or chunk tracking "
                        + "was incomplete after " + STARTUP_COMPONENT_TIMEOUT_TICKS + " client ticks");
            }
            return;
        }

        BlockPos playerPosition = client.player.blockPosition();
        int playerChunkX = playerPosition.getX() >> 4;
        int playerChunkZ = playerPosition.getZ() >> 4;
        boolean playerChunkClientLoaded = !(client.level.getChunk(playerChunkX, playerChunkZ)
                instanceof EmptyLevelChunk);
        boolean playerChunkTracked = mod.getChunkTracker().getLoadedChunks().stream()
                .anyMatch(chunk -> chunk.x() == playerChunkX && chunk.z() == playerChunkZ);
        if (!playerChunkClientLoaded || !playerChunkTracked) {
            if (!chunkTrackerWaitLogged) {
                chunkTrackerWaitStartedTick = ticks;
                chunkTrackerWaitLogged = true;
                append("CHUNK_TRACKER_WAIT\tplayerChunk=" + playerChunkX + "," + playerChunkZ
                        + "\tclientLoaded=" + playerChunkClientLoaded
                        + "\ttrackerVisible=" + playerChunkTracked);
            }
            if (ticks - chunkTrackerWaitStartedTick >= STARTUP_CHUNK_TIMEOUT_TICKS) {
                fail("Player chunk did not become loaded and tracked within " + STARTUP_CHUNK_TIMEOUT_TICKS
                        + " client ticks: " + playerChunkX + "," + playerChunkZ
                        + "; clientLoaded=" + playerChunkClientLoaded
                        + "; trackerVisible=" + playerChunkTracked);
            }
            return;
        }

        try {
            String runtimeStart = System.getProperty("altoclef.runtimeStart", "full");
            naturalMode = "natural".equalsIgnoreCase(runtimeStart);
            naturalResourceMode = "naturalresource".equalsIgnoreCase(runtimeStart);
            litematicaRecoveryMode = "litematicarecovery".equalsIgnoreCase(runtimeStart);
            prefix = mod.getModSettings().getCommandPrefix();
            append("ALTOCLEF_READY\tinitializationComplete=true"
                    + "\tinitializationClientThread=" + mod.wasLoadInitializationOnClientThread()
                    + "\tclientThread=true\tcommandExecutor=true\ttaskRunner=true"
                    + "\tstorageTracker=true\tsettings=true\tchunkTracker=true"
                    + "\tplayerChunkClientLoaded=true\tplayerChunkTracked=true"
                    + "\tplayerChunk=" + playerChunkX + "," + playerChunkZ);
            testPlayer = client.player.getUUID();
            floorOrigin = client.player.blockPosition().below();
            buildOrigin = client.player.blockPosition();
            naturalInitialPosition = client.player.blockPosition();
            gameSchematic = client.gameDirectory.toPath().resolve("schematics/runtime-acceptance.litematic");
            mixedGameSchematic = client.gameDirectory.toPath().resolve("schematics/mixed-runtime-acceptance.litematic");
            crafterBuildGameSchematic = client.gameDirectory.toPath().resolve("schematics/crafter-runtime-acceptance.litematic");
            boolean dimensionsMode = "dimensions".equalsIgnoreCase(System.getProperty("altoclef.runtimeStart", "full"));
            boolean mobDefenseMode = "mobdefense".equalsIgnoreCase(System.getProperty("altoclef.runtimeStart", "full"));
            if (!naturalMode && !naturalResourceMode && !litematicaRecoveryMode && !dimensionsMode
                    && !mobDefenseMode) {
                Files.createDirectories(gameSchematic.getParent());
                writeLitematicFixture(gameSchematic);
                writeMixedLitematicFixture(mixedGameSchematic);
                writeCrafterBuildFixture(crafterBuildGameSchematic);
            }
            append("START\t26.2\t" + client.level.dimension() + "\t" + floorOrigin);
            append("COMMAND_PREFIX\t" + prefix);
            if (!mobDefenseMode && (!TaskCatalogue.taskExists("diamond")
                    || !TaskCatalogue.taskExists("chest")
                    || !TaskCatalogue.taskExists("stick"))) {
                fail("Expected catalog resources are missing.");
                return;
            }

            if (naturalMode || naturalResourceMode || litematicaRecoveryMode) {
                naturalPrepared.set(false);
                naturalServerSeedValid = false;
                naturalServerSeedState = "not-prepared";
                server.execute(() -> prepareNaturalWorld(server));
                append((naturalResourceMode ? "NATURAL_RESOURCE_SETUP"
                        : litematicaRecoveryMode ? "LITEMATICA_RECOVERY_SETUP" : "NATURAL_SETUP")
                        + "\tqueued\tclear player inventory/equipment; Survival; Normal difficulty; full health/food; terrain unchanged; no teleport");
            } else if (dimensionsMode) {
                dimensionsFixtureReady = false;
                dimensionsFixturePassed = false;
                dimensionsFixtureState = "not-prepared";
                server.execute(() -> prepareDimensionFixtures(server));
                append("DIMENSIONS_SETUP\tqueued\tserver-thread-only linked Nether portals, active central End portal, and a real Feature.END_GATEWAY with exact central destination; setup teleports happen before each task only");
            } else if (mobDefenseMode) {
                arenaReady.set(true);
                append("MOB_DEFENSE_ARENA_SETUP\tready\tcustom Run 144 server-thread fixture owns the arena, inventory, and two ordinary zombies");
            } else {
                server.execute(() -> prepareArena(server));
                append("ARENA_SETUP\tqueued\tclear inventory; survival mode; stone floor; logs; coal, iron, and diamond ore");
            }
            setupQueued = true;
            phaseStarted = ticks;
            phase = Phase.WAITING;
        } catch (Exception e) {
            fail("Could not initialize the acceptance harness: " + e);
        }
    }

    private void runChatInputSmoke() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            fail("Chat input smoke could not find the local player");
            return;
        }
        String command = prefix + "coords";
        java.util.concurrent.atomic.AtomicBoolean cancelledAfterCommandListener =
                new java.util.concurrent.atomic.AtomicBoolean();
        Subscription<SendChatEvent> observer = EventBus.subscribe(SendChatEvent.class,
                event -> cancelledAfterCommandListener.set(event.isCancelled()));
        ChatScreen screen = new ChatScreen(command, false);
        try {
            client.gui.setScreen(screen);
            screen.handleChatInput(command, true);
        } catch (Throwable error) {
            fail("ChatScreen.handleChatInput smoke failed: " + error);
            return;
        } finally {
            EventBus.unsubscribe(observer);
            client.gui.setScreen(null);
        }
        if (!cancelledAfterCommandListener.get()) {
            fail("Chat command input reached SendChatEvent without AltoClef cancelling it");
            return;
        }
        chatSmokeDone = true;
        append("CHAT_SMOKE\tactual ChatScreen.handleChatInput(" + command + ") on client thread"
                + "\tcommandOutputPosition=" + client.player.blockPosition().toShortString());
        append("ASSERT\tchat input mixin published SendChatEvent and canceled the local @coords command");
    }

    private void forceManualCraftingMode(AltoClef mod) throws ReflectiveOperationException {
        if (mod == null || mod.getModSettings() == null) {
            throw new IllegalStateException("AltoClef settings are not initialized");
        }
        java.lang.reflect.Field setting = mod.getModSettings().getClass()
                .getDeclaredField("useCraftingBookToCraft");
        setting.setAccessible(true);
        originalCraftingBookSetting = setting.getBoolean(mod.getModSettings());
        setting.setBoolean(mod.getModSettings(), false);
        manualCraftingSettingChanged = true;
        append("SETTINGS\tuseCraftingBookToCraft=false\truntime-only manual crafting test");
    }

    private void restoreManualCraftingMode() {
        if (!manualCraftingSettingChanged) return;
        try {
            AltoClef mod = Debug.jankModInstance;
            if (mod == null || mod.getModSettings() == null) return;
            java.lang.reflect.Field setting = mod.getModSettings().getClass()
                    .getDeclaredField("useCraftingBookToCraft");
            setting.setAccessible(true);
            setting.setBoolean(mod.getModSettings(), originalCraftingBookSetting);
        } catch (Exception error) {
            Debug.logError("Could not restore recipe-book crafting setting after runtime test: " + error);
        } finally {
            manualCraftingSettingChanged = false;
        }
    }

    private void forcePlankFuelMode(AltoClef mod) throws ReflectiveOperationException {
        if (mod == null || mod.getModSettings() == null) {
            throw new IllegalStateException("AltoClef settings are not initialized");
        }
        java.lang.reflect.Field limitSetting = mod.getModSettings().getClass()
                .getDeclaredField("limitFuelsToSupportedFuels");
        java.lang.reflect.Field supportedSetting = mod.getModSettings().getClass()
                .getDeclaredField("supportedFuels");
        limitSetting.setAccessible(true);
        supportedSetting.setAccessible(true);
        originalFuelLimitSetting = limitSetting.getBoolean(mod.getModSettings());
        @SuppressWarnings("unchecked")
        List<Item> configuredFuels = (List<Item>) supportedSetting.get(mod.getModSettings());
        originalSupportedFuels = new ArrayList<>(configuredFuels);
        limitSetting.setBoolean(mod.getModSettings(), true);
        supportedSetting.set(mod.getModSettings(), new ArrayList<>(List.of(Items.OAK_PLANKS)));
        plankFuelSettingsChanged = true;
        append("SETTINGS\tlimitFuelsToSupportedFuels=true\tsupportedFuels=[oak_planks]");
    }

    private void restorePlankFuelMode() {
        if (!plankFuelSettingsChanged) return;
        try {
            AltoClef mod = Debug.jankModInstance;
            if (mod == null || mod.getModSettings() == null) return;
            java.lang.reflect.Field limitSetting = mod.getModSettings().getClass()
                    .getDeclaredField("limitFuelsToSupportedFuels");
            java.lang.reflect.Field supportedSetting = mod.getModSettings().getClass()
                    .getDeclaredField("supportedFuels");
            limitSetting.setAccessible(true);
            supportedSetting.setAccessible(true);
            limitSetting.setBoolean(mod.getModSettings(), originalFuelLimitSetting);
            supportedSetting.set(mod.getModSettings(), new ArrayList<>(originalSupportedFuels));
        } catch (Exception error) {
            Debug.logError("Could not restore fuel settings after plank-fuel runtime acceptance: " + error);
        } finally {
            plankFuelSettingsChanged = false;
        }
    }

    private void runManualStairsSetup() {
        phase = Phase.MANUAL_STAIRS_SETUP;
        phaseStarted = ticks;
        manualSeeded.set(false);
        manualServerSeedValid = false;
        manualStairsTaskSeen = false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before manual-crafting acceptance");
            return;
        }
        manualTablePos = floorOrigin.offset(-1, 1, 0);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) return;
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 6));
            ServerLevel level = (ServerLevel) player.level();
            level.setBlock(manualTablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            manualServerSeedState = serverInventoryState(player);
            manualServerSeedValid = exactInventory(player, new Item[]{Items.OAK_PLANKS}, new int[]{6})
                    && player.containerMenu.getCarried().isEmpty();
            manualSeeded.set(true);
        });
    }

    private void startManualStairsCommand() {
        Minecraft client = Minecraft.getInstance();
        if (!manualSetupReady(new Item[]{Items.OAK_PLANKS}, new int[]{6})
                || !client.level.getBlockState(manualTablePos).is(Blocks.CRAFTING_TABLE)
                || count(client.player, Items.OAK_PLANKS) != 6
                || count(client.player, Items.OAK_STAIRS) != 0) {
            if (ticks - phaseStarted > 100) fail("Manual stairs seed did not sync: client=" + clientInventoryState()
                    + ", server=" + manualServerSeedState);
            return;
        }
        phase = Phase.MANUAL_STAIRS;
        phaseStarted = ticks;
        append("MANUAL_SEED\tserver seeded oak_planks=6, crafting_grid=empty, table=" + manualTablePos);
        append("COMMAND\t@get oak_stairs 4");
        execute("get oak_stairs 4", () -> {
            int stairs = count(Minecraft.getInstance().player, Items.OAK_STAIRS);
            int planks = count(Minecraft.getInstance().player, Items.OAK_PLANKS);
            if (stairs != 4 || planks != 0 || !manualStairsTaskSeen
                    || !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) {
                fail("Manual oak-stairs crafting failed: stairs=" + stairs + ", planks=" + planks
                        + ", manualTaskSeen=" + manualStairsTaskSeen + ", client=" + clientInventoryState());
                return;
            }
            verifyManualServerResult("oak stairs", new Item[]{Items.OAK_STAIRS}, new int[]{4},
                    this::runManualTemplateSetup);
        });
    }

    private void runManualTemplateSetup() {
        phase = Phase.MANUAL_TEMPLATE_SETUP;
        phaseStarted = ticks;
        manualSeeded.set(false);
        manualServerSeedValid = false;
        manualTemplateTaskSeen = false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before manual template duplication");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) return;
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2));
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 7));
            player.getInventory().setItem(2, new ItemStack(Items.NETHERRACK, 1));
            ServerLevel level = (ServerLevel) player.level();
            level.setBlock(manualTablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            manualServerSeedState = serverInventoryState(player);
            manualServerSeedValid = exactInventory(player,
                    new Item[]{Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND, Items.NETHERRACK},
                    new int[]{2, 7, 1}) && player.containerMenu.getCarried().isEmpty();
            manualSeeded.set(true);
        });
    }

    private void startManualTemplateCommand() {
        Minecraft client = Minecraft.getInstance();
        if (!manualSetupReady(new Item[]{Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND,
                Items.NETHERRACK}, new int[]{2, 7, 1})
                || !client.level.getBlockState(manualTablePos).is(Blocks.CRAFTING_TABLE)
                || count(client.player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) != 2
                || count(client.player, Items.DIAMOND) != 7
                || count(client.player, Items.NETHERRACK) != 1) {
            if (ticks - phaseStarted > 100) fail("Manual template seed did not sync: client=" + clientInventoryState()
                    + ", server=" + manualServerSeedState);
            return;
        }
        phase = Phase.MANUAL_TEMPLATE;
        phaseStarted = ticks;
        append("MANUAL_SEED\tserver seeded templates=2, diamonds=7, netherrack=1"
                + "\tcrafting_grid=empty\ttable=" + manualTablePos);
        append("COMMAND\t@get netherite_upgrade_smithing_template 3");
        execute("get netherite_upgrade_smithing_template 3", () -> {
            int templates = count(Minecraft.getInstance().player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            int diamonds = count(Minecraft.getInstance().player, Items.DIAMOND);
            int netherrack = count(Minecraft.getInstance().player, Items.NETHERRACK);
            if (templates != 3 || diamonds != 0 || netherrack != 0 || !manualTemplateTaskSeen
                    || !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) {
                fail("Manual template duplication failed: templates=" + templates + ", diamonds=" + diamonds
                        + ", netherrack=" + netherrack + ", manualTaskSeen=" + manualTemplateTaskSeen
                        + ", client=" + clientInventoryState());
                return;
            }
            verifyManualServerResult("template duplication", new Item[]{Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE},
                    new int[]{3}, this::runManualSmallRecipeSetup);
        });
    }

    private void runManualSmallRecipeSetup() {
        phase = Phase.MANUAL_GRID_SETUP;
        phaseStarted = ticks;
        manualSeeded.set(false);
        manualServerSeedValid = false;
        manualSmallRecipeTaskSeen = false;
        manualSmallRecipeTableSeen = false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before the 2x2-in-3x3 manual crafting check");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) return;
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            ServerLevel level = (ServerLevel) player.level();
            level.setBlock(manualTablePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            manualServerSeedState = serverInventoryState(player);
            manualServerSeedValid = exactInventory(player, new Item[]{Items.OAK_PLANKS}, new int[]{2})
                    && player.containerMenu.getCarried().isEmpty();
            manualSeeded.set(true);
        });
    }

    private void startManualSmallRecipe() {
        Minecraft client = Minecraft.getInstance();
        if (!manualSetupReady(new Item[]{Items.OAK_PLANKS}, new int[]{2})
                || !client.level.getBlockState(manualTablePos).is(Blocks.CRAFTING_TABLE)
                || count(client.player, Items.OAK_PLANKS) != 2) {
            if (ticks - phaseStarted > 100) fail("Manual 2x2 seed did not sync: client=" + clientInventoryState()
                    + ", server=" + manualServerSeedState);
            return;
        }
        phase = Phase.MANUAL_GRID_CRAFT;
        phaseStarted = ticks;
        append("MANUAL_TASK\tCraftInTableTask -> CraftGenericManuallyTask\trecipe=2x2 sticks in 3x3 crafting table");
        RecipeTarget target = new RecipeTarget(Items.STICK, 4,
                CraftingRecipe.newShapedRecipe("runtime_2x2_stick", new ItemTarget[]{
                        new ItemTarget(Items.OAK_PLANKS), null,
                        new ItemTarget(Items.OAK_PLANKS), null
                }, 4));
        Debug.jankModInstance.runUserTask(new CraftInTableTask(target, false, true), () -> {
            int sticks = count(Minecraft.getInstance().player, Items.STICK);
            int planks = count(Minecraft.getInstance().player, Items.OAK_PLANKS);
            if (sticks != 4 || planks != 0 || !manualSmallRecipeTaskSeen || !manualSmallRecipeTableSeen
                    || !Minecraft.getInstance().player.containerMenu.getCarried().isEmpty()) {
                fail("2x2 recipe in open 3x3 table failed: sticks=" + sticks + ", planks=" + planks
                        + ", tableSeen=" + manualSmallRecipeTableSeen + ", manualTaskSeen=" + manualSmallRecipeTaskSeen
                        + ", client=" + clientInventoryState());
                return;
            }
            verifyManualServerResult("2x2 recipe in 3x3 table", new Item[]{Items.STICK}, new int[]{4}, () -> {
                append("ASSERT\tCraftInTableTask manually placed a 2x2 stick recipe in the open 3x3 table");
                restoreManualCraftingMode();
                append("SUMMARY\tPASS\t26.2 runtimeStart=manual explicitly skipped world-gathering/build phases; manual oak-stairs crafting; manual netherite-template duplication 2-to-3 with exact inputs; 2x2 recipe in open 3x3 table");
                phase = Phase.DONE;
                writeResult();
            });
        });
    }

    /** Close any crafting screen on the server before changing the player's backing inventory. */
    private void resetToSynchronizedPlayerInventory(ServerPlayer player) {
        player.closeContainer();
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        if (player.containerMenu != player.inventoryMenu) {
            throw new IllegalStateException("Server did not return player to inventory menu: "
                    + player.containerMenu.getClass().getSimpleName());
        }
    }

    /** Force a full menu sync so the client does not compare a new seed against stale crafting slots. */
    private void publishInventoryMenu(ServerPlayer player) {
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.broadcastFullState();
        player.containerMenu.broadcastChanges();
    }

    private String serverInventoryState(ServerPlayer player) {
        return "menu=" + player.containerMenu.getClass().getSimpleName()
                + ",inventory=" + inventoryContents(player.getInventory())
                + ",cursor=" + stackState(player.containerMenu.getCarried());
    }

    private String clientInventoryState() {
        var player = Minecraft.getInstance().player;
        if (player == null) return "player=null";
        return "menu=" + player.containerMenu.getClass().getSimpleName()
                + ",inventory=" + inventoryContents(player.getInventory())
                + ",cursor=" + stackState(player.containerMenu.getCarried())
                + ",grid=" + craftingGridState(player.containerMenu);
    }

    private String inventoryContents(net.minecraft.world.entity.player.Inventory inventory) {
        List<String> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) stacks.add(slot + "=" + stack.getCount() + "x" + stack.getItem());
        }
        return stacks.toString();
    }

    private String craftingGridState(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        List<String> stacks = new ArrayList<>();
        int end = Math.min(menu.slots.size(), 5);
        for (int slot = 1; slot < end; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (!stack.isEmpty()) stacks.add(slot + "=" + stackState(stack));
        }
        return stacks.toString();
    }

    private String craftingResultState(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return menu == null || menu.slots.isEmpty()
                ? "unavailable"
                : stackState(menu.getSlot(0).getItem());
    }

    private boolean craftingResultEmpty(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return menu != null && !menu.slots.isEmpty() && menu.getSlot(0).getItem().isEmpty();
    }

    private String stackState(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getCount() + "x" + stack.getItem();
    }

    private boolean manualSetupReady(Item[] expectedItems, int[] expectedCounts) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()) return false;
        for (int slot = 1; slot <= 4; slot++) {
            if (!client.player.inventoryMenu.getSlot(slot).getItem().isEmpty()) return false;
        }
        int expectedTotal = 0;
        for (int i = 0; i < expectedItems.length; i++) {
            if (count(client.player, expectedItems[i]) != expectedCounts[i]) return false;
            expectedTotal += expectedCounts[i];
        }
        return manualServerSeedValid && inventoryCount(client.player.getInventory()) == expectedTotal
                && manualServerSeedState.contains("menu=InventoryMenu")
                && manualServerSeedState.contains("cursor=empty");
    }

    private void verifyManualServerResult(String label, Item[] expectedItems, int[] expectedCounts,
                                          Runnable continueOnSuccess) {
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            fail("Integrated server disappeared while verifying " + label);
            return;
        }
        manualVerificationPending = true;
        manualVerificationStarted = ticks;
        manualVerificationLabel = label;
        manualVerificationItems = expectedItems.clone();
        manualVerificationCounts = expectedCounts.clone();
        manualVerificationContinuation = continueOnSuccess;
        manualServerPollOutstanding = false;
        manualServerPollReady = false;
        manualServerPollValid = false;
        manualServerPollState = "waiting for server tick";
        lastManualServerPollLogged = "";
        append("MANUAL_SERVER_VERIFY_START\t" + label + "\texpecting inventory-menu sync within 100 ticks");
    }

    private void pollManualServerResult() {
        Minecraft client = Minecraft.getInstance();
        if (manualServerPollReady) {
            manualServerPollReady = false;
            if (!manualServerPollState.equals(lastManualServerPollLogged)) {
                lastManualServerPollLogged = manualServerPollState;
                append("MANUAL_SERVER_POLL\t" + manualVerificationLabel + "\t" + manualServerPollState);
            }
            if (manualServerPollValid && clientManualInventoryMatches()) {
                manualVerificationPending = false;
                append("MANUAL_SERVER_VERIFY\t" + manualVerificationLabel + "\t" + manualServerPollState);
                append("ASSERT\tserver and client inventories synchronized for " + manualVerificationLabel
                        + " with InventoryMenu and empty cursor/grid");
                Runnable continuation = manualVerificationContinuation;
                manualVerificationContinuation = null;
                if (continuation != null) continuation.run();
                return;
            }
        }
        if (ticks - manualVerificationStarted > 100) {
            manualVerificationPending = false;
            fail("Server/client inventory did not synchronize for " + manualVerificationLabel
                    + " within 100 ticks; server=" + manualServerPollState
                    + "; client=" + clientInventoryState());
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || manualServerPollOutstanding) return;
        manualServerPollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            String state = player == null ? "player=null" : serverInventoryState(player);
            boolean valid = player != null && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && exactInventory(player, manualVerificationItems, manualVerificationCounts);
            manualServerPollState = state + ",grid="
                    + (player == null ? "unknown" : craftingGridState(player.containerMenu));
            manualServerPollValid = valid;
            manualServerPollOutstanding = false;
            manualServerPollReady = true;
        });
    }

    private boolean clientManualInventoryMatches() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !craftingGridState(client.player.containerMenu).equals("[]")) return false;
        return exactInventory(client.player, manualVerificationItems, manualVerificationCounts);
    }

    private int inventoryCount(net.minecraft.world.entity.player.Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    private boolean crafterPlacementInventoryMatches(Player player) {
        return player != null
                && count(player, Items.STONE) == 1
                && count(player, Items.CRAFTER) == 1
                && inventoryCount(player.getInventory()) == 2;
    }

    private boolean exactInventory(net.minecraft.world.entity.player.Player player, Item[] expectedItems,
                                   int[] expectedCounts) {
        int expectedTotal = 0;
        for (int i = 0; i < expectedItems.length; i++) {
            if (count(player, expectedItems[i]) != expectedCounts[i]) return false;
            expectedTotal += expectedCounts[i];
        }
        return inventoryCount(player.getInventory()) == expectedTotal;
    }

    private void runRepairSetup() {
        phase = Phase.REPAIR_SETUP;
        phaseStarted = ticks;
        repairSeeded.set(false);
        repairDoorDestroySeen = false;
        repairBedDestroySeen = false;
        repairInterruptCheckInFlight = false;
        repairInterruptionTriggered = false;
        repairInterruptionResumed = false;
        repairServerCheckStarted = false;
        repairServerCheckReady = false;
        repairServerCheckPassed = false;
        repairSetupFailure = null;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            fail("Integrated server disappeared before paired-block repair setup");
            return;
        }
        repairOrigin = floorOrigin.offset(2, 1, 4);
        repairDoorAnchor = repairOrigin;
        repairBedAnchor = repairOrigin.offset(3, 0, 0);
        repairSchematic = createRepairFixtureSchematic();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                repairSetupFailure = "Integrated server could not find the test player";
                return;
            }
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_DOOR, 1));
            player.getInventory().setItem(1, new ItemStack(Items.BED.red(), 1));

            ServerLevel level = (ServerLevel) player.level();
            int partialFixtureFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(repairDoorAnchor.below(), Blocks.STONE.defaultBlockState(), partialFixtureFlags);
            level.setBlock(repairBedAnchor.below(), Blocks.STONE.defaultBlockState(), partialFixtureFlags);
            level.setBlock(repairBedAnchor.east().below(), Blocks.STONE.defaultBlockState(), partialFixtureFlags);
            // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE synchronizes the client while suppressing
            // neighbor-shape updates that would normalize either intentionally missing partner.
            BlockState doorLower = repairSchematic.getDirect(0, 0, 0);
            BlockState doorUpper = repairSchematic.getDirect(0, 1, 0);
            BlockState bedFoot = repairSchematic.getDirect(3, 0, 0);
            BlockState bedHead = repairSchematic.getDirect(4, 0, 0);
            level.setBlock(repairDoorAnchor.above(), Blocks.AIR.defaultBlockState(), partialFixtureFlags);
            level.setBlock(repairBedAnchor.east(), Blocks.AIR.defaultBlockState(), partialFixtureFlags);
            level.setBlock(repairDoorAnchor, doorLower, partialFixtureFlags);
            level.setBlock(repairBedAnchor, bedFoot, partialFixtureFlags);
            if (!repairSourceState(doorLower, level.getBlockState(repairDoorAnchor))
                    || !level.getBlockState(repairDoorAnchor.above()).isAir()
                    || !repairSourceState(bedFoot, level.getBlockState(repairBedAnchor))
                    || !level.getBlockState(repairBedAnchor.east()).isAir()) {
                repairSetupFailure = "Could not retain the incomplete door/bed states with neighbor updates suppressed; server="
                        + serverRepairSeedWorldState(level);
                return;
            }
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            manualServerSeedState = serverInventoryState(player);
            manualServerSeedValid = exactInventory(player,
                    new Item[]{Items.OAK_DOOR, Items.BED.red()}, new int[]{1, 1})
                    && player.containerMenu.getCarried().isEmpty();
            repairSeeded.set(true);
        });
    }

    private String serverRepairSeedWorldState(ServerLevel level) {
        return "door=" + level.getBlockState(repairDoorAnchor) + "/"
                + level.getBlockState(repairDoorAnchor.above()) + ", bed="
                + level.getBlockState(repairBedAnchor) + "/"
                + level.getBlockState(repairBedAnchor.east());
    }

    private void runDoorPairSetup() {
        phase = Phase.DOORPAIR_SETUP;
        phaseStarted = ticks;
        doorPairSeeded.set(false);
        doorPairServerSeedValid = false;
        doorPairServerCheckStarted = false;
        doorPairServerCheckReady = false;
        doorPairServerCheckPassed = false;
        doorPairSetupFailure = null;
        doorPairOrigin = floorOrigin.offset(2, 1, 2);
        doorPairWestAnchor = doorPairOrigin;
        doorPairEastAnchor = doorPairOrigin.east();
        doorPairSchematic = createDoorPairSchematic();

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before adjacent-door setup");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                doorPairSetupFailure = "Integrated server could not find the test player";
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
            player.getInventory().setItem(0, new ItemStack(Items.OAK_DOOR, 2));

            BlockPos[] targets = {doorPairWestAnchor, doorPairWestAnchor.above(),
                    doorPairEastAnchor, doorPairEastAnchor.above()};
            for (BlockPos target : targets) {
                // Only seed AIR at target cells; floor supports are the real stone platform
                // prepared before the runtime scenario began.
                level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            boolean supportsPrepared = level.getBlockState(doorPairWestAnchor.below()).is(Blocks.STONE)
                    && level.getBlockState(doorPairEastAnchor.below()).is(Blocks.STONE);
            boolean targetsClear = java.util.Arrays.stream(targets)
                    .allMatch(target -> level.getBlockState(target).isAir());
            if (!supportsPrepared || !targetsClear) {
                doorPairSetupFailure = "Real floor supports or AIR targets were not prepared: "
                        + doorPairServerWorldState(level);
                return;
            }
            publishInventoryMenu(player);
            doorPairServerSeedState = serverInventoryState(player);
            doorPairServerSeedValid = exactInventory(player,
                    new Item[]{Items.OAK_DOOR}, new int[]{2})
                    && player.containerMenu.getCarried().isEmpty();
            if (!doorPairServerSeedValid) {
                doorPairSetupFailure = "Server inventory did not contain exactly two oak doors: "
                        + doorPairServerSeedState;
                return;
            }
            doorPairSeeded.set(true);
        });
    }

    private IStaticSchematic createDoorPairSchematic() {
        var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        var hinge = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOOR_HINGE;
        BlockState westLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(half, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                .setValue(facing, net.minecraft.core.Direction.NORTH)
                .setValue(hinge, net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT);
        BlockState eastLower = westLower.setValue(hinge,
                net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT);
        BlockState westUpper = westLower.setValue(half,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        BlockState eastUpper = eastLower.setValue(half,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        Map<BlockPos, BlockState> cells = Map.of(
                new BlockPos(0, 0, 0), westLower,
                new BlockPos(0, 1, 0), westUpper,
                new BlockPos(1, 0, 0), eastLower,
                new BlockPos(1, 1, 0), eastUpper);
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) {
                return cells.get(new BlockPos(x, y, z));
            }

            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> available) {
                return getDirect(x, y, z);
            }

            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return getDirect(x, y, z) != null;
            }

            @Override public int widthX() { return 2; }
            @Override public int heightY() { return 2; }
            @Override public int lengthZ() { return 1; }
        };
    }

    private void startDoorPairTask() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !doorPairServerSeedValid
                || !exactInventory(client.player, new Item[]{Items.OAK_DOOR}, new int[]{2})
                || !client.player.containerMenu.getCarried().isEmpty()
                || !doorPairWorldIsClear(client.level)) {
            if (ticks - phaseStarted > 100) {
                fail("Door-pair fixture did not synchronize before direct build task: client="
                        + clientInventoryState() + ", server=" + doorPairServerSeedState
                        + ", world=" + (client.level == null ? "level=null" : doorPairClientWorldState()));
            }
            return;
        }
        phase = Phase.DOORPAIR_BUILD;
        phaseStarted = ticks;
        append("DOORPAIR_SEED\titems=oak_door:2 (exact server/client inventory)"
                + "\ttarget=adjacent NORTH-facing doors; west hinge=LEFT, east hinge=RIGHT"
                + "\tsupports=pre-existing stone platform\ttarget writes=AIR only");
        doorPairTask = new BuildSchematicTask("runtime adjacent door pair", doorPairSchematic, doorPairOrigin);
        Debug.jankModInstance.runUserTask(doorPairTask, () -> {
            if (doorPairTask.hasFailed()) {
                fail("Adjacent-door BuildSchematicTask failed: " + doorPairTask.getFailureReason());
                return;
            }
            phase = Phase.DOORPAIR_VERIFY;
            phaseStarted = ticks;
            append("ASSERT\tdirect BuildSchematicTask callback completed normally; verifying all four exact door states");
        });
    }

    private void verifyDoorPairBuild() {
        Minecraft client = Minecraft.getInstance();
        if (doorPairTask == null || doorPairTask.hasFailed()) {
            fail("Adjacent-door build failed: " + (doorPairTask == null ? "task=null" : doorPairTask.getFailureReason()));
            return;
        }
        boolean clientExact = client.level != null && doorPairWorldMatches(client.level);
        if (!clientExact) {
            if (ticks - phaseStarted > 200) {
                fail("Client adjacent-door verification failed: " + doorPairClientWorldState());
            }
            return;
        }
        if (!doorPairServerCheckStarted) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) {
                fail("Integrated server disappeared during adjacent-door verification");
                return;
            }
            doorPairServerCheckStarted = true;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = player == null ? null : (ServerLevel) player.level();
                doorPairServerCheckState = level == null ? "player=null"
                        : doorPairServerWorldState(level);
                doorPairServerCheckPassed = level != null && doorPairWorldMatches(level);
                doorPairServerCheckReady = true;
            });
            return;
        }
        if (!doorPairServerCheckReady) return;
        if (!doorPairServerCheckPassed) {
            fail("Server adjacent-door verification failed: " + doorPairServerCheckState);
            return;
        }
        append("ASSERT\tclient and integrated server match every lower/upper, facing, hinge, open, and powered state");
        append("ASSERT\tBuildSchematicTask completed through normal task callback without harness intervention");
        append("SUMMARY\tPASS\t26.2 runtimeStart=doorpair skipped resource gathering; exact two-door inventory; real interactions built adjacent north-facing oak doors with west LEFT/east RIGHT hinges; client and server all-cell state checks passed");
        phase = Phase.DONE;
        writeResult();
    }

    private boolean doorPairWorldIsClear(net.minecraft.world.level.LevelReader level) {
        return level.getBlockState(doorPairWestAnchor.below()).is(Blocks.STONE)
                && level.getBlockState(doorPairEastAnchor.below()).is(Blocks.STONE)
                && level.getBlockState(doorPairWestAnchor).isAir()
                && level.getBlockState(doorPairWestAnchor.above()).isAir()
                && level.getBlockState(doorPairEastAnchor).isAir()
                && level.getBlockState(doorPairEastAnchor.above()).isAir();
    }

    private boolean doorPairWorldMatches(net.minecraft.world.level.LevelReader level) {
        return doorPairCellMatches(level, doorPairWestAnchor, 0, 0)
                && doorPairCellMatches(level, doorPairWestAnchor.above(), 0, 1)
                && doorPairCellMatches(level, doorPairEastAnchor, 1, 0)
                && doorPairCellMatches(level, doorPairEastAnchor.above(), 1, 1);
    }

    private boolean doorPairCellMatches(net.minecraft.world.level.LevelReader level,
                                        BlockPos worldPosition, int x, int y) {
        return doorPairSchematic.getDirect(x, y, 0).equals(level.getBlockState(worldPosition));
    }

    private String doorPairClientWorldState() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? "level=null" : doorPairServerWorldState(client.level);
    }

    private String doorPairServerWorldState(net.minecraft.world.level.LevelReader level) {
        return "west=" + level.getBlockState(doorPairWestAnchor) + "/"
                + level.getBlockState(doorPairWestAnchor.above()) + ", east="
                + level.getBlockState(doorPairEastAnchor) + "/"
                + level.getBlockState(doorPairEastAnchor.above());
    }

    private void startRepairTask() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !craftingGridState(client.player.containerMenu).equals("[]")
                || !manualServerSeedValid
                || count(client.player, Items.OAK_DOOR) != 1
                || count(client.player, Items.BED.red()) != 1
                || inventoryCount(client.player.getInventory()) != 2
                || !repairSourceState(repairSchematic.getDirect(0, 0, 0), client.level.getBlockState(repairDoorAnchor))
                || !client.level.getBlockState(repairDoorAnchor.above()).isAir()
                || !repairSourceState(repairSchematic.getDirect(3, 0, 0), client.level.getBlockState(repairBedAnchor))
                || !client.level.getBlockState(repairBedAnchor.east()).isAir()) {
            if (ticks - phaseStarted > 100) {
                fail("Paired-block fixture did not sync before direct build task: client=" + clientInventoryState()
                        + ", server=" + manualServerSeedState);
            }
            return;
        }
        phase = Phase.REPAIR_BUILD;
        phaseStarted = ticks;
        append("REPAIR_SEED\tdoor=oak_door lower-only at " + repairDoorAnchor.toShortString()
                + "\tbed=red_bed foot-only at " + repairBedAnchor.toShortString()
                + "\titems=oak_door:1,red_bed:1\tfixture=immutable null-masked schematic");
        repairTask = new BuildSchematicTask("runtime paired-block repair", repairSchematic, repairOrigin);
        Debug.jankModInstance.runUserTask(repairTask, () -> {
            if (repairTask.hasFailed()) {
                fail("Paired-block BuildSchematicTask failed: " + repairTask.getFailureReason());
                return;
            }
            phase = Phase.REPAIR_VERIFY;
            phaseStarted = ticks;
            append("ASSERT\tpaired-block BuildSchematicTask callback completed; waiting for world-state verification");
        });
    }

    private IStaticSchematic createRepairFixtureSchematic() {
        BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
        BlockState doorUpper = doorLower.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
        BlockState bedFoot = Blocks.BED.red().defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                        net.minecraft.core.Direction.EAST);
        BlockState bedHead = bedFoot.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD);
        Map<BlockPos, BlockState> cells = java.util.Map.of(
                new BlockPos(0, 0, 0), doorLower,
                new BlockPos(0, 1, 0), doorUpper,
                new BlockPos(3, 0, 0), bedFoot,
                new BlockPos(4, 0, 0), bedHead);
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) {
                return cells.get(new BlockPos(x, y, z));
            }

            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> available) {
                return getDirect(x, y, z);
            }

            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return getDirect(x, y, z) != null;
            }

            @Override public int widthX() { return 5; }
            @Override public int heightY() { return 2; }
            @Override public int lengthZ() { return 1; }
        };
    }

    private boolean repairSourceState(BlockState desired, BlockState actual) {
        if (desired == null || actual == null || desired.getBlock() != actual.getBlock()) return false;
        if (desired.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return actual.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == desired.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF);
        }
        return desired.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                && actual.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                && actual.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                == desired.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART)
                && actual.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                == desired.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
    }

    private void maybeInterruptAndResumeRepairTask() {
        if (repairInterruptionTriggered || repairInterruptCheckInFlight
                || !repairDoorDestroySeen || !repairBedDestroySeen || ticks % 10 != 0) return;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        repairInterruptCheckInFlight = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean bothAnchorsRemoved = level != null
                    && level.getBlockState(repairDoorAnchor).isAir()
                    && level.getBlockState(repairBedAnchor).isAir();
            client.execute(() -> {
                repairInterruptCheckInFlight = false;
                if (!bothAnchorsRemoved || phase != Phase.REPAIR_BUILD || repairInterruptionTriggered) return;
                repairInterruptionTriggered = true;
                append("INTERRUPT\tboth partial anchors are server-confirmed air; injecting one-tick priority chain to interrupt and resume the same BuildSchematicTask");
                new RuntimeOneTickInterruptChain(Debug.jankModInstance.getTaskRunner());
            });
        });
    }

    private void verifyRepairBuild() {
        Minecraft client = Minecraft.getInstance();
        if (repairTask != null && repairTask.hasFailed()) {
            fail("Paired-block BuildSchematicTask failed: " + repairTask.getFailureReason());
            return;
        }
        boolean clientDoor = client.level != null && repairDoorPairMatches(
                client.level.getBlockState(repairDoorAnchor), client.level.getBlockState(repairDoorAnchor.above()));
        boolean clientBed = client.level != null && repairBedPairMatches(
                client.level.getBlockState(repairBedAnchor), client.level.getBlockState(repairBedAnchor.east()));
        if ((!clientDoor || !clientBed || !repairDoorDestroySeen || !repairBedDestroySeen
                    || !repairInterruptionTriggered || !repairInterruptionResumed)
                && ticks - phaseStarted > 100) {
            fail("Paired-block client verification failed: door=" + clientDoor + ", bed=" + clientBed
                    + ", doorDestroyObserved=" + repairDoorDestroySeen + ", bedDestroyObserved="
                    + repairBedDestroySeen + ", interruption=" + repairInterruptionTriggered
                    + ", resumed=" + repairInterruptionResumed + ", states=" + clientRepairStates());
            return;
        }
        if (!repairDoorDestroySeen || !repairBedDestroySeen || !repairInterruptionTriggered
                || !repairInterruptionResumed || !clientDoor || !clientBed) return;
        if (!repairServerCheckStarted) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) {
                fail("Integrated server disappeared during paired-block verification");
                return;
            }
            repairServerCheckStarted = true;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = player == null ? null : (ServerLevel) player.level();
                boolean door = level != null && repairDoorPairMatches(
                        level.getBlockState(repairDoorAnchor), level.getBlockState(repairDoorAnchor.above()));
                boolean bed = level != null && repairBedPairMatches(
                        level.getBlockState(repairBedAnchor), level.getBlockState(repairBedAnchor.east()));
                repairServerCheckState = "door=" + door + ", bed=" + bed
                        + (level == null ? ", player=null" : ", " + serverRepairStates(level));
                repairServerCheckPassed = door && bed;
                repairServerCheckReady = true;
            });
            return;
        }
        if (repairServerCheckReady) {
            if (repairServerCheckPassed) {
                append("ASSERT\tserver and client world states contain complete oak door and red bed pairs");
                append("ASSERT\tDestroyBlockTask was observed at both incomplete paired-block anchors before completion");
                append("ASSERT\tcontrolled one-tick task-chain interruption resumed BuildSchematicTask and completed the paired-block repair");
                append("SUMMARY\tPASS\t26.2 runtimeStart=repair explicitly skipped full gathering phases; direct BuildSchematicTask repaired a lower-only oak door and foot-only red bed using exact seeded materials; controlled interruption/resume");
                phase = Phase.DONE;
                writeResult();
            } else if (ticks - phaseStarted > 100) {
                fail("Server paired-block verification failed: " + repairServerCheckState
                        + "; client=" + clientRepairStates());
            } else {
                repairServerCheckStarted = false;
                repairServerCheckReady = false;
            }
        }
    }

    private boolean repairDoorPairMatches(BlockState lower, BlockState upper) {
        var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        return lower.is(Blocks.OAK_DOOR) && upper.is(Blocks.OAK_DOOR)
                && lower.getValue(half) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                && upper.getValue(half) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                && lower.getValue(facing) == upper.getValue(facing)
                && lower.getValue(facing) == repairSchematic.getDirect(0, 0, 0).getValue(facing);
    }

    private boolean repairBedPairMatches(BlockState foot, BlockState head) {
        var part = net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART;
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        return foot.is(Blocks.BED.red()) && head.is(Blocks.BED.red())
                && foot.getValue(part) == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                && head.getValue(part) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                && foot.getValue(facing) == head.getValue(facing)
                && foot.getValue(facing) == net.minecraft.core.Direction.EAST;
    }

    private String clientRepairStates() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return "level=null";
        return "door=" + client.level.getBlockState(repairDoorAnchor) + "/"
                + client.level.getBlockState(repairDoorAnchor.above()) + ", bed="
                + client.level.getBlockState(repairBedAnchor) + "/"
                + client.level.getBlockState(repairBedAnchor.east());
    }

    private String serverRepairStates(ServerLevel level) {
        return "door=" + level.getBlockState(repairDoorAnchor) + "/"
                + level.getBlockState(repairDoorAnchor.above()) + ", bed="
                + level.getBlockState(repairBedAnchor) + "/"
                + level.getBlockState(repairBedAnchor.east());
    }

    private void runBatchSetup() {
        phase = Phase.BATCH_SETUP;
        phaseStarted = ticks;
        batchSeeded.set(false);
        batchServerSeedValid = false;
        batchServerCheckStarted = false;
        batchServerCheckReady = false;
        batchServerCheckPassed = false;
        batchGatherTaskSeen = false;
        batchFirstGatherObservedEmptyInventory = false;
        batchNumberLastLogged = 0;
        batchBlocks = List.of(
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
                Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS,
                Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS,
                Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
                Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.ANDESITE,
                Blocks.GRANITE, Blocks.DIORITE, Blocks.CALCITE, Blocks.TUFF, Blocks.SANDSTONE,
                Blocks.NETHERRACK, Blocks.MAGMA_BLOCK, Blocks.BLACKSTONE, Blocks.SOUL_SAND,
                Blocks.SOUL_SOIL, Blocks.OBSIDIAN, Blocks.NETHER_BRICKS, Blocks.NETHER_WART_BLOCK,
                Blocks.WARPED_WART_BLOCK, Blocks.GILDED_BLACKSTONE);
        Set<Item> distinctItems = new java.util.HashSet<>();
        List<String> unsupported = new ArrayList<>();
        for (Block block : batchBlocks) {
            Item item = block.asItem();
            if (item == Items.AIR || !distinctItems.add(item)) {
                unsupported.add("not-distinct:" + block);
            } else if (!TaskCatalogue.taskExists(item)) {
                unsupported.add("no-task:" + item);
            }
        }
        if (batchBlocks.size() != 29 || distinctItems.size() != 29 || !unsupported.isEmpty()) {
            fail("Batch fixture requires 29 distinct catalogued block items: " + unsupported
                    + "; distinct=" + distinctItems.size() + ", blocks=" + batchBlocks.size());
            return;
        }
        batchOrigin = floorOrigin.offset(-14, 1, 8);
        batchSchematic = createBatchFixtureSchematic(batchBlocks);

        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            fail("Integrated server disappeared before inventory-batching setup");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                batchServerSeedState = "player=null";
                batchSeeded.set(true);
                return;
            }
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            ServerLevel level = (ServerLevel) player.level();
            int dropped = 0;
            for (int index = 0; index < batchBlocks.size(); index++) {
                Item item = batchBlocks.get(index).asItem();
                int column = index % 5;
                int row = index / 5;
                double x = floorOrigin.getX() + (column - 2) * 0.45 + 0.5;
                double y = floorOrigin.getY() + 1.15;
                double z = floorOrigin.getZ() + 10 + row * 0.45 + 0.5;
                ItemEntity entity = new ItemEntity(level, x, y, z, new ItemStack(item, 1));
                level.addFreshEntity(entity);
                dropped++;
            }
            int confirmedDrops = countBatchDropItems(level);
            String dropCounts = batchDropItemCounts(level);
            batchServerSeedState = serverInventoryState(player) + ",droppedEntities=" + confirmedDrops
                    + ",perItem=" + dropCounts;
            batchServerSeedValid = inventoryCount(player.getInventory()) == 0
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && confirmedDrops == 29 && dropped == 29 && batchDroppedItemsExact(level);
            batchSeeded.set(true);
        });
    }

    private IStaticSchematic createBatchFixtureSchematic(List<Block> blocks) {
        BlockState[] states = blocks.stream().map(Block::defaultBlockState).toArray(BlockState[]::new);
        return new IStaticSchematic() {
            @Override public BlockState getDirect(int x, int y, int z) {
                if (y != 0 || z != 0 || x < 0 || x >= states.length) return null;
                return states[x];
            }

            @Override public BlockState desiredState(int x, int y, int z, BlockState current,
                                                     List<BlockState> available) {
                return getDirect(x, y, z);
            }

            @Override public boolean inSchematic(int x, int y, int z, BlockState current) {
                return getDirect(x, y, z) != null;
            }

            @Override public int widthX() { return states.length; }
            @Override public int heightY() { return 1; }
            @Override public int lengthZ() { return 1; }
        };
    }

    private int countBatchDropItems(ServerLevel level) {
        AABB bounds = new AABB(floorOrigin.getX() - 3, floorOrigin.getY(), floorOrigin.getZ() + 8,
                floorOrigin.getX() + 3, floorOrigin.getY() + 4, floorOrigin.getZ() + 17);
        int found = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (batchBlocks.stream().anyMatch(block -> block.asItem() == entity.getItem().getItem())
                    && entity.getItem().getCount() == 1) found++;
        }
        return found;
    }

    private String batchDropItemCounts(ServerLevel level) {
        AABB bounds = new AABB(floorOrigin.getX() - 3, floorOrigin.getY(), floorOrigin.getZ() + 8,
                floorOrigin.getX() + 3, floorOrigin.getY() + 4, floorOrigin.getZ() + 17);
        List<String> counts = new ArrayList<>();
        for (Block block : batchBlocks) {
            Item item = block.asItem();
            int count = 0;
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
                if (entity.getItem().getItem() == item) count += entity.getItem().getCount();
            }
            counts.add(item + "=" + count);
        }
        return counts.toString();
    }

    private boolean batchDroppedItemsExact(ServerLevel level) {
        AABB bounds = new AABB(floorOrigin.getX() - 3, floorOrigin.getY(), floorOrigin.getZ() + 8,
                floorOrigin.getX() + 3, floorOrigin.getY() + 4, floorOrigin.getZ() + 17);
        for (Block block : batchBlocks) {
            int count = 0;
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
                if (entity.getItem().getItem() == block.asItem()) count += entity.getItem().getCount();
            }
            if (count != 1) return false;
        }
        return true;
    }

    private void startBatchTask() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !batchServerSeedValid
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !craftingGridState(client.player.containerMenu).equals("[]")
                || inventoryCount(client.player.getInventory()) != 0
                || !batchLineIsClear(client.level)) {
            if (ticks - phaseStarted > 100) {
                fail("Empty inventory or the 29 dropped items did not sync before batch planning: client="
                        + clientInventoryState() + ", server=" + batchServerSeedState);
            }
            return;
        }
        append("BATCH_SEED\t29 distinct catalogued block items dropped as individual entities"
                + "\tserver=" + batchServerSeedState + "\tplayerInventory=[]"
                + "\tplannedLine=" + batchOrigin + ".." + batchOrigin.offset(28, 0, 0));
        append("ASSERT\tBuildSchematicTask begins batch planning with an empty player inventory; drops are clustered about 10 blocks away");
        phase = Phase.BATCH_BUILD;
        phaseStarted = ticks;
        batchBuildTask = new BuildSchematicTask("runtime 29-item batching", batchSchematic, batchOrigin);
        Debug.jankModInstance.runUserTask(batchBuildTask, () -> {
            int prepared = readPreparedBatchNumber(batchBuildTask);
            if (batchBuildTask.hasFailed()) {
                fail("29-item batch BuildSchematicTask failed after " + prepared + " batches: "
                        + batchBuildTask.getFailureReason());
                return;
            }
            if (prepared < 2 || !batchGatherTaskSeen || !batchFirstGatherObservedEmptyInventory) {
                fail("Batching evidence was incomplete: preparedBatches=" + prepared
                        + ", gatherTaskSeen=" + batchGatherTaskSeen
                        + ", firstGatherWithEmptyInventory=" + batchFirstGatherObservedEmptyInventory);
                return;
            }
            phase = Phase.BATCH_VERIFY;
            phaseStarted = ticks;
            append("ASSERT\tBuildSchematicTask prepared " + prepared + " inventory-bounded batches");
        });
    }

    private void observeBatchProgress() {
        int prepared = readPreparedBatchNumber(batchBuildTask);
        if (prepared < 0) return;
        if (prepared > batchNumberLastLogged) {
            batchNumberLastLogged = prepared;
            append("BATCH_PROGRESS\tpreparedBatch=" + prepared + "\tinventory=" + clientInventoryState());
            if (prepared >= 2) append("ASSERT\tsecond material batch prepared after the initial 28-slot batch");
        }
    }

    private void observeBuildProgress() {
        boolean crafterBuildTelemetry = crafterBuildMode
                && (phase == Phase.FALLBACK || phase == Phase.CRAFTER_BUILD_VERIFY);
        boolean regularBuildTelemetry = phase == Phase.BUILD || phase == Phase.MIXED_BUILD
                || phase == Phase.REPAIR_BUILD || phase == Phase.DOORPAIR_BUILD
                || phase == Phase.BATCH_BUILD;
        if ((!crafterBuildTelemetry && !regularBuildTelemetry)
                || ticks % (phase == Phase.CRAFTER_BUILD_VERIFY ? 20 : 100) != 0) return;
        AltoClef mod = Debug.jankModInstance;
        Minecraft client = Minecraft.getInstance();
        if (mod == null) return;
        var baritone = mod.getClientBaritone();
        var builder = baritone == null ? null : baritone.getBuilderProcess();
        String builderActive = builder == null ? "unknown" : Boolean.toString(builder.isActive());
        String builderPaused = builder == null ? "unknown" : Boolean.toString(builder.isPaused());
        String minLayer = builder == null ? "unknown"
                : builder.getMinLayer().map(String::valueOf).orElse("none");
        String maxLayer = builder == null ? "unknown"
                : builder.getMaxLayer().map(String::valueOf).orElse("none");
        String playerPosition = client.player == null ? "player=null"
                : client.player.blockPosition() + ",vec=" + client.player.position();
        String builderTelemetry = "baritone=null";
        String placeable = "unknown";
        if (baritone != null) {
            try {
                var pathingBehavior = baritone.getPathingBehavior();
                var goal = pathingBehavior.getGoal();
                var current = pathingBehavior.getCurrent();
                var next = pathingBehavior.getNext();
                var inProgress = pathingBehavior.getInProgress();
                var controlManager = baritone.getPathingControlManager();
                String controllingProcess = controlManager.mostRecentInControl()
                        .map(process -> process.getClass().getSimpleName() + ":" + process.displayName())
                        .orElse("none");
                String recentCommand = controlManager.mostRecentCommand()
                        .map(Object::toString).orElse("none");
                String calculation = inProgress.map(finder -> finder.getClass().getSimpleName()
                                + ",finished=" + finder.isFinished()
                                + ",goal=" + finder.getGoal()
                                + ",nodes=" + finder.pathToMostRecentNodeConsidered()
                                        .map(path -> path.getNumNodesConsidered()).orElse(-1)
                                + ",bestPath=" + finder.bestPathSoFar()
                                        .map(path -> path.length() + "->" + path.getDest()).orElse("none"))
                        .orElse("none");
                builderTelemetry = "pathing=" + pathingBehavior.isPathing()
                        + ",hasPath=" + pathingBehavior.hasPath()
                        + ",goal=" + (goal == null ? "none" : goal.getClass().getSimpleName() + ":" + goal)
                        + ",control=" + controllingProcess
                        + ",command=" + recentCommand
                        + ",currentPath=" + describePathExecutor(current)
                        + ",nextPath=" + describePathExecutor(next)
                        + ",calculation=" + calculation;
                if (builder != null) {
                    var placeableStates = builder.getApproxPlaceable();
                    placeable = placeableStates == null ? "not-initialized"
                            : "count=" + placeableStates.size()
                                + ",stone=" + placeableStates.stream().anyMatch(state -> state.is(Blocks.STONE))
                                + ",crafter=" + placeableStates.stream().anyMatch(state -> state.is(Blocks.CRAFTER));
                }
            } catch (RuntimeException exception) {
                builderTelemetry += ",diagnosticError=" + exception.getClass().getSimpleName()
                        + ":" + String.valueOf(exception.getMessage()).replace('\t', ' ');
                placeable = "diagnosticError=" + exception.getClass().getSimpleName();
            }
        }
        String crafterTargets = crafterBuildTelemetry && crafterBuildOrigin != null
                ? "origin=" + crafterBuildOrigin
                    + ",support=" + crafterSupportTarget(crafterBuildOrigin)
                    + ",supportState=" + (client.level == null ? "level=null"
                            : client.level.getBlockState(crafterSupportTarget(crafterBuildOrigin)))
                    + ",crafter=" + crafterTarget(crafterBuildOrigin)
                    + ",crafterState=" + (client.level == null ? "level=null"
                            : client.level.getBlockState(crafterTarget(crafterBuildOrigin)))
                    + ",expectedCrafterState=" + crafterFixtureState()
                    + ",playerToSupport=" + (client.player == null ? "unknown"
                            : client.player.blockPosition().distManhattan(crafterSupportTarget(crafterBuildOrigin)))
                    + ",playerToCrafter=" + (client.player == null ? "unknown"
                            : client.player.blockPosition().distManhattan(crafterTarget(crafterBuildOrigin)))
                : "none";
        String crafterMatrixBuilderState = crafterMatrixMode && crafterBuildTelemetry
                ? crafterMatrixBuilderState(builder, client) : "none";
        String failure = "BuildSchematicTask not visible in active task chain";
        var runner = mod.getTaskRunner();
        if (runner != null && runner.getCurrentTaskChain() != null) {
            for (Task task : runner.getCurrentTaskChain().getTasks()) {
                if (task instanceof BuildSchematicTask buildTask) {
                    failure = buildTask.hasFailed() ? "FAILED: " + buildTask.getFailureReason() : "none";
                    break;
                }
            }
        }
        if (failure.startsWith("BuildSchematicTask not visible")) {
            BuildSchematicTask knownTask = phase == Phase.REPAIR_BUILD ? repairTask
                    : phase == Phase.DOORPAIR_BUILD ? doorPairTask
                    : phase == Phase.BATCH_BUILD ? batchBuildTask
                    : crafterBuildTelemetry ? crafterBuildTask : null;
            if (knownTask != null) {
                failure = knownTask.hasFailed() ? "FAILED: " + knownTask.getFailureReason() : "none";
            }
        }
        append("BUILD_OBSERVE\t" + ticks + "\tphase=" + phase
                + "\tbuilderActive=" + builderActive
                + "\tbuilderPaused=" + builderPaused
                + "\tminLayer=" + minLayer
                + "\tmaxLayer=" + maxLayer
                + "\tplayerPosition=" + playerPosition
                + "\tbuilder=" + builderTelemetry
                + "\tapproxPlaceable=" + placeable
                + "\tcrafterTargets=" + crafterTargets
                + "\tcrafterMatrixBuilderState=" + crafterMatrixBuilderState
                + "\tinventory=" + clientInventoryState()
                + "\ttaskFailure=" + failure);
    }

    private String crafterMatrixBuilderState(baritone.api.process.IBuilderProcess builder,
                                             Minecraft client) {
        if (!(builder instanceof baritone.process.BuilderProcess concrete)
                || crafterBuildOrigin == null || client.level == null) return "builder/origin/level unavailable";
        BlockPos support = crafterSupportTarget(crafterBuildOrigin);
        BlockPos target = crafterTarget(crafterBuildOrigin);
        String desiredSupport = String.valueOf(concrete.placeAt(
                support.getX(), support.getY(), support.getZ(), client.level.getBlockState(support)));
        String desiredCrafter = String.valueOf(concrete.placeAt(
                target.getX(), target.getY(), target.getZ(), client.level.getBlockState(target)));
        String incorrect = "unavailable";
        try {
            java.lang.reflect.Field field = baritone.process.BuilderProcess.class
                    .getDeclaredField("incorrectPositions");
            field.setAccessible(true);
            Object value = field.get(concrete);
            incorrect = value == null ? "null" : value.toString();
        } catch (ReflectiveOperationException | RuntimeException error) {
            incorrect = "inspection-failed:" + error.getClass().getSimpleName();
        }
        return "orientation=" + crafterFixtureOrientation()
                + ",supportDesired=" + desiredSupport
                + ",crafterDesired=" + desiredCrafter
                + ",incorrectPositions=" + incorrect;
    }

    private String describePathExecutor(baritone.api.pathing.path.IPathExecutor executor) {
        if (executor == null) return "none";
        try {
            var path = executor.getPath();
            return "index=" + executor.getPosition()
                    + ",length=" + path.length()
                    + ",src=" + path.getSrc()
                    + ",dest=" + path.getDest()
                    + ",goal=" + path.getGoal();
        } catch (RuntimeException exception) {
            return "diagnosticError=" + exception.getClass().getSimpleName();
        }
    }

    private int readPreparedBatchNumber(BuildSchematicTask task) {
        if (task == null) return -1;
        try {
            java.lang.reflect.Field field = BuildSchematicTask.class.getDeclaredField("_batchNumber");
            field.setAccessible(true);
            return field.getInt(task);
        } catch (ReflectiveOperationException error) {
            fail("Could not inspect prepared schematic batch count: " + error);
            return -1;
        }
    }

    private boolean batchLineIsClear(net.minecraft.world.level.Level level) {
        for (int x = 0; x < batchBlocks.size(); x++) {
            if (!level.getBlockState(batchOrigin.offset(x, 0, 0)).isAir()) return false;
        }
        return true;
    }

    private void verifyBatchBuild() {
        Minecraft client = Minecraft.getInstance();
        if (batchBuildTask != null && batchBuildTask.hasFailed()) {
            fail("29-item batch build failed: " + batchBuildTask.getFailureReason());
            return;
        }
        boolean clientMatches = client.level != null && batchLineMatches(client.level);
        if (!clientMatches && ticks - phaseStarted > 100) {
            fail("Batch client verification found a missing or incorrect block: " + batchLineState(client.level));
            return;
        }
        if (!clientMatches) return;
        if (!batchServerCheckStarted) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server == null) {
                fail("Integrated server disappeared during batch verification");
                return;
            }
            batchServerCheckStarted = true;
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = player == null ? null : (ServerLevel) player.level();
                boolean serverMatches = level != null && batchLineMatches(level);
                batchServerCheckState = "matches=" + serverMatches
                        + (level == null ? ", player=null" : ", " + batchLineState(level));
                batchServerCheckPassed = serverMatches;
                batchServerCheckReady = true;
            });
            return;
        }
        if (batchServerCheckReady) {
            if (batchServerCheckPassed) {
                append("ASSERT\tall 29 distinct target block states match on the server and client");
                append("ASSERT\tno earlier batch placements were cleared; every one of the 29 schematic cells remains correct");
                append("SUMMARY\tPASS\t26.2 runtimeStart=batch explicitly skipped mining/resource-list full mode; empty-inventory planning prepared at least two material batches, gathered 29 nearby seeded block items, and built the full 29-cell line");
                phase = Phase.DONE;
                writeResult();
            } else if (ticks - phaseStarted > 100) {
                fail("Batch server verification failed: " + batchServerCheckState
                        + "; client=" + batchLineState(client.level));
            } else {
                batchServerCheckStarted = false;
                batchServerCheckReady = false;
            }
        }
    }

    private boolean batchLineMatches(net.minecraft.world.level.Level level) {
        for (int x = 0; x < batchBlocks.size(); x++) {
            BlockState expected = batchBlocks.get(x).defaultBlockState();
            if (!level.getBlockState(batchOrigin.offset(x, 0, 0)).equals(expected)) return false;
        }
        return true;
    }

    private String batchLineState(net.minecraft.world.level.Level level) {
        if (level == null) return "level=null";
        List<String> states = new ArrayList<>();
        for (int x = 0; x < batchBlocks.size(); x++) {
            states.add(x + "=" + level.getBlockState(batchOrigin.offset(x, 0, 0)));
        }
        return states.toString();
    }

    private void prepareDimensionFixtures(MinecraftServer server) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            ServerLevel nether = server.getLevel(Level.NETHER);
            ServerLevel end = server.getLevel(Level.END);
            if (player == null || overworld == null || nether == null || end == null) {
                throw new IllegalStateException("Integrated server is missing player or vanilla dimensions");
            }

            server.setDifficulty(Difficulty.PEACEFUL, true);
            buildPortalPlatform(overworld, DIMENSIONS_OVERWORLD_PORTAL.below(), 12, 8);
            buildPortalPlatform(nether, DIMENSIONS_NETHER_PORTAL.below(), 12, 8);
            buildNetherPortal(overworld, DIMENSIONS_OVERWORLD_PORTAL);
            buildNetherPortal(nether, DIMENSIONS_NETHER_PORTAL);
            boolean overworldPortalMatches = netherPortalFixtureMatches(overworld, DIMENSIONS_OVERWORLD_PORTAL);
            boolean netherPortalMatches = netherPortalFixtureMatches(nether, DIMENSIONS_NETHER_PORTAL);
            if (!overworldPortalMatches || !netherPortalMatches) {
                throw new IllegalStateException("Linked Nether portal blocks did not survive fixture construction: overworld="
                        + netherPortalFixtureSnapshot(overworld, DIMENSIONS_OVERWORLD_PORTAL)
                        + ", nether=" + netherPortalFixtureSnapshot(nether, DIMENSIONS_NETHER_PORTAL));
            }

            buildPortalPlatform(end, DIMENSIONS_END_EXIT_PORTAL.below(), 18, 10);
            buildPortalPlatform(end, DIMENSIONS_END_CENTRAL_LANDING.below(), 8, 10);
            buildPortalPlatform(end, DIMENSIONS_END_OUTER_GATEWAY.below(2), 16, 10);
            // The central landing's clear volume overlaps the exit portal location.
            // Activate the portal only after all platforms have finished clearing.
            buildEndPortal(end, DIMENSIONS_END_EXIT_PORTAL);
            boolean endExitPortalMatches = endExitPortalMatches(end, DIMENSIONS_END_EXIT_PORTAL);
            if (!endExitPortalMatches) {
                throw new IllegalStateException("Central End exit portal did not retain all 9 portal blocks: "
                        + endExitPortalSnapshot(end, DIMENSIONS_END_EXIT_PORTAL));
            }
            end.getChunk(DIMENSIONS_END_OUTER_GATEWAY);
            boolean placed = Feature.END_GATEWAY.place(
                    EndGatewayConfiguration.knownExit(DIMENSIONS_END_CENTRAL_LANDING, true),
                    end, end.getChunkSource().getGenerator(), RandomSource.create(0xA17C0EFL),
                    DIMENSIONS_END_OUTER_GATEWAY);
            if (!placed) throw new IllegalStateException("Vanilla End gateway feature refused fixture placement");

            var blockEntity = end.getBlockEntity(DIMENSIONS_END_OUTER_GATEWAY);
            if (!(blockEntity instanceof TheEndGatewayBlockEntity gateway)) {
                throw new IllegalStateException("End gateway feature did not create its block entity");
            }
            var actualExit = gateway.getPortalPosition(end, DIMENSIONS_END_OUTER_GATEWAY);
            var expectedExit = net.minecraft.world.phys.Vec3.atBottomCenterOf(DIMENSIONS_END_CENTRAL_LANDING);
            boolean geometryMatches = end.getBlockState(DIMENSIONS_END_OUTER_GATEWAY).is(Blocks.END_GATEWAY)
                    && actualExit != null && actualExit.distanceToSqr(expectedExit) < 0.0001;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean cross = dx == 0 || dz == 0;
                    for (int dy = -2; dy <= 2; dy++) {
                        Block expectedFeatureBlock = dy == 0
                                ? dx == 0 && dz == 0 ? Blocks.END_GATEWAY : Blocks.AIR
                                : Math.abs(dy) == 1 && cross || Math.abs(dy) == 2 && dx == 0 && dz == 0
                                        ? Blocks.BEDROCK : Blocks.AIR;
                        geometryMatches &= end.getBlockState(DIMENSIONS_END_OUTER_GATEWAY.offset(dx, dy, dz))
                                .is(expectedFeatureBlock);
                    }
                }
            }
            if (!geometryMatches) {
                throw new IllegalStateException("Vanilla gateway bedrock cage or exact exit mismatched: actual="
                        + actualExit + ", expected=" + expectedExit);
            }

            player.closeContainer();
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.setGameMode(GameType.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(20.0f);
            player.setDeltaMovement(0.0, 0.0, 0.0);
            if (dimensionTaskRunning) throw new IllegalStateException("Fixture teleport attempted during task");
            boolean teleported = player.teleportTo(overworld,
                    DIMENSIONS_OVERWORLD_PORTAL.getX() - 3.5, DIMENSIONS_OVERWORLD_PORTAL.getY(),
                    DIMENSIONS_OVERWORLD_PORTAL.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
            if (!teleported) throw new IllegalStateException("Could not place initial player at Nether fixture");
            publishInventoryMenu(player);

            dimensionsFixtureState = "owPortal=" + DIMENSIONS_OVERWORLD_PORTAL
                    + ",netherPortal=" + DIMENSIONS_NETHER_PORTAL
                    + ",owPortalBlocks=" + netherPortalFixtureSnapshot(overworld, DIMENSIONS_OVERWORLD_PORTAL)
                    + ",netherPortalBlocks=" + netherPortalFixtureSnapshot(nether, DIMENSIONS_NETHER_PORTAL)
                    + ",endExit=" + DIMENSIONS_END_EXIT_PORTAL
                    + ",endExitBlocks=" + endExitPortalSnapshot(end, DIMENSIONS_END_EXIT_PORTAL)
                    + ",gateway=" + DIMENSIONS_END_OUTER_GATEWAY
                    + ",gatewayExit=" + actualExit
                    + ",player=" + player.level().dimension() + "/" + player.blockPosition()
                    + ",menu=" + player.containerMenu.getClass().getSimpleName()
                    + ",cursor=" + player.containerMenu.getCarried();
            dimensionsFixturePassed = player.level() == overworld && player.isAlive()
                    && player.getHealth() > 0 && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && overworldPortalMatches && netherPortalMatches && endExitPortalMatches;
            dimensionsFixtureReady = true;
            arenaReady.set(true);
        } catch (Throwable error) {
            dimensionsFixtureState = error.toString();
            dimensionsFixturePassed = false;
            dimensionsFixtureReady = true;
            arenaReady.set(true);
        }
    }

    private void startDimensionAcceptance() {
        if (!dimensionsFixtureReady) return;
        if (!dimensionsFixturePassed) {
            fail("Dimension fixture setup failed: " + dimensionsFixtureState);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !client.level.dimension().equals(Level.OVERWORLD)
                || !netherPortalFixtureMatches(client.level, DIMENSIONS_OVERWORLD_PORTAL)
                || !client.player.isAlive() || client.player.getHealth() <= 0
                || !isSurvivalMode(client.player)
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || client.gui.screen() instanceof DeathScreen) {
            if (!dimensionInitialSyncLogged) {
                dimensionInitialSyncLogged = true;
                dimensionInitialSyncStarted = ticks;
                append("DIMENSIONS_CLIENT_SYNC_WAIT\tserver fixture is ready; waiting for initial Overworld teleport sync");
            }
            if (ticks - dimensionInitialSyncStarted > 100) {
                fail("Dimension fixture initial Overworld teleport did not synchronize: " + currentClientDimensionState());
            }
            return;
        }
        append("DIMENSIONS_FIXTURE_READY\t" + dimensionsFixtureState);
        append("ASSERT\tserver validated six live portal interior cells and complete obsidian frames in both dimensions; client validated all six Overworld interior cells and its complete frame before commands");
        startDimensionCommand(DimensionStage.NETHER_TO_NETHER, Level.NETHER,
                () -> startNetherReturn());
    }

    private void startDimensionCommand(DimensionStage stage, ResourceKey<Level> expected, Runnable continuation) {
        dimensionStage = stage;
        dimensionExpected = expected;
        dimensionContinuation = continuation;
        dimensionServerPollReady = false;
        dimensionServerPollPassed = false;
        dimensionServerPollState = "not-polled";
        dimensionGatewayObservationOutstanding = false;
        dimensionOuterCommandActive = stage == DimensionStage.END_OUTER_TO_OVERWORLD;
        if (dimensionOuterCommandActive) {
            Player player = Minecraft.getInstance().player;
            dimensionPearlsBeforeOuterCommand = player == null ? -1 : count(player, Items.ENDER_PEARL);
            dimensionPearlInteractionSeen = false;
            dimensionPearlConsumedVerified = false;
            dimensionGatewayCentralSyncStarted = -1;
            dimensionOuterEntryTaskSeen = false;
            dimensionOuterGatewayCenterObserved = false;
            dimensionExitPortalAfterGatewayMatches = false;
        }
        dimensionTaskRunning = true;
        phase = Phase.DIMENSIONS;
        phaseStarted = ticks;
        String target = expected == Level.NETHER ? "nether" : "overworld";
        append("COMMAND\t@goto " + target + "\tstage=" + stage);
        execute("goto " + target, () -> {
            dimensionTaskRunning = false;
            queueDimensionVerification(dimensionContinuation);
        });
    }

    private void startNetherReturn() {
        startDimensionCommand(DimensionStage.NETHER_TO_OVERWORLD, Level.OVERWORLD, () -> {
            if (!dimensionNetherOutTaskSeen || !dimensionNetherBackTaskSeen) {
                fail("Nether round trip did not exercise both portal-entry tasks: out="
                        + dimensionNetherOutTaskSeen + ", back=" + dimensionNetherBackTaskSeen
                        + ", trace=" + taskTrace);
                return;
            }
            startCentralEndSetup();
        });
    }

    private void startCentralEndSetup() {
        setupTeleportForDimensionTest(Level.END, DIMENSIONS_END_CENTRAL_LANDING,
                "central End island setup", () -> startDimensionCommand(
                        DimensionStage.END_CENTRAL_TO_OVERWORLD, Level.OVERWORLD,
                        () -> startOuterEndSetup()));
    }

    private void startOuterEndSetup() {
        setupTeleportForDimensionTest(Level.END, DIMENSIONS_END_OUTER_GATEWAY.offset(-14, -1, 0),
                "outer End gateway setup", () -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) {
                        fail("Client player disappeared after outer gateway fixture setup");
                        return;
                    }
                    startDimensionCommand(DimensionStage.END_OUTER_TO_OVERWORLD, Level.OVERWORLD,
                            () -> finishDimensionAcceptance());
                }, true);
    }

    private void setupTeleportForDimensionTest(ResourceKey<Level> dimension, BlockPos destination,
                                               String label, Runnable continuation) {
        setupTeleportForDimensionTest(dimension, destination, label, continuation, false);
    }

    private void setupTeleportForDimensionTest(ResourceKey<Level> dimension, BlockPos destination,
                                               String label, Runnable continuation, boolean seedPearlKit) {
        if (dimensionTaskRunning) {
            fail("Refusing " + label + " fixture teleport while a dimension task is running");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || dimensionSetupOutstanding) {
            fail("Could not queue " + label + " fixture teleport");
            return;
        }
        dimensionSetupOutstanding = true;
        dimensionSetupReady = false;
        dimensionSetupState = "queued:" + label;
        dimensionSetupExpected = dimension;
        dimensionSetupDestination = destination;
        dimensionSetupSeedPearlKit = seedPearlKit;
        dimensionSetupStarted = ticks;
        dimensionSetupContinuation = continuation;
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = server.getLevel(dimension);
                if (player == null || level == null) throw new IllegalStateException("player or dimension missing");
                if (dimensionTaskRunning) throw new IllegalStateException("task started during fixture teleport");
                boolean centralEndSetup = "central End island setup".equals(label);
                if (centralEndSetup && !endExitPortalMatches(level, DIMENSIONS_END_EXIT_PORTAL)) {
                    throw new IllegalStateException("Server End exit portal is inactive or incomplete before the return task: "
                            + endExitPortalSnapshot(level, DIMENSIONS_END_EXIT_PORTAL));
                }
                player.closeContainer();
                resetToSynchronizedPlayerInventory(player);
                player.getInventory().clearContent();
                if (seedPearlKit) {
                    player.getInventory().add(new ItemStack(Items.ENDER_PEARL, 1));
                    player.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
                }
                player.setGameMode(GameType.SURVIVAL);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);
                player.setDeltaMovement(0.0, 0.0, 0.0);
                boolean moved = player.teleportTo(level, destination.getX() + 0.5, destination.getY(),
                        destination.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
                if (!moved) throw new IllegalStateException("teleportTo returned false");
                publishInventoryMenu(player);
                if (seedPearlKit && (count(player, Items.ENDER_PEARL) != 1
                        || count(player, Items.COBBLESTONE) != 64)) {
                    throw new IllegalStateException("Outer gateway kit count mismatched: " + serverInventoryState(player));
                }
                dimensionSetupState = "label=" + label + ",dimension=" + player.level().dimension()
                        + ",position=" + player.blockPosition() + ",kit=" + seedPearlKit
                        + ",pearls=" + count(player, Items.ENDER_PEARL)
                        + ",cobblestone=" + count(player, Items.COBBLESTONE)
                        + (centralEndSetup ? ",endExit=" + endExitPortalSnapshot(level, DIMENSIONS_END_EXIT_PORTAL) : "")
                        + ",menu=" + player.containerMenu.getClass().getSimpleName()
                        + ",cursor=" + player.containerMenu.getCarried();
            } catch (Throwable error) {
                dimensionSetupState = "error=" + error;
            } finally {
                dimensionSetupOutstanding = false;
                dimensionSetupReady = true;
            }
        });
    }

    private void queueDimensionVerification(Runnable continuation) {
        dimensionContinuation = continuation;
        phase = Phase.DIMENSIONS_VERIFY;
        phaseStarted = ticks;
        dimensionServerPollReady = false;
        dimensionGatewayObservationOutstanding = false;
    }

    private void pollDimensionVerification() {
        Minecraft client = Minecraft.getInstance();
        if (dimensionSetupReady) {
            if (dimensionSetupState.startsWith("error=")) {
                fail("Dimension fixture setup failed: " + dimensionSetupState);
                return;
            }
            boolean synchronizedClient = client.player != null && client.level != null
                    && client.level.dimension().equals(dimensionSetupExpected)
                    && client.player.blockPosition().distManhattan(dimensionSetupDestination) <= 2
                    && saplingPlayerReady(client.player)
                    && (!dimensionSetupSeedPearlKit || count(client.player, Items.ENDER_PEARL) == 1
                    && count(client.player, Items.COBBLESTONE) == 64);
            if (!synchronizedClient) {
                if (ticks - dimensionSetupStarted > 100) {
                    fail("Dimension fixture teleport did not synchronize to the client: server="
                            + dimensionSetupState + ", client=" + currentClientDimensionState());
                }
                return;
            }
            if ("central End island setup".equals(dimensionSetupState.substring(
                    "label=".length(), dimensionSetupState.indexOf(",dimension=")))
                    && !endExitPortalMatches(client.level, DIMENSIONS_END_EXIT_PORTAL)) {
                if (ticks - dimensionSetupStarted > 100) {
                    fail("Client End exit portal did not synchronize before the return task: "
                            + endExitPortalSnapshot(client.level, DIMENSIONS_END_EXIT_PORTAL)
                            + ", setup=" + dimensionSetupState);
                }
                return;
            }
            if ("central End island setup".equals(dimensionSetupState.substring(
                    "label=".length(), dimensionSetupState.indexOf(",dimension=")))) {
                append("ASSERT\tserver/client confirmed all nine active End exit portal blocks before the normal dimension-return task; "
                        + endExitPortalSnapshot(client.level, DIMENSIONS_END_EXIT_PORTAL));
            }
            dimensionSetupReady = false;
            append("DIMENSIONS_SETUP_TELEPORT\t" + dimensionSetupState);
            Runnable next = dimensionSetupContinuation;
            dimensionSetupContinuation = null;
            if (next != null) next.run();
            return;
        }
        if (dimensionServerPollReady) {
            dimensionServerPollReady = false;
            append("DIMENSIONS_SERVER_POLL\t" + dimensionServerPollState);
            boolean clientGood = client.player != null && client.level != null
                    && client.level.dimension().equals(dimensionExpected)
                    && client.player.isAlive() && client.player.getHealth() > 0
                    && isSurvivalMode(client.player)
                    && client.player.containerMenu == client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && !(client.gui.screen() instanceof DeathScreen)
                    && (dimensionStage != DimensionStage.END_OUTER_TO_OVERWORLD
                    || count(client.player, Items.ENDER_PEARL) == 0);
            if (dimensionServerPollPassed && clientGood) {
                if (dimensionStage == DimensionStage.END_OUTER_TO_OVERWORLD) {
                    dimensionPearlConsumedVerified = dimensionPearlsBeforeOuterCommand == 1;
                    append("ASSERT\tserver/client gateway pearl count decreased from exact seeded 1 to 0");
                }
                append("ASSERT\tdimension transition synchronized; " + currentClientDimensionState());
                if (dimensionContinuation != null) {
                    Runnable next = dimensionContinuation;
                    dimensionContinuation = null;
                    next.run();
                }
                return;
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Dimension server/client state did not synchronize: expected=" + dimensionExpected
                    + ", server=" + dimensionServerPollState + ", client=" + currentClientDimensionState());
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || dimensionGatewayObservationOutstanding) return;
        dimensionGatewayObservationOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            boolean passed = player != null && player.level().dimension().equals(dimensionExpected)
                    && player.isAlive() && player.getHealth() > 0
                    && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && (dimensionStage != DimensionStage.END_OUTER_TO_OVERWORLD
                    || count(player, Items.ENDER_PEARL) == 0);
            dimensionServerPollState = player == null ? "player=null"
                    : "dimension=" + player.level().dimension() + ",alive=" + player.isAlive()
                    + ",health=" + player.getHealth() + ",mode=" + player.gameMode.getGameModeForPlayer()
                    + ",menu=" + player.containerMenu.getClass().getSimpleName()
                    + ",cursor=" + player.containerMenu.getCarried()
                    + ",pearls=" + count(player, Items.ENDER_PEARL);
            dimensionServerPollPassed = passed;
            dimensionServerPollReady = true;
            dimensionGatewayObservationOutstanding = false;
        });
    }

    private String currentClientDimensionState() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return "player=null,level=null";
        return "dimension=" + client.level.dimension() + ",position=" + client.player.blockPosition()
                + ",alive=" + client.player.isAlive() + ",health=" + client.player.getHealth()
                + ",survival=" + isSurvivalMode(client.player)
                + ",menu=" + client.player.containerMenu.getClass().getSimpleName()
                + ",cursor=" + client.player.containerMenu.getCarried()
                + ",deathScreen=" + (client.gui.screen() instanceof DeathScreen);
    }

    private void observeDimensionTaskTrace(String signature) {
        if (phase != Phase.DIMENSIONS) return;
        if (dimensionStage == DimensionStage.NETHER_TO_NETHER
                && signature.contains("EnterNetherPortalTask")) dimensionNetherOutTaskSeen = true;
        if (dimensionStage == DimensionStage.NETHER_TO_OVERWORLD
                && signature.contains("EnterNetherPortalTask")) dimensionNetherBackTaskSeen = true;
        if (dimensionStage == DimensionStage.END_CENTRAL_TO_OVERWORLD
                && (signature.contains("DoToClosestBlockTask") || signature.contains("End return portal"))) {
            dimensionCentralRouteTaskSeen = true;
        }
        if (dimensionStage == DimensionStage.END_OUTER_TO_OVERWORLD) {
            if (signature.contains("EnterEndGatewayTask")) {
                dimensionOuterEntryTaskSeen = true;
                append("ASSERT\touter return route selected EnterEndGatewayTask");
            }
            if (signature.contains("InteractWithBlockTask")
                    && signature.toLowerCase(java.util.Locale.ROOT).contains("ender_pearl")
                    && signature.contains(DIMENSIONS_END_OUTER_GATEWAY.toString())) {
                dimensionPearlInteractionSeen = true;
                append("ASSERT\touter return task invoked real ender-pearl block interaction");
            }
        }
    }

    private void observeOuterGatewayTransition() {
        if (phase != Phase.DIMENSIONS || dimensionStage != DimensionStage.END_OUTER_TO_OVERWORLD
                || !dimensionOuterCommandActive) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !client.level.dimension().equals(Level.END)) return;
        BlockPos pos = client.player.blockPosition();
        if (Math.abs(pos.getX()) < 800 && Math.abs(pos.getZ()) < 800
                && !dimensionOuterGatewayCenterObserved) {
            if (dimensionGatewayCentralSyncStarted < 0) dimensionGatewayCentralSyncStarted = ticks;
            dimensionExitPortalAfterGatewayMatches = endExitPortalMatches(
                    client.level, DIMENSIONS_END_EXIT_PORTAL);
            if (!dimensionExitPortalAfterGatewayMatches) {
                if (ticks - dimensionGatewayCentralSyncStarted > 100) {
                    fail("End exit portal did not synchronize after gateway return to the central island: "
                            + endExitPortalSnapshot(client.level, DIMENSIONS_END_EXIT_PORTAL));
                }
                return;
            }
            dimensionOuterGatewayCenterObserved = true;
            append("ASSERT\tclient confirmed all nine active End exit portal blocks after gateway return: "
                    + endExitPortalSnapshot(client.level, DIMENSIONS_END_EXIT_PORTAL));
        }
    }

    private void finishDimensionAcceptance() {
        if (!dimensionNetherOutTaskSeen || !dimensionNetherBackTaskSeen
                || !dimensionCentralRouteTaskSeen || !dimensionOuterEntryTaskSeen
                || !dimensionPearlInteractionSeen || !dimensionPearlConsumedVerified || !dimensionOuterGatewayCenterObserved
                || !dimensionExitPortalAfterGatewayMatches) {
            fail("Dimension acceptance route evidence incomplete: netherOut=" + dimensionNetherOutTaskSeen
                    + ", netherBack=" + dimensionNetherBackTaskSeen
                    + ", centralPortal=" + dimensionCentralRouteTaskSeen
                    + ", outerGateway=" + dimensionOuterEntryTaskSeen
                    + ", pearlAttempt=" + dimensionPearlInteractionSeen
                    + ", pearlConsumedServerClient=" + dimensionPearlConsumedVerified
                    + ", centralTransition=" + dimensionOuterGatewayCenterObserved
                    + ", exitPortalAfterGateway=" + dimensionExitPortalAfterGatewayMatches
                    + ", trace=" + taskTrace);
            return;
        }
        dimensionStage = DimensionStage.COMPLETE;
        dimensionOuterCommandActive = false;
        append("SUMMARY\tPASS\t26.2 runtimeStart=dimensions completed linked Nether portal round trip, central End exit portal return, and outer End return through real pearl interaction with survival/alive/inventory-menu/cursor/death-screen checks: "
                + currentClientDimensionState());
        phase = Phase.DONE;
        writeResult();
    }

    private void buildPortalPlatform(ServerLevel level, BlockPos floorCenter, int radius, int clearHeight) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = floorCenter.offset(dx, 0, dz);
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= clearHeight; dy++) {
                    level.setBlock(floor.above(dy), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private void buildNetherPortal(ServerLevel level, BlockPos bottomInterior) {
        // Complete the whole obsidian frame before placing portal blocks. Updates to
        // an interior cell inside a partial frame can immediately remove that cell.
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                boolean frame = dx == -1 || dx == 2 || dy == -1 || dy == 3;
                if (frame) level.setBlock(bottomInterior.offset(dx, dy, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
        }
        PortalShape shape = PortalShape.findEmptyPortalShape(level, bottomInterior, Direction.Axis.X)
                .orElseThrow(() -> new IllegalStateException("Vanilla portal activation could not find the complete obsidian frame"));
        shape.createPortalBlocks(level);
    }

    private boolean netherPortalFixtureMatches(net.minecraft.world.level.LevelAccessor level, BlockPos bottomInterior) {
        if (level == null || bottomInterior == null) return false;
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        int interiorCount = 0;
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                BlockState actual = level.getBlockState(bottomInterior.offset(dx, dy, 0));
                boolean frame = dx == -1 || dx == 2 || dy == -1 || dy == 3;
                if (frame) {
                    if (!actual.is(Blocks.OBSIDIAN)) return false;
                } else {
                    if (!actual.equals(portal)) return false;
                    interiorCount++;
                }
            }
        }
        return interiorCount == 6;
    }

    private String netherPortalFixtureSnapshot(net.minecraft.world.level.LevelAccessor level, BlockPos bottomInterior) {
        if (level == null || bottomInterior == null) return "level/origin=null";
        List<String> cells = new ArrayList<>();
        int interiorCount = 0;
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                BlockState state = level.getBlockState(bottomInterior.offset(dx, dy, 0));
                cells.add("(" + dx + "," + dy + ")=" + state);
                if (state.is(Blocks.NETHER_PORTAL)) interiorCount++;
            }
        }
        return "interiorCount=" + interiorCount + ",cells=" + cells;
    }

    private void buildEndPortal(ServerLevel level, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(center.offset(dx, 0, dz), Blocks.END_PORTAL.defaultBlockState(), 3);
            }
        }
    }

    private boolean endExitPortalMatches(net.minecraft.world.level.LevelAccessor level, BlockPos center) {
        if (level == null || center == null) return false;
        BlockState expected = Blocks.END_PORTAL.defaultBlockState();
        int portalCount = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.getBlockState(center.offset(dx, 0, dz)).equals(expected)) return false;
                portalCount++;
            }
        }
        return portalCount == 9;
    }

    private String endExitPortalSnapshot(net.minecraft.world.level.LevelAccessor level, BlockPos center) {
        if (level == null || center == null) return "level/origin=null";
        List<String> cells = new ArrayList<>();
        int portalCount = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockState state = level.getBlockState(center.offset(dx, 0, dz));
                cells.add("(" + dx + ",0," + dz + ")=" + state);
                if (state.is(Blocks.END_PORTAL)) portalCount++;
            }
        }
        return "portalCount=" + portalCount + ",cells=" + cells;
    }

    private void runSaplingSetup() {
        phase = Phase.SAPLING_SETUP;
        phaseStarted = ticks;
        saplingPrepared.set(false);
        saplingSeedValid = false;
        saplingSeedState = "not-prepared";
        saplingPollOutstanding = false;
        saplingPollReady = false;
        saplingPollPassed = false;
        saplingPollState = "not-polled";
        saplingOutputResetReady = false;
        saplingOutputResetState = "not-reset";
        propagulePollOutstanding = false;
        propagulePollReady = false;
        propagulePollPassed = false;
        propagulePollState = "not-polled";
        saplingShearsSafeHandSeen = false;
        saplingSilkSafeHandSeen = false;
        saplingCollectorTraceSeen = false;
        saplingShearsTraceSeen = false;
        saplingSilkTraceSeen = false;
        propaguleCollectorTraceSeen = false;
        propaguleMatureDestroySeen = false;
        propaguleImmatureDestroySeen = false;
        saplingPollStep = 0;

        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            fail("Integrated server or player disappeared before sapling safety setup");
            return;
        }
        testPlayer = client.player.getUUID();
        server.execute(() -> prepareSaplingFixture(server));
    }

    private void prepareSaplingFixture(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
        if (player == null) {
            saplingSeedState = "player=null";
            saplingPrepared.set(true);
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos floor = player.blockPosition().below();
        saplingLeafOrigin = floor.offset(1, 1, 1);
        saplingMaturePropagule = floor.offset(2, 5, 12);
        saplingImmaturePropagule = floor.offset(4, 5, 12);
        saplingOriginalRandomTickSpeed = server.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        resetToSynchronizedPlayerInventory(player);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.SHEARS));
        ItemStack silkTouchAxe = new ItemStack(Items.DIAMOND_AXE);
        var silkTouch = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        silkTouchAxe.enchant(silkTouch, 1);
        player.getInventory().setItem(1, silkTouchAxe);
        player.getInventory().setSelectedSlot(0);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0f);
        player.setDeltaMovement(0.0, 0.0, 0.0);

        // A compact ground-accessible leaf volume gives the real vanilla loot table
        // many independent sapling rolls without seeding any sapling item output.
        for (int dx = -2; dx <= 12; dx++) {
            for (int dz = -2; dz <= 14; dz++) {
                BlockPos floorPos = floor.offset(dx, 0, dz);
                level.setBlock(floorPos, Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 8; dy++) {
                    level.setBlock(floorPos.above(dy), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        BlockState persistentOakLeaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true)
                .setValue(LeavesBlock.DISTANCE, 1);
        int leavesPlaced = 0;
        for (int dx = 0; dx < 10; dx++) {
            for (int dz = 0; dz < 10; dz++) {
                for (int dy = 0; dy < 5; dy++) {
                    level.setBlock(saplingLeafOrigin.offset(dx, dy, dz), persistentOakLeaves, 3);
                    leavesPlaced++;
                }
            }
        }
        // Add a low, reachable edge of 12 leaves to bring the total to 512.
        for (int dz = 0; dz < 12; dz++) {
            level.setBlock(saplingLeafOrigin.offset(10, 0, dz), persistentOakLeaves, 3);
            leavesPlaced++;
        }
        BlockState mangroveLeaves = Blocks.MANGROVE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true)
                .setValue(LeavesBlock.DISTANCE, 1);
        level.setBlock(saplingMaturePropagule.above(), mangroveLeaves, 3);
        level.setBlock(saplingImmaturePropagule.above(), mangroveLeaves, 3);
        BlockState mature = Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                .setValue(MangrovePropaguleBlock.AGE, 4)
                .setValue(MangrovePropaguleBlock.HANGING, true);
        BlockState immature = Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                .setValue(MangrovePropaguleBlock.AGE, 3)
                .setValue(MangrovePropaguleBlock.HANGING, true);
        level.setBlock(saplingMaturePropagule, mature, 3);
        level.setBlock(saplingImmaturePropagule, immature, 3);

        AABB fixtureBounds = new AABB(floor.offset(-2, 0, -2)).expandTowards(17, 10, 17).inflate(2);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, fixtureBounds)) drop.discard();
        publishInventoryMenu(player);
        saplingSeedValid = player.level() == level
                && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && player.isAlive() && player.getHealth() > 0
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]")
                && count(player, Items.OAK_SAPLING) == 0
                && count(player, Items.MANGROVE_PROPAGULE) == 0
                && saplingToolsMatch(player)
                && leavesPlaced == 512
                && countSaplingLeaves(level) == 512
                && level.getBlockState(saplingMaturePropagule).equals(mature)
                && level.getBlockState(saplingImmaturePropagule).equals(immature);
        saplingSeedState = serverInventoryState(player)
                + ",difficulty=" + server.getWorldData().getDifficulty()
                + ",gamemode=" + player.gameMode.getGameModeForPlayer()
                + ",food=" + player.getFoodData().getFoodLevel()
                + ",randomTickSpeed=" + server.getGameRules().get(GameRules.RANDOM_TICK_SPEED)
                + ",leaves=" + countSaplingLeaves(level)
                + ",mature=" + level.getBlockState(saplingMaturePropagule)
                + ",immature=" + level.getBlockState(saplingImmaturePropagule)
                + ",shearsDamage=" + player.getInventory().getItem(0).getDamageValue()
                + ",silkAxeDamage=" + player.getInventory().getItem(1).getDamageValue();
        saplingPrepared.set(true);
    }

    private int countSaplingLeaves(net.minecraft.world.level.LevelAccessor level) {
        if (level == null || saplingLeafOrigin == null) return 0;
        int total = 0;
        for (int dx = 0; dx < 10; dx++) {
            for (int dz = 0; dz < 10; dz++) {
                for (int dy = 0; dy < 5; dy++) {
                    if (level.getBlockState(saplingLeafOrigin.offset(dx, dy, dz)).is(Blocks.OAK_LEAVES)) total++;
                }
            }
        }
        for (int dz = 0; dz < 12; dz++) {
            if (level.getBlockState(saplingLeafOrigin.offset(10, 0, dz)).is(Blocks.OAK_LEAVES)) total++;
        }
        return total;
    }

    private boolean saplingToolsMatch(Player player) {
        if (player == null || count(player, Items.SHEARS) != 1
                || count(player, Items.DIAMOND_AXE) != 1) return false;
        ItemStack shears = ItemStack.EMPTY;
        ItemStack axe = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.SHEARS)) shears = stack;
            if (stack.is(Items.DIAMOND_AXE)) axe = stack;
        }
        boolean silkTouch = axe.getEnchantments().entrySet().stream()
                .anyMatch(entry -> entry.getIntValue() == 1 && entry.getKey().is(Enchantments.SILK_TOUCH));
        return shears.getCount() == 1 && shears.getDamageValue() == 0
                && axe.getCount() == 1 && axe.getDamageValue() == 0 && silkTouch;
    }

    private boolean saplingPlayerReady(Player player) {
        return player != null && player.isAlive() && player.getHealth() > 0
                && isSurvivalMode(player)
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]");
    }

    private void startSaplingShearsCommand() {
        if (!saplingSeedValid || "player=null".equals(saplingSeedState)) {
            fail("Sapling safety fixture did not start from an empty output inventory and real safe tools: " + saplingSeedState);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !saplingPlayerReady(client.player) || !saplingToolsMatch(client.player)
                || count(client.player, Items.OAK_SAPLING) != 0
                || count(client.player, Items.MANGROVE_PROPAGULE) != 0
                || countSaplingLeaves(client.level) != 512) {
            if (ticks - phaseStarted > 100) fail("Sapling safety fixture did not synchronize: " + clientInventoryState()
                    + ",selectedSlot=" + (client.player == null ? -1 : client.player.getInventory().getSelectedSlot())
                    + ",clientLeaves=" + countSaplingLeaves(client.level) + ",server=" + saplingSeedState);
            return;
        }
        selectSaplingSlot(0);
        if (!client.player.getMainHandItem().is(Items.SHEARS)) {
            fail("Could not select the real shears before sapling collection");
            return;
        }
        append("SAPLING_SEED\t" + saplingSeedState);
        append("ASSERT\t512 persistent oak leaves and mature/immature propagule blocks are real block fixtures; no sapling or propagule item output was seeded; shears and live Silk Touch enchantment are intact");
        phase = Phase.SAPLING_SHEARS;
        phaseStarted = ticks;
        append("COMMAND\t@get oak_sapling 1\tselected=shears, expected safe mining hand=empty");
        execute("get oak_sapling 1", () -> {
            Minecraft current = Minecraft.getInstance();
            if (current.player == null || count(current.player, Items.OAK_SAPLING) < 1) {
                fail("Real leaf loot did not produce an oak sapling while shears were selected");
                return;
            }
            beginSaplingOutputVerification(0);
        });
    }

    private void beginSaplingOutputVerification(int step) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared during sapling output verification");
            return;
        }
        saplingPollStep = step;
        saplingPollOutstanding = false;
        saplingPollReady = false;
        saplingPollPassed = false;
        saplingPollCount = 0;
        saplingPollState = "waiting for server tick";
        phase = Phase.SAPLING_VERIFY;
        phaseStarted = ticks;
    }

    private void pollSaplingVerification() {
        Minecraft client = Minecraft.getInstance();
        if (saplingOutputResetReady) {
            boolean clientReset = client.player != null && saplingPlayerReady(client.player)
                    && saplingToolsMatch(client.player)
                    && count(client.player, Items.OAK_SAPLING) == 0;
            if (saplingOutputResetState.startsWith("error=") || !clientReset && ticks - phaseStarted > 100) {
                fail("Could not reset and synchronize the first oak sapling output before the Silk Touch pass: server="
                        + saplingOutputResetState + ",client=" + clientInventoryState());
                return;
            }
            if (!clientReset) return;
            saplingOutputResetReady = false;
            append("ASSERT\tfirst pass oak sapling output cleared on server and client; Silk Touch pass starts at zero");
            startSilkSaplingPass(client);
            return;
        }
        if ("waiting for server tick".equals(saplingOutputResetState)) {
            if (ticks - phaseStarted > 100) {
                fail("Timed out resetting and synchronizing the first oak sapling output on the server");
            }
            return;
        }
        if (saplingPollReady) {
            saplingPollReady = false;
            append("SAPLING_SERVER_POLL\tstep=" + saplingPollStep + "\t" + saplingPollState);
            int required = 1;
            boolean clientPassed = saplingPlayerReady(client.player) && saplingToolsMatch(client.player)
                    && count(client.player, Items.OAK_SAPLING) >= required
                    && count(client.player, Items.OAK_SAPLING) == saplingPollCount;
            boolean safetySeen = saplingPollStep == 0
                    ? saplingShearsSafeHandSeen && saplingShearsTraceSeen
                    : saplingSilkSafeHandSeen && saplingSilkTraceSeen;
            if (saplingPollPassed && clientPassed && safetySeen && saplingCollectorTraceSeen) {
                append("ASSERT\tserver/client oak sapling output synchronized; selected drop-preserving tool stayed present and undamaged; empty hand observed during leaf breaking");
                if (saplingPollStep == 0) {
                    selectSaplingSlot(1);
                    if (client.player == null || client.player.getMainHandItem().getItem() != Items.DIAMOND_AXE) {
                        fail("Could not select the live Silk Touch axe for the second sapling collection pass");
                        return;
                    }
                    resetSaplingOutputForSilkPass();
                } else {
                    if (!selectEmptySaplingSlot(client)) return;
                    startPropaguleCommand();
                }
                return;
            }
            if (ticks - phaseStarted > 100) {
                fail("Oak sapling server/client/tool safety verification failed: step=" + saplingPollStep
                        + ",server=" + saplingPollState + ",client=" + clientInventoryState()
                        + ",shearsSafeHand=" + saplingShearsSafeHandSeen
                        + ",silkSafeHand=" + saplingSilkSafeHandSeen
                        + ",phaseTraces=" + saplingShearsTraceSeen + "/" + saplingSilkTraceSeen
                        + ",collectorTrace=" + saplingCollectorTraceSeen);
                return;
            }
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || saplingPollOutstanding) return;
        saplingPollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            int required = 1;
            saplingPollCount = player == null ? 0 : count(player, Items.OAK_SAPLING);
            saplingPollPassed = saplingPlayerReady(player) && saplingToolsMatch(player)
                    && saplingPollCount >= required;
            saplingPollState = (player == null ? "player=null" : serverInventoryState(player))
                    + ",oakSaplings=" + saplingPollCount
                    + ",toolsIntact=" + saplingToolsMatch(player)
                    + ",safeHand(shears=" + saplingShearsSafeHandSeen + ",silk=" + saplingSilkSafeHandSeen + ")";
            saplingPollOutstanding = false;
            saplingPollReady = true;
        });
    }

    private void resetSaplingOutputForSilkPass() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before resetting the first sapling output");
            return;
        }
        saplingOutputResetReady = false;
        saplingOutputResetState = "waiting for server tick";
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                if (player == null) throw new IllegalStateException("test player missing");
                resetToSynchronizedPlayerInventory(player);
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    if (player.getInventory().getItem(slot).is(Items.OAK_SAPLING)) {
                        player.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                }
                // The second pass must obtain a new leaf drop, rather than an
                // uncollected item left behind by the shears pass.
                ServerLevel level = (ServerLevel) player.level();
                AABB bounds = new AABB(saplingLeafOrigin.offset(-4, -2, -4))
                        .expandTowards(20, 12, 22);
                for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
                    if (drop.getItem().is(Items.OAK_SAPLING)) drop.discard();
                }
                publishInventoryMenu(player);
                if (count(player, Items.OAK_SAPLING) != 0) {
                    throw new IllegalStateException("oak sapling output remains after clearing inventory slots");
                }
                saplingOutputResetState = serverInventoryState(player);
            } catch (Throwable error) {
                saplingOutputResetState = "error=" + error;
            } finally {
                saplingOutputResetReady = true;
            }
        });
    }

    private void startSilkSaplingPass(Minecraft client) {
        if (client.player == null || count(client.player, Items.OAK_SAPLING) != 0
                || client.player.getInventory().getSelectedSlot() != 1
                || client.player.getMainHandItem().getItem() != Items.DIAMOND_AXE) {
            fail("Silk Touch sapling pass did not start with zero output and the live axe selected: "
                    + clientInventoryState());
            return;
        }
        phase = Phase.SAPLING_SILK;
        phaseStarted = ticks;
        append("COMMAND\t@get oak_sapling 1\tselected=Silk Touch diamond axe, expected safe mining hand=empty");
        execute("get oak_sapling 1", () -> {
            Minecraft current = Minecraft.getInstance();
            if (current.player == null || count(current.player, Items.OAK_SAPLING) < 1) {
                fail("Second real leaf loot did not satisfy the oak sapling target while Silk Touch axe was selected");
                return;
            }
            beginSaplingOutputVerification(1);
        });
    }

    private void selectSaplingSlot(int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.getInventory().setSelectedSlot(slot);
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player != null) player.getInventory().setSelectedSlot(slot);
        });
    }

    private boolean selectEmptySaplingSlot(Minecraft client) {
        if (client.player != null) {
            for (int slot = 0; slot < 9; slot++) {
                if (client.player.getInventory().getItem(slot).isEmpty()) {
                    selectSaplingSlot(slot);
                    return true;
                }
            }
        }
        fail("No empty hotbar slot available before propagule collection: " + clientInventoryState());
        return false;
    }

    private void startPropaguleCommand() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !saplingPlayerReady(client.player)
                || !client.player.getMainHandItem().isEmpty()
                || count(client.player, Items.MANGROVE_PROPAGULE) != 0
                || !client.level.getBlockState(saplingMaturePropagule).equals(
                        Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                                .setValue(MangrovePropaguleBlock.AGE, 4)
                                .setValue(MangrovePropaguleBlock.HANGING, true))
                || !client.level.getBlockState(saplingImmaturePropagule).equals(
                        Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                                .setValue(MangrovePropaguleBlock.AGE, 3)
                                .setValue(MangrovePropaguleBlock.HANGING, true))) {
            fail("Mature-propagule fixture did not synchronize without seeded item outputs: " + clientInventoryState()
                    + ",mainHand=" + (client.player == null ? "player=null" : client.player.getMainHandItem())
                    + ",mature=" + (client.level == null ? "level=null" : client.level.getBlockState(saplingMaturePropagule))
                    + ",immature=" + (client.level == null ? "level=null" : client.level.getBlockState(saplingImmaturePropagule)));
            return;
        }
        phase = Phase.SAPLING_PROPAGULE;
        phaseStarted = ticks;
        append("COMMAND\t@get mangrove_propagule 1\tfixture=mature plus age-3 immature; randomTickSpeed=0");
        execute("get mangrove_propagule 1", () -> beginPropaguleVerification());
    }

    private void beginPropaguleVerification() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared during propagule verification");
            return;
        }
        propagulePollOutstanding = false;
        propagulePollReady = false;
        propagulePollPassed = false;
        propagulePollState = "waiting for server tick";
        phase = Phase.SAPLING_PROPAGULE_VERIFY;
        phaseStarted = ticks;
    }

    private void pollPropaguleVerification() {
        Minecraft client = Minecraft.getInstance();
        if (propagulePollReady) {
            propagulePollReady = false;
            append("PROPAGULE_SERVER_POLL\t" + propagulePollState);
            BlockState immature = Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                    .setValue(MangrovePropaguleBlock.AGE, 3)
                    .setValue(MangrovePropaguleBlock.HANGING, true);
            boolean clientPassed = saplingPlayerReady(client.player)
                    && count(client.player, Items.MANGROVE_PROPAGULE) >= 1
                    && count(client.player, Items.MANGROVE_PROPAGULE) == propagulePollCount
                    && saplingToolsMatch(client.player)
                    && client.level != null
                    && client.level.getBlockState(saplingMaturePropagule).isAir()
                    && client.level.getBlockState(saplingImmaturePropagule).equals(immature);
            if (propagulePollPassed && clientPassed && propaguleCollectorTraceSeen
                    && propaguleMatureDestroySeen && !propaguleImmatureDestroySeen) {
                append("ASSERT\tserver/client obtained a real mature mangrove propagule; exact mature cell was harvested while the age-3 hanging propagule remained untouched");
                append("SUMMARY\tPASS\t26.2 runtimeStart=sapling collected real oak sapling drops while selected shears and Silk Touch axe remained undamaged, then collected only the mature mangrove propagule; server/client inventory and exact block states verified");
                restoreSaplingRandomTickSpeed();
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Mature propagule server/client verification failed: server=" + propagulePollState
                    + ",client=" + clientInventoryState()
                    + ",taskTrace=" + propaguleCollectorTraceSeen
                    + ",matureDestroy=" + propaguleMatureDestroySeen
                    + ",immatureDestroy=" + propaguleImmatureDestroySeen);
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || propagulePollOutstanding) return;
        propagulePollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            BlockState immature = Blocks.MANGROVE_PROPAGULE.defaultBlockState()
                    .setValue(MangrovePropaguleBlock.AGE, 3)
                    .setValue(MangrovePropaguleBlock.HANGING, true);
            int output = player == null ? 0 : count(player, Items.MANGROVE_PROPAGULE);
            boolean cellsMatch = level != null && level.getBlockState(saplingMaturePropagule).isAir()
                    && level.getBlockState(saplingImmaturePropagule).equals(immature);
            propagulePollCount = output;
            propagulePollPassed = saplingPlayerReady(player) && output >= 1
                    && saplingToolsMatch(player) && cellsMatch;
            propagulePollState = (player == null ? "player=null" : serverInventoryState(player))
                    + ",propagules=" + output
                    + ",toolsIntact=" + saplingToolsMatch(player)
                    + ",mature=" + (level == null ? "unknown" : level.getBlockState(saplingMaturePropagule))
                    + ",immature=" + (level == null ? "unknown" : level.getBlockState(saplingImmaturePropagule))
                    + ",task(mature=" + propaguleMatureDestroySeen + ",immature=" + propaguleImmatureDestroySeen + ")";
            propagulePollOutstanding = false;
            propagulePollReady = true;
        });
    }

    private void observeSaplingTaskTrace(String signature) {
        String trace = signature.toLowerCase(java.util.Locale.ROOT);
        Minecraft client = Minecraft.getInstance();
        boolean mining = trace.contains("destroyblocktask") && client.player != null
                && client.player.getMainHandItem().isEmpty();
        if ((phase == Phase.SAPLING_SHEARS || phase == Phase.SAPLING_SILK)
                && (trace.contains("collectsaplingtask") || trace.contains("oak_sapling")
                || trace.contains("cataloguedresourcetask"))) {
            if (!saplingCollectorTraceSeen) append("ASSERT\tactive resource task trace observed oak sapling collection");
            saplingCollectorTraceSeen = true;
            if (phase == Phase.SAPLING_SHEARS) saplingShearsTraceSeen = true;
            if (phase == Phase.SAPLING_SILK) saplingSilkTraceSeen = true;
        }
        if ((phase == Phase.SAPLING_SHEARS || phase == Phase.SAPLING_SILK)
                && mining && countSaplingLeaves(client.level) < 512) {
            if (phase == Phase.SAPLING_SHEARS && !saplingShearsSafeHandSeen) {
                saplingShearsSafeHandSeen = true;
                append("ASSERT\tleaf DestroyBlockTask observed with empty main hand while shears were selected initially");
            }
            if (phase == Phase.SAPLING_SILK && !saplingSilkSafeHandSeen) {
                saplingSilkSafeHandSeen = true;
                append("ASSERT\tleaf DestroyBlockTask observed with empty main hand while Silk Touch axe was selected initially");
            }
        }
        if (phase == Phase.SAPLING_PROPAGULE
                && (trace.contains("collectmaturemangrovepropaguletask") || trace.contains("mangrove_propagule"))) {
            propaguleCollectorTraceSeen = true;
        }
        if (phase == Phase.SAPLING_PROPAGULE && trace.contains("destroyblocktask")) {
            if (trace.contains(saplingMaturePropagule.toShortString())
                    || client.level != null && client.level.getBlockState(saplingMaturePropagule).isAir()) {
                propaguleMatureDestroySeen = true;
            }
            if (trace.contains(saplingImmaturePropagule.toShortString())) propaguleImmatureDestroySeen = true;
        }
    }

    private void restoreSaplingRandomTickSpeed() {
        int original = saplingOriginalRandomTickSpeed;
        if (original < 0) return;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(() -> {
            server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, original, server);
            saplingOriginalRandomTickSpeed = -1;
        });
    }

    private void prepareArena(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
        if (player == null) {
            append("ERROR\tIntegrated server could not find the test player");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        server.setDifficulty(Difficulty.PEACEFUL, true);
        int x = floorOrigin.getX();
        int y = floorOrigin.getY();
        int z = floorOrigin.getZ();

        player.setGameMode(GameType.SURVIVAL);
        AABB arenaBounds = new AABB(x - 16, y - 8, z - 16, x + 17, y + 16, z + 17);
        // Prepared tests may briefly path outside the platform while a container
        // opens. Remove old drops from previous runs in the surrounding loaded area.
        for (ItemEntity dropped : level.getEntitiesOfClass(ItemEntity.class, arenaBounds.inflate(128))) dropped.discard();
        player.getInventory().clearContent();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20.0f);
        player.setDeltaMovement(0.0, 0.0, 0.0);

        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                level.setBlock(new BlockPos(x + dx, y, z + dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 12; dy++) {
                    level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        // A deliberately plain, accessible oak stand provides enough real wood for
        // the wood -> tools -> furnace -> iron pick progression and later crafting.
        for (int dx = 1; dx <= 2; dx++) {
            for (int dz : new int[]{-1, 1}) {
                for (int dy = 1; dy <= 8; dy++) {
                    level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.OAK_LOG.defaultBlockState(), 3);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 2), Blocks.COAL_ORE.defaultBlockState(), 3);
        }
        for (int i = 0; i < 8; i++) {
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 4), Blocks.IRON_ORE.defaultBlockState(), 3);
        }
        for (int i = 0; i < 5; i++) {
            level.setBlock(new BlockPos(x + 5 + i, y + 1, z + 6), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
        }
        player.teleportTo(x + 0.5, y + 1.0, z + 0.5);
        arenaReady.set(true);
    }

    private void prepareNaturalWorld(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
        if (player == null) {
            naturalServerSeedState = "player=null";
            naturalPrepared.set(true);
            arenaReady.set(true);
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (level.isFlat()) {
            naturalServerSeedState = "rejected-superflat-world";
            naturalPrepared.set(true);
            arenaReady.set(true);
            return;
        }
        server.setDifficulty(Difficulty.NORMAL, true);
        player.setGameMode(GameType.SURVIVAL);
        resetToSynchronizedPlayerInventory(player);
        player.getInventory().clearContent();
        for (int slot = 1; slot <= 4; slot++) player.inventoryMenu.getSlot(slot).set(ItemStack.EMPTY);
        naturalResourceServerChestCraftedBefore = player.getStats()
                .getValue(Stats.ITEM_CRAFTED, Items.CHEST);
        player.setHealth(20.0f);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        publishInventoryMenu(player);
        naturalWorldSeed = level.getSeed();
        naturalDimension = level.dimension().identifier().toString();
        naturalServerSeedState = serverInventoryState(player)
                + ",craftingResult=" + craftingResultState(player.containerMenu)
                + ",position=" + player.blockPosition()
                + ",gamemode=" + player.gameMode.getGameModeForPlayer()
                + ",difficulty=" + server.getWorldData().getDifficulty()
                + ",health=" + player.getHealth()
                + ",food=" + player.getFoodData().getFoodLevel()
                + ",saturation=" + player.getFoodData().getSaturationLevel()
                + ",chestCraftedBefore=" + naturalResourceServerChestCraftedBefore
                + ",seed=" + naturalWorldSeed
                + ",dimension=" + naturalDimension;
        naturalServerSeedValid = inventoryCount(player.getInventory()) == 0
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]")
                && craftingResultEmpty(player.containerMenu)
                && isSurvivalMode(player)
                && server.getWorldData().getDifficulty() == Difficulty.NORMAL
                && player.getHealth() == 20.0f
                && player.getFoodData().getFoodLevel() == 20;
        naturalPrepared.set(true);
        arenaReady.set(true);
    }

    private void startNaturalAcceptance() {
        Minecraft client = Minecraft.getInstance();
        if (!naturalPrepared.get() || !naturalServerSeedValid
                || "player=null".equals(naturalServerSeedState)) {
            fail("Natural-world setup did not establish a valid synchronized player: " + naturalServerSeedState);
            return;
        }
        boolean clientReady = client.player != null && client.level != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && craftingGridState(client.player.containerMenu).equals("[]")
                && inventoryCount(client.player.getInventory()) == 0
                && client.level.dimension().identifier().toString().equals(naturalDimension);
        if (!clientReady) {
            fail("Natural-world empty inventory or world identity did not sync: server="
                    + naturalServerSeedState + ", client=" + clientInventoryState());
            return;
        }
        naturalDiamondCount = 0;
        naturalDiamondComplete = false;
        naturalListComplete = false;
        naturalResourceListComplete = false;
        naturalWoodTraceSeen = false;
        naturalToolTraceSeen = false;
        naturalIronTraceSeen = false;
        naturalSmeltTraceSeen = false;
        naturalFuelTraceSeen = false;
        naturalDiamondTraceSeen = false;
        naturalBuildTaskSeen = false;
        naturalBuildMaterialGatherSeen = false;
        append("NATURAL_SEED\tserver/client inventory empty; " + naturalServerSeedState);
        startDiamondTask();
    }

    private void startNaturalResourceListAcceptance() {
        Minecraft client = Minecraft.getInstance();
        if (!naturalPrepared.get() || !naturalServerSeedValid
                || naturalWorldSeed != NATURAL_RESOURCE_ACCEPTANCE_SEED
                || !"minecraft:overworld".equals(naturalDimension)) {
            fail("Natural resource-list setup was not a fresh accepted seed/world: prepared="
                    + naturalPrepared.get() + ",valid=" + naturalServerSeedValid
                    + ",seed=" + naturalWorldSeed + ",dimension=" + naturalDimension
                    + ",server=" + naturalServerSeedState);
            return;
        }
        if (client.player == null || client.level == null
                || !isSurvivalMode(client.player)
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !craftingGridState(client.player.containerMenu).equals("[]")
                || !craftingResultEmpty(client.player.containerMenu)
                || inventoryCount(client.player.getInventory()) != 0
                || client.player.getHealth() <= 0
                || client.player.getFoodData().getFoodLevel() != 20
                || !client.level.dimension().identifier().toString().equals(naturalDimension)) {
            fail("Natural resource-list empty-inventory setup did not synchronize: server="
                    + naturalServerSeedState + ",client=" + clientInventoryState()
                    + ",craftingResult=" + craftingResultState(client.player == null
                    ? null : client.player.containerMenu));
            return;
        }

        MinecraftServer logObservationServer = client.getSingleplayerServer();
        if (logObservationServer == null || testPlayer == null
                || !testPlayer.equals(client.player.getUUID())) {
            fail("Natural resource-list log observer could not bind to the active integrated player/server");
            return;
        }
        clearNaturalLogObservation();
        naturalLogObservationSession = new NaturalLogObservationSession(
                logObservationServer, testPlayer, client.level.dimension());
        naturalResourceServerNaturalLogBreakCount = 0;
        naturalResourceServerNaturalLogBreaks = "not-polled";
        naturalResourceServerChestCraftedAfter = naturalResourceServerChestCraftedBefore;
        naturalResourceServerChestCraftedDelta = 0;

        naturalResourceRootSeen = false;
        naturalResourceCommandRoot = null;
        naturalResourceLogsSeen = false;
        naturalResourcePlanksSeen = false;
        naturalResourceChestCraftSeen = false;
        naturalResourceStickTaskSeen = false;
        naturalResourceStickPathSeen = false;
        naturalResourceContainerLootSeen = false;
        naturalResourceServerPollOutstanding = false;
        naturalResourceServerPollReady = false;
        naturalResourceServerPollPassed = false;
        naturalResourceServerPollState = "not-polled";
        naturalResourceServerCraftingResult = "not-polled";
        naturalResourceServerInventory = List.of();
        naturalResourceServerChestCount = 0;
        naturalResourceServerStickCount = 0;
        phase = Phase.NATURAL_RESOURCE_LIST;
        phaseStarted = ticks;
        String command = "get [chest 2, stick 4]";
        append("NATURAL_RESOURCE_SEED\tserver=" + naturalServerSeedState
                + "\tclient=" + clientInventoryState()
                + "\tcraftingResult=" + craftingResultState(client.player.containerMenu)
                + "\tseed=" + naturalWorldSeed + "\tdimension=" + naturalDimension
                + "\tstart=" + naturalInitialPosition
                + "\tchestCraftedBefore=" + naturalResourceServerChestCraftedBefore);
        append("COMMAND\t" + prefix + command);
        var mod = Debug.jankModInstance;
        var previousCompletion = mod.getUserTaskChain().getLastCompletionSnapshot();
        try {
            AltoClef.getCommandExecutor().execute(prefix + command, () -> {
                if (phase != Phase.NATURAL_RESOURCE_LIST) {
                    append("RESOURCE_LIST_LATE_CALLBACK_IGNORED\tphase=" + phase);
                    return;
                }
                var completion = mod.getUserTaskChain().getLastCompletionSnapshot();
                if (completion == null || completion == previousCompletion) {
                    fail("Resource-list callback had no new task-completion snapshot");
                } else if (completion.failure() != null || completion.cancelled()) {
                    fail("Natural resource-list task failed or was cancelled: "
                            + (completion.failure() == null ? "cancelled" : completion.failure().reason()));
                } else {
                    append("COMMAND_FINISHED\t" + command);
                    phase = Phase.NATURAL_RESOURCE_VERIFY;
                    phaseStarted = ticks;
                    requestNaturalResourceListServerPoll();
                }
            }, error -> fail("Natural resource-list command failed: " + error.getMessage()));
        } catch (Throwable error) {
            fail("Could not start natural resource-list command: " + error);
        }
    }

    private void observeNaturalResourceListTrace(List<Task> tasks) {
        if (naturalResourceCommandRoot == null) {
            for (Task task : tasks) {
                if (task instanceof CataloguedResourceTask resource
                        && resource.getItemTargets().length == 2
                        && itemTargetCountIs(resource.getItemTargets(), Items.CHEST, 2)
                        && itemTargetCountIs(resource.getItemTargets(), Items.STICK, 4)) {
                    naturalResourceCommandRoot = resource;
                    break;
                }
            }
        }
        if (naturalResourceCommandRoot == null
                || tasks.stream().noneMatch(task -> task == naturalResourceCommandRoot)) return;

        for (Task task : tasks) {
            if (!naturalResourceRootSeen && task == naturalResourceCommandRoot) {
                naturalResourceRootSeen = true;
                append("ASSERT\tnormal CataloguedResourceTask targets chest and stick");
            }
            if (task instanceof MineAndCollectTask mine) {
                if (!naturalResourceLogsSeen && itemTargetsOverlap(mine.getItemTargets(), ItemHelper.LOG)) {
                    naturalResourceLogsSeen = true;
                    append("ASSERT\tnatural wood-source mining target observed");
                }
                if (!naturalResourceStickPathSeen
                        && itemTargetsInclude(mine.getItemTargets(), Items.DEAD_BUSH)) {
                    naturalResourceStickPathSeen = true;
                    append("ASSERT\tstick target used the normal dead-bush path");
                }
            }
            if (!naturalResourcePlanksSeen && task instanceof CollectPlanksTask) {
                naturalResourcePlanksSeen = true;
                append("ASSERT\tnormal plank collection/crafting task observed");
            }
            if (!naturalResourceChestCraftSeen && task instanceof CraftInTableTask craft
                    && java.util.Arrays.stream(craft.getRecipeTargets())
                    .anyMatch(target -> target.getOutputItem() == Items.CHEST)) {
                naturalResourceChestCraftSeen = true;
                append("ASSERT\tCraftInTableTask targets the chest recipe output");
            }
            if (!naturalResourceStickTaskSeen && task instanceof CollectSticksTask) {
                naturalResourceStickTaskSeen = true;
                append("ASSERT\tnormal stick collection task observed");
            }
            if (!naturalResourceStickPathSeen && task instanceof CraftInInventoryTask craft
                    && craft.getRecipeTarget().getOutputItem() == Items.STICK
                    && craft.getRecipeTarget().getRecipe().toString().contains("craft sticks")) {
                naturalResourceStickPathSeen = true;
                append("ASSERT\tstick target used the normal crafting recipe");
            }

            String taskTrace = (task.getClass().getSimpleName() + " " + task)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!naturalResourceContainerLootSeen
                    && (taskTrace.contains("lootcontainertask") || taskTrace.contains("lootchesttask")
                    || taskTrace.contains("pickupfromcontainertask"))) {
                naturalResourceContainerLootSeen = true;
                append("ASSERT\tcontainer-looting task entered the resource-list trace; this invalidates the natural recipe scope");
            }
        }
    }

    private boolean itemTargetsInclude(ItemTarget[] targets, Item item) {
        for (ItemTarget target : targets) {
            if (target != null && target.matches(item)) return true;
        }
        return false;
    }

    private boolean itemTargetCountIs(ItemTarget[] targets, Item item, int expectedCount) {
        for (ItemTarget target : targets) {
            if (target != null && target.matches(item)) {
                return target.getTargetCount() == expectedCount;
            }
        }
        return false;
    }

    private boolean itemTargetsOverlap(ItemTarget[] targets, Item[] items) {
        for (Item item : items) {
            if (itemTargetsInclude(targets, item)) return true;
        }
        return false;
    }

    private void requestNaturalResourceListServerPoll() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || naturalResourceServerPollOutstanding) return;
        naturalResourceServerPollOutstanding = true;
        naturalResourceServerPollReady = false;
        UUID checkingPlayer = testPlayer;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(checkingPlayer);
            if (player == null) {
                naturalResourceServerPollPassed = false;
                naturalResourceServerInventory = List.of();
                naturalResourceServerChestCount = 0;
                naturalResourceServerStickCount = 0;
                naturalResourceServerNaturalLogBreakCount = 0;
                naturalResourceServerNaturalLogBreaks = "player-null";
                naturalResourceServerCraftingResult = "player-null";
                naturalResourceServerChestCraftedAfter = naturalResourceServerChestCraftedBefore;
                naturalResourceServerChestCraftedDelta = 0;
                naturalResourceServerPollState = "player=null";
            } else {
                naturalResourceServerCraftingResult = craftingResultState(player.containerMenu);
                naturalResourceServerChestCount = count(player, Items.CHEST);
                naturalResourceServerStickCount = count(player, Items.STICK);
                naturalResourceServerChestCraftedAfter = player.getStats()
                        .getValue(Stats.ITEM_CRAFTED, Items.CHEST);
                naturalResourceServerChestCraftedDelta = naturalResourceServerChestCraftedAfter
                        - naturalResourceServerChestCraftedBefore;
                List<ItemStack> snapshot = new ArrayList<>(player.getInventory().getContainerSize());
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    snapshot.add(player.getInventory().getItem(slot).copy());
                }
                naturalResourceServerInventory = List.copyOf(snapshot);
                NaturalLogObservationSession observation = naturalLogObservationSession;
                List<NaturalLogBreakReceipt> confirmedLogBreaks = observation != null
                        && observation.server == server
                        && observation.playerId.equals(player.getUUID())
                        && observation.dimension.equals(player.level().dimension())
                        ? observation.receipts.values().stream()
                        .filter(receipt -> !receipt.sourceBlock().equals(receipt.immediatePostBreakBlock()))
                        .sorted(Comparator.comparingInt((NaturalLogBreakReceipt receipt) -> receipt.position().getX())
                                .thenComparingInt(receipt -> receipt.position().getY())
                                .thenComparingInt(receipt -> receipt.position().getZ()))
                        .toList()
                        : List.of();
                naturalResourceServerNaturalLogBreakCount = confirmedLogBreaks.size();
                naturalResourceServerNaturalLogBreaks = confirmedLogBreaks.stream()
                        .map(receipt -> receipt.position().toShortString() + ":" + receipt.sourceBlock()
                                + "->" + receipt.immediatePostBreakBlock() + "@" + receipt.serverGameTime())
                        .collect(java.util.stream.Collectors.joining(";"));
                boolean valid = naturalResourceServerChestCount >= 2
                        && naturalResourceServerStickCount >= 4
                        && naturalResourceServerNaturalLogBreakCount >= 1
                        && naturalResourceServerChestCraftedDelta >= 2
                        && player.containerMenu == player.inventoryMenu
                        && player.containerMenu.getCarried().isEmpty()
                        && craftingGridState(player.containerMenu).equals("[]")
                        && craftingResultEmpty(player.containerMenu)
                        && isSurvivalMode(player)
                        && player.getHealth() > 0
                        && player.getFoodData().getFoodLevel() == 20
                        && server.getWorldData().getDifficulty() == Difficulty.NORMAL
                        && player.level().dimension().identifier().toString().equals(naturalDimension);
                naturalResourceServerPollPassed = valid;
                naturalResourceServerPollState = serverInventoryState(player)
                        + ",craftingResult=" + craftingResultState(player.containerMenu)
                        + ",chest=" + naturalResourceServerChestCount
                        + ",stick=" + naturalResourceServerStickCount
                        + ",seed=" + naturalWorldSeed
                        + ",dimension=" + player.level().dimension().identifier()
                        + ",survival=" + isSurvivalMode(player)
                        + ",health=" + player.getHealth()
                        + ",food=" + player.getFoodData().getFoodLevel()
                        + ",difficulty=" + server.getWorldData().getDifficulty()
                        + ",serverLogBreakCount=" + naturalResourceServerNaturalLogBreakCount
                        + ",serverLogBreaks=" + naturalResourceServerNaturalLogBreaks
                        + ",chestCrafted=" + naturalResourceServerChestCraftedBefore
                        + "->" + naturalResourceServerChestCraftedAfter
                        + ",chestCraftedDelta=" + naturalResourceServerChestCraftedDelta
                        + ",valid=" + valid;
            }
            naturalResourceServerPollReady = true;
            naturalResourceServerPollOutstanding = false;
        });
    }

    private void pollNaturalResourceListResult() {
        if (naturalResourceServerPollReady) {
            naturalResourceServerPollReady = false;
            append("NATURAL_RESOURCE_SERVER_RESULT\t" + naturalResourceServerPollState);
            Minecraft client = Minecraft.getInstance();
            boolean clientValid = client.player != null && client.level != null
                    && client.player.containerMenu == client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(client.player.containerMenu).equals("[]")
                    && craftingResultEmpty(client.player.containerMenu)
                    && isSurvivalMode(client.player)
                    && client.player.getHealth() > 0
                    && client.player.getFoodData().getFoodLevel() == 20
                    && client.level.dimension().identifier().toString().equals(naturalDimension)
                    && count(client.player, Items.CHEST) >= 2
                    && count(client.player, Items.STICK) >= 4
                    && inventoriesMatch(naturalResourceServerInventory, client.player.getInventory())
                    && naturalResourceServerNaturalLogBreakCount >= 1
                    && naturalResourceServerChestCraftedDelta >= 2
                    && naturalResourceRootSeen && naturalResourceLogsSeen
                    && naturalResourcePlanksSeen && naturalResourceChestCraftSeen
                    && naturalResourceStickTaskSeen && naturalResourceStickPathSeen
                    && !naturalResourceContainerLootSeen;
            if (naturalResourceServerPollPassed && clientValid) {
                clearNaturalLogObservation();
                append("NATURAL_LOG_HARVEST\tcount=" + naturalResourceServerNaturalLogBreakCount
                        + "\tserverPlayerDestroyReceipts=" + naturalResourceServerNaturalLogBreaks);
                append("ASSERT\tcontrolled Survival player broke at least one natural log on the integrated server; playerDestroy completed drops and the immediate server block state changed");
                append("CHEST_CRAFT_STATS\tserverBefore=" + naturalResourceServerChestCraftedBefore
                        + "\tserverAfter=" + naturalResourceServerChestCraftedAfter
                        + "\tdelta=" + naturalResourceServerChestCraftedDelta);
                append("ASSERT\tserver item-crafted chest statistic increased by at least two during the resource-list request");
                append("ASSERT\tserver/client crafting result slot 0 is empty"
                        + "\tserver=" + naturalResourceServerCraftingResult
                        + "\tclient=" + craftingResultState(client.player.containerMenu));
                append("ASSERT\tserver/client inventories and minimum chest/stick quantities match"
                        + "\tchest=" + count(client.player, Items.CHEST)
                        + "\tstick=" + count(client.player, Items.STICK)
                        + "\tinventory=" + clientInventoryState()
                        + "\tcraftingResult=" + craftingResultState(client.player.containerMenu));
                append("RESOURCE_LIST_ACCEPTANCE\tPASS\tnatural multi-target chest/stick collection from empty inventory");
                append("SUMMARY\tPASS\t26.2 runtimeStart=naturalresource used one normal @get [chest 2, stick 4] command from a fresh natural world and empty inventory; server-confirmed natural log break with completed drop generation, recursive plank/table/chest acquisition and normal stick path observed; synchronized server/client inventories, quantities, survival, menu, cursor, crafting inputs, and result slot verified");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - phaseStarted > NATURAL_RESOURCE_SYNC_TIMEOUT_TICKS) {
            fail("Natural resource-list result did not pass server/client and task-trace checks: server="
                    + naturalResourceServerPollState + ",serverValid=" + naturalResourceServerPollPassed
                    + ",client=" + clientInventoryState()
                    + ",clientCraftingResult=" + craftingResultState(Minecraft.getInstance().player == null
                    ? null : Minecraft.getInstance().player.containerMenu)
                    + ",trace{root=" + naturalResourceRootSeen + ",logs=" + naturalResourceLogsSeen
                    + ",serverLogBreakCount=" + naturalResourceServerNaturalLogBreakCount
                    + ",chestCraftedDelta=" + naturalResourceServerChestCraftedDelta
                    + ",planks=" + naturalResourcePlanksSeen + ",chestCraft=" + naturalResourceChestCraftSeen
                    + ",stickTask=" + naturalResourceStickTaskSeen + ",stickPath=" + naturalResourceStickPathSeen
                    + ",containerLoot=" + naturalResourceContainerLootSeen + "}");
            return;
        }
        if (!naturalResourceServerPollOutstanding && !naturalResourceServerPollReady) {
            requestNaturalResourceListServerPoll();
        }
    }

    private boolean inventoriesMatch(List<ItemStack> expected,
                                     net.minecraft.world.entity.player.Inventory actual) {
        if (expected.size() != actual.getContainerSize()) return false;
        for (int slot = 0; slot < expected.size(); slot++) {
            if (!ItemStack.matches(expected.get(slot), actual.getItem(slot))) return false;
        }
        return true;
    }

    private void startDiamondTask() {
        if (phase != Phase.WAITING || !arenaReady.get()) return;
        phase = Phase.DIAMOND;
        phaseStarted = ticks;
        naturalDiamondComplete = false;
        append("COMMAND\t@get diamond");
        execute("get diamond", () -> {
            int diamonds = count(Minecraft.getInstance().player, Items.DIAMOND);
            if (diamonds < 1) {
                fail("@get diamond completed without acquiring a diamond: client count=" + diamonds);
                return;
            }
            if (naturalMode) {
                naturalDiamondCount = diamonds;
                naturalDiamondComplete = true;
            }
            append("ASSERT\tdiamond acquired\tclient count=" + diamonds
                    + (naturalMode ? "\tcaptured natural checkpoint count=" + naturalDiamondCount : ""));
            runListCommand();
        });
    }

    private void runListCommand() {
        phase = Phase.LIST;
        phaseStarted = ticks;
        append("COMMAND\t@list");
        execute("list", () -> {
            if (naturalMode) naturalListComplete = true;
            append("ASSERT\tlist command completed\tresources=" + TaskCatalogue.resourceNames().size());
            runResourceListCommand();
        });
    }

    private void runResourceListCommand() {
        phase = Phase.RESOURCE_LIST;
        phaseStarted = ticks;
        append("COMMAND\t@get [chest, stick]");
        execute("get [chest, stick]", () -> {
            Minecraft client = Minecraft.getInstance();
            if (count(client.player, Items.CHEST) < 1 || count(client.player, Items.STICK) < 1) {
                fail("Multi-resource collection did not produce both chest and stick");
                return;
            }
            append("ASSERT\tchest and stick acquired through one resource-list command");
            if (naturalMode) {
                naturalResourceListComplete = true;
                beginNaturalResourceCheckpoint();
            } else {
                runBuildCommand();
            }
        });
    }

    private void beginNaturalResourceCheckpoint() {
        if (!naturalDiamondComplete || !naturalListComplete || !naturalResourceListComplete) {
            fail("Natural-world acceptance cannot pass because one or more requested command phases did not complete");
            return;
        }
        if (!naturalWoodTraceSeen || !naturalToolTraceSeen || !naturalIronTraceSeen
                || !naturalSmeltTraceSeen || !naturalFuelTraceSeen || !naturalDiamondTraceSeen) {
            fail("Natural-world prerequisite trace incomplete: wood=" + naturalWoodTraceSeen
                    + ", tool=" + naturalToolTraceSeen + ", iron=" + naturalIronTraceSeen
                    + ", smelt=" + naturalSmeltTraceSeen + ", fuel=" + naturalFuelTraceSeen
                    + ", diamond=" + naturalDiamondTraceSeen
                    + ", trace=" + taskTrace);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() == null) {
            fail("Integrated server disappeared before the natural-world resource checkpoint");
            return;
        }
        phase = Phase.NATURAL_CHECKPOINT;
        phaseStarted = ticks;
        naturalCheckpointOutstanding = false;
        naturalCheckpointReady = false;
        naturalCheckpointPassed = false;
        naturalCheckpointState = "waiting for server tick";
        append("NATURAL_RESOURCE_CHECKPOINT_START\tdiamond=" + naturalDiamondCount
                + ",chest=1,stick>=1; exact server/client counts captured before build materials can be consumed");
    }

    private void observeNaturalTaskTrace(List<String> sequence) {
        String trace = String.join(" -> ", sequence).toLowerCase(java.util.Locale.ROOT);
        if (!naturalWoodTraceSeen && (trace.contains("collectplankstask") || trace.contains("collectwood"))) {
            naturalWoodTraceSeen = true;
            append("ASSERT\tnatural-world trace observed wood gathering/crafting prerequisite");
        }
        if (!naturalToolTraceSeen && (trace.contains("wooden_pickaxe") || trace.contains("stone_pickaxe")
                || trace.contains("iron_pickaxe"))) {
            naturalToolTraceSeen = true;
            append("ASSERT\tnatural-world trace observed a pickaxe/tool prerequisite");
        }
        if (!naturalIronTraceSeen && trace.contains("mineandcollecttask")
                && (trace.contains("iron_ore") || trace.contains("raw_iron"))) {
            naturalIronTraceSeen = true;
            append("ASSERT\tnatural-world trace observed iron mining prerequisite");
        }
        if (!naturalSmeltTraceSeen && trace.contains("smeltinfurnacetask")) {
            naturalSmeltTraceSeen = true;
            append("ASSERT\tnatural-world trace observed iron smelting prerequisite");
        }
        boolean fuelLoaded = trace.contains("smeltinfurnacetask")
                && sequence.stream().anyMatch(entry -> entry.contains("MoveItemToSlotFromInventoryTask")
                        && entry.contains("FurnaceSlot{") && entry.contains("window slot = 1}"));
        if (!naturalFuelTraceSeen && (trace.contains("collectfueltask") || fuelLoaded)) {
            naturalFuelTraceSeen = true;
            append("ASSERT\tnatural-world trace observed "
                    + (fuelLoaded ? "loading naturally gathered inventory fuel into the furnace fuel slot"
                            : "fuel collection for iron smelting"));
        }
        if (!naturalDiamondTraceSeen && trace.contains("mineandcollecttask") && trace.contains("diamond")) {
            naturalDiamondTraceSeen = true;
            append("ASSERT\tnatural-world trace observed diamond mining task");
        }
        if (phase == Phase.NATURAL_BUILD) {
            if (!naturalBuildMaterialGatherSeen && trace.contains("cataloguedresourcetask")) {
                naturalBuildMaterialGatherSeen = true;
                append("ASSERT\tnatural schematic build entered CataloguedResourceTask to gather its torch material");
            }
            if (!naturalBuildTaskSeen && trace.contains("buildschematictask")) {
                naturalBuildTaskSeen = true;
                append("ASSERT\tnatural schematic build ran the normal BuildSchematicTask");
            }
        }
    }

    private boolean isNaturalCommandPhase(Phase phase) {
        return phase == Phase.DIAMOND || phase == Phase.LIST || phase == Phase.RESOURCE_LIST;
    }

    private void appendNaturalObservation(AltoClef mod) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || client.level == null) return;

        String pathing = "unknown";
        try {
            var baritone = mod.getClientBaritone();
            if (baritone != null) {
                pathing = Boolean.toString(baritone.getPathingBehavior().isPathing());
            }
        } catch (RuntimeException error) {
            pathing = "unavailable:" + error.getClass().getSimpleName();
        }

        int accessibleDiamonds = StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(Items.DIAMOND));
        int accessibleChests = StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(Items.CHEST));
        int accessibleSticks = StorageHelper.getAccessibleInventoryItemCount(mod, new ItemTarget(Items.STICK));
        append("NATURAL_OBSERVE\ttick=" + ticks
                + "\tphase=" + phase
                + "\tposition=" + player.blockPosition()
                + "\thealth=" + player.getHealth()
                + "\tfood=" + player.getFoodData().getFoodLevel()
                + "\tsaturation=" + player.getFoodData().getSaturationLevel()
                + "\tpathing=" + pathing
                + "\taccessible(diamond=" + accessibleDiamonds
                + ",chest=" + accessibleChests
                + ",stick=" + accessibleSticks + ")"
                + "\tnearestChestDrop=" + nearestNaturalDrop(mod, player, Items.CHEST)
                + "\tnearestStickDrop=" + nearestNaturalDrop(mod, player, Items.STICK));
    }

    private String nearestNaturalDrop(AltoClef mod, Player player, Item wantedItem) {
        ItemEntity nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
            if (!drop.isAlive() || drop.getItem().getItem() != wantedItem) continue;
            double distanceSquared = drop.position().distanceToSqr(player.position());
            if (distanceSquared < nearestDistanceSquared) {
                nearest = drop;
                nearestDistanceSquared = distanceSquared;
            }
        }
        if (nearest == null) return "none";
        return "{position=" + nearest.position()
                + ",distance=" + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(nearestDistanceSquared))
                + ",count=" + nearest.getItem().getCount()
                + ",alive=" + nearest.isAlive()
                + ",onGround=" + nearest.onGround()
                + ",playerCollision=" + mod.getEntityTracker().isCollidingWithPlayer(player, nearest)
                + "}";
    }

    private void pollNaturalResourceCheckpoint() {
        Minecraft client = Minecraft.getInstance();
        if (naturalCheckpointReady) {
            naturalCheckpointReady = false;
            append("NATURAL_RESOURCE_CHECKPOINT_SERVER\t" + naturalCheckpointState);
            boolean clientMatches = client.player != null && client.level != null
                    && client.level.dimension().identifier().toString().equals(naturalDimension)
                    && isSurvivalMode(client.player)
                    && client.player.containerMenu == client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(client.player.containerMenu).equals("[]")
                    && count(client.player, Items.DIAMOND) == naturalDiamondCount
                    && count(client.player, Items.CHEST) == 1
                    && count(client.player, Items.STICK) == naturalCheckpointStickCount
                    && count(client.player, Items.STICK) >= 1
                    && count(client.player, Items.TORCH) == 0;
            if (naturalCheckpointPassed && clientMatches) {
                append("ASSERT\tserver/client resource-list checkpoint retained diamond=" + naturalDiamondCount
                        + ", chest=1, stick="
                        + naturalCheckpointStickCount + "; torch=0 before the schematic build began");
                startNaturalBuildSiteCheck();
                return;
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Natural resource-list checkpoint did not synchronize within 100 ticks: server="
                    + naturalCheckpointState + ",client=" + clientInventoryState()
                    + ",expectedDiamond=" + naturalDiamondCount);
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || naturalCheckpointOutstanding) return;
        naturalCheckpointOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            naturalCheckpointStickCount = player == null ? 0 : count(player, Items.STICK);
            naturalCheckpointPassed = player != null
                    && player.level().dimension().identifier().toString().equals(naturalDimension)
                    && isSurvivalMode(player)
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && count(player, Items.DIAMOND) == naturalDiamondCount
                    && count(player, Items.CHEST) == 1
                    && naturalCheckpointStickCount >= 1
                    && count(player, Items.TORCH) == 0;
            naturalCheckpointState = (player == null ? "player=null" : serverInventoryState(player))
                    + ",diamond=" + (player == null ? "unknown" : count(player, Items.DIAMOND))
                    + ",expectedDiamond=" + naturalDiamondCount
                    + ",chest=" + (player == null ? "unknown" : count(player, Items.CHEST))
                    + ",stick=" + naturalCheckpointStickCount
                    + ",torch=" + (player == null ? "unknown" : count(player, Items.TORCH))
                    + ",valid=" + naturalCheckpointPassed;
            naturalCheckpointOutstanding = false;
            naturalCheckpointReady = true;
        });
    }

    private void startNaturalBuildSiteCheck() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            fail("Natural-world player disappeared after the resource checkpoint");
            return;
        }
        BlockPos searchOrigin = client.player.blockPosition();
        naturalBuildSiteCandidates = findNaturalTorchSites(client.level, searchOrigin);
        if (naturalBuildSiteCandidates.isEmpty()) {
            fail("Could not find a strict natural torch lane in loaded blocks within "
                    + NATURAL_BUILD_SITE_SEARCH_RADIUS + " horizontal blocks of " + searchOrigin);
            return;
        }
        phase = Phase.NATURAL_BUILD_TRAVEL;
        phaseStarted = ticks;
        append("NATURAL_BUILD_SITE_CANDIDATES\tcount=" + naturalBuildSiteCandidates.size()
                + "\tsearchRadius=" + NATURAL_BUILD_SITE_SEARCH_RADIUS
                + "\tdirections=north,east,south,west\tloadedChunksOnly=true");
        AltoClef mod = Debug.jankModInstance;
        if (mod == null) {
            fail("AltoClef disappeared before safe natural-site navigation");
            return;
        }
        mod.runUserTask(new NaturalBuildSiteApproachTask(naturalBuildSiteCandidates));
    }

    private List<NaturalTorchSite> findNaturalTorchSites(Level level, BlockPos searchOrigin) {
        if (level == null || searchOrigin == null) return List.of();
        List<NaturalTorchSite> sites = new ArrayList<>();
        Direction[] headings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int distance = 0; distance <= NATURAL_BUILD_SITE_SEARCH_RADIUS; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                int remainingZ = distance - Math.abs(dx);
                int[] zOffsets = remainingZ == 0 ? new int[]{0} : new int[]{-remainingZ, remainingZ};
                for (int dz : zOffsets) {
                    int x = searchOrigin.getX() + dx;
                    int z = searchOrigin.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, searchOrigin.getY(), z))) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos laneOrigin = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(laneOrigin) || !level.hasChunkAt(laneOrigin.below())) continue;
                    for (Direction direction : headings) {
                        for (int offset = 2; offset <= 6; offset++) {
                            if (!naturalTorchSiteMatches(level, laneOrigin, offset, null, direction)) continue;
                            // File builds use the normalized minimum corner as their
                            // player-relative origin. Reverse negative lanes before
                            // navigation so the actual command and verified site agree.
                            boolean negative = direction == Direction.NORTH || direction == Direction.WEST;
                            BlockPos buildOrigin = negative ? laneOrigin.relative(direction, offset) : laneOrigin;
                            Direction buildDirection = negative ? direction.getOpposite() : direction;
                            BlockState support = level.getBlockState(buildOrigin.relative(buildDirection, offset).below());
                            sites.add(new NaturalTorchSite(buildOrigin, buildDirection, offset, support, distance));
                            break;
                        }
                    }
                }
            }
        }
        sites.sort(java.util.Comparator.comparingInt(NaturalTorchSite::searchDistance)
                .thenComparingInt(NaturalTorchSite::offset)
                .thenComparingInt(site -> headingsIndex(site.direction())));
        // Do not spend the runtime harness traversing dozens of similar clearings.
        if (sites.size() > 48) return List.copyOf(sites.subList(0, 48));
        return List.copyOf(sites);
    }

    private int headingsIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 4;
        };
    }

    private void activateNaturalTorchSite(NaturalTorchSite site) {
        Minecraft client = Minecraft.getInstance();
        naturalBuildOrigin = site.origin();
        naturalBuildTorchDirection = site.direction();
        naturalBuildTorchOffset = site.offset();
        naturalBuildTorchTarget = naturalTorchPosition(site.origin(), site.direction(), site.offset());
        naturalBuildSupportState = site.support();
        naturalBuildGameSchematic = client.gameDirectory.toPath().resolve("schematics/natural-runtime-torch.litematic");
        try {
            Files.createDirectories(naturalBuildGameSchematic.getParent());
            writeNaturalTorchFixture(naturalBuildGameSchematic, site);
        } catch (IOException error) {
            fail("Could not write the natural one-torch Litematica fixture: " + error.getMessage());
            return;
        }

        phase = Phase.NATURAL_BUILD_SETUP;
        phaseStarted = ticks;
        naturalBuildSiteCheckOutstanding = true;
        naturalBuildSiteCheckReady = false;
        naturalBuildSiteCheckPassed = false;
        naturalBuildSiteCheckState = "waiting for server tick";
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            naturalBuildSiteCheckOutstanding = false;
            fail("Integrated server disappeared while verifying the natural torch build site");
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = player == null ? null : (ServerLevel) player.level();
                naturalBuildSiteCheckPassed = player != null && level != null
                        && player.blockPosition().equals(naturalBuildOrigin)
                        && player.level().dimension().identifier().toString().equals(naturalDimension)
                        && player.containerMenu == player.inventoryMenu
                        && player.containerMenu.getCarried().isEmpty()
                        && craftingGridState(player.containerMenu).equals("[]")
                        && count(player, Items.DIAMOND) == naturalDiamondCount
                        && count(player, Items.CHEST) == 1
                        && count(player, Items.STICK) == naturalCheckpointStickCount
                        && count(player, Items.TORCH) == 0
                        && naturalTorchSiteMatches(level, naturalBuildOrigin,
                                naturalBuildTorchOffset, naturalBuildSupportState, naturalBuildTorchDirection);
                naturalBuildSiteCheckState = (player == null ? "player=null" : serverInventoryState(player))
                        + ",expectedDiamond=" + naturalDiamondCount
                        + ",origin=" + naturalBuildOrigin
                        + ",target=" + naturalBuildTorchTarget
                        + ",torchOffset=" + naturalBuildTorchOffset
                        + ",worldSiteValid=" + (level != null && naturalTorchSiteMatches(
                                level, naturalBuildOrigin, naturalBuildTorchOffset, naturalBuildSupportState,
                                naturalBuildTorchDirection))
                        + ",valid=" + naturalBuildSiteCheckPassed;
            } catch (Throwable error) {
                naturalBuildSiteCheckPassed = false;
                naturalBuildSiteCheckState = "error=" + error;
            } finally {
                naturalBuildSiteCheckOutstanding = false;
                naturalBuildSiteCheckReady = true;
            }
        });
    }

    private void pollNaturalBuildSite() {
        Minecraft client = Minecraft.getInstance();
        if (naturalBuildSiteCheckReady) {
            naturalBuildSiteCheckReady = false;
            boolean clientSiteValid = client.player != null && client.level != null
                    && client.player.blockPosition().equals(naturalBuildOrigin)
                    && client.level.dimension().identifier().toString().equals(naturalDimension)
                    && count(client.player, Items.DIAMOND) == naturalDiamondCount
                    && count(client.player, Items.CHEST) == 1
                    && count(client.player, Items.STICK) == naturalCheckpointStickCount
                    && count(client.player, Items.TORCH) == 0
                    && naturalTorchSiteMatches(client.level, naturalBuildOrigin,
                            naturalBuildTorchOffset, naturalBuildSupportState, naturalBuildTorchDirection);
            if (!naturalBuildSiteCheckPassed || !clientSiteValid) {
                fail("Natural schematic site or checkpoint inventory did not match on server/client: server="
                        + naturalBuildSiteCheckState + ", client=" + clientInventoryState()
                        + ",expectedDiamond=" + naturalDiamondCount);
                return;
            }
            append("NATURAL_BUILD_SITE\tclient and server confirmed an unmodified, air-clear approach to a sturdy natural support; no fixture terrain was placed or cleared\torigin="
                    + naturalBuildOrigin + "\ttarget=" + naturalBuildTorchTarget
                    + "\tsupport=" + naturalBuildSupportState);
            startNaturalTorchBuild();
            return;
        }
        if (!naturalBuildSiteCheckOutstanding && ticks - phaseStarted > 100) {
            fail("Natural schematic site verification timed out: " + naturalBuildSiteCheckState);
        }
    }

    private void startNaturalTorchBuild() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.player.blockPosition().equals(naturalBuildOrigin)
                || count(client.player, Items.DIAMOND) != naturalDiamondCount
                || count(client.player, Items.CHEST) != 1
                || count(client.player, Items.STICK) != naturalCheckpointStickCount
                || count(client.player, Items.TORCH) != 0) {
            fail("Natural build did not begin from the verified pre-build inventory checkpoint: "
                    + clientInventoryState() + ",expectedDiamond=" + naturalDiamondCount);
            return;
        }
        naturalBuildTaskSeen = false;
        naturalBuildMaterialGatherSeen = false;
        phase = Phase.NATURAL_BUILD;
        phaseStarted = ticks;
        append("COMMAND\t@build natural-runtime-torch.litematic\tmaterial=torch:1\torigin="
                + naturalBuildOrigin + "\ttarget=" + naturalBuildTorchTarget
                + "\tcheckpoint retained in server/client evidence before material consumption");
        execute("build natural-runtime-torch.litematic", () -> {
            phase = Phase.NATURAL_BUILD_VERIFY;
            phaseStarted = ticks;
            naturalBuildVerifyOutstanding = false;
            naturalBuildVerifyReady = false;
            naturalBuildVerifyPassed = false;
            naturalBuildVerifyState = "waiting for server tick";
            append("ASSERT\tnatural @build command completed; verifying exact torch placement, source-air cells, and final server/client inventory");
        });
    }

    private BlockPos naturalTorchPosition(BlockPos origin, Direction direction, int offset) {
        return origin.relative(direction, offset);
    }

    private boolean naturalTorchSiteMatches(Level level, BlockPos origin, int offset,
                                            BlockState expectedSupport, Direction direction) {
        if (level == null || origin == null || offset < 2 || offset > 6) return false;
        if (direction == null || !direction.getAxis().isHorizontal()) return false;
        BlockPos target = naturalTorchPosition(origin, direction, offset);
        BlockPos supportPos = target.below();
        if (!level.hasChunkAt(supportPos) || !level.hasChunkAt(target) || !level.hasChunkAt(target.above())) return false;
        for (int x = 0; x <= offset; x++) {
            BlockPos feet = naturalTorchPosition(origin, direction, x);
            if (!level.getBlockState(feet).isAir() || !level.getFluidState(feet).isEmpty()
                    || !level.getBlockState(feet.above()).isAir()
                    || !level.getFluidState(feet.above()).isEmpty()) return false;
            BlockPos pathFloor = feet.below();
            if (!level.hasChunkAt(pathFloor)
                    || !level.getBlockState(pathFloor).isFaceSturdy(level, pathFloor, Direction.UP)) return false;
        }
        BlockState support = level.getBlockState(supportPos);
        return support.isFaceSturdy(level, supportPos, Direction.UP)
                && (expectedSupport == null || support.equals(expectedSupport));
    }

    private void writeNaturalTorchFixture(Path file, NaturalTorchSite site) throws IOException {
        int axisLength = site.offset() + 1;
        int signedXSize = site.direction().getAxis() == Direction.Axis.X
                ? site.direction().getStepX() * axisLength : 1;
        int signedZSize = site.direction().getAxis() == Direction.Axis.Z
                ? site.direction().getStepZ() * axisLength : 1;
        int width = Math.abs(signedXSize);
        int length = Math.abs(signedZSize);
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef natural-world acceptance: one torch");
        metadata.putString("Author", "AltoClef runtime test");
        metadata.putString("Description", "One torch; all other authored cells are pre-verified natural air.");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putLong("TotalBlocks", 1L);
        metadata.putLong("TotalVolume", (long) width * length);
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", width);
        enclosing.putInt("y", 1);
        enclosing.putInt("z", length);
        metadata.put("EnclosingSize", enclosing);
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", signedXSize);
        size.putInt("y", 1);
        size.putInt("z", signedZSize);
        region.put("Size", size);
        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        palette.add(stateTag(Blocks.TORCH.defaultBlockState()));
        region.put("BlockStatePalette", palette);
        region.putLongArray("BlockStates", new long[]{1L << (site.offset() * 2)});
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("NaturalTorch", region);
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private void pollNaturalBuildVerification() {
        Minecraft client = Minecraft.getInstance();
        if (naturalBuildVerifyReady) {
            naturalBuildVerifyReady = false;
            append("NATURAL_BUILD_SERVER_VERIFY\t" + naturalBuildVerifyState);
            boolean clientWorldMatches = client.level != null && naturalTorchBuildWorldMatches(client.level)
                    && saplingPlayerReady(client.player) && client.player.containerMenu == client.player.inventoryMenu
                    && client.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(client.player.containerMenu).equals("[]")
                    && client.level.dimension().identifier().toString().equals(naturalDimension);
            boolean clientInventoryMatches = client.player != null
                    && count(client.player, Items.DIAMOND) == naturalDiamondCount
                    && count(client.player, Items.CHEST) == 1
                    && count(client.player, Items.STICK) == naturalBuildFinalStickCount
                    && count(client.player, Items.TORCH) == naturalBuildFinalTorchCount;
            if (naturalBuildVerifyPassed && clientWorldMatches && clientInventoryMatches
                    && naturalBuildTaskSeen && naturalBuildMaterialGatherSeen) {
                append("ASSERT\tserver/client exact torch schematic and all source-air cells verified; BuildSchematicTask gathered torch material; diamond="
                        + naturalDiamondCount + " and chest checkpoint resources retained; inventory menu, cursor, and crafting grid clean");
                append("SUMMARY\tPASS\t26.2 runtimeStart=natural\tseed=" + naturalWorldSeed
                        + "\tdimension=" + naturalDimension
                        + "\tstart=" + naturalInitialPosition
                        + "\tdiamondsRetained=" + naturalDiamondCount
                        + "\tno terrain fixtures or teleports; @get diamond recursively gathered wood, tools, iron, fuel, and diamond; @list and @get [chest, stick] passed the exact synchronized pre-build checkpoint; normal @build gathered a torch and placed its natural-terrain schematic at a server/client-verified reachable site");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Natural schematic build did not verify within 100 ticks: server=" + naturalBuildVerifyState
                    + ",clientWorld=" + (client.level == null ? "level=null" : naturalTorchBuildWorldState(client.level))
                    + ",clientInventory=" + clientInventoryState()
                    + ",expectedDiamond=" + naturalDiamondCount
                    + ",buildTask=" + naturalBuildTaskSeen + ",materialGather=" + naturalBuildMaterialGatherSeen);
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || naturalBuildVerifyOutstanding) return;
        naturalBuildVerifyOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            naturalBuildFinalStickCount = player == null ? 0 : count(player, Items.STICK);
            naturalBuildFinalTorchCount = player == null ? 0 : count(player, Items.TORCH);
            naturalBuildVerifyPassed = saplingPlayerReady(player) && level != null
                    && server.getWorldData().getDifficulty() == Difficulty.NORMAL
                    && player.level().dimension().identifier().toString().equals(naturalDimension)
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && count(player, Items.DIAMOND) == naturalDiamondCount
                    && count(player, Items.CHEST) == 1
                    && naturalTorchBuildWorldMatches(level);
            naturalBuildVerifyState = (player == null ? "player=null" : serverInventoryState(player))
                    + ",expectedDiamond=" + naturalDiamondCount
                    + ",torchTarget=" + naturalBuildTorchTarget
                    + ",targetTorch=" + (level == null ? "unknown" : level.getBlockState(naturalBuildTorchTarget))
                    + ",diamond=" + (player == null ? "unknown" : count(player, Items.DIAMOND))
                    + ",chest=" + (player == null ? "unknown" : count(player, Items.CHEST))
                    + ",stick=" + naturalBuildFinalStickCount
                    + ",inventoryTorches=" + naturalBuildFinalTorchCount
                    + ",world=" + (level == null ? "unknown" : naturalTorchBuildWorldState(level))
                    + ",valid=" + naturalBuildVerifyPassed;
            naturalBuildVerifyOutstanding = false;
            naturalBuildVerifyReady = true;
        });
    }

    private boolean naturalTorchBuildWorldMatches(Level level) {
        if (level == null || naturalBuildOrigin == null || naturalBuildTorchTarget == null) return false;
        for (int x = 0; x <= naturalBuildTorchOffset; x++) {
            BlockState expected = x == naturalBuildTorchOffset
                    ? Blocks.TORCH.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (!level.getBlockState(naturalTorchPosition(
                    naturalBuildOrigin, naturalBuildTorchDirection, x)).equals(expected)) return false;
        }
        BlockPos support = naturalBuildTorchTarget.below();
        return level.getBlockState(support).equals(naturalBuildSupportState)
                && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP);
    }

    private String naturalTorchBuildWorldState(Level level) {
        if (level == null) return "level=null";
        List<String> cells = new ArrayList<>();
        for (int x = 0; x <= naturalBuildTorchOffset; x++) {
            BlockPos pos = naturalTorchPosition(naturalBuildOrigin, naturalBuildTorchDirection, x);
            cells.add(pos + "=" + level.getBlockState(pos));
        }
        return "origin=" + naturalBuildOrigin + ",target=" + naturalBuildTorchTarget
                + ",support=" + level.getBlockState(naturalBuildTorchTarget.below()) + ",cells=" + cells;
    }

    private void runBuildCommand() {
        phase = Phase.BUILD_SETUP;
        phaseStarted = ticks;
        buildInventoryCleared.set(false);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) buildOrigin = client.player.blockPosition();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before the schematic build");
            return;
        }
        // Remove previously gathered logs so the build task must obtain its schematic
        // material from the arena before Baritone can place it.
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player != null) {
                player.getInventory().clearContent();
                player.containerMenu.broadcastChanges();
                buildInventoryCleared.set(true);
            }
        });
    }

    private void verifyBuild() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        try {
            var snapshot = testingPlacement
                    ? placementSnapshot
                    : adris.altoclef.util.schematic.SchematicLoader.load(gameSchematic);
            if (snapshot == null) throw new IOException("Active placement snapshot was not captured");
            BlockPos origin = testingPlacement ? snapshot.origin() : buildOrigin;
            String mismatch = findSnapshotMismatch(snapshot, origin);
            if (mismatch != null) {
                if (ticks - phaseStarted > 100) fail("Schematic build callback ran but " + mismatch);
                return;
            }
            append("ASSERT\tall " + snapshot.schematic().widthX() * snapshot.schematic().heightY()
                    * snapshot.schematic().lengthZ() + " schematic positions match, including source air");
            if (!testingPlacement) {
                try {
                    installRotatedPlacement();
                    testingPlacement = true;
                    runBuildCommand();
                } catch (Exception e) {
                    fail("Could not create the active Litematica placement test: " + e);
                }
                return;
            }
            append("ASSERT\trotated active Litematica placement completed with every snapshot position verified");
            runStrippedLogsCommand();
        } catch (Exception e) {
            fail("Could not verify schematic snapshot: " + e.getMessage());
        }
    }

    private String findSnapshotMismatch(adris.altoclef.util.schematic.SchematicSnapshot snapshot, BlockPos origin) {
        Minecraft client = Minecraft.getInstance();
        for (int x = 0; x < snapshot.schematic().widthX(); x++) {
            for (int y = 0; y < snapshot.schematic().heightY(); y++) {
                for (int z = 0; z < snapshot.schematic().lengthZ(); z++) {
                    BlockState expected = snapshot.schematic().getDirect(x, y, z);
                    BlockPos target = origin.offset(x, y, z);
                    BlockState actual = client.level.getBlockState(target);
                    if (expected == null) continue;
                    boolean matches = expected.isAir() ? actual.isAir() : expected.equals(actual);
                    if (!matches) return "state mismatch at " + target + " (local " + x + "," + y + "," + z
                            + "): expected " + expected + ", found " + actual;
                }
            }
        }
        return null;
    }

    private void runStrippedLogsCommand() {
        phase = Phase.STRIPPED_LOGS;
        phaseStarted = ticks;
        append("COMMAND\t@get stripped_oak_log 2");
        execute("get stripped_oak_log 2", () -> {
            Minecraft client = Minecraft.getInstance();
            int held = count(client.player, Items.STRIPPED_OAK_LOG);
            if (held < 2) {
                fail("@get stripped_oak_log 2 completed with only " + held + " stripped oak logs");
                return;
            }
            boolean taskSeen = taskTrace.stream().anyMatch(trace -> trace.contains("CollectStrippedBlockTask"));
            if (!taskSeen) {
                fail("Stripped oak logs were acquired but CollectStrippedBlockTask was not observed in the task trace");
                return;
            }
            append("ASSERT\t@get acquired two stripped oak logs via CollectStrippedBlockTask");
            runMixedBuildCommand();
        });
    }

    private void runMixedBuildCommand() {
        phase = Phase.MIXED_BUILD_SETUP;
        phaseStarted = ticks;
        buildInventoryCleared.set(false);
        mixedFixtureSeeded.set(false);
        mixedMaterialGatherSeen = false;
        mixedServerPollOutstanding = false;
        mixedServerPollReady = false;
        mixedServerPollPassed = false;
        mixedServerPollState = "not-polled";
        mixedLastServerPollTick = ticks - 5;
        hingeRemovalTaskSeen = false;
        hingeRemovalObserved = false;
        hingeRestorationTracked = false;
        hingeRestorationObserved = false;
        hingeFailureInjected = false;
        hingeFailureServerAirOutstanding = false;
        hingeFailureServerAirReady = false;
        hingeFailureServerAirConfirmed = false;
        hingeFailureServerAirState = "not-checked";
        hingeFailurePollOutstanding = false;
        hingeFailurePollReady = false;
        hingeFailurePollPassed = false;
        hingeFailurePollState = "not-polled";
                hingeFailureBuildTask = null;
        testingMixedSchematic = true;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) buildOrigin = client.player.blockPosition();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before the mixed schematic build");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player != null) {
                ServerLevel level = (ServerLevel) player.level();
                BlockPos fixtureOrigin = buildOrigin;
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < MIXED_FIXTURE_WIDTH; x++) {
                        level.setBlock(fixtureOrigin.offset(x, y, 0), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                for (int x = 10; x <= 12; x++) {
                    level.setBlock(fixtureOrigin.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
                }
                level.setBlock(fixtureOrigin.offset(16, -1, 0), Blocks.STONE.defaultBlockState(), 3);
                if (hingeRepairMode || hingeFailureMode) {
                    level.setBlock(fixtureOrigin.offset(12, 0, 0), mixedExpectedState(12, 0), 3);
                }
                player.getInventory().clearContent();
                player.containerMenu.broadcastChanges();
                if (hingeRepairMode || hingeFailureMode) {
                    append("HINGE_FIXTURE\tlocal=12,0,0\tserver seeded exact authored DOUBLE oak slab beside north-facing left-hinge door; empty inventory preserved for material gathering");
                }
                mixedFixtureSeeded.set(true);
                buildInventoryCleared.set(true);
            }
        });
    }

    private void verifyMixedBuild() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        BlockPos fixtureOrigin = buildOrigin;
        boolean clientMatches = mixedFixtureMatches(client.level, fixtureOrigin);
        if (mixedServerPollReady) {
            mixedServerPollReady = false;
            append("MIXED_SERVER_POLL\t" + mixedServerPollState);
            if (mixedServerPollPassed && clientMatches && mixedMaterialGatherSeen) {
            append("ASSERT\tmixed build gathered materials; all 36 fixture cells match, including AIR; closed/left and open/right doors, stair, and double slab agree on client/server");
            append("MIXED_CLIENT_VERIFY\t" + mixedFixtureSnapshot(client.level, fixtureOrigin));
            if (hingeRepairMode) {
                if (!hingeRemovalTaskSeen || !hingeRemovalObserved
                        || !hingeRestorationTracked || !hingeRestorationObserved) {
                    fail("Hinge repair did not prove clear-and-restore: destroyTask=" + hingeRemovalTaskSeen
                            + ", sawAir=" + hingeRemovalObserved + ", restorationTracked=" + hingeRestorationTracked
                            + ", sawRestoredSlab=" + hingeRestorationObserved
                            + ", fixture=" + mixedFixtureSnapshot(client.level, fixtureOrigin));
                    return;
                }
                append("ASSERT\tauthored slab was cleared only after hinge mismatch, tracked for restoration, restored, and all 36 final cells match on client/server");
                append("SUMMARY\tPASS\t26.2 runtimeStart=hingerepair explicitly skipped diamond/list/resource-list, file build, active placement, stripped logs, and smithing; empty-inventory BuildSchematicTask gathered mixed materials, cleared the exact authored double slab adjacent to the north-facing door after hinge-only mismatch, tracked/restored it, and verified all 36 schematic cells on server/client");
                phase = Phase.DONE;
                writeResult();
                return;
            }
            runSmithingSetup();
            return;
            }
        }
        if (clientMatches && !mixedServerPollOutstanding
                && ticks - mixedLastServerPollTick >= 5) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                mixedServerPollOutstanding = true;
                mixedLastServerPollTick = ticks;
                BlockPos serverFixtureOrigin = fixtureOrigin;
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                    ServerLevel level = player == null ? null : (ServerLevel) player.level();
                    mixedServerPollPassed = level != null && mixedFixtureMatches(level, serverFixtureOrigin);
                    mixedServerPollState = level == null ? "player=missing"
                            : mixedFixtureSnapshot(level, serverFixtureOrigin);
                    mixedServerPollOutstanding = false;
                    mixedServerPollReady = true;
                });
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Mixed schematic verification timed out; gathered=" + mixedMaterialGatherSeen
                    + ", clientMatches=" + clientMatches
                    + ", client=" + mixedFixtureSnapshot(client.level, fixtureOrigin)
                    + ", server=" + mixedServerPollState);
        }
    }

    private boolean mixedFixtureMatches(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < MIXED_FIXTURE_WIDTH; x++) {
                if (!level.getBlockState(origin.offset(x, y, 0)).equals(mixedExpectedState(x, y))) return false;
            }
        }
        return true;
    }

    private boolean mixedFixtureIsClear(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < MIXED_FIXTURE_WIDTH; x++) {
                if (!level.getBlockState(origin.offset(x, y, 0)).isAir()) return false;
            }
        }
        return true;
    }

    private boolean mixedFixtureIsPrepared(net.minecraft.world.level.LevelAccessor level,
                                           BlockPos origin, boolean seededAuthoredSlab) {
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < MIXED_FIXTURE_WIDTH; x++) {
                BlockState expected = seededAuthoredSlab && x == 12 && y == 0
                        ? mixedExpectedState(x, y) : Blocks.AIR.defaultBlockState();
                if (!level.getBlockState(origin.offset(x, y, 0)).equals(expected)) return false;
            }
        }
        return true;
    }

    private BlockState mixedExpectedState(int x, int y) {
        if (y == 0 && x == 10) {
            return Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            net.minecraft.core.Direction.EAST)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF,
                            net.minecraft.world.level.block.state.properties.Half.BOTTOM)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE,
                            net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false);
        }
        if (x == 11 || x == 16) {
            boolean openedRightDoor = x == 16;
            return Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                            net.minecraft.core.Direction.NORTH)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                            y == 0 ? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                                    : net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOOR_HINGE,
                            openedRightDoor
                                    ? net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT
                                    : net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED, false)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN,
                            openedRightDoor);
        }
        if (y == 0 && x == 12) {
            return Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                            net.minecraft.world.level.block.state.properties.SlabType.DOUBLE)
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false);
        }
        return Blocks.AIR.defaultBlockState();
    }

    private String mixedFixtureSnapshot(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        List<String> states = new ArrayList<>(MIXED_FIXTURE_WIDTH * 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < MIXED_FIXTURE_WIDTH; x++) {
                states.add(x + "," + y + "=" + level.getBlockState(origin.offset(x, y, 0)));
            }
        }
        return states.toString();
    }

    private void runSmithingSetup() {
        phase = Phase.SMITHING_SETUP;
        phaseStarted = ticks;
        smithingSeeded.set(false);
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before seeded smithing acceptance");
            return;
        }
        smithingTablePos = floorOrigin.offset(-1, 1, 0);
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                append("ERROR\tIntegrated server could not find player for smithing setup");
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 3));
            player.getInventory().setItem(1, new ItemStack(Items.NETHERITE_INGOT, 2));
            player.getInventory().setItem(2, new ItemStack(Items.DIAMOND_PICKAXE, 1));
            player.getInventory().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE, 1));
            level.setBlock(smithingTablePos, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            smithingSeeded.set(true);
        });
    }

    private void startSmithingCommand() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || !client.level.getBlockState(smithingTablePos).is(Blocks.SMITHING_TABLE)
                || count(client.player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) != 3
                || count(client.player, Items.NETHERITE_INGOT) != 2
                || count(client.player, Items.DIAMOND_PICKAXE) != 2) {
            if (ticks - phaseStarted > 100) {
                fail("Server-seeded smithing preconditions did not reach the client in time");
            }
            return;
        }
        phase = Phase.SMITHING;
        phaseStarted = ticks;
        append("SMITHING_SEED\tserver seeded templates=3, ingots=2, diamond_pickaxes=2"
                + "\ttable=" + smithingTablePos);
        append("COMMAND\t@get netherite_pickaxe 2");
        execute("get netherite_pickaxe 2", () -> {
            int pickaxes = count(Minecraft.getInstance().player, Items.NETHERITE_PICKAXE);
            int templates = count(Minecraft.getInstance().player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            if (pickaxes != 2 || templates != 1) {
                fail("Smithing command result mismatch: netheritePickaxes=" + pickaxes
                        + ", retainedTemplates=" + templates);
                return;
            }
            verifyManualServerResult("two successive smithing upgrades",
                    new Item[]{Items.NETHERITE_PICKAXE, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE},
                    new int[]{2, 1}, this::finishSmithingAcceptance);
        });
    }

    private void finishSmithingAcceptance() {
            append("ASSERT\t@get netherite_pickaxe 2 completed successive smithing upgrades and retained one upgrade template");
            String startMode = System.getProperty("altoclef.runtimeStart", "full");
            String gatheringMode = switch (startMode.toLowerCase(java.util.Locale.ROOT)) {
                case "build" -> "build-only mode explicitly skipped diamond/list/resource-list phases";
                case "smithing" -> "smithing-only mode explicitly skipped world gathering/build phases";
                case "mixed" -> "mixed-only mode explicitly skipped diamond/list/resource-list, file build, active placement, and stripped-log phases; mixed materials were gathered before build";
                default -> "empty-inventory diamond/resource gathering and schematic-build phases";
            };
            append("SUMMARY\tPASS\t26.2: real chat-input interception; " + gatheringMode
                    + "; two server-verified smithing transactions with one retained template");
            phase = Phase.DONE;
            writeResult();
    }

    private void runSmeltSetup() {
        phase = Phase.SMELT_SETUP;
        phaseStarted = ticks;
        smeltSeeded.set(false);
        smeltServerSeedValid = false;
        smeltServerSeedState = "not-seeded";
        smeltTaskSeen = false;
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before seeded smelting acceptance");
            return;
        }
        smeltingFurnacePos = floorOrigin.offset(0, 1, 2);
        smeltFurnaceCacheGateLogged = false;
        if (!smeltFurnaceTrackedForAcceptance) {
            Debug.jankModInstance.getBlockTracker().trackBlock(Blocks.FURNACE);
            smeltFurnaceTrackedForAcceptance = true;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                smeltServerSeedState = "player=null";
                smeltSeeded.set(true);
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.RAW_IRON, overstackSmeltMode ? 4 : 3));
            if (multiSmeltMode) {
                player.getInventory().setItem(1, new ItemStack(Items.RAW_COPPER, 3));
                player.getInventory().setItem(2, new ItemStack(Items.COAL, 1));
            } else if (plankFuelMode) {
                player.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 2));
            } else {
                player.getInventory().setItem(1, new ItemStack(Items.COAL, 1));
            }
            level.setBlock(smeltingFurnacePos, Blocks.FURNACE.defaultBlockState(), 3);
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            smeltServerSeedState = serverInventoryState(player) + ",furnace="
                    + level.getBlockState(smeltingFurnacePos);
            smeltServerSeedValid = (multiSmeltMode
                    ? exactInventory(player, new Item[]{Items.RAW_IRON, Items.RAW_COPPER, Items.COAL}, new int[]{3, 3, 1})
                    : plankFuelMode
                    ? exactInventory(player, new Item[]{Items.RAW_IRON, Items.OAK_PLANKS}, new int[]{3, 2})
                    : exactInventory(player, new Item[]{Items.RAW_IRON, Items.COAL}, new int[]{overstackSmeltMode ? 4 : 3, 1}))
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && level.getBlockState(smeltingFurnacePos).is(Blocks.FURNACE);
            smeltSeeded.set(true);
        });
    }

    /**
     * This runtime-only fixture gate waits for the prepared furnace to enter AltoClef's
     * asynchronous BlockTracker cache before issuing the acceptance command. It distinguishes
     * cache warm-up from the resource planner's furnace choice; it does not claim or change
     * production furnace-discovery behavior.
     */
    private boolean smeltFurnaceIsInBlockTrackerCache() {
        AltoClef mod = Debug.jankModInstance;
        return mod != null && smeltingFurnacePos != null
                && smeltFurnaceTrackedForAcceptance
                && mod.getBlockTracker().isTracking(Blocks.FURNACE)
                && mod.getBlockTracker().getKnownLocations(Blocks.FURNACE).contains(smeltingFurnacePos);
    }

    private void stopTrackingSmeltFurnaceForAcceptance() {
        if (!smeltFurnaceTrackedForAcceptance) return;
        AltoClef mod = Debug.jankModInstance;
        if (mod != null) mod.getBlockTracker().stopTracking(Blocks.FURNACE);
        smeltFurnaceTrackedForAcceptance = false;
    }

    private void startSmeltCommand() {
        Minecraft client = Minecraft.getInstance();
        if ("player=null".equals(smeltServerSeedState)) {
            fail("Integrated server could not find player for smelting setup");
            return;
        }
        if (!smeltFurnaceIsInBlockTrackerCache()) {
            if (ticks - phaseStarted > 100) {
                fail("Prepared smelting furnace did not enter BlockTracker cache within 100 ticks: pos="
                        + smeltingFurnacePos + ", tracking=" + Debug.jankModInstance.getBlockTracker().isTracking(Blocks.FURNACE)
                        + ", knownFurnaces=" + Debug.jankModInstance.getBlockTracker().getKnownLocations(Blocks.FURNACE).size()
                        + ", server=" + smeltServerSeedState);
            }
            return;
        }
        if (!smeltFurnaceCacheGateLogged) {
            append("ASSERT\tprepared furnace is present at " + smeltingFurnacePos + " in AltoClef BlockTracker cache");
            smeltFurnaceCacheGateLogged = true;
        }
        int expectedRawIron = overstackSmeltMode ? 4 : 3;
        int expectedFuel = plankFuelMode ? 2 : 1;
        Item fuelItem = plankFuelMode ? Items.OAK_PLANKS : Items.COAL;
        boolean clientSeedMatches = client.level != null && client.player != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && craftingGridState(client.player.containerMenu).equals("[]")
                && inventoryCount(client.player.getInventory()) == expectedRawIron + expectedFuel
                && count(client.player, Items.RAW_IRON) == expectedRawIron
                && count(client.player, fuelItem) == expectedFuel
                && count(client.player, fuelItem == Items.COAL ? Items.OAK_PLANKS : Items.COAL) == 0
                && count(client.player, Items.IRON_INGOT) == 0
                && client.level.getBlockState(smeltingFurnacePos).is(Blocks.FURNACE);
        if (!smeltServerSeedValid || !clientSeedMatches) {
            if (ticks - phaseStarted > 100) {
                fail("Server-seeded smelting preconditions did not reach the client in time: server="
                        + smeltServerSeedState + ", client=" + clientInventoryState()
                        + ", furnace=" + (client.level == null ? "level=null"
                        : client.level.getBlockState(smeltingFurnacePos)));
            }
            return;
        }
        append(plankFuelMode
                ? "ASSERT\tserver and client have exactly 3 raw iron and 2 oak planks, no coal, empty cursor/grid, and a nearby furnace"
                : overstackSmeltMode
                ? "ASSERT\tserver and client have exactly 4 raw iron and 1 coal, no iron ingots, empty cursor/grid, and a nearby furnace"
                : "ASSERT\tserver and client have exactly 3 raw iron and 1 coal, empty cursor/grid, and a nearby furnace");
        phase = Phase.SMELT;
        phaseStarted = ticks;
        append(plankFuelMode
                ? "SMELT_SEED\tserver seeded raw_iron=3, oak_planks=2, coal=0, iron_ingot=0\tfuelAllowed=[oak_planks]\tfurnace=" + smeltingFurnacePos
                : overstackSmeltMode
                ? "SMELT_SEED\tserver seeded raw_iron=4, coal=1, iron_ingot=0\tfurnace=" + smeltingFurnacePos
                : "SMELT_SEED\tserver seeded raw_iron=3, coal=1, iron_ingot=0\tfurnace=" + smeltingFurnacePos);
        append("COMMAND\t@get iron_ingot 3");
        execute("get iron_ingot 3", () -> {
            Minecraft current = Minecraft.getInstance();
            boolean exactResult = current.player != null
                    && (overstackSmeltMode
                    ? exactInventory(current.player, new Item[]{Items.IRON_INGOT, Items.RAW_IRON}, new int[]{3, 1})
                    : exactInventory(current.player, new Item[]{Items.IRON_INGOT}, new int[]{3}))
                    && current.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(current.player.containerMenu).equals("[]");
            if (!exactResult || !smeltTaskSeen) {
                fail("Smelting command result mismatch: exactThreeIngots=" + exactResult
                        + ", smeltTaskSeen=" + smeltTaskSeen + ", client=" + clientInventoryState());
                return;
            }
            verifyManualServerResult(overstackSmeltMode
                            ? "three iron ingots and one leftover raw iron from four seeded raw iron"
                            : "three iron ingots from seeded furnace inputs",
                    overstackSmeltMode ? new Item[]{Items.IRON_INGOT, Items.RAW_IRON} : new Item[]{Items.IRON_INGOT},
                    overstackSmeltMode ? new int[]{3, 1} : new int[]{3}, this::finishSmeltAcceptance);
        });
    }

    private void startMultiSmeltTask() {
        Minecraft client = Minecraft.getInstance();
        if ("player=null".equals(smeltServerSeedState)) {
            fail("Integrated server could not find player for multi-target smelting setup");
            return;
        }
        if (!smeltFurnaceIsInBlockTrackerCache()) {
            if (ticks - phaseStarted > 100) {
                fail("Prepared multi-target smelting furnace did not enter BlockTracker cache within 100 ticks: pos="
                        + smeltingFurnacePos + ", tracking=" + Debug.jankModInstance.getBlockTracker().isTracking(Blocks.FURNACE)
                        + ", knownFurnaces=" + Debug.jankModInstance.getBlockTracker().getKnownLocations(Blocks.FURNACE).size()
                        + ", server=" + smeltServerSeedState);
            }
            return;
        }
        if (!smeltFurnaceCacheGateLogged) {
            append("ASSERT\tprepared furnace is present at " + smeltingFurnacePos + " in AltoClef BlockTracker cache");
            smeltFurnaceCacheGateLogged = true;
        }
        boolean clientSeedMatches = client.level != null && client.player != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && craftingGridState(client.player.containerMenu).equals("[]")
                && inventoryCount(client.player.getInventory()) == 7
                && count(client.player, Items.RAW_IRON) == 3
                && count(client.player, Items.RAW_COPPER) == 3
                && count(client.player, Items.COAL) == 1
                && count(client.player, Items.IRON_INGOT) == 0
                && count(client.player, Items.COPPER_INGOT) == 0
                && client.level.getBlockState(smeltingFurnacePos).is(Blocks.FURNACE);
        if (!smeltServerSeedValid || !clientSeedMatches) {
            if (ticks - phaseStarted > 100) {
                fail("Server-seeded multi-target smelting preconditions did not reach the client in time: server="
                        + smeltServerSeedState + ", client=" + clientInventoryState()
                        + ", furnace=" + (client.level == null ? "level=null"
                        : client.level.getBlockState(smeltingFurnacePos)));
            }
            return;
        }
        append("ASSERT\tserver and client have exactly 3 raw iron, 3 raw copper, and 1 coal, empty cursor/grid, and a nearby furnace");
        phase = Phase.SMELT;
        phaseStarted = ticks;
        append("SMELT_SEED\tserver seeded raw_iron=3, raw_copper=3, coal=1, iron_ingot=0, copper_ingot=0\tfurnace="
                + smeltingFurnacePos);
        append("DIRECT_TASK\tSmeltInFurnaceTask targets=3 iron_ingots + 3 copper_ingots");
        SmeltTarget[] targets = {
                new SmeltTarget(new ItemTarget(Items.IRON_INGOT, 3), new ItemTarget(Items.RAW_IRON, 3)),
                new SmeltTarget(new ItemTarget(Items.COPPER_INGOT, 3), new ItemTarget(Items.RAW_COPPER, 3))
        };
        Debug.jankModInstance.runUserTask(new SmeltInFurnaceTask(targets), () -> {
            Minecraft current = Minecraft.getInstance();
            boolean exactResult = current.player != null
                    && current.player.containerMenu == current.player.inventoryMenu
                    && exactInventory(current.player,
                    new Item[]{Items.IRON_INGOT, Items.COPPER_INGOT}, new int[]{3, 3})
                    && current.player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(current.player.containerMenu).equals("[]");
            if (!exactResult || !smeltTaskSeen) {
                fail("Multi-target smelting result mismatch: exactOutputs=" + exactResult
                        + ", smeltTaskSeen=" + smeltTaskSeen + ", client=" + clientInventoryState());
                return;
            }
            verifyManualServerResult("three iron and three copper ingots from one direct multi-target furnace task",
                    new Item[]{Items.IRON_INGOT, Items.COPPER_INGOT}, new int[]{3, 3},
                    this::finishMultiSmeltAcceptance);
        });
    }

    private void finishMultiSmeltAcceptance() {
        stopTrackingSmeltFurnaceForAcceptance();
        append("ASSERT\tdirect multi-target SmeltInFurnaceTask completed both output targets and left the inventory menu/cursor/grid clean");
        append("SUMMARY\tPASS\t26.2 runtimeStart=multismelt explicitly skipped world gathering and schematic building; direct multi-target SmeltInFurnaceTask produced exactly 3 iron ingots and 3 copper ingots from 3 raw iron, 3 raw copper, and 1 coal; server/client inventory, menu, cursor, and grid verified");
        phase = Phase.DONE;
        writeResult();
    }

    private void runProjectileSetup() {
        phase = Phase.PROJECTILE_SETUP;
        phaseStarted = ticks;
        projectileSeeded.set(false);
        projectileServerSeedValid = false;
        projectileServerSeedState = "not-seeded";
        projectileServerCheckOutstanding = false;
        projectileServerCheckReady = false;
        projectileServerCheckPassed = false;
        projectileServerCheckState = "not-checked";
        projectileFlightStarted = false;
        projectileFlightStartFailed = false;
        projectileFlightState = "not-started";
        projectileClientSawMotion = false;
        projectileClientSawGrounded = false;
        projectileClientHasLastPosition = false;
        projectileClientRequestedFlight = false;
        projectileClientLoggedFlight = false;
        projectileLastServerCheckTick = ticks - 5;

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before projectile accessor acceptance");
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                if (player == null) {
                    projectileServerSeedState = "player=null";
                    return;
                }
                ServerLevel level = (ServerLevel) player.level();
                BlockPos origin = player.blockPosition();
                BlockPos target = origin.offset(12, 1, 0);
                level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);

                Arrow arrow = new Arrow(level, origin.getX() + 1.5, origin.getY() + 1.55,
                        origin.getZ() + 0.5, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                arrow.setNoGravity(true);
                arrow.setDeltaMovement(0.0, 0.0, 0.0);
                boolean added = level.addFreshEntity(arrow);
                projectileId = arrow.getUUID();
                projectileServerSeedValid = added;
                projectileServerSeedState = "spawned=" + added
                        + ",uuid=" + projectileId
                        + ",position=" + arrow.position()
                        + ",target=" + target
                        + ",alive=" + arrow.isAlive()
                        + ",weapon=bow,waitingForClientSpawnAck=true";
            } catch (Exception error) {
                projectileServerSeedValid = false;
                projectileServerSeedState = "setup-error=" + error.getClass().getSimpleName()
                        + ":" + String.valueOf(error.getMessage());
            } finally {
                projectileSeeded.set(true);
            }
        });
    }

    private void startProjectileAcceptance() {
        if (!projectileServerSeedValid || projectileId == null) {
            fail("Integrated-server arrow fixture did not spawn: " + projectileServerSeedState);
            return;
        }
        phase = Phase.PROJECTILE;
        phaseStarted = ticks;
        append("PROJECTILE_SEED\t" + projectileServerSeedState);
        append("PROJECTILE_ASSERT\tserver spawned a real vanilla Arrow; client will query the in-ground accessor each tick");
    }

    private void observeProjectileAcceptance() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || projectileId == null) return;
        net.minecraft.world.entity.Entity entity = client.level.getEntity(projectileId);
        if (entity instanceof Arrow arrow) {
            if (!arrow.isAlive()) {
                fail("Client observed the projectile die before acceptance: " + projectileServerSeedState);
                return;
            }

            if (!projectileClientRequestedFlight) {
                projectileClientRequestedFlight = true;
                startProjectileFlightAfterClientSpawn(client);
            }
            if (projectileFlightStartFailed) {
                fail("Could not start projectile flight after client spawn observation: " + projectileFlightState);
                return;
            }
            if (projectileFlightStarted && !projectileClientLoggedFlight) {
                projectileClientLoggedFlight = true;
                append("PROJECTILE_LAUNCH\t" + projectileFlightState);
            }

            // This is the exact accessor that previously recursed while vanilla arrows ticked.
            boolean inGround = ((PersistentProjectileEntityAccessor) arrow).invokeIsInGround();
            var position = arrow.position();
            if (projectileFlightStarted && projectileClientHasLastPosition
                    && (Math.abs(position.x - projectileClientLastX)
                    + Math.abs(position.y - projectileClientLastY)
                    + Math.abs(position.z - projectileClientLastZ)) > 1.0e-4) {
                if (!projectileClientSawMotion) {
                    projectileClientSawMotion = true;
                    append("PROJECTILE_ASSERT\tclient observed vanilla arrow movement at " + position);
                }
            }
            projectileClientLastX = position.x;
            projectileClientLastY = position.y;
            projectileClientLastZ = position.z;
            projectileClientHasLastPosition = true;

            if (inGround && !projectileClientSawGrounded) {
                projectileClientSawGrounded = true;
                append("PROJECTILE_ASSERT\tclient accessor observed vanilla arrow embedded at " + position
                        + "\talive=" + arrow.isAlive());
            }
        }

        if (projectileClientSawGrounded && !projectileServerCheckOutstanding
                && ticks - projectileLastServerCheckTick >= 5) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                projectileServerCheckOutstanding = true;
                projectileLastServerCheckTick = ticks;
                server.execute(() -> {
                    ServerPlayer serverPlayer = server.getPlayerList().getPlayer(testPlayer);
                    ServerLevel level = serverPlayer == null ? null : (ServerLevel) serverPlayer.level();
                    net.minecraft.world.entity.Entity serverEntity = level == null ? null : level.getEntity(projectileId);
                    if (serverEntity instanceof Arrow serverArrow) {
                        boolean serverGrounded = ((PersistentProjectileEntityAccessor) serverArrow).invokeIsInGround();
                        projectileServerCheckPassed = serverArrow.isAlive() && serverGrounded;
                        projectileServerCheckState = "alive=" + serverArrow.isAlive()
                                + ",inGround=" + serverGrounded
                                + ",position=" + serverArrow.position();
                    } else {
                        projectileServerCheckPassed = false;
                        projectileServerCheckState = serverEntity == null ? "arrow=missing"
                                : "wrongType=" + serverEntity.getClass().getSimpleName();
                    }
                    projectileServerCheckOutstanding = false;
                    projectileServerCheckReady = true;
                });
            }
        }

        if (projectileServerCheckReady) {
            projectileServerCheckReady = false;
            append("PROJECTILE_SERVER_VERIFY\t" + projectileServerCheckState);
            if (projectileServerCheckPassed && projectileClientSawMotion && projectileClientSawGrounded) {
                append("PROJECTILE_ASSERT\tserver/client arrow survived ticking, moved, and reached in-ground state without accessor recursion");
                append("SUMMARY\tPASS\t26.2 runtimeStart=projectile; integrated server spawned a vanilla arrow; client and server queried PersistentProjectileEntityAccessor while arrow moved and embedded; arrow remained alive; no StackOverflowError");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }

        if (ticks - phaseStarted > 200) {
            fail("Projectile accessor acceptance timed out: clientMotion=" + projectileClientSawMotion
                    + ", clientGrounded=" + projectileClientSawGrounded
                    + ", server=" + projectileServerCheckState
                    + ", fixture=" + projectileServerSeedState);
        }
    }

    private void startProjectileFlightAfterClientSpawn(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            projectileFlightStartFailed = true;
            projectileFlightState = "integrated-server=null";
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                ServerLevel level = player == null ? null : (ServerLevel) player.level();
                net.minecraft.world.entity.Entity entity = level == null ? null : level.getEntity(projectileId);
                if (!(entity instanceof Arrow arrow)) {
                    projectileFlightStartFailed = true;
                    projectileFlightState = entity == null ? "arrow=missing"
                            : "wrongType=" + entity.getClass().getSimpleName();
                    return;
                }
                arrow.setNoGravity(true);
                arrow.setDeltaMovement(0.4, 0.0, 0.0);
                projectileFlightState = "server released arrow after client spawn observation"
                        + ",velocity=" + arrow.getDeltaMovement()
                        + ",position=" + arrow.position();
                projectileFlightStarted = true;
            } catch (Exception error) {
                projectileFlightStartFailed = true;
                projectileFlightState = "launch-error=" + error.getClass().getSimpleName()
                        + ":" + String.valueOf(error.getMessage());
            }
        });
    }

    private void finishSmeltAcceptance() {
        stopTrackingSmeltFurnaceForAcceptance();
        if (overstackSmeltMode) {
            append("ASSERT\t@get iron_ingot 3 smelted exactly 3 ingots from 4 raw iron and 1 coal, retained exactly 1 raw iron, consumed all coal, and kept the inventory menu/cursor/grid clean");
            append("SUMMARY\tPASS\t26.2 runtimeStart=smeltoverstack explicitly skipped world gathering and schematic building; @get iron_ingot 3 produced exactly 3 iron ingots from 4 raw iron and 1 coal and retained exactly 1 raw iron; server/client counts, inventory menu, cursor, and grid verified; SmeltInFurnaceTask observed");
            phase = Phase.DONE;
            writeResult();
            return;
        }
        if (plankFuelMode) {
            append("ASSERT\tplank-fuel acceptance completed with exactly 3 iron ingots using oak planks as the only supported fuel; server/client inventory, menu, cursor, and grid verified");
            append("SUMMARY\tPASS\t26.2 runtimeStart=plankfuel explicitly skipped world gathering and schematic building; produced exactly 3 iron ingots from 3 raw iron and 2 oak planks with coal absent and supported fuels restricted to oak planks; server/client inventory, menu, cursor, and grid verified");
            restorePlankFuelMode();
            phase = Phase.DONE;
            writeResult();
            return;
        }
        append("ASSERT\t@get iron_ingot 3 completed furnace smelting with exact server/client inventory state");
        append("SUMMARY\tPASS\t26.2 runtimeStart=smelt explicitly skipped world gathering and schematic building; server/client verified exactly 3 smelted iron ingots from 3 raw iron and 1 coal in a furnace; SmeltInFurnaceTask observed");
        phase = Phase.DONE;
        writeResult();
    }

    private void runConcreteSetup() {
        phase = Phase.CONCRETE_SETUP;
        phaseStarted = ticks;
        concreteSeeded.set(false);
        concreteSeedValid = false;
        concreteSeedState = "not-seeded";
        concreteVerifyOutstanding = false;
        concreteVerifyReady = false;
        concreteVerifyPassed = false;
        concreteVerifyState = "not-polled";
        concreteResourceTaskSeen = false;
        concretePlacementTaskSeen = false;
        concreteMiningTaskSeen = false;
        concreteSourceWaterSeen = false;
        concretePowderItemsConsumed = 0;
        concreteLastPowderCount = 2;
        concretePowderUseLogged = false;
        concretePowderStatesObserved = 0;
        concreteHardeningTransitions = 0;
        concreteMinedTransitions = 0;
        concretePlacementTaskCount = 0;
        concreteLastObservedBlocks.clear();
        concreteMiningTargets.clear();
        concretePowderCoordinates.clear();
        concreteHardenedCoordinates.clear();
        concreteMinedCoordinates.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            fail("Cannot seed concrete fixture without a local player");
            return;
        }
        concretePoolOrigin = floorOrigin.offset(8, 1, 0);
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before concrete fixture setup");
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
                if (player == null) throw new IllegalStateException("player=null");
                ServerLevel level = (ServerLevel) player.level();
                resetToSynchronizedPlayerInventory(player);
                player.getInventory().clearContent();
                player.getInventory().add(new ItemStack(Items.CONCRETE_POWDER.white(), 2));
                player.getInventory().add(new ItemStack(Items.STONE_PICKAXE, 1));
                player.setGameMode(GameType.SURVIVAL);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);

                // The basin has a four-block stone floor, a complete wall ring at the
                // source-water level, and exactly four level-0 source blocks inside.
                for (int dx = -1; dx <= 2; dx++) {
                    for (int dz = -1; dz <= 2; dz++) {
                        level.setBlock(concretePoolOrigin.offset(dx, -1, dz),
                                Blocks.STONE.defaultBlockState(), 3);
                        boolean ring = dx == -1 || dx == 2 || dz == -1 || dz == 2;
                        level.setBlock(concretePoolOrigin.offset(dx, 0, dz),
                                ring ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 3);
                        level.setBlock(concretePoolOrigin.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                player.setDeltaMovement(0.0, 0.0, 0.0);
                publishInventoryMenu(player);
                concreteSeedValid = concreteInventoryReady(player)
                        && concretePoolReady(level, concretePoolOrigin)
                        && player.isAlive() && player.getHealth() > 0
                        && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL;
                concreteSeedState = serverInventoryState(player)
                        + ",sourcePool=" + concretePoolState(level, concretePoolOrigin)
                        + ",survival=" + (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                        + ",alive=" + player.isAlive();
            } catch (Throwable error) {
                concreteSeedValid = false;
                concreteSeedState = "setup-error=" + error;
            } finally {
                concreteSeeded.set(true);
            }
        });
    }

    private void startConcreteCommand() {
        Minecraft client = Minecraft.getInstance();
        if (!concreteSeedValid) {
            fail("Concrete fixture did not establish exact ingredients and contained source water: "
                    + concreteSeedState);
            return;
        }
        if (client.player == null || client.level == null
                || !concreteInventoryReady(client.player)
                || !concretePoolReady(client.level, concretePoolOrigin)) {
            if (ticks - phaseStarted > 100) {
                fail("Concrete source pool or seeded inventory did not synchronize: server="
                        + concreteSeedState + ", client=" + clientInventoryState()
                        + ",pool=" + (client.level == null ? "level=null"
                        : concretePoolState(client.level, concretePoolOrigin)));
            }
            return;
        }
        concreteSourceWaterSeen = true;
        for (BlockPos water : concretePoolPositions(concretePoolOrigin)) {
            concreteLastObservedBlocks.put(water, client.level.getBlockState(water).getBlock());
        }
        append("CONCRETE_SEED\t" + concreteSeedState + "\tclientPool="
                + concretePoolState(client.level, concretePoolOrigin));
        append("ASSERT\tinitial inventory is exactly white concrete powder x2 and stone pickaxe x1; no white concrete is present; all four basin cells are source water");
        append("COMMAND\t@get white_concrete 2\tpool=" + concretePoolOrigin);
        phase = Phase.CONCRETE;
        phaseStarted = ticks;
        execute("get white_concrete 2", () -> {
            phase = Phase.CONCRETE_VERIFY;
            phaseStarted = ticks;
            concreteVerifyOutstanding = false;
            concreteVerifyReady = false;
            concreteVerifyPassed = false;
            concreteVerifyState = "waiting for integrated-server final state";
            append("ASSERT\t@get white_concrete 2 task completed; checking hardening/mining trace and exact client/server output");
        });
    }

    private boolean concreteInventoryReady(Player player) {
        return player != null
                && player.isAlive() && player.getHealth() > 0
                && isSurvivalMode(player)
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]")
                && exactInventory(player,
                        new Item[]{Items.CONCRETE_POWDER.white(), Items.STONE_PICKAXE}, new int[]{2, 1});
    }

    private boolean concretePoolReady(net.minecraft.world.level.Level level, BlockPos origin) {
        if (level == null || origin == null) return false;
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos floor = origin.offset(dx, -1, dz);
                BlockPos ring = origin.offset(dx, 0, dz);
                if (!level.getBlockState(floor).isRedstoneConductor(level, floor)) return false;
                boolean wall = dx == -1 || dx == 2 || dz == -1 || dz == 2;
                if (wall && !level.getBlockState(ring).isRedstoneConductor(level, ring)) return false;
                if (!wall && (!level.getBlockState(ring).is(Blocks.WATER)
                        || !level.getBlockState(ring).getFluidState().isSource())) return false;
            }
        }
        return true;
    }

    private String concretePoolState(net.minecraft.world.level.Level level, BlockPos origin) {
        if (level == null || origin == null) return "pool=null";
        List<String> cells = new ArrayList<>();
        for (BlockPos pos : concretePoolPositions(origin)) {
            cells.add(pos.toShortString() + "=" + level.getBlockState(pos));
        }
        return cells.toString();
    }

    private List<BlockPos> concretePoolPositions(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>(4);
        if (origin == null) return result;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) result.add(origin.offset(dx, 0, dz));
        }
        return result;
    }

    private void observeConcreteTaskTrace(String signature, List<Task> activeTasks) {
        if (phase != Phase.CONCRETE) return;
        if (signature.contains("CollectConcreteTask") && !concreteResourceTaskSeen) {
            concreteResourceTaskSeen = true;
            append("ASSERT\tactive resource task trace includes CollectConcreteTask");
        }
        if (signature.contains("ObservedPowderPlacementTask")) {
            concretePlacementTaskSeen = true;
            concretePlacementTaskCount++;
            append("CONCRETE_POWDER_PLACEMENT_TASK\tcount=" + concretePlacementTaskCount
                    + "\t" + signature);
            for (Task task : activeTasks) {
                if (!task.getClass().getSimpleName().equals("ObservedPowderPlacementTask")) continue;
                try {
                    java.lang.reflect.Field powderPosition = task.getClass().getDeclaredField("_powderPosition");
                    powderPosition.setAccessible(true);
                    Object value = powderPosition.get(task);
                    if (value instanceof BlockPos pos) {
                        concretePowderCoordinates.add(pos.immutable());
                        append("CONCRETE_POWDER_TARGET\t" + pos.toShortString());
                    }
                } catch (ReflectiveOperationException error) {
                    append("CONCRETE_POWDER_TARGET\tposition unavailable: " + error.getClass().getSimpleName());
                }
            }
        }
        if (signature.contains("DestroyBlockTask")) {
            for (BlockPos pos : concretePoolPositions(concretePoolOrigin)) {
                if (signature.contains(pos.toShortString())) {
                    concreteMiningTaskSeen = true;
                    concreteMiningTargets.add(pos);
                    append("ASSERT\tDestroyBlockTask observed mining converted white concrete at "
                            + pos.toShortString());
                }
            }
        }
    }

    private void observeConcreteProgress() {
        if ((phase != Phase.CONCRETE && phase != Phase.CONCRETE_VERIFY)
                || concretePoolOrigin == null) return;
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player != null) {
            concretePowderItemsConsumed = Math.max(concretePowderItemsConsumed,
                    Math.min(2, 2 - count(player, Items.CONCRETE_POWDER.white())));
            if (concretePowderItemsConsumed > 0 && !concretePowderUseLogged) {
                concretePowderUseLogged = true;
                append("ASSERT\twhite concrete powder item consumption observed: used="
                        + concretePowderItemsConsumed + "/2");
            }
            int currentPowderCount = count(player, Items.CONCRETE_POWDER.white());
            if (currentPowderCount < concreteLastPowderCount) {
                append("CONCRETE_ITEM_USE\twhite concrete powder " + concreteLastPowderCount
                        + "->" + currentPowderCount);
                concreteLastPowderCount = currentPowderCount;
            }
        }
        if (client.level == null) return;
        for (BlockPos pos : concretePoolPositions(concretePoolOrigin)) {
            Block actual = client.level.getBlockState(pos).getBlock();
            Block previous = concreteLastObservedBlocks.put(pos, actual);
            if (actual == Blocks.CONCRETE_POWDER.white() && previous != actual) {
                concretePowderStatesObserved++;
                concretePowderCoordinates.add(pos);
                append("CONCRETE_STATE\tpowder placed at " + pos.toShortString());
            }
            if (actual == Blocks.CONCRETE.white() && previous != actual) {
                concreteHardeningTransitions++;
                concreteHardenedCoordinates.add(pos);
                append("CONCRETE_STATE\twhite concrete hardened at " + pos.toShortString()
                        + "\ttransition=" + concreteHardeningTransitions);
            } else if (previous == Blocks.CONCRETE.white() && actual != Blocks.CONCRETE.white()
                    && concreteMiningTargets.contains(pos)) {
                concreteMinedTransitions++;
                concreteMinedCoordinates.add(pos);
                append("CONCRETE_STATE\tmined white concrete cleared at " + pos.toShortString()
                        + "\ttransition=" + concreteMinedTransitions + "\tnow=" + actual);
            }
        }
    }

    private boolean concreteFinalPlayerState(Player player) {
        return player != null && player.isAlive() && player.getHealth() > 0
                && isSurvivalMode(player)
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]")
                && exactInventory(player, new Item[]{Items.CONCRETE.white(), Items.STONE_PICKAXE}, new int[]{2, 1});
    }

    private void pollConcreteVerification() {
        Minecraft client = Minecraft.getInstance();
        if (concreteVerifyReady) {
            concreteVerifyReady = false;
            append("CONCRETE_SERVER_POLL\t" + concreteVerifyState);
            boolean clientPassed = concreteFinalPlayerState(client.player)
                    && !(client.gui.screen() instanceof DeathScreen);
            boolean tracePassed = concreteResourceTaskSeen && concretePlacementTaskSeen
                    && concretePlacementTaskCount >= 2 && concretePowderItemsConsumed == 2
                    && concreteSourceWaterSeen && !concretePowderCoordinates.isEmpty()
                    && concreteHardeningTransitions >= 2
                    && concreteMiningTaskSeen && concreteMinedTransitions >= 2;
            if (concreteVerifyPassed && clientPassed && tracePassed) {
                append("CONCRETE_CLIENT_FINAL\t" + clientInventoryState()
                        + "\tpool=" + concretePoolState(client.level, concretePoolOrigin)
                        + "\tpowderPositions=" + concretePowderCoordinates
                        + "\thardenedPositions=" + concreteHardenedCoordinates
                        + "\tminedPositions=" + concreteMinedCoordinates);
                append("ASSERT\tCollectConcreteTask consumed both powders, placed/hardened and mined concrete twice, and server/client inventory equals white concrete x2 plus one durability-worn stone pickaxe; cursor/grid/menu/survival/death checks passed");
                append("SUMMARY\tPASS\t26.2 runtimeStart=concrete gathered white concrete x2 through the normal @get command from an existing contained 2x2 source-water pool and seeded powder/pickaxe; recursive ingredient gathering explicitly skipped; observed powder placement, concrete hardening, mining, and exact server/client result");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - phaseStarted > 100) {
            fail("Concrete final state or conversion evidence incomplete: server=" + concreteVerifyState
                    + ", client=" + clientInventoryState()
                    + ", resourceTask=" + concreteResourceTaskSeen
                    + ", placementTask=" + concretePlacementTaskCount
                    + ", powderConsumed=" + concretePowderItemsConsumed
                    + ", sourceWater=" + concreteSourceWaterSeen
                    + ", hardenedTransitions=" + concreteHardeningTransitions
                    + ", miningTask=" + concreteMiningTaskSeen
                    + ", minedTransitions=" + concreteMinedTransitions
                    + ", pool=" + (client.level == null ? "level=null"
                    : concretePoolState(client.level, concretePoolOrigin)));
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || concreteVerifyOutstanding) return;
        concreteVerifyOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            concreteVerifyPassed = concreteFinalPlayerState(player);
            concreteVerifyState = player == null ? "player=null" : serverInventoryState(player)
                    + ",alive=" + player.isAlive()
                    + ",health=" + player.getHealth()
                    + ",survival=" + (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL)
                    + ",pool=" + concretePoolState((ServerLevel) player.level(), concretePoolOrigin);
            concreteVerifyOutstanding = false;
            concreteVerifyReady = true;
        });
    }

    private boolean isSurvivalMode(Player player) {
        return player != null && player.gameMode() == GameType.SURVIVAL;
    }

    private void runFallbackSetup() {
        phase = Phase.FALLBACK_SETUP;
        phaseStarted = ticks;
        fallbackSeeded.set(false);
        fallbackServerSeedValid = false;
        fallbackServerSeedState = "not-seeded";
        fallbackSmeltSeen = false;
        fallbackCraftingTableSeen = false;
        fallbackDropperSeen = false;
        fallbackCrafterSeen = false;
        crafterBuildGatherStartedEmpty = false;
        crafterBuildServerPollOutstanding = false;
        crafterBuildServerPollReady = false;
        crafterBuildServerPollPassed = false;
        crafterBuildServerPollState = "not-polled";
        crafterBuildTask = null;
        crafterBuildVerifyStarted = 0;
        crafterMatrixServerSetupValid = false;
        crafterMatrixServerPlayerPosition = null;
        crafterMatrixServerSetupSnapshot = "not-prepared";
        crafterMatrixLastSyncLogTick = -20;
        Minecraft client = Minecraft.getInstance();
        crafterBuildOrigin = client.player == null ? null
                : crafterMatrixMode ? floorOrigin.above() : client.player.blockPosition();
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared before vanilla recipe fallback acceptance");
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            if (player == null) {
                fallbackServerSeedState = "player=null";
                fallbackSeeded.set(true);
                return;
            }
            ServerLevel level = (ServerLevel) player.level();
            resetToSynchronizedPlayerInventory(player);
            player.getInventory().clearContent();
            if (crafterBuildMode) player.setGameMode(GameType.SURVIVAL);
            if (crafterPlacementOnlyMode) {
                player.getInventory().add(new ItemStack(Items.STONE));
                player.getInventory().add(new ItemStack(Items.CRAFTER));
            }
            for (int i = 0; i < 8; i++) {
                level.setBlock(floorOrigin.offset(5 + i, 1, 8), Blocks.REDSTONE_ORE.defaultBlockState(), 3);
            }
            if (crafterMatrixMode) {
                // The DOWN-facing approach uses a one-block-deep landing below the
                // arena floor. prepareArena resets the floor and everything above it;
                // clear this bounded below-floor staging pad between orientations too.
                for (int x = 3; x <= 5; x++) {
                    for (int z = -1; z <= 1; z++) {
                        level.setBlock(floorOrigin.offset(x, -1, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            if (crafterBuildMode && crafterBuildOrigin != null) {
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < crafterFixtureHeight(); y++) {
                        level.setBlock(crafterBuildOrigin.offset(x, y, 0), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                prepareCrafterApproachGeometry(level, crafterBuildOrigin);
            }
            player.teleportTo(floorOrigin.getX() + 0.5, floorOrigin.getY() + 1.0,
                    floorOrigin.getZ() + 0.5);
            publishInventoryMenu(player);
            crafterMatrixServerPlayerPosition = player.blockPosition();
            crafterMatrixServerSetupValid = crafterMatrixMode
                    && crafterMatrixServerPlayerPosition.equals(crafterBuildOrigin)
                    && crafterMatrixStartWorldMatches(level, crafterBuildOrigin)
                    && crafterApproachGeometryReady(level, crafterBuildOrigin);
            crafterMatrixServerSetupSnapshot = crafterMatrixWorldSnapshot(
                    level, player, crafterBuildOrigin);
            fallbackServerSeedState = serverInventoryState(player) + ",redstoneOre="
                    + level.getBlockState(floorOrigin.offset(5, 1, 8))
                    + (crafterMatrixMode ? ",matrixServerValid=" + crafterMatrixServerSetupValid
                            + ",matrix=" + crafterMatrixServerSetupSnapshot : "");
            fallbackServerSeedValid = (crafterPlacementOnlyMode
                    ? crafterPlacementInventoryMatches(player)
                    : inventoryCount(player.getInventory()) == 0)
                    && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && (!crafterBuildMode || crafterApproachGeometryReady(level, crafterBuildOrigin))
                    && (!crafterMatrixMode || crafterMatrixServerSetupValid)
                    && level.getBlockState(floorOrigin.offset(5, 1, 8)).is(Blocks.REDSTONE_ORE);
            fallbackSeeded.set(true);
        });
    }

    private void startFallbackCommand() {
        Minecraft client = Minecraft.getInstance();
        if ("player=null".equals(fallbackServerSeedState)) {
            fail("Integrated server could not find player for recipe fallback setup");
            return;
        }
        boolean clientInventoryMatches = crafterPlacementOnlyMode
                ? crafterPlacementInventoryMatches(client.player)
                : client.player != null && inventoryCount(client.player.getInventory()) == 0;
        boolean crafterMatrixClientMatches = !crafterMatrixMode
                || client.player != null && client.level != null
                        && client.player.blockPosition().equals(floorOrigin.above())
                        && crafterMatrixStartWorldMatches(client.level, floorOrigin.above())
                        && crafterApproachGeometryReady(client.level, floorOrigin.above())
                        && crafterMatrixServerSetupSnapshot.equals(crafterMatrixWorldSnapshot(
                                client.level, client.player, floorOrigin.above()));
        boolean clientSeedMatches = client.level != null && client.player != null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && craftingGridState(client.player.containerMenu).equals("[]")
                && clientInventoryMatches
                && crafterMatrixClientMatches
                && client.level.getBlockState(floorOrigin.offset(5, 1, 8)).is(Blocks.REDSTONE_ORE);
        if (!fallbackServerSeedValid || !clientSeedMatches) {
            if (crafterMatrixMode && ticks - crafterMatrixLastSyncLogTick >= 20) {
                crafterMatrixLastSyncLogTick = ticks;
                append("CRAFTER_MATRIX_SYNC_WAIT\tindex=" + (crafterMatrixIndex + 1)
                        + "/" + CRAFTER_MATRIX_ORIENTATIONS.length
                        + "\tserverSeedValid=" + fallbackServerSeedValid
                        + "\tclientSeedValid=" + clientSeedMatches
                        + "\tclientMatrixValid=" + crafterMatrixClientMatches
                        + "\tserver=" + crafterMatrixServerSetupSnapshot
                        + "\tclient=" + (client.level == null ? "level=null"
                                : crafterMatrixWorldSnapshot(client.level, client.player, floorOrigin.above())));
            }
            if (ticks - phaseStarted > 100) {
                fail((crafterPlacementOnlyMode ? "Seeded crafter placement inventory" : "Empty-inventory crafter fallback seed")
                        + " did not sync: server=" + fallbackServerSeedState
                        + ", client=" + clientInventoryState());
            }
            return;
        }
        if (crafterPlacementOnlyMode) {
            append("ASSERT\tserver/client inventory contains exactly one stone support and one crafter; no other items; accessible redstone ore starts at "
                    + floorOrigin.offset(5, 1, 8));
        } else {
            append("ASSERT\tserver/client inventory empty; accessible redstone ore starts at "
                    + floorOrigin.offset(5, 1, 8));
        }
        if (crafterBuildMode) {
            startCrafterBuildCommand(client);
            return;
        }
        phase = Phase.FALLBACK;
        phaseStarted = ticks;
        append("COMMAND\t@get crafter 1");
        execute("get crafter 1", () -> {
            Minecraft current = Minecraft.getInstance();
            if (current.player == null || count(current.player, Items.CRAFTER) != 1
                    || !fallbackSmeltSeen || !fallbackCraftingTableSeen
                    || !fallbackDropperSeen || !fallbackCrafterSeen) {
                fail("Vanilla crafter fallback chain incomplete: crafter="
                        + (current.player == null ? "player=null" : count(current.player, Items.CRAFTER))
                        + ", smelt=" + fallbackSmeltSeen + ", craftingTable=" + fallbackCraftingTableSeen
                        + ", dropper=" + fallbackDropperSeen + ", crafterRecipe=" + fallbackCrafterSeen
                        + ", client=" + clientInventoryState());
                return;
            }
            beginFallbackServerVerification();
        });
    }

    private void startCrafterBuildCommand(Minecraft client) {
        crafterBuildOrigin = crafterMatrixMode ? floorOrigin.above() : client.player.blockPosition();
        BlockPos supportTarget = crafterSupportTarget(crafterBuildOrigin);
        BlockPos crafterTarget = crafterTarget(crafterBuildOrigin);
        boolean inventoryReady = crafterPlacementOnlyMode
                ? crafterPlacementInventoryMatches(client.player)
                : client.player != null && inventoryCount(client.player.getInventory()) == 0;
        if (client.player == null || client.level == null
                || !inventoryReady
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !craftingGridState(client.player.containerMenu).equals("[]")
                || !isSurvivalMode(client.player)
                || (crafterMatrixMode && (!crafterMatrixServerSetupValid
                        || !client.player.blockPosition().equals(crafterBuildOrigin)
                        || !crafterMatrixStartWorldMatches(client.level, crafterBuildOrigin)
                        || !crafterApproachGeometryReady(client.level, crafterBuildOrigin)))
                || !client.level.getBlockState(supportTarget).isAir()
                || !client.level.getBlockState(crafterTarget).isAir()
                || (crafterFixtureOrientation().front() != Direction.DOWN
                        && !client.level.getBlockState(supportTarget.below()).isRedstoneConductor(client.level, supportTarget.below()))) {
            fail("Crafter build did not begin from "
                    + (crafterPlacementOnlyMode ? "the exact seeded placement inventory" : "empty survival inventory")
                    + " and a clear supported target: "
                    + "inventory=" + clientInventoryState() + ", support=" + supportTarget + "="
                    + (client.level == null ? "level=null" : client.level.getBlockState(supportTarget))
                    + ", crafter=" + (client.level == null ? "level=null" : client.level.getBlockState(crafterTarget)));
            return;
        }
        fallbackServerSeedValid = false;
        crafterBuildServerPollOutstanding = false;
        crafterBuildServerPollReady = false;
        crafterBuildServerPollPassed = false;
        crafterBuildServerPollState = "not-polled";
        append("CRAFTER_BUILD_SEED\t" + clientInventoryState()
                + "\tredstoneOre=" + floorOrigin.offset(5, 1, 8)
                + "\tsupportTarget=" + supportTarget
                + "\tcrafterTarget=" + crafterTarget
                + "\tpreparedFloorSupport=" + client.level.getBlockState(supportTarget.below())
                + (crafterMatrixMode ? "\torientation=" + crafterFixtureOrientation()
                        + "\tserverPlayer=" + crafterMatrixServerPlayerPosition
                        + "\tserverSetup=" + crafterMatrixServerSetupSnapshot
                        + "\tclientSetup=" + crafterMatrixWorldSnapshot(
                                client.level, client.player, crafterBuildOrigin) : ""));
        if (crafterPlacementOnlyMode) {
            append("ASSERT\tplacement-only regression seeded exactly one stone and one crafter; recursive gathering and crafting are not exercised");
        } else {
            append("ASSERT\tno crafter, dropper, redstone dust, iron ingot, furnace, or other crafted output was preseeded; recursive production resources remain in the prepared arena");
        }
        phase = Phase.FALLBACK;
        phaseStarted = ticks;
        append("COMMAND\t@build crafter-runtime-acceptance.litematic\tfixture=" + crafterBuildGameSchematic);
        execute("build crafter-runtime-acceptance.litematic", () -> {
            phase = Phase.CRAFTER_BUILD_VERIFY;
            phaseStarted = ticks;
            crafterBuildVerifyStarted = ticks;
            crafterBuildServerPollOutstanding = false;
            crafterBuildServerPollReady = false;
            crafterBuildServerPollPassed = false;
            append("ASSERT\tnormal @build command completed; checking "
                    + (crafterPlacementOnlyMode ? "seeded placement regression" : "recipe chain")
                    + ", exact crafter/support/air cells, and clean server/client inventory UI state");
        });
    }

    private net.minecraft.core.FrontAndTop crafterFixtureOrientation() {
        if (crafterMatrixMode) return net.minecraft.core.FrontAndTop.valueOf(
                CRAFTER_MATRIX_ORIENTATIONS[crafterMatrixIndex].toUpperCase(java.util.Locale.ROOT));
        String requested = System.getProperty("altoclef.runtimeCrafterOrientation", "north_up");
        return net.minecraft.core.FrontAndTop.valueOf(requested.toUpperCase(java.util.Locale.ROOT));
    }

    private static final String[] CRAFTER_MATRIX_ORIENTATIONS = {
        "down_east", "down_north", "down_south", "down_west",
        "up_east", "up_north", "up_south", "up_west",
        "east_up", "north_up", "south_up", "west_up"
    };

    private void startCrafterMatrixIteration() {
        if (crafterMatrixIndex >= CRAFTER_MATRIX_ORIENTATIONS.length) {
            fail("Crafter orientation matrix advanced beyond its 12 supported orientations");
            return;
        }
        crafterBuildMode = true;
        crafterPlacementOnlyMode = true;
        crafterMatrixMode = true;
        crafterBuildOrigin = floorOrigin.above();
        try {
            writeCrafterBuildFixture(crafterBuildGameSchematic);
        } catch (IOException error) {
            fail("Could not generate crafter orientation fixture for " + crafterFixtureOrientation() + ": " + error);
            return;
        }
        append("CRAFTER_MATRIX_ORIENTATION_START\t" + (crafterMatrixIndex + 1) + "/"
                + CRAFTER_MATRIX_ORIENTATIONS.length + "\torientation=" + crafterFixtureOrientation()
                + "\torigin=" + crafterBuildOrigin);
        runFallbackSetup();
    }

    private void prepareNextCrafterMatrixIteration() {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            fail("Integrated server disappeared between crafter orientation matrix runs at "
                    + crafterFixtureOrientation());
            return;
        }
        crafterMatrixIndex++;
        if (crafterMatrixIndex >= CRAFTER_MATRIX_ORIENTATIONS.length) {
            append("SUMMARY\tPASS\t26.2 runtimeStart=craftermatrix verified normal @build placement for all 12 supported FrontAndTop orientations using exactly one seeded stone and one seeded crafter per run; exact server/client block states for all fixture cells including AIR and clean cursor, crafting grid, inventory menu, survival, alive, and death screen state verified");
            phase = Phase.DONE;
            writeResult();
            return;
        }
        arenaReady.set(false);
        phase = Phase.WAITING;
        phaseStarted = ticks;
        append("CRAFTER_MATRIX_ARENA_RESET\tcompleted=" + crafterMatrixPassedOrientations.size()
                + "/" + CRAFTER_MATRIX_ORIENTATIONS.length + "\tnext="
                + CRAFTER_MATRIX_ORIENTATIONS[crafterMatrixIndex]);
        crafterMatrixResetSettleUntilTick = ticks + 5;
        server.execute(() -> prepareArena(server));
    }

    private boolean crafterMatrixStartWorldMatches(net.minecraft.world.level.LevelAccessor level,
                                                    BlockPos origin) {
        if (level == null || origin == null) return false;
        for (int y = 0; y < crafterFixtureHeight(); y++) {
            for (int x = 0; x < 5; x++) {
                if (!level.getBlockState(origin.offset(x, y, 0)).isAir()) return false;
            }
        }
        return true;
    }

    private String crafterMatrixWorldSnapshot(net.minecraft.world.level.LevelAccessor level,
                                              Player player, BlockPos origin) {
        if (level == null || origin == null) return "level/origin=null";
        StringBuilder result = new StringBuilder("orientation=").append(crafterFixtureOrientation())
                .append(",player=").append(player == null ? "null" : player.blockPosition())
                .append(",expectedPlayer=").append(floorOrigin.above())
                .append(",support=").append(crafterSupportTarget(origin)).append(':')
                .append(level.getBlockState(crafterSupportTarget(origin)))
                .append(",crafter=").append(crafterTarget(origin)).append(':')
                .append(level.getBlockState(crafterTarget(origin)))
                .append(",fixture=[");
        boolean first = true;
        for (int y = 0; y < crafterFixtureHeight(); y++) {
            for (int x = 0; x < 5; x++) {
                if (!first) result.append(';');
                first = false;
                BlockPos pos = origin.offset(x, y, 0);
                result.append(pos).append('=').append(level.getBlockState(pos));
            }
        }
        result.append("],approach=[");
        first = true;
        for (BlockPos pos : crafterMatrixApproachProbePositions(origin)) {
            if (!first) result.append(';');
            first = false;
            result.append(pos).append('=').append(level.getBlockState(pos));
        }
        return result.append("],geometryReady=")
                .append(crafterApproachGeometryReady(level, origin)).toString();
    }

    private List<BlockPos> crafterMatrixApproachProbePositions(BlockPos origin) {
        BlockPos target = crafterTarget(origin);
        List<BlockPos> positions = new ArrayList<>();
        if (crafterFixtureOrientation().front() == Direction.DOWN) {
            BlockPos feet = target.below(2);
            positions.add(feet);
            positions.add(feet.above());
            positions.add(feet.below());
            for (int y = 0; y <= 2; y++) positions.add(origin.offset(4, y, 1));
        } else if (crafterFixtureOrientation().front() == Direction.UP) {
            Direction side = crafterFixtureOrientation().top().getOpposite();
            BlockPos feet = target.above(2).relative(side, 2);
            positions.add(feet);
            positions.add(feet.above());
            positions.add(feet.below());
            positions.addAll(crafterUpApproachBlocks(origin));
            positions.addAll(crafterUpApproachClearancePositions(origin));
        } else {
            positions.addAll(adris.altoclef.util.helpers.BaritoneBuilderStateCompatibility
                    .crafterApproachPositions(target, crafterFixtureState()));
        }
        return positions.stream().distinct().toList();
    }

    private BlockState crafterFixtureState() {
        return Blocks.CRAFTER.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.ORIENTATION, crafterFixtureOrientation());
    }

    private int crafterFixtureHeight() {
        return crafterFixtureOrientation().front() == Direction.DOWN ? 3 : 2;
    }

    private BlockPos crafterTarget(BlockPos origin) {
        return origin.offset(4, 1, 0);
    }

    private BlockPos crafterSupportTarget(BlockPos origin) {
        return crafterFixtureOrientation().front() == Direction.DOWN
                ? crafterTarget(origin).above() : crafterTarget(origin).below();
    }

    private void prepareCrafterApproachGeometry(ServerLevel level, BlockPos origin) {
        BlockPos target = crafterTarget(origin);
        if (crafterFixtureOrientation().front() == Direction.DOWN) {
            // The native goal stands directly below the crafter. Clear its feet and
            // head cells, provide a lower landing, and give the authored overhead
            // support block a real side face from outside the schematic region.
            BlockPos feet = target.below(2);
            level.setBlock(feet, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), 3);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlock(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            for (int y = 0; y <= 2; y++) {
                level.setBlock(origin.offset(4, y, 1), Blocks.STONE.defaultBlockState(), 3);
            }
            return;
        }
        if (crafterFixtureOrientation().front() != Direction.UP) return;

        // Build a connected three-rise route in an adjacent lane, then join the
        // requested landing. Keeping the lane off z=0 preserves every authored cell
        // for east/west approaches too.
        Direction side = crafterFixtureOrientation().top().getOpposite();
        BlockPos feet = target.above(2).relative(side, 2);
        level.setBlock(feet, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), 3);
        for (BlockPos clearance : crafterUpApproachClearancePositions(origin)) {
            level.setBlock(clearance, Blocks.AIR.defaultBlockState(), 3);
        }
        for (BlockPos step : crafterUpApproachBlocks(origin)) {
            level.setBlock(step, Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 3);
    }

    private List<BlockPos> crafterUpApproachBlocks(BlockPos origin) {
        Direction side = crafterFixtureOrientation().top().getOpposite();
        BlockPos feet = crafterTarget(origin).above(2).relative(side, 2);
        // North/south routes join the landing from the side, leaving the
        // steep downward placement ray over z=+/-1 unobstructed.
        int laneZ = side.getAxis() == Direction.Axis.Z ? side.getStepZ() * 2 : 1;
        int landingX = feet.getX() - origin.getX();
        int firstX = landingX < 4 ? landingX - 2 : 1;
        List<BlockPos> blocks = new ArrayList<>();
        blocks.add(origin.offset(firstX, 0, laneZ));
        blocks.add(origin.offset(firstX + 1, 1, laneZ));
        blocks.add(origin.offset(firstX + 2, 2, laneZ));
        int lastX = firstX + 2;
        int direction = Integer.compare(landingX, lastX);
        for (int x = lastX + direction; direction != 0 && x != landingX + direction; x += direction) {
            blocks.add(origin.offset(x, 2, laneZ));
        }
        return blocks;
    }

    /**
     * Clears the bounded vertical corridor used by UP-facing crafter approaches.
     * The prepared arena's oak stand reaches eight blocks above its floor; remove
     * that overhanging wood over each stair and the landing so Baritone's jump
     * search sees the same unobstructed route that the setup probe validates.
     */
    private List<BlockPos> crafterUpApproachClearancePositions(BlockPos origin) {
        int ceilingY = floorOrigin.getY() + 8;
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos step : crafterUpApproachBlocks(origin)) {
            for (int y = step.getY() + 1; y <= ceilingY; y++) {
                positions.add(new BlockPos(step.getX(), y, step.getZ()));
            }
        }
        BlockPos feet = crafterTarget(origin).above(2)
                .relative(crafterFixtureOrientation().top().getOpposite(), 2);
        for (int y = feet.getY(); y <= ceilingY; y++) {
            positions.add(new BlockPos(feet.getX(), y, feet.getZ()));
        }
        return positions.stream().distinct().toList();
    }

    private boolean crafterApproachGeometryReady(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        BlockPos target = crafterTarget(origin);
        if (crafterFixtureOrientation().front() == Direction.DOWN) {
            BlockPos feet = target.below(2);
            return level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                    && level.getBlockState(feet.below()).isRedstoneConductor(level, feet.below())
                    && level.getBlockState(origin.offset(4, 2, 1)).isRedstoneConductor(level, origin.offset(4, 2, 1));
        }
        if (crafterFixtureOrientation().front() == Direction.UP) {
            Direction side = crafterFixtureOrientation().top().getOpposite();
            BlockPos feet = target.above(2).relative(side, 2);
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()
                    || !level.getBlockState(feet.below()).isRedstoneConductor(level, feet.below())) return false;
            for (BlockPos clearance : crafterUpApproachClearancePositions(origin)) {
                if (!level.getBlockState(clearance).isAir()) return false;
            }
            for (BlockPos step : crafterUpApproachBlocks(origin)) {
                if (!level.getBlockState(step).isRedstoneConductor(level, step)) return false;
            }
            return true;
        }
        return true;
    }

    private BlockState crafterBuildExpectedState(int x, int y) {
        if (x == 4 && y == (crafterFixtureOrientation().front() == Direction.DOWN ? 2 : 0)) return Blocks.STONE.defaultBlockState();
        if (x == 4 && y == 1) return crafterFixtureState();
        return Blocks.AIR.defaultBlockState();
    }

    private boolean crafterBuildFixtureMatches(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        if (level == null || origin == null) return false;
        for (int y = 0; y < crafterFixtureHeight(); y++) {
            for (int x = 0; x < 5; x++) {
                if (!level.getBlockState(origin.offset(x, y, 0)).equals(crafterBuildExpectedState(x, y))) return false;
            }
        }
        return true;
    }

    private boolean crafterBuildPlayerReady(Player player) {
        return player != null && player.isAlive() && player.getHealth() > 0
                && isSurvivalMode(player)
                && player.containerMenu == player.inventoryMenu
                && player.containerMenu.getCarried().isEmpty()
                && craftingGridState(player.containerMenu).equals("[]");
    }

    private void pollCrafterBuildVerification() {
        Minecraft client = Minecraft.getInstance();
        if (crafterBuildServerPollReady) {
            crafterBuildServerPollReady = false;
            append("CRAFTER_BUILD_SERVER_POLL\t" + crafterBuildServerPollState);
            boolean clientPassed = crafterBuildPlayerReady(client.player)
                    && !(client.gui.screen() instanceof DeathScreen)
                    && crafterBuildFixtureMatches(client.level, crafterBuildOrigin);
            boolean chainPassed = crafterPlacementOnlyMode
                    || (crafterBuildGatherStartedEmpty
                            && fallbackSmeltSeen && fallbackCraftingTableSeen
                            && fallbackDropperSeen && fallbackCrafterSeen);
            boolean taskPassed = crafterBuildTask != null && !crafterBuildTask.hasFailed();
            if (crafterBuildServerPollPassed && clientPassed && chainPassed && taskPassed) {
                append("CRAFTER_BUILD_CLIENT_FINAL\t" + clientInventoryState()
                        + "\tcrafter=" + client.level.getBlockState(crafterTarget(crafterBuildOrigin))
                        + "\tsupport=" + client.level.getBlockState(crafterSupportTarget(crafterBuildOrigin))
                        + "\tcells=5x" + crafterFixtureHeight() + " exact including AIR");
                if (crafterPlacementOnlyMode) {
                    append("ASSERT\tseeded placement-only regression placed the crafter and support; client/server match all "
                            + (5 * crafterFixtureHeight()) + " exact cells including AIR; cursor, grid, inventory menu, survival, alive, and death screen verified; recursive gathering and crafting were not exercised");
                    if (crafterMatrixMode) {
                        String orientation = crafterFixtureOrientation().toString();
                        crafterMatrixPassedOrientations.add(orientation);
                        append("CRAFTER_MATRIX_ORIENTATION_PASS\t" + crafterMatrixPassedOrientations.size()
                                + "/" + CRAFTER_MATRIX_ORIENTATIONS.length + "\torientation=" + orientation
                                + "\tcells=" + (5 * crafterFixtureHeight()) + " exact including AIR"
                                + "\tserverClient=true\tcleanUi=true");
                        prepareNextCrafterMatrixIteration();
                        return;
                    }
                    append("SUMMARY\tPASS\t26.2 runtimeStart=crafterplacement verified only the placement regression with exactly one seeded stone and one seeded crafter using normal @build; exact server/client block states and clean UI state verified; no recursive gathering acceptance claimed");
                } else {
                    append("ASSERT\tBuildSchematicTask recursively gathered and crafted the crafter from an empty inventory (iron smelting, crafting table, dropper, crafter recipe traces observed), placed stone support plus crafter, and server/client match all "
                            + (5 * crafterFixtureHeight()) + " cells including AIR; cursor, grid, inventory menu, survival, alive, and death screen verified");
                    append("SUMMARY\tPASS\t26.2 runtimeStart=crafterbuild used normal @build for a generated 2-cell crafter/support schematic from empty inventory and prepared arena resources; recursive vanilla fallback chain produced required crafter without seeded crafted outputs; exact server/client block states and clean UI state verified");
                }
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - crafterBuildVerifyStarted > 100) {
            fail("Crafter schematic build verification failed: server=" + crafterBuildServerPollState
                    + ", client=" + clientInventoryState()
                    + ", gatherStartedEmpty=" + crafterBuildGatherStartedEmpty
                    + ", smelt=" + fallbackSmeltSeen + ", craftingTable=" + fallbackCraftingTableSeen
                    + ", dropper=" + fallbackDropperSeen + ", crafterRecipe=" + fallbackCrafterSeen
                    + ", buildFailed=" + (crafterBuildTask == null ? "task missing" : crafterBuildTask.getFailureReason())
                    + ", fixture=" + (client.level == null ? "level=null"
                    : crafterBuildSnapshot(client.level, crafterBuildOrigin)));
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || crafterBuildServerPollOutstanding) return;
        crafterBuildServerPollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            ServerLevel level = player == null ? null : (ServerLevel) player.level();
            boolean matches = level != null && crafterBuildFixtureMatches(level, crafterBuildOrigin)
                    && crafterBuildPlayerReady(player);
            crafterBuildServerPollPassed = matches;
            crafterBuildServerPollState = player == null ? "player=null"
                    : serverInventoryState(player) + ",alive=" + player.isAlive()
                    + ",health=" + player.getHealth()
                    + ",survival=" + isSurvivalMode(player)
                    + ",fixture=" + crafterBuildSnapshot(level, crafterBuildOrigin)
                    + ",matches=" + matches;
            crafterBuildServerPollOutstanding = false;
            crafterBuildServerPollReady = true;
        });
    }

    private String crafterBuildSnapshot(net.minecraft.world.level.LevelAccessor level, BlockPos origin) {
        if (level == null || origin == null) return "level/origin=null";
        List<String> states = new ArrayList<>();
        for (int y = 0; y < crafterFixtureHeight(); y++) {
            for (int x = 0; x < 5; x++) {
                states.add("(" + x + "," + y + ",0)=" + level.getBlockState(origin.offset(x, y, 0)));
            }
        }
        return states.toString();
    }

    private void observeFallbackTaskTrace(List<String> sequence) {
        boolean craftingTableOutputActive = hasCraftGenericOutput(sequence, "crafting_table");
        boolean dropperOutputActive = hasCraftGenericOutput(sequence, "dropper");
        boolean crafterOutputActive = hasCraftGenericOutput(sequence, "crafter");
        if (!fallbackSmeltSeen && sequence.stream().anyMatch(entry -> entry.contains("SmeltInFurnaceTask"))) {
            fallbackSmeltSeen = true;
            append("ASSERT\tiron prerequisite smelt task observed in recursive crafter trace");
        }
        if (!fallbackCraftingTableSeen && craftingTableOutputActive) {
            fallbackCraftingTableSeen = true;
            append("ASSERT\tfallback crafting_table recipe craft task observed");
        }
        if (!fallbackDropperSeen && dropperOutputActive) {
            fallbackDropperSeen = true;
            append("ASSERT\tfallback dropper recipe craft task observed");
        }
        if (!fallbackCrafterSeen && crafterOutputActive
                && sequence.stream().anyMatch(entry -> entry.contains("CraftInTableTask"))) {
            fallbackCrafterSeen = true;
            append("ASSERT\tvanilla fallback crafter output recipe craft task observed in task trace");
        }
    }

    private boolean hasCraftGenericOutput(List<String> sequence, String outputName) {
        return sequence.stream().anyMatch(entry -> entry.contains("CraftGeneric")
                && entry.toLowerCase(java.util.Locale.ROOT).contains(outputName));
    }

    private void beginFallbackServerVerification() {
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            fail("Integrated server disappeared while verifying crafter fallback result");
            return;
        }
        fallbackVerificationPending = true;
        fallbackVerificationStarted = ticks;
        fallbackPollOutstanding = false;
        fallbackPollReady = false;
        fallbackPollPassed = false;
        fallbackPollState = "waiting for server tick";
        append("FALLBACK_SERVER_VERIFY_START\tcrafter=1, leftovers allowed; inventory menu/cursor/grid must be clear");
    }

    private void pollFallbackServerResult() {
        Minecraft client = Minecraft.getInstance();
        if (fallbackPollReady) {
            fallbackPollReady = false;
            append("FALLBACK_SERVER_POLL\t" + fallbackPollState);
            if (fallbackPollPassed && clientFallbackInventoryReady()) {
                fallbackVerificationPending = false;
                append("ASSERT\tserver and client inventories both contain one crafter; leftover resources allowed; cursor/grid empty");
                append("SUMMARY\tPASS\t26.2 runtimeStart=fallback explicitly skipped diamond/list/build; empty inventory recursively smelted iron and crafted crafting_table, dropper, and fallback crafter; server/client crafter count=1 with leftover tools/resources allowed");
                phase = Phase.DONE;
                writeResult();
                return;
            }
        }
        if (ticks - fallbackVerificationStarted > 100) {
            fallbackVerificationPending = false;
            fail("Server/client crafter inventory did not synchronize within 100 ticks: server="
                    + fallbackPollState + ", client=" + clientInventoryState());
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || fallbackPollOutstanding) return;
        fallbackPollOutstanding = true;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(testPlayer);
            boolean valid = player != null && player.containerMenu == player.inventoryMenu
                    && player.containerMenu.getCarried().isEmpty()
                    && craftingGridState(player.containerMenu).equals("[]")
                    && count(player, Items.CRAFTER) == 1;
            fallbackPollState = (player == null ? "player=null" : serverInventoryState(player))
                    + ",crafter=" + (player == null ? "unknown" : count(player, Items.CRAFTER))
                    + ",grid=" + (player == null ? "unknown" : craftingGridState(player.containerMenu));
            fallbackPollPassed = valid;
            fallbackPollOutstanding = false;
            fallbackPollReady = true;
        });
    }

    private boolean clientFallbackInventoryReady() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.containerMenu == client.player.inventoryMenu
                && client.player.containerMenu.getCarried().isEmpty()
                && craftingGridState(client.player.containerMenu).equals("[]")
                && count(client.player, Items.CRAFTER) == 1;
    }

    private void installRotatedPlacement() throws Exception {
        Class<?> schematicClass = Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic");
        Object schematic = schematicClass.getMethod("createFromFile", Path.class, String.class)
                .invoke(null, gameSchematic.getParent(), gameSchematic.getFileName().toString());
        if (schematic == null) throw new IllegalStateException("Litematica could not load the fixture");
        Class<?> placementClass = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
        BlockPos origin = Minecraft.getInstance().player.blockPosition().offset(0, 0, -8);
        Object placement = placementClass.getMethod("createFor", schematicClass, BlockPos.class,
                String.class, boolean.class, boolean.class).invoke(null, schematic, origin,
                "AltoClef runtime rotated placement", true, true);
        Class<?> consumer = Class.forName("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer");
        placementClass.getMethod("setRotation", net.minecraft.world.level.block.Rotation.class, consumer)
                .invoke(placement, net.minecraft.world.level.block.Rotation.CLOCKWISE_90, null);
        Object manager = Class.forName("fi.dy.masa.litematica.data.DataManager")
                .getMethod("getSchematicPlacementManager").invoke(null);
        @SuppressWarnings("unchecked")
        List<Object> placementsBefore = (List<Object>) manager.getClass()
                .getMethod("getAllSchematicsPlacements").invoke(manager);
        int unrelatedPlacementCount = placementsBefore.size();
        manager.getClass().getMethod("addSchematicPlacement", placementClass, boolean.class)
                .invoke(manager, placement, true);
        @SuppressWarnings("unchecked")
        List<Object> placementsAfter = (List<Object>) manager.getClass()
                .getMethod("getAllSchematicsPlacements").invoke(manager);
        int acceptancePlacementIndex = -1;
        for (int index = 0; index < placementsAfter.size(); index++) {
            if (placementsAfter.get(index) == placement) {
                acceptancePlacementIndex = index;
                break;
            }
        }
        if (acceptancePlacementIndex < 0 || placementsAfter.size() != unrelatedPlacementCount + 1) {
            throw new IllegalStateException("Litematica did not retain the newly added acceptance placement without disturbing existing placements: before="
                    + unrelatedPlacementCount + ", after=" + placementsAfter.size()
                    + ", acceptanceIndex=" + acceptancePlacementIndex);
        }
        if (acceptancePlacementIndex != 0) {
            throw new IllegalStateException("@build placement reads Litematica placement index 0; the acceptance placement was added at index "
                    + acceptancePlacementIndex + " and the existing placement list must remain untouched");
        }
        var snapshot = adris.altoclef.util.schematic.SchematicLoader
                .loadActiveLitematica(acceptancePlacementIndex);
        // Litematica transforms CLOCKWISE_90 as (-z,y,x). This 10x1x1 region therefore
        // extends toward positive Z; its signed size (-1,1,10) needs no origin translation.
        BlockPos expectedTransformedOrigin = origin;
        if (!snapshot.origin().equals(expectedTransformedOrigin)) {
            throw new IllegalStateException("Acceptance placement snapshot used the wrong transformed origin: expected="
                    + expectedTransformedOrigin + ", actual=" + snapshot.origin()
                    + ", placementIndex=" + acceptancePlacementIndex);
        }
        placementSnapshot = snapshot;
        placementTarget = null;
        for (int x = 0; x < snapshot.schematic().widthX(); x++) {
            for (int y = 0; y < snapshot.schematic().heightY(); y++) {
                for (int z = 0; z < snapshot.schematic().lengthZ(); z++) {
                    if (snapshot.schematic().getDirect(x, y, z).is(Blocks.OAK_LOG))
                        placementTarget = snapshot.origin().offset(x, y, z);
                }
            }
        }
        if (placementTarget == null) throw new IllegalStateException("Active adapter omitted the fixture oak log");
        append("PLACEMENT\trotated90\tindex=" + acceptancePlacementIndex
                + "\tunrelatedPreserved=" + unrelatedPlacementCount
                + "\torigin=" + snapshot.origin() + "\ttarget=" + placementTarget);
    }

    private void execute(String command, Runnable onFinish) {
        try {
            var previousCompletion = Debug.jankModInstance.getUserTaskChain().getLastCompletionSnapshot();
            AltoClef.getCommandExecutor().execute(prefix + command, () -> {
                var completion = Debug.jankModInstance.getUserTaskChain().getLastCompletionSnapshot();
                if (completion != null && completion != previousCompletion && completion.failure() != null) {
                    fail("Task failed for " + command + ": " + completion.failure().reason());
                    return;
                }
                if (completion != null && completion != previousCompletion && completion.cancelled()) {
                    fail("Task cancelled for " + command);
                    return;
                }
                append("COMMAND_FINISHED\t" + command);
                onFinish.run();
            }, error -> fail("Command failed: " + error.getMessage()));
        } catch (Throwable t) {
            fail("Command threw: " + t);
        }
    }

    private int count(net.minecraft.world.entity.player.Player player, Item item) {
        if (player == null) return 0;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private void writeLitematicFixture(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef runtime acceptance: one oak log");
        metadata.putString("Author", "AltoClef runtime test");
        metadata.putString("Description", "Generated only by the opt-in 26.2 integration harness.");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putLong("TotalBlocks", 1L);
        metadata.putLong("TotalVolume", 10L);
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", 10);
        enclosing.putInt("y", 1);
        enclosing.putInt("z", 1);
        metadata.put("EnclosingSize", enclosing);
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", 10);
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
        // Litematica stores 2-bit palette indices. The one log is local x=9.
        region.putLongArray("BlockStates", new long[]{1L << 18});
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("RuntimeAcceptance", region);
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private void writeMixedLitematicFixture(Path file) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef runtime acceptance: mixed block states");
        metadata.putString("Author", "AltoClef runtime test");
        metadata.putString("Description", "Stair, closed/left and open/right paired doors, and double slab fixture.");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putLong("TotalBlocks", 6L);
        metadata.putLong("TotalVolume", MIXED_FIXTURE_WIDTH * 2L);
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", MIXED_FIXTURE_WIDTH);
        enclosing.putInt("y", 2);
        enclosing.putInt("z", 1);
        metadata.put("EnclosingSize", enclosing);
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", MIXED_FIXTURE_WIDTH);
        size.putInt("y", 2);
        size.putInt("z", 1);
        region.put("Size", size);

        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        palette.add(stateTag("minecraft:oak_stairs", "facing", "east", "half", "bottom", "shape", "straight", "waterlogged", "false"));
        palette.add(stateTag("minecraft:oak_door", "facing", "north", "half", "lower", "hinge", "left", "open", "false", "powered", "false"));
        palette.add(stateTag("minecraft:oak_door", "facing", "north", "half", "upper", "hinge", "left", "open", "false", "powered", "false"));
        palette.add(stateTag("minecraft:oak_slab", "type", "double", "waterlogged", "false"));
        palette.add(stateTag("minecraft:oak_door", "facing", "north", "half", "lower", "hinge", "right", "open", "true", "powered", "false"));
        palette.add(stateTag("minecraft:oak_door", "facing", "north", "half", "upper", "hinge", "right", "open", "true", "powered", "false"));
        region.put("BlockStatePalette", palette);

        // Compact Litematica bit array: x is the fastest changing coordinate, then z, then y.
        // The isolated open/right door at x=16 has clear neighboring cells for hinge selection.
        // Array order is x-fastest, then z, then y. Door halves use palette entries 2/3 and 5/6.
        int[] indices = new int[MIXED_FIXTURE_WIDTH * 2];
        indices[10] = 1;
        indices[11] = 2;
        indices[12] = 4;
        indices[MIXED_FIXTURE_WIDTH + 11] = 3;
        indices[16] = 5;
        indices[MIXED_FIXTURE_WIDTH + 16] = 6;
        long[] packed = new long[(indices.length * 3 + 63) / 64];
        for (int index = 0; index < indices.length; index++) {
            int bit = index * 3;
            packed[bit >>> 6] |= ((long) indices[index]) << (bit & 63);
            if ((bit & 63) > 61) packed[(bit >>> 6) + 1] |= ((long) indices[index]) >>> (64 - (bit & 63));
        }
        region.putLongArray("BlockStates", packed);
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("RuntimeAcceptanceMixed", region);
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private void writeCrafterBuildFixture(Path file) throws IOException {
        int width = 5;
        int height = crafterFixtureHeight();
        int volume = width * height;
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", 0);

        CompoundTag metadata = new CompoundTag();
        metadata.putString("Name", "AltoClef runtime acceptance: crafter with stone support");
        metadata.putString("Author", "AltoClef runtime test");
        metadata.putString("Description", "Empty-inventory normal @build fixture for the recursive crafter recipe chain.");
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putLong("TotalBlocks", 2L);
        metadata.putLong("TotalVolume", volume);
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", width);
        enclosing.putInt("y", height);
        enclosing.putInt("z", 1);
        metadata.put("EnclosingSize", enclosing);
        root.put("Metadata", metadata);

        CompoundTag region = new CompoundTag();
        CompoundTag position = new CompoundTag();
        position.putInt("x", 0);
        position.putInt("y", 0);
        position.putInt("z", 0);
        region.put("Position", position);
        CompoundTag size = new CompoundTag();
        size.putInt("x", width);
        size.putInt("y", height);
        size.putInt("z", 1);
        region.put("Size", size);

        ListTag palette = new ListTag();
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        palette.add(stateTag(Blocks.STONE.defaultBlockState()));
        palette.add(stateTag(crafterFixtureState()));
        region.put("BlockStatePalette", palette);
        int[] indices = new int[volume];
        indices[(crafterFixtureOrientation().front() == Direction.DOWN ? 2 : 0) * width + 4] = 1; // Local support block.
        indices[width + 4] = 2; // Local (4,1,0): crafter directly above the support.
        int bitsPerBlock = 2;
        long[] packed = new long[(volume * bitsPerBlock + 63) / 64];
        for (int index = 0; index < indices.length; index++) {
            int bit = index * bitsPerBlock;
            packed[bit >>> 6] |= ((long) indices[index]) << (bit & 63);
        }
        region.putLongArray("BlockStates", packed);
        region.put("Entities", new ListTag());
        region.put("TileEntities", new ListTag());
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        CompoundTag regions = new CompoundTag();
        regions.put("CrafterBuild", region);
        root.put("Regions", regions);
        NbtIo.writeCompressed(root, file);
    }

    private CompoundTag stateTag(BlockState state) {
        CompoundTag result = new CompoundTag();
        result.putString("Name", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        CompoundTag properties = new CompoundTag();
        for (Property<?> property : state.getProperties()) {
            putStateProperty(properties, property, state);
        }
        result.put("Properties", properties);
        return result;
    }

    private <T extends Comparable<T>> void putStateProperty(CompoundTag tag, Property<T> property,
                                                            BlockState state) {
        tag.putString(property.getName(), property.getName(state.getValue(property)));
    }

    private CompoundTag stateTag(String name, String... properties) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", name);
        CompoundTag values = new CompoundTag();
        for (int i = 0; i < properties.length; i += 2) values.putString(properties[i], properties[i + 1]);
        state.put("Properties", values);
        return state;
    }

    private void failMobDefense(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        String status = reason != null && (reason.startsWith("EVIDENCE_INCOMPLETE\t")
                || reason.startsWith("EVIDENCE_INCOMPLETE:"))
                ? "EVIDENCE_INCOMPLETE" : "FAIL";
        append("MOB_DEFENSE_CAPACITY_ACCEPTANCE\t" + status + "\t" + reason);
        fail("runtimeStart=mobdefense " + reason);
    }

    private void fail(String reason) {
        if (phase == Phase.FAILED || phase == Phase.DONE) return;
        clearNaturalLogObservation();
        stopTrackingSmeltFurnaceForAcceptance();
        restoreSaplingRandomTickSpeed();
        restoreManualCraftingMode();
        restorePlankFuelMode();
        phase = Phase.FAILED;
        append("SUMMARY\tFAIL\t" + reason);
        Debug.logError("Runtime acceptance test failed: " + reason);
        writeResult();
    }

    private void append(String line) {
        String safe = line.replace('\n', ' ').replace('\r', ' ');
        if (output != null) {
            try {
                Files.writeString(output, safe + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Console and final summary still report a failure if the result path is unavailable.
            }
        }
    }

    private void writeResult() {
        if (resultWritten) return;
        resultWritten = true;
        if (output != null) Debug.logMessage("Runtime acceptance evidence: " + output.toAbsolutePath());
    }

    private void ensureOutput(Minecraft client) {
        if (output != null) return;
        try {
            Path root = client.gameDirectory.toPath().resolve("runtime-test-results");
            Files.createDirectories(root);
            output = root.resolve("altoclef-26.2-" + Instant.now().toEpochMilli() + ".tsv");
            append("HARNESS_START\t26.2\truntimeStart="
                    + System.getProperty("altoclef.runtimeStart", "full"));
            Debug.logMessage("Runtime acceptance evidence: " + output.toAbsolutePath());
        } catch (IOException error) {
            Debug.logError("Could not create runtime acceptance result file: " + error);
        }
    }

    private static final class RuntimeOneTickInterruptChain extends TaskChain {
        private boolean active = true;

        private RuntimeOneTickInterruptChain(TaskRunner runner) {
            super(runner);
        }

        @Override protected void onStop(AltoClef mod) { active = false; }
        @Override public void onInterrupt(AltoClef mod, TaskChain other) { active = false; }
        @Override protected void onTick(AltoClef mod) { active = false; }
        @Override public float getPriority(AltoClef mod) { return 100.0f; }
        @Override public boolean isActive() { return active; }
        @Override public String getName() { return "Runtime Repair Interruption"; }
    }
}
