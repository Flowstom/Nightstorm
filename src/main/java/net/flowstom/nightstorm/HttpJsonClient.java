package net.flowstom.nightstorm;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpJsonClient {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    JsonNode get(URI uri, String token) throws IOException, InterruptedException {
        final var request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Flowstom-Nightstorm")
                .timeout(Duration.ofSeconds(30));
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        final var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + uri + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return Json.MAPPER.readTree(response.body());
    }
}
