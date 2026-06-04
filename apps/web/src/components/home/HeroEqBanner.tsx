import { useEffect, useMemo, useRef, useState } from 'react'
import { Pause, Play, RotateCcw, Volume2, VolumeX } from 'lucide-react'
import { Link } from 'react-router-dom'
import SpectrumVisualizerFrame from '@/components/visualizer/SpectrumVisualizerFrame'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useHeroTracks } from '@/hooks/useHeroTracks'
import { usePreviewAudioAnalyser } from '@/hooks/usePreviewAudioAnalyser'
import type { PlaybackMediaItem } from '@/lib/musicPlayback'
import type { HeroTrackResponse } from '@/types/api'

const HERO_LIMIT = 5
const TRACK_GAP_MS = 3_000

const formatTime = (seconds: number) => {
    if (!Number.isFinite(seconds) || seconds <= 0) {
        return '0:00'
    }
    const total = Math.round(seconds)
    const m = Math.floor(total / 60)
    const s = total % 60
    return `${m}:${String(s).padStart(2, '0')}`
}

const toPlaybackItem = (hero: HeroTrackResponse): PlaybackMediaItem => ({
    id: hero.spotify_track_id ?? hero.external_track_id,
    kind: 'track',
    title: hero.title,
    subtitle: hero.album_title ? `${hero.artist_name} · ${hero.album_title}` : hero.artist_name,
    sourcePlatform: hero.source_platform,
    spotifyTrackId: hero.spotify_track_id,
    externalTrackId: hero.external_track_id,
    imageUrl: hero.image_url,
    albumTitle: hero.album_title,
    externalUrl: hero.platform_external_url,
    durationMs: hero.duration_ms,
    previewUrl: hero.preview_url,
})

const HeroEqBanner = () => {
    const { session } = useAuthSession()
    const heroState = useHeroTracks(session?.userId ?? null, HERO_LIMIT)
    const audioRef = useRef<HTMLAudioElement | null>(null)
    const gapTimerRef = useRef<number | null>(null)
    const [currentIndex, setCurrentIndex] = useState(0)
    const [isMuted, setIsMuted] = useState(true)
    const [isPlaying, setIsPlaying] = useState(false)
    const [isInGap, setIsInGap] = useState(false)
    const [rotationFinished, setRotationFinished] = useState(false)
    const [rotationStopped, setRotationStopped] = useState(false)
    const [position, setPosition] = useState(0)
    const [duration, setDuration] = useState(0)
    const playback = usePlayback()

    const tracks = heroState.status === 'ready' ? heroState.tracks : []
    const currentTrack = tracks[currentIndex] ?? null
    const previewUrl = currentTrack?.preview_url ?? null
    const analyser = usePreviewAudioAnalyser(previewUrl, audioRef.current)

    useEffect(() => {
        setCurrentIndex(0)
        setRotationFinished(false)
        setRotationStopped(false)
        setIsInGap(false)
    }, [heroState.status, tracks.length])

    useEffect(() => () => {
        if (gapTimerRef.current !== null) {
            window.clearTimeout(gapTimerRef.current)
            gapTimerRef.current = null
        }
    }, [])

    useEffect(() => {
        const audio = audioRef.current
        if (!audio || !previewUrl) {
            return
        }
        if (rotationStopped) {
            return
        }

        const handlePlay = () => setIsPlaying(true)
        const handlePause = () => setIsPlaying(false)
        const handleTimeUpdate = () => setPosition(audio.currentTime)
        const handleDurationChange = () => setDuration(audio.duration || 0)
        const handleEnded = () => {
            setIsPlaying(false)
            setPosition(audio.duration || 0)
            if (rotationStopped) {
                return
            }
            if (currentIndex >= tracks.length - 1) {
                setRotationFinished(true)
                return
            }
            setIsInGap(true)
            gapTimerRef.current = window.setTimeout(() => {
                gapTimerRef.current = null
                setIsInGap(false)
                setCurrentIndex((prev) => prev + 1)
            }, TRACK_GAP_MS)
        }

        audio.addEventListener('play', handlePlay)
        audio.addEventListener('pause', handlePause)
        audio.addEventListener('ended', handleEnded)
        audio.addEventListener('timeupdate', handleTimeUpdate)
        audio.addEventListener('durationchange', handleDurationChange)

        audio.muted = true
        audio.currentTime = 0
        setIsMuted(true)
        setIsPlaying(false)
        setPosition(0)
        setDuration(audio.duration || 0)
        audio.play().catch(() => undefined)

        return () => {
            audio.removeEventListener('play', handlePlay)
            audio.removeEventListener('pause', handlePause)
            audio.removeEventListener('ended', handleEnded)
            audio.removeEventListener('timeupdate', handleTimeUpdate)
            audio.removeEventListener('durationchange', handleDurationChange)
        }
    }, [previewUrl, rotationStopped, currentIndex, tracks.length])

    const handlePrimary = () => {
        const audio = audioRef.current
        if (!audio) {
            return
        }
        if (rotationFinished) {
            return
        }
        if (audio.paused) {
            audio.muted = false
            setIsMuted(false)
            void audio.play().catch(() => undefined)
            return
        }
        if (isMuted) {
            audio.muted = false
            setIsMuted(false)
            return
        }
        audio.pause()
    }

    const handleReplayAll = () => {
        if (gapTimerRef.current !== null) {
            window.clearTimeout(gapTimerRef.current)
            gapTimerRef.current = null
        }
        setRotationFinished(false)
        setRotationStopped(false)
        setIsInGap(false)
        setCurrentIndex(0)
    }

    const handlePlayFullTrack = () => {
        if (!currentTrack) {
            return
        }
        if (gapTimerRef.current !== null) {
            window.clearTimeout(gapTimerRef.current)
            gapTimerRef.current = null
        }
        setRotationStopped(true)
        const audio = audioRef.current
        if (audio) {
            audio.pause()
        }
        const item = toPlaybackItem(currentTrack)
        void playback.playItem(item).catch(() => undefined)
    }

    const primaryLabel = useMemo(() => {
        if (rotationFinished) {
            return 'Replay all'
        }
        if (!isPlaying) {
            return 'Listen preview'
        }
        if (isMuted) {
            return 'Unmute preview'
        }
        return 'Pause preview'
    }, [rotationFinished, isPlaying, isMuted])

    if (heroState.status === 'loading') {
        return (
            <section className="relative h-72 animate-pulse rounded-3xl border border-hud-border-secondary bg-hud-bg-primary/60" />
        )
    }
    if (heroState.status === 'empty' || heroState.status === 'error' || !currentTrack) {
        return null
    }

    const trackKey = `${currentTrack.source_platform}-${currentTrack.external_track_id}`
    const totalDisplay = duration > 0 ? duration : 30
    const progressRatio = totalDisplay > 0 ? Math.min(1, position / totalDisplay) : 0

    return (
        <section className="relative overflow-hidden rounded-3xl border border-white/10 bg-black text-white shadow-[0_30px_80px_-30px_rgba(0,0,0,0.7)]">
            {currentTrack.image_url ? (
                <img
                    src={currentTrack.image_url}
                    alt=""
                    aria-hidden
                    className="absolute inset-0 h-full w-full scale-110 object-cover opacity-50 blur-2xl"
                />
            ) : null}
            <div className="absolute inset-0 bg-gradient-to-br from-black/70 via-black/40 to-black/70" />

            <div className="relative grid gap-8 px-6 py-8 sm:px-10 md:grid-cols-[240px_1fr] md:gap-10 md:py-10">
                <div className="mx-auto h-56 w-56 overflow-hidden rounded-2xl border border-white/10 bg-black/40 shadow-[0_30px_60px_-20px_rgba(0,0,0,0.8)] md:mx-0 md:h-60 md:w-60">
                    <MusicArtwork
                        imageUrl={currentTrack.image_url}
                        seed={trackKey}
                        label={currentTrack.title}
                    />
                </div>
                <div className="flex flex-col justify-between gap-6">
                    <div>
                        <div className="flex flex-wrap items-center gap-3">
                            {currentTrack.source_label && (
                                <p className="text-[11px] uppercase tracking-[0.32em] text-white/60">{currentTrack.source_label}</p>
                            )}
                            <span className="rounded-full border border-white/15 bg-white/5 px-2 py-0.5 text-[11px] uppercase tracking-[0.2em] text-white/60">
                                {currentIndex + 1}/{tracks.length}
                            </span>
                            {isInGap && (
                                <span className="rounded-full border border-white/20 bg-white/10 px-2 py-0.5 text-[11px] uppercase tracking-[0.2em] text-white/80">
                                    Up next…
                                </span>
                            )}
                        </div>
                        <h2 className="mt-3 text-3xl font-semibold leading-tight tracking-tight md:text-4xl">{currentTrack.title}</h2>
                        <p className="mt-2 text-base text-white/70">
                            <ArtistDetailLink
                                artistName={currentTrack.artist_name}
                                className="transition-hud hover:text-white"
                            />
                            {currentTrack.album_title ? <span className="text-white/40"> · {currentTrack.album_title}</span> : null}
                        </p>
                        <div className="mt-3 flex items-center gap-1.5">
                            {tracks.map((track, index) => (
                                <span
                                    key={track.external_track_id}
                                    className={`h-1.5 w-6 rounded-full transition-colors ${
                                        index === currentIndex
                                            ? 'bg-white'
                                            : index < currentIndex
                                                ? 'bg-white/40'
                                                : 'bg-white/10'
                                    }`}
                                />
                            ))}
                        </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                        {rotationFinished ? (
                            <button
                                type="button"
                                onClick={handleReplayAll}
                                className="inline-flex h-12 items-center gap-2 rounded-full bg-white px-5 text-sm font-semibold text-black shadow-md transition hover:bg-white/90"
                            >
                                <RotateCcw size={18} />
                                <span>Replay all</span>
                            </button>
                        ) : (
                            <button
                                type="button"
                                onClick={handlePrimary}
                                className="inline-flex h-12 items-center gap-2 rounded-full bg-white px-5 text-sm font-semibold text-black shadow-md transition hover:bg-white/90"
                                aria-label={primaryLabel}
                            >
                                {!isPlaying ? <Play size={18} /> : isMuted ? <VolumeX size={18} /> : <Pause size={18} />}
                                <span>{primaryLabel}</span>
                            </button>
                        )}
                        {session?.userId ? (
                            <button
                                type="button"
                                onClick={handlePlayFullTrack}
                                className="inline-flex h-12 items-center gap-2 rounded-full border border-white/40 px-5 text-sm font-semibold text-white transition hover:bg-white/10"
                            >
                                전체 듣기
                            </button>
                        ) : (
                            <Link
                                to="/signin"
                                className="inline-flex h-12 items-center gap-2 rounded-full border border-white/40 px-5 text-sm font-semibold text-white transition hover:bg-white/10"
                            >
                                로그인하고 전체 듣기
                            </Link>
                        )}
                        <span className="ml-auto inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-3 py-1 text-xs text-white/70">
                            {isMuted ? <VolumeX size={14} /> : <Volume2 size={14} />}
                            {isMuted ? 'muted' : 'sound on'}
                        </span>
                    </div>
                </div>
            </div>

            <div className="relative px-6 pb-6 sm:px-10">
                <SpectrumVisualizerFrame
                    analyser={analyser}
                    accentHex="#ffffff"
                    isPlaying={isPlaying && !isMuted}
                    className="h-28 w-full"
                />
                <div className="mt-3 flex items-center gap-3 text-xs font-mono text-white/60">
                    <span className="w-10 text-right">{formatTime(position)}</span>
                    <div className="relative h-1 flex-1 overflow-hidden rounded-full bg-white/15">
                        <div
                            className="absolute inset-y-0 left-0 bg-white/80 transition-[width] duration-100"
                            style={{ width: `${progressRatio * 100}%` }}
                        />
                    </div>
                    <span className="w-10">{formatTime(totalDisplay)}</span>
                </div>
            </div>

            <audio
                ref={audioRef}
                src={previewUrl ?? undefined}
                preload="auto"
                playsInline
                className="hidden"
            />
        </section>
    )
}

export default HeroEqBanner
