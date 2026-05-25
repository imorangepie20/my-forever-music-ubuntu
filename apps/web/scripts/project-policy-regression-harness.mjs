import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join, relative } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()
const repoRoot = join(root, '../..')

const read = (path) => readFileSync(join(root, path), 'utf8')
const readIfExists = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : null
}

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

const collectFiles = (startPath, extensions) => {
    const absoluteStart = join(root, startPath)
    if (!existsSync(absoluteStart)) {
        return []
    }

    const results = []
    const walk = (directory) => {
        for (const entry of readdirSync(directory)) {
            if (entry.startsWith('.') || entry === 'node_modules' || entry === 'dist' || entry === 'build' || entry === '__pycache__') {
                continue
            }

            const absolutePath = join(directory, entry)
            const stats = statSync(absolutePath)
            if (stats.isDirectory()) {
                walk(absolutePath)
                continue
            }

            if (stats.isFile() && extensions.has(extname(entry))) {
                results.push(absolutePath)
            }
        }
    }

    walk(absoluteStart)
    return results
}

const repoAgents = read('../../AGENTS.md')
const policyFiles = {
    workspaceAgents: readIfExists('../../../AGENTS.md') ?? repoAgents,
    repoAgents,
    projectGuide: read('../../docs/PROJECT_GUIDE.md'),
    realImplementationPolicy: read('../../docs/architecture/REAL_IMPLEMENTATION_POLICY.md'),
    playbackPolicy: read('../../docs/architecture/PLAYBACK_ERROR_HANDLING_POLICY.md'),
}

check(
    'Workspace or repository AGENTS applies root-cause error handling',
    /(모든 프로젝트|이 레포)/.test(policyFiles.workspaceAgents) &&
        /근본 원인/.test(policyFiles.workspaceAgents) &&
        /우회, 회피, 임시 처리, 에러 숨김/.test(policyFiles.workspaceAgents) &&
        /최소 재현 하네스/.test(policyFiles.workspaceAgents),
    'The workspace or repository AGENTS.md must make root-cause error handling mandatory for this project.',
)

check(
    'Repository AGENTS repeats the same root-cause rule for this project',
    /근본 원인/.test(policyFiles.repoAgents) &&
        /token refresh/.test(policyFiles.repoAgents) &&
        /provider account id/.test(policyFiles.repoAgents) &&
        /격리 페이지/.test(policyFiles.repoAgents),
    'The repo-level agent guide must prevent future sessions from treating reconnect/retry as the first fix.',
)

check(
    'Real implementation policy covers all project areas',
    /## 4\. 전 프로젝트 오류 처리 원칙/.test(policyFiles.realImplementationPolicy) &&
        /재생, 플랫폼 연동, PMS\/EMS\/GMS, 인증, 저장소, SSL, AI 서비스, 데스크탑 확장/.test(policyFiles.realImplementationPolicy) &&
        /실패한 경계와 근본 원인/.test(policyFiles.realImplementationPolicy) &&
        /provider\/SDK\/API 경계/.test(policyFiles.realImplementationPolicy),
    'Root-cause error handling must live in the shared implementation policy, not only in playback docs.',
)

check(
    'Project guide points new sessions to the shared root-cause rule',
    /우회, 회피, 임시 처리, 에러 숨김/.test(policyFiles.projectGuide) &&
        /REAL_IMPLEMENTATION_POLICY/.test(policyFiles.projectGuide),
    'New sessions must see the root-cause rule before touching feature code.',
)

check(
    'Playback policy is explicitly a specialization of the project-wide rule',
    /project-wide rule/.test(policyFiles.playbackPolicy) &&
        /PMS, EMS, GMS, auth, storage, SSL, AI, desktop/.test(policyFiles.playbackPolicy),
    'Playback-specific rules should not become the only place where root-cause handling is enforced.',
)

const runtimeFiles = [
    ...collectFiles('src', new Set(['.ts', '.tsx', '.js', '.jsx'])),
    ...collectFiles('../../services/api/src/main', new Set(['.java', '.kt', '.yml', '.yaml'])),
    ...collectFiles('../../services/ai/app', new Set(['.py'])),
    ...collectFiles('../../packages/shared-types/src', new Set(['.ts'])),
    ...collectFiles('../../packages/shared-utils/src', new Set(['.ts'])),
    ...collectFiles('../../packages/design-tokens/src', new Set(['.ts', '.css'])),
]

const workaroundMarkerPattern = /\b(workaround|quick fix|hack|bypass|temporary cleanup|temporary fix)\b|임시|우회|회피|대충|일단/i
const runtimeMarkerMatches = runtimeFiles
    .map((absolutePath) => ({
        path: relative(repoRoot, absolutePath),
        content: readFileSync(absolutePath, 'utf8'),
    }))
    .filter(({ content }) => workaroundMarkerPattern.test(content))
    .map(({ path }) => path)

check(
    'Runtime source has no workaround markers across apps, services, and packages',
    runtimeMarkerMatches.length === 0,
    `Remove root-cause bypass markers from runtime source: ${runtimeMarkerMatches.join(', ')}`,
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
    console.error(`\nProject policy regression harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nProject policy regression harness passed.')
