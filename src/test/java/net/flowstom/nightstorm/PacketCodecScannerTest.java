package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PacketCodecScannerTest {
    @Test
    void translatesCompositeListCodec() throws Exception {
        final var jar = Files.createTempFile("nightstorm-codec", ".jar");
        final String owner = "net/minecraft/network/protocol/common/GeneratedPacket";
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        writer.visitRecordComponent("effects", "Ljava/util/List;",
                "Ljava/util/List<Lnet/minecraft/resources/Identifier;>;").visitEnd();
        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/resources/Identifier", "STREAM_CODEC",
                "Lnet/minecraft/network/codec/StreamCodec;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs", "list",
                "()Lnet/minecraft/network/codec/StreamCodec$CodecOperation;", true);
        clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/network/codec/StreamCodec", "apply",
                "(Lnet/minecraft/network/codec/StreamCodec$CodecOperation;)Lnet/minecraft/network/codec/StreamCodec;", true);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        writer.visitEnd();
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(owner + ".class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }

        final var shape = PacketCodecScanner.scan(jar, owner.replace('/', '.'));

        assertFalse(shape.opaque());
        assertEquals(1, shape.fields().size());
        assertEquals("effects", shape.fields().getFirst().name());
        assertEquals("java.util.List<net.kyori.adventure.key.Key>", shape.fields().getFirst().javaType());
        assertEquals("NetworkBuffer.KEY.list()", shape.fields().getFirst().networkType());
    }
}
