package net.flowstom.nightstorm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

final class PacketMigrationScanner {
    private static final String OPTIONAL_DESCRIPTOR = "Ljava/util/Optional;";
    private static final String STREAM_CODEC_DESCRIPTOR = "Lnet/minecraft/network/codec/StreamCodec;";
    private static final String CODEC_OWNER = "net/minecraft/network/codec/ByteBufCodecs";
    private static final String OPTIONAL_VAR_INT = "OPTIONAL_VAR_INT";

    private PacketMigrationScanner() {
    }

    static List<Migration> scan(Path baselineJar, Path targetJar, List<PacketUpdater.RetainedPacket> packets)
            throws IOException {
        try (var baseline = new Classes(baselineJar); var target = new Classes(targetJar)) {
            final Map<MigrationKey, Migration> migrations = new LinkedHashMap<>();
            final List<IllegalStateException> failures = new ArrayList<>();
            for (PacketUpdater.RetainedPacket packet : packets) {
                try {
                    compare(baseline, target, packet.baselineCodecOwner(), packet.targetCodecOwner(), List.of(),
                            new HashSet<>(), migration -> {
                                final var key = new MigrationKey(packet.className(), packet.serializer(), migration.kind(),
                                        migration.path());
                                final var previous = migrations.putIfAbsent(key, migration.withPacket(packet));
                                if (previous != null && !previous.equals(migration.withPacket(packet))) {
                                    throw new IllegalStateException("Conflicting packet component migrations for " + key);
                                }
                            });
                } catch (IllegalStateException exception) {
                    failures.add(new IllegalStateException(packet.className() + ": " + exception.getMessage(), exception));
                }
            }
            if (!failures.isEmpty()) {
                final var failure = new IllegalStateException("Unsupported retained packet changes:\n"
                        + failures.stream().map(Throwable::getMessage).collect(java.util.stream.Collectors.joining("\n")));
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
            return List.copyOf(migrations.values());
        }
    }

    private static void compare(Classes baseline, Classes target, String baselineOwner, String targetOwner,
                                List<Integer> path, Set<ClassPair> active,
                                java.util.function.Consumer<Migration> output) {
        final ClassPair pair = new ClassPair(baselineOwner, targetOwner);
        if (!active.add(pair)) return;
        try {
            final ClassNode before = baseline.read(baselineOwner);
            final ClassNode after = target.read(targetOwner);
            final List<RecordComponentNode> beforeComponents = components(before);
            final List<RecordComponentNode> afterComponents = components(after);
            final Optional<List<String>> beforeShape = streamShape(baseline, baselineOwner, new HashSet<>());
            final Optional<List<String>> afterShape = streamShape(target, targetOwner, new HashSet<>());
            if (wireEquivalent(beforeShape, afterShape)) return;
            final boolean[] migrated = {false};
            final java.util.function.Consumer<Migration> accept = migration -> {
                migrated[0] = true;
                output.accept(migration);
            };
            final Optional<Migration> semantic = semanticMigration(baseline, target, before, after,
                    beforeComponents, afterComponents, path);
            if (semantic.isPresent()) {
                accept.accept(semantic.get());
                return;
            }
            if (beforeComponents.size() != afterComponents.size()) {
                throw new IllegalStateException("Retained type " + baselineOwner + " -> " + targetOwner
                        + " component count changed at " + path + " from "
                        + beforeComponents.size() + " to " + afterComponents.size());
            }
            final int nullableTransitions = nullableObjectTransitions(baseline, beforeComponents, afterComponents);
            if (nullableTransitions > 0) requireLegacyNullableEncoding(before, nullableTransitions);
            for (int index = 0; index < beforeComponents.size(); index++) {
                final RecordComponentNode beforeComponent = beforeComponents.get(index);
                final RecordComponentNode afterComponent = afterComponents.get(index);
                final List<Integer> componentPath = append(path, index);
                final String beforeType = objectType(beforeComponent.descriptor);
                final ClassNode beforeClass = beforeType == null ? null : baseline.readIfPresent(beforeType);
                final String afterType = objectType(afterComponent.descriptor);
                if (beforeComponent.descriptor.equals("Z") && afterType != null) {
                    final ClassNode afterClass = target.readIfPresent(afterType);
                    if (afterClass != null && isBinaryEnum(afterClass)
                            && componentReferencesCodec(after, afterComponent, afterType, "STREAM_CODEC")) {
                        final BinaryBooleanIds ids = binaryBooleanIds(afterClass, beforeComponent);
                        if (ids != null && ids.falseId() == 0 && ids.trueId() == 1) continue;
                    }
                }
                if (beforeClass != null && beforeComponent.signature == null
                        && OPTIONAL_DESCRIPTOR.equals(afterComponent.descriptor)) {
                    final String targetEnum = optionalArgument(afterComponent.signature);
                    if (targetEnum == null || !targetEnum.equals(beforeType)) {
                        throw new IllegalStateException("Record component " + componentPath
                                + " did not change to Optional of the same type");
                    }
                    if (!isEnum(beforeClass)) {
                        requireBooleanOptionalComponent(target, after, afterComponent, beforeType, componentPath);
                        continue;
                    }
                    if (!isEnum(target.read(targetEnum))) {
                        throw new IllegalStateException("Record component " + componentPath
                                + " changed from an enum to Optional of a non-enum type");
                    }
                    final ClassNode targetEnumClass = target.read(targetEnum);
                    final String codecField = requireOptionalVarIntCodec(targetEnumClass);
                    requireComponentCodecReference(after, afterComponent, targetEnum, codecField, componentPath);
                    accept.accept(Migration.optionalEnum(componentPath, enumIds(targetEnumClass), targetEnum));
                    continue;
                }
                if (sameComponent(beforeComponent, afterComponent)
                        && byteArrayBitSetMigration(baseline, target, before, after,
                        beforeComponent, afterComponent)) {
                    accept.accept(Migration.byteArrayBitSet(componentPath));
                    continue;
                }
                if (!beforeComponent.descriptor.equals(afterComponent.descriptor)
                        || !Objects.equals(beforeComponent.signature, afterComponent.signature)) {
                    if (equivalentCollectionType(beforeComponent, afterComponent)) continue;
                    throw new IllegalStateException("Retained type " + baselineOwner + " -> " + targetOwner
                            + " component " + componentPath + " changed descriptor or signature from "
                            + beforeComponent.descriptor + '/' + beforeComponent.signature + " to "
                            + afterComponent.descriptor + '/' + afterComponent.signature);
                }
                if (beforeComponent.descriptor.length() == 1) {
                    requireEquivalentComponentShape(baseline, target, before, after, beforeComponent, afterComponent,
                            componentPath);
                }
                if (beforeType != null && afterType != null) {
                    final ClassNode nestedBefore = baseline.readIfPresent(beforeType);
                    final ClassNode nestedAfter = target.readIfPresent(afterType);
                    if (nestedBefore != null && nestedAfter != null
                            && !components(nestedBefore).isEmpty() && !components(nestedAfter).isEmpty()
                            && componentReferencesCompositeCodec(after, afterComponent, afterType, nestedAfter)) {
                        compare(baseline, target, beforeType, afterType, componentPath, active, accept);
                    }
                }
            }
            if (!migrated[0]) {
                if (beforeShape.isPresent() && afterShape.isPresent()
                        && !beforeShape.get().equals(afterShape.get())) {
                    throw new IllegalStateException("Retained type " + baselineOwner + " -> " + targetOwner
                            + " normalized codec shape changed at " + path + " from "
                            + beforeShape.get() + " to " + afterShape.get());
                }
            }
        } finally {
            active.remove(pair);
        }
    }

    private static Optional<Migration> semanticMigration(Classes baseline, Classes target, ClassNode before,
                                                          ClassNode after, List<RecordComponentNode> beforeComponents,
                                                          List<RecordComponentNode> afterComponents,
                                                          List<Integer> path) {
        final Optional<Migration> reordered = reorderedBooleanEnum(target, before, after, beforeComponents,
                afterComponents, path);
        if (reordered.isPresent()) return reordered;
        final Optional<Migration> positionPath = linearPositionPath(baseline, target, before, after,
                beforeComponents, afterComponents, path);
        if (positionPath.isPresent()) return positionPath;
        return appendedPrimitive(baseline, target, before, after, beforeComponents, afterComponents, path);
    }

    private static Optional<Migration> reorderedBooleanEnum(Classes target, ClassNode before, ClassNode after,
                                                             List<RecordComponentNode> beforeComponents,
                                                             List<RecordComponentNode> afterComponents,
                                                             List<Integer> path) {
        if (beforeComponents.size() != 3 || afterComponents.size() != 3
                || objectType(beforeComponents.get(0).descriptor) == null
                || !sameComponent(beforeComponents.get(0), afterComponents.get(0))
                || !isStringContainer(beforeComponents.get(1))
                || !isStringList(afterComponents.get(1))
                || !beforeComponents.get(2).descriptor.equals("Z")) return Optional.empty();
        final String enumOwner = objectType(afterComponents.get(2).descriptor);
        if (enumOwner == null) return Optional.empty();
        final ClassNode enumClass = target.readIfPresent(enumOwner);
        if (enumClass == null || !isBinaryEnum(enumClass)
                || !componentReferencesCodec(after, afterComponents.get(2), enumOwner, "STREAM_CODEC")) {
            return Optional.empty();
        }
        final int fixedSize = fixedStringListSize(after);
        if (fixedSize < 0 || !legacyBooleanStringPayload(before, fixedSize)) return Optional.empty();
        final BinaryBooleanIds ids = binaryBooleanIds(enumClass, beforeComponents.get(2));
        if (ids == null) return Optional.empty();
        return Optional.of(Migration.reorderedBooleanEnum(path, fixedSize, ids.falseId(), ids.trueId()));
    }

    private static Optional<Migration> linearPositionPath(Classes baseline, Classes target, ClassNode before,
                                                           ClassNode after, List<RecordComponentNode> beforeComponents,
                                                           List<RecordComponentNode> afterComponents,
                                                           List<Integer> path) {
        if (beforeComponents.size() != 3 || afterComponents.size() != 5
                || !beforeComponents.get(0).descriptor.equals("I")
                || !afterComponents.get(0).descriptor.equals("I")
                || !beforeComponents.get(2).descriptor.equals("Z")
                || !afterComponents.get(2).descriptor.equals("F")
                || !afterComponents.get(3).descriptor.equals("F")
                || !afterComponents.get(4).descriptor.equals("Z")) return Optional.empty();
        final String nestedOwner = objectType(beforeComponents.get(1).descriptor);
        final String unionOwner = objectType(afterComponents.get(1).descriptor);
        if (nestedOwner == null || unionOwner == null) return Optional.empty();
        final ClassNode nested = baseline.readIfPresent(nestedOwner);
        final ClassNode union = target.readIfPresent(unionOwner);
        if (nested == null || union == null || !componentReferencesCompositeCodec(before,
                beforeComponents.get(1), nestedOwner, nested)) return Optional.empty();
        final List<RecordComponentNode> values = components(nested);
        if (values.size() != 4 || objectType(values.get(0).descriptor) == null
                || !sameComponentType(values.get(0), values.get(1))
                || !values.get(2).descriptor.equals("F") || !values.get(3).descriptor.equals("F")
                || !callsDispatch(union)) return Optional.empty();
        final Integer discriminator = linearVariantDiscriminator(target, union, values.get(0));
        if (discriminator == null) return Optional.empty();
        return Optional.of(Migration.linearPositionPath(path, discriminator));
    }

    private static Optional<Migration> appendedPrimitive(Classes baseline, Classes target, ClassNode before,
                                                          ClassNode after,
                                                          List<RecordComponentNode> beforeComponents,
                                                          List<RecordComponentNode> afterComponents,
                                                          List<Integer> path) {
        if (afterComponents.size() != beforeComponents.size() + 1
                || !afterComponents.getLast().descriptor.equals("Z")) return Optional.empty();
        for (int index = 0; index < beforeComponents.size(); index++) {
            if (!sameComponent(beforeComponents.get(index), afterComponents.get(index))) return Optional.empty();
        }
        final Optional<List<String>> beforeShape = streamShape(baseline, before.name, new HashSet<>());
        final Optional<List<String>> afterShape = streamShape(target, after.name, new HashSet<>());
        if (beforeShape.isEmpty() || afterShape.isEmpty()
                || afterShape.get().size() != beforeShape.get().size() + 1
                || !afterShape.get().subList(0, beforeShape.get().size()).equals(beforeShape.get())
                || !afterShape.get().getLast().equals("BOOLEAN")) return Optional.empty();
        final boolean defaultValue = constructorBooleanDefault(baseline, target, before, after,
                beforeComponents, afterComponents);
        return Optional.of(Migration.appendedBoolean(path, defaultValue));
    }

    private static boolean sameComponent(RecordComponentNode left, RecordComponentNode right) {
        return left.descriptor.equals(right.descriptor) && Objects.equals(left.signature, right.signature);
    }

    private static boolean sameComponentType(RecordComponentNode left, RecordComponentNode right) {
        return left.descriptor.equals(right.descriptor) && Objects.equals(left.signature, right.signature);
    }

    private static boolean isStringContainer(RecordComponentNode component) {
        return component.descriptor.equals("[Ljava/lang/String;") || isStringList(component);
    }

    private static boolean isStringList(RecordComponentNode component) {
        return component.descriptor.equals("Ljava/util/List;")
                && "Ljava/util/List<Ljava/lang/String;>;".equals(component.signature);
    }

    private static int fixedStringListSize(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            boolean stringCodec = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(CODEC_OWNER)
                        && call.name.equals("stringUtf8")) stringCodec = true;
                if (stringCodec && instruction instanceof MethodInsnNode call && call.owner.equals(CODEC_OWNER)
                        && call.name.equals("fixedSizeList")) return previousInteger(instruction);
            }
        }
        return -1;
    }

    private static boolean legacyBooleanStringPayload(ClassNode owner, int size) {
        int booleanReads = 0;
        int booleanWrites = 0;
        int stringReads = 0;
        int stringWrites = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                switch (call.name) {
                    case "readBoolean" -> booleanReads++;
                    case "writeBoolean" -> booleanWrites++;
                    case "readUtf" -> stringReads++;
                    case "writeUtf" -> stringWrites++;
                    default -> {
                    }
                }
            }
        }
        boolean fixedArray = false;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && instruction.getOpcode() == Opcodes.ANEWARRAY
                        && type.desc.equals("java/lang/String")
                        && Integer.valueOf(size).equals(integerConstant(previousReal(instruction)))) fixedArray = true;
            }
        }
        return booleanReads == 1 && booleanWrites == 1 && stringReads == 1 && stringWrites == 1 && fixedArray;
    }

    private static boolean callsDispatch(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/network/codec/StreamCodec")
                        && call.name.equals("dispatch")) return true;
            }
        }
        return false;
    }

    private static Integer linearVariantDiscriminator(Classes target, ClassNode union,
                                                       RecordComponentNode positionComponent) {
        String enumOwner = null;
        for (MethodNode method : union.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)) {
                    final ClassNode candidate = target.readIfPresent(field.owner);
                    if (candidate != null && isEnum(candidate)) enumOwner = field.owner;
                }
            }
        }
        if (enumOwner == null) return null;
        final ClassNode enumClass = target.read(enumOwner);
        if (!usesIdMapper(enumClass)) return null;
        final MethodNode clinit = enumClass.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElse(null);
        if (clinit == null) return null;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(enumOwner) || !call.name.equals("<init>")) continue;
            final AbstractInsnNode codec = previousReal(instruction);
            final AbstractInsnNode ordinal = codec == null ? null : previousReal(codec);
            if (!(codec instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                    || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
            final ClassNode variant = target.readIfPresent(field.owner);
            final List<RecordComponentNode> variantComponents = variant == null ? List.of() : components(variant);
            if (variantComponents.size() != 1 || !sameComponentType(variantComponents.getFirst(), positionComponent)) {
                continue;
            }
            return integerConstant(ordinal);
        }
        return null;
    }

    private static boolean usesIdMapper(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(CODEC_OWNER)
                        && call.name.equals("idMapper")) return true;
            }
        }
        return false;
    }

    private static boolean constructorBooleanDefault(Classes baseline, Classes target, ClassNode before,
                                                     ClassNode after, List<RecordComponentNode> beforeComponents,
                                                     List<RecordComponentNode> afterComponents) {
        final Type[] arguments = afterComponents.stream().map(component -> Type.getType(component.descriptor))
                .toArray(Type[]::new);
        final String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, arguments);
        final Type[] oldArguments = beforeComponents.stream().map(component -> Type.getType(component.descriptor))
                .toArray(Type[]::new);
        final String oldDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, oldArguments);
        final Boolean behavioralDefault = baselineBehaviorDefault(baseline, target, after,
                afterComponents.getLast());
        if (behavioralDefault != null) return behavioralDefault;
        Boolean value = null;
        int calls = 0;
        for (ClassNode candidate : target.all()) {
            final ClassNode baselineCandidate = baseline.readIfPresent(candidate.name);
            if (baselineCandidate == null) continue;
            for (MethodNode method : candidate.methods) {
                final MethodNode baselineMethod = baselineCandidate.methods.stream()
                        .filter(candidateMethod -> candidateMethod.name.equals(method.name)
                                && candidateMethod.desc.equals(method.desc))
                        .findFirst().orElse(null);
                if (baselineMethod == null || !callsConstructor(baselineMethod, before.name, oldDescriptor)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                            || !call.owner.equals(after.name) || !call.name.equals("<init>")
                            || !call.desc.equals(descriptor)) continue;
                    calls++;
                    Integer constant = nullableConditionDefault(instruction);
                    if (constant == null) constant = directIntegerArgument(instruction);
                    if (constant == null || constant < 0 || constant > 1) {
                        throw new IllegalStateException("Unable to prove appended boolean constructor argument in "
                                + candidate.name + '.' + method.name);
                    }
                    final boolean booleanValue = constant == 1;
                    if (value != null && value != booleanValue) {
                        throw new IllegalStateException("Appended boolean constructor argument has conflicting constants");
                    }
                    value = booleanValue;
                }
            }
        }
        if (calls == 0 || value == null) {
            throw new IllegalStateException("Unable to prove a compatibility default for appended boolean");
        }
        return value;
    }

    private static Boolean baselineBehaviorDefault(Classes baseline, Classes target, ClassNode packet,
                                                   RecordComponentNode appended) {
        Boolean result = null;
        for (ClassNode owner : target.all()) {
            final ClassNode oldOwner = baseline.readIfPresent(owner.name);
            if (oldOwner == null) continue;
            for (MethodNode method : owner.methods) {
                final boolean baselineMethodExists = oldOwner.methods.stream().anyMatch(candidate ->
                        candidate.name.equals(method.name) && candidate.desc.equals(method.desc));
                if (!baselineMethodExists) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                            || !call.owner.equals(packet.name) || !call.name.equals(appended.name)
                            || !call.desc.equals("()Z")) continue;
                    final AbstractInsnNode branch = nextReal(instruction);
                    if (!(branch instanceof JumpInsnNode jump)
                            || jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) continue;
                    final boolean value = jump.getOpcode() == Opcodes.IFEQ;
                    if (result != null && result != value) {
                        throw new IllegalStateException("Appended boolean guards behavior with conflicting polarity");
                    }
                    result = value;
                }
            }
        }
        return result;
    }

    private static Integer directIntegerArgument(AbstractInsnNode call) {
        final AbstractInsnNode value = previousReal(call);
        for (AbstractInsnNode instruction = value == null ? null : value.getNext(); instruction != null
                && instruction != call; instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode) return null;
        }
        return integerConstant(value);
    }

    private static Integer nullableConditionDefault(AbstractInsnNode call) {
        int inspected = 0;
        for (AbstractInsnNode instruction = call.getPrevious(); instruction != null && inspected < 24;
             instruction = instruction.getPrevious()) {
            if (instruction.getOpcode() >= 0) inspected++;
            if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNULL) continue;
            final Integer value = integerConstant(nextReal(jump.label));
            if (value != null && (value == 0 || value == 1)) return value;
        }
        return null;
    }

    private static boolean callsConstructor(MethodNode method, String owner, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(owner) && call.name.equals("<init>") && call.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static Integer integerConstant(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode integer) return integer.operand;
        if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Integer integer) return integer;
        if (instruction != null && instruction.getOpcode() >= Opcodes.ICONST_M1
                && instruction.getOpcode() <= Opcodes.ICONST_5) {
            return instruction.getOpcode() - Opcodes.ICONST_0;
        }
        return null;
    }

    private static boolean equivalentCollectionType(RecordComponentNode before, RecordComponentNode after) {
        final Set<String> collections = Set.of("Ljava/util/Collection;", "Ljava/util/List;", "Ljava/util/Set;");
        if (!collections.contains(before.descriptor) || !collections.contains(after.descriptor)
                || before.signature == null || after.signature == null) return false;
        return before.signature.substring(before.signature.indexOf('<'))
                .equals(after.signature.substring(after.signature.indexOf('<')));
    }

    private static boolean componentReferencesCompositeCodec(ClassNode owner, RecordComponentNode component,
                                                              String nestedOwner, ClassNode nestedClass) {
        if (nestedClass.methods.stream().noneMatch(PacketMigrationScanner::callsCompositeCodec)) return false;
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                        || !field.owner.equals(nestedOwner) || !field.name.equals("STREAM_CODEC")
                        || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
                final AbstractInsnNode accessor = nextReal(instruction);
                if (accessor instanceof InvokeDynamicInsnNode dynamic
                        && dynamicReferencesAccessor(dynamic, owner.name, component.name, component.descriptor)) return true;
            }
        }
        return false;
    }

    private static boolean componentReferencesCodec(ClassNode owner, RecordComponentNode component,
                                                    String codecOwner, String codecField) {
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                        || !field.owner.equals(codecOwner) || !field.name.equals(codecField)
                        || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
                final AbstractInsnNode accessor = nextReal(instruction);
                if (accessor instanceof InvokeDynamicInsnNode dynamic
                        && dynamicReferencesAccessor(dynamic, owner.name, component.name, component.descriptor)) return true;
            }
        }
        return false;
    }

    private static void requireEquivalentComponentShape(Classes baseline, Classes target, ClassNode beforeOwner,
                                                        ClassNode afterOwner, RecordComponentNode beforeComponent,
                                                        RecordComponentNode afterComponent, List<Integer> path) {
        final ComponentCodecShape before = componentCodecShape(baseline, beforeOwner, beforeComponent);
        final ComponentCodecShape after = componentCodecShape(target, afterOwner, afterComponent);
        if (!before.referenced() && !after.referenced()) return;
        if (before.shape().isPresent() && after.shape().isPresent()
                && completeShape(before.shape().get()) && completeShape(after.shape().get())
                && !before.shape().get().equals(after.shape().get())) {
            throw new IllegalStateException("Normalized component codec shape changed at " + path + " from "
                    + before.shape().get() + " to " + after.shape().get());
        }
    }

    private static ComponentCodecShape componentCodecShape(Classes classes, ClassNode owner,
                                                            RecordComponentNode component) {
        ComponentCodecShape result = new ComponentCodecShape(false, Optional.empty());
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode dynamic)
                        || !dynamicReferencesAccessor(dynamic, owner.name, component.name, component.descriptor)) {
                    continue;
                }
                final AbstractInsnNode codec = previousReal(instruction);
                final Optional<List<String>> shape;
                if (codec instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)) {
                    final String primitive = primitiveCodec(classes, field.owner, field.name);
                    shape = primitive == null
                            ? streamShape(classes, field.owner, field.name, new HashSet<>())
                            : Optional.of(List.of(primitive));
                } else {
                    shape = Optional.empty();
                }
                final ComponentCodecShape candidate = new ComponentCodecShape(true, shape);
                if (result.referenced() && !result.equals(candidate)) {
                    return new ComponentCodecShape(true, Optional.empty());
                }
                result = candidate;
            }
        }
        final ComponentCodecShape manual = manualComponentShape(classes, owner, component);
        if (manual.referenced()) {
            if (!result.referenced()) return manual;
            if (!result.shape().equals(manual.shape())) return new ComponentCodecShape(true, Optional.empty());
        }
        return result;
    }

    private static boolean byteArrayBitSetMigration(Classes baseline, Classes target, ClassNode beforeOwner,
                                                    ClassNode afterOwner, RecordComponentNode beforeComponent,
                                                    RecordComponentNode afterComponent) {
        if (!beforeComponent.descriptor.equals("Ljava/util/BitSet;")) return false;
        final ComponentCodecShape before = componentCodecShape(baseline, beforeOwner, beforeComponent);
        final ComponentCodecShape after = componentCodecShape(target, afterOwner, afterComponent);
        final boolean candidate = manualBitSetReference(beforeOwner, beforeComponent)
                && componentReferencesCodec(afterOwner, afterComponent, CODEC_OWNER, "BIT_SET");
        final boolean proven = before.shape().filter(List.of("BIT_SET(LONG_ARRAY)")::equals).isPresent()
                && after.shape().filter(List.of("BIT_SET(BYTE_ARRAY)")::equals).isPresent();
        if (candidate && !proven) {
            throw new IllegalStateException("Unable to prove BitSet storage migration for component "
                    + beforeComponent.name);
        }
        return proven;
    }

    private static ComponentCodecShape manualComponentShape(Classes classes, ClassNode owner,
                                                            RecordComponentNode component) {
        boolean read = false;
        boolean write = false;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (!call.owner.equals("net/minecraft/network/FriendlyByteBuf")) continue;
                if (call.name.equals("readBitSet") && call.desc.equals("()Ljava/util/BitSet;")) {
                    final AbstractInsnNode assignment = nextReal(instruction);
                    if (assignment instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                            && field.owner.equals(owner.name) && field.name.equals(component.name)
                            && field.desc.equals(component.descriptor)) read = true;
                } else if (call.name.equals("writeBitSet") && call.desc.equals("(Ljava/util/BitSet;)V")) {
                    final AbstractInsnNode value = previousReal(instruction);
                    if (value instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                            && field.owner.equals(owner.name) && field.name.equals(component.name)
                            && field.desc.equals(component.descriptor)) write = true;
                }
            }
        }
        if (!read || !write) return new ComponentCodecShape(read || write, Optional.empty());
        return new ComponentCodecShape(true, friendlyBitSetIsLongArrayBacked(classes)
                ? Optional.of(List.of("BIT_SET(LONG_ARRAY)")) : Optional.empty());
    }

    private static boolean manualBitSetReference(ClassNode owner, RecordComponentNode component) {
        return manualComponentShapeReferences(owner, component, "readBitSet", "()Ljava/util/BitSet;", Opcodes.PUTFIELD)
                && manualComponentShapeReferences(owner, component, "writeBitSet", "(Ljava/util/BitSet;)V",
                Opcodes.GETFIELD);
    }

    private static boolean manualComponentShapeReferences(ClassNode owner, RecordComponentNode component,
                                                           String methodName, String methodDescriptor,
                                                           int fieldOpcode) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("net/minecraft/network/FriendlyByteBuf")
                        || !call.name.equals(methodName) || !call.desc.equals(methodDescriptor)) continue;
                final AbstractInsnNode fieldInstruction = fieldOpcode == Opcodes.PUTFIELD
                        ? nextReal(instruction) : previousReal(instruction);
                if (fieldInstruction instanceof FieldInsnNode field && field.getOpcode() == fieldOpcode
                        && field.owner.equals(owner.name) && field.name.equals(component.name)
                        && field.desc.equals(component.descriptor)) return true;
            }
        }
        return false;
    }

    private static boolean callsCompositeCodec(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/network/codec/StreamCodec")
                    && call.name.equals("composite")) return true;
        }
        return false;
    }

    private static boolean wireEquivalent(Optional<List<String>> before, Optional<List<String>> after) {
        return before.isPresent() && after.isPresent() && completeShape(before.get())
                && completeShape(after.get()) && before.get().equals(after.get());
    }

    private static boolean completeShape(List<String> shape) {
        return shape.stream().noneMatch(segment -> segment.startsWith("UNKNOWN("));
    }

    private static Optional<List<String>> streamShape(Classes classes, String owner, Set<String> active) {
        return streamShape(classes, owner, "STREAM_CODEC", active);
    }

    private static Optional<List<String>> streamShape(Classes classes, String owner, String fieldName,
                                                       Set<String> active) {
        final String normalized = owner.replace('.', '/');
        final String key = normalized + '#' + fieldName;
        if (!active.add(key)) return Optional.empty();
        try {
            final ClassNode node = classes.readIfPresent(normalized);
            if (node == null) return Optional.empty();
            final MethodNode clinit = node.methods.stream().filter(method -> method.name.equals("<clinit>"))
                    .findFirst().orElse(null);
            if (clinit == null) return fieldName.equals("STREAM_CODEC")
                    ? manualBufferShape(classes, node) : Optional.empty();
            AbstractInsnNode assignment = null;
            for (AbstractInsnNode instruction : clinit.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && field.owner.equals(node.name) && field.name.equals(fieldName)
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)) {
                    assignment = instruction;
                    break;
                }
            }
            if (assignment == null) {
                return fieldName.equals("STREAM_CODEC") ? manualBufferShape(classes, node) : Optional.empty();
            }
            AbstractInsnNode start = assignment;
            while (start.getPrevious() != null) {
                start = start.getPrevious();
                if (start instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) {
                    start = start.getNext();
                    break;
                }
            }
            final List<String> result = new ArrayList<>();
            for (AbstractInsnNode instruction = start; instruction != null && instruction != assignment;
                 instruction = instruction.getNext()) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)) {
                    final String primitive = primitiveCodec(classes, field.owner, field.name);
                    if (primitive != null) result.add(primitive);
                    else {
                        final Optional<List<String>> nested = streamShape(classes, field.owner, field.name, active);
                        if (nested.isEmpty()) result.add(unknownSegment("field", field.owner + '#' + field.name));
                        else result.addAll(nested.get());
                    }
                } else if (instruction instanceof TypeInsnNode type && instruction.getOpcode() == Opcodes.NEW) {
                    final ClassNode codec = classes.readIfPresent(type.desc);
                    if (codec != null && codec.interfaces.stream()
                            .anyMatch(name -> name.equals("net/minecraft/network/codec/StreamCodec"))) {
                        final Optional<List<String>> manual = manualBufferShape(classes, codec);
                        if (manual.isEmpty()) result.add(unknownSegment("codec", type.desc));
                        else result.addAll(manual.get());
                    }
                } else if (instruction instanceof MethodInsnNode call
                        && Type.getReturnType(call.desc).getDescriptor().equals(STREAM_CODEC_DESCRIPTOR)) {
                    if (call.owner.equals("net/minecraft/network/protocol/Packet") && call.name.equals("codec")) {
                        return Optional.empty();
                    }
                    if (call.owner.equals(CODEC_OWNER) && call.name.equals("stringUtf8")) {
                        final Integer size = integerConstant(previousReal(instruction));
                        result.add(size == null ? unknownSegment("call", call.owner + '.' + call.name)
                                : "STRING(" + size + ')');
                    } else if (call.owner.equals(CODEC_OWNER) && call.name.equals("fixedSizeList")) {
                        final Integer size = integerConstant(previousReal(instruction));
                        result.add(size == null ? unknownSegment("call", call.owner + '.' + call.name)
                                : "FIXED_LIST(" + size + ')');
                    } else if (call.owner.equals(CODEC_OWNER)
                            && (call.name.equals("list") || call.name.equals("collection"))) {
                        result.add("LIST");
                    } else if (!knownCodecCombinator(call)) {
                        result.add(unknownSegment("call", call.owner + '.' + call.name + call.desc));
                    }
                }
            }
            return Optional.of(List.copyOf(result));
        } finally {
            active.remove(key);
        }
    }

    private static boolean knownCodecCombinator(MethodInsnNode call) {
        if (call.owner.equals(CODEC_OWNER)) {
            return call.name.equals("idMapper");
        }
        if (!call.owner.equals("net/minecraft/network/codec/StreamCodec")) return false;
        return call.name.equals("composite") || call.name.equals("apply") || call.name.equals("map")
                || call.name.equals("dispatch") || call.name.equals("recursive");
    }

    private static String unknownSegment(String kind, String value) {
        return "UNKNOWN(" + kind + ':' + value + ')';
    }

    private static String primitiveCodec(Classes classes, String owner, String field) {
        if (!owner.equals(CODEC_OWNER)) return null;
        return switch (field) {
            case "BOOL" -> "BOOLEAN";
            case "BYTE" -> "BYTE";
            case "SHORT" -> "SHORT";
            case "UNSIGNED_SHORT" -> "UNSIGNED_SHORT";
            case "INT" -> "INT";
            case "VAR_INT" -> "VAR_INT";
            case "LONG" -> "LONG";
            case "VAR_LONG" -> "VAR_LONG";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE" -> "DOUBLE";
            case "BYTE_ARRAY" -> "BYTE_ARRAY";
            case "LONG_ARRAY" -> "LONG_ARRAY";
            case "BIT_SET" -> byteBufBitSetIsByteArrayBacked(classes) ? "BIT_SET(BYTE_ARRAY)" : null;
            default -> null;
        };
    }

    private static boolean byteBufBitSetIsByteArrayBacked(Classes classes) {
        final ClassNode owner = classes.readIfPresent(CODEC_OWNER);
        if (owner == null) return false;
        final ClassNode implementation = assignedCodecImplementation(classes, owner, "BIT_SET");
        if (implementation == null) return false;
        boolean decode = false;
        boolean encode = false;
        for (MethodNode method : implementation.methods) {
            if (method.name.equals("decode")) {
                decode |= calls(method, "net/minecraft/network/FriendlyByteBuf", "readByteArray",
                        "(Lio/netty/buffer/ByteBuf;)[B")
                        && calls(method, "java/util/BitSet", "valueOf", "([B)Ljava/util/BitSet;");
            } else if (method.name.equals("encode")) {
                encode |= calls(method, "java/util/BitSet", "toByteArray", "()[B")
                        && calls(method, "net/minecraft/network/FriendlyByteBuf", "writeByteArray",
                        "(Lio/netty/buffer/ByteBuf;[B)V");
            }
        }
        return decode && encode;
    }

    private static ClassNode assignedCodecImplementation(Classes classes, ClassNode owner, String fieldName) {
        final MethodNode clinit = owner.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElse(null);
        if (clinit == null) return null;
        for (AbstractInsnNode instruction : clinit.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(owner.name) || !field.name.equals(fieldName)
                    || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
            final AbstractInsnNode constructor = previousReal(instruction);
            if (!(constructor instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.name.equals("<init>") || !call.desc.equals("()V")) return null;
            final AbstractInsnNode duplicate = previousReal(constructor);
            final AbstractInsnNode allocation = duplicate == null ? null : previousReal(duplicate);
            if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP) return null;
            if (!(allocation instanceof TypeInsnNode type) || !type.desc.equals(call.owner)) return null;
            return classes.readIfPresent(type.desc);
        }
        return null;
    }

    private static boolean friendlyBitSetIsLongArrayBacked(Classes classes) {
        final ClassNode buffer = classes.readIfPresent("net/minecraft/network/FriendlyByteBuf");
        if (buffer == null) return false;
        final MethodNode read = method(buffer, "readBitSet", "()Ljava/util/BitSet;");
        final MethodNode write = method(buffer, "writeBitSet", "(Ljava/util/BitSet;)V");
        return read != null && write != null
                && calls(read, buffer.name, "readLongArray", "()[J")
                && calls(read, "java/util/BitSet", "valueOf", "([J)Ljava/util/BitSet;")
                && calls(write, "java/util/BitSet", "toLongArray", "()[J")
                && calls(write, buffer.name, "writeLongArray", "([J)Lnet/minecraft/network/FriendlyByteBuf;");
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElse(null);
    }

    private static boolean calls(MethodNode method, String owner, String name, String descriptor) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static boolean isBinaryEnum(ClassNode node) {
        if (!isEnum(node)) return false;
        try {
            return new HashSet<>(enumIds(node).values()).equals(Set.of(0, 1));
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static BinaryBooleanIds binaryBooleanIds(ClassNode enumClass, RecordComponentNode booleanComponent) {
        final Map<String, Integer> ids = enumIds(enumClass);
        if (ids.size() != 2 || !new HashSet<>(ids.values()).equals(Set.of(0, 1))) return null;
        final Integer explicitFalse = ids.get("FALSE");
        final Integer explicitTrue = ids.get("TRUE");
        if (explicitFalse != null && explicitTrue != null) return new BinaryBooleanIds(explicitFalse, explicitTrue);

        final Set<String> componentMeaning = identifierWords(booleanComponent.name);
        componentMeaning.removeAll(Set.of("is", "has", "text", "value", "mode", "state", "type", "side"));
        if (componentMeaning.isEmpty()) return null;
        String trueConstant = null;
        for (String constant : ids.keySet()) {
            final Set<String> meaning = identifierWords(constant);
            meaning.removeAll(Set.of("text", "value", "mode", "state", "type", "side"));
            if (meaning.stream().noneMatch(componentMeaning::contains)) continue;
            if (trueConstant != null) return null;
            trueConstant = constant;
        }
        if (trueConstant == null) return null;
        final String matched = trueConstant;
        final String falseConstant = ids.keySet().stream().filter(name -> !name.equals(matched)).findFirst().orElse(null);
        return falseConstant == null ? null : new BinaryBooleanIds(ids.get(falseConstant), ids.get(trueConstant));
    }

    private static Set<String> identifierWords(String identifier) {
        final String separated = identifier.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        final Set<String> result = new HashSet<>();
        for (String word : separated.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) result.add(word);
        }
        return result;
    }

    private static Optional<List<String>> manualBufferShape(Classes classes, ClassNode node) {
        final List<String> result = new ArrayList<>();
        boolean decode = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("decode") || !method.desc.contains("ByteBuf")) continue;
            decode = true;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                final String token = switch (call.name) {
                    case "readBoolean" -> "BOOLEAN";
                    case "readByte" -> "BYTE";
                    case "readShort" -> "SHORT";
                    case "readUnsignedShort" -> "UNSIGNED_SHORT";
                    case "readInt" -> "INT";
                    case "readVarInt" -> "VAR_INT";
                    case "readLong" -> "LONG";
                    case "readVarLong" -> "VAR_LONG";
                    case "readFloat" -> "FLOAT";
                    case "readDouble" -> "DOUBLE";
                    case "readUtf" -> "STRING";
                    case "readBitSet" -> friendlyBitSetIsLongArrayBacked(classes)
                            ? "BIT_SET(LONG_ARRAY)" : unknownSegment("read", call.owner + '.' + call.name + call.desc);
                    default -> null;
                };
                if (token != null) result.add(token);
                else if (call.name.startsWith("read")) {
                    result.add(unknownSegment("read", call.owner + '.' + call.name + call.desc));
                }
            }
        }
        return decode && !result.isEmpty() ? Optional.of(List.copyOf(result)) : Optional.empty();
    }

    private static int nullableObjectTransitions(Classes baseline, List<RecordComponentNode> before,
                                                 List<RecordComponentNode> after) {
        int count = 0;
        for (int index = 0; index < before.size(); index++) {
            final String beforeType = objectType(before.get(index).descriptor);
            if (beforeType == null || before.get(index).signature != null
                    || !OPTIONAL_DESCRIPTOR.equals(after.get(index).descriptor)
                    || !beforeType.equals(optionalArgument(after.get(index).signature))) continue;
            final ClassNode type = baseline.readIfPresent(beforeType);
            if (type != null && !isEnum(type)) count++;
        }
        return count;
    }

    private static void requireLegacyNullableEncoding(ClassNode recordClass, int expected) {
        int reads = 0;
        int writes = 0;
        for (MethodNode method : recordClass.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.name.equals("readNullable")) reads++;
                if (call.name.equals("writeNullable")) writes++;
            }
        }
        if (reads != expected || writes != expected) {
            throw new IllegalStateException("Retained type " + recordClass.name + " has " + expected
                    + " direct-to-Optional object changes but " + reads + " nullable reads and " + writes
                    + " nullable writes");
        }
    }

    private static void requireBooleanOptionalComponent(Classes classes, ClassNode recordClass,
                                                        RecordComponentNode component, String valueOwner,
                                                        List<Integer> path) {
        for (MethodNode method : recordClass.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                        || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
                final AbstractInsnNode operation = nextReal(instruction);
                final AbstractInsnNode apply = operation == null ? null : nextReal(operation);
                final AbstractInsnNode accessor = apply == null ? null : nextReal(apply);
                if (operation instanceof InvokeDynamicInsnNode dynamic && isBooleanOptionalOperation(dynamic)
                        && apply instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/network/codec/StreamCodec")
                        && call.name.equals("apply") && accessor instanceof InvokeDynamicInsnNode getter
                        && dynamicReferencesAccessor(getter, recordClass.name, component.name,
                        component.descriptor)) {
                    return;
                }
                if (operation instanceof InvokeDynamicInsnNode getter
                        && dynamicReferencesAccessor(getter, recordClass.name, component.name, component.descriptor)
                        && fieldUsesBooleanOptional(classes, field.owner, field.name, valueOwner)) return;
            }
        }
        throw new IllegalStateException("Target record component " + path
                + " does not use the boolean-prefixed optional codec");
    }

    private static boolean fieldUsesBooleanOptional(Classes classes, String owner, String fieldName,
                                                    String valueOwner) {
        final ClassNode codecOwner = classes.readIfPresent(owner);
        if (codecOwner == null) return false;
        final FieldNode codecField = codecOwner.fields.stream().filter(field -> field.name.equals(fieldName)
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)).findFirst().orElse(null);
        if (codecField == null) return false;
        for (MethodNode method : codecOwner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode assignment) || assignment.getOpcode() != Opcodes.PUTSTATIC
                        || !assignment.owner.equals(owner) || !assignment.name.equals(fieldName)) continue;
                final AbstractInsnNode apply = previousReal(instruction);
                final AbstractInsnNode operation = apply == null ? null : previousReal(apply);
                return apply instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/network/codec/StreamCodec") && call.name.equals("apply")
                        && operation instanceof InvokeDynamicInsnNode;
            }
        }
        return false;
    }

    private static boolean isBooleanOptionalOperation(InvokeDynamicInsnNode dynamic) {
        for (Object argument : dynamic.bsmArgs) {
            if (argument instanceof Handle handle && handle.getOwner().equals(CODEC_OWNER)
                    && handle.getName().equals("optional")
                    && handle.getTag() == Opcodes.H_INVOKESTATIC) return true;
        }
        return false;
    }

    private static List<String> legacyWireShape(ClassNode node) {
        final List<String> result = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") || !method.desc.contains("Lnet/minecraft/network/FriendlyByteBuf;")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("net/minecraft/network/FriendlyByteBuf")) continue;
                switch (call.name) {
                    case "readVarInt" -> result.add("VAR_INT");
                    case "readUtf" -> result.add("STRING(" + previousInteger(instruction) + ')');
                    default -> {
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<String> compositeWireShape(ClassNode node) {
        final List<String> result = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!Type.getReturnType(method.desc).getDescriptor().equals(STREAM_CODEC_DESCRIPTOR)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(CODEC_OWNER)) {
                    if (field.name.equals("VAR_INT")) result.add("VAR_INT");
                } else if (instruction instanceof MethodInsnNode call && call.owner.equals(CODEC_OWNER)
                        && call.name.equals("stringUtf8")) {
                    result.add("STRING(" + codecInteger(node, method, instruction) + ')');
                }
            }
        }
        return List.copyOf(result);
    }

    private static int previousInteger(AbstractInsnNode instruction) {
        final AbstractInsnNode previous = previousReal(instruction);
        if (previous instanceof IntInsnNode integer) return integer.operand;
        if (previous instanceof LdcInsnNode constant && constant.cst instanceof Integer integer) return integer;
        if (previous != null && previous.getOpcode() >= Opcodes.ICONST_M1
                && previous.getOpcode() <= Opcodes.ICONST_5) {
            return previous.getOpcode() - Opcodes.ICONST_0;
        }
        throw new IllegalStateException("Codec size argument is not a constant in " + instruction);
    }

    private static int codecInteger(ClassNode owner, MethodNode method, AbstractInsnNode instruction) {
        final AbstractInsnNode previous = previousReal(instruction);
        if (!(previous instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ILOAD) {
            return previousInteger(instruction);
        }
        int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int argument = -1;
        final Type[] arguments = Type.getArgumentTypes(method.desc);
        for (int index = 0; index < arguments.length; index++) {
            if (local == variable.var) argument = index;
            local += arguments[index].getSize();
        }
        if (argument < 0 || arguments.length != 1) {
            throw new IllegalStateException("Unsupported codec size parameter in " + owner.name + '.' + method.name);
        }
        Integer value = null;
        for (MethodNode caller : owner.methods) {
            for (AbstractInsnNode candidate : caller.instructions) {
                if (!(candidate instanceof MethodInsnNode call) || !call.owner.equals(owner.name)
                        || !call.name.equals(method.name) || !call.desc.equals(method.desc)) continue;
                final int constant = previousInteger(candidate);
                if (value != null && value != constant) {
                    throw new IllegalStateException("Codec factory " + owner.name + '.' + method.name
                            + " is called with multiple sizes");
                }
                value = constant;
            }
        }
        if (value == null) throw new IllegalStateException("Unable to resolve codec size parameter in " + owner.name);
        return value;
    }

    private static Map<String, Integer> enumIds(ClassNode enumClass) {
        final List<FieldNode> idFields = enumClass.fields.stream()
                .filter(field -> field.desc.equals("I"))
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
                .toList();
        if (idFields.size() != 1) {
            throw new IllegalStateException("Expected one integer ID field in target enum " + enumClass.name
                    + " but found " + idFields.size());
        }
        final String idField = idFields.getFirst().name;
        final MethodNode constructor = enumClass.methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .filter(method -> assignedParameter(method, enumClass.name, idField) >= 0)
                .reduce((left, right) -> {
                    throw new IllegalStateException("Multiple enum constructors assign the ID field in " + enumClass.name);
                })
                .orElseThrow(() -> new IllegalStateException("Unable to locate enum ID constructor in " + enumClass.name));
        final int parameter = assignedParameter(constructor, enumClass.name, idField);
        final Map<String, Integer> ids = enumConstants(enumClass, constructor.desc, parameter);
        final long constants = enumClass.fields.stream().filter(field -> (field.access & Opcodes.ACC_ENUM) != 0).count();
        if (ids.size() != constants) {
            throw new IllegalStateException("Unable to derive every target enum ID in " + enumClass.name);
        }
        if (new HashSet<>(ids.values()).size() != ids.size()) {
            throw new IllegalStateException("Duplicate target enum IDs in " + enumClass.name);
        }
        return Map.copyOf(ids);
    }

    private static String requireOptionalVarIntCodec(ClassNode enumClass) {
        final Set<String> optionalCodecFields = new HashSet<>();
        for (FieldNode field : enumClass.fields) {
            if (field.desc.equals(STREAM_CODEC_DESCRIPTOR) && field.signature != null
                    && field.signature.matches("Lnet/minecraft/network/codec/StreamCodec<.+Ljava/util/Optional<L"
                    + java.util.regex.Pattern.quote(enumClass.name) + ";>;>;")) {
                optionalCodecFields.add(field.name);
            }
        }
        for (MethodNode method : enumClass.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(CODEC_OWNER) && field.name.equals(OPTIONAL_VAR_INT)
                        && field.desc.equals(STREAM_CODEC_DESCRIPTOR)) {
                    final String codecField = traceOptionalCodecAssignment(instruction, enumClass, optionalCodecFields);
                    if (codecField != null) return codecField;
                }
            }
        }
        throw new IllegalStateException("Target enum " + enumClass.name
                + " does not define an optional codec based on ByteBufCodecs.OPTIONAL_VAR_INT");
    }

    private static String traceOptionalCodecAssignment(AbstractInsnNode source, ClassNode enumClass,
                                                       Set<String> optionalCodecFields) {
        final ArrayDeque<Value> stack = new ArrayDeque<>();
        stack.push(Value.DERIVED);
        for (AbstractInsnNode instruction = source.getNext(); instruction != null; instruction = instruction.getNext()) {
            final int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                final boolean derived = popArguments(stack, dynamic.desc);
                pushReturn(stack, dynamic.desc, derived);
            } else if (instruction instanceof MethodInsnNode method) {
                boolean derived = popArguments(stack, method.desc);
                if (opcode != Opcodes.INVOKESTATIC) derived |= popValue(stack) == Value.DERIVED;
                if (derived && (!method.owner.equals("net/minecraft/network/codec/StreamCodec")
                        || !Type.getReturnType(method.desc).getDescriptor().equals(STREAM_CODEC_DESCRIPTOR))) {
                    throw unsupportedCodecFlow(enumClass, instruction);
                }
                pushReturn(stack, method.desc, derived);
            } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) {
                stack.push(Value.OTHER);
            } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.PUTSTATIC) {
                final Value value = popValue(stack);
                if (value == Value.DERIVED) {
                    if (field.owner.equals(enumClass.name) && field.desc.equals(STREAM_CODEC_DESCRIPTOR)
                            && optionalCodecFields.contains(field.name)) {
                        return field.name;
                    }
                    throw unsupportedCodecFlow(enumClass, instruction);
                }
            } else if (instruction instanceof LdcInsnNode || instruction instanceof IntInsnNode
                    || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5 || opcode == Opcodes.ACONST_NULL) {
                stack.push(Value.OTHER);
            } else if (instruction instanceof TypeInsnNode && opcode == Opcodes.CHECKCAST) {
                // CHECKCAST preserves the value and its provenance.
            } else {
                throw unsupportedCodecFlow(enumClass, instruction);
            }
        }
        return null;
    }

    private static void requireComponentCodecReference(ClassNode recordClass, RecordComponentNode component,
                                                       String enumOwner, String codecField, List<Integer> path) {
        for (MethodNode method : recordClass.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                        || !field.owner.equals(enumOwner) || !field.name.equals(codecField)
                        || !field.desc.equals(STREAM_CODEC_DESCRIPTOR)) continue;
                final AbstractInsnNode next = nextReal(instruction);
                if (next instanceof InvokeDynamicInsnNode dynamic && dynamicReferencesAccessor(
                        dynamic, recordClass.name, component.name, component.descriptor)) {
                    return;
                }
            }
        }
        throw new IllegalStateException("Target record component " + path
                + " does not directly reference the target enum optional codec");
    }

    private static boolean dynamicReferencesAccessor(InvokeDynamicInsnNode dynamic, String owner, String name,
                                                     String descriptor) {
        for (Object argument : dynamic.bsmArgs) {
            if (argument instanceof Handle handle && handle.getOwner().equals(owner) && handle.getName().equals(name)
                    && handle.getDesc().equals("()" + descriptor)
                    && handle.getTag() == Opcodes.H_INVOKEVIRTUAL) {
                return true;
            }
        }
        return false;
    }

    private static int assignedParameter(MethodNode constructor, String owner, String fieldName) {
        int parameter = -1;
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(owner) || !field.name.equals(fieldName) || !field.desc.equals("I")) continue;
            final AbstractInsnNode value = previousReal(instruction);
            final AbstractInsnNode receiver = value == null ? null : previousReal(value);
            if (!(value instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ILOAD
                    || !(receiver instanceof VarInsnNode loadThis) || loadThis.getOpcode() != Opcodes.ALOAD
                    || loadThis.var != 0 || parameter >= 0) return -1;
            int local = 1;
            final Type[] arguments = Type.getArgumentTypes(constructor.desc);
            for (int index = 0; index < arguments.length; index++) {
                if (local == variable.var) parameter = index;
                local += arguments[index].getSize();
            }
            if (parameter < 0 || Type.getArgumentTypes(constructor.desc)[parameter].getSort() != Type.INT) return -1;
        }
        return parameter;
    }

    private static Map<String, Integer> enumConstants(ClassNode enumClass, String constructorDescriptor,
                                                       int idParameter) {
        final MethodNode clinit = enumClass.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing enum initializer in " + enumClass.name));
        final ArrayDeque<Object> stack = new ArrayDeque<>();
        final Map<String, Integer> result = new LinkedHashMap<>();
        final Set<String> constantNames = new HashSet<>();
        enumClass.fields.stream().filter(field -> (field.access & Opcodes.ACC_ENUM) != 0)
                .forEach(field -> constantNames.add(field.name));
        for (AbstractInsnNode instruction : clinit.instructions) {
            final int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            if (instruction instanceof TypeInsnNode type && opcode == Opcodes.NEW) {
                if (!type.desc.equals(enumClass.name)) throw unsupportedEnumInitializer(enumClass, instruction);
                stack.push(new EnumValue());
            } else if (opcode == Opcodes.DUP) {
                if (stack.isEmpty()) throw unsupportedEnumInitializer(enumClass, instruction);
                stack.push(stack.peek());
            } else if (instruction instanceof LdcInsnNode constant) {
                stack.push(constant.cst);
            } else if (instruction instanceof IntInsnNode integer) {
                stack.push(integer.operand);
            } else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                stack.push(opcode - Opcodes.ICONST_0);
            } else if (opcode == Opcodes.ACONST_NULL) {
                stack.push(Value.OTHER);
            } else if (instruction instanceof MethodInsnNode method && opcode == Opcodes.INVOKESPECIAL
                    && method.owner.equals(enumClass.name) && method.name.equals("<init>")
                    && method.desc.equals(constructorDescriptor)) {
                final Type[] arguments = Type.getArgumentTypes(method.desc);
                final Object[] values = new Object[arguments.length];
                for (int index = arguments.length - 1; index >= 0; index--) {
                    values[index] = requireStackValue(stack, enumClass, instruction);
                }
                final Object receiver = requireStackValue(stack, enumClass, instruction);
                if (!(receiver instanceof EnumValue enumValue) || !(values[idParameter] instanceof Integer id)) {
                    throw new IllegalStateException("Enum ID is not a direct integer constant in " + enumClass.name);
                }
                enumValue.id = id;
            } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.PUTSTATIC
                    && field.owner.equals(enumClass.name) && constantNames.contains(field.name)) {
                final Object value = requireStackValue(stack, enumClass, instruction);
                if (!(value instanceof EnumValue enumValue) || enumValue.id == null) {
                    throw new IllegalStateException("Missing constant ID for " + enumClass.name + '.' + field.name);
                }
                result.put(field.name, enumValue.id);
                if (result.size() == constantNames.size()) return result;
            } else {
                throw unsupportedEnumInitializer(enumClass, instruction);
            }
        }
        return result;
    }

    private static Object requireStackValue(ArrayDeque<Object> stack, ClassNode owner, AbstractInsnNode instruction) {
        if (stack.isEmpty()) throw unsupportedEnumInitializer(owner, instruction);
        return stack.pop();
    }

    private static boolean popArguments(ArrayDeque<Value> stack, String descriptor) {
        boolean derived = false;
        final Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int index = arguments.length - 1; index >= 0; index--) derived |= popValue(stack) == Value.DERIVED;
        return derived;
    }

    private static Value popValue(ArrayDeque<Value> stack) {
        if (stack.isEmpty()) throw new IllegalStateException("Unsupported optional codec stack underflow");
        return stack.pop();
    }

    private static void pushReturn(ArrayDeque<Value> stack, String descriptor, boolean derived) {
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
            stack.push(derived ? Value.DERIVED : Value.OTHER);
        }
    }

    private static IllegalStateException unsupportedCodecFlow(ClassNode owner, AbstractInsnNode instruction) {
        return new IllegalStateException("Unsupported optional codec dataflow in " + owner.name
                + " at opcode " + instruction.getOpcode());
    }

    private static IllegalStateException unsupportedEnumInitializer(ClassNode owner, AbstractInsnNode instruction) {
        return new IllegalStateException("Unsupported enum initializer instruction in " + owner.name
                + " at opcode " + instruction.getOpcode());
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static boolean isEnum(ClassNode node) {
        return (node.access & Opcodes.ACC_ENUM) != 0;
    }

    private static List<RecordComponentNode> components(ClassNode node) {
        if (node.recordComponents != null && !node.recordComponents.isEmpty()) return node.recordComponents;
        return node.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
                .filter(field -> (field.access & Opcodes.ACC_FINAL) != 0)
                .map(field -> new RecordComponentNode(field.name, field.desc, field.signature))
                .toList();
    }

    private static String objectType(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L")) return null;
        return Type.getType(descriptor).getInternalName();
    }

    private static String optionalArgument(String signature) {
        final String prefix = "Ljava/util/Optional<L";
        final String suffix = ";>;";
        if (signature == null || !signature.startsWith(prefix) || !signature.endsWith(suffix)) return null;
        final String argument = signature.substring(prefix.length(), signature.length() - suffix.length());
        return argument.isEmpty() || argument.indexOf(';') >= 0 || argument.indexOf('<') >= 0 ? null : argument;
    }

    private static List<Integer> append(List<Integer> path, int component) {
        final List<Integer> result = new ArrayList<>(path);
        result.add(component);
        return List.copyOf(result);
    }

    enum Kind {
        OPTIONAL_ENUM,
        BYTE_ARRAY_BIT_SET,
        REORDERED_BOOLEAN_ENUM,
        LINEAR_POSITION_PATH,
        APPENDED_BOOLEAN
    }

    record Migration(PacketUpdater.RetainedPacket packet, Kind kind, List<Integer> path,
                     Map<String, Integer> ids, String targetEnum, int fixedSize, int discriminator,
                     boolean defaultValue, int falseId, int trueId) {
        Migration {
            path = List.copyOf(path);
            ids = Map.copyOf(ids);
        }

        Migration(PacketUpdater.RetainedPacket packet, Kind kind, List<Integer> path,
                  Map<String, Integer> ids, String targetEnum, int fixedSize, int discriminator,
                  boolean defaultValue) {
            this(packet, kind, path, ids, targetEnum, fixedSize, discriminator, defaultValue, 0, 1);
        }

        static Migration optionalEnum(List<Integer> path, Map<String, Integer> ids, String targetEnum) {
            return new Migration(null, Kind.OPTIONAL_ENUM, path, ids, targetEnum, -1, -1, false, -1, -1);
        }

        static Migration byteArrayBitSet(List<Integer> path) {
            return new Migration(null, Kind.BYTE_ARRAY_BIT_SET, path, Map.of(), "", -1, -1, false, -1, -1);
        }

        static Migration reorderedBooleanEnum(List<Integer> path, int fixedSize, int falseId, int trueId) {
            return new Migration(null, Kind.REORDERED_BOOLEAN_ENUM, path, Map.of(), "", fixedSize, -1, false,
                    falseId, trueId);
        }

        static Migration linearPositionPath(List<Integer> path, int discriminator) {
            return new Migration(null, Kind.LINEAR_POSITION_PATH, path, Map.of(), "", -1, discriminator, false,
                    -1, -1);
        }

        static Migration appendedBoolean(List<Integer> path, boolean defaultValue) {
            return new Migration(null, Kind.APPENDED_BOOLEAN, path, Map.of(), "", -1, -1, defaultValue, -1, -1);
        }

        Migration withPacket(PacketUpdater.RetainedPacket packet) {
            return new Migration(packet, kind, path, ids, targetEnum, fixedSize, discriminator, defaultValue,
                    falseId, trueId);
        }
    }

    private record MigrationKey(String packetClass, String serializer, Kind kind, List<Integer> path) {
    }

    private record ClassPair(String baseline, String target) {
    }

    private record BinaryBooleanIds(int falseId, int trueId) {
    }

    private record ComponentCodecShape(boolean referenced, Optional<List<String>> shape) {
    }

    private enum Value {
        DERIVED,
        OTHER
    }

    private static final class EnumValue {
        private Integer id;
    }

    private static final class Classes implements AutoCloseable {
        private final JarFile jar;
        private final Map<String, ClassNode> cache = new HashMap<>();

        private Classes(Path path) throws IOException {
            this.jar = new JarFile(path.toFile());
        }

        private ClassNode read(String internalName) {
            final String normalizedName = internalName.replace('.', '/');
            return cache.computeIfAbsent(normalizedName, name -> {
                final var entry = jar.getJarEntry(name + ".class");
                if (entry == null) throw new IllegalStateException("Missing class " + name + " in " + jar.getName());
                try (var input = jar.getInputStream(entry)) {
                    final var node = new ClassNode(Opcodes.ASM9);
                    new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    return node;
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to inspect " + name, exception);
                }
            });
        }

        private ClassNode readIfPresent(String internalName) {
            final String normalizedName = internalName.replace('.', '/');
            return jar.getJarEntry(normalizedName + ".class") == null ? null : read(normalizedName);
        }

        private List<ClassNode> all() {
            return jar.stream().filter(entry -> entry.getName().endsWith(".class"))
                    .map(entry -> entry.getName().substring(0, entry.getName().length() - 6))
                    .map(this::read).toList();
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }
}
