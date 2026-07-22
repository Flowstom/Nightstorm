package net.minestom.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryDataLoader;
import net.minestom.datagen.DataGenerator;

import java.util.ArrayList;

public final class SynchronizedRegistryGenerator extends DataGenerator {
    @Override
    public JsonObject generate() {
        var result = new JsonObject();
        var registries = new JsonArray();
        HolderLookup.Provider lookup = MinecraftCompatibility.vanillaLookup();
        for (var registryData : RegistryDataLoader.SYNCHRONIZED_REGISTRIES) {
            registries.add(generateRegistry(lookup, registryData));
        }
        result.add("registries", registries);
        return result;
    }

    private static <T> JsonObject generateRegistry(HolderLookup.Provider provider,
                                                    RegistryDataLoader.RegistryData<T> registryData) {
        HolderLookup.RegistryLookup<T> lookup = provider.lookupOrThrow(registryData.key());
        var nbtOps = provider.createSerializationContext(NbtOps.INSTANCE);
        var result = new JsonObject();
        result.addProperty("id", registryData.key().identifier().toString());

        var entries = new JsonArray();
        var elements = lookup.listElements().toList();
        for (int index = 0; index < elements.size(); index++) {
            Holder.Reference<T> holder = elements.get(index);
            Tag encoded = registryData.elementCodec().encodeStart(nbtOps, holder.value()).getOrThrow();
            var entry = new JsonObject();
            entry.addProperty("id", holder.key().identifier().toString());
            entry.addProperty("snbt", new SnbtPrinterTagVisitor("", 0, new ArrayList<>()).visit(encoded));
            entries.add(entry);
        }
        result.add("entries", entries);

        var tags = new JsonArray();
        result.add("tags", tags);
        return result;
    }
}
