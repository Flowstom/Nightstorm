package net.flowstom.nightstorm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EnumDataAccessTest {
    @TempDir Path root;

    @Test
    void discoversMovedMaskedScalarsAndObjectMembersThenFollowsChangedTargetValues() throws Exception {
        final Path state = root.resolve("state.json");
        final Path helper = compileHelper();
        assertEquals(0, run(helper, fixture("baseline", true, 100, false), state).code());
        final var relocated = run(helper, fixture("relocated", false, 100, false), state);
        assertEquals(0, relocated.code(), relocated.output());
        assertTrue(relocated.output().contains("learned data source"));
        assertTrue(Files.readString(state).contains("Fixture$Sources"));
        final var changed = run(helper, fixture("changed", false, 321, false), state);
        assertEquals(0, changed.code(), changed.output());
        assertTrue(changed.output().contains("LIGHT_BLUE=421"), changed.output());
        assertTrue(changed.output().contains("LIGHT_BLUE map=1421"), changed.output());
    }

    @Test
    void refusesAmbiguousOrMissingBaselineMatches() throws Exception {
        final Path helper = compileHelper();
        final Path state = root.resolve("state.json");
        assertEquals(0, run(helper, fixture("baseline", true, 100, false), state).code());
        final var ambiguous = run(helper, fixture("ambiguous", false, 100, true), state);
        assertNotEquals(0, ambiguous.code());
        assertTrue(ambiguous.output().contains("found 2"), ambiguous.output());
        final var missing = run(helper, fixture("missing", false, 100, false), root.resolve("absent.json"));
        assertNotEquals(0, missing.code());
        assertTrue(missing.output().contains("No baseline values"), missing.output());
        final var unmatched = run(helper, fixture("unmatched", false, 999, false), state);
        assertNotEquals(0, unmatched.code());
        assertTrue(unmatched.output().contains("found 0"), unmatched.output());
    }

    @Test
    void wrapsEnumGettersWithoutDependingOnTheirTypeOrVariableNames() throws Exception {
        final Path generator = root.resolve("DataGenerator/src/main/java/net/minestom/generators/Example.java");
        Files.createDirectories(generator.getParent());
        Files.writeString(generator, """
                for (SomeEnum entry : SomeEnum.values()) {
                    json.addProperty("name", entry.name());
                    json.addProperty("value", entry.getValue() & 0xFFFFFF);
                    json.addProperty("index", entry.getMap().id);
                    json.addProperty("other", unrelated.getValue());
                }
                """);
        final var process = new ProcessBuilder("python3", "scripts/install-enum-data-access.py", root.toString())
                .redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        final String transformed = Files.readString(generator);
        assertTrue(transformed.contains("EnumDataAccess.read(entry, \"name\", null, null)"));
        assertTrue(transformed.contains("EnumDataAccess.read(entry, \"getValue\", null, 0xFFFFFF)"));
        assertTrue(transformed.contains("EnumDataAccess.read(entry, \"getMap\", \"id\", null)"));
        assertTrue(transformed.contains("json.addProperty(\"other\", unrelated.getValue())"));
    }

    private Path compileHelper() throws Exception {
        final Path classes = root.resolve("helper");
        Files.createDirectories(classes);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", dependencies(), "-d",
                classes.toString(), "templates/data-generator/EnumDataAccess.java"));
        return classes;
    }

    private Path fixture(String name, boolean getter, int first, boolean duplicate) throws Exception {
        final Path directory = root.resolve(name);
        Files.createDirectories(directory);
        final Path source = directory.resolve("Fixture.java");
        Files.writeString(source, """
                import net.minestom.generators.EnumDataAccess;
                public class Fixture {
                    public enum Palette {
                        RED, LIGHT_BLUE;
                        %s
                    }
                    public record Slots<T>(T red, T lightBlue) {}
                    public static final class Id {
                        public final int id;
                        public Id(int id) { this.id = id; }
                    }
                    public static final class Sources {
                        public static final Slots<String> NAMES = new Slots<>("red", "light_blue");
                        public static final Slots<Integer> TINTS = new Slots<>(0xFF000000 | %d, 0xFF000000 | %d);
                        public static final Slots<Integer> ALIAS = TINTS;
                        public static final Slots<Id> MAPS = new Slots<>(new Id(%d), new Id(%d));
                        %s
                    }
                    public static void main(String[] args) throws Exception {
                        for (Palette p : Palette.values()) {
                            System.out.println(p + "=" + EnumDataAccess.read(p, "getValue", null, 0xFFFFFF));
                            System.out.println(p + " map=" + EnumDataAccess.read(p, "getMap", "id", null));
                        }
                        EnumDataAccess.save();
                    }
                }
                """.formatted(getter ? "public int getValue() { return 0xFF000000 | (100 + ordinal() * 100); }"
                        + " public Id getMap() { return new Id(1100 + ordinal() * 100); }" : "",
                first, first + 100, first + 1000, first + 1100,
                duplicate ? "public static final Slots<Integer> OTHER = new Slots<>(0xFF000000 | 100, 0xFF000000 | 200);" : ""));
        final Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp",
                root.resolve("helper") + java.io.File.pathSeparator + dependencies(), "-d", classes.toString(), source.toString()));
        final Path jar = directory.resolve("fixture.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(path).toString()));
                out.write(Files.readAllBytes(path));
                out.closeEntry();
            }
        }
        return jar;
    }

    private record Result(int code, String output) {}

    private Result run(Path helper, Path fixture, Path state) throws Exception {
        final String classpath = String.join(java.io.File.pathSeparator, fixture.toString(), helper.toString(), dependencies());
        final var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", classpath, "Fixture").redirectErrorStream(true);
        builder.environment().put("NIGHTSTORM_ENUM_DATA_STATE", state.toString());
        final var process = builder.start();
        final String output = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), output);
    }

    private static String dependencies() throws Exception {
        return String.join(java.io.File.pathSeparator,
                Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(ClassReader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(ClassNode.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    }
}
