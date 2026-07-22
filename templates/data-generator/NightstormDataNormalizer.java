package net.minestom.generators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class NightstormDataNormalizer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern REGISTRATION = Pattern.compile("register\\(\"([^\"]+)\"");
    private static final List<String> POT_SIDES = List.of("back", "left", "right", "front");

    private NightstormDataNormalizer() {
    }

    public static void normalizeFromEnvironment(Path output) throws IOException {
        String source = System.getenv("NIGHTSTORM_MINSTOM_SOURCE");
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("NIGHTSTORM_MINSTOM_SOURCE is required");
        }
        Path sourceRoot = Path.of(source);
        Set<String> components = registrations(sourceRoot.resolve(
                "src/main/java/net/minestom/server/component/DataComponents.java"));
        Set<String> attributes = registrations(sourceRoot.resolve(
                "src/main/java/net/minestom/server/world/attribute/EnvironmentAttributes.java"));

        int changed = 0;
        try (var files = Files.walk(output)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                JsonElement root = GSON.fromJson(Files.readString(path), JsonElement.class);
                int fileChanges = normalize(root, components, attributes);
                if (fileChanges > 0) {
                    Files.writeString(path, GSON.toJson(root));
                    changed += fileChanges;
                }
            }
        }
        System.out.println("Nightstorm normalized " + changed + " generated data values");
    }

    private static Set<String> registrations(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IllegalStateException("Missing Minestom source " + source);
        var matcher = REGISTRATION.matcher(Files.readString(source));
        var result = new HashSet<String>();
        while (matcher.find()) result.add("minecraft:" + matcher.group(1));
        if (result.isEmpty()) throw new IllegalStateException("No registrations found in " + source);
        return Set.copyOf(result);
    }

    private static int normalize(JsonElement element, Set<String> components, Set<String> attributes) {
        if (element.isJsonArray()) {
            int changed = 0;
            for (JsonElement value : element.getAsJsonArray()) changed += normalize(value, components, attributes);
            return changed;
        }
        if (!element.isJsonObject()) return 0;

        JsonObject object = element.getAsJsonObject();
        int changed = 0;
        JsonObject componentValues = object.getAsJsonObject("components");
        if (componentValues != null) {
            for (String key : Set.copyOf(componentValues.keySet())) {
                if (!components.contains(key)) {
                    componentValues.remove(key);
                    changed++;
                    continue;
                }
                JsonElement value = componentValues.get(key);
                if (isPotDecorationShape(value)) {
                    var decorations = new JsonArray();
                    for (String side : POT_SIDES) decorations.add(value.getAsJsonObject().getAsJsonObject(side).get("id"));
                    componentValues.add(key, decorations);
                    changed++;
                }
            }
        }

        JsonObject attributeValues = object.getAsJsonObject("attributes");
        if (attributeValues != null && attributeValues.keySet().stream().allMatch(key -> key.contains(":"))) {
            for (String key : Set.copyOf(attributeValues.keySet())) {
                if (!attributes.contains(key)) {
                    attributeValues.remove(key);
                    changed++;
                }
            }
        }

        if (object.has("palette_id") && !object.has("asset_name") && object.get("palette_id").isJsonPrimitive()) {
            String palette = object.remove("palette_id").getAsString();
            object.addProperty("asset_name", palette.substring(palette.lastIndexOf('/') + 1));
            changed++;
        }
        if (object.has("destroy_on_use") && !object.has("explodes")) {
            object.add("explodes", object.remove("destroy_on_use"));
            changed++;
        }

        for (String key : List.copyOf(object.keySet())) {
            JsonElement value = object.get(key);
            if (isAppendShape(value)) {
                object.add(key, value.getAsJsonObject().get("argument"));
                changed++;
            } else {
                changed += normalize(value, components, attributes);
            }
        }
        return changed;
    }

    private static boolean isAppendShape(JsonElement value) {
        if (!value.isJsonObject()) return false;
        JsonObject object = value.getAsJsonObject();
        return object.size() == 2 && object.has("modifier") && object.has("argument")
                && object.get("modifier").isJsonPrimitive()
                && "append".equals(object.get("modifier").getAsString());
    }

    private static boolean isPotDecorationShape(JsonElement value) {
        if (!value.isJsonObject() || value.getAsJsonObject().size() != POT_SIDES.size()) return false;
        JsonObject object = value.getAsJsonObject();
        return POT_SIDES.stream().allMatch(side -> object.has(side) && object.get(side).isJsonObject()
                && object.getAsJsonObject(side).size() == 1 && object.getAsJsonObject(side).has("id"));
    }
}
