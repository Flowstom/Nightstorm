package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class CodecFunctionsTest {
    @Test
    void learnsPortableScalarFactoriesForUnrelatedCarriersAndRejectsEncodingMismatch() {
        var read = new MethodNode();
        read.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        read.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", "readLong", "()J", false));
        read.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/time/Instant", "ofEpochMilli", "(J)Ljava/time/Instant;", false));
        read.instructions.add(new InsnNode(Opcodes.ARETURN));
        var write = new MethodNode();
        write.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        write.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        write.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/time/Instant", "toEpochMilli", "()J", false));
        var output = new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf", "writeLong", "(J)V", false);
        write.instructions.add(output);
        write.instructions.add(new InsnNode(Opcodes.RETURN));
        var adapter = CodecFunctions.analyze(read, write, "Ljava/time/Instant;", 1, 1, 2).orElseThrow();
        assertEquals("NetworkBuffer.LONG.transform(java.time.Instant::ofEpochMilli, java.time.Instant::toEpochMilli)", adapter.expression());
        output.name = "writeVarLong";
        assertTrue(CodecFunctions.analyze(read, write, "Ljava/time/Instant;", 1, 1, 2).isEmpty());
        output.name = "writeLong";
        read.instructions.insertBefore(read.instructions.getLast(), new JumpInsnNode(Opcodes.GOTO, new LabelNode()));
        assertTrue(CodecFunctions.analyze(read, write, "Ljava/time/Instant;", 1, 1, 2).isEmpty());
    }
}
