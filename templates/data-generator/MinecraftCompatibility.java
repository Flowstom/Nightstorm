package net.minestom.generators;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

final class MinecraftCompatibility {
    private static final Method BLOCKS_MOTION = findMethod(BlockState.class, "blocksMotion");

    private MinecraftCompatibility() {
    }

    static HolderLookup.Provider vanillaLookup() {
        var type = net.minecraft.data.registries.VanillaRegistries.class;
        var candidates = Arrays.stream(type.getMethods()).filter(method -> Modifier.isStatic(method.getModifiers())
                && method.getParameterCount() == 0 && method.getReturnType().equals(HolderLookup.Provider.class)).toList();
        if (candidates.size() != 1) throw new IllegalStateException("Cannot identify a unique vanilla registry lookup factory: " + candidates);
        try {
            return (HolderLookup.Provider) candidates.getFirst().invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke vanilla registry lookup factory", exception);
        }
    }

    static boolean blocksMotion(BlockState state) {
        if (BLOCKS_MOTION == null) return state.isSolid();
        try {
            return (boolean) BLOCKS_MOTION.invoke(state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to inspect block motion", exception);
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
