import { useState } from 'react'
import { ExternalLink, Loader2, Play } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { ApiError, resolveMelonHotTrack } from '@/services/api'
import type { MelonChartTrack } from '@/types/api'

interface MelonChartRowProps {
    track: MelonChartTrack
    compact?: boolean
}

const MelonChartRow = ({ track, compact = false }: MelonChartRowProps) => {
    const { session } = useAuthSession()
    const playback = usePlayback()
    const navigate = useNavigate()
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const handlePlay = async () => {
        if (!session?.userId) {
            navigate('/signin')
            return
        }
        setLoading(true)
        setError(null)
        try {
            const resolved = await resolveMelonHotTrack(track.rank, session.userId)
            const matchedPlatform = resolved.resolved ? resolved.source_platform : null
            const matchedExternalTrackId = matchedPlatform === 'tidal'
                ? resolved.tidal_track_id
                : matchedPlatform === 'spotify'
                    ? resolved.spotify_track_id
                    : null
            const useYouTubeFallback = !matchedPlatform || !matchedExternalTrackId
            await playback.playItem({
                id: matchedExternalTrackId ?? `melon-${track.rank}`,
                kind: 'track',
                title: resolved.resolved_title ?? track.title,
                subtitle: resolved.resolved_album_title
                    ? `${resolved.resolved_artist_name ?? track.artist_name} · ${resolved.resolved_album_title}`
                    : resolved.resolved_artist_name ?? track.artist_name,
                sourcePlatform: useYouTubeFallback ? 'youtube' : matchedPlatform,
                playbackPlatformId: useYouTubeFallback ? 'youtube' : matchedPlatform,
                spotifyTrackId: resolved.spotify_track_id,
                tidalTrackId: resolved.tidal_track_id,
                externalTrackId: matchedExternalTrackId,
                platformUri: resolved.tidal_uri,
                imageUrl: resolved.image_url ?? track.image_url,
                albumTitle: resolved.resolved_album_title,
                externalUrl: resolved.external_url,
            })
        } catch (cause) {
            const message = cause instanceof ApiError
                ? cause.message
                : cause instanceof Error
                    ? cause.message
                    : 'Unable to resolve Melon track.'
            setError(message)
        } finally {
            setLoading(false)
        }
    }

    return (
        <div
            className={`group flex items-center gap-3 rounded-${compact ? 'xl' : 'lg'} border border-hud-border-secondary bg-hud-bg-primary/70 px-3 ${compact ? 'py-2' : 'py-2.5'} transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90`}
        >
            <span className={`${compact ? 'w-7 text-xs' : 'w-10 text-sm'} text-center font-mono text-hud-text-muted`}>
                {track.rank}
            </span>
            <div className="h-10 w-10 shrink-0 overflow-hidden rounded-md border border-hud-border-secondary bg-hud-bg-primary">
                <MusicArtwork
                    imageUrl={track.image_url}
                    seed={`melon-${track.rank}`}
                    label={track.title}
                />
            </div>
            <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-hud-text-primary">{track.title}</p>
                <p className="truncate text-xs text-hud-text-secondary">
                    <ArtistDetailLink
                        artistName={track.artist_name}
                        className="transition-hud hover:text-hud-accent-primary"
                    />
                    {!compact && track.album_title ? <span className="text-hud-text-muted"> · {track.album_title}</span> : null}
                </p>
                {error && (
                    <p className="mt-0.5 truncate text-[11px] text-amber-300">{error}</p>
                )}
            </div>
            <button
                type="button"
                onClick={() => void handlePlay()}
                disabled={loading}
                aria-label="Play"
                title={session?.userId ? 'Play' : 'Sign in to play'}
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-hud-border-secondary text-hud-text-secondary transition-hud hover:border-hud-accent-primary hover:text-hud-accent-primary disabled:cursor-not-allowed disabled:opacity-50"
            >
                {loading ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} className="translate-x-0.5" />}
            </button>
            <a
                href={track.song_external_url ?? '#'}
                target="_blank"
                rel="noreferrer noopener"
                aria-label="Open in Melon"
                title="Open in Melon"
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-hud-border-secondary text-hud-text-muted transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
            >
                <ExternalLink size={14} />
            </a>
        </div>
    )
}

export default MelonChartRow
