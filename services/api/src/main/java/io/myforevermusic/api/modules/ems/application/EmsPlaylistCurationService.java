package io.myforevermusic.api.modules.ems.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackRepository;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsCollectedPlaylistTrackRepository.PlaylistAudioStatsProjection;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.ArtistAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.PlatformAffinity;
import io.myforevermusic.api.modules.recommendation.application.UserPersonalizationProfileStore.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@org.springframework.context.annotation.Profile("!local")
@Service
public class EmsPlaylistCurationService {

    public static final String TITLE_MODEL = "ems-curation-title-v1";

    private static final int DEFAULT_PER_SECTION_LIMIT = 6;
    private static final int MAX_PER_SECTION_LIMIT = 8;
    private static final List<GenreRule> GENRE_RULES = List.of(
        new GenreRule("k-pop", "K-Pop / Korean", List.of("k-pop", "kpop", "korean", "newjeans", "ive", "bts", "blackpink", "seventeen")),
        new GenreRule("indie-alt", "Indie / Alt", List.of("indie", "alternative", "alt ", "bedroom", "shoegaze")),
        new GenreRule("hiphop-rnb", "Hip-Hop / R&B", List.of("hip hop", "hip-hop", "rap", "r&b", "rnb", "soul")),
        new GenreRule("electronic-dance", "Electronic / Dance", List.of("electronic", "edm", "dance", "house", "techno", "club")),
        new GenreRule("rock", "Rock", List.of("rock", "punk", "metal", "garage")),
        new GenreRule("jazz-soul", "Jazz / Soul", List.of("jazz", "soul", "blues", "funk")),
        new GenreRule("pop", "Pop / Hits", List.of("pop", "hits", "top 50", "chart"))
    );

    private final EmsCollectedPlaylistRepository playlistRepository;
    private final EmsCollectedPlaylistTrackRepository playlistTrackRepository;
    private final UserPersonalizationProfileStore personalizationProfileStore;

    public EmsPlaylistCurationService(
        EmsCollectedPlaylistRepository playlistRepository,
        EmsCollectedPlaylistTrackRepository playlistTrackRepository,
        UserPersonalizationProfileStore personalizationProfileStore
    ) {
        this.playlistRepository = playlistRepository;
        this.playlistTrackRepository = playlistTrackRepository;
        this.personalizationProfileStore = personalizationProfileStore;
    }

    public EmsPlaylistCurationResult getPlaylistSections(
        String userId,
        List<String> platformIds,
        Integer perSectionLimit
    ) {
        int safeLimit = Math.min(Math.max(perSectionLimit == null ? DEFAULT_PER_SECTION_LIMIT : perSectionLimit, 2), MAX_PER_SECTION_LIMIT);
        int candidateLimit = Math.min(Math.max(safeLimit * 12, 60), 120);
        List<String> normalizedPlatformIds = normalizePlatformIds(platformIds);
        List<EmsCollectedPlaylistEntity> playlists = normalizedPlatformIds.isEmpty()
            ? playlistRepository.findRecentWithTracks(PageRequest.of(0, candidateLimit))
            : playlistRepository.findRecentWithTracksBySourcePlatforms(normalizedPlatformIds, PageRequest.of(0, candidateLimit));

        if (playlists.isEmpty()) {
            return new EmsPlaylistCurationResult(userId, normalizedPlatformIds, TITLE_MODEL, false, List.of());
        }

        Map<Long, PlaylistAudioStats> statsByPlaylistId = audioStatsByPlaylistId(playlists);
        List<Candidate> candidates = playlists.stream()
            .filter(playlist -> playlist.getId() != null)
            .map(playlist -> new Candidate(
                playlist,
                statsByPlaylistId.getOrDefault(playlist.getId(), PlaylistAudioStats.fromPlaylistMetadata(playlist)),
                searchableText(playlist)
            ))
            .toList();
        Optional<Profile> profile = userId == null || userId.isBlank()
            ? Optional.empty()
            : personalizationProfileStore.findByUserId(userId);

        List<EmsPlaylistSection> sections = new ArrayList<>();
        personalizedSection(profile, candidates, safeLimit).ifPresent(sections::add);
        moodSection("high-energy", candidates, safeLimit).ifPresent(sections::add);
        moodSection("late-night", candidates, safeLimit).ifPresent(sections::add);
        sections.addAll(genreSections(candidates, safeLimit));
        qualitySection(candidates, safeLimit).ifPresent(sections::add);
        recentSection(candidates, safeLimit).ifPresent(sections::add);

        boolean personalized = sections.stream().anyMatch(section -> "personalized".equals(section.categoryType()));
        return new EmsPlaylistCurationResult(userId, normalizedPlatformIds, TITLE_MODEL, personalized, sections);
    }

    private Map<Long, PlaylistAudioStats> audioStatsByPlaylistId(List<EmsCollectedPlaylistEntity> playlists) {
        List<Long> playlistIds = playlists.stream()
            .map(EmsCollectedPlaylistEntity::getId)
            .filter(Objects::nonNull)
            .toList();
        if (playlistIds.isEmpty()) {
            return Map.of();
        }

        return playlistTrackRepository.findAudioStatsByPlaylistIds(playlistIds).stream()
            .filter(stats -> stats.getPlaylistId() != null)
            .collect(Collectors.toMap(
                PlaylistAudioStatsProjection::getPlaylistId,
                PlaylistAudioStats::fromProjection,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Optional<EmsPlaylistSection> personalizedSection(
        Optional<Profile> profile,
        List<Candidate> candidates,
        int limit
    ) {
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        Profile value = profile.get();
        List<ScoredCandidate> ranked = candidates.stream()
            .map(candidate -> scorePersonalized(candidate, value))
            .filter(scored -> scored.score() > 0)
            .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
            .limit(limit)
            .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }

        String titleAnchor = firstArtistSignal(value)
            .or(() -> firstPlatformSignal(value).map(EmsPlaylistCurationService::capitalize))
            .orElse("내 취향");
        return Optional.of(new EmsPlaylistSection(
            "personalized-signal",
            "%s 근처에서 확장하는 EMS".formatted(titleAnchor),
            "최근 PMS 행동 신호와 EMS 공개 풀을 겹쳐서 고른 후보",
            "personalized",
            titleAnchor,
            "hero",
            TITLE_MODEL,
            toSectionItems(ranked)
        ));
    }

    private ScoredCandidate scorePersonalized(Candidate candidate, Profile profile) {
        double score = candidate.stats().coverageRatio() * 0.5;
        List<String> signals = new ArrayList<>();

        if (profile.topArtists() != null) {
            for (ArtistAffinity artist : profile.topArtists()) {
                if (artist.artistName() == null || artist.artistName().isBlank()) {
                    continue;
                }
                if (candidate.text().contains(artist.artistName().toLowerCase(Locale.ROOT))) {
                    score += 3.0 + Math.max(0.0, artist.score() * 0.1);
                    signals.add("artist " + artist.artistName());
                    break;
                }
            }
        }

        if (profile.topSourcePlatforms() != null) {
            for (PlatformAffinity platform : profile.topSourcePlatforms()) {
                if (platform.platform() == null || platform.platform().isBlank()) {
                    continue;
                }
                if (candidate.playlist().getSourcePlatform().equalsIgnoreCase(platform.platform())) {
                    score += 1.2 + Math.max(0.0, platform.score() * 0.05);
                    signals.add("source " + platform.platform());
                    break;
                }
            }
        }

        return new ScoredCandidate(candidate, round(score), signals);
    }

    private Optional<EmsPlaylistSection> moodSection(String moodId, List<Candidate> candidates, int limit) {
        List<ScoredCandidate> ranked = candidates.stream()
            .map(candidate -> scoreMood(candidate, moodId))
            .filter(scored -> scored.score() > 0)
            .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
            .limit(limit)
            .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }

        if ("high-energy".equals(moodId)) {
            return Optional.of(new EmsPlaylistSection(
                "mood-high-energy",
                "심박을 올리는 EMS 부스터",
                "energy, dance, party 신호가 강한 공개 플레이리스트",
                "mood",
                "High Energy",
                "mosaic",
                TITLE_MODEL,
                toSectionItems(ranked)
            ));
        }

        return Optional.of(new EmsPlaylistSection(
            "mood-late-night",
            "밤공기 쪽으로 기우는 플레이리스트",
            "chill, acoustic, focus 신호와 낮은 energy를 같이 본 묶음",
            "mood",
            "Late Night",
            "rail",
            TITLE_MODEL,
            toSectionItems(ranked)
        ));
    }

    private ScoredCandidate scoreMood(Candidate candidate, String moodId) {
        List<String> signals = new ArrayList<>();
        double score = 0.0;
        if ("high-energy".equals(moodId)) {
            double energy = candidate.stats().averageEnergyOrZero();
            double danceability = candidate.stats().averageDanceabilityOrZero();
            if (energy >= 0.67) {
                score += energy * 2.0;
                signals.add("energy " + percent(energy));
            }
            if (danceability >= 0.62) {
                score += danceability;
                signals.add("dance " + percent(danceability));
            }
            if (hasAny(candidate.text(), "workout", "dance", "party", "club", "festival", "boost", "hits")) {
                score += 1.2;
                signals.add("mood keyword");
            }
        } else {
            double energy = candidate.stats().averageEnergyOrOne();
            double acousticness = candidate.stats().averageAcousticnessOrZero();
            if (energy <= 0.5) {
                score += (1.0 - energy) * 1.6;
                signals.add("low energy " + percent(1.0 - energy));
            }
            if (acousticness >= 0.38) {
                score += acousticness;
                signals.add("acoustic " + percent(acousticness));
            }
            if (hasAny(candidate.text(), "chill", "sleep", "focus", "ambient", "acoustic", "jazz", "night", "lofi", "lo-fi", "ballad")) {
                score += 1.2;
                signals.add("mood keyword");
            }
        }
        if (score > 0) {
            score += candidate.stats().coverageRatio() * 0.3;
        }
        return new ScoredCandidate(candidate, round(score), signals);
    }

    private List<EmsPlaylistSection> genreSections(List<Candidate> candidates, int limit) {
        Map<GenreRule, List<Candidate>> buckets = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            for (GenreRule rule : GENRE_RULES) {
                if (hasAny(candidate.text(), rule.keywords().toArray(String[]::new))) {
                    buckets.computeIfAbsent(rule, ignored -> new ArrayList<>()).add(candidate);
                    break;
                }
            }
        }

        return buckets.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .sorted((left, right) -> Integer.compare(right.getValue().size(), left.getValue().size()))
            .limit(2)
            .map(entry -> {
                GenreRule rule = entry.getKey();
                List<ScoredCandidate> ranked = entry.getValue().stream()
                    .map(candidate -> new ScoredCandidate(
                        candidate,
                        round(1.0 + candidate.stats().coverageRatio()),
                        List.of("genre " + rule.label())
                    ))
                    .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                    .limit(limit)
                    .toList();
                return new EmsPlaylistSection(
                    "genre-" + rule.id(),
                    "%s 입구를 넓히는 EMS".formatted(rule.label()),
                    "제목, 설명, curator에 남은 genre 단서를 기준으로 묶음",
                    "genre",
                    rule.label(),
                    "compact",
                    TITLE_MODEL,
                    toSectionItems(ranked)
                );
            })
            .toList();
    }

    private Optional<EmsPlaylistSection> qualitySection(List<Candidate> candidates, int limit) {
        List<ScoredCandidate> ranked = candidates.stream()
            .filter(candidate -> candidate.stats().coverageRatio() >= 0.55 && candidate.stats().trackCount() >= 8)
            .map(candidate -> new ScoredCandidate(
                candidate,
                round(candidate.stats().coverageRatio() + Math.min(candidate.stats().trackCount(), 80) / 100.0),
                List.of("audio features " + percent(candidate.stats().coverageRatio()))
            ))
            .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
            .limit(limit)
            .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EmsPlaylistSection(
            "quality-feature-ready",
            "모델이 바로 읽기 좋은 플레이리스트",
            "오디오 특성 커버리지가 높은 EMS 후보",
            "quality",
            "Feature Ready",
            "mosaic",
            TITLE_MODEL,
            toSectionItems(ranked)
        ));
    }

    private Optional<EmsPlaylistSection> recentSection(List<Candidate> candidates, int limit) {
        List<ScoredCandidate> ranked = candidates.stream()
            .limit(limit)
            .map(candidate -> new ScoredCandidate(
                candidate,
                1.0,
                List.of("collected " + candidate.playlist().getCollectedAt())
            ))
            .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EmsPlaylistSection(
            "fresh-pool",
            "새로 들어온 외부 풀",
            "최근 수집된 공개 플레이리스트를 빠르게 훑는 레일",
            "fresh",
            "Recently Collected",
            "rail",
            TITLE_MODEL,
            toSectionItems(ranked)
        ));
    }

    private List<EmsPlaylistSectionItem> toSectionItems(List<ScoredCandidate> ranked) {
        List<EmsPlaylistSectionItem> items = new ArrayList<>();
        Set<Long> added = new java.util.LinkedHashSet<>();
        for (ScoredCandidate scored : ranked) {
            Long playlistId = scored.candidate().playlist().getId();
            if (playlistId == null || !added.add(playlistId)) {
                continue;
            }
            items.add(new EmsPlaylistSectionItem(
                scored.candidate().playlist(),
                scored.candidate().stats(),
                scored.signals()
            ));
        }
        return items;
    }

    private static String searchableText(EmsCollectedPlaylistEntity playlist) {
        return String.join(" ",
            coalesce(playlist.getTitle()),
            coalesce(playlist.getDescription()),
            coalesce(playlist.getCurator()),
            coalesce(playlist.getCollectionSource()),
            coalesce(playlist.getSearchQuery())
        ).toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizePlatformIds(List<String> platformIds) {
        if (platformIds == null) {
            return List.of();
        }
        return platformIds.stream()
            .filter(Objects::nonNull)
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static Optional<String> firstArtistSignal(Profile profile) {
        if (profile.topArtists() == null) {
            return Optional.empty();
        }
        return profile.topArtists().stream()
            .map(ArtistAffinity::artistName)
            .filter(value -> value != null && !value.isBlank())
            .findFirst();
    }

    private static Optional<String> firstPlatformSignal(Profile profile) {
        if (profile.topSourcePlatforms() == null) {
            return Optional.empty();
        }
        return profile.topSourcePlatforms().stream()
            .map(PlatformAffinity::platform)
            .filter(value -> value != null && !value.isBlank())
            .findFirst();
    }

    private static boolean hasAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String coalesce(String value) {
        return value == null ? "" : value;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "EMS";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record EmsPlaylistCurationResult(
        String userId,
        List<String> platformIds,
        String titleModel,
        boolean personalized,
        List<EmsPlaylistSection> sections
    ) {}

    public record EmsPlaylistSection(
        String sectionId,
        String title,
        String subtitle,
        String categoryType,
        String categoryLabel,
        String displayStyle,
        String titleSource,
        List<EmsPlaylistSectionItem> playlists
    ) {}

    public record EmsPlaylistSectionItem(
        EmsCollectedPlaylistEntity playlist,
        PlaylistAudioStats audioStats,
        List<String> matchSignals
    ) {}

    public record PlaylistAudioStats(
        long trackCount,
        long filledTrackCount,
        double coverageRatio,
        Double averageEnergy,
        Double averageValence,
        Double averageDanceability,
        Double averageAcousticness,
        Double averageSpeechiness
    ) {
        static PlaylistAudioStats fromProjection(PlaylistAudioStatsProjection projection) {
            long trackCount = projection.getTrackCount();
            long filledTrackCount = projection.getFilledTrackCount() == null ? 0L : projection.getFilledTrackCount();
            double coverageRatio = trackCount == 0 ? 0.0 : (double) filledTrackCount / trackCount;
            return new PlaylistAudioStats(
                trackCount,
                filledTrackCount,
                round(coverageRatio),
                projection.getAverageEnergy(),
                projection.getAverageValence(),
                projection.getAverageDanceability(),
                projection.getAverageAcousticness(),
                projection.getAverageSpeechiness()
            );
        }

        static PlaylistAudioStats fromPlaylistMetadata(EmsCollectedPlaylistEntity playlist) {
            return new PlaylistAudioStats(Math.max(0, playlist.getTrackCount()), 0, 0.0, null, null, null, null, null);
        }

        public long pendingTrackCount() {
            return Math.max(0, trackCount - filledTrackCount);
        }

        double averageEnergyOrZero() {
            return averageEnergy == null ? 0.0 : averageEnergy;
        }

        double averageEnergyOrOne() {
            return averageEnergy == null ? 1.0 : averageEnergy;
        }

        double averageDanceabilityOrZero() {
            return averageDanceability == null ? 0.0 : averageDanceability;
        }

        double averageAcousticnessOrZero() {
            return averageAcousticness == null ? 0.0 : averageAcousticness;
        }
    }

    private record Candidate(
        EmsCollectedPlaylistEntity playlist,
        PlaylistAudioStats stats,
        String text
    ) {}

    private record ScoredCandidate(
        Candidate candidate,
        double score,
        List<String> signals
    ) {}

    private record GenreRule(
        String id,
        String label,
        List<String> keywords
    ) {}
}
