package io.myforevermusic.api.modules.gms.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.gms.presentation.GmsRecommendationPreviewResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GmsAxisEvidenceAuditSummaryService {

    private final ObjectMapper objectMapper;

    public GmsAxisEvidenceAuditSummaryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String forTrackPreview(GmsRecommendationPreviewResponse response) {
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> items = response.items().stream()
            .filter(item -> item.axisEvidence() != null && !item.axisEvidence().isEmpty())
            .map(item -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", item.rank());
                entry.put("track_id", item.trackId());
                entry.put("title", item.title());
                entry.put("artist_name", item.artistName());
                entry.put("source_platform", item.sourcePlatform());
                entry.put("score", item.score());
                entry.put("axis_evidence", item.axisEvidence());
                return entry;
            })
            .toList();
        if (items.isEmpty()) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "gms-preview");
        summary.put("items", items);
        return write(summary);
    }

    public String forPlaylistPreview(GmsPlaylistPreviewService.GmsPlaylistPreviewResult result) {
        if (result == null || result.candidates() == null || result.candidates().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> playlists = result.candidates().stream()
            .filter(candidate -> candidate.axisEvidence() != null && !candidate.axisEvidence().isEmpty())
            .map(candidate -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("rank", result.candidates().indexOf(candidate) + 1);
                entry.put("playlist_id", candidate.playlistId());
                entry.put("title", candidate.title());
                entry.put("source_platform", candidate.sourcePlatform());
                entry.put("track_count", candidate.trackCount());
                entry.put("composite_score", candidate.compositeScore());
                entry.put("affinity_score", candidate.affinityScore());
                entry.put("confidence_score", candidate.confidenceScore());
                entry.put("axis_evidence", candidate.axisEvidence());
                return entry;
            })
            .toList();
        if (playlists.isEmpty()) {
            return null;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", "gms-playlists");
        summary.put("playlists", playlists);
        return write(summary);
    }

    private String write(Map<String, Object> summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            return "{\"source\":\"gms\",\"serialization_error\":\"%s\"}".formatted(
                exception.getClass().getSimpleName()
            );
        }
    }
}
