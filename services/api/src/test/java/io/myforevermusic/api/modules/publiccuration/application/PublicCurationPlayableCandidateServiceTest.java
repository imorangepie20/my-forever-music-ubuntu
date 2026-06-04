package io.myforevermusic.api.modules.publiccuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.common.error.ApiResourceNotFoundException;
import io.myforevermusic.api.modules.platform.application.TidalPlaybackTargetResolverService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class PublicCurationPlayableCandidateServiceTest {

    private final PublicCurationCandidatePoolStore store = mock(PublicCurationCandidatePoolStore.class);
    private final TidalPlaybackTargetResolverService tidalResolver = mock(TidalPlaybackTargetResolverService.class);
    private final PublicCurationPlayableCandidateService service = new PublicCurationPlayableCandidateService(
        store,
        tidalResolver
    );

    @Test
    void shouldPassNativeTidalCandidatesAndResolveMetadataCandidates() {
        when(store.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(nativeTidalCandidate(), unresolvedSpotifyCandidate()));
        when(tidalResolver.resolve(eq("admin-001"), any(TidalPlaybackTargetResolverService.TrackQuery.class)))
            .thenReturn(new TidalPlaybackTargetResolverService.TidalPlaybackTarget(
                "90002",
                "tidal:track:90002",
                "Soft Rain",
                "Blue Trio",
                "Resolved Album",
                "https://images.example/soft-rain.jpg",
                "https://tidal.com/browse/track/90002",
                null,
                "KRA000000002",
                180000,
                "metadata",
                85
            ));

        PublicCurationPlayableCandidateService.PreparedCandidateBatch batch = service.prepare(
            new PublicCurationPlayableCandidateService.PrepareCommand("admin-001", 2, 2)
        );

        ArgumentCaptor<PublicCurationCandidatePoolStore.CandidateQuery> queryCaptor =
            ArgumentCaptor.forClass(PublicCurationCandidatePoolStore.CandidateQuery.class);
        verify(store).findCandidates(queryCaptor.capture());
        assertThat(queryCaptor.getValue().limit()).isEqualTo(6);
        assertThat(queryCaptor.getValue().tidalReadyRequired()).isFalse();
        assertThat(batch.candidates()).hasSize(2);
        assertThat(batch.candidates().get(0).playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
        assertThat(batch.candidates().get(1).tidalTrackId()).isEqualTo("90002");
        assertThat(batch.candidates().get(1).playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_RESOLVED_TO_TIDAL
        );
        assertThat(batch.summary().rawCount()).isEqualTo(2);
        assertThat(batch.summary().nativeTidalCount()).isEqualTo(1);
        assertThat(batch.summary().resolvedCount()).isEqualTo(1);
        assertThat(batch.summary().playableCount()).isEqualTo(2);
        assertThat(batch.summary().excludedCount()).isEqualTo(0);
    }

    @Test
    void shouldExcludeResolveFailuresAndReturnConflictWhenPlayableCandidatesAreTooFew() {
        when(store.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(unresolvedSpotifyCandidate()));
        when(tidalResolver.resolve(eq("admin-001"), any(TidalPlaybackTargetResolverService.TrackQuery.class)))
            .thenThrow(new ApiResourceNotFoundException("No playable TIDAL match was found."));

        assertThatThrownBy(() -> service.prepare(
            new PublicCurationPlayableCandidateService.PrepareCommand("admin-001", 5, 2)
        ))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Not enough playable public curation candidates");
    }

    @Test
    void shouldBackfillWithNativeTidalCandidatesWhenMetadataResolutionIsTooSparse() {
        when(store.findCandidates(any(PublicCurationCandidatePoolStore.CandidateQuery.class)))
            .thenReturn(List.of(unresolvedSpotifyCandidate()))
            .thenReturn(List.of(nativeTidalCandidate()));
        when(tidalResolver.resolve(eq("admin-001"), any(TidalPlaybackTargetResolverService.TrackQuery.class)))
            .thenThrow(new ApiResourceNotFoundException("No playable TIDAL match was found."));

        PublicCurationPlayableCandidateService.PreparedCandidateBatch batch = service.prepare(
            new PublicCurationPlayableCandidateService.PrepareCommand("admin-001", 5, 1)
        );

        ArgumentCaptor<PublicCurationCandidatePoolStore.CandidateQuery> queryCaptor =
            ArgumentCaptor.forClass(PublicCurationCandidatePoolStore.CandidateQuery.class);
        verify(store, org.mockito.Mockito.times(2)).findCandidates(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues().get(0).tidalReadyRequired()).isFalse();
        assertThat(queryCaptor.getAllValues().get(1).tidalReadyRequired()).isTrue();
        assertThat(batch.candidates()).hasSize(1);
        assertThat(batch.candidates().getFirst().playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
        assertThat(batch.summary().nativeTidalCount()).isEqualTo(1);
        assertThat(batch.summary().resolveFailedCount()).isEqualTo(1);
        assertThat(batch.summary().playableCount()).isEqualTo(1);
    }

    private PublicCurationCandidatePoolStore.CandidateTrack nativeTidalCandidate() {
        return candidate("pms_user_track", "track-001", "tidal", "90001", "tidal:track:90001", "native_tidal");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack unresolvedSpotifyCandidate() {
        return candidate("ems_collected_track", "track-002", "spotify", null, null, "unresolved");
    }

    private PublicCurationCandidatePoolStore.CandidateTrack candidate(
        String sourceScope,
        String sourceId,
        String sourcePlatform,
        String tidalTrackId,
        String tidalUri,
        String playbackResolutionStatus
    ) {
        return new PublicCurationCandidatePoolStore.CandidateTrack(
            sourceScope,
            sourceId,
            "Soft Rain",
            "Blue Trio",
            "Night Walk",
            "https://images.example/source.jpg",
            181000,
            "KRA000000002",
            sourcePlatform,
            tidalTrackId,
            tidalUri,
            tidalTrackId == null ? null : "https://tidal.com/browse/track/" + tidalTrackId,
            Map.of("energy", 0.42d),
            "reccobeats",
            0.8d,
            true,
            List.of("jazz"),
            List.of(sourcePlatform),
            List.of("rainy jazz"),
            new PublicCurationCandidatePoolStore.SourcePlaylistSignals(
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
            ),
            new PublicCurationCandidatePoolStore.AudienceResponse(0, 0, 0),
            0.9d,
            playbackResolutionStatus
        );
    }
}
