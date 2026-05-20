import { startTransition, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ExternalLink, Heart, ListPlus, Play, RefreshCw } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { toEmsTrackPlaybackItem } from '@/lib/emsPlayback'
import {
    formatDuration,
    resolveSpotifyTrackId,
    resolveTidalTrackId,
} from '@/lib/musicPlayback'
import { ApiError, fetchEmsCollectedPlaylistDetail, recordUserMusicEvent } from '@/services/api'
import type { EmsCollectionPlaylistDetailResponse } from '@/types/api'

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }

    window.open(url, '_blank', 'noopener,noreferrer')
}

const EmsPlaylistDetailPage = () => {
    const navigate = useNavigate()
    const { playlistId } = useParams<{ playlistId: string }>()
    const { appendToQueue, playQueue, isLoading: playbackLoading } = usePlayback()
    const { session } = useAuthSession()
    const [detail, setDetail] = useState<EmsCollectionPlaylistDetailResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)
    const [likedTrackIds, setLikedTrackIds] = useState<Set<number>>(() => new Set())
    const [likePendingTrackId, setLikePendingTrackId] = useState<number | null>(null)

    useEffect(() => {
        const controller = new AbortController()
        const parsedPlaylistId = Number(playlistId)

        if (!Number.isFinite(parsedPlaylistId)) {
            setIsLoading(false)
            setDetail(null)
            setError('EMS playlist id is invalid.')
            return () => controller.abort()
        }

        setIsLoading(true)
        setError(null)

        fetchEmsCollectedPlaylistDetail(parsedPlaylistId, controller.signal)
            .then((response) => {
                startTransition(() => {
                    setDetail(response)
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
                        : 'Unable to load EMS playlist detail.'
                startTransition(() => {
                    setDetail(null)
                    setError(message)
                })
            })
            .finally(() => setIsLoading(false))

        return () => controller.abort()
    }, [playlistId])

    const playbackItems = useMemo(
        () => detail?.tracks.map((track) => toEmsTrackPlaybackItem(track, detail.playlist.title)) ?? [],
        [detail],
    )

    const handlePlayAll = () => {
        if (playbackItems.length > 0) {
            void playQueue(playbackItems, 0)
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

    const handleLikeTrack = (track: EmsCollectionPlaylistDetailResponse['tracks'][number]) => {
        if (!session?.userId || likedTrackIds.has(track.id) || likePendingTrackId !== null) {
            return
        }
        setLikePendingTrackId(track.id)
        void recordUserMusicEvent({
            user_id: session.userId,
            event_type: 'track_saved',
            source_space: 'ems',
            source_platform: track.source_platform,
            item_id: String(track.id),
            item_kind: 'track',
            track_id: String(track.id),
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
                setLikedTrackIds((prev) => {
                    const next = new Set(prev)
                    next.add(track.id)
                    return next
                })
            })
            .catch(() => undefined)
            .finally(() => setLikePendingTrackId(null))
    }

    if (isLoading) {
        return (
            <HudCard title="Playlist Detail" subtitle="Loading EMS playlist">
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
                    {error ?? 'EMS playlist detail was not found.'}
                </div>
            </div>
        )
    }

    const { playlist } = detail
    const coverage = playlist.audio_feature_coverage

    return (
        <div className="space-y-6">
            <div className="flex items-center gap-3">
                <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                    <ArrowLeft size={18} />
                    Back
                </Button>
                <span className="rounded-lg border border-hud-border-primary px-4 py-2 text-sm font-medium text-hud-accent-primary">
                    EMS
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
                                    {detail.tracks.length} tracks
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {coverage.filled_track_count}/{coverage.track_count} audio
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {playlist.collection_source}
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
                                disabled={playbackItems.length === 0 || playbackLoading}
                                glow
                            >
                                <Play size={18} />
                                Play All
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
                subtitle={`${playbackItems.length} stored tracks`}
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
                        const playbackStatusLabel = hasNativePlaybackTarget ? `${track.source_platform} ready` : 'TIDAL searchable'
                        const audioFeatureStatusLabel = track.audio_features.audio_features_filled
                            ? `audio ${Math.round((track.audio_features.energy ?? 0) * 100)} energy`
                            : `audio ${track.audio_features.audio_feature_source}`

                        return (
                            <div
                                key={track.id}
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
                                    <p className={`mt-1 truncate text-xs uppercase tracking-[0.2em] ${track.audio_features.audio_features_filled ? 'text-hud-accent-primary' : 'text-hud-text-muted'}`}>
                                        {audioFeatureStatusLabel}
                                    </p>
                                </div>

                                <div className="flex items-center justify-between gap-3 md:justify-end">
                                    <span className="w-12 text-sm text-hud-text-muted">
                                        {durationLabel ?? '--:--'}
                                    </span>
                                    <button
                                        type="button"
                                        onClick={() => handleLikeTrack(track)}
                                        disabled={!session?.userId || likedTrackIds.has(track.id) || likePendingTrackId === track.id}
                                        className={`flex h-10 w-10 items-center justify-center rounded-lg transition-hud hover:bg-hud-bg-secondary disabled:cursor-not-allowed disabled:opacity-50 ${
                                            likedTrackIds.has(track.id)
                                                ? 'text-hud-accent-primary'
                                                : 'text-hud-text-secondary hover:text-hud-text-primary'
                                        }`}
                                        aria-label={likedTrackIds.has(track.id) ? `Liked ${track.title}` : `Save ${track.title}`}
                                        aria-pressed={likedTrackIds.has(track.id)}
                                    >
                                        <Heart
                                            size={17}
                                            fill={likedTrackIds.has(track.id) ? 'currentColor' : 'none'}
                                        />
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

export default EmsPlaylistDetailPage
