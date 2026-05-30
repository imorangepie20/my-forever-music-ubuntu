import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { ApiError, fetchEmsCollectedPlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

const LIMIT = 6

type State =
    | { status: 'loading' }
    | { status: 'ready'; playlists: EmsCollectionPlaylistItem[] }
    | { status: 'empty' }

/**
 * "Popular playlists on TIDAL" — collected from the public TIDAL home-page sources by the EMS
 * discovery job. Renders nothing until the first discovery run has populated TIDAL playlists.
 */
const PopularTidalPlaylistsSection = () => {
    const [state, setState] = useState<State>({ status: 'loading' })

    useEffect(() => {
        const controller = new AbortController()
        setState({ status: 'loading' })

        fetchEmsCollectedPlaylists('tidal', controller.signal, LIMIT)
            .then((response) => {
                if (controller.signal.aborted) {
                    return
                }
                setState(
                    response.playlists.length > 0
                        ? { status: 'ready', playlists: response.playlists }
                        : { status: 'empty' },
                )
            })
            .catch((error: unknown) => {
                if (error instanceof DOMException && error.name === 'AbortError') {
                    return
                }
                if (!(error instanceof ApiError) && !(error instanceof Error)) {
                    // ignore
                }
                setState({ status: 'empty' })
            })

        return () => controller.abort()
    }, [])

    if (state.status === 'loading') {
        return (
            <section className="space-y-3">
                <header className="flex items-baseline justify-between">
                    <h2 className="text-lg font-semibold text-hud-text-primary">Popular playlists on TIDAL</h2>
                    <span className="text-xs text-hud-text-muted">Loading…</span>
                </header>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                    {Array.from({ length: LIMIT }).map((_, index) => (
                        <div
                            key={index}
                            className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60"
                        />
                    ))}
                </div>
            </section>
        )
    }

    if (state.status === 'empty') {
        return null
    }

    return (
        <section className="space-y-4">
            <header className="flex items-baseline justify-between">
                <h2 className="text-lg font-semibold text-hud-text-primary">Popular playlists on TIDAL</h2>
                <span className="text-xs text-hud-text-muted">TIDAL 공개 편집 플레이리스트</span>
            </header>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                {state.playlists.map((playlist) => (
                    <Link
                        key={playlist.id}
                        to={`/playlists/ems/${playlist.id}`}
                        className="group flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
                    >
                        <div className="relative aspect-square overflow-hidden">
                            <MusicArtwork
                                imageUrl={playlist.cover_image_url}
                                seed={`tidal-${playlist.external_playlist_id}`}
                                label={playlist.title}
                            />
                        </div>
                        <div className="space-y-1 p-3">
                            <p className="truncate text-sm font-semibold text-hud-text-primary">{playlist.title}</p>
                            <p className="truncate text-xs text-hud-text-secondary">{playlist.curator || 'TIDAL'}</p>
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

export default PopularTidalPlaylistsSection
