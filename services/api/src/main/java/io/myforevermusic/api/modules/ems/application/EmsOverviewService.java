package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.infrastructure.ai.AiEmsOverviewClient;
import io.myforevermusic.api.modules.ems.infrastructure.ai.AiEmsOverviewClient.AiEmsOverviewRequest;
import io.myforevermusic.api.modules.ems.infrastructure.ai.AiEmsOverviewClient.AiEmsOverviewResponse;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedTrackRepository;
import io.myforevermusic.api.modules.ems.presentation.EmsOverviewRequest;
import io.myforevermusic.api.modules.ems.presentation.EmsOverviewResponse;
import io.myforevermusic.api.modules.ems.presentation.EmsWorkspaceAnalysisRequest;
import io.myforevermusic.api.modules.ems.presentation.EmsWorkspaceAnalysisResponse;
import io.myforevermusic.api.modules.pms.application.PmsWorkspaceBootstrapService;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class EmsOverviewService {

    private static final List<String> POOL_PLATFORMS = List.of("tidal", "spotify");

    private final EmsWorkspaceAnalysisService analysisService;
    private final PmsWorkspaceBootstrapService pmsWorkspaceBootstrapService;
    private final AiEmsOverviewClient aiEmsOverviewClient;
    private final Optional<EmsCollectedPlaylistRepository> playlistRepository;
    private final Optional<EmsCollectedTrackRepository> trackRepository;

    public EmsOverviewService(
        EmsWorkspaceAnalysisService analysisService,
        PmsWorkspaceBootstrapService pmsWorkspaceBootstrapService,
        AiEmsOverviewClient aiEmsOverviewClient,
        Optional<EmsCollectedPlaylistRepository> playlistRepository,
        Optional<EmsCollectedTrackRepository> trackRepository
    ) {
        this.analysisService = analysisService;
        this.pmsWorkspaceBootstrapService = pmsWorkspaceBootstrapService;
        this.aiEmsOverviewClient = aiEmsOverviewClient;
        this.playlistRepository = playlistRepository;
        this.trackRepository = trackRepository;
    }

    public EmsOverviewResponse getOverview(EmsOverviewRequest request) {
        PmsWorkspaceBootstrapResponse bootstrap = pmsWorkspaceBootstrapService.getWorkspaceBootstrap(
            request.userId(),
            request.playlistId()
        );
        String playlistId = request.playlistId() == null || request.playlistId().isBlank()
            ? bootstrap.workspaceDefaults().playlistId()
            : request.playlistId();

        EmsWorkspaceAnalysisResponse analysis = analysisService.analyzeWorkspace(
            new EmsWorkspaceAnalysisRequest(
                request.userId(),
                playlistId,
                bootstrap.workspaceDefaults().seedTrackIds(),
                bootstrap.workspaceDefaults().seedArtistNames(),
                bootstrap.workspaceDefaults().seedGenres()
            )
        );

        List<EmsOverviewResponse.ProviderPool> providerPools = buildProviderPools();
        int playlistCount = providerPools.stream().mapToInt(pool -> pool.playlistCount().intValue()).sum();
        int trackCount = providerPools.stream().mapToInt(pool -> pool.trackCount().intValue()).sum();
        int audioFeatureFilledTrackCount = providerPools.stream()
            .mapToInt(pool -> pool.audioFeatureFilledTrackCount().intValue())
            .sum();
        double audioFeatureCoverageRatio = trackCount == 0 ? 0.0 : (double) audioFeatureFilledTrackCount / trackCount;

        List<String> warnings = new ArrayList<>(analysis.warnings());
        if (playlistCount == 0) {
            warnings.add("EMS public playlist pool is empty. Wait for scheduled collection or configure EMS_DISCOVERY_USER_ID.");
        }
        if (trackCount > 0 && audioFeatureFilledTrackCount < trackCount) {
            warnings.add(
                "EMS audio feature coverage is incomplete: %d/%d tracks are filled."
                    .formatted(audioFeatureFilledTrackCount, trackCount)
            );
        }

        AiEmsOverviewResponse interpretation = aiEmsOverviewClient.requestOverview(
            new AiEmsOverviewRequest(
                request.userId(),
                playlistId,
                playlistTitle(bootstrap, playlistId),
                bootstrap.playlists().size(),
                bootstrap.suggestedTracks().size(),
                bootstrap.workspaceDefaults().seedTrackIds().size(),
                bootstrap.workspaceDefaults().seedArtistNames().size(),
                bootstrap.workspaceDefaults().seedGenres().size(),
                new AiEmsOverviewClient.Recommendation(
                    analysis.workspaceRecommendation().mood(),
                    analysis.workspaceRecommendation().energyLevel(),
                    analysis.workspaceRecommendation().familiarityBias(),
                    analysis.workspaceRecommendation().confidenceScore()
                ),
                analysis.topSignals().stream()
                    .map(signal -> new AiEmsOverviewClient.Signal(
                        signal.type(),
                        signal.label(),
                        signal.weight(),
                        signal.reason()
                    ))
                    .toList(),
                providerPools.stream()
                    .map(pool -> new AiEmsOverviewClient.ProviderPool(
                        pool.platformId(),
                        pool.playlistCount(),
                        pool.trackCount(),
                        pool.audioFeatureFilledTrackCount(),
                        pool.audioFeatureCoverageRatio(),
                        pool.lastCollectedAt()
                    ))
                    .toList(),
                warnings
            )
        );

        List<String> systemAttention = new ArrayList<>();
        if (interpretation.attentionItems() != null) {
            systemAttention.addAll(interpretation.attentionItems());
        }

        return new EmsOverviewResponse(
            "api",
            "ok",
            Instant.now(),
            request.userId(),
            playlistId,
            new EmsOverviewResponse.PipelineStatus(
                bootstrap.suggestedTracks().isEmpty() ? "needs_pms_library" : "ready",
                playlistCount == 0 ? "empty" : "ready",
                resolveGmsReadiness(interpretation.readinessStatus(), playlistCount)
            ),
            new EmsOverviewResponse.TasteModelSnapshot(
                interpretation.status(),
                interpretation.model(),
                interpretation.tasteModelSnapshot(),
                interpretation.confidence()
            ),
            new EmsOverviewResponse.CandidateDirection(
                interpretation.status(),
                interpretation.candidateDirection(),
                analysis.workspaceRecommendation().mood(),
                analysis.workspaceRecommendation().energyLevel(),
                analysis.workspaceRecommendation().familiarityBias(),
                analysis.workspaceRecommendation().confidenceScore()
            ),
            new EmsOverviewResponse.PmsContext(
                playlistTitle(bootstrap, playlistId),
                bootstrap.playlists().size(),
                bootstrap.suggestedTracks().size(),
                bootstrap.workspaceDefaults().seedTrackIds().size(),
                bootstrap.workspaceDefaults().seedArtistNames().size(),
                bootstrap.workspaceDefaults().seedGenres().size()
            ),
            new EmsOverviewResponse.EmsPoolHealth(
                playlistCount,
                trackCount,
                audioFeatureFilledTrackCount,
                audioFeatureCoverageRatio,
                providerPools
            ),
            List.copyOf(systemAttention),
            interpretation.evidence() == null ? List.of() : interpretation.evidence(),
            warnings
        );
    }

    private List<EmsOverviewResponse.ProviderPool> buildProviderPools() {
        if (playlistRepository.isEmpty() || trackRepository.isEmpty()) {
            return POOL_PLATFORMS.stream()
                .map(platformId -> new EmsOverviewResponse.ProviderPool(platformId, 0L, 0L, 0L, 0.0, null))
                .toList();
        }

        EmsCollectedPlaylistRepository playlists = playlistRepository.get();
        EmsCollectedTrackRepository tracks = trackRepository.get();
        Set<String> platformIds = new LinkedHashSet<>(POOL_PLATFORMS);
        platformIds.addAll(playlists.findDistinctSourcePlatforms());
        platformIds.addAll(tracks.findDistinctSourcePlatforms());

        return platformIds.stream()
            .map(platformId -> {
                long trackCount = tracks.countBySourcePlatform(platformId);
                long filledTrackCount = tracks.countBySourcePlatformAndAudioFeaturesAudioFeaturesFilled(platformId, true);
                double coverageRatio = trackCount == 0 ? 0.0 : (double) filledTrackCount / trackCount;
                return new EmsOverviewResponse.ProviderPool(
                    platformId,
                    playlists.countBySourcePlatform(platformId),
                    trackCount,
                    filledTrackCount,
                    coverageRatio,
                    playlists.findFirstBySourcePlatformOrderByCollectedAtDesc(platformId)
                        .map(playlist -> playlist.getCollectedAt())
                        .orElse(null)
                );
            })
            .toList();
    }

    private String playlistTitle(PmsWorkspaceBootstrapResponse bootstrap, String playlistId) {
        return bootstrap.playlists().stream()
            .filter(playlist -> playlist.playlistId().equals(playlistId))
            .map(PmsWorkspaceBootstrapResponse.PlaylistOption::title)
            .findFirst()
            .orElse(null);
    }

    private String resolveGmsReadiness(String aiReadiness, int playlistCount) {
        if (playlistCount == 0) {
            return "waiting_for_ems_pool";
        }
        if (aiReadiness == null || aiReadiness.isBlank()) {
            return "ready_for_gms_review";
        }
        return aiReadiness;
    }
}
