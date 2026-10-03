package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DataShapeScannerTest {
    @Test
    void derivesListOrderFromEncoderAndConstructorAndRejectsContradictoryBindings() throws Exception {
        var root = Files.createTempDirectory("nightstorm-shapes");
        Files.writeString(root.resolve("Container.java"), """
                package unrelated;
                import java.util.List;
                import net.minestom.server.codec.Codec;
                record Container(String gamma, String alpha) {
                    static final Codec<Container> DATA = Codec.STRING.list(2).transform(Container::new, Container::ordered);
                    Container(List<String> values) { this(values.get(1), values.get(0)); }
                    List<String> ordered() { return List.of(alpha, gamma); }
                }
                """);
        Files.writeString(root.resolve("Registration.java"), """
                package net.minestom.server.component;
                import unrelated.Container;
                class DataComponents {
                    static final Object ENTRY = register("unrelated_example", null, Container.DATA);
                }
                """);
        var shapes = DataShapeScanner.scan(root);
        assertEquals(List.of("alpha", "gamma"), shapes.lists().get("minecraft:unrelated_example").fields());
        assertEquals("string", shapes.lists().get("minecraft:unrelated_example").scalar());
        var source = root.resolve("Container.java");
        Files.writeString(source, Files.readString(source).replace("this(values.get(1), values.get(0))", "this(values.get(0), values.get(1))"));
        assertTrue(DataShapeScanner.scan(root).lists().isEmpty());
    }
}
