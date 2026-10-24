package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationInstallerTest {
    @Test
    void installsWithRenamedLocalsDifferentLayoutAndFormattingAndIsIdempotent() throws Exception {
        var root = Files.createTempDirectory("nightstorm-integration");
        var generator = root.resolve("generator");
        var source = root.resolve("source");
        var main = write(generator.resolve("custom/net/minestom/datagen/Runner.java"), """
                package net.minestom.datagen;
                import java.nio.file.Path;
                public class DataGen {
                    private static Path destination = Path.of("output");
                    public static void main(String[] argv) { System.out.println("Different completion message"); }
                }
                """);
        var registry = write(source.resolve("unusual/Registry.java"), """
                package net.minestom.server.registry;
                class RegistriesImpl {
                    Object packets(Object lookup, boolean skip) {
                        var outgoing = new java.util.ArrayList<>();
                        for (var entry : synchronizedValues(lookup)) outgoing.add(entry.registryDataPacket(lookup, skip));
                        return outgoing;
                    }
                    Object tags(Object lookup) {
                        var outgoing = new java.util.ArrayList<>();
                        for (var entry : taggedValues(lookup)) outgoing.add(entry.tagRegistry());
                        return new TagsPacket(outgoing);
                    }
                }
                """);
        var enumSource = write(generator.resolve("custom/net/minestom/generators/Random.java"), """
                package net.minestom.generators;
                class Random {
                    void generate() {
                        for (Shape shape : Shape.values()) {
                            json.addProperty(
                                "tint", shape.presentation().rgb & 0xFFFFFF
                            );
                        }
                    }
                }
                """);
        var scalarSource = write(generator.resolve("custom/net/minestom/generators/Values.java"), """
                package net.minestom.generators;
                class Values {
                    void generate() {
                        var registry = Registry.VALUES;
                        for (var owner : registry) {
                            for (Unrelated value : owner.entries()) consume(value);
                        }
                    }
                    void consume(Unrelated state) { append(json, "enabled", state.enabled(), boolean.class); }
                }
                """);
        var untouched = write(source.resolve("Other.java"), "class Other {   int unchanged; }");
        var keys = write(source.resolve("Keys.java"), """
                package sample;
                class Keys {
                    static @Nullable String fromName(String key) {
                        return fromName(Key.key(key));
                    }
                    static String required(String key) {
                        return required(Key.key(key));
                    }
                }
                """);
        IntegrationInstaller.install(generator, source, Path.of("templates"));
        var onceMain = Files.readString(main);
        var onceRegistry = Files.readString(registry);
        assertTrue(onceMain.contains("normalizeFromEnvironment(destination)"));
        assertTrue(onceRegistry.contains("appendPackets(outgoing, synchronizedValues(lookup), skip)"));
        assertTrue(onceRegistry.contains("appendTags(outgoing, taggedValues(lookup))"));
        assertTrue(Files.readString(enumSource).contains("EnumDataAccess.read(shape, \"presentation\", \"rgb\", 0xFFFFFF)"));
        assertTrue(Files.readString(scalarSource).contains("ScalarDataAccess.read(state, \"enabled\", boolean.class)"), Files.readString(scalarSource));
        assertTrue(Files.readString(scalarSource).contains("ScalarDataAccess.domain(Unrelated.class"));
        assertEquals("class Other {   int unchanged; }", Files.readString(untouched));
        String keysOnce = Files.readString(keys);
        assertTrue(keysOnce.contains("key == null") && keysOnce.contains("return null"), keysOnce);
        assertTrue(keysOnce.contains("return required(Key.key(key))"), keysOnce);
        IntegrationInstaller.install(generator, source, Path.of("templates"));
        assertEquals(onceMain, Files.readString(main));
        assertEquals(onceRegistry, Files.readString(registry));
        assertEquals(keysOnce, Files.readString(keys));
    }

    private static Path write(Path path, String source) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
        return path;
    }
}
