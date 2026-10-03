package adris.altoclef.util;

/** A pair of values used by event subscriptions and inventory bookkeeping. */
public record Pair<L, R>(L left, R right) {
    public L getLeft() { return left; }
    public R getRight() { return right; }
    public L getA() { return left; }
    public R getB() { return right; }
}
