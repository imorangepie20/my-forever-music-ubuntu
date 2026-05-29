package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationPlaylistStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JpaPublicCurationPlaylistStoreTest {

    @Test
    void shouldSavePlaylistDraftWithTracksAndRun() {
        PublicCurationPlaylistRepository playlistRepository = mock(PublicCurationPlaylistRepository.class);
        PublicCurationPlaylistTrackRepository trackRepository = mock(PublicCurationPlaylistTrackRepository.class);
        PublicCurationRunRepository runRepository = mock(PublicCurationRunRepository.class);
        JpaPublicCurationPlaylistStore store = new JpaPublicCurationPlaylistStore(
            playlistRepository,
            trackRepository,
            runRepository
        );

        when(playlistRepository.save(any(PublicCurationPlaylistEntity.class))).thenAnswer(invocation -> {
            PublicCurationPlaylistEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "playlistId", 10L);
            return entity;
        });
        when(trackRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(PublicCurationRunEntity.class))).thenAnswer(invocation -> {
            PublicCurationRunEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "runId", 20L);
            return entity;
        });

        PublicCurationPlaylistStore.StoredPlaylist stored = store.createDraft(draft());

        assertThat(stored.playlistId()).isEqualTo(10L);
        assertThat(stored.status()).isEqualTo("draft");
        assertThat(stored.slug()).isEqualTo("rainy-jazz-night");
        assertThat(stored.tracks()).hasSize(2);
        assertThat(stored.tracks().getFirst().trackOrder()).isEqualTo(1);
        assertThat(stored.tracks().getFirst().title()).isEqualTo("Rain Theme");
        assertThat(stored.run()).isNotNull();
        assertThat(stored.run().runId()).isEqualTo(20L);
        assertThat(stored.run().candidateCount()).isEqualTo(120);
    }

    private PublicCurationPlaylistStore.CreateDraft draft() {
        Instant now = Instant.parse("2026-05-30T00:00:00Z");
        return new PublicCurationPlaylistStore.CreateDraft(
            "rainy-jazz-night",
            "비 오는 밤의 재즈",
            "조용한 밤을 위한 30곡",
            "카페에 공유하기 좋은 한국 인디와 재즈 감성.",
            "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
            "{\"targetTrackCount\":30}",
            "poster-dark",
            "public-curation-v1",
            2,
            390_000L,
            "admin-001",
            now,
            List.of(
                new PublicCurationPlaylistStore.TrackDraft(
                    1,
                    "ems_collected_track",
                    "ems-track-001",
                    "Rain Theme",
                    "Blue Artist",
                    "Night Album",
                    "https://img.example/1.jpg",
                    180_000,
                    "KRA000000001",
                    "10001",
                    "tidal:track:10001",
                    "https://tidal.com/browse/track/10001",
                    0.94,
                    "{\"theme_fit\":0.9}",
                    "비 오는 밤의 첫 분위기를 부드럽게 잡아준다."
                ),
                new PublicCurationPlaylistStore.TrackDraft(
                    2,
                    "pms_user_track",
                    "pms-track-002",
                    "Late Window",
                    "Gray Artist",
                    "Window Album",
                    null,
                    210_000,
                    "KRA000000002",
                    "10002",
                    "tidal:track:10002",
                    "https://tidal.com/browse/track/10002",
                    0.91,
                    "{\"coherence\":0.88}",
                    "첫 곡의 잔향을 이어받아 흐름을 안정시킨다."
                )
            ),
            new PublicCurationPlaylistStore.RunDraft(
                "비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성",
                "{\"targetTrackCount\":30}",
                120,
                2,
                "public-curation-v1",
                "completed",
                "{\"averageScore\":0.925}",
                null,
                now,
                now
            )
        );
    }
}
