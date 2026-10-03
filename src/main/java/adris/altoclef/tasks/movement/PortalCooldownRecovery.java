package adris.altoclef.tasks.movement;

/** Tracks the consecutive outside-portal ticks needed to clear vanilla's portal cooldown. */
final class PortalCooldownRecovery {
    private final int requiredOutsideTicks;
    private boolean active;
    private int outsideTicks;

    PortalCooldownRecovery(int requiredOutsideTicks) {
        if (requiredOutsideTicks < 1) throw new IllegalArgumentException("requiredOutsideTicks must be positive");
        this.requiredOutsideTicks = requiredOutsideTicks;
    }

    PortalCooldownRecovery() {
        this(20);
    }

    void begin() {
        active = true;
        outsideTicks = 0;
    }

    /**
     * Records one game tick. Returns true once cooldown recovery has completed.
     */
    boolean tick(boolean intersectsPortal) {
        if (!active) return false;
        if (intersectsPortal) {
            outsideTicks = 0;
            return false;
        }
        outsideTicks++;
        return outsideTicks >= requiredOutsideTicks;
    }

    boolean isActive() {
        return active;
    }

    int outsideTicks() {
        return outsideTicks;
    }

    void reset() {
        active = false;
        outsideTicks = 0;
    }
}
