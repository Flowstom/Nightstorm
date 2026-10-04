package net.flowstom.nightstorm;

import org.objectweb.asm.ClassReader;

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
                final var node = new org.objectweb.asm.tree.ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                final WireSchema schema = WireSchema.scan(node, name -> {
                    final var nested = jar.getJarEntry(name + ".class");
                    if (nested == null) return null;
                    try (var bytes = jar.getInputStream(nested)) {
                        final var type = new org.objectweb.asm.tree.ClassNode();
                        new ClassReader(bytes).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        return type;
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                }).orElseThrow(() ->
                        new UnsupportedCodecException("Cannot prove field-to-codec bindings"));
                final List<PacketField> fields = new ArrayList<>();
                for (WireSchema.Field field : schema.fields()) {
                    final var component = schema.components().get(field.component());
                    final CodecType codec = translate(field.codec());
                    if (!codec.supported() || !codec.vanillaDescriptor().equals(component.descriptor())) {
                        throw new UnsupportedCodecException("Unsupported or mismatched codec for " + component.name());
                    }
                    fields.add(new PacketField(component.name(), codec.javaType(), codec.networkType()));
                }
                return new PacketShape(fields);
            }
        } catch (UnsupportedCodecException exception) {
            return PacketShape.opaque(codecOwner + ": " + exception.getMessage());
        }
    }

    static CodecType translate(WireSchema.Codec codec) {
        CodecType result;
        if (codec.owner().equals(WireSchema.BYTE_CODECS) && codec.name().equals("map") && codec.arguments().size() == 2) {
            final CodecType key = translate(codec.arguments().get(0)), value = translate(codec.arguments().get(1));
            result = key.supported() && value.supported()
                    ? new CodecType("java.util.Map<" + CodecType.boxed(key.javaType()) + ", " + CodecType.boxed(value.javaType()) + ">",
                    key.networkType() + ".mapValue(" + value.networkType() + ")", "map", "Ljava/util/Map;", true)
                    : unsupported(codec.owner(), codec.name());
        } else result = knownCodec(codec.owner(), codec.name());
        for (String operation : codec.operations()) {
            if (operation.equals("list")) result = result.list();
            else if (operation.equals("nonempty") && result.supported() && result.javaType().startsWith("java.util.List<")) {
                result = new CodecType(result.javaType(), nonemptyExpression(result.networkType(), result.javaType()),
                        result.source(), result.vanillaDescriptor(), true);
            } else if (operation.startsWith("limit:") && result.supported() && result.javaType().equals("String")) {
                int limit = Integer.parseInt(operation.substring(6));
                String check = "{ if (value.length() > " + limit + " || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > "
                        + ((long) limit * 3) + "L) throw new IllegalArgumentException(\"String exceeds codec limit\"); return value; }";
                result = new CodecType(result.javaType(), result.networkType() + ".transform((String value) -> " + check
                        + ", (String value) -> " + check + ")", result.source(), result.vanillaDescriptor(), true);
            } else if (operation.startsWith("fixed:") && result.supported()) {
                int count = Integer.parseInt(operation.substring(6));
                String element = CodecType.boxed(result.javaType());
                String list = "java.util.List<" + element + ">";
                String expression = "new NetworkBuffer.Type<" + list + ">() { @Override public void write(NetworkBuffer fixedBuffer, " + list
                        + " fixedValues) { if (fixedValues.size() != " + count + ") throw new IllegalArgumentException(\"Fixed list length\");"
                        + " for (var fixedElement : fixedValues) fixedBuffer.write(" + result.networkType() + ", fixedElement); } @Override public " + list
                        + " read(NetworkBuffer fixedBuffer) { var fixedResult = new java.util.ArrayList<" + element + ">(" + count
                        + "); for (int fixedIndex = 0; fixedIndex < " + count + "; fixedIndex++) fixedResult.add(fixedBuffer.read(" + result.networkType() + ")); return java.util.List.copyOf(fixedResult); } }";
                result = new CodecType(list, expression, result.source(), "Ljava/util/List;", true);
            }
            else return unsupported(codec.owner(), codec.name());
        }
        return result;
    }

    static String nonemptyExpression(String codec, String type) {
        String check = "(" + type + " listValue) -> { if (listValue.isEmpty()) throw new IllegalArgumentException(\"Empty mapped collection\"); return listValue; }";
        return codec + ".transform(" + check + ", " + check + ")";
    }

    private static CodecType knownCodec(String owner, String fieldName) {
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

    record CodecType(String javaType, String networkType, String source, String vanillaDescriptor, boolean supported) {
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
