import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight, ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { fetchEmsTidalHomePlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

const TIDAL_HOME_SOURCES = [
    { id: 'POPULAR_PLAYLISTS', title: 'Popular Playlists' },
    { id: 'THE_HITS', title: 'The Hits' },
    { id: 'POPULAR_MIXES', title: 'Popular Mixes' },
    { id: 'FROM_OUR_EDITORS', title: 'From our editors' },
]
const PAGE_SIZE = 12

type SourceState = {
    status: 'loading' | 'ready' | 'empty' | 'error'
    totalPages: number
    playlists: EmsCollectionPlaylistItem[]
}

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

const PageButton = ({
    label,
    disabled,
    onClick,
    children,
}: {
    label: string
    disabled: boolean
    onClick: () => void
    children: ReactNode
}) => (
    <button
        type="button"
        title={label}
        aria-label={label}
        disabled={disabled}
        onClick={onClick}
        className="grid h-8 w-8 place-items-center rounded-lg border border-hud-border-secondary text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary disabled:cursor-not-allowed disabled:opacity-35"
    >
        {children}
    </button>
)

const TidalHomePageSection = ({ source }: { source: { id: string; title: string } }) => {
    const [page, setPage] = useState(0)
    const [state, setState] = useState<SourceState>({
        status: 'loading',
        totalPages: 0,
        playlists: [],
    })

    useEffect(() => {
        const controller = new AbortController()
        setState((current) => ({ ...current, status: 'loading' }))
        fetchEmsTidalHomePlaylists(source.id, page, PAGE_SIZE, controller.signal)
            .then((response) => {
                if (controller.signal.aborted) return
                setState({
                    status: response.playlists.length > 0 ? 'ready' : 'empty',
                    totalPages: response.total_pages,
                    playlists: response.playlists,
                })
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    setState({ status: 'error', totalPages: 0, playlists: [] })
                }
            })
        return () => controller.abort()
    }, [page, source.id])

    if (state.status === 'empty') return null

    return (
        <section className="space-y-4">
            <header className="flex min-h-8 items-center justify-between gap-3">
                <div className="flex items-baseline gap-2">
                    <h2 className="text-lg font-semibold text-hud-text-primary">{source.title}</h2>
                    <span className="text-xs text-hud-text-muted">on TIDAL</span>
                </div>
                {state.totalPages > 1 && (
                    <div className="flex items-center gap-2">
                        <PageButton label="이전 페이지" disabled={page === 0} onClick={() => setPage((value) => value - 1)}>
                            <ChevronLeft size={16} />
                        </PageButton>
                        <span className="min-w-12 text-center text-xs text-hud-text-muted">{page + 1} / {state.totalPages}</span>
                        <PageButton
                            label="다음 페이지"
                            disabled={page + 1 >= state.totalPages}
                            onClick={() => setPage((value) => value + 1)}
                        >
                            <ChevronRight size={16} />
                        </PageButton>
                    </div>
                )}
            </header>
            {state.status === 'error' ? (
                <p className="text-sm text-hud-text-secondary">저장된 TIDAL 플레이리스트 목록을 불러오지 못했습니다.</p>
            ) : (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
                    {state.status === 'loading'
                        ? Array.from({ length: 6 }).map((_, index) => (
                            <div key={index} className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60" />
                        ))
                        : state.playlists.map((playlist) => <TidalPlaylistCard key={playlist.id} playlist={playlist} />)}
                </div>
            )}
        </section>
    )
}

const TidalHomePageSections = () => (
    <div className="space-y-8">
        {TIDAL_HOME_SOURCES.map((source) => <TidalHomePageSection key={source.id} source={source} />)}
    </div>
)

export default TidalHomePageSections
