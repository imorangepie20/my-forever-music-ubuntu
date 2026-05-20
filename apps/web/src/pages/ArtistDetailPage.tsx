import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { ExternalLink, Loader2, Music2, Play, Radio, Search } from 'lucide-react'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { readableArtistNameFromSlug } from '@/lib/artistLinks'
import type { PlaybackMediaItem } from '@/lib/musicPlayback'
import { ApiError, fetchArtistDetail } from '@/services/api'
import type { ArtistDetailResponse, ArtistDetailTrack } from '@/types/api'

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }
    window.open(url, '_blank', 'noopener,noreferrer')
}

const toPlaybackItem = (track: ArtistDetailTrack, sourceLabel: string): PlaybackMediaItem => ({
    id: `${sourceLabel}:${track.source_platform}:${track.track_id}`,
    kind: 'track',
    title: track.title,
    subtitle: `${track.artist_name} · ${track.source_platform}`,
    sourcePlatform: track.source_platform,
    playbackPlatformId: track.source_platform,
    externalTrackId: track.track_id,
    imageUrl: track.album_image_url,
    albumTitle: track.album_title,
    externalUrl: track.platform_external_url,
    platformUri: track.platform_uri,
    previewUrl: track.preview_url,
    isrc: track.isrc,
    durationMs: track.duration_ms,
    supportingText: track.collection_source,
})

const ArtistDetailPage = () => {
    const { artistSlug } = useParams()
    const [searchParams] = useSearchParams()
    const requestedName = searchParams.get('name') ?? readableArtistNameFromSlug(artistSlug)
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const activeUserId = session?.userId || workspace.userId || undefined
    const { playItem, playQueue } = usePlayback()
    const [detail, setDetail] = useState<ArtistDetailResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()
        setIsLoading(true)
        setError(null)

        fetchArtistDetail(artistSlug ?? '', requestedName, activeUserId, controller.signal)
            .then(setDetail)
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load artist detail.'
                setDetail(null)
                setError(message)
            })
            .finally(() => setIsLoading(false))

        return () => controller.abort()
    }, [activeUserId, artistSlug, requestedName])

    const allTracks = useMemo(
        () => detail ? [...detail.pms_tracks, ...detail.ems_tracks] : [],
        [detail],
    )

    const playTracks = (tracks: ArtistDetailTrack[], sourceLabel: string) => {
        const items = tracks.map((track) => toPlaybackItem(track, sourceLabel))
        if (items.length === 1) {
            void playItem(items[0])
            return
        }
        if (items.length > 1) {
            void playQueue(items, 0)
        }
    }

    if (isLoading) {
        return (
            <div className="flex min-h-[50vh] items-center justify-center text-hud-text-secondary">
                <Loader2 size={24} className="mr-3 animate-spin" />
                Loading artist
            </div>
        )
    }

    if (error) {
        return (
            <HudCard title="Artist Detail" subtitle="Unable to load this artist">
                <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-5 text-sm leading-6 text-hud-text-secondary">
                    {error}
                </div>
            </HudCard>
        )
    }

    if (!detail) {
        return (
            <HudCard title="Artist Detail" subtitle="No artist data was returned">
                <Link to="/ems" className="text-sm font-medium text-hud-accent-primary">Search EMS</Link>
            </HudCard>
        )
    }

    return (
        <div className="space-y-6">
            <section className="overflow-hidden rounded-[28px] border border-hud-border-secondary bg-hud-bg-primary/75">
                <div className="grid gap-0 lg:grid-cols-[320px_minmax(0,1fr)]">
                    <div className="aspect-square lg:aspect-auto">
                        <MusicArtwork
                            imageUrl={detail.artist.image_url}
                            seed={detail.artist.display_name}
                            label={detail.artist.display_name}
                        />
                    </div>
                    <div className="space-y-6 p-6 lg:p-8">
                        <div className="flex flex-wrap gap-2">
                            <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                Artist
                            </span>
                            <span className="rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-accent-primary">
                                PMS taste anchor
                            </span>
                        </div>
                        <div>
                            <h1 className="text-4xl font-semibold text-hud-text-primary md:text-5xl">
                                {detail.artist.display_name}
                            </h1>
                            <p className="mt-3 max-w-3xl text-sm leading-6 text-hud-text-secondary">
                                {detail.artist.match_reason}
                            </p>
                        </div>
                        <div className="grid gap-3 sm:grid-cols-4">
                            <Metric label="PMS Tracks" value={detail.summary.pms_track_count} />
                            <Metric label="EMS Tracks" value={detail.summary.ems_track_count} />
                            <Metric label="Total" value={detail.summary.total_track_count} />
                            <Metric label="Sources" value={detail.summary.platform_candidate_count} />
                        </div>
                        <div className="flex flex-wrap gap-3">
                            <Button
                                type="button"
                                variant="primary"
                                disabled={allTracks.length === 0}
                                onClick={() => playTracks(allTracks, 'artist')}
                            >
                                <Play size={18} />
                                Play Artist Tracks
                            </Button>
                            <Link to={`/ems?q=${encodeURIComponent(detail.artist.display_name)}&playlist_page=1&track_page=1`}>
                                <Button type="button" variant="ghost">
                                    <Search size={18} />
                                    Search EMS
                                </Button>
                            </Link>
                        </div>
                    </div>
                </div>
            </section>

            <HudCard title="Platform Candidates" subtitle="Provider signals inferred from stored PMS and EMS tracks">
                {detail.platform_candidates.length > 0 ? (
                    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                        {detail.platform_candidates.map((candidate) => (
                            <div
                                key={`${candidate.provider}-${candidate.match_source}`}
                                className="rounded-[20px] border border-hud-border-secondary bg-hud-bg-primary/70 p-4"
                            >
                                <div className="flex items-center gap-3">
                                    <div className="size-12 overflow-hidden rounded-2xl">
                                        <MusicArtwork
                                            imageUrl={candidate.image_url}
                                            seed={`${candidate.provider}-${candidate.name}`}
                                            label={candidate.name}
                                        />
                                    </div>
                                    <div className="min-w-0">
                                        <p className="truncate text-sm font-semibold text-hud-text-primary">{candidate.provider}</p>
                                        <p className="truncate text-xs text-hud-text-muted">{candidate.match_source}</p>
                                    </div>
                                </div>
                                {candidate.external_url && (
                                    <Button
                                        type="button"
                                        variant="ghost"
                                        size="sm"
                                        onClick={() => openExternal(candidate.external_url)}
                                        className="mt-4"
                                    >
                                        <ExternalLink size={15} />
                                        Open
                                    </Button>
                                )}
                            </div>
                        ))}
                    </div>
                ) : (
                    <EmptyState icon={<Radio size={18} />} text="No provider candidates yet." />
                )}
            </HudCard>

            <TrackSection
                title="PMS Library Tracks"
                subtitle="Tracks already inside the user's long-term music space"
                tracks={detail.pms_tracks}
                sourceLabel="pms"
                onPlayTracks={playTracks}
            />

            <TrackSection
                title="EMS Discovery Tracks"
                subtitle="External collection tracks that can expand this artist context"
                tracks={detail.ems_tracks}
                sourceLabel="ems"
                onPlayTracks={playTracks}
            />
        </div>
    )
}

const Metric = ({ label, value }: { label: string; value: number }) => (
    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/70 p-4">
        <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">{value}</p>
    </div>
)

const EmptyState = ({ icon, text }: { icon: ReactNode; text: string }) => (
    <div className="flex items-center gap-2 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm text-hud-text-secondary">
        {icon}
        {text}
    </div>
)

const TrackSection = ({
    title,
    subtitle,
    tracks,
    sourceLabel,
    onPlayTracks,
}: {
    title: string
    subtitle: string
    tracks: ArtistDetailTrack[]
    sourceLabel: string
    onPlayTracks: (tracks: ArtistDetailTrack[], sourceLabel: string) => void
}) => (
    <HudCard
        title={title}
        subtitle={subtitle}
        action={
            tracks.length > 0 ? (
                <Button type="button" variant="ghost" size="sm" onClick={() => onPlayTracks(tracks, sourceLabel)}>
                    <Play size={15} />
                    Play Section
                </Button>
            ) : null
        }
    >
        {tracks.length > 0 ? (
            <div className="divide-y divide-hud-border-secondary overflow-hidden rounded-[20px] border border-hud-border-secondary">
                {tracks.map((track) => (
                    <div key={`${sourceLabel}-${track.track_id}`} className="grid gap-4 bg-hud-bg-primary/60 p-4 md:grid-cols-[72px_minmax(0,1fr)_auto] md:items-center">
                        <div className="size-[72px] overflow-hidden rounded-2xl">
                            <MusicArtwork imageUrl={track.album_image_url} seed={`${track.artist_name}-${track.title}`} label={track.title} />
                        </div>
                        <div className="min-w-0">
                            <p className="truncate text-base font-semibold text-hud-text-primary">{track.title}</p>
                            <p className="mt-1 truncate text-sm text-hud-text-secondary">
                                {track.album_title ?? track.source_platform}
                            </p>
                            <div className="mt-2 flex flex-wrap gap-2">
                                <span className="rounded-full border border-hud-border-secondary px-2.5 py-1 text-[10px] uppercase tracking-[0.18em] text-hud-text-muted">
                                    {track.source_platform}
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-2.5 py-1 text-[10px] uppercase tracking-[0.18em] text-hud-text-muted">
                                    {track.collection_source}
                                </span>
                            </div>
                        </div>
                        <div className="flex flex-wrap gap-2 md:justify-end">
                            <Button type="button" variant="primary" size="sm" onClick={() => onPlayTracks([track], sourceLabel)}>
                                <Play size={15} />
                                Play
                            </Button>
                            {track.platform_external_url && (
                                <Button type="button" variant="ghost" size="sm" onClick={() => openExternal(track.platform_external_url)}>
                                    <ExternalLink size={15} />
                                    Open
                                </Button>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        ) : (
            <EmptyState icon={<Music2 size={18} />} text="No tracks in this section yet." />
        )}
    </HudCard>
)

export default ArtistDetailPage
