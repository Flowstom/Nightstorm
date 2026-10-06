package net.flowstom.nightstorm;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Rewrites a data generator when a referenced type's constant catalog moves between server jars.
 * Names, wrappers, and the registry lookup chain come from the two classpaths, not from a version list.
 */
final class GeneratorApiRelocation {
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    private GeneratorApiRelocation() {
    }

    static List<String> relocate(Path generator, Path baselineJar, Path targetJar, Path proofOutput) throws IOException {
        var baseline = load(baselineJar);
        var target = load(targetJar);
        var units = parse(generator);
        var vanished = referenced(units, baseline, target);
        if (vanished.isEmpty()) {
            if (Files.exists(proofOutput) && sourcesContain(units, "ApiRelocation.")) return List.of();
            writeProof(proofOutput, Map.of(), List.of());
            return List.of();
        }
        var usage = usage(units, vanished, baseline);
        var moves = new LinkedHashMap<String, TypeMove>();
        var accessors = new ArrayList<Map<String, Object>>();
        for (String type : vanished) {
            moves.put(type, prove(type, usage.byType.get(type), units, baseline, target, accessors));
        }
        var changed = new ArrayList<Parsed>();
        for (var unit : units) if (rewrite(unit, vanished, baseline, moves.keySet())) changed.add(unit);
        writeProof(proofOutput, moves, accessors);
        for (var unit : changed) Files.writeString(unit.path(), unit.unit().toString());
        return List.copyOf(moves.keySet());
    }

    private static TypeMove prove(String type, Usage usage, List<Parsed> units, Map<String, ClassModel> baseline,
                                  Map<String, ClassModel> target, List<Map<String, Object>> accessors) {
        var model = baseline.get(type);
        if (model == null) fail(type, "missing from the baseline jar");
        var catalog = catalog(type, model, target);
        var members = new LinkedHashMap<String, Map<String, Object>>();
        for (String field : usage.fields()) align(type, member(model, field, true), catalog.value(), target, members);
        for (String method : usage.methods()) align(type, member(model, method, false), catalog.value(), target, members);
        ResolveMove resolve = catalog.identity().raw().equals(catalog.value()) ? null
                : resolve(type, catalog.identity(), catalog.value(), provider(units, target), target);
        for (Call call : usage.accessors()) {
            var owner = findType(call.receiver(), baseline);
            var original = findMethod(owner, call.method(), call.arguments(), baseline);
            if (original == null || !original.type().raw().equals(type)) {
                fail(type, "cannot find " + call.receiver() + "." + call.method() + " in the baseline jar");
            }
            var replacement = replacement(type, call, catalog, target);
            accessors.add(Map.of(
                    "baselineType", type,
                    "method", call.method(),
                    "targetMethod", replacement.name(),
                    "unwrap", replacement.unwrap()));
        }
        var encoded = new LinkedHashMap<String, Object>();
        encoded.put("catalog", catalog.catalogClass());
        encoded.put("identity", catalog.identity().raw());
        encoded.put("value", catalog.value());
        encoded.put("members", members);
        if (resolve != null) encoded.put("resolve", Map.of("method", resolve.method(), "unwrap", resolve.unwrap()));
        return new TypeMove(encoded, catalog);
    }

    private static Catalog catalog(String type, ClassModel model, Map<String, ClassModel> target) {
        var names = new HashSet<String>();
        for (Member field : model.fields()) {
            if (field.isStatic() && field.type().raw().equals(type)) names.add(field.name());
        }
        if (names.size() < 2) fail(type, "no constant catalog");
        String bestClass = null;
        int best = 0;
        boolean tie = false;
        JavaType bestType = null;
        for (ClassModel candidate : target.values()) {
            if (candidate.name().matches(".*\\$\\d+($|\\..*)")) continue;
            var groups = new HashMap<String, List<Member>>();
            for (Member field : candidate.fields()) {
                if (!field.isStatic() || !names.contains(field.name())) continue;
                groups.computeIfAbsent(field.type().key(), key -> new ArrayList<>()).add(field);
            }
            for (var group : groups.values()) {
                if (group.size() > best) {
                    best = group.size();
                    bestClass = candidate.name();
                    bestType = group.getFirst().type();
                    tie = false;
                } else if (group.size() == best && best > 0) {
                    tie = true;
                }
            }
        }
        if (bestClass == null || tie || best < 2 || best * 2 <= names.size()) {
            fail(type, "no unique constant catalog");
        }
        String value = bestType.arguments().size() == 1 ? bestType.arguments().getFirst().raw() : bestType.raw();
        if (lookup(target, value) == null) fail(type, "relocated value " + value + " is missing");
        return new Catalog(bestClass, bestType, value);
    }

    private static void align(String type, Member source, String value, Map<String, ClassModel> target,
                              Map<String, Map<String, Object>> members) {
        var destination = lookup(target, value);
        if (destination == null) fail(type, "relocated value " + value + " is missing");
        String property = property(source.name());
        Member match = null;
        for (Member candidate : destination.members()) {
            if (candidate.isStatic() || candidate.bridge() || !property(candidate.name()).equals(property)) continue;
            if (candidate.field() && !candidate.parameters().isEmpty()) continue;
            if (!candidate.field() && !candidate.parameters().isEmpty()) continue;
            if (match != null) fail(type, "ambiguous member " + source.name());
            match = candidate;
        }
        if (match == null) fail(type, "member " + source.name() + " has no target on " + value);
        var unwrap = peel(type, match.type(), source.type().raw(), target);
        members.put(source.name(), Map.of(
                "kind", match.field() ? "field" : "method",
                "name", match.name(),
                "unwrap", unwrap));
    }

    private static MethodRef replacement(String type, Call call, Catalog catalog, Map<String, ClassModel> target) {
        var owner = findType(call.receiver(), target);
        var candidates = new ArrayList<MethodRef>();
        var seen = new HashSet<String>();
        String current = owner == null ? null : owner.name();
        var visited = new HashSet<String>();
        while (current != null && visited.add(current)) {
            var model = target.get(current);
            if (model == null) break;
            for (Member method : model.methods()) {
                if (method.isStatic() || method.bridge() || !method.parameters().isEmpty() || !method.type().references(catalog.value())) continue;
                try {
                    var unwrapped = peel(type, method.type(), catalog.identity().raw(), target);
                    if (!seen.add(method.name())) continue;
                    candidates.add(new MethodRef(method.name(), unwrapped));
                } catch (IllegalStateException ignored) {
                    // A no-argument method that mentions the value but does not unwrap to the constant identity is not a replacement.
                }
            }
            current = model.superName();
            for (String iface : model.interfaces()) {
                if (seen.add("interface:" + iface)) collectInterface(iface, catalog, target, candidates, seen, type);
            }
        }
        if (candidates.size() != 1) {
            fail(type, (candidates.isEmpty() ? "no replacement for " : "ambiguous replacement for ") + call.receiver() + "." + call.method());
        }
        return candidates.getFirst();
    }

    private static void collectInterface(String name, Catalog catalog, Map<String, ClassModel> target, List<MethodRef> candidates,
                                         Set<String> seen, String type) {
        var model = target.get(name);
        if (model == null) return;
        for (Member method : model.methods()) {
            if (method.isStatic() || method.bridge() || !method.parameters().isEmpty() || !method.type().references(catalog.value())) continue;
            try {
                var unwrapped = peel(type, method.type(), catalog.identity().raw(), target);
                if (!seen.add(method.name())) continue;
                candidates.add(new MethodRef(method.name(), unwrapped));
            } catch (IllegalStateException ignored) {
                // Same as the class walk: keep only unwrappings that land on the constant identity.
            }
        }
        for (String iface : model.interfaces()) collectInterface(iface, catalog, target, candidates, seen, type);
    }

    private static ResolveMove resolve(String type, JavaType identity, String value, ClassModel provider, Map<String, ClassModel> classes) {
        record Chain(int score, String method, List<String> unwrap) {
        }
        var chains = new LinkedHashMap<String, Chain>();
        for (Member method : inherited(provider, classes)) {
            if (method.isStatic() || method.bridge() || method.parameters().size() != 1) continue;
            var bindings = new HashMap<String, JavaType>();
            if (!unify(method.parameters().getFirst(), identity, bindings)) continue;
            var returned = substitute(method.type(), bindings);
            var unwrap = new ArrayList<String>();
            int score = 1;
            while ("java.util.Optional".equals(returned.raw()) && returned.arguments().size() == 1) {
                unwrap.add("optional");
                returned = returned.arguments().getFirst();
                score++;
            }
            if (returned.raw().equals(value) && returned.arguments().isEmpty()) {
                chains.putIfAbsent(method.name() + unwrap, new Chain(score, method.name(), List.copyOf(unwrap)));
                continue;
            }
            String valueMethod = valueMethod(lookup(classes, returned.raw()), returned, value);
            if (valueMethod == null) continue;
            unwrap.add("value:" + valueMethod);
            chains.putIfAbsent(method.name() + unwrap, new Chain(score + 1, method.name(), List.copyOf(unwrap)));
        }
        if (chains.isEmpty()) fail(type, "no registry resolution from " + identity.raw() + " to " + value);
        int best = chains.values().stream().mapToInt(Chain::score).min().orElseThrow();
        var winners = chains.values().stream().filter(chain -> chain.score() == best).toList();
        if (winners.size() != 1) fail(type, "ambiguous registry resolution for " + value);
        return new ResolveMove(winners.getFirst().method(), winners.getFirst().unwrap());
    }

    private static String valueMethod(ClassModel model, JavaType parameterized, String goal) {
        if (model == null) return null;
        var bindings = new HashMap<String, JavaType>();
        for (int index = 0; index < model.typeParameters().size() && index < parameterized.arguments().size(); index++) {
            bindings.put(model.typeParameters().get(index), parameterized.arguments().get(index));
        }
        String found = null;
        for (Member method : model.methods()) {
            if (method.field() || method.isStatic() || method.bridge() || !method.parameters().isEmpty()) continue;
            var returned = substitute(method.type(), bindings);
            if (returned.variable() || !returned.raw().equals(goal) || !returned.arguments().isEmpty()) continue;
            if (found != null) return null;
            found = method.name();
        }
        return found;
    }

    private static List<String> peel(String type, JavaType current, String goal, Map<String, ClassModel> classes) {
        var steps = new ArrayList<String>();
        var seen = new HashSet<String>();
        while (!current.raw().equals(goal)) {
            if (!seen.add(current.key())) fail(type, "cannot unwrap " + current.raw() + " to " + goal);
            if ("java.util.Optional".equals(current.raw()) && current.arguments().size() == 1) {
                steps.add("optional");
                current = current.arguments().getFirst();
                continue;
            }
            if (current.arguments().size() == 1) {
                String method = valueMethod(lookup(classes, current.raw()), current, current.arguments().getFirst().raw());
                if (method != null) {
                    steps.add("value:" + method);
                    current = current.arguments().getFirst();
                    continue;
                }
            }
            fail(type, "cannot unwrap " + current.raw() + " to " + goal);
        }
        return List.copyOf(steps);
    }

    private static boolean rewrite(Parsed unit, Set<String> vanished, Map<String, ClassModel> baseline, Set<String> proved) {
        var resolver = new Resolver(unit.unit(), baseline);
        var locals = locals(unit.unit(), resolver, baseline);
        var edits = new ArrayList<Edit>();
        for (ClassExpr expr : unit.unit().findAll(ClassExpr.class)) {
            String name = resolver.typeName(expr.getType());
            if (!vanished.contains(name)) continue;
            if (expr.getParentNode().orElse(null) instanceof MethodCallExpr parent
                    && parent.getArguments().isEmpty()
                    && (parent.getNameAsString().equals("getDeclaredFields") || parent.getNameAsString().equals("getFields"))) {
                final var catalogCall = parent;
                edits.add(new Edit(depth(catalogCall), () -> catalogCall.replace(expression("ApiRelocation.catalogFields(\"" + name + "\")"))));
            } else {
                fail(name, "unsupported class literal");
            }
        }
        for (ObjectCreationExpr creation : unit.unit().findAll(ObjectCreationExpr.class)) {
            String name = resolver.typeName(creation.getType());
            if (vanished.contains(name)) fail(name, "unsupported construction");
        }
        for (FieldAccessExpr access : unit.unit().findAll(FieldAccessExpr.class)) {
            String owner = expressionType(access.getScope(), locals, resolver, baseline);
            if (!vanished.contains(owner)) {
                if (access.getScope() instanceof NameExpr name && !locals.containsKey(name.getNameAsString())
                        && vanished.contains(resolver.simple(name.getNameAsString()))) {
                    fail(resolver.simple(name.getNameAsString()), "unsupported static field " + access.getNameAsString());
                }
                continue;
            }
            var member = member(baseline.get(owner), access.getNameAsString(), true);
            edits.add(new Edit(depth(access), () -> access.replace(read(owner, access.getScope(), member))));
        }
        for (MethodCallExpr call : unit.unit().findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) continue;
            String owner = expressionType(call.getScope().get(), locals, resolver, baseline);
            if (vanished.contains(owner)) {
                if (call.getNameAsString().equals("equals") && call.getArguments().size() == 1) {
                    Expression argument = call.getArgument(0);
                    edits.add(new Edit(depth(call), () -> call.replace(expression(
                            "java.util.Objects.equals(" + call.getScope().get() + ", " + argument + ")"))));
                    continue;
                }
                if (!call.getArguments().isEmpty()) fail(owner, "unsupported call " + call.getNameAsString());
                var member = member(baseline.get(owner), call.getNameAsString(), false);
                edits.add(new Edit(depth(call), () -> call.replace(read(owner, call.getScope().get(), member))));
                continue;
            }
            if (owner == null) continue;
            var method = findMethod(findType(owner, baseline), call.getNameAsString(), call.getArguments().size(), baseline);
            if (method != null && vanished.contains(method.type().raw())) {
                String returned = method.type().raw();
                if (!proved.contains(returned)) fail(returned, "call " + call.getNameAsString() + " was not proved");
                edits.add(new Edit(depth(call), () -> call.replace(expression(
                        "ApiRelocation.read(\"" + returned + "\", " + call.getScope().get() + ", \"" + call.getNameAsString() + "\")"))));
            }
        }
        for (CastExpr cast : unit.unit().findAll(CastExpr.class)) {
            String name = resolver.typeName(cast.getType());
            if (!vanished.contains(name)) continue;
            edits.add(new Edit(depth(cast), () -> cast.replace(cast.getExpression().clone())));
        }
        edits.sort((left, right) -> Integer.compare(right.depth(), left.depth()));
        for (Edit edit : edits) edit.apply().run();
        boolean typesChanged = false;
        for (ClassOrInterfaceType type : List.copyOf(unit.unit().findAll(ClassOrInterfaceType.class))) {
            String name = resolver.typeName(type);
            if (!vanished.contains(name)) continue;
            if (type.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration) fail(name, "unsupported supertype");
            type.setName("Object");
            type.setScope(null);
            type.setTypeArguments((NodeList<com.github.javaparser.ast.type.Type>) null);
            typesChanged = true;
        }
        boolean importsChanged = false;
        for (String name : vanished) {
            importsChanged |= unit.unit().getImports().removeIf(imported -> !imported.isAsterisk() && imported.getNameAsString().equals(name));
        }
        if ((!edits.isEmpty() || typesChanged) && !unit.unit().getPackageDeclaration().map(pkg -> pkg.getNameAsString()).orElse("").equals("net.minestom.generators")) {
            unit.unit().addImport("net.minestom.generators.ApiRelocation");
        }
        return !edits.isEmpty() || typesChanged || importsChanged;
    }

    private static Expression read(String owner, Expression receiver, Member member) {
        String helper = primitiveHelper(member.type().raw());
        if (helper != null) {
            return expression("ApiRelocation." + helper + "(\"" + owner + "\", " + receiver + ", \"" + member.name() + "\")");
        }
        return expression("((" + member.type().raw().replace('$', '.') + ") ApiRelocation.read(\"" + owner + "\", " + receiver + ", \"" + member.name() + "\"))");
    }

    private static String expressionType(Expression expression, Map<String, String> locals, Resolver resolver, Map<String, ClassModel> baseline) {
        if (expression instanceof NameExpr name) return locals.get(name.getNameAsString());
        if (expression instanceof CastExpr cast) return resolver.typeName(cast.getType());
        if (expression instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String owner = expressionType(call.getScope().get(), locals, resolver, baseline);
            if (owner == null) return null;
            var method = findMethod(findType(owner, baseline), call.getNameAsString(), call.getArguments().size(), baseline);
            return method == null ? null : method.type().raw();
        }
        if (expression instanceof FieldAccessExpr access) {
            String owner = expressionType(access.getScope(), locals, resolver, baseline);
            if (owner == null) return null;
            try {
                return member(baseline.get(owner), access.getNameAsString(), true).type().raw();
            } catch (IllegalStateException exception) {
                return null;
            }
        }
        return null;
    }

    private static Map<String, String> locals(CompilationUnit unit, Resolver resolver, Map<String, ClassModel> baseline) {
        var locals = new HashMap<String, String>();
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            for (var parameter : method.getParameters()) {
                String name = resolver.typeName(parameter.getType());
                if (name != null) locals.put(parameter.getNameAsString(), name);
            }
        }
        for (VariableDeclarator variable : unit.findAll(VariableDeclarator.class)) {
            String name = resolver.typeName(variable.getType());
            if ("var".equals(variable.getType().asString()) && variable.getInitializer().isPresent()) {
                name = expressionType(variable.getInitializer().get(), locals, resolver, baseline);
            }
            if (name != null) locals.put(variable.getNameAsString(), name);
        }
        return locals;
    }

    private static UsageMap usage(List<Parsed> units, Set<String> vanished, Map<String, ClassModel> baseline) {
        var usage = new UsageMap();
        for (String type : vanished) usage.byType.put(type, new Usage(new HashSet<>(), new HashSet<>(), new ArrayList<>()));
        for (Parsed parsed : units) {
            var resolver = new Resolver(parsed.unit(), baseline);
            var locals = locals(parsed.unit(), resolver, baseline);
            for (FieldAccessExpr access : parsed.unit().findAll(FieldAccessExpr.class)) {
                String owner = expressionType(access.getScope(), locals, resolver, baseline);
                if (vanished.contains(owner)) usage.byType.get(owner).fields().add(access.getNameAsString());
            }
            for (MethodCallExpr call : parsed.unit().findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty()) continue;
                String owner = expressionType(call.getScope().get(), locals, resolver, baseline);
                if (vanished.contains(owner)) {
                    if (!call.getNameAsString().equals("equals")) usage.byType.get(owner).methods().add(call.getNameAsString());
                    continue;
                }
                if (owner == null) continue;
                var method = findMethod(findType(owner, baseline), call.getNameAsString(), call.getArguments().size(), baseline);
                if (method != null && vanished.contains(method.type().raw())) {
                    usage.byType.get(method.type().raw()).accessors().add(new Call(owner, call.getNameAsString(), call.getArguments().size()));
                }
            }
        }
        for (Usage value : usage.byType.values()) {
            var unique = new ArrayList<Call>();
            for (Call call : value.accessors()) if (!unique.contains(call)) unique.add(call);
            value.accessors().clear();
            value.accessors().addAll(unique);
        }
        return usage;
    }

    private static Set<String> referenced(List<Parsed> units, Map<String, ClassModel> baseline, Map<String, ClassModel> target) {
        var vanished = new HashSet<String>();
        for (Parsed parsed : units) {
            var resolver = new Resolver(parsed.unit(), baseline);
            for (ClassOrInterfaceType type : parsed.unit().findAll(ClassOrInterfaceType.class)) consider(resolver.typeName(type), baseline, target, vanished);
            for (ClassExpr expr : parsed.unit().findAll(ClassExpr.class)) consider(resolver.typeName(expr.getType()), baseline, target, vanished);
        }
        return vanished;
    }

    private static void consider(String name, Map<String, ClassModel> baseline, Map<String, ClassModel> target, Set<String> vanished) {
        if (name == null || name.indexOf('.') < 0) return;
        if (baseline.containsKey(name) && lookup(target, name) == null) vanished.add(name);
    }

    private static Member member(ClassModel model, String name, boolean field) {
        if (model == null) return fail(name, "has no baseline type");
        for (Member candidate : field ? model.fields() : model.methods()) {
            if (candidate.name().equals(name) && candidate.field() == field && !candidate.isStatic()) return candidate;
        }
        return fail(model.name(), (field ? "field " : "method ") + name + " is missing");
    }

    private static Member findMethod(ClassModel owner, String name, int arguments, Map<String, ClassModel> classes) {
        Member found = null;
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        if (owner != null) pending.add(owner.name());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            var model = classes.get(current);
            if (model == null) continue;
            for (Member method : model.methods()) {
                if (method.field() || method.isStatic() || method.bridge() || !method.name().equals(name) || method.parameters().size() != arguments) continue;
                if (found != null && !found.type().equals(method.type())) return fail(owner.name(), "ambiguous method " + name);
                found = method;
            }
            if (model.superName() != null) pending.add(model.superName());
            pending.addAll(model.interfaces());
        }
        return found;
    }

    private static ClassModel findType(String name, Map<String, ClassModel> classes) {
        return lookup(classes, name);
    }

    private static List<Member> inherited(ClassModel model, Map<String, ClassModel> classes) {
        var result = new ArrayList<Member>();
        var seen = new HashSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(model.name());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) continue;
            var current = classes.get(name);
            if (current == null) continue;
            for (Member method : current.methods()) {
                if (method.field()) continue;
                String key = method.name() + method.parameters().stream().map(JavaType::raw).toList() + method.type().raw();
                if (seen.add(key)) result.add(method);
            }
            if (current.superName() != null) pending.add(current.superName());
            pending.addAll(current.interfaces());
        }
        return result;
    }

    private static ClassModel provider(List<Parsed> units, Map<String, ClassModel> target) {
        MethodDeclaration factory = null;
        CompilationUnit owner = null;
        for (Parsed parsed : units) {
            for (var type : parsed.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.getNameAsString().equals("MinecraftCompatibility")) continue;
                for (MethodDeclaration method : type.getMethods()) {
                    if (!method.isStatic() || !method.getParameters().isEmpty()) continue;
                    if (factory != null) fail("MinecraftCompatibility", "ambiguous registry lookup factory");
                    factory = method;
                    owner = parsed.unit();
                }
            }
        }
        if (factory == null) return fail("MinecraftCompatibility", "missing registry lookup factory");
        String name = new Resolver(owner, target).typeName(factory.getType());
        var model = lookup(target, name);
        if (model == null) return fail(name == null ? "MinecraftCompatibility" : name, "registry lookup type is missing from the target jar");
        return model;
    }

    private static boolean unify(JavaType parameter, JavaType argument, Map<String, JavaType> bindings) {
        if (parameter.variable()) {
            if ("*".equals(parameter.raw())) return true;
            var existing = bindings.putIfAbsent(parameter.raw(), argument);
            return existing == null || existing.equals(argument);
        }
        if (!parameter.raw().equals(argument.raw())) return false;
        if (parameter.arguments().size() != argument.arguments().size()) return parameter.arguments().isEmpty();
        for (int index = 0; index < parameter.arguments().size(); index++) {
            if (!unify(parameter.arguments().get(index), argument.arguments().get(index), bindings)) return false;
        }
        return true;
    }

    private static JavaType substitute(JavaType type, Map<String, JavaType> bindings) {
        if (type.variable()) return bindings.getOrDefault(type.raw(), type);
        return new JavaType(type.raw(), type.arguments().stream().map(argument -> substitute(argument, bindings)).toList(), false);
    }

    private static String property(String name) {
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        return name;
    }

    private static String primitiveHelper(String primitive) {
        return switch (primitive) {
            case "float" -> "asFloat";
            case "double" -> "asDouble";
            case "int" -> "asInt";
            case "long" -> "asLong";
            case "boolean" -> "asBoolean";
            default -> null;
        };
    }

    private static Expression expression(String source) {
        return PARSER.parseExpression(source).getResult().orElseThrow(() -> new IllegalStateException("Cannot parse relocation " + source));
    }

    private static int depth(Node node) {
        int depth = 0;
        while (node.getParentNode().isPresent()) {
            depth++;
            node = node.getParentNode().get();
        }
        return depth;
    }

    private static void writeProof(Path output, Map<String, TypeMove> types, List<Map<String, Object>> accessors) throws IOException {
        var encoded = new LinkedHashMap<String, Object>();
        var typeMap = new LinkedHashMap<String, Object>();
        types.forEach((name, move) -> typeMap.put(name, move.encoded()));
        encoded.put("types", typeMap);
        encoded.put("accessors", accessors);
        Json.write(output, encoded);
    }

    private static boolean sourcesContain(List<Parsed> units, String text) throws IOException {
        for (Parsed unit : units) if (unit.original().contains(text)) return true;
        return false;
    }

    private static List<Parsed> parse(Path root) throws IOException {
        var units = new ArrayList<Parsed>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("/build/") && !path.toString().contains("/.git/")).toList()) {
                var result = PARSER.parse(file);
                var unit = result.getResult().filter(ignored -> result.isSuccessful())
                        .orElseThrow(() -> new IllegalStateException("Cannot parse " + file + ": " + result.getProblems()));
                units.add(new Parsed(file, unit, Files.readString(file)));
            }
        }
        return units;
    }

    private static Map<String, ClassModel> load(Path jar) throws IOException {
        var models = new HashMap<String, ClassModel>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                var reader = new ClassReader(zip.getInputStream(entry).readAllBytes());
                var node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                models.put(node.name.replace('/', '.'), classModel(node));
            }
        }
        return models;
    }

    private static ClassModel classModel(ClassNode node) {
        var parameters = new ArrayList<String>();
        if (node.signature != null && node.signature.startsWith("<")) skipTypeParameters(node.signature, new int[]{0}, parameters);
        var fields = new ArrayList<Member>();
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_PUBLIC) == 0 || (field.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            fields.add(new Member(field.name, (field.access & Opcodes.ACC_STATIC) != 0, false, true, List.of(), type(field.desc, field.signature)));
        }
        var methods = new ArrayList<Member>();
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) == 0) continue;
            boolean bridge = (method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0;
            var signature = method.signature == null ? methodSignature(method.desc) : parseMethod(method.signature);
            methods.add(new Member(method.name, (method.access & Opcodes.ACC_STATIC) != 0, bridge, false, signature.parameters(), signature.returned()));
        }
        String superName = node.superName == null ? null : node.superName.replace('/', '.');
        var interfaces = node.interfaces.stream().map(name -> name.replace('/', '.')).toList();
        return new ClassModel(node.name.replace('/', '.'), superName, interfaces, List.copyOf(parameters), List.copyOf(fields), List.copyOf(methods));
    }

    private static ClassModel lookup(Map<String, ClassModel> classes, String name) {
        if (name == null) return null;
        String current = name;
        while (true) {
            var model = classes.get(current);
            if (model != null) return model;
            int dot = current.lastIndexOf('.');
            if (dot < 0) return null;
            current = current.substring(0, dot) + "$" + current.substring(dot + 1);
        }
    }

    private static JavaType type(String descriptor, String signature) {
        if (signature != null) return parseType(signature, new int[]{0});
        return parseType(descriptor, new int[]{0});
    }

    private static MethodSig methodSignature(String descriptor) {
        return parseMethod(descriptor);
    }

    private static MethodSig parseMethod(String signature) {
        var cursor = new int[]{0};
        if (signature.charAt(0) == '<') skipTypeParameters(signature, cursor, new ArrayList<>());
        if (signature.charAt(cursor[0]) != '(') throw new IllegalStateException("Bad method signature " + signature);
        cursor[0]++;
        var parameters = new ArrayList<JavaType>();
        while (signature.charAt(cursor[0]) != ')') parameters.add(parseType(signature, cursor));
        cursor[0]++;
        return new MethodSig(List.copyOf(parameters), parseType(signature, cursor));
    }

    private static void skipTypeParameters(String signature, int[] cursor, List<String> names) {
        if (signature.charAt(cursor[0]) != '<') return;
        cursor[0]++;
        while (signature.charAt(cursor[0]) != '>') {
            int colon = signature.indexOf(':', cursor[0]);
            names.add(signature.substring(cursor[0], colon));
            cursor[0] = colon + 1;
            if (signature.charAt(cursor[0]) != ':') parseType(signature, cursor);
            while (signature.charAt(cursor[0]) == ':') {
                cursor[0]++;
                parseType(signature, cursor);
            }
        }
        cursor[0]++;
    }

    private static JavaType parseType(String signature, int[] cursor) {
        char kind = signature.charAt(cursor[0]++);
        return switch (kind) {
            case 'B' -> JavaType.primitive("byte");
            case 'C' -> JavaType.primitive("char");
            case 'D' -> JavaType.primitive("double");
            case 'F' -> JavaType.primitive("float");
            case 'I' -> JavaType.primitive("int");
            case 'J' -> JavaType.primitive("long");
            case 'S' -> JavaType.primitive("short");
            case 'Z' -> JavaType.primitive("boolean");
            case 'V' -> JavaType.primitive("void");
            case '[' -> {
                var element = parseType(signature, cursor);
                yield new JavaType(element.raw() + "[]", List.of(), false);
            }
            case 'T' -> {
                int start = cursor[0];
                while (signature.charAt(cursor[0]) != ';') cursor[0]++;
                String name = signature.substring(start, cursor[0]);
                cursor[0]++;
                yield new JavaType(name, List.of(), true);
            }
            case 'L' -> {
                // Inner classes of a parameterized type are separated by '.' and belong to the same binary name.
                var raw = new StringBuilder();
                var arguments = List.<JavaType>of();
                while (true) {
                    int start = cursor[0];
                    while (signature.charAt(cursor[0]) != ';' && signature.charAt(cursor[0]) != '<' && signature.charAt(cursor[0]) != '.') {
                        cursor[0]++;
                    }
                    if (!raw.isEmpty()) raw.append('$');
                    raw.append(signature.substring(start, cursor[0]).replace('/', '.'));
                    if (signature.charAt(cursor[0]) == '<') {
                        cursor[0]++;
                        var current = new ArrayList<JavaType>();
                        while (signature.charAt(cursor[0]) != '>') {
                            char wildcard = signature.charAt(cursor[0]);
                            if (wildcard == '*') {
                                cursor[0]++;
                                current.add(new JavaType("*", List.of(), true));
                                continue;
                            }
                            if (wildcard == '+' || wildcard == '-') cursor[0]++;
                            current.add(parseType(signature, cursor));
                        }
                        cursor[0]++;
                        arguments = List.copyOf(current);
                    }
                    if (signature.charAt(cursor[0]) != '.') break;
                    cursor[0]++;
                }
                if (signature.charAt(cursor[0]) != ';') throw new IllegalStateException("Bad type signature " + signature);
                cursor[0]++;
                yield new JavaType(raw.toString(), arguments, false);
            }
            default -> throw new IllegalStateException("Bad type signature " + signature + " at " + kind);
        };
    }

    private static <T> T fail(String type, String reason) {
        throw new IllegalStateException("Cannot relocate " + type + ": " + reason);
    }

    private record JavaType(String raw, List<JavaType> arguments, boolean variable) {
        static JavaType primitive(String name) {
            return new JavaType(name, List.of(), false);
        }

        boolean references(String name) {
            return raw.equals(name) || arguments.stream().anyMatch(argument -> argument.references(name));
        }

        String key() {
            return raw + arguments.stream().map(JavaType::key).toList();
        }
    }

    private record MethodSig(List<JavaType> parameters, JavaType returned) {
    }

    private record Member(String name, boolean isStatic, boolean bridge, boolean field, List<JavaType> parameters, JavaType type) {
    }

    private record ClassModel(String name, String superName, List<String> interfaces, List<String> typeParameters, List<Member> fields,
                              List<Member> methods) {
        List<Member> members() {
            var combined = new ArrayList<Member>(fields);
            combined.addAll(methods);
            return combined;
        }
    }

    private record Catalog(String catalogClass, JavaType identity, String value) {
    }

    private record ResolveMove(String method, List<String> unwrap) {
    }

    private record MethodRef(String name, List<String> unwrap) {
    }

    private record TypeMove(Map<String, Object> encoded, Catalog catalog) {
    }

    private record Call(String receiver, String method, int arguments) {
    }

    private record Usage(Set<String> fields, Set<String> methods, List<Call> accessors) {
    }

    private static final class UsageMap {
        private final Map<String, Usage> byType = new HashMap<>();
    }

    private record Parsed(Path path, CompilationUnit unit, String original) {
    }

    private record Edit(int depth, Runnable apply) {
    }

    private static final class Resolver {
        private final Map<String, String> imports = new HashMap<>();
        private final List<String> stars = new ArrayList<>();
        private final Map<String, ClassModel> baseline;
        private final Set<String> declared = new HashSet<>();

        private Resolver(CompilationUnit unit, Map<String, ClassModel> baseline) {
            this.baseline = baseline;
            for (var imported : unit.getImports()) {
                if (imported.isAsterisk()) stars.add(imported.getNameAsString());
                else imports.put(imported.getName().getIdentifier(), imported.getNameAsString());
            }
            unit.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> declared.add(type.getNameAsString()));
        }

        private String typeName(com.github.javaparser.ast.type.Type type) {
            if (!(type instanceof ClassOrInterfaceType declaredType)) return null;
            String scoped = declaredType.getNameWithScope();
            if (scoped.contains(".")) {
                int first = scoped.indexOf('.');
                String simple = scoped.substring(0, first);
                String imported = imports.get(simple);
                if (imported != null) return imported + scoped.substring(first);
                return scoped;
            }
            return simple(scoped);
        }

        private String simple(String name) {
            if (name == null || declared.contains(name)) return null;
            if (imports.containsKey(name)) return imports.get(name);
            var found = new ArrayList<String>();
            for (String star : stars) {
                String candidate = star + "." + name;
                if (baseline.containsKey(candidate) || lookup(baseline, candidate) != null) found.add(candidate);
            }
            if (found.size() > 1) return fail(name, "ambiguous import");
            return found.isEmpty() ? null : found.getFirst();
        }
    }
}
