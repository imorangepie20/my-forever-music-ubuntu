import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { ApiError, recordUserMusicEvent } from '@/services/api'
import {
    resolvePlaybackPlatformId,
    resolveSpotifyTrackId,
    resolveTidalTrackId,
    resolveYouTubeVideoId,
    type PlaybackMediaItem,
} from '@/lib/musicPlayback'
import {
    addSpotifyUriToQueue,
    ensureSpotifyWebPlayer,
    getSpotifyCurrentState,
    playSpotifyUris,
    spotifyPause,
    spotifyResume,
    resetSpotifyWebPlayer,
    spotifySeek,
    spotifySetVolume,
    type SpotifyPlaybackState,
} from '@/lib/spotifyPlaybackSdk'
import { resolveSpotifyPlayableItem } from '@/lib/spotifyResolvedPlayback'
import {
    getYouTubeCurrentSnapshot,
    playYouTubeVideo,
    resolveYouTubePlayableItem,
    youtubePause,
    youtubeResume,
    youtubeSeek,
    youtubeSetVolume,
    youtubeStop,
    type YouTubePlaybackSnapshot,
    type YouTubePlayerCallbacks,
} from '@/lib/youtubePlayback'
import {
    describeTidalPreviewFailure,
    ensureTidalWebPlayer,
    getTidalCurrentSnapshot,
    getTidalDeviceId,
    isTidalPreviewSnapshot,
    playTidalMediaItem,
    resolveTidalPlayableItem,
    tidalPause,
    tidalReset,
    tidalResume,
    tidalSeek,
    tidalSetVolume,
    type TidalPlaybackSnapshot,
    type TidalPlayerCallbacks,
} from '@/lib/tidalStreamPlayback'
import {
    readTidalPlaybackQuality,
    tidalPlaybackQualityLabel,
    writeTidalPlaybackQuality,
    type TidalPlaybackQuality,
} from '@/lib/tidalPlaybackQuality'
import type { UserMusicEventType } from '@/types/api'

interface PlaybackContextValue {
    currentItem: PlaybackMediaItem | null
    queue: PlaybackMediaItem[]
    currentIndex: number
    isPlaying: boolean
    isLoading: boolean
    error: string | null
    notice: string | null
    positionMs: number
    durationMs: number
    volume: number
    deviceId: string | null
    shuffleEnabled: boolean
    repeatMode: PlaybackRepeatMode
    audioQualityLabel: string | null
    tidalPlaybackQuality: TidalPlaybackQuality
    playItem: (item: PlaybackMediaItem) => Promise<void>
    playQueue: (items: PlaybackMediaItem[], startIndex?: number) => Promise<void>
    appendToQueue: (items: PlaybackMediaItem[]) => Promise<void>
    pause: () => Promise<void>
    resume: () => Promise<void>
    skipNext: () => Promise<void>
    skipPrevious: () => Promise<void>
    seek: (positionMs: number) => Promise<void>
    setVolume: (volume: number) => Promise<void>
    toggleShuffle: () => Promise<void>
    cycleRepeatMode: () => Promise<void>
    setTidalPlaybackQuality: (quality: TidalPlaybackQuality) => void
    clearItem: () => void
}

const PlaybackContext = createContext<PlaybackContextValue | null>(null)
export type PlaybackRepeatMode = 'off' | 'all' | 'one'

const clampIndex = (index: number, length: number) => Math.min(Math.max(0, index), Math.max(0, length - 1))
const toSpotifyUri = (item: PlaybackMediaItem) => {
    const spotifyTrackId = resolveSpotifyTrackId(item)
    return spotifyTrackId ? `spotify:track:${spotifyTrackId}` : null
}
const nextRepeatMode = (mode: PlaybackRepeatMode): PlaybackRepeatMode =>
    mode === 'off' ? 'all' : mode === 'all' ? 'one' : 'off'
const shuffledQueueWithStart = (items: PlaybackMediaItem[], startIndex: number) => {
    const safeStartIndex = clampIndex(startIndex, items.length)
    const selectedItem = items[safeStartIndex]
    const remainingItems = items.filter((_, index) => index !== safeStartIndex)
    for (let index = remainingItems.length - 1; index > 0; index -= 1) {
        const swapIndex = Math.floor(Math.random() * (index + 1))
        ;[remainingItems[index], remainingItems[swapIndex]] = [remainingItems[swapIndex], remainingItems[index]]
    }
    return [selectedItem, ...remainingItems]
}
const formatTidalAudioQuality = (
    snapshot: TidalPlaybackSnapshot,
    requestedQualityOverride?: TidalPlaybackQuality | string | null,
) => {
    const selectedRequestedQuality = requestedQualityOverride ?? snapshot.requestedQuality
    const requestedQuality = selectedRequestedQuality
        ? `요청 ${tidalPlaybackQualityLabel(selectedRequestedQuality as TidalPlaybackQuality)}`
        : null
    const actualQuality = snapshot.audioQuality ? `실제 ${snapshot.audioQuality}` : null
    const codec = snapshot.codec
    const sampleRate = snapshot.sampleRate
        ? `${Number.isInteger(snapshot.sampleRate / 1000) ? snapshot.sampleRate / 1000 : (snapshot.sampleRate / 1000).toFixed(1)} kHz`
        : null
    const bitDepth = snapshot.bitDepth ? `${snapshot.bitDepth}-bit` : null
    const resolution = [sampleRate, bitDepth].filter(Boolean).join(' / ')
    const parts = [requestedQuality, actualQuality, codec, resolution || null].filter(Boolean)
    return parts.length > 0 ? parts.join(' · ') : null
}
const replaceQueueItem = (items: PlaybackMediaItem[], index: number, item: PlaybackMediaItem) =>
    items.map((entry, entryIndex) => entryIndex === index ? item : entry)
const readArtistName = (item: PlaybackMediaItem) => item.subtitle.split(' · ')[0]?.trim() || item.subtitle || null
const resolvePostFallbackQueuePlatformId = (item: PlaybackMediaItem, fallbackPlatformId?: string | null) => {
    return resolvePlaybackPlatformId(item, fallbackPlatformId)
}
const playbackErrorMessage = (error: unknown, fallback = 'Playback failed.') =>
    error instanceof Error && error.message ? error.message : fallback
const isRecoverableTrackPlaybackError = (error: unknown) => {
    const message = playbackErrorMessage(error).toLowerCase()
    const apiStatus = error instanceof ApiError ? error.status : null
    const accountBoundary = [
        'sign in',
        'credential',
        'token',
        'scope',
        'permission',
        'reconnect',
        'account',
        'premium',
        'unauthorized',
        'forbidden',
    ].some((phrase) => message.includes(phrase))
    if (accountBoundary) {
        return false
    }

    if (apiStatus === 404) {
        return true
    }

    return [
        'not found',
        '(404)',
        ' 404',
        'no playable',
        'no valid',
        'missing',
        'not have a valid',
        'could not start',
        'failed to fetch',
        'failed to play',
        'did not return full playback',
        'manifest',
        'preview playback',
        'returned preview',
        'dash stream',
        'hls stream failed',
        'cannot play',
        "can't play",
        'not playable',
        'unplayable',
        'load failed',
    ].some((phrase) => message.includes(phrase))
}
const skippedTrackMessage = (item: PlaybackMediaItem, error: unknown) =>
    `Skipped "${item.title}": ${playbackErrorMessage(error)}`

export const PlaybackProvider = ({ children }: { children: ReactNode }) => {
    const { session } = useAuthSession()
    const [currentItem, setCurrentItem] = useState<PlaybackMediaItem | null>(null)
    const [queue, setQueue] = useState<PlaybackMediaItem[]>([])
    const [currentIndex, setCurrentIndex] = useState(0)
    const [isPlaying, setIsPlaying] = useState(false)
    const [isLoading, setIsLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [notice, setNotice] = useState<string | null>(null)
    const [positionMs, setPositionMs] = useState(0)
    const [durationMs, setDurationMs] = useState(0)
    const [volumeState, setVolumeState] = useState(0.5)
    const [deviceId, setDeviceId] = useState<string | null>(null)
    const [shuffleEnabled, setShuffleEnabled] = useState(false)
    const [repeatMode, setRepeatMode] = useState<PlaybackRepeatMode>('off')
    const [audioQualityLabel, setAudioQualityLabel] = useState<string | null>(null)
    const [tidalPlaybackQuality, setTidalPlaybackQualityState] = useState<TidalPlaybackQuality>(() => readTidalPlaybackQuality())
    const queueRef = useRef(queue)
    const currentItemRef = useRef(currentItem)
    const currentIndexRef = useRef(currentIndex)
    const volumeStateRef = useRef(volumeState)
    const shuffleEnabledRef = useRef(shuffleEnabled)
    const repeatModeRef = useRef(repeatMode)
    const positionMsRef = useRef(positionMs)
    const durationMsRef = useRef(durationMs)
    const playbackRequestIdRef = useRef(0)
    const playQueueRef = useRef<PlaybackContextValue['playQueue'] | null>(null)
    const autoSkipInFlightRef = useRef(false)
    const previousSessionUserIdRef = useRef(session?.userId ?? null)
    const tidalCallbacksRef = useRef<TidalPlayerCallbacks>({})
    const youtubeCallbacksRef = useRef<YouTubePlayerCallbacks>({})
    const tidalPreviewBlockedRef = useRef(false)
    const spotifyEndedHandlerRef = useRef<(() => void) | null>(null)
    const lastSpotifyStateRef = useRef<{
        trackId: string | null
        position: number
        duration: number
        completionEmittedForTrackId: string | null
    }>({ trackId: null, position: 0, duration: 0, completionEmittedForTrackId: null })

    useEffect(() => {
        queueRef.current = queue
    }, [queue])

    useEffect(() => {
        currentItemRef.current = currentItem
    }, [currentItem])

    useEffect(() => {
        currentIndexRef.current = currentIndex
    }, [currentIndex])

    useEffect(() => {
        volumeStateRef.current = volumeState
    }, [volumeState])

    useEffect(() => {
        shuffleEnabledRef.current = shuffleEnabled
    }, [shuffleEnabled])

    useEffect(() => {
        repeatModeRef.current = repeatMode
    }, [repeatMode])

    useEffect(() => {
        positionMsRef.current = positionMs
    }, [positionMs])

    useEffect(() => {
        durationMsRef.current = durationMs
    }, [durationMs])

    const clearPlaybackError = useCallback(() => {
        setError(null)
    }, [])

    const setPlaybackQueueState = useCallback((
        nextQueue: PlaybackMediaItem[],
        nextIndex: number,
        nextItem: PlaybackMediaItem | null = nextQueue[nextIndex] ?? null,
    ) => {
        queueRef.current = nextQueue
        currentIndexRef.current = nextIndex
        currentItemRef.current = nextItem
        setQueue(nextQueue)
        setCurrentIndex(nextIndex)
        setCurrentItem(nextItem)
    }, [])

    const resetPlaybackSurface = useCallback(() => {
        queueRef.current = []
        currentIndexRef.current = 0
        currentItemRef.current = null
        positionMsRef.current = 0
        durationMsRef.current = 0
        setQueue([])
        setCurrentIndex(0)
        setCurrentItem(null)
        setPositionMs(0)
        setDurationMs(0)
        setAudioQualityLabel(null)
        setIsPlaying(false)
        tidalPreviewBlockedRef.current = false
        lastSpotifyStateRef.current = { trackId: null, position: 0, duration: 0, completionEmittedForTrackId: null }
    }, [])

    useEffect(() => {
        const nextUserId = session?.userId ?? null
        if (previousSessionUserIdRef.current === nextUserId) {
            return
        }

        previousSessionUserIdRef.current = nextUserId
        playbackRequestIdRef.current += 1
        resetPlaybackSurface()
        resetSpotifyWebPlayer()
        void tidalReset().catch(() => undefined)
        void youtubeStop().catch(() => undefined)
    }, [resetPlaybackSurface, session?.userId])

    const recordPlaybackEvent = useCallback(
        (
            eventType: UserMusicEventType,
            item: PlaybackMediaItem,
            overrides: { positionMs?: number; durationMs?: number } = {},
        ) => {
            if (!session?.userId) {
                return
            }

            const nextDurationMs = overrides.durationMs ?? item.durationMs ?? durationMsRef.current
            const nextPositionMs = overrides.positionMs ?? positionMsRef.current
            const playRatio = nextDurationMs && nextDurationMs > 0
                ? Math.min(1, Math.max(0, nextPositionMs / nextDurationMs))
                : null
            const playbackPlatformId = resolvePlaybackPlatformId(item, session.preferredPlatformId)

            void recordUserMusicEvent({
                user_id: session.userId,
                event_type: eventType,
                source_space: 'player',
                source_platform: item.sourcePlatform,
                playback_platform_id: playbackPlatformId,
                item_id: item.id,
                item_kind: item.kind,
                track_id: item.kind === 'track' ? item.id : null,
                external_track_id: item.externalTrackId ?? item.spotifyTrackId ?? item.tidalTrackId ?? null,
                platform_uri: item.platformUri ?? null,
                title: item.title,
                artist_name: readArtistName(item),
                album_title: item.albumTitle ?? null,
                isrc: item.isrc ?? null,
                duration_ms: nextDurationMs && nextDurationMs > 0 ? Math.round(nextDurationMs) : null,
                position_ms: nextPositionMs && nextPositionMs > 0 ? Math.round(nextPositionMs) : null,
                play_ratio: playRatio,
                occurred_at: new Date().toISOString(),
            }).catch(() => undefined)
        },
        [session?.preferredPlatformId, session?.userId],
    )

    const skipCurrentTrackAfterPlaybackError = useCallback((message: string) => {
        if (autoSkipInFlightRef.current) {
            return true
        }

        const activeItem = currentItemRef.current
        const activeQueue = queueRef.current
        const activeIndex = currentIndexRef.current
        let nextIndex = activeIndex + 1
        if (nextIndex >= activeQueue.length && repeatModeRef.current === 'all') {
            nextIndex = 0
        }

        const canAdvance = activeItem
            && activeQueue.length > 1
            && nextIndex !== activeIndex
            && Boolean(activeQueue[nextIndex])
            && isRecoverableTrackPlaybackError(new Error(message))
            && playQueueRef.current

        if (!canAdvance) {
            setError(message)
            setNotice(null)
            setIsPlaying(false)
            return false
        }

        autoSkipInFlightRef.current = true
        setError(skippedTrackMessage(activeItem, new Error(message)))
        setNotice('Trying next track...')
        void playQueueRef.current?.(activeQueue, nextIndex)
            .catch((playbackError: unknown) => {
                setError(playbackErrorMessage(playbackError))
                setNotice(null)
                setIsPlaying(false)
            })
            .finally(() => {
                autoSkipInFlightRef.current = false
            })
        return true
    }, [])

    const handleSpotifyStateChange = useCallback((state: SpotifyPlaybackState | null) => {
        if (!state) {
            return
        }

        const activeItem = currentItemRef.current
        if (!activeItem || resolvePlaybackPlatformId(activeItem, session?.preferredPlatformId) !== 'spotify') {
            return
        }

        const spotifyTrackId = state.track_window.current_track?.id ?? null
        const nextIndex = spotifyTrackId
            ? queueRef.current.findIndex((item) => resolveSpotifyTrackId(item) === spotifyTrackId)
            : -1
        if (spotifyTrackId && nextIndex < 0) {
            return
        }

        setNotice(null)
        setIsPlaying(!state.paused)
        setPositionMs(state.position ?? 0)
        setDurationMs(state.duration ?? 0)
        setAudioQualityLabel('Spotify')

        if (!state.paused && spotifyTrackId) {
            clearPlaybackError()
        }

        const prev = lastSpotifyStateRef.current
        const prevWasNearEnd = prev.trackId !== null
            && prev.duration > 0
            && prev.position / prev.duration >= 0.95
            && prev.completionEmittedForTrackId !== prev.trackId
        const currentNearStart = (state.position ?? 0) < Math.max(2000, (state.duration ?? 0) * 0.05)

        let nextCompletionEmittedForTrackId = prev.completionEmittedForTrackId

        if (prevWasNearEnd) {
            const completedQueueItem = queueRef.current.find((item) => resolveSpotifyTrackId(item) === prev.trackId)
            if (completedQueueItem) {
                recordPlaybackEvent('play_completed', completedQueueItem, {
                    durationMs: prev.duration,
                    positionMs: prev.duration,
                })
            }
            nextCompletionEmittedForTrackId = prev.trackId

            if (spotifyTrackId === prev.trackId && currentNearStart && !state.paused) {
                const replayItem = completedQueueItem
                    ?? queueRef.current.find((item) => resolveSpotifyTrackId(item) === spotifyTrackId)
                if (replayItem) {
                    recordPlaybackEvent('replay', replayItem, { positionMs: 0 })
                }
            }

            // In single-track playback mode the SDK will not auto-advance — when the
            // track ends and pauses with no follow-up URI queued, fire our ended
            // handler so we can resolve and start the next queue entry.
            if (state.paused && (spotifyTrackId === prev.trackId || spotifyTrackId === null)) {
                spotifyEndedHandlerRef.current?.()
            }
        }

        lastSpotifyStateRef.current = {
            trackId: spotifyTrackId,
            position: state.position ?? 0,
            duration: state.duration ?? 0,
            completionEmittedForTrackId: spotifyTrackId === prev.trackId
                ? nextCompletionEmittedForTrackId
                : null,
        }

        if (!spotifyTrackId) {
            return
        }

        if (nextIndex >= 0) {
            setPlaybackQueueState(queueRef.current, nextIndex, queueRef.current[nextIndex])
        }
    }, [clearPlaybackError, recordPlaybackEvent, session?.preferredPlatformId, setPlaybackQueueState])

    const spotifyCallbacks = useMemo(
        () => ({
            onReady: (nextDeviceId: string) => setDeviceId(nextDeviceId),
            onStateChange: handleSpotifyStateChange,
            onError: (message: string) => {
                skipCurrentTrackAfterPlaybackError(message)
            },
        }),
        [handleSpotifyStateChange, skipCurrentTrackAfterPlaybackError],
    )

    const handleTidalStateChange = useCallback((state: TidalPlaybackSnapshot['state'], snapshot: TidalPlaybackSnapshot) => {
        const activeItem = currentItemRef.current
        if (!activeItem || resolvePlaybackPlatformId(activeItem, session?.preferredPlatformId) !== 'tidal') {
            return
        }

        const nextIndex = snapshot.productId
            ? queueRef.current.findIndex((item) => resolveTidalTrackId(item) === snapshot.productId)
            : -1
        if (snapshot.productId && nextIndex < 0) {
            return
        }

        if (isTidalPreviewSnapshot(snapshot)) {
            const message = describeTidalPreviewFailure(snapshot)
            setNotice(null)
            setError(message)
            setIsPlaying(false)
            setPositionMs(0)
            if (!tidalPreviewBlockedRef.current) {
                tidalPreviewBlockedRef.current = true
                void tidalReset().catch(() => undefined)
            }
            skipCurrentTrackAfterPlaybackError(message)
            return
        }

        tidalPreviewBlockedRef.current = false
        setNotice(null)
        setIsPlaying(state === 'PLAYING' || state === 'STALLED')
        setPositionMs(snapshot.positionMs)
        setAudioQualityLabel(formatTidalAudioQuality(snapshot, tidalPlaybackQuality))
        if (snapshot.durationMs > 0) {
            setDurationMs(snapshot.durationMs)
        }

        if (state === 'PLAYING') {
            clearPlaybackError()
        }

        if (!snapshot.productId) {
            return
        }

        if (nextIndex >= 0) {
            setPlaybackQueueState(queueRef.current, nextIndex, queueRef.current[nextIndex])
        }
    }, [clearPlaybackError, session?.preferredPlatformId, setPlaybackQueueState, skipCurrentTrackAfterPlaybackError, tidalPlaybackQuality])

    const tryYouTubeFallbackForTrack = useCallback(
        async (
            userId: string,
            sourceQueue: PlaybackMediaItem[],
            attemptIndex: number,
            candidateItem: PlaybackMediaItem,
            skippedMessage: string | null,
            isActiveRequest: () => boolean,
        ) => {
            if (!isActiveRequest()) {
                return null
            }
            setNotice('Trying YouTube fallback...')
            const playableItem = await resolveYouTubePlayableItem(userId, candidateItem)
            const youtubeVideoId = resolveYouTubeVideoId(playableItem)
            if (!youtubeVideoId) {
                throw new Error(`Track does not have a valid YouTube video id: ${playableItem.title}`)
            }
            if (!isActiveRequest()) {
                return null
            }

            const resolvedQueue = replaceQueueItem(sourceQueue, attemptIndex, playableItem)
            setPlaybackQueueState(resolvedQueue, attemptIndex, playableItem)
            setPositionMs(0)
            setDurationMs(playableItem.durationMs ?? 0)
            setAudioQualityLabel('YouTube')
            await youtubeSetVolume(volumeStateRef.current)
            await playYouTubeVideo(youtubeVideoId, youtubeCallbacksRef.current, volumeStateRef.current)
            if (!isActiveRequest()) {
                return null
            }

            setIsPlaying(true)
            setNotice(null)
            if (skippedMessage) {
                setError(skippedMessage)
            } else {
                clearPlaybackError()
            }
            return { item: playableItem, index: attemptIndex, queue: resolvedQueue }
        },
        [clearPlaybackError, setPlaybackQueueState],
    )

    const fallbackCurrentTidalTrackAfterPlaybackError = useCallback((message: string) => {
        if (autoSkipInFlightRef.current) {
            return true
        }

        const activeItem = currentItemRef.current
        const activeQueue = queueRef.current
        const activeIndex = currentIndexRef.current
        if (
            !session?.userId
            || !activeItem
            || !activeQueue[activeIndex]
            || !isRecoverableTrackPlaybackError(new Error(message))
        ) {
            return skipCurrentTrackAfterPlaybackError(message)
        }

        autoSkipInFlightRef.current = true
        void tryYouTubeFallbackForTrack(
            session.userId,
            activeQueue,
            activeIndex,
            activeItem,
            null,
            () => true,
        )
            .catch((youtubeFallbackError: unknown) => {
                autoSkipInFlightRef.current = false
                const fallbackMessage = playbackErrorMessage(youtubeFallbackError, 'YouTube fallback failed.')
                setNotice('YouTube fallback failed; trying next track...')
                skipCurrentTrackAfterPlaybackError(fallbackMessage)
            })
            .finally(() => {
                autoSkipInFlightRef.current = false
            })
        return true
    }, [session?.userId, skipCurrentTrackAfterPlaybackError, tryYouTubeFallbackForTrack])

    const playTidalQueueFromIndex = useCallback(
        async (
            userId: string,
            sourceQueue: PlaybackMediaItem[],
            startIndex: number,
            isActiveRequest: () => boolean = () => true,
        ) => {
            let attemptIndex = startIndex
            let resolvedQueue = sourceQueue
            let skippedMessage: string | null = null
            while (attemptIndex < resolvedQueue.length) {
                const candidateItem = resolvedQueue[attemptIndex]
                if (!candidateItem) {
                    break
                }

                try {
                    if (!isActiveRequest()) {
                        return null
                    }
                    setPlaybackQueueState(resolvedQueue, attemptIndex, candidateItem)
                    setPositionMs(0)
                    setDurationMs(candidateItem.durationMs ?? 0)
                    setNotice('Searching TIDAL for playable track...')
                    const playableItem = await resolveTidalPlayableItem(userId, candidateItem)
                    if (!isActiveRequest()) {
                        return null
                    }
                    resolvedQueue = replaceQueueItem(resolvedQueue, attemptIndex, playableItem)
                    setPlaybackQueueState(resolvedQueue, attemptIndex, playableItem)
                    setDurationMs(playableItem.durationMs ?? 0)
                    setNotice('Starting TIDAL stream...')
                    await tidalSetVolume(volumeStateRef.current)
                    await playTidalMediaItem(
                        userId,
                        playableItem,
                        resolvedQueue[attemptIndex + 1],
                        tidalCallbacksRef.current,
                        tidalPlaybackQuality,
                    )
                    if (!isActiveRequest()) {
                        return null
                    }
                    setIsPlaying(true)
                    setNotice(null)
                    if (skippedMessage) {
                        setError(skippedMessage)
                    } else {
                        clearPlaybackError()
                    }
                    return { item: playableItem, index: attemptIndex, queue: resolvedQueue }
                } catch (playbackError: unknown) {
                    if (!isActiveRequest()) {
                        return null
                    }
                    let nextPlaybackError = playbackError
                    if (isRecoverableTrackPlaybackError(playbackError)) {
                        try {
                            const fallbackStarted = await tryYouTubeFallbackForTrack(
                                userId,
                                resolvedQueue,
                                attemptIndex,
                                candidateItem,
                                skippedMessage,
                                isActiveRequest,
                            )
                            if (fallbackStarted || !isActiveRequest()) {
                                return fallbackStarted
                            }
                        } catch (youtubeFallbackError: unknown) {
                            nextPlaybackError = youtubeFallbackError
                        }
                    }
                    const hasNextTrack = attemptIndex < resolvedQueue.length - 1
                    if (!isRecoverableTrackPlaybackError(nextPlaybackError)) {
                        throw nextPlaybackError
                    }
                    if (!hasNextTrack) {
                        throw nextPlaybackError
                    }
                    skippedMessage = skippedTrackMessage(candidateItem, nextPlaybackError)
                    setError(skippedMessage)
                    setNotice('Trying next track...')
                    attemptIndex += 1
                }
            }

            throw new Error('No playable TIDAL tracks remained in the queue.')
        },
        [clearPlaybackError, setPlaybackQueueState, tidalPlaybackQuality, tryYouTubeFallbackForTrack],
    )

    const playSpotifyTrackFromQueue = useCallback(
        async (
            userId: string,
            sourceQueue: PlaybackMediaItem[],
            startIndex: number,
            isActiveRequest: () => boolean = () => true,
        ) => {
            let attemptIndex = startIndex
            let resolvedQueue = sourceQueue
            let skippedMessage: string | null = null
            while (attemptIndex < resolvedQueue.length) {
                const candidateItem = resolvedQueue[attemptIndex]
                if (!candidateItem) {
                    break
                }
                try {
                    if (!isActiveRequest()) {
                        return null
                    }
                    setPlaybackQueueState(resolvedQueue, attemptIndex, candidateItem)
                    setPositionMs(0)
                    setDurationMs(candidateItem.durationMs ?? 0)
                    setNotice('Searching Spotify for playable track...')
                    const playableItem = await resolveSpotifyPlayableItem(userId, candidateItem)
                    const uri = toSpotifyUri(playableItem)
                    if (!uri) {
                        throw new Error(`Track does not have a valid Spotify URI: ${playableItem.title}`)
                    }
                    if (!isActiveRequest()) {
                        return null
                    }
                    resolvedQueue = replaceQueueItem(resolvedQueue, attemptIndex, playableItem)
                    setPlaybackQueueState(resolvedQueue, attemptIndex, playableItem)
                    setDurationMs(playableItem.durationMs ?? 0)
                    setNotice('Starting Spotify playback...')
                    await playSpotifyUris(userId, [uri], 0)
                    if (!isActiveRequest()) {
                        return null
                    }
                    setIsPlaying(true)
                    setNotice(null)
                    if (skippedMessage) {
                        setError(skippedMessage)
                    } else {
                        clearPlaybackError()
                    }
                    return { item: playableItem, index: attemptIndex, queue: resolvedQueue }
                } catch (playbackError: unknown) {
                    if (!isActiveRequest()) {
                        return null
                    }
                    let nextPlaybackError = playbackError
                    if (isRecoverableTrackPlaybackError(playbackError)) {
                        try {
                            const fallbackStarted = await tryYouTubeFallbackForTrack(
                                userId,
                                resolvedQueue,
                                attemptIndex,
                                candidateItem,
                                skippedMessage,
                                isActiveRequest,
                            )
                            if (fallbackStarted || !isActiveRequest()) {
                                return fallbackStarted
                            }
                        } catch (youtubeFallbackError: unknown) {
                            nextPlaybackError = youtubeFallbackError
                        }
                    }
                    const hasNextTrack = attemptIndex < resolvedQueue.length - 1
                    if (!isRecoverableTrackPlaybackError(nextPlaybackError)) {
                        throw nextPlaybackError
                    }
                    if (!hasNextTrack) {
                        throw nextPlaybackError
                    }
                    skippedMessage = skippedTrackMessage(candidateItem, nextPlaybackError)
                    setError(skippedMessage)
                    setNotice('Trying next track...')
                    attemptIndex += 1
                }
            }
            throw new Error('No playable Spotify tracks remained in the queue.')
        },
        [clearPlaybackError, setPlaybackQueueState, tryYouTubeFallbackForTrack],
    )

    const playYouTubeQueueFromIndex = useCallback(
        async (
            userId: string,
            sourceQueue: PlaybackMediaItem[],
            startIndex: number,
            isActiveRequest: () => boolean = () => true,
        ) => {
            let attemptIndex = startIndex
            let resolvedQueue = sourceQueue
            let skippedMessage: string | null = null
            while (attemptIndex < resolvedQueue.length) {
                const candidateItem = resolvedQueue[attemptIndex]
                if (!candidateItem) {
                    break
                }

                try {
                    if (!isActiveRequest()) {
                        return null
                    }
                    setPlaybackQueueState(resolvedQueue, attemptIndex, candidateItem)
                    setPositionMs(0)
                    setDurationMs(candidateItem.durationMs ?? 0)
                    setNotice('Searching YouTube for playable video...')
                    const playableItem = await resolveYouTubePlayableItem(userId, candidateItem)
                    const youtubeVideoId = resolveYouTubeVideoId(playableItem)
                    if (!youtubeVideoId) {
                        throw new Error(`Track does not have a valid YouTube video id: ${playableItem.title}`)
                    }
                    if (!isActiveRequest()) {
                        return null
                    }
                    resolvedQueue = replaceQueueItem(resolvedQueue, attemptIndex, playableItem)
                    setPlaybackQueueState(resolvedQueue, attemptIndex, playableItem)
                    setDurationMs(playableItem.durationMs ?? 0)
                    setAudioQualityLabel('YouTube')
                    setNotice('Starting YouTube playback...')
                    await youtubeSetVolume(volumeStateRef.current)
                    await playYouTubeVideo(youtubeVideoId, youtubeCallbacksRef.current, volumeStateRef.current)
                    if (!isActiveRequest()) {
                        return null
                    }
                    setIsPlaying(true)
                    setNotice(null)
                    if (skippedMessage) {
                        setError(skippedMessage)
                    } else {
                        clearPlaybackError()
                    }
                    return { item: playableItem, index: attemptIndex, queue: resolvedQueue }
                } catch (playbackError: unknown) {
                    if (!isActiveRequest()) {
                        return null
                    }
                    const hasNextTrack = attemptIndex < resolvedQueue.length - 1
                    if (!isRecoverableTrackPlaybackError(playbackError)) {
                        throw playbackError
                    }
                    if (!hasNextTrack) {
                        throw playbackError
                    }
                    skippedMessage = skippedTrackMessage(candidateItem, playbackError)
                    setError(skippedMessage)
                    setNotice('Trying next track...')
                    attemptIndex += 1
                }
            }
            throw new Error('No playable YouTube videos remained in the queue.')
        },
        [clearPlaybackError, setPlaybackQueueState],
    )

    const playQueueItemByOriginalPlatform = useCallback(
        async (
            userId: string,
            sourceQueue: PlaybackMediaItem[],
            nextIndex: number,
        ) => {
            const nextItem = sourceQueue[nextIndex]
            if (!nextItem) {
                return null
            }

            const nextPlaybackPlatformId = resolvePostFallbackQueuePlatformId(nextItem, session?.preferredPlatformId)
            const activeItem = currentItemRef.current
            const activePlaybackPlatformId = activeItem
                ? resolvePlaybackPlatformId(activeItem, session?.preferredPlatformId)
                : null

            if (activePlaybackPlatformId !== nextPlaybackPlatformId) {
                if (activePlaybackPlatformId === 'youtube') {
                    await youtubeStop()
                } else if (activePlaybackPlatformId === 'tidal') {
                    await tidalReset()
                } else if (activePlaybackPlatformId === 'spotify') {
                    await spotifyPause(userId)
                }
            }

            if (nextPlaybackPlatformId === 'tidal') {
                return playTidalQueueFromIndex(userId, sourceQueue, nextIndex)
            }
            if (nextPlaybackPlatformId === 'spotify') {
                return playSpotifyTrackFromQueue(userId, sourceQueue, nextIndex)
            }
            return playYouTubeQueueFromIndex(userId, sourceQueue, nextIndex)
        },
        [
            playSpotifyTrackFromQueue,
            playTidalQueueFromIndex,
            playYouTubeQueueFromIndex,
            session?.preferredPlatformId,
        ],
    )

    const handleTidalEnded = useCallback(() => {
        const nextQueue = queueRef.current
        const completedItem = nextQueue[currentIndexRef.current]
        if (completedItem) {
            const completedDurationMs = durationMsRef.current || completedItem.durationMs || 0
            recordPlaybackEvent('play_completed', completedItem, {
                durationMs: completedDurationMs,
                positionMs: completedDurationMs,
            })
        }

        let nextIndex = currentIndexRef.current + 1
        let isReplay = false
        if (repeatModeRef.current === 'one') {
            nextIndex = currentIndexRef.current
            isReplay = true
        } else if (nextIndex >= nextQueue.length && repeatModeRef.current === 'all') {
            nextIndex = 0
        }
        const nextItem = nextQueue[nextIndex]

        if (!session?.userId || !nextItem) {
            setIsPlaying(false)
            return
        }

        if (isReplay) {
            recordPlaybackEvent('replay', nextItem, { positionMs: 0 })
        }

        void playQueueItemByOriginalPlatform(session.userId, nextQueue, nextIndex).catch((playbackError: unknown) => {
            const message = playbackError instanceof Error ? playbackError.message : 'TIDAL playback failed.'
            setError(message)
            setNotice(null)
            setIsPlaying(false)
        })
    }, [playQueueItemByOriginalPlatform, recordPlaybackEvent, session?.userId])

    const handleSpotifyEnded = useCallback(() => {
        const nextQueue = queueRef.current
        if (nextQueue.length === 0) {
            setIsPlaying(false)
            return
        }

        let nextIndex = currentIndexRef.current + 1
        let isReplay = false
        if (repeatModeRef.current === 'one') {
            nextIndex = currentIndexRef.current
            isReplay = true
        } else if (nextIndex >= nextQueue.length && repeatModeRef.current === 'all') {
            nextIndex = 0
        }
        const nextItem = nextQueue[nextIndex]

        if (!session?.userId || !nextItem) {
            setIsPlaying(false)
            return
        }

        if (isReplay) {
            recordPlaybackEvent('replay', nextItem, { positionMs: 0 })
        }

        void playQueueItemByOriginalPlatform(session.userId, nextQueue, nextIndex).catch((playbackError: unknown) => {
            const message = playbackError instanceof Error ? playbackError.message : 'Spotify playback failed.'
            setError(message)
            setNotice(null)
            setIsPlaying(false)
        })
    }, [playQueueItemByOriginalPlatform, recordPlaybackEvent, session?.userId])

    useEffect(() => {
        spotifyEndedHandlerRef.current = handleSpotifyEnded
    }, [handleSpotifyEnded])

    const tidalCallbacks = useMemo<TidalPlayerCallbacks>(
        () => ({
            onReady: (nextDeviceId: string) => setDeviceId(nextDeviceId),
            onStateChange: handleTidalStateChange,
            onTransition: (_productId, snapshot) => handleTidalStateChange(snapshot.state, snapshot),
            onEnded: handleTidalEnded,
            onError: (message: string) => {
                fallbackCurrentTidalTrackAfterPlaybackError(message)
            },
        }),
        [fallbackCurrentTidalTrackAfterPlaybackError, handleTidalEnded, handleTidalStateChange],
    )

    useEffect(() => {
        tidalCallbacksRef.current = tidalCallbacks
    }, [tidalCallbacks])

    const handleYouTubeStateChange = useCallback((state: YouTubePlaybackSnapshot['state'], snapshot: YouTubePlaybackSnapshot) => {
        const activeItem = currentItemRef.current
        if (!activeItem || resolvePlaybackPlatformId(activeItem, session?.preferredPlatformId) !== 'youtube') {
            return
        }

        setNotice(null)
        setIsPlaying(state === 'PLAYING' || state === 'BUFFERING')
        setPositionMs(snapshot.positionMs)
        setAudioQualityLabel('YouTube')
        if (snapshot.durationMs > 0) {
            setDurationMs(snapshot.durationMs)
        }
        if (state === 'PLAYING') {
            clearPlaybackError()
        }
    }, [clearPlaybackError, session?.preferredPlatformId])

    const handleYouTubeEnded = useCallback(() => {
        const nextQueue = queueRef.current
        const completedItem = nextQueue[currentIndexRef.current]
        if (completedItem) {
            const completedDurationMs = durationMsRef.current || completedItem.durationMs || 0
            recordPlaybackEvent('play_completed', completedItem, {
                durationMs: completedDurationMs,
                positionMs: completedDurationMs,
            })
        }

        let nextIndex = currentIndexRef.current + 1
        let isReplay = false
        if (repeatModeRef.current === 'one') {
            nextIndex = currentIndexRef.current
            isReplay = true
        } else if (nextIndex >= nextQueue.length && repeatModeRef.current === 'all') {
            nextIndex = 0
        }
        const nextItem = nextQueue[nextIndex]

        if (!session?.userId || !nextItem) {
            setIsPlaying(false)
            return
        }

        if (isReplay) {
            recordPlaybackEvent('replay', nextItem, { positionMs: 0 })
        }

        void playQueueItemByOriginalPlatform(session.userId, nextQueue, nextIndex)
            .catch((playbackError: unknown) => {
                const message = playbackError instanceof Error ? playbackError.message : 'YouTube playback failed.'
                setError(message)
                setNotice(null)
                setIsPlaying(false)
            })
    }, [playQueueItemByOriginalPlatform, recordPlaybackEvent, session])

    const youtubeCallbacks = useMemo<YouTubePlayerCallbacks>(
        () => ({
            onReady: (nextDeviceId: string) => setDeviceId(nextDeviceId),
            onStateChange: handleYouTubeStateChange,
            onEnded: handleYouTubeEnded,
            onError: (message: string) => {
                skipCurrentTrackAfterPlaybackError(message)
            },
        }),
        [handleYouTubeEnded, handleYouTubeStateChange, skipCurrentTrackAfterPlaybackError],
    )

    useEffect(() => {
        youtubeCallbacksRef.current = youtubeCallbacks
    }, [youtubeCallbacks])

    const requireUserId = useCallback(() => {
        if (!session?.userId) {
            throw new Error('Sign in before starting playback.')
        }
        return session.userId
    }, [session?.userId])

    const playQueue = useCallback(
        async (items: PlaybackMediaItem[], startIndex = 0) => {
            const playbackRequestId = playbackRequestIdRef.current + 1
            playbackRequestIdRef.current = playbackRequestId
            const isActiveRequest = () => playbackRequestIdRef.current === playbackRequestId

            if (items.length === 0) {
                setError('No tracks were provided for playback.')
                return
            }

            const userId = requireUserId()
            const nextPlaybackItems = shuffleEnabledRef.current
                ? shuffledQueueWithStart(items, startIndex)
                : items
            const safeStartIndex = shuffleEnabledRef.current ? 0 : clampIndex(startIndex, nextPlaybackItems.length)
            const selectedItem = nextPlaybackItems[safeStartIndex]
            const playbackPlatformId = resolvePlaybackPlatformId(selectedItem, session?.preferredPlatformId)
            const pendingItems = nextPlaybackItems.map((item, index) =>
                index === safeStartIndex ? { ...item, playbackPlatformId } : item
            )
            const pendingSelectedItem = pendingItems[safeStartIndex]
            const previousItem = currentItemRef.current

            setIsLoading(true)
            clearPlaybackError()
            setNotice(null)
            resetPlaybackSurface()

            try {
                if (previousItem) {
                    const previousPlatformId = resolvePlaybackPlatformId(previousItem, session?.preferredPlatformId)
                    if (previousPlatformId === 'tidal') {
                        await tidalReset()
                    } else if (previousPlatformId === 'spotify') {
                        await spotifyPause(userId)
                    } else if (previousPlatformId === 'youtube') {
                        await youtubeStop()
                    }
                }

                if (!isActiveRequest()) {
                    return
                }

                setPlaybackQueueState(pendingItems, safeStartIndex, pendingSelectedItem)
                setPositionMs(0)
                setDurationMs(pendingSelectedItem.durationMs ?? 0)

                if (playbackPlatformId === 'spotify') {
                    setNotice('Preparing Spotify playback...')
                    await ensureSpotifyWebPlayer(userId, spotifyCallbacks)
                    if (!isActiveRequest()) {
                        return
                    }
                    await spotifySetVolume(userId, volumeState)
                    if (!isActiveRequest()) {
                        return
                    }
                    setAudioQualityLabel('Spotify')
                    const started = await playSpotifyTrackFromQueue(userId, pendingItems, safeStartIndex, isActiveRequest)
                    if (!started || !isActiveRequest()) {
                        return
                    }
                    recordPlaybackEvent('play_started', started.item, {
                        durationMs: started.item.durationMs ?? 0,
                        positionMs: 0,
                    })
                    return
                }

                if (playbackPlatformId === 'tidal') {
                    setNotice('Preparing TIDAL playback...')
                    await ensureTidalWebPlayer(userId, tidalCallbacks)
                    if (!isActiveRequest()) {
                        return
                    }

                    setDeviceId(getTidalDeviceId())
                    const started = await playTidalQueueFromIndex(userId, pendingItems, safeStartIndex, isActiveRequest)
                    if (!started || !isActiveRequest()) {
                        return
                    }
                    recordPlaybackEvent('play_started', started.item, {
                        durationMs: started.item.durationMs ?? 0,
                        positionMs: 0,
                    })
                    return
                }

                if (playbackPlatformId === 'youtube') {
                    setNotice('Preparing YouTube playback...')
                    const started = await playYouTubeQueueFromIndex(userId, pendingItems, safeStartIndex, isActiveRequest)
                    if (!started || !isActiveRequest()) {
                        return
                    }
                    recordPlaybackEvent('play_started', started.item, {
                        durationMs: started.item.durationMs ?? 0,
                        positionMs: 0,
                    })
                    return
                }

                throw new Error(`Playback is not implemented for ${playbackPlatformId ?? 'unknown'} tracks yet.`)
            } catch (playbackError: unknown) {
                if (!isActiveRequest()) {
                    return
                }
                if (playbackPlatformId !== 'youtube') {
                    try {
                        setNotice('Trying YouTube fallback...')
                        const fallbackStarted = await playYouTubeQueueFromIndex(userId, pendingItems, safeStartIndex, isActiveRequest)
                        if (fallbackStarted && isActiveRequest()) {
                            recordPlaybackEvent('play_started', fallbackStarted.item, {
                                durationMs: fallbackStarted.item.durationMs ?? 0,
                                positionMs: 0,
                            })
                            return
                        }
                    } catch (youtubeError: unknown) {
                        if (!isActiveRequest()) {
                            return
                        }
                        const message = youtubeError instanceof Error ? youtubeError.message : 'YouTube fallback failed.'
                        setError(message)
                        setNotice(null)
                        setIsPlaying(false)
                        return
                    }
                }
                const message = playbackError instanceof Error ? playbackError.message : 'Playback failed.'
                setError(message)
                setNotice(null)
                setIsPlaying(false)
            } finally {
                if (isActiveRequest()) {
                    setIsLoading(false)
                }
            }
        },
        [
            clearPlaybackError,
            playSpotifyTrackFromQueue,
            playTidalQueueFromIndex,
            playYouTubeQueueFromIndex,
            recordPlaybackEvent,
            requireUserId,
            resetPlaybackSurface,
            session?.preferredPlatformId,
            setPlaybackQueueState,
            spotifyCallbacks,
            tidalCallbacks,
            volumeState,
        ],
    )
    playQueueRef.current = playQueue

    const playItem = useCallback((item: PlaybackMediaItem) => playQueue([item], 0), [playQueue])

    const appendToQueue = useCallback(
        async (items: PlaybackMediaItem[]) => {
            if (items.length === 0) {
                setError('No tracks were provided for playback.')
                return
            }
            if (!currentItem || queueRef.current.length === 0) {
                await playQueue(items, 0)
                return
            }

            const userId = requireUserId()
            const currentPlaybackPlatformId = resolvePlaybackPlatformId(currentItem, session?.preferredPlatformId)

            setIsLoading(true)
            clearPlaybackError()
            setNotice(`Adding ${items.length} track(s) to queue...`)

            try {
                if (
                    currentPlaybackPlatformId !== 'spotify'
                    && currentPlaybackPlatformId !== 'tidal'
                    && currentPlaybackPlatformId !== 'youtube'
                ) {
                    throw new Error(`Playback queue append is not implemented for ${currentPlaybackPlatformId ?? 'unknown'} tracks yet.`)
                }
                // Single-track playback model: append to the internal queue only.
                // The next track is resolved + dispatched per platform on skipNext / handleEnded.
                const appendedItems = currentPlaybackPlatformId === 'spotify'
                    ? await Promise.all(items.map((item) => resolveSpotifyPlayableItem(userId, item)))
                    : items
                if (currentPlaybackPlatformId === 'spotify') {
                    for (const item of appendedItems) {
                        const spotifyUri = item.platformUri ??
                            (item.spotifyTrackId ? `spotify:track:${item.spotifyTrackId}` : null)
                        if (!spotifyUri) {
                            throw new Error(`Unable to resolve Spotify queue URI for "${item.title}".`)
                        }
                        await addSpotifyUriToQueue(userId, spotifyUri)
                    }
                }
                const nextQueue = [...queueRef.current, ...appendedItems]
                queueRef.current = nextQueue
                setQueue(nextQueue)
                setNotice(`Added ${items.length} track(s) to queue.`)
            } catch (playbackError: unknown) {
                const message = playbackError instanceof Error ? playbackError.message : 'Unable to add tracks to queue.'
                setError(message)
                setNotice(null)
            } finally {
                setIsLoading(false)
            }
        },
        [clearPlaybackError, currentItem, playQueue, requireUserId, session?.preferredPlatformId],
    )

    const pause = useCallback(async () => {
        if (!currentItem) {
            return
        }

        const userId = requireUserId()
        const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session?.preferredPlatformId)
        if (playbackPlatformId === 'tidal') {
            await tidalPause()
        } else if (playbackPlatformId === 'youtube') {
            await youtubePause()
        } else {
            await spotifyPause(userId)
        }
        setIsPlaying(false)
        recordPlaybackEvent('play_paused', currentItem)
    }, [currentItem, recordPlaybackEvent, requireUserId, session?.preferredPlatformId])

    const resume = useCallback(async () => {
        if (!currentItem) {
            return
        }

        const userId = requireUserId()
        clearPlaybackError()
        const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session?.preferredPlatformId)
        if (playbackPlatformId === 'tidal') {
            await ensureTidalWebPlayer(userId, tidalCallbacks)
            await tidalSetVolume(volumeState)
            await tidalResume()
        } else if (playbackPlatformId === 'youtube') {
            await youtubeSetVolume(volumeState)
            await youtubeResume()
        } else {
            await ensureSpotifyWebPlayer(userId, spotifyCallbacks)
            await spotifyResume(userId)
        }
        setIsPlaying(true)
        recordPlaybackEvent('play_resumed', currentItem)
    }, [clearPlaybackError, currentItem, recordPlaybackEvent, requireUserId, session?.preferredPlatformId, spotifyCallbacks, tidalCallbacks, volumeState])

    const skipNext = useCallback(async () => {
        if (!currentItem) {
            return
        }

        const isAtQueueEnd = currentIndexRef.current >= queueRef.current.length - 1
        const nextIndex = isAtQueueEnd && repeatModeRef.current === 'all'
            ? 0
            : clampIndex(currentIndexRef.current + 1, queueRef.current.length)
        const userId = requireUserId()
        if (isAtQueueEnd && repeatModeRef.current !== 'all') {
            const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session?.preferredPlatformId)
            if (playbackPlatformId === 'tidal') {
                await tidalReset()
            } else if (playbackPlatformId === 'youtube') {
                await youtubeStop()
            } else {
                await spotifyPause(userId)
            }
            setIsPlaying(false)
            setPositionMs(0)
            recordPlaybackEvent('skip_next', currentItem)
            return
        }

        const started = await playQueueItemByOriginalPlatform(userId, queueRef.current, nextIndex)
        if (started) {
            recordPlaybackEvent('skip_next', currentItem)
        }
    }, [currentItem, playQueueItemByOriginalPlatform, recordPlaybackEvent, requireUserId, session?.preferredPlatformId])

    const skipPrevious = useCallback(async () => {
        if (!currentItem) {
            return
        }

        const nextIndex = clampIndex(currentIndexRef.current - 1, queueRef.current.length)
        const userId = requireUserId()
        const started = await playQueueItemByOriginalPlatform(userId, queueRef.current, nextIndex)
        if (started) {
            recordPlaybackEvent('skip_previous', currentItem)
        }
    }, [currentItem, playQueueItemByOriginalPlatform, recordPlaybackEvent, requireUserId])

    const seek = useCallback(
        async (nextPositionMs: number) => {
            if (!currentItem) {
                return
            }

            const userId = requireUserId()
            const safePosition = Math.min(Math.max(0, nextPositionMs), Math.max(durationMs, 0))
            setPositionMs(safePosition)
            const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session?.preferredPlatformId)
            if (playbackPlatformId === 'tidal') {
                await tidalSeek(safePosition)
            } else if (playbackPlatformId === 'youtube') {
                await youtubeSeek(safePosition)
            } else {
                await spotifySeek(userId, safePosition)
            }
        },
        [currentItem, durationMs, requireUserId, session?.preferredPlatformId],
    )

    const setVolume = useCallback(
        async (nextVolume: number) => {
            const safeVolume = Math.min(1, Math.max(0, Number.isFinite(nextVolume) ? nextVolume : 0.5))
            setVolumeState(safeVolume)
            if (!session?.userId) {
                return
            }

            if (currentItem && resolvePlaybackPlatformId(currentItem, session.preferredPlatformId) === 'tidal') {
                await tidalSetVolume(safeVolume)
                return
            }

            if (currentItem && resolvePlaybackPlatformId(currentItem, session.preferredPlatformId) === 'youtube') {
                await youtubeSetVolume(safeVolume)
                return
            }

            await spotifySetVolume(session.userId, safeVolume)
        },
        [currentItem, session],
    )

    const toggleShuffle = useCallback(async () => {
        const nextShuffleEnabled = !shuffleEnabledRef.current
        shuffleEnabledRef.current = nextShuffleEnabled
        setShuffleEnabled(nextShuffleEnabled)

        if (nextShuffleEnabled && queueRef.current.length > 1) {
            const nextQueue = shuffledQueueWithStart(queueRef.current, currentIndexRef.current)
            setPlaybackQueueState(nextQueue, 0, nextQueue[0])
        }
        // Shuffle/repeat are tracked locally; we never delegate to the Spotify SDK's
        // shuffle/repeat state since we drive playback one URI at a time.
    }, [setPlaybackQueueState])

    const cycleRepeatMode = useCallback(async () => {
        const nextMode = nextRepeatMode(repeatModeRef.current)
        repeatModeRef.current = nextMode
        setRepeatMode(nextMode)
    }, [])

    const setTidalPlaybackQuality = useCallback((quality: TidalPlaybackQuality) => {
        writeTidalPlaybackQuality(quality)
        setTidalPlaybackQualityState(quality)
        const activeItem = currentItemRef.current
        if (activeItem && resolvePlaybackPlatformId(activeItem, session?.preferredPlatformId) === 'tidal') {
            setAudioQualityLabel(formatTidalAudioQuality(getTidalCurrentSnapshot(), quality))
        }
    }, [session?.preferredPlatformId])

    const clearItem = useCallback(() => {
        playbackRequestIdRef.current += 1
        if (session?.userId && currentItem) {
            const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session.preferredPlatformId)
            if (playbackPlatformId === 'tidal') {
                void tidalReset().catch(() => undefined)
            } else if (playbackPlatformId === 'youtube') {
                void youtubeStop().catch(() => undefined)
            } else {
                void spotifyPause(session.userId).catch(() => undefined)
            }
        }
        resetPlaybackSurface()
        setIsPlaying(false)
        setIsLoading(false)
        clearPlaybackError()
        setNotice(null)
    }, [clearPlaybackError, currentItem, resetPlaybackSurface, session])

    useEffect(() => {
        if (!session?.userId || !currentItem) {
            return
        }

        const playbackPlatformId = resolvePlaybackPlatformId(currentItem, session.preferredPlatformId)
        const intervalId = window.setInterval(() => {
            if (playbackPlatformId === 'tidal') {
                const snapshot = getTidalCurrentSnapshot()
                handleTidalStateChange(snapshot.state, snapshot)
                return
            }

            if (playbackPlatformId === 'youtube') {
                const snapshot = getYouTubeCurrentSnapshot()
                handleYouTubeStateChange(snapshot.state, snapshot)
                return
            }

            void getSpotifyCurrentState(session.userId)
                .then(handleSpotifyStateChange)
                .catch(() => undefined)
        }, 2_500)

        return () => window.clearInterval(intervalId)
    }, [currentItem, handleSpotifyStateChange, handleTidalStateChange, handleYouTubeStateChange, session])

    const value = useMemo<PlaybackContextValue>(
        () => ({
            currentItem,
            queue,
            currentIndex,
            isPlaying,
            isLoading,
            error,
            notice,
            positionMs,
            durationMs,
            volume: volumeState,
            deviceId,
            shuffleEnabled,
            repeatMode,
            audioQualityLabel,
            tidalPlaybackQuality,
            playItem,
            playQueue,
            appendToQueue,
            pause,
            resume,
            skipNext,
            skipPrevious,
            seek,
            setVolume,
            toggleShuffle,
            cycleRepeatMode,
            setTidalPlaybackQuality,
            clearItem,
        }),
        [
            currentItem,
            queue,
            currentIndex,
            isPlaying,
            isLoading,
            error,
            notice,
            positionMs,
            durationMs,
            volumeState,
            deviceId,
            shuffleEnabled,
            repeatMode,
            audioQualityLabel,
            tidalPlaybackQuality,
            playItem,
            playQueue,
            appendToQueue,
            pause,
            resume,
            skipNext,
            skipPrevious,
            seek,
            setVolume,
            toggleShuffle,
            cycleRepeatMode,
            setTidalPlaybackQuality,
            clearItem,
        ],
    )

    return <PlaybackContext.Provider value={value}>{children}</PlaybackContext.Provider>
}

export const usePlayback = () => {
    const context = useContext(PlaybackContext)
    if (!context) {
        throw new Error('usePlayback must be used within PlaybackProvider')
    }
    return context
}
