import { startTransition, useEffect, useState } from 'react'
import { ArrowLeft, ExternalLink, Heart, ListPlus, Play, RefreshCw } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import {
    emsSearchPlaylistCacheKey,
    toEmsSearchTrackPlaybackItem,
} from '@/lib/emsPlayback'
import {
    formatDuration,
} from '@/lib/musicPlayback'
import { ApiError, fetchEmsSearchPlaylistTracks, recordUserMusicEvent } from '@/services/api'
import type { EmsCollectionSearchPlaylistItem, EmsCollectionSearchTrackItem } from '@/types/api'

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }

    window.open(url, '_blank', 'noopener,noreferrer')
}

const readCachedPlaylist = (platformId?: string, externalPlaylistId?: string) => {
    if (!platformId || !externalPlaylistId) {
        return null
    }
    const cached = window.sessionStorage.getItem(emsSearchPlaylistCacheKey(platformId, externalPlaylistId))
    if (!cached) {
        return null
    }
    try {
        return JSON.parse(cached) as EmsCollectionSearchPlaylistItem
    } catch {
        window.sessionStorage.removeItem(emsSearchPlaylistCacheKey(platformId, externalPlaylistId))
        return null
    }
}

const EmsSearchPlaylistDetailPage = () => {
    const navigate = useNavigate()
    const { platformId, externalPlaylistId } = useParams<{ platformId: string; externalPlaylistId: string }>()
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const { appendToQueue, playQueue, isLoading: playbackLoading } = usePlayback()
    const activeUserId = session?.userId || workspace.userId
    const [playlist] = useState(() => readCachedPlaylist(platformId, externalPlaylistId))
    const [tracks, setTracks] = useState<EmsCollectionSearchTrackItem[]>([])
    const [trackCount, setTrackCount] = useState(() => playlist?.track_count ?? 0)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [likedTrackKeys, setLikedTrackKeys] = useState<Set<string>>(() => new Set())
    const [likePendingTrackKey, setLikePendingTrackKey] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        if (!platformId || !externalPlaylistId) {
            setIsLoading(false)
            setError('EMS search playlist id is invalid.')
            return () => controller.abort()
        }
        if (!activeUserId) {
            setIsLoading(false)
            setError('Sign in before loading provider playlist tracks.')
            return () => controller.abort()
        }

        setIsLoading(true)
        setError(null)

        fetchEmsSearchPlaylistTracks(platformId, externalPlaylistId, activeUserId, controller.signal)
            .then((response) => {
                startTransition(() => {
                    setTracks(response.tracks)
                    setTrackCount(response.track_count)
                    setError(null)
                })
            })
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load provider playlist tracks.'
                startTransition(() => {
                    setTracks([])
                    setTrackCount(0)
                    setError(message)
                })
            })
            .finally(() => setIsLoading(false))

        return () => controller.abort()
    }, [activeUserId, externalPlaylistId, platformId])

    const playlistTitle = playlist?.title ?? externalPlaylistId ?? 'Search Playlist'
    const totalTrackCount = playlist?.track_count ?? trackCount
    const handlePlayAll = () => {
        const playbackItems = tracks.map((track) => toEmsSearchTrackPlaybackItem(track, playlistTitle))
        if (playbackItems.length > 0) {
            void playQueue(playbackItems, 0)
        }
    }

    const handleQueueAll = () => {
        const playbackItems = tracks.map((track) => toEmsSearchTrackPlaybackItem(track, playlistTitle))
        if (playbackItems.length > 0) {
            void appendToQueue(playbackItems)
        }
    }

    const handlePlayTrack = (index: number) => {
        const playbackItems = tracks.map((track) => toEmsSearchTrackPlaybackItem(track, playlistTitle))
        if (playbackItems[index]) {
            void playQueue(playbackItems, index)
        }
    }

    const trackKey = (track: EmsCollectionSearchTrackItem) => `${track.source_platform}:${track.external_track_id}`

    const handleLikeTrack = (track: EmsCollectionSearchTrackItem) => {
        const key = trackKey(track)
        if (!session?.userId || likedTrackKeys.has(key) || likePendingTrackKey !== null) {
            return
        }
        setLikePendingTrackKey(key)
        void recordUserMusicEvent({
            user_id: session.userId,
            event_type: 'track_saved',
            source_space: 'ems',
            source_platform: track.source_platform,
            item_id: track.external_track_id,
            item_kind: 'track',
            external_track_id: track.external_track_id,
            platform_uri: track.platform_uri,
            title: track.title,
            artist_name: track.artist_name,
            album_title: track.album_title,
            isrc: track.isrc,
            duration_ms: track.duration_ms,
            occurred_at: new Date().toISOString(),
        })
            .then(() => {
                setLikedTrackKeys((prev) => {
                    const next = new Set(prev)
                    next.add(key)
                    return next
                })
            })
            .catch(() => undefined)
            .finally(() => setLikePendingTrackKey(null))
    }

    if (isLoading) {
        return (
            <HudCard title="Search Playlist" subtitle="Loading provider tracks">
                <div className="flex items-center gap-3 text-sm text-hud-text-secondary">
                    <RefreshCw size={16} className="animate-spin" />
                    Loading playlist tracks
                </div>
            </HudCard>
        )
    }

    if (error) {
        return (
            <div className="space-y-4">
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                    <ArrowLeft size={18} />
                    Back
                </Button>
                <div className="rounded-lg border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-5 text-sm leading-6 text-hud-text-secondary">
                    {error}
                </div>
            </div>
        )
    }

    return (
        <div className="space-y-6">
            <div className="flex items-center gap-3">
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                    <ArrowLeft size={18} />
                    Back
                </Button>
                <span className="rounded-lg border border-hud-border-primary px-4 py-2 text-sm font-medium uppercase text-hud-accent-primary">
                    {platformId}
                </span>
            </div>

            <section className="grid gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
                <div className="overflow-hidden rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75">
                    <div className="aspect-square">
                        <MusicArtwork
                            imageUrl={playlist?.cover_image_url}
                            seed={`${platformId}-${playlistTitle}`}
                            label={playlistTitle}
                        />
                    </div>
                </div>

                <HudCard noPadding className="min-h-[360px]">
                    <div className="flex h-full flex-col justify-between p-6">
                        <div>
                            <div className="flex flex-wrap gap-2">
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    Search Result
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {totalTrackCount} tracks
                                </span>
                            </div>
                            <h1 className="mt-6 text-4xl font-semibold text-hud-text-primary lg:text-5xl">
                                {playlistTitle}
                            </h1>
                            {playlist?.curator && (
                                <p className="mt-3 text-base text-hud-text-secondary">
                                    Curated by {playlist.curator}
                                </p>
                            )}
                            <p className="mt-5 max-w-3xl text-sm leading-6 text-hud-text-secondary">
                                {playlist?.description || 'Provider playlist tracks are loaded on demand and are not stored in EMS.'}
                            </p>
                        </div>

                        <div className="mt-8 flex flex-wrap gap-3">
                            <Button
                                type="button"
                                onClick={handlePlayAll}
                                disabled={tracks.length === 0 || playbackLoading}
                                glow
                            >
                                <Play size={18} />
                                Play All
                            </Button>
                            {playlist?.platform_external_url && (
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
                subtitle={`${totalTrackCount} provider tracks`}
                action={
                    <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={handleQueueAll}
                        disabled={tracks.length === 0 || playbackLoading}
                    >
                        <ListPlus size={16} />
                        Queue All
                    </Button>
                }
            >
                <div className="divide-y divide-hud-border-secondary overflow-hidden rounded-lg border border-hud-border-secondary">
                    {tracks.map((track, index) => {
                        const durationLabel = formatDuration(track.duration_ms)
                        const hasProviderTrackTarget = Boolean(track.external_track_id || track.platform_uri || track.spotify_uri)
                        const playbackStatusLabel = hasProviderTrackTarget ? `${track.source_platform} result` : 'Metadata only'

                        return (
                            <div
                                key={`${track.source_platform}-${track.external_track_id}-${index}`}
                                className="grid gap-4 bg-hud-bg-primary/70 p-4 transition-hud hover:bg-hud-bg-hover md:grid-cols-[56px_minmax(0,1.3fr)_minmax(180px,0.7fr)_auto]"
                            >
                                <button
                                    type="button"
                                    onClick={() => handlePlayTrack(index)}
                                    disabled={playbackLoading}
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
                                    <p className={`mt-1 truncate text-xs uppercase tracking-[0.2em] ${hasProviderTrackTarget ? 'text-hud-accent-primary' : 'text-hud-text-muted'}`}>
                                        {playbackStatusLabel}
                                    </p>
                                    <p className="mt-1 truncate text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                        {track.isrc ? `ISRC ${track.isrc}` : 'ISRC missing'}
                                    </p>
                                </div>

                                <div className="flex items-center justify-between gap-3 md:justify-end">
                                    <span className="w-12 text-sm text-hud-text-muted">
                                        {durationLabel ?? '--:--'}
                                    </span>
                                    {(() => {
                                        const key = trackKey(track)
                                        const liked = likedTrackKeys.has(key)
                                        const pending = likePendingTrackKey === key
                                        return (
                                            <button
                                                type="button"
                                                onClick={() => handleLikeTrack(track)}
                                                disabled={!session?.userId || liked || pending}
                                                className={`flex h-10 w-10 items-center justify-center rounded-lg transition-hud hover:bg-hud-bg-secondary disabled:cursor-not-allowed disabled:opacity-50 ${
                                                    liked
                                                        ? 'text-hud-accent-primary'
                                                        : 'text-hud-text-secondary hover:text-hud-text-primary'
                                                }`}
                                                aria-label={liked ? `Liked ${track.title}` : `Save ${track.title}`}
                                                aria-pressed={liked}
                                            >
                                                <Heart size={17} fill={liked ? 'currentColor' : 'none'} />
                                            </button>
                                        )
                                    })()}
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

export default EmsSearchPlaylistDetailPage
