package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
class PacketScannerTest {
    @Test
    void ignoresJarsWithoutProtocolBootstrapClasses() throws Exception {
        final var jar = Files.createTempFile("nightstorm-packets", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            // A valid empty JAR is sufficient for this boundary case.
        }
        final var report = PacketScanner.scan(jar);
        assertEquals(0, report.packets().size());
    }

    @Test
    void scansHandshakeAlternateCodecsAndBundlePackets() throws Exception {
        final var jar = Files.createTempFile("nightstorm-packets", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeProtocol(output, "net/minecraft/network/protocol/handshake/HandshakeProtocols",
                    "net/minecraft/network/protocol/handshake/HandshakePacketTypes", "CLIENT_INTENTION",
                    "net/minecraft/network/protocol/handshake/ClientIntentionPacket", "CONFIG_STREAM_CODEC", "addPacket");
            writeProtocol(output, "net/minecraft/network/protocol/game/GameProtocols",
                    "net/minecraft/network/protocol/game/GamePacketTypes", "CLIENTBOUND_BUNDLE",
                    "net/minecraft/network/protocol/game/ClientboundBundleDelimiterPacket", null, "withBundlePacket");
            writeTypes(output, "net/minecraft/network/protocol/handshake/HandshakePacketTypes", "CLIENT_INTENTION", "SERVERBOUND");
            writeTypes(output, "net/minecraft/network/protocol/game/GamePacketTypes", "CLIENTBOUND_BUNDLE", "CLIENTBOUND");
        }

        final var packets = PacketScanner.scan(jar).packets();
        assertEquals(2, packets.size());
        final var handshake = packets.stream().filter(packet -> packet.state().equals("handshake")).findFirst().orElseThrow();
        final var bundle = packets.stream().filter(packet -> packet.handling().equals("bundle")).findFirst().orElseThrow();
        assertEquals(new PacketScanner.PacketCandidate("handshake", "serverbound", 0,
                "net.minecraft.network.protocol.handshake.HandshakePacketTypes#CLIENT_INTENTION",
                "net.minecraft.network.protocol.handshake.ClientIntentionPacket", "existing-or-opaque"), handshake);
        assertEquals(0, bundle.id());
    }

    @Test
    void derivesDirectionFromConstructorRatherThanMisleadingFieldNames() throws Exception {
        final var jar = Files.createTempFile("nightstorm-flow", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            String owner = "net/minecraft/network/protocol/game/OtherPacketTypes";
            writeProtocol(output, "net/minecraft/network/protocol/game/OtherProtocols", owner,
                    "CLIENTBOUND_MISLEADING", "synthetic/UnrelatedPacket", "CODEC", "addPacket");
            writeTypes(output, owner, "CLIENTBOUND_MISLEADING", "SERVERBOUND");
        }
        assertEquals("serverbound", PacketScanner.scan(jar).packets().getFirst().direction());
    }

    private static void writeTypes(JarOutputStream output, String owner, String field, String flow) throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitTypeInsn(Opcodes.NEW, "net/minecraft/network/protocol/PacketType");
        method.visitInsn(Opcodes.DUP);
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/protocol/PacketFlow", flow,
                "Lnet/minecraft/network/protocol/PacketFlow;");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/network/protocol/PacketType", "<init>",
                "(Lnet/minecraft/network/protocol/PacketFlow;Ljava/lang/Object;)V", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, field, "Lnet/minecraft/network/protocol/PacketType;");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 0);
        method.visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(owner + ".class"));
        output.write(writer.toByteArray());
        output.closeEntry();
    }

    private static void writeProtocol(JarOutputStream output, String className, String packetTypesOwner,
                                      String packetType, String codecOwner, String codecField, String builderMethod) throws Exception {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_STATIC, "register", "()V", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, packetTypesOwner, packetType,
                "Lnet/minecraft/network/protocol/PacketType;");
        if (codecField != null) {
            method.visitFieldInsn(Opcodes.GETSTATIC, codecOwner, codecField,
                    "Lnet/minecraft/network/codec/StreamCodec;");
        } else {
            method.visitTypeInsn(Opcodes.NEW, codecOwner);
        }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/protocol/ProtocolInfoBuilder",
                builderMethod, "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        output.putNextEntry(new JarEntry(className + ".class"));
        output.write(writer.toByteArray());
        output.closeEntry();
    }
}
