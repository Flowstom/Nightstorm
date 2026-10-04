package net.flowstom.nightstorm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Producer provenance survives local variables and merges, but never an unproven computation. */
final class BytecodeArguments {
    private BytecodeArguments() {}

    static Frame<SourceValue>[] frames(ClassNode owner, MethodNode method) {
        try {
            var interpreter = new SourceInterpreter(Opcodes.ASM9) {
                @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
                    return value.insns.isEmpty() && instruction instanceof VarInsnNode load
                            && (load.getOpcode() == Opcodes.ALOAD || load.getOpcode() == Opcodes.ILOAD
                            || load.getOpcode() == Opcodes.LLOAD || load.getOpcode() == Opcodes.FLOAD || load.getOpcode() == Opcodes.DLOAD)
                            ? new SourceValue(value.getSize(), instruction) : value;
                }
            };
            return new Analyzer<>(interpreter).analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException ignored) { return null; }
    }

    static SourceValue argument(Frame<SourceValue> frame, int count, int index) {
        return frame == null || frame.getStackSize() < count ? null : frame.getStack(frame.getStackSize() - count + index);
    }

    static Object constant(Frame<SourceValue> frame, MethodInsnNode call, int index) {
        var value = argument(frame, Type.getArgumentTypes(call.desc).length, index);
        if (value == null || value.insns.isEmpty()) return null;
        Object result = null;
        for (var instruction : value.insns) {
            Object literal = literal(instruction);
            if (literal == null || result != null && !result.equals(literal)) return null;
            result = literal;
        }
        return result;
    }

    private static Object literal(AbstractInsnNode i) {
        if (i instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof Number)) return ldc.cst;
        if (i instanceof IntInsnNode push && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)) return push.operand;
        int op = i.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (op >= Opcodes.LCONST_0 && op <= Opcodes.LCONST_1) return (long) (op - Opcodes.LCONST_0);
        if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (float) (op - Opcodes.FCONST_0);
        if (op >= Opcodes.DCONST_0 && op <= Opcodes.DCONST_1) return (double) (op - Opcodes.DCONST_0);
        return null;
    }
}
