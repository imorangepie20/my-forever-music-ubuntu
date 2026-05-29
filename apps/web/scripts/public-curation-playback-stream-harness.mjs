import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const assertIncludes = (path, needle) => {
    const source = read(path)
    return {
        name: `${path} includes ${needle}`,
        passed: source.includes(needle),
        detail: `${path} should include ${needle}.`,
    }
}

const checks = [
    assertIncludes('src/services/api.ts', 'fetchPublicCurationTidalPlaybackStream'),
    assertIncludes('src/types/api.ts', 'PublicCurationPlaybackStreamResponse'),
    assertIncludes('src/lib/tidalStreamPlayback.ts', 'playPublicCurationTidalTrack'),
    assertIncludes('src/pages/PublicCurationSharePage.tsx', 'fetchPublicCurationPlaybackSession'),
    assertIncludes('src/pages/PublicCurationSharePage.tsx', 'playPublicCurationTidalTrack'),
    assertIncludes('src/pages/PublicCurationSharePage.tsx', 'recordPublicCurationPlaybackEvent'),
    assertIncludes('src/pages/PublicCurationSharePage.tsx', '지금 재생 중'),
]

const failed = checks.filter((result) => !result.passed)

for (const result of checks) {
    const prefix = result.passed ? 'PASS' : 'FAIL'
    console.log(`${prefix} ${result.name}`)
    if (!result.passed) {
        console.log(`     ${result.detail}`)
    }
}

if (failed.length > 0) {
    console.error(`\nPublic curation playback stream harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nPublic curation playback stream harness passed.')
