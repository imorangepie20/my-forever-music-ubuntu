import { expect, test } from '@playwright/test'

test('recommendation explainer shows the advanced playlist recommendation diagrams', async ({ page }) => {
    await page.goto('/about/recommendation')

    await expect(page.getByRole('heading', { name: 'Recommendation Operating System' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'PMS Intake' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Candidate Refinery' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Feedback Flywheel' })).toBeVisible()
    await expect(page.getByLabel('Playlist recommendation signal flow diagram')).toBeVisible()
    await expect(page.getByLabel('Recommendation color legend')).toBeVisible()
    await expect(page.getByText('PMS · Mint')).toBeVisible()
    await expect(page.getByText('EMS · Violet')).toBeVisible()
    await expect(page.getByText('GMS · Gold')).toBeVisible()
    await expect(page.getByText('Feedback · Green')).toBeVisible()
    await expect(page.getByText('Taste Graph')).toBeVisible()
    await expect(page.getByText('Scoring Engine')).toBeVisible()
    await expect(page.getByText('GMS Playlist Output')).toBeVisible()
    await expect(page.getByText('Feedback Loop', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: '6-axis verdict board' })).toBeVisible()
    await expect(page.getByRole('link', { name: '추천 플레이리스트 보기' })).toHaveAttribute('href', '/gms-playlists')
})
