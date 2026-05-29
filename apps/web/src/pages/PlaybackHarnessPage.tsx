import { useCallback, useEffect, useMemo, useState } from 'react'
import { CheckCircle2, ListMusic, Play, RefreshCw, ShieldCheck, XCircle } from 'lucide-react'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { extractSpotifyTrackIdFromUrl, formatDuration, type PlaybackMediaItem } from '@/lib/musicPlayback'
import { ApiError, fetchPlaybackCredentials } from '@/services/api'
import type { PlatformPlaybackCredentialsResponse } from '@/types/api'

const HARNESS_INPUT_STORAGE_KEY = 'my-forever-music.playback-harness.spotify-input'
const REQUIRED_SPOTIFY_SCOPES = [
    'streaming',
    'user-read-playback-state',
    'user-modify-playback-state',
]

const parseSpotifyTracks = (rawValue: string) => {
    const ids = rawValue
        .split(/[\s,]+/)
        .map((value) => extractSpotifyTrackIdFromUrl(value))
        .filter((value): value is string => Boolean(value))

    return Array.from(new Set(ids))
}

const toHarnessItems = (spotifyTrackIds: string[]): PlaybackMediaItem[] =>
    spotifyTrackIds.map((spotifyTrackId, index) => ({
        id: `harness:spotify:${spotifyTrackId}`,
        kind: 'track',
        title: `Spotify Track ${index + 1}`,
        subtitle: `spotify:track:${spotifyTrackId}`,
        sourcePlatform: 'spotify',
        playbackPlatformId: 'spotify',
        spotifyTrackId,
        platformUri: `spotify:track:${spotifyTrackId}`,
        externalUrl: `https://open.spotify.com/track/${spotifyTrackId}`,
        durationMs: null,
    }))

const youtubeHarnessItem: PlaybackMediaItem = {
    id: 'harness:youtube:dQw4w9WgXcQ',
    kind: 'track',
    title: 'YouTube Test Track',
    subtitle: 'YouTube Mock Artist · Playback Harness',
    sourcePlatform: 'youtube',
    playbackPlatformId: 'youtube',
    youtubeVideoId: 'dQw4w9WgXcQ',
    platformUri: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
    externalUrl: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
    durationMs: 214_000,
}

const readStoredInput = () => {
    if (typeof window === 'undefined') {
        return ''
    }

    return window.localStorage.getItem(HARNESS_INPUT_STORAGE_KEY) ?? ''
}

const PlaybackHarnessPage = () => {
    const { session } = useAuthSession()
    const {
        currentItem,
        queue,
        currentIndex,
        isPlaying,
        isLoading,
        error,
        notice,
        positionMs,
        durationMs,
        volume,
        deviceId,
        playQueue,
        playItem,
    } = usePlayback()
    const [rawInput, setRawInput] = useState(readStoredInput)
    const [startIndex, setStartIndex] = useState(0)
    const [credential, setCredential] = useState<PlatformPlaybackCredentialsResponse | null>(null)
    const [credentialError, setCredentialError] = useState<string | null>(null)
    const [isCheckingCredential, setIsCheckingCredential] = useState(false)
    const [events, setEvents] = useState<string[]>([])

    useEffect(() => {
        window.localStorage.setItem(HARNESS_INPUT_STORAGE_KEY, rawInput)
    }, [rawInput])

    const spotifyTrackIds = useMemo(() => parseSpotifyTracks(rawInput), [rawInput])
    const harnessItems = useMemo(() => toHarnessItems(spotifyTrackIds), [spotifyTrackIds])
    const safeStartIndex = harnessItems.length > 0 ? Math.min(startIndex, harnessItems.length - 1) : 0
    const missingScopes = useMemo(() => {
        if (!credential) {
            return REQUIRED_SPOTIFY_SCOPES
        }

        return REQUIRED_SPOTIFY_SCOPES.filter((scope) => !credential.scopes.includes(scope))
    }, [credential])

    const appendEvent = useCallback((message: string) => {
        const timestamp = new Date().toLocaleTimeString()
        setEvents((current) => [`${timestamp} ${message}`, ...current].slice(0, 8))
    }, [])

    const handleCredentialCheck = useCallback(async () => {
        if (!session?.userId) {
            setCredentialError('Sign in before checking Spotify credentials.')
            return
        }

        setIsCheckingCredential(true)
        setCredentialError(null)
        try {
            const response = await fetchPlaybackCredentials(session.userId, 'spotify')
            setCredential(response)
            appendEvent('Spotify credential check completed.')
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Spotify credential check failed.'
            setCredential(null)
            setCredentialError(message)
            appendEvent(`Credential check failed: ${message}`)
        } finally {
            setIsCheckingCredential(false)
        }
    }, [appendEvent, session?.userId])

    const handlePlayQueue = useCallback(async () => {
        if (harnessItems.length === 0) {
            appendEvent('No Spotify tracks parsed.')
            return
        }

        appendEvent(`Play queue requested: ${harnessItems.length} tracks from ${safeStartIndex + 1}.`)
        await playQueue(harnessItems, safeStartIndex)
    }, [appendEvent, harnessItems, playQueue, safeStartIndex])

    const handlePlaySelected = useCallback(async () => {
        const selectedItem = harnessItems[safeStartIndex]
        if (!selectedItem) {
            appendEvent('No selected Spotify track.')
            return
        }

        appendEvent(`Single track playback requested: ${selectedItem.spotifyTrackId}`)
        await playItem(selectedItem)
    }, [appendEvent, harnessItems, playItem, safeStartIndex])

    const handlePlayYouTubeTest = useCallback(async () => {
        appendEvent('YouTube test playback requested.')
        await playItem(youtubeHarnessItem)
    }, [appendEvent, playItem])

    return (
        <div className="space-y-6">
            <section className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_minmax(360px,0.9fr)]">
                <HudCard
                    title="Playback Harness"
                    subtitle="Spotify queue runner"
                    action={
                        <span className="rounded-full border border-hud-border-secondary px-3 py-1.5 text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                            {session?.displayName ?? 'Signed out'}
                        </span>
                    }
                >
                    <div className="space-y-4">
                        <textarea
                            value={rawInput}
                            onChange={(event) => setRawInput(event.target.value)}
                            rows={8}
                            placeholder="spotify:track:4iV5W9uYEdYUVa79Axb7Rh"
                            className="w-full resize-y rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 font-mono text-sm text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-border-primary"
                        />

                        <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_180px]">
                            <label className="block">
                                <span className="mb-2 block text-sm font-medium text-hud-text-secondary">
                                    Start Track
                                </span>
                                <input
                                    type="number"
                                    min={1}
                                    max={Math.max(harnessItems.length, 1)}
                                    value={safeStartIndex + 1}
                                    onChange={(event) => setStartIndex(Math.max(0, Number(event.target.value) - 1))}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-2.5 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                />
                            </label>

                            <div className="flex items-end gap-2">
                                <Button
                                    type="button"
                                    variant="outline"
                                    fullWidth
                                    onClick={handleCredentialCheck}
                                    disabled={isCheckingCredential}
                                >
                                    {isCheckingCredential ? <RefreshCw size={16} className="animate-spin" /> : <ShieldCheck size={16} />}
                                    Check
                                </Button>
                            </div>
                        </div>

                        <div className="flex flex-wrap gap-3">
                            <Button
                                type="button"
                                onClick={handlePlayQueue}
                                disabled={harnessItems.length === 0 || isLoading}
                            >
                                <Play size={16} />
                                Play Queue
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                onClick={handlePlaySelected}
                                disabled={harnessItems.length === 0 || isLoading}
                            >
                                <ListMusic size={16} />
                                Play Selected
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                onClick={handlePlayYouTubeTest}
                                disabled={isLoading}
                            >
                                <Play size={16} />
                                YouTube 테스트 재생
                            </Button>
                        </div>
                    </div>
                </HudCard>

                <HudCard title="Runtime" subtitle="Spotify SDK state">
                    <div className="grid gap-3 sm:grid-cols-2">
                        <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">Credential</p>
                            <div className="mt-3 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                                {credential && missingScopes.length === 0 ? (
                                    <CheckCircle2 size={18} className="text-hud-accent-primary" />
                                ) : (
                                    <XCircle size={18} className="text-amber-300" />
                                )}
                                {credential ? credential.platform_id : 'Unchecked'}
                            </div>
                            <p className="mt-2 truncate text-xs text-hud-text-muted">
                                {credential?.external_account_label ?? credentialError ?? 'No credential result yet'}
                            </p>
                        </div>

                        <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">Player</p>
                            <p className="mt-3 text-sm font-medium text-hud-text-primary">
                                {isPlaying ? 'Playing' : isLoading ? 'Loading' : 'Idle'}
                            </p>
                            <p className="mt-2 truncate text-xs text-hud-text-muted">
                                {deviceId ?? 'No device id'}
                            </p>
                        </div>

                        <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">Queue</p>
                            <p className="mt-3 text-sm font-medium text-hud-text-primary">
                                {queue.length > 0 ? `${currentIndex + 1} / ${queue.length}` : `${harnessItems.length} parsed`}
                            </p>
                            <p className="mt-2 truncate text-xs text-hud-text-muted">
                                {currentItem?.title ?? 'Nothing selected'}
                            </p>
                        </div>

                        <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                            <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">Position</p>
                            <p className="mt-3 text-sm font-medium text-hud-text-primary">
                                {formatDuration(positionMs) ?? '0:00'} / {formatDuration(durationMs) ?? '0:00'}
                            </p>
                            <p className="mt-2 truncate text-xs text-hud-text-muted">
                                Volume {Math.round(volume * 100)}%
                            </p>
                        </div>
                    </div>

                    {(error || notice || missingScopes.length > 0) && (
                        <div className={`mt-4 rounded-lg border p-4 text-sm leading-6 ${
                            error || missingScopes.length > 0
                                ? 'border-amber-300/30 bg-amber-300/10 text-amber-100'
                                : 'border-hud-accent-primary/30 bg-hud-accent-primary/10 text-hud-accent-primary'
                        }`}
                        >
                            {error ?? notice ?? `Missing scopes: ${missingScopes.join(', ')}`}
                        </div>
                    )}
                </HudCard>
            </section>

            <section className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
                <HudCard title="Parsed Queue" subtitle={`${harnessItems.length} Spotify tracks`}>
                    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                        {harnessItems.map((item, index) => (
                            <button
                                key={item.id}
                                type="button"
                                onClick={() => setStartIndex(index)}
                                className={`min-w-0 rounded-lg border p-4 text-left transition-hud ${
                                    index === safeStartIndex
                                        ? 'border-hud-border-primary bg-hud-accent-primary/10'
                                        : 'border-hud-border-secondary bg-hud-bg-primary/70 hover:border-hud-border-primary'
                                }`}
                            >
                                <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                    Track {index + 1}
                                </p>
                                <p className="mt-2 truncate font-mono text-sm text-hud-text-primary">
                                    {item.spotifyTrackId}
                                </p>
                            </button>
                        ))}
                    </div>
                </HudCard>

                <HudCard title="Events" subtitle="Last playback actions">
                    <div className="space-y-2">
                        {events.length === 0 ? (
                            <p className="text-sm text-hud-text-muted">No events yet.</p>
                        ) : (
                            events.map((event) => (
                                <p
                                    key={event}
                                    className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-2 text-xs leading-5 text-hud-text-secondary"
                                >
                                    {event}
                                </p>
                            ))
                        )}
                    </div>
                </HudCard>
            </section>
        </div>
    )
}

export default PlaybackHarnessPage
