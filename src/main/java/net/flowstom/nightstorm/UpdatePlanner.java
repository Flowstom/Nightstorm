package net.flowstom.nightstorm;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

final class UpdatePlanner {
    private static final DateTimeFormatter RELEASE_DATE = DateTimeFormatter.ofPattern("uuuu.MM.dd").withZone(ZoneOffset.UTC);

    private final MojangClient mojang;
    private final GitHubClient github;
    UpdatePlanner(MojangClient mojang, GitHubClient github) {
        this.mojang = mojang;
        this.github = github;
    }

    UpdatePlan plan(NightstormConfig config) throws Exception {
        final var minecraft = mojang.latest(config.minecraftChannel());
        final var minestom = github.latestRelease(config.minestomRepository());
        final String stream = config.branchPrefix() + "/" + sanitize(minestom.tag()) + "/";
        final String branch = stream + sanitize(minecraft.id());
        final var knownVersions = mojang.versions(config.minecraftChannel());
        final String parent = github.branches(stream).stream()
                .map(candidate -> new BranchVersion(candidate, minecraftVersion(candidate, stream, knownVersions)))
                .filter(candidate -> candidate.version() != null)
                .filter(candidate -> candidate.version().releaseTime().isBefore(minecraft.releaseTime()))
                .max(Comparator.comparing(candidate -> candidate.version().releaseTime()))
                .map(BranchVersion::branch)
                .orElse(minestom.tag());
        final String reason = parent.equals(minestom.tag()) ? "new-minestom-stream" : "minecraft-update";
        final String releaseDate = RELEASE_DATE.format(minestom.publishedAt());
        return new UpdatePlan(
                minecraft.id(),
                minecraft.type(),
                minecraft.metadataUrl().toString(),
                config.minestomRepository(),
                minestom.tag(),
                releaseDate,
                config.minestomDataGeneratorRepository(),
                config.minestomDataGeneratorCommit(),
                branch,
                parent,
                reason,
                releaseDate + "-" + minecraft.id()
        );
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static MojangClient.MinecraftVersion minecraftVersion(String branch, String stream,
                                                                    java.util.List<MojangClient.MinecraftVersion> versions) {
        final String id = branch.substring(stream.length());
        return versions.stream().filter(version -> sanitize(version.id()).equals(id)).findFirst().orElse(null);
    }

    private record BranchVersion(String branch, MojangClient.MinecraftVersion version) {
    }
}
