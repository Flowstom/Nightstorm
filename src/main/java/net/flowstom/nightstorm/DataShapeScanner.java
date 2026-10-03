package net.flowstom.nightstorm;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Learns object-to-list component layouts from both sides of the retained source codec. */
final class DataShapeScanner {
    record ListShape(List<String> fields, String scalar) { ListShape { fields = List.copyOf(fields); } }
    record Layouts(Map<String, ListShape> lists, Set<String> components, Set<String> attributes) { }

    static Layouts scan(Path source) throws IOException {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        var codecs = new LinkedHashMap<String, ListShape>();
        var definitions = new LinkedHashMap<String, Expression>();
        var pending = new LinkedHashMap<String, Expression>();
        CompilationUnit components = null;
        CompilationUnit attributes = null;
        try (var files = Files.walk(source)) {
            for (var file : files.filter(Files::isRegularFile).filter(value -> value.toString().endsWith(".java"))
                    .filter(value -> !value.toString().contains("/build/") && !value.toString().contains("/src/test/"))
                    .toList()) {
                String text = Files.readString(file);
                boolean registrations = text.contains("DataComponents") || text.contains("EnvironmentAttributes");
                if (!registrations && !text.contains("Codec")) continue;
                var parsed = parser.parse(text);
                var unit = parsed.getResult().filter(ignored -> parsed.isSuccessful()).orElseThrow(() -> new IllegalStateException("Cannot parse data codec " + file));
                boolean componentRegistry = unit.getTypes().stream().anyMatch(type -> type.getFullyQualifiedName().orElse("").equals("net.minestom.server.component.DataComponents"));
                boolean attributeRegistry = unit.getTypes().stream().anyMatch(type -> type.getFullyQualifiedName().orElse("").equals("net.minestom.server.world.attribute.EnvironmentAttributes"));
                if (componentRegistry) {
                    if (components != null) throw new IllegalStateException("Ambiguous component registry source");
                    components = unit;
                }
                if (attributeRegistry) {
                    if (attributes != null) throw new IllegalStateException("Ambiguous attribute registry source");
                    attributes = unit;
                }
                for (com.github.javaparser.ast.body.TypeDeclaration<?> type : unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
                    for (var field : type.getFields()) for (var variable : field.getVariables()) {
                        if (variable.getInitializer().isPresent()) definitions.put(type.getFullyQualifiedName().orElseThrow() + '#' + variable.getNameAsString(), variable.getInitializer().get());
                    }
                }
                for (var record : unit.findAll(RecordDeclaration.class)) {
                    for (var field : record.getFields()) {
                        for (var variable : field.getVariables()) {
                            if (variable.getInitializer().isEmpty() || !(variable.getInitializer().get() instanceof MethodCallExpr transform)
                                    || !transform.getNameAsString().equals("transform") || transform.getArguments().size() != 2
                                    || !(transform.getArgument(0) instanceof MethodReferenceExpr constructor) || !constructor.getIdentifier().equals("new")
                                    || !constructor.getScope().toString().equals(record.getNameAsString())
                                    || !(transform.getArgument(1) instanceof MethodReferenceExpr encoder)
                                    || !encoder.getScope().toString().equals(record.getNameAsString())
                                    || transform.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()) continue;
                            var list = transform.getScope().get().asMethodCallExpr();
                            if (!list.getNameAsString().equals("list") || list.getArguments().size() != 1 || !(list.getArgument(0) instanceof IntegerLiteralExpr size)) continue;
                            var methods = record.getMethodsByName(encoder.getIdentifier());
                            if (methods.size() != 1 || methods.getFirst().getBody().isEmpty()) continue;
                            var returns = methods.getFirst().getBody().get().getStatements();
                            if (returns.size() != 1 || !returns.get(0).isReturnStmt()
                                    || returns.get(0).asReturnStmt().getExpression().filter(MethodCallExpr.class::isInstance).isEmpty()) continue;
                            var values = returns.get(0).asReturnStmt().getExpression().get().asMethodCallExpr();
                            if (!values.getNameAsString().equals("of") || values.getScope().isEmpty()
                                    || !Set.of("List", "java.util.List").contains(values.getScope().get().toString())) continue;
                            var order = new ArrayList<String>();
                            for (var value : values.getArguments()) {
                                String name = value instanceof NameExpr n ? n.getNameAsString()
                                        : value instanceof FieldAccessExpr f && f.getScope().isThisExpr() ? f.getNameAsString() : "";
                                if (record.getParameters().stream().noneMatch(p -> p.getNameAsString().equals(name))) { order.clear(); break; }
                                order.add(name);
                            }
                            if (order.size() != size.asNumber().intValue() || order.size() != record.getParameters().size()
                                    || new HashSet<>(order).size() != order.size() || !decodesInOrder(record, order)) continue;
                            String name = record.getFullyQualifiedName().orElseThrow() + '#' + variable.getNameAsString();
                            codecs.put(name, new ListShape(order, ""));
                            pending.put(name, list.getScope().orElseThrow());
                        }
                    }
                }
            }
        }
        for (var entry : List.copyOf(codecs.entrySet())) {
            String scalar = scalar(pending.get(entry.getKey()), definitions, new HashSet<>());
            if (scalar == null) codecs.remove(entry.getKey());
            else codecs.put(entry.getKey(), new ListShape(entry.getValue().fields(), scalar));
        }
        if (components == null) return new Layouts(Map.of(), Set.of(), registrations(attributes));
        var result = new LinkedHashMap<String, ListShape>();
        for (var call : components.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals("register") || call.getArguments().size() < 3
                    || !(call.getArgument(0) instanceof StringLiteralExpr key) || !(call.getArgument(2) instanceof FieldAccessExpr codec)) continue;
            String owner = codec.getScope().toString();
            String selectedOwner = owner;
            if (!owner.contains(".")) {
                var imports = components.getImports().stream().filter(value -> !value.isAsterisk() && !value.isStatic()
                        && value.getName().getIdentifier().equals(owner)).toList();
                if (imports.size() == 1) selectedOwner = imports.getFirst().getNameAsString();
                else selectedOwner = components.getPackageDeclaration().map(value -> value.getNameAsString() + '.').orElse("") + owner;
            }
            var shape = codecs.get(selectedOwner + '#' + codec.getNameAsString());
            if (shape != null) result.put("minecraft:" + key.asString(), shape);
        }
        return new Layouts(Map.copyOf(result), registrations(components), registrations(attributes));
    }

    private static Set<String> registrations(CompilationUnit unit) {
        if (unit == null) return Set.of();
        var keys = new TreeSet<String>();
        for (var call : unit.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("register") && !call.getArguments().isEmpty() && call.getArgument(0) instanceof StringLiteralExpr key) {
                keys.add("minecraft:" + key.asString());
            }
        }
        return Set.copyOf(keys);
    }

    private static String scalar(Expression expression, Map<String, Expression> definitions, Set<String> active) {
        if (expression instanceof MethodCallExpr call && call.getScope().isPresent()
                && Set.of("transform", "optional").contains(call.getNameAsString())) return scalar(call.getScope().get(), definitions, active);
        if (!(expression instanceof FieldAccessExpr field)) return null;
        var unit = field.findCompilationUnit().orElseThrow();
        String owner = qualify(unit, field.getScope().toString());
        if (owner.equals("net.minestom.server.codec.Codec")) return switch (field.getNameAsString()) {
            case "STRING", "KEY" -> "string";
            case "BYTE", "SHORT", "INT", "LONG" -> "integer";
            case "FLOAT", "DOUBLE" -> "number";
            case "BOOLEAN" -> "boolean";
            default -> null;
        };
        String key = owner + '#' + field.getNameAsString();
        if (!active.add(key)) return null;
        var definition = definitions.get(key);
        return definition == null ? null : scalar(definition, definitions, active);
    }

    private static String qualify(CompilationUnit unit, String owner) {
        if (owner.contains(".")) return owner;
        var imports = unit.getImports().stream().filter(value -> !value.isAsterisk() && !value.isStatic()
                && value.getName().getIdentifier().equals(owner)).toList();
        if (imports.size() == 1) return imports.getFirst().getNameAsString();
        return unit.getPackageDeclaration().map(value -> value.getNameAsString() + '.').orElse("") + owner;
    }

    private static boolean decodesInOrder(RecordDeclaration record, List<String> order) {
        for (var constructor : record.getConstructors()) {
            if (constructor.getParameters().size() != 1 || !constructor.getParameter(0).getType().asString().matches("(?:java\\.util\\.)?List<.+>")) continue;
            var body = constructor.getBody().getStatements();
            if (body.size() != 1 || !body.get(0).isExplicitConstructorInvocationStmt()) continue;
            var invocation = body.get(0).asExplicitConstructorInvocationStmt();
            if (!invocation.isThis() || invocation.getArguments().size() != order.size()) continue;
            boolean valid = true;
            for (int i = 0; i < order.size(); i++) {
                if (!(invocation.getArgument(i) instanceof MethodCallExpr call)) { valid = false; break; }
                String list = constructor.getParameter(0).getNameAsString();
                Integer index = null;
                if (call.getNameAsString().equals("get") && call.getScope().map(Object::toString).filter(list::equals).isPresent()
                        && call.getArguments().size() == 1 && call.getArgument(0) instanceof IntegerLiteralExpr number) index = number.asNumber().intValue();
                else if (call.getScope().isEmpty() && call.getArguments().size() == 2 && call.getArgument(0).toString().equals(list)
                        && call.getArgument(1) instanceof IntegerLiteralExpr number) {
                    var helpers = record.getMethodsByName(call.getNameAsString());
                    if (helpers.size() == 1 && helpers.getFirst().getParameters().size() == 2) {
                        var helper = helpers.getFirst();
                        String values = helper.getParameter(0).getNameAsString(), offset = helper.getParameter(1).getNameAsString();
                        var getters = helper.findAll(MethodCallExpr.class).stream().filter(value -> value.getNameAsString().equals("get")
                                && value.getScope().map(Object::toString).filter(values::equals).isPresent()
                                && value.getArguments().size() == 1 && value.getArgument(0).toString().equals(offset)).toList();
                        // A guarded lookup is supported only when its successful arm returns the element directly.
                        if (getters.size() == 1 && helpers.getFirst().getBody().filter(block -> block.getStatements().size() == 1
                                && block.getStatement(0).isReturnStmt()).isPresent()) {
                            var returned = helper.getBody().get().getStatement(0).asReturnStmt().getExpression().orElseThrow();
                            boolean direct = returned.equals(getters.getFirst());
                            if (returned instanceof ConditionalExpr condition && condition.getThenExpr().equals(getters.getFirst())
                                    && condition.getCondition() instanceof BinaryExpr comparison && comparison.getOperator() == BinaryExpr.Operator.LESS
                                    && comparison.getLeft().toString().equals(offset) && comparison.getRight() instanceof MethodCallExpr size
                                    && size.getNameAsString().equals("size") && size.getScope().map(Object::toString).filter(values::equals).isPresent()) direct = true;
                            if (direct) index = number.asNumber().intValue();
                        }
                    }
                }
                if (index == null || index < 0 || index >= order.size() || !order.get(index).equals(record.getParameter(i).getNameAsString())) { valid = false; break; }
            }
            if (valid) return true;
        }
        return false;
    }
}
