package io.myforevermusic.api.modules.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsTrackAudioFeatures;
import io.myforevermusic.api.modules.pms.application.PmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

class AudioFeatureCompletionServiceTest {

    @Test
    void shouldEnqueueMissingPmsAndEmsTracksWithoutDuplicates() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();

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
            Instant.parse("2026-05-20T00:00:00Z"),
            List.of(
                pmsTrack("pms-track-complete", completePmsFeatures()),
                pmsTrack("pms-track-missing-001", PmsTrackAudioFeatures.unresolved()),
                pmsTrack("pms-track-missing-002", null)
            )
        )));
        when(emsTrackRepository.findAudioFeatureCompletionCandidates(Pageable.ofSize(10))).thenReturn(List.of(
            emsTrack(101L, "spotify", "sp-101", unavailableEmsFeatures()),
            emsTrack(102L, "tidal", "td-102", null)
        ));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore
        );

        AudioFeatureCompletionService.EnqueueCompletionResult first = service.enqueueMissingAudioFeatures(
            "admin-user",
            "target-user",
            "all",
            10,
            10
        );
        AudioFeatureCompletionService.EnqueueCompletionResult second = service.enqueueMissingAudioFeatures(
            "admin-user",
            "target-user",
            "all",
            10,
            10
        );

        assertThat(first.scannedTrackCount()).isEqualTo(5);
        assertThat(first.enqueuedJobCount()).isEqualTo(4);
        assertThat(first.skippedExistingJobCount()).isZero();
        assertThat(first.jobs())
            .extracting(AudioFeatureCompletionJobStore.StoredJob::trackScope, AudioFeatureCompletionJobStore.StoredJob::trackId)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("pms_user_track", "pms-track-missing-001"),
                org.assertj.core.groups.Tuple.tuple("pms_user_track", "pms-track-missing-002"),
                org.assertj.core.groups.Tuple.tuple("ems_collected_track", "101"),
                org.assertj.core.groups.Tuple.tuple("ems_collected_track", "102")
            );
        assertThat(second.scannedTrackCount()).isEqualTo(5);
        assertThat(second.enqueuedJobCount()).isZero();
        assertThat(second.skippedExistingJobCount()).isEqualTo(4);
        assertThat(jobStore.jobs()).hasSize(4);
    }

    @Test
    void shouldRejectNonAdminUsers() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        when(authAccountStore.findByUserId("user-001")).thenReturn(Optional.of(userAccount("user-001")));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            mock(PmsUserLibraryStore.class),
            Optional.empty(),
            new InMemoryJobStore()
        );

        assertThatThrownBy(() -> service.enqueueMissingAudioFeatures("user-001", null, "all", 10, 10))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void shouldEnqueueMissingPositiveEventPmsTracksForAudioTasteReadiness() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-21T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(
                pmsTrack("pms-track-positive-missing", PmsTrackAudioFeatures.unresolved()),
                pmsTrack("pms-track-positive-complete", completePmsFeatures()),
                pmsTrack("pms-track-negative-missing", PmsTrackAudioFeatures.unresolved()),
                pmsTrack("pms-track-no-event-missing", PmsTrackAudioFeatures.unresolved())
            )
        )));
        eventStore.add(event("target-user", "play_completed", null, "pms-track-positive-missing", now.plusSeconds(4)));
        eventStore.add(event("target-user", "track_saved", null, "pms-track-positive-complete", now.plusSeconds(3)));
        eventStore.add(event("target-user", "skip_next", null, "pms-track-negative-missing", now.plusSeconds(2)));
        eventStore.add(event("target-user", "play_completed", null, "pms-track-not-in-library", now.plusSeconds(1)));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.empty(),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            500,
            10
        );

        assertThat(result.targetUserId()).isEqualTo("target-user");
        assertThat(result.scope()).isEqualTo("pms_positive_events");
        assertThat(result.scannedTrackCount()).isEqualTo(3);
        assertThat(result.enqueuedJobCount()).isEqualTo(1);
        assertThat(result.skippedExistingJobCount()).isZero();
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.trackScope()).isEqualTo("pms_user_track");
            assertThat(job.trackId()).isEqualTo("pms-track-positive-missing");
            assertThat(job.requestedReason()).isEqualTo("positive_audio_taste_retry");
            assertThat(job.priority()).isEqualTo(130);
        });
    }

    @Test
    void shouldEnqueueMissingPositiveEventEmsTracksForAudioTasteReadiness() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-22T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
            emsTrack(318283L, "tidal", "194088149", unavailableEmsFeatures())
        ));
        eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "ems",
            500,
            10
        );

        assertThat(result.targetUserId()).isEqualTo("target-user");
        assertThat(result.scope()).isEqualTo("ems_positive_events");
        assertThat(result.scannedTrackCount()).isEqualTo(1);
        assertThat(result.enqueuedJobCount()).isEqualTo(1);
        assertThat(result.skippedExistingJobCount()).isZero();
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.trackScope()).isEqualTo("ems_collected_track");
            assertThat(job.trackId()).isEqualTo("318283");
            assertThat(job.userId()).isNull();
            assertThat(job.requestedReason()).isEqualTo("positive_audio_taste_retry");
            assertThat(job.priority()).isEqualTo(130);
        });
    }

    @Test
    void shouldUsePositiveEventItemIdForEmsWhenTrackIdIsMissing() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-22T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
            emsTrack(318279L, "tidal", "189033560", unavailableEmsFeatures())
        ));
        eventStore.add(emsEvent("target-user", "play_completed", 1.0d, null, "ems-track:318279", now));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "ems",
            500,
            10
        );

        assertThat(result.enqueuedJobCount()).isEqualTo(1);
        assertThat(result.jobs()).singleElement().satisfies(job ->
            assertThat(job.trackId()).isEqualTo("318279")
        );
    }

    @Test
    void shouldIgnoreMalformedAndNegativeEmsPositiveEventReferences() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-22T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
        eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:not-a-number", "ems-track:not-a-number", now));
        eventStore.add(emsEvent("target-user", "skip_next", -0.25d, "ems-track:318283", "ems-track:318283", now.plusSeconds(1)));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "ems",
            500,
            10
        );

        assertThat(result.scannedTrackCount()).isZero();
        assertThat(result.enqueuedJobCount()).isZero();
        assertThat(result.jobs()).isEmpty();
        verifyNoInteractions(emsTrackRepository);
    }

    @Test
    void shouldSkipCompleteEmsPositiveEventTracks() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-22T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of());
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
            emsTrack(318283L, "tidal", "194088149", completeEmsFeatures())
        ));
        eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult result = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "ems",
            500,
            10
        );

        assertThat(result.scannedTrackCount()).isEqualTo(1);
        assertThat(result.enqueuedJobCount()).isZero();
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void shouldRespectPositiveEventTrackScopeSelection() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        EmsCollectedTrackRepository emsTrackRepository = mock(EmsCollectedTrackRepository.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemoryEventStore eventStore = new InMemoryEventStore();
        Instant now = Instant.parse("2026-05-22T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(pmsTrack("pms-track-positive-missing", PmsTrackAudioFeatures.unresolved()))
        )));
        when(emsTrackRepository.findAllById(any())).thenReturn(List.of(
            emsTrack(318283L, "tidal", "194088149", unavailableEmsFeatures())
        ));
        eventStore.add(event("target-user", "play_completed", 1.0d, "pms-track-positive-missing", now.plusSeconds(1)));
        eventStore.add(emsEvent("target-user", "play_completed", 1.0d, "ems-track:318283", "ems-track:318283", now));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.of(emsTrackRepository),
            jobStore,
            Optional.empty(),
            Optional.of(eventStore),
            new EventSignalWeights()
        );

        AudioFeatureCompletionService.EnqueueCompletionResult pmsOnly = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "pms",
            500,
            10
        );
        AudioFeatureCompletionService.EnqueueCompletionResult emsOnly = service.enqueuePositiveEventAudioFeatures(
            "admin-user",
            "target-user",
            "ems",
            500,
            10
        );

        assertThat(pmsOnly.scope()).isEqualTo("pms_positive_events");
        assertThat(pmsOnly.scannedTrackCount()).isEqualTo(1);
        assertThat(pmsOnly.jobs()).singleElement().satisfies(job ->
            assertThat(job.trackScope()).isEqualTo("pms_user_track")
        );
        assertThat(emsOnly.scope()).isEqualTo("ems_positive_events");
        assertThat(emsOnly.scannedTrackCount()).isEqualTo(1);
        assertThat(emsOnly.jobs()).singleElement().satisfies(job ->
            assertThat(job.trackScope()).isEqualTo("ems_collected_track")
        );
    }

    @Test
    void shouldRequeueUnresolvedPmsJobsForManualLlmRetry() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        Instant now = Instant.parse("2026-05-21T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(
                pmsTrack("pms-track-unresolved", PmsTrackAudioFeatures.unresolved()),
                pmsTrack("pms-track-complete", completePmsFeatures())
            )
        )));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-unresolved",
            "target-user",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            now,
            now
        ));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            2L,
            "pms_user_track",
            "pms-track-complete",
            "target-user",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            now,
            now
        ));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.empty(),
            jobStore
        );

        AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
            "admin-user",
            "target-user",
            "pms_user_track",
            "reccobeats_no_match",
            "manual_llm_retry",
            50
        );

        assertThat(result.scannedJobCount()).isEqualTo(2);
        assertThat(result.requeuedJobCount()).isEqualTo(1);
        assertThat(result.skippedExistingJobCount()).isZero();
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.trackId()).isEqualTo("pms-track-unresolved");
            assertThat(job.status()).isEqualTo("queued");
            assertThat(job.requestedReason()).isEqualTo("manual_llm_retry");
            assertThat(job.priority()).isEqualTo(110);
        });
    }

    @Test
    void shouldNotDuplicateExistingManualLlmRetryJobs() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        Instant now = Instant.parse("2026-05-21T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(pmsTrack("pms-track-unresolved", PmsTrackAudioFeatures.unresolved()))
        )));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-unresolved",
            "target-user",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            now,
            now
        ));
        jobStore.enqueueIfAbsent(new AudioFeatureCompletionJobStore.Draft(
            "pms_user_track",
            "pms-track-unresolved",
            "target-user",
            110,
            "manual_llm_retry",
            now
        ));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.empty(),
            jobStore
        );

        AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
            "admin-user",
            "target-user",
            "pms_user_track",
            "reccobeats_no_match",
            "manual_llm_retry",
            50
        );

        assertThat(result.scannedJobCount()).isEqualTo(1);
        assertThat(result.requeuedJobCount()).isZero();
        assertThat(result.skippedExistingJobCount()).isEqualTo(1);
    }

    @Test
    void shouldCheckPmsCompletenessPerJobUserWhenRequeueingAllUsers() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        Instant now = Instant.parse("2026-05-21T00:00:00Z");

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        when(pmsUserLibraryStore.findPlaylists("user-a")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "user-a",
            "playlist-a",
            "external-playlist-a",
            "Complete Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(pmsTrack("pms-track-complete", completePmsFeatures()))
        )));
        when(pmsUserLibraryStore.findPlaylists("user-b")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "user-b",
            "playlist-b",
            "external-playlist-b",
            "Missing Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            now,
            List.of(pmsTrack("pms-track-missing", PmsTrackAudioFeatures.unresolved()))
        )));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-complete",
            "user-a",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            now,
            now
        ));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            2L,
            "pms_user_track",
            "pms-track-missing",
            "user-b",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            now,
            now
        ));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.empty(),
            jobStore
        );

        AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
            "admin-user",
            null,
            "pms_user_track",
            "reccobeats_no_match",
            "manual_llm_retry",
            50
        );

        assertThat(result.scannedJobCount()).isEqualTo(2);
        assertThat(result.requeuedJobCount()).isEqualTo(1);
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.userId()).isEqualTo("user-b");
            assertThat(job.trackId()).isEqualTo("pms-track-missing");
        });
    }

    @Test
    void shouldReachEligibleJobsWhenLimitedPageContainsCompletedTracks() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        PmsUserLibraryStore pmsUserLibraryStore = mock(PmsUserLibraryStore.class);
        InMemoryJobStore jobStore = new InMemoryJobStore();
        Instant older = Instant.parse("2026-05-21T00:00:00Z");
        Instant newer = Instant.parse("2026-05-21T00:01:00Z");
        List<PmsUserLibraryStore.LibraryTrackState> tracks = new ArrayList<>();
        tracks.add(pmsTrack("pms-track-missing", PmsTrackAudioFeatures.unresolved()));

        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount("admin-user")));
        for (int index = 1; index <= 55; index++) {
            String trackId = "pms-track-complete-" + index;
            tracks.add(pmsTrack(trackId, completePmsFeatures()));
            Instant updatedAt = newer.plusSeconds(index);
            jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
                index + 1L,
                "pms_user_track",
                trackId,
                "target-user",
                100,
                "unresolved",
                "pms_import",
                1,
                null,
                null,
                null,
                "reccobeats_no_match",
                updatedAt,
                updatedAt
            ));
        }
        when(pmsUserLibraryStore.findPlaylists("target-user")).thenReturn(List.of(new PmsUserLibraryStore.LibraryPlaylistState(
            "target-user",
            "playlist-001",
            "external-playlist-001",
            "Target Playlist",
            "tidal",
            "curator",
            null,
            null,
            null,
            null,
            newer,
            tracks
        )));
        jobStore.addExisting(new AudioFeatureCompletionJobStore.StoredJob(
            1L,
            "pms_user_track",
            "pms-track-missing",
            "target-user",
            100,
            "unresolved",
            "pms_import",
            1,
            null,
            null,
            null,
            "reccobeats_no_match",
            older,
            older
        ));

        AudioFeatureCompletionService service = new AudioFeatureCompletionService(
            authAccountStore,
            pmsUserLibraryStore,
            Optional.empty(),
            jobStore
        );

        AudioFeatureCompletionService.RequeueUnresolvedResult result = service.requeueUnresolvedJobs(
            "admin-user",
            "target-user",
            "pms_user_track",
            "reccobeats_no_match",
            "manual_llm_retry",
            1
        );

        assertThat(result.requeuedJobCount()).isEqualTo(1);
        assertThat(result.jobs()).singleElement().satisfies(job -> assertThat(job.trackId()).isEqualTo("pms-track-missing"));
    }

    private PmsUserLibraryStore.LibraryTrackState pmsTrack(String trackId, PmsTrackAudioFeatures audioFeatures) {
        return new PmsUserLibraryStore.LibraryTrackState(
            trackId,
            "external-" + trackId,
            "Track " + trackId,
            "Artist",
            "spotify",
            null,
            null,
            null,
            null,
            null,
            null,
            "USRC17607839",
            trackId,
            null,
            null,
            null,
            null,
            "native",
            1,
            false,
            audioFeatures
        );
    }

    private PmsTrackAudioFeatures completePmsFeatures() {
        return new PmsTrackAudioFeatures(
            "sp-complete",
            "reccobeats_lookup",
            true,
            null,
            null,
            "spotify:track:complete",
            "audio_features",
            180000,
            1,
            1,
            4,
            0.1d,
            0.7d,
            0.8d,
            0.0d,
            0.1d,
            -6.0d,
            0.04d,
            120.0d,
            0.6d,
            Instant.parse("2026-05-20T00:00:00Z")
        );
    }

    private EmsCollectedTrackEntity emsTrack(Long id, String sourcePlatform, String externalTrackId, EmsTrackAudioFeatures audioFeatures) {
        EmsCollectedTrackEntity track = new EmsCollectedTrackEntity(
            externalTrackId,
            "Track " + externalTrackId,
            "Artist",
            sourcePlatform,
            "USRC17607839",
            null,
            null,
            null,
            null,
            null,
            180000,
            "search_pool",
            Instant.parse("2026-05-20T00:00:00Z"),
            audioFeatures
        );
        org.springframework.test.util.ReflectionTestUtils.setField(track, "id", id);
        return track;
    }

    private EmsTrackAudioFeatures unavailableEmsFeatures() {
        return new EmsTrackAudioFeatures(
            null,
            "unavailable",
            false,
            null,
            null,
            null,
            "audio_features",
            180000,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private EmsTrackAudioFeatures completeEmsFeatures() {
        return new EmsTrackAudioFeatures(
            "tidal-complete",
            "reccobeats_isrc_match",
            true,
            null,
            null,
            null,
            "audio_features",
            180000,
            1,
            1,
            4,
            0.1d,
            0.7d,
            0.8d,
            0.0d,
            0.1d,
            -6.0d,
            0.04d,
            120.0d,
            0.6d,
            Instant.parse("2026-05-20T00:00:00Z")
        );
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
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-20T00:00:00Z")
        );
    }

    private UserMusicEventStore.StoredEvent event(
        String userId,
        String eventType,
        Double eventWeight,
        String trackId,
        Instant occurredAt
    ) {
        return new UserMusicEventStore.StoredEvent(
            null,
            userId,
            eventType,
            eventWeight,
            "pms",
            "tidal",
            null,
            trackId,
            "track",
            trackId,
            null,
            null,
            null,
            "Track " + trackId,
            "Artist",
            null,
            null,
            180000,
            null,
            null,
            null,
            1.0d,
            occurredAt,
            occurredAt
        );
    }

    private UserMusicEventStore.StoredEvent emsEvent(
        String userId,
        String eventType,
        Double eventWeight,
        String trackId,
        String itemId,
        Instant occurredAt
    ) {
        return new UserMusicEventStore.StoredEvent(
            null,
            userId,
            eventType,
            eventWeight,
            "player",
            "tidal",
            "tidal",
            itemId,
            "track",
            trackId,
            null,
            trackId == null ? null : trackId.substring("ems-track:".length()),
            itemId == null ? null : "tidal:track:" + itemId.substring("ems-track:".length()),
            "EMS Track",
            "EMS Artist",
            "EMS Album",
            null,
            180000,
            180000,
            1.0d,
            null,
            1.0d,
            occurredAt,
            occurredAt
        );
    }

    private static class InMemoryJobStore implements AudioFeatureCompletionJobStore {
        private final Map<String, StoredJob> jobs = new LinkedHashMap<>();
        private long sequence = 1L;

        void addExisting(StoredJob job) {
            jobs.put(job.trackScope() + ":" + job.trackId() + ":" + job.requestedReason(), job);
            sequence = Math.max(sequence, job.jobId() + 1);
        }

        @Override
        public EnqueueOutcome enqueueIfAbsent(Draft draft) {
            String key = draft.trackScope() + ":" + draft.trackId() + ":" + draft.requestedReason();
            StoredJob existing = jobs.get(key);
            if (existing != null) {
                return new EnqueueOutcome(existing, false);
            }
            StoredJob inserted = new StoredJob(
                sequence++,
                draft.trackScope(),
                draft.trackId(),
                draft.userId(),
                draft.priority(),
                "queued",
                draft.requestedReason(),
                0,
                null,
                null,
                null,
                null,
                draft.createdAt(),
                draft.createdAt()
            );
            jobs.put(key, inserted);
            return new EnqueueOutcome(inserted, true);
        }

        @Override
        public List<StoredJob> findRecent(String status, int limit) {
            return jobs.values().stream()
                .filter(job -> status == null || status.equals(job.status()))
                .limit(limit)
                .toList();
        }

        @Override
        public List<StoredJob> findUnresolvedForRequeue(String trackScope, String userId, String lastError, int limit) {
            return findUnresolvedForRequeue(trackScope, userId, lastError, null, null, limit);
        }

        @Override
        public List<StoredJob> findUnresolvedForRequeue(
            String trackScope,
            String userId,
            String lastError,
            Instant beforeUpdatedAt,
            Long beforeJobId,
            int limit
        ) {
            return jobs.values().stream()
                .filter(job -> "unresolved".equals(job.status()))
                .filter(job -> trackScope == null || trackScope.equals(job.trackScope()))
                .filter(job -> userId == null || userId.equals(job.userId()))
                .filter(job -> lastError == null || lastError.equals(job.lastError()))
                .filter(job -> isBeforeCursor(job, beforeUpdatedAt, beforeJobId))
                .sorted(java.util.Comparator.comparing(StoredJob::updatedAt).reversed()
                    .thenComparing(StoredJob::jobId, java.util.Comparator.reverseOrder()))
                .limit(limit)
                .toList();
        }

        private boolean isBeforeCursor(StoredJob job, Instant beforeUpdatedAt, Long beforeJobId) {
            if (beforeUpdatedAt == null) {
                return true;
            }
            if (job.updatedAt().isBefore(beforeUpdatedAt)) {
                return true;
            }
            return job.updatedAt().equals(beforeUpdatedAt)
                && beforeJobId != null
                && job.jobId() < beforeJobId;
        }

        @Override
        public List<StoredJob> claimQueued(String workerId, int limit, Instant now) {
            return List.of();
        }

        @Override
        public void markCompleted(Long jobId, Instant now) {
        }

        @Override
        public void markRetryWait(Long jobId, String lastError, Instant nextRetryAt, Instant now) {
        }

        @Override
        public void markUnresolved(Long jobId, String lastError, Instant now) {
        }

        @Override
        public void markFailed(Long jobId, String lastError, Instant now) {
        }

        List<StoredJob> jobs() {
            return new ArrayList<>(jobs.values());
        }
    }

    private static class InMemoryEventStore implements UserMusicEventStore {
        private final List<StoredEvent> events = new ArrayList<>();

        void add(StoredEvent event) {
            events.add(event);
        }

        @Override
        public StoredEvent save(EventDraft draft) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public List<StoredEvent> findRecentByUserId(String userId, int limit) {
            return events.stream()
                .filter(event -> userId.equals(event.userId()))
                .sorted(java.util.Comparator.comparing(StoredEvent::occurredAt).reversed())
                .limit(limit)
                .toList();
        }

        @Override
        public List<String> findActiveUserIds(Instant since, int limit) {
            return List.of();
        }

        @Override
        public long countEventsByUserIdAfter(String userId, Instant since) {
            return 0;
        }
    }
}
