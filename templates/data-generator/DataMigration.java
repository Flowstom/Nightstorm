package net.minestom.generators;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded projections learned from complete, identity-matched baseline data profiles. */
public final class DataMigration {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private record Sample(JsonElement before, JsonObject after) { }
    private record Location(String file, List<String> path, String field, boolean rename) { }

    private DataMigration() { }

    public static void migrate(Path output, Path state, Path baseline) throws IOException {
        JsonArray rules = Files.exists(state) ? JsonParser.parseString(Files.readString(state)).getAsJsonArray() : new JsonArray();
        Map<String, JsonElement> current = read(output);
        current.forEach((file, root) -> apply(root, file, List.of(), rules));
        if (Files.exists(baseline)) {
            JsonObject previous = JsonParser.parseString(Files.readString(baseline)).getAsJsonObject();
            Map<Location, List<Sample>> profiles = new LinkedHashMap<>();
            current.forEach((file, root) -> {
                if (previous.has(file)) compare(previous.get(file), root, file, List.of(), profiles);
            });
            for (var entry : profiles.entrySet()) {
                JsonObject rule = infer(entry.getKey(), entry.getValue());
                if (rule != null && !contains(rules, rule)) {
                    rules.add(rule);
                    System.out.println("Nightstorm learned data projection " + rule);
                }
            }
            current.forEach((file, root) -> apply(root, file, List.of(), rules));
        }
        JsonObject snapshot = new JsonObject();
        for (var entry : current.entrySet()) {
            Files.writeString(output.resolve(entry.getKey()), GSON.toJson(entry.getValue()));
            snapshot.add(entry.getKey(), entry.getValue());
        }
        Files.createDirectories(state.toAbsolutePath().getParent());
        Files.createDirectories(baseline.toAbsolutePath().getParent());
        Files.writeString(state, GSON.toJson(rules));
        Files.writeString(baseline, GSON.toJson(snapshot));
    }

    private static Map<String, JsonElement> read(Path output) throws IOException {
        Map<String, JsonElement> result = new TreeMap<>();
        try (var files = Files.walk(output)) {
            for (Path path : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList()) {
                result.put(output.relativize(path).toString(), JsonParser.parseString(Files.readString(path)));
            }
        }
        return result;
    }

    private static String segment(String key) { return key.contains(":") ? "*" : key; }
    private static List<String> child(List<String> path, String key) {
        var result = new ArrayList<>(path);
        result.add(segment(key));
        return List.copyOf(result);
    }

    private static void compare(JsonElement before, JsonElement after, String file, List<String> path,
                                Map<Location, List<Sample>> profiles) {
        // Array positions are not stable identities. Do not infer relationships through them.
        if (!before.isJsonObject() || !after.isJsonObject()) return;
        JsonObject old = before.getAsJsonObject(), now = after.getAsJsonObject();
        for (String key : old.keySet()) {
            if (!now.has(key)) {
                if (!key.contains(":")) profiles.computeIfAbsent(new Location(file, path, key, true), ignored -> new ArrayList<>())
                        .add(new Sample(old.get(key), additions(old, now)));
                continue;
            }
            JsonElement left = old.get(key), right = now.get(key);
            if (!left.isJsonObject() && right.isJsonObject()) {
                profiles.computeIfAbsent(new Location(file, path, key, false), ignored -> new ArrayList<>())
                        .add(new Sample(left, right.getAsJsonObject()));
            } else compare(left, right, file, child(path, key), profiles);
        }
    }

    private static JsonObject additions(JsonObject old, JsonObject now) {
        JsonObject result = new JsonObject();
        for (String key : now.keySet()) if (!old.has(key)) result.add(key, now.get(key));
        return result;
    }

    private static JsonObject infer(Location location, List<Sample> samples) {
        Set<String> candidates = new TreeSet<>(samples.getFirst().after().keySet());
        samples.forEach(sample -> candidates.retainAll(sample.after().keySet()));
        List<JsonObject> matches = new ArrayList<>();
        for (String key : candidates) {
            for (String projection : List.of("identity", "lastPathSegment")) {
                if (!samples.stream().allMatch(sample -> sample.before().equals(project(sample.after().get(key), projection)))) continue;
                // Prefer the simpler identity when both expressions are equivalent in the baseline.
                if (projection.equals("lastPathSegment") && samples.stream().allMatch(sample ->
                        sample.before().equals(sample.after().get(key)))) continue;
                JsonObject guards = new JsonObject();
                if (!location.rename()) {
                    boolean valid = true;
                    for (String guard : candidates) {
                        if (guard.equals(key)) continue;
                        JsonElement value = samples.getFirst().after().get(guard);
                        if (!value.isJsonPrimitive() || !samples.stream().allMatch(s -> value.equals(s.after().get(guard)))) {
                            valid = false; break;
                        }
                        guards.add(guard, value);
                    }
                    if (!valid || !samples.stream().allMatch(s -> s.after().size() == guards.size() + 1)) continue;
                }
                JsonObject rule = new JsonObject();
                rule.addProperty("file", location.file());
                rule.add("path", GSON.toJsonTree(location.path()));
                rule.addProperty("field", location.field());
                rule.addProperty("rename", location.rename());
                rule.addProperty("source", key);
                rule.addProperty("projection", projection);
                rule.add("guards", guards);
                rule.addProperty("evidence", samples.size());
                matches.add(rule);
            }
        }
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous data projections for " + location + ": " + matches);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static JsonElement project(JsonElement value, String projection) {
        if (value == null) return JsonNull.INSTANCE;
        if (projection.equals("identity")) return value;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return JsonNull.INSTANCE;
        String text = value.getAsString();
        return new JsonPrimitive(text.substring(text.lastIndexOf('/') + 1));
    }

    private static boolean contains(JsonArray rules, JsonObject rule) {
        JsonObject comparable = rule.deepCopy();
        comparable.remove("evidence");
        for (JsonElement existing : rules) {
            JsonObject other = existing.getAsJsonObject().deepCopy();
            other.remove("evidence");
            if (other.equals(comparable)) return true;
        }
        return false;
    }

    private static void apply(JsonElement root, String file, List<String> path, JsonArray rules) {
        if (root.isJsonArray()) {
            // Saved paths never include an array index, so an array cannot inherit its parent's rules.
            for (JsonElement element : root.getAsJsonArray()) apply(element, file, child(path, "[]"), rules);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject object = root.getAsJsonObject();
        for (JsonElement value : rules) {
            JsonObject rule = value.getAsJsonObject();
            if (!file.equals(rule.get("file").getAsString()) || !GSON.toJsonTree(path).equals(rule.get("path"))) continue;
            String field = rule.get("field").getAsString(), source = rule.get("source").getAsString();
            boolean rename = rule.get("rename").getAsBoolean();
            if (rename && object.has(field)) continue;
            JsonObject input = rename ? object : object.has(field) && object.get(field).isJsonObject()
                    ? object.getAsJsonObject(field) : null;
            if (input == null || !input.has(source)) continue;
            JsonObject guards = rule.getAsJsonObject("guards");
            if (!rename && (input.size() != guards.size() + 1 || guards.entrySet().stream()
                    .anyMatch(guard -> !guard.getValue().equals(input.get(guard.getKey()))))) continue;
            JsonElement projected = project(input.get(source), rule.get("projection").getAsString());
            if (projected.isJsonNull() && !input.get(source).isJsonNull()) throw new IllegalStateException("Data projection input changed shape: " + rule);
            if (rename) object.remove(source);
            object.add(field, projected.deepCopy());
        }
        for (String key : List.copyOf(object.keySet())) apply(object.get(key), file, child(path, key), rules);
    }
}
