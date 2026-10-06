package net.flowstom.nightstorm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorApiRelocationTest {
    @TempDir Path root;

    @Test
    void relocatesConstantCatalogWhenInstancesBecomeRegistryKeys() throws Exception {
        var baseline = jar("baseline.jar", baselineSources());
        var target = jar("target.jar", targetSources(true));
        var generator = sources();
        var proof = root.resolve("api-relocation.json");
        var relocated = GeneratorApiRelocation.relocate(generator, baseline, target, proof);
        assertEquals(List.of("sample.Tone"), relocated);
        String catalog = Files.readString(generator.resolve("src/Catalog.java"));
        assertTrue(catalog.contains("ApiRelocation.catalogFields(\"sample.Tone\")"), catalog);
        assertTrue(catalog.contains("ApiRelocation.asFloat(\"sample.Tone\""), catalog);
        assertTrue(catalog.contains("((sample.Note) ApiRelocation.read(\"sample.Tone\""), catalog);
        assertTrue(catalog.contains("ApiRelocation.read(\"sample.Tone\", box, \"getTone\")"), catalog);
        assertTrue(catalog.contains("java.util.Objects.equals"), catalog);
        assertFalse(catalog.contains("Tone.class"));
        assertFalse(catalog.contains("(Tone)"));
        String once = catalog;
        assertEquals(List.of(), GeneratorApiRelocation.relocate(generator, baseline, target, proof));
        assertEquals(once, Files.readString(generator.resolve("src/Catalog.java")));

        var classes = root.resolve("runtime");
        compile(generator, classes, List.of(target, gson()));
        System.setProperty("nightstorm.api.relocation", proof.toString());
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL(), target.toUri().toURL(), gson().toUri().toURL()})) {
            Object box = loader.loadClass("sample.Box").getConstructor(loader.loadClass("sample.Key"))
                    .newInstance(loader.loadClass("sample.Tones").getField("WOOD").get(null));
            String described = (String) loader.loadClass("net.minestom.generators.Catalog").getMethod("describe", loader.loadClass("sample.Box")).invoke(null, box);
            assertTrue(described.contains("wood=1.0,minecraft:wood_break"), described);
            assertTrue(described.contains("stone=1.0,minecraft:stone_break"), described);
            assertTrue(described.endsWith("match=wood"), described);
        } finally {
            System.clearProperty("nightstorm.api.relocation");
        }
    }

    @Test
    void rejectsAnAmbiguousConstantCatalog() throws Exception {
        var baseline = jar("baseline.jar", baselineSources());
        var sources = targetSources(true);
        sources.add(source("sample/Decoy.java", """
                package sample;
                public final class Decoy {
                    public static final Key<ToneValue> WOOD = Tones.WOOD;
                    public static final Key<ToneValue> STONE = Tones.STONE;
                }
                """));
        var target = jar("target.jar", sources);
        var generator = sources();
        String original = Files.readString(generator.resolve("src/Catalog.java"));
        var failure = assertThrows(IllegalStateException.class, () -> GeneratorApiRelocation.relocate(
                generator, baseline, target, root.resolve("proof.json")));
        assertTrue(failure.getMessage().contains("no unique constant catalog"), failure.getMessage());
        assertEquals(original, Files.readString(generator.resolve("src/Catalog.java")));
    }

    @Test
    void rejectsAMemberThatDoesNotLineUp() throws Exception {
        var baseline = jar("baseline.jar", baselineSources());
        var broken = targetSources(false);
        var target = jar("target.jar", broken);
        var generator = sources();
        String original = Files.readString(generator.resolve("src/Catalog.java"));
        var failure = assertThrows(IllegalStateException.class, () -> GeneratorApiRelocation.relocate(
                generator, baseline, target, root.resolve("proof.json")));
        assertTrue(failure.getMessage().contains("getBreakSound"), failure.getMessage());
        assertEquals(original, Files.readString(generator.resolve("src/Catalog.java")));
    }

    @Test
    void leavesSourceUnchangedWhenTheTypeStillExists() throws Exception {
        var sources = baselineSources();
        var baseline = jar("baseline.jar", sources);
        var target = jar("target.jar", sources);
        var generator = sources();
        String original = Files.readString(generator.resolve("src/Catalog.java"));
        assertEquals(List.of(), GeneratorApiRelocation.relocate(generator, baseline, target, root.resolve("proof.json")));
        assertEquals(original, Files.readString(generator.resolve("src/Catalog.java")));
    }

    private Path sources() throws IOException {
        var generator = root.resolve("generator-" + System.nanoTime());
        write(generator.resolve("src/Catalog.java"), """
                package net.minestom.generators;
                import sample.*;
                import java.lang.reflect.Modifier;
                public class Catalog {
                    public static String describe(Box box) throws Exception {
                        var tones = new java.util.LinkedHashMap<String, Tone>();
                        var text = new StringBuilder();
                        for (var field : Tone.class.getDeclaredFields()) {
                            if ((field.getModifiers() & Modifier.STATIC) == 0) continue;
                            Tone tone = (Tone) field.get(null);
                            String name = field.getName().toLowerCase();
                            tones.put(name, tone);
                            text.append(name).append('=').append(tone.volume).append(',').append(tone.getBreakSound().location()).append(';');
                        }
                        Tone selected = box.getTone();
                        for (var entry : tones.entrySet()) {
                            if (selected.equals(entry.getValue())) return text + "match=" + entry.getKey();
                        }
                        return text + "match=";
                    }
                }
                """);
        write(generator.resolve("src/MinecraftCompatibility.java"), """
                package net.minestom.generators;
                import sample.Provider;
                public final class MinecraftCompatibility {
                    public static Provider vanillaLookup() { return new Provider(); }
                }
                """);
        Files.copy(Path.of("templates/data-generator/ApiRelocation.java"), generator.resolve("src/ApiRelocation.java"));
        return generator;
    }

    private static List<Source> baselineSources() {
        var sources = sharedTypes();
        sources.add(source("sample/Tone.java", """
                package sample;
                public class Tone {
                    public static final Tone WOOD = new Tone(1.0f, new Note(new Id("minecraft:wood_break")));
                    public static final Tone STONE = new Tone(1.0f, new Note(new Id("minecraft:stone_break")));
                    public final float volume;
                    private final Note breakSound;
                    public Tone(float volume, Note breakSound) {
                        this.volume = volume;
                        this.breakSound = breakSound;
                    }
                    public Note getBreakSound() { return breakSound; }
                }
                """));
        sources.add(source("sample/Box.java", """
                package sample;
                public final class Box {
                    private final Tone tone;
                    public Box(Tone tone) { this.tone = tone; }
                    public Tone getTone() { return tone; }
                }
                """));
        return sources;
    }

    private static List<Source> targetSources(boolean aligned) {
        var sources = sharedTypes();
        sources.add(source("sample/ToneValue.java", """
                package sample;
                import java.util.Optional;
                public final class ToneValue {
                    private final float volume;
                    private final Optional<Holder<Note>> breakSound;
                    public ToneValue(float volume, Optional<Holder<Note>> breakSound) {
                        this.volume = volume;
                        this.breakSound = breakSound;
                    }
                    public float volume() { return volume; }
                    public Optional<Holder<Note>> %s() { return breakSound; }
                }
                """.formatted(aligned ? "breakSound" : "smash")));
        sources.add(source("sample/Tones.java", """
                package sample;
                import java.util.Map;
                import java.util.Optional;
                public final class Tones {
                    public static final Key<ToneValue> WOOD = new Key<>("WOOD");
                    public static final Key<ToneValue> STONE = new Key<>("STONE");
                    public static final Map<String, ToneValue> VALUES = Map.of(
                            "WOOD", new ToneValue(1.0f, Optional.of(new Holder<>(new Note(new Id("minecraft:wood_break"))))),
                            "STONE", new ToneValue(1.0f, Optional.of(new Holder<>(new Note(new Id("minecraft:stone_break"))))));
                }
                """));
        sources.add(source("sample/Box.java", """
                package sample;
                import java.util.Optional;
                public final class Box {
                    private final Key<ToneValue> tone;
                    public Box(Key<ToneValue> tone) { this.tone = tone; }
                    public Optional<Key<ToneValue>> getTones() { return Optional.ofNullable(tone); }
                }
                """));
        sources.add(source("sample/Key.java", """
                package sample;
                public final class Key<T> {
                    public final String name;
                    public Key(String name) { this.name = name; }
                    public boolean equals(Object other) { return other instanceof Key<?> key && name.equals(key.name); }
                    public int hashCode() { return name.hashCode(); }
                }
                """));
        sources.add(source("sample/Holder.java", """
                package sample;
                public final class Holder<T> {
                    private final T value;
                    public Holder(T value) { this.value = value; }
                    public T value() { return value; }
                }
                """));
        sources.add(source("sample/Provider.java", """
                package sample;
                public final class Provider {
                    public <T> Holder<T> getOrThrow(Key<T> key) {
                        @SuppressWarnings("unchecked")
                        T value = (T) Tones.VALUES.get(key.name);
                        if (value == null) throw new IllegalStateException(key.name);
                        return new Holder<>(value);
                    }
                }
                """));
        return sources;
    }

    private static List<Source> sharedTypes() {
        var sources = new ArrayList<Source>();
        sources.add(source("sample/Id.java", """
                package sample;
                public final class Id {
                    private final String text;
                    public Id(String text) { this.text = text; }
                    public String toString() { return text; }
                }
                """));
        sources.add(source("sample/Note.java", """
                package sample;
                public final class Note {
                    private final Id id;
                    public Note(Id id) { this.id = id; }
                    public Id location() { return id; }
                }
                """));
        return sources;
    }

    private Path jar(String name, List<Source> sources) throws Exception {
        var java = root.resolve(name + "-src");
        var classes = root.resolve(name + "-classes");
        for (Source source : sources) write(java.resolve(source.path()), source.code());
        compile(java, classes, List.of());
        var jar = root.resolve(name);
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    private static void compile(Path source, Path classes, List<Path> classpath) throws IOException {
        Files.createDirectories(classes);
        var arguments = new ArrayList<String>();
        arguments.add("-d");
        arguments.add(classes.toString());
        if (!classpath.isEmpty()) {
            arguments.add("-cp");
            arguments.add(classpath.stream().map(Path::toString).reduce((left, right) -> left + java.io.File.pathSeparator + right).orElse(""));
        }
        try (var files = Files.walk(source)) {
            arguments.addAll(files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)), arguments.toString());
    }

    private static Path gson() throws Exception {
        return Path.of(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Source source(String path, String code) {
        return new Source(path, code);
    }

    private static void write(Path path, String code) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, code);
    }

    private record Source(String path, String code) {
    }
}
