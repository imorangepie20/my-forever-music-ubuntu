import { expect, test, type Page, type Route } from '@playwright/test'

const userSession = {
    userId: 'user-e2e-gms',
    email: 'gms-e2e@example.com',
    displayName: 'GMS E2E User',
    preferredPlatformId: 'spotify',
    onboardingStage: 'ready',
    registeredAt: '2026-05-16T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/gms-playlists',
    nextStepMessage: 'Ready for GMS playlist testing.',
}

const spotifyTrackA = '1111111111111111111111'
const spotifyTrackB = '2222222222222222222222'

const previewResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-16T00:00:00Z',
    user_id: userSession.userId,
    preferred_platform: 'spotify',
    model_stage: 'personalized',
    candidates: [
        {
            playlist_id: 101,
            external_playlist_id: 'spotify-playlist-101',
            title: 'Midnight Drive Test Mix',
            source_platform: 'spotify',
            curator: 'E2E Curator',
            description: 'A deterministic playlist for GMS save verification.',
            cover_image_url: null,
            platform_external_url: 'https://open.spotify.com/playlist/spotify-playlist-101',
            track_count: 2,
            audio_feature_filled_count: 2,
            affinity_score: 0.91,
            confidence_score: 0.88,
            composite_score: 0.9,
            collected_at: '2026-05-16T00:00:00Z',
            axis_evidence: [
                {
                    axis: 'genre',
                    level: 'strong',
                    score: 0.92,
                    summary: 'Matches user library genre anchors.',
                },
            ],
        },
    ],
}

const playlistDetailResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-16T00:00:01Z',
    playlist: {
        playlist_id: 101,
        external_playlist_id: 'spotify-playlist-101',
        title: 'Midnight Drive Test Mix',
        source_platform: 'spotify',
        curator: 'E2E Curator',
        description: 'A deterministic playlist for GMS save verification.',
        cover_image_url: null,
        platform_external_url: 'https://open.spotify.com/playlist/spotify-playlist-101',
        platform_uri: 'spotify:playlist:spotify-playlist-101',
        track_count: 2,
        audio_feature_filled_count: 2,
        collected_at: '2026-05-16T00:00:00Z',
    },
    tracks: [
        {
            id: 9001,
            external_track_id: spotifyTrackA,
            title: 'Alpha Road',
            artist_name: 'Test Artist',
            source_platform: 'spotify',
            isrc: 'USRC10009001',
            album_title: 'Night Test',
            album_image_url: null,
            platform_external_url: `https://open.spotify.com/track/${spotifyTrackA}`,
            platform_uri: `spotify:track:${spotifyTrackA}`,
            spotify_uri: `spotify:track:${spotifyTrackA}`,
            preview_url: null,
            duration_ms: 180_000,
            collection_source: 'acquisition_pool',
            collected_at: '2026-05-16T00:00:00Z',
            audio_features_filled: true,
            audio_feature_track_id: spotifyTrackA,
            audio_feature_source: 'spotify',
        },
        {
            id: 9002,
            external_track_id: spotifyTrackB,
            title: 'Beta Skip',
            artist_name: 'Test Artist',
            source_platform: 'spotify',
            isrc: 'USRC10009002',
            album_title: 'Night Test',
            album_image_url: null,
            platform_external_url: `https://open.spotify.com/track/${spotifyTrackB}`,
            platform_uri: `spotify:track:${spotifyTrackB}`,
            spotify_uri: `spotify:track:${spotifyTrackB}`,
            preview_url: null,
            duration_ms: 181_000,
            collection_source: 'acquisition_pool',
            collected_at: '2026-05-16T00:00:00Z',
            audio_features_filled: true,
            audio_feature_track_id: spotifyTrackB,
            audio_feature_source: 'spotify',
        },
    ],
}

const saveResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-16T00:00:02Z',
    user_id: userSession.userId,
    ems_playlist_id: 101,
    personal_playlist_id: 'gms-ems-101',
    personal_playlist_title: 'Midnight Drive Test Mix (GMS)',
    personal_playlist_track_count: 1,
    added_track_count: 1,
    saved_at: '2026-05-16T00:00:02Z',
}

const fulfillJson = (route: Route, body: unknown, status = 200) =>
    route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

const installSpotifyPlaybackBoundary = async (page: Page) => {
    await page.route('https://sdk.scdn.co/spotify-player.js', (route) =>
        route.fulfill({
            status: 200,
            contentType: 'application/javascript',
            body: `
                window.Spotify = {
                    Player: class {
                        constructor() {
                            this.listeners = {};
                        }
                        addListener(name, callback) {
                            this.listeners[name] = callback;
                            return true;
                        }
                        connect() {
                            setTimeout(() => this.listeners.ready?.({ device_id: 'playwright-device' }), 0);
                            return Promise.resolve(true);
                        }
                        disconnect() {}
                        getCurrentState() { return Promise.resolve(null); }
                        pause() { return Promise.resolve(); }
                        resume() { return Promise.resolve(); }
                        nextTrack() { return Promise.resolve(); }
                        previousTrack() { return Promise.resolve(); }
                        seek() { return Promise.resolve(); }
                        setVolume() { return Promise.resolve(); }
                    }
                };
                window.onSpotifyWebPlaybackSDKReady?.();
            `,
        }),
    )

    await page.route('https://api.spotify.com/v1/**', (route) => {
        if (route.request().method() === 'GET') {
            return fulfillJson(route, null)
        }
        return fulfillJson(route, {})
    })

    await page.route('**/api/v1/platforms/playback/credentials**', (route) =>
        fulfillJson(route, {
            service: 'api',
            status: 'ready',
            generated_at: '2026-05-16T00:00:00Z',
            user_id: userSession.userId,
            platform_id: 'spotify',
            access_token: 'test-spotify-token',
            token_type: 'Bearer',
            expires_at: '2026-05-17T00:00:00Z',
            scopes: [
                'streaming',
                'user-read-playback-state',
                'user-modify-playback-state',
            ],
            external_user_id: 'spotify-user-e2e',
            scope_summary: 'streaming user-read-playback-state user-modify-playback-state',
            client_id: null,
            country_code: 'US',
        }),
    )
}

const isDevServerHmrNoise = (message: string) =>
    message.includes('[vite] failed to connect to websocket') ||
    message.includes("WebSocket connection to 'wss://imapplepie20.tplinkdns.com/") ||
    message.includes("WebSocket connection to 'ws://imapplepie20.tplinkdns.com/")

test('GMS playlist preview removes a track and saves the selected playlist to PMS', async ({ page }) => {
    const consoleErrors: string[] = []
    const failedRequests: string[] = []
    let saveRequestBody: unknown = null

    page.on('console', (message) => {
        if (message.type() === 'error' && !isDevServerHmrNoise(message.text())) {
            consoleErrors.push(message.text())
        }
    })
    page.on('requestfailed', (request) => {
        failedRequests.push(`${request.method()} ${request.url()} ${request.failure()?.errorText ?? ''}`.trim())
    })

    await page.addInitScript((session) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
    }, userSession)

    await installSpotifyPlaybackBoundary(page)

    await page.route('**/api/v1/gms/playlists/preview**', (route) =>
        fulfillJson(route, previewResponse),
    )
    await page.route('**/api/v1/ems/collection/playlists/101', (route) =>
        fulfillJson(route, playlistDetailResponse),
    )
    await page.route('**/api/v1/gms/playlists/101/save**', (route) => {
        saveRequestBody = route.request().postDataJSON()
        return fulfillJson(route, saveResponse)
    })

    const previewApi = page.waitForResponse((response) =>
        response.url().includes('/api/v1/gms/playlists/preview') && response.status() === 200,
    )
    await page.goto('/gms-playlists')
    await previewApi

    await expect(page.getByText('Midnight Drive Test Mix')).toBeVisible()

    const detailApi = page.waitForResponse((response) =>
        response.url().includes('/api/v1/ems/collection/playlists/101') && response.status() === 200,
    )
    await page.getByRole('button', { name: 'Preview tracks' }).click()
    await detailApi

    const previewDialog = page.getByRole('dialog', { name: 'Midnight Drive Test Mix' })
    await expect(previewDialog).toBeVisible()
    await expect(previewDialog.getByRole('link', { name: 'Test Artist' }).first()).toHaveAttribute(
        'href',
        '/artists/test-artist?name=Test%20Artist',
    )
    await expect(previewDialog.getByRole('button', { name: 'Remove Alpha Road from PMS save preview' })).toBeVisible()
    await expect(previewDialog.getByRole('button', { name: 'Remove Beta Skip from PMS save preview' })).toBeVisible()

    await previewDialog.getByRole('button', { name: 'Remove Beta Skip from PMS save preview' }).click()

    await expect(previewDialog.getByRole('button', { name: 'Remove Beta Skip from PMS save preview' })).toHaveCount(0)
    await expect(previewDialog.getByText('1 removed before PMS save')).toBeVisible()

    const saveApi = page.waitForResponse((response) =>
        response.url().includes('/api/v1/gms/playlists/101/save') && response.status() === 200,
    )
    await previewDialog.getByRole('button', { name: 'PMS에 저장' }).click()
    const saveApiResponse = await saveApi

    await expect(saveApiResponse.json()).resolves.toMatchObject(saveResponse)
    expect(saveRequestBody).toEqual({
        title: null,
        excluded_track_ids: [9002],
    })

    await expect(page.getByText('Saved to PMS: Midnight Drive Test Mix (GMS)')).toBeVisible()
    await expect(page.getByText('Added 1 track(s) · 1 total in this playlist')).toBeVisible()
    expect(consoleErrors).toEqual([])
    expect(failedRequests).toEqual([])
})
