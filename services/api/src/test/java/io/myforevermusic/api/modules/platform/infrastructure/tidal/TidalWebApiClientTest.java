package io.myforevermusic.api.modules.platform.infrastructure.tidal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformOAuthProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TidalWebApiClientTest {

    @Test
    void shouldFetchUserCollectionPlaylistRelationshipWithIncludedPlaylistMetadata() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/users/tidal-user-001/playlists", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/v2/userCollections/tidal-user-001/relationships/playlists", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("include=playlists")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "data": [
                    {
                      "id": "playlist-001",
                      "type": "playlists"
                    }
                  ],
                  "included": [
                    {
                      "id": "playlist-001",
                      "type": "playlists",
                      "attributes": {
                        "name": "My Untouched TIDAL Playlist",
                        "description": "Original user playlist.",
                        "numberOfItems": 42,
                        "imageLinks": [
                          { "href": "https://resources.tidal.com/images/playlist-001/750x750.jpg" }
                        ],
                        "externalLinks": [
                          { "href": "https://tidal.com/browse/playlist/playlist-001" }
                        ]
                      }
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            properties.getTidal().setLegacyApiBaseUri("http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            List<TidalWebApiClient.TidalPlaylistSummary> playlists = client.getUserPlaylists(
                tidalCredentialWithJwtUserId("tidal-user-001")
            );

            assertThat(playlists).hasSize(1);
            assertThat(playlists.getFirst().playlistId()).isEqualTo("playlist-001");
            assertThat(playlists.getFirst().name()).isEqualTo("My Untouched TIDAL Playlist");
            assertThat(playlists.getFirst().description()).isEqualTo("Original user playlist.");
            assertThat(playlists.getFirst().trackCount()).isEqualTo(42);
            assertThat(playlists.getFirst().coverImageUrl())
                .isEqualTo("https://resources.tidal.com/images/playlist-001/750x750.jpg");
            assertThat(playlists.getFirst().externalUrl()).isEqualTo("https://tidal.com/browse/playlist/playlist-001");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldListUserPlaylistsFromLegacyV1EndpointForDeviceScopedToken() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/users/tidal-user-001/playlists", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "limit": 50,
                  "offset": 0,
                  "totalNumberOfItems": 1,
                  "items": [
                    {
                      "uuid": "legacy-playlist-001",
                      "title": "My Legacy TIDAL Playlist",
                      "description": "Created on TIDAL.",
                      "numberOfTracks": 12,
                      "url": "https://tidal.com/playlist/legacy-playlist-001",
                      "creator": { "name": "Device User" }
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            properties.getTidal().setLegacyApiBaseUri("http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            List<TidalWebApiClient.TidalPlaylistSummary> playlists = client.getUserPlaylists(
                tidalCredentialWithJwtUserId("tidal-user-001")
            );

            assertThat(playlists).hasSize(1);
            assertThat(playlists.getFirst().playlistId()).isEqualTo("legacy-playlist-001");
            assertThat(playlists.getFirst().name()).isEqualTo("My Legacy TIDAL Playlist");
            assertThat(playlists.getFirst().trackCount()).isEqualTo(12);
            assertThat(playlists.getFirst().externalUrl())
                .isEqualTo("https://tidal.com/playlist/legacy-playlist-001");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseOpenApiTrackSearchMetaTotalForFullSearchCount() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("limit=50")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "data": [
                    {
                      "id": "track-001",
                      "type": "tracks",
                      "attributes": {
                        "title": "Meta Count Track",
                        "duration": 180
                      }
                    }
                  ],
                  "included": [],
                  "meta": {
                    "totalNumberOfItems": 123
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            TidalWebApiClient.TidalSearchResult<TidalWebApiClient.TidalPlaylistTrack> result = client.searchTrackResults(
                tidalCredential(),
                "jazz"
            );

            assertThat(result.items()).hasSize(1);
            assertThat(result.total()).isEqualTo(123);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFollowLegacyTrackSearchPagesForFullSearch() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/search", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/v1/search/tracks", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("limit=50")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            int offset = query.contains("offset=50") ? 50 : 0;
            int count = offset == 0 ? 50 : 1;
            byte[] response = legacyTrackSearchResponse(offset, count).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            properties.getTidal().setLegacyApiBaseUri("http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            List<TidalWebApiClient.TidalPlaylistTrack> tracks = client.searchTracks(
                tidalCredential(),
                "jazz"
            );

            assertThat(tracks).hasSize(51);
            assertThat(tracks.get(0).tidalTrackId()).isEqualTo("track-000");
            assertThat(tracks.get(50).tidalTrackId()).isEqualTo("track-050");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParsePlaylistTracksWithIncludedArtistsAlbumsAndIsrc() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/playlists/playlist-001/relationships/items", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "data": [
                    {
                      "id": "track-001",
                      "type": "tracks"
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v2/tracks/track-001", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("include=artists,albums")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "data": {
                    "id": "track-001",
                    "type": "tracks",
                    "attributes": {
                      "title": "Midnight Receiver",
                      "duration": 218,
                      "isrc": "USRC17607839",
                      "url": "https://tidal.com/browse/track/track-001",
                      "previewUrl": "https://cdn.tidal.com/preview/track-001.mp3"
                    },
                    "relationships": {
                      "artists": {
                        "data": [
                          {"id": "artist-001", "type": "artists"},
                          {"id": "artist-002", "type": "artists"}
                        ]
                      },
                      "albums": {
                        "data": [
                          {"id": "album-001", "type": "albums"}
                        ]
                      }
                    }
                  },
                  "included": [
                    {
                      "id": "artist-001",
                      "type": "artists",
                      "attributes": {
                        "name": "Neon Bloom"
                      }
                    },
                    {
                      "id": "artist-002",
                      "type": "artists",
                      "attributes": {
                        "name": "Aurora Lane"
                      }
                    },
                    {
                      "id": "album-001",
                      "type": "albums",
                      "attributes": {
                        "title": "Signal Bloom",
                        "imageId": "ab12cd34-ef56-7890-ab12-cd34ef567890"
                      }
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            List<TidalWebApiClient.TidalPlaylistTrack> tracks = client.getPlaylistTracks(
                tidalCredential(),
                "playlist-001"
            );

            assertThat(tracks).hasSize(1);
            assertThat(tracks.get(0).tidalTrackId()).isEqualTo("track-001");
            assertThat(tracks.get(0).title()).isEqualTo("Midnight Receiver");
            assertThat(tracks.get(0).artistName()).isEqualTo("Neon Bloom, Aurora Lane");
            assertThat(tracks.get(0).albumTitle()).isEqualTo("Signal Bloom");
            assertThat(tracks.get(0).externalUrl()).isEqualTo("https://tidal.com/browse/track/track-001");
            assertThat(tracks.get(0).previewUrl()).isEqualTo("https://cdn.tidal.com/preview/track-001.mp3");
            assertThat(tracks.get(0).tidalUri()).isEqualTo("tidal:track:track-001");
            assertThat(tracks.get(0).isrc()).isEqualTo("USRC17607839");
            assertThat(tracks.get(0).durationMs()).isEqualTo(218000);
            assertThat(tracks.get(0).albumImageUrl())
                .isEqualTo("https://resources.tidal.com/images/ab12cd34/ef56/7890/ab12/cd34ef567890/750x750.jpg");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchPlaylistMetadataById() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/playlists/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "data": {
                    "id": "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                    "type": "playlists",
                    "attributes": {
                      "name": "Night Drive Imports",
                      "description": "Public TIDAL playlist",
                      "numberOfItems": 24,
                      "imageId": "ab12cd34-ef56-7890-ab12-cd34ef567890",
                      "url": "https://tidal.com/playlist/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            TidalWebApiClient.TidalPlaylistSummary playlist = client.getPlaylist(
                tidalCredential(),
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
            );

            assertThat(playlist.playlistId()).isEqualTo("0a3d87d2-27dc-4edc-84b6-9f1eaa567f33");
            assertThat(playlist.name()).isEqualTo("Night Drive Imports");
            assertThat(playlist.description()).isEqualTo("Public TIDAL playlist");
            assertThat(playlist.trackCount()).isEqualTo(24);
            assertThat(playlist.externalUrl()).isEqualTo("https://tidal.com/playlist/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFetchPlaylistMetadataImageFromImageLinks() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/playlists/0a3d87d2-27dc-4edc-84b6-9f1eaa567f33", exchange -> {
            byte[] response = """
                {
                  "data": {
                    "id": "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33",
                    "type": "playlists",
                    "attributes": {
                      "name": "Night Drive Imports",
                      "numberOfItems": 24,
                      "imageLinks": [
                        { "href": "https://resources.tidal.com/images/ab12/cd34/ef56/7890/750x750.jpg" }
                      ]
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/vnd.api+json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            TidalWebApiClient.TidalPlaylistSummary playlist = client.getPlaylist(
                tidalCredential(),
                "0a3d87d2-27dc-4edc-84b6-9f1eaa567f33"
            );

            assertThat(playlist.coverImageUrl())
                .isEqualTo("https://resources.tidal.com/images/ab12/cd34/ef56/7890/750x750.jpg");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldParseHomePagePlaylistsFromTidalModuleSource() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/pages/home", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("deviceType=BROWSER")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "title": "Home",
                  "rows": [
                    {
                      "modules": [
                        {
                          "type": "PLAYLIST_LIST",
                          "title": "The Hits",
                          "showMore": {
                            "apiPath": "pages/single-module-page/home/7/hits/1"
                          }
                        }
                      ]
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/pages/single-module-page/home/7/hits/1", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query == null || !query.contains("countryCode=KR") || !query.contains("deviceType=BROWSER")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }

            byte[] response = """
                {
                  "title": "The Hits",
                  "rows": [
                    {
                      "modules": [
                        {
                          "type": "PLAYLIST_LIST",
                          "pagedList": {
                            "items": [
                              {
                                "uuid": "playlist-001",
                                "title": "TIDAL's Top Hits",
                                "description": "Editorial hits.",
                                "url": "https://tidal.com/browse/playlist/playlist-001",
                                "squareImage": "ab12cd34-ef56-7890-ab12-cd34ef567890",
                                "numberOfTracks": 100
                              }
                            ]
                          }
                        }
                      ]
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            PlatformOAuthProperties properties = new PlatformOAuthProperties();
            properties.getTidal().setCountryCode("KR");
            properties.getTidal().setApiBaseUri("http://127.0.0.1:%d/v2".formatted(server.getAddress().getPort()));
            properties.getTidal().setLegacyApiBaseUri("http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()));
            TidalWebApiClient client = new TidalWebApiClient(
                properties,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                properties.getTidal().getApiBaseUri()
            );

            List<TidalWebApiClient.TidalPlaylistSummary> playlists = client.getHomePagePlaylists(
                tidalCredential(),
                "THE_HITS",
                5
            );

            assertThat(playlists).hasSize(1);
            assertThat(playlists.get(0).playlistId()).isEqualTo("playlist-001");
            assertThat(playlists.get(0).name()).isEqualTo("TIDAL's Top Hits");
            assertThat(playlists.get(0).trackCount()).isEqualTo(100);
            assertThat(playlists.get(0).externalUrl()).isEqualTo("https://tidal.com/browse/playlist/playlist-001");
        } finally {
            server.stop(0);
        }
    }

    private PlatformAccountCredential tidalCredential() {
        return new PlatformAccountCredential(
            "user-001",
            "tidal",
            "tidal-pkce-draft",
            "tidal-user-001",
            "Forever Listener TIDAL account",
            "tidal-access-token",
            "tidal-refresh-token",
            "Bearer",
            "playlist-read-private",
            Instant.parse("2026-05-04T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        );
    }

    private PlatformAccountCredential tidalCredentialWithJwtUserId(String tidalUserId) {
        String accessToken = "eyJhbGciOiJub25lIn0.%s.signature".formatted(
            java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("{\"uid\":\"%s\",\"cc\":\"KR\"}".formatted(tidalUserId)).getBytes(StandardCharsets.UTF_8))
        );
        return new PlatformAccountCredential(
            "user-001",
            "tidal",
            "tidal-device-code",
            tidalUserId,
            "TIDAL %s".formatted(tidalUserId),
            accessToken,
            "tidal-refresh-token",
            "Bearer",
            "r_usr w_usr collection.read playlists.read",
            Instant.parse("2026-05-04T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        );
    }

    private static String legacyTrackSearchResponse(int start, int count) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < count; index++) {
            int trackNumber = start + index;
            if (index > 0) {
                items.append(",");
            }
            items.append("""
                {
                  "id": "track-%03d",
                  "title": "Legacy Track %03d",
                  "duration": 180,
                  "artist": {"name": "TIDAL Artist"},
                  "album": {"title": "TIDAL Album"}
                }
                """.formatted(trackNumber, trackNumber));
        }
        return "{\"items\": [%s]}".formatted(items);
    }
}
