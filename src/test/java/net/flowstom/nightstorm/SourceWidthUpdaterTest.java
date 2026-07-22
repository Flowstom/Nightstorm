package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceWidthUpdaterTest {
    @Test
    void updatesRetainedWidthAcrossPowerOfTwoBoundary() throws Exception {
        final Path root = sourceRoot(15);
        final Path archive = archive(root, 32_768);

        final var result = SourceWidthUpdater.update(root, archive, "data/registry.json",
                "*/entries/*/id", Path.of("src/main/java/example/Values.java"), "DIRECT_BITS");

        assertEquals(32_768, result.maximumValue());
        assertEquals(16, result.width());
        final String source = Files.readString(root.resolve("src/main/java/example/Values.java"));
        assertTrue(source.contains("int DIRECT_BITS = 16; // retained"));
    }

    @Test
    void retainsWidthBelowPowerOfTwoBoundary() throws Exception {
        final Path root = sourceRoot(15);
        final Path archive = archive(root, 32_767);

        final var result = SourceWidthUpdater.update(root, archive, "data/registry.json",
                "*/entries/*/id", Path.of("src/main/java/example/Values.java"), "DIRECT_BITS");

        assertEquals(15, result.width());
        assertTrue(Files.readString(root.resolve("src/main/java/example/Values.java"))
                .contains("int DIRECT_BITS = 15; // retained"));
    }

    @Test
    void leavesSourceUntouchedWhenTheConfiguredPathDoesNotMatch() throws Exception {
        final Path root = sourceRoot(15);
        final Path source = root.resolve("src/main/java/example/Values.java");
        final String original = Files.readString(source);
        final Path archive = archive(root, 32_768);

        assertThrows(IllegalStateException.class, () -> SourceWidthUpdater.update(root, archive,
                "data/registry.json", "*/missing/*/id",
                Path.of("src/main/java/example/Values.java"), "DIRECT_BITS"));

        assertEquals(original, Files.readString(source));
    }

    private static Path sourceRoot(int width) throws Exception {
        final Path root = Files.createTempDirectory("source-width-updater");
        final Path source = root.resolve("src/main/java/example/Values.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                interface Values {
                    int DIRECT_BITS = %d; // retained
                }
                """.formatted(width));
        return root;
    }

    private static Path archive(Path root, int maximum) throws Exception {
        final Path archive = root.resolve("data.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("data/registry.json"));
            output.write(("""
                    {
                      "first": {"entries": {"a": {"id": 0}}},
                      "last": {"entries": {"z": {"id": %d}}}
                    }
                    """.formatted(maximum)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }
}
