import { startTransition, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ExternalLink, Heart, ListMusic, ListPlus, Play, RefreshCw } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import {
    formatDuration,
    resolveSpotifyContextUri,
    resolveSpotifyTrackId,
    resolveTidalTrackId,
} from '@/lib/musicPlayback'
import {
    toPmsPlaylistPlaybackItem,
    toPmsTrackPlaybackItem,
} from '@/lib/pmsPlayback'
import { ApiError, fetchPmsPlaylistDetail, recordUserMusicEvent } from '@/services/api'
import type { PmsPlaylistDetailResponse } from '@/types/api'

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }

    window.open(url, '_blank', 'noopener,noreferrer')
}

const resolvePlaybackStatusLabel = (item?: ReturnType<typeof toPmsTrackPlaybackItem>) => {
    if (!item) {
        return 'Playback unavailable'
    }

    if (resolveTidalTrackId(item)) {
        return 'TIDAL ready'
    }

    if (resolveSpotifyTrackId(item)) {
        return 'Spotify ready'
    }

    return 'TIDAL searchable'
}

const PmsPlaylistDetailPage = () => {
    const navigate = useNavigate()
    const { playlistId } = useParams<{ playlistId: string }>()
    const { session } = useAuthSession()
    const { appendToQueue, playItem, playQueue, isLoading: playbackLoading } = usePlayback()
    const [detail, setDetail] = useState<PmsPlaylistDetailResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [likedTrackIds, setLikedTrackIds] = useState<Set<string>>(() => new Set())
    const [likePendingTrackId, setLikePendingTrackId] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        if (!session?.userId || !playlistId) {
            setIsLoading(false)
            setDetail(null)
            setError(!session?.userId ? 'Sign in before opening PMS playlist detail.' : 'Playlist id is missing.')
            return () => controller.abort()
        }

        setIsLoading(true)
        setError(null)

        const load = async () => {
            try {
                const response = await fetchPmsPlaylistDetail(session.userId, playlistId, controller.signal)
                startTransition(() => {
                    setDetail(response)
                    setError(null)
                })
            } catch (requestError: unknown) {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }

                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load PMS playlist detail.'
                startTransition(() => {
                    setError(message)
                    setDetail(null)
                })
            } finally {
                setIsLoading(false)
            }
        }

        void load()

        return () => controller.abort()
    }, [playlistId, session?.userId])

    const playbackItems = useMemo(
        () => detail?.tracks.map((track) => toPmsTrackPlaybackItem(track, detail.playlist.title)) ?? [],
        [detail],
    )

    const playlistPlaybackItem = useMemo(
        () => detail ? toPmsPlaylistPlaybackItem(detail.playlist) : null,
        [detail],
    )

    const spotifyContextUri = useMemo(
        () => playlistPlaybackItem ? resolveSpotifyContextUri(playlistPlaybackItem) : null,
        [playlistPlaybackItem],
    )

    const playableCount = useMemo(
        () => playbackItems.length,
        [playbackItems],
    )

    const handlePlayAll = () => {
        if (playbackItems.length > 0) {
            void playQueue(playbackItems, 0)
            return
        }

        if (playlistPlaybackItem && spotifyContextUri) {
            void playItem(playlistPlaybackItem)
        }
    }

    const handleQueueAll = () => {
        if (playbackItems.length > 0) {
            void appendToQueue(playbackItems)
        }
    }

    const handlePlayTrack = (index: number) => {
        const selectedItem = playbackItems[index]
        if (!selectedItem) {
            return
        }

        void playQueue(playbackItems, index)
    }

    const handleLikeTrack = (track: PmsPlaylistDetailResponse['tracks'][number]) => {
        if (!session?.userId || likedTrackIds.has(track.track_id) || likePendingTrackId !== null) {
            return
        }
        setLikePendingTrackId(track.track_id)
        void recordUserMusicEvent({
            user_id: session.userId,
            event_type: 'track_saved',
            source_space: 'pms',
            source_platform: track.source_platform,
            item_id: track.track_id,
            item_kind: 'track',
            track_id: track.track_id,
            playlist_id: detail?.playlist.playlist_id ?? null,
            external_track_id: track.external_track_id,
            platform_uri: track.platform_uri,
            title: track.title,
            artist_name: track.artist_name,
            album_title: track.album_title,
            isrc: track.isrc ?? null,
            duration_ms: track.duration_ms,
            occurred_at: new Date().toISOString(),
        })
            .then(() => {
                setLikedTrackIds((prev) => {
                    const next = new Set(prev)
                    next.add(track.track_id)
                    return next
                })
            })
            .catch(() => undefined)
            .finally(() => setLikePendingTrackId(null))
    }

    if (isLoading) {
        return (
            <HudCard title="Playlist Detail" subtitle="Loading PMS playlist">
                <div className="flex items-center gap-3 text-sm text-hud-text-secondary">
                    <RefreshCw size={16} className="animate-spin" />
                    Loading playlist detail
                </div>
            </HudCard>
        )
    }

    if (error || !detail) {
        return (
            <div className="space-y-4">
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                    <ArrowLeft size={18} />
                    Back
                </Button>
                <div className="rounded-lg border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-5 text-sm leading-6 text-hud-text-secondary">
                    {error ?? 'Playlist detail was not found.'}
                </div>
            </div>
        )
    }

    const { playlist } = detail

    return (
        <div className="space-y-6">
            <div className="flex items-center gap-3">
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                    <ArrowLeft size={18} />
                    Back
                </Button>
                <span className="rounded-lg border border-hud-border-primary px-4 py-2 text-sm font-medium text-hud-accent-primary">
                    PMS
                </span>
            </div>

            <section className="grid gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
                <div className="overflow-hidden rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75">
                    <div className="aspect-square">
                        <MusicArtwork
                            imageUrl={playlist.cover_image_url}
                            seed={`${playlist.source_platform}-${playlist.title}`}
                            label={playlist.title}
                        />
                    </div>
                </div>

                <HudCard noPadding className="min-h-[360px]">
                    <div className="flex h-full flex-col justify-between p-6">
                        <div>
                            <div className="flex flex-wrap gap-2">
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {playlist.source_platform}
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {playlist.track_count} tracks
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {detail.source_collection}
                                </span>
                            </div>

                            <h1 className="mt-6 text-4xl font-semibold text-hud-text-primary lg:text-5xl">
                                {playlist.title}
                            </h1>
                            <p className="mt-3 text-base text-hud-text-secondary">
                                Curated by {playlist.curator}
                            </p>
                            <p className="mt-5 max-w-3xl text-sm leading-6 text-hud-text-secondary">
                                {playlist.description}
                            </p>
                        </div>

                        <div className="mt-8 flex flex-wrap gap-3">
                            <Button
                                type="button"
                                onClick={handlePlayAll}
                                disabled={(playbackItems.length === 0 && !spotifyContextUri) || playbackLoading}
                                glow
                            >
                                <Play size={18} />
                                Play All
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                onClick={() => playlistPlaybackItem ? void playItem(playlistPlaybackItem) : undefined}
                                disabled={!spotifyContextUri || playbackLoading}
                            >
                                <ListMusic size={18} />
                                Play Context
                            </Button>
                            {playlist.platform_external_url && (
                                <Button
                                    type="button"
                                    variant="ghost"
                                    onClick={() => openExternal(playlist.platform_external_url)}
                                >
                                    <ExternalLink size={18} />
                                    Open
                                </Button>
                            )}
                        </div>
                    </div>
                </HudCard>
            </section>

            <HudCard
                title="Tracks"
                subtitle={`${playableCount} stored tracks`}
                action={
                    <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={handleQueueAll}
                        disabled={playbackItems.length === 0 || playbackLoading}
                    >
                        <ListPlus size={16} />
                        Queue All
                    </Button>
                }
            >
                <div className="divide-y divide-hud-border-secondary overflow-hidden rounded-lg border border-hud-border-secondary">
                    {detail.tracks.map((track, index) => {
                        const durationLabel = formatDuration(track.duration_ms)
                        const playbackItem = playbackItems[index]
                        const hasNativePlaybackTarget = Boolean(playbackItem && (resolveSpotifyTrackId(playbackItem) || resolveTidalTrackId(playbackItem)))
                        const playbackStatusLabel = resolvePlaybackStatusLabel(playbackItem)

                        return (
                            <div
                                key={track.track_id}
                                className="grid gap-4 bg-hud-bg-primary/70 p-4 transition-hud hover:bg-hud-bg-hover md:grid-cols-[56px_minmax(0,1.3fr)_minmax(180px,0.7fr)_auto]"
                            >
                                <button
                                    type="button"
                                    onClick={() => handlePlayTrack(index)}
                                    disabled={playbackLoading || !playbackItem}
                                    className="flex h-12 w-12 items-center justify-center rounded-lg border border-hud-border-secondary text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-accent-primary disabled:cursor-not-allowed disabled:opacity-50"
                                    aria-label={`Play ${track.title}`}
                                >
                                    <Play size={18} />
                                </button>

                                <div className="min-w-0">
                                    <p className="truncate font-medium text-hud-text-primary">
                                        {index + 1}. {track.title}
                                    </p>
                                    <p className="mt-1 truncate text-sm text-hud-text-secondary">
                                        <ArtistDetailLink
                                            artistName={track.artist_name}
                                            className="inline-block max-w-full truncate transition-hud hover:text-hud-accent-primary"
                                        />
                                    </p>
                                </div>

                                <div className="min-w-0">
                                    <p className="truncate text-sm text-hud-text-secondary">
                                        {track.album_title ?? 'Single'}
                                    </p>
                                    <p className={`mt-1 truncate text-xs uppercase tracking-[0.2em] ${hasNativePlaybackTarget ? 'text-hud-accent-primary' : 'text-hud-text-muted'}`}>
                                        {playbackStatusLabel}
                                    </p>
                                </div>

                                <div className="flex items-center justify-between gap-3 md:justify-end">
                                    <span className="w-12 text-sm text-hud-text-muted">
                                        {durationLabel ?? '--:--'}
                                    </span>
                                    <button
                                        type="button"
                                        onClick={() => handleLikeTrack(track)}
                                        disabled={!session?.userId || likedTrackIds.has(track.track_id) || likePendingTrackId === track.track_id}
                                        className={`flex h-10 w-10 items-center justify-center rounded-lg transition-hud hover:bg-hud-bg-secondary disabled:cursor-not-allowed disabled:opacity-50 ${
                                            likedTrackIds.has(track.track_id)
                                                ? 'text-hud-accent-primary'
                                                : 'text-hud-text-secondary hover:text-hud-text-primary'
                                        }`}
                                        aria-label={likedTrackIds.has(track.track_id) ? `Liked ${track.title}` : `Save ${track.title}`}
                                        aria-pressed={likedTrackIds.has(track.track_id)}
                                    >
                                        <Heart size={17} fill={likedTrackIds.has(track.track_id) ? 'currentColor' : 'none'} />
                                    </button>
                                    {track.platform_external_url && (
                                        <button
                                            type="button"
                                            onClick={() => openExternal(track.platform_external_url)}
                                            className="flex h-10 w-10 items-center justify-center rounded-lg text-hud-text-secondary transition-hud hover:bg-hud-bg-secondary hover:text-hud-text-primary"
                                            aria-label={`Open ${track.title}`}
                                        >
                                            <ExternalLink size={17} />
                                        </button>
                                    )}
                                </div>
                            </div>
                        )
                    })}
                </div>
            </HudCard>
        </div>
    )
}

export default PmsPlaylistDetailPage
