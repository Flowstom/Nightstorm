package net.flowstom.nightstorm;

import java.util.*;

/** Renders all catalog payloads using proved retained representations or structured primitive codecs. */
final class DispatchRenderer {
    record SourceCodec(String type, String expression) { }
    private final WireDispatch dispatch;
    private final Map<WireSchema.Codec, SourceCodec> retained;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, String> declarations = new LinkedHashMap<>();
    DispatchRenderer(WireDispatch dispatch, Map<WireSchema.Codec, SourceCodec> retained) {
        this.dispatch = dispatch; this.retained = retained;
    }
    String variantName(String union, int id) {
        for (int i=0; i<dispatch.variants().size(); i++) if (dispatch.variants().get(i).id()==id) return union + "Case" + i;
        throw new IllegalStateException("Missing dispatch variant " + id);
    }
    String declarations(String union) {
        for (var variant : dispatch.variants()) names.put(variant.payload().owner(), variantName(union, variant.id()));
        StringBuilder write = new StringBuilder(), read = new StringBuilder("return switch (buffer.read(NetworkBuffer.VAR_INT)) { ");
        for (var variant : dispatch.variants()) {
            String name = variantName(union, variant.id());
            renderRecord(variant.payload(), name, " implements " + union);
            write.append("if (value instanceof ").append(name).append(" variant) { buffer.write(NetworkBuffer.VAR_INT, ")
                    .append(variant.id()).append("); buffer.write(").append(name).append(".SERIALIZER, variant); return; } ");
            read.append("case ").append(variant.id()).append(" -> buffer.read(").append(name).append(".SERIALIZER); ");
        }
        read.append("default -> throw new IllegalArgumentException(\"Unknown dispatch tag\"); }; ");
        var permits = dispatch.variants().stream().map(v -> variantName(union, v.id())).toList();
        String header = "public sealed interface " + union + " permits " + String.join(", ", permits) + " { NetworkBuffer.Type<" + union
                + "> SERIALIZER = new NetworkBuffer.Type<" + union + ">() { @Override public void write(NetworkBuffer buffer, " + union
                + " value) { " + write + "throw new IllegalArgumentException(\"Unknown dispatch value\"); } @Override public " + union
                + " read(NetworkBuffer buffer) { " + read + " } }; } ";
        return header + String.join("\n", declarations.values());
    }
    private void renderRecord(WireSchema schema, String name, String interfaces) {
        if (declarations.containsKey(schema.owner())) return;
        declarations.put(schema.owner(), "");
        List<String> parameters = new ArrayList<>(), values = new ArrayList<>();
        StringBuilder write = new StringBuilder(), read = new StringBuilder();
        for (var field : schema.fields()) {
            var component = schema.components().get(field.component());
            var codec = resolve(field.codec());
            parameters.add(codec.type() + " " + component.name());
            write.append("buffer.write(").append(codec.expression()).append(", value.").append(component.name()).append("()); ");
            String variable = "component" + values.size();
            read.append("var ").append(variable).append(" = buffer.read(").append(codec.expression()).append("); ");
            values.add(variable);
        }
        declarations.put(schema.owner(), "public record " + name + "(" + String.join(", ", parameters) + ")" + interfaces
                + " { public static final NetworkBuffer.Type<" + name + "> SERIALIZER = new NetworkBuffer.Type<" + name
                + ">() { @Override public void write(NetworkBuffer buffer, " + name + " value) { " + write
                + " } @Override public " + name + " read(NetworkBuffer buffer) { " + read + "return new " + name + "(" + String.join(", ", values) + "); } }; } ");
    }
    private SourceCodec resolve(WireSchema.Codec codec) {
        var known = retained.get(codec);
        if (known != null) return known;
        var translated = PacketCodecScanner.translate(codec);
        if (translated.supported()) return new SourceCodec(translated.javaType(), translated.networkType());
        var base = new WireSchema.Codec(codec.owner(), codec.name(), List.of(), codec.arguments());
        SourceCodec element = retained.get(base);
        if (element == null) {
            var schema = dispatch.schemas().get(codec.owner());
            if (schema == null) throw new IllegalStateException("No proved source representation for dispatch codec " + codec);
            String name = names.computeIfAbsent(schema.owner(), ignored -> "WirePayload" + names.size());
            renderRecord(schema, name, ""); element = new SourceCodec(name, name + ".SERIALIZER");
        }
        for (String operation : codec.operations()) {
            if (operation.equals("nonempty") && element.type().startsWith("java.util.List<")) {
                element = new SourceCodec(element.type(), PacketCodecScanner.nonemptyExpression(element.expression(), element.type()));
                continue;
            }
            if (!operation.equals("list")) throw new IllegalStateException("Unsupported dispatch operation " + operation);
            element = new SourceCodec("java.util.List<" + boxed(element.type()) + ">", element.expression() + ".list()");
        }
        return element;
    }
    private static String boxed(String type) {
        return switch(type) { case "int" -> "Integer"; case "long" -> "Long"; case "float" -> "Float";
            case "double" -> "Double"; case "boolean" -> "Boolean"; case "byte" -> "Byte"; case "short" -> "Short"; default -> type; };
    }
}
