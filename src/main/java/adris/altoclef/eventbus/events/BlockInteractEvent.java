package adris.altoclef.eventbus.events;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.BlockHitResult;

public class BlockInteractEvent {
    public BlockHitResult hitResult;
    public ClientLevel world;

    public BlockInteractEvent(BlockHitResult hitResult, ClientLevel world) {
        this.hitResult = hitResult;
        this.world = world;
    }
}
