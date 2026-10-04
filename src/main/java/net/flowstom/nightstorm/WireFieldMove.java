package net.flowstom.nightstorm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;

/** Proves scalar suffixes moved out of a nested codec into a collection-element wrapper. */
record WireFieldMove(String element, WireSchema wrapper, List<Moved> fields) {
    record Moved(String name, String descriptor, String javaType, String codec, String defaultValue) { }
    static Optional<WireFieldMove> scan(String element, ClassNode wrapper, Function<String,ClassNode> baseline,
                                       Function<String,ClassNode> target) {
        var schema = WireSchema.scan(wrapper, target);
        if (schema.isEmpty() || schema.get().fields().size() < 2) return Optional.empty();
        var first = schema.get().fields().getFirst();
        if (!schema.get().components().get(first.component()).descriptor().equals("L" + element + ";")
                || !first.codec().owner().equals(element) || !first.codec().operations().isEmpty()) return Optional.empty();
        List<Moved> moved = new ArrayList<>();
        for (var field : schema.get().fields().subList(1, schema.get().fields().size())) {
            var component = schema.get().components().get(field.component());
            var codec = PacketCodecScanner.translate(field.codec());
            if (!codec.supported() || !codec.vanillaDescriptor().equals(component.descriptor()) || component.descriptor().length() != 1) return Optional.empty();
            moved.add(new Moved(component.name(), component.descriptor(), codec.javaType(), codec.networkType(), zero(component.descriptor())));
        }
        List<ClassNode> matches = new ArrayList<>();
        visit(element, baseline, target, new HashSet<>(), moved, matches);
        if (matches.size() != 1) return Optional.empty();
        return Optional.of(new WireFieldMove(element, schema.get(), List.copyOf(moved)));
    }
    private static void visit(String owner, Function<String,ClassNode> baseline, Function<String,ClassNode> target,
                              Set<String> visited, List<Moved> moved, List<ClassNode> matches) {
        if (!visited.add(owner)) return;
        ClassNode before = baseline.apply(owner), after = target.apply(owner);
        if (before == null || after == null) return;
        if (removed(before, after, moved)) matches.add(before);
        for (var field : before.fields) {
            if ((field.access & Opcodes.ACC_STATIC) != 0) continue;
            String signature = field.signature == null ? field.desc : field.signature;
            var types = java.util.regex.Pattern.compile("L([^;<]+)").matcher(signature);
            while (types.find()) visit(types.group(1), baseline, target, visited, moved, matches);
        }
    }
    private static boolean removed(ClassNode before, ClassNode after, List<Moved> moved) {
        for (var field : moved) {
            if (before.fields.stream().noneMatch(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.name.equals(field.name()) && f.desc.equals(field.descriptor()))
                    || after.fields.stream().anyMatch(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.name.equals(field.name()))
                    || before.methods.stream().filter(m -> m.name.equals("<init>")).anyMatch(m -> Arrays.stream(m.instructions.toArray())
                    .anyMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals(field.name())))) return false;
        }
        for (var constructor : before.methods) if (constructor.name.equals("<init>")) {
            for (var instruction : constructor.instructions) if (instruction instanceof MethodInsnNode call
                    && !(call.name.equals("<init>") && Set.of(before.name,"java/lang/Object","java/lang/Record").contains(call.owner))) return false;
        }
        boolean writer = false, reader = false;
        for (var method : before.methods) {
            var code = real(method);
            if (code.size() < moved.size()*4+1) continue;
            if (code.getLast().getOpcode() == Opcodes.RETURN) {
                int start = code.size()-1;
                for (int n=moved.size()-1; n>=0; n--) {
                    if (code.get(start-1).getOpcode()==Opcodes.POP) start--;
                    if (start<4 || !(code.get(start-1) instanceof MethodInsnNode call) || !operation(call, moved.get(n), false)
                            || !(code.get(start-2) instanceof FieldInsnNode field) || !field.name.equals(moved.get(n).name()) || !field.owner.equals(before.name)
                            || !(code.get(start-3) instanceof VarInsnNode self) || self.getOpcode()!=Opcodes.ALOAD || self.var!=0
                            || !(code.get(start-4) instanceof VarInsnNode buffer) || buffer.getOpcode()!=Opcodes.ALOAD) { start=-1; break; }
                    start-=4;
                }
                if (start>=0) writer=true;
            }
            // A reader can bind the suffix through an assignment-only setter.
            for (int i=0; i<code.size(); i++) {
                if (!(code.get(i) instanceof MethodInsnNode call) || !call.owner.equals(before.name)) continue;
                var setter = before.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst();
                if (setter.isEmpty()) continue;
                var assignments = ConstructorMapping.assignments(before,setter.get());
                if (assignments.isEmpty() || assignments.get().size()!=moved.size()) continue;
                boolean valid=true;
                for (int n=0;n<moved.size();n++) {
                    var field=moved.get(n);
                    if (!new ConstructorMapping.Parameter(n,field.descriptor()).equals(assignments.get().get(field.name()))) valid=false;
                    int position=i-2*(moved.size()-n)+1;
                    if (position<0 || !(code.get(position) instanceof MethodInsnNode read) || !operation(read,field,true)) valid=false;
                }
                if (valid) reader=true;
            }
        }
        return writer && reader;
    }
    private static boolean operation(MethodInsnNode call, Moved field, boolean read) {
        var name = switch(field.descriptor()) {case "F"->"Float";case "D"->"Double";case "I"->"Int";case "J"->"Long";case "Z"->"Boolean";case "B"->"Byte";case "S"->"Short";default->"";};
        String codec=field.codec().substring(field.codec().lastIndexOf('.')+1);
        if (codec.equals("VAR_INT")) name="VarInt"; else if(codec.equals("VAR_LONG")) name="VarLong";
        return call.owner.endsWith("FriendlyByteBuf") && call.name.equals((read?"read":"write")+name);
    }
    private static String zero(String descriptor) {
        return switch(descriptor) {case "Z"->"false";case "F"->"0.0f";case "D"->"0.0d";case "J"->"0L";case "B"->"(byte) 0";case "S"->"(short) 0";default->"0";};
    }
    private static List<AbstractInsnNode> real(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode()>=0).toList();
    }
}
