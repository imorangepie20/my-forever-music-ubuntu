package io.myforevermusic.api.modules.platform.infrastructure.lastfm;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.application.LastFmProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LastFmWebApiClient {

    private final LastFmProperties lastFmProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public LastFmWebApiClient(
        LastFmProperties lastFmProperties,
        ObjectMapper objectMapper
    ) {
        this(lastFmProperties, objectMapper, HttpClient.newHttpClient());
    }

    protected LastFmWebApiClient(
        LastFmProperties lastFmProperties,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.lastFmProperties = lastFmProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public LastFmUserProfile getUserProfile(String username) {
        LastFmUserInfoEnvelope payload = get(
            Map.of("method", "user.getinfo", "user", username),
            LastFmUserInfoEnvelope.class
        );

        if (payload.user() == null || payload.user().name() == null || payload.user().name().isBlank()) {
            throw new IllegalArgumentException("Last.fm user profile could not be resolved for '%s'.".formatted(username));
        }

        return new LastFmUserProfile(
            payload.user().name(),
            blankToNull(payload.user().realName()),
            blankToNull(payload.user().country()),
            parseLong(payload.user().playcount()),
            blankToNull(payload.user().url()),
            extractLargestImage(payload.user().images()),
            parseInstant(payload.user().registered() == null ? null : payload.user().registered().unixTime())
        );
    }

    public List<LastFmRecentTrack> getRecentTracks(String username, int limit) {
        LastFmRecentTracksEnvelope payload = get(
            Map.of(
                "method", "user.getrecenttracks",
                "user", username,
                "limit", Integer.toString(limit),
                "extended", "1"
            ),
            LastFmRecentTracksEnvelope.class
        );

        return Optional.ofNullable(payload.recentTracks())
            .map(RecentTracksPayload::tracks)
            .orElse(List.of())
            .stream()
            .map(track -> new LastFmRecentTrack(
                blankToNull(track.name()),
                blankToNull(track.artist() == null ? null : track.artist().text()),
                blankToNull(track.album() == null ? null : track.album().text()),
                blankToNull(track.url()),
                extractLargestImage(track.images()),
                track.attributes() != null && "true".equalsIgnoreCase(track.attributes().nowPlaying()),
                parseInstant(track.date() == null ? null : track.date().unixTime()),
                "1".equals(track.loved())
            ))
            .toList();
    }

    public List<LastFmTopArtist> getTopArtists(String username, String period, int limit) {
        LastFmTopArtistsEnvelope payload = get(
            Map.of(
                "method", "user.gettopartists",
                "user", username,
                "period", period,
                "limit", Integer.toString(limit)
            ),
            LastFmTopArtistsEnvelope.class
        );

        return Optional.ofNullable(payload.topArtists())
            .map(TopArtistsPayload::artists)
            .orElse(List.of())
            .stream()
            .map(artist -> new LastFmTopArtist(
                blankToNull(artist.name()),
                parseInt(artist.attributes() == null ? null : artist.attributes().rank()),
                parseLong(artist.playcount()),
                blankToNull(artist.url()),
                extractLargestImage(artist.images())
            ))
            .toList();
    }

    public List<LastFmTopTrack> getTopTracks(String username, String period, int limit) {
        LastFmTopTracksEnvelope payload = get(
            Map.of(
                "method", "user.gettoptracks",
                "user", username,
                "period", period,
                "limit", Integer.toString(limit)
            ),
            LastFmTopTracksEnvelope.class
        );

        return Optional.ofNullable(payload.topTracks())
            .map(TopTracksPayload::tracks)
            .orElse(List.of())
            .stream()
            .map(track -> new LastFmTopTrack(
                blankToNull(track.name()),
                blankToNull(track.artist() == null ? null : track.artist().name()),
                parseInt(track.attributes() == null ? null : track.attributes().rank()),
                parseLong(track.playcount()),
                blankToNull(track.url()),
                blankToNull(track.artist() == null ? null : track.artist().url()),
                extractLargestImage(track.images())
            ))
            .toList();
    }

    public List<LastFmTag> getTrackTopTags(String artistName, String trackName) {
        LastFmTopTagsEnvelope payload = get(
            Map.of(
                "method", "track.getTopTags",
                "artist", artistName,
                "track", trackName,
                "autocorrect", "1"
            ),
            LastFmTopTagsEnvelope.class
        );

        return toTags(payload.topTags());
    }

    public List<LastFmTag> getArtistTopTags(String artistName) {
        LastFmTopTagsEnvelope payload = get(
            Map.of(
                "method", "artist.getTopTags",
                "artist", artistName,
                "autocorrect", "1"
            ),
            LastFmTopTagsEnvelope.class
        );

        return toTags(payload.topTags());
    }

    private <T> T get(Map<String, String> parameters, Class<T> responseType) {
        if (!lastFmProperties.isConfigured()) {
            throw new IllegalArgumentException(
                "Last.fm preview is not configured. Set LASTFM_ENABLED=true and LASTFM_API_KEY first."
            );
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildUri(parameters)))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException(readErrorMessage(response.statusCode(), response.body()));
            }

            LastFmErrorEnvelope errorEnvelope = objectMapper.readValue(response.body(), LastFmErrorEnvelope.class);
            if (errorEnvelope.error() != null) {
                throw new IllegalArgumentException(
                    "Last.fm API request failed (%s): %s".formatted(errorEnvelope.error(), errorEnvelope.message())
                );
            }

            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException exception) {
            throw new IllegalStateException("Last.fm API response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Last.fm API request was interrupted.", exception);
        }
    }

    private String buildUri(Map<String, String> parameters) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("format", "json");
        query.put("api_key", lastFmProperties.getApiKey());
        parameters.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                query.put(key, value);
            }
        });

        String queryString = query.entrySet().stream()
            .map(entry -> "%s=%s".formatted(
                encode(entry.getKey()),
                encode(entry.getValue())
            ))
            .collect(Collectors.joining("&"));

        String apiRoot = lastFmProperties.getApiRoot();
        String normalizedRoot = apiRoot.endsWith("/") ? apiRoot : "%s/".formatted(apiRoot);
        return "%s?%s".formatted(normalizedRoot, queryString);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String readErrorMessage(int statusCode, String body) {
        try {
            LastFmErrorEnvelope errorEnvelope = objectMapper.readValue(body, LastFmErrorEnvelope.class);
            if (errorEnvelope.message() != null && !errorEnvelope.message().isBlank()) {
                return "Last.fm API request failed (%s): %s".formatted(statusCode, errorEnvelope.message());
            }
        } catch (IOException ignored) {
            // Fall through to generic response below.
        }

        return body == null || body.isBlank()
            ? "Last.fm API request failed with status %s.".formatted(statusCode)
            : "Last.fm API request failed (%s): %s".formatted(statusCode, body);
    }

    private String extractLargestImage(List<ImageNode> images) {
        return Optional.ofNullable(images)
            .orElse(List.of())
            .stream()
            .map(ImageNode::text)
            .filter(value -> value != null && !value.isBlank())
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Instant parseInstant(String unixTime) {
        Long value = parseLong(unixTime);
        return value == null ? null : Instant.ofEpochSecond(value);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record LastFmUserProfile(
        String username,
        String realName,
        String country,
        Long playcount,
        String profileUrl,
        String avatarUrl,
        Instant registeredAt
    ) {
    }

    public record LastFmRecentTrack(
        String trackName,
        String artistName,
        String albumName,
        String trackUrl,
        String imageUrl,
        boolean nowPlaying,
        Instant playedAt,
        boolean loved
    ) {
    }

    public record LastFmTopArtist(
        String artistName,
        Integer rank,
        Long playcount,
        String artistUrl,
        String imageUrl
    ) {
    }

    public record LastFmTopTrack(
        String trackName,
        String artistName,
        Integer rank,
        Long playcount,
        String trackUrl,
        String artistUrl,
        String imageUrl
    ) {
    }

    public record LastFmTag(
        String tagName,
        Long count,
        String tagUrl
    ) {
    }

    private List<LastFmTag> toTags(TopTagsPayload topTags) {
        return Optional.ofNullable(topTags)
            .map(TopTagsPayload::tags)
            .orElse(List.of())
            .stream()
            .map(tag -> new LastFmTag(
                blankToNull(tag.name()),
                parseLong(tag.count()),
                blankToNull(tag.url())
            ))
            .filter(tag -> tag.tagName() != null && !tag.tagName().isBlank())
            .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmErrorEnvelope(
        Integer error,
        String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmUserInfoEnvelope(
        UserNode user
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmRecentTracksEnvelope(
        @JsonProperty("recenttracks")
        RecentTracksPayload recentTracks
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmTopArtistsEnvelope(
        @JsonProperty("topartists")
        TopArtistsPayload topArtists
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmTopTracksEnvelope(
        @JsonProperty("toptracks")
        TopTracksPayload topTracks
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LastFmTopTagsEnvelope(
        @JsonProperty("toptags")
        TopTagsPayload topTags
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserNode(
        String name,
        @JsonProperty("realname")
        String realName,
        String country,
        String playcount,
        String url,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<ImageNode> image,
        RegisteredNode registered
    ) {
        List<ImageNode> images() {
            return image;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegisteredNode(
        @JsonProperty("unixtime")
        String unixTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecentTracksPayload(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<RecentTrackNode> track
    ) {
        List<RecentTrackNode> tracks() {
            return track;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RecentTrackNode(
        String name,
        TextNode artist,
        TextNode album,
        String url,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<ImageNode> image,
        DateNode date,
        String loved,
        @JsonProperty("@attr")
        TrackAttributeNode attr
    ) {
        List<ImageNode> images() {
            return image;
        }

        TrackAttributeNode attributes() {
            return attr;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopArtistsPayload(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<TopArtistNode> artist
    ) {
        List<TopArtistNode> artists() {
            return artist;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopArtistNode(
        String name,
        String playcount,
        String url,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<ImageNode> image,
        @JsonProperty("@attr")
        RankAttributeNode attr
    ) {
        List<ImageNode> images() {
            return image;
        }

        RankAttributeNode attributes() {
            return attr;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopTracksPayload(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<TopTrackNode> track
    ) {
        List<TopTrackNode> tracks() {
            return track;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopTagsPayload(
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<TagNode> tag
    ) {
        List<TagNode> tags() {
            return tag;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopTrackNode(
        String name,
        String playcount,
        String url,
        ArtistNode artist,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<ImageNode> image,
        @JsonProperty("@attr")
        RankAttributeNode attr
    ) {
        List<ImageNode> images() {
            return image;
        }

        RankAttributeNode attributes() {
            return attr;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TextNode(
        @JsonProperty("#text")
        String text
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagNode(
        String name,
        String count,
        String url
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ArtistNode(
        String name,
        String url
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ImageNode(
        @JsonProperty("#text")
        String text,
        String size
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DateNode(
        @JsonProperty("uts")
        String unixTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TrackAttributeNode(
        @JsonProperty("nowplaying")
        String nowPlaying
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RankAttributeNode(
        String rank
    ) {
    }
}
