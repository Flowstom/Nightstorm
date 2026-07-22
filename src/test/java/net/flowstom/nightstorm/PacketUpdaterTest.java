package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketUpdaterTest {
    @Test
    void reordersRemovesAndGeneratesPacketRegistrations() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        Files.createDirectories(packetDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;

                import net.minestom.server.network.packet.server.ServerPacket;

                final class PacketVanilla {
                    static final PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(AlphaPacket.class, AlphaPacket.SERIALIZER),
                            entry(BetaPacket.class, BetaPacket.SERIALIZER)
                    );
                }
                """);
        final var baseline = report(
                packet(0, "CLIENTBOUND_ALPHA"),
                packet(1, "CLIENTBOUND_BETA"));
        final var target = report(
                packet(0, "CLIENTBOUND_BETA"),
                packet(1, "CLIENTBOUND_NEW_EFFECT"));

        final var result = PacketUpdater.update(sourceRoot, baseline, target, ignored ->
                new PacketCodecScanner.PacketShape(List.of(
                        new PacketCodecScanner.PacketField("effects", "java.util.List<net.kyori.adventure.key.Key>",
                                "NetworkBuffer.KEY.list()"))));

        assertEquals(2, result.packets());
        assertEquals(1, result.generatedPackets());
        assertTrue(result.warnings().isEmpty());
        final String updated = Files.readString(packetVanilla);
        assertFalse(updated.contains("AlphaPacket.class"));
        assertTrue(updated.indexOf("BetaPacket.class") < updated.indexOf("NightstormPlayClientboundNewEffectPacket.class"));
        assertTrue(updated.contains("import net.minestom.server.network.packet.nightstorm.*;"));
        final String generated = Files.readString(packetDirectory.resolve("nightstorm/NightstormPlayClientboundNewEffectPacket.java"));
        assertTrue(generated.contains("implements ServerPacket.Play"));
        assertTrue(generated.contains("record NightstormPlayClientboundNewEffectPacket(List<Key> effects)"));
        assertTrue(generated.contains("NetworkBuffer.KEY.list().transform"));
    }

    @Test
    void generatesOpaquePacketAndWarningForUnsupportedCodec() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        Files.createDirectories(packetDirectory);
        Files.writeString(packetDirectory.resolve("PacketVanilla.java"), """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER);
                }
                """);
        final var target = report(packet(0, "CLIENTBOUND_UNKNOWN"));

        final var result = PacketUpdater.update(sourceRoot, report(), target,
                ignored -> PacketCodecScanner.PacketShape.opaque("unsupported test codec"));

        assertEquals(1, result.warnings().size());
        final String generated = Files.readString(packetDirectory.resolve("nightstorm/NightstormPlayClientboundUnknownPacket.java"));
        assertTrue(generated.contains("NetworkBuffer.RAW_BYTES"));
        final String warnings = Files.readString(sourceRoot.resolve(".nightstorm/packet-warnings.md"));
        assertTrue(warnings.contains("CLIENTBOUND_UNKNOWN"));
        assertTrue(warnings.contains("unsupported test codec"));
    }

    @Test
    void removesGeneratedPacketImportWhenNoPacketsAreGenerated() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var generatedDirectory = packetDirectory.resolve("nightstorm");
        Files.createDirectories(generatedDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.nightstorm.*;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(AlphaPacket.class, AlphaPacket.SERIALIZER)
                    );
                }
                """);
        final var stalePacket = generatedDirectory.resolve("NightstormPlayClientboundStalePacket.java");
        Files.writeString(stalePacket, "stale");
        final var packets = report(packet(0, "CLIENTBOUND_ALPHA"));

        final var result = PacketUpdater.update(sourceRoot, packets, packets, ignored -> null);

        assertEquals(0, result.generatedPackets());
        assertFalse(Files.readString(packetVanilla).contains("packet.nightstorm.*"));
        assertFalse(Files.exists(stalePacket));
    }

    @Test
    void regeneratesReferencedPacketsWhenBaselineMatchesTarget() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var generatedDirectory = packetDirectory.resolve("nightstorm");
        Files.createDirectories(generatedDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.nightstorm.*;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(NightstormPlayClientboundNewEffectPacket.class, NightstormPlayClientboundNewEffectPacket.SERIALIZER)
                    );
                }
                """);
        final var generatedPacket = generatedDirectory.resolve("NightstormPlayClientboundNewEffectPacket.java");
        Files.writeString(generatedPacket, "stale");
        final var packets = report(packet(0, "CLIENTBOUND_NEW_EFFECT"));

        final var result = PacketUpdater.update(sourceRoot, packets, packets, ignored ->
                new PacketCodecScanner.PacketShape(List.of(new PacketCodecScanner.PacketField(
                        "effects", "java.util.List<net.kyori.adventure.key.Key>", "NetworkBuffer.KEY.list()"))));

        assertEquals(1, result.generatedPackets());
        assertTrue(Files.readString(packetVanilla).contains("packet.nightstorm.*"));
        final String generatedSource = Files.readString(generatedPacket);
        assertTrue(generatedSource.contains("import java.util.List;"));
        assertTrue(generatedSource.contains("import net.kyori.adventure.key.Key;"));
        assertTrue(generatedSource.contains("record NightstormPlayClientboundNewEffectPacket(List<Key> effects)"));
        assertTrue(generatedSource.contains("NetworkBuffer.KEY.list().transform"));
    }

    @Test
    void migratesNestedEnumToOptionalVarIntWithExplicitIds() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var sourceDirectory = packetDirectory.resolve("server/play");
        Files.createDirectories(sourceDirectory);
        Files.writeString(packetDirectory.resolve("PacketVanilla.java"), """
                package net.minestom.server.network.packet;

                import net.minestom.server.network.packet.server.play.Envelope;

                final class PacketVanilla {
                    static final PacketRegistry<Object> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(Envelope.class, Envelope.SERIALIZER),
                            entry(Envelope.class, Envelope.SERIALIZER)
                    );
                }
                """);
        Files.writeString(sourceDirectory.resolve("Envelope.java"), """
                package net.minestom.server.network.packet.server.play;

                import net.minestom.server.network.NetworkBuffer;
                import net.minestom.server.network.NetworkBufferTemplate;

                record Envelope(int marker, Payload payload) {
                    static final NetworkBuffer.Type<Envelope> SERIALIZER = NetworkBufferTemplate.template(
                            NetworkBuffer.VAR_INT, Envelope::marker,
                            Payload.NETWORK_TYPE, Envelope::payload,
                            Envelope::new
                    );
                }
                """);
        final var payload = sourceDirectory.resolve("Payload.java");
        Files.writeString(payload, """
                package net.minestom.server.network.packet.server.play;

                import net.minestom.server.network.NetworkBuffer;
                import net.minestom.server.network.NetworkBufferTemplate;
                import org.jetbrains.annotations.Nullable;

                record Payload(String label, @Nullable Tone tone) {
                    static final NetworkBuffer.Type<Payload> NETWORK_TYPE = NetworkBufferTemplate.template(
                            NetworkBuffer.STRING, Payload::label,
                            Tone.NETWORK_TYPE, Payload::tone,
                            Payload::new
                    );
                }
                """);
        Files.writeString(sourceDirectory.resolve("Tone.java"), """
                package net.minestom.server.network.packet.server.play;

                enum Tone {
                    QUIET,
                    LOUD;

                    static final net.minestom.server.network.NetworkBuffer.Type<Tone> NETWORK_TYPE = null;
                }
                """);
        final var baselineJar = packetMigrationJar(false);
        final var targetJar = packetMigrationJar(true);
        final var baseline = report(
                packet(0, "CLIENTBOUND_FIRST", "synthetic.packet.EnvelopeWire"),
                packet(1, "CLIENTBOUND_SECOND", "synthetic.packet.EnvelopeWire"));
        final var target = report(
                packet(0, "CLIENTBOUND_FIRST", "synthetic.packet.EnvelopeWire"),
                packet(1, "CLIENTBOUND_SECOND", "synthetic.packet.EnvelopeWire"));

        PacketUpdater.update(sourceRoot, baseline, target, baselineJar, targetJar);

        final String updated = Files.readString(payload);
        assertEquals(1, occurrences(updated, "OPTIONAL_VAR_INT"));
        assertFalse(updated.contains(".optional()"));
        assertTrue(updated.contains("case 11 -> Tone.QUIET"));
        assertTrue(updated.contains("case 3 -> Tone.LOUD"));
        assertTrue(updated.contains("case QUIET -> 11"));
        assertTrue(updated.contains("case LOUD -> 3"));
        assertTrue(updated.contains("value == null ? null"));
    }

    @Test
    void productionRerunUsesPersistedCurrentSchema() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        Files.createDirectories(packetDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static final PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(AlphaPacket.class, AlphaPacket.SERIALIZER),
                            entry(BetaPacket.class, BetaPacket.SERIALIZER)
                    );
                }
                """);
        final String owner = "synthetic/packet/ReorderWire";
        final var jar = jarWithClass(owner, recordClass(owner, List.of(new Component("value", "I", null))));
        final var baseline = report(packet(0, "CLIENTBOUND_ALPHA", owner.replace('/', '.')),
                packet(1, "CLIENTBOUND_BETA", owner.replace('/', '.')));
        final var target = report(packet(0, "CLIENTBOUND_BETA", owner.replace('/', '.')),
                packet(1, "CLIENTBOUND_ALPHA", owner.replace('/', '.')));
        final var schema = sourceRoot.resolve(".nightstorm/packet-schema.json");
        Json.write(schema, baseline);

        PacketUpdater.update(sourceRoot, baseline, target, jar, jar, schema);
        final String first = Files.readString(packetVanilla);
        PacketUpdater.update(sourceRoot, baseline, target, jar, jar, schema);
        final String second = Files.readString(packetVanilla);

        assertEquals(first, second);
        assertEquals(target.packets(), Json.read(schema, PacketScanner.PacketReport.class).packets());
        assertTrue(second.indexOf("BetaPacket.class") < second.indexOf("AlphaPacket.class"));
    }

    @Test
    void migrationFailureLeavesSourceTreeUntouched() throws Exception {
        final var sourceRoot = Files.createTempDirectory("nightstorm-source");
        final var packetDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet");
        final var generatedDirectory = packetDirectory.resolve("nightstorm");
        Files.createDirectories(generatedDirectory);
        final var packetVanilla = packetDirectory.resolve("PacketVanilla.java");
        Files.writeString(packetVanilla, """
                package net.minestom.server.network.packet;
                import net.minestom.server.network.packet.server.ServerPacket;
                final class PacketVanilla {
                    static final PacketRegistry<ServerPacket.Play> SERVER_PLAY = registry(ConnectionState.PLAY, PacketRegistry.ConnectionSide.SERVER,
                            entry(Envelope.class, Envelope.SERIALIZER)
                    );
                }
                """);
        final var stale = generatedDirectory.resolve("NightstormStalePacket.java");
        Files.writeString(stale, "stale");
        final String originalRegistry = Files.readString(packetVanilla);
        final String owner = "synthetic/packet/EnvelopeWire";
        final Path baselineJar = jarWithClass(owner, recordClass(owner,
                List.of(new Component("value", "I", null))));
        final Path targetJar = jarWithClass(owner, recordClass(owner, List.of(
                new Component("value", "I", null), new Component("extra", "I", null))));
        final var packets = report(packet(0, "CLIENTBOUND_ENVELOPE", owner.replace('/', '.')));

        assertThrows(IllegalStateException.class,
                () -> PacketUpdater.update(sourceRoot, packets, packets, baselineJar, targetJar));

        assertEquals(originalRegistry, Files.readString(packetVanilla));
        assertEquals("stale", Files.readString(stale));
    }

    private static Path packetMigrationJar(boolean optional) throws Exception {
        final var jar = Files.createTempFile("nightstorm-migration", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            addClass(output, recordClass("synthetic/packet/EnvelopeWire", List.of(
                    new Component("marker", "I", null),
                    new Component("payload", "Lsynthetic/packet/PayloadWire;", null))));
            addClass(output, recordClass("synthetic/packet/PayloadWire", List.of(
                    new Component("label", "Ljava/lang/String;", null),
                    optional
                            ? new Component("tone", "Ljava/util/Optional;",
                            "Ljava/util/Optional<Lsynthetic/value/ToneWire;>;")
                            : new Component("tone", "Lsynthetic/value/ToneWire;", null))));
            final Map<String, Integer> ids = new LinkedHashMap<>();
            ids.put("QUIET", 11);
            ids.put("LOUD", 3);
            addClass(output, enumClass("synthetic/value/ToneWire", ids, optional));
        }
        return jar;
    }

    private static byte[] recordClass(String owner, List<Component> components) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STREAM_CODEC",
                "Lnet/minecraft/network/codec/StreamCodec;", null, null).visitEnd();
        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (Component component : components) {
            final String componentOwner;
            final String codecField;
            if (component.signature() != null && component.signature().startsWith("Ljava/util/Optional<L")) {
                componentOwner = component.signature().substring("Ljava/util/Optional<L".length(),
                        component.signature().length() - ";>;".length());
                codecField = "MAYBE_CODEC";
            } else if (component.descriptor().startsWith("Lsynthetic/")) {
                componentOwner = component.descriptor().substring(1, component.descriptor().length() - 1);
                codecField = "STREAM_CODEC";
            } else if (component.descriptor().equals("I")) {
                componentOwner = "net/minecraft/network/codec/ByteBufCodecs";
                codecField = "VAR_INT";
            } else if (component.descriptor().equals("Z")) {
                componentOwner = "net/minecraft/network/codec/ByteBufCodecs";
                codecField = "BOOL";
            } else {
                continue;
            }
            clinit.visitFieldInsn(Opcodes.GETSTATIC, componentOwner, codecField,
                    "Lnet/minecraft/network/codec/StreamCodec;");
            clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory",
                            "bootstrap", "()V", false),
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKEVIRTUAL, owner, component.name(),
                            "()" + component.descriptor(), false));
            clinit.visitInsn(Opcodes.POP);
            clinit.visitInsn(Opcodes.POP);
        }
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec", "composite",
                "()Lnet/minecraft/network/codec/StreamCodec;", true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC",
                "Lnet/minecraft/network/codec/StreamCodec;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path jarWithClass(String owner, byte[] bytecode) throws Exception {
        final var jar = Files.createTempFile("nightstorm-record", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(owner + ".class"));
            output.write(bytecode);
            output.closeEntry();
        }
        return jar;
    }

    private static byte[] enumClass(String owner, Map<String, Integer> ids, boolean optional) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                owner, "Ljava/lang/Enum<L" + owner + ";>;", "java/lang/Enum", null);
        ids.keySet().forEach(name -> writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_ENUM, name, "L" + owner + ";", null, null).visitEnd());
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "wireId", "I", null, null).visitEnd();
        if (optional) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MAYBE_CODEC",
                    "Lnet/minecraft/network/codec/StreamCodec;",
                    "Lnet/minecraft/network/codec/StreamCodec<Ljava/lang/Object;Ljava/util/Optional<L" + owner + ";>;>;",
                    null).visitEnd();
        }
        final var constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "wireId", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 4);
        constructor.visitEnd();
        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        int ordinal = 0;
        for (Map.Entry<String, Integer> entry : ids.entrySet()) {
            clinit.visitTypeInsn(Opcodes.NEW, owner);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn(entry.getKey());
            clinit.visitIntInsn(Opcodes.BIPUSH, ordinal++);
            clinit.visitIntInsn(Opcodes.BIPUSH, entry.getValue());
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(Ljava/lang/String;II)V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, entry.getKey(), "L" + owner + ";");
        }
        if (optional) {
            clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs", "OPTIONAL_VAR_INT",
                    "Lnet/minecraft/network/codec/StreamCodec;");
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "MAYBE_CODEC", "Lnet/minecraft/network/codec/StreamCodec;");
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(5, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addClass(JarOutputStream output, byte[] bytecode) throws Exception {
        final var reader = new org.objectweb.asm.ClassReader(bytecode);
        output.putNextEntry(new JarEntry(reader.getClassName() + ".class"));
        output.write(bytecode);
        output.closeEntry();
    }

    private static int occurrences(String value, String target) {
        return (value.length() - value.replace(target, "").length()) / target.length();
    }

    private static PacketScanner.PacketReport report(PacketScanner.PacketCandidate... packets) {
        return new PacketScanner.PacketReport(Instant.EPOCH.toString(), List.of(packets));
    }

    private static PacketScanner.PacketCandidate packet(int id, String name) {
        return packet(id, name, "net.minecraft.network.protocol.game." + name);
    }

    private static PacketScanner.PacketCandidate packet(int id, String name, String codecOwner) {
        return new PacketScanner.PacketCandidate("play", "clientbound", id,
                "net.minecraft.network.protocol.game.GamePacketTypes#" + name,
                codecOwner, "existing-or-opaque");
    }

    private record Component(String name, String descriptor, String signature) {
    }
}
