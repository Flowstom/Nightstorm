package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WireSchemaTest {
    @Test
    void unitCodecSingletonDoesNotBecomeAWireField() {
        final var node = WireFixtures.node("synthetic/Empty", List.of(), List.of(), List.of(), true);
        final var initializer = method(node, "<clinit>");
        final var call = (MethodInsnNode) initializer.instructions.getFirst();
        call.name = "unit";
        call.desc = "(Ljava/lang/Object;)" + WireSchema.STREAM_CODEC;
        initializer.instructions.insertBefore(call, new FieldInsnNode(Opcodes.GETSTATIC, node.name, "INSTANCE", "L" + node.name + ";"));
        assertTrue(WireSchema.scan(node).orElseThrow().fields().isEmpty());
    }
    @Test
    void projectsRemovedFieldsAndTranslatesIndependentMapKeyAndValueCodecs() {
        assertEquals(List.of(0, 2), WireFixtures.removal().plan().bindings().stream().map(WireMigration.Binding::source).toList());
        final var added = WireFixtures.collectionAddition().plan().bindings().getLast();
        assertTrue(added.missing());
        assertEquals("java.util.Map<String, String>", added.javaType());
        assertEquals("NetworkBuffer.STRING.mapValue(NetworkBuffer.STRING)", added.networkType());
    }
    @Test
    void separatesWireOrderFromConstructorOrderAndAcceptsPrimitiveEncodingChanges() {
        var fields = List.of("first:I", "second:I");
        var before = WireFixtures.node("synthetic/Pair", fields, List.of("INT", "INT"), List.of(0, 1), true);
        var after = WireFixtures.node("synthetic/Pair", fields, List.of("VAR_INT", "INT"), List.of(1, 0), true);
        var plan = WireMigration.plan(before, after, name -> null).orElseThrow();
        assertEquals(List.of(1, 0), plan.bindings().stream().map(WireMigration.Binding::source).toList());
        assertEquals(List.of("NetworkBuffer.INT", "NetworkBuffer.VAR_INT"), plan.bindings().stream().map(WireMigration.Binding::networkType).toList());
    }

    @Test
    void rejectsNarrowerNumericDomainsEvenWhenJavaTypesMatch() {
        var fields = List.of("value:I");
        var before = WireFixtures.node("synthetic/Count", fields, List.of("INT"), List.of(0), true);
        var after = WireFixtures.node("synthetic/Count", fields, List.of("UNSIGNED_SHORT"), List.of(0), true);
        assertTrue(WireMigration.plan(before, after, name -> null).isEmpty());
    }

    @Test
    void rejectsDuplicateAndUnknownGetterBindings() {
        var fixture = WireFixtures.acknowledgement();
        var initializer = method(fixture.after(), "<clinit>");
        var accessors = java.util.Arrays.stream(initializer.instructions.toArray()).filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast).toList();
        accessors.get(1).bsmArgs = accessors.getFirst().bsmArgs;
        assertTrue(WireSchema.scan(fixture.after()).isEmpty());
    }

    @Test
    void rejectsUnrecognizedCodecTransformAndDescriptorMismatch() {
        var fixture = WireFixtures.acknowledgement();
        var initializer = method(fixture.after(), "<clinit>");
        var first = initializer.instructions.getFirst();
        initializer.instructions.insert(first, new MethodInsnNode(Opcodes.INVOKESTATIC, "synthetic/Transform", "scramble",
                "(" + WireSchema.STREAM_CODEC + ")" + WireSchema.STREAM_CODEC, false));
        assertTrue(WireSchema.scan(fixture.after()).isEmpty());
        fixture = WireFixtures.acknowledgement();
        ((FieldInsnNode) method(fixture.after(), "<clinit>").instructions.getFirst()).name = "STRING_UTF8";
        assertTrue(WireMigration.plan(fixture.before(), fixture.after(), name -> null).isEmpty());
    }

    @Test
    void checksManualReadAndWriteAgreement() {
        var fixture = WireFixtures.acknowledgement();
        var writer = method(fixture.before(), "write");
        for (var instruction : writer.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("writeVarInt")) call.name = "writeInt";
        }
        assertTrue(WireSchema.scan(fixture.before()).isEmpty());
    }

    @Test
    void rejectsBranchesAndComputedCompatibilityArguments() {
        for (boolean branch : List.of(true, false)) {
            var fixture = WireFixtures.effect();
            var constructor = fixture.after().methods.stream().filter(m -> m.name.equals("<init>") && m.desc.endsWith("FFFFI)V"))
                    .findFirst().orElseThrow();
            constructor.instructions.insertBefore(constructor.instructions.getLast(), branch
                    ? new JumpInsnNode(Opcodes.GOTO, new LabelNode()) : new InsnNode(Opcodes.IADD));
            assertTrue(WireMigration.plan(fixture.before(), fixture.after(), fixture.extra()::get).isEmpty());
        }
    }

    @Test
    void infersExplicitNumericDefaultsWithoutAssumingZero() {
        var before = WireFixtures.node("synthetic/Notice", List.of("sequence:I"), List.of("VAR_INT"), List.of(0), true);
        var after = WireFixtures.node("synthetic/Notice", List.of("sequence:I", "weight:D"), List.of("VAR_INT", "DOUBLE"), List.of(0, 1), true);
        var constructor = after.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 1);
        constructor.visitLdcInsn(3.5d);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, after.name, "<init>", "(ID)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(4, 2);
        constructor.visitEnd();
        var plan = WireMigration.plan(before, after, name -> null).orElseThrow();
        assertEquals("3.5d", plan.bindings().getLast().constant());
    }

    @Test
    void composesSuccessiveSnapshotsAndRejectsUnrelatedSavedSchemas() throws Exception {
        var fixture = WireFixtures.effect();
        var previous = fixture.plan();
        var nextNode = new ClassNode();
        fixture.after().accept(nextNode);
        var codecs = java.util.Arrays.stream(method(nextNode, "<clinit>").instructions.toArray())
                .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                .filter(field -> field.getOpcode() == Opcodes.GETSTATIC).toList();
        codecs.get(12).name = "INT";
        var next = WireMigration.plan(fixture.after(), nextNode, fixture.extra()::get).orElseThrow();
        // Exercise exactly the JSON representation persisted between workflow runs.
        var restored = Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(previous), WireMigration.class);
        var composed = WireMigration.compose(restored, next);
        assertEquals(previous.baseline(), composed.baseline());
        assertEquals(composed, WireMigration.compose(composed, next));
        assertEquals("NetworkBuffer.INT", composed.bindings().get(12).networkType());
        assertEquals("7", composed.bindings().getLast().constant());
        assertEquals(9, composed.bindings().get(10).source());
        assertThrows(IllegalStateException.class, () -> WireMigration.compose(previous, WireFixtures.acknowledgement().plan()));
    }

    @Test
    void constructorAssignmentsFollowParameterSlotsRatherThanStatementOrder() {
        var fixture = WireFixtures.acknowledgement();
        var constructor = fixture.after().methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        var mapping = ConstructorMapping.assignments(fixture.after(), constructor).orElseThrow();
        assertEquals(new ConstructorMapping.Parameter(3, "D"), mapping.get("north"));
        assertEquals(new ConstructorMapping.Parameter(5, "F"), mapping.get("pitch"));
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
