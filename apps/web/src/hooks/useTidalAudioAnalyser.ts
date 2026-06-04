import { useEffect, useMemo, useRef, useState } from 'react'
import { AudioRingBuffer } from '@/lib/audioRingBuffer'
import {
    decodeCompleteAudioData,
    decodeFragmentedMp4Segment,
    type DecodedPcmSegment,
} from '@/lib/segmentDecoder'
import { pcmToByteFrequencyData } from '@/lib/simpleFft'
import {
    subscribeTidalAudioCapture,
    type CapturedTidalAudioSegment,
    type PublicCurationAnalysisSource,
} from '@/lib/tidalAudioCapture'
import {
    fetchPublicCurationTidalPlaybackAnalysisAudio,
    fetchTidalPlaybackAnalysisAudio,
} from '@/services/api'

export type AnalyserMode = 'pcm' | 'waiting' | 'idle' | 'error' | 'unsupported'

export interface TidalAudioAnalyserHandle {
    mode: AnalyserMode
    reason: string | null
    binCount: number
    read: (target: Uint8Array) => void
}

const FFT_SIZE = 256
const BIN_COUNT = FFT_SIZE / 2
const PUBLIC_ANALYSIS_CACHE_LIMIT = 3
const publicAnalysisCache = new Map<string, Promise<DecodedPcmSegment>>()

const publicAnalysisCacheKey = (
    source: PublicCurationAnalysisSource,
    quality: string,
) => [
    source.slug,
    source.publicSessionId,
    source.publicTrackId,
    quality,
].join(':')

const trimPublicAnalysisCache = () => {
    while (publicAnalysisCache.size > PUBLIC_ANALYSIS_CACHE_LIMIT) {
        const oldestKey = publicAnalysisCache.keys().next().value
        if (!oldestKey) {
            return
        }
        publicAnalysisCache.delete(oldestKey)
    }
}

const loadPublicCurationTidalAnalysis = (
    source: PublicCurationAnalysisSource,
    quality = 'HIGH',
) => {
    const key = publicAnalysisCacheKey(source, quality)
    const cached = publicAnalysisCache.get(key)
    if (cached) {
        publicAnalysisCache.delete(key)
        publicAnalysisCache.set(key, cached)
        return cached
    }

    const pending: Promise<DecodedPcmSegment> = fetchPublicCurationTidalPlaybackAnalysisAudio(
        source.slug,
        source.publicSessionId,
        source.publicTrackId,
        quality,
    )
        .then(decodeCompleteAudioData)
        .catch((error: unknown) => {
            if (publicAnalysisCache.get(key) === pending) {
                publicAnalysisCache.delete(key)
            }
            throw error
        })
    publicAnalysisCache.set(key, pending)
    trimPublicAnalysisCache()
    return pending
}

export const preloadPublicCurationTidalAnalysis = (
    source: PublicCurationAnalysisSource,
    quality = 'HIGH',
) => {
    void loadPublicCurationTidalAnalysis(source, quality).catch(() => undefined)
}

// Read offset relative to `audio.currentTime`. Positive value pulls the
// visualization back in time (delays it relative to what the browser reports
// as current playback). Use to align with OS audio output buffer (Bluetooth
// headphones ~150ms, wired ~20-40ms). Tune live in devtools via
// `window.__visualizerOffsetMs = 120` etc.
let visualizerOffsetMs = 0
if (typeof window !== 'undefined') {
    Object.defineProperty(window, '__visualizerOffsetMs', {
        configurable: true,
        get: () => visualizerOffsetMs,
        set: (value: number) => {
            visualizerOffsetMs = Number.isFinite(value) ? value : 0
        },
    })
}

const supportsAudioContext = () =>
    typeof window !== 'undefined'
        && (typeof window.AudioContext !== 'undefined'
            || typeof (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext !== 'undefined')

const zero = (target: Uint8Array) => {
    target.fill(0)
}

const errorMessage = (error: unknown, fallback: string) =>
    error instanceof Error && error.message ? error.message : fallback

const resolveSegmentStart = (segment: CapturedTidalAudioSegment, audioElement: HTMLAudioElement | null) => {
    if (typeof segment.startTime === 'number' && Number.isFinite(segment.startTime)) {
        return segment.startTime
    }
    if (audioElement && Number.isFinite(audioElement.currentTime)) {
        return audioElement.currentTime
    }
    return 0
}

export function useTidalAudioAnalyser(
    audioElement: HTMLAudioElement | null,
    isPlaying: boolean,
): TidalAudioAnalyserHandle {
    const ringRef = useRef(new AudioRingBuffer())
    const decodeQueueRef = useRef<Promise<void>>(Promise.resolve())
    const analysisJobRef = useRef(0)
    const abortControllerRef = useRef<AbortController | null>(null)
    const audioElementRef = useRef(audioElement)
    const isPlayingRef = useRef(isPlaying)
    const [mode, setMode] = useState<AnalyserMode>('idle')
    const [reason, setReason] = useState<string | null>('waiting for TIDAL playback')

    useEffect(() => {
        audioElementRef.current = audioElement
    }, [audioElement])

    useEffect(() => {
        isPlayingRef.current = isPlaying
    }, [isPlaying])

    useEffect(() => {
        if (!supportsAudioContext()) {
            setMode('unsupported')
            setReason('AudioContext unavailable')
            return
        }

        let mounted = true

        const startNewAnalysisJob = () => {
            analysisJobRef.current += 1
            abortControllerRef.current?.abort()
            abortControllerRef.current = null
            return analysisJobRef.current
        }

        const isCurrentJob = (jobId: number) =>
            mounted && analysisJobRef.current === jobId

        const decodeSegment = async (segment: CapturedTidalAudioSegment, jobId: number) => {
            if (!isCurrentJob(jobId)) {
                return
            }
            if (segment.kind !== 'media') {
                return
            }
            if (!segment.initSegment) {
                if (isCurrentJob(jobId)) {
                    setMode('waiting')
                    setReason('waiting for HLS init segment')
                }
                return
            }

            try {
                const decoded = await decodeFragmentedMp4Segment(segment.initSegment, segment.payload)
                if (!isCurrentJob(jobId)) {
                    return
                }

                ringRef.current.append({
                    startTime: resolveSegmentStart(segment, audioElementRef.current),
                    sampleRate: decoded.sampleRate,
                    samples: decoded.samples,
                })
                setMode('pcm')
                setReason(`decoded ${decoded.duration.toFixed(2)}s segment`)
            } catch (error) {
                if (!isCurrentJob(jobId)) {
                    return
                }
                const message = error instanceof Error ? error.message : 'segment decode failed'
                setMode('error')
                setReason(message)
            }
        }

        const decodeDirectStream = async (
            url: string,
            startTime: number | null,
            userId: string,
            trackId: string,
            quality: string,
            publicCuration: PublicCurationAnalysisSource | null,
            jobId: number,
            signal: AbortSignal,
        ) => {
            // Guard: a public-curation mix on the generic path has no resolvable analysis
            // credential, so the cross-origin direct CDN fetch (403) and the API analysis
            // fetch (400) only produce noise. Skip visual analysis instead of erroring;
            // playback itself is unaffected.
            if (!publicCuration && userId.startsWith('public-curation:')) {
                if (isCurrentJob(jobId)) {
                    setMode('unsupported')
                    setReason('visual analysis unavailable for this TIDAL stream')
                }
                return
            }
            if (isCurrentJob(jobId)) {
                setMode('waiting')
                setReason('fetching direct TIDAL stream for analysis')
            }

            let decoded: DecodedPcmSegment
            try {
                if (publicCuration) {
                    decoded = await loadPublicCurationTidalAnalysis(publicCuration, quality)
                } else {
                    let audioData: ArrayBuffer
                    try {
                        const response = await fetch(url, { method: 'GET', mode: 'cors', signal })
                        if (!response.ok) {
                            throw new Error(`direct stream fetch failed: HTTP ${response.status}`)
                        }
                        audioData = await response.arrayBuffer()
                    } catch (directFetchError) {
                        if (!isCurrentJob(jobId)) {
                            return
                        }
                        const directMessage = errorMessage(directFetchError, 'browser direct fetch failed')
                        setReason(`browser direct fetch failed; trying API analysis fetch (${directMessage})`)

                        try {
                            audioData = await fetchTidalPlaybackAnalysisAudio(userId, trackId, quality, signal)
                        } catch (apiFetchError) {
                            if (!isCurrentJob(jobId)) {
                                return
                            }
                            throw new Error(
                                `browser direct fetch failed (${directMessage}); API analysis fetch failed (${errorMessage(apiFetchError, 'unknown error')})`,
                            )
                        }
                    }
                    decoded = await decodeCompleteAudioData(audioData)
                }
            } catch (error) {
                if (!isCurrentJob(jobId)) {
                    return
                }
                setMode('error')
                setReason(errorMessage(error, 'direct stream analysis fetch failed'))
                return
            }

            try {
                if (!isCurrentJob(jobId)) {
                    return
                }
                ringRef.current.clear()
                ringRef.current.append({
                    startTime: startTime ?? 0,
                    sampleRate: decoded.sampleRate,
                    samples: decoded.samples,
                })
                setMode('pcm')
                setReason(`decoded direct ${decoded.duration.toFixed(2)}s stream`)
            } catch (error) {
                if (!isCurrentJob(jobId)) {
                    return
                }
                setMode('error')
                setReason(errorMessage(error, 'direct stream decode failed'))
            }
        }

        const unsubscribe = subscribeTidalAudioCapture((event) => {
            if (event.type === 'reset') {
                startNewAnalysisJob()
                ringRef.current.clear()
                if (mounted) {
                    setMode('waiting')
                    setReason('waiting for HLS audio segment')
                }
                return
            }
            if (event.type === 'source') {
                if (event.source === 'native-hls') {
                    setMode('unsupported')
                    setReason('native HLS playback does not expose HLS.js segments')
                } else if (event.source === 'direct') {
                    setMode('waiting')
                    setReason('direct TIDAL stream detected')
                } else {
                    setMode('waiting')
                    setReason('waiting for HLS audio segment')
                }
                return
            }
            if (event.type === 'direct-stream') {
                const jobId = startNewAnalysisJob()
                const controller = new AbortController()
                abortControllerRef.current = controller
                decodeQueueRef.current = decodeQueueRef.current
                    .catch(() => undefined)
                    .then(() => decodeDirectStream(
                        event.url,
                        event.startTime,
                        event.userId,
                        event.trackId,
                        event.quality,
                        event.publicCuration,
                        jobId,
                        controller.signal,
                    ))
                return
            }

            const jobId = analysisJobRef.current
            decodeQueueRef.current = decodeQueueRef.current
                .catch(() => undefined)
                .then(() => decodeSegment(event.segment, jobId))
        })

        setMode('waiting')
        setReason('waiting for HLS audio segment')

        return () => {
            mounted = false
            abortControllerRef.current?.abort()
            unsubscribe()
        }
    }, [])

    return useMemo<TidalAudioAnalyserHandle>(() => ({
        mode,
        reason,
        binCount: BIN_COUNT,
        read: (target: Uint8Array) => {
            if (mode !== 'pcm' || !isPlayingRef.current) {
                zero(target)
                return
            }

            const currentTime = audioElementRef.current?.currentTime ?? Number.NaN
            const adjustedTime = Number.isFinite(currentTime) ? currentTime - visualizerOffsetMs / 1000 : Number.NaN
            const samples = ringRef.current.readWindow(adjustedTime, FFT_SIZE)
            if (!samples) {
                zero(target)
                return
            }
            pcmToByteFrequencyData(samples, target)
        },
    }), [mode, reason])
}
