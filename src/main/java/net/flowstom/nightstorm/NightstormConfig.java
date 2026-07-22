package net.flowstom.nightstorm;

import java.util.Objects;

record NightstormConfig(
        String githubRepository,
        String minestomRepository,
        String minestomDataGeneratorRepository,
        String minestomDataGeneratorCommit,
        String branchPrefix,
        String minecraftChannel
) {
    NightstormConfig {
        require(githubRepository, "githubRepository");
        require(minestomRepository, "minestomRepository");
        require(minestomDataGeneratorRepository, "minestomDataGeneratorRepository");
        require(minestomDataGeneratorCommit, "minestomDataGeneratorCommit");
        require(branchPrefix, "branchPrefix");
        if (!"snapshot".equals(minecraftChannel) && !"release".equals(minecraftChannel)) {
            throw new IllegalArgumentException("minecraftChannel must be snapshot or release");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
