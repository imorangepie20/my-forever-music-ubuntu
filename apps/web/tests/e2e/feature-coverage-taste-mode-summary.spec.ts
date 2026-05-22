import { expect, test, type Route } from '@playwright/test'

const adminSession = {
    userId: 'admin-user',
    email: 'jowoosungtidal@gmail.com',
    displayName: 'Admin',
    preferredPlatformId: 'tidal',
    onboardingStage: 'ready',
    registeredAt: '2026-05-22T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/recommendations/feature-coverage',
    nextStepMessage: 'Ready.',
}

const featureCoverageResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-22T00:00:00Z',
    target_user_id: 'target-user',
    pms_library: {
        playlist_count: 4,
        track_count: 100,
        audio_feature_filled_count: 80,
        audio_feature_coverage_ratio: 0.8,
        stale_audio_feature_count: 2,
        stale_audio_feature_ratio: 0.025,
        latest_audio_resolved_at: '2026-05-22T00:00:00Z',
        isrc_count: 70,
        isrc_coverage_ratio: 0.7,
        playback_target_available_count: 90,
        playback_target_coverage_ratio: 0.9,
        audio_feature_source_classes: [],
    },
    ems_pool: {
        track_count: 120,
        audio_feature_filled_count: 60,
        audio_feature_coverage_ratio: 0.5,
        stale_audio_feature_count: 4,
        stale_audio_feature_ratio: 0.0667,
        latest_audio_resolved_at: '2026-05-22T00:00:00Z',
        isrc_count: 55,
        isrc_coverage_ratio: 0.4583,
        canonical_track_count: 44,
        canonical_track_coverage_ratio: 0.3667,
        audio_feature_source_classes: [],
        sources: [],
        warnings: [],
    },
    ems_acquisition: {
        recent_run_count: 3,
        article_count: 20,
        skipped_article_count: 2,
        seed_count: 12,
        skipped_seed_count: 1,
        checked_item_count: 40,
        skipped_item_count: 4,
        skipped_item_ratio: 0.1,
        warnings: [],
    },
    audio_feature_completion: {
        recent_job_count: 10,
        status_counts: [],
        top_reasons: [],
        warnings: [],
    },
    learning_data: {
        event_count: 30,
        recent_recommendation_snapshot_count: 6,
        recent_recommendation_snapshot_limit: 100,
    },
    warnings: [],
    drift_signals: [],
}

const tasteModeSummaryResponse = {
    service: 'api',
    status: 'ok',
    generated_at: '2026-05-22T00:01:00Z',
    summary: {
        entries_analyzed: 50,
        entries_with_summary: 12,
        parse_error_count: 1,
        boost_enabled_count: 4,
        evaluated_total: 120,
        eligible_total: 70,
        dry_run_total: 20,
        blocked_total: 30,
        not_applicable_total: 0,
        boost_applied_total: 40,
        rank_changed_total: 12,
        max_positive_delta: 0.0129,
        max_negative_delta: 0,
        reason_counts: {
            eligible: 70,
            low_mode_similarity: 30,
        },
        latest_summary: {
            audit_log_id: 1001,
            model_version: 'rule-based-preview-v1+audio-taste:v1+taste-mode-affinity:v1',
            created_at: '2026-05-22T00:01:00Z',
            taste_mode_gate_summary: '{"boost_applied_count":7}',
        },
        recommendation: 'boost_active',
    },
}

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test.beforeEach(async ({ page }) => {
    await page.addInitScript((session) => {
        window.localStorage.setItem('my-forever-music.auth-session', JSON.stringify(session))
    }, adminSession)
})

test('feature coverage admin renders taste mode rollout summary', async ({ page }) => {
    await page.route('**/api/v1/recommendations/admin/feature-coverage**', (route) =>
        fulfillJson(route, featureCoverageResponse),
    )
    await page.route('**/api/v1/recommendations/admin/audit-log/taste-mode-summary**', (route) =>
        fulfillJson(route, tasteModeSummaryResponse),
    )

    await page.goto('/recommendations/feature-coverage')

    const rolloutPanel = page.locator('section', { hasText: 'Taste Mode Rollout' }).first()
    await expect(rolloutPanel.getByRole('heading', { name: 'Taste Mode Rollout' })).toBeVisible()
    await expect(rolloutPanel.getByText('boost_active')).toBeVisible()
    await expect(rolloutPanel.getByLabel('Boost Applied 40')).toBeVisible()
    await expect(rolloutPanel.getByLabel('Rank Changed 12')).toBeVisible()
    await expect(rolloutPanel.getByText('eligible', { exact: true })).toBeVisible()
    await expect(rolloutPanel.getByText('low_mode_similarity', { exact: true })).toBeVisible()
})

test('feature coverage remains visible when taste mode summary fails', async ({ page }) => {
    await page.route('**/api/v1/recommendations/admin/feature-coverage**', (route) =>
        fulfillJson(route, featureCoverageResponse),
    )
    await page.route('**/api/v1/recommendations/admin/audit-log/taste-mode-summary**', (route) =>
        route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"unavailable"}' }),
    )

    await page.goto('/recommendations/feature-coverage')

    await expect(page.getByText('추천 데이터 준비도')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Taste Mode Rollout' })).toBeVisible()
    await expect(page.getByText('Taste mode rollout summary를 불러오지 못했습니다.')).toBeVisible()
})
