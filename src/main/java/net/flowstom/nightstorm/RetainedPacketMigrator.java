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
        final Map<String, WireMigration> wirePlans = readWirePlans(sourceRoot);
        sources.existingWireAdapters.putAll(wirePlans);
        final Map<SerializerSlot, PlannedEdit> edits = new LinkedHashMap<>();
        for (PacketMigrationScanner.Migration requested : migrations) {
            PacketMigrationScanner.Migration resolvedMigration = requested;
            if (requested.wire() != null) {
                final String key = wireKey(requested.packet(), requested.path());
                final WireMigration previous = wirePlans.get(key);
                final WireMigration plan = previous == null ? requested.wire() : WireMigration.compose(previous, requested.wire());
                wirePlans.put(key, plan);
                resolvedMigration = PacketMigrationScanner.Migration.wire(plan, requested.path()).withPacket(requested.packet());
            }
            final PacketMigrationScanner.Migration migration = resolvedMigration;
            final ResolvedType packetType = sources.resolveUnique(migration.packet().className());
            if (migration.kind() == PacketMigrationScanner.Kind.MOVED_NESTED_FLOATS) {
                planMovedNestedFloats(sources, edits, packetType, migration);
                continue;
            }
            if (migration.kind() != PacketMigrationScanner.Kind.OPTIONAL_ENUM
                    && migration.kind() != PacketMigrationScanner.Kind.BYTE_ARRAY_BIT_SET) {
                planSemanticMigration(sources, edits, wirePlans, packetType, migration);
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
                if (leaf) {
                    final FieldLocation field = resolved.field();
                    if (field == null) {
                        throw new IllegalStateException("Serializer component " + migration.path()
                                + " is not backed by a source field");
                    }
                    final SerializerSlot slot = new SerializerSlot(field.source(), field.owner(), field.name(), componentPath);
                    final Expression replacement;
                    if (migration.kind() == PacketMigrationScanner.Kind.OPTIONAL_ENUM) {
                        final ResolvedType enumType = sources.recordComponentType(owner, component, true);
                        requireMatchingEnum(enumType, migration);
                        replacement = sources.parseExpression(codecExpression(enumType, migration),
                                "optional enum codec expression");
                    } else {
                        replacement = byteArrayBitSetCodec(sources, owner, componentCodec, component);
                    }
                    final PlannedEdit edit = new PlannedEdit(componentCodec, replacement, semanticKey(migration), List.of());
                    final PlannedEdit previous = edits.putIfAbsent(slot, edit);
                    if (previous != null && !previous.semantic().equals(semanticKey(migration))) {
                        throw new IllegalStateException("Conflicting migrations resolve to the same serializer slot in "
                                + owner.qualifiedName());
                    }
                } else {
                    final ResolvedType componentType = sources.recordComponentType(owner, component, false);
                    codec = new CodecReference(componentCodec, componentType, resolved.field(), componentPath);
                }
            }
        }
        for (PlannedEdit edit : edits.values()) {
            if (!edit.expression().equals(edit.replacement())) edit.expression().replace(edit.replacement());
            edit.removals().forEach(Node::remove);
        }
        sources.writeChanged();
        if (!wirePlans.isEmpty()) Json.write(sourceRoot.resolve(".nightstorm/wire-adapters.json"), wirePlans);
    }

    static Map<String, WireMigration> readWirePlans(Path sourceRoot) throws IOException {
        final Path path = sourceRoot.resolve(".nightstorm/wire-adapters.json");
        if (!Files.exists(path)) return new LinkedHashMap<>();
        return Json.MAPPER.readValue(path.toFile(), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, WireMigration>>() { });
    }

    static String wireKey(PacketUpdater.RetainedPacket packet) {
        return packet.className() + "#" + packet.serializer();
    }

    static String wireKey(PacketUpdater.RetainedPacket packet, List<Integer> path) {
        return wireKey(packet) + (path.isEmpty() ? "" : "@" + path);
    }

    private static void planSemanticMigration(SourceIndex sources, Map<SerializerSlot, PlannedEdit> edits,
                                               Map<String, WireMigration> wirePlans,
                                               ResolvedType packetType,
                                               PacketMigrationScanner.Migration migration) {
        CodecReference reference = new CodecReference(
                sources.parseExpression(migration.packet().serializer(), "retained packet serializer"),
                packetType, null, List.of());
        for (int component : migration.path()) {
            final ResolvedExpression parent = sources.dereference(reference);
            final MethodCallExpr template = parent.expression().toMethodCallExpr()
                    .filter(call -> call.getNameAsString().equals("template")
                            && call.getScope().map(Object::toString).orElse("").endsWith("NetworkBufferTemplate"))
                    .orElseThrow(() -> new IllegalStateException("Nested projection requires a source template"));
            packetType = sources.recordComponentType(parent.owner(), component, false);
            reference = new CodecReference(template.getArgument(component * 2), packetType, parent.field(),
                    append(parent.componentPath(), component));
        }
        final ResolvedExpression resolved = sources.dereference(reference);
        if (resolved.field() == null) {
            throw new IllegalStateException("Retained packet serializer is not backed by a source field");
        }
        final SerializerSlot slot = new SerializerSlot(resolved.field().source(), resolved.field().owner(),
                resolved.field().name(), List.of());
        final PlannedEdit already = edits.get(slot);
        if (already != null) {
            if (!already.semantic().equals(semanticKey(migration))) throw new IllegalStateException("Conflicting shared serializer migrations");
            final WireMigration shared = sources.resolvedWirePlans.get(slot);
            if (shared != null) wirePlans.put(wireKey(migration.packet(), migration.path()), shared);
            return;
        }
        final Expression current = resolved.expression();
        final Expression replacement = switch (migration.kind()) {
            case REORDERED_BOOLEAN_ENUM -> reorderedBooleanSerializer(sources, packetType, current, migration);
            case LINEAR_POSITION_PATH -> linearPositionSerializer(sources, packetType, current, migration);
            case WIRE_PROJECTION -> {
                final String key = wireKey(migration.packet(), migration.path());
                final WireMigration previous = sources.existingWireAdapters.get(key);
                WireMigration plan = migration.wire();
                if (previous == null) {
                    plan = withSourceCodecs(plan, requireBaselineSerializer(sources, packetType, current, plan));
                }
                else if (!wireSerializer(sources, packetType, previous).equals(current)) {
                    throw new IllegalStateException("Saved wire adapter does not match the source serializer; review source edits before regenerating");
                }
                plan = migrateSourceShape(sources, packetType, plan, current);
                wirePlans.put(key, plan);
                sources.resolvedWirePlans.put(slot, plan);
                yield wireSerializer(sources, packetType, plan);
            }
            case APPENDED_BOOLEAN -> appendedBooleanSerializer(sources, packetType, current, migration.defaultValue());
            case OPTIONAL_ENUM, BYTE_ARRAY_BIT_SET, MOVED_NESTED_FLOATS ->
                    throw new IllegalStateException("Unexpected leaf migration");
        };
        final List<Node> removals = migration.kind() == PacketMigrationScanner.Kind.REORDERED_BOOLEAN_ENUM
                ? obsoletePrivateHelpers(packetType, current) : List.of();
        final PlannedEdit edit = new PlannedEdit(current, replacement, semanticKey(migration), removals);
        final PlannedEdit previous = edits.putIfAbsent(slot, edit);
        if (previous != null && !previous.semantic().equals(edit.semantic())) {
            throw new IllegalStateException("Conflicting whole-payload migrations for " + packetType.qualifiedName());
        }
    }

    /** Changes source APIs only for deletions and supported collection additions with neutral legacy values. */
    private static WireMigration migrateSourceShape(SourceIndex sources, ResolvedType owner, WireMigration plan,
                                                     Expression oldSerializer) {
        final Set<Integer> retained = plan.bindings().stream().filter(b -> b.source() >= 0)
                .map(WireMigration.Binding::source).collect(java.util.stream.Collectors.toSet());
        final boolean removed = retained.size() < plan.baseline().components().size();
        final boolean collections = plan.bindings().stream().anyMatch(b -> b.missing() && emptyCollection(b.javaType()) != null);
        if (!removed && !collections) return plan;
        if (plan.bindings().stream().anyMatch(b -> b.constant() != null || b.missing() && emptyCollection(b.javaType()) == null)
                || plan.bindings().stream().filter(b -> b.source() >= 0).count() != retained.size()) {
            throw new IllegalStateException("Source API migration requires a direct projection with supported collection additions");
        }
        final RecordDeclaration record = requireRecord(owner);
        final var oldParameters = record.getParameters().stream().map(Parameter::clone).toList();
        if (oldParameters.size() != plan.baseline().components().size()) throw new IllegalStateException("Source API arity differs from baseline");
        for (var constructor : record.getConstructors()) {
            final var statements = constructor.getBody().getStatements();
            if (statements.size() != 1 || !statements.get(0).isExplicitConstructorInvocationStmt()
                    || !statements.get(0).asExplicitConstructorInvocationStmt().isThis()
                    || statements.get(0).asExplicitConstructorInvocationStmt().getArguments().size() != oldParameters.size()) {
                throw new IllegalStateException("Source API migration requires constructors that delegate directly to the canonical record");
            }
        }
        for (int i = 0; i < oldParameters.size(); i++) {
            if (retained.contains(i)) continue;
            final String name = oldParameters.get(i).getNameAsString();
            final boolean used = record.findAll(NameExpr.class).stream().anyMatch(expression -> expression.getNameAsString().equals(name)
                    && !isWithin(expression, oldSerializer)
                    && expression.findAncestor(com.github.javaparser.ast.body.ConstructorDeclaration.class).isEmpty())
                    || record.findAll(FieldAccessExpr.class).stream().anyMatch(expression -> expression.getNameAsString().equals(name)
                    && !isWithin(expression, oldSerializer))
                    || record.findAll(MethodCallExpr.class).stream().anyMatch(expression -> expression.getNameAsString().equals(name)
                    && !isWithin(expression, oldSerializer));
            if (used) throw new IllegalStateException("Removed source component has non-serializer uses: " + name);
        }
        final Map<Integer, WireMigration.Binding> byComponent = new HashMap<>();
        plan.bindings().forEach(binding -> byComponent.put(binding.component(), binding));
        final var parameters = new com.github.javaparser.ast.NodeList<Parameter>();
        final List<String> compatibilityArguments = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < plan.target().components().size(); i++) {
            final WireMigration.Binding binding = byComponent.get(i);
            final Parameter parameter;
            if (binding.source() >= 0) {
                parameter = oldParameters.get(binding.source()).clone();
                compatibilityArguments.add(parameter.getNameAsString());
            } else {
                final String collectionType = binding.javaType().startsWith("java.util.Map<") ? "java.util.Map" : "java.util.List";
                final String imported = sources.importName(owner, collectionType);
                parameter = new Parameter(sources.parser.parseType(binding.javaType().replace(collectionType, imported)).getResult().orElseThrow(),
                        plan.target().components().get(i).name());
                compatibilityArguments.add(imported + ".of()");
            }
            if (!names.add(parameter.getNameAsString())) throw new IllegalStateException("Source component names collide");
            parameters.add(parameter);
        }
        final String legacySignature = oldParameters.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "));
        final var constructor = sources.parser.parseBodyDeclaration("public " + record.getNameAsString() + "(" + legacySignature
                + ") { this(" + String.join(", ", compatibilityArguments) + "); }").getResult().orElseThrow();
        for (var existing : record.getConstructors()) {
            final var call = existing.getBody().getStatement(0).asExplicitConstructorInvocationStmt();
            final var arguments = new com.github.javaparser.ast.NodeList<Expression>();
            for (int i = 0; i < plan.target().components().size(); i++) {
                final var binding = byComponent.get(i);
                arguments.add(binding.source() >= 0 ? call.getArgument(binding.source()).clone()
                        : sources.parseExpression(compatibilityArguments.get(i), "legacy collection value"));
            }
            call.setArguments(arguments);
        }
        record.setParameters(parameters);
        record.addMember(constructor);
        final List<WireMigration.Binding> bindings = plan.bindings().stream().map(binding ->
                new WireMigration.Binding(binding.component(), binding.component(), binding.javaType(), binding.networkType(), null)).toList();
        // The source record now represents the complete target schema; later adapters compose from it.
        return new WireMigration(plan.target(), plan.target(), bindings);
    }

    private static boolean isWithin(Node node, Node parent) {
        for (Node cursor = node; cursor != null; cursor = cursor.getParentNode().orElse(null)) if (cursor == parent) return true;
        return false;
    }

    private static String emptyCollection(String type) {
        if (type.startsWith("java.util.Map<")) return "java.util.Map.of()";
        if (type.startsWith("java.util.List<")) return "java.util.List.of()";
        return null;
    }

    private static void planMovedNestedFloats(SourceIndex sources, Map<SerializerSlot, PlannedEdit> edits,
                                               ResolvedType packetType,
                                               PacketMigrationScanner.Migration migration) {
        if (migration.path().size() != 1) {
            throw new IllegalStateException("Moved nested float migration requires one collection component path");
        }
        final ResolvedType mappingType = sources.recordListElementType(packetType, migration.path().getFirst());
        final RecordDeclaration mapping = requireRecord(mappingType);
        if (mapping.getParameters().size() != 2) {
            throw new IllegalStateException("Positioned collection element must be a two-component source record");
        }
        final int nestedIndex = 1;
        final ResolvedType nestedType = sources.recordComponentType(mappingType, nestedIndex, false);
        final RecordDeclaration nested = requireRecord(nestedType);
        int positionedIndex = -1;
        ResolvedType positionedType = null;
        for (int index = 0; index < nested.getParameters().size(); index++) {
            final Parameter parameter = nested.getParameter(index);
            if (!(parameter.getType() instanceof ClassOrInterfaceType)) continue;
            final ResolvedType candidate;
            try {
                candidate = sources.recordComponentType(nestedType, index, false);
            } catch (IllegalStateException ignored) {
                continue;
            }
            if (!(candidate.declaration() instanceof RecordDeclaration record) || record.getParameters().size() < 2) {
                continue;
            }
            final int firstFloat = record.getParameters().size() - 2;
            if (isFloat(record.getParameter(firstFloat)) && isFloat(record.getParameter(firstFloat + 1))) {
                if (positionedType != null) {
                    throw new IllegalStateException("Nested source record has multiple trailing float-pair components");
                }
                positionedIndex = index;
                positionedType = candidate;
            }
        }
        if (positionedType == null) {
            throw new IllegalStateException("Unable to locate nested source record containing the moved float pair");
        }
        final RecordDeclaration positioned = requireRecord(positionedType);
        final int firstFloat = positioned.getParameters().size() - 2;
        final ResolvedExpression mappingSerializer = sources.field(mappingType, "SERIALIZER");
        final ResolvedExpression positionedSerializer = sources.field(positionedType, "SERIALIZER");
        final Expression mappingReplacement = positionedCollectionSerializer(sources, mappingType, mapping,
                nestedType, nested, nestedIndex, positionedType, positioned, positionedIndex, firstFloat,
                mappingSerializer.expression());
        final Expression positionedReplacement = serializerWithoutTrailingFloats(sources, positionedType,
                positioned, positionedSerializer.expression(), firstFloat);
        final String semantic = semanticKey(migration);
        addEdit(edits, mappingSerializer, mappingReplacement, semantic);
        addEdit(edits, positionedSerializer, positionedReplacement, semantic);
    }

    private static void addEdit(Map<SerializerSlot, PlannedEdit> edits, ResolvedExpression resolved,
                                Expression replacement, String semantic) {
        if (resolved.field() == null) throw new IllegalStateException("Migrated serializer is not backed by a field");
        final SerializerSlot slot = new SerializerSlot(resolved.field().source(), resolved.field().owner(),
                resolved.field().name(), List.of());
        final PlannedEdit edit = new PlannedEdit(resolved.expression(), replacement, semantic, List.of());
        final PlannedEdit previous = edits.putIfAbsent(slot, edit);
        if (previous != null && !previous.semantic().equals(semantic)) {
            throw new IllegalStateException("Conflicting moved-float migrations for " + resolved.field().owner());
        }
    }

    private static Expression positionedCollectionSerializer(SourceIndex sources, ResolvedType mappingType,
                                                               RecordDeclaration mapping, ResolvedType nestedType,
                                                               RecordDeclaration nested, int nestedIndex,
                                                               ResolvedType positionedType,
                                                               RecordDeclaration positioned, int positionedIndex,
                                                               int firstFloat, Expression current) {
        if (current.toString().contains("positionCompatibilityDelegate")) return current.clone();
        final String mappingName = mapping.getNameAsString();
        final String nestedName = nested.getNameAsString();
        final String positionedName = positioned.getNameAsString();
        final String nestedComponent = mapping.getParameter(nestedIndex).getNameAsString();
        final String positionedComponent = nested.getParameter(positionedIndex).getNameAsString();
        final String firstName = positioned.getParameter(firstFloat).getNameAsString();
        final String secondName = positioned.getParameter(firstFloat + 1).getNameAsString();
        final String adjustedPosition = constructor(positionedName, positioned, "positionedValue",
                Map.of(firstFloat, "position0", firstFloat + 1, "position1"));
        final String adjustedNested = constructor(nestedName, nested, "nestedValue",
                Map.of(positionedIndex, "adjustedPosition"));
        final String adjustedMapping = constructor(mappingName, mapping, "value",
                Map.of(nestedIndex, "adjustedNested"));
        return sources.parseExpression("""
                new NetworkBuffer.Type<%s>() {
                    private final NetworkBuffer.Type<%s> positionCompatibilityDelegate = %s;

                    @Override
                    public void write(NetworkBuffer buffer, %s value) {
                        positionCompatibilityDelegate.write(buffer, value);
                        var positionedValue = value.%s().%s();
                        buffer.write(NetworkBuffer.FLOAT, positionedValue == null ? 0.0f : positionedValue.%s());
                        buffer.write(NetworkBuffer.FLOAT, positionedValue == null ? 0.0f : positionedValue.%s());
                    }

                    @Override
                    public %s read(NetworkBuffer buffer) {
                        var value = positionCompatibilityDelegate.read(buffer);
                        float position0 = buffer.read(NetworkBuffer.FLOAT);
                        float position1 = buffer.read(NetworkBuffer.FLOAT);
                        var nestedValue = value.%s();
                        var positionedValue = nestedValue.%s();
                        if (positionedValue == null) return value;
                        var adjustedPosition = %s;
                        var adjustedNested = %s;
                        return %s;
                    }
                }
                """.formatted(mappingName, mappingName, current, mappingName, nestedComponent,
                positionedComponent, firstName, secondName, mappingName, nestedComponent, positionedComponent,
                adjustedPosition, adjustedNested, adjustedMapping), "positioned collection serializer");
    }

    private static Expression serializerWithoutTrailingFloats(SourceIndex sources, ResolvedType owner,
                                                               RecordDeclaration record, Expression current,
                                                               int firstFloat) {
        final ObjectCreationExpr replacement = current.clone().toObjectCreationExpr()
                .orElseThrow(() -> new IllegalStateException("Moved float pair requires a custom serializer in "
                        + owner.qualifiedName()));
        final Set<String> names = Set.of(record.getParameter(firstFloat).getNameAsString(),
                record.getParameter(firstFloat + 1).getNameAsString());
        final List<MethodCallExpr> writes = replacement.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("write") && call.getArguments().size() == 2)
                .filter(call -> terminalName(call.getArgument(0)).equals("FLOAT"))
                .filter(call -> names.stream().anyMatch(name -> isAccessor(call.getArgument(1), "value", name)))
                .toList();
        writes.forEach(call -> call.findAncestor(com.github.javaparser.ast.stmt.ExpressionStmt.class)
                .orElseThrow(() -> new IllegalStateException("FLOAT write is not a statement in "
                        + owner.qualifiedName())).remove());
        final List<VariableDeclarator> reads = replacement.findAll(VariableDeclarator.class).stream()
                .filter(variable -> names.contains(variable.getNameAsString()))
                .filter(variable -> variable.getInitializer().flatMap(Expression::toMethodCallExpr)
                        .filter(call -> call.getNameAsString().equals("read") && call.getArguments().size() == 1)
                        .filter(call -> terminalName(call.getArgument(0)).equals("FLOAT")).isPresent())
                .toList();
        reads.forEach(variable -> variable.findAncestor(com.github.javaparser.ast.stmt.ExpressionStmt.class)
                .orElseThrow(() -> new IllegalStateException("FLOAT read is not a statement in "
                        + owner.qualifiedName())).remove());
        final List<ObjectCreationExpr> constructors = replacement.findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> creation.getType().getNameAsString().equals(record.getNameAsString()))
                .filter(creation -> creation.getArguments().size() == record.getParameters().size()).toList();
        if (constructors.size() != 1) {
            throw new IllegalStateException("Unable to locate positioned value construction in " + owner.qualifiedName());
        }
        final ObjectCreationExpr constructor = constructors.getFirst();
        final boolean alreadyMigrated = writes.isEmpty() && reads.isEmpty()
                && constructor.getArgument(firstFloat).toString().matches("0(?:\\.0)?[fF]?")
                && constructor.getArgument(firstFloat + 1).toString().matches("0(?:\\.0)?[fF]?");
        if (!alreadyMigrated && (writes.size() != 2 || reads.size() != 2)) {
            throw new IllegalStateException("Expected two trailing FLOAT reads and writes in " + owner.qualifiedName());
        }
        constructor.setArgument(firstFloat, sources.parseExpression("0.0f", "position placeholder"));
        constructor.setArgument(firstFloat + 1, sources.parseExpression("0.0f", "position placeholder"));
        return sources.parseExpression(replacement.toString(), "serializer without nested positions");
    }

    private static boolean isAccessor(Expression expression, String receiver, String name) {
        final String value = expression.toString().replace("()", "");
        return value.equals(receiver + '.' + name);
    }

    private static boolean isFloat(Parameter parameter) {
        return parameter.getType().isPrimitiveType()
                && parameter.getType().asPrimitiveType().getType()
                == com.github.javaparser.ast.type.PrimitiveType.Primitive.FLOAT;
    }

    private static String constructor(String type, RecordDeclaration record, String receiver,
                                      Map<Integer, String> replacements) {
        final List<String> arguments = new ArrayList<>();
        for (int index = 0; index < record.getParameters().size(); index++) {
            arguments.add(replacements.getOrDefault(index,
                    receiver + '.' + record.getParameter(index).getNameAsString() + "()"));
        }
        return "new " + type + '(' + String.join(", ", arguments) + ')';
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

    private static Map<Integer, String> requireBaselineSerializer(SourceIndex sources, ResolvedType type,
                                                                  Expression expression, WireMigration migration) {
        final RecordDeclaration record = requireRecord(type);
        final List<Integer> expected = migration.baseline().fields().stream().map(WireSchema.Field::component).toList();
        final List<Integer> actual = new ArrayList<>();
        final Map<Integer, String> codecs = new LinkedHashMap<>();
        if (expression instanceof MethodCallExpr template && template.getNameAsString().equals("template")
                && template.getScope().map(Object::toString).orElse("").endsWith("NetworkBufferTemplate")) {
            if (template.getArguments().size() != expected.size() * 2 + 1) {
                throw new IllegalStateException("Source template arity differs from the baseline wire schema in " + type.qualifiedName()
                        + ": " + template.getArguments().size() + " arguments versus " + (expected.size() * 2 + 1));
            }
            if (!(template.getArguments().getLast().orElseThrow() instanceof com.github.javaparser.ast.expr.MethodReferenceExpr constructor)
                    || !constructor.getIdentifier().equals("new") || !constructor.getScope().toString().equals(record.getNameAsString())) {
                throw new IllegalStateException("Cannot prove source template constructor binding");
            }
            for (int i = 0; i < expected.size(); i++) {
                final Expression getter = template.getArgument(i * 2 + 1);
                if (!(getter instanceof com.github.javaparser.ast.expr.MethodReferenceExpr reference)
                        || !reference.getScope().toString().equals(record.getNameAsString())) {
                    throw new IllegalStateException("Cannot prove source template getter binding");
                }
                final int component = sourceComponent(record, reference.getIdentifier());
                actual.add(component);
                if (codecs.putIfAbsent(component, template.getArgument(i * 2).toString()) != null) {
                    throw new IllegalStateException("Source template binds a component more than once");
                }
            }
        } else if (expression instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            final var writers = creation.getAnonymousClassBody().orElseThrow().stream()
                    .filter(com.github.javaparser.ast.body.MethodDeclaration.class::isInstance)
                    .map(com.github.javaparser.ast.body.MethodDeclaration.class::cast)
                    .filter(method -> method.getNameAsString().equals("write") && method.getParameters().size() == 2).toList();
            if (writers.size() != 1 || writers.getFirst().getBody().isEmpty()) {
                throw new IllegalStateException("Cannot prove source serializer writer");
            }
            final var writer = writers.getFirst();
            final String buffer = writer.getParameter(0).getNameAsString();
            final String value = writer.getParameter(1).getNameAsString();
            final var statements = writer.getBody().orElseThrow().getStatements();
            final List<List<com.github.javaparser.ast.stmt.Statement>> chunks = new ArrayList<>();
            for (var statement : statements) {
                final Set<Integer> referenced = sourceComponents(record, statement, value);
                if (referenced.size() != 1) throw new IllegalStateException("Unrecognized source serializer operation: " + statement);
                final int component = referenced.iterator().next();
                if (actual.isEmpty() || actual.getLast() != component) {
                    actual.add(component);
                    chunks.add(new ArrayList<>());
                }
                chunks.getLast().add(statement);
            }
            for (int i = 0; i < actual.size(); i++) {
                final int component = actual.get(i);
                final List<com.github.javaparser.ast.stmt.Statement> chunk = chunks.get(i);
                final String codec = directWriteCodec(chunk, buffer, component, record, value);
                codecs.put(component, codec != null ? codec
                        : sources.componentNetworkType(type, component, chunk, buffer, value));
            }
        } else throw new IllegalStateException("Source serializer is not a supported template or straight-line writer");
        if (!expected.equals(actual)) throw new IllegalStateException("Source serializer field bindings disagree with the baseline: " + actual + " versus " + expected);
        return Map.copyOf(codecs);
    }

    private static WireMigration withSourceCodecs(WireMigration migration, Map<Integer, String> sourceCodecs) {
        final List<WireMigration.Binding> bindings = new ArrayList<>();
        for (WireMigration.Binding binding : migration.bindings()) {
            if (!binding.networkType().isEmpty()) {
                bindings.add(binding);
                continue;
            }
            if (binding.source() < 0) throw new IllegalStateException("Missing target codec for an unbound field");
            final WireSchema.Codec baselineCodec = migration.baseline().fields().stream()
                    .filter(field -> field.component() == binding.source()).map(WireSchema.Field::codec)
                    .findFirst().orElseThrow();
            final WireSchema.Codec targetCodec = migration.target().fields().stream()
                    .filter(field -> field.component() == binding.component()).map(WireSchema.Field::codec)
                    .findFirst().orElseThrow();
            if (!baselineCodec.equals(targetCodec)) {
                throw new IllegalStateException("No source codec for changed target encoding " + targetCodec);
            }
            final String sourceCodec = sourceCodecs.get(binding.source());
            if (sourceCodec == null) throw new IllegalStateException("No source codec for retained component " + binding.source());
            bindings.add(new WireMigration.Binding(binding.component(), binding.source(), binding.javaType(), sourceCodec,
                    binding.constant()));
        }
        return new WireMigration(migration.baseline(), migration.target(), bindings);
    }

    private static String directWriteCodec(List<com.github.javaparser.ast.stmt.Statement> statements, String buffer,
                                           int component, RecordDeclaration record, String value) {
        if (statements.size() != 1 || !statements.getFirst().isExpressionStmt()
                || !(statements.getFirst().asExpressionStmt().getExpression() instanceof MethodCallExpr call)
                || !call.getNameAsString().equals("write") || call.getArguments().size() != 2
                || !call.getScope().map(Object::toString).orElse("").equals(buffer)
                || !sourceComponents(record, call.getArgument(1), value).equals(Set.of(component))) return null;
        return call.getArgument(0).toString();
    }

    private static Set<Integer> sourceComponents(RecordDeclaration record, Node node, String value) {
        final Set<Integer> result = new LinkedHashSet<>();
        node.findAll(FieldAccessExpr.class).stream()
                .filter(field -> field.getScope().toString().equals(value))
                .forEach(field -> addSourceComponent(record, field.getNameAsString(), result));
        node.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().isEmpty())
                .filter(call -> call.getScope().map(Object::toString).orElse("").equals(value))
                .forEach(call -> addSourceComponent(record, call.getNameAsString(), result));
        return result;
    }

    private static void addSourceComponent(RecordDeclaration record, String name, Set<Integer> output) {
        for (int i = 0; i < record.getParameters().size(); i++) {
            if (record.getParameter(i).getNameAsString().equals(name)) {
                output.add(i);
                return;
            }
        }
    }

    private static int sourceComponent(RecordDeclaration record, Expression expression, String value) {
        if (expression instanceof FieldAccessExpr field && field.getScope().toString().equals(value)) {
            return sourceComponent(record, field.getNameAsString());
        }
        if (expression instanceof MethodCallExpr getter && getter.getArguments().isEmpty()
                && getter.getScope().map(Object::toString).orElse("").equals(value)) {
            return sourceComponent(record, getter.getNameAsString());
        }
        throw new IllegalStateException("Cannot prove source value getter: " + expression);
    }

    private static int sourceComponent(RecordDeclaration record, String name) {
        for (int i = 0; i < record.getParameters().size(); i++) {
            if (record.getParameter(i).getNameAsString().equals(name)) return i;
        }
        throw new IllegalStateException("Unknown source record component " + name);
    }

    private static String normalizedCodec(String expression) {
        return expression.replace("NetworkBuffer.", "").replace(" ", "");
    }

    private static Expression wireSerializer(SourceIndex sources, ResolvedType packetType, WireMigration migration) {
        final RecordDeclaration record = requireRecord(packetType);
        if (record.getParameters().size() != migration.baseline().components().size()) {
            throw new IllegalStateException("Retained record does not match the baseline constructor arity");
        }
        if (migration.bindings().stream().anyMatch(WireMigration.Binding::missing)
                && record.getImplementedTypes().stream().noneMatch(value -> value.getNameWithScope().startsWith("ClientPacket."))) {
            throw new IllegalStateException("Added outbound packet fields have no proven defaults; source API migration is required");
        }
        final String type = record.getNameAsString();
        final Set<String> codecNames = new LinkedHashSet<>();
        final var identifiers = java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*");
        for (var binding : migration.bindings()) {
            final var matcher = identifiers.matcher(binding.networkType());
            while (matcher.find()) codecNames.add(matcher.group());
        }
        final String buffer = freshName("buffer", codecNames), valueName = freshName("value", codecNames);
        final StringBuilder output = new StringBuilder("new NetworkBuffer.Type<" + type + ">() {\n")
                .append("@Override public void write(NetworkBuffer ").append(buffer).append(", ").append(type)
                .append(' ').append(valueName).append(") {\n");
        if (migration.bindings().stream().anyMatch(WireMigration.Binding::missing)) {
            output.append("throw new UnsupportedOperationException(\"Upstream added fields without proven defaults; the retained packet API can only decode this payload\");\n");
        } else {
            for (var binding : migration.bindings()) {
                final String value = binding.source() >= 0 ? valueName + "." + record.getParameter(binding.source()).getNameAsString() + "()"
                        : binding.constant();
                output.append(buffer).append(".write(").append(sourceCodec(packetType, binding.networkType())).append(", ").append(value).append(");\n");
            }
        }
        output.append("}\n@Override public ").append(type).append(" read(NetworkBuffer ").append(buffer).append(") {\n");
        final Map<Integer, String> values = new LinkedHashMap<>();
        final List<String> checks = new ArrayList<>();
        int index = 0;
        for (var binding : migration.bindings()) {
            final String name = freshName("field" + index++, codecNames);
            // Use source type spellings so imported Minestom value types remain valid.
            final String javaType = binding.source() >= 0 ? record.getParameter(binding.source()).getType().asString() : binding.javaType();
            if (!binding.missing()) output.append(javaType).append(' ').append(name).append(" = ");
            output.append(buffer).append(".read(")
                    .append(sourceCodec(packetType, binding.networkType())).append(");\n");
            if (binding.source() >= 0) {
                final String previous = values.putIfAbsent(binding.source(), name);
                if (previous != null) checks.add(unequal(javaType, previous, name));
            } else if (binding.constant() != null) checks.add(unequal(javaType, name, binding.constant()));
        }
        if (!checks.isEmpty()) output.append("if (").append(String.join(" || ", checks))
                .append(") throw new IllegalArgumentException(\"Target payload cannot be represented by the retained packet API\");\n");
        output.append("return new ").append(type).append('(');
        for (int i = 0; i < record.getParameters().size(); i++) {
            if (i > 0) output.append(", ");
            if (!values.containsKey(i)) throw new IllegalStateException("No decoded value for retained component " + i);
            output.append(values.get(i));
        }
        output.append(");\n}\n}");
        final Expression formatted = sources.parseExpression(output.toString(), "schema-derived packet serializer");
        return sources.parseExpression(formatted.toString(), "formatted schema-derived packet serializer");
    }

    private static String freshName(String base, Set<String> used) {
        String name = base;
        for (int suffix = 1; !used.add(name); suffix++) name = base + suffix;
        return name;
    }

    private static String simpleType(String type) {
        // Strip package qualification inside type arguments as well as at the outer type.
        return type.replaceAll("(?:[a-z][\\w$]*\\.)+", "");
    }

    private static String unequal(String type, String left, String right) {
        return switch (type) {
            case "float" -> "Float.compare(" + left + ", " + right + ") != 0";
            case "double" -> "Double.compare(" + left + ", " + right + ") != 0";
            case "boolean", "byte", "short", "int", "long" -> left + " != " + right;
            default -> "!java.util.Objects.deepEquals(" + left + ", " + right + ")";
        };
    }

    private static String sourceCodec(ResolvedType owner, String codec) {
        if (!codec.startsWith("NetworkBuffer.")) return codec;
        final String name = codec.substring("NetworkBuffer.".length());
        final boolean imported = owner.source().unit().getImports().stream().anyMatch(value -> value.isStatic()
                && (value.getNameAsString().equals("net.minestom.server.network.NetworkBuffer." + name)
                    || value.isAsterisk() && value.getNameAsString().endsWith("NetworkBuffer")));
        return imported ? name : codec;
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
                + ':' + migration.falseId() + ':' + migration.trueId() + ':' + migration.wire();
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

    private static Expression byteArrayBitSetCodec(SourceIndex sources, ResolvedType owner,
                                                   Expression current, int component) {
        final Parameter parameter = sources.recordComponent(owner, component);
        final String bitSet = parameter.getType().asString();
        if (!bitSet.equals("BitSet") && !bitSet.equals("java.util.BitSet")) {
            throw new IllegalStateException("Migrated serializer leaf " + component + " in "
                    + owner.qualifiedName() + " is not a BitSet");
        }
        final String existing = current.toString();
        final String byteArray;
        if (existing.equals("BITSET")) byteArray = "BYTE_ARRAY";
        else if (existing.equals("NetworkBuffer.BITSET")) byteArray = "NetworkBuffer.BYTE_ARRAY";
        else {
            final String expected = existing.startsWith("NetworkBuffer.")
                    ? "NetworkBuffer.BYTE_ARRAY.transform" : "BYTE_ARRAY.transform";
            if (existing.startsWith(expected) && existing.contains(bitSet + "::valueOf")
                    && existing.contains(bitSet + "::toByteArray")) return current.clone();
            throw new IllegalStateException("BitSet serializer component " + component + " in "
                    + owner.qualifiedName() + " does not use BITSET");
        }
        return sources.parseExpression(byteArray + ".transform(" + bitSet + "::valueOf, "
                + bitSet + "::toByteArray)", "byte-array BitSet codec expression");
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
        private final Map<String, WireMigration> existingWireAdapters = new HashMap<>();
        private final Map<SerializerSlot, WireMigration> resolvedWirePlans = new HashMap<>();

        private String importName(ResolvedType owner, String qualified) {
            final String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            final var unit = owner.source().unit();
            final boolean conflict = unit.findAll(TypeDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals(simple))
                    || unit.getImports().stream().anyMatch(value -> !value.isAsterisk() && !value.isStatic()
                    && value.getName().getIdentifier().equals(simple) && !value.getNameAsString().equals(qualified));
            if (conflict) return qualified;
            unit.addImport(qualified);
            return simple;
        }

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
            final Parameter parameter = recordComponent(owner, ordinal);
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

        private String componentNetworkType(ResolvedType owner, int ordinal,
                                            List<com.github.javaparser.ast.stmt.Statement> packetStatements,
                                            String packetBuffer, String packetValue) {
            final Parameter parameter = recordComponent(owner, ordinal);
            final ResolvedType component = recordComponentType(owner, ordinal, false);
            final boolean implicitStatic = component.declaration().isClassOrInterfaceDeclaration()
                    && component.declaration().asClassOrInterfaceDeclaration().isInterface();
            final List<String> matches = new ArrayList<>();
            for (FieldDeclaration field : component.declaration().getFields()) {
                if (!field.isStatic() && !implicitStatic) continue;
                for (VariableDeclarator variable : field.getVariables()) {
                    if (!(variable.getType() instanceof ClassOrInterfaceType type)
                            || !type.getNameAsString().equals("Type")
                            || type.getScope().map(Object::toString).filter("NetworkBuffer"::equals).isEmpty()
                            || type.getTypeArguments().isEmpty() || type.getTypeArguments().orElseThrow().size() != 1
                            || !simpleType(type.getTypeArguments().orElseThrow().getFirst().orElseThrow().asString())
                            .equals(component.declaration().getNameAsString())
                            || variable.getInitializer().isEmpty()
                            || !(variable.getInitializer().orElseThrow() instanceof ObjectCreationExpr candidate)
                            || candidate.getAnonymousClassBody().isEmpty()) continue;
                    final var writers = candidate.getAnonymousClassBody().orElseThrow().stream()
                            .filter(com.github.javaparser.ast.body.MethodDeclaration.class::isInstance)
                            .map(com.github.javaparser.ast.body.MethodDeclaration.class::cast)
                            .filter(method -> method.getNameAsString().equals("write") && method.getParameters().size() == 2
                                    && method.getBody().isPresent()).toList();
                    if (writers.size() != 1) continue;
                    final var writer = writers.getFirst();
                    if (normalizeComponentWriter(recordComponent(owner, ordinal).getNameAsString(), packetStatements,
                            packetBuffer, packetValue).equals(normalizeWriter(writer))) {
                        matches.add(parameter.getType().asString() + "." + variable.getNameAsString());
                    }
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one source NetworkBuffer.Type matching component " + ordinal
                        + " in " + owner.qualifiedName() + " but found " + matches.size());
            }
            return matches.getFirst();
        }

        private ResolvedType recordListElementType(ResolvedType owner, int ordinal) {
            final Parameter parameter = recordComponent(owner, ordinal);
            if (!(parameter.getType() instanceof ClassOrInterfaceType type)
                    || !type.getNameAsString().equals("List") || type.getTypeArguments().isEmpty()
                    || type.getTypeArguments().orElseThrow().size() != 1
                    || !(type.getTypeArguments().orElseThrow().get(0) instanceof ClassOrInterfaceType element)) {
                throw new IllegalStateException("Record component " + ordinal + " in " + owner.qualifiedName()
                        + " is not a List of source records");
            }
            return resolveType(element.getNameWithScope(), owner);
        }

        private Parameter recordComponent(ResolvedType owner, int ordinal) {
            if (!(owner.declaration() instanceof RecordDeclaration record) || record.getParameters().size() <= ordinal) {
                throw new IllegalStateException(owner.qualifiedName() + " is not a record with component " + ordinal);
            }
            return record.getParameter(ordinal);
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
                final String printed = removeUnusedImports(LexicalPreservingPrinter.print(source.unit()));
                if (!printed.equals(Files.readString(source.path()))) Files.writeString(source.path(), printed);
            }
        }

        private static String removeUnusedImports(String source) {
            final java.util.regex.Pattern imports = java.util.regex.Pattern.compile(
                    "(?m)^import\\s+(?:static\\s+)?([\\w.]+);(?:\\R|$)");
            final String body = imports.matcher(source).replaceAll("");
            final java.util.regex.Matcher matcher = imports.matcher(source);
            final StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                final String qualified = matcher.group(1);
                final String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
                if (java.util.regex.Pattern.compile("(?<![\\w.])" + java.util.regex.Pattern.quote(simple) + "\\b")
                        .matcher(body).find()) {
                    matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group()));
                } else {
                    matcher.appendReplacement(result, "");
                }
            }
            matcher.appendTail(result);
            return result.toString();
        }

        private static boolean isNullable(Parameter parameter) {
            return hasNullableAnnotation(parameter.getAnnotations())
                    || hasNullableAnnotation(parameter.getType().getAnnotations());
        }

        private static boolean hasNullableAnnotation(List<? extends AnnotationExpr> annotations) {
            return annotations.stream().anyMatch(annotation -> annotation.getName().getIdentifier().equals("Nullable"));
        }
    }

    private static String normalizeComponentWriter(String component,
                                                   List<com.github.javaparser.ast.stmt.Statement> statements,
                                                   String buffer, String value) {
        final var block = new com.github.javaparser.ast.stmt.BlockStmt();
        statements.forEach(statement -> block.addStatement(statement.clone()));
        block.findAll(FieldAccessExpr.class).stream()
                .filter(field -> field.getScope().toString().equals(value) && field.getNameAsString().equals(component))
                .forEach(field -> field.replace(new NameExpr("codecValue")));
        block.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().isEmpty() && call.getNameAsString().equals(component)
                        && call.getScope().map(Object::toString).orElse("").equals(value))
                .forEach(call -> call.replace(new NameExpr("codecValue")));
        block.findAll(NameExpr.class).stream().filter(name -> name.getNameAsString().equals(buffer))
                .forEach(name -> name.setName("codecBuffer"));
        return block.toString().replace("NetworkBuffer.", "").replaceAll("\\s+", "");
    }

    private static String normalizeWriter(com.github.javaparser.ast.body.MethodDeclaration writer) {
        final var block = writer.getBody().orElseThrow().clone();
        final String buffer = writer.getParameter(0).getNameAsString();
        final String value = writer.getParameter(1).getNameAsString();
        block.findAll(NameExpr.class).forEach(name -> {
            if (name.getNameAsString().equals(buffer)) name.setName("codecBuffer");
            else if (name.getNameAsString().equals(value)) name.setName("codecValue");
        });
        return block.toString().replace("NetworkBuffer.", "").replaceAll("\\s+", "");
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
