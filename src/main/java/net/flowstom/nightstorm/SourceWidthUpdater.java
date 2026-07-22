package net.flowstom.nightstorm;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

final class SourceWidthUpdater {
    private SourceWidthUpdater() {
    }

    static Result update(Path sourceRoot, Path archive, String resource, String valuePath,
                         Path sourceFile, String constantName) throws IOException {
        final int maximum = maximumValue(archive, resource, valuePath);
        if (maximum < 1) throw new IllegalStateException("Width values must contain a positive integer");
        final int width = Integer.SIZE - Integer.numberOfLeadingZeros(maximum);
        final Path relativeSource = sourceFile.normalize();
        if (relativeSource.isAbsolute() || !relativeSource.startsWith("src/main/java")) {
            throw new IllegalArgumentException("Source file must be relative to src/main/java: " + sourceFile);
        }
        SourceTransaction.run(sourceRoot, null, (stagedRoot, ignored) -> {
            replaceConstant(stagedRoot.resolve(relativeSource), constantName, width);
            return null;
        });
        return new Result(maximum, width);
    }

    private static int maximumValue(Path archive, String resource, String valuePath) throws IOException {
        final JsonNode root;
        try (var zip = new ZipFile(archive.toFile())) {
            final var entry = zip.getEntry(resource);
            if (entry == null) throw new IllegalStateException("Archive resource not found: " + resource);
            try (var input = zip.getInputStream(entry)) {
                root = Json.MAPPER.readTree(input);
            }
        }
        final String[] segments = List.of(valuePath.split("/", -1)).stream()
                .filter(segment -> !segment.isEmpty()).toArray(String[]::new);
        if (segments.length == 0) throw new IllegalArgumentException("Value path must not be empty");
        final List<JsonNode> values = new ArrayList<>();
        collect(root, segments, 0, values);
        if (values.isEmpty()) throw new IllegalStateException("No values matched archive path: " + valuePath);
        int maximum = Integer.MIN_VALUE;
        for (JsonNode value : values) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new IllegalStateException("Width value is not a 32-bit integer at path: " + valuePath);
            }
            maximum = Math.max(maximum, value.intValue());
        }
        return maximum;
    }

    private static void collect(JsonNode node, String[] segments, int index, List<JsonNode> output) {
        if (index == segments.length) {
            output.add(node);
            return;
        }
        final String segment = segments[index];
        if (segment.equals("*")) {
            node.elements().forEachRemaining(child -> collect(child, segments, index + 1, output));
            return;
        }
        final JsonNode child = node.get(segment);
        if (child != null) collect(child, segments, index + 1, output);
    }

    private static void replaceConstant(Path source, String constantName, int width) throws IOException {
        if (!Files.isRegularFile(source)) throw new IllegalStateException("Source file not found: " + source);
        final var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        final var result = parser.parse(source);
        final var unit = result.getResult().orElseThrow(() ->
                new IllegalStateException("Unable to parse source " + source + ": " + result.getProblems()));
        LexicalPreservingPrinter.setup(unit);
        final List<VariableDeclarator> matches = unit.findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getNameAsString().equals(constantName)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected one constant named " + constantName + " in " + source
                    + ", found " + matches.size());
        }
        final VariableDeclarator variable = matches.getFirst();
        if (!variable.getType().isPrimitiveType()
                || variable.getType().asPrimitiveType().getType()
                != com.github.javaparser.ast.type.PrimitiveType.Primitive.INT
                || variable.getInitializer().filter(IntegerLiteralExpr.class::isInstance).isEmpty()) {
            throw new IllegalStateException("Expected integer literal constant " + constantName + " in " + source);
        }
        variable.setInitializer(new IntegerLiteralExpr(Integer.toString(width)));
        Files.writeString(source, LexicalPreservingPrinter.print(unit));
    }

    record Result(int maximumValue, int width) {
    }
}
