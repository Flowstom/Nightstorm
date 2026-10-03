package net.flowstom.nightstorm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.*;
import java.util.jar.JarFile;

/** Resolves PacketType constructor flow arguments instead of interpreting packet field names. */
final class PacketDirections {
    private static final String TYPE = "net/minecraft/network/protocol/PacketType";
    private static final String FLOW = "net/minecraft/network/protocol/PacketFlow";
    private static final Object UNKNOWN = new Object();
    private final JarFile jar;
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, Map<String, String>> directions = new HashMap<>();

    PacketDirections(JarFile jar) { this.jar = jar; }

    String direction(String packetType) {
        int separator = packetType.lastIndexOf('#');
        String owner = packetType.substring(0, separator).replace('.', '/');
        String field = packetType.substring(separator + 1);
        String direction = directions.computeIfAbsent(owner, this::scan).get(field);
        if (direction == null) throw new IllegalStateException("Cannot prove protocol direction for " + packetType);
        return direction;
    }

    private Map<String, String> scan(String owner) {
        var result = new LinkedHashMap<String, String>();
        var node = read(owner);
        var initializer = node.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
        interpret(owner, initializer, List.of(), result, new HashSet<>());
        return result;
    }

    private Object interpret(String owner, MethodNode method, List<Object> parameters,
                             Map<String, String> fields, Set<String> active) {
        String key = owner + "#" + method.name + method.desc;
        if (!active.add(key)) throw new IllegalStateException("Cyclic packet flow factory " + key);
        try {
            var stack = new ArrayList<Object>();
            var locals = new HashMap<Integer, Object>();
            int slot = 0;
            var types = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < parameters.size(); i++) { locals.put(slot, parameters.get(i)); slot += types[i].getSize(); }
            for (var instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC) {
                    stack.add(field.owner.equals(FLOW) && field.desc.equals("L" + FLOW + ";")
                            ? new Flow(field.name.toLowerCase(Locale.ROOT)) : UNKNOWN);
                } else if (instruction instanceof FieldInsnNode field && opcode == Opcodes.PUTSTATIC) {
                    Object value = pop(stack);
                    if (field.owner.equals(owner) && field.desc.equals("L" + TYPE + ";") && value instanceof Packet packet && packet.flow != null) {
                        fields.put(field.name, packet.flow.direction());
                    }
                } else if (instruction instanceof TypeInsnNode && opcode == Opcodes.NEW) {
                    stack.add(new Packet());
                } else if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                    stack.add(locals.getOrDefault(variable.var, UNKNOWN));
                } else if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                    locals.put(variable.var, pop(stack));
                } else if (instruction instanceof LdcInsnNode || instruction instanceof IntInsnNode
                        || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1 || opcode == Opcodes.ACONST_NULL) {
                    stack.add(UNKNOWN);
                } else if (opcode == Opcodes.DUP) {
                    stack.add(stack.getLast());
                } else if (opcode == Opcodes.POP) pop(stack);
                else if (instruction instanceof MethodInsnNode call) {
                    var arguments = new ArrayList<Object>();
                    var argumentTypes = Type.getArgumentTypes(call.desc);
                    for (int i = argumentTypes.length - 1; i >= 0; i--) arguments.addFirst(pop(stack));
                    Object receiver = opcode == Opcodes.INVOKESTATIC ? UNKNOWN : pop(stack);
                    if (call.owner.equals(TYPE) && call.name.equals("<init>")) {
                        for (int i = 0; i < argumentTypes.length; i++) {
                            if (argumentTypes[i].getDescriptor().equals("L" + FLOW + ";")
                                    && arguments.get(i) instanceof Flow flow && receiver instanceof Packet packet) packet.flow = flow;
                        }
                    } else if (Type.getReturnType(call.desc).getDescriptor().equals("L" + TYPE + ";") && opcode == Opcodes.INVOKESTATIC) {
                        var factory = read(call.owner).methods.stream().filter(value -> value.name.equals(call.name) && value.desc.equals(call.desc))
                                .findFirst().orElseThrow();
                        stack.add(interpret(call.owner, factory, arguments, new LinkedHashMap<>(), active));
                    } else if (Type.getReturnType(call.desc).getSort() != Type.VOID) stack.add(UNKNOWN);
                } else if (opcode == Opcodes.ARETURN) return pop(stack);
                else if (opcode == Opcodes.RETURN) return UNKNOWN;
                else if (opcode != Opcodes.CHECKCAST) throw new IllegalStateException("Unproven packet flow operation in " + key);
            }
            return UNKNOWN;
        } finally { active.remove(key); }
    }

    private ClassNode read(String owner) {
        return classes.computeIfAbsent(owner, value -> {
            var entry = jar.getJarEntry(value + ".class");
            if (entry == null) throw new IllegalStateException("Missing packet flow owner " + owner);
            try (var input = jar.getInputStream(entry)) {
                var node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }

    private static Object pop(List<Object> stack) {
        if (stack.isEmpty()) throw new IllegalStateException("Packet flow stack underflow");
        return stack.removeLast();
    }
    private record Flow(String direction) {
        Flow {
            if (!Set.of("clientbound", "serverbound").contains(direction)) throw new IllegalStateException("Unknown packet flow " + direction);
        }
    }
    private static final class Packet { Flow flow; }
}
