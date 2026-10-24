package net.flowstom.nightstorm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;
import java.util.function.Function;

/** Correlates an accessor's consumers with pure boolean/enum decisions in the two runtimes. */
final class BooleanEnumProof {
    record Ids(int falseId, int trueId) {}
    private record Pair(ClassNode before, MethodNode oldMethod, ClassNode after, MethodNode newMethod, int argument) {}
    private record Field(String owner, String name, String descriptor) {}
    private record EnumValue(String name) {}
    private static final Object THIS = new Object();

    private BooleanEnumProof() {}

    static Optional<Ids> infer(ClassNode oldPacket, RecordComponentNode oldField,
                               ClassNode newPacket, RecordComponentNode newField, ClassNode enumeration,
                               Function<String, ClassNode> baseline, Function<String, ClassNode> target,
                               Collection<ClassNode> callers) {
        final var ids = PacketMigrationScanner.enumIds(enumeration);
        if (ids.size() != 2 || new HashSet<>(ids.values()).size() != 2) return Optional.empty();
        final var pending = new ArrayDeque<Pair>();
        for (var owner : callers) {
            var oldOwner = baseline.apply(owner.name);
            if (oldOwner == null) continue;
            for (var method : owner.methods) {
                if (!callsAccessor(method, newPacket.name, newField)) continue;
                var oldMethod = find(oldOwner, method.name, method.desc);
                if (oldMethod == null || !callsAccessor(oldMethod, oldPacket.name, oldField)) continue;
                pending.addAll(forwarded(new Pair(oldOwner, oldMethod, owner, method, -1), oldPacket, oldField,
                        newPacket, newField, enumeration.name, baseline, target));
            }
        }
        pending.addAll(constructorSeeds(oldPacket, oldField, newPacket, newField, enumeration.name, baseline, target, callers));
        final var visited = new HashSet<String>();
        Ids result = null;
        while (!pending.isEmpty()) {
            var pair = pending.removeFirst();
            String key = pair.after.name + '#' + pair.newMethod.name + pair.newMethod.desc + ':' + pair.argument;
            if (!visited.add(key)) continue;
            var proof = decision(pair, enumeration, ids, target);
            if (proof.isPresent()) {
                if (result != null && !result.equals(proof.get())) throw new IllegalStateException("Conflicting boolean/enum behavior at " + key);
                result = proof.get();
            } else {
                pending.addAll(forwarded(pair, oldPacket, oldField, newPacket, newField, enumeration.name, baseline, target));
                pending.addAll(upward(pair, enumeration.name, baseline, target, callers));
            }
        }
        return Optional.ofNullable(result);
    }

    /** Methods that pass one of their parameters straight into the component's constructor slot. */
    private static List<Pair> constructorSeeds(ClassNode oldPacket, RecordComponentNode oldField, ClassNode newPacket,
                                                RecordComponentNode newField, String enumOwner,
                                                Function<String, ClassNode> baseline, Function<String, ClassNode> target,
                                                Collection<ClassNode> callers) {
        int oldIndex = constructorParameter(oldPacket, oldField);
        int newIndex = constructorParameter(newPacket, newField);
        if (oldIndex < 0 || newIndex < 0) return List.of();
        String enumDescriptor = "L" + enumOwner + ";";
        var result = new ArrayList<Pair>();
        for (var owner : callers) {
            var oldOwner = baseline.apply(owner.name);
            if (oldOwner == null) continue;
            for (var method : owner.methods) {
                var newFrames = BytecodeArguments.frames(owner, method);
                if (newFrames == null) continue;
                for (int n = 0; n < method.instructions.size(); n++) {
                    if (!(method.instructions.get(n) instanceof MethodInsnNode call) || !call.name.equals("<init>")
                            || !call.owner.equals(newPacket.name)) continue;
                    var arguments = Type.getArgumentTypes(call.desc);
                    if (newIndex >= arguments.length || !arguments[newIndex].getDescriptor().equals(enumDescriptor)) continue;
                    Integer parameter = parameterOf(method, BytecodeArguments.argument(newFrames[n], arguments.length, newIndex));
                    if (parameter == null) continue;
                    var methodArguments = Type.getArgumentTypes(method.desc);
                    if (parameter >= methodArguments.length || !methodArguments[parameter].getDescriptor().equals(enumDescriptor)) continue;
                    String oldDescriptor = substitute(method.desc, parameter, Type.BOOLEAN_TYPE);
                    var oldMethod = find(oldOwner, method.name, oldDescriptor);
                    if (oldMethod == null || !constructorArgumentFromParameter(oldOwner, oldMethod, oldPacket.name, oldIndex, parameter)) continue;
                    result.add(new Pair(oldOwner, oldMethod, owner, method, parameter));
                }
            }
        }
        return result;
    }

    /**
     * Walks callers of a paired method. A supertype call counts only when every other implementation ignores
     * the argument. A computed value passed onward is followed into every other call that receives that same value.
     */
    private static List<Pair> upward(Pair pair, String enumOwner, Function<String, ClassNode> baseline,
                                      Function<String, ClassNode> target, Collection<ClassNode> callers) {
        if (pair.argument < 0) return List.of();
        var parameters = Type.getArgumentTypes(pair.newMethod.desc);
        if (pair.argument >= parameters.length || !parameters[pair.argument].getDescriptor().equals("L" + enumOwner + ";")) return List.of();
        var baselineClasses = new ArrayList<ClassNode>();
        for (var node : callers) {
            var old = baseline.apply(node.name);
            if (old != null) baselineClasses.add(old);
        }
        var result = new ArrayList<Pair>();
        for (var owner : callers) {
            var oldOwner = baseline.apply(owner.name);
            if (oldOwner == null) continue;
            for (var method : owner.methods) {
                var sites = new ArrayList<Integer>();
                for (int n = 0; n < method.instructions.size(); n++) {
                    if (!(method.instructions.get(n) instanceof MethodInsnNode call)) continue;
                    if (!call.name.equals(pair.newMethod.name) || !call.desc.equals(pair.newMethod.desc)) continue;
                    if (!resolvesTo(pair.after, pair.newMethod, call.owner, pair.argument, target, callers)) continue;
                    sites.add(n);
                }
                if (sites.isEmpty()) continue;
                var newFrames = BytecodeArguments.frames(owner, method);
                if (newFrames == null) continue;
                Integer parameter = null;
                SourceValue subject = null;
                int subjectAt = -1;
                for (int site : sites) {
                    var call = (MethodInsnNode) method.instructions.get(site);
                    var passed = BytecodeArguments.argument(newFrames[site], Type.getArgumentTypes(call.desc).length, pair.argument);
                    if (passed == null || passed.insns.isEmpty()) continue;
                    Integer found = parameterOf(method, passed);
                    if (found != null) {
                        parameter = found;
                        subject = passed;
                        subjectAt = site;
                        break;
                    }
                    if (subject == null) {
                        subject = passed;
                        subjectAt = site;
                    }
                }
                if (subject == null) continue;
                var methodArguments = Type.getArgumentTypes(method.desc);
                if (parameter != null && (parameter >= methodArguments.length
                        || !methodArguments[parameter].getDescriptor().equals("L" + enumOwner + ";"))) continue;
                String oldDescriptor = parameter == null ? method.desc : substitute(method.desc, parameter, Type.BOOLEAN_TYPE);
                var oldMethod = find(oldOwner, method.name, oldDescriptor);
                var oldFrames = oldMethod == null ? null : BytecodeArguments.frames(oldOwner, oldMethod);
                int oldSite = oldMethod == null || oldFrames == null ? -1
                        : oldCall(oldMethod, oldFrames, pair.before, pair.oldMethod, pair.argument, parameter, baseline, baselineClasses);
                if (oldSite < 0) continue;
                if (parameter != null) result.add(new Pair(oldOwner, oldMethod, owner, method, parameter));
                var oldCall = (MethodInsnNode) oldMethod.instructions.get(oldSite);
                var oldSubject = BytecodeArguments.argument(oldFrames[oldSite], Type.getArgumentTypes(oldCall.desc).length, pair.argument);
                result.addAll(siblings(owner, method, newFrames, subject, subjectAt, oldOwner, oldMethod, oldFrames, oldSubject,
                        enumOwner, baseline, target));
            }
        }
        return result;
    }

    private static List<Pair> siblings(ClassNode owner, MethodNode method, Frame<SourceValue>[] newFrames, SourceValue subject,
                                        int subjectAt, ClassNode oldOwner, MethodNode oldMethod, Frame<SourceValue>[] oldFrames,
                                        SourceValue oldSubject, String enumOwner, Function<String, ClassNode> baseline,
                                        Function<String, ClassNode> target) {
        if (oldSubject == null || oldSubject.insns.isEmpty()) return List.of();
        var result = new ArrayList<Pair>();
        for (int n = 0; n < method.instructions.size(); n++) {
            if (n == subjectAt || !(method.instructions.get(n) instanceof MethodInsnNode call)) continue;
            var arguments = Type.getArgumentTypes(call.desc);
            for (int a = 0; a < arguments.length; a++) {
                if (!arguments[a].getDescriptor().equals("L" + enumOwner + ";")) continue;
                var passed = BytecodeArguments.argument(newFrames[n], arguments.length, a);
                if (passed == null || !passed.insns.equals(subject.insns)) continue;
                String oldDescriptor = substitute(call.desc, a, Type.BOOLEAN_TYPE);
                if (!sameProducer(oldMethod, oldFrames, call.owner, call.name, oldDescriptor, a, oldSubject)) continue;
                var newCallee = target.apply(call.owner);
                var oldCallee = baseline.apply(call.owner);
                if (newCallee == null || oldCallee == null) continue;
                var newMethod = find(newCallee, call.name, call.desc);
                var paired = find(oldCallee, call.name, oldDescriptor);
                if (newMethod != null && paired != null) result.add(new Pair(oldCallee, paired, newCallee, newMethod, a));
            }
        }
        return result;
    }

    private static boolean sameProducer(MethodNode method, Frame<SourceValue>[] frames, String owner, String name,
                                         String descriptor, int argument, SourceValue expected) {
        for (int n = 0; n < method.instructions.size(); n++) {
            if (!(method.instructions.get(n) instanceof MethodInsnNode call) || !call.owner.equals(owner)
                    || !call.name.equals(name) || !call.desc.equals(descriptor)) continue;
            var passed = BytecodeArguments.argument(frames[n], Type.getArgumentTypes(call.desc).length, argument);
            if (passed != null && passed.insns.equals(expected.insns)) return true;
        }
        return false;
    }

    private static int oldCall(MethodNode method, Frame<SourceValue>[] frames, ClassNode implementation, MethodNode target,
                                int argument, Integer parameter, Function<String, ClassNode> classes, Collection<ClassNode> all) {
        int found = -1;
        for (int n = 0; n < method.instructions.size(); n++) {
            if (!(method.instructions.get(n) instanceof MethodInsnNode call) || !call.name.equals(target.name)
                    || !call.desc.equals(target.desc)) continue;
            if (!resolvesTo(implementation, target, call.owner, argument, classes, all)) continue;
            var passed = BytecodeArguments.argument(frames[n], Type.getArgumentTypes(call.desc).length, argument);
            if (parameter != null && !origin(passed, method, parameter, implementation, null)) continue;
            if (parameter == null && (passed == null || passed.insns.isEmpty())) continue;
            if (found >= 0) return -1;
            found = n;
        }
        return found;
    }

    private static boolean resolvesTo(ClassNode implementation, MethodNode method, String callOwner, int argument,
                                       Function<String, ClassNode> classes, Collection<ClassNode> all) {
        if (!implementation.name.equals(callOwner) && !descendsFrom(implementation, callOwner, classes)) return false;
        int users = 0;
        for (var candidate : all) {
            if (!candidate.name.equals(callOwner) && !descendsFrom(candidate, callOwner, classes)) continue;
            var body = find(candidate, method.name, method.desc);
            if (body == null || !loadsParameter(body, argument)) continue;
            if (!candidate.name.equals(implementation.name)) return false;
            users++;
        }
        return users == 1;
    }

    private static boolean descendsFrom(ClassNode type, String ancestor, Function<String, ClassNode> classes) {
        var seen = new HashSet<String>();
        String name = type.name;
        ClassNode node = type;
        while (name != null && seen.add(name)) {
            if (name.equals(ancestor)) return true;
            if (node == null) return false;
            name = node.superName;
            node = name == null || classes == null ? null : classes.apply(name);
        }
        return false;
    }

    private static boolean loadsParameter(MethodNode method, int argument) {
        int slot = parameterSlot(method, argument);
        for (var instruction : method.instructions) {
            if (instruction instanceof VarInsnNode load && load.var == slot
                    && (load.getOpcode() == Opcodes.ILOAD || load.getOpcode() == Opcodes.ALOAD)) return true;
        }
        return false;
    }

    private static boolean constructorArgumentFromParameter(ClassNode owner, MethodNode method, String packet,
                                                             int constructorIndex, int parameter) {
        var frames = BytecodeArguments.frames(owner, method);
        if (frames == null) return false;
        for (int n = 0; n < method.instructions.size(); n++) {
            if (!(method.instructions.get(n) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(packet) || !call.name.equals("<init>")) continue;
            var arguments = Type.getArgumentTypes(call.desc);
            if (constructorIndex >= arguments.length || !arguments[constructorIndex].equals(Type.BOOLEAN_TYPE)) continue;
            if (origin(BytecodeArguments.argument(frames[n], arguments.length, constructorIndex), method, parameter, owner, null)) return true;
        }
        return false;
    }

    private static int constructorParameter(ClassNode packet, RecordComponentNode field) {
        int found = -1;
        for (var method : packet.methods) {
            if (!method.name.equals("<init>")) continue;
            var arguments = Type.getArgumentTypes(method.desc);
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTFIELD
                        || !put.owner.equals(packet.name) || !put.name.equals(field.name) || !put.desc.equals(field.descriptor)) continue;
                var value = previous(instruction);
                var receiver = previous(value);
                if (!(value instanceof VarInsnNode load) || !(receiver instanceof VarInsnNode self)
                        || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                        || (load.getOpcode() != Opcodes.ILOAD && load.getOpcode() != Opcodes.ALOAD)) continue;
                int slot = 1;
                int index = -1;
                for (int i = 0; i < arguments.length; i++) {
                    if (slot == load.var) index = i;
                    slot += arguments[i].getSize();
                }
                if (index < 0 || !arguments[index].getDescriptor().equals(field.descriptor)) continue;
                if (found >= 0 && found != index) return -1;
                found = index;
            }
        }
        return found;
    }

    private static Integer parameterOf(MethodNode method, SourceValue value) {
        if (value == null || value.insns.isEmpty()) return null;
        Integer slot = null;
        for (var instruction : value.insns) {
            if (!(instruction instanceof VarInsnNode load)
                    || (load.getOpcode() != Opcodes.ILOAD && load.getOpcode() != Opcodes.ALOAD)) return null;
            if (slot != null && slot != load.var) return null;
            slot = load.var;
        }
        int parameter = -1;
        int current = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        var arguments = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < arguments.length; i++) {
            if (current == slot) parameter = i;
            current += arguments[i].getSize();
        }
        return parameter < 0 ? null : parameter;
    }

    private static String substitute(String descriptor, int index, Type replacement) {
        var arguments = Type.getArgumentTypes(descriptor);
        arguments[index] = replacement;
        return Type.getMethodDescriptor(Type.getReturnType(descriptor), arguments);
    }

    private static Optional<Ids> decision(Pair pair, ClassNode enumeration, Map<String, Integer> ids,
                                           Function<String, ClassNode> target) {
        Object whenFalse = evaluate(pair.before, pair.oldMethod, pair.argument, 0, enumeration, target);
        Object whenTrue = evaluate(pair.before, pair.oldMethod, pair.argument, 1, enumeration, target);
        if (whenFalse == null || whenTrue == null || whenFalse.equals(whenTrue)) return Optional.empty();
        Integer falseId = null, trueId = null;
        for (var entry : ids.entrySet()) {
            Object value = evaluate(pair.after, pair.newMethod, pair.argument, new EnumValue(entry.getKey()), enumeration, target);
            if (whenFalse.equals(value)) falseId = entry.getValue();
            else if (whenTrue.equals(value)) trueId = entry.getValue();
            else return Optional.empty();
        }
        return falseId == null || trueId == null ? Optional.empty() : Optional.of(new Ids(falseId, trueId));
    }

    private static List<Pair> forwarded(Pair pair, ClassNode oldPacket, RecordComponentNode oldField,
                                         ClassNode newPacket, RecordComponentNode newField, String enumOwner,
                                         Function<String, ClassNode> baseline, Function<String, ClassNode> target) {
        var oldFrames = BytecodeArguments.frames(pair.before, pair.oldMethod);
        var newFrames = BytecodeArguments.frames(pair.after, pair.newMethod);
        if (oldFrames == null || newFrames == null) return List.of();
        var result = new ArrayList<Pair>();
        for (int n = 0; n < pair.newMethod.instructions.size(); n++) {
            if (!(pair.newMethod.instructions.get(n) instanceof MethodInsnNode call)) continue;
            var newArgs = Type.getArgumentTypes(call.desc);
            for (int a = 0; a < newArgs.length; a++) {
                if (!newArgs[a].getDescriptor().equals("L" + enumOwner + ";")
                        || !origin(BytecodeArguments.argument(newFrames[n], newArgs.length, a), pair.newMethod, pair.argument, newPacket, newField)) continue;
                var oldArgs = newArgs.clone();
                oldArgs[a] = Type.BOOLEAN_TYPE;
                String oldDescriptor = Type.getMethodDescriptor(Type.getReturnType(call.desc), oldArgs);
                boolean found = false;
                for (int o = 0; o < pair.oldMethod.instructions.size(); o++) {
                    if (!(pair.oldMethod.instructions.get(o) instanceof MethodInsnNode oldCall)
                            || !oldCall.owner.equals(call.owner) || !oldCall.name.equals(call.name) || !oldCall.desc.equals(oldDescriptor)) continue;
                    if (origin(BytecodeArguments.argument(oldFrames[o], oldArgs.length, a), pair.oldMethod, pair.argument, oldPacket, oldField)) found = true;
                }
                if (!found) continue;
                var oldOwner = baseline.apply(call.owner);
                var newOwner = target.apply(call.owner);
                if (oldOwner == null || newOwner == null) continue;
                var oldMethod = find(oldOwner, call.name, oldDescriptor);
                var newMethod = find(newOwner, call.name, call.desc);
                if (oldMethod != null && newMethod != null) result.add(new Pair(oldOwner, oldMethod, newOwner, newMethod, a));
            }
        }
        return result;
    }

    private static boolean origin(SourceValue value, MethodNode method, int argument,
                                    ClassNode packet, RecordComponentNode field) {
        if (value == null || value.insns.isEmpty()) {
            return false;
        }
        if (argument < 0) return value.insns.stream().allMatch(i -> i instanceof MethodInsnNode call
                && call.owner.equals(packet.name) && call.name.equals(field.name) && call.desc.equals("()" + field.descriptor));
        int slot = parameterSlot(method, argument);
        return value.insns.stream().allMatch(i -> i instanceof VarInsnNode load && load.var == slot
                && (load.getOpcode() == Opcodes.ILOAD || load.getOpcode() == Opcodes.ALOAD));
    }

    private static int parameterSlot(MethodNode method, int argument) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        var args = Type.getArgumentTypes(method.desc);
        for (int i = 0; i < argument; i++) slot += args[i].getSize();
        return slot;
    }

    private static boolean callsAccessor(MethodNode method, String owner, RecordComponentNode field) {
        for (var i : method.instructions) if (i instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(field.name) && call.desc.equals("()" + field.descriptor)) return true;
        return false;
    }

    private static MethodNode find(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }

    /** Executes only pure selection instructions; unsupported computation cannot establish a correspondence. */
    private static Object evaluate(ClassNode owner, MethodNode method, int argument, Object selected,
                                     ClassNode enumeration, Function<String, ClassNode> classes) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        var locals = new HashMap<Integer, Object>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.put(0, THIS);
        locals.put(parameterSlot(method, argument), selected);
        var stack = new ArrayList<Object>();
        var code = method.instructions.toArray();
        try {
            for (int pc = 0, steps = 0; pc < code.length && steps++ < 256; pc++) {
                var i = code[pc];
                int op = i.getOpcode();
                if (op < 0 || op == Opcodes.NOP) continue;
                if (i instanceof VarInsnNode load) {
                    if (op == Opcodes.ALOAD || op == Opcodes.ILOAD) {
                        Object value = locals.get(load.var);
                        if (value == null) return null;
                        stack.add(value);
                    } else if (op == Opcodes.ASTORE || op == Opcodes.ISTORE) locals.put(load.var, pop(stack));
                    else return null;
                } else if (i instanceof FieldInsnNode field) {
                    if (op == Opcodes.GETFIELD && pop(stack) == THIS) stack.add(new Field(field.owner, field.name, field.desc));
                    else if (op == Opcodes.GETSTATIC && field.owner.equals(enumeration.name) && field.desc.equals("L" + enumeration.name + ";")) stack.add(new EnumValue(field.name));
                    else if (op == Opcodes.GETSTATIC && field.desc.equals("[I")) stack.add(new Field(field.owner, field.name, field.desc));
                    else return null;
                } else if (i instanceof MethodInsnNode call) {
                    if (!call.name.equals("ordinal") || !call.desc.equals("()I") || !call.owner.equals(enumeration.name)
                            || !(pop(stack) instanceof EnumValue value)) return null;
                    Integer ordinal = ordinal(enumeration, value.name);
                    if (ordinal == null) return null;
                    stack.add(ordinal);
                } else if (op == Opcodes.IALOAD) {
                    int ordinal = (Integer) pop(stack);
                    Field array = (Field) pop(stack);
                    Integer value = switchEntry(classes.apply(array.owner), array, enumeration, ordinal);
                    if (value == null) return null;
                    stack.add(value);
                } else if (i instanceof JumpInsnNode jump) {
                    boolean take;
                    if (op == Opcodes.GOTO) take = true;
                    else if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
                        int value = (Integer) pop(stack);
                        take = op == Opcodes.IFEQ ? value == 0 : value != 0;
                    } else if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                        boolean equal = pop(stack).equals(pop(stack));
                        take = op == Opcodes.IF_ACMPEQ ? equal : !equal;
                    } else return null;
                    if (take) pc = method.instructions.indexOf(jump.label);
                } else if (i instanceof LookupSwitchInsnNode select) {
                    int index = select.keys.indexOf((Integer) pop(stack));
                    pc = method.instructions.indexOf(index < 0 ? select.dflt : select.labels.get(index));
                } else if (i instanceof TableSwitchInsnNode select) {
                    int index = (Integer) pop(stack) - select.min;
                    pc = method.instructions.indexOf(index < 0 || index >= select.labels.size() ? select.dflt : select.labels.get(index));
                } else if (op == Opcodes.ARETURN || op == Opcodes.IRETURN) return stack.size() == 1 ? stack.getFirst() : null;
                else {
                    Integer constant = integer(i);
                    if (constant == null) return null;
                    stack.add(constant);
                }
            }
        } catch (RuntimeException ignored) { return null; }
        return null;
    }

    private static Object pop(List<Object> stack) { return stack.removeLast(); }

    private static Integer ordinal(ClassNode enumeration, String constant) {
        var clinit = find(enumeration, "<clinit>", "()V");
        if (clinit == null) return null;
        Integer result = null;
        for (var instruction : clinit.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(enumeration.name) || !field.name.equals(constant)) continue;
            var callNode = previous(instruction);
            if (!(callNode instanceof MethodInsnNode call) || !call.name.equals("<init>") || !call.owner.equals(enumeration.name)) return null;
            var args = Type.getArgumentTypes(call.desc);
            if (args.length < 2 || !args[0].getDescriptor().equals("Ljava/lang/String;") || !args[1].equals(Type.INT_TYPE)) return null;
            var value = previous(call);
            for (int a = args.length - 1; a > 1; a--) value = previous(value);
            Integer found = integer(value);
            if (found == null || result != null && !result.equals(found)) return null;
            result = found;
        }
        return result;
    }

    private static Integer switchEntry(ClassNode owner, Field array, ClassNode enumeration, int selectedOrdinal) {
        if (owner == null) return null;
        var clinit = find(owner, "<clinit>", "()V");
        if (clinit == null) return null;
        Integer result = null;
        for (var i : clinit.instructions) {
            if (i.getOpcode() != Opcodes.IASTORE) continue;
            var value = previous(i);
            var callNode = previous(value);
            var constantNode = previous(callNode);
            var arrayNode = previous(constantNode);
            if (!(arrayNode instanceof FieldInsnNode get) || !new Field(get.owner, get.name, get.desc).equals(array)) continue;
            if (!(constantNode instanceof FieldInsnNode constant) || constant.getOpcode() != Opcodes.GETSTATIC
                    || !constant.owner.equals(enumeration.name) || !(callNode instanceof MethodInsnNode call)
                    || !call.owner.equals(enumeration.name) || !call.name.equals("ordinal") || !call.desc.equals("()I")) return null;
            Integer ordinal = ordinal(enumeration, constant.name);
            if (ordinal == null) return null;
            if (ordinal == selectedOrdinal) {
                Integer found = integer(value);
                if (found == null || result != null && !result.equals(found)) return null;
                result = found;
            }
        }
        return result;
    }

    private static AbstractInsnNode previous(AbstractInsnNode i) {
        if (i == null) return null;
        do { i = i.getPrevious(); } while (i != null && i.getOpcode() < 0);
        return i;
    }

    private static Integer integer(AbstractInsnNode i) {
        if (i == null) return null;
        if (i instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        if (i instanceof IntInsnNode push && (push.getOpcode() == Opcodes.BIPUSH || push.getOpcode() == Opcodes.SIPUSH)) return push.operand;
        return i.getOpcode() >= Opcodes.ICONST_M1 && i.getOpcode() <= Opcodes.ICONST_5 ? i.getOpcode() - Opcodes.ICONST_0 : null;
    }
}
