package net.flowstom.nightstorm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

final class PacketScanner {
    private PacketScanner() {
    }

    static PacketReport scan(Path serverJar) throws IOException {
        final List<PacketCandidate> packets = new ArrayList<>();
        try (var jar = new JarFile(serverJar.toFile())) {
            jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith("net/minecraft/network/protocol/"))
                    .filter(name -> name.endsWith("Protocols.class"))
                    .filter(name -> !name.contains("$"))
                    .sorted()
                    .forEach(name -> scanProtocol(jar, name, packets));
        }
        return new PacketReport(Instant.now().toString(), packets);
    }

    private static void scanProtocol(JarFile jar, String entryName, List<PacketCandidate> output) {
        final String state = state(entryName);
        if (state == null) {
            return;
        }
        try (var input = jar.getInputStream(jar.getJarEntry(entryName))) {
            final Map<String, Integer> ids = new HashMap<>();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private String packetType;
                        private String codecOwner;

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                            if (opcode != Opcodes.GETSTATIC) {
                                return;
                            }
                            if (owner.endsWith("PacketTypes") && descriptor.endsWith("PacketType;")) {
                                packetType = owner.replace('/', '.') + "#" + fieldName;
                            } else if (packetType != null && descriptor.endsWith("StreamCodec;")) {
                                codecOwner = owner.replace('/', '.');
                            }
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW && packetType != null) {
                                codecOwner = type.replace('/', '.');
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName, String descriptor, boolean isInterface) {
                            if (!owner.endsWith("ProtocolInfoBuilder") || packetType == null) {
                                return;
                            }
                            final String handling;
                            if ("addPacket".equals(methodName)) {
                                handling = "existing-or-opaque";
                            } else if ("withBundlePacket".equals(methodName)) {
                                handling = "bundle";
                            } else {
                                return;
                            }
                            final String direction = direction(packetType);
                            if (direction != null && codecOwner != null) {
                                final String key = state + "/" + direction;
                                final int id = ids.getOrDefault(key, 0);
                                ids.put(key, id + 1);
                                output.add(new PacketCandidate(state, direction, id, packetType, codecOwner, handling));
                            }
                            packetType = null;
                            codecOwner = null;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to inspect " + entryName, exception);
        }
    }

    private static String state(String entryName) {
        if (entryName.contains("/game/")) return "play";
        if (entryName.contains("/configuration/")) return "configuration";
        if (entryName.contains("/login/")) return "login";
        if (entryName.contains("/status/")) return "status";
        if (entryName.contains("/handshake/")) return "handshake";
        return null;
    }

    private static String direction(String packetType) {
        if (packetType.contains("#CLIENTBOUND_")) return "clientbound";
        if (packetType.contains("#SERVERBOUND_")) return "serverbound";
        if (packetType.endsWith("#CLIENT_INTENTION")) return "serverbound";
        return null;
    }

    record PacketReport(String generatedAt, List<PacketCandidate> packets) {
        PacketReport {
            packets = List.copyOf(packets);
        }
    }

    record PacketCandidate(String state, String direction, int id, String vanillaType, String codecOwner, String handling) {
    }
}
