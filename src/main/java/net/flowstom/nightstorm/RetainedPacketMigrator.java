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
        Path structuralPath = sourceRoot.resolve(".nightstorm/structural-adapters.json");
        Map<String, StructuralAdapter> structural = Files.exists(structuralPath) ? Json.MAPPER.readValue(structuralPath.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, StructuralAdapter>>() { }) : new LinkedHashMap<>();
        for (var adapter : structural.values()) for (var file : adapter.files().entrySet()) {
            if (!sourceHash(sourceRoot.resolve(file.getKey())).equals(file.getValue()))
                throw new IllegalStateException("Saved structural adapter differs from source edits: " + file.getKey());
        }
        Set<String> completedStructural = new LinkedHashSet<>();
        final Map<String, WireMigration> wirePlans = readWirePlans(sourceRoot);
        sources.existingWireAdapters.putAll(wirePlans);
        final Map<SerializerSlot, PlannedEdit> edits = new LinkedHashMap<>();
        for (PacketMigrationScanner.Migration requested : migrations) {
            if (requested.dispatch() != null || requested.move() != null) {
                String key = wireKey(requested.packet(), requested.path());
                var previous = structural.get(key);
                if (previous != null) {
                    for (var file : previous.files().entrySet()) if (!sourceHash(sourceRoot.resolve(file.getKey())).equals(file.getValue()))
                        throw new IllegalStateException("Saved structural adapter differs from source edits: " + file.getKey());
                    if (!previous.migration().equals(requested)) throw new IllegalStateException("Structural adapter changed; a new API projection is required");
                    continue;
                }
                completedStructural.add(key);
            }
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
            if (migration.kind() == PacketMigrationScanner.Kind.WIRE_FIELD_MOVE) {
                planFieldMove(sources, edits, packetType, migration);
                continue;
            }
            if (migration.kind() != PacketMigrationScanner.Kind.CODEC_REWRITE) {
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
                    final CodecChange change = migration.codec();
                    if (change == null) throw new IllegalStateException("Missing proven codec expression");
                    String expression = change.expression();
                    if (!change.enumIds().isEmpty()) {
                        final ResolvedType enumType = sources.recordComponentType(owner, component, change.nullable());
                        requireMatchingEnum(enumType, migration);
                        expression = expression.replace("$TYPE$", enumType.declaration().getNameAsString());
                    } else {
                        var parameter = sources.recordComponent(owner, component);
                        String sourceType = parameter.getType().asString();
                        String simple = change.carrier().substring(change.carrier().lastIndexOf('.') + 1);
                        if (!sourceType.equals(change.carrier()) && !(sourceType.equals(simple)
                                && owner.source().unit().getImports().stream().anyMatch(value -> value.getNameAsString().equals(change.carrier())
                                    || value.isAsterisk() && value.getNameAsString().equals(change.carrier().substring(0, change.carrier().lastIndexOf('.')))))) {
                            throw new IllegalStateException("Source carrier does not match proven codec carrier " + change.carrier());
                        }
                    }
                    replacement = sources.parseExpression(expression, "proven component codec expression");
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
        for (var requested : migrations) {
            String key = wireKey(requested.packet(), requested.path());
            if (!completedStructural.contains(key)) continue;
            Map<String, String> files = new LinkedHashMap<>();
            for (var file : sources.parsed.keySet()) files.put(sourceRoot.relativize(file).toString(), sourceHash(file));
            structural.put(key, new StructuralAdapter(requested, files));
        }
        if (!structural.isEmpty()) {
            for (var entry : List.copyOf(structural.entrySet())) {
                Map<String,String> hashes=new LinkedHashMap<>();
                for (String file : entry.getValue().files().keySet()) hashes.put(file,sourceHash(sourceRoot.resolve(file)));
                structural.put(entry.getKey(),new StructuralAdapter(entry.getValue().migration(), hashes));
            }
            Json.write(structuralPath, structural);
        }
        if (!wirePlans.isEmpty()) Json.write(sourceRoot.resolve(".nightstorm/wire-adapters.json"), wirePlans);
    }

    private record StructuralAdapter(PacketMigrationScanner.Migration migration, Map<String, String> files) { }
    private static String sourceHash(Path path) throws IOException {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        sources.shortenJdkNames(packetType.source().unit(), current);
        final Expression replacement = switch (migration.kind()) {
            case WIRE_DISPATCH -> dispatchSerializer(sources, packetType, current, migration.dispatch());
            case WIRE_PROJECTION -> {
                final String key = wireKey(migration.packet(), migration.path());
                final WireMigration previous = sources.existingWireAdapters.get(key);
                WireMigration plan = migration.wire();
                if (previous == null) {
                    plan = withSourceCodecs(plan, requireBaselineSerializer(sources, packetType, current, plan));
                    plan = substituteEnumPlaceholders(sources, packetType, plan);
                }
                else if (!wireSerializer(sources, packetType, previous).equals(current)) {
                    throw new IllegalStateException("Saved wire adapter does not match the source serializer; review source edits before regenerating");
                }
                plan = migrateSourceShape(sources, packetType, plan, current);
                wirePlans.put(key, plan);
                sources.resolvedWirePlans.put(slot, plan);
                yield wireSerializer(sources, packetType, plan);
            }
            case WIRE_SUFFIX -> suffixSerializer(sources, packetType, current, migration.suffix());
            case CODEC_REWRITE, WIRE_FIELD_MOVE ->
                    throw new IllegalStateException("Unexpected leaf migration");
        };
        final List<Node> removals = migration.kind() == PacketMigrationScanner.Kind.WIRE_PROJECTION
                ? obsoletePrivateHelpers(packetType, current) : List.of();
        final PlannedEdit edit = new PlannedEdit(current, replacement, semanticKey(migration), removals);
        final PlannedEdit previous = edits.putIfAbsent(slot, edit);
        if (previous != null && !previous.semantic().equals(edit.semantic())) {
            throw new IllegalStateException("Conflicting whole-payload migrations for " + packetType.qualifiedName());
        }
    }

    /** Saved adapters must name the source enum; {@code $TYPE$} is only a planning placeholder. */
    private static WireMigration substituteEnumPlaceholders(SourceIndex sources, ResolvedType owner, WireMigration plan) {
        final List<WireMigration.Binding> bindings = new ArrayList<>();
        for (WireMigration.Binding binding : plan.bindings()) {
            if (!binding.networkType().contains("$TYPE$")) {
                bindings.add(binding);
                continue;
            }
            if (binding.source() < 0) throw new IllegalStateException("Enum codec rewrite is not bound to a source component");
            final ResolvedType enumType = sources.recordComponentType(owner, binding.source(), true);
            if (!(enumType.declaration() instanceof EnumDeclaration declaration)) {
                throw new IllegalStateException("Migrated serializer leaf " + enumType.qualifiedName() + " is not a source enum");
            }
            final Set<String> referenced = new LinkedHashSet<>();
            final var matcher = java.util.regex.Pattern.compile("\\$TYPE\\$\\.([A-Za-z_]\\w*)").matcher(binding.networkType());
            while (matcher.find()) referenced.add(matcher.group(1));
            final Set<String> constants = new LinkedHashSet<>();
            declaration.getEntries().forEach(entry -> constants.add(entry.getNameAsString()));
            if (!constants.equals(referenced)) {
                throw new IllegalStateException("Target and source enum constants do not match for "
                        + enumType.qualifiedName() + ": " + referenced + " versus " + constants);
            }
            bindings.add(new WireMigration.Binding(binding.component(), binding.source(), binding.javaType(),
                    binding.networkType().replace("$TYPE$", declaration.getNameAsString()), binding.constant()));
        }
        return new WireMigration(plan.baseline(), plan.target(), bindings);
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

    private record SourcePath(List<ResolvedType> types, List<Integer> indices) { }

    private static void planFieldMove(SourceIndex sources, Map<SerializerSlot, PlannedEdit> edits,
                                       ResolvedType packetType, PacketMigrationScanner.Migration migration) {
        if (migration.path().size() != 1) throw new IllegalStateException("Nested field move requires a direct collection component");
        var mappingType = sources.recordListElementType(packetType, migration.path().getFirst());
        var mapping = requireRecord(mappingType);
        var mappingSerializer = sources.field(mappingType, "SERIALIZER");
        if (mappingSerializer.expression().toString().contains("nestedFieldDelegate")) return;
        var fields = migration.move().fields();
        List<SourcePath> paths = new ArrayList<>();
        findMovedSource(sources, mappingType, List.of(mappingType), List.of(), new LinkedHashSet<>(), fields, paths);
        if (paths.size() != 1 || paths.getFirst().indices().isEmpty()) throw new IllegalStateException("Expected one source path for moved fields, found " + paths.size());
        var path = paths.getFirst(); var leafType = path.types().getLast(); var leaf = requireRecord(leafType);
        var leafSerializer = sources.field(leafType, "SERIALIZER");
        Map<Integer, String> placeholders = new LinkedHashMap<>();
        for (var field : fields) placeholders.put(sourceComponent(leaf, field.name()), field.defaultValue());
        var leafReplacement = serializerWithoutFields(sources, leafType, leaf, leafSerializer.expression(), fields, placeholders);
        int arity = mapping.getParameters().size();
        var oldParameters = mapping.getParameters().stream().map(Parameter::clone).toList();
        if (!mapping.getConstructors().isEmpty() || !mapping.getCompactConstructors().isEmpty()) throw new IllegalStateException("Moved-field source API has custom constructors");
        for (var field : fields) if (oldParameters.stream().anyMatch(p -> p.getNameAsString().equals(field.name()))) throw new IllegalStateException("Moved-field source name collision " + field.name());
        // Methods rebuilding this value must retain the new independently stored fields.
        for (var method : mapping.getMethods()) for (var creation : method.findAll(ObjectCreationExpr.class)) {
            if (creation.getType().getNameAsString().equals(mapping.getNameAsString()) && creation.getArguments().size()==arity)
                fields.forEach(f -> creation.addArgument("this." + f.name()));
        }
        for (var field : fields) mapping.addParameter(field.javaType(), field.name());
        // Use the legacy arguments directly, without creating a temporary value before this(...).
        String legacyExpression = oldParameters.get(path.indices().getFirst()).getNameAsString();
        for (int depth=1; depth<path.indices().size();depth++) legacyExpression += "." + requireRecord(path.types().get(depth)).getParameter(path.indices().get(depth)).getNameAsString()+"()";
        List<String> legacyNulls = new ArrayList<>(); String cursor = oldParameters.get(path.indices().getFirst()).getNameAsString();
        for (int depth=0; depth<path.indices().size();depth++) {
            var parent = requireRecord(path.types().get(depth));
            if (SourceIndex.isNullable(parent.getParameter(path.indices().get(depth)))) legacyNulls.add(cursor+" == null");
            if (depth+1<path.indices().size()) cursor += "."+requireRecord(path.types().get(depth+1)).getParameter(path.indices().get(depth+1)).getNameAsString()+"()";
        }
        List<String> compatibilityArguments = new ArrayList<>(oldParameters.stream().map(Parameter::getNameAsString).toList());
        for (var field : fields) compatibilityArguments.add(legacyNulls.isEmpty() ? legacyExpression+"."+field.name()+"()"
                : "("+String.join(" || ",legacyNulls)+") ? "+field.defaultValue()+" : "+legacyExpression+"."+field.name()+"()");
        var compatibility = new com.github.javaparser.ast.body.ConstructorDeclaration();
        compatibility.setName(mapping.getNameAsString()).setPublic(true).setParameters(new com.github.javaparser.ast.NodeList<>(oldParameters));
        compatibility.setBody(sources.parser.parseBlock("{ this("+String.join(", ",compatibilityArguments)+"); }").getResult().orElseThrow());
        mapping.addMember(compatibility);
        StringBuilder writes = new StringBuilder(), reads = new StringBuilder(); Map<Integer,String> movedValues = new LinkedHashMap<>();
        for (int n=0;n<fields.size();n++) {
            var field=fields.get(n); String variable="moved"+n;
            writes.append("buffer.write(").append(field.codec()).append(", value.").append(field.name()).append("()); ");
            reads.append(field.javaType()).append(' ').append(variable).append(" = buffer.read(").append(field.codec()).append("); ");
            movedValues.put(sourceComponent(leaf,field.name()),variable);
        }
        String rebuilt = constructor(leaf.getNameAsString(), leaf, sourcePath(path,"value",path.indices().size()), movedValues);
        for (int depth=path.indices().size()-1;depth>=0;depth--) {
            String child=sourcePath(path,"value",depth+1);
            if (SourceIndex.isNullable(requireRecord(path.types().get(depth)).getParameter(path.indices().get(depth)))) rebuilt = child+" == null ? null : "+rebuilt;
            var parent=requireRecord(path.types().get(depth));
            String receiver=depth==0?"value":sourcePath(path,"value",depth);
            rebuilt=constructor(parent.getNameAsString(),parent,receiver,Map.of(path.indices().get(depth),rebuilt));
        }
        // Replace the reconstructed root's appended arguments with the actual wire values.
        var rootConstructor = sources.parseExpression(rebuilt,"moved-field reconstruction").asObjectCreationExpr();
        for(int n=0;n<fields.size();n++) rootConstructor.setArgument(arity+n,sources.parseExpression("moved"+n,"moved field"));
        String type=mapping.getNameAsString();
        var replacement=sources.parseExpression("new NetworkBuffer.Type<"+type+">() { private final NetworkBuffer.Type<"+type+"> nestedFieldDelegate = "+mappingSerializer.expression()
                +"; @Override public void write(NetworkBuffer buffer, "+type+" value) { nestedFieldDelegate.write(buffer,value); "+writes
                +" } @Override public "+type+" read(NetworkBuffer buffer) { var value=nestedFieldDelegate.read(buffer); "+reads+"return "+rootConstructor+"; } }","moved-field serializer");
        addEdit(edits,mappingSerializer,replacement,semanticKey(migration));
        addEdit(edits,leafSerializer,leafReplacement,semanticKey(migration));
    }

    private static void findMovedSource(SourceIndex sources, ResolvedType owner, List<ResolvedType> types, List<Integer> indices,
                                         Set<String> active, List<WireFieldMove.Moved> fields, List<SourcePath> matches) {
        if (!active.add(owner.qualifiedName()) || types.size()>16) return;
        try {
            var record=requireRecord(owner);
            if (fields.stream().allMatch(f -> record.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(f.name()) && p.getType().asString().equals(f.javaType()))))
                matches.add(new SourcePath(List.copyOf(types),List.copyOf(indices)));
            for(int n=0;n<record.getParameters().size();n++) {
                ResolvedType child;
                try { child=sources.recordComponentType(owner,n,false); } catch(IllegalStateException unsupported) { continue; }
                if (!(child.declaration() instanceof RecordDeclaration)) continue;
                var chain=new ArrayList<>(types);chain.add(child);var path=new ArrayList<>(indices);path.add(n);
                findMovedSource(sources,child,chain,path,active,fields,matches);
            }
        } finally { active.remove(owner.qualifiedName()); }
    }
    private static String sourcePath(SourcePath path,String root,int through) {
        String result=root;
        for(int n=0;n<through;n++) result+="."+requireRecord(path.types().get(n)).getParameter(path.indices().get(n)).getNameAsString()+"()";
        return result;
    }
    private static Expression serializerWithoutFields(SourceIndex sources, ResolvedType owner, RecordDeclaration record,
                                                       Expression current, List<WireFieldMove.Moved> fields, Map<Integer,String> placeholders) {
        var replacement=current.clone().asObjectCreationExpr();
        var writers=replacement.getAnonymousClassBody().orElseThrow().stream().filter(com.github.javaparser.ast.body.MethodDeclaration.class::isInstance)
                .map(com.github.javaparser.ast.body.MethodDeclaration.class::cast).filter(m -> m.getNameAsString().equals("write")).toList();
        var readers=replacement.getAnonymousClassBody().orElseThrow().stream().filter(com.github.javaparser.ast.body.MethodDeclaration.class::isInstance)
                .map(com.github.javaparser.ast.body.MethodDeclaration.class::cast).filter(m -> m.getNameAsString().equals("read")).toList();
        if(writers.size()!=1 || readers.size()!=1)throw new IllegalStateException("Moved fields require one source reader and writer");
        var writer=writers.getFirst();String buffer=writer.getParameter(0).getNameAsString(),value=writer.getParameter(1).getNameAsString();
        var statements=writer.getBody().orElseThrow().getStatements();
        if(statements.size()<fields.size())throw new IllegalStateException("Missing moved-field source writes");
        for(int n=fields.size()-1;n>=0;n--) {
            var field=fields.get(n);var statement=statements.getLast().orElseThrow();
            if(!statement.isExpressionStmt() || !(statement.asExpressionStmt().getExpression() instanceof MethodCallExpr call)
                    || !call.getNameAsString().equals("write") || !call.getScope().map(Object::toString).orElse("").equals(buffer)
                    || call.getArguments().size()!=2 || !normalizedCodec(call.getArgument(0).toString()).equals(normalizedCodec(field.codec()))
                    || !isAccessor(call.getArgument(1),value,field.name()))throw new IllegalStateException("Moved fields do not match source writer suffix");
            statements.removeLast();
        }
        var reader=readers.getFirst();String readBuffer=reader.getParameter(0).getNameAsString();
        var constructions=reader.findAll(ObjectCreationExpr.class).stream().filter(c -> c.getType().getNameAsString().equals(record.getNameAsString()) && c.getArguments().size()==record.getParameters().size()).toList();
        if(constructions.size()!=1)throw new IllegalStateException("Missing moved-field source reader construction");
        var construction=constructions.getFirst();
        for(var field:fields) {
            int index=sourceComponent(record,field.name());var argument=construction.getArgument(index);
            if(!(argument instanceof NameExpr variable))throw new IllegalStateException("Moved source field needs a direct read binding");
            var declarations=reader.findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(variable.getNameAsString())).toList();
            if(declarations.size()!=1 || !(declarations.getFirst().getInitializer().orElse(null) instanceof MethodCallExpr call)
                    || !call.getNameAsString().equals("read") || !call.getScope().map(Object::toString).orElse("").equals(readBuffer)
                    || call.getArguments().size()!=1 || !normalizedCodec(call.getArgument(0).toString()).equals(normalizedCodec(field.codec())))throw new IllegalStateException("Moved source field codec differs");
            declarations.getFirst().findAncestor(com.github.javaparser.ast.stmt.ExpressionStmt.class).orElseThrow().remove();
            construction.setArgument(index,sources.parseExpression(placeholders.get(index),"baseline field initialization"));
        }
        return sources.parseExpression(replacement.toString(),"nested codec without moved suffix");
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

    private static boolean isAccessor(Expression expression, String receiver, String name) {
        final String value = expression.toString().replace("()", "");
        return value.equals(receiver + '.' + name);
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

    private static Expression dispatchSerializer(SourceIndex sources, ResolvedType owner, Expression current,
                                                  WireDispatch.Plan plan) {
        var record = requireRecord(owner);
        var baselinePlan = new WireMigration(plan.baseline(), plan.baseline(), List.of());
        Map<Integer, String> sourceCodecs = requireBaselineSerializer(sources, owner, current, baselinePlan);
        Map<WireSchema.Codec, DispatchRenderer.SourceCodec> codecs = new LinkedHashMap<>();
        for (var field : plan.baseline().fields()) {
            var source = new DispatchRenderer.SourceCodec(record.getParameter(field.component()).getType().asString(), sourceCodecs.get(field.component()));
            var old = codecs.putIfAbsent(field.codec(), source);
            if (old != null && !old.equals(source)) throw new IllegalStateException("Ambiguous source representation for dispatch payload " + field.codec());
        }
        var renderer = new DispatchRenderer(plan.dispatch(), codecs);
        var parameters = record.getParameters().stream().map(Parameter::clone).toList();
        String unionName = freshName("WireChoice", record.getMembers().stream().filter(TypeDeclaration.class::isInstance)
                .map(TypeDeclaration.class::cast).map(TypeDeclaration::getNameAsString).collect(java.util.stream.Collectors.toSet()));
        String payloadName = parameters.get(plan.legacySource()).getNameAsString() + "Path";
        var newParameters = new com.github.javaparser.ast.NodeList<Parameter>();
        for (int i = 0; i < plan.target().fields().size(); i++) {
            var field = plan.target().fields().get(i);
            if (field.component() == plan.unionComponent()) newParameters.add(new Parameter(sources.parser.parseType(unionName).getResult().orElseThrow(), payloadName));
            else newParameters.add(parameters.get(plan.sources().get(i)).clone());
        }
        // Preserve old construction with the uniquely identity-matched variant; obsolete values are accepted and ignored.
        var arguments = new ArrayList<String>();
        for (int i = 0; i < plan.sources().size(); i++) arguments.add(plan.sources().get(i) < 0
                ? "new " + renderer.variantName(unionName, plan.legacyVariant()) + "(" + parameters.get(plan.legacySource()).getNameAsString() + ")"
                : parameters.get(plan.sources().get(i)).getNameAsString());
        if (!record.getConstructors().isEmpty() || !record.getCompactConstructors().isEmpty()) throw new IllegalStateException("Dispatch API migration requires review of custom constructors");
        var retainedNames = newParameters.stream().map(Parameter::getNameAsString).collect(java.util.stream.Collectors.toSet());
        for (var parameter : parameters) {
            String name = parameter.getNameAsString();
            if (retainedNames.contains(name)) continue;
            boolean used = record.findAll(NameExpr.class).stream().anyMatch(e -> e.getNameAsString().equals(name) && !isWithin(e, current))
                    || record.findAll(FieldAccessExpr.class).stream().anyMatch(e -> e.getNameAsString().equals(name) && !isWithin(e, current))
                    || record.findAll(MethodCallExpr.class).stream().anyMatch(e -> e.getNameAsString().equals(name) && !isWithin(e, current));
            if (used) throw new IllegalStateException("Removed source component has non-serializer uses: " + name);
        }
        record.setParameters(newParameters);
        var compatibility = new com.github.javaparser.ast.body.ConstructorDeclaration();
        compatibility.setName(record.getNameAsString()).setPublic(true).setParameters(new com.github.javaparser.ast.NodeList<>(parameters));
        compatibility.setBody(sources.parser.parseBlock("{ this(" + String.join(", ", arguments) + "); }").getResult().orElseThrow());
        record.addMember(compatibility);
        String declaration = renderer.declarations(unionName);
        var unit = sources.parser.parse("class Container { " + declaration + " }").getResult().orElseThrow();
        unit.getClassByName("Container").orElseThrow().getMembers().forEach(member -> record.addMember(member.clone()));
        String type = record.getNameAsString();
        StringBuilder write = new StringBuilder(), read = new StringBuilder(); List<String> values = new ArrayList<>();
        for (int i = 0; i < plan.target().fields().size(); i++) {
            boolean union = plan.sources().get(i) < 0;
            String codec = union ? unionName + ".SERIALIZER" : sourceCodecs.get(plan.sources().get(i));
            String name = newParameters.get(i).getNameAsString(), variable = "component" + i;
            write.append("buffer.write(").append(sourceCodec(owner, codec)).append(", value.").append(name).append("());\n");
            read.append("var ").append(variable).append(" = buffer.read(").append(sourceCodec(owner, codec)).append(");\n");
            values.add(variable);
        }
        return sources.parseExpression("new NetworkBuffer.Type<" + type + ">() { @Override public void write(NetworkBuffer buffer, " + type + " value) { "
                + write + " } @Override public " + type + " read(NetworkBuffer buffer) { " + read + "return new " + type + "(" + String.join(", ", values) + "); } }", "dispatch serializer");
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
                String codec = directWriteCodec(chunk, buffer, component, record, value);
                if (codec == null) codec = fixedWritesCodec(chunk, buffer, component, record, value, migration.baseline());
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

    private static String fixedWritesCodec(List<com.github.javaparser.ast.stmt.Statement> statements, String buffer,
                                           int component, RecordDeclaration record, String value, WireSchema schema) {
        var field = schema.fields().stream().filter(f -> f.component() == component).findFirst().orElseThrow();
        var fixed = field.codec().operations().stream().filter(op -> op.startsWith("fixed:")).findFirst();
        if (fixed.isEmpty() || statements.size() != Integer.parseInt(fixed.get().substring(6))) return null;
        String elementCodec = null;
        for (int i = 0; i < statements.size(); i++) {
            if (!statements.get(i).isExpressionStmt() || !(statements.get(i).asExpressionStmt().getExpression() instanceof MethodCallExpr call)
                    || !call.getNameAsString().equals("write") || call.getArguments().size() != 2
                    || !call.getScope().map(Object::toString).orElse("").equals(buffer)
                    || !(call.getArgument(1) instanceof MethodCallExpr get) || !get.getNameAsString().equals("get") || get.getArguments().size() != 1
                    || !get.getArgument(0).isIntegerLiteralExpr() || get.getArgument(0).asIntegerLiteralExpr().asInt() != i
                    || sourceComponent(record, get.getScope().orElseThrow(), value) != component) return null;
            String codec = call.getArgument(0).toString();
            if (elementCodec != null && !elementCodec.equals(codec)) return null;
            elementCodec = codec;
        }
        var translated = PacketCodecScanner.translate(field.codec());
        if (!translated.supported()) return null;
        // The scalar encoding is verified independently of the list representation.
        var base = PacketCodecScanner.translate(new WireSchema.Codec(field.codec().owner(), field.codec().name()));
        if (!normalizedCodec(elementCodec).equals(normalizedCodec(base.networkType()))) return null;
        return translated.networkType();
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
        sources.shortenJdkNames(packetType.source().unit(), formatted);
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

    private static Expression suffixSerializer(SourceIndex sources, ResolvedType packetType,
                                               Expression current, List<WireMigration.Binding> fields) {
        return suffixSerializer(sources, packetType, current, fields, true);
    }

    private static Expression suffixSerializer(SourceIndex sources, ResolvedType packetType,
                                               Expression current, List<WireMigration.Binding> fields, boolean unwrap) {
        final String type = requireRecord(packetType).getNameAsString();
        // On repeated application rebuild from the original delegate rather than nesting adapters.
        Expression delegate = current;
        if (unwrap && current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            var delegates = creation.getAnonymousClassBody().get().stream().filter(com.github.javaparser.ast.body.FieldDeclaration.class::isInstance)
                    .map(com.github.javaparser.ast.body.FieldDeclaration.class::cast).flatMap(field -> field.getVariables().stream())
                    .filter(field -> field.getNameAsString().equals("wireSuffixDelegate")).toList();
            if (delegates.size() == 1) delegate = delegates.getFirst().getInitializer().orElseThrow();
        }
        var writes = new StringBuilder();
        var reads = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            if (field.constant() == null) throw new IllegalStateException("Wire suffix lacks a proven default");
            writes.append("buffer.write(").append(field.networkType()).append(", ").append(field.constant()).append(");\n");
            reads.append(field.javaType()).append(" suffix").append(i).append(" = buffer.read(").append(field.networkType()).append(");\n")
                    .append("if (").append(unequal(field.javaType(), "suffix" + i, field.constant()))
                    .append(") throw new IllegalArgumentException(\"Added field cannot be represented by the retained API\");\n");
        }
        final Expression result = sources.parseExpression("""
                new NetworkBuffer.Type<%s>() {
                    private final NetworkBuffer.Type<%s> wireSuffixDelegate = %s;
                    @Override public void write(NetworkBuffer buffer, %s value) {
                        wireSuffixDelegate.write(buffer, value);
                        %s
                    }
                    @Override public %s read(NetworkBuffer buffer) {
                        var value = wireSuffixDelegate.read(buffer);
                        %s
                        return value;
                    }
                }
                """.formatted(type, type, delegate, type, writes, type, reads), "schema-derived wire suffix");
        sources.shortenJdkNames(packetType.source().unit(), result);
        if (delegate != current && !result.equals(current)) return suffixSerializer(sources, packetType, current, fields, false);
        return result;
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
                + ':' + migration.falseId() + ':' + migration.trueId() + ':' + migration.wire() + ':' + migration.suffix() + ':' + migration.codec() + ':' + migration.dispatch() + ':' + migration.move();
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
            return importName(owner.source().unit(), qualified);
        }

        private String importName(CompilationUnit unit, String qualified) {
            final String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            final boolean conflict = unit.findAll(TypeDeclaration.class).stream().anyMatch(type -> type.getNameAsString().equals(simple))
                    || unit.findAll(com.github.javaparser.ast.type.TypeParameter.class).stream().anyMatch(type -> type.getNameAsString().equals(simple))
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
                shortenJdkNames(source.unit());
                final String printed = removeUnusedImports(LexicalPreservingPrinter.print(source.unit()));
                if (!printed.equals(Files.readString(source.path()))) Files.writeString(source.path(), printed);
            }
        }

        private void shortenJdkNames(CompilationUnit unit) {
            shortenJdkNames(unit, unit);
        }

        private void shortenJdkNames(CompilationUnit unit, Node node) {
            String qualifiedType = "java(?:\\.[a-z][\\w]*)+\\.[A-Z][\\w]*";
            for (var type : List.copyOf(node.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class))) {
                String qualified = type.getNameWithScope();
                if (!qualified.matches(qualifiedType)) continue;
                String simple = importName(unit, qualified);
                if (simple.equals(qualified)) continue;
                type.setScope(null);
                type.setName(simple);
            }
            for (var field : List.copyOf(node.findAll(FieldAccessExpr.class))) {
                String qualified = field.toString();
                if (!qualified.matches(qualifiedType)) continue;
                String simple = importName(unit, qualified);
                if (!simple.equals(qualified)) field.replace(new NameExpr(simple));
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
