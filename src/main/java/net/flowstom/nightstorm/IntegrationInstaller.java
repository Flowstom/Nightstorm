package net.flowstom.nightstorm;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Integrates at declared API boundaries, independent of formatting, local names and source layout. */
final class IntegrationInstaller {
    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
    private final Map<Path, CompilationUnit> units = new LinkedHashMap<>();

    static void install(Path generator, Path source, Path templates) throws IOException {
        var installer = new IntegrationInstaller();
        installer.read(generator, null);
        installer.read(source, "RegistriesImpl");
        var dataGen = installer.type("net.minestom.datagen.DataGen");
        var registries = installer.type("net.minestom.server.registry.RegistriesImpl");
        var main = unique(dataGen.getMethodsByName("main"), "generator entry point");
        var output = unique(dataGen.getFields().stream().flatMap(field -> field.getVariables().stream())
                .filter(field -> field.getType().asString().equals("Path") || field.getType().asString().equals("java.nio.file.Path"))
                .toList(), "generator output Path").getNameAsString();
        installer.finish(main, "SynchronizedRegistryGenerator", "generate(\"synchronized_registries\", new SynchronizedRegistryGenerator());");
        installer.finish(main, "EnumDataAccess", "EnumDataAccess.save();");
        installer.finish(main, "NightstormDataNormalizer", "NightstormDataNormalizer.normalizeFromEnvironment(" + output + ");");

        for (var unit : installer.units.values()) {
            if (!installer.file(unit).startsWith(generator.toAbsolutePath().normalize())) continue;
            for (var call : List.copyOf(unit.findAll(MethodCallExpr.class))) {
                if (call.getArguments().isEmpty() && call.getScope().map(Object::toString).orElse("").equals("VanillaRegistries")
                        && List.of("createWorldLookup", "createLookup").contains(call.getNameAsString())
                        && imported(unit, "net.minecraft.data.registries.VanillaRegistries")) {
                    call.replace(installer.expression("MinecraftCompatibility.vanillaLookup()"));
                } else if (List.of("blocksMotion", "isSolid").contains(call.getNameAsString()) && call.getArguments().isEmpty()
                        && call.getScope().isPresent() && unit.getImports().stream()
                        .anyMatch(value -> value.getNameAsString().equals("net.minecraft.world.level.block.state.BlockState"))) {
                    // Match the receiver's declared type, rather than a local variable spelling.
                    String receiver = call.getScope().get().toString();
                    boolean blockState = unit.findAll(com.github.javaparser.ast.body.Parameter.class).stream()
                            .anyMatch(value -> value.getNameAsString().equals(receiver) && value.getType().asString().equals("BlockState"))
                            || unit.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).stream()
                            .anyMatch(value -> value.getNameAsString().equals(receiver) && value.getType().asString().equals("BlockState"));
                    if (blockState) call.replace(installer.expression("MinecraftCompatibility.blocksMotion(" + receiver + ")"));
                }
            }
            wrapEnumAccess(unit);
        }
        installer.appendRegistryHook(registries, "registryDataPacket", "appendPackets", true);
        installer.appendRegistryHook(registries, "tagRegistry", "appendTags", false);
        var dataShapes = DataShapeScanner.scan(source);

        // Resolve every integration before writing anything.
        for (var entry : installer.units.entrySet()) {
            String text = LexicalPreservingPrinter.print(entry.getValue());
            if (!text.equals(Files.readString(entry.getKey()))) Files.writeString(entry.getKey(), text);
        }
        Path generatorPackage = installer.file(dataGen.findCompilationUnit().orElseThrow()).getParent();
        generatorPackage = generatorPackage.getParent().resolve("generators");
        Files.createDirectories(generatorPackage);
        for (String name : List.of("MinecraftCompatibility", "NightstormDataNormalizer", "EnumDataAccess")) {
            Files.copy(templates.resolve("data-generator/" + name + ".java"), generatorPackage.resolve(name + ".java"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(templates.resolve("registry-sync/SynchronizedRegistryGenerator.java"),
                generatorPackage.resolve("SynchronizedRegistryGenerator.java"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(templates.resolve("registry-sync/NightstormRegistryData.java"),
                installer.file(registries.findCompilationUnit().orElseThrow()).getParent().resolve("NightstormRegistryData.java"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Json.write(source.resolve(".nightstorm/data-shapes.json"), dataShapes);
    }

    private void read(Path root, String requiredType) throws IOException {
        root = root.toAbsolutePath().normalize();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).filter(value -> value.toString().endsWith(".java"))
                    .filter(value -> !value.toString().contains("/build/") && !value.toString().contains("/.git/"))
                    .toList()) {
                if (requiredType != null && !java.util.regex.Pattern.compile("\\bclass\\s+" + java.util.regex.Pattern.quote(requiredType) + "\\b")
                        .matcher(Files.readString(file)).find()) continue;
                var result = parser.parse(file);
                var unit = result.getResult().filter(ignored -> result.isSuccessful())
                        .orElseThrow(() -> new IllegalStateException("Cannot parse integration source " + file + ": " + result.getProblems()));
                LexicalPreservingPrinter.setup(unit);
                units.put(file, unit);
            }
        }
    }

    private com.github.javaparser.ast.body.ClassOrInterfaceDeclaration type(String name) {
        return unique(units.values().stream().flatMap(unit -> unit.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).stream())
                .filter(type -> type.getFullyQualifiedName().orElse("").equals(name)).toList(), name);
    }

    private Path file(CompilationUnit unit) {
        return units.entrySet().stream().filter(entry -> entry.getValue() == unit).findFirst().orElseThrow().getKey();
    }

    private void finish(MethodDeclaration method, String helper, String statement) {
        var unit = method.findCompilationUnit().orElseThrow();
        unit.addImport("net.minestom.generators." + helper);
        if (method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getScope().map(Object::toString).orElse("").equals(helper))) return;
        if (method.findAll(ObjectCreationExpr.class).stream().anyMatch(value -> value.getType().getNameAsString().equals(helper))) return;
        var body = method.getBody().orElseThrow();
        int index = body.getStatements().size();
        if (index > 0 && body.getStatement(index - 1).isReturnStmt()) index--;
        body.getStatements().add(index, parser.parseStatement(statement).getResult().orElseThrow());
    }

    private void appendRegistryHook(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration type,
                                    String operation, String hook, boolean exclude) {
        if (type.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getNameAsString().equals(hook)
                && call.getScope().map(Object::toString).orElse("").equals("NightstormRegistryData"))) return;
        var call = unique(type.findAll(MethodCallExpr.class).stream().filter(value -> value.getNameAsString().equals(operation)).toList(), operation);
        var loop = call.findAncestor(ForEachStmt.class).orElseThrow(() -> new IllegalStateException(operation + " must be in a registry loop"));
        var add = call.findAncestor(MethodCallExpr.class).filter(value -> value.getNameAsString().equals("add") && value.getScope().isPresent())
                .orElseThrow(() -> new IllegalStateException("Cannot establish registry accumulator"));
        var arguments = new NodeList<Expression>(add.getScope().orElseThrow().clone(), loop.getIterable().clone());
        if (exclude) arguments.add(call.getArguments().getLast().orElseThrow().clone());
        var replacement = new MethodCallExpr(new NameExpr("NightstormRegistryData"), hook, arguments);
        var block = loop.getParentNode().filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast).orElseThrow();
        block.getStatements().add(block.getStatements().indexOf(loop) + 1, new ExpressionStmt(replacement));
    }

    static void wrapEnumAccess(CompilationUnit unit) {
        for (var loop : unit.findAll(ForEachStmt.class)) {
            if (!(loop.getIterable() instanceof MethodCallExpr values) || !values.getNameAsString().equals("values")
                    || values.getScope().isEmpty() || loop.getVariable().getVariables().size() != 1) continue;
            var variable = loop.getVariable().getVariable(0);
            if (!values.getScope().get().toString().equals(variable.getType().asString())) continue;
            for (var call : List.copyOf(loop.getBody().findAll(MethodCallExpr.class))) {
                if (!call.getNameAsString().equals("addProperty") || call.getArguments().size() != 2) continue;
                Expression value = call.getArgument(1);
                Expression mask = new NullLiteralExpr(), member = new NullLiteralExpr();
                if (value instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.BINARY_AND
                        && binary.getRight() instanceof IntegerLiteralExpr) {
                    mask = binary.getRight().clone();
                    value = binary.getLeft();
                }
                if (value instanceof FieldAccessExpr field) {
                    member = new StringLiteralExpr(field.getNameAsString());
                    value = field.getScope();
                }
                if (!(value instanceof MethodCallExpr getter) || !getter.getArguments().isEmpty()
                        || getter.getScope().filter(NameExpr.class::isInstance).map(Object::toString)
                        .filter(variable.getNameAsString()::equals).isEmpty()) continue;
                call.setName("add");
                call.setArgument(1, new MethodCallExpr(new NameExpr("EnumDataAccess"), "read", new NodeList<>(
                        new NameExpr(variable.getNameAsString()), new StringLiteralExpr(getter.getNameAsString()), member, mask)));
                unit.addImport("net.minestom.generators.EnumDataAccess");
            }
        }
    }

    private Expression expression(String source) { return parser.parseExpression(source).getResult().orElseThrow(); }
    private static boolean imported(CompilationUnit unit, String name) {
        return unit.getImports().stream().anyMatch(value -> value.getNameAsString().equals(name));
    }
    private static <T> T unique(List<T> values, String role) {
        if (values.size() != 1) throw new IllegalStateException("Expected one " + role + ", found " + values.size());
        return values.getFirst();
    }
}
