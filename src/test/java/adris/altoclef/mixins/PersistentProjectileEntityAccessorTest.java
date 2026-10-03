package adris.altoclef.mixins;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.gen.Invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PersistentProjectileEntityAccessorTest {

    @Test
    void inGroundInvokerUsesAnAliasToAvoidOverridingTheTargetMethod() throws NoSuchMethodException {
        var invoker = PersistentProjectileEntityAccessor.class.getDeclaredMethod("invokeIsInGround");
        Invoker annotation = invoker.getAnnotation(Invoker.class);

        assertNotNull(annotation);
        assertEquals("isInGround", annotation.value());
        assertNotEquals(annotation.value(), invoker.getName(),
                "an invoker must not overwrite the target method and recurse into itself");
    }
}
