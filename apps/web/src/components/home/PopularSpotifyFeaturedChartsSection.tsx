import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { fetchEmsCollectedPlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

const LIMIT = 4
const SOURCE = 'spotify_featured_charts'

type State =
    | { status: 'loading' }
    | { status: 'ready'; playlists: EmsCollectionPlaylistItem[] }
    | { status: 'empty' }

const PopularSpotifyFeaturedChartsSection = () => {
    const [state, setState] = useState<State>({ status: 'loading' })

    useEffect(() => {
        const controller = new AbortController()

        fetchEmsCollectedPlaylists('spotify', controller.signal, 50, false)
            .then((response) => {
                if (controller.signal.aborted) return
                const playlists = response.playlists
                    .filter((playlist) => playlist.collection_source === SOURCE)
                    .slice(0, LIMIT)
                setState(playlists.length > 0 ? { status: 'ready', playlists } : { status: 'empty' })
            })
            .catch(() => {
                if (!controller.signal.aborted) setState({ status: 'empty' })
            })

        return () => controller.abort()
    }, [])

    if (state.status === 'empty') return null

    return (
        <section className="space-y-4">
            <header className="flex items-baseline justify-between">
                <h2 className="text-lg font-semibold text-hud-text-primary">Spotify Featured Charts</h2>
                <span className="text-xs text-hud-text-muted">
                    {state.status === 'loading' ? 'Loading...' : 'Spotify 공개 차트 플레이리스트'}
                </span>
            </header>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {state.status === 'loading'
                    ? Array.from({ length: LIMIT }).map((_, index) => (
                        <div
                            key={index}
                            className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60"
                        />
                    ))
                    : state.playlists.map((playlist) => (
                        <Link
                            key={playlist.id}
                            to={`/playlists/ems/${playlist.id}`}
                            className="group flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
                        >
                            <div className="relative aspect-square overflow-hidden">
                                <MusicArtwork
                                    imageUrl={playlist.cover_image_url}
                                    seed={`spotify-${playlist.external_playlist_id}`}
                                    label={playlist.title}
                                />
                            </div>
                            <div className="space-y-1 p-3">
                                <p className="truncate text-sm font-semibold text-hud-text-primary">{playlist.title}</p>
                                <p className="truncate text-xs text-hud-text-secondary">{playlist.curator || 'Spotify'}</p>
                                <p className="flex items-center gap-1 text-[11px] text-hud-text-muted">
                                    <ListMusic size={12} />
                                    {playlist.track_count} tracks
                                </p>
                            </div>
                        </Link>
                    ))}
            </div>
        </section>
    )
}

export default PopularSpotifyFeaturedChartsSection
