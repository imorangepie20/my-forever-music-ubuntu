import { expect, test, type Route } from '@playwright/test'

const userSession = {
    userId: 'user-gms-affinity-e2e',
    email: 'gms-affinity@example.com',
    displayName: 'GMS Affinity User',
    preferredPlatformId: 'spotify',
    onboardingStage: 'ready',
    registeredAt: '2026-05-21T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/gms-preview',
    nextStepMessage: 'Ready for GMS affinity review.',
}

const workspaceState = {
    userId: userSession.userId,
    playlistId: 'playlist-affinity',
    mood: 'upbeat',
    energyLevel: 4,
    familiarityBias: 2,
    limit: 3,
    includeExplanations: true,
}

const workspaceBootstrapResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-21T00:00:00Z',
    workspace_defaults: {
        user_id: userSession.userId,
        playlist_id: 'playlist-affinity',
        seed_track_ids: ['track-affinity-001'],
        seed_artist_names: ['Neon Bloom'],
        seed_genres: ['synth-pop'],
    },
    playlists: [
        {
            playlist_id: 'playlist-affinity',
            title: 'Affinity Source Library',
            source_platform: 'spotify',
            track_count: 2,
            curator: 'Forever Listener',
            highlight: 'Imported from the connected platform.',
            cover_image_url: null,
            platform_external_url: 'https://open.spotify.com/playlist/playlist-affinity',
            platform_uri: 'spotify:playlist:playlist-affinity',
            source_collection: 'pms-user-library',
        },
    ],
    suggested_tracks: [],
    suggested_artists: [],
    suggested_genres: [],
}

const gmsPreviewResponse = {
    request_id: 'preview-affinity-001',
    generated_at: '2026-05-21T00:01:00Z',
    service: 'api',
    status: 'ok',
    context: {
        strategy: 'gms-hybrid-blend',
        engine: 'gms-baseline-v1+audio-taste:v1',
        mode: 'gms',
        mood: 'upbeat',
        energy_level: 4,
        seed_basis: ['track-affinity-001'],
    },
    input_summary: {
        user_id: userSession.userId,
        playlist_id: 'playlist-affinity',
        track_seed_count: 1,
        artist_seed_count: 1,
        genre_seed_count: 1,
        familiarity_bias: 2,
        limit: 3,
    },
    items: [
        {
            rank: 1,
            track_id: 'track-affinity-001',
            title: 'Velvet Voltage',
            artist_name: 'Neon Bloom',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-001',
            platform_uri: 'spotify:track:track-affinity-001',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-001',
            audio_feature_track_id: 'spotify-track-affinity-001',
            duration_ms: 180000,
            score: 0.91,
            source_space: 'gms',
            energy_level: 4,
            reason: 'Audio taste matched this candidate.',
            taste_mode_affinity: {
                applied: true,
                mode_id: 'mode-1',
                label: 'high_energy_bright_danceable',
                similarity: 0.9321,
                distance: 0.0679,
                tokens: ['mode_energy_match', 'mode_valence_match'],
            },
            taste_mode_gate: {
                status: 'dry_run',
                reason: 'eligible',
                reason_tokens: ['eligible', 'mode_energy_match', 'mode_valence_match'],
                suggested_boost_weight: 0.018,
                dry_run_score: 0.9184,
                dry_run_delta: 0.0084,
            },
            axis_evidence: [
                {
                    axis: 'confidence',
                    score: 0.91,
                    level: 'strong',
                    summary: 'Strong playable candidate.',
                },
            ],
        },
        {
            rank: 2,
            track_id: 'track-affinity-002',
            title: 'Signal Drift',
            artist_name: 'Distance Field',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-002',
            platform_uri: 'spotify:track:track-affinity-002',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-002',
            audio_feature_track_id: 'spotify-track-affinity-002',
            duration_ms: 181000,
            score: 0.73,
            source_space: 'gms',
            energy_level: 3,
            reason: 'Playable library candidate.',
            taste_mode_affinity: {
                applied: true,
                mode_id: 'mode-low-similarity',
                label: 'low_similarity_sparse_match',
                similarity: 0.71,
                distance: 0.29,
                tokens: ['mode_profile_distance'],
            },
            taste_mode_gate: {
                status: 'blocked',
                reason: 'low_mode_similarity',
                reason_tokens: ['low_mode_similarity'],
                suggested_boost_weight: null,
                dry_run_score: null,
                dry_run_delta: null,
            },
            axis_evidence: [],
        },
        {
            rank: 3,
            track_id: 'track-affinity-003',
            title: 'Quiet Static',
            artist_name: 'No Mode',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-003',
            platform_uri: 'spotify:track:track-affinity-003',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-003',
            audio_feature_track_id: 'spotify-track-affinity-003',
            duration_ms: 182000,
            score: 0.42,
            source_space: 'gms',
            energy_level: 2,
            reason: 'No nearest taste mode in this fixture.',
            taste_mode_affinity: null,
            taste_mode_gate: null,
            axis_evidence: [
                {
                    axis: 'confidence',
                    score: 0.68,
                    level: 'moderate',
                    summary: 'Moderate confidence from broader GMS signals.',
                },
            ],
        },
    ],
    warnings: [],
}

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test('GMS preview renders taste mode affinity when backend provides it', async ({ page }) => {
    await page.addInitScript(({ session, workspace }) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
        window.localStorage.setItem('my-forever-music.recommendation-workspace', JSON.stringify(workspace))
    }, { session: userSession, workspace: workspaceState })

    await page.route('**/api/v1/pms/workspace/bootstrap**', (route) =>
        fulfillJson(route, workspaceBootstrapResponse),
    )
    await page.route('**/api/v1/gms/recommendations/preview', (route) =>
        fulfillJson(route, gmsPreviewResponse),
    )

    await page.goto('/gms-preview')
    await expect(page.getByRole('heading', { name: 'GMS Approval Request' })).toBeVisible()

    await page.getByRole('button', { name: /Request GMS Preview/ }).click()

    await expect(page.getByText('Velvet Voltage')).toBeVisible()
    await expect(page.getByRole('heading', { name: '이 추천의 이유' })).toHaveCount(3)
    const strongExplanation = page.getByLabel('Recommendation explanation track-affinity-001')
    await expect(strongExplanation.getByText('현재 취향 모드와 잘 맞아요')).toBeVisible()
    await expect(strongExplanation.getByText('모드 유사도')).toBeVisible()
    await expect(strongExplanation.getByText('0.93')).toBeVisible()
    await expect(strongExplanation.getByText('게이트')).toBeVisible()
    await expect(strongExplanation.getByText('시범 적용 · 추천 가능')).toBeVisible()
    await expect(strongExplanation.getByText('순위 변화')).toBeVisible()
    await expect(strongExplanation.getByText('+0.0084')).toBeVisible()
    await expect(strongExplanation.getByText('오디오 취향이 이 후보와 잘 맞아요.')).toBeVisible()

    const broaderExplanation = page.getByLabel('Recommendation explanation track-affinity-003')
    await expect(broaderExplanation.getByText('전반적인 청취 신호로 추천됐어요')).toBeVisible()
    await expect(broaderExplanation.getByText('넓은 GMS 신호에서 중간 수준의 확신을 얻었어요.')).toBeVisible()
    await expect(page.locator('[aria-label^="Taste mode affinity"]')).toHaveCount(2)
    const strongModePanel = page.getByLabel('Taste mode affinity mode-1')
    await expect(strongModePanel.getByText('high_energy_bright_danceable')).toBeVisible()
    await expect(strongModePanel.getByText('mode-1')).toBeVisible()
    await expect(strongModePanel.getByText('Similarity', { exact: true })).toBeVisible()
    await expect(strongModePanel.getByText('0.93')).toBeVisible()
    await expect(strongModePanel.getByText('Distance', { exact: true })).toBeVisible()
    await expect(strongModePanel.getByText('0.07')).toBeVisible()
    await expect(strongModePanel.getByText('mode_energy_match')).toHaveCount(2)
    await expect(strongModePanel.getByText('mode_valence_match')).toHaveCount(2)
    await expect(strongModePanel.getByText('Gate dry run')).toBeVisible()
    await expect(strongModePanel.getByText('+0.0084')).toBeVisible()
    await expect(strongModePanel.getByText('ranking unchanged')).toBeVisible()
    const blockedModePanel = page.getByLabel('Taste mode affinity mode-low-similarity')
    await expect(blockedModePanel.getByText('blocked')).toBeVisible()
    await expect(blockedModePanel.getByText('low_mode_similarity')).toHaveCount(2)
    await expect(page.getByText('Quiet Static')).toBeVisible()
})
