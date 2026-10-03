package net.flowstom.nightstorm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Function;

/** Recognizes a portable JDK value factory and inverse accessor surrounding one proven wire primitive. */
final class CodecFunctions {
    record Adapter(String carrier, String networkType, String decoder, String encoder) {
        String expression() {
            return networkType + ".transform(" + carrier + "::" + decoder + ", " + carrier + "::" + encoder + ")";
        }
    }

    static boolean requiresAdapter(ClassNode before, ClassNode after, RecordComponentNode component) {
        if (!component.descriptor.startsWith("Ljava/")) return false;
        boolean manual = before.methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .anyMatch(value -> value instanceof MethodInsnNode call && buffer(call.owner)
                        && Type.getReturnType(call.desc).getDescriptor().equals(component.descriptor)
                        && next(value) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(before.name) && field.name.equals(component.name));
        if (!manual) return false;
        for (var method : after.methods) {
            for (var value : method.instructions) {
                if (value instanceof InvokeDynamicInsnNode dynamic && accessorReferences(dynamic, after, component)
                        && previous(value) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.desc.equals(WireSchema.STREAM_CODEC)) {
                    var translated = PacketCodecScanner.translate(new WireSchema.Codec(field.owner, field.name));
                    if (!translated.supported()) return true;
                }
            }
        }
        return false;
    }

    static Optional<Adapter> component(ClassNode owner, RecordComponentNode component, Function<String, ClassNode> classes) {
        String carrier = component.descriptor;
        if (!carrier.startsWith("Ljava/") || !carrier.endsWith(";")) return Optional.empty();
        var candidates = new LinkedHashSet<Adapter>();
        var readers = new LinkedHashMap<String, MethodInsnNode>();
        var writers = new LinkedHashMap<String, MethodInsnNode>();
        for (var method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && buffer(call.owner)) {
                    var next = next(instruction);
                    var previous = previous(instruction);
                    if (Type.getReturnType(call.desc).getDescriptor().equals(carrier)
                            && next instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                            && field.owner.equals(owner.name) && field.name.equals(component.name)) readers.put(call.owner + '#' + call.name + call.desc, call);
                    if (Arrays.stream(Type.getArgumentTypes(call.desc)).anyMatch(type -> type.getDescriptor().equals(carrier))
                            && previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(owner.name) && field.name.equals(component.name)) writers.put(call.owner + '#' + call.name + call.desc, call);
                }
                if (instruction instanceof InvokeDynamicInsnNode accessor && accessorReferences(accessor, owner, component)) {
                    var previous = previous(instruction);
                    if (previous instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                        var implementation = implementation(classes.apply(field.owner), field.name, classes);
                        if (implementation != null) {
                            var reads = implementation.methods.stream().filter(value -> value.name.equals("decode")
                                    && Type.getReturnType(value.desc).getDescriptor().equals(carrier) && (value.access & Opcodes.ACC_BRIDGE) == 0).toList();
                            var writes = implementation.methods.stream().filter(value -> value.name.equals("encode")
                                    && Arrays.stream(Type.getArgumentTypes(value.desc)).anyMatch(type -> type.getDescriptor().equals(carrier))
                                    && (value.access & Opcodes.ACC_BRIDGE) == 0).toList();
                            if (reads.size() == 1 && writes.size() == 1) analyze(reads.getFirst(), writes.getFirst(), carrier, 1, 1, 2).ifPresent(candidates::add);
                        }
                    }
                }
            }
        }
        if (readers.size() == 1 && writers.size() == 1) {
            var reader = readers.values().iterator().next();
            var writer = writers.values().iterator().next();
            var readOwner = classes.apply(reader.owner);
            var writeOwner = classes.apply(writer.owner);
            var read = method(readOwner, reader.name, reader.desc);
            var write = method(writeOwner, writer.name, writer.desc);
            if (read != null && write != null && reader.getOpcode() != Opcodes.INVOKESTATIC && writer.getOpcode() != Opcodes.INVOKESTATIC) {
                analyze(read, write, carrier, 0, 0, 1).ifPresent(candidates::add);
            }
        }
        return candidates.size() == 1 ? Optional.of(candidates.iterator().next()) : Optional.empty();
    }

    static Optional<Adapter> analyze(MethodNode read, MethodNode write, String carrier, int readBuffer, int writeBuffer, int valueSlot) {
        var decode = body(read);
        var encode = body(write);
        if (decode.size() != 4 || encode.size() < 5 || encode.size() > 6
                || !load(decode.get(0), readBuffer) || !load(encode.get(0), writeBuffer) || !load(encode.get(1), valueSlot)
                || !(decode.get(1) instanceof MethodInsnNode wireRead) || !(decode.get(2) instanceof MethodInsnNode factory)
                || !(encode.get(2) instanceof MethodInsnNode accessor) || !(encode.get(3) instanceof MethodInsnNode wireWrite)
                || decode.get(3).getOpcode() != Opcodes.ARETURN) return Optional.empty();
        String name = carrier.substring(1, carrier.length() - 1);
        var input = Type.getReturnType(wireRead.desc);
        var factoryArguments = Type.getArgumentTypes(factory.desc);
        if (!name.startsWith("java/") || !factory.owner.equals(name) || factory.getOpcode() != Opcodes.INVOKESTATIC
                || !Type.getReturnType(factory.desc).getDescriptor().equals(carrier) || factoryArguments.length != 1
                || !factoryArguments[0].equals(input) || !accessor.owner.equals(name) || accessor.getOpcode() != Opcodes.INVOKEVIRTUAL
                || Type.getArgumentTypes(accessor.desc).length != 0 || !Type.getReturnType(accessor.desc).equals(input)) return Optional.empty();
        var written = Type.getArgumentTypes(wireWrite.desc);
        if (!buffer(wireRead.owner) || !buffer(wireWrite.owner) || written.length == 0 || !written[written.length - 1].equals(input)) return Optional.empty();
        if (Type.getArgumentTypes(wireRead.desc).length != (wireRead.getOpcode() == Opcodes.INVOKESTATIC ? 1 : 0)
                || written.length != (wireWrite.getOpcode() == Opcodes.INVOKESTATIC ? 2 : 1)) return Optional.empty();
        String primitive = primitive(wireRead.name, "read");
        if (primitive == null || !primitive.equals(primitive(wireWrite.name, "write"))) return Optional.empty();
        String descriptor = switch (primitive) {
            case "BOOLEAN" -> "Z"; case "BYTE" -> "B"; case "SHORT" -> "S"; case "INT", "VAR_INT" -> "I";
            case "LONG", "VAR_LONG" -> "J"; case "FLOAT" -> "F"; case "DOUBLE" -> "D"; case "BYTE_ARRAY" -> "[B"; case "LONG_ARRAY" -> "[J";
            default -> "";
        };
        if (!input.getDescriptor().equals(descriptor)) return Optional.empty();
        if (encode.size() == 6 && encode.get(4).getOpcode() != Opcodes.POP) return Optional.empty();
        int last = encode.getLast().getOpcode();
        if (last != Opcodes.RETURN && last != Opcodes.ARETURN) return Optional.empty();
        return Optional.of(new Adapter(name.replace('/', '.'), "NetworkBuffer." + primitive, factory.name, accessor.name));
    }

    private static String primitive(String method, String prefix) {
        if (!method.startsWith(prefix)) return null;
        return switch (method.substring(prefix.length())) {
            case "Boolean" -> "BOOLEAN"; case "Byte" -> "BYTE"; case "Short" -> "SHORT";
            case "Int" -> "INT"; case "VarInt" -> "VAR_INT"; case "Long" -> "LONG"; case "VarLong" -> "VAR_LONG";
            case "Float" -> "FLOAT"; case "Double" -> "DOUBLE"; case "ByteArray" -> "BYTE_ARRAY"; case "LongArray" -> "LONG_ARRAY";
            default -> null;
        };
    }

    private static boolean accessorReferences(InvokeDynamicInsnNode dynamic, ClassNode owner, RecordComponentNode component) {
        return Arrays.stream(dynamic.bsmArgs).anyMatch(value -> value instanceof org.objectweb.asm.Handle handle
                && handle.getOwner().equals(owner.name) && handle.getName().equals(component.name) && handle.getDesc().equals("()" + component.descriptor));
    }
    private static ClassNode implementation(ClassNode owner, String field, Function<String, ClassNode> classes) {
        if (owner == null) return null;
        for (var method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC || !put.owner.equals(owner.name) || !put.name.equals(field)) continue;
                var call = previous(instruction);
                if (call instanceof MethodInsnNode constructor && constructor.getOpcode() == Opcodes.INVOKESPECIAL
                        && constructor.name.equals("<init>") && constructor.desc.equals("()V")) {
                    var duplicate = previous(call);
                    var allocation = duplicate == null ? null : previous(duplicate);
                    if (duplicate != null && duplicate.getOpcode() == Opcodes.DUP && allocation instanceof TypeInsnNode type
                            && type.getOpcode() == Opcodes.NEW && type.desc.equals(constructor.owner)) return classes.apply(type.desc);
                }
            }
        }
        return null;
    }
    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner == null ? null : owner.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(descriptor)).findFirst().orElse(null);
    }
    private static boolean buffer(String owner) { return owner.startsWith("net/minecraft/network/") && owner.endsWith("FriendlyByteBuf"); }
    private static boolean load(AbstractInsnNode node, int slot) { return node instanceof VarInsnNode value && value.getOpcode() == Opcodes.ALOAD && value.var == slot; }
    private static List<AbstractInsnNode> body(MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return List.of();
        return Arrays.stream(method.instructions.toArray()).filter(value -> value.getOpcode() >= 0).toList();
    }
    private static AbstractInsnNode next(AbstractInsnNode node) { do { node = node.getNext(); } while (node != null && node.getOpcode() < 0); return node; }
    private static AbstractInsnNode previous(AbstractInsnNode node) { do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0); return node; }
}
