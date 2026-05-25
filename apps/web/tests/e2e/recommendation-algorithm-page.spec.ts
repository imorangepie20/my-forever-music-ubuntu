import { expect, test } from '@playwright/test'

test('recommendation explainer shows the advanced playlist recommendation diagrams', async ({ page }) => {
    await page.goto('/about/recommendation')

    await expect(page.getByRole('heading', { name: '추천 운영 시스템' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'PMS 입력' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'EMS 후보 정제' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '피드백 학습 루프' })).toBeVisible()
    await expect(page.getByLabel('추천 신호 흐름 도표')).toBeVisible()
    await expect(page.getByLabel('추천 색상 범례')).toBeVisible()
    await expect(page.getByText('PMS · 민트')).toBeVisible()
    await expect(page.getByText('EMS · 보라')).toBeVisible()
    await expect(page.getByText('GMS · 골드')).toBeVisible()
    await expect(page.getByText('피드백 · 그린')).toBeVisible()
    await expect(page.getByText('취향 그래프')).toBeVisible()
    await expect(page.getByText('점수화 엔진')).toBeVisible()
    await expect(page.getByLabel('추천 신호 흐름 도표').getByText('GMS 추천 출력')).toBeVisible()
    await expect(page.getByLabel('추천 신호 흐름 도표').getByText('피드백 루프', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '6축 판정 보드' })).toBeVisible()
    await expect(page.getByRole('link', { name: '추천 플레이리스트 보기' })).toHaveAttribute('href', '/gms-playlists')
})
