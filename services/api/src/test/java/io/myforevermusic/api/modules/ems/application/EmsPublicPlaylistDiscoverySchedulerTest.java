package io.myforevermusic.api.modules.ems.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.ems.application.EmsCollectionService.EmsCollectionSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmsPublicPlaylistDiscoverySchedulerTest {

    @Mock
    private EmsCollectionService emsCollectionService;

    @Test
    void shouldSkipDiscoveryWhenUserIdIsMissing() {
        EmsDiscoveryProperties properties = properties();
        properties.setUserId("");

        EmsPublicPlaylistDiscoveryScheduler scheduler = new EmsPublicPlaylistDiscoveryScheduler(
            emsCollectionService,
            properties,
            Optional.empty()
        );

        EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun run =
            scheduler.runNow(null, null, null, null);

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.collectedPlaylistCount()).isZero();
        assertThat(run.collectedTrackCount()).isZero();
        assertThat(scheduler.lastRun()).isEqualTo(run);
        verify(emsCollectionService).isCredentialFreeSource("tidal", "jazz");
        verify(emsCollectionService, never()).collectPublicPlaylistPool(any(), any(), any(), anyInt());
    }

    @Test
    void shouldRecordProviderFailuresWithoutHidingThem() {
        EmsDiscoveryProperties properties = properties();
        properties.setUserId("user-001");

        when(emsCollectionService.collectPublicPlaylistPool("user-001", "tidal", "jazz", 2))
            .thenReturn(new EmsCollectionSearchResult(
                "tidal",
                "jazz",
                1,
                24,
                Instant.parse("2026-05-10T01:00:00Z")
            ));
        when(emsCollectionService.collectPublicPlaylistPool("user-001", "spotify", "jazz", 2))
            .thenThrow(new IllegalStateException("Spotify credential expired"));

        EmsPublicPlaylistDiscoveryScheduler scheduler = new EmsPublicPlaylistDiscoveryScheduler(
            emsCollectionService,
            properties,
            Optional.empty()
        );

        EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun run =
            scheduler.runNow(null, List.of("tidal", "spotify"), List.of("jazz"), 2);

        assertThat(run.status()).isEqualTo("completed_with_failures");
        assertThat(run.collectedPlaylistCount()).isEqualTo(1);
        assertThat(run.collectedTrackCount()).isEqualTo(24);
        assertThat(run.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.platformId()).isEqualTo("spotify");
            assertThat(failure.query()).isEqualTo("jazz");
            assertThat(failure.message()).isEqualTo("Spotify credential expired");
        });
        assertThat(scheduler.lastRun()).isEqualTo(run);
        verify(emsCollectionService).collectPublicPlaylistPool("user-001", "tidal", "jazz", 2);
        verify(emsCollectionService).collectPublicPlaylistPool("user-001", "spotify", "jazz", 2);
    }

    @Test
    void shouldRouteNamespacedSourcesToMatchingPlatformOnly() {
        EmsDiscoveryProperties properties = properties();
        properties.setUserId("user-001");
        when(emsCollectionService.collectPublicPlaylistPool("user-001", "tidal", "THE_HITS", 2))
            .thenReturn(new EmsCollectionSearchResult(
                "tidal",
                "THE_HITS",
                1,
                10,
                Instant.parse("2026-05-10T01:00:00Z")
            ));
        when(emsCollectionService.collectPublicPlaylistPool("user-001", "spotify", "category:toplists", 2))
            .thenReturn(new EmsCollectionSearchResult(
                "spotify",
                "category:toplists",
                1,
                20,
                Instant.parse("2026-05-10T01:00:00Z")
            ));

        EmsPublicPlaylistDiscoveryScheduler scheduler = new EmsPublicPlaylistDiscoveryScheduler(
            emsCollectionService,
            properties,
            Optional.empty()
        );

        EmsPublicPlaylistDiscoveryScheduler.EmsPublicPlaylistDiscoveryRun run =
            scheduler.runNow(
                null,
                List.of("tidal", "spotify"),
                List.of("tidal:THE_HITS", "spotify:category:toplists"),
                2
            );

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.collectedPlaylistCount()).isEqualTo(2);
        assertThat(run.collectedTrackCount()).isEqualTo(30);
        verify(emsCollectionService).collectPublicPlaylistPool("user-001", "tidal", "THE_HITS", 2);
        verify(emsCollectionService).collectPublicPlaylistPool("user-001", "spotify", "category:toplists", 2);
    }

    private EmsDiscoveryProperties properties() {
        EmsDiscoveryProperties properties = new EmsDiscoveryProperties();
        properties.setPlatforms(List.of("tidal"));
        properties.setSeedQueries(List.of("jazz"));
        properties.setPerQueryLimit(2);
        return properties;
    }
}
