package net.flowstom.nightstorm;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SourceTransaction {
    private SourceTransaction() {
    }

    static <T> T run(Path sourceRoot, Path outputPath, Operation<T> operation) throws IOException {
        return run(sourceRoot, outputPath, operation, (target, replacementCount) -> {
        });
    }

    static <T> T run(Path sourceRoot, Path outputPath, Operation<T> operation,
                     AfterReplace afterReplace) throws IOException {
        final Path root = sourceRoot.toAbsolutePath().normalize();
        final Path parent = root.getParent();
        if (parent == null) throw new IllegalArgumentException("Source root has no parent: " + sourceRoot);
        final Path staging = Files.createTempDirectory(parent, ".nightstorm-update-");
        final Path stagedOutput = outputPath == null ? null : stagedOutput(root, staging, outputPath);
        try {
            copyTree(root.resolve("src/main/java"), staging.resolve("src/main/java"));
            copyIfPresent(root.resolve(".nightstorm/packet-warnings.md"),
                    staging.resolve(".nightstorm/packet-warnings.md"));
            if (outputPath != null) copyIfPresent(outputPath.toAbsolutePath().normalize(), stagedOutput);

            final T result = operation.run(staging, stagedOutput);
            final Map<Path, Path> writes = new LinkedHashMap<>();
            final Set<Path> deletes = new LinkedHashSet<>();
            collectChanges(root.resolve("src/main/java"), staging.resolve("src/main/java"), writes, deletes);
            collectFileChange(root.resolve(".nightstorm/packet-warnings.md"),
                    staging.resolve(".nightstorm/packet-warnings.md"), writes, deletes);
            if (outputPath != null) {
                collectFileChange(outputPath.toAbsolutePath().normalize(), stagedOutput, writes, deletes);
            }
            commit(writes, deletes, afterReplace);
            return result;
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException ignored) {
                // Staging cleanup must not turn a successfully committed update into a reported failure.
            }
        }
    }

    private static Path stagedOutput(Path root, Path staging, Path outputPath) {
        final Path output = outputPath.toAbsolutePath().normalize();
        return output.startsWith(root)
                ? staging.resolve(root.relativize(output))
                : staging.resolve(".nightstorm/external-output");
    }

    private static void collectChanges(Path originalRoot, Path stagedRoot, Map<Path, Path> writes,
                                       Set<Path> deletes) throws IOException {
        final Set<Path> relativePaths = new LinkedHashSet<>();
        collectRelativeFiles(originalRoot, relativePaths);
        collectRelativeFiles(stagedRoot, relativePaths);
        for (Path relative : relativePaths) {
            collectFileChange(originalRoot.resolve(relative), stagedRoot.resolve(relative), writes, deletes);
        }
    }

    private static void collectRelativeFiles(Path root, Set<Path> output) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> output.add(root.relativize(path)));
        }
    }

    private static void collectFileChange(Path original, Path staged, Map<Path, Path> writes,
                                          Set<Path> deletes) throws IOException {
        if (Files.exists(staged)) {
            if (!Files.exists(original) || Files.mismatch(original, staged) != -1) writes.put(original, staged);
        } else if (Files.exists(original)) {
            deletes.add(original);
        }
    }

    private static void commit(Map<Path, Path> writes, Set<Path> deletes, AfterReplace afterReplace) throws IOException {
        final Map<Path, byte[]> originals = new LinkedHashMap<>();
        final List<Path> touched = new ArrayList<>();
        for (Path target : writes.keySet()) originals.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
        for (Path target : deletes) originals.putIfAbsent(target, Files.exists(target) ? Files.readAllBytes(target) : null);
        try {
            int replacementCount = 0;
            for (Map.Entry<Path, Path> write : writes.entrySet()) {
                replace(write.getKey(), Files.readAllBytes(write.getValue()));
                touched.add(write.getKey());
                afterReplace.run(write.getKey(), ++replacementCount);
            }
            for (Path target : deletes) {
                Files.deleteIfExists(target);
                touched.add(target);
            }
        } catch (IOException | RuntimeException exception) {
            IOException rollbackFailure = null;
            for (int index = touched.size() - 1; index >= 0; index--) {
                final Path target = touched.get(index);
                try {
                    final byte[] original = originals.get(target);
                    if (original == null) Files.deleteIfExists(target);
                    else replace(target, original);
                } catch (IOException failure) {
                    if (rollbackFailure == null) rollbackFailure = failure;
                    else rollbackFailure.addSuppressed(failure);
                }
            }
            if (rollbackFailure != null) exception.addSuppressed(rollbackFailure);
            throw exception;
        }
    }

    private static void replace(Path target, byte[] content) throws IOException {
        final Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, ".nightstorm-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                final Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyIfPresent(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;
        final Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            final List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            IOException failure = null;
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Path stagedRoot, Path stagedOutput) throws IOException;
    }

    @FunctionalInterface
    interface AfterReplace {
        void run(Path target, int replacementCount) throws IOException;
    }
}
