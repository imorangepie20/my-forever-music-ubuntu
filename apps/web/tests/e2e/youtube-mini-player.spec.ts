import { expect, test, type Route } from '@playwright/test'

const generatedAt = '2026-05-30T00:00:00Z'

const fulfillJson = (route: Route, body: unknown) =>
    route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
    })

test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
        window.localStorage.setItem(
            'my-forever-music.auth-session',
            JSON.stringify({
                userId: 'youtube-mini-player-user',
                email: 'listener@example.com',
                displayName: 'YouTube Listener',
                preferredPlatformId: 'tidal',
                onboardingStage: 'ready',
                registeredAt: '2026-05-30T00:00:00Z',
                platformConnectionRequired: false,
                nextStepPath: '/',
                nextStepMessage: 'ready',
            }),
        )

        const playerState = {
            state: 5,
            currentTime: 0,
            duration: 214,
            volume: 50,
            videoId: null as string | null,
        }

        const playerStateValues = {
            ENDED: 0,
            PLAYING: 1,
            PAUSED: 2,
            BUFFERING: 3,
            CUED: 5,
        }

        window.YT = {
            PlayerState: playerStateValues,
            Player: function Player(element: HTMLElement, options: {
                videoId?: string
                events: {
                    onReady: (event: { target: unknown; data: number }) => void
                    onStateChange: (event: { target: unknown; data: number }) => void
                }
            }) {
                const player = {
                    loadVideoById: (videoId: string) => {
                        playerState.videoId = videoId
                    },
                    playVideo: () => {
                        playerState.state = playerStateValues.PLAYING
                        options.events.onStateChange({ target: player, data: playerStateValues.PLAYING })
                    },
                    pauseVideo: () => {
                        playerState.state = playerStateValues.PAUSED
                    },
                    stopVideo: () => {
                        playerState.state = playerStateValues.CUED
                    },
                    seekTo: (seconds: number) => {
                        playerState.currentTime = seconds
                    },
                    setVolume: (volume: number) => {
                        playerState.volume = volume
                    },
                    getCurrentTime: () => playerState.currentTime,
                    getDuration: () => playerState.duration,
                    getPlayerState: () => playerState.state,
                    destroy: () => undefined,
                }

                playerState.videoId = options.videoId ?? null
                element.dataset.youtubeMockPlayer = 'mounted'
                window.setTimeout(() => options.events.onReady({ target: player, data: playerStateValues.CUED }), 0)
                return player
            },
        }
    })

    await page.route('**/api/v1/user/music-events', (route) =>
        fulfillJson(route, {
            service: 'user-music-event',
            status: 'recorded',
            processed_at: generatedAt,
        }),
    )
})

test('YouTube 재생 화면을 클릭하면 앱 내부 미니 플레이어가 열린다', async ({ page }) => {
    await page.goto('/playback-harness')

    await page.getByRole('button', { name: 'YouTube 테스트 재생' }).click()
    await expect(page.getByTitle('YouTube 플레이어')).toBeVisible()

    await page.getByRole('button', { name: 'YouTube 미니 플레이어 열기' }).click()

    await expect(page.getByRole('dialog', { name: 'YouTube 미니 플레이어' })).toBeVisible()
    await expect(page.getByTitle('YouTube 플레이어')).toHaveClass(/fixed/)

    await page.getByRole('button', { name: 'YouTube 미니 플레이어 닫기' }).click()

    await expect(page.getByRole('dialog', { name: 'YouTube 미니 플레이어' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'YouTube 미니 플레이어 열기' })).toBeVisible()
})
