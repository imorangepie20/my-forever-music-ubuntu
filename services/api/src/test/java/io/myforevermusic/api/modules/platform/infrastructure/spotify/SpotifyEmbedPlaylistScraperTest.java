package io.myforevermusic.api.modules.platform.infrastructure.spotify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.platform.infrastructure.spotify.SpotifyWebApiClient.SpotifyPlaylistTrack;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpotifyEmbedPlaylistScraperTest {

    @Test
    void shouldParseEmbedPlaylistTracksFromNextData() {
        SpotifyEmbedPlaylistScraper scraper = new SpotifyEmbedPlaylistScraper(
            new ObjectMapper(),
            java.net.http.HttpClient.newHttpClient(),
            "https://open.spotify.com/embed/playlist"
        );
        String html = """
            <html>
              <body>
                <script id="__NEXT_DATA__" type="application/json">
                  {
                    "props": {
                      "pageProps": {
                        "state": {
                          "data": {
                            "entity": {
                              "trackList": [
                                {
                                  "uri": "spotify:track:7J1uxwnxfQLu4APicE5Rnj",
                                  "title": "Billie Jean",
                                  "subtitle": "Michael Jackson",
                                  "duration": 293802,
                                  "audioPreview": {
                                    "url": "https://p.scdn.co/mp3-preview/sample"
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                </script>
              </body>
            </html>
            """;

        List<SpotifyPlaylistTrack> tracks = scraper.parseTracks(html);

        assertThat(tracks).hasSize(1);
        SpotifyPlaylistTrack track = tracks.getFirst();
        assertThat(track.spotifyTrackId()).isEqualTo("7J1uxwnxfQLu4APicE5Rnj");
        assertThat(track.title()).isEqualTo("Billie Jean");
        assertThat(track.artistName()).isEqualTo("Michael Jackson");
        assertThat(track.spotifyUri()).isEqualTo("spotify:track:7J1uxwnxfQLu4APicE5Rnj");
        assertThat(track.previewUrl()).isEqualTo("https://p.scdn.co/mp3-preview/sample");
        assertThat(track.durationMs()).isEqualTo(293802);
    }

    @Test
    void shouldRejectEmbedPlaylistWithoutTracks() {
        SpotifyEmbedPlaylistScraper scraper = new SpotifyEmbedPlaylistScraper(
            new ObjectMapper(),
            java.net.http.HttpClient.newHttpClient(),
            "https://open.spotify.com/embed/playlist"
        );
        String html = """
            <script id="__NEXT_DATA__" type="application/json">
              {
                "props": {
                  "pageProps": {
                    "state": {
                      "data": {
                        "entity": {}
                      }
                    }
                  }
                }
              }
            </script>
            """;

        assertThatThrownBy(() -> scraper.parseTracks(html))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Spotify embed playlist response does not contain tracks.");
    }
}
