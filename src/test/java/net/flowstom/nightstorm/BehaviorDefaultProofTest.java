package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorDefaultProofTest {
    @Test
    void provesBothPolaritiesAndRejectsSimilarButChangedBehavior() {
        var packet = packet();
        var field = packet.recordComponents.getFirst();
        for (int opcode : List.of(Opcodes.IFEQ, Opcodes.IFNE)) {
            var oldOwner = consumer("unrelated/Consumer", -1, "effect");
            var newOwner = consumer(oldOwner.name, opcode, "effect");
            assertEquals(opcode == Opcodes.IFEQ, BehaviorDefaultProof.booleanValue(packet, field,
                    Map.of(oldOwner.name, oldOwner)::get, List.of(newOwner)).orElseThrow());
            newOwner = consumer(oldOwner.name, opcode, "otherEffect");
            assertTrue(BehaviorDefaultProof.booleanValue(packet, field, Map.of(oldOwner.name, oldOwner)::get, List.of(newOwner)).isEmpty());
        }
        var first = consumer("unrelated/First", -1, "effect");
        var second = consumer("unrelated/Second", -1, "effect");
        assertThrows(IllegalStateException.class, () -> BehaviorDefaultProof.booleanValue(packet, field,
                Map.of(first.name, first, second.name, second)::get,
                List.of(consumer(first.name, Opcodes.IFEQ, "effect"), consumer(second.name, Opcodes.IFNE, "effect"))));
    }

    @Test
    void provesTheGuardedPrefixWithoutAssumingThatUnrelatedLaterBehaviorIsUnchanged() {
        var oldOwner = consumer("unrelated/Consumer", -1, "effect");
        var newOwner = consumer(oldOwner.name, Opcodes.IFEQ, "effect");
        var method = newOwner.methods.getFirst();
        method.instructions.insertBefore(method.instructions.getLast(), new MethodInsnNode(Opcodes.INVOKESTATIC,
                "unrelated/Effects", "laterChange", "()V", false));
        var packet = packet();
        assertEquals(true, BehaviorDefaultProof.booleanValue(packet, packet.recordComponents.getFirst(),
                Map.of(oldOwner.name, oldOwner)::get, List.of(newOwner)).orElseThrow());
        // The old prefix must actually execute the guarded operation.
        oldOwner.methods.getFirst().instructions.insert(new InsnNode(Opcodes.RETURN));
        assertTrue(BehaviorDefaultProof.booleanValue(packet, packet.recordComponents.getFirst(),
                Map.of(oldOwner.name, oldOwner)::get, List.of(newOwner)).isEmpty());
    }

    private static ClassNode packet() {
        var node = new ClassNode();
        node.name = "unrelated/Notice";
        node.recordComponents = List.of(new RecordComponentNode("option", "Z", null));
        var getter = new MethodNode(Opcodes.ACC_PUBLIC, "option", "()Z", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "option", "Z"));
        getter.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(getter);
        return node;
    }

    private static ClassNode consumer(String owner, int opcode, String effect) {
        var node = new ClassNode();
        node.name = owner;
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "handle", "(Lunrelated/Notice;)V", null, null);
        var end = new LabelNode();
        if (opcode >= 0) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "unrelated/Notice", "option", "()Z", false));
            method.instructions.add(new JumpInsnNode(opcode, end));
        }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "unrelated/Effects", effect, "()V", false));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return node;
    }
}
