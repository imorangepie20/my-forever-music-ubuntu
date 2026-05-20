import { useNavigate } from 'react-router-dom'
import { Play } from 'lucide-react'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useLatestTracks } from '@/hooks/useLatestTracks'
import type { PlaybackMediaItem } from '@/lib/musicPlayback'
import type { HeroTrackResponse } from '@/types/api'

const LATEST_LIMIT = 10

const toPlaybackItem = (track: HeroTrackResponse): PlaybackMediaItem => ({
    id: track.spotify_track_id ?? track.external_track_id,
    kind: 'track',
    title: track.title,
    subtitle: track.album_title ? `${track.artist_name} · ${track.album_title}` : track.artist_name,
    sourcePlatform: track.source_platform,
    spotifyTrackId: track.spotify_track_id,
    externalTrackId: track.external_track_id,
    imageUrl: track.image_url,
    albumTitle: track.album_title,
    externalUrl: track.platform_external_url,
    durationMs: track.duration_ms,
    previewUrl: track.preview_url,
})

const LatestTracksSection = () => {
    const { session } = useAuthSession()
    const navigate = useNavigate()
    const playback = usePlayback()
    const state = useLatestTracks(LATEST_LIMIT)

    if (state.status === 'loading') {
        return (
            <section className="space-y-3">
                <header className="flex items-baseline justify-between">
                    <h2 className="text-lg font-semibold text-hud-text-primary">Latest tracks</h2>
                    <span className="text-xs text-hud-text-muted">Loading…</span>
                </header>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                    {Array.from({ length: LATEST_LIMIT }).map((_, index) => (
                        <div
                            key={index}
                            className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60"
                        />
                    ))}
                </div>
            </section>
        )
    }

    if (state.status === 'empty' || state.status === 'error') {
        return null
    }

    const handlePlay = (track: HeroTrackResponse) => {
        if (!session?.userId) {
            navigate('/signin')
            return
        }
        void playback.playItem(toPlaybackItem(track)).catch(() => undefined)
    }

    return (
        <section className="space-y-4">
            <header className="flex items-baseline justify-between">
                <h2 className="text-lg font-semibold text-hud-text-primary">Latest tracks</h2>
                <span className="text-xs text-hud-text-muted">{state.tracks.length} editorial picks</span>
            </header>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
                {state.tracks.map((track) => {
                    const trackKey = `${track.source_platform}-${track.external_track_id}`
                    return (
                        <div
                            key={trackKey}
                            role="button"
                            tabIndex={0}
                            onClick={() => handlePlay(track)}
                            onKeyDown={(event) => {
                                if (event.key === 'Enter' || event.key === ' ') {
                                    event.preventDefault()
                                    handlePlay(track)
                                }
                            }}
                            className="group relative flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 text-left transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
                        >
                            <div className="relative aspect-square overflow-hidden">
                                <MusicArtwork
                                    imageUrl={track.image_url}
                                    seed={trackKey}
                                    label={track.title}
                                />
                                <div className="absolute inset-0 flex items-center justify-center bg-black/40 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                                    <span className="flex h-12 w-12 items-center justify-center rounded-full bg-white text-black shadow-md">
                                        <Play size={18} className="translate-x-0.5" />
                                    </span>
                                </div>
                            </div>
                            <div className="space-y-1 p-3">
                                <p className="truncate text-sm font-semibold text-hud-text-primary">{track.title}</p>
                                <ArtistDetailLink
                                    artistName={track.artist_name}
                                    stopPropagation
                                    className="block truncate text-xs text-hud-text-secondary transition-hud hover:text-hud-accent-primary"
                                />
                            </div>
                        </div>
                    )
                })}
            </div>
        </section>
    )
}

export default LatestTracksSection
