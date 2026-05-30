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
    page: read('src/pages/PublicCurationSharePage.tsx'),
}

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

check(
    'Public share route is mounted outside the authenticated app shell',
    /PublicCurationSharePage/.test(files.app) &&
        /path="mix\/:slug"/.test(files.app) &&
        /path="share\/playlists\/:slug"/.test(files.app) &&
        files.app.indexOf('path="share/playlists/:slug"') > files.app.indexOf('</Route>'),
    'App.tsx should route /mix/:slug and legacy /share/playlists/:slug without MainLayout.',
)

check(
    'Public share API client exists',
    /fetchPublicCurationShare/.test(files.api) &&
        /\/api\/v1\/public-curations\/share/.test(files.api) &&
        /PublicCurationShareResponse/.test(files.api),
    'src/services/api.ts should fetch the public curation share payload.',
)

check(
    'Public share response types include playlist tracks',
    /interface PublicCurationShareResponse/.test(files.types) &&
        /interface PublicCurationShareTrack/.test(files.types) &&
        /tidal_track_id/.test(files.types) &&
        /reason/.test(files.types),
    'src/types/api.ts should define the public share playlist and track payload.',
)

check(
    'Public share page renders a magazine-style playlist page in Korean',
    /공개 큐레이션/.test(files.page) &&
        /TIDAL로 여기서 듣기/.test(files.page) &&
        /추천 이유/.test(files.page) &&
        /일반 앱 shell/.test(files.page) === false,
    'PublicCurationSharePage.tsx should be Korean-facing and not describe internal shell behavior.',
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
    console.error(`\nPublic curation share page harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nPublic curation share page harness passed.')
