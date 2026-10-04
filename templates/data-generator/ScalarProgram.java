package net.minestom.generators;

import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Portable, bounded read-only accessor programs copied from baseline bytecode. */
final class ScalarProgram {
    private ScalarProgram() { }
    static JsonArray capture(Method method) {
        try (InputStream input = method.getDeclaringClass().getResourceAsStream("/" + method.getDeclaringClass().getName().replace('.', '/') + ".class")) {
            if (input == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MethodNode body = node.methods.stream().filter(m -> m.name.equals(method.getName())
                    && m.desc.equals(org.objectweb.asm.Type.getMethodDescriptor(method))).findFirst().orElseThrow();
            JsonArray program = new JsonArray();
            List<AbstractInsnNode> code = Arrays.asList(body.instructions.toArray());
            if (code.size() > 128 || !body.tryCatchBlocks.isEmpty()) return null;
            for (AbstractInsnNode instruction : code) {
                int op = instruction.getOpcode();
                JsonObject entry = new JsonObject();
                entry.addProperty("op", op);
                if (op < 0) { program.add(entry); continue; }
                if (instruction instanceof VarInsnNode variable && Set.of(Opcodes.ALOAD, Opcodes.ASTORE,
                        Opcodes.ILOAD, Opcodes.ISTORE).contains(op)) entry.addProperty("slot", variable.var);
                else if (instruction instanceof FieldInsnNode field && Set.of(Opcodes.GETFIELD, Opcodes.GETSTATIC).contains(op)) {
                    entry.addProperty("owner", field.owner); entry.addProperty("name", field.name); entry.addProperty("descriptor", field.desc);
                } else if (instruction instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                        && org.objectweb.asm.Type.getArgumentTypes(call.desc).length == 0
                        && org.objectweb.asm.Type.getReturnType(call.desc) != org.objectweb.asm.Type.VOID_TYPE
                        && !call.name.equals("<init>")) {
                    entry.addProperty("owner", call.owner); entry.addProperty("name", call.name); entry.addProperty("descriptor", call.desc);
                } else if (instruction instanceof JumpInsnNode jump && Set.of(Opcodes.GOTO, Opcodes.IFEQ, Opcodes.IFNE,
                        Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                        Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE).contains(op)) entry.addProperty("target", code.indexOf(jump.label));
                else if (instruction instanceof TypeInsnNode && op == Opcodes.CHECKCAST) { }
                else if (instruction instanceof LdcInsnNode literal && (literal.cst instanceof Number || literal.cst instanceof String))
                    { entry.add("value", new Gson().toJsonTree(literal.cst)); entry.addProperty("kind", literal.cst.getClass().getSimpleName()); }
                else if (!Set.of(Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                        Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.ACONST_NULL,
                        Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.LRETURN).contains(op)) return null;
                program.add(entry);
            }
            return program;
        } catch (IOException failure) { throw new IllegalStateException("Cannot read scalar program " + method, failure); }
    }

    static Object evaluate(JsonArray program, Object value, Class<?> result) {
        List<Object> stack = new ArrayList<>(); Map<Integer, Object> locals = new HashMap<>(); locals.put(0, value);
        int position = 0, steps = 0;
        try {
            while (position < program.size() && steps++ < 256) {
                JsonObject entry = program.get(position++).getAsJsonObject();
                int op = entry.get("op").getAsInt();
                if (op < 0 || op == Opcodes.CHECKCAST) continue;
                if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) stack.add(op - Opcodes.ICONST_0);
                else if (op == Opcodes.ACONST_NULL) stack.add(null);
                else if (op == Opcodes.ALOAD || op == Opcodes.ILOAD) stack.add(locals.get(entry.get("slot").getAsInt()));
                else if (op == Opcodes.ASTORE || op == Opcodes.ISTORE) locals.put(entry.get("slot").getAsInt(), pop(stack));
                else if (op == Opcodes.GETSTATIC || op == Opcodes.GETFIELD) {
                    Class<?> owner = Class.forName(entry.get("owner").getAsString().replace('/', '.'), false, value.getClass().getClassLoader());
                    Field field = owner.getDeclaredField(entry.get("name").getAsString());
                    if (!org.objectweb.asm.Type.getDescriptor(field.getType()).equals(entry.get("descriptor").getAsString())) throw new IllegalStateException("Scalar field type changed");
                    field.setAccessible(true);
                    stack.add(field.get(op == Opcodes.GETSTATIC ? null : pop(stack)));
                } else if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKESPECIAL) {
                    Object receiver = pop(stack);
                    Class<?> owner = Class.forName(entry.get("owner").getAsString().replace('/', '.'), false, value.getClass().getClassLoader());
                    Method method = owner.getMethod(entry.get("name").getAsString());
                    if (!org.objectweb.asm.Type.getMethodDescriptor(method).equals(entry.get("descriptor").getAsString())) throw new IllegalStateException("Scalar method type changed");
                    stack.add(method.invoke(receiver));
                } else if (op == Opcodes.LDC) {
                    JsonPrimitive literal = entry.getAsJsonPrimitive("value");
                    stack.add(switch(entry.get("kind").getAsString()) {
                        case "String" -> literal.getAsString(); case "Long" -> literal.getAsLong(); case "Float" -> literal.getAsFloat();
                        case "Double" -> literal.getAsDouble(); case "Integer" -> literal.getAsInt(); default -> throw new IllegalStateException("Unsupported scalar literal");
                    });
                } else if (Set.of(Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.LRETURN).contains(op)) {
                    Object returned = pop(stack);
                    if (!stack.isEmpty()) throw new IllegalStateException("Unbalanced scalar program");
                    return result == boolean.class && returned instanceof Number number ? number.intValue() != 0 : returned;
                } else {
                    boolean branch = switch (op) {
                        case Opcodes.GOTO -> true;
                        case Opcodes.IFEQ -> integer(pop(stack)) == 0;
                        case Opcodes.IFNE -> integer(pop(stack)) != 0;
                        case Opcodes.IFNULL -> pop(stack) == null;
                        case Opcodes.IFNONNULL -> pop(stack) != null;
                        case Opcodes.IF_ACMPEQ -> pop(stack) == pop(stack);
                        case Opcodes.IF_ACMPNE -> pop(stack) != pop(stack);
                        case Opcodes.IF_ICMPEQ -> integer(pop(stack)) == integer(pop(stack));
                        case Opcodes.IF_ICMPNE -> integer(pop(stack)) != integer(pop(stack));
                        default -> throw new IllegalStateException("Unsupported scalar opcode " + op);
                    };
                    if (branch) position = entry.get("target").getAsInt();
                }
            }
            throw new IllegalStateException("Scalar program did not return within its instruction bound");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Scalar program dependency disappeared", failure); }
    }
    private static Object pop(List<Object> stack) { return stack.removeLast(); }
    private static int integer(Object value) { return value instanceof Boolean bool ? bool ? 1 : 0 : ((Number) value).intValue(); }
}
