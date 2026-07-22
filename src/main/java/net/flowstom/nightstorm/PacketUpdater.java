package net.flowstom.nightstorm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

final class PacketUpdater {
    private static final Pattern REGISTRY_START = Pattern.compile("\\b(CLIENT|SERVER)_(HANDSHAKE|STATUS|LOGIN|CONFIGURATION|PLAY)\\b");
    private static final Pattern ENTRY = Pattern.compile("^(\\s*)entry\\((\\w+)\\.class,\\s*(.+)\\)(,?)$");
    private static final Pattern QUALIFIED_TYPE = Pattern.compile("(?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*");

    private PacketUpdater() {
    }

    static UpdateResult update(Path sourceRoot, PacketScanner.PacketReport baseline, PacketScanner.PacketReport target,
                               Path targetJar) throws IOException {
        final Map<String, PacketCodecScanner.PacketShape> shapes = new HashMap<>();
        return update(sourceRoot, baseline, target, codecOwner -> shapes.computeIfAbsent(codecOwner, owner -> {
            try {
                return PacketCodecScanner.scan(targetJar, owner);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to inspect packet codec " + owner, exception);
            }
        }));
    }

    static UpdateResult update(Path sourceRoot, PacketScanner.PacketReport baseline, PacketScanner.PacketReport target,
                               Function<String, PacketCodecScanner.PacketShape> shapeResolver) throws IOException {
        final Path packetVanilla = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet/PacketVanilla.java");
        final String source = Files.readString(packetVanilla);
        final Map<RegistryKey, List<PacketScanner.PacketCandidate>> baselinePackets = group(baseline);
        final Map<RegistryKey, List<PacketScanner.PacketCandidate>> targetPackets = group(target);
        final List<GeneratedPacket> generatedPackets = new ArrayList<>();
        final StringBuilder updated = new StringBuilder();
        final String[] lines = source.split("\\R", -1);

        for (int index = 0; index < lines.length; index++) {
            final String line = lines[index];
            if (line.equals("import net.minestom.server.network.packet.nightstorm.*;")) continue;

            final Matcher registryMatcher = REGISTRY_START.matcher(line);
            if (!line.contains("PacketRegistry") || !registryMatcher.find()) {
                updated.append(line).append('\n');
                continue;
            }

            final RegistryKey key = RegistryKey.from(registryMatcher.group(1), registryMatcher.group(2));
            final List<String> block = new ArrayList<>();
            block.add(line);
            if (!line.strip().endsWith(");")) {
                while (++index < lines.length) {
                    block.add(lines[index]);
                    if (lines[index].strip().equals(");")) break;
                }
            }
            rewriteRegistry(updated, block, key, baselinePackets.getOrDefault(key, List.of()),
                    targetPackets.getOrDefault(key, List.of()), generatedPackets, shapeResolver);
        }

        String updatedSource = removeFinalExtraNewline(updated.toString());
        if (!generatedPackets.isEmpty()) {
            final String firstServerImport = "import net.minestom.server.network.packet.server.";
            final int importIndex = updatedSource.indexOf(firstServerImport);
            if (importIndex < 0) throw new IllegalStateException("Unable to locate server packet imports");
            updatedSource = updatedSource.substring(0, importIndex)
                    + "import net.minestom.server.network.packet.nightstorm.*;\n"
                    + updatedSource.substring(importIndex);
        }
        Files.writeString(packetVanilla, updatedSource);
        final Path generatedDirectory = sourceRoot.resolve("src/main/java/net/minestom/server/network/packet/nightstorm");
        Files.createDirectories(generatedDirectory);
        try (var existing = Files.list(generatedDirectory)) {
            existing.filter(path -> path.getFileName().toString().startsWith("Nightstorm") && path.toString().endsWith("Packet.java"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Unable to remove stale generated packet " + path, exception);
                        }
                    });
        }
        for (GeneratedPacket packet : generatedPackets) {
            Files.writeString(generatedDirectory.resolve(packet.className() + ".java"), packetSource(packet));
        }
        final List<String> warnings = generatedPackets.stream()
                .filter(packet -> packet.shape().opaque())
                .map(packet -> packet.vanillaType() + ": " + packet.shape().warning())
                .distinct()
                .toList();
        final Path warningFile = sourceRoot.resolve(".nightstorm/packet-warnings.md");
        Files.createDirectories(warningFile.getParent());
        if (warnings.isEmpty()) {
            Files.deleteIfExists(warningFile);
        } else {
            final StringBuilder warningText = new StringBuilder("## Packet compatibility warnings\n\n");
            warnings.forEach(warning -> {
                final int separator = warning.indexOf(": ");
                warningText.append("- `").append(warning, 0, separator).append("`: ")
                        .append(warning.substring(separator + 2)).append('\n');
            });
            Files.writeString(warningFile, warningText.toString());
        }
        return new UpdateResult(target.packets().size(), generatedPackets.size(), warnings);
    }

    private static void rewriteRegistry(StringBuilder output, List<String> block, RegistryKey key,
                                        List<PacketScanner.PacketCandidate> baseline,
                                        List<PacketScanner.PacketCandidate> target, List<GeneratedPacket> generatedPackets,
                                        Function<String, PacketCodecScanner.PacketShape> shapeResolver) {
        final List<RegistryEntry> entries = new ArrayList<>();
        int firstEntry = -1;
        int lastEntry = -1;
        for (int index = 0; index < block.size(); index++) {
            final Matcher matcher = ENTRY.matcher(block.get(index));
            if (matcher.matches()) {
                if (firstEntry < 0) firstEntry = index;
                lastEntry = index;
                entries.add(new RegistryEntry(matcher.group(1), matcher.group(2), matcher.group(3)));
            }
        }
        final List<PacketScanner.PacketCandidate> sourcePackets;
        if (baseline.size() == entries.size()) {
            sourcePackets = baseline;
        } else if (target.size() == entries.size() && entries.stream().anyMatch(entry -> entry.className().startsWith("Nightstorm"))) {
            sourcePackets = target;
        } else {
            throw new IllegalStateException("Baseline " + key + " has " + baseline.size()
                    + " packets but PacketVanilla has " + entries.size());
        }
        if (target.isEmpty() && !baseline.isEmpty()) {
            throw new IllegalStateException("Target scan did not contain registry " + key);
        }

        final Map<String, RegistryEntry> existingByVanillaType = new LinkedHashMap<>();
        for (int index = 0; index < sourcePackets.size(); index++) {
            existingByVanillaType.put(sourcePackets.get(index).vanillaType(), entries.get(index));
        }
        final String indent = entries.isEmpty() ? "            " : entries.getFirst().indent();
        final List<RegistryEntry> reordered = new ArrayList<>();
        for (PacketScanner.PacketCandidate packet : target) {
            final RegistryEntry existing = existingByVanillaType.get(packet.vanillaType());
            if (existing != null) {
                reordered.add(existing);
                if (existing.className().startsWith("Nightstorm")) {
                    final String expectedClassName = className(key, packet.vanillaType());
                    if (!existing.className().equals(expectedClassName)) {
                        throw new IllegalStateException("Generated packet " + existing.className()
                                + " does not match " + packet.vanillaType());
                    }
                    generatedPackets.add(new GeneratedPacket(existing.className(), key, packet.vanillaType(),
                            shapeResolver.apply(packet.codecOwner())));
                }
            } else {
                final String className = className(key, packet.vanillaType());
                generatedPackets.add(new GeneratedPacket(className, key, packet.vanillaType(), shapeResolver.apply(packet.codecOwner())));
                reordered.add(new RegistryEntry(indent, className, className + ".SERIALIZER"));
            }
        }

        if (firstEntry < 0) {
            firstEntry = block.size() - 1;
            lastEntry = firstEntry - 1;
        }
        for (int index = 0; index < firstEntry; index++) output.append(block.get(index)).append('\n');
        for (int index = 0; index < reordered.size(); index++) {
            final RegistryEntry entry = reordered.get(index);
            output.append(entry.indent()).append("entry(").append(entry.className()).append(".class, ")
                    .append(entry.serializer()).append(')');
            if (index + 1 < reordered.size()) output.append(',');
            output.append('\n');
        }
        for (int index = lastEntry + 1; index < block.size(); index++) output.append(block.get(index)).append('\n');
    }

    private static Map<RegistryKey, List<PacketScanner.PacketCandidate>> group(PacketScanner.PacketReport report) {
        final Map<RegistryKey, List<PacketScanner.PacketCandidate>> grouped = new HashMap<>();
        for (PacketScanner.PacketCandidate packet : report.packets()) {
            grouped.computeIfAbsent(new RegistryKey(packet.state(), packet.direction()), ignored -> new ArrayList<>()).add(packet);
        }
        return grouped;
    }

    private static String className(RegistryKey key, String vanillaType) {
        final String field = vanillaType.substring(vanillaType.indexOf('#') + 1);
        final StringBuilder name = new StringBuilder("Nightstorm").append(title(key.state())).append(title(key.direction()));
        final String packetName = field.replaceFirst("^(CLIENTBOUND|SERVERBOUND)_", "");
        for (String part : packetName.split("_")) name.append(title(part.toLowerCase(Locale.ROOT)));
        return name.append("Packet").toString();
    }

    private static String packetSource(GeneratedPacket packet) {
        final String className = packet.className();
        final RegistryKey key = packet.key();
        final boolean clientbound = key.direction().equals("clientbound");
        final String packetType = (clientbound ? "ServerPacket." : "ClientPacket.") + title(key.state());
        final String packetImport = clientbound
                ? "net.minestom.server.network.packet.server.ServerPacket"
                : "net.minestom.server.network.packet.client.ClientPacket";
        if (packet.shape().opaque()) {
            return """
                    package net.minestom.server.network.packet.nightstorm;

                    import net.minestom.server.network.NetworkBuffer;
                    import %s;
                    import org.jetbrains.annotations.ApiStatus;

                    @ApiStatus.Internal
                    public record %s(byte[] payload) implements %s {
                        public static final NetworkBuffer.Type<%s> SERIALIZER =
                                NetworkBuffer.RAW_BYTES.transform(%s::new, %s::payload);
                    }
                    """.formatted(packetImport, className, packetType, className, className, className);
        }
        final Map<String, String> typeImports = new LinkedHashMap<>();
        final String components = packet.shape().fields().stream()
                .map(field -> simplifyType(field.javaType(), typeImports) + " " + field.name())
                .collect(java.util.stream.Collectors.joining(", "));
        final String serializer = serializerSource(className, packet.shape());
        final String templateImport = packet.shape().fields().size() == 1
                ? ""
                : "import net.minestom.server.network.NetworkBufferTemplate;\n";
        final String generatedImports = typeImports.keySet().stream()
                .sorted()
                .map(type -> "import " + type + ";\n")
                .collect(java.util.stream.Collectors.joining());
        return """
                package net.minestom.server.network.packet.nightstorm;

                import net.minestom.server.network.NetworkBuffer;
                %simport %s;
                %simport org.jetbrains.annotations.ApiStatus;

                @ApiStatus.Internal
                public record %s(%s) implements %s {
                    public static final NetworkBuffer.Type<%s> SERIALIZER = %s;
                }
                """.formatted(templateImport, packetImport, generatedImports, className, components, packetType, className, serializer);
    }

    private static String simplifyType(String javaType, Map<String, String> imports) {
        final Matcher matcher = QUALIFIED_TYPE.matcher(javaType);
        final StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            final String qualified = matcher.group();
            final String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (imports.containsValue(simple) && !simple.equals(imports.get(qualified))) continue;
            imports.put(qualified, simple);
            matcher.appendReplacement(result, Matcher.quoteReplacement(simple));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String serializerSource(String className, PacketCodecScanner.PacketShape shape) {
        if (shape.fields().isEmpty()) return "NetworkBufferTemplate.template(new " + className + "())";
        if (shape.fields().size() == 1) {
            final var field = shape.fields().getFirst();
            return field.networkType() + ".transform(" + className + "::new, " + className + "::" + field.name() + ")";
        }
        final StringBuilder serializer = new StringBuilder("NetworkBufferTemplate.template(\n");
        for (PacketCodecScanner.PacketField field : shape.fields()) {
            serializer.append("            ").append(field.networkType()).append(", ")
                    .append(className).append("::").append(field.name()).append(",\n");
        }
        return serializer.append("            ").append(className).append("::new\n    )").toString();
    }

    private static String title(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String removeFinalExtraNewline(String value) {
        return value.endsWith("\n\n") ? value.substring(0, value.length() - 1) : value;
    }

    record UpdateResult(int packets, int generatedPackets, List<String> warnings) {
        UpdateResult {
            warnings = List.copyOf(warnings);
        }
    }

    private record RegistryEntry(String indent, String className, String serializer) {
    }

    private record GeneratedPacket(String className, RegistryKey key, String vanillaType,
                                   PacketCodecScanner.PacketShape shape) {
    }

    private record RegistryKey(String state, String direction) {
        static RegistryKey from(String registrySide, String state) {
            return new RegistryKey(state.toLowerCase(Locale.ROOT), registrySide.equals("CLIENT") ? "serverbound" : "clientbound");
        }
    }
}
