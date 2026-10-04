package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScalarDataAccessTest {
    @TempDir Path root;
    @Test void learnsAnUnrelatedGetterAndReadsChangedValues() throws Exception {
        Path helper = helper(), state = root.resolve("profile.gz");
        assertEquals(0, run(helper, fixture("before", true, false, false), state).code());
        var renamed = run(helper, fixture("renamed", false, false, false), state);
        assertEquals(0, renamed.code(), renamed.output());
        assertTrue(renamed.output().contains("learned scalar getter"));
        var changed = run(helper, fixture("changed", false, true, false), state);
        assertEquals(0, changed.code(), changed.output());
        assertTrue(changed.output().contains("a=false"), changed.output());
    }
    @Test void rejectsIndependentCandidatesWithIdenticalProfiles() throws Exception {
        Path helper = helper(), state = root.resolve("profile.gz");
        assertEquals(0, run(helper, fixture("before", true, false, false), state).code());
        var ambiguous = run(helper, fixture("ambiguous", false, false, true), state);
        assertNotEquals(0, ambiguous.code());
        assertTrue(ambiguous.output().contains("found 2"), ambiguous.output());
    }
    @Test void retainsAccessorBranchesAndReadsCurrentValues() throws Exception {
        Path helper=helper(), state=root.resolve("program.gz");
        Path before=fixture("program-before",true,false,false);
        Path source=before.resolve("Fixture.java");
        Files.writeString(source,Files.readString(source).replace("public boolean oldGetter() { return (value ? 1 : 0) + 0 == 1; }",
                "public boolean oldGetter() { if (id == null) return false; return value; }"));
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-cp",helper+File.pathSeparator+dependencies(),"-d",before.toString(),source.toString()));
        var baseline=run(helper,before,state);assertEquals(0,baseline.code(),baseline.output());
        var current=run(helper,fixture("program-after",false,true,true),state);
        assertEquals(0,current.code(),current.output());
        assertTrue(current.output().contains("retained scalar accessor program"),current.output());
        assertTrue(current.output().contains("a=false"),current.output());
        var unsupported = run(helper, fixture("program-unportable", true, false, false), state);
        assertEquals(0, unsupported.code(), unsupported.output());
        var stale = run(helper, fixture("program-removed", false, false, true), state);
        assertNotEquals(0, stale.code());
        assertTrue(stale.output().contains("found 2"), stale.output());
    }

    private Path helper() throws Exception {
        Path classes = root.resolve("helper"); Files.createDirectories(classes);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", dependencies(), "-d", classes.toString(),
                "templates/data-generator/ScalarDataAccess.java", "templates/data-generator/ScalarProgram.java"));
        return classes;
    }
    private Path fixture(String name, boolean getter, boolean changed, boolean ambiguous) throws Exception {
        Path dir = root.resolve(name); Files.createDirectories(dir);
        Path source = dir.resolve("Fixture.java");
        Files.writeString(source, """
                import java.util.*;
                import net.minestom.generators.ScalarDataAccess;
                public class Fixture {
                    public static class Value {
                        private final String id; private final boolean value; private final boolean other;
                        Value(String id, boolean value) { this.id=id; this.value=value; this.other=value; }
                        public String toString() { return id; }
                        %s
                        public boolean relocated() { return value; }
                        public boolean alias() { return value; }
                        %s
                    }
                    public static void main(String[] args) throws Exception {
                        var values = List.of(new Value("a", %s), new Value("b", false));
                        ScalarDataAccess.domain(Value.class, () -> values);
                        for (var value : values) System.out.println(value + "=" + ScalarDataAccess.read(value, "oldGetter", boolean.class));
                        ScalarDataAccess.save();
                    }
                }
                """.formatted(getter ? "public boolean oldGetter() { return (value ? 1 : 0) + 0 == 1; }" : "",
                        ambiguous ? "public boolean independent() { return other; }" : "", changed ? "false" : "true"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", root.resolve("helper") + File.pathSeparator + dependencies(),
                "-d", dir.toString(), source.toString()));
        return dir;
    }
    private Result run(Path helper, Path fixture, Path state) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dnightstorm.scalar.state=" + state, "-cp", helper + File.pathSeparator + fixture + File.pathSeparator + dependencies(), "Fixture")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        return new Result(process.waitFor(), output);
    }
    private String dependencies() throws Exception {
        var paths = new ArrayList<String>();
        for (Class<?> type : List.of(com.google.gson.Gson.class, org.objectweb.asm.ClassReader.class, org.objectweb.asm.tree.ClassNode.class))
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(File.pathSeparator, paths);
    }
    record Result(int code, String output) { }
}
