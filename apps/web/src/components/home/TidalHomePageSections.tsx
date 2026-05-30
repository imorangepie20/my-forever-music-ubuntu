import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { ApiError, fetchEmsCollectedPlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

// TIDAL public home-page sources, in display order, with their human titles.
const TIDAL_HOME_SOURCES: { id: string; title: string }[] = [
    { id: 'POPULAR_PLAYLISTS', title: 'Popular Playlists' },
    { id: 'THE_HITS', title: 'The Hits' },
    { id: 'POPULAR_MIXES', title: 'Popular Mixes' },
    { id: 'FROM_OUR_EDITORS', title: 'From our editors' },
]
const PER_SOURCE_LIMIT = 12

type State =
    | { status: 'loading' }
    | { status: 'ready'; bySource: Map<string, EmsCollectionPlaylistItem[]> }
    | { status: 'empty' }

const TidalPlaylistCard = ({ playlist }: { playlist: EmsCollectionPlaylistItem }) => (
    <Link
        to={`/playlists/ems/${playlist.id}`}
        className="group flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
    >
        <div className="relative aspect-square overflow-hidden">
            <MusicArtwork imageUrl={playlist.cover_image_url} seed={`tidal-${playlist.external_playlist_id}`} label={playlist.title} />
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
)

/**
 * All TIDAL public home-page sources (Popular Playlists, The Hits, Popular Mixes, From our
 * editors), each rendered as its own section. Collected by the EMS discovery job into the
 * public_pool, grouped here by their source id (stored as search_query).
 */
const TidalHomePageSections = () => {
    const [state, setState] = useState<State>({ status: 'loading' })

    useEffect(() => {
        const controller = new AbortController()
        setState({ status: 'loading' })

        fetchEmsCollectedPlaylists('tidal', controller.signal, 200, false)
            .then((response) => {
                if (controller.signal.aborted) {
                    return
                }
                const bySource = new Map<string, EmsCollectionPlaylistItem[]>()
                for (const playlist of response.playlists) {
                    if (playlist.collection_source !== 'public_pool' || !playlist.search_query) {
                        continue
                    }
                    const list = bySource.get(playlist.search_query) ?? []
                    if (list.length < PER_SOURCE_LIMIT) {
                        list.push(playlist)
                        bySource.set(playlist.search_query, list)
                    }
                }
                setState(bySource.size > 0 ? { status: 'ready', bySource } : { status: 'empty' })
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
                <h2 className="text-lg font-semibold text-hud-text-primary">Popular playlists on TIDAL</h2>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                    {Array.from({ length: 6 }).map((_, index) => (
                        <div key={index} className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60" />
                    ))}
                </div>
            </section>
        )
    }

    if (state.status === 'empty') {
        return null
    }

    const sections = TIDAL_HOME_SOURCES.map((source) => ({
        ...source,
        playlists: state.bySource.get(source.id) ?? [],
    })).filter((section) => section.playlists.length > 0)

    if (sections.length === 0) {
        return null
    }

    return (
        <div className="space-y-8">
            {sections.map((section) => (
                <section key={section.id} className="space-y-4">
                    <header className="flex items-baseline justify-between">
                        <h2 className="text-lg font-semibold text-hud-text-primary">{section.title}</h2>
                        <span className="text-xs text-hud-text-muted">on TIDAL</span>
                    </header>
                    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                        {section.playlists.map((playlist) => (
                            <TidalPlaylistCard key={playlist.id} playlist={playlist} />
                        ))}
                    </div>
                </section>
            ))}
        </div>
    )
}

export default TidalHomePageSections
