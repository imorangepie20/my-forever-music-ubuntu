import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()

const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}
const listIfExists = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readdirSync(absolutePath) : []
}

const files = {
    playbackContext: read('src/contexts/PlaybackContext.tsx'),
    playbackDock: read('src/components/music/PlaybackDock.tsx'),
    spotifySdk: read('src/lib/spotifyPlaybackSdk.ts'),
    tidalSdk: read('src/lib/tidalPlaybackSdk.ts'),
    tidalStream: read('src/lib/tidalStreamPlayback.ts'),
    tidalQuality: read('src/lib/tidalPlaybackQuality.ts'),
    tidalPlaylistTestPage: read('src/pages/TidalPlaylistPlaybackTestPage.tsx'),
    gmsPlaylistsPage: read('src/pages/GmsPlaylistsPage.tsx'),
    pmsPlaylistDetailPage: read('src/pages/PmsPlaylistDetailPage.tsx'),
    emsPlaylistDetailPage: read('src/pages/EmsPlaylistDetailPage.tsx'),
    emsSearchPlaylistDetailPage: read('src/pages/EmsSearchPlaylistDetailPage.tsx'),
    musicPlayback: read('src/lib/musicPlayback.ts'),
    pmsPlayback: read('src/lib/pmsPlayback.ts'),
    app: read('src/App.tsx'),
    packageJson: read('package.json'),
    applicationYml: read('../../services/api/src/main/resources/application.yml'),
    oauthProperties: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/application/PlatformOAuthProperties.java'),
    playbackCredentialController: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/PlatformPlaybackCredentialsController.java'),
    playbackCredentialResponse: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/PlatformPlaybackCredentialsResponse.java'),
    tidalPlaybackDiagnosticsController: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackDiagnosticsController.java'),
    tidalPlaybackEventController: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/presentation/TidalPlaybackEventController.java'),
    tidalWebApiClient: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalWebApiClient.java'),
    tidalTokenRefreshClient: read('../../services/api/src/main/java/io/myforevermusic/api/modules/platform/infrastructure/tidal/TidalTokenRefreshClient.java'),
    macbookDomainProxyCompose: read('../../infra/docker/docker-compose.macbook-domain-proxy.yml'),
    macbookDomainHttpsConf: read('../../infra/nginx/macbook.domain.https.conf'),
    playbackPolicy: read('../../docs/architecture/PLAYBACK_ERROR_HANDLING_POLICY.md'),
}

const localCertFiles = listIfExists('../../infra/local-certs')
const playbackCriticalSource = [
    files.playbackContext,
    files.spotifySdk,
    files.tidalSdk,
    files.tidalStream,
    files.musicPlayback,
    files.pmsPlayback,
    files.playbackCredentialController,
    files.tidalPlaybackDiagnosticsController,
    files.tidalPlaybackEventController,
    files.tidalWebApiClient,
    files.tidalTokenRefreshClient,
].join('\n')
const postFallbackQueuePlatformResolver =
    files.playbackContext.match(/const resolvePostFallbackQueuePlatformId[\s\S]*?const playbackErrorMessage/)?.[0] ?? ''

const requiredSpotifyScopes = [
    'streaming',
    'user-read-playback-state',
    'user-modify-playback-state',
]

const requiredTidalSdkScopes = [
    'playback',
    'entitlements.read',
]

const requiredTidalLegacyPlaybackScopes = [
    'r_usr',
    'w_usr',
    'w_sub',
    'r_stream',
]

const checks = []

const check = (name, passed, detail) => {
    checks.push({ name, passed, detail })
}

check(
    'PlaybackContext does not delay or suppress playback auth errors',
    !/TRANSIENT_SPOTIFY_ERROR_DELAY_MS|invalid token scopes|setTimeout\(/i.test(files.playbackContext),
    'Do not hide scope/authentication errors with timers or special-case message suppression in the shared player context.',
)

check(
    'Playback failures require root-cause fixes, not bypasses',
    /## Root Cause Rule/.test(files.playbackPolicy) &&
        /fixing the concrete failing boundary/.test(files.playbackPolicy) &&
        /Do not bypass provider calls/.test(files.playbackPolicy),
    'The playback policy must explicitly require root-cause fixes at the failing boundary instead of bypassing, suppressing, or redirecting the problem.',
)

check(
    'Playback critical path has no workaround markers',
    !/\b(workaround|quick fix|hack|bypass|temporary cleanup|temporary fix)\b|임시|우회|회피|대충|일단/i.test(playbackCriticalSource),
    'Playback-critical code must not introduce temporary workaround markers. Fix credentials, queue routing, SDK setup, or provider recovery directly.',
)

check(
    'Spotify token cache invalidates old missing-scope credentials',
    /getMissingSpotifyPlaybackScopes/.test(files.spotifySdk) &&
        /tokenCache\.delete\(cacheKey\)/.test(files.spotifySdk) &&
        /assertSpotifyPlaybackScopes\(credentials\)/.test(files.spotifySdk),
    'Cached credentials missing playback scopes must be discarded and fresh backend credentials must be checked.',
)

for (const scope of requiredSpotifyScopes) {
    check(
        `application.yml includes ${scope}`,
        files.applicationYml.includes(scope),
        'Default Spotify OAuth scopes must request playback permissions.',
    )
    check(
        `PlatformOAuthProperties includes ${scope}`,
        files.oauthProperties.includes(scope),
        'Java property defaults must match playback OAuth requirements.',
    )
    check(
        `Playback credential API enforces ${scope}`,
        files.playbackCredentialController.includes(scope),
        'Backend playback credentials must fail before browser playback when scopes are missing.',
    )
}

for (const scope of requiredTidalSdkScopes) {
    check(
        `application.yml includes TIDAL ${scope}`,
        files.applicationYml.includes(scope),
        'Default TIDAL OAuth scopes must request the SDK playback permissions.',
    )
    check(
        `PlatformOAuthProperties includes TIDAL ${scope}`,
        files.oauthProperties.includes(scope),
        'Java TIDAL property defaults must include SDK playback scopes.',
    )
    check(
        `Playback credential API enforces TIDAL ${scope}`,
        files.playbackCredentialController.includes(scope),
        'Backend playback credentials must fail before browser SDK playback when TIDAL playback scopes are missing.',
    )
    check(
        `TIDAL SDK adapter enforces ${scope}`,
        files.tidalSdk.includes(scope),
        'The browser SDK adapter must reject credentials missing required SDK playback scopes before loading media.',
    )
}

for (const scope of requiredTidalLegacyPlaybackScopes) {
    check(
        `TIDAL legacy playback scope is documented: ${scope}`,
        files.playbackPolicy.includes(scope) || files.tidalPlaybackDiagnosticsController.includes(scope),
        'The full-track stream boundary verified from the prior app must remain documented or diagnosed explicitly.',
    )
}

check(
    'Playback credential API returns reconnect_required for missing scopes',
    /PlatformReconnectRequiredException/.test(files.playbackCredentialController) &&
        /missingPlaybackScopes/.test(files.playbackCredentialController),
    'Missing scopes should be treated as a reconnect requirement, not as a client-side cosmetic warning.',
)

check(
    'TIDAL SDK browser dependencies are installed',
    /"@tidal-music\/player"/.test(files.packageJson) &&
        /"@tidal-music\/common"/.test(files.packageJson) &&
        /"@tidal-music\/event-producer"/.test(files.packageJson),
    'TIDAL playback must use the official browser SDK packages.',
)

check(
    'TIDAL SDK adapter initializes credentials and event sender before playback',
    /setCredentialsProvider\(activeCredentialsProvider\)/.test(files.tidalSdk) &&
        /tidalEventProducer\s*\.\s*init/.test(files.tidalSdk) &&
        /setEventSender\(tidalEventProducer\)/.test(files.tidalSdk) &&
        /bootstrap\(\{/.test(files.tidalSdk) &&
        /await setNext/.test(files.tidalSdk) &&
        /await play\(\)/.test(files.tidalSdk),
    'The SDK path must configure credentials and event producer at the SDK boundary before load/play.',
)

check(
    'PlaybackContext routes TIDAL queues through the verified TIDAL stream adapter',
    /playbackPlatformId === 'tidal'/.test(files.playbackContext) &&
        /playTidalMediaItem/.test(files.playbackContext) &&
        /tidalStreamPlayback/.test(files.playbackContext) &&
        /resolveTidalTrackId/.test(files.playbackContext),
    'TIDAL playlists must be queued by TIDAL track ids from PMS detail tracks.',
)

check(
    'All shared TIDAL playback uses the user selected quality setting',
    /TIDAL_PLAYBACK_QUALITY_OPTIONS/.test(files.tidalQuality) &&
        /readTidalPlaybackQuality/.test(files.tidalQuality) &&
        /writeTidalPlaybackQuality/.test(files.tidalQuality) &&
        /tidalPlaybackQuality/.test(files.playbackContext) &&
        /setTidalPlaybackQuality/.test(files.playbackContext) &&
        /playTidalMediaItem\([^)]*tidalPlaybackQuality/s.test(files.playbackContext) &&
        /resolveBrowserCompatibleTidalStream\(\s*quality,/s.test(files.tidalStream) &&
        /fetchTidalPlaybackStream\(userId, tidalTrackId, fallbackQuality\)/.test(files.tidalStream) &&
        /TIDAL_PLAYBACK_QUALITY_OPTIONS/.test(files.playbackDock) &&
        /TIDAL 재생 품질/.test(files.playbackDock) &&
        /요청/.test(files.playbackContext) &&
        /실제/.test(files.playbackContext) &&
        /formatTidalAudioQuality\(getTidalCurrentSnapshot\(\), quality\)/.test(files.playbackContext),
    'The authenticated playback dock should expose a TIDAL quality selector, pass the selected value into stream playback, and update the displayed requested quality immediately.',
)

check(
    'TIDAL runtime player errors attempt YouTube fallback before skipping the current PMS track',
    /fallbackCurrentTidalTrackAfterPlaybackError/.test(files.playbackContext) &&
        /tryYouTubeFallbackForTrack/.test(files.playbackContext) &&
        /'manifest'/.test(files.playbackContext) &&
        /onError:\s*\(message: string\) => \{\s*fallbackCurrentTidalTrackAfterPlaybackError\(message\)/s.test(files.playbackContext) &&
        /YouTube fallback failed; trying next track/.test(files.playbackContext),
    'Runtime TIDAL stream errors can happen after a TIDAL-ready track starts; the shared player should try YouTube for the same track before moving on.',
)

check(
    'YouTube fallback does not force following TIDAL-ready PMS tracks onto YouTube',
    /resolvePostFallbackQueuePlatformId/.test(files.playbackContext) &&
        /playQueueItemByOriginalPlatform/.test(files.playbackContext) &&
        /const nextPlaybackPlatformId = resolvePostFallbackQueuePlatformId\(nextItem, session\?\.preferredPlatformId\)/.test(files.playbackContext) &&
        /handleYouTubeEnded[\s\S]*playQueueItemByOriginalPlatform\(session\.userId, nextQueue, nextIndex\)/.test(files.playbackContext),
    'After a fallback YouTube track ends, the shared player should route the next queue item by its original playable platform instead of staying on YouTube.',
)

check(
    'Queue navigation always dispatches by the destination track platform',
    /playQueueItemByOriginalPlatform/.test(files.playbackContext) &&
        /const nextPlaybackPlatformId = resolvePostFallbackQueuePlatformId\(nextItem, session\?\.preferredPlatformId\)/.test(files.playbackContext) &&
        /handleTidalEnded[\s\S]*playQueueItemByOriginalPlatform\(session\.userId, nextQueue, nextIndex\)/.test(files.playbackContext) &&
        /handleSpotifyEnded[\s\S]*playQueueItemByOriginalPlatform\(session\.userId, nextQueue, nextIndex\)/.test(files.playbackContext) &&
        /handleYouTubeEnded[\s\S]*playQueueItemByOriginalPlatform\(session\.userId, nextQueue, nextIndex\)/.test(files.playbackContext) &&
        /skipNext[\s\S]*playQueueItemByOriginalPlatform\(userId, queueRef\.current, nextIndex\)/.test(files.playbackContext) &&
        /skipPrevious[\s\S]*playQueueItemByOriginalPlatform\(userId, queueRef\.current, nextIndex\)/.test(files.playbackContext),
    'Automatic continuation and manual next/previous actions must route from the destination track metadata. A YouTube fallback must not keep later TIDAL-ready tracks on YouTube.',
)

check(
    'YouTube fallback recovery retries the authenticated platform for EMS source tracks',
    /return resolvePlaybackPlatformId\(item, fallbackPlatformId\)/.test(postFallbackQueuePlatformResolver) &&
        !/item\.sourcePlatform/.test(postFallbackQueuePlatformResolver),
    'An EMS track sourcePlatform describes where the track was collected. After a YouTube fallback, the next EMS track must retry the user authenticated preferred platform instead of forcing the collection source provider.',
)

check(
    'TIDAL stream adapter requires a FULL provider manifest before playing',
    /fetchTidalPlaybackStream/.test(files.tidalStream) &&
        /stream\.asset_presentation !== 'FULL'/.test(files.tidalStream) &&
        /playDirectStream|playHlsStream/.test(files.tidalStream),
    'The verified stream boundary must reject provider preview manifests instead of playing 30-second previews.',
)

check(
    'TIDAL browser playback downgrades cross-origin DASH before YouTube fallback',
    /TIDAL_BROWSER_COMPATIBLE_FALLBACK_QUALITIES/.test(files.tidalStream) &&
        /resolveBrowserCompatibleTidalStream/.test(files.tidalStream) &&
        /fetchTidalPlaybackStream\(userId, tidalTrackId, fallbackQuality\)/.test(files.tidalStream) &&
        /fetchPublicCurationTidalPlaybackStream\(slug, publicSessionId, track\.track_id, fallbackQuality\)/.test(files.tidalStream) &&
        !/"dashjs"/.test(files.packageJson),
    'TIDAL CDN rejects browser Origin headers on DASH segment requests. Shared players must retry the same TIDAL track at a browser-compatible quality before falling back to YouTube.',
)

check(
    'TIDAL direct media playback does not force CORS on signed audio URLs',
    !/crossOrigin\s*=|setAttribute\(\s*['"]crossorigin['"]/i.test(files.tidalStream),
    'TIDAL signed media URLs reject Origin-bearing CORS media requests; direct audio playback must not set crossOrigin on the HTMLAudioElement.',
)

check(
    'TIDAL SDK volume is kept inside the 0-1 range',
    /const clampVolume01/.test(files.tidalSdk) &&
        /setVolumeLevel\(toTidalVolumeLevel\(volume\)\)/.test(files.tidalSdk),
    'The TIDAL SDK receives normalized volume only.',
)

check(
    'TIDAL SDK preview transitions are treated as provider failures',
    /isTidalPreviewSnapshot/.test(files.tidalSdk) &&
        /describeTidalPreviewFailure/.test(files.tidalSdk) &&
        /await reset\(\)/.test(files.tidalSdk) &&
        /tidalPreviewBlockedRef/.test(files.playbackContext),
    'A 30-second SDK preview must stop playback and surface provider state instead of being treated as successful full-track playback.',
)

check(
    'TIDAL playlist stream test page stays isolated from the shared player',
    /path="tidal-playlist-test"/.test(files.app) &&
        /playTidalMediaItem/.test(files.tidalPlaylistTestPage) &&
        /tidalSetNextMediaItem/.test(files.tidalPlaylistTestPage) &&
        /tidalStreamPlayback/.test(files.tidalPlaylistTestPage) &&
        /fetchPmsPlaylistDetail/.test(files.tidalPlaylistTestPage) &&
        !/usePlayback/.test(files.tidalPlaylistTestPage) &&
        !/fetchTidalPlaybackManifestDiagnostics/.test(files.tidalPlaylistTestPage) &&
        !/spotify/i.test(files.tidalPlaylistTestPage),
    'Complex playlist playback bugs need an isolated TIDAL stream page that does not route through the shared Spotify/TIDAL player context or unrelated diagnostics.',
)

check(
    'TIDAL playlist stream test page auto-advances on ended',
    /playAtRef/.test(files.tidalPlaylistTestPage) &&
        /onEnded:[\s\S]*playAtRef\.current\(endedIndex \+ 1\)/.test(files.tidalPlaylistTestPage),
    'The isolated TIDAL playlist page must actually start the next track after an ended event, not only log that the current track ended.',
)

check(
    'Playback media resolver supports TIDAL track ids',
    /tidalTrackId/.test(files.musicPlayback) &&
        /extractTidalTrackIdFromUrl/.test(files.musicPlayback) &&
        /resolveTidalTrackId/.test(files.musicPlayback),
    'Imported TIDAL PMS tracks must be recognized from tidal:track URIs, TIDAL URLs, or external ids.',
)

check(
    'PlaybackContext appends queue items without replacing active playback',
    /appendToQueue:\s*\(items: PlaybackMediaItem\[\]\) => Promise<void>/.test(files.playbackContext) &&
        /addSpotifyUriToQueue/.test(files.playbackContext) &&
        /const nextQueue = \[\.\.\.queueRef\.current, \.\.\.appendedItems\]/.test(files.playbackContext) &&
        !/appendToQueue[\s\S]{0,1200}setCurrentItem\(null\)/.test(files.playbackContext),
    'Queue append must preserve the current player item and extend the existing queue tail.',
)

check(
    'PlaybackContext cancels stale playlist start requests',
    /playbackRequestIdRef/.test(files.playbackContext) &&
        /const isActiveRequest = \(\) => playbackRequestIdRef\.current === playbackRequestId/.test(files.playbackContext) &&
        /resetPlaybackSurface\(\)/.test(files.playbackContext) &&
        /if \(!isActiveRequest\(\)\) \{[\s\S]{0,80}return[\s\S]{0,80}\}/.test(files.playbackContext),
    'Starting a new playlist must reset player state and prevent older async provider work from overwriting the new queue.',
)

check(
    'Playback provider state callbacks ignore stale queue events',
    /resolvePlaybackPlatformId\(activeItem, session\?\.preferredPlatformId\) !== 'spotify'/.test(files.playbackContext) &&
        /spotifyTrackId && nextIndex < 0/.test(files.playbackContext) &&
        /resolvePlaybackPlatformId\(activeItem, session\?\.preferredPlatformId\) !== 'tidal'/.test(files.playbackContext) &&
        /snapshot\.productId && nextIndex < 0/.test(files.playbackContext),
    'Late Spotify/TIDAL state callbacks from the previous playlist must not revive old playback state after a fresh queue starts.',
)

check(
    'GMS playlist preview starts a fresh focused playback queue',
    /playQueueRef\.current\(playbackItems, 0\)/.test(files.gmsPlaylistsPage) &&
        /playQueue\(previewPlaybackItems, 0\)/.test(files.gmsPlaylistsPage) &&
        /playQueue\(previewPlaybackItems, index\)/.test(files.gmsPlaylistsPage) &&
        !/appendToQueue/.test(files.gmsPlaylistsPage),
    'GMS Preview tracks should clear the previous queue and start the selected candidate playlist for focused review.',
)

check(
    'Playlist detail Queue All actions append behind active playback',
    /handleQueueAll[\s\S]*appendToQueue\(playbackItems\)/.test(files.pmsPlaylistDetailPage) &&
        /handleQueueAll[\s\S]*appendToQueue\(playbackItems\)/.test(files.emsPlaylistDetailPage) &&
        /buildEmsPlaylistDetailPath/.test(files.emsSearchPlaylistDetailPage) &&
        /replace:\s*true/.test(files.emsSearchPlaylistDetailPage),
    'PMS/EMS DB detail pages should reserve Play All for replacement playback and Queue All for appending; legacy EMS search URLs should redirect to DB detail before playback controls appear.',
)

check(
    'PMS TIDAL tracks stay on TIDAL when Spotify audio-feature matches exist',
    /if \(sourcePlatform === 'tidal'\)[\s\S]*return tidalTrackId \? 'tidal' : spotifyTrackId \? 'spotify' : 'tidal'/.test(files.pmsPlayback) &&
        /track\.source_platform === 'spotify'[\s\S]*extractSpotifyTrackIdFromUrl\(track\.audio_feature_track_id\)/.test(files.pmsPlayback) &&
        /item\.sourcePlatform === 'tidal' && resolveTidalTrackId\(item\)/.test(files.musicPlayback),
    'A TIDAL PMS import may have Spotify audio-feature lookup ids, but playback must not silently switch to Spotify when a TIDAL track id is present.',
)

check(
    'TIDAL provider account id is recovered from token claims',
    /profileFromAccessToken/.test(files.tidalWebApiClient) &&
        /claimAsString\(claims, "uid"\)/.test(files.tidalWebApiClient) &&
        /profileFromAccessToken\(credential\.accessToken\(\)\)/.test(files.tidalWebApiClient),
    'Existing TIDAL credentials must recover the provider account id from the JWT uid claim instead of falling back to a local app user id.',
)

check(
    'TIDAL country code follows the provider token claim',
    /countryCodeFromAccessToken/.test(files.playbackCredentialController) &&
        /countryCodeFromAccessToken\(credential\.accessToken\(\)\)/.test(files.playbackCredentialController) &&
        /claimFromAccessToken\(accessToken, "cc"\)/.test(files.playbackCredentialController) &&
        /claimAsString\(claims, "cc"\)/.test(files.tidalWebApiClient),
    'TIDAL playlist import and SDK playback credentials must use the provider token country claim before the static environment country.',
)

check(
    'TIDAL refresh requests use the configured client-auth boundary',
    /grant_type=refresh_token/.test(files.tidalTokenRefreshClient) &&
        /applyClientAuthentication/.test(files.tidalTokenRefreshClient) &&
        /clientIdFormField/.test(files.tidalTokenRefreshClient) &&
        /getTidal\(\)\.getClientId\(\)/.test(files.tidalTokenRefreshClient),
    'TIDAL refresh token requests must send Basic auth for confidential clients and client_id form fields for public clients.',
)

check(
    'Domain SSL does not use local certificate workarounds',
    !/mkcert|local-certs|local-dev-https/i.test(files.macbookDomainProxyCompose) &&
        !/mkcert|local-certs|local-dev-https/i.test(files.macbookDomainHttpsConf) &&
        localCertFiles.filter((fileName) => /\.(pem|key|crt)$/i.test(fileName)).length === 0,
    'The public domain proxy must use a real certificate path. Do not commit or generate mkcert/local-certs as a shortcut for imapplepie20.tplinkdns.com.',
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
    console.error(`\nPlayback regression harness failed: ${failed.length} check(s).`)
    exit(1)
}

console.log('\nPlayback regression harness passed.')
