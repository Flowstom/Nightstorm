package net.minestom.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads values after a proved constant-catalog relocation. Method and type names come from
 * evidence written before compilation, so the same helper runs against whichever jar is current.
 */
public final class ApiRelocation {
    private static JsonObject evidence;
    private static Object provider;
    private static final Map<Object, Object> RESOLVED = new HashMap<>();

    private ApiRelocation() {
    }

    public static List<Field> catalogFields(String baselineType) {
        var type = type(baselineType);
        try {
            Class<?> catalog = Class.forName(type.get("catalog").getAsString());
            Class<?> identity = Class.forName(type.get("identity").getAsString());
            var fields = new ArrayList<Field>();
            for (Field field : catalog.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !identity.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                fields.add(field);
            }
            return fields;
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load relocated catalog for " + baselineType, exception);
        }
    }

    public static Object apply(Object value, String... methods) {
        for (String method : methods) {
            if (value == null) return null;
            value = invoke(value, method);
        }
        return value;
    }

    public static Object read(String baselineType, Object receiver, String member) {
        var type = type(baselineType);
        var accessors = evidence().getAsJsonArray("accessors");
        if (accessors != null) {
            for (JsonElement element : accessors) {
                var accessor = element.getAsJsonObject();
                if (!baselineType.equals(text(accessor, "baselineType")) || !member.equals(text(accessor, "method"))) continue;
                if (receiver == null) return null;
                return unwrap(invoke(receiver, text(accessor, "targetMethod")), accessor.getAsJsonArray("unwrap"));
            }
        }
        var members = type.getAsJsonObject("members");
        if (members == null || !members.has(member)) {
            throw new IllegalStateException("No relocated member " + baselineType + "." + member);
        }
        var move = members.getAsJsonObject(member);
        Object value = resolve(type, receiver);
        if (value == null) return null;
        Object raw = "field".equals(text(move, "kind")) ? field(value, text(move, "name")) : invoke(value, text(move, "name"));
        return unwrap(raw, move.getAsJsonArray("unwrap"));
    }

    public static float asFloat(String baselineType, Object receiver, String member) {
        return ((Number) read(baselineType, receiver, member)).floatValue();
    }

    public static double asDouble(String baselineType, Object receiver, String member) {
        return ((Number) read(baselineType, receiver, member)).doubleValue();
    }

    public static int asInt(String baselineType, Object receiver, String member) {
        return ((Number) read(baselineType, receiver, member)).intValue();
    }

    public static long asLong(String baselineType, Object receiver, String member) {
        return ((Number) read(baselineType, receiver, member)).longValue();
    }

    public static boolean asBoolean(String baselineType, Object receiver, String member) {
        return (Boolean) read(baselineType, receiver, member);
    }

    private static Object resolve(JsonObject type, Object identity) {
        if (identity == null) return null;
        if (isInstance(text(type, "value"), identity)) return identity;
        var resolution = type.get("resolve");
        if (resolution == null || resolution.isJsonNull()) return identity;
        synchronized (RESOLVED) {
            Object cached = RESOLVED.get(identity);
            if (cached != null) return cached;
            Object holder = invoke(provider(), text(resolution.getAsJsonObject(), "method"), identity);
            Object value = unwrap(holder, resolution.getAsJsonObject().getAsJsonArray("unwrap"));
            RESOLVED.put(identity, value);
            return value;
        }
    }

    private static Object provider() {
        if (provider != null) return provider;
        try {
            var type = Class.forName("net.minestom.generators.MinecraftCompatibility");
            var factories = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0).toList();
            if (factories.size() != 1) {
                throw new IllegalStateException("Cannot identify a unique vanilla registry lookup factory: " + factories);
            }
            factories.getFirst().setAccessible(true);
            provider = factories.getFirst().invoke(null);
            return provider;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to invoke vanilla registry lookup factory", exception);
        }
    }

    private static Object unwrap(Object value, JsonArray steps) {
        if (steps == null) return value;
        for (JsonElement step : steps) {
            if (value == null) return null;
            String name = step.getAsString();
            if (name.equals("optional")) value = ((Optional<?>) value).orElse(null);
            else if (name.startsWith("value:")) value = invoke(value, name.substring("value:".length()));
            else throw new IllegalStateException("Unknown relocation unwrap " + name);
        }
        return value;
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        Method found = null;
        for (Method method : target.getClass().getMethods()) {
            if (method.isSynthetic() || method.isBridge() || !method.getName().equals(name)
                    || method.getParameterCount() != arguments.length) continue;
            boolean compatible = true;
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (argument != null && !wrap(method.getParameterTypes()[index]).isInstance(argument)) compatible = false;
            }
            if (!compatible) continue;
            if (found != null) throw new IllegalStateException("Ambiguous relocated method " + name + " on " + target.getClass().getName());
            found = method;
        }
        if (found == null) throw new IllegalStateException("Missing relocated method " + name + " on " + target.getClass().getName());
        try {
            return found.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(exception.getCause());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException exception) {
                type = type.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
        throw new IllegalStateException("Missing relocated field " + name + " on " + target.getClass().getName());
    }

    private static boolean isInstance(String name, Object value) {
        try {
            return Class.forName(name, false, value.getClass().getClassLoader()).isInstance(value);
        } catch (ClassNotFoundException exception) {
            return value.getClass().getName().equals(name);
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        return switch (type.getName()) {
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "short" -> Short.class;
            case "char" -> Character.class;
            default -> type;
        };
    }

    private static JsonObject type(String baselineType) {
        var types = evidence().getAsJsonObject("types");
        if (types == null || !types.has(baselineType)) throw new IllegalStateException("No relocation for " + baselineType);
        return types.getAsJsonObject(baselineType);
    }

    private static JsonObject evidence() {
        if (evidence != null) return evidence;
        String path = System.getenv("NIGHTSTORM_API_RELOCATION");
        if (path == null || path.isBlank()) path = System.getProperty("nightstorm.api.relocation");
        if (path == null || path.isBlank()) throw new IllegalStateException("Missing relocated API evidence");
        try {
            evidence = JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();
            return evidence;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read relocated API evidence", exception);
        }
    }

    private static String text(JsonObject object, String field) {
        return object.get(field).getAsString();
    }
}
