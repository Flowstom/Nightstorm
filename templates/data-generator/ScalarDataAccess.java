package net.minestom.generators;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.*;

/** Resolves renamed scalar getters from a complete stable-identity domain; never copies old values. */
public final class ScalarDataAccess {
    private static final Gson GSON = new Gson();
    private static final Map<Class<?>, List<?>> DOMAINS = new LinkedHashMap<>();
    private static final Map<String, JsonArray> PROGRAMS = new HashMap<>();
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final JsonObject STATE = load();
    private static final Map<String, JsonObject> OBSERVED = new LinkedHashMap<>();

    private ScalarDataAccess() { }

    public static void domain(Class<?> type, Supplier<? extends Collection<?>> supplier) {
        if (DOMAINS.containsKey(type)) return;
        List<?> values = List.copyOf(supplier.get());
        Set<String> identities = new HashSet<>();
        for (Object value : values) {
            if (!type.isInstance(value) || !identities.add(value.toString())) {
                throw new IllegalStateException("Non-unique scalar data identities for " + type.getName());
            }
        }
        if (values.isEmpty()) throw new IllegalStateException("Empty scalar data domain " + type.getName());
        DOMAINS.put(type, values);
    }

    public static <T> T read(Object value, String getter, Class<T> resultType) {
        Class<?> type = DOMAINS.keySet().stream().filter(c -> c.isInstance(value)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing scalar data domain for " + value.getClass()));
        String key = type.getName() + "#" + getter;
        Method method = PROGRAMS.containsKey(key) ? null : METHODS.computeIfAbsent(key, ignored -> resolve(type, getter, resultType, key));
        if (PROGRAMS.containsKey(key)) {
            @SuppressWarnings("unchecked") T result = (T) ScalarProgram.evaluate(PROGRAMS.get(key), value, resultType);
            return result;
        }
        try {
            @SuppressWarnings("unchecked") T result = (T) method.invoke(value);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read scalar data " + key, failure);
        }
    }

    private static Method resolve(Class<?> type, String getter, Class<?> result, String key) {
        JsonObject entry = STATE.has(key) ? STATE.getAsJsonObject(key) : new JsonObject();
        Method method;
        try {
            method = type.getMethod(getter);
            if (method.getReturnType() != result) throw new IllegalStateException("Scalar data return type changed: " + key);
            JsonArray program = ScalarProgram.capture(method);
            if (program != null && DOMAINS.get(type).stream().allMatch(value -> {
                try { return Objects.equals(methodResult(type, getter, value), ScalarProgram.evaluate(program, value, result)); }
                catch (IllegalStateException unportable) { return false; }
            })) entry.add("program", program);
            else entry.remove("program");
        } catch (NoSuchMethodException missing) {
            if (entry.has("program")) {
                JsonArray program = entry.getAsJsonArray("program");
                JsonObject values = new JsonObject();
                for (Object value : DOMAINS.get(type)) values.add(value.toString(), GSON.toJsonTree(ScalarProgram.evaluate(program, value, result)));
                OBSERVED.put(key, values);
                PROGRAMS.put(key, program);
                System.out.println("Nightstorm retained scalar accessor program " + key);
                return null;
            }
            if (entry.has("method")) {
                try { method = type.getMethod(entry.get("method").getAsString()); }
                catch (NoSuchMethodException gone) { throw new IllegalStateException("Learned scalar getter disappeared: " + key, gone); }
                if (method.getReturnType() != result) throw new IllegalStateException("Learned scalar getter changed type: " + key);
            } else {
                if (!entry.has("values")) throw new IllegalStateException("No baseline scalar profile for " + key);
                JsonObject expected = entry.getAsJsonObject("values");
                Map<String, Method> matches = new LinkedHashMap<>();
                for (Method candidate : type.getMethods()) {
                    if (Modifier.isStatic(candidate.getModifiers()) || candidate.getParameterCount() != 0 || candidate.getReturnType() != result) continue;
                    JsonObject actual;
                    try { actual = profile(type, candidate); }
                    catch (IllegalStateException unavailable) { continue; }
                    Set<String> common = new HashSet<>(expected.keySet());
                    common.retainAll(actual.keySet());
                    if (common.size() < 2 || common.stream().map(expected::get).distinct().count() < 2) continue;
                    if (common.stream().allMatch(id -> expected.get(id).equals(actual.get(id)))) matches.put(origin(candidate), candidate);
                }
                if (matches.size() != 1) throw new IllegalStateException("Expected one scalar getter for " + key
                        + ", found " + matches.size() + ": " + matches.keySet());
                method = matches.values().iterator().next();
                entry.addProperty("method", method.getName());
                entry.addProperty("origin", origin(method));
                System.out.println("Nightstorm learned scalar getter " + key + " -> " + method);
            }
        }
        OBSERVED.put(key, profile(type, method));
        STATE.add(key, entry);
        return method;
    }

    private static Object methodResult(Class<?> type, String getter, Object value) {
        try { return type.getMethod(getter).invoke(value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static JsonObject profile(Class<?> type, Method method) {
        JsonObject result = new JsonObject();
        for (Object value : DOMAINS.get(type)) {
            try { result.add(value.toString(), GSON.toJsonTree(method.invoke(value))); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot profile " + method, failure); }
        }
        return result;
    }

    // Direct aliases of the same field are a single candidate, even if their getter names differ.
    private static String origin(Method method) {
        String owner = method.getDeclaringClass().getName().replace('.', '/');
        try (InputStream input = method.getDeclaringClass().getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw new IllegalStateException("Missing accessor bytecode " + method);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode body : node.methods) {
                if (!body.name.equals(method.getName()) || !body.desc.equals(org.objectweb.asm.Type.getMethodDescriptor(method))) continue;
                var code = Arrays.stream(body.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
                if (code.size() == 3 && code.getFirst() instanceof VarInsnNode load && load.var == 0
                        && load.getOpcode() == Opcodes.ALOAD && code.get(1) instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD) return field.owner + "#" + field.name + ":" + field.desc;
            }
            return owner + "#" + method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
        } catch (IOException failure) { throw new IllegalStateException("Cannot inspect accessor " + method, failure); }
    }

    public static void save() throws IOException {
        OBSERVED.forEach((key, values) -> STATE.getAsJsonObject(key).add("values", values));
        Path path = path();
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), java.nio.charset.StandardCharsets.UTF_8)) {
            GSON.toJson(STATE, writer);
        }
    }

    private static JsonObject load() {
        Path path = path();
        if (!Files.exists(path)) return new JsonObject();
        try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException failure) { throw new IllegalStateException("Cannot read scalar data profiles " + path, failure); }
    }

    private static Path path() {
        String path = System.getProperty("nightstorm.scalar.state", System.getenv("NIGHTSTORM_SCALAR_DATA_STATE"));
        if (path == null || path.isBlank()) throw new IllegalStateException("NIGHTSTORM_SCALAR_DATA_STATE is required");
        return Path.of(path);
    }
}
