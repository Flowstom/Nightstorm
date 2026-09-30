package net.flowstom.nightstorm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;

final class WireFixtures {
    static final String STREAM = WireSchema.STREAM_CODEC;
    static final String PARTICLE = "net/minecraft/core/particles/ParticleTypes#STREAM_CODEC";

    record Pair(ClassNode before, ClassNode after, Map<String, ClassNode> extra) {
        WireMigration plan() { return WireMigration.plan(before, after, extra::get).orElseThrow(); }
    }

    static Pair acknowledgement() {
        String owner = "synthetic/wire/Acknowledgement";
        return new Pair(node(owner, List.of("token:I"), List.of("VAR_INT"), List.of(0), false),
                node(owner, List.of("token:I", "east:D", "up:D", "north:D", "yaw:F", "pitch:F"),
                        List.of("VAR_INT", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT"), List.of(0, 1, 2, 3, 4, 5), true), Map.of());
    }

    static Pair collectionAddition() {
        final String owner = "synthetic/wire/Destination";
        final var before = node(owner, List.of("address:Ljava/lang/String;", "number:I"),
                List.of("STRING_UTF8", "VAR_INT"), List.of(0, 1), true);
        final var after = node(owner, List.of("address:Ljava/lang/String;", "number:I", "attributes:Ljava/util/Map;"),
                List.of("STRING_UTF8", "VAR_INT", "FAKE"), List.of(0, 1, 2), true);
        after.recordComponents.getLast().signature = "Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>;";
        final var initializer = after.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        for (var instruction : initializer.instructions.toArray()) {
            if (!(instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) || !field.name.equals("FAKE")) continue;
            final var code = new org.objectweb.asm.tree.InsnList();
            code.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("apply", "()Ljava/util/function/IntFunction;",
                    new Handle(Opcodes.H_INVOKESTATIC, "synthetic/Factory", "bootstrap", "()V", false)));
            code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, WireSchema.BYTE_CODECS, "STRING_UTF8", STREAM));
            code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, WireSchema.BYTE_CODECS, "STRING_UTF8", STREAM));
            code.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, WireSchema.BYTE_CODECS, "map",
                    "(Ljava/util/function/IntFunction;" + STREAM + STREAM + ")" + STREAM, true));
            initializer.instructions.insertBefore(instruction, code);
            initializer.instructions.remove(instruction);
        }
        return new Pair(before, after, Map.of());
    }

    static Pair removal() {
        final String owner = "synthetic/wire/State";
        return new Pair(node(owner, List.of("token:I", "entropy:J", "active:Z"), List.of("VAR_INT", "LONG", "BOOL"), List.of(0, 1, 2), true),
                node(owner, List.of("token:I", "active:Z"), List.of("VAR_INT", "BOOL"), List.of(0, 1), true), Map.of());
    }

    static Pair effect() {
        String owner = "synthetic/wire/Effect", mode = owner + "$Distribution";
        var oldFields = List.of("effect:Lnet/minecraft/core/particles/ParticleOptions;", "force:Z", "far:Z", "a:D", "b:D", "c:D",
                "da:F", "db:F", "dc:F", "speed:F", "amount:I");
        var newFields = List.of("effect:Lnet/minecraft/core/particles/ParticleOptions;", "force:Z", "far:Z", "a:D", "b:D", "c:D",
                "da:F", "db:F", "dc:F", "firstSpeed:F", "secondSpeed:F", "thirdSpeed:F", "amount:I", "distribution:L" + mode + ";");
        var before = node(owner, oldFields, List.of(PARTICLE, "BOOL", "BOOL", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "INT"),
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0), false);
        var after = node(owner, newFields, List.of(PARTICLE, "BOOL", "BOOL", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "VAR_INT", mode + "#STREAM_CODEC"),
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), true);
        delegate(after, oldFields, newFields, List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9, 10), mode, "NORMAL");
        var enumNode = new ClassNode();
        new ClassReader(PacketMigrationScannerTest.binaryEnumClass(mode, "NORMAL", 7, "OTHER", 3)).accept(enumNode, 0);
        var idGetter = enumNode.visitMethod(Opcodes.ACC_STATIC, "id", "(L" + mode + ";)I", null, null);
        idGetter.visitVarInsn(Opcodes.ALOAD, 0);
        idGetter.visitFieldInsn(Opcodes.GETFIELD, mode, "wireId", "I");
        idGetter.visitInsn(Opcodes.IRETURN);
        idGetter.visitMaxs(1, 1);
        idGetter.visitEnd();
        var initializer = enumNode.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
        for (var instruction : initializer.instructions.toArray()) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("idMapper")) {
                initializer.instructions.insertBefore(call, new org.objectweb.asm.tree.InvokeDynamicInsnNode("applyAsInt",
                        "()Ljava/util/function/ToIntFunction;",
                        new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                        new Handle(Opcodes.H_INVOKESTATIC, mode, "id", "(L" + mode + ";)I", false)));
            }
        }
        return new Pair(before, after, Map.of(mode, enumNode));
    }

    static ClassNode node(String owner, List<String> layout, List<String> codecs, List<Integer> order, boolean composite) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | (composite ? Opcodes.ACC_RECORD : 0), owner, null,
                composite ? "java/lang/Record" : "java/lang/Object", null);
        var types = layout.stream().map(f -> Type.getType(f.split(":", 2)[1])).toArray(Type[]::new);
        for (String field : layout) {
            String[] pair = field.split(":", 2);
            if (composite) writer.visitRecordComponent(pair[0], pair[1], null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, pair[0], pair[1], null, null).visitEnd();
        }
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, types), null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, composite ? "java/lang/Record" : "java/lang/Object", "<init>", "()V", false);
        int slot = 1;
        for (int i = 0; i < types.length; i++) {
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitVarInsn(types[i].getOpcode(Opcodes.ILOAD), slot);
            constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, layout.get(i).split(":", 2)[0], types[i].getDescriptor());
            slot += types[i].getSize();
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(4, slot);
        constructor.visitEnd();
        if (composite) {
            var method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            for (int i : order) {
                String[] reference = reference(codecs.get(i));
                method.visitFieldInsn(Opcodes.GETSTATIC, reference[0], reference[1], STREAM);
                method.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                        new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                        new Handle(Opcodes.H_INVOKEVIRTUAL, owner, layout.get(i).split(":", 2)[0], "()" + types[i].getDescriptor(), false));
            }
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec", "composite", "()" + STREAM, true);
            method.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(40, 0);
            method.visitEnd();
        } else {
            for (boolean read : List.of(true, false)) {
                String buffer = "net/minecraft/network/FriendlyByteBuf";
                var method = writer.visitMethod(Opcodes.ACC_PRIVATE, read ? "<init>" : "write", "(L" + buffer + ";)V", null, null);
                if (read) {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                }
                for (int i : order) {
                    if (read) method.visitVarInsn(Opcodes.ALOAD, 0);
                    String field = layout.get(i).split(":", 2)[0];
                    boolean object = codecs.get(i).contains("#");
                    if (object) {
                        String[] reference = reference(codecs.get(i));
                        method.visitFieldInsn(Opcodes.GETSTATIC, reference[0], reference[1], STREAM);
                    }
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    if (!read) {
                        method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, types[i].getDescriptor());
                    }
                    if (object) {
                        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/minecraft/network/codec/StreamCodec", read ? "decode" : "encode",
                                read ? "(Ljava/lang/Object;)Ljava/lang/Object;" : "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
                        if (read) method.visitTypeInsn(Opcodes.CHECKCAST, types[i].getInternalName());
                    } else {
                        String suffix = switch (codecs.get(i)) {
                            case "BOOL" -> "Boolean"; case "VAR_INT" -> "VarInt"; case "INT" -> "Int";
                            case "DOUBLE" -> "Double"; case "FLOAT" -> "Float"; default -> throw new IllegalArgumentException();
                        };
                        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, buffer, (read ? "read" : "write") + suffix,
                                read ? "()" + types[i].getDescriptor() : "(" + types[i].getDescriptor() + ")V", false);
                    }
                    if (read) method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, types[i].getDescriptor());
                }
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(5, 2);
                method.visitEnd();
            }
        }
        writer.visitEnd();
        var result = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(result, 0);
        return result;
    }

    static void delegate(ClassNode owner, List<String> oldFields, List<String> newFields, List<Integer> parameters, String enumOwner, String constant) {
        Type[] oldTypes = oldFields.stream().map(f -> Type.getType(f.split(":", 2)[1])).toArray(Type[]::new);
        Type[] newTypes = newFields.stream().map(f -> Type.getType(f.split(":", 2)[1])).toArray(Type[]::new);
        var method = owner.visitMethod(Opcodes.ACC_PUBLIC, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, oldTypes), null, null);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        int[] slots = new int[oldTypes.length];
        int slot = 1;
        for (int i = 0; i < slots.length; i++) { slots[i] = slot; slot += oldTypes[i].getSize(); }
        for (int parameter : parameters) method.visitVarInsn(oldTypes[parameter].getOpcode(Opcodes.ILOAD), slots[parameter]);
        method.visitFieldInsn(Opcodes.GETSTATIC, enumOwner, constant, "L" + enumOwner + ";");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner.name, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, newTypes), false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(40, slot);
        method.visitEnd();
    }

    private static String[] reference(String codec) {
        return codec.contains("#") ? codec.split("#") : new String[]{WireSchema.BYTE_CODECS, codec};
    }
}
