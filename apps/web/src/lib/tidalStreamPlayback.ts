import Hls from 'hls.js'
import { resolveSpotifyTrackId, resolveTidalTrackId, type PlaybackMediaItem } from '@/lib/musicPlayback'
import {
    attachTidalAudioCapture,
    detachTidalAudioCapture,
    notifyDirectTidalAudioSource,
    notifyNativeHlsTidalAudioSource,
} from '@/lib/tidalAudioCapture'
import {
    fetchPublicCurationTidalPlaybackStream,
    fetchTidalPlaybackStream,
    resolveTidalPlaybackTarget,
} from '@/services/api'
import type {
    PublicCurationPlaybackStreamResponse,
    PublicCurationShareTrack,
    TidalPlaybackStreamResponse,
} from '@/types/api'

const TIDAL_STREAM_DEVICE_ID = 'tidal-stream-player'

export interface TidalPlaybackSnapshot {
    state: 'IDLE' | 'NOT_PLAYING' | 'PLAYING' | 'STALLED'
    positionMs: number
    durationMs: number
    productId: string | null
    presentation?: string | null
    previewReason?: string | null
    requestedQuality?: string | null
    audioQuality?: string | null
    codec?: string | null
    bitRate?: number | null
    sampleRate?: number | null
    bitDepth?: number | null
}

export interface TidalPlayerCallbacks {
    onReady?: (deviceId: string) => void
    onStateChange?: (state: TidalPlaybackSnapshot['state'], snapshot: TidalPlaybackSnapshot) => void
    onTransition?: (productId: string, snapshot: TidalPlaybackSnapshot) => void
    onEnded?: (productId: string | null) => void
    onError?: (message: string) => void
}

type TidalPlayableStream = Pick<
    TidalPlaybackStreamResponse | PublicCurationPlaybackStreamResponse,
    | 'requested_quality'
    | 'audio_quality'
    | 'codec'
    | 'bit_rate'
    | 'sample_rate'
    | 'bit_depth'
    | 'asset_presentation'
    | 'manifest_mime_type'
    | 'duration_seconds'
    | 'stream_url'
>

// `audioElement` 는 DOM 미부착 detached element 이며 `crossOrigin` 속성은 의도적으로
// 설정하지 않는다. TIDAL CDN 의 CORS 설정상 crossOrigin 을 켜면 재생 자체가 깨질 수 있다.
let audioElement: HTMLAudioElement | null = null
let hls: Hls | null = null
let activeCallbacks: TidalPlayerCallbacks = {}
let currentProductId: string | null = null
let currentPresentation: string | null = null
let currentRequestedQuality: string | null = null
let currentAudioQuality: string | null = null
let currentCodec: string | null = null
let currentBitRate: number | null = null
let currentSampleRate: number | null = null
let currentBitDepth: number | null = null
let lastDurationMs = 0
let listenersAttached = false

const clampVolume01 = (volume: number) => Math.min(1, Math.max(0, Number.isFinite(volume) ? volume : 0.5))

const secondsToMs = (seconds: number) =>
    Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : 0

const currentState = (): TidalPlaybackSnapshot['state'] => {
    if (!audioElement || !currentProductId) {
        return 'IDLE'
    }
    if (audioElement.readyState < HTMLMediaElement.HAVE_FUTURE_DATA && !audioElement.paused) {
        return 'STALLED'
    }
    return audioElement.paused ? 'NOT_PLAYING' : 'PLAYING'
}

export const getTidalDeviceId = () => TIDAL_STREAM_DEVICE_ID

// Visualizer uses this for playback timing only. Audio samples are captured
// before SourceBuffer append in tidalAudioCapture because the media element is
// cross-origin tainted by TIDAL HLS segments.
export const getTidalAudioElement = (): HTMLAudioElement | null => audioElement

// Development probe needs access to the live HLS instance for diagnostics.
// Production capture is attached above through tidalAudioCapture.
export const getTidalHlsInstance = (): Hls | null => hls

export const getTidalCurrentSnapshot = (): TidalPlaybackSnapshot => {
    const durationMs = secondsToMs(audioElement?.duration ?? 0) || lastDurationMs
    return {
        state: currentState(),
        positionMs: secondsToMs(audioElement?.currentTime ?? 0),
        durationMs,
        productId: currentProductId,
        presentation: currentPresentation,
        previewReason: null,
        requestedQuality: currentRequestedQuality,
        audioQuality: currentAudioQuality,
        codec: currentCodec,
        bitRate: currentBitRate,
        sampleRate: currentSampleRate,
        bitDepth: currentBitDepth,
    }
}

export const isTidalPreviewSnapshot = (snapshot: TidalPlaybackSnapshot) =>
    snapshot.presentation === 'PREVIEW' || Boolean(snapshot.previewReason)

export const describeTidalPreviewFailure = (snapshot: TidalPlaybackSnapshot) => {
    const details = [
        snapshot.productId ? `product=${snapshot.productId}` : null,
        snapshot.presentation ? `presentation=${snapshot.presentation}` : null,
        snapshot.previewReason ? `preview=${snapshot.previewReason}` : null,
        snapshot.durationMs ? `duration=${Math.round(snapshot.durationMs / 1000)}s` : null,
    ].filter(Boolean)

    return `TIDAL returned preview playback instead of the full track.${details.length ? ` ${details.join(' ')}` : ''}`
}

const emitState = (state = currentState()) => {
    activeCallbacks.onStateChange?.(state, getTidalCurrentSnapshot())
}

const errorMessage = (error: unknown, fallback: string) => {
    if (error instanceof Error && error.message) {
        return error.message
    }
    if (typeof error === 'string' && error) {
        return error
    }
    return fallback
}

export const resolveTidalPlayableItem = async (userId: string, item: PlaybackMediaItem): Promise<PlaybackMediaItem> => {
    if (resolveTidalTrackId(item)) {
        return item
    }

    const target = await resolveTidalPlaybackTarget({
        user_id: userId,
        title: item.title,
        artist_name: item.subtitle.split(' · ')[0] || item.subtitle || item.title,
        source_platform: item.sourcePlatform,
        external_track_id: item.externalTrackId,
        platform_uri: item.platformUri,
        spotify_track_id: resolveSpotifyTrackId(item),
        isrc: item.isrc,
        duration_ms: item.durationMs,
    })

    return {
        ...item,
        playbackPlatformId: 'tidal',
        externalUrl: target.platform_external_url ?? item.externalUrl,
        platformUri: target.tidal_uri ?? item.platformUri,
        previewUrl: target.preview_url ?? item.previewUrl,
        tidalTrackId: target.tidal_track_id,
        isrc: target.isrc ?? item.isrc,
        durationMs: target.duration_ms ?? item.durationMs,
        albumTitle: target.album_title ?? item.albumTitle,
        imageUrl: target.album_image_url ?? item.imageUrl,
        supportingText: item.supportingText ?? `TIDAL match: ${target.match_reason}`,
    }
}

const ensureAudioElement = () => {
    if (audioElement) {
        return audioElement
    }

    audioElement = document.createElement('audio')
    audioElement.preload = 'auto'
    audioElement.volume = 0.5
    attachAudioListeners(audioElement)
    return audioElement
}

const attachAudioListeners = (audio: HTMLAudioElement) => {
    if (listenersAttached) {
        return
    }

    audio.addEventListener('play', () => emitState('PLAYING'))
    audio.addEventListener('pause', () => {
        if (audio.ended) {
            return
        }
        emitState('NOT_PLAYING')
    })
    audio.addEventListener('waiting', () => emitState('STALLED'))
    audio.addEventListener('canplay', () => emitState(currentState()))
    audio.addEventListener('timeupdate', () => emitState(currentState()))
    audio.addEventListener('durationchange', () => {
        lastDurationMs = secondsToMs(audio.duration)
        emitState(currentState())
    })
    audio.addEventListener('ended', () => {
        const endedProductId = currentProductId
        emitState('IDLE')
        activeCallbacks.onEnded?.(endedProductId)
    })
    audio.addEventListener('error', () => {
        const mediaError = audio.error
        const detail = mediaError
            ? ` code=${mediaError.code}${mediaError.message ? ` message=${mediaError.message}` : ''}`
            : ''
        activeCallbacks.onError?.(`TIDAL stream media element failed to play the resolved full-track URL.${detail}`)
    })

    listenersAttached = true
}

const resetSource = () => {
    detachTidalAudioCapture()
    if (hls) {
        hls.destroy()
        hls = null
    }

    if (audioElement) {
        audioElement.pause()
        audioElement.removeAttribute('src')
        audioElement.load()
    }
    currentRequestedQuality = null
    currentAudioQuality = null
    currentCodec = null
    currentBitRate = null
    currentSampleRate = null
    currentBitDepth = null
}

const isHlsStream = (stream: TidalPlayableStream) => {
    const mimeType = stream.manifest_mime_type?.toLowerCase() ?? ''
    return mimeType.includes('mpegurl') || stream.stream_url.includes('.m3u8')
}

const isDashStream = (stream: TidalPlayableStream) => {
    const mimeType = stream.manifest_mime_type?.toLowerCase() ?? ''
    return mimeType.includes('dash') || stream.stream_url.includes('.mpd')
}

const playHlsStream = async (audio: HTMLAudioElement, streamUrl: string) => {
    if (Hls.isSupported()) {
        hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,
            backBufferLength: 90,
        })
        attachTidalAudioCapture(hls)

        await new Promise<void>((resolve, reject) => {
            hls?.once(Hls.Events.MANIFEST_PARSED, () => resolve())
            hls?.once(Hls.Events.ERROR, (_event, data) => {
                if (data.fatal) {
                    reject(new Error(`TIDAL HLS stream failed: ${data.type}/${data.details}`))
                }
            })
            hls?.loadSource(streamUrl)
            hls?.attachMedia(audio)
        })
        await audio.play()
        return
    }

    if (audio.canPlayType('application/vnd.apple.mpegurl')) {
        notifyNativeHlsTidalAudioSource()
        audio.src = streamUrl
        await audio.play()
        return
    }

    throw new Error('This browser cannot play the HLS stream returned by TIDAL.')
}

const playDirectStream = async (audio: HTMLAudioElement, streamUrl: string) => {
    audio.src = streamUrl
    await audio.play()
}

const applyStreamMetadata = (stream: TidalPlayableStream) => {
    currentPresentation = stream.asset_presentation
    currentRequestedQuality = stream.requested_quality
    currentAudioQuality = stream.audio_quality
    currentCodec = stream.codec
    currentBitRate = stream.bit_rate
    currentSampleRate = stream.sample_rate
    currentBitDepth = stream.bit_depth
    if (stream.duration_seconds && stream.duration_seconds > 0) {
        lastDurationMs = Math.round(stream.duration_seconds * 1000)
    }
}

const assertFullStream = (stream: TidalPlayableStream) => {
    if (stream.asset_presentation !== 'FULL') {
        throw new Error(`TIDAL stream endpoint did not return FULL playback. presentation=${stream.asset_presentation ?? 'unknown'}`)
    }
}

const playResolvedTidalStream = async (
    audio: HTMLAudioElement,
    stream: TidalPlayableStream,
    audioSourceUserId: string,
    tidalTrackId: string,
) => {
    // eslint-disable-next-line no-console
    console.log('[tidalStream] dispatch', {
        productId: tidalTrackId,
        manifestMime: stream.manifest_mime_type,
        urlExt: stream.stream_url.split('?')[0].split('.').pop(),
        codec: stream.codec,
        quality: stream.audio_quality,
        dispatch: isDashStream(stream) ? 'DASH(unsupported)' : isHlsStream(stream) ? 'HLS' : 'direct',
    })
    if (isDashStream(stream)) {
        throw new Error(`TIDAL returned a DASH stream (${stream.manifest_mime_type ?? 'unknown'}), which is not supported by the direct browser player yet.`)
    }
    if (isHlsStream(stream)) {
        await playHlsStream(audio, stream.stream_url)
        return
    }

    notifyDirectTidalAudioSource(
        stream.stream_url,
        audioSourceUserId,
        tidalTrackId,
        stream.requested_quality ?? stream.audio_quality ?? 'HIGH',
        0,
    )
    await playDirectStream(audio, stream.stream_url)
}

export const ensureTidalWebPlayer = async (_userId: string, callbacks: TidalPlayerCallbacks = {}) => {
    activeCallbacks = callbacks
    ensureAudioElement()
    activeCallbacks.onReady?.(TIDAL_STREAM_DEVICE_ID)
}

export const playTidalMediaItem = async (
    userId: string,
    item: PlaybackMediaItem,
    _nextItem?: PlaybackMediaItem | null,
    callbacks: TidalPlayerCallbacks = {},
) => {
    await ensureTidalWebPlayer(userId, callbacks)
    const playableItem = await resolveTidalPlayableItem(userId, item)
    const tidalTrackId = resolveTidalTrackId(playableItem)
    if (!tidalTrackId) {
        throw new Error(`TIDAL track id is missing for "${item.title}".`)
    }

    const audio = ensureAudioElement()
    resetSource()
    currentProductId = tidalTrackId
    currentPresentation = null
    lastDurationMs = playableItem.durationMs ?? 0
    emitState('NOT_PLAYING')

    try {
        const stream = await fetchTidalPlaybackStream(userId, tidalTrackId)
        applyStreamMetadata(stream)
        assertFullStream(stream)

        activeCallbacks.onTransition?.(tidalTrackId, getTidalCurrentSnapshot())
        await playResolvedTidalStream(audio, stream, userId, tidalTrackId)
    } catch (error: unknown) {
        resetSource()
        emitState('IDLE')
        throw new Error(errorMessage(error, 'TIDAL stream playback could not start.'))
    }
}

export const playPublicCurationTidalTrack = async (
    slug: string,
    publicSessionId: string,
    track: PublicCurationShareTrack,
    callbacks: TidalPlayerCallbacks = {},
) => {
    await ensureTidalWebPlayer(`public-curation:${slug}`, callbacks)
    if (!track.tidal_track_id) {
        throw new Error(`TIDAL track id is missing for "${track.title}".`)
    }

    const audio = ensureAudioElement()
    resetSource()
    currentProductId = track.tidal_track_id
    currentPresentation = null
    lastDurationMs = track.duration_ms ?? 0
    emitState('NOT_PLAYING')

    try {
        const stream = await fetchPublicCurationTidalPlaybackStream(slug, publicSessionId, track.track_id, 'HIGH')
        applyStreamMetadata(stream)
        assertFullStream(stream)

        activeCallbacks.onTransition?.(track.tidal_track_id, getTidalCurrentSnapshot())
        await playResolvedTidalStream(audio, stream, `public-curation:${slug}`, track.tidal_track_id)
    } catch (error: unknown) {
        resetSource()
        emitState('IDLE')
        throw new Error(errorMessage(error, 'Public curation TIDAL stream playback could not start.'))
    }
}

export const tidalSetNextMediaItem = async (_item?: PlaybackMediaItem | null) => {
}

export const tidalPause = async () => {
    audioElement?.pause()
}

export const tidalResume = async () => {
    if (!audioElement || !currentProductId) {
        throw new Error('No TIDAL stream is loaded.')
    }
    await audioElement.play()
}

export const tidalReset = async () => {
    resetSource()
    currentProductId = null
    currentPresentation = null
    lastDurationMs = 0
    emitState('IDLE')
}

export const tidalSeek = async (positionMs: number) => {
    if (!audioElement) {
        return
    }
    audioElement.currentTime = Math.max(0, positionMs) / 1000
}

export const tidalSetVolume = async (volume: number) => {
    ensureAudioElement().volume = clampVolume01(volume)
}
