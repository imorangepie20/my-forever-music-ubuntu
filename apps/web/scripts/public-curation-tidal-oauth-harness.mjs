import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const files = {
    api: read('src/services/api.ts'),
    types: read('src/types/api.ts'),
    page: read('src/pages/PublicCurationSharePage.tsx'),
    callback: read('src/pages/platforms/PlatformOAuthCallbackPage.tsx'),
}

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

check(
    'Public curation TIDAL OAuth API client exists',
    /startPublicCurationTidalOAuth/.test(files.api) &&
        /completePublicCurationTidalOAuth/.test(files.api) &&
        /fetchPublicCurationPlaybackSession/.test(files.api),
    'src/services/api.ts should expose start, complete, and session lookup calls.',
)

check(
    'Public curation TIDAL OAuth response types exist',
    /PublicCurationTidalOAuthStartResponse/.test(files.types) &&
        /PublicCurationTidalOAuthCompleteResponse/.test(files.types) &&
        /PublicCurationPlaybackSessionResponse/.test(files.types) &&
        /external_authorization_url/.test(files.types),
    'src/types/api.ts should define public OAuth start, complete, and playback session payloads.',
)

check(
    'Public share page starts OAuth and stores callback context',
    /PUBLIC_CURATION_OAUTH_STORAGE_KEY/.test(files.page) &&
        /startPublicCurationTidalOAuth\(slug/.test(files.page) &&
        /window\.sessionStorage\.setItem/.test(files.page) &&
        /window\.location\.assign\(response\.authorization\.external_authorization_url\)/.test(files.page),
    'PublicCurationSharePage.tsx should start public TIDAL OAuth and redirect to TIDAL.',
)

check(
    'Public share play CTA is active',
    /TIDAL로 여기서 듣기/.test(files.page) &&
        !/disabled\s*\n/.test(files.page) &&
        !/cursor-not-allowed/.test(files.page),
    'The public share play button should be active once public OAuth is available.',
)

check(
    'OAuth callback branches public curation completion',
    /completePublicCurationTidalOAuth/.test(files.callback) &&
        /PUBLIC_CURATION_OAUTH_STORAGE_KEY/.test(files.callback) &&
        /public-curation/.test(files.callback) &&
        /return_path/.test(files.callback),
    'PlatformOAuthCallbackPage.tsx should complete public curation OAuth separately from user platform connection.',
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
    console.error(`\nPublic curation TIDAL OAuth harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nPublic curation TIDAL OAuth harness passed.')
