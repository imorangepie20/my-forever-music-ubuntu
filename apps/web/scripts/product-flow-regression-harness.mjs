import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => readFileSync(join(root, path), 'utf8')
const readOptional = (path) => {
    try {
        return read(path)
    } catch {
        return ''
    }
}

const userFacingFlowFiles = {
    pms: read('src/pages/PmsPage.tsx'),
    ems: read('src/pages/EmsPage.tsx'),
    emsSearchPlaylistDetail: read('src/pages/EmsSearchPlaylistDetailPage.tsx'),
    gms: read('src/pages/GmsPreviewPage.tsx'),
    platforms: read('src/pages/PlatformsPage.tsx'),
    melonHot100: read('src/pages/MelonHot100Page.tsx'),
    home: read('src/pages/HomePage.tsx'),
    spotifyFeaturedCharts: readOptional('src/components/home/PopularSpotifyFeaturedChartsSection.tsx'),
    tidalHomeSections: read('src/components/home/TidalHomePageSections.tsx'),
    header: read('src/components/layout/Header.tsx'),
    sidebar: read('src/components/layout/Sidebar.tsx'),
    trackCard: read('src/components/music/TrackFeatureCard.tsx'),
    app: read('src/App.tsx'),
    api: read('src/services/api.ts'),
}

const workspaceFiles = {
    context: read('src/contexts/RecommendationWorkspaceContext.tsx'),
    state: read('src/types/workspace.ts'),
}

const adminFlowFiles = {
    schedules: read('src/pages/SchedulingAdminPage.tsx'),
    errorLogs: readOptional('src/pages/ErrorLogsAdminPage.tsx'),
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

check(
    'EMS page surfaces Spotify Featured Charts as direct discovery content',
    /Spotify Featured Charts/.test(userFacingFlowFiles.ems) &&
        /fetchEmsCollectedPlaylists/.test(userFacingFlowFiles.ems) &&
        /spotify_featured_charts/.test(userFacingFlowFiles.ems) &&
        /queueEmsSpotifyFeaturedCharts/.test(userFacingFlowFiles.ems),
    'EMS should queue Spotify official charts into EMS POOL, then display only DB-collected playlist cards.',
)

check(
    'Home page surfaces DB-collected Spotify Featured Charts',
    /PopularSpotifyFeaturedChartsSection/.test(userFacingFlowFiles.home) &&
        /fetchEmsCollectedPlaylists\('spotify'/.test(userFacingFlowFiles.spotifyFeaturedCharts) &&
        /spotify_featured_charts/.test(userFacingFlowFiles.spotifyFeaturedCharts) &&
        /slice\(0,\s*LIMIT\)/.test(userFacingFlowFiles.spotifyFeaturedCharts),
    'Home should display up to four Spotify Featured Charts cards from EMS DB reads.',
)

check(
    'EMS TIDAL home sections use DB paging instead of client-side truncation',
    /fetchEmsTidalHomePlaylists/.test(userFacingFlowFiles.tidalHomeSections) &&
        /total_pages/.test(userFacingFlowFiles.tidalHomeSections) &&
        !/fetchEmsCollectedPlaylists\('tidal'/.test(userFacingFlowFiles.tidalHomeSections) &&
        !/PER_SOURCE_LIMIT/.test(userFacingFlowFiles.tidalHomeSections),
    'TIDAL home sections should request source-specific DB pages and render independent paging controls.',
)

check(
    'EMS page does not render live provider search result lists',
    !/pagedSearchPlaylists|pagedSearchTracks|toEmsSearchTrackPlaybackItem|writeSearchPlaylistCache|searchResult\?\.playlists|searchResult\?\.tracks|buildEmsSearchPlaylistDetailPath/.test(userFacingFlowFiles.ems),
    'EMS search may queue provider results, but playlist/track cards must come from ems_collected_* DB reads.',
)

check(
    'EMS search playlist route stores then redirects to DB playlist detail',
    /fetchEmsSearchPlaylistTracks/.test(userFacingFlowFiles.emsSearchPlaylistDetail) &&
        /buildEmsPlaylistDetailPath/.test(userFacingFlowFiles.emsSearchPlaylistDetail) &&
        /replace:\s*true/.test(userFacingFlowFiles.emsSearchPlaylistDetail) &&
        !/toEmsSearchTrackPlaybackItem|Provider playlist tracks are loaded on demand|tracks\.map/.test(userFacingFlowFiles.emsSearchPlaylistDetail),
    'Legacy EMS search playlist URLs should be collection bridges only, never provider-track display pages.',
)

const melonOperatorMutationMatches = scan(
    {
        ems: userFacingFlowFiles.ems,
        melonHot100: userFacingFlowFiles.melonHot100,
    },
    /triggerMelonScrape|handleRefreshMelonHot100|isRefreshingMelonHot100|Melon Hot 100을 업데이트|업데이트를 실행|업데이트 중/,
)
check(
    'User-facing Melon Hot 100 surfaces do not expose operator update controls',
    melonOperatorMutationMatches.length === 0,
    `Melon operator update controls found in: ${melonOperatorMutationMatches.join(', ')}`,
)

check(
    'Melon Hot 100 refresh is managed from the admin scheduling page',
    /triggerMelonScrape/.test(adminFlowFiles.schedules) &&
        /melon-hot-100-scrape/.test(adminFlowFiles.schedules) &&
        /Melon Hot 100/.test(adminFlowFiles.schedules),
    'Melon scrape trigger should be reachable only from SchedulingAdminPage.',
)

check(
    'Major service errors are routed to an admin-only error log page',
    /admin\/error-logs/.test(userFacingFlowFiles.app) &&
        /에러 로그/.test(userFacingFlowFiles.sidebar) &&
        /fetchApplicationErrorLogs/.test(userFacingFlowFiles.api) &&
        /recordClientApplicationError/.test(userFacingFlowFiles.api) &&
        /운영자 전용/.test(adminFlowFiles.errorLogs),
    'Application errors should be collected and reviewed from the admin-only error log page.',
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
