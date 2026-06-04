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
    spectrumPlayer: read('src/components/public-curation/PublicMixSpectrumPlayer.tsx'),
    spectrumFrame: read('src/components/visualizer/SpectrumVisualizerFrame.tsx'),
    trackThumbnail: read('src/components/public-curation/PublicTrackThumbnail.tsx'),
    audioCapture: read('src/lib/tidalAudioCapture.ts'),
    streamPlayback: read('src/lib/tidalStreamPlayback.ts'),
    youtubePlayback: read('src/lib/youtubePlayback.ts'),
    tidalQuality: read('src/lib/tidalPlaybackQuality.ts'),
    analyser: read('src/hooks/useTidalAudioAnalyser.ts'),
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
        /path="public-curations\/:slug"/.test(files.app) &&
        /path="public-curations\/share\/:slug"/.test(files.app) &&
        files.app.indexOf('path="share/playlists/:slug"') > files.app.indexOf('</Route>'),
    'App.tsx should route /mix/:slug, legacy share paths, and backend-shaped aliases without MainLayout.',
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
        /tidal_track_id/.test(files.types),
    'src/types/api.ts should define the public share playlist and track payload.',
)

check(
    'Public share page renders a magazine-style playlist page in Korean',
    /오늘의 공유 믹스/.test(files.page) &&
        /TIDAL로 여기서 듣기/.test(files.page) &&
        /일반 앱 shell/.test(files.page) === false,
    'PublicCurationSharePage.tsx should be Korean-facing and not describe internal shell behavior.',
)

check(
    'Public share visitor copy avoids operator/model language',
    /오늘의 공유 믹스/.test(files.page) &&
        /공유 믹스를 여는 중/.test(files.page) &&
        /공유 믹스를 열 수 없습니다/.test(files.page) &&
        /이 믹스의 흐름/.test(files.page) &&
        /처음부터 끝까지 자연스럽게 이어지도록 담은 곡들입니다/.test(files.page) &&
        /TIDAL-ready/.test(files.page) === false &&
        /모델이 고른 흐름/.test(files.page) === false &&
        /후보입니다/.test(files.page) === false &&
        /Public Curation/.test(files.page) === false,
    'External share page copy should read like a playlist page, not an operator/model diagnostic surface.',
)

check(
    'Public share hero keeps playlist titles compact',
    /text-4xl font-bold leading-snug text-white md:text-6xl md:leading-snug/.test(files.page) &&
        /max-w-sm text-2xl font-bold leading-snug text-white/.test(files.page) &&
        /text-5xl font-black leading-tight text-white md:text-7xl/.test(files.page) === false &&
        /max-w-sm text-3xl font-black leading-tight text-white/.test(files.page) === false,
    'The public share hero and poster should use compact title sizes, lighter weight, and relaxed line-height.',
)

check(
    'Public share track cards hide repeated recommendation reasons',
    /추천 이유/.test(files.page) === false &&
        /track\.reason/.test(files.page) === false &&
        /scoreLabel/.test(files.page) === false &&
        /track\.score/.test(files.page) === false,
    'PublicCurationSharePage.tsx should keep repeated model reasons out of the external share cards.',
)

check(
    'Public share page exposes queue-based Play All with shuffle and repeat',
    /Play All/.test(files.page) &&
        /PublicMixSpectrumPlayer/.test(files.page) &&
        /playTrackAtQueuePosition/.test(files.page) &&
        /playbackQueue/.test(files.page) &&
        /activeQueuePosition/.test(files.page) &&
        /shuffleEnabled/.test(files.page) &&
        /repeatMode/.test(files.page) &&
        /getTidalAudioElement/.test(files.page) &&
        /getTidalCurrentSnapshot/.test(files.page) &&
        /tidalPause/.test(files.page) &&
        /tidalResume/.test(files.page) &&
        /Shuffle/.test(files.spectrumPlayer) &&
        /Repeat1/.test(files.spectrumPlayer) &&
        /SpectrumVisualizerFrame/.test(files.spectrumPlayer) &&
        /BarsVisualizer/.test(files.spectrumFrame) &&
        /LOW/.test(files.spectrumFrame) &&
        /MID/.test(files.spectrumFrame) &&
        /HIGH/.test(files.spectrumFrame),
    'PublicCurationSharePage.tsx should run a queue-based Play All flow and render shuffle/repeat controls.',
)

check(
    'Public share player exposes the shared TIDAL playback quality setting',
    /TIDAL_PLAYBACK_QUALITY_OPTIONS/.test(files.tidalQuality) &&
        /readTidalPlaybackQuality/.test(files.page) &&
        /writeTidalPlaybackQuality/.test(files.page) &&
        /tidalPlaybackQuality/.test(files.page) &&
        /playPublicCurationTidalTrack[\s\S]*tidalPlaybackQuality/.test(files.page) &&
        /preloadPublicCurationTidalAnalysis[\s\S]*tidalPlaybackQuality/.test(files.page) &&
        /TIDAL_PLAYBACK_QUALITY_OPTIONS/.test(files.spectrumPlayer) &&
        /TIDAL 재생 품질/.test(files.spectrumPlayer) &&
        /requestedQuality/.test(files.spectrumPlayer) &&
        /audioQuality/.test(files.spectrumPlayer) &&
        /const requestedQuality = tidalPlaybackQuality/.test(files.spectrumPlayer),
    'The public mix player should let visitors select TIDAL stream quality, update the displayed requested quality immediately, and use it for playback and EQ analysis.',
)

check(
    'Public share player falls back to YouTube when a TIDAL-ready track cannot start',
    /resolveYouTubePlayableItem/.test(files.page) &&
        /playYouTubeVideo/.test(files.page) &&
        /setYouTubePlayerHost/.test(files.page) &&
        /isRecoverablePublicCurationTidalPlaybackError/.test(files.page) &&
        /startYouTubeFallbackForTrack/.test(files.page) &&
        /playbackProvider/.test(files.page) &&
        /TIDAL 재생 실패로 YouTube 재생으로 전환합니다/.test(files.page) &&
        /data-public-youtube-player-host/.test(files.spectrumPlayer) &&
        /YouTube fallback/.test(files.spectrumPlayer),
    'PublicCurationSharePage.tsx should recover from track-level TIDAL stream failures by resolving and playing a YouTube target inside the public player.',
)

check(
    'Public share EQ analysis uses the public playback session proxy and preload cache',
    /fetchPublicCurationTidalPlaybackAnalysisAudio/.test(files.api) &&
        /publicCuration/.test(files.audioCapture) &&
        /publicSessionId/.test(files.audioCapture) &&
        /fetchPublicCurationTidalPlaybackAnalysisAudio/.test(files.analyser) &&
        /preloadPublicCurationTidalAnalysis/.test(files.analyser) &&
        /PUBLIC_ANALYSIS_CACHE_LIMIT/.test(files.analyser) &&
        /preloadPublicCurationTidalAnalysis/.test(files.page) &&
        /publicCurationAnalysisSource/.test(files.streamPlayback),
    'Public direct streams should reuse preloaded EQ audio through the public-session analysis endpoint.',
)

check(
    'Public share track cards and player use album thumbnails with text fallback',
    /PublicTrackThumbnail/.test(files.page) &&
        /PublicTrackThumbnail/.test(files.spectrumPlayer) &&
        /onError/.test(files.trackThumbnail) &&
        /toLocaleUpperCase/.test(files.trackThumbnail) &&
        /fallbackLabel/.test(files.trackThumbnail) &&
        /FM/.test(files.spectrumPlayer),
    'Track cards and the large player should share an album thumbnail component with an uppercase text fallback.',
)

check(
    'Public share track list highlights the active playback card',
    /playlist\.tracks\.map\(\(track, trackIndex\)/.test(files.page) &&
        /activeTrackIndex === trackIndex/.test(files.page) &&
        /data-playing-track/.test(files.page) &&
        /bg-cyan-300\/\[0\.12\]/.test(files.page),
    'The active public track card should use activeTrackIndex to expose a cyan background highlight.',
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
