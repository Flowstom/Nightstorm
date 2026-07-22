package net.flowstom.nightstorm;

import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        final var command = args[0];
        final var options = Arguments.parse(Arrays.copyOfRange(args, 1, args.length));
        switch (command) {
            case "plan" -> plan(options);
            case "scan-packets" -> scanPackets(options);
            case "update-packets" -> updatePackets(options);
            case "update-source-width" -> updateSourceWidth(options);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void plan(Arguments options) throws Exception {
        final Path configPath = options.path("config", Path.of(".nightstorm/config.json"));
        final Path outputPath = options.requiredPath("output");
        final var config = Json.read(configPath, NightstormConfig.class);
        final var github = new GitHubClient(config.githubRepository(), options.optional("github-token"));
        final var plan = new UpdatePlanner(new MojangClient(), github).plan(config);
        Json.write(outputPath, plan);
        System.out.printf("Planned %s from %s (%s)%n", plan.branch(), plan.baseRef(), plan.reason());
    }

    private static void scanPackets(Arguments options) throws Exception {
        final Path jar = options.requiredPath("jar");
        final Path outputPath = options.requiredPath("output");
        final var report = PacketScanner.scan(jar);
        Json.write(outputPath, report);
        System.out.printf("Found %d packet candidates in %s%n", report.packets().size(), jar);
    }

    private static void updatePackets(Arguments options) throws Exception {
        final Path baselineJar = options.requiredPath("baseline-jar");
        final Path targetJar = options.requiredPath("jar");
        final Path source = options.requiredPath("source");
        final Path outputPath = options.requiredPath("output");
        final var baseline = PacketScanner.scan(baselineJar);
        final var target = PacketScanner.scan(targetJar);
        final var result = PacketUpdater.update(source, baseline, target, baselineJar, targetJar, outputPath);
        System.out.printf("Updated %d packet registrations and generated %d packet classes in %s%n",
                result.packets(), result.generatedPackets(), source);
        if (!result.warnings().isEmpty()) {
            System.out.printf("Generated %d packet compatibility warnings%n", result.warnings().size());
        }
    }

    private static void updateSourceWidth(Arguments options) throws Exception {
        final var result = SourceWidthUpdater.update(
                options.requiredPath("source"),
                options.requiredPath("archive"),
                options.required("resource"),
                options.required("value-path"),
                options.requiredPath("file"),
                options.required("constant"));
        System.out.printf("Updated %s to %d for maximum value %d%n",
                options.required("constant"), result.width(), result.maximumValue());
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  nightstorm plan --output <plan.json> [--config <config.json>] [--github-token <token>]");
        System.err.println("  nightstorm scan-packets --jar <minecraft-server.jar> --output <packet-schema.json>");
        System.err.println("  nightstorm update-packets --baseline-jar <server.jar> --jar <server.jar> --source <minestom> --output <packet-schema.json>");
        System.err.println("  nightstorm update-source-width --source <root> --archive <data.jar> --resource <file.json> --value-path <path> --file <source.java> --constant <name>");
    }
}
