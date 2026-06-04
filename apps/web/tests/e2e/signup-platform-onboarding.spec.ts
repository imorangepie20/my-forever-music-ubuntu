import { expect, test, type Page, type Route } from '@playwright/test'

const generatedAt = '2026-05-26T00:00:00Z'
const activeDeviceExpiresAt = '2099-05-26T00:10:00Z'

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

const platformCatalog = {
    service: 'platform-catalog',
    status: 'ready',
    generated_at: generatedAt,
    primary_audio_feature_source: 'reccobeats',
    onboarding_flow: [
        '스트리밍 플랫폼 선택',
        '플랫폼 계정 인증',
        'PMS 플레이리스트 가져오기',
    ],
    platforms: [
        {
            platform_id: 'spotify',
            display_name: 'Spotify',
            integration_stage: 'priority-import-source',
            pms_import_supported: true,
            ems_collection_supported: true,
            audio_feature_strategy: 'provider metadata and completion queue',
            pms_role: 'PMS 플레이리스트 가져오기 우선 출처',
            ems_role: 'EMS 후보 수집 출처',
            notes: ['회원가입 직후 OAuth 인증을 시작합니다.'],
        },
        {
            platform_id: 'tidal',
            display_name: 'TIDAL',
            integration_stage: 'testing-provider',
            pms_import_supported: true,
            ems_collection_supported: true,
            audio_feature_strategy: 'provider metadata and completion queue',
            pms_role: 'PMS 플레이리스트 가져오기 우선 출처',
            ems_role: 'EMS 후보 수집 출처',
            notes: ['회원가입 직후 device 인증을 시작합니다.'],
        },
    ],
}

const lastFmBootstrap = {
    service: 'lastfm-scrobble-bootstrap',
    status: 'ready',
    generated_at: generatedAt,
    user: {
        user_id: 'signup-user',
        last_fm_username: null,
        last_fm_connected_at: null,
    },
    summary: {
        stored_scrobble_count: 0,
        last_synced_at: null,
        returned_scrobble_count: 0,
        next_step_message: 'Last.fm은 가입 후 보조 신호로 연결할 수 있습니다.',
    },
    recent_scrobbles: [],
}

const workspaceResponse = {
    service: 'pms-workspace',
    status: 'ready',
    generated_at: generatedAt,
    workspace_defaults: {
        user_id: 'signup-user',
        playlist_id: '',
        seed_track_ids: [],
        seed_artist_names: [],
        seed_genres: [],
    },
    playlists: [],
    suggested_tracks: [],
    suggested_artists: [],
    suggested_genres: [],
}

const importedWorkspaceResponse = (platformId: 'spotify' | 'tidal') => ({
    service: 'pms-workspace',
    status: 'ready',
    generated_at: generatedAt,
    workspace_defaults: {
        user_id: 'signup-user',
        playlist_id: `pms-${platformId}-signup-playlist`,
        seed_track_ids: [`${platformId}-track-001`],
        seed_artist_names: ['가입 아티스트'],
        seed_genres: [],
    },
    playlists: [
        {
            playlist_id: `pms-${platformId}-signup-playlist`,
            title: platformId === 'spotify' ? 'Spotify 가입 플레이리스트' : 'TIDAL 가입 플레이리스트',
            source_platform: platformId,
            curator: 'Streaming Account',
            track_count: 12,
            cover_image_url: null,
            highlight: '가입 직후 PMS로 저장된 원본 플랫폼 플레이리스트입니다.',
            source_collection: 'pms-imported-playlist',
            platform_external_url: `https://example.com/${platformId}/playlist/signup`,
            platform_uri: `${platformId}:playlist:signup`,
        },
    ],
    suggested_tracks: [],
    suggested_artists: [],
    suggested_genres: [],
})

const pmsImportBootstrap = (platformId: 'spotify' | 'tidal') => ({
    service: 'pms-import',
    status: 'ready',
    generated_at: generatedAt,
    user: {
        user_id: 'signup-user',
        display_name: '가입 테스트',
        preferred_platform_id: platformId,
    },
    platform_connection: {
        platform_id: platformId,
        display_name: platformId === 'spotify' ? 'Spotify' : 'TIDAL',
        pms_import_supported: true,
        connected: true,
        connection_mode: platformId === 'spotify' ? 'oauth' : 'device-code',
        external_account_label: `${platformId}-signup-account`,
        sync_ready: true,
        credential_status: 'ready',
        reconnect_required: false,
    },
    summary: {
        preferred_platform_connected: true,
        reconnect_required: false,
        available_playlist_count: 1,
        imported_playlist_count: 0,
        next_step_path: '/pms',
        next_step_message: '연결된 플랫폼의 플레이리스트를 PMS로 가져올 수 있습니다.',
    },
    available_playlists: [
        {
            external_playlist_id: `${platformId}-signup-playlist`,
            title: platformId === 'spotify' ? 'Spotify 가입 플레이리스트' : 'TIDAL 가입 플레이리스트',
            source_platform: platformId,
            track_count: 12,
            curator: 'Streaming Account',
            description: '가입 직후 PMS로 가져올 수 있는 플랫폼 플레이리스트입니다.',
            already_imported: false,
            audio_feature_policy: 'provider-neutral',
            cover_image_url: null,
            platform_external_url: `https://example.com/${platformId}/playlist/signup`,
            platform_uri: `${platformId}:playlist:signup`,
        },
    ],
    imported_playlists: [],
})

const pmsImportResult = (platformId: 'spotify' | 'tidal') => ({
    service: 'pms-import',
    status: 'playlists_imported',
    processed_at: generatedAt,
    import_result: {
        user_id: 'signup-user',
        platform_id: platformId,
        platform_display_name: platformId === 'spotify' ? 'Spotify' : 'TIDAL',
        imported_playlist_count: 1,
        imported_track_count: 12,
        complete_audio_feature_track_count: 0,
        complete_spotify_audio_feature_track_count: 0,
        connection_mode: platformId === 'spotify' ? 'oauth' : 'device-code',
        library_synced_playlist_count: 1,
        library_synced_track_count: 12,
    },
    playlists: [
        {
            playlist_id: `pms-${platformId}-signup-playlist`,
            external_playlist_id: `${platformId}-signup-playlist`,
            title: platformId === 'spotify' ? 'Spotify 가입 플레이리스트' : 'TIDAL 가입 플레이리스트',
            source_platform: platformId,
            track_count: 12,
            imported_at: generatedAt,
        },
    ],
    next_step: {
        path: '/ems',
        message: '플레이리스트를 PMS로 가져왔습니다.',
    },
})

const personalPlaylistsBootstrap = {
    service: 'pms-personal-playlists',
    status: 'ready',
    generated_at: generatedAt,
    user_id: 'signup-user',
    summary: {
        playlist_count: 0,
        saved_track_count: 0,
    },
    playlists: [],
}

const registerResponse = (preferredPlatformId: 'spotify' | 'tidal') => ({
    service: 'auth',
    status: 'registered',
    registered_at: generatedAt,
    user: {
        user_id: 'signup-user',
        email: 'signup@example.com',
        display_name: '가입 테스트',
        email_verified: false,
    },
    onboarding: {
        stage: 'connect-platform',
        preferred_platform_id: preferredPlatformId,
        platform_connection_required: true,
        next_step_path: '/platforms',
        next_step_message: '선택한 스트리밍 플랫폼 인증을 완료하세요.',
    },
})

const connectionBootstrap = (preferredPlatformId: 'spotify' | 'tidal') => ({
    service: 'platform-connection-bootstrap',
    status: 'ready',
    generated_at: generatedAt,
    user: {
        user_id: 'signup-user',
        display_name: '가입 테스트',
        email: 'signup@example.com',
        preferred_platform_id: preferredPlatformId,
        last_fm_username: null,
        last_fm_connected_at: null,
    },
    summary: {
        connected_platform_count: 0,
        preferred_platform_connected: false,
        preferred_platform_reconnect_required: false,
        onboarding_stage: 'connect-platform',
        next_step_path: '/platforms',
        next_step_message: '선택한 스트리밍 플랫폼 인증을 완료하세요.',
    },
    connections: ['spotify', 'tidal'].map((platformId) => ({
        platform_id: platformId,
        display_name: platformId === 'spotify' ? 'Spotify' : 'TIDAL',
        preferred: platformId === preferredPlatformId,
        connected: false,
        connection_status: 'not_connected',
        connection_mode: platformId === 'spotify' ? 'oauth' : 'device-code',
        external_account_label: null,
        sync_ready: false,
        credential_status: 'missing',
        reconnect_required: false,
        connected_at: null,
        next_action_label: '인증하기',
    })),
})

const fillSignupForm = async (page: Page) => {
    await page.getByPlaceholder('Forever Listener').fill('가입 테스트')
    await page.getByPlaceholder('listener@example.com').fill('signup@example.com')
    await page.getByPlaceholder('8자 이상').fill('strong-password')
    await page.getByPlaceholder('비밀번호 다시 입력').fill('strong-password')
    await page.getByLabel(/이용약관/).check()
    await page.getByLabel(/개인정보 처리방침/).check()
}

test.beforeEach(async ({ page }) => {
    await page.route('**/api/v1/system/info', (route) =>
        fulfillJson(route, {
            service: 'api',
            status: 'ok',
            message: 'ready',
            timestamp: generatedAt,
        }),
    )

    await page.route('**/api/v1/platforms/catalog', (route) => fulfillJson(route, platformCatalog))
    await page.route('**/api/v1/platforms/lastfm/scrobbles/bootstrap**', (route) =>
        fulfillJson(route, lastFmBootstrap),
    )
})

test('회원가입은 스트리밍 플랫폼 선택을 요구하고 Spotify OAuth를 자동 시작한다', async ({ page }) => {
    let registerPayload: Record<string, unknown> | null = null
    let oauthPayload: Record<string, unknown> | null = null

    await page.route('**/api/v1/auth/register', async (route) => {
        registerPayload = route.request().postDataJSON()
        fulfillJson(route, registerResponse(registerPayload?.preferred_platform_id as 'spotify' | 'tidal'))
    })
    await page.route('**/api/v1/platforms/connections/bootstrap**', (route) =>
        fulfillJson(route, connectionBootstrap('spotify')),
    )
    await page.route('**/api/v1/platforms/oauth/start', async (route) => {
        oauthPayload = route.request().postDataJSON()
        fulfillJson(route, {
            service: 'platform-oauth',
            status: 'authorization_started',
            generated_at: generatedAt,
            user: {
                user_id: 'signup-user',
                display_name: '가입 테스트',
                email: 'signup@example.com',
            },
            authorization: {
                state: 'signup-spotify',
                platform_id: 'spotify',
                platform_display_name: 'Spotify',
                authorization_mode: 'oauth',
                authorization_channel: 'internal_approval_page',
                requested_scopes: ['playlist-read-private'],
                expires_at: '2026-05-26T00:10:00Z',
                approval_page_path: '/platforms/oauth/authorize?state=signup-spotify',
                callback_path: '/platforms/oauth/callback',
                approval_code: 'spotify-approval-code',
                external_authorization_url: null,
                redirect_uri: null,
            },
        })
    })

    await page.goto('/signup')

    await expect(page.getByRole('button', { name: /계정 만들고 계속하기/ })).toBeDisabled()

    await page.locator('select').selectOption('spotify')
    await fillSignupForm(page)
    await page.getByRole('button', { name: /계정 만들고 계속하기/ }).click()

    await expect.poll(() => registerPayload?.preferred_platform_id).toBe('spotify')
    await expect.poll(() => oauthPayload?.platform_id).toBe('spotify')
    await expect(page).toHaveURL(/\/platforms\/oauth\/authorize\?state=signup-spotify/)
})

test('회원가입 후 TIDAL device 인증 안내를 팝업 없이 표시한다', async ({ page }) => {
    let deviceStartPayload: Record<string, unknown> | null = null
    let deviceStartCount = 0
    let pollCount = 0

    await page.route('**/api/v1/auth/register', async (route) => {
        fulfillJson(route, registerResponse(route.request().postDataJSON().preferred_platform_id))
    })
    await page.route('**/api/v1/platforms/connections/bootstrap**', (route) =>
        fulfillJson(route, connectionBootstrap('tidal')),
    )
    await page.route('**/api/v1/platforms/oauth/tidal/device/start', async (route) => {
        deviceStartPayload = route.request().postDataJSON()
        deviceStartCount += 1
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_pending',
            generated_at: generatedAt,
            user_id: 'signup-user',
            authorization: {
                device_code: deviceStartCount === 1 ? 'tidal-device-code' : 'new-tidal-device-code',
                user_code: deviceStartCount === 1 ? 'ABCD-EFGH' : 'NEWCODE',
                verification_uri: 'https://link.tidal.com',
                verification_uri_complete: deviceStartCount === 1
                    ? 'https://link.tidal.com/ABCD-EFGH'
                    : 'https://link.tidal.com/NEWCODE',
                expires_at: activeDeviceExpiresAt,
                interval_seconds: 1,
                requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            },
        })
    })
    await page.route('**/api/v1/platforms/oauth/tidal/device/poll', (route) => {
        pollCount += 1
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_pending',
            processed_at: generatedAt,
            user_id: 'signup-user',
            requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            connection: null,
            message: 'TIDAL authorization is still pending.',
        })
    })
    await page.addInitScript(() => {
        const originalOpen = window.open.bind(window)
        let openCount = 0
        Object.defineProperty(window, '__tidalWindowOpenCount', {
            configurable: true,
            get: () => openCount,
        })
        window.open = (...args) => {
            openCount += 1
            return originalOpen(...args)
        }
    })

    const getWindowOpenCount = () =>
        page.evaluate(() => (window as unknown as { __tidalWindowOpenCount?: number }).__tidalWindowOpenCount ?? 0)

    await page.goto('/signup')

    await page.locator('select').selectOption('tidal')
    await fillSignupForm(page)
    await page.getByRole('button', { name: /계정 만들고 계속하기/ }).click()

    await expect.poll(() => deviceStartPayload?.user_id).toBe('signup-user')
    await expect.poll(getWindowOpenCount).toBe(0)
    await expect(page).toHaveURL(/\/platforms/)
    await expect(page.getByRole('heading', { name: 'TIDAL 인증 코드' })).toBeVisible()
    await expect(page.getByText('ABCD-EFGH', { exact: true })).toBeVisible()
    await expect(page.getByText('TIDAL 인증 페이지를 준비했습니다. 아래 링크로 TIDAL에서 인증하고, 인증이 끝나면 TIDAL 창을 닫고 이 화면으로 돌아오세요. 연결 상태는 자동으로 확인됩니다.')).toBeVisible()
    await expect(page.getByRole('link', { name: /TIDAL 인증 페이지 열기/ })).toHaveAttribute(
        'href',
        'https://link.tidal.com/ABCD-EFGH',
    )
    await expect(page.getByRole('link', { name: /TIDAL 인증 페이지 열기/ }).locator('button')).toHaveCount(0)
    await expect(page.getByText('https://link.tidal.com/ABCD-EFGH')).toBeVisible()
    await expect(page.getByRole('button', { name: /코드 입력 완료, 연결 확인/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /새 TIDAL 코드 발급/ })).toBeEnabled()
    await expect.poll(() => pollCount).toBeGreaterThan(0)
    const firstCodePollCount = pollCount

    await page.getByRole('button', { name: /새 TIDAL 코드 발급/ }).click()
    await expect.poll(() => deviceStartCount).toBe(2)
    await expect.poll(getWindowOpenCount).toBe(0)
    await expect(page.getByText('NEWCODE', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /코드 입력 완료, 연결 확인/ })).toBeEnabled()
    await expect.poll(() => pollCount).toBeGreaterThan(firstCodePollCount)
})

test('만료된 TIDAL device 인증 코드는 poll을 시작하지 않고 새 코드 발급을 안내한다', async ({ page }) => {
    let pollCount = 0
    let deviceStartCount = 0

    await page.route('**/api/v1/auth/register', async (route) => {
        fulfillJson(route, registerResponse(route.request().postDataJSON().preferred_platform_id))
    })
    await page.route('**/api/v1/platforms/connections/bootstrap**', (route) =>
        fulfillJson(route, connectionBootstrap('tidal')),
    )
    await page.route('**/api/v1/platforms/oauth/tidal/device/start', (route) => {
        deviceStartCount += 1
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_pending',
            generated_at: generatedAt,
            user_id: 'signup-user',
            authorization: {
                device_code: deviceStartCount === 1 ? 'expired-tidal-device-code' : 'fresh-tidal-device-code',
                user_code: deviceStartCount === 1 ? 'EXPIRED' : 'FRESH',
                verification_uri: 'https://link.tidal.com',
                verification_uri_complete: deviceStartCount === 1
                    ? 'https://link.tidal.com/EXPIRED'
                    : 'https://link.tidal.com/FRESH',
                expires_at: deviceStartCount === 1 ? '2020-01-01T00:00:00Z' : activeDeviceExpiresAt,
                interval_seconds: 1,
                requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            },
        })
    })
    await page.route('**/api/v1/platforms/oauth/tidal/device/poll', async (route) => {
        pollCount += 1
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_pending',
            processed_at: generatedAt,
            user_id: 'signup-user',
            requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            connection: null,
            message: 'TIDAL authorization is still pending.',
        })
    })

    await page.goto('/signup')
    await page.locator('select').selectOption('tidal')
    await fillSignupForm(page)
    await page.getByRole('button', { name: /계정 만들고 계속하기/ }).click()

    await expect(page.getByText('TIDAL 인증 코드가 만료되었습니다. 새 코드를 발급받아 다시 인증해 주세요.')).toBeVisible()
    await page.waitForTimeout(1200)
    expect(pollCount).toBe(0)
    await expect(page.getByRole('button', { name: /새 TIDAL 코드 발급/ })).toBeEnabled()

    await page.getByRole('button', { name: /새 TIDAL 코드 발급/ }).click()

    await expect.poll(() => deviceStartCount).toBe(2)
    await expect(page.getByText('FRESH', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: /코드 입력 완료, 연결 확인/ })).toBeEnabled()
})

test('TIDAL device 인증 완료 후 별도 API로 PMS 플레이리스트를 저장한 뒤 PMS로 이동한다', async ({ page }) => {
    let deviceStartPayload: Record<string, unknown> | null = null
    let devicePollPayload: Record<string, unknown> | null = null
    let importPayload: Record<string, unknown> | null = null

    await page.route('**/api/v1/auth/register', async (route) => {
        fulfillJson(route, registerResponse(route.request().postDataJSON().preferred_platform_id))
    })
    await page.route('**/api/v1/platforms/connections/bootstrap**', (route) =>
        fulfillJson(route, connectionBootstrap('tidal')),
    )
    await page.route('**/api/v1/platforms/oauth/tidal/device/start', async (route) => {
        deviceStartPayload = route.request().postDataJSON()
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_pending',
            generated_at: generatedAt,
            user_id: 'signup-user',
            authorization: {
                device_code: 'tidal-device-code',
                user_code: 'ABCD-EFGH',
                verification_uri: 'https://link.tidal.com',
                verification_uri_complete: 'https://link.tidal.com/ABCD-EFGH',
                expires_at: activeDeviceExpiresAt,
                interval_seconds: 1,
                requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            },
        })
    })
    await page.route('**/api/v1/platforms/oauth/tidal/device/poll', async (route) => {
        devicePollPayload = route.request().postDataJSON()
        fulfillJson(route, {
            service: 'tidal-device-authorization',
            status: 'authorization_completed',
            processed_at: generatedAt,
            user_id: 'signup-user',
            requested_scopes: ['r_usr', 'w_usr', 'w_sub'],
            connection: {
                user_id: 'signup-user',
                platform_id: 'tidal',
                connected: true,
                connection_status: 'connected',
                connection_mode: 'tidal-device-code',
                external_account_label: 'tidal-signup-account',
                scope_summary: 'r_usr, w_usr, w_sub',
                sync_ready: true,
                connected_at: generatedAt,
            },
            message: 'TIDAL 연결이 완료됐습니다.',
        })
    })
    await page.route('**/api/v1/pms/workspace/bootstrap**', (route) =>
        fulfillJson(route, importPayload ? importedWorkspaceResponse('tidal') : workspaceResponse),
    )
    await page.route('**/api/v1/pms/import/bootstrap**', (route) => fulfillJson(route, pmsImportBootstrap('tidal')))
    await page.route('**/api/v1/pms/import/playlists', async (route) => {
        importPayload = route.request().postDataJSON()
        fulfillJson(route, pmsImportResult('tidal'))
    })
    await page.route('**/api/v1/pms/personal-playlists/bootstrap**', (route) =>
        fulfillJson(route, personalPlaylistsBootstrap),
    )

    await page.goto('/signup')
    await page.locator('select').selectOption('tidal')
    await fillSignupForm(page)
    await page.getByRole('button', { name: /계정 만들고 계속하기/ }).click()

    await expect.poll(() => deviceStartPayload?.user_id).toBe('signup-user')
    await expect.poll(() => devicePollPayload?.device_code).toBe('tidal-device-code')
    await expect(page).toHaveURL(/\/pms/)
    await expect.poll(() => importPayload).toMatchObject({
        user_id: 'signup-user',
        platform_id: 'tidal',
        external_playlist_ids: ['tidal-signup-playlist'],
    })
    await expect(page.getByText('플랫폼 플레이리스트 1개와 12곡을 PMS에 원본 그대로 저장했습니다.')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'TIDAL 가입 플레이리스트' }).first()).toBeVisible()
})

test('Spotify OAuth 완료 후 별도 API로 PMS 플레이리스트를 저장한 뒤 PMS로 이동한다', async ({ page }) => {
    let importPayload: Record<string, unknown> | null = null

    const pendingAuthorization = {
        service: 'platform-oauth',
        status: 'authorization_started',
        generated_at: generatedAt,
        user: {
            user_id: 'signup-user',
            display_name: '가입 테스트',
            email: 'signup@example.com',
        },
        authorization: {
            state: 'signup-spotify',
            platform_id: 'spotify',
            platform_display_name: 'Spotify',
            authorization_mode: 'oauth',
            authorization_channel: 'external_browser_redirect',
            requested_scopes: ['playlist-read-private'],
            expires_at: '2026-05-26T00:10:00Z',
            approval_page_path: null,
            callback_path: '/platforms/oauth/callback',
            approval_code: null,
            external_authorization_url: 'https://accounts.spotify.com/authorize',
            redirect_uri: 'https://imapplepie20.tplinkdns.com/platforms/oauth/callback',
        },
    }

    await page.addInitScript((authorization) => {
        window.localStorage.setItem(
            'my-forever-music.auth-session',
            JSON.stringify({
                userId: 'signup-user',
                email: 'signup@example.com',
                displayName: '가입 테스트',
                preferredPlatformId: 'spotify',
                onboardingStage: 'connect-platform',
                registeredAt: '2026-05-26T00:00:00Z',
                platformConnectionRequired: true,
                nextStepPath: '/platforms',
                nextStepMessage: '선택한 스트리밍 플랫폼 인증을 완료하세요.',
            }),
        )
        window.sessionStorage.setItem(
            'my-forever-music.platform-oauth-session.signup-spotify',
            JSON.stringify(authorization),
        )
    }, pendingAuthorization)

    await page.route('**/api/v1/platforms/oauth/complete', (route) =>
        fulfillJson(route, {
            service: 'platform-oauth',
            status: 'authorization_completed',
            processed_at: generatedAt,
            authorization: {
                state: 'signup-spotify',
                platform_id: 'spotify',
                platform_display_name: 'Spotify',
                authorization_mode: 'oauth',
                requested_scopes: ['playlist-read-private'],
                completed_at: generatedAt,
            },
            connection: {
                user_id: 'signup-user',
                platform_id: 'spotify',
                connected: true,
                connection_status: 'connected',
                connection_mode: 'oauth',
                external_account_label: 'spotify-signup-account',
                scope_summary: 'playlist-read-private',
                sync_ready: true,
                connected_at: generatedAt,
            },
            next_step: {
                path: '/pms',
                message: '플랫폼 연결이 완료됐습니다. PMS 가져오기를 계속하세요.',
            },
        }),
    )
    await page.route('**/api/v1/pms/workspace/bootstrap**', (route) =>
        fulfillJson(route, importPayload ? importedWorkspaceResponse('spotify') : workspaceResponse),
    )
    await page.route('**/api/v1/pms/import/bootstrap**', (route) => fulfillJson(route, pmsImportBootstrap('spotify')))
    await page.route('**/api/v1/pms/import/playlists', async (route) => {
        importPayload = route.request().postDataJSON()
        fulfillJson(route, pmsImportResult('spotify'))
    })
    await page.route('**/api/v1/pms/personal-playlists/bootstrap**', (route) =>
        fulfillJson(route, personalPlaylistsBootstrap),
    )

    await page.goto('/platforms/oauth/callback?state=signup-spotify&code=spotify-code')

    await expect(page).toHaveURL(/\/pms/)
    await expect.poll(() => importPayload).toMatchObject({
        user_id: 'signup-user',
        platform_id: 'spotify',
        external_playlist_ids: ['spotify-signup-playlist'],
    })
    await expect(page.getByText('플랫폼 플레이리스트 1개와 12곡을 PMS에 원본 그대로 저장했습니다.')).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Spotify 가입 플레이리스트' }).first()).toBeVisible()
})
