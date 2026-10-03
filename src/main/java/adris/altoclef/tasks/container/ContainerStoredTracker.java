package adris.altoclef.tasks.container;

import adris.altoclef.AltoClef;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.SlotClickChangedEvent;
import adris.altoclef.trackers.storage.ContainerType;
import adris.altoclef.util.ItemTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import adris.altoclef.util.helpers.StorageHelper;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

public class ContainerStoredTracker {
    private final HashMap<Item, Integer> _totalDeposited = new HashMap<>();
    private final Predicate<SlotClickChangedEvent> _acceptDeposit;

    private Subscription<SlotClickChangedEvent> _slotClickChangedSubscription;
    private boolean _tracking;

    public ContainerStoredTracker(Predicate<SlotClickChangedEvent> acceptDeposit) {
        _acceptDeposit = acceptDeposit;
    }

    private void trackChange(Item item, int delta) {
        _totalDeposited.put(item, _totalDeposited.getOrDefault(item, 0) + delta);
    }

    public void startTracking() {
        if (_tracking) return;
        _slotClickChangedSubscription = EventBus.subscribe(SlotClickChangedEvent.class, evt -> {
            if (shouldCountDeposit(evt.menu().getClass(), evt.playerInventorySlot(), _acceptDeposit.test(evt))) {
                ItemStack before = evt.before();
                ItemStack after = evt.after();
                if (before.getItem() != after.getItem()) {
                    // Before has been replaced! We lost before and added all of after.
                    if (!before.isEmpty())
                        recordChange(before.getItem(), -1 * before.getCount());
                    if (!after.isEmpty())
                        recordChange(after.getItem(), after.getCount());
                } else {
                    // Before and after are the same, track the difference.
                    recordChange(after.getItem(), after.getCount() - before.getCount());
                }
            }
        });
        _tracking = true;
    }

    static boolean shouldCountDeposit(Class<?> menuClass, boolean playerInventorySlot,
                                      boolean acceptedByTask) {
        return ContainerType.isStorageMenu(menuClass) && !playerInventorySlot && acceptedByTask;
    }

    static boolean isConfiguredContainerBlock(Block openedBlock, Block[] allowedBlocks) {
        return Arrays.stream(allowedBlocks).anyMatch(block -> block == openedBlock);
    }

    static boolean acceptsBoundContainer(Optional<BlockPos> boundPosition,
                                         Predicate<BlockPos> acceptsPosition) {
        return boundPosition.filter(acceptsPosition).isPresent();
    }

    void recordChange(Item item, int delta) {
        trackChange(item, delta);
    }

    void resetForNewRun() {
        _totalDeposited.clear();
    }

    public void stopTracking() {
        if (!_tracking) return;
        EventBus.unsubscribe(_slotClickChangedSubscription);
        _slotClickChangedSubscription = null;
        _tracking = false;
    }

    /**
     * How many client-predicted items have been added to containers satisfying our conditions?
     */
    public int getStoredCount(Item ...items) {
        int result = 0;
        for (Item item : items) {
            result += _totalDeposited.getOrDefault(item, 0);
        }
        return result;
    }

    static int remainingAvailableCount(int requestedCount, int storedCount, int availableCount) {
        return Math.max(0, Math.min(requestedCount - storedCount, availableCount));
    }

    static int accessibleItemCount(int inventoryCount, int cursorCount, boolean cursorMatches) {
        return inventoryCount + (cursorMatches ? cursorCount : 0);
    }

    public boolean matches(ItemTarget target) {
        return getStoredCount(target.getMatches()) >= target.getTargetCount();
    }

    public ItemTarget[] getUnstoredItemTargetsYouCanStore(AltoClef mod, ItemTarget[] toStore) {
        return Arrays.stream(toStore)
                .map(target -> {
                    ItemStack cursor = StorageHelper.getItemStackInCursorSlot();
                    int accessible = accessibleItemCount(mod.getItemStorage().getItemCount(target),
                            cursor.getCount(), target.matches(cursor.getItem()));
                    return new ItemTarget(target, remainingAvailableCount(
                            target.getTargetCount(), getStoredCount(target.getMatches()), accessible));
                })
                .filter(target -> target.getTargetCount() > 0)
                .toArray(ItemTarget[]::new);
    }
}
