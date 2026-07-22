package net.flowstom.nightstorm;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RetainedPacketMigrator {
    private RetainedPacketMigrator() {
    }

    static void apply(Path sourceRoot, List<PacketMigrationScanner.Migration> migrations) throws IOException {
        if (migrations.isEmpty()) return;
        final var sources = new SourceIndex(sourceRoot);
        final Map<SerializerSlot, PlannedEdit> edits = new LinkedHashMap<>();
        for (PacketMigrationScanner.Migration migration : migrations) {
            final ResolvedType packetType = sources.resolveUnique(migration.packet().className());
            if (migration.kind() != PacketMigrationScanner.Kind.OPTIONAL_ENUM) {
                planSemanticMigration(sources, edits, packetType, migration);
                continue;
            }
            CodecReference codec = new CodecReference(
                    sources.parseExpression(migration.packet().serializer(), "retained packet serializer"),
                    packetType, null, List.of());
            ResolvedType owner = packetType;
            for (int depth = 0; depth < migration.path().size(); depth++) {
                final ResolvedExpression resolved = sources.dereference(codec);
                owner = resolved.owner();
                final ResolvedType templateOwner = owner;
                final int component = migration.path().get(depth);
                final MethodCallExpr template = resolved.expression().toMethodCallExpr()
                        .filter(call -> call.getNameAsString().equals("template"))
                        .filter(call -> call.getScope().map(Object::toString).orElse("").endsWith("NetworkBufferTemplate"))
                        .orElseThrow(() -> new IllegalStateException("Component path " + migration.path()
                                + " does not resolve to a NetworkBufferTemplate.template call in "
                                + templateOwner.qualifiedName()));
                final int codecSlot = component * 2;
                if (template.getArguments().size() <= codecSlot + 1) {
                    throw new IllegalStateException("Template in " + owner.qualifiedName()
                            + " has no component " + component);
                }
                final List<Integer> componentPath = append(resolved.componentPath(), component);
                final Expression componentCodec = template.getArgument(codecSlot);
                final boolean leaf = depth + 1 == migration.path().size();
                final ResolvedType componentType = sources.recordComponentType(owner, component, leaf);
                if (leaf) {
                    final ResolvedType enumType = componentType;
                    requireMatchingEnum(enumType, migration);
                    final FieldLocation field = resolved.field();
                    if (field == null) {
                        throw new IllegalStateException("Serializer component " + migration.path()
                                + " is not backed by a source field");
                    }
                    final SerializerSlot slot = new SerializerSlot(field.source(), field.owner(), field.name(), componentPath);
                    final Expression replacement = sources.parseExpression(codecExpression(enumType, migration),
                            "optional enum codec expression");
                    final PlannedEdit edit = new PlannedEdit(componentCodec, replacement, semanticKey(migration), List.of());
                    final PlannedEdit previous = edits.putIfAbsent(slot, edit);
                    if (previous != null && !previous.semantic().equals(semanticKey(migration))) {
                        throw new IllegalStateException("Conflicting migrations resolve to the same serializer slot in "
                                + owner.qualifiedName());
                    }
                } else {
                    codec = new CodecReference(componentCodec, componentType, resolved.field(), componentPath);
                }
            }
        }
        for (PlannedEdit edit : edits.values()) {
            if (!edit.expression().equals(edit.replacement())) edit.expression().replace(edit.replacement());
            edit.removals().forEach(Node::remove);
        }
        sources.writeChanged();
    }

    private static void planSemanticMigration(SourceIndex sources, Map<SerializerSlot, PlannedEdit> edits,
                                               ResolvedType packetType,
                                               PacketMigrationScanner.Migration migration) {
        if (!migration.path().isEmpty()) {
            throw new IllegalStateException("Whole-payload migration unexpectedly has component path "
                    + migration.path());
        }
        final CodecReference reference = new CodecReference(
                sources.parseExpression(migration.packet().serializer(), "retained packet serializer"),
                packetType, null, List.of());
        final ResolvedExpression resolved = sources.dereference(reference);
        if (resolved.field() == null) {
            throw new IllegalStateException("Retained packet serializer is not backed by a source field");
        }
        final Expression current = resolved.expression();
        final Expression replacement = switch (migration.kind()) {
            case REORDERED_BOOLEAN_ENUM -> reorderedBooleanSerializer(sources, packetType, current, migration);
            case LINEAR_POSITION_PATH -> linearPositionSerializer(sources, packetType, current, migration);
            case APPENDED_BOOLEAN -> appendedBooleanSerializer(sources, packetType, current, migration.defaultValue());
            case OPTIONAL_ENUM -> throw new IllegalStateException("Unexpected optional enum migration");
        };
        final SerializerSlot slot = new SerializerSlot(resolved.field().source(), resolved.field().owner(),
                resolved.field().name(), List.of());
        final List<Node> removals = migration.kind() == PacketMigrationScanner.Kind.REORDERED_BOOLEAN_ENUM
                ? obsoletePrivateHelpers(packetType, current) : List.of();
        final PlannedEdit edit = new PlannedEdit(current, replacement, semanticKey(migration), removals);
        final PlannedEdit previous = edits.putIfAbsent(slot, edit);
        if (previous != null && !previous.semantic().equals(edit.semantic())) {
            throw new IllegalStateException("Conflicting whole-payload migrations for " + packetType.qualifiedName());
        }
    }

    private static Expression reorderedBooleanSerializer(SourceIndex sources, ResolvedType packetType,
                                                          Expression current,
                                                          PacketMigrationScanner.Migration migration) {
        final RecordDeclaration record = requireRecord(packetType);
        if (record.getParameters().size() != 3 || migration.fixedSize() <= 0) {
            throw new IllegalStateException("Reordered boolean payload source shape does not have three components");
        }
        int booleanIndex = -1;
        int stringsIndex = -1;
        int objectIndex = -1;
        for (int index = 0; index < record.getParameters().size(); index++) {
            final Parameter parameter = record.getParameter(index);
            if (parameter.getType().isPrimitiveType()
                    && parameter.getType().asPrimitiveType().getType()
                    == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN) booleanIndex = index;
            else if (parameter.getType().asString().replace(" ", "").matches("(?:java\\.util\\.)?List<String>")) {
                stringsIndex = index;
            } else objectIndex = index;
        }
        if (booleanIndex < 0 || stringsIndex < 0 || objectIndex < 0
                || !(current instanceof ObjectCreationExpr creation)) {
            throw new IllegalStateException("Reordered boolean payload requires a custom serializer and flat source API");
        }
        final String existing = current.toString();
        final String type = record.getNameAsString();
        final String objectName = record.getParameter(objectIndex).getNameAsString();
        final String booleanName = record.getParameter(booleanIndex).getNameAsString();
        final String stringsName = record.getParameter(stringsIndex).getNameAsString();
        final String listFactory = record.getParameter(stringsIndex).getType().asString().replace(" ", "")
                .startsWith("java.util.List") ? "java.util.List" : "List";
        if (existing.contains("booleanValueId") && existing.contains("NetworkBuffer.VAR_INT")) {
            final String normalized = listFactory.equals("List")
                    ? existing.replace("java.util.List.of(", "List.of(")
                    : existing.replace("var stringValues = List.of(", "var stringValues = java.util.List.of(");
            return normalized.equals(existing) ? current.clone()
                    : sources.parseExpression(normalized, "qualified reordered boolean serializer");
        }
        if (existing.contains("boolean booleanValue = buffer.read(VAR_INT) != 0")
                && existing.contains("? 1 : 0")) {
            final String upgraded = existing
                    .replace("buffer.write(VAR_INT, value." + booleanName + "() ? 1 : 0)",
                            "buffer.write(NetworkBuffer.VAR_INT, value." + booleanName + "() ? "
                                    + migration.trueId() + " : " + migration.falseId() + ")")
                    .replace("var stringValues = List.of(", "var stringValues = " + listFactory + ".of(")
                    .replace("boolean booleanValue = buffer.read(VAR_INT) != 0;", """
                            int booleanValueId = buffer.read(NetworkBuffer.VAR_INT);
                            boolean booleanValue;
                            if (booleanValueId == %d) booleanValue = true;
                            else if (booleanValueId == %d) booleanValue = false;
                            else throw new IllegalArgumentException("Unknown binary enum id: " + booleanValueId);"""
                            .formatted(migration.trueId(), migration.falseId()));
            return sources.parseExpression(upgraded, "legacy reordered boolean serializer");
        }
        final List<MethodCallExpr> writes = creation.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("write") && call.getArguments().size() == 2).toList();
        final Expression objectCodec = uniqueCodec(writes, "BOOLEAN", "STRING");
        final Expression stringCodec = uniqueNamedCodec(writes, "STRING");
        if (writes.stream().filter(call -> terminalName(call.getArgument(0)).equals("STRING")).count()
                != migration.fixedSize()) {
            throw new IllegalStateException("Fixed string payload source write count does not match target");
        }
        final StringBuilder stringWrites = new StringBuilder();
        final StringBuilder stringReads = new StringBuilder();
        for (int index = 0; index < migration.fixedSize(); index++) {
            stringWrites.append("            buffer.write(").append(stringCodec).append(", value.")
                    .append(stringsName).append("().get(").append(index).append("));\n");
            if (index > 0) stringReads.append(", ");
            stringReads.append("buffer.read(").append(stringCodec).append(")");
        }
        final String[] arguments = new String[3];
        arguments[objectIndex] = "objectValue";
        arguments[booleanIndex] = "booleanValue";
        arguments[stringsIndex] = "stringValues";
        return sources.parseExpression("""
                new NetworkBuffer.Type<%s>() {
                    @Override
                    public void write(NetworkBuffer buffer, %s value) {
                        buffer.write(%s, value.%s());
                %s        buffer.write(NetworkBuffer.VAR_INT, value.%s() ? %d : %d);
                    }

                    @Override
                    public %s read(NetworkBuffer buffer) {
                        var objectValue = buffer.read(%s);
                        var stringValues = %s.of(%s);
                        int booleanValueId = buffer.read(NetworkBuffer.VAR_INT);
                        boolean booleanValue;
                        if (booleanValueId == %d) booleanValue = true;
                        else if (booleanValueId == %d) booleanValue = false;
                        else throw new IllegalArgumentException("Unknown binary enum id: " + booleanValueId);
                        return new %s(%s);
                    }
                }
                """.formatted(type, type, objectCodec, objectName, stringWrites, booleanName,
                migration.trueId(), migration.falseId(), type, objectCodec, listFactory, stringReads,
                migration.trueId(), migration.falseId(), type, String.join(", ", arguments)),
                "reordered boolean serializer");
    }

    private static Expression linearPositionSerializer(SourceIndex sources, ResolvedType packetType,
                                                        Expression current,
                                                        PacketMigrationScanner.Migration migration) {
        final RecordDeclaration record = requireRecord(packetType);
        if (record.getParameters().size() != 6) {
            throw new IllegalStateException("Linear position-path source shape does not have six components");
        }
        final MethodCallExpr template = current.toMethodCallExpr()
                .filter(call -> call.getNameAsString().equals("template"))
                .orElse(null);
        if (template == null) {
            final String existing = current.toString();
            if (existing.contains("buffer.write(NetworkBuffer.VAR_INT, " + migration.discriminator() + ")")
                    && existing.contains("component2 = component1")) return current.clone();
            if (existing.contains("buffer.write(VAR_INT, " + migration.discriminator() + ")")
                    && existing.contains("component2 = component1")) {
                return sources.parseExpression(existing
                                .replace("buffer.write(VAR_INT,", "buffer.write(NetworkBuffer.VAR_INT,")
                                .replace("buffer.read(VAR_INT)", "buffer.read(NetworkBuffer.VAR_INT)"),
                        "legacy linear position serializer");
            }
            throw new IllegalStateException("Linear position-path migration requires a template serializer");
        }
        if (template.getArguments().size() != 13
                || !record.getParameter(0).getType().isPrimitiveType()
                || !record.getParameter(1).getType().equals(record.getParameter(2).getType())
                || !record.getParameter(3).getType().isPrimitiveType()
                || !record.getParameter(4).getType().isPrimitiveType()
                || !record.getParameter(5).getType().isPrimitiveType()) {
            throw new IllegalStateException("Linear position-path source component shape is incompatible");
        }
        final Expression[] codecs = new Expression[6];
        for (int index = 0; index < codecs.length; index++) codecs[index] = template.getArgument(index * 2).clone();
        if (!terminalName(codecs[0]).equals("VAR_INT") || !codecs[1].equals(codecs[2])
                || !terminalName(codecs[3]).equals("FLOAT") || !terminalName(codecs[4]).equals("FLOAT")
                || !terminalName(codecs[5]).equals("BOOLEAN")) {
            throw new IllegalStateException("Linear position-path source codecs are incompatible");
        }
        final String type = record.getNameAsString();
        final StringBuilder writes = new StringBuilder();
        writes.append("        buffer.write(").append(codecs[0]).append(", value.")
                .append(record.getParameter(0).getNameAsString()).append("());\n")
                .append("        buffer.write(NetworkBuffer.VAR_INT, ").append(migration.discriminator()).append(");\n")
                .append("        buffer.write(").append(codecs[1]).append(", value.")
                .append(record.getParameter(1).getNameAsString()).append("());\n");
        for (int index = 3; index < 6; index++) writes.append("        buffer.write(").append(codecs[index])
                .append(", value.").append(record.getParameter(index).getNameAsString()).append("());\n");
        return sources.parseExpression("""
                new NetworkBuffer.Type<%s>() {
                    @Override
                    public void write(NetworkBuffer buffer, %s value) {
                %s    }

                    @Override
                    public %s read(NetworkBuffer buffer) {
                        %s component0 = buffer.read(%s);
                         int discriminator = buffer.read(NetworkBuffer.VAR_INT);
                        if (discriminator != %d) throw new IllegalArgumentException("Unsupported position path id: " + discriminator);
                        %s component1 = buffer.read(%s);
                        %s component2 = component1;
                        %s component3 = buffer.read(%s);
                        %s component4 = buffer.read(%s);
                        %s component5 = buffer.read(%s);
                        return new %s(component0, component1, component2, component3, component4, component5);
                    }
                }
                """.formatted(type, type, writes, type,
                record.getParameter(0).getType(), codecs[0], migration.discriminator(),
                record.getParameter(1).getType(), codecs[1], record.getParameter(2).getType(),
                record.getParameter(3).getType(), codecs[3], record.getParameter(4).getType(), codecs[4],
                record.getParameter(5).getType(), codecs[5], type), "linear position-path serializer");
    }

    private static Expression appendedBooleanSerializer(SourceIndex sources, ResolvedType packetType,
                                                         Expression current, boolean defaultValue) {
        final RecordDeclaration record = requireRecord(packetType);
        if (current.toString().contains("compatibilityDelegate")) {
            final Expression corrected = current.clone();
            final List<MethodCallExpr> writes = corrected.findAll(MethodCallExpr.class).stream()
                    .filter(call -> call.getNameAsString().equals("write") && call.getArguments().size() == 2)
                    .filter(call -> terminalName(call.getArgument(0)).equals("BOOLEAN"))
                    .filter(call -> call.getArgument(1) instanceof BooleanLiteralExpr).toList();
            if (writes.size() != 1) {
                throw new IllegalStateException("Unable to validate appended boolean compatibility wrapper");
            }
            writes.getFirst().setArgument(1, new BooleanLiteralExpr(defaultValue));
            return corrected;
        }
        final String type = record.getNameAsString();
        return sources.parseExpression("""
                new NetworkBuffer.Type<%s>() {
                    private final NetworkBuffer.Type<%s> compatibilityDelegate = %s;

                    @Override
                    public void write(NetworkBuffer buffer, %s value) {
                        compatibilityDelegate.write(buffer, value);
                        buffer.write(NetworkBuffer.BOOLEAN, %s);
                    }

                    @Override
                    public %s read(NetworkBuffer buffer) {
                        var value = compatibilityDelegate.read(buffer);
                        buffer.read(NetworkBuffer.BOOLEAN);
                        return value;
                    }
                }
                """.formatted(type, type, current, type, defaultValue, type), "appended boolean serializer");
    }

    private static RecordDeclaration requireRecord(ResolvedType type) {
        if (!(type.declaration() instanceof RecordDeclaration record)) {
            throw new IllegalStateException("Retained packet " + type.qualifiedName() + " is not a source record");
        }
        return record;
    }

    private static String semanticKey(PacketMigrationScanner.Migration migration) {
        return migration.kind() + ":" + migration.ids() + ':' + migration.targetEnum()
                + ':' + migration.fixedSize() + ':' + migration.discriminator() + ':' + migration.defaultValue()
                + ':' + migration.falseId() + ':' + migration.trueId();
    }

    private static List<Node> obsoletePrivateHelpers(ResolvedType packetType, Expression serializer) {
        final Set<String> called = serializer.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().isEmpty()).map(call -> call.getNameAsString())
                .collect(java.util.stream.Collectors.toSet());
        return packetType.declaration().getMethods().stream().filter(method -> method.isPrivate())
                .filter(method -> called.contains(method.getNameAsString()))
                .filter(method -> packetType.declaration().findAll(MethodCallExpr.class).stream()
                        .filter(call -> call.getNameAsString().equals(method.getNameAsString())).count() == 1)
                .map(method -> (Node) method).toList();
    }

    private static Expression uniqueCodec(List<MethodCallExpr> writes, String... excluded) {
        final Set<String> names = Set.of(excluded);
        final List<Expression> matches = writes.stream().map(call -> call.getArgument(0))
                .filter(codec -> !names.contains(terminalName(codec))).map(Expression::clone).distinct().toList();
        if (matches.size() != 1) throw new IllegalStateException("Unable to identify unique payload object codec");
        return matches.getFirst();
    }

    private static Expression uniqueNamedCodec(List<MethodCallExpr> writes, String name) {
        final List<Expression> matches = writes.stream().map(call -> call.getArgument(0))
                .filter(codec -> terminalName(codec).equals(name)).map(Expression::clone).distinct().toList();
        if (matches.size() != 1) throw new IllegalStateException("Unable to identify unique " + name + " codec");
        return matches.getFirst();
    }

    private static String terminalName(Expression expression) {
        if (expression instanceof NameExpr name) return name.getNameAsString();
        if (expression instanceof FieldAccessExpr field) return field.getNameAsString();
        return "";
    }

    private static void requireMatchingEnum(ResolvedType enumType, PacketMigrationScanner.Migration migration) {
        if (!(enumType.declaration() instanceof EnumDeclaration declaration)) {
            throw new IllegalStateException("Migrated serializer leaf " + enumType.qualifiedName()
                    + " is not a source enum");
        }
        final Set<String> sourceConstants = new LinkedHashSet<>();
        declaration.getEntries().forEach(entry -> sourceConstants.add(entry.getNameAsString()));
        if (!sourceConstants.equals(migration.ids().keySet())) {
            throw new IllegalStateException("Target and source enum constants do not match for "
                    + enumType.qualifiedName() + ": " + migration.ids().keySet() + " versus " + sourceConstants);
        }
    }

    private static String codecExpression(ResolvedType enumType, PacketMigrationScanner.Migration migration) {
        final String enumName = enumType.declaration().getNameAsString();
        final StringBuilder source = new StringBuilder("NetworkBuffer.OPTIONAL_VAR_INT.transform(\n")
                .append("        (Integer id) -> id == null ? null : switch (id) {\n");
        migration.ids().entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(entry -> source
                .append("            case ").append(entry.getValue()).append(" -> ").append(enumName).append('.')
                .append(entry.getKey()).append(";\n"));
        source.append("            default -> throw new IllegalArgumentException(\"Unknown ")
                .append(enumName).append(" id: \" + id);\n")
                .append("        },\n")
                .append("        (").append(enumName).append(" value) -> value == null ? null : switch (value) {\n");
        migration.ids().forEach((name, id) -> source.append("            case ").append(name).append(" -> ")
                .append(id).append(";\n"));
        return source.append("        }\n").append(")").toString();
    }

    private static List<Integer> append(List<Integer> path, int component) {
        final List<Integer> result = new ArrayList<>(path);
        result.add(component);
        return List.copyOf(result);
    }

    private record CodecReference(Expression expression, ResolvedType owner, FieldLocation field,
                                  List<Integer> componentPath) {
    }

    private record ResolvedExpression(Expression expression, ResolvedType owner, FieldLocation field,
                                      List<Integer> componentPath) {
    }

    private record ResolvedType(ParsedSource source, TypeDeclaration<?> declaration, String qualifiedName) {
    }

    private record FieldLocation(Path source, String owner, String name) {
    }

    private record SerializerSlot(Path source, String owner, String field, List<Integer> componentPath) {
    }

    private record PlannedEdit(Expression expression, Expression replacement, String semantic, List<Node> removals) {
    }

    private static final class SourceIndex {
        private final JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setLexicalPreservationEnabled(true));
        private final Map<String, List<Path>> bySimpleName = new HashMap<>();
        private final Map<Path, ParsedSource> parsed = new HashMap<>();

        private SourceIndex(Path sourceRoot) throws IOException {
            final Path javaRoot = sourceRoot.resolve("src/main/java");
            try (var files = Files.walk(javaRoot)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    final String fileName = path.getFileName().toString();
                    bySimpleName.computeIfAbsent(fileName.substring(0, fileName.length() - 5), ignored -> new ArrayList<>())
                            .add(path);
                });
            }
        }

        private ResolvedExpression dereference(CodecReference reference) {
            final Expression expression = reference.expression();
            if (expression instanceof NameExpr name) {
                return field(reference.owner(), name.getNameAsString());
            }
            if (expression instanceof FieldAccessExpr field) {
                final ResolvedType owner = resolveType(field.getScope().toString(), reference.owner());
                return field(owner, field.getNameAsString());
            }
            return new ResolvedExpression(expression, reference.owner(), reference.field(), reference.componentPath());
        }

        private ResolvedExpression field(ResolvedType owner, String name) {
            final List<VariableDeclarator> matches = owner.declaration().getFields().stream()
                    .flatMap(field -> field.getVariables().stream())
                    .filter(variable -> variable.getNameAsString().equals(name))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one field " + owner.qualifiedName() + '.' + name
                        + " but found " + matches.size());
            }
            final VariableDeclarator variable = matches.getFirst();
            final FieldDeclaration field = variable.getParentNode()
                    .filter(FieldDeclaration.class::isInstance).map(FieldDeclaration.class::cast)
                    .orElseThrow(() -> new IllegalStateException(name + " is not a field"));
            if (!field.isStatic() || !variable.getType().isClassOrInterfaceType()) {
                throw new IllegalStateException(owner.qualifiedName() + '.' + name
                        + " is not a static NetworkBuffer.Type field");
            }
            final ClassOrInterfaceType type = variable.getType().asClassOrInterfaceType();
            if (!type.getNameAsString().equals("Type")
                    || type.getScope().map(Object::toString).filter("NetworkBuffer"::equals).isEmpty()) {
                throw new IllegalStateException(owner.qualifiedName() + '.' + name
                        + " is not a static NetworkBuffer.Type field");
            }
            return new ResolvedExpression(variable.getInitializer()
                    .orElseThrow(() -> new IllegalStateException("Missing initializer for " + owner.qualifiedName() + '.' + name)),
                    owner, new FieldLocation(owner.source().path(), owner.qualifiedName(), name), List.of());
        }

        private ResolvedType recordComponentType(ResolvedType owner, int ordinal, boolean requireNullable) {
            if (!(owner.declaration() instanceof RecordDeclaration record) || record.getParameters().size() <= ordinal) {
                throw new IllegalStateException(owner.qualifiedName() + " is not a record with component " + ordinal);
            }
            final Parameter parameter = record.getParameter(ordinal);
            if (requireNullable && !isNullable(parameter)) {
                throw new IllegalStateException("Record component " + ordinal + " in " + owner.qualifiedName()
                        + " is not explicitly nullable");
            }
            if (!(parameter.getType() instanceof ClassOrInterfaceType type)) {
                throw new IllegalStateException("Record component " + ordinal + " in " + owner.qualifiedName()
                        + " is not a reference type");
            }
            return resolveType(type.getNameWithScope(), owner);
        }

        private ResolvedType resolveUnique(String simpleName) {
            final List<Path> paths = bySimpleName.getOrDefault(simpleName, List.of());
            final List<ResolvedType> matches = paths.stream().map(this::parse)
                    .flatMap(source -> source.topLevelTypes().stream())
                    .filter(type -> type.declaration().getNameAsString().equals(simpleName))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one source type named " + simpleName + " but found " + matches.size());
            }
            return matches.getFirst();
        }

        private ResolvedType resolveType(String name, ResolvedType context) {
            final String simpleName = name.substring(name.lastIndexOf('.') + 1);
            final List<ResolvedType> indexed = indexedTypes(simpleName);
            if (name.contains(".")) {
                final Set<String> qualified = new LinkedHashSet<>();
                qualified.add(name);
                if (!context.source().packageName().isEmpty()) {
                    qualified.add(context.source().packageName() + '.' + name);
                }
                context.source().unit().getImports().stream()
                        .filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                        .filter(imported -> imported.getName().getIdentifier().equals(name.substring(0, name.indexOf('.'))))
                        .forEach(imported -> qualified.add(imported.getNameAsString()
                                + name.substring(name.indexOf('.'))));
                return unique(name, context, indexed.stream()
                        .filter(type -> qualified.contains(type.qualifiedName())).toList());
            }

            final List<ResolvedType> lexical = lexicalTypes(context, simpleName);
            if (!lexical.isEmpty()) return unique(name, context, lexical);

            final Set<String> explicitImports = new LinkedHashSet<>();
            context.source().unit().getImports().stream()
                    .filter(imported -> !imported.isStatic() && !imported.isAsterisk())
                    .filter(imported -> imported.getName().getIdentifier().equals(simpleName))
                    .forEach(imported -> explicitImports.add(imported.getNameAsString()));
            final List<ResolvedType> explicit = indexed.stream()
                    .filter(type -> explicitImports.contains(type.qualifiedName())).toList();
            if (!explicit.isEmpty()) return unique(name, context, explicit);

            final List<ResolvedType> samePackage = indexed.stream()
                    .filter(type -> type.source().packageName().equals(context.source().packageName()))
                    .filter(type -> type.declaration().getParentNode().orElse(null) instanceof CompilationUnit)
                    .toList();
            if (!samePackage.isEmpty()) return unique(name, context, samePackage);

            final Set<String> wildcardPackages = new LinkedHashSet<>();
            context.source().unit().getImports().stream()
                    .filter(imported -> !imported.isStatic() && imported.isAsterisk())
                    .forEach(imported -> wildcardPackages.add(imported.getNameAsString()));
            final List<ResolvedType> wildcard = indexed.stream()
                    .filter(type -> wildcardPackages.stream()
                            .anyMatch(packageName -> type.qualifiedName().equals(packageName + '.' + simpleName)))
                    .toList();
            return unique(name, context, wildcard);
        }

        private List<ResolvedType> indexedTypes(String simpleName) {
            return bySimpleName.getOrDefault(simpleName, List.of()).stream()
                    .map(this::parse).flatMap(source -> source.allTypes().stream())
                    .filter(type -> type.declaration().getNameAsString().equals(simpleName)).toList();
        }

        private static List<ResolvedType> lexicalTypes(ResolvedType context, String simpleName) {
            final List<ResolvedType> matches = new ArrayList<>();
            Node current = context.declaration();
            while (current instanceof TypeDeclaration<?> declaration) {
                if (declaration.getNameAsString().equals(simpleName)) {
                    matches.add(new ResolvedType(context.source(), declaration, context.source().qualifiedName(declaration)));
                }
                declaration.getMembers().stream()
                        .filter(TypeDeclaration.class::isInstance).map(TypeDeclaration.class::cast)
                        .filter(type -> type.getNameAsString().equals(simpleName))
                        .map(type -> new ResolvedType(context.source(), type, context.source().qualifiedName(type)))
                        .forEach(matches::add);
                current = declaration.getParentNode().orElse(null);
            }
            return matches.stream().distinct().toList();
        }

        private static ResolvedType unique(String name, ResolvedType context, List<ResolvedType> matches) {
            if (matches.size() != 1) {
                throw new IllegalStateException("Unable to resolve source type " + name + " from "
                        + context.source().path() + "; found " + matches.size());
            }
            return matches.getFirst();
        }

        private Expression parseExpression(String source, String description) {
            return requireSuccessful(parser.parseExpression(source), "Unable to parse " + description);
        }

        private ParsedSource parse(Path path) {
            return parsed.computeIfAbsent(path, sourcePath -> {
                try {
                    final CompilationUnit unit = requireSuccessful(parser.parse(sourcePath), "Unable to parse " + sourcePath);
                    LexicalPreservingPrinter.setup(unit);
                    return new ParsedSource(sourcePath, unit);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to read " + sourcePath, exception);
                }
            });
        }

        private static <T> T requireSuccessful(ParseResult<T> result, String message) {
            if (!result.isSuccessful() || !result.getProblems().isEmpty() || result.getResult().isEmpty()) {
                throw new IllegalStateException(message + ": " + result.getProblems());
            }
            return result.getResult().orElseThrow();
        }

        private void writeChanged() throws IOException {
            for (ParsedSource source : parsed.values()) {
                if (source.unit().getTokenRange().map(range -> range.getBegin().getText()).isEmpty()) continue;
                final String printed = LexicalPreservingPrinter.print(source.unit());
                if (!printed.equals(Files.readString(source.path()))) Files.writeString(source.path(), printed);
            }
        }

        private static boolean isNullable(Parameter parameter) {
            return hasNullableAnnotation(parameter.getAnnotations())
                    || hasNullableAnnotation(parameter.getType().getAnnotations());
        }

        private static boolean hasNullableAnnotation(List<? extends AnnotationExpr> annotations) {
            return annotations.stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("Nullable"));
        }
    }

    private record ParsedSource(Path path, CompilationUnit unit) {
        private String packageName() {
            return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        }

        private List<ResolvedType> topLevelTypes() {
            return unit.getTypes().stream().map(type -> new ResolvedType(this, type,
                    packageName().isEmpty() ? type.getNameAsString() : packageName() + '.' + type.getNameAsString())).toList();
        }

        private List<ResolvedType> allTypes() {
            final List<ResolvedType> result = new ArrayList<>();
            for (Node node : unit.findAll(Node.class)) {
                if (node instanceof TypeDeclaration<?> type) {
                    result.add(new ResolvedType(this, type, qualifiedName(type)));
                }
            }
            return result;
        }

        private String qualifiedName(TypeDeclaration<?> type) {
            final List<String> names = new ArrayList<>();
            Node current = type;
            while (current instanceof TypeDeclaration<?> declaration) {
                names.addFirst(declaration.getNameAsString());
                current = declaration.getParentNode().orElse(null);
            }
            return (packageName().isEmpty() ? "" : packageName() + '.') + String.join(".", names);
        }
    }
}
