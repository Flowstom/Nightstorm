package net.flowstom.nightstorm;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Proves that differently spelled codecs are the same wire operation.
 * A byte-sized enum id matches a varint enum id only when every id is in {@code 0..127},
 * because that is the range where a VarInt is a single byte equal to its value.
 * A null encoded as byte {@code -1} is not an optional varint.
 */
final class WireCanonical {
    static final String ENUM_ID = "ENUM_ID";
    static final String VARINT_ENUM_ID = "VARINT_ENUM_ID";
    static final String BYTE_ENUM_ID = "BYTE_ENUM_ID";
    static final String NULLABLE_BYTE_ID = "NULLABLE_BYTE_ID";
    static final String OPTIONAL_VAR_INT_ENUM = "OPTIONAL_VAR_INT_ENUM";
    static final String IDENTIFIER = "IDENTIFIER";
    static final Object ARGUMENT = new Object();
    private static final Object BUFFER = new Object(), VALUE = new Object(), RESULT = new Object();
    private static final ThreadLocal<Set<String>> ACTIVE = ThreadLocal.withInitial(HashSet::new);

    private enum Kind { ID, NULLABLE }

    record EnumBits(int component, String owner, boolean nullable) { }

    private WireCanonical() { }

    static WireSchema.Codec canonicalize(WireSchema.Codec codec, Function<String, ClassNode> classes) {
        var arguments = codec.arguments().stream().map(argument -> canonicalize(argument, classes)).toList();
        var proved = prove(new WireSchema.Codec(codec.owner(), codec.name(), List.of(), arguments), classes);
        if (proved == null) proved = new WireSchema.Codec(codec.owner(), codec.name(), List.of(), arguments);
        for (String operation : codec.operations()) proved = proved.apply(operation);
        return proved;
    }

    static WireSchema.Codec referencedCodec(InvokeDynamicInsnNode dynamic, boolean reading, Function<String, ClassNode> classes) {
        Handle handle = null;
        for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle candidate) handle = candidate;
        if (handle == null) return null;
        int opcode = handle.getTag() == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
        return directCodec(new MethodInsnNode(opcode, handle.getOwner(), handle.getName(), handle.getDesc(), false), reading, classes);
    }

    static WireSchema.Codec directCodec(MethodInsnNode call, boolean reading, Function<String, ClassNode> classes) {
        String key = call.owner + '.' + call.name + call.desc + ':' + reading;
        if (!ACTIVE.get().add(key)) return null;
        try {
            MethodNode method = resolve(call.owner, call.name, call.desc, classes);
            if (method == null) return null;
            String identifier = identifierOwner(method, reading, classes);
            if (identifier != null) return new WireSchema.Codec(identifier, IDENTIFIER);
            var delegated = WireManual.delegated(call, reading, classes);
            if (delegated != null) return delegated;
            return structural(method, reading, classes);
        } finally {
            ACTIVE.get().remove(key);
        }
    }

    static boolean booleanOptional(MethodInsnNode call, Function<String, ClassNode> classes) {
        MethodNode method = resolve(call.owner, call.name, call.desc, classes);
        if (method == null || !presenceOptional(method, call.name.startsWith("read"))) return false;
        ClassNode codecs = classes.apply(WireSchema.BYTE_CODECS);
        if (codecs == null) return false;
        MethodNode factory = codecs.methods.stream().filter(candidate -> candidate.name.equals("optional")
                && Type.getArgumentTypes(candidate.desc).length == 1).findFirst().orElse(null);
        if (factory == null) return false;
        String implementation = null;
        for (AbstractInsnNode instruction : factory.instructions) {
            if (instruction instanceof TypeInsnNode type && instruction.getOpcode() == Opcodes.NEW) implementation = type.desc;
        }
        ClassNode codec = implementation == null ? null : classes.apply(implementation);
        if (codec == null) return false;
        MethodNode decode = codec.methods.stream().filter(candidate -> candidate.name.equals("decode")
                && !candidate.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")).findFirst().orElse(null);
        MethodNode encode = codec.methods.stream().filter(candidate -> candidate.name.equals("encode")
                && !candidate.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")).findFirst().orElse(null);
        return decode != null && encode != null && optionalDecode(decode) && optionalEncode(encode);
    }

    static boolean applyEnum(MethodInsnNode call, boolean reading, List<Object> stack, Function<String, ClassNode> classes) {
        if (classes == null) return false;
        ClassNode owner = classes.apply(call.owner);
        if (owner == null || (owner.access & Opcodes.ACC_ENUM) == 0) return false;
        Kind kind = idKind(owner, call.name, call.desc);
        if (kind == null) return false;
        if (reading) {
            Object value = WireSchema.pop(stack);
            if (!(value instanceof WireSchema.Codec codec) || !codec.owner().equals(WireSchema.BYTE_CODECS)
                    || !codec.operations().isEmpty() || !Set.of("BYTE", "VAR_INT").contains(codec.name())
                    || kind == Kind.NULLABLE && !codec.name().equals("BYTE")) WireSchema.fail();
            stack.add(enumCodec(owner, codec.name(), kind));
        } else {
            Object value = WireSchema.pop(stack);
            if (!(value instanceof Integer index)) WireSchema.fail();
            stack.add(new EnumBits(index, owner.name, kind == Kind.NULLABLE));
        }
        return true;
    }

    static WireSchema.Codec enumWidth(EnumBits bits, String primitive, Function<String, ClassNode> classes) {
        if (primitive == null || !Set.of("BYTE", "VAR_INT").contains(primitive)) return null;
        ClassNode owner = classes.apply(bits.owner());
        if (owner == null || (owner.access & Opcodes.ACC_ENUM) == 0) return null;
        if (bits.nullable()) return primitive.equals("BYTE") ? new WireSchema.Codec(bits.owner(), NULLABLE_BYTE_ID) : null;
        return enumCodec(owner, primitive, Kind.ID);
    }

    static Integer nullableByteSource(WireSchema baseline, Map<Integer, WireSchema.Codec> codecs,
                                      WireSchema.Component component, WireSchema.Codec target) {
        if (!target.name().equals(OPTIONAL_VAR_INT_ENUM) || !component.descriptor().equals("Ljava/util/Optional;")) return null;
        if (component.signature() != null && !component.signature().contains("L" + target.owner() + ";")) return null;
        Integer found = null;
        for (int i = 0; i < baseline.components().size(); i++) {
            var candidate = baseline.components().get(i);
            var codec = codecs.get(i);
            if (!candidate.name().equals(component.name()) || codec == null || !codec.name().equals(NULLABLE_BYTE_ID)
                    || !codec.owner().equals(target.owner()) || !candidate.descriptor().equals("L" + target.owner() + ";")) continue;
            if (found != null) return null;
            found = i;
        }
        return found;
    }

    private static WireSchema.Codec prove(WireSchema.Codec codec, Function<String, ClassNode> classes) {
        if (classes == null) return null;
        if (identifierFactory(codec, classes)) return new WireSchema.Codec(codec.owner(), IDENTIFIER);
        ClassNode owner = classes.apply(codec.owner());
        if (owner == null) return null;
        if (codec.name().equals("STREAM_CODEC") && WireMigration.enumIdCodec(owner) && varIntBacked(owner, classes)) {
            return new WireSchema.Codec(owner.name, byteCompatible(owner) ? ENUM_ID : VARINT_ENUM_ID);
        }
        if (optionalVarIntEnum(owner, codec.name())) return new WireSchema.Codec(owner.name, OPTIONAL_VAR_INT_ENUM);
        return null;
    }

    private static WireSchema.Codec enumCodec(ClassNode owner, String width, Kind kind) {
        if (kind == Kind.NULLABLE) return new WireSchema.Codec(owner.name, NULLABLE_BYTE_ID);
        if (width.equals("BYTE")) return new WireSchema.Codec(owner.name, byteCompatible(owner) ? ENUM_ID : BYTE_ENUM_ID);
        return new WireSchema.Codec(owner.name, byteCompatible(owner) ? ENUM_ID : VARINT_ENUM_ID);
    }

    private static boolean byteCompatible(ClassNode owner) {
        try {
            return PacketMigrationScanner.enumIds(owner).values().stream().allMatch(id -> id >= 0 && id <= 127);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Kind idKind(ClassNode owner, String name, String descriptor) {
        MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst().orElse(null);
        if (method == null) return null;
        if (isGetId(owner, method) || isById(owner, method)) return Kind.ID;
        if (isNullableGet(owner, method) || isNullableBy(owner, method)) return Kind.NULLABLE;
        return null;
    }

    private static boolean isGetId(ClassNode owner, MethodNode method) {
        String field = idField(owner);
        var body = real(method);
        return field != null && body.size() == 3 && load(body.get(0), Opcodes.ALOAD, 0)
                && body.get(1) instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD
                && get.owner.equals(owner.name) && get.name.equals(field) && get.desc.equals("I")
                && body.get(2).getOpcode() == Opcodes.IRETURN;
    }

    private static boolean isById(ClassNode owner, MethodNode method) {
        var body = real(method);
        if (body.size() != 5 || !(body.get(0) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                || !load(body.get(1), Opcodes.ILOAD, 0) || !(body.get(2) instanceof MethodInsnNode call)
                || !(body.get(3) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                || !cast.desc.equals(owner.name) || body.get(4).getOpcode() != Opcodes.ARETURN) return false;
        if (call.name.equals("apply") && call.owner.equals("java/util/function/IntFunction")) {
            return continuousIds(owner, field.name);
        }
        return call.name.equals("byId") && call.owner.equals("net/minecraft/network/codec/EnumStreamCodec")
                && field.name.equals("STREAM_CODEC") && field.owner.equals(owner.name) && WireMigration.enumIdCodec(owner);
    }

    private static boolean continuousIds(ClassNode owner, String fieldName) {
        String id = idField(owner);
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>") || id == null) continue;
            var body = real(method);
            for (int i = 0; i < body.size(); i++) {
                if (!(body.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner.name) || !field.name.equals(fieldName)) continue;
                if (i < 2 || !(body.get(i - 1) instanceof MethodInsnNode call) || !call.owner.equals("net/minecraft/util/ByIdMap")
                        || !call.name.equals("continuous")) return false;
                for (int earlier = i - 2; earlier >= 0 && earlier >= i - 6; earlier--) {
                    if (body.get(earlier) instanceof InvokeDynamicInsnNode dynamic && handle(dynamic, owner.name, "getId", "()I")) return true;
                }
                return false;
            }
        }
        return false;
    }

    private static boolean isNullableBy(ClassNode owner, MethodNode method) {
        var body = real(method);
        if (body.size() != 8 || !load(body.get(0), Opcodes.ILOAD, 0) || body.get(1).getOpcode() != Opcodes.ICONST_M1
                || !(body.get(2) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IF_ICMPNE
                || body.get(3).getOpcode() != Opcodes.ACONST_NULL || body.get(4).getOpcode() != Opcodes.ARETURN
                || !load(body.get(5), Opcodes.ILOAD, 0) || !(body.get(6) instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals(owner.name)
                || body.get(7).getOpcode() != Opcodes.ARETURN || next(jump.label) != body.get(5)) return false;
        return idKind(owner, call.name, call.desc) == Kind.ID;
    }

    private static boolean isNullableGet(ClassNode owner, MethodNode method) {
        String field = idField(owner);
        var body = real(method);
        if (field == null || body.size() != 7 || !load(body.get(0), Opcodes.ALOAD, 0)
                || !(body.get(1) instanceof JumpInsnNode absent) || absent.getOpcode() != Opcodes.IFNULL
                || !load(body.get(2), Opcodes.ALOAD, 0) || !(body.get(4) instanceof JumpInsnNode done)
                || done.getOpcode() != Opcodes.GOTO || body.get(5).getOpcode() != Opcodes.ICONST_M1
                || body.get(6).getOpcode() != Opcodes.IRETURN || next(absent.label) != body.get(5)
                || next(done.label) != body.get(6)) return false;
        AbstractInsnNode read = body.get(3);
        return read instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD && get.owner.equals(owner.name)
                && get.name.equals(field) && get.desc.equals("I")
                || read instanceof MethodInsnNode call && call.owner.equals(owner.name) && isGetId(owner,
                owner.methods.stream().filter(candidate -> candidate.name.equals(call.name) && candidate.desc.equals(call.desc))
                        .findFirst().orElse(null));
    }

    private static boolean optionalVarIntEnum(ClassNode owner, String fieldName) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var body = real(method);
            for (int i = 0; i < body.size(); i++) {
                if (!(body.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner.name) || !field.name.equals(fieldName)) continue;
                if (i < 4 || !(body.get(i - 1) instanceof MethodInsnNode map) || !map.owner.equals("net/minecraft/network/codec/StreamCodec")
                        || !map.name.equals("map") || !(body.get(i - 2) instanceof InvokeDynamicInsnNode encode)
                        || !(body.get(i - 3) instanceof InvokeDynamicInsnNode decode)
                        || !(body.get(i - 4) instanceof FieldInsnNode source) || source.getOpcode() != Opcodes.GETSTATIC
                        || !source.owner.equals(WireSchema.BYTE_CODECS) || !source.name.equals("OPTIONAL_VAR_INT")) return false;
                return optionalEnumLambda(owner, encode, false) && optionalEnumLambda(owner, decode, true);
            }
        }
        return false;
    }

    private static boolean optionalEnumLambda(ClassNode owner, InvokeDynamicInsnNode dynamic, boolean decode) {
        Handle implementation = null;
        for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle) implementation = handle;
        if (implementation == null || !implementation.getOwner().equals(owner.name)) return false;
        MethodNode method = owner.methods.stream().filter(candidate -> candidate.name.equals(implementation.getName())
                && candidate.desc.equals(implementation.getDesc())).findFirst().orElse(null);
        if (method == null) return nullCall();
        var body = real(method);
        if (decode) {
            return body.size() == 10 && load(body.get(0), Opcodes.ALOAD, 0) && call(body.get(1), "java/util/OptionalInt", "isPresent")
                    && body.get(2) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ
                    && load(body.get(3), Opcodes.ALOAD, 0) && call(body.get(4), "java/util/OptionalInt", "getAsInt")
                    && body.get(5) instanceof MethodInsnNode byId && byId.getOpcode() == Opcodes.INVOKESTATIC
                    && byId.owner.equals(owner.name) && idKind(owner, byId.name, byId.desc) == Kind.ID
                    && call(body.get(6), "java/util/Optional", "of") && body.get(7) instanceof JumpInsnNode
                    && call(body.get(8), "java/util/Optional", "empty") && body.get(9).getOpcode() == Opcodes.ARETURN;
        }
        return body.size() == 11 && load(body.get(0), Opcodes.ALOAD, 0) && call(body.get(1), "java/util/Optional", "isPresent")
                && body.get(2) instanceof JumpInsnNode && load(body.get(3), Opcodes.ALOAD, 0)
                && call(body.get(4), "java/util/Optional", "get") && body.get(5) instanceof TypeInsnNode
                && body.get(6) instanceof MethodInsnNode id && id.owner.equals(owner.name) && isGetId(owner,
                owner.methods.stream().filter(candidate -> candidate.name.equals(id.name) && candidate.desc.equals(id.desc)).findFirst().orElse(null))
                && call(body.get(7), "java/util/OptionalInt", "of") && body.get(8) instanceof JumpInsnNode
                && call(body.get(9), "java/util/OptionalInt", "empty") && body.get(10).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean nullCall() { return false; }

    private static boolean varIntBacked(ClassNode owner, Function<String, ClassNode> classes) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var body = real(method);
            for (int i = 0; i < body.size(); i++) {
                if (!(body.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.name.equals("STREAM_CODEC") || !field.owner.equals(owner.name)
                        || !(body.get(i == 0 ? 0 : i - 1) instanceof MethodInsnNode call)) continue;
                if (!Set.of("idMapper", "enumCodec").contains(call.name)) return false;
                return followsVarInt(call.owner, call.name, call.desc, classes, new HashSet<>(), 0);
            }
        }
        return false;
    }

    private static boolean followsVarInt(String owner, String name, String descriptor, Function<String, ClassNode> classes,
                                         Set<String> seen, int depth) {
        if (depth > 8 || !seen.add(owner + '.' + name + descriptor)) return false;
        MethodNode method = resolve(owner, name, descriptor, classes);
        if (method == null) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && instruction.getOpcode() == Opcodes.NEW) {
                ClassNode implementation = classes.apply(type.desc);
                if (implementation != null && varIntCodec(implementation)) return true;
            } else if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && Type.getReturnType(call.desc).getSort() == Type.OBJECT
                    && Type.getReturnType(call.desc).getInternalName().endsWith("StreamCodec")) {
                if (followsVarInt(call.owner, call.name, call.desc, classes, seen, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean varIntCodec(ClassNode implementation) {
        boolean read = false, write = false;
        for (MethodNode method : implementation.methods) {
            if (method.name.equals("decode") && calls(method, "net/minecraft/network/VarInt", "read")) read = true;
            if (method.name.equals("encode") && calls(method, "net/minecraft/network/VarInt", "write")) write = true;
        }
        return read && write;
    }

    private static boolean identifierFactory(WireSchema.Codec codec, Function<String, ClassNode> classes) {
        if (!codec.name().startsWith("streamCodec")) return false;
        ClassNode owner = classes.apply(codec.owner());
        if (owner == null) return false;
        MethodNode method = owner.methods.stream().filter(candidate -> codec.name().equals(candidate.name + candidate.desc)).findFirst().orElse(null);
        if (method == null) return false;
        var body = real(method);
        if (body.size() != 5 || !(body.get(0) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                || !field.name.equals("STREAM_CODEC") || !(body.get(1) instanceof InvokeDynamicInsnNode decode)
                || !(body.get(2) instanceof InvokeDynamicInsnNode encode) || !(body.get(3) instanceof MethodInsnNode map)
                || !map.owner.equals("net/minecraft/network/codec/StreamCodec") || !map.name.equals("map")
                || body.get(4).getOpcode() != Opcodes.ARETURN || !utfIdentifier(field.owner, classes)) return false;
        return createsFromIdentifier(owner, decode) && handle(encode, owner.name, "identifier", "()L" + field.owner + ";");
    }

    private static boolean createsFromIdentifier(ClassNode owner, InvokeDynamicInsnNode dynamic) {
        Handle implementation = null;
        for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle) implementation = handle;
        if (implementation == null) return false;
        MethodNode method = resolve(implementation.getOwner(), implementation.getName(), implementation.getDesc(), name -> name.equals(owner.name) ? owner : null);
        if (method == null) method = owner.methods.stream().filter(candidate -> candidate.name.equals(implementation.getName())
                && candidate.desc.equals(implementation.getDesc())).findFirst().orElse(null);
        var body = method == null ? List.<AbstractInsnNode>of() : real(method);
        return body.size() == 4 && load(body.get(0), Opcodes.ALOAD, 0) && load(body.get(1), Opcodes.ALOAD, 1)
                && body.get(2) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(owner.name) && call.name.equals("create") && body.get(3).getOpcode() == Opcodes.ARETURN;
    }

    private static String identifierOwner(MethodNode method, boolean reading, Function<String, ClassNode> classes) {
        if (reading) {
            Type result = Type.getReturnType(method.desc);
            if (result.getSort() != Type.OBJECT || !readsIdentifier(method, classes)) return null;
            return result.getInternalName();
        }
        Type[] arguments = Type.getArgumentTypes(method.desc);
        if (arguments.length != 1 || arguments[0].getSort() != Type.OBJECT || !writesIdentifier(method, classes)) return null;
        return arguments[0].getInternalName();
    }

    private static boolean readsIdentifier(MethodNode method, Function<String, ClassNode> classes) {
        var body = real(method);
        if (body.size() != 7 || !load(body.get(0), Opcodes.ALOAD, 0) || !(body.get(1) instanceof MethodInsnNode read)
                || !identifierValue(read, true, classes) || !(body.get(2) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE || !load(body.get(3), Opcodes.ALOAD, 1)
                || !load(body.get(4), Opcodes.ALOAD, store.var) || !(body.get(5) instanceof MethodInsnNode create)
                || create.getOpcode() != Opcodes.INVOKESTATIC || !create.name.equals("create")
                || !create.owner.equals(Type.getReturnType(method.desc).getInternalName())
                || body.get(6).getOpcode() != Opcodes.ARETURN) return false;
        return true;
    }

    private static boolean writesIdentifier(MethodNode method, Function<String, ClassNode> classes) {
        var body = real(method);
        return body.size() == 6 && load(body.get(0), Opcodes.ALOAD, 0) && load(body.get(1), Opcodes.ALOAD, 1)
                && body.get(2) instanceof MethodInsnNode identifier && identifier.name.equals("identifier")
                && identifier.owner.equals(Type.getArgumentTypes(method.desc)[0].getInternalName())
                && body.get(3) instanceof MethodInsnNode write && identifierValue(write, false, classes)
                && body.get(4).getOpcode() == Opcodes.POP && body.get(5).getOpcode() == Opcodes.RETURN;
    }

    private static boolean identifierValue(MethodInsnNode call, boolean reading, Function<String, ClassNode> classes) {
        MethodNode method = resolve(call.owner, call.name, call.desc, classes);
        if (method == null) return false;
        var body = real(method);
        if (reading) {
            if (body.size() != 5 || !load(body.get(0), Opcodes.ALOAD, 0) || !(body.get(1) instanceof IntInsnNode limit)
                    || !(body.get(2) instanceof MethodInsnNode read) || !read.name.equals("readUtf")
                    || !(body.get(3) instanceof MethodInsnNode parse) || !parse.name.equals("parse")
                    || body.get(4).getOpcode() != Opcodes.ARETURN) return false;
            return limit.operand == utfLimit(classes) && parse.owner.equals(Type.getReturnType(method.desc).getInternalName());
        }
        if (body.size() != 5 || !load(body.get(0), Opcodes.ALOAD, 0) || !load(body.get(1), Opcodes.ALOAD, 1)
                || !(body.get(2) instanceof MethodInsnNode text) || !text.name.equals("toString")
                || !(body.get(3) instanceof MethodInsnNode write) || !write.name.equals("writeUtf")
                || body.get(4).getOpcode() != Opcodes.ARETURN) return false;
        MethodNode writer = resolve(write.owner, write.name, write.desc, classes);
        var written = writer == null ? List.<AbstractInsnNode>of() : real(writer);
        return written.size() == 5 && written.get(2) instanceof IntInsnNode limit && limit.operand == utfLimit(classes)
                && written.get(4).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean utfIdentifier(String owner, Function<String, ClassNode> classes) {
        ClassNode node = classes.apply(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var body = real(method);
            for (int i = 0; i < body.size(); i++) {
                if (!(body.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner) || !field.name.equals("STREAM_CODEC")) continue;
                if (i < 4 || !(body.get(i - 4) instanceof FieldInsnNode source) || source.getOpcode() != Opcodes.GETSTATIC
                        || !source.owner.equals(WireSchema.BYTE_CODECS) || !source.name.equals("STRING_UTF8")
                        || !(body.get(i - 3) instanceof InvokeDynamicInsnNode decode)
                        || !(body.get(i - 2) instanceof InvokeDynamicInsnNode encode)
                        || !(body.get(i - 1) instanceof MethodInsnNode map) || !map.name.equals("map")) return false;
                return handle(decode, owner, "parse", "(Ljava/lang/String;)L" + owner + ";")
                        && handle(encode, owner, "toString", "()Ljava/lang/String;")
                        && utfLimit(classes) > 0;
            }
        }
        return false;
    }

    private static int utfLimit(Function<String, ClassNode> classes) {
        ClassNode codecs = classes.apply(WireSchema.BYTE_CODECS);
        if (codecs == null) return -1;
        for (MethodNode method : codecs.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var body = real(method);
            for (int i = 1; i < body.size(); i++) {
                if (body.get(i) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && field.owner.equals(WireSchema.BYTE_CODECS) && field.name.equals("STRING_UTF8")
                        && body.get(i - 1) instanceof MethodInsnNode call && call.name.equals("stringUtf8")
                        && body.get(i - 2) instanceof IntInsnNode limit) return limit.operand;
            }
        }
        return -1;
    }

    private static WireSchema.Codec structural(MethodNode method, boolean reading, Function<String, ClassNode> classes) {
        String owner = reading ? Type.getReturnType(method.desc).getInternalName()
                : Type.getArgumentTypes(method.desc).length == 1 ? Type.getArgumentTypes(method.desc)[0].getInternalName() : null;
        if (owner == null || real(method).size() > 64 || real(method).stream().anyMatch(JumpInsnNode.class::isInstance)) return null;
        ClassNode type = classes.apply(owner);
        if (type == null) return null;
        var schema = WireSchema.scan(type, classes);
        if (schema.isEmpty()) return null;
        List<WireSchema.Codec> observed = reading ? readSequence(method, schema.get(), classes) : writeSequence(method, schema.get(), classes);
        var expected = schema.get().fields().stream().map(WireSchema.Field::codec).toList();
        return observed != null && observed.equals(expected) ? new WireSchema.Codec(owner, "STREAM_CODEC") : null;
    }

    private static List<WireSchema.Codec> readSequence(MethodNode method, WireSchema schema, Function<String, ClassNode> classes) {
        Map<Integer, Object> locals = new HashMap<>();
        locals.put(0, BUFFER);
        List<Object> stack = new ArrayList<>();
        List<WireSchema.Codec> reads = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof VarInsnNode variable && opcode == Opcodes.ALOAD) {
                if (!locals.containsKey(variable.var)) return null;
                stack.add(locals.get(variable.var));
            } else if (instruction instanceof VarInsnNode variable && opcode == Opcodes.ASTORE) {
                locals.put(variable.var, pop(stack));
            } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) stack.add(ARGUMENT);
            else if (opcode == Opcodes.CHECKCAST) continue;
            else if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                    var arguments = new Object[Type.getArgumentTypes(call.desc).length];
                    for (int i = arguments.length - 1; i >= 0; i--) arguments[i] = pop(stack);
                    if (pop(stack) != BUFFER || java.util.Arrays.stream(arguments).anyMatch(argument -> argument != ARGUMENT)) return null;
                    var codec = directCodec(call, true, classes);
                    if (codec == null) return null;
                    reads.add(codec);
                    stack.add(codec);
                } else if (Type.getReturnType(call.desc).getSort() == Type.OBJECT
                        && Type.getReturnType(call.desc).getInternalName().equals(schema.owner())) {
                    var arguments = new WireSchema.Codec[Type.getArgumentTypes(call.desc).length];
                    for (int i = arguments.length - 1; i >= 0; i--) {
                        if (!(pop(stack) instanceof WireSchema.Codec codec)) return null;
                        arguments[i] = codec;
                    }
                    if (arguments.length != schema.components().size()) return null;
                    for (int i = 0; i < arguments.length; i++) {
                        int component = i;
                        var field = schema.fields().stream().filter(candidate -> candidate.component() == component).findFirst().orElse(null);
                        if (field == null || !field.codec().equals(arguments[i])
                                || !Type.getArgumentTypes(call.desc)[i].getDescriptor().equals(schema.components().get(i).descriptor())) return null;
                    }
                    stack.add(RESULT);
                } else return null;
            } else if (opcode == Opcodes.ARETURN) {
                return pop(stack) == RESULT && stack.isEmpty() ? reads : null;
            } else return null;
        }
        return null;
    }

    private static List<WireSchema.Codec> writeSequence(MethodNode method, WireSchema schema, Function<String, ClassNode> classes) {
        Map<Integer, Object> locals = new HashMap<>();
        locals.put(0, BUFFER);
        locals.put(1, VALUE);
        List<Object> stack = new ArrayList<>();
        List<WireSchema.Codec> writes = new ArrayList<>();
        List<Integer> components = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof VarInsnNode variable && opcode == Opcodes.ALOAD) {
                if (!locals.containsKey(variable.var)) return null;
                stack.add(locals.get(variable.var));
            } else if (opcode == Opcodes.POP) pop(stack);
            else if (opcode == Opcodes.CHECKCAST) continue;
            else if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL && !stack.isEmpty() && stack.getLast() == VALUE) {
                pop(stack);
                int index = -1;
                for (int i = 0; i < schema.components().size(); i++) {
                    var component = schema.components().get(i);
                    if (call.name.equals(component.name()) && call.desc.equals("()" + component.descriptor())) index = i;
                }
                if (index < 0) return null;
                stack.add(index);
            } else if (instruction instanceof MethodInsnNode call && !stack.isEmpty() && stack.getLast() != VALUE) {
                var arguments = new Object[Type.getArgumentTypes(call.desc).length];
                for (int i = arguments.length - 1; i >= 0; i--) arguments[i] = pop(stack);
                if (pop(stack) != BUFFER || arguments.length != 1 || !(arguments[0] instanceof Integer component)) return null;
                var codec = directCodec(call, false, classes);
                var field = schema.fields().stream().filter(candidate -> candidate.component() == component).findFirst().orElse(null);
                if (codec == null || field == null || !field.codec().equals(codec)) return null;
                components.add(component);
                writes.add(codec);
                if (Type.getReturnType(call.desc) != Type.VOID_TYPE) stack.add(BUFFER);
            } else if (opcode == Opcodes.RETURN) {
                var expected = schema.fields().stream().map(WireSchema.Field::component).toList();
                return stack.isEmpty() && components.equals(expected) ? writes : null;
            } else return null;
        }
        return null;
    }

    private static boolean presenceOptional(MethodNode method, boolean reading) {
        var body = real(method);
        if (reading) {
            return body.size() == 10 && load(body.get(0), Opcodes.ALOAD, 0) && call(body.get(1), null, "readBoolean")
                    && body.get(2) instanceof JumpInsnNode && load(body.get(3), Opcodes.ALOAD, 1)
                    && load(body.get(4), Opcodes.ALOAD, 0) && call(body.get(5), "net/minecraft/network/codec/StreamDecoder", "decode")
                    && call(body.get(6), "java/util/Optional", "of") && body.get(7).getOpcode() == Opcodes.ARETURN
                    && call(body.get(8), "java/util/Optional", "empty") && body.get(9).getOpcode() == Opcodes.ARETURN;
        }
        return body.size() == 18 && load(body.get(0), Opcodes.ALOAD, 1) && call(body.get(1), "java/util/Optional", "isPresent")
                && body.get(2) instanceof JumpInsnNode && load(body.get(3), Opcodes.ALOAD, 0)
                && body.get(4).getOpcode() == Opcodes.ICONST_1 && call(body.get(5), null, "writeBoolean")
                && body.get(6).getOpcode() == Opcodes.POP && load(body.get(7), Opcodes.ALOAD, 2)
                && load(body.get(8), Opcodes.ALOAD, 0) && load(body.get(9), Opcodes.ALOAD, 1)
                && call(body.get(10), "java/util/Optional", "get")
                && call(body.get(11), "net/minecraft/network/codec/StreamEncoder", "encode")
                && body.get(12) instanceof JumpInsnNode && load(body.get(13), Opcodes.ALOAD, 0)
                && body.get(14).getOpcode() == Opcodes.ICONST_0 && call(body.get(15), null, "writeBoolean")
                && body.get(16).getOpcode() == Opcodes.POP && body.get(17).getOpcode() == Opcodes.RETURN;
    }

    private static boolean optionalDecode(MethodNode method) {
        var body = real(method);
        return body.size() == 11 && load(body.get(0), Opcodes.ALOAD, 1) && call(body.get(1), "io/netty/buffer/ByteBuf", "readBoolean")
                && body.get(2) instanceof JumpInsnNode && load(body.get(3), Opcodes.ALOAD, 0)
                && body.get(4) instanceof FieldInsnNode && load(body.get(5), Opcodes.ALOAD, 1)
                && call(body.get(6), "net/minecraft/network/codec/StreamCodec", "decode")
                && call(body.get(7), "java/util/Optional", "of") && body.get(8).getOpcode() == Opcodes.ARETURN
                && call(body.get(9), "java/util/Optional", "empty") && body.get(10).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean optionalEncode(MethodNode method) {
        var body = real(method);
        return body.size() == 19 && load(body.get(0), Opcodes.ALOAD, 2) && call(body.get(1), "java/util/Optional", "isPresent")
                && body.get(2) instanceof JumpInsnNode && load(body.get(3), Opcodes.ALOAD, 1)
                && body.get(4).getOpcode() == Opcodes.ICONST_1 && call(body.get(5), "io/netty/buffer/ByteBuf", "writeBoolean")
                && body.get(6).getOpcode() == Opcodes.POP && load(body.get(7), Opcodes.ALOAD, 0)
                && body.get(8) instanceof FieldInsnNode && load(body.get(9), Opcodes.ALOAD, 1)
                && load(body.get(10), Opcodes.ALOAD, 2) && call(body.get(11), "java/util/Optional", "get")
                && call(body.get(12), "net/minecraft/network/codec/StreamCodec", "encode")
                && body.get(13) instanceof JumpInsnNode && load(body.get(14), Opcodes.ALOAD, 1)
                && body.get(15).getOpcode() == Opcodes.ICONST_0 && call(body.get(16), "io/netty/buffer/ByteBuf", "writeBoolean")
                && body.get(17).getOpcode() == Opcodes.POP && body.get(18).getOpcode() == Opcodes.RETURN;
    }

    private static MethodNode resolve(String owner, String name, String descriptor, Function<String, ClassNode> classes) {
        Set<String> seen = new HashSet<>();
        while (owner != null && seen.add(owner)) {
            ClassNode node = classes.apply(owner);
            if (node == null) return null;
            for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
            owner = node.superName;
        }
        return null;
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name) && (owner == null || call.owner.equals(owner))) return true;
        }
        return false;
    }

    private static boolean call(AbstractInsnNode instruction, String owner, String name) {
        return instruction instanceof MethodInsnNode call && call.name.equals(name) && (owner == null || call.owner.equals(owner));
    }

    private static boolean handle(InvokeDynamicInsnNode dynamic, String owner, String name, String descriptor) {
        for (Object argument : dynamic.bsmArgs) {
            if (argument instanceof Handle handle && handle.getOwner().equals(owner) && handle.getName().equals(name)
                    && handle.getDesc().equals(descriptor)) return true;
        }
        return false;
    }

    private static boolean load(AbstractInsnNode instruction, int opcode, int variable) {
        return instruction instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == variable;
    }

    private static String idField(ClassNode owner) {
        var fields = owner.fields.stream().filter(field -> field.desc.equals("I") && (field.access & Opcodes.ACC_STATIC) == 0).toList();
        return fields.size() == 1 ? fields.getFirst().name : null;
    }

    private static List<AbstractInsnNode> real(MethodNode method) {
        if (method == null) return List.of();
        var result = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static AbstractInsnNode next(LabelNode label) {
        AbstractInsnNode instruction = label;
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static Object pop(List<Object> stack) {
        return stack.isEmpty() ? null : stack.removeLast();
    }
}
