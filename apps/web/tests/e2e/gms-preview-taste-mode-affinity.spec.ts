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
                    axis: 'energy',
                    score: 0.91,
                    level: 'strong',
                    summary: 'Strong energy match.',
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
        {
            rank: 4,
            track_id: 'track-affinity-004',
            title: 'Golden Weather',
            artist_name: 'Warm Signal',
            source_platform: 'spotify',
            source_playlist_id: 'playlist-affinity',
            source_playlist_title: 'Affinity Source Library',
            album_title: 'Signal Bloom',
            album_image_url: null,
            platform_external_url: 'https://open.spotify.com/track/track-affinity-004',
            platform_uri: 'spotify:track:track-affinity-004',
            preview_url: null,
            spotify_track_id: 'spotify-track-affinity-004',
            audio_feature_track_id: 'spotify-track-affinity-004',
            duration_ms: 183000,
            score: 0.87,
            source_space: 'gms',
            energy_level: 4,
            reason: 'Audio taste matched this candidate.',
            taste_mode_affinity: {
                applied: true,
                mode_id: 'mode-2',
                label: 'warm_bright_midtempo',
                similarity: 0.8842,
                distance: 0.1158,
                tokens: ['mode_valence_match'],
            },
            taste_mode_gate: {
                status: 'dry_run',
                reason: 'eligible',
                reason_tokens: ['eligible', 'mode_valence_match'],
                suggested_boost_weight: 0.018,
                dry_run_score: 0.8755,
                dry_run_delta: 0.0055,
            },
            axis_evidence: [
                {
                    axis: 'valence',
                    score: 0.86,
                    level: 'strong',
                    summary: 'Strong valence match.',
                },
            ],
        },
    ],
    warnings: [],
}

const repeatedGatePreviewResponse = {
    ...gmsPreviewResponse,
    items: [
        {
            ...gmsPreviewResponse.items[0],
            track_id: 'same-gate-001',
            title: 'Winter Wonderland',
            reason: "Upbeat was selected by discovery-fallback to support an upbeat listening flow.",
            taste_mode_gate: {
                status: 'blocked',
                reason: 'low_profile_confidence',
                reason_tokens: [
                    'low_profile_confidence',
                    'mode_energy_match',
                    'mode_valence_match',
                    'mode_danceability_match',
                ],
            },
            axis_evidence: [],
        },
        {
            ...gmsPreviewResponse.items[1],
            track_id: 'same-gate-002',
            title: 'Streets of Minneapolis',
            reason: "Library was selected by discovery-fallback to support an upbeat listening flow.",
            taste_mode_affinity: {
                ...gmsPreviewResponse.items[1].taste_mode_affinity,
                tokens: ['mode_danceability_match', 'mode_tempo_match'],
            },
            taste_mode_gate: {
                status: 'blocked',
                reason: 'low_profile_confidence',
                reason_tokens: ['low_profile_confidence', 'mode_danceability_match', 'mode_tempo_match'],
            },
            axis_evidence: [],
        },
        {
            ...gmsPreviewResponse.items[2],
            track_id: 'same-gate-003',
            title: 'The Start',
            reason: "Discovery was selected by discovery-fallback to support an upbeat listening flow.",
            taste_mode_affinity: {
                applied: true,
                mode_id: 'mode-tempo',
                label: 'tempo_match',
                similarity: 0.7926,
                distance: 0.2074,
                tokens: ['mode_tempo_match'],
            },
            taste_mode_gate: {
                status: 'blocked',
                reason: 'low_profile_confidence',
                reason_tokens: ['low_profile_confidence', 'mode_tempo_match'],
            },
            axis_evidence: [],
        },
    ],
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
    await expect(page.getByRole('heading', { name: '이 추천의 이유' })).toHaveCount(4)
    const strongExplanation = page.getByLabel('Recommendation explanation track-affinity-001')
    await expect(strongExplanation.getByText('에너지·분위기 특성이 잘 맞아요')).toBeVisible()
    await expect(strongExplanation.getByText('모드 유사도')).toBeVisible()
    await expect(strongExplanation.getByText('0.93')).toBeVisible()
    await expect(strongExplanation.getByText('게이트')).toBeVisible()
    await expect(strongExplanation.getByText('시범 적용 · 추천 가능')).toBeVisible()
    await expect(strongExplanation.getByText('순위 변화')).toBeVisible()
    await expect(strongExplanation.getByText('+0.0084')).toBeVisible()
    await expect(strongExplanation.getByText('오디오 취향이 이 후보와 잘 맞아요.')).toBeVisible()

    const broaderExplanation = page.getByLabel('Recommendation explanation track-affinity-003')
    await expect(broaderExplanation.getByText('추천 신뢰도가 보통이에요')).toBeVisible()
    await expect(broaderExplanation.getByText('넓은 GMS 신호에서 중간 수준의 확신을 얻었어요.')).toBeVisible()

    const valenceExplanation = page.getByLabel('Recommendation explanation track-affinity-004')
    await expect(valenceExplanation.getByText('분위기 특성이 잘 맞아요')).toBeVisible()
    await expect(valenceExplanation.getByText('0.88')).toBeVisible()
    await expect(valenceExplanation.getByText('+0.0055')).toBeVisible()
    await expect(page.locator('[aria-label^="Taste mode affinity"]')).toHaveCount(3)
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

test('GMS preview varies explanation headlines when gate reason repeats', async ({ page }) => {
    await page.addInitScript(({ session, workspace }) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
        window.localStorage.setItem('my-forever-music.recommendation-workspace', JSON.stringify(workspace))
    }, { session: userSession, workspace: workspaceState })

    await page.route('**/api/v1/pms/workspace/bootstrap**', (route) =>
        fulfillJson(route, workspaceBootstrapResponse),
    )
    await page.route('**/api/v1/gms/recommendations/preview', (route) =>
        fulfillJson(route, repeatedGatePreviewResponse),
    )

    await page.goto('/gms-preview')
    await page.getByRole('button', { name: /Request GMS Preview/ }).click()

    const upbeatExplanation = page.getByLabel('Recommendation explanation same-gate-001')
    await expect(upbeatExplanation.getByText('업비트 흐름 보강 · 에너지·분위기·댄스감')).toBeVisible()
    await expect(upbeatExplanation.getByText('추천 포지션')).toBeVisible()
    await expect(upbeatExplanation.getByText('업비트 흐름', { exact: true })).toBeVisible()
    await expect(upbeatExplanation.getByText('취향 근거')).toBeVisible()
    await expect(upbeatExplanation.getByText('에너지 · 분위기 · 댄스감')).toBeVisible()
    await expect(upbeatExplanation.getByText('low_profile_confidence')).not.toBeVisible()
    await expect(upbeatExplanation.getByText('보류 · 취향 모델 신뢰도 부족')).toBeVisible()

    const libraryExplanation = page.getByLabel('Recommendation explanation same-gate-002')
    await expect(libraryExplanation.getByText('내 라이브러리에서 다시 꺼낸 후보 · 댄스감·템포')).toBeVisible()
    await expect(libraryExplanation.getByText('라이브러리 재발견')).toBeVisible()
    await expect(libraryExplanation.getByText('댄스감 · 템포')).toBeVisible()

    const discoveryExplanation = page.getByLabel('Recommendation explanation same-gate-003')
    await expect(discoveryExplanation.getByText('새 발견 슬롯으로 넣은 후보 · 템포')).toBeVisible()
    await expect(discoveryExplanation.getByText('새 발견', { exact: true })).toBeVisible()
    await expect(discoveryExplanation.locator('dd').filter({ hasText: /^템포$/ })).toBeVisible()
})
