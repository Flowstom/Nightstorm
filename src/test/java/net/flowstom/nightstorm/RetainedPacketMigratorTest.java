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
    void snapshotSerializersPreserveLegacyValuesAndConsumeCompletePayloads() throws Exception {
        final Path root = sourceRoot();
        final Path directory = root.resolve("src/main/java/example");
        final Path teleport = directory.resolve("Teleport.java");
        write(teleport, """
                package example;
                record Teleport(int id) {
                    static final NetworkBuffer.Type<Teleport> SERIALIZER = new NetworkBuffer.Type<>() {
                        public void write(NetworkBuffer buffer, Teleport value) { buffer.write(NetworkBuffer.VAR_INT, value.id); }
                        public Teleport read(NetworkBuffer buffer) { return new Teleport(buffer.read(NetworkBuffer.VAR_INT)); }
                    };
                }
                """);
        final Path particle = directory.resolve("ParticlePacket.java");
        write(particle, """
                package example;
                import java.util.Objects;
                import static example.NetworkBuffer.*;
                record ParticlePacket(Particle particle, boolean overrideLimiter, boolean longDistance,
                        double x, double y, double z, float offsetX, float offsetY, float offsetZ,
                        float maxSpeed, int particleCount) {
                    static final NetworkBuffer.Type<ParticlePacket> SERIALIZER = null;
                }
                """);
        final var migrations = List.of(
                semanticMigration("Teleport", PacketMigrationScanner.Kind.TELEPORT_POSITION, -1, -1, false),
                semanticMigration("ParticlePacket", PacketMigrationScanner.Kind.PARTICLE_AXES, -1, 7, false));
        RetainedPacketMigrator.apply(root, migrations);
        final String firstTeleport = Files.readString(teleport);
        final String firstParticle = Files.readString(particle);
        RetainedPacketMigrator.apply(root, migrations);
        assertEquals(firstTeleport, Files.readString(teleport));
        assertEquals(firstParticle, Files.readString(particle));
        write(directory.resolve("WireCheck.java"), """
                package example;
                import java.util.*;
                public class WireCheck {
                    public static void verify() {
                        var buffer = new NetworkBuffer();
                        // Independent target-wire fixture with nonzero position and rotation.
                        buffer.values.addAll(List.of(123, 1d, 2d, 3d, 4f, 5f));
                        buffer.codecs.addAll(List.of("VAR_INT", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT"));
                        if (Teleport.SERIALIZER.read(buffer).id() != 123 || buffer.index != 6) throw new AssertionError();
                        buffer = new NetworkBuffer();
                        var packet = new ParticlePacket(new Particle(300), true, false, 1d, 2d, 3d, 4f, 5f, 6f, 7f, 500);
                        ParticlePacket.SERIALIZER.write(buffer, packet);
                        if (!buffer.codecs.equals(List.of("VAR_INT", "BOOLEAN", "BOOLEAN", "DOUBLE", "DOUBLE", "DOUBLE",
                                "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "VAR_INT", "VAR_INT"))) throw new AssertionError(buffer.codecs);
                        if (!buffer.values.equals(List.of(300, true, false, 1d, 2d, 3d, 4f, 5f, 6f, 7f, 7f, 7f, 500, 7))) throw new AssertionError(buffer.values);
                        if (!ParticlePacket.SERIALIZER.read(buffer).equals(packet) || buffer.index != 14) throw new AssertionError();
                        buffer.index = 0;
                        buffer.values.set(10, 8f);
                        try {
                            ParticlePacket.SERIALIZER.read(buffer);
                            throw new AssertionError("Unrepresentable axis speeds must not be silently discarded");
                        } catch (IllegalArgumentException expected) { }
                    }
                }
                record Particle(int id) {
                    static Particle fromId(int id) { return new Particle(id); }
                    Particle readData(NetworkBuffer buffer) { return this; }
                    void writeData(NetworkBuffer buffer) { }
                }
                class NetworkBuffer {
                    interface Type<T> {
                        void write(NetworkBuffer buffer, T value);
                        T read(NetworkBuffer buffer);
                    }
                    record Codec<T>(String name) { }
                    static final Codec<Integer> VAR_INT = new Codec<>("VAR_INT");
                    static final Codec<Boolean> BOOLEAN = new Codec<>("BOOLEAN");
                    static final Codec<Double> DOUBLE = new Codec<>("DOUBLE");
                    static final Codec<Float> FLOAT = new Codec<>("FLOAT");
                    final List<String> codecs = new ArrayList<>();
                    final List<Object> values = new ArrayList<>();
                    int index;
                    <T> void write(Codec<T> codec, T value) { codecs.add(codec.name()); values.add(value); }
                    @SuppressWarnings("unchecked")
                    <T> T read(Codec<T> codec) {
                        if (!codecs.get(index).equals(codec.name())) throw new AssertionError(codec);
                        return (T) values.get(index++);
                    }
                }
                """);
        final Path classes = Files.createDirectories(root.resolve("classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), teleport.toString(), particle.toString(), directory.resolve("WireCheck.java").toString()));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            loader.loadClass("example.WireCheck").getMethod("verify").invoke(null);
        }
    }

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

    @Test
    void rewritesNestedBitSetCodecSlotsWithStableQualification() throws Exception {
        final Path root = sourceRoot();
        final Path directory = root.resolve("src/main/java/example");
        write(directory.resolve("Envelope.java"), """
                package example;

                record Envelope(int x, int z, SectionData data) {
                    static final NetworkBuffer.Type<Envelope> SERIALIZER = NetworkBufferTemplate.template(
                            NetworkBuffer.VAR_INT, Envelope::x,
                            NetworkBuffer.VAR_INT, Envelope::z,
                            SectionData.NETWORK_TYPE, Envelope::data,
                            Envelope::new);
                }
                """);
        final Path data = directory.resolve("SectionData.java");
        write(data, """
                package example;

                import java.util.BitSet;
                import java.util.List;

                import static example.NetworkBuffer.BITSET;
                import static example.NetworkBuffer.BYTE_ARRAY;

                record SectionData(BitSet firstMask, BitSet secondMask, BitSet emptyFirstMask,
                                   BitSet emptySecondMask, List<byte[]> firstUpdates, List<byte[]> secondUpdates) {
                    static final NetworkBuffer.Type<SectionData> NETWORK_TYPE = NetworkBufferTemplate.template(
                            BITSET, SectionData::firstMask,
                            NetworkBuffer.BITSET, SectionData::secondMask,
                            BITSET, SectionData::emptyFirstMask,
                            NetworkBuffer.BITSET, SectionData::emptySecondMask,
                            BYTE_ARRAY.list(256), SectionData::firstUpdates,
                            BYTE_ARRAY.list(256), SectionData::secondUpdates,
                            SectionData::new);
                }
                """);
        final List<PacketMigrationScanner.Migration> migrations = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> bitSetMigration("Envelope", List.of(2, index))).toList();

        RetainedPacketMigrator.apply(root, migrations);
        final String migrated = Files.readString(data);
        RetainedPacketMigrator.apply(root, migrations);

        assertEquals(migrated, Files.readString(data));
        assertEquals(4, occurrences(migrated, "BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray)"));
        assertEquals(2, occurrences(migrated,
                "NetworkBuffer.BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray)"));
        assertTrue(!migrated.contains("BITSET, SectionData::"));
        assertTrue(!migrated.contains("NetworkBuffer.BITSET, SectionData::"));
    }

    @Test
    void movesNestedDisplayPositionsToCollectionEntries() throws Exception {
        final Path root = sourceRoot();
        final Path source = root.resolve("src/main/java/example/AdvancementEnvelope.java");
        write(source, """
                package example;

                import org.jetbrains.annotations.Nullable;

                import java.util.List;

                record AdvancementEnvelope(boolean reset, List<Mapping> mappings) {
                    static final NetworkBuffer.Type<AdvancementEnvelope> SERIALIZER = NetworkBufferTemplate.template(
                            NetworkBuffer.BOOLEAN, AdvancementEnvelope::reset,
                            Mapping.SERIALIZER.list(), AdvancementEnvelope::mappings,
                            AdvancementEnvelope::new);

                    record Mapping(String key, Value value) {
                        static final NetworkBuffer.Type<Mapping> SERIALIZER = NetworkBufferTemplate.template(
                                NetworkBuffer.STRING, Mapping::key,
                                Value.SERIALIZER, Mapping::value,
                                Mapping::new);
                    }

                    record Value(@Nullable Display display) {
                        static final NetworkBuffer.Type<Value> SERIALIZER = NetworkBufferTemplate.template(
                                Display.SERIALIZER.optional(), Value::display, Value::new);
                    }

                    record Display(String title, float x, float y) {
                        static final NetworkBuffer.Type<Display> SERIALIZER = new NetworkBuffer.Type<>() {
                            public void write(NetworkBuffer buffer, Display value) {
                                buffer.write(NetworkBuffer.STRING, value.title);
                                buffer.write(NetworkBuffer.FLOAT, value.x);
                                buffer.write(NetworkBuffer.FLOAT, value.y);
                            }

                            public Display read(NetworkBuffer buffer) {
                                var title = buffer.read(NetworkBuffer.STRING);
                                var x = buffer.read(NetworkBuffer.FLOAT);
                                var y = buffer.read(NetworkBuffer.FLOAT);
                                return new Display(title, x, y);
                            }
                        };
                    }
                }
                """);
        final PacketMigrationScanner.Migration migration = new PacketMigrationScanner.Migration(
                new PacketUpdater.RetainedPacket("AdvancementEnvelope", "AdvancementEnvelope.SERIALIZER",
                        "before", "after"),
                PacketMigrationScanner.Kind.MOVED_NESTED_FLOATS, List.of(1), Map.of(), "", -1, -1, false);

        RetainedPacketMigrator.apply(root, List.of(migration));
        final String migrated = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(migration));

        assertEquals(migrated, Files.readString(source));
        assertTrue(migrated.contains("positionCompatibilityDelegate"));
        assertTrue(migrated.contains("import org.jetbrains.annotations.Nullable;"));
        assertTrue(migrated.contains("positionedValue == null ? 0.0f : positionedValue.x()"));
        assertTrue(migrated.contains("new Display(positionedValue.title(), position0, position1)"));
        assertTrue(migrated.contains("return new Mapping(value.key(), adjustedNested)"));
        assertTrue(!migrated.contains("buffer.write(NetworkBuffer.FLOAT, value.x)"));
        assertTrue(!migrated.contains("var x = buffer.read(NetworkBuffer.FLOAT)"));
        assertTrue(migrated.contains("new Display(title, 0.0f, 0.0f)"));
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

    private static PacketMigrationScanner.Migration bitSetMigration(String className, List<Integer> path) {
        return new PacketMigrationScanner.Migration(
                new PacketUpdater.RetainedPacket(className, className + ".SERIALIZER", "before", "after"),
                PacketMigrationScanner.Kind.BYTE_ARRAY_BIT_SET, path, Map.of(), "", -1, -1, false);
    }

    private static void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static int occurrences(String value, String target) {
        return (value.length() - value.replace(target, "").length()) / target.length();
    }
}
