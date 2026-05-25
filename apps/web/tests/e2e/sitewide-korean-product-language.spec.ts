import { expect, test, type Route } from '@playwright/test'

const userSession = {
    userId: 'user-language-e2e',
    email: 'language@example.com',
    displayName: '한국어 사용자',
    preferredPlatformId: 'tidal',
    onboardingStage: 'ready',
    registeredAt: '2026-05-26T00:00:00Z',
    platformConnectionRequired: false,
    nextStepPath: '/gms-preview',
    nextStepMessage: '추천 검토를 이어갈 수 있습니다.',
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
    }, userSession)

    await page.route('**/api/v1/system/info', (route) =>
        fulfillJson(route, {
            service: 'api',
            status: 'ok',
            message: 'ready',
            timestamp: '2026-05-26T00:00:00Z',
        }),
    )
})

test('primary shell uses Korean product language', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('link', { name: /내 음악\(PMS\)/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /음악 탐색\(EMS\)/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /추천 검토\(GMS\)/ })).toBeVisible()
    await expect(page.getByText('플랫폼에서 가져온 음악을 내 보관함에 남기고')).toBeVisible()
    await expect(page.getByText('Delivery Snapshot')).toHaveCount(0)
})

test('login page explains the user flow in Korean', async ({ page }) => {
    await page.goto('/login')

    await expect(page.getByRole('heading', { name: '이어서 듣고 추천받기 위해 다시 로그인하세요.' })).toBeVisible()
    await expect(page.getByText('계정 복원')).toBeVisible()
    await expect(page.getByRole('button', { name: '로그인' })).toBeVisible()
    await expect(page.getByText('Session Restore')).toHaveCount(0)
})
