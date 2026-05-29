package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPublicCurationCandidatePoolStore implements PublicCurationCandidatePoolStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcPublicCurationCandidatePoolStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<CandidateTrack> findCandidates(CandidateQuery query) {
        if (query.limit() <= 0) {
            return List.of();
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("limit", query.limit());
        return jdbcTemplate.query(sql(query.tidalReadyRequired()), params, this::mapCandidate);
    }

    private String sql(boolean tidalReadyRequired) {
        String pmsTidalPredicate = tidalReadyRequired
            ? "and tidal_track_id is not null and tidal_uri is not null"
            : "";
        String emsTidalPredicate = tidalReadyRequired
            ? "and source_platform = 'tidal' and external_track_id is not null"
            : "";

        return """
            select *
            from (
                select
                    'pms_user_track' as source_scope,
                    track_id as source_id,
                    title,
                    artist_name,
                    album_title,
                    coalesce(audio_duration_ms, spotify_duration_ms) as duration_ms,
                    isrc,
                    source_platform,
                    tidal_track_id,
                    tidal_uri,
                    platform_external_url as tidal_external_url,
                    audio_acousticness,
                    audio_danceability,
                    audio_energy,
                    audio_tempo,
                    audio_valence,
                    primary_genre,
                    audio_resolved_at as recency_at
                from pms_user_track
                where 1 = 1
                  %s
                union all
                select
                    'ems_collected_track' as source_scope,
                    cast(ems_collected_track_id as varchar) as source_id,
                    title,
                    artist_name,
                    album_title,
                    duration_ms,
                    isrc,
                    source_platform,
                    case when source_platform = 'tidal' then external_track_id else null end as tidal_track_id,
                    case when source_platform = 'tidal' then 'tidal:track:' || external_track_id else null end as tidal_uri,
                    platform_external_url as tidal_external_url,
                    audio_acousticness,
                    audio_danceability,
                    audio_energy,
                    audio_tempo,
                    audio_valence,
                    null as primary_genre,
                    audio_resolved_at as recency_at
                from ems_collected_track
                where 1 = 1
                  %s
            ) candidates
            order by recency_at desc nulls last, source_scope asc, title asc
            limit :limit
            """.formatted(pmsTidalPredicate, emsTidalPredicate);
    }

    private CandidateTrack mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        String primaryGenre = resultSet.getString("primary_genre");
        String sourcePlatform = resultSet.getString("source_platform");
        return new CandidateTrack(
            resultSet.getString("source_scope"),
            resultSet.getString("source_id"),
            resultSet.getString("title"),
            resultSet.getString("artist_name"),
            resultSet.getString("album_title"),
            nullableInt(resultSet, "duration_ms"),
            resultSet.getString("isrc"),
            sourcePlatform,
            resultSet.getString("tidal_track_id"),
            resultSet.getString("tidal_uri"),
            resultSet.getString("tidal_external_url"),
            audioFeatures(resultSet),
            primaryGenre == null || primaryGenre.isBlank() ? List.of() : List.of(primaryGenre),
            sourcePlatform == null || sourcePlatform.isBlank() ? List.of() : List.of(sourcePlatform)
        );
    }

    private Map<String, Double> audioFeatures(ResultSet resultSet) throws SQLException {
        Map<String, Double> features = new LinkedHashMap<>();
        putFeature(features, "acousticness", nullableDouble(resultSet, "audio_acousticness"));
        putFeature(features, "danceability", nullableDouble(resultSet, "audio_danceability"));
        putFeature(features, "energy", nullableDouble(resultSet, "audio_energy"));
        putFeature(features, "tempo", nullableDouble(resultSet, "audio_tempo"));
        putFeature(features, "valence", nullableDouble(resultSet, "audio_valence"));
        return features;
    }

    private void putFeature(Map<String, Double> features, String key, Double value) {
        if (value != null) {
            features.put(key, value);
        }
    }

    private Integer nullableInt(ResultSet resultSet, String columnName) throws SQLException {
        int value = resultSet.getInt(columnName);
        return resultSet.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
