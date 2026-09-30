package net.flowstom.nightstorm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/** A deliberately bounded interpreter: forwarding, constants and constructor delegation only. */
final class ConstructorMapping {
    private ConstructorMapping() { }

    sealed interface Value permits Parameter, Constant, EnumConstant { }
    record Parameter(int index, String descriptor) implements Value { }
    record Constant(Object value) implements Value { }
    record EnumConstant(String owner, String name) implements Value { }

    static Optional<Map<String, Value>> assignments(ClassNode owner, MethodNode constructor) {
        try {
            var parameters = Type.getArgumentTypes(constructor.desc);
            var arguments = new ArrayList<Value>();
            for (int i = 0; i < parameters.length; i++) arguments.add(new Parameter(i, parameters[i].getDescriptor()));
            return Optional.of(evaluate(owner, constructor, arguments, new HashSet<>()));
        } catch (UnsupportedOperationException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, Value> evaluate(ClassNode owner, MethodNode method, List<Value> arguments, Set<String> active) {
        if (!active.add(method.desc) || !method.tryCatchBlocks.isEmpty()) return WireSchema.fail();
        try {
            final Object self = new Object();
            var locals = new HashMap<Integer, Object>();
            locals.put(0, self);
            var parameters = Type.getArgumentTypes(method.desc);
            int slot = 1;
            for (int i = 0; i < parameters.length; i++) {
                locals.put(slot, arguments.get(i));
                slot += parameters[i].getSize();
            }
            var stack = new ArrayList<Object>();
            var fields = new LinkedHashMap<String, Value>();
            boolean returned = false;
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                if (returned) return WireSchema.fail();
                if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                    if (!locals.containsKey(variable.var)) return WireSchema.fail();
                    stack.add(locals.get(variable.var));
                } else if (instruction instanceof LdcInsnNode constant && (constant.cst instanceof Number || constant.cst instanceof String)) {
                    stack.add(new Constant(constant.cst));
                } else if (instruction instanceof IntInsnNode constant && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
                    stack.add(new Constant(constant.operand));
                } else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) stack.add(new Constant(opcode - Opcodes.ICONST_0));
                else if (opcode >= Opcodes.LCONST_0 && opcode <= Opcodes.LCONST_1) stack.add(new Constant((long) (opcode - Opcodes.LCONST_0)));
                else if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) stack.add(new Constant((float) (opcode - Opcodes.FCONST_0)));
                else if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) stack.add(new Constant((double) (opcode - Opcodes.DCONST_0)));
                else if (opcode == Opcodes.ACONST_NULL) stack.add(new Constant(null));
                else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC
                        && field.desc.equals("L" + field.owner + ";")) {
                    stack.add(new EnumConstant(field.owner, field.name));
                } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.PUTFIELD && field.owner.equals(owner.name)) {
                    Object value = WireSchema.pop(stack);
                    if (WireSchema.pop(stack) != self || !(value instanceof Value mapped) || fields.putIfAbsent(field.name, mapped) != null) return WireSchema.fail();
                } else if (instruction instanceof MethodInsnNode call && opcode == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) {
                    var passed = new ArrayList<Value>();
                    for (Type ignored : Type.getArgumentTypes(call.desc)) {
                        Object value = WireSchema.pop(stack);
                        if (!(value instanceof Value mapped)) return WireSchema.fail();
                        passed.addFirst(mapped);
                    }
                    if (WireSchema.pop(stack) != self) return WireSchema.fail();
                    if (call.owner.equals(owner.name)) {
                        var delegated = owner.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(call.desc))
                                .findFirst().orElseThrow(UnsupportedOperationException::new);
                        for (var assignment : evaluate(owner, delegated, passed, active).entrySet()) {
                            if (fields.putIfAbsent(assignment.getKey(), assignment.getValue()) != null) return WireSchema.fail();
                        }
                    } else if (!(call.owner.equals("java/lang/Object") || call.owner.equals("java/lang/Record")) || !passed.isEmpty()) {
                        return WireSchema.fail();
                    }
                } else if (opcode == Opcodes.RETURN && stack.isEmpty()) returned = true;
                else return WireSchema.fail();
            }
            if (!returned) return WireSchema.fail();
            return Map.copyOf(fields);
        } finally {
            active.remove(method.desc);
        }
    }
}
