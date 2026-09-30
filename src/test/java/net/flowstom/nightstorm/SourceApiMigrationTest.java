package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceApiMigrationTest {
    @TempDir Path root;

    @Test
    void migratesSharedNestedRecordsAndMapApisAcrossSuccessiveSchemas() throws Exception {
        final Path source = root.resolve("src/main/java/example/Check.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                import java.util.*;
                import java.util.function.*;
                record Shared(int key, long obsolete, boolean flag) {
                    static final NetworkBuffer.Type<Shared> CODEC = NetworkBufferTemplate.template(
                        NetworkBuffer.VAR_INT, Shared::key, NetworkBuffer.LONG, Shared::obsolete,
                        NetworkBuffer.BOOLEAN, Shared::flag, Shared::new);
                }
                record First(Shared state) {
                    static final NetworkBuffer.Type<First> CODEC = NetworkBufferTemplate.template(Shared.CODEC, First::state, First::new);
                }
                record Second(Shared state) {
                    static final NetworkBuffer.Type<Second> CODEC = NetworkBufferTemplate.template(Shared.CODEC, Second::state, Second::new);
                }
                record Destination(String hostname, int number) {
                    static final NetworkBuffer.Type<Destination> CODEC = NetworkBufferTemplate.template(
                        NetworkBuffer.STRING, Destination::hostname, NetworkBuffer.VAR_INT, Destination::number, Destination::new);
                }
                public class Check {
                    public static void verify() {
                        var buffer = new NetworkBuffer();
                        var legacy = new Shared(7, 987654321L, true);
                        Shared.CODEC.write(buffer, legacy);
                        if (!buffer.values.equals(List.of(7, true))) throw new AssertionError(buffer.values);
                        if (!Shared.CODEC.read(buffer).equals(legacy) || buffer.index != 2) throw new AssertionError();
                        buffer = new NetworkBuffer();
                        var target = new Destination("host", 25565, Map.of("key", "value"));
                        Destination.CODEC.write(buffer, target);
                        if (!Destination.CODEC.read(buffer).equals(target) || buffer.index != 3) throw new AssertionError();
                        if (!new Destination("host", 25565).attributes().isEmpty()) throw new AssertionError();
                    }
                }
                class NetworkBuffer {
                    interface Type<T> {
                        void write(NetworkBuffer buffer, T value);
                        T read(NetworkBuffer buffer);
                        default <V> Type<Map<T, V>> mapValue(Type<V> value) { return new Scalar<>(); }
                    }
                    static class Scalar<T> implements Type<T> {
                        public void write(NetworkBuffer buffer, T value) { buffer.values.add(value); }
                        public T read(NetworkBuffer buffer) { return (T) buffer.values.get(buffer.index++); }
                    }
                    static final Type<String> STRING = new Scalar<>();
                    static final Type<Integer> VAR_INT = new Scalar<>();
                    static final Type<Long> LONG = new Scalar<>();
                    // A lambda named value must not collide with the generated method's parameter.
                    static final Type<Boolean> BOOLEAN = new Scalar<>();
                    final List<Object> values = new ArrayList<>();
                    int index;
                    <T> void write(Type<T> type, T value) { type.write(this, value); }
                    <T> T read(Type<T> type) { return type.read(this); }
                }
                class NetworkBufferTemplate {
                    static <T, A> NetworkBuffer.Type<T> template(NetworkBuffer.Type<A> codec, Function<T, A> get, Function<A, T> create) {
                        return new NetworkBuffer.Type<>() {
                            public void write(NetworkBuffer buffer, T value) { codec.write(buffer, get.apply(value)); }
                            public T read(NetworkBuffer buffer) { return create.apply(codec.read(buffer)); }
                        };
                    }
                }
                """);
        final var unit = new com.github.javaparser.JavaParser().parse(Files.readString(source)).getResult().orElseThrow();
        for (var declaration : unit.getTypes().stream().toList()) {
            if (!declaration.isRecordDeclaration()) continue;
            Files.writeString(source.resolveSibling(declaration.getNameAsString() + ".java"),
                    "package example;\nimport java.util.*;\n" + declaration);
            declaration.remove();
        }
        Files.writeString(source, unit.toString());
        final var removed = WireFixtures.removal();
        final var collection = WireFixtures.collectionAddition();
        final var first = new PacketUpdater.RetainedPacket("First", "First.CODEC", "before", "after");
        final var second = new PacketUpdater.RetainedPacket("Second", "Second.CODEC", "before", "after");
        final var map = new PacketUpdater.RetainedPacket("Destination", "Destination.CODEC", "before", "after");
        final var migrations = List.of(PacketMigrationScanner.Migration.wire(removed.plan(), List.of(0)).withPacket(first),
                PacketMigrationScanner.Migration.wire(removed.plan(), List.of(0)).withPacket(second),
                PacketMigrationScanner.Migration.wire(collection.plan()).withPacket(map));
        RetainedPacketMigrator.apply(root, migrations);
        final String initial = Files.readString(source);
        RetainedPacketMigrator.apply(root, migrations);
        assertEquals(initial, Files.readString(source));
        verify(source);
        final var finalSchema = WireFixtures.node(removed.after().name, List.of("token:I"), List.of("VAR_INT"), List.of(0), true);
        final var next = WireMigration.plan(removed.after(), finalSchema, ignored -> null).orElseThrow();
        RetainedPacketMigrator.apply(root, List.of(PacketMigrationScanner.Migration.wire(next, List.of(0)).withPacket(first),
                PacketMigrationScanner.Migration.wire(next, List.of(0)).withPacket(second)));
        final String finalSource = Files.readString(source.resolveSibling("Shared.java"));
        assertTrue(finalSource.contains("record Shared(int key)"), finalSource);
        assertTrue(finalSource.contains("this(key);"), finalSource);
        // Both historical constructor signatures remain callable after another removal.
        final var classes = root.resolve("next-classes");
        Files.createDirectories(classes);
        compile(source, classes);
    }

    private void verify(Path source) throws Exception {
        final var classes = root.resolve("classes");
        Files.createDirectories(classes);
        compile(source, classes);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
            loader.loadClass("example.Check").getMethod("verify").invoke(null);
        }
    }

    private static void compile(Path source, Path classes) throws Exception {
        final var arguments = new java.util.ArrayList<String>(List.of("-d", classes.toString()));
        try (var files = Files.list(source.getParent())) {
            arguments.addAll(files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
    }
}
