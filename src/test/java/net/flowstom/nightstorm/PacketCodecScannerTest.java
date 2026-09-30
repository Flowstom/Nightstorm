package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketCodecScannerTest {
    @Test
    void translatesCompositeListCodec() throws Exception {
        final var jar = Files.createTempFile("nightstorm-codec", ".jar");
        final String owner = "net/minecraft/network/protocol/common/GeneratedPacket";
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        writer.visitRecordComponent("effects", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/Integer;>;").visitEnd();
        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs", "INT",
                "Lnet/minecraft/network/codec/StreamCodec;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs", "list",
                "()Lnet/minecraft/network/codec/StreamCodec$CodecOperation;", true);
        clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/network/codec/StreamCodec", "apply",
                "(Lnet/minecraft/network/codec/StreamCodec$CodecOperation;)Lnet/minecraft/network/codec/StreamCodec;", true);
        getter(clinit, owner, "effects", "Ljava/util/List;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec", "composite",
                "()Lnet/minecraft/network/codec/StreamCodec;", true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", "Lnet/minecraft/network/codec/StreamCodec;");
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
        assertEquals("java.util.List<Integer>", shape.fields().getFirst().javaType());
        assertEquals("NetworkBuffer.INT.list()", shape.fields().getFirst().networkType());
    }

    @Test
    void leavesUnprovenSemanticCodecsOpaque() throws Exception {
        final var jar = Files.createTempFile("nightstorm-swing-codec", ".jar");
        final String owner = "net/minecraft/network/protocol/game/ClientboundSwingAnimationPacket";
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        writer.visitRecordComponent("entityId", "I", null).visitEnd();
        writer.visitRecordComponent("hand", "Lnet/minecraft/world/InteractionHand;", null).visitEnd();
        writer.visitRecordComponent("animation",
                "Lnet/minecraft/world/item/component/SwingAnimation;", null).visitEnd();
        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "VAR_INT", "Lnet/minecraft/network/codec/StreamCodec;");
        getter(clinit, owner, "entityId", "I");
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/InteractionHand",
                "STREAM_CODEC", "Lnet/minecraft/network/codec/StreamCodec;");
        getter(clinit, owner, "hand", "Lnet/minecraft/world/InteractionHand;");
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/item/component/SwingAnimation",
                "STREAM_CODEC", "Lnet/minecraft/network/codec/StreamCodec;");
        getter(clinit, owner, "animation", "Lnet/minecraft/world/item/component/SwingAnimation;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec", "composite",
                "()Lnet/minecraft/network/codec/StreamCodec;", true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", "Lnet/minecraft/network/codec/StreamCodec;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(3, 0);
        clinit.visitEnd();
        writer.visitEnd();
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(owner + ".class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }

        final var shape = PacketCodecScanner.scan(jar, owner.replace('/', '.'));

        assertTrue(shape.opaque());
        assertTrue(shape.warning().contains("Unsupported or mismatched codec"));
    }
    private static void getter(org.objectweb.asm.MethodVisitor method, String owner, String name, String descriptor) {
        method.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "synthetic/Bootstrap", "bootstrap", "()V", false),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKEVIRTUAL, owner, name, "()" + descriptor, false));
    }
}
