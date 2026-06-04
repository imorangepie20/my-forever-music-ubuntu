package io.myforevermusic.api.modules.publiccuration.infrastructure.persistence;

import io.myforevermusic.api.modules.publiccuration.application.PublicCurationCandidatePoolStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcPublicCurationCandidatePoolStore implements PublicCurationCandidatePoolStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public JdbcPublicCurationCandidatePoolStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    JdbcPublicCurationCandidatePoolStore(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
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
        String pmsCandidatePredicate = tidalReadyRequired
            ? "and tidal_track_id is not null and tidal_uri is not null"
            : "and title is not null and btrim(title) <> '' and artist_name is not null and btrim(artist_name) <> ''";
        String emsCandidatePredicate = tidalReadyRequired
            ? "and track.source_platform = 'tidal' and track.external_track_id is not null"
            : "and track.title is not null and btrim(track.title) <> '' and track.artist_name is not null and btrim(track.artist_name) <> ''";

        return """
            select
                candidates.*,
                evidence.audio_feature_confidence,
                coalesce(audience.play_started_count, 0) as play_started_count,
                coalesce(audience.play_completed_count, 0) as play_completed_count,
                coalesce(audience.skip_count, 0) as skip_count
            from (
                select
                    'pms_user_track' as source_scope,
                    track_id as source_id,
                    title,
                    artist_name,
                    album_title,
                    album_image_url as image_url,
                    coalesce(audio_duration_ms, spotify_duration_ms) as duration_ms,
                    isrc,
                    source_platform,
                    tidal_track_id,
                    tidal_uri,
                    platform_external_url as tidal_external_url,
                    audio_acousticness,
                    audio_danceability,
                    audio_energy,
                    audio_instrumentalness,
                    audio_liveness,
                    audio_loudness,
                    audio_speechiness,
                    audio_tempo,
                    audio_valence,
                    audio_feature_source,
                    audio_features_filled,
                    primary_genre,
                    audio_resolved_at as recency_at,
                    0 as source_playlist_count,
                    cast(null as integer) as max_followers_count,
                    cast(null as varchar) as source_playlist_titles,
                    cast(null as varchar) as source_playlist_descriptions,
                    cast(null as varchar) as source_playlist_curators,
                    cast(null as varchar) as source_playlist_collection_sources,
                    cast(null as varchar) as source_playlist_search_queries
                from pms_user_track
                where 1 = 1
                  %s
                union all
                select
                    'ems_collected_track' as source_scope,
                    cast(track.ems_collected_track_id as varchar) as source_id,
                    track.title,
                    track.artist_name,
                    track.album_title,
                    track.album_image_url as image_url,
                    track.duration_ms,
                    track.isrc,
                    track.source_platform,
                    case when track.source_platform = 'tidal' then track.external_track_id else null end as tidal_track_id,
                    case when track.source_platform = 'tidal' then 'tidal:track:' || track.external_track_id else null end as tidal_uri,
                    track.platform_external_url as tidal_external_url,
                    track.audio_acousticness,
                    track.audio_danceability,
                    track.audio_energy,
                    track.audio_instrumentalness,
                    track.audio_liveness,
                    track.audio_loudness,
                    track.audio_speechiness,
                    track.audio_tempo,
                    track.audio_valence,
                    track.audio_feature_source,
                    track.audio_features_filled,
                    null as primary_genre,
                    coalesce(track.audio_resolved_at, track.collected_at) as recency_at,
                    coalesce(source_playlists.source_playlist_count, 0) as source_playlist_count,
                    source_playlists.max_followers_count,
                    source_playlists.source_playlist_titles,
                    source_playlists.source_playlist_descriptions,
                    source_playlists.source_playlist_curators,
                    source_playlists.source_playlist_collection_sources,
                    source_playlists.source_playlist_search_queries
                from ems_collected_track track
                left join (
                    select
                        link.ems_collected_track_id,
                        count(distinct playlist.ems_collected_playlist_id) as source_playlist_count,
                        max(playlist.followers_count) as max_followers_count,
                        string_agg(distinct playlist.title, '||') as source_playlist_titles,
                        string_agg(distinct playlist.description, '||') as source_playlist_descriptions,
                        string_agg(distinct playlist.curator, '||') as source_playlist_curators,
                        string_agg(distinct playlist.collection_source, '||') as source_playlist_collection_sources,
                        string_agg(distinct playlist.search_query, '||') as source_playlist_search_queries
                    from ems_collected_playlist_track link
                    join ems_collected_playlist playlist
                      on playlist.ems_collected_playlist_id = link.ems_collected_playlist_id
                    group by link.ems_collected_track_id
                ) source_playlists
                  on source_playlists.ems_collected_track_id = track.ems_collected_track_id
                where 1 = 1
                  %s
            ) candidates
            left join (
                select
                    track_scope,
                    track_id,
                    cast(max(confidence) as double precision) as audio_feature_confidence
                from track_audio_feature_evidence
                group by track_scope, track_id
            ) evidence
              on evidence.track_scope = candidates.source_scope
             and evidence.track_id = candidates.source_id
            left join (
                select
                    playlist_track.tidal_track_id,
                    sum(case when event.event_type = 'play_started' then 1 else 0 end) as play_started_count,
                    sum(case when event.event_type = 'play_completed' then 1 else 0 end) as play_completed_count,
                    sum(case when event.event_type in ('play_skipped', 'skip') then 1 else 0 end) as skip_count
                from public_curation_playlist_track playlist_track
                join public_playlist_play_event event
                  on event.track_id = playlist_track.public_curation_playlist_track_id
                group by playlist_track.tidal_track_id
            ) audience
              on audience.tidal_track_id = candidates.tidal_track_id
            order by recency_at desc nulls last, source_scope asc, title asc
            limit :limit
            """.formatted(pmsCandidatePredicate, emsCandidatePredicate);
    }

    private CandidateTrack mapCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        String primaryGenre = resultSet.getString("primary_genre");
        String sourcePlatform = resultSet.getString("source_platform");
        String tidalTrackId = resultSet.getString("tidal_track_id");
        String tidalUri = resultSet.getString("tidal_uri");
        String playbackResolutionStatus = hasText(tidalTrackId) && hasText(tidalUri)
            ? PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_NATIVE_TIDAL
            : PublicCurationCandidatePoolStore.PLAYBACK_RESOLUTION_UNRESOLVED;
        return new CandidateTrack(
            resultSet.getString("source_scope"),
            resultSet.getString("source_id"),
            resultSet.getString("title"),
            resultSet.getString("artist_name"),
            resultSet.getString("album_title"),
            resultSet.getString("image_url"),
            nullableInt(resultSet, "duration_ms"),
            resultSet.getString("isrc"),
            sourcePlatform,
            tidalTrackId,
            tidalUri,
            resultSet.getString("tidal_external_url"),
            audioFeatures(resultSet),
            resultSet.getString("audio_feature_source"),
            nullableDouble(resultSet, "audio_feature_confidence"),
            resultSet.getBoolean("audio_features_filled"),
            primaryGenre == null || primaryGenre.isBlank() ? List.of() : List.of(primaryGenre),
            tags(resultSet, sourcePlatform),
            metadataTags(resultSet, primaryGenre),
            sourcePlaylistSignals(resultSet),
            new AudienceResponse(
                resultSet.getInt("play_started_count"),
                resultSet.getInt("play_completed_count"),
                resultSet.getInt("skip_count")
            ),
            freshness(resultSet),
            playbackResolutionStatus
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Double> audioFeatures(ResultSet resultSet) throws SQLException {
        Map<String, Double> features = new LinkedHashMap<>();
        putFeature(features, "acousticness", nullableDouble(resultSet, "audio_acousticness"));
        putFeature(features, "danceability", nullableDouble(resultSet, "audio_danceability"));
        putFeature(features, "energy", nullableDouble(resultSet, "audio_energy"));
        putFeature(features, "instrumentalness", nullableDouble(resultSet, "audio_instrumentalness"));
        putFeature(features, "liveness", nullableDouble(resultSet, "audio_liveness"));
        putFeature(features, "loudness", nullableDouble(resultSet, "audio_loudness"));
        putFeature(features, "speechiness", nullableDouble(resultSet, "audio_speechiness"));
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

    private List<String> tags(ResultSet resultSet, String sourcePlatform) throws SQLException {
        return merge(
            sourcePlatform == null || sourcePlatform.isBlank() ? List.of() : List.of(sourcePlatform),
            split(resultSet.getString("source_playlist_collection_sources")),
            split(resultSet.getString("source_playlist_search_queries"))
        );
    }

    private List<String> metadataTags(ResultSet resultSet, String primaryGenre) throws SQLException {
        return merge(
            primaryGenre == null || primaryGenre.isBlank() ? List.of() : List.of(primaryGenre),
            split(resultSet.getString("source_playlist_titles")),
            split(resultSet.getString("source_playlist_descriptions")),
            split(resultSet.getString("source_playlist_curators")),
            split(resultSet.getString("source_playlist_search_queries"))
        );
    }

    private SourcePlaylistSignals sourcePlaylistSignals(ResultSet resultSet) throws SQLException {
        return new SourcePlaylistSignals(
            resultSet.getInt("source_playlist_count"),
            nullableInt(resultSet, "max_followers_count"),
            split(resultSet.getString("source_playlist_titles")),
            split(resultSet.getString("source_playlist_descriptions")),
            split(resultSet.getString("source_playlist_curators")),
            split(resultSet.getString("source_playlist_collection_sources")),
            split(resultSet.getString("source_playlist_search_queries"))
        );
    }

    private List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\|\\|"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    @SafeVarargs
    private final List<String> merge(List<String>... values) {
        return Stream.of(values)
            .flatMap(List::stream)
            .distinct()
            .toList();
    }

    private Double freshness(ResultSet resultSet) throws SQLException {
        Timestamp recencyAt = resultSet.getTimestamp("recency_at");
        if (recencyAt == null) {
            return null;
        }
        long ageInDays = Math.max(0L, Duration.between(recencyAt.toInstant(), clock.instant()).toDays());
        return Math.max(0.0d, 1.0d - (ageInDays / 365.0d));
    }

    private Double nullableDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? null : value;
    }
}
