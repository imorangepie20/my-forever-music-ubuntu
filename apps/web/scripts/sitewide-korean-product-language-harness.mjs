import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const root = process.cwd()

const requiredByFile = new Map([
  ['src/lib/productLanguage.ts', ['내 음악 보관함(PMS)', '음악 탐색 풀(EMS)', '추천 게이트(GMS)', '운영자 전용 진단']],
  ['src/components/layout/Sidebar.tsx', ['내 음악(PMS)', '음악 탐색(EMS)', '추천 검토(GMS)']],
  ['src/components/layout/Header.tsx', ['내 음악 보관함(PMS)', '음악 탐색 풀(EMS)', '추천 게이트(GMS)']],
  ['src/pages/GmsPreviewPage.tsx', ['PAGE_EXPLANATIONS.gmsPreview', 'OperatorDiagnosticsNotice']],
  ['src/pages/PmsPage.tsx', ['PAGE_EXPLANATIONS.pms']],
  ['src/pages/EmsPage.tsx', ['PAGE_EXPLANATIONS.ems']],
  ['src/pages/auth/Login.tsx', ['로그인', '이어서 듣고 추천받기']],
  ['src/pages/auth/Register.tsx', ['회원가입', '내 음악 보관함(PMS)']],
])

const forbiddenByFile = new Map([
  ['src/pages/GmsPreviewPage.tsx', ['Recommendation Candidates', 'Response Feed', 'Request GMS Preview', 'Gate dry run']],
  ['src/pages/HomePage.tsx', ['Delivery Snapshot', 'Continue Onboarding', 'Open Platform Intake', 'Open GMS Preview']],
  ['src/pages/auth/Login.tsx', ['Session Restore', 'Sign back in', 'Continue local testing', 'Signing In...']],
  ['src/components/music/PlaybackDock.tsx', ['Preparing playback...', 'Shuffle on', 'Repeat queue', 'Open visualizer']],
])

const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')

const failures = []

for (const [relativePath, requiredTexts] of requiredByFile) {
  const content = read(relativePath)
  for (const text of requiredTexts) {
    if (!content.includes(text)) {
      failures.push(`${relativePath} is missing required Korean copy: ${text}`)
    }
  }
}

for (const [relativePath, forbiddenTexts] of forbiddenByFile) {
  const content = read(relativePath)
  for (const text of forbiddenTexts) {
    if (content.includes(text)) {
      failures.push(`${relativePath} still contains old English product copy: ${text}`)
    }
  }
}

if (failures.length > 0) {
  console.error('[sitewide-korean-product-language] failed')
  for (const failure of failures) {
    console.error(`- ${failure}`)
  }
  process.exit(1)
}

console.log('[sitewide-korean-product-language] ok')
