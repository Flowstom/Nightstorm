package net.flowstom.nightstorm;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/** Field identity and wire order are deliberately separate from Java constructor order. */
record WireSchema(String owner, List<Component> components, List<Field> fields) {
    static final String STREAM_CODEC = "Lnet/minecraft/network/codec/StreamCodec;";
    static final String BYTE_CODECS = "net/minecraft/network/codec/ByteBufCodecs";

    WireSchema {
        components = List.copyOf(components);
        fields = List.copyOf(fields);
        if (fields.size() != components.size() || fields.stream().map(Field::component).distinct().count() != components.size()) {
            throw new IllegalArgumentException("Wire schema must encode every component exactly once");
        }
    }

    record Component(String name, String descriptor, String signature) { }
    record Field(int component, Codec codec) { }
    record Codec(String owner, String name, List<String> operations, List<Codec> arguments) {
        Codec(String owner, String name) { this(owner, name, List.of()); }
        Codec(String owner, String name, List<String> operations) { this(owner, name, operations, List.of()); }
        Codec { operations = List.copyOf(operations); arguments = arguments == null ? List.of() : List.copyOf(arguments); }
        Codec apply(String operation) {
            var result = new ArrayList<>(operations);
            result.add(operation);
            return new Codec(owner, name, result, arguments);
        }
    }

    static Optional<WireSchema> scan(ClassNode node) {
        return scan(node, ignored -> null);
    }

    static Optional<WireSchema> scan(ClassNode node, java.util.function.Function<String, ClassNode> classes) {
        try {
            var components = components(node);
            var composite = composite(node, components, classes);
            if (composite.isPresent()) return composite;
            Optional<WireSchema> straight;
            try { straight = manual(node, components); }
            catch (UnsupportedOperationException exception) { straight = Optional.empty(); }
            if (straight.isPresent()) return straight;
            return boundedManual(node, components, classes);
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static List<Component> components(ClassNode node) {
        if (node.recordComponents != null && !node.recordComponents.isEmpty()) {
            return node.recordComponents.stream().map(c -> new Component(c.name, c.descriptor, c.signature)).toList();
        }
        var fields = node.fields.stream().filter(f -> (f.access & Opcodes.ACC_STATIC) == 0 && (f.access & Opcodes.ACC_FINAL) != 0)
                .map(f -> new Component(f.name, f.desc, f.signature)).toList();
        // Parameter provenance, not field declaration or assignment order, determines constructor order.
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") || Type.getArgumentTypes(method.desc).length != fields.size()) continue;
            var assignments = ConstructorMapping.assignments(node, method);
            if (assignments.isEmpty()) continue;
            var ordered = new Component[fields.size()];
            boolean valid = true;
            for (Component field : fields) {
                var value = assignments.get().get(field.name());
                if (!(value instanceof ConstructorMapping.Parameter parameter) || parameter.index() >= ordered.length
                        || ordered[parameter.index()] != null) { valid = false; break; }
                ordered[parameter.index()] = field;
            }
            if (valid && Arrays.stream(ordered).noneMatch(Objects::isNull)) return List.of(ordered);
        }
        // Some manual packets have expanded constructors (for example individual array elements).
        // Preserve field identity here; the wire reader/writer establishes their order separately.
        return fields;
    }

    static boolean codecDescriptor(String descriptor, java.util.function.Function<String, ClassNode> classes) {
        if (descriptor.equals(STREAM_CODEC)) return true;
        if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return false;
        return codecClass(descriptor.substring(1, descriptor.length() - 1), classes, new HashSet<>());
    }

    private static boolean codecClass(String owner, java.util.function.Function<String, ClassNode> classes, Set<String> active) {
        if (owner.equals("net/minecraft/network/codec/StreamCodec")) return true;
        if (!active.add(owner)) return false;
        final ClassNode node = classes.apply(owner);
        if (node == null) return false;
        return node.interfaces.stream().anyMatch(value -> codecClass(value, classes, active))
                || node.superName != null && codecClass(node.superName, classes, active);
    }

    private static Optional<WireSchema> composite(ClassNode node, List<Component> components,
                                                 java.util.function.Function<String, ClassNode> classes) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var fields = new ArrayList<Field>();
            var operands = new ArrayList<Object>();
            boolean composed = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction.getOpcode() < 0) continue;
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
                    if (composed) return Optional.empty();
                    operands.add(codecDescriptor(field.desc, classes) ? new Codec(field.owner, field.name)
                            : "field:" + field.owner + "#" + field.name + ":" + field.desc);
                } else if (instruction instanceof IntInsnNode literal && Set.of(Opcodes.BIPUSH, Opcodes.SIPUSH).contains(literal.getOpcode())) {
                    operands.add(literal.operand);
                } else if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Integer) {
                    operands.add(literal.cst);
                } else if (instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5) {
                    operands.add(instruction.getOpcode() - Opcodes.ICONST_0);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    int component = accessor(dynamic, node, components);
                    if (component >= 0) {
                        if (operands.size() != 1 || !(operands.getFirst() instanceof Codec codec)) return Optional.empty();
                        fields.add(new Field(component, codec));
                        operands.clear();
                    } else {
                        String operation = null;
                        for (Object arg : dynamic.bsmArgs) {
                            if (arg instanceof Handle handle && handle.getOwner().equals(BYTE_CODECS)
                                    && Set.of("list", "optional").contains(handle.getName())) operation = handle.getName();
                        }
                        operands.add(operation == null ? "lambda:" + dynamic.desc : "operation:" + operation);
                    }
                } else if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(BYTE_CODECS) && call.name.equals("stringUtf8") && operands.size() == 1
                            && operands.getFirst() instanceof Integer limit && limit >= 0) {
                        operands.clear();
                        operands.add(new Codec(BYTE_CODECS, "STRING_UTF8").apply("limit:" + limit));
                    } else if (call.owner.equals(BYTE_CODECS) && call.name.equals("fixedSizeList") && operands.size() == 2
                            && operands.getLast() instanceof Integer size && size >= 0 && size <= 4096) {
                        operands.removeLast();
                        operands.add("operation:fixed:" + size);
                    } else if (call.owner.equals(BYTE_CODECS) && Set.of("list", "optional").contains(call.name)
                            && Type.getArgumentTypes(call.desc).length == 0) {
                        operands.add("operation:" + call.name);
                    } else if (call.owner.equals(BYTE_CODECS) && call.name.equals("map")
                            && operands.size() == 3 && operands.get(0) instanceof String function
                            && function.equals("lambda:()Ljava/util/function/IntFunction;")
                            && operands.get(1) instanceof Codec key && operands.get(2) instanceof Codec value) {
                        operands.clear();
                        operands.add(new Codec(BYTE_CODECS, "map", List.of(), List.of(key, value)));
                    } else if (call.owner.equals("net/minecraft/network/codec/StreamCodec") && call.name.equals("apply")
                            && operands.size() == 2 && operands.get(0) instanceof Codec codec
                            && operands.get(1) instanceof String operation && operation.startsWith("operation:")) {
                        operands.clear();
                        operands.add(codec.apply(operation.substring("operation:".length())));
                    } else if (call.owner.equals("net/minecraft/network/codec/StreamCodec") && call.name.equals("cast")
                            && operands.size() == 1 && operands.getFirst() instanceof Codec) {
                        // Generic type erasure does not change wire bytes.
                    } else if (call.owner.equals("net/minecraft/network/codec/StreamCodec") && call.name.equals("composite")
                            && !fields.isEmpty() && operands.stream().noneMatch(Codec.class::isInstance)) {
                        operands.clear();
                        composed = true;
                    } else if (call.owner.equals("net/minecraft/network/codec/StreamCodec") && call.name.equals("unit")
                            && components.isEmpty() && fields.isEmpty() && operands.stream().noneMatch(Codec.class::isInstance)) {
                        operands.clear();
                        composed = true;
                    } else if (call.getOpcode() == Opcodes.INVOKESTATIC && codecDescriptor(Type.getReturnType(call.desc).getDescriptor(), classes)
                            && operands.size() == Type.getArgumentTypes(call.desc).length
                            && operands.stream().allMatch(value -> value instanceof String token && token.startsWith("field:"))) {
                        final var arguments = operands.stream().map(Object::toString).toList();
                        operands.clear();
                        operands.add(new Codec(call.owner, call.name + call.desc, arguments));
                    } else return Optional.empty();
                } else if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                    if (field.owner.equals(node.name) && field.name.equals("STREAM_CODEC") && composed && operands.isEmpty()) {
                        return Optional.of(new WireSchema(node.name, components, fields));
                    }
                    fields.clear();
                    operands.clear();
                    composed = false;
                } else if (!fields.isEmpty() || !operands.isEmpty()) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static int accessor(InvokeDynamicInsnNode dynamic, ClassNode owner, List<Component> components) {
        for (Object argument : dynamic.bsmArgs) {
            if (!(argument instanceof Handle handle) || !handle.getOwner().equals(owner.name)) continue;
            for (int i = 0; i < components.size(); i++) {
                var component = components.get(i);
                if (handle.getName().equals(component.name()) && handle.getDesc().equals("()" + component.descriptor())) return i;
            }
        }
        return -1;
    }

    private static Optional<WireSchema> boundedManual(ClassNode node, List<Component> components,
                                                       java.util.function.Function<String, ClassNode> classes) {
        List<Field> reads = null, writes = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && method.desc.matches("\\(Lnet/minecraft/network/\\w*FriendlyByteBuf;\\)V"))
                reads = WireManual.scan(node, method, components, true, classes);
            else if (method.name.equals("write") && method.desc.matches("\\(Lnet/minecraft/network/\\w*FriendlyByteBuf;\\)V"))
                writes = WireManual.scan(node, method, components, false, classes);
        }
        if (reads == null || !reads.equals(writes)) return Optional.empty();
        return Optional.of(new WireSchema(node.name, components, reads));
    }

    private static Optional<WireSchema> manual(ClassNode node, List<Component> components) {
        List<Field> reads = null;
        List<Field> writes = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && method.desc.matches("\\(Lnet/minecraft/network/\\w*FriendlyByteBuf;\\)V")) {
                reads = manualFields(node, method, components, true);
            } else if (method.name.equals("write") && method.desc.matches("\\(Lnet/minecraft/network/\\w*FriendlyByteBuf;\\)V")) {
                writes = manualFields(node, method, components, false);
            }
        }
        if (reads == null || !reads.equals(writes)) return Optional.empty();
        return Optional.of(new WireSchema(node.name, components, reads));
    }

    private static List<Field> manualFields(ClassNode owner, MethodNode method, List<Component> components, boolean reading) {
        var result = new ArrayList<Field>();
        var stack = new ArrayList<Object>();
        final Object self = new Object(), buffer = new Object();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0 || opcode == Opcodes.RETURN) continue;
            if (instruction instanceof VarInsnNode variable && opcode == Opcodes.ALOAD) {
                stack.add(variable.var == 0 ? self : variable.var == 1 ? buffer : fail());
            } else if (instruction instanceof FieldInsnNode field) {
                if (opcode == Opcodes.GETSTATIC && field.desc.equals(STREAM_CODEC)) stack.add(new Codec(field.owner, field.name));
                else if (opcode == Opcodes.GETFIELD && pop(stack) == self && field.owner.equals(owner.name)) stack.add(componentIndex(components, field.name));
                else if (opcode == Opcodes.PUTFIELD && reading) {
                    Object codec = pop(stack);
                    if (pop(stack) != self || !(codec instanceof Codec value) || !field.owner.equals(owner.name)) return fail();
                    result.add(new Field(componentIndex(components, field.name), value));
                } else return fail();
            } else if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("<init>") && Set.of("java/lang/Object", "java/lang/Record").contains(call.owner)
                        && pop(stack) == self) continue;
                if (reading && call.name.equals("<init>") && call.owner.equals(owner.name)) {
                    final Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (arguments.length != components.size()) return fail();
                    final var constructor = owner.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(call.desc))
                            .findFirst().orElseThrow(UnsupportedOperationException::new);
                    final var assignments = ConstructorMapping.assignments(owner, constructor).orElseThrow(UnsupportedOperationException::new);
                    final Codec[] codecs = new Codec[arguments.length];
                    for (int i = arguments.length - 1; i >= 0; i--) {
                        final Object value = pop(stack);
                        if (!(value instanceof Codec codec) || !arguments[i].getDescriptor().equals(components.get(i).descriptor())
                                || !new ConstructorMapping.Parameter(i, components.get(i).descriptor()).equals(assignments.get(components.get(i).name()))) return fail();
                        codecs[i] = codec;
                    }
                    if (pop(stack) != self) return fail();
                    for (int i = 0; i < codecs.length; i++) result.add(new Field(i, codecs[i]));
                    continue;
                }
                if (call.owner.endsWith("FriendlyByteBuf")) {
                    String name = call.name.startsWith("read") ? call.name.substring(4) : call.name.startsWith("write") ? call.name.substring(5) : "";
                    String codec = switch (name) {
                        case "Boolean" -> "BOOL"; case "Byte" -> "BYTE"; case "Short" -> "SHORT";
                        case "Int" -> "INT"; case "VarInt" -> "VAR_INT"; case "Long" -> "LONG";
                        case "VarLong" -> "VAR_LONG"; case "Float" -> "FLOAT"; case "Double" -> "DOUBLE";
                        case "Utf" -> "STRING_UTF8";
                        default -> null;
                    };
                    if (codec == null || opcode == Opcodes.INVOKESTATIC) return fail();
                    if (reading && call.name.startsWith("read") && Type.getArgumentTypes(call.desc).length == 0 && pop(stack) == buffer) {
                        stack.add(new Codec(BYTE_CODECS, codec));
                    } else if (!reading && call.name.startsWith("write") && Type.getArgumentTypes(call.desc).length == 1) {
                        Object component = pop(stack);
                        if (pop(stack) != buffer || !(component instanceof Integer index)) return fail();
                        result.add(new Field(index, new Codec(BYTE_CODECS, codec)));
                        if (Type.getReturnType(call.desc) != Type.VOID_TYPE) stack.add(buffer);
                    } else return fail();
                } else if (call.owner.equals("net/minecraft/network/codec/StreamCodec")) {
                    if (reading && call.name.equals("decode") && pop(stack) == buffer) {
                        Object codec = pop(stack);
                        if (!(codec instanceof Codec)) return fail();
                        stack.add(codec);
                    } else if (!reading && call.name.equals("encode")) {
                        Object component = pop(stack), input = pop(stack), codec = pop(stack);
                        if (input != buffer || !(component instanceof Integer index) || !(codec instanceof Codec value)) return fail();
                        result.add(new Field(index, value));
                    } else return fail();
                } else return fail();
            } else if (opcode == Opcodes.POP) pop(stack);
            else if (opcode != Opcodes.CHECKCAST) return fail();
        }
        if (!stack.isEmpty()) return fail();
        return result;
    }

    private static int componentIndex(List<Component> components, String name) {
        for (int i = 0; i < components.size(); i++) if (components.get(i).name().equals(name)) return i;
        return fail();
    }

    static <T> T fail() { throw new UnsupportedOperationException("Unproven bytecode operation"); }
    static Object pop(List<Object> stack) { return stack.isEmpty() ? fail() : stack.removeLast(); }
}
