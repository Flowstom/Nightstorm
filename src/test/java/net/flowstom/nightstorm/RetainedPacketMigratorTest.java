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
                record Teleport(int id) implements ClientPacket.Play {
                    static final NetworkBuffer.Type<Teleport> SERIALIZER = new NetworkBuffer.Type<>() {
                        public void write(NetworkBuffer buffer, Teleport value) { buffer.write(NetworkBuffer.VAR_INT, value.id); }
                        public Teleport read(NetworkBuffer buffer) { return new Teleport(buffer.read(NetworkBuffer.VAR_INT)); }
                    };
                }
                """);
        final Path particle = directory.resolve("ParticlePacket.java");
        write(directory.resolve("Payload.java"), """
                package example;
                record Payload(int id) {
                    static final NetworkBuffer.Type<Payload> NETWORK_TYPE = new NetworkBuffer.Type<>() {
                        public void write(NetworkBuffer buffer, Payload value) {
                            buffer.write(NetworkBuffer.VAR_INT, value.id());
                            value.writeData(buffer);
                        }
                        public Payload read(NetworkBuffer buffer) {
                            return new Payload(buffer.read(NetworkBuffer.VAR_INT)).readData(buffer);
                        }
                    };
                    Payload readData(NetworkBuffer buffer) { return this; }
                    void writeData(NetworkBuffer buffer) { }
                }
                """);
        write(particle, """
                package example;
                import java.util.Objects;
                import static example.NetworkBuffer.*;
                record ParticlePacket(Payload payload, boolean overrideLimiter, boolean longDistance,
                        double x, double y, double z, float offsetX, float offsetY, float offsetZ,
                        float maxSpeed, int particleCount) {
                    static final NetworkBuffer.Type<ParticlePacket> SERIALIZER = new NetworkBuffer.Type<>() {
                        public void write(NetworkBuffer buffer, ParticlePacket value) {
                            buffer.write(BOOLEAN, value.overrideLimiter);
                            buffer.write(BOOLEAN, value.longDistance);
                            buffer.write(DOUBLE, value.x);
                            buffer.write(DOUBLE, value.y);
                            buffer.write(DOUBLE, value.z);
                            buffer.write(FLOAT, value.offsetX);
                            buffer.write(FLOAT, value.offsetY);
                            buffer.write(FLOAT, value.offsetZ);
                            buffer.write(FLOAT, value.maxSpeed);
                            buffer.write(INT, value.particleCount);
                            buffer.write(VAR_INT, value.payload.id());
                            value.payload.writeData(buffer);
                        }
                        public ParticlePacket read(NetworkBuffer buffer) { throw new UnsupportedOperationException(); }
                    };
                }
                """);
        final var migrations = List.of(
                PacketMigrationScanner.Migration.wire(WireFixtures.acknowledgement().plan()).withPacket(
                        new PacketUpdater.RetainedPacket("Teleport", "Teleport.SERIALIZER", "before", "after")),
                PacketMigrationScanner.Migration.wire(WireFixtures.effect().plan()).withPacket(
                        new PacketUpdater.RetainedPacket("ParticlePacket", "ParticlePacket.SERIALIZER", "before", "after")));
        RetainedPacketMigrator.apply(root, migrations);
        final String firstTeleport = Files.readString(teleport);
        final String firstParticle = Files.readString(particle);
        assertTrue(firstParticle.contains("buffer.write(Payload.NETWORK_TYPE, value.payload())"));
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
                        try {
                            Teleport.SERIALIZER.write(buffer, new Teleport(123));
                            throw new AssertionError("Must not guess defaults");
                        } catch (UnsupportedOperationException expected) {
                            if (!buffer.values.isEmpty()) throw new AssertionError("Failed write must not emit bytes");
                        }
                        var packet = new ParticlePacket(new Payload(300), true, false, 1d, 2d, 3d, 4f, 5f, 6f, 7f, 500);
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
                interface ClientPacket { interface Play { } }
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
                    <T> void write(Type<T> type, T value) { type.write(this, value); }
                    <T> T read(Type<T> type) { return type.read(this); }
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
                "-d", classes.toString(), teleport.toString(), particle.toString(), directory.resolve("Payload.java").toString(),
                directory.resolve("WireCheck.java").toString()));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            loader.loadClass("example.WireCheck").getMethod("verify").invoke(null);
        }
    }

    @Test
    void rejectsSourceFieldOrderMismatchWithoutWritingAdapterMetadata() throws Exception {
        final Path root = sourceRoot();
        final Path packet = root.resolve("src/main/java/example/Pair.java");
        write(packet, """
                package example;
                record Pair(int left, int right) {
                    static final NetworkBuffer.Type<Pair> SERIALIZER = NetworkBufferTemplate.template(
                        NetworkBuffer.INT, Pair::right, NetworkBuffer.INT, Pair::left, Pair::new);
                }
                """);
        final String original = Files.readString(packet);
        final var fields = List.of("left:I", "right:I");
        final var before = WireFixtures.node("synthetic/Pair", fields, List.of("INT", "INT"), List.of(0, 1), true);
        final var after = WireFixtures.node("synthetic/Pair", fields, List.of("INT", "INT"), List.of(1, 0), true);
        final var migration = PacketMigrationScanner.Migration.wire(WireMigration.plan(before, after, name -> null).orElseThrow())
                .withPacket(new PacketUpdater.RetainedPacket("Pair", "Pair.SERIALIZER", before.name, after.name));
        final var failure = assertThrows(IllegalStateException.class, () -> RetainedPacketMigrator.apply(root, List.of(migration)));
        assertTrue(failure.getMessage().contains("field bindings disagree"));
        assertEquals(original, Files.readString(packet));
        assertTrue(Files.notExists(root.resolve(".nightstorm/wire-adapters.json")));
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
        final PacketMigrationScanner.Migration migration = PacketMigrationScanner.Migration.suffix(List.of(), List.of(
                new WireMigration.Binding(1, -1, "boolean", "NetworkBuffer.BOOLEAN", "true")))
                .withPacket(new PacketUpdater.RetainedPacket("PulseNotice", "PulseNotice.SERIALIZER", "before", "after"));

        RetainedPacketMigrator.apply(root, List.of(migration));
        final String migrated = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(migration));

        assertEquals(migrated, Files.readString(source));
        assertEquals(1, occurrences(migrated, "wireSuffixDelegate ="));
        assertTrue(migrated.contains("buffer.write(NetworkBuffer.BOOLEAN, true)"));
        assertTrue(migrated.contains("buffer.read(NetworkBuffer.BOOLEAN)"));

        var next = PacketMigrationScanner.Migration.suffix(List.of(), List.of(
                new WireMigration.Binding(2, -1, "int", "NetworkBuffer.VAR_INT", "73"),
                new WireMigration.Binding(3, -1, "String", "NetworkBuffer.STRING", "\"arbitrary\"")))
                .withPacket(migration.packet());
        RetainedPacketMigrator.apply(root, List.of(next));
        final String composed = Files.readString(source);
        RetainedPacketMigrator.apply(root, List.of(next));
        assertEquals(composed, Files.readString(source));
        assertEquals(2, occurrences(composed, "wireSuffixDelegate ="));
        assertTrue(composed.contains("buffer.write(NetworkBuffer.VAR_INT, 73)"));
        assertTrue(composed.contains("buffer.write(NetworkBuffer.STRING, \"arbitrary\")"));
        assertTrue(composed.contains("Added field cannot be represented by the retained API"));
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
        assertEquals(4, occurrences(migrated,
                "NetworkBuffer.BYTE_ARRAY.transform(BitSet::valueOf, BitSet::toByteArray)"));
        assertTrue(!migrated.contains("BITSET, SectionData::"));
        assertTrue(!migrated.contains("NetworkBuffer.BITSET, SectionData::"));
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
        return PacketMigrationScanner.Migration.codec(path, CodecChange.enumeration("NetworkBuffer.OPTIONAL_VAR_INT", ids, true), "wire/Tone")
                .withPacket(new PacketUpdater.RetainedPacket(className, serializer, "before", "after"));
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
        return PacketMigrationScanner.Migration.codec(path, CodecChange.functions(new CodecFunctions.Adapter("java.util.BitSet", "NetworkBuffer.BYTE_ARRAY", "valueOf", "toByteArray")), "")
                .withPacket(new PacketUpdater.RetainedPacket(className, className + ".SERIALIZER", "before", "after"));
    }

    private static void write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static int occurrences(String value, String target) {
        return (value.length() - value.replace(target, "").length()) / target.length();
    }
}
