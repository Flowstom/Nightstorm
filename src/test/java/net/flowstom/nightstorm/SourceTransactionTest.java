package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceTransactionTest {
    @Test
    void rollsBackSourceAndOutputAfterPartialCommit() throws Exception {
        final var root = Files.createTempDirectory("nightstorm-transaction");
        final var source = root.resolve("src/main/java/example/Value.java");
        final var output = root.resolve(".nightstorm/packet-schema.json");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(source, "before-source");
        Files.writeString(output, "before-schema");
        final var adapters = root.resolve(".nightstorm/wire-adapters.json");
        Files.writeString(adapters, "before-adapters");
        final var sawCommittedFiles = new boolean[1];

        assertThrows(IOException.class, () -> SourceTransaction.run(root, output, (stagedRoot, stagedOutput) -> {
            Files.writeString(stagedRoot.resolve("src/main/java/example/Value.java"), "after-source");
            Files.writeString(stagedOutput, "after-schema");
            assertEquals("before-adapters", Files.readString(stagedRoot.resolve(".nightstorm/wire-adapters.json")));
            Files.writeString(stagedRoot.resolve(".nightstorm/wire-adapters.json"), "after-adapters");
            return null;
        }, (target, replacementCount) -> {
            if (replacementCount == 3) {
                sawCommittedFiles[0] = Files.readString(source).equals("after-source")
                        && Files.readString(output).equals("after-schema")
                        && Files.readString(adapters).equals("after-adapters");
                throw new IOException("injected failure");
            }
        }));

        assertTrue(sawCommittedFiles[0]);
        assertEquals("before-source", Files.readString(source));
        assertEquals("before-schema", Files.readString(output));
        assertEquals("before-adapters", Files.readString(adapters));
    }
}
