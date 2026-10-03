package adris.altoclef.util.helpers;

import adris.altoclef.util.helpers.ItemCapabilities;
import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.mixins.AbstractFurnaceScreenHandlerAccessor;
import adris.altoclef.tasks.CraftInInventoryTask;
import adris.altoclef.util.*;
import adris.altoclef.util.slots.*;
import baritone.utils.ToolSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.apache.commons.lang3.ArrayUtils;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Helper functions for interpreting containers/slots/windows/inventory
 */
@SuppressWarnings({"ConstantConditions", "rawtypes"})
public class StorageHelper {

    public static List<PlayerSlot> INACCESSIBLE_PLAYER_SLOTS = Stream.concat(Stream.of(PlayerSlot.CRAFT_INPUT_SLOTS), Stream.of(PlayerSlot.ARMOR_SLOTS)).toList();

    public static void closeScreen() {
        if (Minecraft.getInstance().player == null)
            return;
        Screen screen = Minecraft.getInstance().gui.screen();
        if (
                screen != null &&
                !(screen instanceof PauseScreen) &&
                !(screen instanceof OptionsSubScreen) &&
                !(screen instanceof ChatScreen)) {
            // Close the screen if we're in-game
            Minecraft.getInstance().player.closeContainer();
        }
    }

    public static ItemStack getItemStackInSlot(Slot slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return ItemStack.EMPTY;
        // Cursor slot
        if (Slot.isCursor(slot)) {
            return StorageHelper.getItemStackInCursorSlot();
        }
        // Inventory slot when inventory is NOT open
        Inventory inv = player.getInventory();
        if (inv != null) {
            if (slot.equals(PlayerSlot.OFFHAND_SLOT))
                return player.getOffhandItem().copy();
            if (slot.equals(PlayerSlot.ARMOR_HELMET_SLOT))
                return player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).copy();
            if (slot.equals(PlayerSlot.ARMOR_CHESTPLATE_SLOT))
                return player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).copy();
            if (slot.equals(PlayerSlot.ARMOR_LEGGINGS_SLOT))
                return player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).copy();
            if (slot.equals(PlayerSlot.ARMOR_BOOTS_SLOT))
                return player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).copy();
        }
        try {
            // We might have messed up and opened the wrong slot.
            net.minecraft.world.inventory.Slot mcSlot = player.containerMenu.getSlot(slot.getWindowSlot());
            return (mcSlot != null) ? mcSlot.getItem().copy() : ItemStack.EMPTY;
        } catch (Exception e) {
            Debug.logWarning("Screen Slot Error (ignored)");
            e.printStackTrace();
            return ItemStack.EMPTY;
        }
    }

    public static MiningRequirement getCurrentMiningRequirement(AltoClef mod) {
        MiningRequirement[] order = new MiningRequirement[]{
                MiningRequirement.DIAMOND, MiningRequirement.IRON, MiningRequirement.STONE, MiningRequirement.WOOD
        };
        for (MiningRequirement check : order) {
            if (miningRequirementMet(mod, check)) {
                return check;
            }
        }
        return MiningRequirement.HAND;
    }
    private static boolean h(AltoClef mod, boolean inventoryOnly, Item ...items) {
        if (inventoryOnly) {
            return mod.getItemStorage().hasItemInventoryOnly(items);
        }
        return mod.getItemStorage().hasItem(items);
    }
    private static boolean miningRequirementMetInner(AltoClef mod, boolean inventoryOnly, MiningRequirement requirement) {
        if (requirement == MiningRequirement.HAND) return true;
        for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (requirement.isSatisfiedBy(item) && h(mod, inventoryOnly, item)) return true;
        }
        return false;
    }
    public static boolean miningRequirementMet(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, false, requirement);
    }
    public static boolean miningRequirementMetInventory(AltoClef mod, MiningRequirement requirement) {
        return miningRequirementMetInner(mod, true, requirement);
    }

    public static Optional<Slot> getBestToolSlot(AltoClef mod, BlockState state) {
        // TODO: mod.configState.silkTouchOverrideMode {
        //      DONT_CARE (Default)
        //      PREFER (Always use silk touch if we have)
        //      AVOID  (Don't use silk touch if we can)
        //  }
        Slot bestToolSlot = null;
        double highestSpeed = Double.NEGATIVE_INFINITY;

        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (!slot.isSlotInPlayerInventory())
                continue;
            ItemStack stack = getItemStackInSlot(slot);
            if (mod.getBehaviour().shouldAvoidUseTool(state, stack)) {
                continue;
            }
            if (ItemCapabilities.isTool(stack.getItem())) {
                if (stack.isCorrectToolForDrops(state)) {
                    double speed = ToolSet.calculateSpeedVsBlock(stack, state);
                    if (speed > highestSpeed) {
                        highestSpeed = speed;
                        bestToolSlot = slot;
                    }
                }
            }
            if (stack.getItem() == Items.SHEARS) {
                // Shears take priority over leaf blocks.
                if (ItemHelper.areShearsEffective(state.getBlock())) {
                    bestToolSlot = slot;
                    break;
                }
            }
        }
        return Optional.ofNullable(bestToolSlot);
    }

    /**
     * Selects a safe hand slot if the currently held stack is forbidden for this block.
     * Empty inventory slots are preferred so that breaking uses the player's bare hand.
     */
    public static Optional<Slot> getSafeHandSlot(AltoClef mod, BlockState state) {
        Slot equipped = PlayerSlot.getEquipSlot();
        if (!mod.getBehaviour().shouldAvoidUseTool(state, getItemStackInSlot(equipped))) {
            return Optional.empty();
        }

        List<Slot> candidates = new ArrayList<>(36);
        // Prefer an empty hotbar slot, then an empty inventory slot, then any safe stack.
        for (int inventorySlot = 0; inventorySlot < 36; inventorySlot++) {
            if (inventorySlot < 9) {
                candidates.add(Slot.getFromCurrentScreenInventory(inventorySlot));
            }
        }
        for (int inventorySlot = 9; inventorySlot < 36; inventorySlot++) {
            candidates.add(Slot.getFromCurrentScreenInventory(inventorySlot));
        }

        return chooseSafeFallback(candidates, StorageHelper::getItemStackInSlot,
                stack -> mod.getBehaviour().shouldAvoidUseTool(state, stack));
    }

    static <T> Optional<T> chooseSafeFallback(Iterable<T> candidates,
                                               Function<T, ItemStack> getStack,
                                               Predicate<ItemStack> shouldAvoid) {
        T firstSafeStack = null;
        for (T candidate : candidates) {
            ItemStack stack = getStack.apply(candidate);
            if (shouldAvoid.test(stack)) continue;
            if (stack.isEmpty()) return Optional.of(candidate);
            if (firstSafeStack == null) firstSafeStack = candidate;
        }
        return Optional.ofNullable(firstSafeStack);
    }

    public static boolean shouldReplaceEquippedTool(ItemStack current, ItemStack replacement) {
        return !ItemStack.isSameItemSameComponents(current, replacement);
    }

    // Gets a slot with an item we can throw away
    public static Optional<Slot> getGarbageSlot(AltoClef mod) {
        // Throwaway items, but keep a few for building.
        final List<Slot> throwawayBlockItems = new ArrayList<>();
        int totalBlockThrowaways = 0;
        for (Slot slot : mod.getItemStorage().getSlotsWithItemPlayerInventory(false, mod.getModSettings().getThrowawayItems(mod))) {
            // Our cursor slot is NOT a garbage slot
            if (Slot.isCursor(slot))
                continue;
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!ItemHelper.canThrowAwayStack(mod, stack))
                continue;
            if (stack.getItem() instanceof BlockItem) {
                totalBlockThrowaways += stack.getCount();
                throwawayBlockItems.add(slot);
            } else {
                // Throw away this non-block immediately.
                return Optional.of(slot);
            }
        }
        if (!throwawayBlockItems.isEmpty() && totalBlockThrowaways > mod.getModSettings().getReservedBuildingBlockCount()) {
            return Optional.of(throwawayBlockItems.get(0));
        }

        // Try throwing away lower tier tools
        final HashMap<String, Integer> bestMaterials = new HashMap<>();
        final HashMap<String, Slot> bestTool = new HashMap<>();
        for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
            ItemStack stack = StorageHelper.getItemStackInSlot(slot);
            if (!ItemHelper.canThrowAwayStack(mod, stack))
                continue;
            Item item = stack.getItem();
            if (ItemCapabilities.isTool(item)) {
                String c = ItemCapabilities.toolKind(item);
                int level = ItemCapabilities.miningLevel(item);
                int prevBest = bestMaterials.getOrDefault(c, 0);
                if (level > prevBest) {
                    // We had a WORSE tool before.
                    if (bestTool.containsKey(c)) {
                        return Optional.of(bestTool.get(c));
                    }
                    bestMaterials.put(c, level);
                    bestTool.put(c, slot);
                } else if (level < prevBest) {
                    // We found something WORSE!
                    return Optional.of(slot);
                }
            }
        }

        // Now we're getting desparate
        if (mod.getModSettings().shouldThrowawayUnusedItems()) {

            // Also uh calculate how much food we have.
            int calcTotalFoodScore = 0;

            // Get all non-important items. For now there is no measure of value.
            final List<Slot> possibleSlots = new ArrayList<>();
            for (Slot slot : PlayerSlot.getCurrentScreenSlots()) {
                ItemStack stack = StorageHelper.getItemStackInSlot(slot);
                // If we're an armor slot, don't count us.
                if (slot instanceof PlayerSlot playerSlot) {
                    if (ArrayUtils.contains(PlayerSlot.ARMOR_SLOTS, playerSlot)) {
                        continue;
                    }
                }
                // Throw away-able slots are good!
                if (ItemHelper.canThrowAwayStack(mod, stack)) {
                    possibleSlots.add(slot);
                }
                if (ItemCapabilities.isFood(stack.getItem())) {
                    calcTotalFoodScore += Objects.requireNonNull(ItemCapabilities.food(stack.getItem())).nutrition();
                }
            }

            final int totalFoodScore = calcTotalFoodScore;

            if (!possibleSlots.isEmpty()) {
                return possibleSlots.stream().min((leftSlot, rightSlot) -> {
                    ItemStack left = StorageHelper.getItemStackInSlot(leftSlot),
                            right = StorageHelper.getItemStackInSlot(rightSlot);
                    boolean leftIsTool = ItemCapabilities.isTool(left.getItem());
                    boolean rightIsTool = ItemCapabilities.isTool(right.getItem());
                    // Prioritize tools over materials.
                    if (rightIsTool && !leftIsTool) {
                        return -1;
                    } else if (leftIsTool && !rightIsTool) {
                        return 1;
                    }
                    if (rightIsTool && leftIsTool) {
                        // Prioritize material type, then durability.
                        Item leftTool = left.getItem();
                        Item rightTool = right.getItem();
                        if (ItemCapabilities.miningLevel(leftTool) != ItemCapabilities.miningLevel(rightTool)) {
                            return ItemCapabilities.miningLevel(leftTool) - ItemCapabilities.miningLevel(rightTool);
                        }
                        // We want less damage.
                        return left.getDamageValue() - right.getDamageValue();
                    }

                    // Prioritize food over other things if we lack food.
                    boolean lacksFood = totalFoodScore < 8;
                    boolean leftIsFood = ItemCapabilities.isFood(left.getItem()) && left.getItem() != Items.SPIDER_EYE;
                    boolean rightIsFood = ItemCapabilities.isFood(right.getItem()) && right.getItem() != Items.SPIDER_EYE;
                    if (lacksFood) {
                        if (rightIsFood && !leftIsFood) {
                            return -1;
                        } else if (leftIsFood && !rightIsFood) {
                            return 1;
                        }
                    }
                    // If both are food, pick the better cost.
                    if (leftIsFood && rightIsFood) {
                        assert ItemCapabilities.food(left.getItem()) != null;
                        assert ItemCapabilities.food(right.getItem()) != null;
                        int leftCost = ItemCapabilities.food(left.getItem()).nutrition() * left.getCount(),
                                rightCost = ItemCapabilities.food(right.getItem()).nutrition() * right.getCount();
                        return -1 * (leftCost - rightCost);
                    }

                    // Just discard the one with the smallest quantity, but this doesn't really matter.
                    return left.getCount() - right.getCount();
                });
            } else {
                Debug.logWarning("No unused items to throw away found. Every item is protected.");
            }
        }
        return Optional.empty();
    }

    /**
     * @return whether EVERY item target in {@code targetsToMeet} is met in our inventory or conversion slots.
     */
    public static boolean itemTargetsMet(AltoClef mod, ItemTarget ...targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCount(target.getMatches()) >= target.getTargetCount());
    }

    /**
     * AVOID using this unless it's the end goal to keep an item in our inventory.
     * @return whether EVERY item target in {@code targetsToMeet} is strictly in our inventory.
     */
    public static boolean itemTargetsMetInventory(AltoClef mod, ItemTarget ...targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target -> mod.getItemStorage().getItemCountInventoryOnly(target.getMatches()) >= target.getTargetCount());
    }

    /**
     * Same as {@code itemTargetsMetInventory} but it ignores the cursor slot.
     */
    public static boolean itemTargetsMetInventoryNoCursor(AltoClef mod, ItemTarget ...targetsToMeet) {
        return itemTargetsMetAccessibleInventory(mod, targetsToMeet);
    }

    /**
     * Checks whether each target is present in regular player inventory slots, excluding
     * the cursor and the 2x2 player crafting grid.
     */
    public static boolean itemTargetsMetAccessibleInventory(AltoClef mod, ItemTarget ...targetsToMeet) {
        return Arrays.stream(targetsToMeet).allMatch(target ->
                getAccessibleInventoryItemCount(mod, target) >= target.getTargetCount());
    }

    /**
     * Counts matching items that remain available in the player's ordinary inventory.
     * Items on the cursor or in the 2x2 crafting grid are excluded because a resource
     * goal must finish with its materials stored in accessible inventory slots.
     */
    public static int getAccessibleInventoryItemCount(AltoClef mod, ItemTarget target) {
        int inventoryCount = mod.getItemStorage().getItemCountInventoryOnly(target.getMatches());
        ItemStack cursorStack = getItemStackInCursorSlot();
        ItemStack[] craftingGrid = new ItemStack[0];
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof InventoryMenu) {
            craftingGrid = Arrays.stream(PlayerSlot.CRAFT_INPUT_SLOTS)
                    .map(StorageHelper::getItemStackInSlot)
                    .toArray(ItemStack[]::new);
        }
        return getAccessibleInventoryItemCount(inventoryCount, target, cursorStack, craftingGrid);
    }

    static int getAccessibleInventoryItemCount(int inventoryCount, ItemTarget target,
                                               ItemStack cursorStack, ItemStack... craftingGrid) {
        int accessibleCount = inventoryCount;
        if (target.matches(cursorStack.getItem())) {
            accessibleCount -= cursorStack.getCount();
        }
        for (ItemStack stack : craftingGrid) {
            if (stack != null && !stack.isEmpty() && target.matches(stack.getItem())) {
                accessibleCount -= stack.getCount();
            }
        }
        return Math.max(0, accessibleCount);
    }

    public static boolean isArmorEquipped(AltoClef mod, Item ...any) {
        for (Item item : any) {
            if (ItemCapabilities.isArmor(item)) {
                ItemStack equippedStack = mod.getPlayer().getItemBySlot(ItemCapabilities.equipmentSlot(item));
                if (equippedStack.getItem().equals(item))
                    return true;
            }
        }
        return false;
    }


    public static int getBuildingMaterialCount(AltoClef mod) {
        return mod.getItemStorage().getItemCount(Arrays.stream(mod.getModSettings().getThrowawayItems(mod, true)).filter(item -> item instanceof BlockItem).toArray(Item[]::new));
    }

    private static boolean isScreenOpenInner(Predicate<AbstractContainerMenu> pNotNull) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null)
            return pNotNull.test(player.containerMenu);
        return false;
    }

    public static boolean isBigCraftingOpen() {
        return isScreenOpenInner(screen -> screen instanceof CraftingMenu);
    }
    public static boolean isPlayerInventoryOpen() {
        return isScreenOpenInner(screen -> screen instanceof InventoryMenu);
    }
    public static boolean isFurnaceOpen() {
        return isScreenOpenInner(screen -> screen instanceof AbstractFurnaceMenu);
    }

    public static boolean isArmorEquippedAll(AltoClef mod, Item ...items) {
        return Arrays.stream(items).allMatch(item -> isArmorEquipped(mod, item));
    }

    public static boolean isEquipped(AltoClef mod, Item ...items) {
        return ArrayUtils.contains(items, StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem());
    }

    public static int calculateInventoryFoodScore(AltoClef mod) {
        int result = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (ItemCapabilities.isFood(stack.getItem()))
                result += Objects.requireNonNull(ItemCapabilities.food(stack.getItem())).nutrition() * stack.getCount();
        }
        return result;
    }
    public static double calculateInventoryFuelCount(AltoClef mod) {
        double result = 0;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(true)) {
            if (mod.getModSettings().isSupportedFuel(stack.getItem())) {
                result += ItemHelper.getFuelAmount(stack.getItem()) * stack.getCount();
            }
        }
        return result;
    }

    /**
     * Returns whether we have the items in our inventory (or currently crafting)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hasRecipeMaterialsOrTarget(AltoClef mod, RecipeTarget... targets) {
        HashMap<Integer, Integer> slotUsedCounts = new HashMap<>();
        for (RecipeTarget target : targets) {
            CraftingRecipe recipe = target.getRecipe();
            int need = 0;
            if (target.getOutputItem() != null) {
                need = target.getTargetCount();
                need -= mod.getItemStorage().getItemCount(target.getOutputItem());
            }
            // need holds how many items we need to CRAFT
            // However, a crafting recipe can output more than 1 of an item.
            int materialsPerSlotNeeded = (int) Math.ceil((float) need / target.getRecipe().outputCount());
            for (int i = 0; i < materialsPerSlotNeeded; ++i) {
                for (int slot = 0; slot < recipe.getSlotCount(); ++slot) {
                    ItemTarget needs = recipe.getSlot(slot);

                    // Satisfied by default.
                    if (needs == null || needs.isEmpty()) continue;

                    // do NOT include craft or armor slots. This would include the OUTPUT (which we DO NOT want)
                    List<Slot> slotsWithItem = mod.getItemStorage().getSlotsWithItemPlayerInventory(false, needs.getMatches());

                    // Other slots may have our crafting supplies.
                    AbstractContainerMenu screen = mod.getPlayer().containerMenu;
                    if (screen instanceof InventoryMenu || screen instanceof CraftingMenu) {
                        // Check crafting slots
                        boolean bigCrafting = (screen instanceof CraftingMenu);
                        boolean bigRecipe = recipe.isBig();
                        for (int craftSlotIndex = 0; craftSlotIndex < (bigCrafting ? 9 : 4); ++craftSlotIndex) {
                            Slot craftSlot = bigCrafting ? CraftingTableSlot.getInputSlot(craftSlotIndex, bigRecipe) : PlayerSlot.getCraftInputSlot(craftSlotIndex);
                            ItemStack stack = StorageHelper.getItemStackInSlot(craftSlot);
                            if (needs.matches(stack.getItem())) {
                                slotsWithItem.add(craftSlot);
                            }
                        }
                    }

                    // Try to satisfy THIS slot.
                    boolean satisfied = false;
                    for (Slot checkSlot : slotsWithItem) {
                        int windowSlot = checkSlot.getWindowSlot();
                        if (!slotUsedCounts.containsKey(windowSlot)) {
                            slotUsedCounts.put(windowSlot, 0);
                        }
                        int usedFromSlot = slotUsedCounts.get(windowSlot);
                        ItemStack stack = StorageHelper.getItemStackInSlot(checkSlot);

                        if (usedFromSlot < stack.getCount()) {
                            slotUsedCounts.put(windowSlot, slotUsedCounts.get(windowSlot) + 1);
                            //Debug.logMessage("Satisfied " + slot + " with " + checkInvSlot);
                            satisfied = true;
                            break;
                        }
                    }

                    if (!satisfied) {
                        //Debug.logMessage("FAILED TO SATISFY " + slot + " : needs " + needs);
                        // We couldn't satisfy this slot in either the inventory or crafting output.
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static boolean hasCataloguedItem(AltoClef mod, String cataloguedName) {
        return mod.getItemStorage().hasItem(TaskCatalogue.getItemMatches(cataloguedName));
    }

    /**
     * There are slots in our inventory that can't be accessed by containers
     *
     * Mainly the crafting + armor + shield slot.
     *
     * @return A slot of {@code withItem} that is inaccessible to open containers, or {@code Optional.empty} if there
     * are none.
     */
    public static Optional<Slot> getFilledInventorySlotInaccessibleToContainer(AltoClef mod, ItemTarget withItem) {
        // First check if we have anything within our regular inventory.
        if (!StorageHelper.isPlayerInventoryOpen() || withItem.isEmpty()) {
            return Optional.empty();
        }
        int accessibleCount = getAccessibleInventoryItemCount(mod, withItem);
        if (accessibleCount >= withItem.getTargetCount()) return Optional.empty();
        // Then check our "invalid" slots for our item.
        for (Slot slot : INACCESSIBLE_PLAYER_SLOTS) {
            ItemStack stack = getItemStackInSlot(slot);
            if (shouldMoveInaccessibleSlot(withItem.matches(stack.getItem()),
                    isActiveConversionSlot(mod, slot, withItem))) {
                return Optional.of(slot);
            }
        }
        // Consider Cursor slot only if we have our player inventory open AND we're not crafting it...
        ItemStack cursorStack = getItemStackInCursorSlot();
        if (StorageHelper.isPlayerInventoryOpen()
                && shouldMoveInaccessibleSlot(withItem.matches(cursorStack.getItem()),
                isActiveConversionSlot(mod, CursorSlot.SLOT, withItem))) {
            if (!mod.getUserTaskChain().getCurrentTask().thisOrChildSatisfies(task -> {
                if (task instanceof CraftInInventoryTask invCraft) {
                    return withItem.matches(invCraft.getRecipeTarget().getOutputItem());
                }
                return false;
            })) {
                return Optional.of(CursorSlot.SLOT);
            }
        }
        return Optional.empty();
    }

    private static boolean isActiveConversionSlot(AltoClef mod, Slot slot, ItemTarget target) {
        return mod.getBehaviour().getConversionSlots().stream().anyMatch(pair -> {
            Slot conversionSlot = pair.getLeft();
            if (!conversionSlot.equals(slot) || !conversionSlot.isScreenOpen()) return false;
            ItemStack stack = getItemStackInSlot(conversionSlot);
            return target.matches(stack.getItem()) && pair.getRight().test(stack);
        });
    }

    static boolean shouldMoveInaccessibleSlot(boolean matchesTarget, boolean activeConversion) {
        return matchesTarget && !activeConversion;
    }

    public static boolean isItemInaccessibleToContainer(AltoClef mod, ItemTarget item) {
        return getFilledInventorySlotInaccessibleToContainer(mod, item).isPresent();
    }

    public static ItemStack getItemStackInCursorSlot() {
        if (Minecraft.getInstance().player != null) {
            if (Minecraft.getInstance().player.containerMenu != null) {
                return Minecraft.getInstance().player.containerMenu.getCarried().copy();
            }
        }
        return ItemStack.EMPTY;
    }

    public static int getBrewingStandFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof BrewingStandMenu stand)
            return getBrewingStandFuel(stand);
        return -1;
    }

    public static int getBrewingStandFuel(BrewingStandMenu handler) {
        return handler.getFuel();
    }

    public static double getFurnaceFuel(AbstractFurnaceMenu handler) {
        ContainerData d = ((AbstractFurnaceScreenHandlerAccessor) handler).getPropertyDelegate();
        return (double) d.get(0) / 200.0;
    }
    public static double getFurnaceFuel() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu furnace)
            return getFurnaceFuel(furnace);
        return -1;
    }
    public static double getFurnaceCookPercent(AbstractFurnaceMenu handler) {
        // Modern getBurnProgress already returns cookTime / totalCookTime in [0, 1].
        return handler.getBurnProgress();
    }
    public static double getFurnaceCookPercent() {
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu furnace)
            return getFurnaceCookPercent(furnace);
        return -1;
    }

    public static ItemTarget[] getAllInventoryItemsAsTargets(Predicate<Slot> accept) {
        HashMap<Item, Integer> counts = new HashMap<>();
        for (Slot slot : Slot.getCurrentScreenSlots()) {
            if (slot.isSlotInPlayerInventory() && accept.test(slot)) {
                ItemStack stack = getItemStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.put(stack.getItem(), counts.getOrDefault(stack.getItem(), 0) + stack.getCount());
                }
            }
        }
        ItemTarget[] results = new ItemTarget[counts.size()];
        int i = 0;
        for (Item item : counts.keySet()) {
            results[i++] = new ItemTarget(item, counts.get(item));
        }
        return results;
    }

    public static boolean instantFillRecipeViaBook(AltoClef mod, CraftingRecipe recipe, Item output, boolean craftAll) {
        Optional<RecipeDisplayId> recipeToSend = JankCraftingRecipeMapping.getMinecraftMappedRecipe(recipe, output);
        if (recipeToSend.isPresent()) {
            mod.getController().handlePlaceRecipe(Minecraft.getInstance().player.containerMenu.containerId, recipeToSend.get(), craftAll);
            return true;
        } else {
            return false;
        }
    }
}
