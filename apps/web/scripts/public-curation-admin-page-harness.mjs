import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const files = {
    app: read('src/App.tsx'),
    api: read('src/services/api.ts'),
    types: read('src/types/api.ts'),
    page: read('src/pages/PublicCurationAdminPage.tsx'),
}

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

check(
    'Public curation admin route is mounted in app shell',
    /PublicCurationAdminPage/.test(files.app) &&
        /path="admin\/public-curations"/.test(files.app) &&
        files.app.indexOf('path="admin/public-curations"') < files.app.indexOf('</Route>'),
    'App.tsx should route /admin/public-curations inside MainLayout.',
)

check(
    'Public curation admin API client exists',
        /createPublicCurationDraft/.test(files.api) &&
        /fetchPublicCurationAdminPlaylists/.test(files.api) &&
        /publishPublicCurationPlaylist/.test(files.api) &&
        /deletePublicCurationPlaylist/.test(files.api) &&
        /\/api\/v1\/public-curations\/admin\/runs/.test(files.api) &&
        /\/api\/v1\/public-curations\/admin\/playlists/.test(files.api),
    'src/services/api.ts should expose saved playlist list, draft generation, publish, and delete calls.',
)

check(
    'Public curation admin types include request filters and tracks',
    /interface PublicCurationAdminRunRequest/.test(files.types) &&
        /audio_feature_ranges/.test(files.types) &&
        /interface PublicCurationAdminTrack/.test(files.types) &&
        /interface PublicCurationAdminPlaylistSummary/.test(files.types) &&
        /interface PublicCurationAdminListResponse/.test(files.types) &&
        /interface PublicCurationAdminDeleteResponse/.test(files.types) &&
        /PublicCurationAdminRunResponse/.test(files.types) &&
        /interface PublicCurationCandidatePreparationSummary/.test(files.types) &&
        /candidate_preparation/.test(files.types),
    'src/types/api.ts should define request, list, delete response, and generated track preview types.',
)

check(
    'Public curation admin page exposes Korean operator workflow',
    /공개 큐레이션 생성/.test(files.page) &&
        /모델 실행/.test(files.page) &&
        /저장된 공개 큐레이션/.test(files.page) &&
        /발행하기/.test(files.page) &&
        /공유 링크/.test(files.page) &&
        /\/mix\//.test(files.page) &&
        /rainy-night-public-curation/.test(files.page) === false &&
        /수동 선별/.test(files.page) === false,
    'PublicCurationAdminPage.tsx should present saved public mixes, short share links, and the model-run workflow without manual curation language.',
)

check(
    'Public curation admin fields include visible explanations and examples',
    /실행자를 기록합니다\. 예: admin-001/.test(files.page) &&
        /공유 URL `\/mix\/공개 코드`에 사용됩니다\. 예: rainy-night-jazz/.test(files.page) &&
        /장면, 분위기, 곡의 흐름을 자연어로 적습니다/.test(files.page) &&
        /예: rainy, night, cafe/.test(files.page) &&
        /예: indie, jazz/.test(files.page) &&
        /발행할 최종 곡 수입니다\. 예: 30/.test(files.page) &&
        /서버 안정성을 위해 최대 240까지 사용합니다\. 예: 220/.test(files.page) &&
        /최소 에너지/.test(files.page) &&
        /최대 정서 밝기/.test(files.page),
    'Each operator value should have always-visible Korean help text and an example.',
)

check(
    'Public curation admin preview renders candidate preparation summary',
    /candidatePreparationLabels/.test(files.page) &&
        /후보 준비 결과/.test(files.page) &&
        /Raw 후보/.test(files.page) &&
        /재생 가능/.test(files.page) &&
        /Resolve 성공/.test(files.page) &&
        /Resolve 성공률/.test(files.page),
    'The draft preview should show raw/playable/resolve candidate preparation counts.',
)

check(
    'Public curation admin preview renders model v2 breakdown',
    /score_summary/.test(files.types) &&
        /score_breakdown/.test(files.types) &&
        /semantic_profile_status/.test(files.page) &&
        /scoreBreakdownEntries/.test(files.page) &&
        /scoreAxisLabel/.test(files.page),
    'The admin preview should render semantic status and per-track score axes.',
)

check(
    'Public curation admin page can delete saved playlists with a custom confirmation dialog',
    /deletePublicCurationPlaylist/.test(files.page) &&
        /Trash2/.test(files.page) &&
        /deleteConfirmPlaylist/.test(files.page) &&
        /삭제 확인/.test(files.page) &&
        /삭제하면 발행된 공유 링크도 더 이상 열리지 않습니다/.test(files.page) &&
        /공유 플레이리스트를 삭제했습니다/.test(files.page) &&
        />\s*취소\s*</.test(files.page) &&
        />\s*삭제\s*</.test(files.page) &&
        !/window\.confirm/.test(files.page),
    'PublicCurationAdminPage.tsx should use an in-app custom delete confirmation dialog instead of window.confirm.',
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
    console.error(`\nPublic curation admin page harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nPublic curation admin page harness passed.')
