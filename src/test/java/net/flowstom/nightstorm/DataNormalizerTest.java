package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataNormalizerTest {
    @Test
    void normalizesLearnedLayoutsAndPreservesValuesThatDoNotMatchTheCodec() throws Exception {
        var directory = Files.createTempDirectory("nightstorm-normalizer");
        var classes = directory.resolve("classes");
        Files.createDirectories(classes);
        String gson = Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", gson, "-d", classes.toString(),
                "templates/data-generator/NightstormDataNormalizer.java"));
        var metadata = directory.resolve("shapes.json");
        Files.writeString(metadata, """
                {"lists":{"minecraft:unrelated":{"fields":["second","first"],"scalar":"integer"}},
                 "components":["minecraft:unrelated"],"attributes":["minecraft:attribute"]}
                """);
        var output = directory.resolve("output");
        Files.createDirectories(output);
        Files.writeString(output.resolve("data.json"), """
                [{"components":{"minecraft:unrelated":{"first":1,"second":7}}},
                 {"components":{"minecraft:unrelated":{"first":"wrong","second":7}}},
                 {"components":{"minecraft:unrelated":{"first":1,"second":7,"extra":9}}},
                 {"components":{"minecraft:unrelated":{"first":1,"second":7.5}}}]
                """);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var normalize = loader.loadClass("net.minestom.generators.NightstormDataNormalizer")
                    .getMethod("normalize", Path.class, Path.class);
            normalize.invoke(null, output, metadata);
            var values = Json.MAPPER.readTree(output.resolve("data.json").toFile());
            assertEquals(Json.MAPPER.readTree("[7,1]"), values.get(0).path("components").path("minecraft:unrelated"));
            for (int i = 1; i < values.size(); i++) assertTrue(values.get(i).path("components").path("minecraft:unrelated").isObject());
            String normalized = Files.readString(output.resolve("data.json"));
            normalize.invoke(null, output, metadata);
            assertEquals(normalized, Files.readString(output.resolve("data.json")));
        }
    }
}
