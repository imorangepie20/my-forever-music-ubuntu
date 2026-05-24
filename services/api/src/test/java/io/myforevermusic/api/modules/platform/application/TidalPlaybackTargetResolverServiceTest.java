package io.myforevermusic.api.modules.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.infrastructure.tidal.TidalWebApiClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TidalPlaybackTargetResolverServiceTest {

    @Test
    void shouldResolveBestTidalTargetByIsrc() {
        PlatformCredentialService credentialService = mock(PlatformCredentialService.class);
        TidalWebApiClient tidalWebApiClient = mock(TidalWebApiClient.class);
        TidalPlaybackTargetResolverService service = new TidalPlaybackTargetResolverService(
            credentialService,
            tidalWebApiClient
        );
        PlatformAccountCredential credential = credential();
        when(credentialService.resolveCredential("user-001", "tidal"))
            .thenReturn(PlatformCredentialResolution.ready(credential));
        when(tidalWebApiClient.searchTracks(credential, "Midnight Signal Neon Bloom", 10))
            .thenReturn(List.of(
                tidalTrack("tidal-metadata", "Midnight Signal", "Neon Bloom", "OTHERISRC", 218000),
                tidalTrack("tidal-isrc", "Midnight Signal - Remaster", "Neon Bloom", "USRC17607839", 223000)
            ));

        var target = service.resolve("user-001", new TidalPlaybackTargetResolverService.TrackQuery(
            "Midnight Signal",
            "Neon Bloom",
            "spotify",
            "spotify-track-001",
            "spotify:track:spotify-track-001",
            "spotify-track-001",
            "USRC17607839",
            218000
        ));

        assertThat(target.tidalTrackId()).isEqualTo("tidal-isrc");
        assertThat(target.matchReason()).isEqualTo("isrc");
        assertThat(target.matchScore()).isEqualTo(100);
        verify(tidalWebApiClient).searchTracks(credential, "Midnight Signal Neon Bloom", 10);
    }

    @Test
    void shouldResolveMetadataMatchWhenIsrcIsMissing() {
        PlatformCredentialService credentialService = mock(PlatformCredentialService.class);
        TidalWebApiClient tidalWebApiClient = mock(TidalWebApiClient.class);
        TidalPlaybackTargetResolverService service = new TidalPlaybackTargetResolverService(
            credentialService,
            tidalWebApiClient
        );
        PlatformAccountCredential credential = credential();
        when(credentialService.resolveCredential("user-001", "tidal"))
            .thenReturn(PlatformCredentialResolution.ready(credential));
        when(tidalWebApiClient.searchTracks(credential, "Quiet Index Mono District", 10))
            .thenReturn(List.of(tidalTrack(
                "tidal-metadata",
                "Quiet Index",
                "Mono District",
                null,
                221500
            )));

        var target = service.resolve("user-001", new TidalPlaybackTargetResolverService.TrackQuery(
            "Quiet Index",
            "Mono District",
            "spotify",
            "spotify-track-002",
            "spotify:track:spotify-track-002",
            "spotify-track-002",
            null,
            221000
        ));

        assertThat(target.tidalTrackId()).isEqualTo("tidal-metadata");
        assertThat(target.matchReason()).isEqualTo("metadata");
        assertThat(target.matchScore()).isEqualTo(100);
    }

    @Test
    void shouldFailWhenNoTidalCandidateMatches() {
        PlatformCredentialService credentialService = mock(PlatformCredentialService.class);
        TidalWebApiClient tidalWebApiClient = mock(TidalWebApiClient.class);
        TidalPlaybackTargetResolverService service = new TidalPlaybackTargetResolverService(
            credentialService,
            tidalWebApiClient
        );
        PlatformAccountCredential credential = credential();
        when(credentialService.resolveCredential("user-001", "tidal"))
            .thenReturn(PlatformCredentialResolution.ready(credential));
        when(tidalWebApiClient.searchTracks(credential, "Unknown Track Unknown Artist", 10))
            .thenReturn(List.of(tidalTrack("tidal-other", "Other Track", "Other Artist", null, 180000)));

        assertThatThrownBy(() -> service.resolve(
            "user-001",
            new TidalPlaybackTargetResolverService.TrackQuery(
                "Unknown Track",
                "Unknown Artist",
                "spotify",
                "spotify-track-404",
                "spotify:track:spotify-track-404",
                "spotify-track-404",
                null,
                240000
            )
        )).isInstanceOf(ApiResourceNotFoundException.class)
            .hasMessageContaining("No playable TIDAL match");
    }

    private PlatformAccountCredential credential() {
        return new PlatformAccountCredential(
            "user-001",
            "tidal",
            "tidal-oauth",
            "tidal-user-001",
            "Forever Listener TIDAL",
            "access-token",
            "refresh-token",
            "Bearer",
            "r_usr w_usr r_stream",
            Instant.now().plusSeconds(3600),
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        );
    }

    private TidalWebApiClient.TidalPlaylistTrack tidalTrack(
        String trackId,
        String title,
        String artistName,
        String isrc,
        int durationMs
    ) {
        return new TidalWebApiClient.TidalPlaylistTrack(
            trackId,
            title,
            artistName,
            "Album",
            null,
            "https://tidal.com/browse/track/" + trackId,
            "tidal:track:" + trackId,
            null,
            isrc,
            durationMs
        );
    }
}
