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

public final class NightstormDataNormalizer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private NightstormDataNormalizer() {
    }

    public static void normalizeFromEnvironment(Path output) throws IOException {
        String source = System.getenv("NIGHTSTORM_MINSTOM_SOURCE");
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("NIGHTSTORM_MINSTOM_SOURCE is required");
        }
        normalize(output, Path.of(source).resolve(".nightstorm/data-shapes.json"));
    }

    public static void normalize(Path output, Path shapes) throws IOException {
        if (!Files.isRegularFile(shapes)) throw new IllegalStateException("Missing source codec layouts " + shapes);
        JsonObject schema = GSON.fromJson(Files.readString(shapes), JsonObject.class);
        JsonObject layouts = schema.getAsJsonObject("lists");
        Set<String> components = registrations(schema.getAsJsonArray("components"));
        Set<String> attributes = registrations(schema.getAsJsonArray("attributes"));

        int changed = 0;
        try (var files = Files.walk(output)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json")).toList()) {
                JsonElement root = GSON.fromJson(Files.readString(path), JsonElement.class);
                int fileChanges = normalize(root, components, attributes, layouts);
                if (fileChanges > 0) {
                    Files.writeString(path, GSON.toJson(root));
                    changed += fileChanges;
                }
            }
        }
        System.out.println("Nightstorm normalized " + changed + " generated data values");
    }

    private static Set<String> registrations(JsonArray values) {
        var result = new HashSet<String>();
        for (var value : values) result.add(value.getAsString());
        if (result.isEmpty()) throw new IllegalStateException("No source registrations found in codec metadata");
        return Set.copyOf(result);
    }

    private static int normalize(JsonElement element, Set<String> components, Set<String> attributes, JsonObject layouts) {
        if (element.isJsonArray()) {
            int changed = 0;
            for (JsonElement value : element.getAsJsonArray()) changed += normalize(value, components, attributes, layouts);
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
                JsonArray converted = listValue(value, layouts.getAsJsonObject(key));
                if (converted != null) {
                    componentValues.add(key, converted);
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
                changed += normalize(value, components, attributes, layouts);
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

    private static JsonArray listValue(JsonElement value, JsonObject layout) {
        if (layout == null || !value.isJsonObject()) return null;
        JsonArray fields = layout.getAsJsonArray("fields");
        JsonObject object = value.getAsJsonObject();
        if (object.size() != fields.size()) return null;
        var result = new JsonArray();
        for (JsonElement field : fields) {
            JsonElement element = object.get(field.getAsString());
            if (element == null) return null;
            if (element.isJsonObject() && element.getAsJsonObject().size() == 1) {
                element = element.getAsJsonObject().entrySet().iterator().next().getValue();
            }
            if (!element.isJsonPrimitive()) return null;
            var scalar = element.getAsJsonPrimitive();
            boolean matches = switch (layout.get("scalar").getAsString()) {
                case "string" -> scalar.isString();
                case "boolean" -> scalar.isBoolean();
                case "integer" -> scalar.isNumber() && scalar.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
                case "number" -> scalar.isNumber();
                default -> false;
            };
            if (!matches) return null;
            result.add(element);
        }
        return result;
    }
}
