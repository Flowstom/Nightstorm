package net.flowstom.nightstorm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;

/** An enum-dispatched codec catalog, including every discovered variant. */
record WireDispatch(String owner, List<Variant> variants, Map<String, WireSchema> schemas) {
    record Variant(int id, String name, WireSchema payload) { }
    record Plan(WireSchema baseline, WireSchema target, int unionComponent, int legacySource,
                int legacyVariant, List<Integer> sources, WireDispatch dispatch) {
        Plan { sources = List.copyOf(sources); }
    }

    static Optional<Plan> plan(ClassNode before, ClassNode after, Function<String, ClassNode> oldClasses,
                               Function<String, ClassNode> classes) {
        var oldSchema = WireSchema.scan(before, oldClasses);
        var newSchema = WireSchema.scan(after, classes);
        if (oldSchema.isEmpty() || newSchema.isEmpty()) return Optional.empty();
        var target = newSchema.get();
        Set<String> identities = target.components().stream().map(WireSchema.Component::name).collect(java.util.stream.Collectors.toSet());
        var baseline = flatten(oldSchema.get(), oldClasses, new HashSet<>(), identities);
        for (var field : target.fields()) {
            var union = classes.apply(field.codec().owner());
            if (union == null || !field.codec().operations().isEmpty()) continue;
            var dispatch = scan(union, classes);
            if (dispatch.isEmpty()) continue;
            var component = target.components().get(field.component());
            List<Integer> sources = new ArrayList<>();
            int legacySource = -1, legacyVariant = -1;
            for (var variant : dispatch.get().variants()) {
                if (variant.payload().fields().size() != 1) continue;
                var payload = variant.payload().fields().getFirst();
                if (!payload.codec().operations().isEmpty()) continue;
                for (int i = 0; i < baseline.components().size(); i++) {
                    if (!baseline.components().get(i).name().equals(component.name())
                            || !codec(baseline, i).equals(payload.codec())) continue;
                    if (legacySource >= 0) return Optional.empty();
                    legacySource = i; legacyVariant = variant.id();
                }
            }
            if (legacySource < 0) continue;
            for (var targetField : target.fields()) {
                if (targetField.component() == field.component()) { sources.add(-1); continue; }
                var c = target.components().get(targetField.component());
                List<Integer> matches = new ArrayList<>();
                for (int i = 0; i < baseline.components().size(); i++) {
                    if (baseline.components().get(i).name().equals(c.name())
                            && baseline.components().get(i).descriptor().equals(c.descriptor())
                            && codec(baseline, i).equals(targetField.codec())) matches.add(i);
                }
                if (matches.size() != 1) return Optional.empty();
                sources.add(matches.getFirst());
            }
            return Optional.of(new Plan(baseline, target, field.component(), legacySource, legacyVariant,
                    sources, dispatch.get()));
        }
        return Optional.empty();
    }

    private static WireSchema.Codec codec(WireSchema schema, int component) {
        return schema.fields().stream().filter(f -> f.component() == component).findFirst().orElseThrow().codec();
    }

    private static WireSchema flatten(WireSchema schema, Function<String, ClassNode> classes, Set<String> active, Set<String> identities) {
        if (!active.add(schema.owner())) throw new IllegalStateException("Recursive dispatch baseline");
        List<WireSchema.Component> components = new ArrayList<>(); List<WireSchema.Field> fields = new ArrayList<>();
        for (var field : schema.fields()) {
            ClassNode type = classes.apply(field.codec().owner());
            Optional<WireSchema> nested = type == null || !field.codec().operations().isEmpty()
                    ? Optional.empty() : WireSchema.scan(type, classes);
            if (nested.isPresent() && !active.contains(type.name)
                    && nested.get().components().stream().anyMatch(c -> identities.contains(c.name()))) {
                var child = flatten(nested.get(), classes, active, identities);
                for (var leaf : child.fields()) {
                    fields.add(new WireSchema.Field(components.size(), leaf.codec()));
                    components.add(child.components().get(leaf.component()));
                }
            } else {
                fields.add(new WireSchema.Field(components.size(), field.codec()));
                components.add(schema.components().get(field.component()));
            }
        }
        active.remove(schema.owner());
        return new WireSchema(schema.owner(), components, fields);
    }

    static Optional<WireDispatch> scan(ClassNode union, Function<String, ClassNode> classes) {
        try {
            var init = union.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
            var code = real(init);
            var dispatches = code.stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                    .filter(c -> c.name.equals("dispatch") && c.owner.equals("net/minecraft/network/codec/StreamCodec")).toList();
            if (dispatches.size() != 1 || code.size() != 6 || !(code.get(0) instanceof FieldInsnNode tag)
                    || !(code.get(1) instanceof InvokeDynamicInsnNode selector) || !(code.get(2) instanceof InvokeDynamicInsnNode catalog)) return Optional.empty();
            Handle select = handle(selector), selectCodec = handle(catalog);
            if (!select.getOwner().equals(union.name) || !select.getDesc().equals("()L" + tag.owner + ";")
                    || !selectCodec.getOwner().equals(tag.owner) || !selectCodec.getDesc().equals("()" + WireSchema.STREAM_CODEC)) return Optional.empty();
            ClassNode tags = classes.apply(tag.owner);
            MethodNode codecGetter = tags.methods.stream().filter(m -> m.name.equals(selectCodec.getName()) && m.desc.equals(selectCodec.getDesc())).findFirst().orElseThrow();
            var getter = real(codecGetter);
            if (getter.size() != 3 || !(getter.getFirst() instanceof VarInsnNode receiver) || receiver.var != 0
                    || receiver.getOpcode() != Opcodes.ALOAD || getter.getLast().getOpcode() != Opcodes.ARETURN
                    || !(getter.get(1) instanceof FieldInsnNode codecField) || codecField.getOpcode() != Opcodes.GETFIELD) return Optional.empty();
            String tagMethod = tagEncoder(tags);
            if (tagMethod == null) return Optional.empty();
            Map<String, Integer> ids = tagMethod.equals("ordinal") ? ordinalIds(tags) : PacketMigrationScanner.enumIds(tags);
            List<Variant> variants = new ArrayList<>();
            var initializer = tags.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
            var catalogCode = real(initializer);
            for (int i = 0; i < catalogCode.size(); i++) {
                if (!(catalogCode.get(i) instanceof MethodInsnNode constructor) || !constructor.name.equals("<init>") || !constructor.owner.equals(tags.name)) continue;
                var parameters = Type.getArgumentTypes(constructor.desc);
                int codecParameter = -1;
                // Enum constructors call Enum's constructor; verify the codec assignment directly.
                var body = real(constructorBody(tags, constructor.desc));
                int slot = 1;
                for (int n = 0; n < parameters.length; n++) {
                    for (int k = 2; k < body.size(); k++) {
                        if (body.get(k) instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                                && f.owner.equals(tags.name) && f.name.equals(codecField.name) && f.desc.equals(WireSchema.STREAM_CODEC)
                                && body.get(k-1) instanceof VarInsnNode load && load.var == slot && load.getOpcode() == Opcodes.ALOAD
                                && body.get(k-2) instanceof VarInsnNode self && self.var == 0 && self.getOpcode() == Opcodes.ALOAD) codecParameter = n;
                    }
                    slot += parameters[n].getSize();
                }
                if (codecParameter != parameters.length - 1 || !(catalogCode.get(i-1) instanceof FieldInsnNode payloadCodec)
                        || payloadCodec.getOpcode() != Opcodes.GETSTATIC || !payloadCodec.desc.equals(WireSchema.STREAM_CODEC)
                        || !(catalogCode.get(i+1) instanceof FieldInsnNode constant) || !ids.containsKey(constant.name)) return Optional.empty();
                var payloadClass = classes.apply(payloadCodec.owner);
                var payload = payload(payloadClass, classes).orElseThrow();
                // The variant's type selector must select the same catalog entry.
                var typeMethod = payloadClass.methods.stream().filter(m -> m.name.equals(select.getName()) && m.desc.equals(select.getDesc())).findFirst().orElseThrow();
                var variantCode = real(typeMethod);
                if (variantCode.size() != 2 || variantCode.getLast().getOpcode() != Opcodes.ARETURN
                        || !(variantCode.getFirst() instanceof FieldInsnNode selected) || selected.getOpcode() != Opcodes.GETSTATIC
                        || !selected.owner.equals(tags.name) || !selected.desc.equals(constant.desc) || !selected.name.equals(constant.name)) return Optional.empty();
                variants.add(new Variant(ids.get(constant.name), constant.name, payload));
            }
            if (variants.size() != ids.size() || variants.stream().map(Variant::id).distinct().count() != variants.size()) return Optional.empty();
            Map<String, WireSchema> schemas = new LinkedHashMap<>();
            for (var variant : variants) gather(variant.payload(), classes, schemas);
            return Optional.of(new WireDispatch(union.name, List.copyOf(variants), Map.copyOf(schemas)));
        } catch (NoSuchElementException | IllegalStateException | IllegalArgumentException | UnsupportedOperationException e) { return Optional.empty(); }
    }

    private static Optional<WireSchema> payload(ClassNode node, Function<String, ClassNode> classes) {
        var schema = WireSchema.scan(node, classes);
        if (schema.isPresent()) return schema;
        // A mapped carrier may derive additional fields, while encoding just one constructor input.
        var code = real(node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow());
        boolean list = code.size() == 8;
        if ((!list && code.size() != 6) || !(code.get(0) instanceof FieldInsnNode element)) return Optional.empty();
        int offset = list ? 2 : 0;
        if (list && (!(code.get(1) instanceof MethodInsnNode operation) || !operation.name.equals("list")
                || !(code.get(2) instanceof MethodInsnNode apply) || !apply.name.equals("apply"))) return Optional.empty();
        if (!(code.get(1+offset) instanceof InvokeDynamicInsnNode factory) || !(code.get(2+offset) instanceof InvokeDynamicInsnNode accessor)
                || !(code.get(3+offset) instanceof MethodInsnNode map) || !map.name.equals("map")) return Optional.empty();
        Handle create = handle(factory), get = handle(accessor);
        var primitive = PacketCodecScanner.translate(new WireSchema.Codec(element.owner, element.name));
        String descriptor = list ? "Ljava/util/List;" : primitive.supported() ? primitive.vanillaDescriptor() : "L" + element.owner + ";";
        if (!create.getOwner().equals(node.name) || !create.getName().equals("<init>") || !create.getDesc().equals("(" + descriptor + ")V")
                || !get.getOwner().equals(node.name) || !get.getDesc().equals("()" + descriptor)) return Optional.empty();
        var components = WireSchema.components(node);
        var component = components.stream().filter(c -> c.name().equals(get.getName()) && c.descriptor().equals(descriptor)).findFirst().orElseThrow();
        if (!capturesInput(node, create.getDesc(), component.name(), 0, classes, new HashSet<>())) return Optional.empty();
        var getterBody = real(node.methods.stream().filter(m -> m.name.equals(get.getName()) && m.desc.equals(get.getDesc())).findFirst().orElseThrow());
        if (getterBody.size()!=3 || !(getterBody.get(1) instanceof FieldInsnNode readField) || readField.getOpcode()!=Opcodes.GETFIELD
                || !readField.name.equals(component.name()) || !readField.owner.equals(node.name)) return Optional.empty();
        var codec = new WireSchema.Codec(element.owner, element.name);
        if (list) {
            codec = codec.apply("list");
            if (requiresNonemptyInput(node, create.getDesc(), 0, new HashSet<>())) codec = codec.apply("nonempty");
        }
        return Optional.of(new WireSchema(node.name, List.of(component), List.of(new WireSchema.Field(0, codec))));
    }

    private static boolean requiresNonemptyInput(ClassNode owner, String descriptor, int parameter, Set<String> active) {
        if (!active.add(descriptor)) return false;
        try {
            var method = constructorBody(owner, descriptor);
            var frames = BytecodeArguments.frames(owner, method);
            if (frames == null) return false;
            int slot = 1;
            var parameters = Type.getArgumentTypes(descriptor);
            for (int n = 0; n < parameter; n++) slot += parameters[n].getSize();
            for (int n = 0; n < method.instructions.size(); n++) {
                if (!(method.instructions.get(n) instanceof MethodInsnNode call) || frames[n] == null) continue;
                var frame = frames[n];
                if (call.owner.equals("java/util/List") && Set.of("getFirst", "getLast").contains(call.name)
                        && parameterLoad(frame.getStack(frame.getStackSize()-1), slot)) return true;
                if (call.owner.equals(owner.name) && call.name.equals("<init>")) {
                    int count = Type.getArgumentTypes(call.desc).length;
                    for (int argument = 0; argument < count; argument++) {
                        if (parameterLoad(BytecodeArguments.argument(frame, count, argument), slot)
                                && requiresNonemptyInput(owner, call.desc, argument, active)) return true;
                    }
                }
            }
            return false;
        } finally { active.remove(descriptor); }
    }
    static Map<String, WireDispatch> catalogs(ClassNode node, Function<String, ClassNode> classes) {
        Map<String, WireDispatch> result=new LinkedHashMap<>();
        collectCatalogs(node,classes,new HashSet<>(),result);
        return result;
    }
    private static void collectCatalogs(ClassNode node,Function<String,ClassNode> classes,Set<String> active,Map<String,WireDispatch> result) {
        if(node==null || node.name.equals(WireSchema.BYTE_CODECS) || !active.add(node.name))return;
        boolean dispatched=node.methods.stream().filter(m->m.name.equals("<clinit>")).anyMatch(m->real(m).stream()
                .anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("dispatch")&&call.owner.equals("net/minecraft/network/codec/StreamCodec")));
        if(dispatched) scan(node,classes).ifPresent(catalog -> result.put(node.name,catalog));
        for(var method:node.methods) if(method.name.equals("<clinit>")) for(var instruction:method.instructions)
            if(instruction instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.desc.equals(WireSchema.STREAM_CODEC))
                collectCatalogs(classes.apply(field.owner),classes,active,result);
    }
    private static boolean capturesInput(ClassNode owner,String descriptor,String field,int parameter,
                                          Function<String,ClassNode> classes,Set<String> active) {
        if(!active.add(descriptor))return false;
        try {
            var method=constructorBody(owner,descriptor);var frames=BytecodeArguments.frames(owner,method);
            if(frames==null || !method.tryCatchBlocks.isEmpty())return false;
            int slot=1;var parameters=Type.getArgumentTypes(descriptor);
            for(int n=0;n<parameter;n++)slot+=parameters[n].getSize();
            boolean captured = false;
            for(int n=0;n<method.instructions.size();n++) {
                var instruction=method.instructions.get(n);var frame=frames[n];
                int opcode = instruction.getOpcode();
                if (opcode >= 0 && !(instruction instanceof VarInsnNode || instruction instanceof FieldInsnNode
                        || instruction instanceof MethodInsnNode || instruction instanceof LdcInsnNode
                        || instruction instanceof IntInsnNode && Set.of(Opcodes.BIPUSH, Opcodes.SIPUSH).contains(opcode))
                        && !Set.of(Opcodes.RETURN, Opcodes.CHECKCAST, Opcodes.ACONST_NULL, Opcodes.ICONST_M1,
                        Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4,
                        Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1,
                        Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1).contains(opcode)) return false;
                if (instruction instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTFIELD && !store.owner.equals(owner.name)) return false;
                if(instruction instanceof FieldInsnNode store&&store.getOpcode()==Opcodes.PUTFIELD&&store.owner.equals(owner.name)&&store.name.equals(field)) {
                    if (captured || frame == null || !parameterLoad(frame.getStack(frame.getStackSize()-1),slot)) return false;
                    captured = true;
                }
                if(instruction instanceof MethodInsnNode call) {
                    if(call.name.equals("<init>")&&call.owner.equals(owner.name)) {
                        for(int argument=0;argument<Type.getArgumentTypes(call.desc).length;argument++) {
                            var input=BytecodeArguments.argument(frame,Type.getArgumentTypes(call.desc).length,argument);
                            if(parameterLoad(input,slot)&&capturesInput(owner,call.desc,field,argument,classes,active)) captured = true;
                        }
                    } else if(!(call.name.equals("<init>")&&Set.of("java/lang/Record","java/lang/Object").contains(call.owner))
                            && !(call.owner.equals("java/util/List")&&Set.of("getLast","getFirst","size","isEmpty").contains(call.name))
                            && !pureGetter(call,classes))return false;
                }
            }
            return captured;
        } finally {active.remove(descriptor);}
    }
    private static boolean parameterLoad(org.objectweb.asm.tree.analysis.SourceValue value,int slot) {
        return value!=null&&!value.insns.isEmpty()&&value.insns.stream().allMatch(i->i instanceof VarInsnNode load
                && Set.of(Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD).contains(load.getOpcode()) && load.var==slot);
    }
    private static boolean pureGetter(MethodInsnNode call,Function<String,ClassNode> classes) {
        if(Type.getArgumentTypes(call.desc).length!=0)return false;
        var owner=classes.apply(call.owner);if(owner==null)return false;
        var method=owner.methods.stream().filter(m->m.name.equals(call.name)&&m.desc.equals(call.desc)).findFirst();
        if(method.isEmpty())return false;var code=real(method.get());
        return code.size()==3&&code.get(0) instanceof VarInsnNode load&&load.var==0&&code.get(1) instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD;
    }

    private static void gather(WireSchema schema, Function<String, ClassNode> classes, Map<String, WireSchema> result) {
        if (result.putIfAbsent(schema.owner(), schema) != null) return;
        for (var field : schema.fields()) {
            var type = classes.apply(field.codec().owner());
            if (type != null) WireSchema.scan(type, classes).ifPresent(child -> gather(child, classes, result));
        }
    }
    private static MethodNode constructorBody(ClassNode node, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Handle handle(InvokeDynamicInsnNode dynamic) {
        return Arrays.stream(dynamic.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).findFirst().orElseThrow();
    }
    private static String tagEncoder(ClassNode enumeration) {
        for (var method : enumeration.methods) {
            if (!method.name.equals("<clinit>")) continue;
            var code = real(method);
            for (int i = 1; i < code.size(); i++) if (code.get(i) instanceof MethodInsnNode call
                    && call.owner.equals(WireSchema.BYTE_CODECS) && Set.of("idMapper", "enumCodec").contains(call.name)
                    && code.get(i-1) instanceof InvokeDynamicInsnNode encoder) {
                var handle = handle(encoder);
                if (handle.getName().equals("ordinal") && Set.of(enumeration.name, "java/lang/Enum").contains(handle.getOwner())) return "ordinal";
                if (WireMigration.enumIdCodec(enumeration)) return handle.getName();
            }
        }
        return null;
    }
    private static Map<String,Integer> ordinalIds(ClassNode node) {
        Map<String,Integer> ids = new LinkedHashMap<>();
        for (var method : node.methods) if (method.name.equals("<clinit>")) {
            var code = real(method);
            for (int i=0; i<code.size(); i++) if (code.get(i) instanceof TypeInsnNode allocation && allocation.getOpcode()==Opcodes.NEW && allocation.desc.equals(node.name)) {
                if (!(code.get(i+2) instanceof LdcInsnNode name) || !(name.cst instanceof String text)) return Map.of();
                AbstractInsnNode id = code.get(i+3); Integer number = id instanceof IntInsnNode v ? v.operand : id instanceof LdcInsnNode v && v.cst instanceof Integer n ? n
                        : id.getOpcode()>=Opcodes.ICONST_0 && id.getOpcode()<=Opcodes.ICONST_5 ? id.getOpcode()-Opcodes.ICONST_0 : null;
                if (number==null) return Map.of(); ids.put(text,number);
            }
        }
        return ids;
    }
    private static List<AbstractInsnNode> real(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
    }
}
