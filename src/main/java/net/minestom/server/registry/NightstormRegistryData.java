package net.minestom.server.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.data.MinestomData;
import net.minestom.server.adventure.MinestomAdventure;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

final class NightstormRegistryData {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<RawRegistry> REGISTRIES = load();

    private NightstormRegistryData() {
    }

    static void appendPackets(List<SendablePacket> packets, List<DynamicRegistry<?>> known,
                              boolean excludeVanilla) {
        var knownKeys = new HashSet<String>();
        known.forEach(registry -> knownKeys.add(registry.key().asString()));
        for (var registry : REGISTRIES) {
            if (knownKeys.contains(registry.id())) continue;
            var entries = registry.entries().stream()
                    .map(entry -> new RegistryDataPacket.Entry(entry.id(), excludeVanilla ? null : entry.data()))
                    .toList();
            packets.add(new RegistryDataPacket(registry.id(), entries));
        }
    }

    static void appendTags(List<TagsPacket.Registry> tags, List<Registry<?>> known) {
        var knownKeys = new HashSet<String>();
        known.forEach(registry -> knownKeys.add(registry.key().asString()));
        for (var registry : REGISTRIES) {
            if (knownKeys.contains(registry.id())) continue;
            tags.add(new TagsPacket.Registry(registry.id(), registry.tags()));
        }
    }

    private static List<RawRegistry> load() {
        try (var stream = MinestomData.resource("synchronized_registries.json")) {
            if (stream == null) return List.of();
            JsonObject root = GSON.fromJson(new InputStreamReader(stream, UTF_8), JsonObject.class);
            var registries = new ArrayList<RawRegistry>();
            for (var registryElement : root.getAsJsonArray("registries")) {
                JsonObject registry = registryElement.getAsJsonObject();
                var entries = new ArrayList<RawEntry>();
                for (var entryElement : registry.getAsJsonArray("entries")) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    CompoundBinaryTag data = MinestomAdventure.tagStringIO().asCompound(entry.get("snbt").getAsString());
                    entries.add(new RawEntry(entry.get("id").getAsString(), data));
                }
                var registryTags = new ArrayList<TagsPacket.Tag>();
                for (var tagElement : registry.getAsJsonArray("tags")) {
                    JsonObject tag = tagElement.getAsJsonObject();
                    JsonArray values = tag.getAsJsonArray("entries");
                    int[] ids = new int[values.size()];
                    for (int index = 0; index < ids.length; index++) ids[index] = values.get(index).getAsInt();
                    registryTags.add(new TagsPacket.Tag(tag.get("id").getAsString(), ids));
                }
                registries.add(new RawRegistry(registry.get("id").getAsString(), entries, registryTags));
            }
            return List.copyOf(registries);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load synchronized registry fallback", exception);
        }
    }

    private record RawRegistry(String id, List<RawEntry> entries, List<TagsPacket.Tag> tags) {
    }

    private record RawEntry(String id, CompoundBinaryTag data) {
    }
}
