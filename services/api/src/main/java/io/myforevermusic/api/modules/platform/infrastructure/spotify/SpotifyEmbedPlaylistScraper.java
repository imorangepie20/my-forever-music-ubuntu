package io.myforevermusic.api.modules.platform.infrastructure.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient.SpotifyPlaylistTrack;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SpotifyEmbedPlaylistScraper {

    private static final String DEFAULT_EMBED_BASE_URI = "https://open.spotify.com/embed/playlist";
    private static final String USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String embedBaseUri;

    @Autowired
    public SpotifyEmbedPlaylistScraper(ObjectMapper objectMapper) {
        this(objectMapper, HttpClient.newHttpClient(), DEFAULT_EMBED_BASE_URI);
    }

    SpotifyEmbedPlaylistScraper(ObjectMapper objectMapper, HttpClient httpClient, String embedBaseUri) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.embedBaseUri = embedBaseUri;
    }

    public List<SpotifyPlaylistTrack> getPlaylistTracks(String externalPlaylistId) {
        if (externalPlaylistId == null || externalPlaylistId.isBlank()) {
            return List.of();
        }

        String html = requestEmbedHtml(externalPlaylistId);
        return parseTracks(html);
    }

    List<SpotifyPlaylistTrack> parseTracks(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        Element nextData = document.selectFirst("script#__NEXT_DATA__");
        if (nextData == null || nextData.html().isBlank()) {
            throw new IllegalArgumentException("Spotify embed playlist response is missing __NEXT_DATA__.");
        }

        try {
            JsonNode root = objectMapper.readTree(nextData.html());
            JsonNode trackList = root.path("props").path("pageProps").path("state").path("data").path("entity").path("trackList");
            List<SpotifyPlaylistTrack> tracks = new ArrayList<>();
            if (!trackList.isArray()) {
                throw new IllegalArgumentException("Spotify embed playlist response does not contain tracks.");
            }
            for (JsonNode track : trackList) {
                SpotifyPlaylistTrack parsed = toTrack(track);
                if (parsed != null) {
                    tracks.add(parsed);
                }
            }
            if (tracks.isEmpty()) {
                throw new IllegalArgumentException("Spotify embed playlist response does not contain tracks.");
            }
            return tracks;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Spotify embed playlist data could not be parsed.", exception);
        }
    }

    private String requestEmbedHtml(String externalPlaylistId) {
        String encodedId = URLEncoder.encode(externalPlaylistId, StandardCharsets.UTF_8);
        URI uri = URI.create("%s/%s".formatted(embedBaseUri, encodedId));
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(
                    "Spotify embed playlist request failed (%s).".formatted(response.statusCode())
                );
            }
            return response.body();
        } catch (IOException exception) {
            throw new IllegalStateException("Spotify embed playlist request failed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Spotify embed playlist request was interrupted.", exception);
        }
    }

    private SpotifyPlaylistTrack toTrack(JsonNode track) {
        String uri = text(track, "uri");
        String spotifyTrackId = spotifyTrackId(uri);
        String title = text(track, "title");
        if (spotifyTrackId == null || spotifyTrackId.isBlank() || title == null || title.isBlank()) {
            return null;
        }
        String artistName = normalizeArtistName(text(track, "subtitle"));
        Integer duration = track.path("duration").isNumber() ? track.path("duration").asInt() : null;
        return new SpotifyPlaylistTrack(
            spotifyTrackId,
            title,
            artistName == null || artistName.isBlank() ? "Spotify Artist" : artistName,
            null,
            null,
            "https://api.spotify.com/v1/tracks/%s".formatted(spotifyTrackId),
            "https://open.spotify.com/track/%s".formatted(spotifyTrackId),
            uri,
            text(track.path("audioPreview"), "url"),
            null,
            duration
        );
    }

    private String spotifyTrackId(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        int index = uri.lastIndexOf(':');
        return index >= 0 ? uri.substring(index + 1) : uri;
    }

    private String normalizeArtistName(String value) {
        return value == null ? null : value.replace('\u00a0', ' ').trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }
}
