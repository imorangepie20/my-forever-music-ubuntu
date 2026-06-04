package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class JdbcPublicCurationCandidatePoolStoreTest {

    @Test
    void shouldBeConstructibleAsSpringComponentWithJdbcTemplateBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));
            context.register(JdbcPublicCurationCandidatePoolStore.class);

            context.refresh();

            assertThat(context.getBean(PublicCurationCandidatePoolStore.class))
                .isInstanceOf(JdbcPublicCurationCandidatePoolStore.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryTidalReadyCandidatesFromPmsAndEmsPools() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> capturedSql = new AtomicReference<>();
        AtomicReference<MapSqlParameterSource> capturedParams = new AtomicReference<>();
        JdbcPublicCurationCandidatePoolStore store = new JdbcPublicCurationCandidatePoolStore(jdbcTemplate);

        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                capturedSql.set(invocation.getArgument(0));
                capturedParams.set(invocation.getArgument(1));
                RowMapper<PublicCurationCandidatePoolStore.CandidateTrack> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(resultSet(), 0));
            });

        List<PublicCurationCandidatePoolStore.CandidateTrack> candidates = store.findCandidates(
            new PublicCurationCandidatePoolStore.CandidateQuery(25, true)
        );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().sourceScope()).isEqualTo("pms_user_track");
        assertThat(candidates.getFirst().sourceId()).isEqualTo("track-001");
        assertThat(candidates.getFirst().tidalTrackId()).isEqualTo("10001");
        assertThat(candidates.getFirst().playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
        assertThat(candidates.getFirst().imageUrl()).isEqualTo("https://images.example/rain-street.jpg");
        assertThat(candidates.getFirst().audioFeatures()).containsEntry("energy", 0.42d);
        assertThat(candidates.getFirst().audioFeatureSource()).isEqualTo("reccobeats");
        assertThat(candidates.getFirst().audioFeatureConfidence()).isEqualTo(0.88d);
        assertThat(candidates.getFirst().audioFeaturesFilled()).isTrue();
        assertThat(candidates.getFirst().sourcePlaylistSignals().playlistCount()).isEqualTo(2);
        assertThat(candidates.getFirst().sourcePlaylistSignals().titles()).containsExactly("Rain Cafe", "Night Walk");
        assertThat(candidates.getFirst().audienceResponse().playCompletedCount()).isEqualTo(4);
        assertThat(candidates.getFirst().freshness()).isNotNull();
        assertThat(capturedSql.get()).contains("pms_user_track");
        assertThat(capturedSql.get()).contains("ems_collected_track");
        assertThat(capturedSql.get()).contains("track_audio_feature_evidence");
        assertThat(capturedSql.get()).contains("ems_collected_playlist_track");
        assertThat(capturedSql.get()).contains("public_playlist_play_event");
        assertThat(capturedSql.get()).contains("tidal_track_id is not null");
        assertThat(capturedSql.get()).contains("track.source_platform = 'tidal'");
        assertThat(capturedSql.get()).contains("track.external_track_id is not null");
        assertThat(capturedSql.get()).contains("coalesce(audio_duration_ms, spotify_duration_ms) as duration_ms");
        assertThat(capturedSql.get()).contains("cast(track.ems_collected_track_id as varchar) as source_id");
        assertThat(capturedSql.get()).doesNotContain("cast(ems_collected_track_id as varchar) as source_id");
        assertThat(capturedSql.get()).contains("album_image_url as image_url");
        assertThat(capturedSql.get()).doesNotContain("::varchar");
        assertThat(capturedParams.get().getValue("limit")).isEqualTo(25);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryExpandedRawCandidatesWithoutTidalOnlyPredicates() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AtomicReference<String> capturedSql = new AtomicReference<>();
        JdbcPublicCurationCandidatePoolStore store = new JdbcPublicCurationCandidatePoolStore(jdbcTemplate);

        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
            .thenAnswer(invocation -> {
                capturedSql.set(invocation.getArgument(0));
                RowMapper<PublicCurationCandidatePoolStore.CandidateTrack> mapper = invocation.getArgument(2);
                return List.of(mapper.mapRow(resultSet(), 0));
            });

        List<PublicCurationCandidatePoolStore.CandidateTrack> candidates = store.findCandidates(
            new PublicCurationCandidatePoolStore.CandidateQuery(50, false)
        );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().playbackResolutionStatus()).isEqualTo(
            PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
        );
        assertThat(capturedSql.get()).contains("from pms_user_track");
        assertThat(capturedSql.get()).contains("from ems_collected_track track");
        assertThat(capturedSql.get()).contains("btrim(title) <> ''");
        assertThat(capturedSql.get()).contains("btrim(artist_name) <> ''");
        assertThat(capturedSql.get()).contains("btrim(track.title) <> ''");
        assertThat(capturedSql.get()).contains("btrim(track.artist_name) <> ''");
        assertThat(capturedSql.get()).doesNotContain("and tidal_track_id is not null and tidal_uri is not null");
        assertThat(capturedSql.get()).doesNotContain("and track.source_platform = 'tidal' and track.external_track_id is not null");
    }

    private ResultSet resultSet() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("source_scope")).thenReturn("pms_user_track");
        when(resultSet.getString("source_id")).thenReturn("track-001");
        when(resultSet.getString("title")).thenReturn("Rain Street");
        when(resultSet.getString("artist_name")).thenReturn("Blue Trio");
        when(resultSet.getString("album_title")).thenReturn("Night Walk");
        when(resultSet.getString("image_url")).thenReturn("https://images.example/rain-street.jpg");
        when(resultSet.getInt("duration_ms")).thenReturn(181000);
        when(resultSet.getString("isrc")).thenReturn("KRA000000001");
        when(resultSet.getString("source_platform")).thenReturn("tidal");
        when(resultSet.getString("tidal_track_id")).thenReturn("10001");
        when(resultSet.getString("tidal_uri")).thenReturn("tidal:track:10001");
        when(resultSet.getString("tidal_external_url")).thenReturn("https://tidal.com/browse/track/10001");
        when(resultSet.getDouble("audio_acousticness")).thenReturn(0.71d);
        when(resultSet.getDouble("audio_danceability")).thenReturn(0.48d);
        when(resultSet.getDouble("audio_energy")).thenReturn(0.42d);
        when(resultSet.getDouble("audio_tempo")).thenReturn(92d);
        when(resultSet.getDouble("audio_valence")).thenReturn(0.38d);
        when(resultSet.getString("audio_feature_source")).thenReturn("reccobeats");
        when(resultSet.getDouble("audio_feature_confidence")).thenReturn(0.88d);
        when(resultSet.getBoolean("audio_features_filled")).thenReturn(true);
        when(resultSet.getInt("source_playlist_count")).thenReturn(2);
        when(resultSet.getInt("max_followers_count")).thenReturn(1800);
        when(resultSet.getString("source_playlist_titles")).thenReturn("Rain Cafe||Night Walk");
        when(resultSet.getString("source_playlist_descriptions")).thenReturn("비 오는 밤||재즈 산책");
        when(resultSet.getString("source_playlist_curators")).thenReturn("editor-a||editor-b");
        when(resultSet.getString("source_playlist_collection_sources")).thenReturn("search_pool");
        when(resultSet.getString("source_playlist_search_queries")).thenReturn("rainy jazz");
        when(resultSet.getInt("play_started_count")).thenReturn(5);
        when(resultSet.getInt("play_completed_count")).thenReturn(4);
        when(resultSet.getInt("skip_count")).thenReturn(1);
        when(resultSet.getTimestamp("recency_at")).thenReturn(java.sql.Timestamp.from(java.time.Instant.now()));
        when(resultSet.getString("primary_genre")).thenReturn("jazz");
        return resultSet;
    }
}
