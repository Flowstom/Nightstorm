package net.flowstom.nightstorm;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

final class MojangClient {
    private static final URI VERSION_MANIFEST = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json");
    private final HttpJsonClient http = new HttpJsonClient();

    MinecraftVersion latest(String channel) throws Exception {
        return versions(channel).stream()
                .max(Comparator.comparing(MinecraftVersion::releaseTime))
                .orElseThrow(() -> new IllegalStateException("No Minecraft " + channel + " version found"));
    }

    List<MinecraftVersion> versions(String channel) throws Exception {
        final JsonNode manifest = http.get(VERSION_MANIFEST, null);
        return java.util.stream.StreamSupport.stream(manifest.path("versions").spliterator(), false)
                .filter(version -> channel.equals(version.path("type").asText()))
                .map(this::toVersion)
                .toList();
    }

    private MinecraftVersion toVersion(JsonNode version) {
        return new MinecraftVersion(
                version.path("id").asText(),
                version.path("type").asText(),
                Instant.parse(version.path("releaseTime").asText()),
                URI.create(version.path("url").asText())
        );
    }

    record MinecraftVersion(String id, String type, Instant releaseTime, URI metadataUrl) {
    }
}
