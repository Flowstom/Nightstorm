package net.flowstom.nightstorm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Function;

/** Proves defaults by matching a folded method, or an unchanged unconditional prefix around a new guard. */
final class BehaviorDefaultProof {
    private BehaviorDefaultProof() {}

    static Optional<Boolean> booleanValue(ClassNode packet, RecordComponentNode field,
                                           Function<String, ClassNode> baseline, Collection<ClassNode> target) {
        var accessor = packet.methods.stream().filter(m -> m.name.equals(field.name) && m.desc.equals("()Z")).findFirst().orElse(null);
        if (accessor == null) return Optional.empty();
        var body = Arrays.stream(accessor.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        if (body.size() != 3 || !(body.get(0) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || !(body.get(1) instanceof FieldInsnNode get) || get.getOpcode() != Opcodes.GETFIELD
                || !get.owner.equals(packet.name) || !get.name.equals(field.name) || !get.desc.equals("Z")
                || body.get(2).getOpcode() != Opcodes.IRETURN) return Optional.empty();
        Boolean result = null;
        for (var owner : target) {
            var oldOwner = baseline.apply(owner.name);
            if (oldOwner == null) continue;
            for (var method : owner.methods) {
                if (Arrays.stream(method.instructions.toArray()).noneMatch(i -> accessor(i, packet.name, field.name))) continue;
                var oldMethod = oldOwner.methods.stream().filter(m -> m.name.equals(method.name) && m.desc.equals(method.desc)).findFirst().orElse(null);
                if (oldMethod == null) continue;
                var oldBody = normalized(oldMethod, null, null, false);
                if (oldBody == null) continue;
                boolean falseMatches = oldBody.equals(normalized(method, packet.name, field.name, false));
                boolean trueMatches = oldBody.equals(normalized(method, packet.name, field.name, true));
                Boolean value = falseMatches != trueMatches ? Boolean.valueOf(trueMatches) : unchangedGuardPrefix(method, packet.name, field.name, oldBody);
                if (value == null) continue;
                if (result != null && !result.equals(value)) throw new IllegalStateException("Conflicting behavior defaults for " + field.name);
                result = value;
            }
        }
        return Optional.ofNullable(result);
    }

    private static boolean accessor(AbstractInsnNode i, String owner, String field) {
        return i instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(field) && call.desc.equals("()Z");
    }

    private static Boolean unchangedGuardPrefix(MethodNode method, String packet, String field, List<String> baseline) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        var code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        var uses = java.util.stream.IntStream.range(0, code.size()).filter(i -> accessor(code.get(i), packet, field)).boxed().toList();
        if (uses.size() != 1) return null;
        int index = uses.getFirst();
        if (index == 0 || index + 1 >= code.size() || !(code.get(index - 1) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || !(code.get(index + 1) instanceof JumpInsnNode guard) || !Set.of(Opcodes.IFEQ, Opcodes.IFNE).contains(guard.getOpcode())) return null;
        int end = destination(code, guard.label);
        if (end <= index + 2) return null;
        // The complete prefix must be unconditional, with only this newly added guard interrupting it.
        var prefix = new MethodNode();
        for (int i = 0; i < end; i++) {
            if (i >= index - 1 && i <= index + 1) continue;
            var instruction = code.get(i);
            int op = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode || instruction instanceof LookupSwitchInsnNode || instruction instanceof TableSwitchInsnNode
                    || op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW) return null;
            prefix.instructions.add(instruction.clone(new HashMap<>()));
        }
        prefix.instructions.add(new InsnNode(Opcodes.RETURN));
        var tokens = normalized(prefix, null, null, false);
        if (tokens == null) return null;
        tokens = tokens.subList(0, tokens.size() - 1);
        return baseline.size() >= tokens.size() && baseline.subList(0, tokens.size()).equals(tokens)
                ? guard.getOpcode() == Opcodes.IFEQ : null;
    }

    private static List<String> normalized(MethodNode method, String packet, String field, boolean value) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        var code = Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        if (code.isEmpty()) return null;
        var folded = new HashMap<Integer, Integer>();
        var removed = new HashSet<Integer>();
        if (packet != null) for (int i = 0; i < code.size(); i++) {
            if (!accessor(code.get(i), packet, field)) continue;
            if (i == 0 || i + 1 >= code.size() || !(code.get(i - 1) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                    || !(code.get(i + 1) instanceof JumpInsnNode jump) || !Set.of(Opcodes.IFEQ, Opcodes.IFNE).contains(jump.getOpcode())) return null;
            // Entering the middle of the expression would make removing its stack operations unsafe.
            for (var node = code.get(i - 1).getNext(); node != code.get(i + 1); node = node.getNext()) if (node instanceof LabelNode) return null;
            boolean take = jump.getOpcode() == Opcodes.IFEQ ? !value : value;
            folded.put(i + 1, take ? destination(code, jump.label) : i + 2);
            removed.addAll(List.of(i - 1, i, i + 1));
        }
        var reachable = new TreeSet<Integer>();
        var pending = new ArrayDeque<Integer>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int i = pending.removeFirst();
            if (i < 0 || i >= code.size() || !reachable.add(i)) continue;
            var instruction = code.get(i);
            int op = instruction.getOpcode();
            if (folded.containsKey(i)) pending.add(folded.get(i));
            else if (instruction instanceof JumpInsnNode jump) {
                pending.add(destination(code, jump.label));
                if (op != Opcodes.GOTO) pending.add(i + 1);
            } else if (instruction instanceof LookupSwitchInsnNode select) {
                pending.add(destination(code, select.dflt));
                select.labels.forEach(label -> pending.add(destination(code, label)));
            } else if (instruction instanceof TableSwitchInsnNode select) {
                pending.add(destination(code, select.dflt));
                select.labels.forEach(label -> pending.add(destination(code, label)));
            } else if (!(op >= Opcodes.IRETURN && op <= Opcodes.RETURN) && op != Opcodes.ATHROW) pending.add(i + 1);
        }
        var kept = reachable.stream().filter(i -> !removed.contains(i)).toList();
        var labels = new HashMap<LabelNode, Integer>();
        for (var i : method.instructions) if (i instanceof LabelNode label) {
            int target = destination(code, label);
            int rank = 0;
            while (rank < kept.size() && kept.get(rank) < target) rank++;
            labels.put(label, rank);
        }
        var result = new ArrayList<String>();
        for (int index : kept) {
            var i = code.get(index);
            String token = Integer.toString(i.getOpcode());
            if (i instanceof VarInsnNode n) token += ":" + n.var;
            else if (i instanceof IntInsnNode n) token += ":" + n.operand;
            else if (i instanceof TypeInsnNode n) token += ":" + n.desc;
            else if (i instanceof FieldInsnNode n) token += ":" + n.owner + '#' + n.name + ':' + n.desc;
            else if (i instanceof MethodInsnNode n) token += ":" + n.owner + '#' + n.name + n.desc + ':' + n.itf;
            else if (i instanceof InvokeDynamicInsnNode n) token += ":" + n.name + n.desc + ':' + n.bsm + ':' + Arrays.deepToString(n.bsmArgs);
            else if (i instanceof LdcInsnNode n) token += ":" + n.cst.getClass().getName() + ':' + n.cst;
            else if (i instanceof IincInsnNode n) token += ":" + n.var + ':' + n.incr;
            else if (i instanceof JumpInsnNode n) token += ":" + labels.get(n.label);
            else if (i instanceof LookupSwitchInsnNode n) token += ":" + n.keys + ':' + n.labels.stream().map(labels::get).toList() + ':' + labels.get(n.dflt);
            else if (i instanceof TableSwitchInsnNode n) token += ":" + n.min + ':' + n.max + ':' + n.labels.stream().map(labels::get).toList() + ':' + labels.get(n.dflt);
            else if (i instanceof MultiANewArrayInsnNode n) token += ":" + n.desc + ':' + n.dims;
            else if (!(i instanceof InsnNode)) return null;
            result.add(token);
        }
        return List.copyOf(result);
    }

    private static int destination(List<AbstractInsnNode> code, LabelNode label) {
        AbstractInsnNode node = label;
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node == null ? code.size() : code.indexOf(node);
    }
}
