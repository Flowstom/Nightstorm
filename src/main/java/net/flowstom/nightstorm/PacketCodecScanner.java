package net.flowstom.nightstorm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

final class PacketCodecScanner {
    private PacketCodecScanner() {
    }

    static PacketShape scan(Path serverJar, String codecOwner) throws IOException {
        final String entryName = codecOwner.replace('.', '/') + ".class";
        try (var jar = new JarFile(serverJar.toFile())) {
            final var entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IllegalStateException("Missing packet codec class " + codecOwner);
            try (var input = jar.getInputStream(entry)) {
                final List<String> componentNames = new ArrayList<>();
                final List<String> componentDescriptors = new ArrayList<>();
                final List<CodecType> codecs = new ArrayList<>();
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                        componentNames.add(name);
                        componentDescriptors.add(descriptor);
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!"<clinit>".equals(name)) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            private String pendingOperation;

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                                if (opcode == Opcodes.GETSTATIC && fieldDescriptor.endsWith("StreamCodec;")) {
                                    codecs.add(knownCodec(owner, fieldName));
                                }
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                                if (owner.equals("net/minecraft/network/codec/ByteBufCodecs") && methodName.equals("list")) {
                                    pendingOperation = "list";
                                } else if (owner.equals("net/minecraft/network/codec/StreamCodec") && methodName.equals("apply")
                                        && "list".equals(pendingOperation)) {
                                    if (codecs.isEmpty()) throw new UnsupportedCodecException("Codec operation has no input codec");
                                    replaceLast(codecs, codecs.getLast().list());
                                    pendingOperation = null;
                                } else if (owner.equals("net/minecraft/network/codec/ByteBufCodecs") && methodName.equals("optional")) {
                                    throw new UnsupportedCodecException("optional codecs are not supported yet");
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                if (componentNames.size() != codecs.size()) {
                    throw new UnsupportedCodecException(codecOwner + " has " + componentNames.size()
                            + " record components but " + codecs.size() + " translatable codecs");
                }
                final List<PacketField> fields = new ArrayList<>();
                for (int index = 0; index < componentNames.size(); index++) {
                    final CodecType codec = codecs.get(index);
                    if (!codec.supported()) {
                        throw new UnsupportedCodecException("Unsupported codec " + codec.source() + " in " + codecOwner);
                    }
                    if (!codec.vanillaDescriptor().equals(componentDescriptors.get(index))) {
                        throw new UnsupportedCodecException(codec.source() + " produces " + codec.vanillaDescriptor()
                                + " but component " + componentNames.get(index) + " is " + componentDescriptors.get(index));
                    }
                    fields.add(new PacketField(componentNames.get(index), codec.javaType(), codec.networkType()));
                }
                return new PacketShape(fields);
            }
        } catch (UnsupportedCodecException exception) {
            return PacketShape.opaque(codecOwner + ": " + exception.getMessage());
        }
    }

    private static CodecType knownCodec(String owner, String fieldName) {
        if (owner.equals("net/minecraft/resources/Identifier") && fieldName.equals("STREAM_CODEC")) {
            return new CodecType("net.kyori.adventure.key.Key", "NetworkBuffer.KEY", "Identifier.STREAM_CODEC",
                    "Lnet/minecraft/resources/Identifier;", true);
        }
        if (owner.equals("net/minecraft/core/UUIDUtil") && fieldName.equals("STREAM_CODEC")) {
            return new CodecType("java.util.UUID", "NetworkBuffer.UUID", "UUIDUtil.STREAM_CODEC", "Ljava/util/UUID;", true);
        }
        if (owner.equals("net/minecraft/network/codec/ByteBufCodecs")) {
            return switch (fieldName) {
                case "BOOL" -> primitive("boolean", "BOOLEAN", fieldName, "Z");
                case "BYTE" -> primitive("byte", "BYTE", fieldName, "B");
                case "SHORT" -> primitive("short", "SHORT", fieldName, "S");
                case "UNSIGNED_SHORT" -> primitive("int", "UNSIGNED_SHORT", fieldName, "I");
                case "INT" -> primitive("int", "INT", fieldName, "I");
                case "VAR_INT" -> primitive("int", "VAR_INT", fieldName, "I");
                case "LONG" -> primitive("long", "LONG", fieldName, "J");
                case "VAR_LONG" -> primitive("long", "VAR_LONG", fieldName, "J");
                case "FLOAT" -> primitive("float", "FLOAT", fieldName, "F");
                case "DOUBLE" -> primitive("double", "DOUBLE", fieldName, "D");
                case "BYTE_ARRAY" -> primitive("byte[]", "BYTE_ARRAY", fieldName, "[B");
                case "LONG_ARRAY" -> primitive("long[]", "LONG_ARRAY", fieldName, "[J");
                case "STRING_UTF8", "PLAYER_NAME" -> primitive("String", "STRING", fieldName, "Ljava/lang/String;");
                case "INSTANT" -> primitive("java.time.Instant", "INSTANT_MS", fieldName, "Ljava/time/Instant;");
                default -> unsupported(owner, fieldName);
            };
        }
        return unsupported(owner, fieldName);
    }

    private static CodecType primitive(String javaType, String networkType, String source, String vanillaDescriptor) {
        return new CodecType(javaType, "NetworkBuffer." + networkType, "ByteBufCodecs." + source, vanillaDescriptor, true);
    }

    private static CodecType unsupported(String owner, String fieldName) {
        return new CodecType("", "", owner.replace('/', '.') + "#" + fieldName, "", false);
    }

    private static void replaceLast(List<CodecType> codecs, CodecType replacement) {
        if (codecs.isEmpty()) throw new UnsupportedCodecException("Codec operation has no input codec");
        codecs.set(codecs.size() - 1, replacement);
    }

    record PacketShape(List<PacketField> fields, String warning) {
        PacketShape(List<PacketField> fields) {
            this(fields, null);
        }

        PacketShape {
            fields = List.copyOf(fields);
        }

        static PacketShape opaque(String warning) {
            return new PacketShape(List.of(), warning);
        }

        boolean opaque() {
            return warning != null;
        }
    }

    record PacketField(String name, String javaType, String networkType) {
    }

    private record CodecType(String javaType, String networkType, String source, String vanillaDescriptor, boolean supported) {
        CodecType list() {
            return supported
                    ? new CodecType("java.util.List<" + boxed(javaType) + ">", networkType + ".list()",
                    source + ".list()", "Ljava/util/List;", true)
                    : this;
        }

        private static String boxed(String type) {
            return switch (type) {
                case "boolean" -> "Boolean";
                case "byte" -> "Byte";
                case "short" -> "Short";
                case "int" -> "Integer";
                case "long" -> "Long";
                case "float" -> "Float";
                case "double" -> "Double";
                default -> type;
            };
        }
    }

    static final class UnsupportedCodecException extends IllegalStateException {
        UnsupportedCodecException(String message) {
            super(message);
        }
    }
}
