package net.flowstom.nightstorm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;

/** Executes only constant control flow and buffer operations; data-dependent branches are unsupported. */
final class WireManual {
    private record Value(WireSchema.Codec codec) { }
    private record Member(int index, Integer element) { }
    private static final class Array {
        final Object[] values;
        Array(int size) { if (size < 0 || size > 4096) WireSchema.fail(); values = new Object[size]; }
    }
    private static final Object SELF = new Object(), BUFFER = new Object();

    static List<WireSchema.Field> scan(ClassNode owner, MethodNode method, List<WireSchema.Component> components,
                                       boolean reading, Function<String, ClassNode> classes) {
        Map<Integer, Object> locals = new HashMap<>(); locals.put(0, SELF); locals.put(1, BUFFER);
        Map<Integer, Object> members = new HashMap<>();
        List<Object> stack = new ArrayList<>(); List<WireSchema.Field> result = new ArrayList<>();
        var instructions = Arrays.asList(method.instructions.toArray());
        int pc = 0, steps = 0;
        while (pc < instructions.size() && steps++ < 100_000) {
            var instruction = instructions.get(pc++); int op = instruction.getOpcode();
            if (op < 0) continue;
            if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) stack.add(op - Opcodes.ICONST_0);
            else if (instruction instanceof IntInsnNode literal && Set.of(Opcodes.BIPUSH, Opcodes.SIPUSH).contains(op)) stack.add(literal.operand);
            else if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Integer) stack.add(literal.cst);
            else if (instruction instanceof VarInsnNode variable) {
                if (Set.of(Opcodes.ALOAD, Opcodes.ILOAD).contains(op)) {
                    if (!locals.containsKey(variable.var)) return WireSchema.fail();
                    stack.add(locals.get(variable.var));
                } else if (Set.of(Opcodes.ASTORE, Opcodes.ISTORE).contains(op)) locals.put(variable.var, pop(stack));
                else return WireSchema.fail();
            } else if (instruction instanceof IincInsnNode increment) {
                if (!(locals.get(increment.var) instanceof Integer value)) return WireSchema.fail();
                locals.put(increment.var, value + increment.incr);
            } else if (instruction instanceof JumpInsnNode jump) {
                boolean branch;
                if (op == Opcodes.GOTO) branch = true;
                else {
                    Object right = pop(stack), left = pop(stack);
                    if (!(left instanceof Integer a) || !(right instanceof Integer b)) return WireSchema.fail();
                    branch = switch (op) {
                        case Opcodes.IF_ICMPGE -> a >= b; case Opcodes.IF_ICMPGT -> a > b;
                        case Opcodes.IF_ICMPLE -> a <= b; case Opcodes.IF_ICMPLT -> a < b;
                        case Opcodes.IF_ICMPEQ -> a.equals(b); case Opcodes.IF_ICMPNE -> !a.equals(b);
                        default -> WireSchema.fail();
                    };
                }
                if (branch) pc = instructions.indexOf(jump.label);
            } else if (instruction instanceof FieldInsnNode field) {
                int index = -1;
                for (int n = 0; n < components.size(); n++) if (components.get(n).name().equals(field.name)) index = n;
                if (op == Opcodes.GETSTATIC && field.desc.equals(WireSchema.STREAM_CODEC)) stack.add(new WireSchema.Codec(field.owner, field.name));
                else if (field.owner.equals(owner.name) && index >= 0 && op == Opcodes.GETFIELD && pop(stack) == SELF)
                    stack.add(reading ? members.get(index) : new Member(index, null));
                else if (field.owner.equals(owner.name) && index >= 0 && op == Opcodes.PUTFIELD && reading) {
                    Object value = pop(stack); if (pop(stack) != SELF || members.putIfAbsent(index, value) != null) return WireSchema.fail();
                    if (value instanceof Value scalar) result.add(new WireSchema.Field(index, scalar.codec()));
                    else if (!(value instanceof Array)) return WireSchema.fail();
                } else return WireSchema.fail();
            } else if (instruction instanceof TypeInsnNode type && op == Opcodes.ANEWARRAY) {
                if (!(pop(stack) instanceof Integer size)) return WireSchema.fail();
                stack.add(new Array(size));
            }
            else if (op == Opcodes.AASTORE) {
                Object value = pop(stack), element = pop(stack), array = pop(stack);
                if (!(element instanceof Integer index) || !(array instanceof Array target)
                        || index < 0 || index >= target.values.length || target.values[index] != null) return WireSchema.fail();
                target.values[index] = value;
                var component = members.entrySet().stream().filter(e -> e.getValue() == array).findFirst();
                if (component.isEmpty() || !(value instanceof Value codec)) return WireSchema.fail();
                if (result.stream().filter(f -> f.component() == component.get().getKey()).count() != index) return WireSchema.fail();
                result.add(new WireSchema.Field(component.get().getKey(), codec.codec()));
            } else if (op == Opcodes.AALOAD) {
                Object index = pop(stack), array = pop(stack);
                if (!(index instanceof Integer element) || !(array instanceof Member member) || member.element() != null) return WireSchema.fail();
                stack.add(new Member(member.index(), element));
            } else if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("<init>") && Set.of("java/lang/Object", "java/lang/Record").contains(call.owner) && pop(stack) == SELF) continue;
                if (!call.owner.endsWith("FriendlyByteBuf")) return WireSchema.fail();
                var arguments = new Object[Type.getArgumentTypes(call.desc).length];
                for (int n = arguments.length - 1; n >= 0; n--) arguments[n] = pop(stack);
                if (call.getOpcode() == Opcodes.INVOKESTATIC || pop(stack) != BUFFER) return WireSchema.fail();
                boolean read = call.name.startsWith("read"), write = call.name.startsWith("write");
                if (read != reading || !read && !write) return WireSchema.fail();
                String suffix = call.name.substring(read ? 4 : 5);
                String primitive = switch (suffix) {
                    case "Boolean" -> "BOOL"; case "Byte" -> "BYTE"; case "Short" -> "SHORT"; case "Int" -> "INT";
                    case "VarInt" -> "VAR_INT"; case "Long" -> "LONG"; case "VarLong" -> "VAR_LONG";
                    case "Float" -> "FLOAT"; case "Double" -> "DOUBLE"; case "Utf" -> "STRING_UTF8"; default -> null;
                };
                WireSchema.Codec codec = primitive == null ? delegated(call, read, classes) : new WireSchema.Codec(WireSchema.BYTE_CODECS, primitive);
                if (codec == null) return WireSchema.fail();
                if (reading) {
                    if (arguments.length != 0 && !(primitive != null && primitive.equals("STRING_UTF8") && arguments.length == 1 && arguments[0] instanceof Integer)) return WireSchema.fail();
                    stack.add(new Value(codec));
                } else {
                    if (arguments.length != 1 || !(arguments[0] instanceof Member member)) return WireSchema.fail();
                    if (member.element() != null) {
                        long prior = result.stream().filter(f -> f.component() == member.index()).count();
                        if (prior != member.element()) return WireSchema.fail();
                    }
                    result.add(new WireSchema.Field(member.index(), codec));
                    if (Type.getReturnType(call.desc) != Type.VOID_TYPE) stack.add(BUFFER);
                }
            } else if (op == Opcodes.POP) pop(stack);
            else if (op == Opcodes.DUP) { Object value = pop(stack); stack.add(value); stack.add(value); }
            else if (op == Opcodes.RETURN) {
                if (!stack.isEmpty()) return WireSchema.fail();
                if (reading && members.values().stream().filter(Array.class::isInstance).map(Array.class::cast)
                        .anyMatch(array -> Arrays.stream(array.values).anyMatch(Objects::isNull))) return WireSchema.fail();
                return collapse(result, components);
            } else return WireSchema.fail();
        }
        return WireSchema.fail();
    }

    private static List<WireSchema.Field> collapse(List<WireSchema.Field> values, List<WireSchema.Component> components) {
        List<WireSchema.Field> result = new ArrayList<>();
        for (int n = 0; n < values.size();) {
            var first = values.get(n); int end = n + 1;
            while (end < values.size() && values.get(end).component() == first.component() && values.get(end).codec().equals(first.codec())) end++;
            if (components.get(first.component()).descriptor().startsWith("[")) {
                // Every array element was individually proved, in order, with the same codec.
                result.add(new WireSchema.Field(first.component(), first.codec().apply("fixed:" + (end - n))));
            } else {
                if (end != n + 1) return WireSchema.fail();
                result.add(first);
            }
            n = end;
        }
        return List.copyOf(result);
    }

    private static WireSchema.Codec delegated(MethodInsnNode call, boolean reading, Function<String, ClassNode> classes) {
        String descriptor = reading ? Type.getReturnType(call.desc).getDescriptor() : Type.getArgumentTypes(call.desc)[0].getDescriptor();
        if (!descriptor.startsWith("L")) return null;
        String owner = Type.getType(descriptor).getInternalName();
        ClassNode type = classes.apply(owner);
        if (type == null) return null;
        for (MethodNode initializer : type.methods) {
            if (!initializer.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : initializer.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner) || !field.name.equals("STREAM_CODEC")) continue;
                AbstractInsnNode previous = instruction.getPrevious();
                while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                if (!(previous instanceof MethodInsnNode constructor) || !constructor.name.equals("<init>")) continue;
                ClassNode implementation = classes.apply(constructor.owner);
                if (implementation == null) continue;
                for (MethodNode method : implementation.methods) {
                    if (!method.name.equals(reading ? "decode" : "encode") || (method.access & Opcodes.ACC_BRIDGE) != 0) continue;
                    var invocations = Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                            .map(MethodInsnNode.class::cast).toList();
                    if (invocations.size() == 1 && sameOperation(invocations.getFirst(), call, classes))
                        return new WireSchema.Codec(owner, "STREAM_CODEC");
                }
            }
        }
        return null;
    }
    private static boolean sameOperation(MethodInsnNode first, MethodInsnNode second, Function<String, ClassNode> classes) {
        MethodInsnNode canonical = second;
        ClassNode owner = classes.apply(second.owner);
        if (owner != null) {
            var wrapper = owner.methods.stream().filter(m -> m.name.equals(second.name) && m.desc.equals(second.desc)).findFirst();
            if (wrapper.isPresent()) {
                var calls = Arrays.stream(wrapper.get().instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
                boolean forwarding = Arrays.stream(wrapper.get().instructions.toArray()).allMatch(i -> i.getOpcode() < 0
                        || i instanceof MethodInsnNode || i instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD
                        || Set.of(Opcodes.RETURN, Opcodes.ARETURN).contains(i.getOpcode()));
                if (forwarding && calls.size() == 1) canonical = calls.getFirst();
            }
        }
        return first.owner.equals(canonical.owner) && first.name.equals(canonical.name) && first.desc.equals(canonical.desc);
    }
    private static Object pop(List<Object> stack) { return WireSchema.pop(stack); }
}
