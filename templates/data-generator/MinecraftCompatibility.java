package net.minestom.generators;

import net.minecraft.core.HolderLookup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

final class MinecraftCompatibility {

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

}
