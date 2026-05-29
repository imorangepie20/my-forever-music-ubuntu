package io.myforevermusic.api.modules.pms.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.myforevermusic.api.modules.auth.application.AuthRegistrationService;
import io.myforevermusic.api.modules.auth.infrastructure.local.InMemoryAuthAccountStore;
import io.myforevermusic.api.modules.auth.presentation.AuthRegistrationRequest;
import io.myforevermusic.api.modules.platform.application.PlatformAccountCredential;
import io.myforevermusic.api.modules.platform.application.PlatformCatalogService;
import io.myforevermusic.api.modules.platform.application.PlatformCredentialService;
import io.myforevermusic.api.modules.platform.application.PlatformConnectionService;
import io.myforevermusic.api.modules.platform.application.PlatformPlaylistProvider;
import io.myforevermusic.api.modules.platform.application.PlatformPlaylistProviderRegistry;
import io.myforevermusic.api.modules.platform.application.PlatformReconnectRequiredException;
import io.myforevermusic.api.modules.platform.application.PlatformTokenExchangeResult;
import io.myforevermusic.api.modules.platform.application.PlatformTokenRefreshClient;
import io.myforevermusic.api.modules.platform.application.PlatformTokenRefreshRegistry;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformCredentialStore;
import io.myforevermusic.api.modules.platform.infrastructure.local.InMemoryPlatformConnectionStore;
import io.myforevermusic.api.modules.platform.infrastructure.sandbox.SandboxPlatformPlaylistProvider;
import io.myforevermusic.api.modules.platform.presentation.PlatformConnectRequest;
import io.myforevermusic.api.modules.pms.application.PmsPlaylistImportCatalogService.ImportCandidatePlaylist;
import io.myforevermusic.api.modules.pms.application.PmsPlaylistImportCatalogService.ImportCandidateTrack;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsUserLibraryStore;
import io.myforevermusic.api.modules.pms.infrastructure.local.InMemoryPmsPlaylistImportStore;
import io.myforevermusic.api.modules.pms.infrastructure.persistence.PmsTrackAudioFeatures;
import io.myforevermusic.api.modules.recommendation.application.AudioFeatureCompletionAutoEnqueueService;
import io.myforevermusic.api.modules.recommendation.infrastructure.local.InMemoryAudioFeatureCompletionJobStore;
import io.myforevermusic.api.modules.pms.presentation.PmsPlaylistImportRequest;
import io.myforevermusic.api.modules.pms.presentation.PmsWorkspaceBootstrapResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PmsPlaylistImportServiceTest {

    @Test
    void shouldImportSandboxPlatformPlaylistsAndExposeWorkspaceBootstrap() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformConnectionService connectionService = new PlatformConnectionService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            platformCredentialStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            )
        );
        connectionService.connectSandboxForTests(new PlatformConnectRequest(userId, "spotify", "sandbox-oauth", null));

        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            ),
            new PlatformPlaylistProviderRegistry(
                List.of(new SandboxPlatformPlaylistProvider(new PmsPlaylistImportCatalogService()))
            ),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore)
        );

        var bootstrapBeforeImport = importService.getBootstrap(userId);
        var importResponse = importService.importPlaylists(
            new PmsPlaylistImportRequest(
                userId,
                "spotify",
                List.of("spotify-liked-night-drive", "spotify-chill-focus-stack")
            )
        );
        var userLibraryBootstrap = new PmsUserLibraryWorkspaceBootstrapSource(userLibraryStore).load(userId, null).orElseThrow();
        var importedWorkspaceBootstrap = new PmsImportedWorkspaceBootstrapSource(importStore).load(userId, null).orElseThrow();

        assertThat(bootstrapBeforeImport.summary().preferredPlatformConnected()).isTrue();
        assertThat(bootstrapBeforeImport.availablePlaylists()).hasSize(2);
        assertThat(importResponse.status()).isEqualTo("playlists_imported");
        assertThat(importResponse.importResult().importedPlaylistCount()).isEqualTo(2);
        assertThat(importResponse.importResult().librarySyncedPlaylistCount()).isEqualTo(2);
        assertThat(importResponse.importResult().librarySyncedTrackCount()).isEqualTo(6);
        assertThat(importResponse.importResult().completeSpotifyAudioFeatureTrackCount()).isEqualTo(6);
        assertThat(importResponse.nextStep().path()).isEqualTo("/ems");
        assertThat(userLibraryBootstrap.playlists()).hasSize(2);
        assertThat(userLibraryBootstrap.workspaceDefaults().userId()).isEqualTo(userId);
        assertThat(userLibraryBootstrap.suggestedTracks()).allMatch(
            PmsWorkspaceBootstrapResponse.TrackSeedSuggestion::audioFeaturesFilled
        );
        assertThat(importedWorkspaceBootstrap.playlists()).hasSize(2);
    }

    @Test
    void shouldRefreshExpiredSpotifyCredentialBeforeImport() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        platformConnectionStore.connect(new io.myforevermusic.api.modules.platform.application.PlatformConnectionDraft(
            userId,
            "spotify",
            "spotify-pkce-draft",
            "Forever Listener Spotify",
            "playlist-read-private, user-read-email",
            true,
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        ));
        platformCredentialStore.save(new PlatformAccountCredential(
            userId,
            "spotify",
            "spotify-pkce-draft",
            "spotify-user-001",
            "Forever Listener Spotify",
            "expired-access-token",
            "spotify-refresh-token",
            "Bearer",
            "playlist-read-private, user-read-email",
            Instant.now().minusSeconds(30),
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        ));

        PlatformTokenRefreshClient refreshClient = new PlatformTokenRefreshClient() {
            @Override
            public boolean supports(PlatformAccountCredential credential) {
                return "spotify".equals(credential.platformId());
            }

            @Override
            public PlatformTokenExchangeResult refreshAccessToken(PlatformAccountCredential credential) {
                return new PlatformTokenExchangeResult(
                    "refreshed-access-token",
                    "",
                    "Bearer",
                    List.of("playlist-read-private", "user-read-email"),
                    Instant.now().plusSeconds(3600)
                );
            }
        };

        PlatformCredentialService platformCredentialService = new PlatformCredentialService(
            platformCredentialStore,
            new PlatformTokenRefreshRegistry(List.of(refreshClient))
        );
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            platformCredentialService,
            new PlatformPlaylistProviderRegistry(List.of(new PlatformPlaylistProvider() {
                @Override
                public boolean supports(String platformId, PlatformAccountCredential credential) {
                    return "spotify".equals(platformId)
                        && "refreshed-access-token".equals(credential.accessToken());
                }

                @Override
                public List<ImportCandidatePlaylist> listImportablePlaylists(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential
                ) {
                    return List.of(
                        new ImportCandidatePlaylist(
                            "spotify-owned-001",
                            "Refreshed Playlist",
                            "spotify",
                            "Forever Listener",
                            "Loaded after refresh.",
                            null,
                            "https://open.spotify.com/playlist/spotify-owned-001",
                            "spotify:playlist:spotify-owned-001",
                            1,
                            List.of()
                        )
                    );
                }

                @Override
                public List<ImportCandidatePlaylist> loadPlaylistsForImport(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential,
                    List<String> externalPlaylistIds
                ) {
                    return List.of(
                        new ImportCandidatePlaylist(
                            "spotify-owned-001",
                            "Refreshed Playlist",
                            "spotify",
                            "Forever Listener",
                            "Loaded after refresh.",
                            null,
                            "https://open.spotify.com/playlist/spotify-owned-001",
                            "spotify:playlist:spotify-owned-001",
                            1,
                            List.of(
                                new ImportCandidateTrack(
                                    "track-001",
                                    "Midnight Receiver",
                                    "Neon Bloom",
                                    "synth-pop",
                                    "Signal Bloom",
                                    null,
                                    "https://open.spotify.com/track/track-001",
                                    "spotify:track:track-001",
                                    null,
                                    true,
                                    new PmsTrackAudioFeatures(
                                        "track-001",
                                        "spotify_api",
                                        true,
                                        "https://api.spotify.com/v1/audio-analysis/track-001",
                                        "https://api.spotify.com/v1/tracks/track-001",
                                        "spotify:track:track-001",
                                        "audio_features",
                                        218000,
                                        1,
                                        1,
                                        4,
                                        0.19,
                                        0.74,
                                        0.78,
                                        0.02,
                                        0.11,
                                        -7.8,
                                        0.05,
                                        116.2,
                                        0.67,
                                        Instant.now()
                                    )
                                )
                            )
                        )
                    );
                }
            })),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore)
        );

        var bootstrap = importService.getBootstrap(userId);
        var importResponse = importService.importPlaylists(new PmsPlaylistImportRequest(
            userId,
            "spotify",
            List.of("spotify-owned-001")
        ));
        var savedCredential = platformCredentialStore.findByUserIdAndPlatformId(userId, "spotify").orElseThrow();

        assertThat(bootstrap.summary().preferredPlatformConnected()).isTrue();
        assertThat(bootstrap.availablePlaylists()).hasSize(1);
        assertThat(importResponse.importResult().importedPlaylistCount()).isEqualTo(1);
        assertThat(importResponse.importResult().librarySyncedPlaylistCount()).isEqualTo(1);
        assertThat(importResponse.importResult().librarySyncedTrackCount()).isEqualTo(1);
        assertThat(savedCredential.accessToken()).isEqualTo("refreshed-access-token");
        assertThat(savedCredential.refreshToken()).isEqualTo("spotify-refresh-token");
    }

    @Test
    void shouldEnqueueMissingAudioFeaturesAfterPlaylistImport() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformConnectionService connectionService = new PlatformConnectionService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            platformCredentialStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            )
        );
        connectionService.connectSandboxForTests(new PlatformConnectRequest(userId, "spotify", "sandbox-oauth", null));

        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        InMemoryAudioFeatureCompletionJobStore jobStore = new InMemoryAudioFeatureCompletionJobStore();
        AudioFeatureCompletionAutoEnqueueService autoEnqueueService =
            new AudioFeatureCompletionAutoEnqueueService(jobStore);
        ImportCandidatePlaylist playlist = new ImportCandidatePlaylist(
            "spotify-needs-audio-features",
            "Needs Audio Features",
            "spotify",
            "Forever Listener",
            "One track is missing audio features.",
            null,
            "https://open.spotify.com/playlist/spotify-needs-audio-features",
            "spotify:playlist:spotify-needs-audio-features",
            1,
            List.of(new ImportCandidateTrack(
                "track-missing-audio",
                "Missing Audio",
                "Feature Artist",
                "synth-pop",
                "Feature Album",
                null,
                "https://open.spotify.com/track/track-missing-audio",
                "spotify:track:track-missing-audio",
                null,
                true,
                PmsTrackAudioFeatures.unresolved()
            ))
        );
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            ),
            new PlatformPlaylistProviderRegistry(List.of(new PlatformPlaylistProvider() {
                @Override
                public boolean supports(String platformId, PlatformAccountCredential credential) {
                    return "spotify".equals(platformId);
                }

                @Override
                public List<ImportCandidatePlaylist> listImportablePlaylists(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential
                ) {
                    return List.of(playlist.withoutTracks());
                }

                @Override
                public List<ImportCandidatePlaylist> loadPlaylistsForImport(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential,
                    List<String> externalPlaylistIds
                ) {
                    return List.of(playlist);
                }
            })),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore),
            Optional.of(autoEnqueueService)
        );

        importService.importPlaylists(new PmsPlaylistImportRequest(
            userId,
            "spotify",
            List.of("spotify-needs-audio-features")
        ));

        assertThat(jobStore.findRecent(null, 10))
            .extracting("trackScope", "trackId", "userId", "requestedReason", "status")
            .containsExactly(org.assertj.core.groups.Tuple.tuple(
                "pms_user_track",
                "pms-track-spotify-track-missing-audio",
                userId,
                "pms_import",
                "queued"
            ));
    }

    @Test
    void shouldPreserveOriginalTrackOccurrenceOrderWhenImportedPlaylistRepeatsSameTrack() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener-repeat@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformConnectionService connectionService = new PlatformConnectionService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            platformCredentialStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            )
        );
        connectionService.connectSandboxForTests(new PlatformConnectRequest(userId, "spotify", "spotify-pkce-draft", null));

        ImportCandidateTrack repeatedTrack = new ImportCandidateTrack(
            "track-repeat",
            "Repeat Me",
            "Original Curator",
            "jazz",
            "Original Sequence",
            null,
            "https://open.spotify.com/track/track-repeat",
            "spotify:track:track-repeat",
            null,
            false,
            PmsTrackAudioFeatures.unresolved()
        );
        ImportCandidatePlaylist playlist = new ImportCandidatePlaylist(
            "spotify-repeated-sequence",
            "Repeated Sequence",
            "spotify",
            "Forever Listener",
            "A source playlist where the same track appears twice.",
            null,
            "https://open.spotify.com/playlist/spotify-repeated-sequence",
            "spotify:playlist:spotify-repeated-sequence",
            3,
            List.of(
                repeatedTrack,
                new ImportCandidateTrack(
                    "track-middle",
                    "Middle Track",
                    "Original Curator",
                    "jazz",
                    "Original Sequence",
                    null,
                    "https://open.spotify.com/track/track-middle",
                    "spotify:track:track-middle",
                    null,
                    false,
                    PmsTrackAudioFeatures.unresolved()
                ),
                repeatedTrack
            )
        );
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            ),
            new PlatformPlaylistProviderRegistry(List.of(new PlatformPlaylistProvider() {
                @Override
                public boolean supports(String platformId, PlatformAccountCredential credential) {
                    return "spotify".equals(platformId);
                }

                @Override
                public List<ImportCandidatePlaylist> listImportablePlaylists(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential
                ) {
                    return List.of(playlist.withoutTracks());
                }

                @Override
                public List<ImportCandidatePlaylist> loadPlaylistsForImport(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential,
                    List<String> externalPlaylistIds
                ) {
                    return List.of(playlist);
                }
            })),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore)
        );

        importService.importPlaylists(new PmsPlaylistImportRequest(
            userId,
            "spotify",
            List.of("spotify-repeated-sequence")
        ));

        assertThat(importStore.findImportedPlaylists(userId).getFirst().tracks())
            .extracting(PmsPlaylistImportStore.ImportedTrackState::externalTrackId,
                PmsPlaylistImportStore.ImportedTrackState::sortOrder)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("track-repeat", 1),
                org.assertj.core.groups.Tuple.tuple("track-middle", 2),
                org.assertj.core.groups.Tuple.tuple("track-repeat", 3)
            );
        assertThat(userLibraryStore.findPlaylists(userId).getFirst().tracks())
            .extracting(PmsUserLibraryStore.LibraryTrackState::externalTrackId,
                PmsUserLibraryStore.LibraryTrackState::sortOrder)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("track-repeat", 1),
                org.assertj.core.groups.Tuple.tuple("track-middle", 2),
                org.assertj.core.groups.Tuple.tuple("track-repeat", 3)
            );
    }

    @Test
    void shouldImportEveryPreferredPlatformPlaylistForOnboardingBeforeEnteringPms() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "TIDAL Listener",
            "tidal-onboarding@example.com",
            "music2026",
            "tidal",
            false,
            true,
            true
        )).user().userId();

        platformConnectionStore.connect(new io.myforevermusic.api.modules.platform.application.PlatformConnectionDraft(
            userId,
            "tidal",
            "tidal-device-code",
            "TIDAL Listener",
            "user.read collection.read playlists.read",
            true,
            Instant.parse("2026-05-26T00:00:00Z"),
            Instant.parse("2026-05-26T00:00:00Z")
        ));
        platformCredentialStore.save(new PlatformAccountCredential(
            userId,
            "tidal",
            "tidal-device-code",
            "tidal-user-001",
            "TIDAL Listener",
            "tidal-access-token",
            "tidal-refresh-token",
            "Bearer",
            "user.read collection.read playlists.read",
            Instant.now().plusSeconds(3600),
            Instant.parse("2026-05-26T00:00:00Z"),
            Instant.parse("2026-05-26T00:00:00Z")
        ));

        ImportCandidatePlaylist first = importCandidatePlaylist("tidal-original-001", "Original One", "track-001");
        ImportCandidatePlaylist second = importCandidatePlaylist("tidal-original-002", "Original Two", "track-002");
        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            ),
            new PlatformPlaylistProviderRegistry(List.of(new PlatformPlaylistProvider() {
                @Override
                public boolean supports(String platformId, PlatformAccountCredential credential) {
                    return "tidal".equals(platformId);
                }

                @Override
                public List<ImportCandidatePlaylist> listImportablePlaylists(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential
                ) {
                    return List.of(first.withoutTracks(), second.withoutTracks());
                }

                @Override
                public List<ImportCandidatePlaylist> loadPlaylistsForImport(
                    io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount account,
                    PlatformAccountCredential credential,
                    List<String> externalPlaylistIds
                ) {
                    return List.of(first, second);
                }
            })),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore)
        );

        var response = importService.importPreferredPlatformPlaylists(userId);

        assertThat(response.status()).isEqualTo("playlists_imported");
        assertThat(response.importResult().platformId()).isEqualTo("tidal");
        assertThat(response.importResult().importedPlaylistCount()).isEqualTo(2);
        assertThat(response.importResult().librarySyncedPlaylistCount()).isEqualTo(2);
        assertThat(response.importResult().librarySyncedTrackCount()).isEqualTo(2);
        assertThat(response.nextStep().path()).isEqualTo("/pms");
        assertThat(importStore.findImportedPlaylists(userId))
            .extracting(PmsPlaylistImportStore.ImportedPlaylistState::externalPlaylistId)
            .containsExactly("tidal-original-001", "tidal-original-002");
        assertThat(userLibraryStore.findPlaylists(userId))
            .extracting(PmsUserLibraryStore.LibraryPlaylistState::externalPlaylistId)
            .containsExactly("tidal-original-001", "tidal-original-002");
    }

    @Test
    void shouldRequireReconnectWhenConnectedPlatformCredentialIsNoLongerUsable() {
        InMemoryAuthAccountStore authAccountStore = new InMemoryAuthAccountStore();
        InMemoryPlatformConnectionStore platformConnectionStore = new InMemoryPlatformConnectionStore();
        InMemoryPlatformCredentialStore platformCredentialStore = new InMemoryPlatformCredentialStore();
        AuthRegistrationService authRegistrationService = new AuthRegistrationService(
            authAccountStore,
            new BCryptPasswordEncoder()
        );
        String userId = authRegistrationService.register(new AuthRegistrationRequest(
            "Forever Listener",
            "listener@example.com",
            "music2026",
            "spotify",
            false,
            true,
            true
        )).user().userId();

        PlatformConnectionService connectionService = new PlatformConnectionService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            platformCredentialStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            )
        );
        connectionService.connectSandboxForTests(new PlatformConnectRequest(userId, "spotify", "spotify-pkce-draft", null));
        platformCredentialStore.save(new PlatformAccountCredential(
            userId,
            "spotify",
            "spotify-pkce-draft",
            "spotify-user-001",
            "Forever Listener Spotify",
            "expired-access-token",
            "",
            "Bearer",
            "playlist-read-private",
            Instant.now().minusSeconds(30),
            Instant.parse("2026-05-03T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z")
        ));

        InMemoryPmsPlaylistImportStore importStore = new InMemoryPmsPlaylistImportStore();
        InMemoryPmsUserLibraryStore userLibraryStore = new InMemoryPmsUserLibraryStore();
        PmsPlaylistImportService importService = new PmsPlaylistImportService(
            authAccountStore,
            new PlatformCatalogService(),
            platformConnectionStore,
            new PlatformCredentialService(
                platformCredentialStore,
                new PlatformTokenRefreshRegistry(List.of())
            ),
            new PlatformPlaylistProviderRegistry(
                List.of(new SandboxPlatformPlaylistProvider(new PmsPlaylistImportCatalogService()))
            ),
            importStore,
            new PmsUserLibrarySyncService(userLibraryStore)
        );

        var bootstrap = importService.getBootstrap(userId);

        assertThat(bootstrap.platformConnection().connected()).isTrue();
        assertThat(bootstrap.platformConnection().syncReady()).isFalse();
        assertThat(bootstrap.platformConnection().reconnectRequired()).isTrue();
        assertThat(bootstrap.summary().reconnectRequired()).isTrue();

        assertThatThrownBy(() -> importService.importPlaylists(new PmsPlaylistImportRequest(
            userId,
            "spotify",
            List.of("spotify-liked-night-drive")
        )))
            .isInstanceOf(PlatformReconnectRequiredException.class)
            .hasMessageContaining("Reconnect Spotify");
    }

    private ImportCandidatePlaylist importCandidatePlaylist(String playlistId, String title, String trackId) {
        return new ImportCandidatePlaylist(
            playlistId,
            title,
            "tidal",
            "TIDAL Listener",
            "Original playlist from TIDAL.",
            null,
            "https://tidal.com/browse/playlist/%s".formatted(playlistId),
            "tidal:playlist:%s".formatted(playlistId),
            1,
            List.of(new ImportCandidateTrack(
                trackId,
                "Track %s".formatted(trackId),
                "Original Artist",
                null,
                "Original Album",
                null,
                "https://tidal.com/browse/track/%s".formatted(trackId),
                "tidal:track:%s".formatted(trackId),
                null,
                false,
                PmsTrackAudioFeatures.unresolved()
            ))
        );
    }
}
