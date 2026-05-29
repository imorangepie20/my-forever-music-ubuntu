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
        /publishPublicCurationPlaylist/.test(files.api) &&
        /\/api\/v1\/public-curations\/admin\/runs/.test(files.api) &&
        /\/api\/v1\/public-curations\/admin\/playlists/.test(files.api),
    'src/services/api.ts should expose draft generation and publish calls.',
)

check(
    'Public curation admin types include request filters and tracks',
    /interface PublicCurationAdminRunRequest/.test(files.types) &&
        /audio_feature_ranges/.test(files.types) &&
        /interface PublicCurationAdminTrack/.test(files.types) &&
        /PublicCurationAdminRunResponse/.test(files.types),
    'src/types/api.ts should define request, response, and generated track preview types.',
)

check(
    'Public curation admin page exposes Korean operator workflow',
    /공개 큐레이션 생성/.test(files.page) &&
        /모델 실행/.test(files.page) &&
        /발행하기/.test(files.page) &&
        /공유 링크/.test(files.page) &&
        /수동 선별/.test(files.page) === false,
    'PublicCurationAdminPage.tsx should present the model-run workflow without manual curation language.',
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
