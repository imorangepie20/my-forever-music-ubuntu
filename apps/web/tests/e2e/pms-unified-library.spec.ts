import { expect, test, type Route } from '@playwright/test'

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
    suggested_tracks:
        playlistId === 'gms-ems-101'
            ? [
                  {
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
                      spotify_uri: 'spotify:track:approved-track',
                      tidal_track_id: null,
                      tidal_uri: null,
                      preferred_playback_platform: 'spotify',
                      playback_target_status: 'native',
                      audio_feature_track_id: null,
                      spotify_audio_features_filled: false,
                      audio_features_filled: false,
                      spotify_audio_feature_source: 'unresolved',
                      audio_feature_source: 'unresolved',
                  },
              ]
            : [
                  {
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
                      spotify_uri: 'spotify:track:library-track',
                      tidal_track_id: null,
                      tidal_uri: null,
                      preferred_playback_platform: 'spotify',
                      playback_target_status: 'native',
                      audio_feature_track_id: 'library-track',
                      spotify_audio_features_filled: true,
                      audio_features_filled: true,
                      spotify_audio_feature_source: 'spotify_api',
                      audio_feature_source: 'spotify_api',
                  },
              ],
    suggested_artists: [],
    suggested_genres: [],
})

const personalResponse = {
    service: 'pms-personal-playlists',
    status: 'ready',
    generated_at: '2026-05-19T00:00:00Z',
    user_id: userSession.userId,
    summary: {
        playlist_count: 2,
        saved_track_count: 3,
    },
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
    ],
    imported_playlists: [
        {
            playlist_id: 'playlist-001',
            external_playlist_id: 'spotify-playlist-imported',
            title: 'Already Imported Candidate',
            source_platform: 'spotify',
            track_count: 2,
            imported_at: '2026-05-19T00:00:00Z',
            cover_image_url: null,
            platform_external_url: 'https://open.spotify.com/playlist/imported',
            platform_uri: 'spotify:playlist:imported',
        },
    ],
}

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test('PMS main shelf merges imported and GMS-approved playlists without personal duplication', async ({ page }) => {
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
    await expect(page.getByText('GMS Approved Mix')).toHaveCount(1)
    await expect(page.getByText('Saved from GMS')).toBeVisible()
    await expect(page.getByText('Already Imported Candidate')).toHaveCount(0)

    const selectedPlaylistBootstrap = page.waitForResponse((response) =>
        response.url().includes('/api/v1/pms/workspace/bootstrap')
        && response.url().includes('playlist_id=gms-ems-101')
        && response.status() === 200,
    )
    await page.locator('button[aria-label="Use playlist GMS Approved Mix"]').click()
    await selectedPlaylistBootstrap

    await expect(page.getByRole('heading', { name: 'Approved Track' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Library Track' })).toHaveCount(0)
})
