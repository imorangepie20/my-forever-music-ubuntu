import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => readFileSync(join(root, path), 'utf8')

const userFacingFlowFiles = {
    pms: read('src/pages/PmsPage.tsx'),
    ems: read('src/pages/EmsPage.tsx'),
    gms: read('src/pages/GmsPreviewPage.tsx'),
    platforms: read('src/pages/PlatformsPage.tsx'),
    home: read('src/pages/HomePage.tsx'),
    header: read('src/components/layout/Header.tsx'),
    sidebar: read('src/components/layout/Sidebar.tsx'),
    trackCard: read('src/components/music/TrackFeatureCard.tsx'),
}

const workspaceFiles = {
    context: read('src/contexts/RecommendationWorkspaceContext.tsx'),
    state: read('src/types/workspace.ts'),
}

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

const scan = (files, pattern) =>
    Object.entries(files)
        .filter(([, content]) => pattern.test(content))
        .map(([name]) => name)

const manualSeedUiPattern =
    /Seed Workspace|Use as Seed|Seed Track|Seed Artist|Seed Genre|PMS Seeds|Artist Seeds|Genre Seeds|EMS Seeds|as EMS Seeds|copy top artists/i

const manualSeedStatePattern =
    /seedTrackIdsText|seedArtistNamesText|seedGenresText|seedTrackCount|seedArtistCount|seedGenreCount|onUseAsSeed|mergeCsv|splitField/

const manualSeedPayloadPattern =
    /seed_track_ids|seed_artist_names|seed_genres/

const manualSeedUiMatches = scan(userFacingFlowFiles, manualSeedUiPattern)
check(
    'User-facing PMS/EMS/GMS pages do not expose manual seed controls',
    manualSeedUiMatches.length === 0,
    `Manual seed UI copy found in: ${manualSeedUiMatches.join(', ')}`,
)

const manualSeedStateMatches = scan({ ...userFacingFlowFiles, ...workspaceFiles }, manualSeedStatePattern)
check(
    'Web workspace state does not store editable manual seed fields',
    manualSeedStateMatches.length === 0,
    `Manual seed state or actions found in: ${manualSeedStateMatches.join(', ')}`,
)

const manualSeedPayloadMatches = scan(
    {
        ems: userFacingFlowFiles.ems,
        gms: userFacingFlowFiles.gms,
    },
    manualSeedPayloadPattern,
)
check(
    'EMS and GMS page requests rely on user and playlist context, not manual seed arrays',
    manualSeedPayloadMatches.length === 0,
    `Manual seed payload fields found in: ${manualSeedPayloadMatches.join(', ')}`,
)

const flowCopy = `${userFacingFlowFiles.header}\n${userFacingFlowFiles.sidebar}\n${userFacingFlowFiles.pms}\n${userFacingFlowFiles.ems}\n${userFacingFlowFiles.gms}`
check(
    'Navigation names reflect PMS library, EMS model, and GMS approval flow',
    /내 음악 보관함\(PMS\)|내 음악\(PMS\)/.test(flowCopy) &&
        /음악 탐색 풀\(EMS\)|음악 탐색\(EMS\)/.test(flowCopy) &&
        /추천 게이트\(GMS\)|추천 검토\(GMS\)/.test(flowCopy),
    'Top-level copy should describe the product loop instead of a manual tuning workspace.',
)

const failed = checks.filter((result) => !result.passed)

for (const result of checks) {
    const prefix = result.passed ? 'PASS' : 'FAIL'
    console.log(`${prefix} ${result.name}`)
    if (!result.passed) {
        console.log(`     ${result.detail}`)
    }
}

if (failed.length > 0) {
    console.error(`\nProduct flow regression harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nProduct flow regression harness passed.')
