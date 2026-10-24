package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentProjectionTest {
    private static final String MODE = "synthetic/wire/Mode";
    private static final String SPAWN = "synthetic/wire/Spawn";

    @Test
    void projectsRemovedComponentAndNullableEnumOntoOptionalVarInt() throws Exception {
        var classes = classes(1, 2);
        var before = classes.get(SPAWN + "/before");
        var after = classes.get(SPAWN + "/after");
        var baseline = WireSchema.scan(before, classes::get).orElseThrow();
        var target = WireSchema.scan(after, classes::get).orElseThrow();
        assertEquals(List.of("VAR_INT", "LONG", "ENUM_ID", "NULLABLE_BYTE_ID"),
                baseline.fields().stream().map(field -> field.codec().name()).toList());
        assertEquals(List.of("VAR_INT", "ENUM_ID", "OPTIONAL_VAR_INT_ENUM"),
                target.fields().stream().map(field -> field.codec().name()).toList());

        var plan = WireMigration.plan(before, after, classes::get).orElseThrow();
        assertEquals(List.of(0, 2, 3), plan.bindings().stream().map(WireMigration.Binding::source).toList());
        assertEquals("", plan.bindings().get(1).networkType());
        assertTrue(plan.bindings().get(2).networkType().contains("NetworkBuffer.OPTIONAL_VAR_INT"));
        assertTrue(plan.bindings().get(2).networkType().contains("$TYPE$.ALPHA"));
        assertFalse(plan.bindings().stream().anyMatch(binding -> binding.source() == 1));

        var wide = classes(200, 2);
        assertTrue(WireMigration.plan(wide.get(SPAWN + "/before"), wide.get(SPAWN + "/after"), wide::get).isEmpty());

        var root = Files.createTempDirectory("component-projection");
        var directory = root.resolve("src/main/java/example");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("Mode.java"), """
                package example;
                enum Mode { ALPHA, BETA }
                """);
        var spawn = directory.resolve("Spawn.java");
        Files.writeString(spawn, """
                package example;
                record Spawn(int token, long entropy, Mode mode, @Nullable Mode prior) {
                    static final NetworkBuffer.Type<Spawn> SERIALIZER = NetworkBufferTemplate.template(
                        NetworkBuffer.VAR_INT, Spawn::token, NetworkBuffer.LONG, Spawn::entropy,
                        Mode.NETWORK_TYPE, Spawn::mode, Mode.OPT_NETWORK_TYPE, Spawn::prior, Spawn::new);
                }
                @interface Nullable {}
                """);
        RetainedPacketMigrator.apply(root, List.of(PacketMigrationScanner.Migration.wire(plan).withPacket(
                new PacketUpdater.RetainedPacket("Spawn", "Spawn.SERIALIZER", SPAWN, SPAWN))));
        var migrated = Files.readString(spawn);
        assertTrue(migrated.contains("record Spawn(int token, Mode mode, @Nullable Mode prior)"));
        assertTrue(migrated.contains("Mode.ALPHA"));
        assertTrue(migrated.contains("NetworkBuffer.OPTIONAL_VAR_INT"));
        assertFalse(migrated.contains("$TYPE$"));
        assertFalse(Files.readString(root.resolve(".nightstorm/wire-adapters.json")).contains("$TYPE$"));
    }

    private static ClassNode node(byte[] bytecode) {
        var node = new ClassNode();
        new org.objectweb.asm.ClassReader(bytecode).accept(node, 0);
        return node;
    }

    private static Map<String, ClassNode> classes(int alphaId, int betaId) {
        var bytes = Map.of(
                MODE, mode(alphaId, betaId),
                SPAWN + "/before", manual(alphaId >= 0),
                SPAWN + "/after", composite(),
                "net/minecraft/network/codec/ByteBufCodecs", codecs(),
                "synthetic/wire/VarEnum", varEnum());
        var nodes = new java.util.HashMap<String, ClassNode>();
        bytes.forEach((name, bytecode) -> nodes.put(name, node(bytecode)));
        nodes.put(SPAWN, nodes.get(SPAWN + "/before"));
        return nodes;
    }

    private static byte[] mode(int alphaId, int betaId) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, MODE,
                "Ljava/lang/Enum<L" + MODE + ";>;", "java/lang/Enum", null);
        for (String name : List.of("ALPHA", "BETA")) writer.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, name, "L" + MODE + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "wireId", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BY_ID", "Ljava/util/function/IntFunction;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "STREAM_CODEC", WireSchema.STREAM_CODEC, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "OPTIONAL_STREAM_CODEC", WireSchema.STREAM_CODEC, null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;II)V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MODE, "wireId", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 4);
        var id = writer.visitMethod(Opcodes.ACC_PUBLIC, "getId", "()I", null, null);
        id.visitVarInsn(Opcodes.ALOAD, 0);
        id.visitFieldInsn(Opcodes.GETFIELD, MODE, "wireId", "I");
        id.visitInsn(Opcodes.IRETURN);
        id.visitMaxs(1, 1);
        var byId = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "byId", "(I)L" + MODE + ";", null, null);
        byId.visitFieldInsn(Opcodes.GETSTATIC, MODE, "BY_ID", "Ljava/util/function/IntFunction;");
        byId.visitVarInsn(Opcodes.ILOAD, 0);
        byId.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/IntFunction", "apply", "(I)Ljava/lang/Object;", true);
        byId.visitTypeInsn(Opcodes.CHECKCAST, MODE);
        byId.visitInsn(Opcodes.ARETURN);
        byId.visitMaxs(2, 1);
        var nullable = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "byNullableId", "(I)L" + MODE + ";", null, null);
        var present = new Label();
        nullable.visitVarInsn(Opcodes.ILOAD, 0);
        nullable.visitInsn(Opcodes.ICONST_M1);
        nullable.visitJumpInsn(Opcodes.IF_ICMPNE, present);
        nullable.visitInsn(Opcodes.ACONST_NULL);
        nullable.visitInsn(Opcodes.ARETURN);
        nullable.visitLabel(present);
        nullable.visitVarInsn(Opcodes.ILOAD, 0);
        nullable.visitMethodInsn(Opcodes.INVOKESTATIC, MODE, "byId", "(I)L" + MODE + ";", false);
        nullable.visitInsn(Opcodes.ARETURN);
        nullable.visitMaxs(2, 1);
        var nullableId = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getNullableId", "(L" + MODE + ";)I", null, null);
        var absent = new Label();
        var end = new Label();
        nullableId.visitVarInsn(Opcodes.ALOAD, 0);
        nullableId.visitJumpInsn(Opcodes.IFNULL, absent);
        nullableId.visitVarInsn(Opcodes.ALOAD, 0);
        nullableId.visitFieldInsn(Opcodes.GETFIELD, MODE, "wireId", "I");
        nullableId.visitJumpInsn(Opcodes.GOTO, end);
        nullableId.visitLabel(absent);
        nullableId.visitInsn(Opcodes.ICONST_M1);
        nullableId.visitLabel(end);
        nullableId.visitInsn(Opcodes.IRETURN);
        nullableId.visitMaxs(1, 1);
        lambda(writer, "decode", "(Ljava/util/OptionalInt;)Ljava/util/Optional;", true);
        lambda(writer, "encode", "(Ljava/util/Optional;)Ljava/util/OptionalInt;", false);
        var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        constant(clinit, "ALPHA", 0, alphaId);
        constant(clinit, "BETA", 1, betaId);
        var idHandle = new Handle(Opcodes.H_INVOKEVIRTUAL, MODE, "getId", "()I", false);
        clinit.visitInvokeDynamicInsn("applyAsInt", "()Ljava/util/function/ToIntFunction;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false), idHandle);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/ByIdMap", "continuous", "()Ljava/util/function/IntFunction;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, MODE, "BY_ID", "Ljava/util/function/IntFunction;");
        clinit.visitLdcInsn(Type.getObjectType(MODE));
        clinit.visitInvokeDynamicInsn("applyAsInt", "()Ljava/util/function/ToIntFunction;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false), idHandle);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs", "enumCodec",
                "(Ljava/lang/Class;Ljava/util/function/ToIntFunction;)Lnet/minecraft/network/codec/StreamCodec;", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, MODE, "STREAM_CODEC", WireSchema.STREAM_CODEC);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, WireSchema.BYTE_CODECS, "OPTIONAL_VAR_INT", WireSchema.STREAM_CODEC);
        clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                new Handle(Opcodes.H_INVOKESTATIC, MODE, "decode", "(Ljava/util/OptionalInt;)Ljava/util/Optional;", false));
        clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                new Handle(Opcodes.H_INVOKESTATIC, MODE, "encode", "(Ljava/util/Optional;)Ljava/util/OptionalInt;", false));
        clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/network/codec/StreamCodec", "map",
                "(Ljava/util/function/Function;Ljava/util/function/Function;)" + WireSchema.STREAM_CODEC, true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, MODE, "OPTIONAL_STREAM_CODEC", WireSchema.STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(6, 0);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void constant(org.objectweb.asm.MethodVisitor method, String name, int ordinal, int id) {
        method.visitTypeInsn(Opcodes.NEW, MODE);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(name);
        method.visitIntInsn(Opcodes.BIPUSH, ordinal);
        method.visitIntInsn(id >= -128 && id <= 127 ? Opcodes.BIPUSH : Opcodes.SIPUSH, id);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, MODE, "<init>", "(Ljava/lang/String;II)V", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, MODE, name, "L" + MODE + ";");
    }

    private static void lambda(ClassWriter writer, String name, String descriptor, boolean decode) {
        var method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, descriptor, null, null);
        var absent = new Label();
        var end = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, decode ? "java/util/OptionalInt" : "java/util/Optional", "isPresent", "()Z", false);
        method.visitJumpInsn(Opcodes.IFEQ, absent);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        if (decode) {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/OptionalInt", "getAsInt", "()I", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, MODE, "byId", "(I)L" + MODE + ";", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "of", "(Ljava/lang/Object;)Ljava/util/Optional;", false);
        } else {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "get", "()Ljava/lang/Object;", false);
            method.visitTypeInsn(Opcodes.CHECKCAST, MODE);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODE, "getId", "()I", false);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/OptionalInt", "of", "(I)Ljava/util/OptionalInt;", false);
        }
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(absent);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, decode ? "java/util/Optional" : "java/util/OptionalInt", "empty",
                decode ? "()Ljava/util/Optional;" : "()Ljava/util/OptionalInt;", false);
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 1);
    }

    private static byte[] manual(boolean ignored) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, SPAWN, null, "java/lang/Record", null);
        recordComponent(writer, "token", "I");
        recordComponent(writer, "entropy", "J");
        recordComponent(writer, "mode", "L" + MODE + ";");
        recordComponent(writer, "prior", "L" + MODE + ";");
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(IJL" + MODE + ";L" + MODE + ";)V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
        store(constructor, "token", "I", 1);
        store(constructor, "entropy", "J", 2);
        store(constructor, "mode", "L" + MODE + ";", 4);
        store(constructor, "prior", "L" + MODE + ";", 5);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 6);
        var read = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        read.visitVarInsn(Opcodes.ALOAD, 0);
        buffer(read, true, "VarInt");
        buffer(read, true, "Long");
        buffer(read, true, "Byte");
        read.visitMethodInsn(Opcodes.INVOKESTATIC, MODE, "byId", "(I)L" + MODE + ";", false);
        buffer(read, true, "Byte");
        read.visitMethodInsn(Opcodes.INVOKESTATIC, MODE, "byNullableId", "(I)L" + MODE + ";", false);
        read.visitMethodInsn(Opcodes.INVOKESPECIAL, SPAWN, "<init>", "(IJL" + MODE + ";L" + MODE + ";)V", false);
        read.visitInsn(Opcodes.RETURN);
        read.visitMaxs(6, 2);
        var write = writer.visitMethod(Opcodes.ACC_PUBLIC, "write", "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        writeField(write, "token", "I", "VarInt", false, false);
        writeField(write, "entropy", "J", "Long", false, false);
        writeField(write, "mode", "L" + MODE + ";", "Byte", true, false);
        writeField(write, "prior", "L" + MODE + ";", "Byte", false, true);
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(3, 2);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] composite() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD, SPAWN, null, "java/lang/Record", null);
        recordComponent(writer, "token", "I");
        recordComponent(writer, "mode", "L" + MODE + ";");
        writer.visitRecordComponent("prior", "Ljava/util/Optional;", "Ljava/util/Optional<L" + MODE + ";>;").visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "prior", "Ljava/util/Optional;", "Ljava/util/Optional<L" + MODE + ";>;", null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(IL" + MODE + ";Ljava/util/Optional;)V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false);
        store(constructor, "token", "I", 1);
        store(constructor, "mode", "L" + MODE + ";", 2);
        store(constructor, "prior", "Ljava/util/Optional;", 3);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 4);
        var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        accessor(clinit, WireSchema.BYTE_CODECS, "VAR_INT", "token", "()I");
        accessor(clinit, MODE, "STREAM_CODEC", "mode", "()L" + MODE + ";");
        accessor(clinit, MODE, "OPTIONAL_STREAM_CODEC", "prior", "()Ljava/util/Optional;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec", "composite", "()" + WireSchema.STREAM_CODEC, true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SPAWN, "STREAM_CODEC", WireSchema.STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(4, 0);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void accessor(org.objectweb.asm.MethodVisitor method, String owner, String codec, String name, String descriptor) {
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, codec, WireSchema.STREAM_CODEC);
        method.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                new Handle(Opcodes.H_INVOKEVIRTUAL, SPAWN, name, descriptor, false));
    }

    private static byte[] codecs() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "net/minecraft/network/codec/ByteBufCodecs", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enumCodec",
                "(Ljava/lang/Class;Ljava/util/function/ToIntFunction;)Lnet/minecraft/network/codec/StreamCodec;", null, null);
        method.visitTypeInsn(Opcodes.NEW, "synthetic/wire/VarEnum");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "synthetic/wire/VarEnum", "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 2);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] varEnum() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "synthetic/wire/VarEnum", null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        var decode = writer.visitMethod(Opcodes.ACC_PUBLIC, "decode", "(Lio/netty/buffer/ByteBuf;)Ljava/lang/Object;", null, null);
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/VarInt", "read", "(Lio/netty/buffer/ByteBuf;)I", false);
        decode.visitInsn(Opcodes.POP);
        decode.visitInsn(Opcodes.ACONST_NULL);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(1, 2);
        var encode = writer.visitMethod(Opcodes.ACC_PUBLIC, "encode", "(Lio/netty/buffer/ByteBuf;Ljava/lang/Object;)V", null, null);
        encode.visitVarInsn(Opcodes.ALOAD, 1);
        encode.visitInsn(Opcodes.ICONST_0);
        encode.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/VarInt", "write", "(Lio/netty/buffer/ByteBuf;I)Lio/netty/buffer/ByteBuf;", false);
        encode.visitInsn(Opcodes.POP);
        encode.visitInsn(Opcodes.RETURN);
        encode.visitMaxs(2, 3);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void recordComponent(ClassWriter writer, String name, String descriptor) {
        writer.visitRecordComponent(name, descriptor, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, name, descriptor, null, null).visitEnd();
    }

    private static void store(org.objectweb.asm.MethodVisitor method, String name, String descriptor, int slot) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Type.getType(descriptor).getOpcode(Opcodes.ILOAD), slot);
        method.visitFieldInsn(Opcodes.PUTFIELD, SPAWN, name, descriptor);
    }

    private static void buffer(org.objectweb.asm.MethodVisitor method, boolean read, String suffix) {
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", (read ? "read" : "write") + suffix,
                read ? "()" + (suffix.equals("Long") ? "J" : suffix.equals("Byte") ? "B" : "I") : "(I)Lnet/minecraft/network/FriendlyByteBuf;", false);
    }

    private static void writeField(org.objectweb.asm.MethodVisitor method, String name, String descriptor, String suffix,
                                   boolean id, boolean nullable) {
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, SPAWN, name, descriptor);
        if (id) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MODE, "getId", "()I", false);
        if (nullable) method.visitMethodInsn(Opcodes.INVOKESTATIC, MODE, "getNullableId", "(L" + MODE + ";)I", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", "write" + suffix,
                "(I)Lnet/minecraft/network/FriendlyByteBuf;", false);
        method.visitInsn(Opcodes.POP);
    }
}
