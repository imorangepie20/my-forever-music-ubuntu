# PMS Unified Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/pms` behave like a user-owned playlist home by merging platform-imported playlists with GMS-approved playlists in the main library, while keeping manual/auto personal playlists and import candidates in separate sections.

**Architecture:** Extend the PMS workspace bootstrap read model with an explicit playlist source classification, add a high-priority unified bootstrap source that merges platform library playlists with `gms-ems-*` playlists, then update playlist detail classification and the React PMS page so the selected playlist context, section boundaries, and source badges all stay aligned.

**Tech Stack:** Spring Boot 3.5 / Java 21 / JUnit 5 / React 18 / TypeScript / Playwright / Vite

---

## File Structure

**Backend create/modify**

- Create: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapResponse.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsImportedWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsDatabaseWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailService.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSourceTest.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailServiceTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSourceTest.java`
- Modify: `services/api/src/test/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapControllerWebMvcTest.java`

**Frontend create/modify**

- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/components/music/PlaylistFeatureCard.tsx`
- Modify: `apps/web/src/pages/PmsPage.tsx`
- Create: `apps/web/tests/e2e/pms-unified-library.spec.ts`

## Task 1: Add a unified PMS bootstrap read model

**Files:**
- Create: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapResponse.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsImportedWorkspaceBootstrapSource.java`
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsDatabaseWorkspaceBootstrapSource.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSourceTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSourceTest.java`
- Test: `services/api/src/test/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapControllerWebMvcTest.java`

- [ ] **Step 1: Write the failing backend tests for merged library bootstrap and source classification**

```java
package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPlaylistImportStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PmsUnifiedLibraryWorkspaceBootstrapSourceTest {

    @Test
    void shouldMergePlatformLibraryAndGmsApprovedPlaylistsIntoMainShelf() {
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();

        userLibraryStore.savePlaylists("user-001", List.of(libraryPlaylist()));
        personalStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "gms-ems-101",
            "GMS Approved Mix",
            "Imported from EMS via GMS"
        ));
        personalStore.addTrack(new PmsPersonalPlaylistStore.AddTrackDraft(
            "user-001",
            "gms-ems-101",
            personalTrack("ems-9001", "Approved Track", "spotify", 1),
            "gms-playlist-import"
        ));

        PmsUnifiedLibraryWorkspaceBootstrapSource source = new PmsUnifiedLibraryWorkspaceBootstrapSource(
            userLibraryStore,
            importStore,
            personalStore
        );

        PmsWorkspaceBootstrapResponse response = source.load("user-001", "gms-ems-101").orElseThrow();

        assertThat(response.playlists())
            .extracting(PmsWorkspaceBootstrapResponse.PlaylistOption::playlistId)
            .containsExactly("playlist-001", "gms-ems-101");
        assertThat(response.playlists())
            .extracting(PmsWorkspaceBootstrapResponse.PlaylistOption::sourceCollection)
            .containsExactly("pms-user-library", "pms-gms-approved-playlist");
        assertThat(response.workspaceDefaults().playlistId()).isEqualTo("gms-ems-101");
        assertThat(response.suggestedTracks())
            .extracting(PmsWorkspaceBootstrapResponse.TrackSeedSuggestion::trackId)
            .containsExactly("ems-9001");
    }

    @Test
    void shouldFallBackToImportedPlaylistsWhenUserLibraryIsEmpty() {
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();

        importStore.saveImportedPlaylists("user-001", List.of(importedPlaylist()));

        PmsUnifiedLibraryWorkspaceBootstrapSource source = new PmsUnifiedLibraryWorkspaceBootstrapSource(
            userLibraryStore,
            importStore,
            personalStore
        );

        PmsWorkspaceBootstrapResponse response = source.load("user-001", null).orElseThrow();

        assertThat(response.playlists()).hasSize(1);
        assertThat(response.playlists().getFirst().sourceCollection()).isEqualTo("pms-imported-playlist");
    }

    private PmsUserLibraryStore.LibraryPlaylistState libraryPlaylist() {
        return new PmsUserLibraryStore.LibraryPlaylistState(
            "user-001",
            "playlist-001",
            "spotify-playlist-001",
            "Library Import",
            "spotify",
            "Forever Listener",
            "Imported from the connected platform.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-001",
            "spotify:playlist:spotify-playlist-001",
            Instant.parse("2026-05-19T00:00:00Z"),
            List.of(
                new PmsUserLibraryStore.LibraryTrackState(
                    "track-001",
                    "spotify-track-001",
                    "Library Track",
                    "Library Artist",
                    "spotify",
                    "synth-pop",
                    "Library Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-001",
                    "spotify:track:spotify-track-001",
                    null,
                    1,
                    true,
                    null
                )
            )
        );
    }

    private PmsPlaylistImportStore.ImportedPlaylistState importedPlaylist() {
        return new PmsPlaylistImportStore.ImportedPlaylistState(
            "user-001",
            "playlist-imported-001",
            "spotify-playlist-imported-001",
            "Imported Fallback",
            "spotify",
            "Platform User",
            "Imported before full PMS library sync.",
            null,
            "https://open.spotify.com/playlist/spotify-playlist-imported-001",
            "spotify:playlist:spotify-playlist-imported-001",
            Instant.parse("2026-05-19T00:00:00Z"),
            List.of(
                new PmsPlaylistImportStore.ImportedTrackState(
                    "track-imported-001",
                    "spotify-track-imported-001",
                    "Imported Track",
                    "Imported Artist",
                    "spotify",
                    "indie-pop",
                    "Imported Album",
                    null,
                    "https://open.spotify.com/track/spotify-track-imported-001",
                    "spotify:track:spotify-track-imported-001",
                    null,
                    1,
                    true,
                    null
                )
            )
        );
    }

    private PmsPersonalPlaylistStore.PersonalTrackState personalTrack(
        String trackId,
        String title,
        String sourcePlatform,
        int sortOrder
    ) {
        return new PmsPersonalPlaylistStore.PersonalTrackState(
            trackId,
            trackId,
            title,
            "Approved Artist",
            sourcePlatform,
            "Approved Album",
            null,
            null,
            "spotify:track:approved-track",
            null,
            null,
            "approved-track",
            "spotify:track:approved-track",
            null,
            null,
            "spotify",
            "native",
            180000,
            sortOrder,
            "gms-playlist-import",
            Instant.parse("2026-05-19T00:00:00Z")
        );
    }
}
```

```java
// services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSourceTest.java
assertThat(result.orElseThrow().playlists().getFirst().sourceCollection())
    .isEqualTo("pms-user-library");
```

```java
// services/api/src/test/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapControllerWebMvcTest.java
.andExpect(jsonPath("$.playlists[0].source_collection").value("pms-user-library"))
```

- [ ] **Step 2: Run the targeted backend tests to verify the new bootstrap coverage fails first**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.pms.application.PmsUnifiedLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.application.PmsUserLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapControllerWebMvcTest
```

Expected: FAIL because `PlaylistOption` does not yet expose `sourceCollection`, and the unified source is not yet part of the committed backend contract.

- [ ] **Step 3: Implement the unified bootstrap source and source classification contract**

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapResponse.java
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record PlaylistOption(
    String playlistId,
    String title,
    String sourcePlatform,
    Integer trackCount,
    String curator,
    String highlight,
    String coverImageUrl,
    String platformExternalUrl,
    String platformUri,
    String sourceCollection
) {
}
```

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSource.java
private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(
    PmsUserLibraryStore.LibraryPlaylistState playlist
) {
    return new PmsWorkspaceBootstrapResponse.PlaylistOption(
        playlist.playlistId(),
        playlist.title(),
        playlist.sourcePlatform(),
        playlist.trackCount(),
        playlist.curator(),
        playlist.highlight(),
        playlist.coverImageUrl(),
        playlist.platformExternalUrl(),
        playlist.platformUri(),
        "pms-user-library"
    );
}
```

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsImportedWorkspaceBootstrapSource.java
private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(
    PmsPlaylistImportStore.ImportedPlaylistState playlist
) {
    return new PmsWorkspaceBootstrapResponse.PlaylistOption(
        playlist.playlistId(),
        playlist.title(),
        playlist.sourcePlatform(),
        playlist.trackCount(),
        playlist.curator(),
        playlist.highlight(),
        playlist.coverImageUrl(),
        playlist.platformExternalUrl(),
        playlist.platformUri(),
        "pms-imported-playlist"
    );
}
```

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsDatabaseWorkspaceBootstrapSource.java
private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(PmsCatalogPlaylistEntity playlist) {
    return new PmsWorkspaceBootstrapResponse.PlaylistOption(
        playlist.getId(),
        playlist.getTitle(),
        playlist.getSourcePlatform(),
        playlist.getTrackCount(),
        playlist.getCurator(),
        playlist.getHighlight(),
        null,
        null,
        null,
        "pms-catalog-playlist"
    );
}
```

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSource.java
@Component
@Order(-30)
public class PmsUnifiedLibraryWorkspaceBootstrapSource implements PmsWorkspaceBootstrapSource {

    private static final String GMS_APPROVED_PLAYLIST_PREFIX = "gms-ems-";
    private static final int TRACK_SUGGESTION_LIMIT = 8;
    private static final int SIGNAL_SUGGESTION_LIMIT = 6;

    private final PmsUserLibraryStore pmsUserLibraryStore;
    private final PmsPlaylistImportStore pmsPlaylistImportStore;
    private final PmsPersonalPlaylistStore pmsPersonalPlaylistStore;

    public PmsUnifiedLibraryWorkspaceBootstrapSource(
        PmsUserLibraryStore pmsUserLibraryStore,
        PmsPlaylistImportStore pmsPlaylistImportStore,
        PmsPersonalPlaylistStore pmsPersonalPlaylistStore
    ) {
        this.pmsUserLibraryStore = pmsUserLibraryStore;
        this.pmsPlaylistImportStore = pmsPlaylistImportStore;
        this.pmsPersonalPlaylistStore = pmsPersonalPlaylistStore;
    }

    @Override
    public Optional<PmsWorkspaceBootstrapResponse> load(String userId, String playlistId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        List<PlaylistCandidate> platformPlaylists = !pmsUserLibraryStore.findPlaylists(userId).isEmpty()
            ? pmsUserLibraryStore.findPlaylists(userId).stream().map(this::toCandidate).toList()
            : pmsPlaylistImportStore.findImportedPlaylists(userId).stream().map(this::toCandidate).toList();

        List<PlaylistCandidate> gmsApprovedPlaylists = pmsPersonalPlaylistStore.findPlaylists(userId).stream()
            .filter(playlist -> playlist.playlistId() != null
                && playlist.playlistId().startsWith(GMS_APPROVED_PLAYLIST_PREFIX)
                && playlist.trackCount() > 0)
            .map(this::toCandidate)
            .toList();

        List<PlaylistCandidate> merged = new ArrayList<>(platformPlaylists.size() + gmsApprovedPlaylists.size());
        merged.addAll(platformPlaylists);
        merged.addAll(gmsApprovedPlaylists);
        if (merged.isEmpty()) {
            return Optional.empty();
        }

        PlaylistCandidate selected = merged.stream()
            .filter(candidate -> playlistId != null && playlistId.equals(candidate.playlistId()))
            .findFirst()
            .orElse(merged.getFirst());

        return Optional.of(buildResponse(userId, merged, selected));
    }

    private PmsWorkspaceBootstrapResponse buildResponse(
        String userId,
        List<PlaylistCandidate> playlists,
        PlaylistCandidate selected
    ) {
        List<TrackCandidate> tracks = selected.tracks().stream()
            .sorted(Comparator.comparingInt(TrackCandidate::sortOrder).thenComparing(TrackCandidate::trackId))
            .toList();
        List<String> seedTrackIds = tracks.stream()
            .filter(TrackCandidate::seed)
            .map(TrackCandidate::trackId)
            .limit(2)
            .toList();

        return new PmsWorkspaceBootstrapResponse(
            "api",
            "ok",
            Instant.now(),
            new PmsWorkspaceBootstrapResponse.WorkspaceDefaults(
                userId,
                selected.playlistId(),
                seedTrackIds,
                distinctSeedArtists(tracks, seedTrackIds),
                distinctSeedGenres(tracks, seedTrackIds)
            ),
            playlists.stream().map(this::toPlaylistOption).toList(),
            tracks.stream()
                .limit(TRACK_SUGGESTION_LIMIT)
                .map(track -> toTrackSuggestion(track, seedTrackIds.contains(track.trackId())))
                .toList(),
            toArtistSuggestions(tracks),
            toGenreSuggestions(tracks)
        );
    }

    private PlaylistCandidate toCandidate(PmsUserLibraryStore.LibraryPlaylistState playlist) {
        return new PlaylistCandidate(
            playlist.playlistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.trackCount(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            (playlist.tracks() == null ? List.<PmsUserLibraryStore.LibraryTrackState>of() : playlist.tracks())
                .stream()
                .map(this::toTrackCandidate)
                .toList(),
            "pms-user-library"
        );
    }

    private PlaylistCandidate toCandidate(PmsPlaylistImportStore.ImportedPlaylistState playlist) {
        return new PlaylistCandidate(
            playlist.playlistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.trackCount(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            (playlist.tracks() == null ? List.<PmsPlaylistImportStore.ImportedTrackState>of() : playlist.tracks())
                .stream()
                .map(this::toTrackCandidate)
                .toList(),
            "pms-imported-playlist"
        );
    }

    private PlaylistCandidate toCandidate(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
        List<TrackCandidate> tracks = (playlist.tracks() == null
            ? List.<PmsPersonalPlaylistStore.PersonalTrackState>of()
            : playlist.tracks())
            .stream()
            .map(this::toTrackCandidate)
            .toList();
        TrackCandidate firstTrack = tracks.isEmpty() ? null : tracks.getFirst();
        return new PlaylistCandidate(
            playlist.playlistId(),
            playlist.title(),
            firstTrack == null ? "pms" : firstTrack.sourcePlatform(),
            playlist.trackCount(),
            "gms approved",
            playlist.description() == null || playlist.description().isBlank()
                ? "Approved from GMS and added to the PMS library."
                : playlist.description(),
            firstTrack == null ? null : firstTrack.albumImageUrl(),
            null,
            null,
            tracks,
            "pms-gms-approved-playlist"
        );
    }

    private PmsWorkspaceBootstrapResponse.PlaylistOption toPlaylistOption(PlaylistCandidate playlist) {
        return new PmsWorkspaceBootstrapResponse.PlaylistOption(
            playlist.playlistId(),
            playlist.title(),
            playlist.sourcePlatform(),
            playlist.trackCount(),
            playlist.curator(),
            playlist.highlight(),
            playlist.coverImageUrl(),
            playlist.platformExternalUrl(),
            playlist.platformUri(),
            playlist.sourceCollection()
        );
    }

    private PmsWorkspaceBootstrapResponse.TrackSeedSuggestion toTrackSuggestion(TrackCandidate track, boolean seed) {
        return new PmsWorkspaceBootstrapResponse.TrackSeedSuggestion(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.durationMs(),
            seed,
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.audioFeatureTrackId(),
            track.audioFeaturesFilled(),
            track.audioFeaturesFilled(),
            track.audioFeatureSource(),
            track.audioFeatureSource()
        );
    }

    private TrackCandidate toTrackCandidate(PmsUserLibraryStore.LibraryTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder(),
            track.seed(),
            track.audioFeatures() == null ? null : track.audioFeatures().getDurationMs(),
            track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureTrackId(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource()
        );
    }

    private TrackCandidate toTrackCandidate(PmsPlaylistImportStore.ImportedTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            track.primaryGenre(),
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder(),
            track.seed(),
            track.audioFeatures() == null ? null : track.audioFeatures().getDurationMs(),
            track.audioFeatures() == null ? null : track.audioFeatures().getAudioFeatureTrackId(),
            track.audioFeatures() != null && track.audioFeatures().isComplete(),
            track.audioFeatures() == null ? "unresolved" : track.audioFeatures().getAudioFeatureSource()
        );
    }

    private TrackCandidate toTrackCandidate(PmsPersonalPlaylistStore.PersonalTrackState track) {
        return new TrackCandidate(
            track.trackId(),
            track.title(),
            track.artistName(),
            track.sourcePlatform(),
            null,
            track.albumTitle(),
            track.albumImageUrl(),
            track.platformExternalUrl(),
            track.platformUri(),
            track.previewUrl(),
            track.isrc(),
            track.spotifyTrackId(),
            track.spotifyUri(),
            track.tidalTrackId(),
            track.tidalUri(),
            track.preferredPlaybackPlatform(),
            track.playbackTargetStatus(),
            track.sortOrder() == null ? 0 : track.sortOrder(),
            false,
            track.durationMs(),
            null,
            false,
            "unresolved"
        );
    }

    private List<String> distinctSeedArtists(List<TrackCandidate> tracks, List<String> seedTrackIds) {
        return tracks.stream()
            .filter(track -> seedTrackIds.contains(track.trackId()))
            .map(TrackCandidate::artistName)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private List<String> distinctSeedGenres(List<TrackCandidate> tracks, List<String> seedTrackIds) {
        return tracks.stream()
            .filter(track -> seedTrackIds.contains(track.trackId()))
            .map(TrackCandidate::primaryGenre)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private List<PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion> toArtistSuggestions(List<TrackCandidate> tracks) {
        Map<String, Long> counts = tracks.stream()
            .map(TrackCandidate::artistName)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(SIGNAL_SUGGESTION_LIMIT)
            .map(entry -> new PmsWorkspaceBootstrapResponse.ArtistSeedSuggestion(
                entry.getKey(),
                roundScore(0.55d + ((entry.getValue() / (double) max) * 0.44d)),
                "Recurring artist signal inside the selected PMS playlist."
            ))
            .toList();
    }

    private List<PmsWorkspaceBootstrapResponse.GenreSeedSuggestion> toGenreSuggestions(List<TrackCandidate> tracks) {
        Map<String, Long> counts = tracks.stream()
            .map(TrackCandidate::primaryGenre)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(SIGNAL_SUGGESTION_LIMIT)
            .map(entry -> new PmsWorkspaceBootstrapResponse.GenreSeedSuggestion(
                entry.getKey(),
                roundScore(0.55d + ((entry.getValue() / (double) max) * 0.44d)),
                "Recurring genre signal inside the selected PMS playlist."
            ))
            .toList();
    }

    private double roundScore(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record PlaylistCandidate(
        String playlistId,
        String title,
        String sourcePlatform,
        Integer trackCount,
        String curator,
        String highlight,
        String coverImageUrl,
        String platformExternalUrl,
        String platformUri,
        List<TrackCandidate> tracks,
        String sourceCollection
    ) {}

    private record TrackCandidate(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        String primaryGenre,
        String albumTitle,
        String albumImageUrl,
        String platformExternalUrl,
        String platformUri,
        String previewUrl,
        String isrc,
        String spotifyTrackId,
        String spotifyUri,
        String tidalTrackId,
        String tidalUri,
        String preferredPlaybackPlatform,
        String playbackTargetStatus,
        Integer sortOrder,
        boolean seed,
        Integer durationMs,
        String audioFeatureTrackId,
        boolean audioFeaturesFilled,
        String audioFeatureSource
    ) {}
}
```

Implement the candidate mapping so GMS-approved playlists project as:

```java
new PmsWorkspaceBootstrapResponse.PlaylistOption(
    playlist.playlistId(),
    playlist.title(),
    firstTrack == null ? "pms" : firstTrack.sourcePlatform(),
    playlist.trackCount(),
    "gms approved",
    hasText(playlist.description()) ? playlist.description() : "Approved from GMS and added to the PMS library.",
    firstTrack == null ? null : firstTrack.albumImageUrl(),
    null,
    null,
    "pms-gms-approved-playlist"
)
```

- [ ] **Step 4: Re-run the targeted backend tests until the unified source and JSON contract pass**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.pms.application.PmsUnifiedLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.application.PmsUserLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapControllerWebMvcTest
```

Expected: PASS, with `source_collection` serialized for every playlist option and the unified source selected ahead of the legacy sources.

- [ ] **Step 5: Commit the bootstrap read model change**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSource.java \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapResponse.java \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSource.java \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsImportedWorkspaceBootstrapSource.java \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/infrastructure/persistence/PmsDatabaseWorkspaceBootstrapSource.java \
  services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUnifiedLibraryWorkspaceBootstrapSourceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsUserLibraryWorkspaceBootstrapSourceTest.java \
  services/api/src/test/java/io/myforevermusic/api/modules/pms/presentation/PmsWorkspaceBootstrapControllerWebMvcTest.java
git commit -m "feat: unify PMS workspace bootstrap library sources"
```

## Task 2: Classify GMS-approved playlist detail separately from personal playlists

**Files:**
- Modify: `services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailService.java`
- Create: `services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailServiceTest.java`

- [ ] **Step 1: Write the failing detail classification tests**

```java
package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPersonalPlaylistStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPlaylistImportStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.presentation.PmsPlaylistDetailResponse;
import org.junit.jupiter.api.Test;

class PmsPlaylistDetailServiceTest {

    @Test
    void shouldLabelGmsApprovedPlaylistsAsDedicatedSourceCollection() {
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();
        personalStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "gms-ems-101",
            "GMS Approved Mix",
            "Imported from EMS via GMS"
        ));
        personalStore.addTrack(new PmsPersonalPlaylistStore.AddTrackDraft(
            "user-001",
            "gms-ems-101",
            personalTrack("ems-9001", "Approved Track", "spotify", 1),
            "gms-playlist-import"
        ));

        PmsPlaylistDetailService service = new PmsPlaylistDetailService(
            new InMemoryPmsUserLibraryStore(),
            new InMemoryPmsPlaylistImportStore(),
            personalStore
        );

        PmsPlaylistDetailResponse response = service.getPlaylistDetail("user-001", "gms-ems-101");

        assertThat(response.sourceCollection()).isEqualTo("pms-gms-approved-playlist");
        assertThat(response.playlist().curator()).isEqualTo("gms approved");
        assertThat(response.playlist().sourcePlatform()).isEqualTo("spotify");
    }

    @Test
    void shouldKeepSavedFromGmsAsPersonalPlaylist() {
        InMemoryPmsPersonalPlaylistStore personalStore = new InMemoryPmsPersonalPlaylistStore();
        personalStore.createPlaylist(new PmsPersonalPlaylistStore.CreatePlaylistDraft(
            "user-001",
            "personal-saved-gms-recommendations",
            "Saved from GMS",
            "Auto-saved track bucket"
        ));

        PmsPlaylistDetailService service = new PmsPlaylistDetailService(
            new InMemoryPmsUserLibraryStore(),
            new InMemoryPmsPlaylistImportStore(),
            personalStore
        );

        PmsPlaylistDetailResponse response = service.getPlaylistDetail(
            "user-001",
            "personal-saved-gms-recommendations"
        );

        assertThat(response.sourceCollection()).isEqualTo("pms-personal-playlist");
        assertThat(response.playlist().curator()).isEqualTo("personal playlist");
    }

    private PmsPersonalPlaylistStore.PersonalTrackState personalTrack(
        String trackId,
        String title,
        String sourcePlatform,
        int sortOrder
    ) {
        return new PmsPersonalPlaylistStore.PersonalTrackState(
            trackId,
            trackId,
            title,
            "Approved Artist",
            sourcePlatform,
            "Approved Album",
            null,
            null,
            "spotify:track:approved-track",
            null,
            null,
            "approved-track",
            "spotify:track:approved-track",
            null,
            null,
            "spotify",
            "native",
            180000,
            sortOrder,
            "gms-playlist-import",
            Instant.parse("2026-05-19T00:00:00Z")
        );
    }
}
```

- [ ] **Step 2: Run the new detail classification tests and confirm they fail**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.pms.application.PmsPlaylistDetailServiceTest
```

Expected: FAIL because `PmsPlaylistDetailService` currently maps all personal-store playlists to `pms-personal-playlist`.

- [ ] **Step 3: Implement dedicated GMS-approved playlist detail mapping**

```java
// services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailService.java
private static final String GMS_APPROVED_PLAYLIST_PREFIX = "gms-ems-";

private boolean isGmsApprovedPlaylist(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
    return playlist.playlistId() != null
        && playlist.playlistId().startsWith(GMS_APPROVED_PLAYLIST_PREFIX);
}

private PmsPlaylistDetailResponse toPersonalResponse(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
    if (isGmsApprovedPlaylist(playlist)) {
        return toGmsApprovedResponse(playlist);
    }
    return toManualPersonalResponse(playlist);
}
```

```java
private PmsPlaylistDetailResponse toGmsApprovedResponse(PmsPersonalPlaylistStore.PersonalPlaylistState playlist) {
    List<PmsPlaylistDetailResponse.TrackDetail> tracks = safePersonalTracks(playlist.tracks()).stream()
        .sorted(Comparator.comparingInt(PmsPersonalPlaylistStore.PersonalTrackState::sortOrder)
            .thenComparing(PmsPersonalPlaylistStore.PersonalTrackState::trackId))
        .map(this::toTrackDetail)
        .toList();

    String sourcePlatform = tracks.stream()
        .map(PmsPlaylistDetailResponse.TrackDetail::sourcePlatform)
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse("pms");

    return new PmsPlaylistDetailResponse(
        "api",
        "ok",
        Instant.now(),
        "pms-gms-approved-playlist",
        new PmsPlaylistDetailResponse.PlaylistDetail(
            playlist.playlistId(),
            null,
            playlist.title(),
            sourcePlatform,
            tracks.size(),
            "gms approved",
            playlist.description(),
            tracks.isEmpty() ? null : tracks.getFirst().albumImageUrl(),
            null,
            null,
            playlist.createdAt(),
            playlist.updatedAt()
        ),
        tracks
    );
}
```

- [ ] **Step 4: Re-run the playlist detail tests**

Run:

```bash
cd /srv/my-forever-music/services/api
./gradlew test --tests io.myforevermusic.api.modules.pms.application.PmsPlaylistDetailServiceTest
```

Expected: PASS, with `gms-ems-*` classified as `pms-gms-approved-playlist` and `personal-saved-gms-recommendations` still classified as `pms-personal-playlist`.

- [ ] **Step 5: Commit the playlist detail classification**

```bash
git add \
  services/api/src/main/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailService.java \
  services/api/src/test/java/io/myforevermusic/api/modules/pms/application/PmsPlaylistDetailServiceTest.java
git commit -m "feat: classify GMS-approved PMS playlist detail"
```

## Task 3: Rebuild the PMS page around the unified library

**Files:**
- Modify: `apps/web/src/types/api.ts`
- Modify: `apps/web/src/components/music/PlaylistFeatureCard.tsx`
- Modify: `apps/web/src/pages/PmsPage.tsx`
- Create: `apps/web/tests/e2e/pms-unified-library.spec.ts`

- [ ] **Step 1: Write the failing PMS page end-to-end test**

```ts
import { expect, test, type Page, type Route } from '@playwright/test'

const userSession = {
  userId: 'user-pms-e2e',
  email: 'pms@example.com',
  displayName: 'PMS E2E User',
  preferredPlatformId: 'spotify',
  onboardingStage: 'pms-imported',
  registeredAt: '2026-05-19T00:00:00Z',
  platformConnectionRequired: false,
  nextStepPath: '/pms',
  nextStepMessage: 'PMS ready.',
}

const workspaceResponse = (playlistId: string) => ({
  service: 'api',
  status: 'ok',
  generated_at: '2026-05-19T00:00:00Z',
  workspace_defaults: {
    user_id: userSession.userId,
    playlist_id: playlistId,
    seed_track_ids: playlistId === 'gms-ems-101' ? ['ems-9001'] : ['track-001'],
    seed_artist_names: playlistId === 'gms-ems-101' ? ['Approved Artist'] : ['Library Artist'],
    seed_genres: playlistId === 'gms-ems-101' ? [] : ['synth-pop'],
  },
  playlists: [
    {
      playlist_id: 'playlist-001',
      title: 'Library Import',
      source_platform: 'spotify',
      track_count: 2,
      curator: 'Forever Listener',
      highlight: 'Imported from the connected platform.',
      cover_image_url: null,
      platform_external_url: 'https://open.spotify.com/playlist/library-import',
      platform_uri: 'spotify:playlist:library-import',
      source_collection: 'pms-user-library',
    },
    {
      playlist_id: 'gms-ems-101',
      title: 'GMS Approved Mix',
      source_platform: 'spotify',
      track_count: 1,
      curator: 'gms approved',
      highlight: 'Approved from GMS and added to the PMS library.',
      cover_image_url: null,
      platform_external_url: null,
      platform_uri: null,
      source_collection: 'pms-gms-approved-playlist',
    },
  ],
  suggested_tracks: playlistId === 'gms-ems-101'
    ? [{
        track_id: 'ems-9001',
        title: 'Approved Track',
        artist_name: 'Approved Artist',
        source_platform: 'spotify',
        album_title: 'Approved Album',
        album_image_url: null,
        platform_external_url: null,
        platform_uri: 'spotify:track:approved-track',
        preview_url: null,
        duration_ms: 180000,
        seed: false,
        spotify_track_id: 'approved-track',
        spotify_audio_features_filled: false,
        audio_features_filled: false,
        spotify_audio_feature_source: 'unresolved',
        audio_feature_source: 'unresolved',
      }]
    : [{
        track_id: 'track-001',
        title: 'Library Track',
        artist_name: 'Library Artist',
        source_platform: 'spotify',
        album_title: 'Library Album',
        album_image_url: null,
        platform_external_url: null,
        platform_uri: 'spotify:track:library-track',
        preview_url: null,
        duration_ms: 200000,
        seed: true,
        spotify_track_id: 'library-track',
        spotify_audio_features_filled: true,
        audio_features_filled: true,
        spotify_audio_feature_source: 'spotify_api',
        audio_feature_source: 'spotify_api',
      }],
  suggested_artists: [],
  suggested_genres: [],
})

const personalResponse = {
  service: 'pms-personal-playlists',
  status: 'ready',
  generated_at: '2026-05-19T00:00:00Z',
  user_id: userSession.userId,
  summary: { playlist_count: 2, saved_track_count: 3 },
  playlists: [
    {
      playlist_id: 'gms-ems-101',
      title: 'GMS Approved Mix',
      description: 'Imported from EMS via GMS',
      track_count: 1,
      created_at: '2026-05-19T00:00:00Z',
      updated_at: '2026-05-19T00:00:00Z',
      tracks: [],
    },
    {
      playlist_id: 'personal-saved-gms-recommendations',
      title: 'Saved from GMS',
      description: 'Auto-saved track bucket',
      track_count: 2,
      created_at: '2026-05-19T00:00:00Z',
      updated_at: '2026-05-19T00:00:00Z',
      tracks: [],
    },
  ],
}

const importResponse = {
  service: 'api',
  status: 'ok',
  generated_at: '2026-05-19T00:00:00Z',
  user: {
    user_id: userSession.userId,
    display_name: 'PMS E2E User',
    preferred_platform_id: 'spotify',
  },
  platform_connection: {
    platform_id: 'spotify',
    display_name: 'Spotify',
    pms_import_supported: true,
    connected: true,
    connection_mode: 'oauth',
    external_account_label: 'spotify-user',
    sync_ready: true,
    credential_status: 'ready',
    reconnect_required: false,
  },
  summary: {
    preferred_platform_connected: true,
    reconnect_required: false,
    available_playlist_count: 1,
    imported_playlist_count: 1,
    next_step_path: '/ems',
    next_step_message: 'Import more playlists when needed.',
  },
  available_playlists: [
    {
      external_playlist_id: 'spotify-playlist-new',
      title: 'Fresh Import Candidate',
      source_platform: 'spotify',
      track_count: 12,
      curator: 'Platform User',
      description: 'Available for PMS import.',
      already_imported: false,
      audio_feature_policy: 'provider-neutral',
      cover_image_url: null,
      platform_external_url: 'https://open.spotify.com/playlist/new',
      platform_uri: 'spotify:playlist:new',
    },
    {
      external_playlist_id: 'spotify-playlist-imported',
      title: 'Already Imported Candidate',
      source_platform: 'spotify',
      track_count: 8,
      curator: 'Platform User',
      description: 'Should stay out of the queue cards.',
      already_imported: true,
      audio_feature_policy: 'provider-neutral',
      cover_image_url: null,
      platform_external_url: 'https://open.spotify.com/playlist/imported',
      platform_uri: 'spotify:playlist:imported',
    },
  ],
  imported_playlists: [
    {
      playlist_id: 'playlist-001',
      external_playlist_id: 'spotify-playlist-imported',
      title: 'Library Import',
      source_platform: 'spotify',
      track_count: 2,
      imported_at: '2026-05-19T00:00:00Z',
      cover_image_url: null,
      platform_external_url: 'https://open.spotify.com/playlist/library-import',
      platform_uri: 'spotify:playlist:library-import',
    },
  ],
}

const fulfillJson = (route: Route, body: unknown) =>
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

test('PMS main shelf merges imported and GMS-approved playlists without duplicating personal cards', async ({ page }) => {
  await page.addInitScript((session) => {
    window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
  }, userSession)

  await page.route('**/api/v1/pms/workspace/bootstrap**', (route) => {
    const url = new URL(route.request().url())
    return fulfillJson(route, workspaceResponse(url.searchParams.get('playlist_id') ?? 'playlist-001'))
  })
  await page.route('**/api/v1/pms/personal-playlists/bootstrap**', (route) => fulfillJson(route, personalResponse))
  await page.route('**/api/v1/pms/import/bootstrap**', (route) => fulfillJson(route, importResponse))

  await page.goto('/pms')

  await expect(page.getByRole('heading', { name: 'Main PMS Library' })).toBeVisible()
  await expect(page.getByText('GMS Approved Mix')).toBeVisible()
  await expect(page.getByText('Saved from GMS')).toBeVisible()
  await expect(page.getByText('Already Imported Candidate')).toHaveCount(0)

  await page.getByRole('button', { name: /Use Playlist/i }).nth(1).click()

  await expect(page.getByRole('heading', { name: 'Selected PMS Playlist' })).toContainText('Selected PMS Playlist')
  await expect(page.getByText('Approved Track')).toBeVisible()
})
```

- [ ] **Step 2: Run the new PMS page Playwright spec and capture the failure**

Run:

```bash
cd /srv/my-forever-music/apps/web
npx playwright test tests/e2e/pms-unified-library.spec.ts
```

Expected: FAIL because the current page still renders `Playlist Shelf`, still shows `Already Imported`, and does not refetch bootstrap data when the selected playlist changes.

- [ ] **Step 3: Implement the PMS page contract, badge support, and section filtering**

```ts
// apps/web/src/types/api.ts
export interface PmsWorkspaceBootstrapResponse {
  // ...
  playlists: Array<RichPlaylistArtwork & {
    playlist_id: string
    title: string
    source_platform: string
    track_count: number
    curator: string
    highlight: string
    source_collection: string
  }>
}
```

```tsx
// apps/web/src/components/music/PlaylistFeatureCard.tsx
interface PlaylistFeatureCardProps {
  title: string
  sourcePlatform: string
  curator: string
  trackCount: number
  description: string
  sourceLabel?: string
  // existing props...
}

<div className="flex flex-wrap gap-2">
  <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
    {sourcePlatform}
  </span>
  {sourceLabel ? (
    <span className="rounded-full border border-hud-accent-primary/40 bg-hud-accent-primary/10 px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-accent-primary">
      {sourceLabel}
    </span>
  ) : null}
  <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
    {trackCount} tracks
  </span>
</div>
```

```tsx
// apps/web/src/pages/PmsPage.tsx
const isGmsApprovedPlaylist = (playlistId: string) => playlistId.startsWith('gms-ems-')

const playlistSourceLabel = (sourceCollection: string) => {
  switch (sourceCollection) {
    case 'pms-gms-approved-playlist':
      return 'GMS approved'
    case 'pms-user-library':
    case 'pms-imported-playlist':
      return 'Platform import'
    default:
      return undefined
  }
}

const visiblePersonalPlaylists = useMemo(
  () => personalPlaylists.filter((playlist) => !isGmsApprovedPlaylist(playlist.playlist_id)),
  [personalPlaylists],
)

useEffect(() => {
  const controller = new AbortController()
  setIsLoading(true)
  setError(null)

  const load = async () => {
    const [workspaceResponse, importResponse, personalResponse] = await Promise.all([
      fetchPmsWorkspaceBootstrap(
        activeUserId,
        workspace.playlistId || undefined,
        controller.signal,
      ),
      activeUserId ? fetchPmsPlaylistImportBootstrap(activeUserId, controller.signal) : Promise.resolve(null),
      activeUserId ? fetchPmsPersonalPlaylists(activeUserId, controller.signal) : Promise.resolve(null),
    ])

    setBootstrap(workspaceResponse)
    setImportBootstrap(importResponse)
    setPersonalBootstrap(personalResponse)

    const nextPlaylistId = workspaceResponse.workspace_defaults.playlist_id
    if (nextPlaylistId && nextPlaylistId !== workspace.playlistId) {
      updateWorkspace({ playlistId: nextPlaylistId })
    }
  }

  void load()
  return () => controller.abort()
}, [activeUserId, session?.preferredPlatformId, workspace.playlistId])
```

Render changes:

```tsx
<HudCard title="Main PMS Library" subtitle="Platform imports and GMS-approved playlists live together here">
  {bootstrap?.playlists.map((playlist) => (
    <PlaylistFeatureCard
      key={playlist.playlist_id}
      title={playlist.title}
      sourcePlatform={playlist.source_platform}
      sourceLabel={playlistSourceLabel(playlist.source_collection)}
      curator={playlist.curator}
      trackCount={playlist.track_count}
      description={playlist.highlight}
      imageUrl={playlist.cover_image_url}
      isActive={playlist.playlist_id === activePlaylist?.playlist_id}
      actionLabel={playlist.playlist_id === activePlaylist?.playlist_id ? 'Current Playlist' : 'Use Playlist'}
      detailPath={buildPmsPlaylistDetailPath(playlist.playlist_id)}
      onSelect={() => updateWorkspace({ playlistId: playlist.playlist_id })}
      onPlay={() => void handlePlayPmsPlaylist(playlist)}
      onOpenExternal={() => openExternal(playlist.platform_external_url)}
    />
  ))}
</HudCard>
```

```tsx
// Replace importedPlaylists card block in Platform Import Queue with a summary panel only.
{importedPlaylists.length > 0 ? (
  <div className="rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75 p-5 text-sm leading-6 text-hud-text-secondary">
    {importedPlaylists.length} playlists already live in the main PMS library.
  </div>
) : null}
```

- [ ] **Step 4: Verify the frontend with Playwright and a production build**

Run:

```bash
cd /srv/my-forever-music/apps/web
npx playwright test tests/e2e/pms-unified-library.spec.ts
npm run build
```

Expected:

- Playwright PASS for the unified PMS page flow
- `vite build` PASS with no TypeScript errors from the new `source_collection` or `sourceLabel` props

- [ ] **Step 5: Commit the PMS page rebuild**

```bash
git add \
  apps/web/src/types/api.ts \
  apps/web/src/components/music/PlaylistFeatureCard.tsx \
  apps/web/src/pages/PmsPage.tsx \
  apps/web/tests/e2e/pms-unified-library.spec.ts
git commit -m "feat: rebuild PMS page around unified library"
```

## Final verification pass

- [ ] **Step 1: Run the full targeted verification set before closing the branch**

```bash
cd /srv/my-forever-music/services/api
./gradlew test \
  --tests io.myforevermusic.api.modules.pms.application.PmsUnifiedLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.application.PmsPlaylistDetailServiceTest \
  --tests io.myforevermusic.api.modules.pms.application.PmsUserLibraryWorkspaceBootstrapSourceTest \
  --tests io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapControllerWebMvcTest

cd /srv/my-forever-music/apps/web
npx playwright test tests/e2e/pms-unified-library.spec.ts
npm run build
```

Expected: all targeted backend tests PASS, PMS Playwright spec PASS, web build PASS.

- [ ] **Step 2: Inspect the diff for scope control**

```bash
cd /srv/my-forever-music
git diff --stat
git diff --check
```

Expected: only PMS bootstrap/detail/frontend files changed; `git diff --check` returns no whitespace or merge-marker issues.
