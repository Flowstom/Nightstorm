package net.flowstom.nightstorm;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GitHubClient {
    private final String repository;
    private final String token;
    private final HttpJsonClient http = new HttpJsonClient();

    GitHubClient(String repository, String explicitToken) {
        this.repository = repository;
        this.token = explicitToken == null ? System.getenv("GITHUB_TOKEN") : explicitToken;
    }

    UpstreamRelease latestRelease(String upstreamRepository) throws Exception {
        final JsonNode releases = http.get(URI.create("https://api.github.com/repos/" + upstreamRepository + "/releases?per_page=100"), token);
        return java.util.stream.StreamSupport.stream(releases.spliterator(), false)
                .filter(release -> !release.path("draft").asBoolean() && !release.path("prerelease").asBoolean())
                .map(this::toRelease)
                .max(Comparator.comparing(UpstreamRelease::publishedAt))
                .orElseThrow(() -> new IllegalStateException("No published release found for " + upstreamRepository));
    }

    List<String> branches(String prefix) throws Exception {
        final List<String> branches = new ArrayList<>();
        for (int page = 1; ; page++) {
            final JsonNode response = http.get(URI.create("https://api.github.com/repos/" + repository + "/branches?per_page=100&page=" + page), token);
            if (response.isEmpty()) {
                return branches;
            }
            response.forEach(branch -> {
                final String name = branch.path("name").asText();
                if (name.startsWith(prefix)) {
                    branches.add(name);
                }
            });
        }
    }

    private UpstreamRelease toRelease(JsonNode release) {
        return new UpstreamRelease(
                release.path("tag_name").asText(),
                Instant.parse(release.path("published_at").asText())
        );
    }

    record UpstreamRelease(String tag, Instant publishedAt) {
    }
}
