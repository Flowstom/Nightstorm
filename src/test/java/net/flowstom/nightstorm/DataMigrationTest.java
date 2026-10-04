package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

class DataMigrationTest {
    @TempDir Path root;

    @Test
    void learnsUnrelatedNamesAndScopedGuardsAndUsesFutureValues() throws Exception {
        try (var loader = helper()) {
            var migrate = loader.loadClass("net.minestom.generators.DataMigration").getMethod("migrate", Path.class, Path.class, Path.class);
            Path output = root.resolve("output"), state = root.resolve("rules.json"), baseline = root.resolve("profile.json");
            Files.createDirectories(output);
            Path data = output.resolve("registry.json"), unrelated = output.resolve("other.json");
            Files.writeString(data, """
                    {"space:a":{"label":"alpha","enabled":true,"values":[1,2]},
                     "space:b":{"label":"beta","enabled":false,"values":[4]}}
                    """);
            migrate.invoke(null, output, state, baseline);
            Files.writeString(data, """
                    {"space:a":{"path":"folder/alpha","active":true,"values":{"operation":"join","payload":[1,2]}},
                     "space:b":{"path":"folder/beta","active":false,"values":{"operation":"join","payload":[4]}}}
                    """);
            migrate.invoke(null, output, state, baseline);
            var learned = Json.MAPPER.readTree(state.toFile());
            assertEquals(3, learned.size());
            assertEquals("alpha", Json.MAPPER.readTree(data.toFile()).path("space:a").path("label").asText());
            Files.writeString(data, """
                    {"space:a":{"path":"elsewhere/changed","active":false,"values":{"operation":"join","payload":[99]}},
                     "space:b":{"path":"folder/beta","active":true,"values":{"operation":"replace","payload":[6]}}}
                    """);
            Files.writeString(unrelated, """
                    {"space:a":{"path":"folder/alpha","values":{"operation":"join","payload":[1,2]}}}
                    """);
            migrate.invoke(null, output, state, baseline);
            var changed = Json.MAPPER.readTree(data.toFile());
            assertEquals("changed", changed.path("space:a").path("label").asText());
            assertFalse(changed.path("space:a").path("enabled").asBoolean());
            assertEquals(Json.MAPPER.readTree("[99]"), changed.path("space:a").path("values"));
            assertTrue(changed.path("space:b").path("values").isObject());
            assertTrue(Json.MAPPER.readTree(unrelated.toFile()).path("space:a").has("path"));
            String once = Files.readString(data);
            migrate.invoke(null, output, state, baseline);
            assertEquals(once, Files.readString(data));
        }
    }

    @Test
    void rejectsAmbiguousRenames() throws Exception {
        try (var loader = helper()) {
            var migrate = loader.loadClass("net.minestom.generators.DataMigration").getMethod("migrate", Path.class, Path.class, Path.class);
            Path output = root.resolve("output"), state = root.resolve("rules.json"), baseline = root.resolve("profile.json");
            Files.createDirectories(output);
            Files.writeString(output.resolve("data.json"), "{\"old\":7}");
            migrate.invoke(null, output, state, baseline);
            Files.writeString(output.resolve("data.json"), "{\"first\":7,\"second\":7}");
            var failure = assertThrows(InvocationTargetException.class, () -> migrate.invoke(null, output, state, baseline));
            assertTrue(failure.getCause().getMessage().contains("Ambiguous"));
            assertEquals("{\"first\":7,\"second\":7}", Files.readString(output.resolve("data.json")));
        }
    }

    private URLClassLoader helper() throws Exception {
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        String gson = Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", gson, "-d", classes.toString(),
                "templates/data-generator/DataMigration.java"));
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
}
