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
    'Public curation TIDAL authorization API client exists',
    /startPublicCurationTidalOAuth/.test(files.api) &&
        /completePublicCurationTidalOAuth/.test(files.api) &&
        /startPublicCurationTidalDeviceAuthorization/.test(files.api) &&
        /completePublicCurationTidalDeviceAuthorization/.test(files.api) &&
        /fetchPublicCurationPlaybackSession/.test(files.api),
    'src/services/api.ts should expose OAuth legacy, device-link, and session lookup calls.',
)

check(
    'Public curation TIDAL authorization response types exist',
    /PublicCurationTidalOAuthStartResponse/.test(files.types) &&
        /PublicCurationTidalOAuthCompleteResponse/.test(files.types) &&
        /PublicCurationTidalDeviceStartResponse/.test(files.types) &&
        /PublicCurationTidalDeviceCompleteResponse/.test(files.types) &&
        /PublicCurationPlaybackSessionResponse/.test(files.types) &&
        /external_authorization_url/.test(files.types),
    'src/types/api.ts should define public OAuth, device-link, and playback session payloads.',
)

check(
    'Public share page opens TIDAL device login in a new tab without polling loops',
    /PUBLIC_CURATION_OAUTH_STORAGE_KEY/.test(files.page) &&
        /normalizeExternalTidalUrl/.test(files.page) &&
        /startPublicCurationTidalDeviceAuthorization\(slug/.test(files.page) &&
        /completePublicCurationTidalDeviceAuthorization/.test(files.page) &&
        /window\.sessionStorage\.setItem/.test(files.page) &&
        /window\.open\('about:blank', '_blank'\)/.test(files.page) &&
        /tidalTab\.opener = null/.test(files.page) &&
        /tidalTab\.location\.href = normalizeExternalTidalUrl\(verificationUrl\)/.test(files.page) &&
        /window\.open\(normalizeExternalTidalUrl\(verificationUrl\), '_blank', 'noopener,noreferrer'\)/.test(files.page) &&
        /visibilitychange/.test(files.page) &&
        /window\.addEventListener\('focus'/.test(files.page) &&
        !/setInterval\([\s\S]{0,240}completePendingDeviceAuthorization/.test(files.page),
    'PublicCurationSharePage.tsx should open absolute link.tidal.com URLs in a new tab and complete once on tab return.',
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
