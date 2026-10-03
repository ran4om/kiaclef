package adris.altoclef.eventbus.events;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ClientRenderEvent {
    public final GuiGraphicsExtractor graphics;
    public final DeltaTracker deltaTracker;

    public ClientRenderEvent(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        this.graphics = graphics;
        this.deltaTracker = deltaTracker;
    }
}
