package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedPacketMigratorTest {
    @Test
    void resolvesEveryMigrationBeforeChangingSources() throws Exception {
        final Path sourceRoot = sourceRoot();
        final Path packageDirectory = sourceRoot.resolve("src/main/java/example");
        write(packageDirectory.resolve("Tone.java"), """
                package example;

                enum Tone {
                    QUIET,
                    LOUD
                }
                """);
        final Path valid = packageDirectory.resolve("ValidPacket.java");
        write(valid, packetSource("ValidPacket", "@Nullable Tone tone"));
        write(packageDirectory.resolve("InvalidPacket.java"), packetSource("InvalidPacket", "Tone tone"));
        final String original = Files.readString(valid);

        final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                RetainedPacketMigrator.apply(sourceRoot, List.of(
                        migration("ValidPacket", "ValidPacket.SERIALIZER"),
                        migration("InvalidPacket", "InvalidPacket.SERIALIZER"))));

        assertTrue(exception.getMessage().contains("not explicitly nullable"));
        assertEquals(original, Files.readString(valid));
    }

    @Test
    void prefersExplicitImportsIgnoresLocalVariablesAndIsIdempotent() throws Exception {
        final Path sourceRoot = sourceRoot();
        final Path sourceDirectory = sourceRoot.resolve("src/main/java");
        write(sourceDirectory.resolve("values/Tone.java"), """
                package values;

                public enum Tone {
                    QUIET,
                    LOUD
                }
                """);
        final Path packet = sourceDirectory.resolve("example/ImportedPacket.java");
        write(packet, """
                package example;

                import values.Tone;

                record ImportedPacket(@Nullable Tone tone) {
                    static final NetworkBuffer.Type<ImportedPacket> SERIALIZER = NetworkBufferTemplate.template(
                            Tone.NETWORK_TYPE, ImportedPacket::tone,
                            ImportedPacket::new
                    );

                    void unrelated() {
                        Object SERIALIZER = null;
                    }
                }

                final class Unrelated {
                    enum Tone {
                        WRONG
                    }
                }
                """);
        final PacketMigrationScanner.Migration migration = migration("ImportedPacket", "ImportedPacket.SERIALIZER");

        RetainedPacketMigrator.apply(sourceRoot, List.of(migration, migration));
        final String migrated = Files.readString(packet);
        RetainedPacketMigrator.apply(sourceRoot, List.of(migration));

        assertEquals(migrated, Files.readString(packet));
        assertEquals(1, occurrences(migrated, "OPTIONAL_VAR_INT"));
        assertTrue(migrated.contains("case 3 -> Tone.LOUD"));
        assertTrue(migrated.contains("case 11 -> Tone.QUIET"));
    }

    @Test
    void requiresNullabilityOnlyOnTheMigratedNestedComponent() throws Exception {
        final Path sourceRoot = sourceRoot();
        final Path packageDirectory = sourceRoot.resolve("src/main/java/example");
        write(packageDirectory.resolve("Tone.java"), """
                package example;

                enum Tone {
                    QUIET,
                    LOUD
                }
                """);
        write(packageDirectory.resolve("Envelope.java"), """
                package example;

                record Envelope(Payload payload) {
                    static final NetworkBuffer.Type<Envelope> SERIALIZER = NetworkBufferTemplate.template(
                            Payload.SERIALIZER, Envelope::payload,
                            Envelope::new
                    );
                }
                """);
        final Path payload = packageDirectory.resolve("Payload.java");
        write(payload, packetSource("Payload", "@Nullable Tone tone"));

        RetainedPacketMigrator.apply(sourceRoot,
                List.of(migration("Envelope", "Envelope.SERIALIZER", List.of(0, 0))));

        assertTrue(Files.readString(payload).contains("NetworkBuffer.OPTIONAL_VAR_INT.transform"));
    }

    @Test
    void rewritesArbitraryFixedTextBooleanPayloadAndPreservesApiOrder() throws Exception {
        final Path root = sourceRoot();
        final Path source = root.resolve("src/main/java/example/GlyphEnvelope.java");
        write(source, """
                package example;

                import java.util.List;

                record GlyphEnvelope(Object anchor, boolean primary, List<String> glyphs) {
                    static final NetworkBuffer.Type<GlyphEnvelope> SERIALIZER = new NetworkBuffer.Type<>() {
                        public void write(NetworkBuffer buffer, GlyphEnvelope value) {
                            buffer.write(ANCHOR, value.anchor());
                            buffer.write(BOOLEAN, value.primary());
                            buffer.write(STRING, value.glyphs().get(0));
                            buffer.write(STRING, value.glyphs().get(1));
                        }
                        public GlyphEnvelope read(NetworkBuffer buffer) {
                            return new GlyphEnvelope(buffer.read(ANCHOR), buffer.read(BOOLEAN), readGlyphs(buffer));
                        }
                    };
                    private static List<String> readGlyphs(NetworkBuffer buffer) {
                        return List.of(buffer.read(STRING), buffer.read(STRING));
                    }
                }
                """);
        final PacketMigrationScanner.Migration migration = semanticMigration("GlyphEnvelope",
                PacketMigrationScanner.Kind.REORDERED_BOOLEAN_ENUM, 2, -1, false);

        RetainedPacketMigrator.apply(root, List.of(migration));
        final String migrated = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(migration));

        assertEquals(migrated, Files.readString(source));
        assertTrue(migrated.indexOf("value.glyphs().get(1)") < migrated.indexOf("value.primary() ? 1 : 0"));
        assertTrue(migrated.contains("new GlyphEnvelope(objectValue, booleanValue, stringValues)"));
        assertTrue(migrated.contains("List.of"));
        assertTrue(migrated.contains("NetworkBuffer.VAR_INT"));
        assertTrue(!migrated.contains("readGlyphs"));
    }

    @Test
    void rewritesArbitraryNestedMotionPayloadAsLinearVariant() throws Exception {
        final Path root = sourceRoot();
        final Path source = root.resolve("src/main/java/example/TransitFrame.java");
        write(source, """
                package example;

                record TransitFrame(int key, Object origin, Object drift, float azimuth, float elevation, boolean stable) {
                    static final NetworkBuffer.Type<TransitFrame> SERIALIZER = NetworkBufferTemplate.template(
                            VAR_INT, TransitFrame::key,
                            VECTOR, TransitFrame::origin,
                            VECTOR, TransitFrame::drift,
                            FLOAT, TransitFrame::azimuth,
                            FLOAT, TransitFrame::elevation,
                            BOOLEAN, TransitFrame::stable,
                            TransitFrame::new);
                }
                """);
        final PacketMigrationScanner.Migration migration = semanticMigration("TransitFrame",
                PacketMigrationScanner.Kind.LINEAR_POSITION_PATH, -1, 7, false);

        RetainedPacketMigrator.apply(root, List.of(migration));
        final String migrated = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(migration));

        assertEquals(migrated, Files.readString(source));
        assertTrue(migrated.contains("buffer.write(NetworkBuffer.VAR_INT, 7)"));
        assertTrue(!migrated.contains("value.drift()"));
        assertTrue(migrated.contains("Object component2 = component1"));
    }

    @Test
    void wrapsArbitrarySerializerForProvenAppendedPrimitive() throws Exception {
        final Path root = sourceRoot();
        final Path source = root.resolve("src/main/java/example/PulseNotice.java");
        write(source, """
                package example;

                record PulseNotice(int amount) {
                    static final NetworkBuffer.Type<PulseNotice> SERIALIZER = NetworkBufferTemplate.template(
                            VAR_INT, PulseNotice::amount, PulseNotice::new);
                }
                """);
        final PacketMigrationScanner.Migration migration = semanticMigration("PulseNotice",
                PacketMigrationScanner.Kind.APPENDED_BOOLEAN, -1, -1, true);

        RetainedPacketMigrator.apply(root, List.of(migration));
        final String migrated = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(migration));

        assertEquals(migrated, Files.readString(source));
        assertEquals(1, occurrences(migrated, "compatibilityDelegate ="));
        assertTrue(migrated.contains("buffer.write(NetworkBuffer.BOOLEAN, true)"));
        assertTrue(migrated.contains("buffer.read(NetworkBuffer.BOOLEAN)"));
    }

    private static Path sourceRoot() throws Exception {
        final Path root = Files.createTempDirectory("retained-packet-migrator");
        Files.createDirectories(root.resolve("src/main/java/example"));
        return root;
    }

    private static String packetSource(String name, String component) {
        return """
                package example;

                record %s(%s) {
                    static final NetworkBuffer.Type<%s> SERIALIZER = NetworkBufferTemplate.template(
                            Tone.NETWORK_TYPE, %s::tone,
                            %s::new
                    );
                }
                """.formatted(name, component, name, name, name);
    }

    private static PacketMigrationScanner.Migration migration(String className, String serializer) {
        return migration(className, serializer, List.of(0));
    }

    private static PacketMigrationScanner.Migration migration(String className, String serializer,
                                                               List<Integer> path) {
        final Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("QUIET", 11);
        ids.put("LOUD", 3);
        return new PacketMigrationScanner.Migration(
                new PacketUpdater.RetainedPacket(className, serializer, "before", "after"),
                PacketMigrationScanner.Kind.OPTIONAL_ENUM, path, ids, "wire/Tone", -1, -1, false);
    }

    private static PacketMigrationScanner.Migration semanticMigration(String className,
                                                                       PacketMigrationScanner.Kind kind,
                                                                       int fixedSize, int discriminator,
                                                                       boolean defaultValue) {
        return new PacketMigrationScanner.Migration(
                new PacketUpdater.RetainedPacket(className, className + ".SERIALIZER", "before", "after"),
                kind, List.of(), Map.of(), "", fixedSize, discriminator, defaultValue);
    }

    private static void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static int occurrences(String value, String target) {
        return (value.length() - value.replace(target, "").length()) / target.length();
    }
}
