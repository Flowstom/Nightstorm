package net.flowstom.nightstorm;

import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Function;

/** A projection from retained constructor arguments into a proven target wire schema. */
record WireMigration(WireSchema baseline, WireSchema target, List<Binding> bindings) {
    WireMigration { bindings = List.copyOf(bindings); }
    record Binding(int component, int source, String javaType, String networkType, String constant) {
        boolean missing() { return source < 0 && constant == null; }
    }

    static Optional<WireMigration> plan(ClassNode before, ClassNode after, Function<String, ClassNode> classes) {
        return plan(before, after, classes, classes, (oldField, newField) -> null);
    }

    static Optional<WireMigration> plan(ClassNode before, ClassNode after, Function<String, ClassNode> baselineClasses,
                                        Function<String, ClassNode> classes,
                                        java.util.function.BiFunction<WireSchema.Component, WireSchema.Component, BooleanEnumProof.Ids> booleanProof) {
        var oldSchema = WireSchema.scan(before, baselineClasses);
        var newSchema = WireSchema.scan(after, classes);
        if (oldSchema.isEmpty() || newSchema.isEmpty()) return Optional.empty();
        var baseline = oldSchema.get();
        if ((before.recordComponents == null || before.recordComponents.isEmpty())
                && baseline.fields().stream().anyMatch(f -> oldSchema.get().components().get(f.component()).descriptor().startsWith("[")
                && f.codec().operations().stream().anyMatch(op -> op.startsWith("fixed:")))) {
            var schema = baseline;
            baseline = new WireSchema(schema.owner(), schema.fields().stream().map(f -> schema.components().get(f.component())).toList(),
                    java.util.stream.IntStream.range(0, schema.fields().size()).mapToObj(i -> new WireSchema.Field(i, schema.fields().get(i).codec())).toList());
        }
        var target = newSchema.get();
        if (baseline.components().equals(target.components()) && baseline.fields().equals(target.fields())) return Optional.empty();
        var oldCodecs = new HashMap<Integer, WireSchema.Codec>();
        for (var field : baseline.fields()) {
            if (oldCodecs.putIfAbsent(field.component(), field.codec()) != null) return Optional.empty();
        }
        String oldConstructor = constructorDescriptor(baseline);
        String newConstructor = constructorDescriptor(target);
        Map<String, ConstructorMapping.Value> constructor = Map.of();
        if (!oldConstructor.equals(newConstructor)) {
            var compatibility = after.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(oldConstructor)).findFirst();
            if (compatibility.isPresent()) {
                // An existing but unrecognized constructor is evidence we must not replace with guesses.
                var inferred = ConstructorMapping.assignments(after, compatibility.get());
                if (inferred.isEmpty()) return Optional.empty();
                constructor = inferred.get();
                if (!constructor.keySet().equals(target.components().stream().map(WireSchema.Component::name).collect(java.util.stream.Collectors.toSet()))) return Optional.empty();
            }
        }
        var bindings = new ArrayList<Binding>();
        var used = new HashSet<Integer>();
        for (var field : target.fields()) {
            var component = target.components().get(field.component());
            ConstructorMapping.Value value = constructor.get(component.name());
            if (constructor.isEmpty()) {
                for (int i = 0; i < baseline.components().size(); i++) {
                    if (baseline.components().get(i).equals(component) || baseline.components().get(i).name().equals(component.name())
                            && baseline.components().get(i).descriptor().startsWith("[") && component.descriptor().equals("Ljava/util/List;")) value = new ConstructorMapping.Parameter(i, component.descriptor());
                }
            }
            var codec = translate(field.codec(), component, classes);
            BooleanEnumProof.Ids booleanIds = null;
            if (value == null && component.descriptor().startsWith("L")) {
                ClassNode enumeration = classes.apply(Type.getType(component.descriptor()).getInternalName());
                if (enumeration != null && enumIdCodec(enumeration)) {
                    for (int i = 0; i < baseline.components().size(); i++) {
                        var candidate = baseline.components().get(i);
                        if (!candidate.descriptor().equals("Z")) continue;
                        var proof = booleanProof.apply(candidate, component);
                        if (proof == null) continue;
                        if (booleanIds != null) return Optional.empty();
                        booleanIds = proof;
                        value = new ConstructorMapping.Parameter(i, "Z");
                    }
                }
            }
            int source = -1;
            String constant = null;
            String javaType = codec.javaType(), networkType = codec.networkType();
            Integer nullableSource = value == null
                    ? WireCanonical.nullableByteSource(baseline, oldCodecs, component, field.codec()) : null;
            if (nullableSource != null) {
                Map<String, Integer> ids = null;
                try {
                    ClassNode enumeration = classes.apply(field.codec().owner());
                    if (enumeration != null) ids = PacketMigrationScanner.enumIds(enumeration);
                } catch (RuntimeException exception) {
                    ids = null;
                }
                if (ids == null || ids.isEmpty()) return Optional.empty();
                used.add(nullableSource);
                bindings.add(new Binding(field.component(), nullableSource, "",
                        CodecChange.enumeration("NetworkBuffer.OPTIONAL_VAR_INT", ids, true).expression(), null));
                continue;
            }
            if (value instanceof ConstructorMapping.Parameter parameter) {
                source = parameter.index();
                boolean container = parameter.descriptor().startsWith("[") && component.descriptor().equals("Ljava/util/List;");
                if (oldCodecs.get(source) == null || booleanIds == null && ((!parameter.descriptor().equals(component.descriptor()) && !container)
                        || !compatibleEncoding(oldCodecs.get(source), field.codec()))) return Optional.empty();
                if (booleanIds != null) {
                    javaType = "boolean";
                    networkType = CodecChange.bool(booleanIds.falseId(), booleanIds.trueId()).expression();
                }
                if (!codec.supported() && booleanIds == null) {
                    javaType = "";
                    networkType = "";
                }
                used.add(source);
            } else if (value instanceof ConstructorMapping.EnumConstant enumValue) {
                if (!field.codec().operations().isEmpty() || !field.codec().owner().equals(enumValue.owner())
                        || !field.codec().name().equals("STREAM_CODEC") || !component.descriptor().equals("L" + enumValue.owner() + ";")) return Optional.empty();
                var enumClass = classes.apply(enumValue.owner());
                if (enumClass == null || !enumIdCodec(enumClass)) return Optional.empty();
                Integer id = PacketMigrationScanner.enumIds(enumClass).get(enumValue.name());
                if (id == null) return Optional.empty();
                constant = id.toString();
                javaType = "int";
                networkType = "NetworkBuffer.VAR_INT";
            } else if (value instanceof ConstructorMapping.Constant literal) {
                constant = literal(literal.value(), component.descriptor());
                if (constant == null || !codec.supported()) return Optional.empty();
            } else if (!codec.supported()) return Optional.empty();
            if (booleanIds == null && codec.supported() && !codec.vanillaDescriptor().equals(component.descriptor())) return Optional.empty();
            bindings.add(new Binding(field.component(), source, javaType, networkType, constant));
        }
        return Optional.of(new WireMigration(baseline, target, bindings));
    }

    static boolean compatibleEncoding(WireSchema.Codec before, WireSchema.Codec after) {
        if (before.equals(after)) return true;
        if (before.owner().equals(after.owner()) && before.name().equals(after.name()) && before.arguments().equals(after.arguments())
                && before.operations().stream().filter(op -> !op.startsWith("limit:")).toList()
                .equals(after.operations().stream().filter(op -> !op.startsWith("limit:")).toList())) return true;
        if (!before.owner().equals(WireSchema.BYTE_CODECS) || !after.owner().equals(WireSchema.BYTE_CODECS)
                || !before.operations().isEmpty() || !after.operations().isEmpty()) return false;
        final Set<String> pair = Set.of(before.name(), after.name());
        return pair.equals(Set.of("INT", "VAR_INT")) || pair.equals(Set.of("LONG", "VAR_LONG"));
    }

    static WireMigration compose(WireMigration previous, WireMigration next) {
        if (previous.baseline().equals(next.baseline()) && previous.target().equals(next.target())) return previous;
        if (previous.baseline().equals(next.baseline())) return next;
        if (previous.target().equals(next.target())) return previous;
        if (!previous.target().equals(next.baseline())) {
            throw new IllegalStateException("Saved wire adapter does not match the migration baseline");
        }
        final Map<Integer, Binding> prior = new HashMap<>();
        previous.bindings().forEach(binding -> prior.put(binding.component(), binding));
        final List<Binding> composed = new ArrayList<>();
        for (var binding : next.bindings()) {
            if (binding.source() < 0) composed.add(binding);
            else {
                final Binding old = prior.get(binding.source());
                if (old == null || !previous.target().components().get(binding.source()).descriptor()
                        .equals(next.target().components().get(binding.component()).descriptor())) {
                    throw new IllegalStateException("Cannot compose incompatible wire field types");
                }
                final String javaType = binding.javaType().isEmpty() ? old.javaType() : binding.javaType();
                final String networkType = binding.networkType().isEmpty() ? old.networkType() : binding.networkType();
                composed.add(new Binding(binding.component(), old.source(), javaType, networkType, old.constant()));
            }
        }
        return new WireMigration(previous.baseline(), next.target(), composed);
    }

    private static PacketCodecScanner.CodecType translate(WireSchema.Codec codec, WireSchema.Component component,
                                                          Function<String, ClassNode> classes) {
        return PacketCodecScanner.translate(codec);
    }

    static boolean enumIdCodec(ClassNode owner) {
        if ((owner.access & Opcodes.ACC_ENUM) == 0) return false;
        var idFields = owner.fields.stream().filter(field -> field.desc.equals("I")
                && (field.access & Opcodes.ACC_STATIC) == 0).toList();
        if (idFields.size() != 1) return false;
        for (var method : owner.methods) {
            if (!method.name.equals("<clinit>")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTSTATIC || !field.owner.equals(owner.name)
                        || !field.name.equals("STREAM_CODEC")) continue;
                var callNode = previous(instruction);
                if (!(callNode instanceof MethodInsnNode call)
                        || !call.owner.equals(WireSchema.BYTE_CODECS)
                        || !Set.of("idMapper", "enumCodec").contains(call.name)) return false;
                if (!(previous(callNode) instanceof InvokeDynamicInsnNode mapper)) return false;
                if (call.name.equals("enumCodec") && (!(previous(mapper) instanceof org.objectweb.asm.tree.LdcInsnNode literal)
                        || !(literal.cst instanceof Type type) || !type.getInternalName().equals(owner.name))) return false;
                for (Object argument : mapper.bsmArgs) {
                    if (!(argument instanceof Handle handle) || !handle.getOwner().equals(owner.name)) continue;
                    var implementation = owner.methods.stream().filter(m -> m.name.equals(handle.getName()) && m.desc.equals(handle.getDesc()))
                            .findFirst().orElse(null);
                    if (implementation == null) continue;
                    var body = java.util.Arrays.stream(implementation.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
                    if (body.size() == 3 && body.get(0) instanceof VarInsnNode load
                            && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                            && body.get(1) instanceof FieldInsnNode get
                            && get.getOpcode() == Opcodes.GETFIELD && get.owner.equals(owner.name)
                            && get.name.equals(idFields.getFirst().name) && get.desc.equals("I")
                            && body.get(2).getOpcode() == Opcodes.IRETURN) return true;
                }
            }
        }
        return false;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static String constructorDescriptor(WireSchema schema) {
        return Type.getMethodDescriptor(Type.VOID_TYPE, schema.components().stream()
                .map(c -> Type.getType(c.descriptor())).toArray(Type[]::new));
    }

    static String literal(Object value, String descriptor) {
        if (descriptor.equals("Ljava/lang/String;") && value instanceof String text) return new com.github.javaparser.ast.expr.StringLiteralExpr(text).toString();
        if (!(value instanceof Number number)) return null;
        return switch (descriptor) {
            case "Z" -> number.intValue() == 0 ? "false" : number.intValue() == 1 ? "true" : null;
            case "B" -> value instanceof Integer && number.intValue() == number.byteValue() ? "(byte) " + number : null;
            case "S" -> value instanceof Integer && number.intValue() == number.shortValue() ? "(short) " + number : null;
            case "I" -> value instanceof Integer ? number.toString() : null;
            case "J" -> value instanceof Long ? number + "L" : null;
            case "F" -> value instanceof Float && Float.isFinite(number.floatValue()) ? number + "f" : null;
            case "D" -> value instanceof Double && Double.isFinite(number.doubleValue()) ? number + "d" : null;
            default -> null;
        };
    }
}
