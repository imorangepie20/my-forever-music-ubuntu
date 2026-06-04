import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertCircle, Clock3, Disc3, Headphones, Loader2, Music2, Play, Sparkles } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import publicCurationCoverUrl from '@/assets/public-curation/public-curation-cover.webp'
import PublicMixSpectrumPlayer, {
    type PublicMixRepeatMode,
} from '@/components/public-curation/PublicMixSpectrumPlayer'
import PublicTrackThumbnail from '@/components/public-curation/PublicTrackThumbnail'
import {
    preloadPublicCurationTidalAnalysis,
    useTidalAudioAnalyser,
} from '@/hooks/useTidalAudioAnalyser'
import {
    formatDuration,
    resolveYouTubeVideoId,
    type PlaybackMediaItem,
} from '@/lib/musicPlayback'
import {
    readTidalPlaybackQuality,
    writeTidalPlaybackQuality,
    type TidalPlaybackQuality,
} from '@/lib/tidalPlaybackQuality'
import {
    getTidalAudioElement,
    getTidalCurrentSnapshot,
    playPublicCurationTidalTrack,
    tidalPause,
    tidalResume,
    type TidalPlaybackSnapshot,
} from '@/lib/tidalStreamPlayback'
import {
    getYouTubeCurrentSnapshot,
    playYouTubeVideo,
    resolveYouTubePlayableItem,
    setYouTubePlayerHost,
    youtubePause,
    youtubeResume,
    youtubeStop,
} from '@/lib/youtubePlayback'
import {
    ApiError,
    completePublicCurationTidalDeviceAuthorization,
    fetchPublicCurationPlaybackSession,
    fetchPublicCurationShare,
    recordPublicCurationPlaybackEvent,
    startPublicCurationTidalDeviceAuthorization,
} from '@/services/api'
import type {
    PublicCurationPlaybackSession,
    PublicCurationShareResponse,
    PublicCurationShareTrack,
    PublicCurationTidalDeviceStartResponse,
} from '@/types/api'

const PUBLIC_CURATION_OAUTH_STORAGE_KEY = 'my-forever-music.public-curation-oauth'

const normalizeExternalTidalUrl = (value: string) => {
    const trimmed = value.trim()
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
        return trimmed
    }
    return `https://${trimmed}`
}

type PendingTidalDeviceAuthorization = {
    state: string
    device_code: string
    user_code: string
    verification_uri_complete: string | null
    expires_at: string
}

const formatTotalDuration = (durationMs: number | null | undefined) => {
    if (!durationMs || durationMs <= 0) {
        return '재생 시간 미정'
    }
    const totalMinutes = Math.round(durationMs / 60000)
    const hours = Math.floor(totalMinutes / 60)
    const minutes = totalMinutes % 60
    if (hours <= 0) {
        return `${minutes}분`
    }
    return `${hours}시간 ${minutes}분`
}

const INTERNAL_PUBLIC_CURATION_LABEL = ['Public', 'Curation'].join(' ')
const INTERNAL_TIDAL_READY_LABEL = ['TIDAL', 'ready'].join('-')

const shareDisplayTitle = (title: string) =>
    title
        .replace(new RegExp(`의 ${INTERNAL_PUBLIC_CURATION_LABEL}$`, 'u'), ' 믹스')
        .replace(new RegExp(` ${INTERNAL_PUBLIC_CURATION_LABEL}$`, 'u'), ' 믹스')
        .replace(new RegExp(INTERNAL_PUBLIC_CURATION_LABEL, 'gu'), '공유 믹스')

const shareDisplaySubtitle = (subtitle: string | null | undefined) => {
    if (!subtitle?.trim()) {
        return '좋은 흐름만 골라 담은 공유 믹스'
    }
    return subtitle
        .replace('모델이 고른 외부 공유용 플레이리스트', '좋은 흐름만 골라 담은 공유 믹스')
        .replace(new RegExp(INTERNAL_PUBLIC_CURATION_LABEL, 'gu'), '공유 믹스')
        .replace(new RegExp(INTERNAL_TIDAL_READY_LABEL, 'gu'), 'TIDAL 재생')
}

const shareDisplayDescription = (description: string | null | undefined) => {
    if (!description?.trim()) {
        return 'TIDAL 로그인 후 이 페이지에서 바로 들을 수 있습니다.'
    }
    if (description.includes(`${INTERNAL_TIDAL_READY_LABEL} 후보`) || description.includes('선별했습니다')) {
        return 'TIDAL 로그인 후 이 페이지에서 바로 들을 수 있는 공유 믹스입니다.'
    }
    return description
        .replace(new RegExp(INTERNAL_PUBLIC_CURATION_LABEL, 'gu'), '공유 믹스')
        .replace(new RegExp(INTERNAL_TIDAL_READY_LABEL, 'gu'), 'TIDAL 재생')
        .replace(/후보/gu, '곡')
}

const posterPalettes = [
    ['rgba(45,212,191,0.42)', 'rgba(244,114,182,0.34)', 'rgba(250,204,21,0.30)'],
    ['rgba(96,165,250,0.40)', 'rgba(52,211,153,0.32)', 'rgba(251,146,60,0.30)'],
    ['rgba(168,85,247,0.38)', 'rgba(14,165,233,0.34)', 'rgba(248,113,113,0.28)'],
    ['rgba(34,197,94,0.34)', 'rgba(236,72,153,0.34)', 'rgba(125,211,252,0.30)'],
    ['rgba(251,191,36,0.34)', 'rgba(45,212,191,0.32)', 'rgba(129,140,248,0.32)'],
]

const hashText = (value: string) =>
    Array.from(value).reduce((hash, character) => ((hash << 5) - hash + character.charCodeAt(0)) | 0, 0)

const playlistPosterPalette = (seed: string) => posterPalettes[Math.abs(hashText(seed)) % posterPalettes.length]

const createPlaybackQueue = (trackCount: number) =>
    Array.from({ length: trackCount }, (_, index) => index)

const shuffleQueueAfterPosition = (queue: number[], position: number | null) => {
    const shuffled = [...queue]
    const firstShuffledIndex = Math.max(0, (position ?? -1) + 1)
    for (let index = shuffled.length - 1; index > firstShuffledIndex; index -= 1) {
        const swapIndex = firstShuffledIndex + Math.floor(Math.random() * (index - firstShuffledIndex + 1))
        ;[shuffled[index], shuffled[swapIndex]] = [shuffled[swapIndex], shuffled[index]]
    }
    return shuffled
}

const nextRepeatMode = (mode: PublicMixRepeatMode): PublicMixRepeatMode => {
    if (mode === 'off') {
        return 'all'
    }
    if (mode === 'all') {
        return 'one'
    }
    return 'off'
}

const publicCurationPlaybackUserId = (slug: string) => `public-curation:${slug}`

const toPublicCurationPlaybackMediaItem = (track: PublicCurationShareTrack): PlaybackMediaItem => ({
    id: `public-curation-track:${track.track_id}`,
    kind: 'track',
    title: track.title,
    subtitle: `${track.artist_name}${track.album_title ? ` · ${track.album_title}` : ''}`,
    sourcePlatform: 'tidal',
    playbackPlatformId: 'tidal',
    externalTrackId: track.tidal_track_id,
    imageUrl: track.image_url,
    albumTitle: track.album_title,
    externalUrl: track.tidal_external_url,
    platformUri: track.tidal_uri,
    tidalTrackId: track.tidal_track_id,
    isrc: track.isrc,
    durationMs: track.duration_ms,
})

const playbackErrorMessage = (error: unknown, fallback: string) =>
    error instanceof Error && error.message ? error.message : fallback

const isRecoverablePublicCurationTidalPlaybackError = (error: unknown) => {
    const message = playbackErrorMessage(error, '').toLowerCase()
    if ([
        'authorization',
        'authorized',
        'authentication',
        'session',
        'token',
        'credential',
        'login',
        'sign in',
    ].some((phrase) => message.includes(phrase))) {
        return false
    }

    const status = error instanceof ApiError ? error.status : null
    if (status === 400 || status === 403 || status === 404) {
        return true
    }

    return [
        '403',
        '404',
        'failed to fetch',
        'forbidden',
        'not playable',
        'unplayable',
        'no playable',
        'no valid',
        'stream',
        'manifest',
        'playbackinfo',
        'could not start',
        'load failed',
        'returned preview',
    ].some((phrase) => message.includes(phrase))
}

const youtubeSnapshotToTidalLikeSnapshot = (
    snapshot: ReturnType<typeof getYouTubeCurrentSnapshot>,
): TidalPlaybackSnapshot => ({
    state: snapshot.state === 'PLAYING'
        ? 'PLAYING'
        : snapshot.state === 'BUFFERING'
          ? 'STALLED'
          : snapshot.state === 'NOT_PLAYING'
            ? 'NOT_PLAYING'
            : 'IDLE',
    positionMs: snapshot.positionMs,
    durationMs: snapshot.durationMs,
    productId: snapshot.videoId,
    audioQuality: 'YouTube',
    codec: 'YouTube',
})

const resolveNextQueuePosition = (
    position: number,
    queueLength: number,
    repeatMode: PublicMixRepeatMode,
) => {
    if (repeatMode === 'one') {
        return position
    }
    if (position + 1 < queueLength) {
        return position + 1
    }
    return repeatMode === 'all' && queueLength > 0 ? 0 : null
}

const readStoredPublicSession = (slug: string) => {
    if (typeof window === 'undefined') {
        return null
    }

    const rawSession = window.sessionStorage.getItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.session.${slug}`)
    if (!rawSession) {
        return null
    }

    try {
        const parsed = JSON.parse(rawSession) as { session?: PublicCurationPlaybackSession }
        return parsed.session?.session_id ? parsed.session : null
    } catch {
        window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.session.${slug}`)
        return null
    }
}

const readStoredPendingDeviceAuthorization = (slug: string) => {
    if (typeof window === 'undefined') {
        return null
    }

    const rawPending = window.sessionStorage.getItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.device.${slug}`)
    if (!rawPending) {
        return null
    }

    try {
        const parsed = JSON.parse(rawPending) as { authorization?: PendingTidalDeviceAuthorization }
        return parsed.authorization?.state && parsed.authorization.device_code ? parsed.authorization : null
    } catch {
        window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.device.${slug}`)
        return null
    }
}

const toPendingDeviceAuthorization = (
    response: PublicCurationTidalDeviceStartResponse,
): PendingTidalDeviceAuthorization => ({
    state: response.authorization.state,
    device_code: response.authorization.device_code,
    user_code: response.authorization.user_code,
    verification_uri_complete: response.authorization.verification_uri_complete,
    expires_at: response.authorization.expires_at,
})

const PublicCurationSharePage = () => {
    const { slug = '' } = useParams()
    const [searchParams] = useSearchParams()
    const playbackReadyFromCallback = searchParams.get('playback') === 'ready'
    const [payload, setPayload] = useState<PublicCurationShareResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [isStartingOAuth, setIsStartingOAuth] = useState(false)
    const [isCheckingSession, setIsCheckingSession] = useState(false)
    const [isCompletingDeviceAuthorization, setIsCompletingDeviceAuthorization] = useState(false)
    const [isStartingPlayback, setIsStartingPlayback] = useState(false)
    const [playingTrackId, setPlayingTrackId] = useState<number | null>(null)
    const [playbackQueue, setPlaybackQueue] = useState<number[]>([])
    const [activeQueuePosition, setActiveQueuePosition] = useState<number | null>(null)
    const [shuffleEnabled, setShuffleEnabled] = useState(false)
    const [repeatMode, setRepeatMode] = useState<PublicMixRepeatMode>('off')
    const [tidalPlaybackQuality, setTidalPlaybackQualityState] = useState<TidalPlaybackQuality>(() => readTidalPlaybackQuality())
    const [playbackProvider, setPlaybackProvider] = useState<'tidal' | 'youtube'>('tidal')
    const [audioElement, setAudioElement] = useState<HTMLAudioElement | null>(() => getTidalAudioElement())
    const [playbackSnapshot, setPlaybackSnapshot] = useState(() => getTidalCurrentSnapshot())
    const [youtubeSnapshot, setYoutubeSnapshot] = useState(() => getYouTubeCurrentSnapshot())
    const [publicSession, setPublicSession] = useState<PublicCurationPlaybackSession | null>(null)
    const [pendingDeviceAuthorization, setPendingDeviceAuthorization] = useState<PendingTidalDeviceAuthorization | null>(() =>
        slug ? readStoredPendingDeviceAuthorization(slug) : null,
    )
    const [playbackStatus, setPlaybackStatus] = useState('TIDAL 재생 인증이 필요합니다')
    const [playbackError, setPlaybackError] = useState<string | null>(null)
    const completingDeviceAuthorizationRef = useRef(false)
    const playbackQueueRef = useRef<number[]>([])
    const activeQueuePositionRef = useRef<number | null>(null)
    const repeatModeRef = useRef<PublicMixRepeatMode>('off')
    const playTrackAtQueuePositionRef = useRef<(position: number) => Promise<void>>(async () => undefined)
    const analyser = useTidalAudioAnalyser(audioElement, playbackProvider === 'tidal' && playbackSnapshot.state === 'PLAYING')
    const youtubePlayerHostCallbackRef = useCallback((element: HTMLDivElement | null) => {
        setYouTubePlayerHost(element)
    }, [])
    const playerSnapshot = playbackProvider === 'youtube'
        ? youtubeSnapshotToTidalLikeSnapshot(youtubeSnapshot)
        : playbackSnapshot
    const playerIsPlaying = playbackProvider === 'youtube'
        ? youtubeSnapshot.state === 'PLAYING' || youtubeSnapshot.state === 'BUFFERING'
        : playbackSnapshot.state === 'PLAYING'

    const setTidalPlaybackQuality = useCallback((quality: TidalPlaybackQuality) => {
        writeTidalPlaybackQuality(quality)
        setTidalPlaybackQualityState(quality)
    }, [])

    useEffect(() => {
        const controller = new AbortController()
        setIsLoading(true)
        setError(null)

        fetchPublicCurationShare(slug, controller.signal)
            .then((nextPayload) => {
                setPayload(nextPayload)
            })
            .catch((nextError: unknown) => {
                if (controller.signal.aborted) {
                    return
                }
                if (nextError instanceof ApiError && nextError.status === 404) {
                    setError('공개된 플레이리스트를 찾을 수 없습니다.')
                    return
                }
                setError(nextError instanceof Error ? nextError.message : '공유 믹스를 불러오지 못했습니다.')
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setIsLoading(false)
                }
            })

        return () => controller.abort()
    }, [slug])

    useEffect(() => {
        setPendingDeviceAuthorization(slug ? readStoredPendingDeviceAuthorization(slug) : null)
    }, [slug])

    useEffect(() => {
        const id = window.setInterval(() => {
            setAudioElement(getTidalAudioElement())
            setPlaybackSnapshot(getTidalCurrentSnapshot())
            setYoutubeSnapshot(getYouTubeCurrentSnapshot())
        }, 250)
        return () => window.clearInterval(id)
    }, [])

    useEffect(
        () => () => {
            void youtubeStop().catch(() => undefined)
        },
        [],
    )

    useEffect(() => {
        const storedSession = readStoredPublicSession(slug)
        if (!slug || !storedSession) {
            setPublicSession(null)
            setIsCheckingSession(false)
            setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
            return
        }

        const controller = new AbortController()
        setIsCheckingSession(true)
        setPlaybackError(null)
        setPlaybackStatus('TIDAL 세션 확인 중')

        fetchPublicCurationPlaybackSession(slug, storedSession.session_id, controller.signal)
            .then((response) => {
                if (controller.signal.aborted) {
                    return
                }
                if (response.status === 'ready' && response.session) {
                    setPublicSession(response.session)
                    setPlaybackStatus(
                        playbackReadyFromCallback
                            ? 'TIDAL 세션 준비됨. 재생 버튼을 눌러 시작하세요.'
                            : 'TIDAL 세션 준비됨',
                    )
                    return
                }
                setPublicSession(null)
                setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
                if (typeof window !== 'undefined') {
                    window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.session.${slug}`)
                }
            })
            .catch((requestError: unknown) => {
                if (controller.signal.aborted) {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'TIDAL 재생 세션을 확인하지 못했습니다.'
                setPublicSession(null)
                setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
                setPlaybackError(message)
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setIsCheckingSession(false)
                }
            })

        return () => controller.abort()
    }, [playbackReadyFromCallback, slug])

    const playlist = payload?.playlist ?? null
    const activeTrackIndex = activeQueuePosition === null
        ? null
        : playbackQueue[activeQueuePosition] ?? null
    const totalDuration = formatTotalDuration(playlist?.duration_ms)
    const displayTitle = playlist ? shareDisplayTitle(playlist.title) : ''
    const displaySubtitle = playlist ? shareDisplaySubtitle(playlist.subtitle) : ''
    const displayDescription = playlist ? shareDisplayDescription(playlist.description) : ''
    const posterPalette = useMemo(
        () => playlistPosterPalette(`${playlist?.slug ?? slug}-${playlist?.title ?? ''}-${playlist?.cover_style ?? ''}`),
        [playlist?.cover_style, playlist?.slug, playlist?.title, slug],
    )
    const posterTrackImages = useMemo(
        () =>
            playlist?.tracks
                .map((track) => track.image_url)
                .filter((imageUrl): imageUrl is string => Boolean(imageUrl))
                .slice(0, 4) ?? [],
        [playlist?.tracks],
    )

    useEffect(() => {
        const nextQueue = createPlaybackQueue(playlist?.tracks.length ?? 0)
        playbackQueueRef.current = nextQueue
        activeQueuePositionRef.current = null
        setPlaybackQueue(nextQueue)
        setActiveQueuePosition(null)
    }, [playlist?.playlist_id, playlist?.tracks.length])

    useEffect(() => {
        playbackQueueRef.current = playbackQueue
    }, [playbackQueue])

    useEffect(() => {
        activeQueuePositionRef.current = activeQueuePosition
    }, [activeQueuePosition])

    useEffect(() => {
        repeatModeRef.current = repeatMode
    }, [repeatMode])

    const preloadTrackAtQueuePosition = useCallback(
        (position: number | null) => {
            if (!slug || !publicSession || position === null) {
                return
            }
            const trackIndex = playbackQueue[position]
            const track = trackIndex === undefined ? null : playlist?.tracks[trackIndex]
            if (!track) {
                return
            }
            preloadPublicCurationTidalAnalysis({
                slug,
                publicSessionId: publicSession.session_id,
                publicTrackId: track.track_id,
            }, tidalPlaybackQuality)
        },
        [playbackQueue, playlist?.tracks, publicSession, slug, tidalPlaybackQuality],
    )

    useEffect(() => {
        if (!publicSession || playbackQueue.length === 0) {
            return
        }
        const currentPosition = activeQueuePosition ?? 0
        preloadTrackAtQueuePosition(currentPosition)
        preloadTrackAtQueuePosition(resolveNextQueuePosition(currentPosition, playbackQueue.length, repeatMode))
    }, [
        activeQueuePosition,
        playbackQueue.length,
        preloadTrackAtQueuePosition,
        publicSession,
        repeatMode,
    ])

    const recordPublicPlaybackEvent = useCallback(
        (track: PublicCurationShareTrack, eventType: string, positionMs: number | null = null) => {
            if (!slug) {
                return
            }

            void recordPublicCurationPlaybackEvent(slug, {
                public_session_id: publicSession?.session_id ?? null,
                track_id: track.track_id,
                event_type: eventType,
                position_ms: positionMs,
                duration_ms: track.duration_ms,
                occurred_at: new Date().toISOString(),
            }).catch(() => undefined)
        },
        [publicSession?.session_id, slug],
    )

    const startYouTubeFallbackForTrack = useCallback(
        async (
            track: PublicCurationShareTrack,
            position: number,
            playlistTracks: PublicCurationShareTrack[],
            reason: unknown,
        ) => {
            const fallbackReason = playbackErrorMessage(reason, 'TIDAL stream playback failed.')
            setPlaybackProvider('youtube')
            setPlaybackStatus(`TIDAL 재생 실패로 YouTube 재생으로 전환합니다. (${fallbackReason})`)
            setPlaybackError(null)
            void tidalPause().catch(() => undefined)

            const playableItem = await resolveYouTubePlayableItem(
                publicCurationPlaybackUserId(slug),
                toPublicCurationPlaybackMediaItem(track),
            )
            const youtubeVideoId = resolveYouTubeVideoId(playableItem)
            if (!youtubeVideoId) {
                throw new Error(`YouTube fallback target is missing for "${track.title}".`)
            }

            await playYouTubeVideo(youtubeVideoId, {
                onStateChange: (state, snapshot) => {
                    setYoutubeSnapshot(snapshot)
                    if (state === 'PLAYING') {
                        setPlaybackStatus(`YouTube로 재생 중: ${track.title}`)
                    }
                    if (state === 'BUFFERING') {
                        setPlaybackStatus(`YouTube 버퍼링 중: ${track.title}`)
                    }
                    if (state === 'NOT_PLAYING') {
                        setPlaybackStatus(`일시정지: ${track.title}`)
                    }
                },
                onEnded: () => {
                    recordPublicPlaybackEvent(track, 'play_completed', track.duration_ms)
                    const currentQueuePosition = activeQueuePositionRef.current ?? position
                    const nextQueuePosition = resolveNextQueuePosition(
                        currentQueuePosition,
                        playbackQueueRef.current.length,
                        repeatModeRef.current,
                    )
                    if (nextQueuePosition !== null) {
                        const nextTrackIndex = playbackQueueRef.current[nextQueuePosition]
                        const nextTrack = nextTrackIndex === undefined ? null : playlistTracks[nextTrackIndex]
                        if (nextTrack) {
                            setPlaybackStatus(`다음 곡 준비 중: ${nextTrack.title}`)
                        }
                        void playTrackAtQueuePositionRef.current(nextQueuePosition)
                        return
                    }
                    setPlayingTrackId(null)
                    setPlaybackStatus('전체 재생이 끝났습니다.')
                },
                onError: (message) => {
                    setPlaybackError(`YouTube fallback failed: ${message}`)
                },
            })
            setYoutubeSnapshot(getYouTubeCurrentSnapshot())
            setPlaybackStatus(`YouTube로 재생 중: ${track.title}`)
            setPlaybackError(null)
            recordPublicPlaybackEvent(track, 'play_started', 0)
        },
        [recordPublicPlaybackEvent, slug],
    )

    const completePendingDeviceAuthorization = useCallback(
        async (pending = pendingDeviceAuthorization) => {
            if (!slug || !pending || completingDeviceAuthorizationRef.current) {
                return
            }
            if (new Date(pending.expires_at).getTime() <= Date.now()) {
                setPendingDeviceAuthorization(null)
                setPlaybackStatus('TIDAL 인증 코드가 만료되었습니다. 다시 시작하세요.')
                setPlaybackError('TIDAL 인증 시간이 만료되었습니다.')
                if (typeof window !== 'undefined') {
                    window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.device.${slug}`)
                }
                return
            }

            completingDeviceAuthorizationRef.current = true
            setIsCompletingDeviceAuthorization(true)
            setPlaybackError(null)
            setPlaybackStatus('TIDAL 인증 완료 여부 확인 중')

            try {
                const response = await completePublicCurationTidalDeviceAuthorization(slug, {
                    state: pending.state,
                    device_code: pending.device_code,
                })
                if (response.status === 'authorization_completed' && response.session) {
                    setPublicSession(response.session)
                    setPendingDeviceAuthorization(null)
                    setPlaybackStatus('TIDAL 세션 준비됨. 재생 버튼을 눌러 시작하세요.')
                    if (typeof window !== 'undefined') {
                        window.sessionStorage.setItem(
                            `${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.session.${slug}`,
                            JSON.stringify({ session: response.session }),
                        )
                        window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.device.${slug}`)
                    }
                    return
                }

                setPlaybackStatus('아직 TIDAL 인증이 완료되지 않았습니다. 새 탭에서 인증을 마친 뒤 이 탭으로 돌아오세요.')
            } catch (requestError: unknown) {
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'TIDAL 인증 완료 여부를 확인하지 못했습니다.'
                setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
                setPlaybackError(message)
            } finally {
                completingDeviceAuthorizationRef.current = false
                setIsCompletingDeviceAuthorization(false)
            }
        },
        [pendingDeviceAuthorization, slug],
    )

    useEffect(() => {
        if (!pendingDeviceAuthorization || publicSession) {
            return undefined
        }

        const handleReturnToTab = () => {
            if (document.visibilityState === 'visible') {
                void completePendingDeviceAuthorization(pendingDeviceAuthorization)
            }
        }

        window.addEventListener('focus', handleReturnToTab)
        document.addEventListener('visibilitychange', handleReturnToTab)

        return () => {
            window.removeEventListener('focus', handleReturnToTab)
            document.removeEventListener('visibilitychange', handleReturnToTab)
        }
    }, [completePendingDeviceAuthorization, pendingDeviceAuthorization, publicSession])

    const handleStartTidalOAuth = useCallback(async () => {
        if (!slug) {
            setPlaybackError('공유 플레이리스트 주소가 올바르지 않습니다.')
            return
        }
        setIsStartingOAuth(true)
        setPlaybackError(null)
        setPlaybackStatus('TIDAL 인증 새 탭을 여는 중')

        const tidalTab = typeof window !== 'undefined' ? window.open('about:blank', '_blank') : null

        try {
            const response = await startPublicCurationTidalDeviceAuthorization(slug)
            const pending = toPendingDeviceAuthorization(response)
            if (typeof window !== 'undefined') {
                window.sessionStorage.setItem(
                    `${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.device.${slug}`,
                    JSON.stringify({
                        authorization: pending,
                    }),
                )
            }
            setPendingDeviceAuthorization(pending)
            setPlaybackStatus(`TIDAL 새 탭에서 ${pending.user_code} 코드를 확인한 뒤 이 탭으로 돌아오세요.`)
            const verificationUrl = pending.verification_uri_complete ?? response.authorization.verification_uri
            if (tidalTab) {
                tidalTab.opener = null
                tidalTab.location.href = normalizeExternalTidalUrl(verificationUrl)
                tidalTab.focus()
            } else if (typeof window !== 'undefined') {
                window.open(normalizeExternalTidalUrl(verificationUrl), '_blank', 'noopener,noreferrer')
            }
            setIsStartingOAuth(false)
        } catch (requestError: unknown) {
            tidalTab?.close()
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'TIDAL 재생 인증을 시작하지 못했습니다.'
            setPlaybackError(message)
            setIsStartingOAuth(false)
            setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
        }
    }, [slug])

    const playTrackAtQueuePosition = useCallback(
        async (position: number) => {
            const trackIndex = playbackQueueRef.current[position]
            const playlistTracks = playlist?.tracks
            const track = trackIndex === undefined ? null : playlistTracks?.[trackIndex]
            if (!playlistTracks || !track) {
                setPlaybackError('재생할 트랙을 찾을 수 없습니다.')
                return
            }
            if (!slug) {
                setPlaybackError('공유 플레이리스트 주소가 올바르지 않습니다.')
                return
            }
            if (!publicSession) {
                if (pendingDeviceAuthorization) {
                    await completePendingDeviceAuthorization(pendingDeviceAuthorization)
                    return
                }
                await handleStartTidalOAuth()
                return
            }

            setIsStartingPlayback(true)
            setPlayingTrackId(track.track_id)
            activeQueuePositionRef.current = position
            setActiveQueuePosition(position)
            setPlaybackError(null)
            setPlaybackStatus(`지금 재생 준비 중: ${track.title}`)
            setPlaybackProvider('tidal')
            void youtubeStop().catch(() => undefined)
            preloadTrackAtQueuePosition(position)

            try {
                await playPublicCurationTidalTrack(slug, publicSession.session_id, track, {
                    onTransition: (_productId, snapshot) => {
                        setPlaybackSnapshot(snapshot)
                        setPlayingTrackId(track.track_id)
                        setPlaybackStatus(`지금 재생 중: ${track.title}`)
                    },
                    onStateChange: (state, snapshot) => {
                        setPlaybackSnapshot(snapshot)
                        if (state === 'PLAYING') {
                            setPlaybackStatus(`지금 재생 중: ${track.title}`)
                        }
                        if (state === 'STALLED') {
                            setPlaybackStatus(`스트림 버퍼링 중: ${track.title}`)
                        }
                        if (state === 'NOT_PLAYING') {
                            setPlaybackStatus(`일시정지: ${track.title}`)
                        }
                    },
                    onEnded: () => {
                        recordPublicPlaybackEvent(track, 'play_completed', track.duration_ms)
                        const currentQueuePosition = activeQueuePositionRef.current ?? position
                        const nextQueuePosition = resolveNextQueuePosition(
                            currentQueuePosition,
                            playbackQueueRef.current.length,
                            repeatModeRef.current,
                        )
                        if (nextQueuePosition !== null) {
                            const nextTrackIndex = playbackQueueRef.current[nextQueuePosition]
                            const nextTrack = nextTrackIndex === undefined ? null : playlistTracks[nextTrackIndex]
                            if (nextTrack) {
                                setPlaybackStatus(`다음 곡 준비 중: ${nextTrack.title}`)
                            }
                            void playTrackAtQueuePositionRef.current(nextQueuePosition)
                            return
                        }
                        setPlayingTrackId(null)
                        setPlaybackStatus('전체 재생이 끝났습니다.')
                    },
                    onError: (message) => {
                        setPlaybackError(message)
                        if (isRecoverablePublicCurationTidalPlaybackError(new Error(message))) {
                            recordPublicPlaybackEvent(track, 'play_failed', 0)
                            void startYouTubeFallbackForTrack(track, position, playlistTracks, message).catch((fallbackError: unknown) => {
                                setPlayingTrackId(null)
                                setPlaybackStatus('재생할 대체 영상을 찾지 못했습니다.')
                                setPlaybackError(playbackErrorMessage(fallbackError, 'YouTube fallback failed.'))
                            })
                        }
                    },
                }, tidalPlaybackQuality)
                setPlaybackStatus(`지금 재생 중: ${track.title}`)
                recordPublicPlaybackEvent(track, 'play_started', 0)
            } catch (requestError: unknown) {
                if (isRecoverablePublicCurationTidalPlaybackError(requestError)) {
                    recordPublicPlaybackEvent(track, 'play_failed', 0)
                    try {
                        await startYouTubeFallbackForTrack(track, position, playlistTracks, requestError)
                        return
                    } catch (fallbackError: unknown) {
                        const fallbackMessage = playbackErrorMessage(fallbackError, 'YouTube fallback failed.')
                        setPlayingTrackId(null)
                        setPlaybackStatus('재생할 대체 영상을 찾지 못했습니다.')
                        setPlaybackError(fallbackMessage)
                        return
                    }
                }
                const message =
                    requestError instanceof Error
                        ? requestError.message
                        : 'TIDAL 스트림 재생을 시작하지 못했습니다.'
                setPlayingTrackId(null)
                setPlaybackStatus('TIDAL 재생 인증이 필요합니다')
                setPlaybackError(message)
                recordPublicPlaybackEvent(track, 'play_failed', 0)
            } finally {
                setIsStartingPlayback(false)
            }
        },
        [
            completePendingDeviceAuthorization,
            handleStartTidalOAuth,
            pendingDeviceAuthorization,
            playlist?.tracks,
            preloadTrackAtQueuePosition,
            publicSession,
            recordPublicPlaybackEvent,
            slug,
            startYouTubeFallbackForTrack,
            tidalPlaybackQuality,
        ],
    )

    useEffect(() => {
        playTrackAtQueuePositionRef.current = playTrackAtQueuePosition
    }, [playTrackAtQueuePosition])

    const handlePlayTrack = useCallback(
        async (track: PublicCurationShareTrack) => {
            const trackIndex = playlist?.tracks.findIndex((item) => item.track_id === track.track_id) ?? -1
            const queuePosition = playbackQueueRef.current.indexOf(trackIndex)
            if (trackIndex < 0 || queuePosition < 0) {
                setPlaybackError('재생할 트랙을 찾을 수 없습니다.')
                return
            }
            await playTrackAtQueuePosition(queuePosition)
        },
        [playTrackAtQueuePosition, playlist?.tracks],
    )

    const handleStartTidalPlayback = useCallback(async () => {
        const firstTrack = playlist?.tracks[0]
        if (publicSession && firstTrack) {
            await playTrackAtQueuePosition(0)
            return
        }
        if (publicSession && !firstTrack) {
            setPlaybackError('재생할 트랙이 없습니다.')
            return
        }
        if (pendingDeviceAuthorization) {
            await completePendingDeviceAuthorization(pendingDeviceAuthorization)
            return
        }
        await handleStartTidalOAuth()
    }, [
        completePendingDeviceAuthorization,
        handleStartTidalOAuth,
        pendingDeviceAuthorization,
        playTrackAtQueuePosition,
        playlist?.tracks,
        publicSession,
    ])

    const handlePreviousTrack = useCallback(() => {
        if (activeQueuePosition === null || activeQueuePosition <= 0) {
            return
        }
        void playTrackAtQueuePosition(activeQueuePosition - 1)
    }, [activeQueuePosition, playTrackAtQueuePosition])

    const handleNextTrack = useCallback(() => {
        if (activeQueuePosition === null) {
            return
        }
        const nextQueuePosition = activeQueuePosition + 1 < playbackQueue.length
            ? activeQueuePosition + 1
            : repeatMode === 'all' && playbackQueue.length > 0
              ? 0
              : null
        if (nextQueuePosition !== null) {
            void playTrackAtQueuePosition(nextQueuePosition)
        }
    }, [activeQueuePosition, playbackQueue.length, playTrackAtQueuePosition, repeatMode])

    const handleToggleShuffle = useCallback(() => {
        setShuffleEnabled((enabled) => {
            if (enabled) {
                return false
            }
            setPlaybackQueue((currentQueue) => {
                const shuffled = shuffleQueueAfterPosition(currentQueue, activeQueuePositionRef.current)
                playbackQueueRef.current = shuffled
                return shuffled
            })
            return true
        })
    }, [])

    const handleCycleRepeat = useCallback(() => {
        setRepeatMode((currentMode) => {
            const nextMode = nextRepeatMode(currentMode)
            repeatModeRef.current = nextMode
            return nextMode
        })
    }, [])

    const handleTogglePlayback = useCallback(() => {
        if (playbackProvider === 'youtube') {
            if (youtubeSnapshot.state === 'PLAYING' || youtubeSnapshot.state === 'BUFFERING') {
                void youtubePause()
                return
            }
            if (youtubeSnapshot.videoId) {
                void youtubeResume()
                return
            }
            void handleStartTidalPlayback()
            return
        }
        if (playbackSnapshot.state === 'PLAYING') {
            void tidalPause()
            return
        }
        if (playbackSnapshot.productId) {
            void tidalResume()
            return
        }
        void handleStartTidalPlayback()
    }, [handleStartTidalPlayback, playbackProvider, playbackSnapshot.productId, playbackSnapshot.state, youtubeSnapshot.state, youtubeSnapshot.videoId])

    if (isLoading) {
        return (
            <main className="min-h-screen bg-[#090b12] text-white">
                <div className="flex min-h-screen items-center justify-center px-6">
                    <div className="flex items-center gap-3 text-sm text-white/70">
                        <Loader2 className="h-5 w-5 animate-spin text-cyan-300" />
                        공유 믹스를 여는 중
                    </div>
                </div>
            </main>
        )
    }

    if (error || !playlist) {
        return (
            <main className="min-h-screen bg-[#090b12] text-white">
                <section className="mx-auto flex min-h-screen max-w-3xl flex-col items-center justify-center px-6 text-center">
                    <AlertCircle className="mb-5 h-10 w-10 text-rose-300" />
                    <p className="mb-3 text-xs font-semibold uppercase text-cyan-200">오늘의 공유 믹스</p>
                    <h1 className="mb-4 text-3xl font-bold">공유 믹스를 열 수 없습니다</h1>
                    <p className="mb-8 text-sm leading-7 text-white/70">{error}</p>
                    <Link
                        to="/"
                        className="inline-flex items-center justify-center rounded-lg border border-white/20 px-5 py-3 text-sm font-semibold text-white transition hover:border-cyan-300 hover:text-cyan-200"
                    >
                        My Forever Music으로 이동
                    </Link>
                </section>
            </main>
        )
    }

    return (
        <main className="min-h-screen bg-[#090b12] text-white">
            <section className="relative min-h-[92vh] overflow-hidden border-b border-white/10">
                <div className="absolute inset-0 bg-[radial-gradient(circle_at_16%_18%,rgba(45,212,191,0.28),transparent_28%),radial-gradient(circle_at_80%_12%,rgba(244,114,182,0.24),transparent_28%),linear-gradient(135deg,#111827_0%,#090b12_48%,#161027_100%)]" />
                <div className="absolute inset-x-0 bottom-0 h-56 bg-gradient-to-t from-[#090b12] to-transparent" />
                <div className="relative mx-auto grid min-h-[92vh] max-w-7xl grid-cols-1 gap-10 px-6 py-8 lg:grid-cols-[1.1fr_0.9fr] lg:px-10">
                    <div className="flex flex-col justify-between">
                        <header className="flex items-center justify-between gap-4">
                            <Link to="/" className="flex items-center gap-3">
                                <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-cyan-300 text-sm font-black text-slate-950">
                                    FM
                                </span>
                                <span>
                                    <span className="block text-xs font-bold uppercase text-cyan-200">My Forever Music</span>
                                    <span className="block text-sm font-semibold text-white/80">공유 믹스</span>
                                </span>
                            </Link>
                            <span className="rounded-lg border border-white/15 px-3 py-2 text-xs font-semibold text-white/70">
                                TIDAL 재생
                            </span>
                        </header>

                        <div className="max-w-3xl py-16 lg:py-20">
                            <p className="mb-5 inline-flex items-center gap-2 rounded-lg border border-cyan-300/30 bg-cyan-300/10 px-3 py-2 text-xs font-semibold uppercase text-cyan-100">
                                <Sparkles className="h-4 w-4" />
                                오늘의 공유 믹스
                            </p>
                            <h1 className="text-4xl font-bold leading-snug text-white md:text-6xl md:leading-snug">
                                {displayTitle}
                            </h1>
                            {displaySubtitle && (
                                <p className="mt-6 max-w-2xl text-xl font-semibold leading-8 text-white/82">
                                    {displaySubtitle}
                                </p>
                            )}
                            {displayDescription && (
                                <p className="mt-5 max-w-2xl text-base leading-8 text-white/68">
                                    {displayDescription}
                                </p>
                            )}

                            <div className="mt-8 flex flex-wrap gap-3">
                                <button
                                    type="button"
                                    disabled={isStartingOAuth || isStartingPlayback || isCheckingSession || isCompletingDeviceAuthorization}
                                    onClick={handleStartTidalPlayback}
                                    className="inline-flex items-center justify-center gap-2 rounded-lg bg-cyan-300 px-5 py-3 text-sm font-black text-slate-950 transition hover:bg-cyan-200 disabled:opacity-70"
                                    title="TIDAL 로그인 후 이 공개 플레이리스트를 여기서 재생합니다."
                                >
                                    {isStartingOAuth || isStartingPlayback || isCheckingSession || isCompletingDeviceAuthorization ? (
                                        <Loader2 className="h-4 w-4 animate-spin" />
                                    ) : (
                                        <Play className="h-4 w-4 fill-current" />
                                    )}
                                    {isStartingOAuth
                                        ? 'TIDAL로 이동 중'
                                        : isCheckingSession
                                          ? 'TIDAL 세션 확인 중'
                                          : isCompletingDeviceAuthorization
                                            ? '인증 확인 중'
                                          : isStartingPlayback
                                            ? '재생 준비 중'
                                            : publicSession
                                              ? 'Play All'
                                              : pendingDeviceAuthorization
                                                ? 'TIDAL 인증 완료 확인'
                                              : 'TIDAL로 여기서 듣기'}
                                </button>
                                <a
                                    href="#public-track-list"
                                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-white/18 px-5 py-3 text-sm font-semibold text-white transition hover:border-cyan-300 hover:text-cyan-200"
                                >
                                    <Music2 className="h-4 w-4" />
                                    전체 곡 보기
                                </a>
                            </div>
                            <div className="mt-4 inline-flex max-w-2xl items-center gap-2 rounded-lg border border-white/12 bg-black/24 px-4 py-3 text-sm font-semibold text-white/74">
                                <Headphones className="h-4 w-4 text-cyan-200" />
                                {playbackStatus}
                            </div>
                            {playbackError && (
                                <p className="mt-4 max-w-2xl rounded-lg border border-rose-300/25 bg-rose-400/10 px-4 py-3 text-sm leading-6 text-rose-100">
                                    {playbackError}
                                </p>
                            )}
                            <p className="mt-4 max-w-2xl text-sm leading-7 text-white/58">
                                TIDAL 인증은 새 탭에서 진행됩니다. 인증을 마치고 이 탭으로 돌아오면 완료 여부를 한 번만 확인합니다.
                            </p>
                        </div>

                        <dl className="grid max-w-2xl grid-cols-3 gap-3 pb-8">
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Disc3 className="h-4 w-4" />
                                    트랙
                                </dt>
                                <dd className="text-2xl font-black">{playlist.track_count}</dd>
                            </div>
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Clock3 className="h-4 w-4" />
                                    길이
                                </dt>
                                <dd className="text-2xl font-black">{totalDuration}</dd>
                            </div>
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Headphones className="h-4 w-4" />
                                    재생
                                </dt>
                                <dd className="text-2xl font-black">TIDAL</dd>
                            </div>
                        </dl>
                    </div>

                    <aside className="flex items-center justify-center py-10 lg:py-0">
                        <div className="relative aspect-[4/5] w-full max-w-md overflow-hidden rounded-lg border border-white/15 bg-black shadow-2xl shadow-black/50">
                            <img
                                src={publicCurationCoverUrl}
                                alt=""
                                className="absolute inset-0 h-full w-full object-cover opacity-[0.86]"
                            />
                            <div
                                className="absolute inset-0 mix-blend-screen"
                                style={{
                                    background:
                                        `radial-gradient(circle at 18% 22%, ${posterPalette[0]}, transparent 34%), ` +
                                        `radial-gradient(circle at 82% 32%, ${posterPalette[1]}, transparent 36%), ` +
                                        `linear-gradient(135deg, transparent 18%, ${posterPalette[2]} 100%)`,
                                }}
                            />
                            <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(3,7,18,0.08)_0%,rgba(3,7,18,0.18)_42%,rgba(3,7,18,0.82)_100%)]" />
                            {posterTrackImages.length > 0 && (
                                <div className="absolute right-5 top-5 grid grid-cols-2 gap-2">
                                    {posterTrackImages.map((imageUrl, index) => (
                                        <img
                                            key={`${imageUrl}-${index}`}
                                            src={imageUrl}
                                            alt=""
                                            className="h-16 w-16 rounded-lg border border-white/20 object-cover shadow-lg shadow-black/35"
                                        />
                                    ))}
                                </div>
                            )}
                            <div className="absolute inset-x-0 bottom-0 p-6">
                                <p className="mb-3 inline-flex rounded-lg border border-white/18 bg-white/10 px-3 py-1.5 text-xs font-black uppercase tracking-[0.22em] text-cyan-100">
                                    Public Mix
                                </p>
                                <h2 className="max-w-sm text-2xl font-bold leading-snug text-white">
                                    {displayTitle}
                                </h2>
                                <div className="mt-5 flex flex-wrap gap-2 text-xs font-semibold text-white/72">
                                    <span className="rounded-lg border border-white/14 bg-black/24 px-3 py-2">
                                        {playlist.track_count} tracks
                                    </span>
                                    <span className="rounded-lg border border-white/14 bg-black/24 px-3 py-2">
                                        {totalDuration}
                                    </span>
                                </div>
                            </div>
                        </div>
                    </aside>
                </div>
            </section>

            <PublicMixSpectrumPlayer
                track={activeTrackIndex === null ? null : playlist.tracks[activeTrackIndex] ?? null}
                trackIndex={activeQueuePosition}
                trackCount={playlist.tracks.length}
                snapshot={playerSnapshot}
                analyser={analyser}
                isPlaying={playerIsPlaying}
                canPrevious={activeQueuePosition !== null && activeQueuePosition > 0}
                canNext={
                    activeQueuePosition !== null
                    && (activeQueuePosition < playbackQueue.length - 1 || repeatMode === 'all')
                }
                shuffleEnabled={shuffleEnabled}
                repeatMode={repeatMode}
                tidalPlaybackQuality={tidalPlaybackQuality}
                playbackProvider={playbackProvider}
                youtubePlayerHostRef={youtubePlayerHostCallbackRef}
                onPrevious={handlePreviousTrack}
                onTogglePlayback={handleTogglePlayback}
                onNext={handleNextTrack}
                onToggleShuffle={handleToggleShuffle}
                onCycleRepeat={handleCycleRepeat}
                onQualityChange={setTidalPlaybackQuality}
            />

            <section id="public-track-list" className="mx-auto max-w-7xl px-6 py-14 lg:px-10">
                <div className="mb-8 flex flex-col justify-between gap-4 md:flex-row md:items-end">
                    <div>
                        <p className="mb-2 text-xs font-bold uppercase text-cyan-200">Track List</p>
                        <h2 className="text-3xl font-black">이 믹스의 흐름</h2>
                    </div>
                    <p className="max-w-xl text-sm leading-7 text-white/58">
                        처음부터 끝까지 자연스럽게 이어지도록 담은 곡들입니다.
                    </p>
                </div>

                <div className="grid gap-3">
                    {playlist.tracks.map((track, trackIndex) => {
                        const isActiveTrack = activeTrackIndex === trackIndex

                        return (
                        <article
                            key={track.track_id}
                            data-playing-track={isActiveTrack ? 'true' : undefined}
                            className={`grid gap-4 rounded-lg border p-4 transition md:grid-cols-[64px_1fr_auto] md:items-center ${
                                isActiveTrack
                                    ? 'border-cyan-300/65 bg-cyan-300/[0.12] shadow-[inset_4px_0_0_rgba(103,232,249,0.92)]'
                                    : 'border-white/10 bg-white/[0.04]'
                            }`}
                        >
                            <PublicTrackThumbnail
                                imageUrl={track.image_url}
                                title={track.title}
                                className="h-16 w-16 border-white/10 bg-cyan-300/12 text-cyan-100"
                            />
                            <div>
                                <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-white/48">
                                    <span>#{track.track_order}</span>
                                    <span>{formatDuration(track.duration_ms) ?? '--:--'}</span>
                                </div>
                                <h3 className="text-lg font-black text-white">{track.title}</h3>
                                <p className="mt-1 text-sm text-white/62">
                                    {track.artist_name}
                                    {track.album_title ? ` · ${track.album_title}` : ''}
                                </p>
                            </div>
                            <div className="flex flex-wrap items-center gap-2 md:justify-end">
                                <span className="rounded-lg border border-white/12 px-3 py-2 text-xs font-semibold text-white/60">
                                    TIDAL {track.tidal_track_id}
                                </span>
                                <button
                                    type="button"
                                    disabled={isStartingOAuth || isCheckingSession || isStartingPlayback || isCompletingDeviceAuthorization}
                                    onClick={() => {
                                        void handlePlayTrack(track)
                                    }}
                                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-cyan-300/55 px-3 py-2 text-xs font-black text-cyan-100 transition hover:border-cyan-200 hover:bg-cyan-300/10 disabled:opacity-60"
                                    title={publicSession ? '이 곡을 TIDAL 스트림으로 재생합니다.' : 'TIDAL 재생 인증 후 이 곡을 재생합니다.'}
                                >
                                    {isStartingPlayback && playingTrackId === track.track_id ? (
                                        <Loader2 className="h-3.5 w-3.5 animate-spin" />
                                    ) : (
                                        <Play className="h-3.5 w-3.5 fill-current" />
                                    )}
                                    {playingTrackId === track.track_id
                                        ? '지금 재생 중'
                                        : publicSession
                                          ? '재생'
                                          : pendingDeviceAuthorization
                                            ? '인증 확인'
                                          : 'TIDAL 인증'}
                                </button>
                            </div>
                        </article>
                        )
                    })}
                </div>
            </section>
        </main>
    )
}

export default PublicCurationSharePage
