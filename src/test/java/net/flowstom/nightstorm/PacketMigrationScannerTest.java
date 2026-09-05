package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketMigrationScannerTest {
    private static final String RECORD = "synthetic/wire/Container";
    private static final String ENUM = "synthetic/wire/Mode";
    private static final String STREAM_CODEC = "Lnet/minecraft/network/codec/StreamCodec;";

    @Test
    void migratesTeleportPositionAndRejectsDifferentEncoding() throws Exception {
        final String owner = "net/minecraft/network/protocol/game/ServerboundAcceptTeleportationPacket";
        final List<String> before = List.of("id:I");
        final List<String> after = List.of("id:I", "x:D", "y:D", "z:D", "yRot:F", "xRot:F");
        final Path baseline = jar(Map.of(owner, snapshotPacket(owner, before, List.of("VarInt"), false)));
        final Path target = jar(Map.of(owner, snapshotPacket(owner, after,
                List.of("VAR_INT", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT"), true)));
        final var packet = new PacketUpdater.RetainedPacket("Teleport", "Teleport.SERIALIZER", owner, owner);
        assertEquals(PacketMigrationScanner.Kind.TELEPORT_POSITION,
                PacketMigrationScanner.scan(baseline, target, List.of(packet)).getFirst().kind());
        final Path invalid = jar(Map.of(owner, snapshotPacket(owner, after,
                List.of("INT", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT"), true)));
        assertThrows(IllegalStateException.class, () -> PacketMigrationScanner.scan(baseline, invalid, List.of(packet)));
    }

    @Test
    void migratesParticleAxesWithDerivedDefaultAndRejectsChangedCountCodec() throws Exception {
        final String owner = "net/minecraft/network/protocol/game/ClientboundLevelParticlesPacket";
        final String particle = "particle:Lnet/minecraft/core/particles/ParticleOptions;";
        final String mode = owner + "$RandomizationType";
        final List<String> before = List.of(particle, "overrideLimiter:Z", "alwaysShow:Z", "x:D", "y:D", "z:D",
                "xDist:F", "yDist:F", "zDist:F", "maxSpeed:F", "count:I");
        final List<String> after = List.of(particle, "overrideLimiter:Z", "alwaysShow:Z", "x:D", "y:D", "z:D",
                "xDist:F", "yDist:F", "zDist:F", "xMaxSpeed:F", "yMaxSpeed:F", "zMaxSpeed:F", "count:I",
                "randomizationType:L" + mode + ";");
        final Path baseline = jar(Map.of(owner, snapshotPacket(owner, before,
                List.of("Boolean", "Boolean", "Double", "Double", "Double", "Float", "Float", "Float", "Float", "Int"), false)));
        final List<String> codecs = new java.util.ArrayList<>(List.of("net/minecraft/core/particles/ParticleTypes#STREAM_CODEC",
                "BOOL", "BOOL", "DOUBLE", "DOUBLE", "DOUBLE", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT", "FLOAT",
                "VAR_INT", mode + "#STREAM_CODEC"));
        final var packet = new PacketUpdater.RetainedPacket("ParticlePacket", "ParticlePacket.SERIALIZER", owner, owner);
        final byte[] enumBytes = binaryEnumClass(mode, "DEFAULT", 7, "ALTERNATIVE", 3);
        final Path target = jar(Map.of(owner, snapshotPacket(owner, after, codecs, true), mode, enumBytes));
        final var migration = PacketMigrationScanner.scan(baseline, target, List.of(packet)).getFirst();
        assertEquals(PacketMigrationScanner.Kind.PARTICLE_AXES, migration.kind());
        assertEquals(7, migration.discriminator());
        codecs.set(12, "INT");
        final Path invalid = jar(Map.of(owner, snapshotPacket(owner, after, codecs, true), mode, enumBytes));
        assertThrows(IllegalStateException.class, () -> PacketMigrationScanner.scan(baseline, invalid, List.of(packet)));
    }

    private static byte[] snapshotPacket(String owner, List<String> fields, List<String> codecs, boolean composite) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        for (String field : fields) {
            final String[] parts = field.split(":", 2);
            writer.visitRecordComponent(parts[0], parts[1], null).visitEnd();
        }
        final var method = writer.visitMethod(Opcodes.ACC_STATIC, composite ? "<clinit>" : "legacyCodec", "()V", null, null);
        method.visitCode();
        for (int i = 0; i < codecs.size(); i++) {
            final String codec = codecs.get(i);
            if (composite) {
                final String[] reference = codec.contains("#") ? codec.split("#")
                        : new String[]{"net/minecraft/network/codec/ByteBufCodecs", codec};
                method.visitFieldInsn(Opcodes.GETSTATIC, reference[0], reference[1], STREAM_CODEC);
                final String[] field = fields.get(i).split(":", 2);
                method.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                        new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                        new Handle(Opcodes.H_INVOKEVIRTUAL, owner, field[0], "()" + field[1], false));
            } else {
                for (String prefix : List.of("read", "write")) {
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                            prefix + codec, "()V", false);
                }
            }
        }
        if (composite) method.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(32, 0);
        method.visitEnd();
        if (composite && fields.size() == 14) {
            final var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDFFFFI)V", null, null);
            constructor.visitCode();
            final int[] opcodes = {25, 25, 21, 21, 24, 24, 24, 23, 23, 23, 23, 23, 23, 21};
            final int[] slots = {0, 1, 2, 3, 4, 6, 8, 10, 11, 12, 13, 13, 13, 14};
            for (int i = 0; i < slots.length; i++) constructor.visitVarInsn(opcodes[i], slots[i]);
            constructor.visitFieldInsn(Opcodes.GETSTATIC, owner + "$RandomizationType", "DEFAULT", "L" + owner + "$RandomizationType;");
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>",
                    "(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDFFFFFFIL" + owner + "$RandomizationType;)V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(24, 15);
            constructor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void acceptsDirectEnumToOptionalOfSameEnumWithBoundOptionalVarIntCodec() throws Exception {
        final Path baseline = jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                enumClass(CodecFlow.NONE, false));
        final Path target = jar(record(List.of(optionalComponent("mode")), true),
                enumClass(CodecFlow.VALID, false));

        final List<PacketMigrationScanner.Migration> migrations = scan(baseline, target);

        assertEquals(1, migrations.size());
        assertEquals(PacketMigrationScanner.Kind.OPTIONAL_ENUM, migrations.getFirst().kind());
        assertEquals(List.of(0), migrations.getFirst().path());
        assertEquals(Map.of("ALPHA", 11, "BETA", 3), migrations.getFirst().ids());
        assertEquals(ENUM, migrations.getFirst().targetEnum());
    }

    @Test
    void rejectsRetainedRecordCountDescriptorAndSignatureChanges() throws Exception {
        final Path baseline = jar(record(List.of(
                component("mode", "L" + ENUM + ";", null),
                component("label", "Ljava/lang/String;", null)), false), enumClass(CodecFlow.NONE, false));

        assertThrows(IllegalStateException.class, () -> scan(baseline,
                jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                        enumClass(CodecFlow.NONE, false))));
        assertThrows(IllegalStateException.class, () -> scan(baseline,
                jar(record(List.of(component("mode", "Ljava/lang/String;", null),
                        component("label", "Ljava/lang/String;", null)), false), enumClass(CodecFlow.NONE, false))));
        assertThrows(IllegalStateException.class, () -> scan(baseline,
                jar(record(List.of(component("mode", "L" + ENUM + ";", "L" + ENUM + ";"),
                        component("label", "Ljava/lang/String;", null)), false), enumClass(CodecFlow.NONE, false))));
    }

    @Test
    void rejectsOptionalOfDifferentEnum() throws Exception {
        final String otherEnum = "synthetic/wire/OtherMode";
        final Path baseline = jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                enumClass(CodecFlow.NONE, false));
        final Path target = jar(Map.of(
                RECORD, recordClass(List.of(new Component("mode", "Ljava/util/Optional;",
                        "Ljava/util/Optional<L" + otherEnum + ";>;")), false),
                ENUM, enumClass(CodecFlow.NONE, false),
                otherEnum, enumClass(otherEnum, CodecFlow.VALID, false)));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
    }

    @Test
    void rejectsCodecThatIsNotDerivedFromOptionalVarInt() throws Exception {
        final Path baseline = jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                enumClass(CodecFlow.NONE, false));
        final Path target = jar(record(List.of(optionalComponent("mode")), true),
                enumClass(CodecFlow.DECOY, false));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
    }

    @Test
    void rejectsTargetComponentThatDoesNotReferenceEnumCodec() throws Exception {
        final Path baseline = jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                enumClass(CodecFlow.NONE, false));
        final Path target = jar(record(List.of(optionalComponent("mode")), false),
                enumClass(CodecFlow.VALID, false));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
    }

    @Test
    void rejectsComputedEnumIds() throws Exception {
        final Path baseline = jar(record(List.of(component("mode", "L" + ENUM + ";", null)), false),
                enumClass(CodecFlow.NONE, false));
        final Path target = jar(record(List.of(optionalComponent("mode")), true),
                enumClass(CodecFlow.VALID, true));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
    }

    @Test
    void detectsReorderedBooleanEnumAndDerivesSemanticIds() throws Exception {
        final String side = "synthetic/wire/Direction";
        final List<Component> baselineComponents = List.of(
                component("anchor", "Lsynthetic/wire/Anchor;", null),
                component("lines", "[Ljava/lang/String;", null),
                component("isFrontText", "Z", null));
        final List<Component> targetComponents = List.of(
                baselineComponents.getFirst(),
                component("lines", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;"),
                component("side", "L" + side + ";", null));
        final Path baseline = jar(Map.of(RECORD, legacyBooleanStringRecord(baselineComponents, 4)));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, reorderedTargetRecord(targetComponents, side, 4));
        targetClasses.put(side, binaryEnumClass(side, "FRONT", 0, "BACK", 1));

        final PacketMigrationScanner.Migration migration = scan(baseline, jar(targetClasses)).getFirst();

        assertEquals(PacketMigrationScanner.Kind.REORDERED_BOOLEAN_ENUM, migration.kind());
        assertEquals(4, migration.fixedSize());
        assertEquals(1, migration.falseId());
        assertEquals(0, migration.trueId());
    }

    @Test
    void rejectsReorderedBooleanEnumWithoutProvablePolarity() throws Exception {
        final String choice = "synthetic/wire/Choice";
        final List<Component> baselineComponents = List.of(
                component("anchor", "Lsynthetic/wire/Anchor;", null),
                component("lines", "[Ljava/lang/String;", null), component("selected", "Z", null));
        final List<Component> targetComponents = List.of(baselineComponents.getFirst(),
                component("lines", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;"),
                component("choice", "L" + choice + ";", null));
        final Path baseline = jar(Map.of(RECORD, legacyBooleanStringRecord(baselineComponents, 2)));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, reorderedTargetRecord(targetComponents, choice, 2));
        targetClasses.put(choice, binaryEnumClass(choice, "ALPHA", 0, "BETA", 1));

        assertThrows(IllegalStateException.class, () -> scan(baseline, jar(targetClasses)));
    }

    @Test
    void detectsLinearPositionPathAndDiscriminator() throws Exception {
        final String nested = "synthetic/wire/LegacyPath";
        final String union = "synthetic/wire/PathUnion";
        final String variant = "synthetic/wire/LinearPath";
        final String kind = "synthetic/wire/PathKind";
        final String point = "synthetic/wire/Point";
        final List<Component> before = List.of(component("entity", "I", null),
                component("path", "L" + nested + ";", null), component("active", "Z", null));
        final List<Component> after = List.of(component("entity", "I", null),
                component("path", "L" + union + ";", null), component("speed", "F", null),
                component("scale", "F", null), component("active", "Z", null));
        final Map<String, byte[]> baselineClasses = new LinkedHashMap<>();
        baselineClasses.put(RECORD, compositeReferenceRecord(before, nested, 1));
        baselineClasses.put(nested, nestedPathClass(nested, point));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, recordClass(after, false));
        targetClasses.put(union, dispatchUnionClass(union, kind));
        targetClasses.put(kind, dispatchEnumClass(kind, variant, 7));
        targetClasses.put(variant, recordClass(variant,
                List.of(component("point", "L" + point + ";", null)), false));

        final PacketMigrationScanner.Migration migration = scan(jar(baselineClasses), jar(targetClasses)).getFirst();

        assertEquals(PacketMigrationScanner.Kind.LINEAR_POSITION_PATH, migration.kind());
        assertEquals(7, migration.discriminator());
    }

    @Test
    void detectsAppendedBooleanAndDerivedDefault() throws Exception {
        final String factory = "synthetic/wire/PacketFactory";
        final List<Component> before = List.of(component("value", "I", null));
        final List<Component> after = List.of(component("value", "I", null), component("enabled", "Z", null));
        final Map<String, byte[]> baselineClasses = new LinkedHashMap<>();
        baselineClasses.put(RECORD, codecRecord(RECORD, before, List.of("VAR_INT"), List.of()));
        baselineClasses.put(factory, constructorCaller(factory, "(I)V", false));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, codecRecord(RECORD, after, List.of("VAR_INT", "BOOL"), List.of()));
        targetClasses.put(factory, constructorCaller(factory, "(IZ)V", true));

        final PacketMigrationScanner.Migration migration = scan(jar(baselineClasses), jar(targetClasses)).getFirst();

        assertEquals(PacketMigrationScanner.Kind.APPENDED_BOOLEAN, migration.kind());
        assertEquals(true, migration.defaultValue());
    }

    @Test
    void rejectsUnknownCodecSegmentsInsteadOfDroppingThem() throws Exception {
        final List<Component> components = List.of(component("value", "I", null));
        final Path baseline = jar(Map.of(RECORD, codecRecord(RECORD, components,
                List.of("VAR_INT"), List.of("synthetic/codec/Unknown"))));
        final Path target = jar(Map.of(RECORD, codecRecord(RECORD, components, List.of("VAR_INT"), List.of())));
        final Path unknownOnly = jar(Map.of(RECORD, codecRecord(RECORD, components,
                List.of(), List.of("synthetic/codec/Unknown"))));
        final Path explicitlyEmpty = jar(Map.of(RECORD, codecRecord(RECORD, components, List.of(), List.of())));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
        assertThrows(IllegalStateException.class, () -> scan(unknownOnly, explicitlyEmpty));
    }

    @Test
    void rejectsSameJavaTypePrimitiveWireChange() throws Exception {
        final List<Component> components = List.of(component("value", "I", null));
        final Path baseline = jar(Map.of(RECORD,
                codecRecord(RECORD, components, List.of("VAR_INT"), List.of())));
        final Path target = jar(Map.of(RECORD,
                codecRecord(RECORD, components, List.of("INT"), List.of())));

        assertThrows(IllegalStateException.class, () -> scan(baseline, target));
    }

    @Test
    void acceptsIncompleteManualToDeclarativeRefactorWithUnchangedComponents() throws Exception {
        final List<Component> components = List.of(component("value", "I", null));
        final Path baseline = jar(Map.of(RECORD, recordClass(components, false)));
        final Path target = jar(Map.of(RECORD,
                codecRecord(RECORD, components, List.of("VAR_INT"), List.of())));

        assertEquals(List.of(), scan(baseline, target));
    }

    @Test
    void ordersLegacyFieldsByTheirCanonicalConstructor() throws Exception {
        final List<Component> components = List.of(component("first", "Ljava/lang/String;", null),
                component("second", "I", null));
        final Path baseline = jar(Map.of(RECORD, legacyFieldOrderClass()));
        final Path target = jar(Map.of(RECORD, recordClass(components, false)));

        assertEquals(List.of(), scan(baseline, target));
    }

    @Test
    void detectsNestedFloatPairMovedToCollectionWrapper() throws Exception {
        final String holder = "synthetic/wire/Holder";
        final String wrapper = "synthetic/wire/PositionedHolder";
        final String display = "synthetic/wire/Display";
        final List<Component> rootBefore = List.of(component("values", "Ljava/util/List;",
                "Ljava/util/List<L" + holder + ";>;"));
        final List<Component> rootAfter = List.of(component("values", "Ljava/util/List;",
                "Ljava/util/List<L" + wrapper + ";>;"));
        final List<Component> holderComponents = List.of(component("display", "L" + display + ";", null));
        final List<Component> wrapperComponents = List.of(component("value", "L" + holder + ";", null),
                component("x", "F", null), component("y", "F", null));
        final Map<String, byte[]> baseline = new LinkedHashMap<>();
        baseline.put(RECORD, recordClass(rootBefore, false));
        baseline.put(holder, compositeCodecRecord(holder, holderComponents));
        baseline.put(display, manualPositionClass(display, true));
        final Map<String, byte[]> target = new LinkedHashMap<>();
        target.put(RECORD, recordClass(rootAfter, false));
        target.put(holder, compositeCodecRecord(holder, holderComponents));
        target.put(wrapper, compositeCodecRecord(wrapper, wrapperComponents));
        target.put(display, manualPositionClass(display, false));

        final PacketMigrationScanner.Migration migration = scan(jar(baseline), jar(target)).getFirst();

        assertEquals(PacketMigrationScanner.Kind.MOVED_NESTED_FLOATS, migration.kind());
        assertEquals(List.of(0), migration.path());
    }

    @Test
    void acceptsBooleanToBinaryEnumOnlyForProvenEquivalentPolarity() throws Exception {
        final String side = "synthetic/wire/Direction";
        final List<Component> baselineComponents = List.of(component("anchor", "Lsynthetic/wire/Anchor;", null),
                component("isFrontText", "Z", null));
        final List<Component> targetComponents = List.of(baselineComponents.getFirst(),
                component("slot", "L" + side + ";", null));
        final Path baseline = jar(Map.of(RECORD, recordClass(baselineComponents, false)));
        final Map<String, byte[]> equivalent = new LinkedHashMap<>();
        equivalent.put(RECORD, compositeReferenceRecord(targetComponents, side, 1));
        equivalent.put(side, binaryEnumClass(side, "BACK", 0, "FRONT", 1));
        final Map<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put(RECORD, compositeReferenceRecord(targetComponents, side, 1));
        reversed.put(side, binaryEnumClass(side, "BACK", 1, "FRONT", 0));

        assertEquals(List.of(), scan(baseline, jar(equivalent)));
        assertThrows(IllegalStateException.class, () -> scan(baseline, jar(reversed)));
    }

    @Test
    void detectsAppendedBooleanWithSharedUnresolvedCodecSegment() throws Exception {
        final String factory = "synthetic/wire/PacketFactory";
        final List<Component> before = List.of(component("value", "I", null));
        final List<Component> after = List.of(component("value", "I", null), component("enabled", "Z", null));
        final CodecField varInt = new CodecField("net/minecraft/network/codec/ByteBufCodecs", "VAR_INT");
        final CodecField unresolved = new CodecField("synthetic/codec/Wrapper", "STREAM_CODEC");
        final CodecField bool = new CodecField("net/minecraft/network/codec/ByteBufCodecs", "BOOL");
        final Map<String, byte[]> baselineClasses = new LinkedHashMap<>();
        baselineClasses.put(RECORD, codecRecordFields(RECORD, before, List.of(varInt, unresolved)));
        baselineClasses.put(factory, constructorCaller(factory, "(I)V", false));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, codecRecordFields(RECORD, after, List.of(varInt, unresolved, bool)));
        targetClasses.put(factory, constructorCaller(factory, "(IZ)V", true));

        final PacketMigrationScanner.Migration migration = scan(jar(baselineClasses), jar(targetClasses)).getFirst();

        assertEquals(PacketMigrationScanner.Kind.APPENDED_BOOLEAN, migration.kind());
        assertEquals(true, migration.defaultValue());
    }

    @Test
    void detectsNestedManualBitSetLeavesChangingToByteArrayCodecs() throws Exception {
        final String data = "synthetic/wire/SectionData";
        final List<Component> packetComponents = List.of(component("x", "I", null), component("z", "I", null),
                component("data", "L" + data + ";", null));
        final List<Component> dataComponents = List.of(
                component("firstMask", "Ljava/util/BitSet;", null),
                component("secondMask", "Ljava/util/BitSet;", null),
                component("emptyFirstMask", "Ljava/util/BitSet;", null),
                component("emptySecondMask", "Ljava/util/BitSet;", null),
                component("firstUpdates", "Ljava/util/List;", "Ljava/util/List<[B>;"),
                component("secondUpdates", "Ljava/util/List;", "Ljava/util/List<[B>;"));
        final Map<String, byte[]> baselineClasses = new LinkedHashMap<>();
        baselineClasses.put(RECORD, compositeReferenceRecord(packetComponents, data, 2));
        baselineClasses.put(data, manualBitSetDataClass(data, dataComponents));
        baselineClasses.put("net/minecraft/network/FriendlyByteBuf", friendlyByteBufClass(BitSetBacking.LONG_ARRAY));
        final Map<String, byte[]> targetClasses = new LinkedHashMap<>();
        targetClasses.put(RECORD, compositeReferenceRecord(packetComponents, data, 2));
        targetClasses.put(data, bitSetCodecRecord(data, dataComponents));
        targetClasses.putAll(byteBufCodecsClasses(BitSetBacking.BYTE_ARRAY));

        final List<PacketMigrationScanner.Migration> migrations = scan(jar(baselineClasses), jar(targetClasses));

        assertEquals(4, migrations.size());
        assertEquals(List.of(List.of(2, 0), List.of(2, 1), List.of(2, 2), List.of(2, 3)),
                migrations.stream().map(PacketMigrationScanner.Migration::path).toList());
        assertEquals(List.of(PacketMigrationScanner.Kind.BYTE_ARRAY_BIT_SET),
                migrations.stream().map(PacketMigrationScanner.Migration::kind).distinct().toList());
    }

    @Test
    void rejectsBitSetFieldWhoseNamedCodecUsesDifferentStorage() throws Exception {
        final Map<String, byte[]> baselineClasses = bitSetMigrationClasses(true, BitSetBacking.LONG_ARRAY);
        final Map<String, byte[]> targetClasses = bitSetMigrationClasses(false, BitSetBacking.LONG_ARRAY);

        assertThrows(IllegalStateException.class, () -> scan(jar(baselineClasses), jar(targetClasses)));
    }

    @Test
    void rejectsNamedFriendlyBitSetMethodsWhoseBodiesUseDifferentStorage() throws Exception {
        final Map<String, byte[]> baselineClasses = bitSetMigrationClasses(true, BitSetBacking.BYTE_ARRAY);
        final Map<String, byte[]> targetClasses = bitSetMigrationClasses(false, BitSetBacking.BYTE_ARRAY);

        assertThrows(IllegalStateException.class, () -> scan(jar(baselineClasses), jar(targetClasses)));
    }

    @Test
    void rejectsOpaqueNamedBitSetImplementations() throws Exception {
        final Map<String, byte[]> baselineClasses = bitSetMigrationClasses(true, BitSetBacking.OPAQUE);
        final Map<String, byte[]> targetClasses = bitSetMigrationClasses(false, BitSetBacking.OPAQUE);

        assertThrows(IllegalStateException.class, () -> scan(jar(baselineClasses), jar(targetClasses)));
    }

    private static List<PacketMigrationScanner.Migration> scan(Path baseline, Path target) throws Exception {
        return PacketMigrationScanner.scan(baseline, target, List.of(
                new PacketUpdater.RetainedPacket("synthetic.Packet", "CODEC", RECORD, RECORD)));
    }

    private static Path jar(Map<String, byte[]> classes) throws Exception {
        final Path jar = Files.createTempFile("nightstorm-migration-scanner", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) addClass(output, entry.getValue());
        }
        return jar;
    }

    private static Path jar(Map<String, byte[]> record, byte[] enumClass) throws Exception {
        final Map<String, byte[]> classes = new LinkedHashMap<>(record);
        classes.put(ENUM, enumClass);
        return jar(classes);
    }

    private static Map<String, byte[]> record(List<Component> components, boolean referenceCodec) {
        return Map.of(RECORD, recordClass(components, referenceCodec));
    }

    private static byte[] recordClass(List<Component> components, boolean referenceCodec) {
        return recordClass(RECORD, components, referenceCodec);
    }

    private static byte[] recordClass(String owner, List<Component> components, boolean referenceCodec) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        if (referenceCodec) {
            final Component component = components.getFirst();
            final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitFieldInsn(Opcodes.GETSTATIC, ENUM, "OPTIONAL_CODEC", STREAM_CODEC);
            clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                    new Handle(Opcodes.H_INVOKEVIRTUAL, owner, component.name(), "()" + component.descriptor(), false));
            clinit.visitInsn(Opcodes.POP);
            clinit.visitInsn(Opcodes.POP);
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(2, 0);
            clinit.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyFieldOrderClass() {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, RECORD, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "second", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "first", "Ljava/lang/String;", null, null)
                .visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;I)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, RECORD, "first", "Ljava/lang/String;");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, RECORD, "second", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 3);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] compositeCodecRecord(String owner, List<Component> components) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (Component component : components) {
            if (component.descriptor().equals("F")) {
                clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                        "FLOAT", STREAM_CODEC);
            } else {
                clinit.visitFieldInsn(Opcodes.GETSTATIC,
                        org.objectweb.asm.Type.getType(component.descriptor()).getInternalName(),
                        "STREAM_CODEC", STREAM_CODEC);
            }
            clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                    new Handle(Opcodes.H_INVOKEVIRTUAL, owner, component.name(),
                            "()" + component.descriptor(), false));
        }
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec",
                "composite", "()" + STREAM_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(components.size() * 2 + 1, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] manualPositionClass(String owner, boolean positioned) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null,
                "java/lang/Object", null);
        if (positioned) {
            writer.visitField(Opcodes.ACC_PRIVATE, "x", "F", null, null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE, "y", "F", null, null).visitEnd();
        }
        final MethodVisitor write = writer.visitMethod(Opcodes.ACC_PRIVATE, "write",
                "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        write.visitCode();
        if (positioned) {
            for (String name : List.of("x", "y")) {
                write.visitVarInsn(Opcodes.ALOAD, 1);
                write.visitVarInsn(Opcodes.ALOAD, 0);
                write.visitFieldInsn(Opcodes.GETFIELD, owner, name, "F");
                write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                        "writeFloat", "(F)Lnet/minecraft/network/FriendlyByteBuf;", false);
                write.visitInsn(Opcodes.POP);
            }
        }
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(2, 2);
        write.visitEnd();
        final MethodVisitor read = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "read",
                "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        read.visitCode();
        if (positioned) {
            for (int index = 0; index < 2; index++) {
                read.visitVarInsn(Opcodes.ALOAD, 0);
                read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                        "readFloat", "()F", false);
                read.visitInsn(Opcodes.POP);
            }
        }
        read.visitInsn(Opcodes.RETURN);
        read.visitMaxs(1, 1);
        read.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] codecRecord(String owner, List<Component> components, List<String> primitives,
                                      List<String> unknownOwners) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        primitives.forEach(name -> clinit.visitFieldInsn(Opcodes.GETSTATIC,
                "net/minecraft/network/codec/ByteBufCodecs", name, STREAM_CODEC));
        unknownOwners.forEach(unknown -> clinit.visitFieldInsn(Opcodes.GETSTATIC, unknown, "STREAM_CODEC", STREAM_CODEC));
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(Math.max(1, primitives.size() + unknownOwners.size()), 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] codecRecordFields(String owner, List<Component> components, List<CodecField> fields) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        fields.forEach(field -> clinit.visitFieldInsn(Opcodes.GETSTATIC, field.owner(), field.name(), STREAM_CODEC));
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(fields.size(), 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyBooleanStringRecord(List<Component> components, int size) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                RECORD, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        final MethodVisitor codec = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "legacyCodec", "()V", null, null);
        codec.visitCode();
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                "readBoolean", "()Z", false);
        codec.visitInsn(Opcodes.POP);
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitInsn(Opcodes.ICONST_0);
        codec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                "writeBoolean", "(Z)V", false);
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                "readUtf", "()Ljava/lang/String;", false);
        codec.visitInsn(Opcodes.POP);
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitInsn(Opcodes.ACONST_NULL);
        codec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                "writeUtf", "(Ljava/lang/String;)V", false);
        codec.visitIntInsn(Opcodes.BIPUSH, size);
        codec.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        codec.visitInsn(Opcodes.POP);
        codec.visitInsn(Opcodes.RETURN);
        codec.visitMaxs(2, 0);
        codec.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] reorderedTargetRecord(List<Component> components, String enumOwner, int size) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                RECORD, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        final Component enumComponent = components.getLast();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, enumOwner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                new Handle(Opcodes.H_INVOKEVIRTUAL, RECORD, enumComponent.name(),
                        "()" + enumComponent.descriptor(), false));
        clinit.visitIntInsn(Opcodes.BIPUSH, size);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "stringUtf8", "()" + STREAM_CODEC, false);
        clinit.visitIntInsn(Opcodes.BIPUSH, size);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "fixedSizeList", "(I)" + STREAM_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, RECORD, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] binaryEnumClass(String owner, String firstName, int firstId,
                                          String secondName, int secondId) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                owner, "Ljava/lang/Enum<L" + owner + ";>;", "java/lang/Enum", null);
        for (String name : List.of(firstName, secondName)) writer.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                name, "L" + owner + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "wireId", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
                "(Ljava/lang/String;I)V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "wireId", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 4);
        constructor.visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        writeConstant(clinit, owner, firstName, 0, firstId, false);
        writeConstant(clinit, owner, secondName, 1, secondId, false);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "VAR_INT", STREAM_CODEC);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "idMapper", "()" + STREAM_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(6, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] constructorCaller(String owner, String constructorDescriptor, boolean booleanArgument) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, RECORD);
        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ICONST_0);
        if (constructorDescriptor.equals("(IZ)V")) {
            method.visitInsn(booleanArgument ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        }
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, RECORD, "<init>", constructorDescriptor, false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(4, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] compositeReferenceRecord(List<Component> components, String nestedOwner, int componentIndex) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                RECORD, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        final Component component = components.get(componentIndex);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, nestedOwner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                new Handle(Opcodes.H_INVOKEVIRTUAL, RECORD, component.name(),
                        "()" + component.descriptor(), false));
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] manualBitSetDataClass(String owner, List<Component> components) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        components.forEach(component -> writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                component.name(), component.descriptor(), component.signature(), null).visitEnd());
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        final int bitSets = (int) components.stream()
                .takeWhile(component -> component.descriptor().equals("Ljava/util/BitSet;"))
                .count();
        for (int index = 0; index < bitSets; index++) {
            final Component component = components.get(index);
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitVarInsn(Opcodes.ALOAD, 1);
            constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                    "readBitSet", "()Ljava/util/BitSet;", false);
            constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, component.name(), component.descriptor());
        }
        for (int index = bitSets; index < components.size(); index++) {
            final Component component = components.get(index);
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitInsn(Opcodes.ACONST_NULL);
            constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, component.name(), component.descriptor());
        }
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        final MethodVisitor write = writer.visitMethod(Opcodes.ACC_PUBLIC, "write",
                "(Lnet/minecraft/network/FriendlyByteBuf;)V", null, null);
        write.visitCode();
        for (int index = 0; index < bitSets; index++) {
            final Component component = components.get(index);
            write.visitVarInsn(Opcodes.ALOAD, 1);
            write.visitVarInsn(Opcodes.ALOAD, 0);
            write.visitFieldInsn(Opcodes.GETFIELD, owner, component.name(), component.descriptor());
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/network/FriendlyByteBuf",
                    "writeBitSet", "(Ljava/util/BitSet;)V", false);
        }
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(2, 2);
        write.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] bitSetCodecRecord(String owner, List<Component> components) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (int index = 0; index < components.size(); index++) {
            final Component component = components.get(index);
            clinit.visitFieldInsn(Opcodes.GETSTATIC,
                    index < 4 ? "net/minecraft/network/codec/ByteBufCodecs" : "synthetic/codec/DataLayer",
                    index < 4 ? "BIT_SET" : "STREAM_CODEC", STREAM_CODEC);
            clinit.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;",
                    new Handle(Opcodes.H_INVOKESTATIC, "synthetic/bootstrap/Factory", "bootstrap", "()V", false),
                    new Handle(Opcodes.H_INVOKEVIRTUAL, owner, component.name(),
                            "()" + component.descriptor(), false));
        }
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec",
                "composite", "()" + STREAM_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(12, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, byte[]> bitSetMigrationClasses(boolean baseline, BitSetBacking backing) {
        final String data = "synthetic/wire/BitSetData";
        final List<Component> packetComponents = List.of(component("data", "L" + data + ";", null));
        final List<Component> dataComponents = List.of(component("mask", "Ljava/util/BitSet;", null));
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(RECORD, compositeReferenceRecord(packetComponents, data, 0));
        if (baseline) {
            classes.put(data, manualBitSetDataClass(data, dataComponents));
            classes.put("net/minecraft/network/FriendlyByteBuf", friendlyByteBufClass(backing));
        } else {
            classes.put(data, bitSetCodecRecord(data, dataComponents));
            classes.putAll(byteBufCodecsClasses(backing));
        }
        return classes;
    }

    private static byte[] friendlyByteBufClass(BitSetBacking backing) {
        final String owner = "net/minecraft/network/FriendlyByteBuf";
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        final MethodVisitor read = writer.visitMethod(Opcodes.ACC_PUBLIC, "readBitSet",
                "()Ljava/util/BitSet;", null, null);
        read.visitCode();
        if (backing == BitSetBacking.LONG_ARRAY) {
            read.visitVarInsn(Opcodes.ALOAD, 0);
            read.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "readLongArray", "()[J", false);
            read.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/BitSet", "valueOf",
                    "([J)Ljava/util/BitSet;", false);
        } else if (backing == BitSetBacking.BYTE_ARRAY) {
            read.visitInsn(Opcodes.ACONST_NULL);
            read.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/BitSet", "valueOf",
                    "([B)Ljava/util/BitSet;", false);
        } else {
            read.visitInsn(Opcodes.ACONST_NULL);
        }
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(1, 1);
        read.visitEnd();
        final MethodVisitor write = writer.visitMethod(Opcodes.ACC_PUBLIC, "writeBitSet",
                "(Ljava/util/BitSet;)V", null, null);
        write.visitCode();
        if (backing == BitSetBacking.LONG_ARRAY) {
            write.visitVarInsn(Opcodes.ALOAD, 0);
            write.visitVarInsn(Opcodes.ALOAD, 1);
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "toLongArray", "()[J", false);
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "writeLongArray",
                    "([J)Lnet/minecraft/network/FriendlyByteBuf;", false);
            write.visitInsn(Opcodes.POP);
        } else if (backing == BitSetBacking.BYTE_ARRAY) {
            write.visitVarInsn(Opcodes.ALOAD, 1);
            write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet", "toByteArray", "()[B", false);
            write.visitInsn(Opcodes.POP);
        }
        write.visitInsn(Opcodes.RETURN);
        write.visitMaxs(2, 2);
        write.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, byte[]> byteBufCodecsClasses(BitSetBacking backing) {
        final String owner = "net/minecraft/network/codec/ByteBufCodecs";
        final String implementation = owner + "$BitSetCodec";
        final var codecs = new ClassWriter(0);
        codecs.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE, owner, null,
                "java/lang/Object", null);
        codecs.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "BIT_SET", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor clinit = codecs.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        if (backing == BitSetBacking.OPAQUE) {
            clinit.visitFieldInsn(Opcodes.GETSTATIC, "synthetic/codec/Opaque", "CODEC", STREAM_CODEC);
        } else {
            clinit.visitTypeInsn(Opcodes.NEW, implementation);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, implementation, "<init>", "()V", false);
        }
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "BIT_SET", STREAM_CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();
        codecs.visitEnd();
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(owner, codecs.toByteArray());
        if (backing != BitSetBacking.OPAQUE) classes.put(implementation, bitSetCodecImplementation(implementation, backing));
        return classes;
    }

    private static byte[] bitSetCodecImplementation(String owner, BitSetBacking backing) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_FINAL, owner, null, "java/lang/Object",
                new String[]{"net/minecraft/network/codec/StreamCodec"});
        final MethodVisitor constructor = writer.visitMethod(0, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        final MethodVisitor decode = writer.visitMethod(Opcodes.ACC_PUBLIC, "decode",
                "(Lio/netty/buffer/ByteBuf;)Ljava/util/BitSet;", null, null);
        decode.visitCode();
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/FriendlyByteBuf",
                backing == BitSetBacking.BYTE_ARRAY ? "readByteArray" : "readLongArray",
                backing == BitSetBacking.BYTE_ARRAY ? "(Lio/netty/buffer/ByteBuf;)[B"
                        : "(Lio/netty/buffer/ByteBuf;)[J", false);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/BitSet", "valueOf",
                backing == BitSetBacking.BYTE_ARRAY ? "([B)Ljava/util/BitSet;" : "([J)Ljava/util/BitSet;", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(1, 2);
        decode.visitEnd();
        final MethodVisitor encode = writer.visitMethod(Opcodes.ACC_PUBLIC, "encode",
                "(Lio/netty/buffer/ByteBuf;Ljava/util/BitSet;)V", null, null);
        encode.visitCode();
        encode.visitVarInsn(Opcodes.ALOAD, 1);
        encode.visitVarInsn(Opcodes.ALOAD, 2);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/BitSet",
                backing == BitSetBacking.BYTE_ARRAY ? "toByteArray" : "toLongArray",
                backing == BitSetBacking.BYTE_ARRAY ? "()[B" : "()[J", false);
        encode.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/FriendlyByteBuf",
                backing == BitSetBacking.BYTE_ARRAY ? "writeByteArray" : "writeLongArray",
                backing == BitSetBacking.BYTE_ARRAY ? "(Lio/netty/buffer/ByteBuf;[B)V"
                        : "(Lio/netty/buffer/ByteBuf;[J)V", false);
        encode.visitInsn(Opcodes.RETURN);
        encode.visitMaxs(2, 3);
        encode.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] nestedPathClass(String owner, String pointOwner) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_RECORD,
                owner, null, "java/lang/Record", null);
        final List<Component> components = List.of(component("from", "L" + pointOwner + ";", null),
                component("to", "L" + pointOwner + ";", null), component("speed", "F", null),
                component("scale", "F", null));
        components.forEach(component -> writer.visitRecordComponent(
                component.name(), component.descriptor(), component.signature()).visitEnd());
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor codec = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "codec", "()" + STREAM_CODEC, null, null);
        codec.visitCode();
        codec.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec",
                "composite", "()" + STREAM_CODEC, false);
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(1, 0);
        codec.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] dispatchUnionClass(String owner, String enumOwner) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, enumOwner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/StreamCodec",
                "dispatch", "()" + STREAM_CODEC, false);
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(1, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] dispatchEnumClass(String owner, String variantOwner, int discriminator) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                owner, "Ljava/lang/Enum<L" + owner + ";>;", "java/lang/Enum", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                "LINEAR", "L" + owner + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "STREAM_CODEC", STREAM_CODEC, null, null).visitEnd();
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;ILjava/lang/Object;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
                "(Ljava/lang/String;I)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 4);
        constructor.visitEnd();
        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "VAR_INT", STREAM_CODEC);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                "idMapper", "()" + STREAM_CODEC, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitTypeInsn(Opcodes.NEW, owner);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn("LINEAR");
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitIntInsn(Opcodes.BIPUSH, discriminator);
        clinit.visitFieldInsn(Opcodes.GETSTATIC, variantOwner, "STREAM_CODEC", STREAM_CODEC);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>",
                "(Ljava/lang/String;ILjava/lang/Object;)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "LINEAR", "L" + owner + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(6, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] enumClass(CodecFlow codecFlow, boolean computedId) {
        return enumClass(ENUM, codecFlow, computedId);
    }

    private static byte[] enumClass(String owner, CodecFlow codecFlow, boolean computedId) {
        final var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                owner, "Ljava/lang/Enum<L" + owner + ";>;", "java/lang/Enum", null);
        for (String name : List.of("ALPHA", "BETA")) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    name, "L" + owner + ";", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "wireId", "I", null, null).visitEnd();
        if (codecFlow != CodecFlow.NONE) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "OPTIONAL_CODEC",
                    STREAM_CODEC, "Lnet/minecraft/network/codec/StreamCodec<Ljava/lang/Object;Ljava/util/Optional<L"
                            + owner + ";>;>;", null).visitEnd();
        }
        if (codecFlow == CodecFlow.DECOY) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "DECOY", STREAM_CODEC, null, null).visitEnd();
        }
        final var constructor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "wireId", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 4);
        constructor.visitEnd();

        final var clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        writeConstant(clinit, owner, "ALPHA", 0, 11, computedId);
        writeConstant(clinit, owner, "BETA", 1, 3, false);
        if (codecFlow != CodecFlow.NONE) {
            clinit.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/codec/ByteBufCodecs",
                    "OPTIONAL_VAR_INT", STREAM_CODEC);
            if (codecFlow == CodecFlow.DECOY) {
                clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "DECOY", STREAM_CODEC);
                clinit.visitFieldInsn(Opcodes.GETSTATIC, "synthetic/codec/Unrelated", "CODEC", STREAM_CODEC);
            }
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, owner, "OPTIONAL_CODEC", STREAM_CODEC);
        }
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(6, 0);
        clinit.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeConstant(org.objectweb.asm.MethodVisitor method, String owner, String name,
                                      int ordinal, int id, boolean computedId) {
        method.visitTypeInsn(Opcodes.NEW, owner);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(name);
        method.visitIntInsn(Opcodes.BIPUSH, ordinal);
        if (computedId) {
            method.visitIntInsn(Opcodes.BIPUSH, id - 1);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
        } else {
            method.visitIntInsn(Opcodes.BIPUSH, id);
        }
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "(Ljava/lang/String;II)V", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, owner, name, "L" + owner + ";");
    }

    private static void addClass(JarOutputStream output, byte[] bytecode) throws Exception {
        final String name = new ClassReader(bytecode).getClassName();
        output.putNextEntry(new JarEntry(name + ".class"));
        output.write(bytecode);
        output.closeEntry();
    }

    private static Component component(String name, String descriptor, String signature) {
        return new Component(name, descriptor, signature);
    }

    private static Component optionalComponent(String name) {
        return component(name, "Ljava/util/Optional;", "Ljava/util/Optional<L" + ENUM + ";>;");
    }

    private enum CodecFlow {
        NONE,
        VALID,
        DECOY
    }

    private enum BitSetBacking {
        BYTE_ARRAY,
        LONG_ARRAY,
        OPAQUE
    }

    private record Component(String name, String descriptor, String signature) {
    }

    private record CodecField(String owner, String name) {
    }
}
