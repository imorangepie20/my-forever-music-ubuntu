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

class JdbcPublicCurationCandidatePoolStoreTest {

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
        assertThat(candidates.getFirst().audioFeatures()).containsEntry("energy", 0.42d);
        assertThat(capturedSql.get()).contains("pms_user_track");
        assertThat(capturedSql.get()).contains("ems_collected_track");
        assertThat(capturedSql.get()).contains("tidal_track_id is not null");
        assertThat(capturedParams.get().getValue("limit")).isEqualTo(25);
    }

    private ResultSet resultSet() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("source_scope")).thenReturn("pms_user_track");
        when(resultSet.getString("source_id")).thenReturn("track-001");
        when(resultSet.getString("title")).thenReturn("Rain Street");
        when(resultSet.getString("artist_name")).thenReturn("Blue Trio");
        when(resultSet.getString("album_title")).thenReturn("Night Walk");
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
        when(resultSet.getString("primary_genre")).thenReturn("jazz");
        return resultSet;
    }
}
