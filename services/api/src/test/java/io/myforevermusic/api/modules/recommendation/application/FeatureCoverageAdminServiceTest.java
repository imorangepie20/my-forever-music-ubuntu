package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionRunRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class FeatureCoverageAdminServiceTest {

    @Test
    void shouldSummarizeRecommendationFeatureCoverageForTargetUser() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        UserMusicEventStore eventStore = mock(UserMusicEventStore.class);
        RecommendationSnapshotStore snapshotStore = mock(RecommendationSnapshotStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        EmsAcquisitionRunRepository acquisitionRunRepository = mock(EmsAcquisitionRunRepository.class);
        AudioFeatureCompletionJobStore audioFeatureCompletionJobStore = mock(AudioFeatureCompletionJobStore.class);

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "spotify",
            "curator",
            null,
            null,
            null,
            null,
            Instant.parse("2026-05-14T00:00:00Z"),
            List.of(
                track("track-001", "spotify", "USRC17607839", "spotify-001", null, "native", completeFeatures()),
                track("track-002", "tidal", "GBAYE0601498", null, "tidal-002", "resolved", PmsTrackAudioFeatures.unresolved()),
                track("track-003", "soundcloud", null, null, null, "unresolved", null)
            )
        )));
        when(eventStore.countEventsByUserIdAfter("target-user", Instant.EPOCH)).thenReturn(7L);
        when(snapshotStore.findRecentByUserId("target-user", 1000)).thenReturn(List.of(snapshot(1L), snapshot(2L)));
        when(emsTrackRepository.summarizeFeatureCoverageBySourcePlatform(any(Instant.class))).thenReturn(List.of(
            new EmsCoverageRow("spotify", 10L, 8L, 1L, Instant.parse("2026-05-14T00:00:00Z"), 9L, 6L),
            new EmsCoverageRow("tidal", 5L, 2L, 0L, Instant.parse("2026-05-13T00:00:00Z"), 5L, 1L)
        ));
        when(emsTrackRepository.summarizeFeatureCoverageByAudioFeatureSource(any(Instant.class))).thenReturn(List.of(
            new EmsAudioFeatureSourceRow("reccobeats_lookup", 10L, 8L, 1L, Instant.parse("2026-05-14T00:00:00Z")),
            new EmsAudioFeatureSourceRow("lastfm_track_tag_inferred", 2L, 2L, 0L, Instant.parse("2026-05-12T00:00:00Z")),
            new EmsAudioFeatureSourceRow("unavailable", 3L, 0L, 0L, null)
        ));
        when(acquisitionRunRepository.findTop20ByOrderByStartedAtDesc()).thenReturn(List.of(
            acquisitionRun(20, 2, 8, 1),
            acquisitionRun(10, 1, 2, 0)
        ));
        when(audioFeatureCompletionJobStore.findRecent(null, 500)).thenReturn(List.of(
            completionJob(1L, "queued", null),
            completionJob(2L, "retry_wait", "ReccoBeats API request failed (429)"),
            completionJob(3L, "unresolved", "lastfm_tag_inferred_partial_audio_features"),
            completionJob(4L, "unresolved", "lastfm_tag_inferred_partial_audio_features"),
            completionJob(5L, "completed", null)
        ));

        FeatureCoverageAdminService.FeatureCoverageReport report = new FeatureCoverageAdminService(
            authAccountStore,
            pmsUserLibraryStore,
            eventStore,
            snapshotStore,
            Optional.of(emsTrackRepository),
            Optional.of(acquisitionRunRepository),
            Optional.of(audioFeatureCompletionJobStore),
            noDriftEvaluator()
        ).summarize("admin-user", "target-user");

        assertThat(report.status()).isEqualTo("ok");
        assertThat(report.targetUserId()).isEqualTo("target-user");
        assertThat(report.pmsLibrary().playlistCount()).isEqualTo(1);
        assertThat(report.pmsLibrary().trackCount()).isEqualTo(3);
        assertThat(report.pmsLibrary().audioFeatureFilledCount()).isEqualTo(1);
        assertThat(report.pmsLibrary().audioFeatureCoverageRatio()).isEqualTo(0.3333d);
        assertThat(report.pmsLibrary().audioFeatureSourceClasses())
            .extracting(
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::sourceClass,
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::trackCount,
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::audioFeatureFilledCount
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("provider_lookup", 1L, 1L),
                org.assertj.core.groups.Tuple.tuple("unresolved", 2L, 0L)
            );
        assertThat(report.pmsLibrary().isrcCount()).isEqualTo(2);
        assertThat(report.pmsLibrary().playbackTargetAvailableCount()).isEqualTo(2);
        assertThat(report.emsPool().trackCount()).isEqualTo(15);
        assertThat(report.emsPool().audioFeatureFilledCount()).isEqualTo(10);
        assertThat(report.emsPool().audioFeatureCoverageRatio()).isEqualTo(0.6667d);
        assertThat(report.emsPool().audioFeatureSourceClasses())
            .extracting(
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::sourceClass,
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::trackCount,
                FeatureCoverageAdminService.AudioFeatureSourceClassCoverage::audioFeatureFilledCount
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("provider_lookup", 10L, 8L),
                org.assertj.core.groups.Tuple.tuple("tag_inferred", 2L, 2L),
                org.assertj.core.groups.Tuple.tuple("unresolved", 3L, 0L)
            );
        assertThat(report.emsPool().staleAudioFeatureCount()).isEqualTo(1);
        assertThat(report.emsPool().staleAudioFeatureRatio()).isEqualTo(0.1d);
        assertThat(report.emsPool().latestAudioResolvedAt()).isEqualTo(Instant.parse("2026-05-14T00:00:00Z"));
        assertThat(report.emsPool().isrcCount()).isEqualTo(14);
        assertThat(report.emsPool().canonicalTrackCount()).isEqualTo(7);
        assertThat(report.emsPool().sources()).extracting(FeatureCoverageAdminService.EmsSourceCoverage::sourcePlatform)
            .containsExactly("spotify", "tidal");
        assertThat(report.emsAcquisition().recentRunCount()).isEqualTo(2);
        assertThat(report.emsAcquisition().skippedArticleCount()).isEqualTo(3);
        assertThat(report.emsAcquisition().skippedSeedCount()).isEqualTo(1);
        assertThat(report.emsAcquisition().skippedItemRatio()).isEqualTo(0.0976d);
        assertThat(report.audioFeatureCompletion().recentJobCount()).isEqualTo(5);
        assertThat(report.audioFeatureCompletion().statusCounts())
            .extracting(
                FeatureCoverageAdminService.AudioFeatureCompletionStatusCount::status,
                FeatureCoverageAdminService.AudioFeatureCompletionStatusCount::jobCount
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("queued", 1L),
                org.assertj.core.groups.Tuple.tuple("retry_wait", 1L),
                org.assertj.core.groups.Tuple.tuple("unresolved", 2L),
                org.assertj.core.groups.Tuple.tuple("completed", 1L)
            );
        assertThat(report.audioFeatureCompletion().topReasons())
            .extracting(
                FeatureCoverageAdminService.AudioFeatureCompletionReasonCount::reason,
                FeatureCoverageAdminService.AudioFeatureCompletionReasonCount::jobCount
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("lastfm_tag_inferred_partial_audio_features", 2L),
                org.assertj.core.groups.Tuple.tuple("ReccoBeats API request failed (429)", 1L)
            );
        assertThat(report.learningData().eventCount()).isEqualTo(7);
        assertThat(report.learningData().recentRecommendationSnapshotCount()).isEqualTo(2);
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void shouldReportDegradedCoverageWhenEmsRepositoryIsUnavailable() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        UserMusicEventStore eventStore = mock(UserMusicEventStore.class);
        RecommendationSnapshotStore snapshotStore = mock(RecommendationSnapshotStore.class);

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("admin-user")).thenReturn(List.of());
        when(eventStore.countEventsByUserIdAfter("admin-user", Instant.EPOCH)).thenReturn(0L);
        when(snapshotStore.findRecentByUserId("admin-user", 1000)).thenReturn(List.of());

        FeatureCoverageAdminService.FeatureCoverageReport report = new FeatureCoverageAdminService(
            authAccountStore,
            pmsUserLibraryStore,
            eventStore,
            snapshotStore,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            noDriftEvaluator()
        ).summarize("admin-user", null);

        assertThat(report.status()).isEqualTo("degraded");
        assertThat(report.emsPool().warnings()).hasSize(1);
        assertThat(report.emsAcquisition().warnings()).hasSize(1);
        assertThat(report.warnings())
            .containsAll(report.emsPool().warnings())
            .containsAll(report.emsAcquisition().warnings());
    }

    @Test
    void shouldRejectNonAdminUsers() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        when(authAccountStore.findByUserId("user-001")).thenReturn(Optional.of(userAccount("user-001")));

        FeatureCoverageAdminService service = new FeatureCoverageAdminService(
            authAccountStore,
            mock(PmsUserLibraryStore.class),
            mock(UserMusicEventStore.class),
            mock(RecommendationSnapshotStore.class),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            noDriftEvaluator()
        );

        assertThatThrownBy(() -> service.summarize("user-001", null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
    }

    private AuthRegisteredAccount adminAccount(String userId) {
        return account(userId, "jowoosungtidal@gmail.com");
    }

    private AuthRegisteredAccount userAccount(String userId) {
        return account(userId, "user@example.com");
    }

    private AuthRegisteredAccount account(String userId, String normalizedEmail) {
        return new AuthRegisteredAccount(
            userId,
            normalizedEmail,
            normalizedEmail,
            "Test User",
            "spotify",
            null,
            null,
            false,
            "complete",
            Instant.parse("2026-05-14T00:00:00Z"),
            Instant.parse("2026-05-14T00:00:00Z"),
            Instant.parse("2026-05-14T00:00:00Z")
        );
    }

    private PmsUserLibraryStore.LibraryTrackState track(
        String trackId,
        String sourcePlatform,
        String isrc,
        String spotifyTrackId,
        String tidalTrackId,
        String playbackTargetStatus,
        PmsTrackAudioFeatures audioFeatures
    ) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            "external-" + trackId,
            "Track " + trackId,
            "Artist",
            sourcePlatform,
            null,
            null,
            null,
            null,
            null,
            null,
            isrc,
            spotifyTrackId,
            null,
            tidalTrackId,
            null,
            null,
            playbackTargetStatus,
            1,
            false,
            audioFeatures
        );
    }

    private PmsTrackAudioFeatures completeFeatures() {
        return new PmsTrackAudioFeatures(
            "spotify-001",
            "reccobeats_spotify_track",
            true,
            null,
            null,
            "spotify:track:001",
            "audio_features",
            180000,
            5,
            1,
            4,
            0.12d,
            0.72d,
            0.81d,
            0.01d,
            0.08d,
            -6.0d,
            0.04d,
            120.0d,
            0.64d,
            Instant.parse("2026-05-14T00:00:00Z")
        );
    }

    private RecommendationSnapshotStore.StoredSnapshot snapshot(Long id) {
        return new RecommendationSnapshotStore.StoredSnapshot(
            id,
            "recommendation-001",
            "request-001",
            "target-user",
            "track-001",
            null,
            "Track",
            "Artist",
            "gms",
            "spotify",
            "gms-baseline-v1",
            null,
            0.8d,
            0.5d,
            0.9d,
            0.7d,
            0.1d,
            0.95d,
            id.intValue(),
            "reason",
            Instant.parse("2026-05-14T00:00:00Z")
        );
    }

    private AudioFeatureCompletionJobStore.StoredJob completionJob(Long id, String status, String lastError) {
        return new AudioFeatureCompletionJobStore.StoredJob(
            id,
            "pms_user_track",
            "track-" + id,
            "target-user",
            100,
            status,
            "pms_import",
            1,
            null,
            null,
            null,
            lastError,
            Instant.parse("2026-05-14T00:00:00Z").plusSeconds(id),
            Instant.parse("2026-05-14T00:00:00Z").plusSeconds(id)
        );
    }

    private DriftSignalEvaluator noDriftEvaluator() {
        DriftSignalEvaluator evaluator = new DriftSignalEvaluator();
        ReflectionTestUtils.setField(evaluator, "audioStaleMaxRatio", 1.0d);
        ReflectionTestUtils.setField(evaluator, "emsAcquisitionSkipMaxRatio", 1.0d);
        return evaluator;
    }

    private EmsAcquisitionRunEntity acquisitionRun(
        int articleCount,
        int skippedArticleCount,
        int seedCount,
        int skippedSeedCount
    ) {
        EmsAcquisitionRunEntity run = new EmsAcquisitionRunEntity(
            "manual",
            "admin-user",
            Instant.parse("2026-05-14T00:00:00Z")
        );
        run.updateProgress(
            1,
            articleCount,
            skippedArticleCount,
            0,
            seedCount,
            skippedSeedCount,
            0,
            0,
            0,
            Instant.parse("2026-05-14T00:00:00Z")
        );
        return run;
    }

    private record EmsCoverageRow(
        String sourcePlatform,
        Long trackCount,
        Long audioFeatureFilledCount,
        Long staleAudioFeatureCount,
        Instant latestAudioResolvedAt,
        Long isrcCount,
        Long canonicalTrackCount
    ) implements EmsCollectedTrackRepository.FeatureCoverageBySourcePlatform {

        @Override
        public String getSourcePlatform() {
            return sourcePlatform;
        }

        @Override
        public Long getTrackCount() {
            return trackCount;
        }

        @Override
        public Long getAudioFeatureFilledCount() {
            return audioFeatureFilledCount;
        }

        @Override
        public Long getStaleAudioFeatureCount() {
            return staleAudioFeatureCount;
        }

        @Override
        public Instant getLatestAudioResolvedAt() {
            return latestAudioResolvedAt;
        }

        @Override
        public Long getIsrcCount() {
            return isrcCount;
        }

        @Override
        public Long getCanonicalTrackCount() {
            return canonicalTrackCount;
        }
    }

    private record EmsAudioFeatureSourceRow(
        String audioFeatureSource,
        Long trackCount,
        Long audioFeatureFilledCount,
        Long staleAudioFeatureCount,
        Instant latestAudioResolvedAt
    ) implements EmsCollectedTrackRepository.FeatureCoverageByAudioFeatureSource {

        @Override
        public String getAudioFeatureSource() {
            return audioFeatureSource;
        }

        @Override
        public Long getTrackCount() {
            return trackCount;
        }

        @Override
        public Long getAudioFeatureFilledCount() {
            return audioFeatureFilledCount;
        }

        @Override
        public Long getStaleAudioFeatureCount() {
            return staleAudioFeatureCount;
        }

        @Override
        public Instant getLatestAudioResolvedAt() {
            return latestAudioResolvedAt;
        }
    }
}
