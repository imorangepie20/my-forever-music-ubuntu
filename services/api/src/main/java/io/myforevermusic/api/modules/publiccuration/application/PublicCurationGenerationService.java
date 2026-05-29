package io.myforevermusic.api.modules.publiccuration.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationCandidateTrack;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationFilters;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationScoreRequest;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationScoreResponse;
import io.myforevermusic.api.modules.publiccuration.infrastructure.ai.AiPublicCurationScoringClient.AiPublicCurationSelectedTrack;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class PublicCurationGenerationService {

    private final AiPublicCurationScoringClient scoringClient;
    private final PublicCurationPlaylistStore playlistStore;
    private final ObjectMapper objectMapper;

    public PublicCurationGenerationService(
        AiPublicCurationScoringClient scoringClient,
        PublicCurationPlaylistStore playlistStore,
        ObjectMapper objectMapper
    ) {
        this.scoringClient = scoringClient;
        this.playlistStore = playlistStore;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PublicCurationPlaylistStore.StoredPlaylist generateDraft(GenerateDraftCommand command) {
        AiPublicCurationScoreResponse response = scoringClient.score(new AiPublicCurationScoreRequest(
            command.prompt(),
            command.filters(),
            command.targetTrackCount(),
            command.candidateTracks()
        ));
        List<PublicCurationPlaylistStore.TrackDraft> tracks = response.tracks().stream()
            .map(this::toTrackDraft)
            .toList();
        long durationMs = tracks.stream()
            .map(PublicCurationPlaylistStore.TrackDraft::durationMs)
            .filter(java.util.Objects::nonNull)
            .mapToLong(Integer::longValue)
            .sum();

        PublicCurationPlaylistStore.CreateDraft draft = new PublicCurationPlaylistStore.CreateDraft(
            command.slug(),
            response.title(),
            response.subtitle(),
            response.description(),
            command.prompt(),
            command.filterSnapshotJson(),
            command.coverStyle(),
            response.modelVersion(),
            tracks.size(),
            durationMs,
            command.createdByAdminUserId(),
            command.createdAt(),
            tracks,
            new PublicCurationPlaylistStore.RunDraft(
                command.prompt(),
                command.filterSnapshotJson(),
                intSummary(response, "candidate_count"),
                tracks.size(),
                response.modelVersion(),
                "completed",
                scoreSummaryJson(response),
                null,
                command.createdAt(),
                response.generatedAt()
            )
        );

        return playlistStore.createDraft(draft);
    }

    private PublicCurationPlaylistStore.TrackDraft toTrackDraft(AiPublicCurationSelectedTrack track) {
        return new PublicCurationPlaylistStore.TrackDraft(
            track.order(),
            track.sourceScope(),
            track.sourceTrackId(),
            track.title(),
            track.artistName(),
            track.albumTitle(),
            null,
            track.durationMs(),
            track.isrc(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.tidalExternalUrl(),
            track.score(),
            writeJson(track.scoreBreakdown()),
            track.reason()
        );
    }

    private int intSummary(AiPublicCurationScoreResponse response, String key) {
        Object value = response.scoreSummary().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String scoreSummaryJson(AiPublicCurationScoreResponse response) {
        return writeJson(response.scoreSummary());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                BAD_GATEWAY,
                "Failed to serialize public curation score summary.",
                exception
            );
        }
    }

    public record GenerateDraftCommand(
        String slug,
        String prompt,
        String filterSnapshotJson,
        AiPublicCurationFilters filters,
        int targetTrackCount,
        String coverStyle,
        String createdByAdminUserId,
        Instant createdAt,
        List<AiPublicCurationCandidateTrack> candidateTracks
    ) {
    }
}
