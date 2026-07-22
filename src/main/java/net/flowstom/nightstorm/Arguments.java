package net.flowstom.nightstorm;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

final class Arguments {
    private final Map<String, String> values;

    private Arguments(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    static Arguments parse(String[] args) {
        final Map<String, String> values = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 == args.length) {
                throw new IllegalArgumentException("Expected --name value arguments");
            }
            values.put(args[index].substring(2), args[index + 1]);
        }
        return new Arguments(values);
    }

    String optional(String name) {
        return values.get(name);
    }

    String required(String name) {
        final String value = optional(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required --" + name + " argument");
        }
        return value;
    }

    Path path(String name, Path defaultValue) {
        final String value = optional(name);
        return value == null ? defaultValue : Path.of(value);
    }

    Path requiredPath(String name) {
        return Path.of(required(name));
    }
}
