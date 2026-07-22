package net.minestom.generators;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

final class MinecraftCompatibility {
    private static final Method BLOCKS_MOTION = findMethod(BlockState.class, "blocksMotion");

    private MinecraftCompatibility() {
    }

    static HolderLookup.Provider vanillaLookup() {
        var type = net.minecraft.data.registries.VanillaRegistries.class;
        for (String name : new String[]{"createWorldLookup", "createLookup"}) {
            try {
                return (HolderLookup.Provider) type.getMethod(name).invoke(null);
            } catch (NoSuchMethodException ignored) {
                // Try the other API spelling.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to invoke VanillaRegistries." + name, exception);
            }
        }
        throw new IllegalStateException("No supported vanilla registry lookup factory found");
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
