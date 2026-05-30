package io.myforevermusic.api.modules.platform.infrastructure.tidal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import java.io.IOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.net.URLEncoder;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * TIDAL Open API v2 Web Client
 *
 * <p>Implements core API calls for PMS playlist import:
 * <ul>
 *   <li>GET /userCollectionPlaylists - user's playlists</li>
 *   <li>GET /playlists/{id} - playlist details with items</li>
 *   <li>GET /tracks/{id} - track details</li>
 * </ul>
 *
 * <p>See: https://developer.tidal.com
 */
@Component
public class TidalWebApiClient {

    private static final Logger log = LoggerFactory.getLogger(TidalWebApiClient.class);
    private static final String ACCEPT_HEADER = "application/vnd.api+json";
    private static final int SEARCH_PAGE_SIZE = 50;
    private static final int PLAYLIST_TRACK_PAGE_SIZE = 100;
    private static final Map<String, String> HOME_PAGE_TITLES_BY_SOURCE_ID = Map.of(
        "THE_HITS", "The Hits",
        "POPULAR_MIXES", "Popular Mixes",
        "POPULAR_PLAYLISTS", "Popular Playlists",
        "FROM_OUR_EDITORS", "From our editors"
    );

    private final PlatformOAuthProperties platformOAuthProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiBaseUri;

    public record TidalSearchResult<T>(List<T> items, int total) {}

    @Autowired
    public TidalWebApiClient(
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper
    ) {
        this(
            platformOAuthProperties,
            objectMapper,
            HttpClient.newHttpClient(),
            platformOAuthProperties.getTidal().getApiBaseUri()
        );
    }

    TidalWebApiClient(
        PlatformOAuthProperties platformOAuthProperties,
        ObjectMapper objectMapper,
        HttpClient httpClient,
        String apiBaseUri
    ) {
        this.platformOAuthProperties = platformOAuthProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.apiBaseUri = trimTrailingSlash(apiBaseUri);
    }

    /**
     * Get the current user's profile from TIDAL.
     */
    public TidalUserProfile getCurrentUserProfile(PlatformAccountCredential credential) {
        Optional<TidalUserProfile> tokenProfile = profileFromAccessToken(credential.accessToken());
        if (tokenProfile.isPresent()) {
            return tokenProfile.get();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("%s/user".formatted(apiBaseUri)))
                .header("Accept", ACCEPT_HEADER)
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .header("Content-Type", "application/vnd.api+json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("TIDAL user profile request failed: %s".formatted(response.statusCode()));
            }

            JsonApiRoot jsonApi = objectMapper.readValue(response.body(), JsonApiRoot.class);
            return Optional.ofNullable(jsonApi.data())
                .filter(data -> "users".equals(data.type()))
                .map(data -> new TidalUserProfile(
                    data.id(),
                    extractAttribute(data.attributes(), "userId", String.class),
                    extractAttribute(data.attributes(), "firstName", String.class),
                    extractAttribute(data.attributes(), "lastName", String.class),
                    extractAttribute(data.attributes(), "email", String.class)
                ))
                .orElseThrow(() -> new IllegalArgumentException("TIDAL user profile response missing user data"));
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL user profile response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL user profile request was interrupted.", exception);
        }
    }

    private Optional<TidalUserProfile> profileFromAccessToken(String accessToken) {
        return claimsFromAccessToken(accessToken)
            .flatMap(claims -> {
                String userId = firstNonBlank(
                    claimAsString(claims, "uid"),
                    claimAsString(claims, "user_id"),
                    claimAsString(claims, "userId"),
                    claimAsString(claims, "tidalUserId"),
                    claimAsString(claims, "sub")
                );
                if (userId == null) {
                    return Optional.empty();
                }

                return Optional.of(new TidalUserProfile(
                    userId,
                    claimAsString(claims, "tidalUserId"),
                    claimAsString(claims, "firstName"),
                    claimAsString(claims, "lastName"),
                    claimAsString(claims, "email")
                ));
            });
    }

    private String claimAsString(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value == null) {
            return null;
        }

        String stringValue = value.toString().trim();
        return stringValue.isBlank() ? null : stringValue;
    }

    private Optional<Map<String, Object>> claimsFromAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }

        String[] jwtParts = accessToken.split("\\.");
        if (jwtParts.length < 2) {
            return Optional.empty();
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
            return Optional.of(claims);
        } catch (RuntimeException | IOException exception) {
            return Optional.empty();
        }
    }

    private String countryCodeForCredential(PlatformAccountCredential credential) {
        return firstNonBlank(
            countryCodeFromAccessToken(credential.accessToken()),
            platformOAuthProperties.getTidal().getCountryCode()
        );
    }

    private String countryCodeFromAccessToken(String accessToken) {
        return claimsFromAccessToken(accessToken)
            .map(claims -> claimAsString(claims, "cc"))
            .orElse(null);
    }

    /**
     * Search public playlists on TIDAL.
     */
    public List<TidalPlaylistSummary> searchPlaylists(
        PlatformAccountCredential credential,
        String query,
        int limit
    ) {
        int requestLimit = Math.min(Math.max(limit, 1), 50);
        return searchPlaylistResults(credential, query, requestLimit, false).items();
    }

    public List<TidalPlaylistSummary> searchPlaylists(
        PlatformAccountCredential credential,
        String query
    ) {
        return searchPlaylistResults(credential, query).items();
    }

    public TidalSearchResult<TidalPlaylistSummary> searchPlaylistResults(
        PlatformAccountCredential credential,
        String query
    ) {
        return searchPlaylistResults(credential, query, SEARCH_PAGE_SIZE, true);
    }

    private TidalSearchResult<TidalPlaylistSummary> searchPlaylistResults(
        PlatformAccountCredential credential,
        String query,
        int requestLimit,
        boolean followAllPages
    ) {
        String countryCode = countryCodeForCredential(credential);
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI nextUri = URI.create("%s/search?query=%s&type=playlists&countryCode=%s&limit=%d".formatted(
                apiBaseUri, encodedQuery, countryCode, requestLimit
            ));
            List<TidalPlaylistSummary> results = new ArrayList<>();
            int total = 0;

            while (nextUri != null) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(nextUri)
                    .header("Accept", ACCEPT_HEADER)
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .header("Content-Type", "application/vnd.api+json")
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.info("TIDAL OpenAPI playlist search unavailable: {}. Falling back to legacy search boundary.", response.statusCode());
                    return searchLegacyPlaylists(credential, query, requestLimit, countryCode, followAllPages);
                }

                JsonApiArrayRoot jsonApi = objectMapper.readValue(response.body(), JsonApiArrayRoot.class);
                List<TidalPlaylistSummary> pagePlaylists = Optional.ofNullable(jsonApi.data())
                    .stream()
                    .flatMap(List::stream)
                    .filter(data -> "playlists".equals(data.type()))
                    .map(this::toPlaylistSummary)
                    .toList();
                results.addAll(pagePlaylists);
                total = Math.max(total, totalFromMeta(jsonApi.meta(), results.size()));
                nextUri = nextPageUri(jsonApi.links());
                if (!followAllPages) {
                    nextUri = null;
                }
            }

            return new TidalSearchResult<>(results, Math.max(total, results.size()));
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playlist search response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playlist search request was interrupted.", exception);
        }
    }

    /**
     * Search tracks on TIDAL.
     */
    public List<TidalPlaylistTrack> searchTracks(
        PlatformAccountCredential credential,
        String query,
        int limit
    ) {
        int requestLimit = Math.min(Math.max(limit, 1), 50);
        return searchTrackResults(credential, query, requestLimit, false).items();
    }

    public List<TidalPlaylistTrack> searchTracks(
        PlatformAccountCredential credential,
        String query
    ) {
        return searchTrackResults(credential, query).items();
    }

    public TidalSearchResult<TidalPlaylistTrack> searchTrackResults(
        PlatformAccountCredential credential,
        String query
    ) {
        return searchTrackResults(credential, query, SEARCH_PAGE_SIZE, true);
    }

    private TidalSearchResult<TidalPlaylistTrack> searchTrackResults(
        PlatformAccountCredential credential,
        String query,
        int requestLimit,
        boolean followAllPages
    ) {
        String countryCode = countryCodeForCredential(credential);
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI nextUri = URI.create("%s/search?query=%s&type=tracks&countryCode=%s&limit=%d".formatted(
                apiBaseUri, encodedQuery, countryCode, requestLimit
            ));
            List<TidalPlaylistTrack> results = new ArrayList<>();
            int total = 0;

            while (nextUri != null) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(nextUri)
                    .header("Accept", ACCEPT_HEADER)
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .header("Content-Type", "application/vnd.api+json")
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.info("TIDAL OpenAPI track search unavailable: {}. Falling back to legacy search boundary.", response.statusCode());
                    return searchLegacyTracks(credential, query, requestLimit, countryCode, followAllPages);
                }

                JsonApiArrayWithIncluded jsonApi = objectMapper.readValue(response.body(), JsonApiArrayWithIncluded.class);
                Map<String, JsonApiData> includedByKey = indexIncluded(jsonApi.included());
                List<TidalPlaylistTrack> pageTracks = Optional.ofNullable(jsonApi.data())
                    .stream()
                    .flatMap(List::stream)
                    .filter(data -> "tracks".equals(data.type()))
                    .map(trackData -> toTrackFromSearch(trackData, includedByKey))
                    .toList();
                results.addAll(pageTracks);
                total = Math.max(total, totalFromMeta(jsonApi.meta(), results.size()));
                nextUri = nextPageUri(jsonApi.links());
                if (!followAllPages) {
                    nextUri = null;
                }
            }

            return new TidalSearchResult<>(results, Math.max(total, results.size()));
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL track search response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL track search request was interrupted.", exception);
        }
    }

    public List<TidalPlaylistSummary> getHomePagePlaylists(
        PlatformAccountCredential credential,
        String sourceId,
        int limit
    ) {
        String countryCode = countryCodeForCredential(credential);
        int clampedLimit = Math.min(Math.max(limit, 1), 50);
        String apiPath = sourceId == null ? null : sourceId.trim();
        if (apiPath == null || apiPath.isBlank()) {
            throw new IllegalArgumentException("TIDAL page source id is required.");
        }

        if (!apiPath.startsWith("pages/")) {
            apiPath = findHomePageModuleApiPath(credential, apiPath, countryCode);
        }
        if (apiPath == null || apiPath.isBlank()) {
            throw new IllegalArgumentException("TIDAL home page source was not found: %s".formatted(sourceId));
        }

        JsonNode page = getLegacyPage(credential, apiPath, countryCode);
        java.util.ArrayList<TidalPlaylistSummary> playlists = new java.util.ArrayList<>();
        collectPlaylistItems(page, playlists);
        return playlists.stream()
            .filter(playlist -> playlist.playlistId() != null && !playlist.playlistId().isBlank())
            .limit(clampedLimit)
            .toList();
    }

    /**
     * Get playlists from a TIDAL public home page (e.g. POPULAR_PLAYLISTS, THE_HITS) without OAuth.
     *
     * <p>Uses the public TIDAL web app endpoint, which gates only on the presence of an
     * x-tidal-client-version header (no user token / no secret to rotate).
     */
    public List<TidalPlaylistSummary> getPublicHomePagePlaylists(String sourceId, int limit) {
        String cleanSource = sourceId == null ? "" : sourceId.trim();
        if (cleanSource.isBlank()) {
            throw new IllegalArgumentException("TIDAL page source id is required.");
        }
        int clampedLimit = Math.min(Math.max(limit, 1), 50);
        String countryCode = platformOAuthProperties.getTidal().getCountryCode();
        try {
            HttpRequest request = publicWebRequest(
                "%s/v2/home/pages/%s/view-all?countryCode=%s&locale=en_US&deviceType=BROWSER&platform=WEB&limit=%d&offset=0".formatted(
                    webBaseUri(),
                    URLEncoder.encode(cleanSource, StandardCharsets.UTF_8),
                    countryCode,
                    clampedLimit
                )
            );

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("TIDAL public home-page request failed (%s): %s"
                    .formatted(response.statusCode(), response.body()));
            }

            JsonNode body = objectMapper.readTree(response.body());
            JsonNode items = body.path("items");
            ArrayList<TidalPlaylistSummary> playlists = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (!"PLAYLIST".equals(text(item, "type"))) {
                        continue;
                    }
                    TidalPlaylistSummary playlist = toLegacyPlaylistSummary(item.path("data"));
                    if (playlist != null && playlist.playlistId() != null && !playlist.playlistId().isBlank()) {
                        playlists.add(playlist);
                    }
                }
            }
            return playlists.stream().limit(clampedLimit).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL public home-page response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL public home-page request was interrupted.", exception);
        }
    }

    /**
     * Get a playlist's tracks from the TIDAL public web endpoint without OAuth.
     */
    public List<TidalPlaylistTrack> getPublicPlaylistTracks(String playlistId) {
        String countryCode = platformOAuthProperties.getTidal().getCountryCode();
        try {
            int offset = 0;
            List<TidalPlaylistTrack> tracks = new ArrayList<>();
            while (true) {
                HttpRequest request = publicWebRequest(
                    "%s/v1/playlists/%s/items?countryCode=%s&limit=%d&offset=%d".formatted(
                        webBaseUri(),
                        URLEncoder.encode(playlistId, StandardCharsets.UTF_8),
                        countryCode,
                        PLAYLIST_TRACK_PAGE_SIZE,
                        offset
                    )
                );

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (offset == 0) {
                        throw new IllegalArgumentException("TIDAL public playlist tracks failed (%s): %s"
                            .formatted(response.statusCode(), response.body()));
                    }
                    break;
                }

                JsonNode body = objectMapper.readTree(response.body());
                JsonNode items = body.path("items");
                List<TidalPlaylistTrack> pageTracks = new ArrayList<>();
                if (items.isArray()) {
                    for (JsonNode wrapper : items) {
                        // Public playlist items wrap the track payload under "item".
                        JsonNode trackNode = wrapper.has("item") ? wrapper.path("item") : wrapper;
                        TidalPlaylistTrack track = toLegacyTrack(trackNode);
                        if (track != null) {
                            pageTracks.add(track);
                        }
                    }
                }
                tracks.addAll(pageTracks);
                if (pageTracks.size() < PLAYLIST_TRACK_PAGE_SIZE) {
                    break;
                }
                offset += pageTracks.size();
            }
            return tracks;
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL public playlist tracks response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL public playlist tracks request was interrupted.", exception);
        }
    }

    private HttpRequest publicWebRequest(String uri) {
        return HttpRequest.newBuilder()
            .uri(URI.create(uri))
            .header("Accept", "application/json")
            .header("x-tidal-client-version", platformOAuthProperties.getTidal().getWebClientVersion())
            .GET()
            .build();
    }

    private String webBaseUri() {
        return trimTrailingSlash(platformOAuthProperties.getTidal().getWebBaseUri());
    }

    private String findHomePageModuleApiPath(
        PlatformAccountCredential credential,
        String sourceId,
        String countryCode
    ) {
        String expectedTitle = HOME_PAGE_TITLES_BY_SOURCE_ID.getOrDefault(sourceId.trim(), sourceId.trim());
        JsonNode homePage = getLegacyPage(credential, "pages/home", countryCode);
        return findShowMoreApiPath(homePage, expectedTitle);
    }

    private JsonNode getLegacyPage(
        PlatformAccountCredential credential,
        String apiPath,
        String countryCode
    ) {
        try {
            String cleanPath = apiPath.startsWith("/") ? apiPath.substring(1) : apiPath;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("%s/%s?countryCode=%s&deviceType=BROWSER".formatted(
                    legacyApiBaseUri(),
                    cleanPath,
                    countryCode
                )))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("TIDAL page request failed (%s): %s"
                    .formatted(response.statusCode(), response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL page response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL page request was interrupted.", exception);
        }
    }

    private String findShowMoreApiPath(JsonNode node, String expectedTitle) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String title = text(node, "title");
            String apiPath = text(node.path("showMore"), "apiPath");
            if (title != null && title.equalsIgnoreCase(expectedTitle) && apiPath != null) {
                return apiPath;
            }
            java.util.Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                String found = findShowMoreApiPath(children.next(), expectedTitle);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findShowMoreApiPath(child, expectedTitle);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void collectPlaylistItems(JsonNode node, java.util.List<TidalPlaylistSummary> playlists) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if ("PLAYLIST_LIST".equals(text(node, "type"))) {
                JsonNode items = node.path("pagedList").path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        TidalPlaylistSummary playlist = toLegacyPlaylistSummary(item);
                        if (playlist != null) {
                            playlists.add(playlist);
                        }
                    }
                }
            }
            java.util.Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                collectPlaylistItems(children.next(), playlists);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectPlaylistItems(child, playlists);
            }
        }
    }

    private TidalSearchResult<TidalPlaylistSummary> searchLegacyPlaylists(
        PlatformAccountCredential credential,
        String query,
        int limit,
        String countryCode,
        boolean followAllPages
    ) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int offset = 0;
            List<TidalPlaylistSummary> playlists = new ArrayList<>();
            while (true) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/search/playlists?query=%s&limit=%d&offset=%d&countryCode=%s".formatted(
                        legacyApiBaseUri(), encodedQuery, limit, offset, countryCode
                    )))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    log.info("TIDAL legacy playlist search endpoint is unavailable. Continuing with track search only.");
                    return new TidalSearchResult<>(List.of(), 0);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("TIDAL legacy playlist search failed (%s): %s"
                        .formatted(response.statusCode(), response.body()));
                }

                JsonNode body = objectMapper.readTree(response.body());
                List<TidalPlaylistSummary> pagePlaylists = jsonItems(body)
                    .map(this::toLegacyPlaylistSummary)
                    .filter(Objects::nonNull)
                    .toList();
                playlists.addAll(pagePlaylists);
                Integer total = totalFromJson(body);
                if (!followAllPages || pagePlaylists.size() < limit || (total != null && total <= playlists.size())) {
                    return new TidalSearchResult<>(playlists, Math.max(total == null ? 0 : total, playlists.size()));
                }
                if (pagePlaylists.isEmpty()) {
                    break;
                }
                offset += pagePlaylists.size();
            }
            return new TidalSearchResult<>(playlists, playlists.size());
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL legacy playlist search response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL legacy playlist search request was interrupted.", exception);
        }
    }

    private TidalSearchResult<TidalPlaylistTrack> searchLegacyTracks(
        PlatformAccountCredential credential,
        String query,
        int limit,
        String countryCode,
        boolean followAllPages
    ) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int offset = 0;
            List<TidalPlaylistTrack> tracks = new ArrayList<>();
            while (true) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/search/tracks?query=%s&limit=%d&offset=%d&countryCode=%s".formatted(
                        legacyApiBaseUri(), encodedQuery, limit, offset, countryCode
                    )))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("TIDAL legacy track search failed (%s): %s"
                        .formatted(response.statusCode(), response.body()));
                }

                JsonNode body = objectMapper.readTree(response.body());
                List<TidalPlaylistTrack> pageTracks = jsonItems(body)
                    .map(this::toLegacyTrack)
                    .filter(Objects::nonNull)
                    .toList();
                tracks.addAll(pageTracks);
                Integer total = totalFromJson(body);
                if (!followAllPages || pageTracks.size() < limit || (total != null && total <= tracks.size())) {
                    return new TidalSearchResult<>(tracks, Math.max(total == null ? 0 : total, tracks.size()));
                }
                if (pageTracks.isEmpty()) {
                    break;
                }
                offset += pageTracks.size();
            }
            return new TidalSearchResult<>(tracks, tracks.size());
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL legacy track search response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL legacy track search request was interrupted.", exception);
        }
    }

    private TidalPlaylistTrack toTrackFromSearch(
        JsonApiData trackData,
        Map<String, JsonApiData> includedByKey
    ) {
        Map<String, Object> trackAttributes = trackData.attributes();
        List<String> artistIds = extractRelationshipIds(trackData.relationships(), "artists");
        List<String> albumIds = extractRelationshipIds(trackData.relationships(), "albums");

        String artistName = artistIds.stream()
            .map(artistId -> includedByKey.get(resourceKey("artists", artistId)))
            .filter(Objects::nonNull)
            .map(JsonApiData::attributes)
            .map(attributes -> firstNonBlank(
                extractAttribute(attributes, "name", String.class),
                extractAttribute(attributes, "artistName", String.class)
            ))
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .reduce((left, right) -> left + ", " + right)
            .orElse("TIDAL Artist");

        JsonApiData albumData = albumIds.stream()
            .map(albumId -> includedByKey.get(resourceKey("albums", albumId)))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        Map<String, Object> albumAttributes = albumData == null ? null : albumData.attributes();

        int durationSeconds = durationSeconds(trackAttributes);
        String albumImageId = firstNonBlank(
            extractAttribute(albumAttributes, "imageId", String.class),
            extractAttribute(albumAttributes, "cover", String.class),
            extractAttribute(albumAttributes, "coverImageId", String.class)
        );
        String externalUrl = firstNonBlank(
            extractAttribute(trackAttributes, "url", String.class),
            extractAttribute(trackAttributes, "shareUrl", String.class),
            extractAttribute(trackAttributes, "externalUrl", String.class),
            externalLink(trackAttributes)
        );
        String previewUrl = firstNonBlank(
            extractAttribute(trackAttributes, "previewUrl", String.class),
            extractAttribute(trackAttributes, "previewURL", String.class)
        );

        return new TidalPlaylistTrack(
            trackData.id(),
            firstNonBlank(extractAttribute(trackAttributes, "title", String.class), "Unknown Track"),
            artistName,
            firstNonBlank(
                extractAttribute(albumAttributes, "title", String.class),
                extractAttribute(trackAttributes, "albumTitle", String.class),
                "TIDAL Album"
            ),
            buildImageUrl(albumImageId),
            externalUrl,
            trackData.id() == null || trackData.id().isBlank() ? null : "tidal:track:%s".formatted(trackData.id()),
            previewUrl,
            normalizeIsrc(extractAttribute(trackAttributes, "isrc", String.class)),
            durationSeconds * 1000
        );
    }

    /**
     * Get user's playlists from TIDAL.
     */
    public List<TidalPlaylistSummary> getUserPlaylists(PlatformAccountCredential credential) {
        String countryCode = countryCodeForCredential(credential);
        String userCollectionId = resolveUserCollectionId(credential);

        // Device-authorized tokens only carry legacy scopes (r_usr/w_usr/w_sub), which the
        // legacy v1 user-playlist endpoint accepts. Try it first, mirroring getPlaylistTracks,
        // then fall back to the OpenAPI v2 collection endpoints for PKCE-scoped tokens.
        Optional<List<TidalPlaylistSummary>> legacyPlaylists =
            getLegacyUserPlaylists(credential, userCollectionId, countryCode);
        if (legacyPlaylists.isPresent()) {
            return legacyPlaylists.get();
        }

        Optional<List<TidalPlaylistSummary>> relationshipPlaylists =
            getUserCollectionRelationshipPlaylists(credential, userCollectionId, countryCode);
        if (relationshipPlaylists.isPresent()) {
            return relationshipPlaylists.get();
        }

        Optional<List<TidalPlaylistSummary>> shortcutPlaylists =
            getUserCollectionShortcutPlaylists(credential, countryCode);
        if (shortcutPlaylists.isPresent()) {
            return shortcutPlaylists.get();
        }

        throw new IllegalArgumentException("TIDAL user playlist listing failed. Check token scopes and user collection access.");
    }

    private String resolveUserCollectionId(PlatformAccountCredential credential) {
        String fromToken = profileFromAccessToken(credential.accessToken())
            .map(profile -> firstNonBlank(profile.userId(), profile.tidalUserId()))
            .orElse(null);
        return firstNonBlank(fromToken, credential.externalUserId());
    }

    private Optional<List<TidalPlaylistSummary>> getLegacyUserPlaylists(
        PlatformAccountCredential credential,
        String userId,
        String countryCode
    ) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        try {
            int offset = 0;
            List<TidalPlaylistSummary> playlists = new ArrayList<>();
            while (true) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/users/%s/playlists?countryCode=%s&limit=%d&offset=%d".formatted(
                        legacyApiBaseUri(),
                        URLEncoder.encode(userId, StandardCharsets.UTF_8),
                        countryCode,
                        PLAYLIST_TRACK_PAGE_SIZE,
                        offset
                    )))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.info("TIDAL legacy user playlist listing unavailable for {}: {}", userId, response.statusCode());
                    return Optional.empty();
                }

                JsonNode body = objectMapper.readTree(response.body());
                List<TidalPlaylistSummary> pagePlaylists = jsonItems(body)
                    .map(this::toLegacyPlaylistSummary)
                    .filter(Objects::nonNull)
                    .toList();
                playlists.addAll(pagePlaylists);
                if (pagePlaylists.size() < PLAYLIST_TRACK_PAGE_SIZE) {
                    break;
                }
                offset += pagePlaylists.size();
            }
            return Optional.of(playlists);
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL legacy user playlist listing could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL legacy user playlist listing was interrupted.", exception);
        }
    }

    private Optional<List<TidalPlaylistSummary>> getUserCollectionRelationshipPlaylists(
        PlatformAccountCredential credential,
        String userCollectionId,
        String countryCode
    ) {
        if (userCollectionId == null || userCollectionId.isBlank()) {
            return Optional.empty();
        }

        try {
            URI nextUri = URI.create("%s/userCollections/%s/relationships/playlists?countryCode=%s&include=playlists".formatted(
                apiBaseUri,
                URLEncoder.encode(userCollectionId, StandardCharsets.UTF_8),
                countryCode
            ));
            List<TidalPlaylistSummary> playlists = new ArrayList<>();
            int referenceCount = 0;

            while (nextUri != null) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(nextUri)
                    .header("Accept", ACCEPT_HEADER)
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("TIDAL user collection playlist relationship request failed: {}", response.statusCode());
                    return Optional.empty();
                }

                JsonApiArrayWithIncluded jsonApi = objectMapper.readValue(response.body(), JsonApiArrayWithIncluded.class);
                Map<String, JsonApiData> includedByKey = indexIncluded(jsonApi.included());
                List<JsonApiData> references = Optional.ofNullable(jsonApi.data()).orElse(List.of())
                    .stream()
                    .filter(data -> "playlists".equals(data.type()))
                    .toList();
                referenceCount += references.size();
                references.stream()
                    .map(data -> toPlaylistSummaryFromRelationship(data, includedByKey, credential))
                    .filter(Objects::nonNull)
                    .forEach(playlists::add);

                nextUri = nextPageUri(jsonApi.links());
            }

            if (referenceCount > 0 && playlists.isEmpty()) {
                // The relationship listed playlist references but none could be resolved to
                // metadata (missing include / per-id lookups rejected). Report it as a miss so
                // the caller can fall back instead of silently returning an empty list.
                log.warn("TIDAL user collection relationship returned {} playlist references but no resolvable metadata.", referenceCount);
                return Optional.empty();
            }

            return Optional.of(playlists);
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playlists response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playlists request was interrupted.", exception);
        }
    }

    private Optional<List<TidalPlaylistSummary>> getUserCollectionShortcutPlaylists(
        PlatformAccountCredential credential,
        String countryCode
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("%s/userCollectionPlaylists?countryCode=%s&limit=50".formatted(apiBaseUri, countryCode)))
                .header("Accept", ACCEPT_HEADER)
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("TIDAL playlist shortcut request failed: {}", response.statusCode());
                return Optional.empty();
            }

            JsonApiArrayWithIncluded jsonApi = objectMapper.readValue(response.body(), JsonApiArrayWithIncluded.class);
            Map<String, JsonApiData> includedByKey = indexIncluded(jsonApi.included());
            List<JsonApiData> references = Optional.ofNullable(jsonApi.data())
                .stream()
                .flatMap(List::stream)
                .filter(data -> "playlists".equals(data.type()))
                .toList();
            List<TidalPlaylistSummary> playlists = references.stream()
                .map(data -> toPlaylistSummaryFromRelationship(data, includedByKey, credential))
                .filter(Objects::nonNull)
                .toList();

            if (!references.isEmpty() && playlists.isEmpty()) {
                log.warn("TIDAL playlist shortcut returned {} playlist references but no resolvable metadata.", references.size());
                return Optional.empty();
            }

            return Optional.of(playlists);
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playlists response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playlists request was interrupted.", exception);
        }
    }

    public TidalPlaylistSummary getPlaylist(
        PlatformAccountCredential credential,
        String playlistId
    ) {
        String countryCode = countryCodeForCredential(credential);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("%s/playlists/%s?countryCode=%s".formatted(
                    apiBaseUri,
                    URLEncoder.encode(playlistId, StandardCharsets.UTF_8),
                    countryCode
                )))
                .header("Accept", ACCEPT_HEADER)
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("TIDAL playlist metadata request failed: %s".formatted(response.statusCode()));
            }

            JsonApiRoot jsonApi = objectMapper.readValue(response.body(), JsonApiRoot.class);
            return Optional.ofNullable(jsonApi.data())
                .filter(data -> "playlists".equals(data.type()))
                .map(this::toPlaylistSummary)
                .orElseThrow(() -> new IllegalArgumentException("TIDAL playlist metadata response missing playlist data"));
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playlist metadata response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playlist metadata request was interrupted.", exception);
        }
    }

    /**
     * Get playlist items (tracks) from TIDAL.
     */
    public List<TidalPlaylistTrack> getPlaylistTracks(
        PlatformAccountCredential credential,
        String playlistId
    ) {
        String countryCode = countryCodeForCredential(credential);
        try {
            try {
                return getLegacyPlaylistTracks(credential, playlistId, countryCode);
            } catch (IllegalArgumentException exception) {
                log.info(
                    "TIDAL legacy playlist tracks unavailable for {}: {}. Falling back to OpenAPI track details.",
                    playlistId,
                    exception.getMessage()
                );
            }

            List<String> trackIds = getPlaylistTrackIds(credential, playlistId, countryCode);
            if (trackIds.isEmpty()) {
                log.info("TIDAL OpenAPI playlist item relationship returned no track ids for {}.", playlistId);
                return List.of();
            }

            List<TidalPlaylistTrack> tracks = trackIds.stream()
                .map(trackId -> getTrackDetail(credential, trackId, countryCode))
                .filter(Objects::nonNull)
                .toList();
            int missing = trackIds.size() - tracks.size();
            if (missing > 0) {
                log.warn(
                    "TIDAL OpenAPI track detail dropped {}/{} tracks for playlist {} (per-id error/404).",
                    missing,
                    trackIds.size(),
                    playlistId
                );
            }
            if (tracks.isEmpty()) {
                log.info("TIDAL OpenAPI track detail returned no tracks for playlist {}.", playlistId);
                return List.of();
            }
            return tracks;
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL playlist tracks response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL playlist tracks request was interrupted.", exception);
        }
    }

    private List<String> getPlaylistTrackIds(
        PlatformAccountCredential credential,
        String playlistId,
        String countryCode
    ) throws IOException, InterruptedException {
        URI nextUri = URI.create("%s/playlists/%s/relationships/items?countryCode=%s".formatted(
            apiBaseUri,
            URLEncoder.encode(playlistId, StandardCharsets.UTF_8),
            countryCode
        ));
        java.util.ArrayList<String> trackIds = new java.util.ArrayList<>();

        while (nextUri != null) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(nextUri)
                .header("Accept", ACCEPT_HEADER)
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.info("TIDAL OpenAPI playlist item relationship unavailable for {}: {}", playlistId, response.statusCode());
                return List.of();
            }

            JsonApiArrayRoot jsonApi = objectMapper.readValue(response.body(), JsonApiArrayRoot.class);
            Optional.ofNullable(jsonApi.data()).orElse(List.of())
                .stream()
                .filter(item -> "tracks".equals(item.type()))
                .map(JsonApiData::id)
                .filter(id -> id != null && !id.isBlank())
                .forEach(trackIds::add);

            nextUri = nextPageUri(jsonApi.links());
        }

        return trackIds;
    }

    private TidalPlaylistTrack getTrackDetail(
        PlatformAccountCredential credential,
        String trackId,
        String countryCode
    ) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("%s/tracks/%s?countryCode=%s&include=artists,albums".formatted(
                    apiBaseUri,
                    URLEncoder.encode(trackId, StandardCharsets.UTF_8),
                    countryCode
                )))
                .header("Accept", ACCEPT_HEADER)
                .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("TIDAL OpenAPI track detail failed for {}: {}", trackId, response.statusCode());
                return null;
            }

            JsonApiWithIncluded jsonApi = objectMapper.readValue(response.body(), JsonApiWithIncluded.class);
            if (jsonApi.data() == null) {
                return null;
            }
            return toTrackFromSearch(jsonApi.data(), indexIncluded(jsonApi.included()));
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL track detail response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL track detail request was interrupted.", exception);
        }
    }

    private List<TidalPlaylistTrack> getLegacyPlaylistTracks(
        PlatformAccountCredential credential,
        String playlistId,
        String countryCode
    ) {
        try {
            int offset = 0;
            List<TidalPlaylistTrack> tracks = new ArrayList<>();
            while (true) {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("%s/playlists/%s/tracks?countryCode=%s&limit=%d&offset=%d".formatted(
                        legacyApiBaseUri(),
                        URLEncoder.encode(playlistId, StandardCharsets.UTF_8),
                        countryCode,
                        PLAYLIST_TRACK_PAGE_SIZE,
                        offset
                    )))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer %s".formatted(credential.accessToken()))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException("TIDAL legacy playlist tracks failed (%s): %s"
                        .formatted(response.statusCode(), response.body()));
                }

                JsonNode body = objectMapper.readTree(response.body());
                List<TidalPlaylistTrack> pageTracks = jsonItems(body)
                    .map(this::toLegacyTrack)
                    .filter(Objects::nonNull)
                    .toList();
                tracks.addAll(pageTracks);
                if (pageTracks.size() < PLAYLIST_TRACK_PAGE_SIZE) {
                    break;
                }
                offset += pageTracks.size();
            }
            return tracks;
        } catch (IOException exception) {
            throw new IllegalStateException("TIDAL legacy playlist tracks response could not be parsed.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TIDAL legacy playlist tracks request was interrupted.", exception);
        }
    }

    private TidalPlaylistSummary toPlaylistSummary(JsonApiData data) {
        Map<String, Object> attrs = data.attributes();
        Integer trackCount = extractAttribute(attrs, "numberOfTracks", Integer.class);
        if (trackCount == null) {
            trackCount = extractAttribute(attrs, "numberOfItems", Integer.class, 0);
        }
        String imageId = firstNonBlank(
            extractAttribute(attrs, "imageId", String.class),
            extractAttribute(attrs, "squareImage", String.class),
            extractAttribute(attrs, "cover", String.class),
            extractAttribute(attrs, "image", String.class)
        );
        return new TidalPlaylistSummary(
            data.id(),
            firstNonBlank(
                extractAttribute(attrs, "title", String.class),
                extractAttribute(attrs, "name", String.class)
            ),
            extractAttribute(attrs, "description", String.class),
            trackCount,
            imageId,
            firstNonBlank(imageLink(attrs), buildImageUrl(imageId)),
            firstNonBlank(
                extractAttribute(attrs, "url", String.class),
                extractAttribute(attrs, "shareUrl", String.class),
                extractAttribute(attrs, "externalUrl", String.class),
                externalLink(attrs)
            ),
            extractAttribute(attrs, "uuid", String.class)
        );
    }

    private TidalPlaylistSummary toPlaylistSummaryFromRelationship(
        JsonApiData playlistReference,
        Map<String, JsonApiData> includedByKey,
        PlatformAccountCredential credential
    ) {
        if (playlistReference == null || playlistReference.id() == null || playlistReference.id().isBlank()) {
            return null;
        }

        JsonApiData playlistData = includedByKey.get(resourceKey("playlists", playlistReference.id()));
        if (playlistData != null && playlistData.attributes() != null && !playlistData.attributes().isEmpty()) {
            return toPlaylistSummary(playlistData);
        }
        if (playlistReference.attributes() != null && !playlistReference.attributes().isEmpty()) {
            return toPlaylistSummary(playlistReference);
        }

        try {
            return getPlaylist(credential, playlistReference.id());
        } catch (RuntimeException exception) {
            log.warn("TIDAL playlist metadata was not available for collection playlist {}: {}",
                playlistReference.id(),
                exception.getMessage()
            );
            return null;
        }
    }


    private TidalPlaylistTrack toPlaylistTrack(
        JsonApiData item,
        Map<String, JsonApiData> includedByKey
    ) {
        String trackId = extractSingleRelationshipId(item.relationships(), "track");
        JsonApiData trackData = includedByKey.get(resourceKey("tracks", trackId));
        Map<String, Object> trackAttributes = trackData == null ? null : trackData.attributes();
        Map<String, Object> trackRelationships = trackData == null ? null : trackData.relationships();
        List<String> artistIds = extractRelationshipIds(trackRelationships, "artists");
        List<String> albumIds = extractRelationshipIds(trackRelationships, "albums");

        String artistName = artistIds.stream()
            .map(artistId -> includedByKey.get(resourceKey("artists", artistId)))
            .filter(Objects::nonNull)
            .map(JsonApiData::attributes)
            .map(attributes -> firstNonBlank(
                extractAttribute(attributes, "name", String.class),
                extractAttribute(attributes, "artistName", String.class)
            ))
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .reduce((left, right) -> left + ", " + right)
            .orElse("TIDAL Artist");

        JsonApiData albumData = albumIds.stream()
            .map(albumId -> includedByKey.get(resourceKey("albums", albumId)))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
        Map<String, Object> albumAttributes = albumData == null ? null : albumData.attributes();

        int durationSeconds = durationSeconds(trackAttributes);
        String albumImageId = firstNonBlank(
            extractAttribute(albumAttributes, "imageId", String.class),
            extractAttribute(albumAttributes, "cover", String.class),
            extractAttribute(albumAttributes, "coverImageId", String.class)
        );
        String externalUrl = firstNonBlank(
            extractAttribute(trackAttributes, "url", String.class),
            extractAttribute(trackAttributes, "shareUrl", String.class),
            extractAttribute(trackAttributes, "externalUrl", String.class),
            externalLink(trackAttributes)
        );
        String previewUrl = firstNonBlank(
            extractAttribute(trackAttributes, "previewUrl", String.class),
            extractAttribute(trackAttributes, "previewURL", String.class)
        );

        return new TidalPlaylistTrack(
            trackId,
            firstNonBlank(extractAttribute(trackAttributes, "title", String.class), "Unknown Track"),
            artistName,
            firstNonBlank(
                extractAttribute(albumAttributes, "title", String.class),
                extractAttribute(trackAttributes, "albumTitle", String.class),
                "TIDAL Album"
            ),
            buildImageUrl(albumImageId),
            externalUrl,
            trackId == null || trackId.isBlank() ? null : "tidal:track:%s".formatted(trackId),
            previewUrl,
            normalizeIsrc(extractAttribute(trackAttributes, "isrc", String.class)),
            durationSeconds * 1000
        );
    }

    private String buildImageUrl(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            return null;
        }
        if (imageId.contains("-")) {
            return "https://resources.tidal.com/images/%s/750x750.jpg".formatted(imageId.replace("-", "/"));
        }
        if (imageId.length() < 12) {
            return null;
        }
        // TIDAL image URL pattern (may need adjustment based on actual API)
        return "https://resources.tidal.com/images/%s/%s/%s/750x750.jpg".formatted(
            imageId.substring(0, 4),
            imageId.substring(4, 8),
            imageId.substring(8, 12)
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T extractAttribute(Map<String, Object> attributes, String key, Class<T> type) {
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        // Handle numeric conversion
        if (type == Integer.class && value instanceof Number) {
            return (T) Integer.valueOf(((Number) value).intValue());
        }
        if (type == String.class && value != null) {
            return (T) value.toString();
        }
        return null;
    }

    private <T> T extractAttribute(Map<String, Object> attributes, String key, Class<T> type, T defaultValue) {
        T value = extractAttribute(attributes, key, type);
        return value != null ? value : defaultValue;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://openapi.tidal.com/v2";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String legacyApiBaseUri() {
        return trimTrailingSlash(platformOAuthProperties.getTidal().getLegacyApiBaseUri())
            .replace("https://openapi.tidal.com/v2", "https://api.tidal.com/v1");
    }

    private java.util.stream.Stream<JsonNode> jsonItems(JsonNode body) {
        if (body == null) {
            return java.util.stream.Stream.empty();
        }
        JsonNode items = body.path("items");
        if (!items.isArray()) {
            items = body.path("data");
        }
        if (!items.isArray()) {
            return java.util.stream.Stream.empty();
        }
        return java.util.stream.StreamSupport.stream(items.spliterator(), false);
    }

    private TidalPlaylistSummary toLegacyPlaylistSummary(JsonNode item) {
        String id = firstNonBlank(text(item, "uuid"), text(item, "id"));
        if (id == null) {
            return null;
        }
        String title = firstNonBlank(text(item, "title"), text(item, "name"), "Untitled TIDAL Playlist");
        String imageId = firstNonBlank(text(item, "squareImage"), text(item, "imageId"), text(item, "image"));
        String externalUrl = firstNonBlank(
            text(item, "url"),
            text(item, "shareUrl"),
            "https://tidal.com/playlist/%s".formatted(id)
        );
        String curator = firstNonBlank(
            text(item.path("creator"), "name"),
            text(item.path("owner"), "name"),
            "TIDAL"
        );
        return new TidalPlaylistSummary(
            id,
            title,
            firstNonBlank(text(item, "description"), curator),
            firstInt(item, "numberOfTracks", "tracksCount", "trackCount"),
            imageId,
            buildImageUrl(imageId),
            externalUrl,
            id
        );
    }

    private TidalPlaylistTrack toLegacyTrack(JsonNode item) {
        String id = text(item, "id");
        if (id == null) {
            return null;
        }
        String albumImageId = firstNonBlank(
            text(item.path("album"), "cover"),
            text(item.path("album"), "imageId"),
            text(item, "imageId")
        );
        String externalUrl = firstNonBlank(
            text(item, "url"),
            text(item, "shareUrl"),
            "https://tidal.com/browse/track/%s".formatted(id)
        );
        int durationSeconds = firstInt(item, "duration");
        return new TidalPlaylistTrack(
            id,
            firstNonBlank(text(item, "title"), "Unknown Track"),
            firstNonBlank(
                text(item.path("artist"), "name"),
                text(item.path("artists").path(0), "name"),
                "TIDAL Artist"
            ),
            firstNonBlank(text(item.path("album"), "title"), text(item, "albumTitle"), "TIDAL Album"),
            buildImageUrl(albumImageId),
            externalUrl,
            "tidal:track:%s".formatted(id),
            firstNonBlank(text(item, "previewUrl"), text(item, "previewURL")),
            normalizeIsrc(text(item, "isrc")),
            durationSeconds <= 0 ? 0 : durationSeconds * 1000
        );
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private int firstInt(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText());
                } catch (NumberFormatException ignored) {
                    // Continue to the next field.
                }
            }
        }
        return 0;
    }

    private Map<String, JsonApiData> indexIncluded(List<JsonApiData> included) {
        Map<String, JsonApiData> indexed = new LinkedHashMap<>();
        if (included == null) {
            return indexed;
        }

        for (JsonApiData data : included) {
            if (data == null || data.type() == null || data.id() == null) {
                continue;
            }
            indexed.put(resourceKey(data.type(), data.id()), data);
        }
        return indexed;
    }

    private URI nextPageUri(Map<String, Object> links) {
        if (links == null) {
            return null;
        }
        Object next = links.get("next");
        if (next == null) {
            return null;
        }
        String href = null;
        if (next instanceof String value) {
            href = value;
        } else if (next instanceof Map<?, ?> nextMap) {
            Object nestedHref = nextMap.get("href");
            href = nestedHref == null ? null : nestedHref.toString();
        }
        if (href == null || href.isBlank()) {
            return null;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return URI.create(href);
        }
        return URI.create("%s%s".formatted(apiBaseUri, href.startsWith("/") ? href : "/" + href));
    }

    private int totalFromMeta(Map<String, Object> meta, int fallback) {
        if (meta == null) {
            return fallback;
        }
        return firstPositiveIntOrDefault(
            fallback,
            meta.get("total"),
            meta.get("totalNumberOfItems"),
            meta.get("total_number_of_items"),
            meta.get("totalItems"),
            meta.get("totalItemCount"),
            meta.get("count")
        );
    }

    private Integer totalFromJson(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return null;
        }
        return firstPositiveIntValue(
            body.path("total"),
            body.path("totalNumberOfItems"),
            body.path("total_number_of_items"),
            body.path("totalItems"),
            body.path("totalItemCount"),
            body.path("count"),
            body.path("meta").path("total"),
            body.path("meta").path("totalNumberOfItems"),
            body.path("meta").path("total_number_of_items"),
            body.path("paging").path("total"),
            body.path("paging").path("totalNumberOfItems")
        );
    }

    private int firstPositiveIntOrDefault(int fallback, Object... values) {
        Integer parsed = firstPositiveIntValue(values);
        return parsed == null ? fallback : parsed;
    }

    private Integer firstPositiveIntValue(Object... values) {
        for (Object value : values) {
            Integer parsed = asPositiveInt(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Integer asPositiveInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        if (value instanceof JsonNode node) {
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            if (node.isNumber()) {
                int parsed = node.asInt();
                return parsed > 0 ? parsed : null;
            }
            if (node.isTextual()) {
                return asPositiveInt(node.asText());
            }
            return null;
        }
        String textValue = value.toString();
        if (textValue == null || textValue.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(textValue);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resourceKey(String type, String id) {
        return type + ":" + id;
    }

    private String extractSingleRelationshipId(Map<String, Object> relationships, String relationshipName) {
        List<String> ids = extractRelationshipIds(relationships, relationshipName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<String> extractRelationshipIds(Map<String, Object> relationships, String relationshipName) {
        if (relationships == null) {
            return List.of();
        }
        Object relationship = relationships.get(relationshipName);
        if (!(relationship instanceof Map<?, ?> relationshipMap)) {
            return List.of();
        }
        Object data = relationshipMap.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object id = dataMap.get("id");
            return id == null ? List.of() : List.of(id.toString());
        }
        if (!(data instanceof List<?> dataList)) {
            return List.of();
        }
        return dataList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(map -> map.get("id"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .toList();
    }

    private String normalizeIsrc(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private int durationSeconds(Map<String, Object> attributes) {
        if (attributes == null) {
            return 0;
        }
        Object value = attributes.get("duration");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isBlank()) {
                return 0;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                // TIDAL OpenAPI track detail returns ISO-8601 durations like PT40S.
            }
            try {
                return Math.toIntExact(Duration.parse(trimmed).toSeconds());
            } catch (DateTimeParseException | ArithmeticException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String externalLink(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object links = attributes.get("externalLinks");
        if (!(links instanceof List<?> linkList)) {
            return null;
        }
        return linkList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(link -> link.get("href"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String imageLink(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object links = attributes.get("imageLinks");
        if (!(links instanceof List<?> linkList)) {
            return null;
        }
        return linkList.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(link -> link.get("href"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // JSON:API Response Records

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JsonApiRoot(
        @JsonProperty("data") JsonApiData data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JsonApiArrayRoot(
        @JsonProperty("data") List<JsonApiData> data,
        @JsonProperty("links") Map<String, Object> links,
        @JsonProperty("meta") Map<String, Object> meta
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JsonApiWithIncluded(
        @JsonProperty("data") JsonApiData data,
        @JsonProperty("included") List<JsonApiData> included
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JsonApiArrayWithIncluded(
        @JsonProperty("data") List<JsonApiData> data,
        @JsonProperty("included") List<JsonApiData> included,
        @JsonProperty("links") Map<String, Object> links,
        @JsonProperty("meta") Map<String, Object> meta
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JsonApiData(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("attributes") Map<String, Object> attributes,
        @JsonProperty("relationships") Map<String, Object> relationships
    ) {}

    // Public Data Models

    public record TidalUserProfile(
        String userId,
        String tidalUserId,
        String firstName,
        String lastName,
        String email
    ) {}

    public record TidalPlaylistSummary(
        String playlistId,
        String name,
        String description,
        int trackCount,
        String imageId,
        String coverImageUrl,
        String externalUrl,
        String uuid
    ) {}

    public record TidalPlaylistTrack(
        String tidalTrackId,
        String title,
        String artistName,
        String albumTitle,
        String albumImageUrl,
        String externalUrl,
        String tidalUri,
        String previewUrl,
        String isrc,
        int durationMs
    ) {}
}
