package net.minestom.generators;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/** Learns enum accessor relocations from vanilla data, without a palette or version table. */
public final class EnumDataAccess {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STATE_PATH = statePath();
    private static final JsonObject STATE = loadState();
    private static final Map<String, Table> TABLES = new LinkedHashMap<>();
    private static final Map<String, JsonObject> OBSERVED = new LinkedHashMap<>();
    private static final Map<Class<?>, List<Field>> CANDIDATES = new LinkedHashMap<>();

    private EnumDataAccess() {
    }

    public static JsonElement read(Enum<?> value, String getter, String member, Integer mask) {
        final Class<?> type = value.getDeclaringClass();
        final String key = type.getName() + "#" + getter + (member == null ? "" : "." + member)
                + (mask == null ? "" : "&" + mask);
        final JsonObject entry = STATE.has(key) ? STATE.getAsJsonObject(key) : new JsonObject();
        final Object result;
        try {
            result = type.getMethod(getter).invoke(value);
        } catch (NoSuchMethodException removed) {
            final Table table = TABLES.computeIfAbsent(key, ignored -> resolve(type, entry, member, mask, key));
            final JsonElement actual = table.values(type, member, mask).get(value.name());
            remember(key, entry, value.name(), actual);
            return actual;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read vanilla accessor " + key, failure);
        }
        final JsonElement actual = scalar(result, member, mask);
        remember(key, entry, value.name(), actual);
        return actual;
    }

    public static void save() throws IOException {
        OBSERVED.forEach((key, values) -> STATE.getAsJsonObject(key).add("values", values));
        Files.createDirectories(STATE_PATH.toAbsolutePath().getParent());
        Files.writeString(STATE_PATH, GSON.toJson(STATE));
    }

    private static void remember(String key, JsonObject entry, String name, JsonElement value) {
        OBSERVED.computeIfAbsent(key, ignored -> new JsonObject()).add(name, value);
        STATE.add(key, entry);
    }

    private static Table resolve(Class<?> type, JsonObject entry, String member, Integer mask, String key) {
        if (entry.has("owner") && entry.has("field")) {
            try {
                final Field field = Class.forName(entry.get("owner").getAsString(), false, type.getClassLoader())
                        .getField(entry.get("field").getAsString());
                final Table table = new Table(field);
                table.values(type, member, mask); // Validate the complete target enum before using the saved source.
                return table;
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IllegalStateException("Learned vanilla data source disappeared for " + key, failure);
            }
        }
        if (!entry.has("values")) throw new IllegalStateException("No baseline values to relocate " + key);
        final JsonObject expected = entry.getAsJsonObject("values").deepCopy();
        final Set<String> names = Arrays.stream(type.getEnumConstants()).map(v -> ((Enum<?>) v).name())
                .collect(Collectors.toSet());
        if (!expected.keySet().equals(names)) {
            throw new IllegalStateException("Baseline enum identities differ; cannot infer data source for " + key);
        }
        final Map<String, Table> matches = new LinkedHashMap<>();
        for (Field field : CANDIDATES.computeIfAbsent(type, EnumDataAccess::candidateFields)) {
            final Table table = new Table(field);
            if (!table.supports(member, mask)) continue;
            if (table.values(type, member, mask).equals(expected)) {
                final Table canonical = new Table(canonicalSource(field, new TreeSet<>()));
                matches.put(canonical.description(), canonical);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one vanilla enum table for " + key + ", found " + matches.size()
                    + ": " + matches.keySet());
        }
        final Table table = matches.values().iterator().next();
        entry.addProperty("owner", table.field().getDeclaringClass().getName());
        entry.addProperty("field", table.field().getName());
        System.out.println("Nightstorm learned data source " + key + " -> " + table.description());
        return table;
    }

    /** Follow direct static aliases so two fields for the same vanilla table are not ambiguous. */
    private static Field canonicalSource(Field field, Set<String> visited) {
        final String owner = field.getDeclaringClass().getName().replace('.', '/');
        if (!visited.add(owner + "#" + field.getName())) throw new IllegalStateException("Cyclic data table alias");
        try (var input = field.getDeclaringClass().getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw new IllegalStateException("Missing vanilla bytecode for " + owner);
            final var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (var method : node.methods) {
                if (!method.name.equals("<clinit>")) continue;
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof FieldInsnNode target) || target.getOpcode() != Opcodes.PUTSTATIC
                            || !target.owner.equals(owner) || !target.name.equals(field.getName())) continue;
                    var previous = instruction.getPrevious();
                    while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                    if (previous instanceof FieldInsnNode source && source.getOpcode() == Opcodes.GETSTATIC
                            && source.desc.equals(target.desc)) {
                        final Field original = Class.forName(source.owner.replace('/', '.'), false,
                                field.getDeclaringClass().getClassLoader()).getField(source.name);
                        if (original.get(null) == field.get(null)) return canonicalSource(original, visited);
                    }
                }
            }
            return field;
        } catch (IOException | ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot establish data table provenance for " + owner, failure);
        }
    }

    private static List<Field> candidateFields(Class<?> enumType) {
        final List<Field> fields = new ArrayList<>();
        final Set<String> enumNames = Arrays.stream(enumType.getEnumConstants())
                .map(v -> normalize(((Enum<?>) v).name())).collect(Collectors.toSet());
        try (var jar = new JarFile(Path.of(enumType.getProtectionDomain().getCodeSource().getLocation().toURI()).toFile())) {
            final Map<String, byte[]> classes = new LinkedHashMap<>();
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                final var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().equals("module-info.class")) continue;
                try (var input = jar.getInputStream(entry)) {
                    classes.put(entry.getName().substring(0, entry.getName().length() - 6), input.readAllBytes());
                }
            }
            // Only inspect record classes whose component names cover the enum identities.
            final Set<String> recordTypes = new TreeSet<>();
            for (var item : classes.entrySet()) {
                final String bytes = new String(item.getValue(), StandardCharsets.ISO_8859_1);
                if (!bytes.contains("java/lang/Record")) continue;
                final Class<?> record = Class.forName(item.getKey().replace('/', '.'), false, enumType.getClassLoader());
                if (!record.isRecord()) continue;
                final var components = record.getRecordComponents();
                final Set<String> componentNames = Arrays.stream(components).map(c -> normalize(c.getName()))
                        .collect(Collectors.toSet());
                if (components.length == enumNames.size() && componentNames.equals(enumNames)) recordTypes.add(item.getKey());
            }
            for (var item : classes.entrySet()) {
                final String bytes = new String(item.getValue(), StandardCharsets.ISO_8859_1);
                if (recordTypes.stream().noneMatch(t -> bytes.contains("L" + t + ";"))) continue;
                final Class<?> owner = Class.forName(item.getKey().replace('/', '.'), false, enumType.getClassLoader());
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())) continue;
                    if (!recordTypes.contains(field.getType().getName().replace('.', '/'))) continue;
                    // Avoid initializing tables of blocks, items, renderers, or other unrelated objects.
                    if (!(field.getGenericType() instanceof ParameterizedType generic)
                            || !(generic.getActualTypeArguments()[0] instanceof Class<?> element)) continue;
                    if (isScalar(element) || Arrays.stream(element.getFields()).anyMatch(f -> !Modifier.isStatic(f.getModifiers())
                            && isScalar(f.getType()))) fields.add(field);
                }
            }
        } catch (Exception | LinkageError failure) {
            throw new IllegalStateException("Cannot discover vanilla enum data tables for " + enumType.getName(), failure);
        }
        return fields;
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || Number.class.isAssignableFrom(type) || type == String.class || type == Boolean.class;
    }

    private static String normalize(String name) {
        return name.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static JsonElement scalar(Object value, String member, Integer mask) {
        try {
            if (member != null) {
                final Field field = value.getClass().getField(member);
                if (Modifier.isStatic(field.getModifiers())) throw new IllegalStateException("Expected instance field " + member);
                value = field.get(value);
            }
            if (mask != null) value = ((Number) value).intValue() & mask;
            if (value == null || !isScalar(value.getClass())) throw new IllegalStateException("Expected scalar vanilla data");
            return GSON.toJsonTree(value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read vanilla data member " + member, failure);
        }
    }

    private record Table(Field field) {
        boolean supports(String member, Integer mask) {
            if (!(field.getGenericType() instanceof ParameterizedType generic)
                    || !(generic.getActualTypeArguments()[0] instanceof Class<?> element)) return false;
            if (isScalar(element)) return mask == null || Number.class.isAssignableFrom(element)
                    || element.isPrimitive() && element != boolean.class && element != char.class;
            if (member == null) return false;
            try {
                final Field value = element.getField(member);
                return !Modifier.isStatic(value.getModifiers()) && isScalar(value.getType());
            } catch (NoSuchFieldException ignored) {
                return false;
            }
        }

        JsonObject values(Class<?> enumType, String member, Integer mask) {
            try {
                final Object record = field.get(null);
                final JsonObject values = new JsonObject();
                final Map<String, RecordComponent> components = Arrays.stream(record.getClass().getRecordComponents())
                        .collect(Collectors.toMap(c -> normalize(c.getName()), c -> c));
                for (Object constant : enumType.getEnumConstants()) {
                    final String name = ((Enum<?>) constant).name();
                    final RecordComponent component = components.get(normalize(name));
                    if (component == null) throw new IllegalStateException("Missing target table entry " + name);
                    final Object element = component.getAccessor().invoke(record);
                    // Scalar tables replace the getter result; object tables retain its original member access.
                    values.add(name, scalar(element, isScalar(element.getClass()) ? null : member, mask));
                }
                return values;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read vanilla table " + description(), failure);
            }
        }

        String description() {
            return field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    private static Path statePath() {
        final String path = System.getenv("NIGHTSTORM_ENUM_DATA_STATE");
        if (path == null || path.isBlank()) throw new IllegalStateException("NIGHTSTORM_ENUM_DATA_STATE is required");
        return Path.of(path);
    }

    private static JsonObject loadState() {
        try {
            return Files.exists(STATE_PATH) ? JsonParser.parseString(Files.readString(STATE_PATH)).getAsJsonObject()
                    : new JsonObject();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read enum data provenance " + STATE_PATH, failure);
        }
    }
}
