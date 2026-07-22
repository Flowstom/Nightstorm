package net.flowstom.nightstorm;

record UpdatePlan(
        String minecraftVersion,
        String minecraftType,
        String minecraftMetadataUrl,
        String minestomRepository,
        String minestomTag,
        String minestomReleaseDate,
        String minestomDataGeneratorRepository,
        String minestomDataGeneratorCommit,
        String branch,
        String baseRef,
        String reason,
        String artifactVersion
) {
}
