import { startTransition, useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ExternalLink, ListMusic, Play, RefreshCw, Search, Sparkles, Tags } from 'lucide-react'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import MusicArtwork from '@/components/music/MusicArtwork'
import PlaylistFeatureCard from '@/components/music/PlaylistFeatureCard'
import TrackFeatureCard from '@/components/music/TrackFeatureCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { buildArtistDetailPath } from '@/lib/artistLinks'
import {
    buildEmsPlaylistDetailPath,
    buildEmsSearchPlaylistDetailPath,
    emsSearchPlaylistCacheKey,
    toEmsSearchTrackPlaybackItem,
    toEmsTrackPlaybackItem,
} from '@/lib/emsPlayback'
import {
    ApiError,
    fetchEmsCollectedPlaylistDetail,
    fetchEmsFloSpecial,
    fetchEmsMelonHot100,
    fetchEmsPlaylistSections,
    refreshEmsFloSpecial,
    searchEmsCollection,
} from '@/services/api'
import type {
    EmsCollectionPlaylistItem,
    EmsCollectionPlaylistSection,
    EmsCollectionPlaylistSectionItem,
    EmsCollectionSearchPlaylistItem,
    EmsCollectionSearchResponse,
    EmsFloSpecialSection,
} from '@/types/api'

type DiscoveryPlatformId = string

const defaultDiscoveryPlatformIds: DiscoveryPlatformId[] = ['tidal', 'spotify']
const SEARCH_RESULT_PAGE_SIZE = 12
const SEARCH_CACHE_PREFIX = 'ems-search'
const FLO_SPECIAL_DISPLAY_LIMIT = 120

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }
    window.open(url, '_blank', 'noopener,noreferrer')
}

const formatPercent = (value?: number | null) =>
    `${Math.round((value ?? 0) * 100)}%`

const pageValue = (value: string | null) => {
    const parsed = Number(value)
    return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 1
}

const searchCacheKey = (userId: string, platformId: string, query: string) =>
    `${SEARCH_CACHE_PREFIX}:${userId}:${platformId}:${query.trim().toLowerCase()}`

const writeSearchPlaylistCache = (playlist: EmsCollectionSearchPlaylistItem) => {
    window.sessionStorage.setItem(
        emsSearchPlaylistCacheKey(playlist.source_platform, playlist.external_playlist_id),
        JSON.stringify(playlist),
    )
}

const pageCountFor = (count: number) =>
    Math.max(1, Math.ceil(count / SEARCH_RESULT_PAGE_SIZE))

const EmsPage = () => {
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const { playItem, playQueue } = usePlayback()
    const navigate = useNavigate()
    const [searchParams, setSearchParams] = useSearchParams()
    const activeUserId = session?.userId || workspace.userId
    const activeSearchPlatformId = session?.preferredPlatformId ?? workspace.preferredPlatformId
    const urlQuery = searchParams.get('q')?.trim() ?? ''
    const playlistPage = pageValue(searchParams.get('playlist_page'))
    const trackPage = pageValue(searchParams.get('track_page'))
    const [searchQuery, setSearchQuery] = useState(urlQuery)
    const [searchResult, setSearchResult] = useState<EmsCollectionSearchResponse | null>(null)
    const [isSearching, setIsSearching] = useState(false)
    const [searchError, setSearchError] = useState<string | null>(null)
    const [playlistSections, setPlaylistSections] = useState<EmsCollectionPlaylistSection[]>([])
    const [isPlaylistPersonalized, setIsPlaylistPersonalized] = useState(false)
    const [isLoadingCollection, setIsLoadingCollection] = useState(false)
    const [preparingPlaylistId, setPreparingPlaylistId] = useState<number | null>(null)
    const [collectionError, setCollectionError] = useState<string | null>(null)
    const [floSpecialSections, setFloSpecialSections] = useState<EmsFloSpecialSection[]>([])
    const [isLoadingFloSpecial, setIsLoadingFloSpecial] = useState(false)
    const [isRefreshingFloSpecial, setIsRefreshingFloSpecial] = useState(false)
    const [floSpecialError, setFloSpecialError] = useState<string | null>(null)
    const [melonHot100Playlists, setMelonHot100Playlists] = useState<EmsCollectionPlaylistItem[]>([])
    const [isLoadingMelonHot100, setIsLoadingMelonHot100] = useState(false)
    const [melonHot100Error, setMelonHot100Error] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        setIsLoadingCollection(true)
        setCollectionError(null)

        fetchEmsPlaylistSections({
            userId: activeUserId,
            platformIds: defaultDiscoveryPlatformIds,
            limit: 6,
            signal: controller.signal,
        })
            .then((response) => {
                startTransition(() => {
                    setPlaylistSections(response.sections)
                    setIsPlaylistPersonalized(response.personalized)
                })
            })
            .catch((err: unknown) => {
                if (err instanceof DOMException && err.name === 'AbortError') return
                const message =
                    err instanceof ApiError
                        ? err.message
                        : 'Unable to load public playlist pool.'
                startTransition(() => setCollectionError(message))
            })
            .finally(() => {
                setIsLoadingCollection(false)
            })

        return () => controller.abort()
    }, [activeUserId])

    useEffect(() => {
        const controller = new AbortController()

        setIsLoadingFloSpecial(true)
        setFloSpecialError(null)

        fetchEmsFloSpecial(controller.signal, FLO_SPECIAL_DISPLAY_LIMIT)
            .then((response) => {
                startTransition(() => setFloSpecialSections(response.sections))
            })
            .catch((err: unknown) => {
                if (err instanceof DOMException && err.name === 'AbortError') return
                const message =
                    err instanceof ApiError
                        ? err.message
                        : 'Unable to load FLO Special playlists.'
                startTransition(() => setFloSpecialError(message))
            })
            .finally(() => {
                setIsLoadingFloSpecial(false)
            })

        return () => controller.abort()
    }, [])

    useEffect(() => {
        const controller = new AbortController()

        setIsLoadingMelonHot100(true)
        setMelonHot100Error(null)

        fetchEmsMelonHot100(controller.signal)
            .then((response) => {
                startTransition(() => setMelonHot100Playlists(response.playlists))
            })
            .catch((err: unknown) => {
                if (err instanceof DOMException && err.name === 'AbortError') return
                const message =
                    err instanceof ApiError
                        ? err.message
                        : 'Unable to load Melon Hot 100 from EMS.'
                startTransition(() => setMelonHot100Error(message))
            })
            .finally(() => {
                setIsLoadingMelonHot100(false)
            })

        return () => controller.abort()
    }, [])

    useEffect(() => {
        setSearchQuery(urlQuery)
        if (!urlQuery) {
            setSearchResult(null)
            setSearchError(null)
            return
        }
        if (!activeUserId) {
            setSearchError('Sign in before searching provider results.')
            return
        }

        const cacheKey = searchCacheKey(activeUserId, activeSearchPlatformId, urlQuery)
        const cached = window.sessionStorage.getItem(cacheKey)
        if (cached) {
            try {
                setSearchResult(JSON.parse(cached) as EmsCollectionSearchResponse)
                setSearchError(null)
                return
            } catch {
                window.sessionStorage.removeItem(cacheKey)
            }
        }

        let isCurrent = true
        setIsSearching(true)
        setSearchError(null)
        searchEmsCollection({
            user_id: activeUserId,
            query: urlQuery,
        })
            .then((response) => {
                if (!isCurrent) {
                    return
                }
                window.sessionStorage.setItem(cacheKey, JSON.stringify(response))
                startTransition(() => setSearchResult(response))
            })
            .catch((requestError: unknown) => {
                if (!isCurrent) {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to search EMS provider results.'
                setSearchResult(null)
                setSearchError(message)
            })
            .finally(() => {
                if (isCurrent) {
                    setIsSearching(false)
                }
            })

        return () => {
            isCurrent = false
        }
    }, [activeSearchPlatformId, activeUserId, urlQuery])

    const playlistPageCount = pageCountFor(searchResult?.playlists.length ?? 0)
    const trackPageCount = pageCountFor(searchResult?.tracks.length ?? 0)
    const safePlaylistPage = Math.min(playlistPage, playlistPageCount)
    const safeTrackPage = Math.min(trackPage, trackPageCount)
    const pagedSearchPlaylists = useMemo(
        () => searchResult?.playlists.slice((safePlaylistPage - 1) * SEARCH_RESULT_PAGE_SIZE, safePlaylistPage * SEARCH_RESULT_PAGE_SIZE) ?? [],
        [safePlaylistPage, searchResult],
    )
    const pagedSearchTracks = useMemo(
        () => searchResult?.tracks.slice((safeTrackPage - 1) * SEARCH_RESULT_PAGE_SIZE, safeTrackPage * SEARCH_RESULT_PAGE_SIZE) ?? [],
        [safeTrackPage, searchResult],
    )

    const updateSearchPage = (key: 'playlist_page' | 'track_page', page: number) => {
        const nextParams = new URLSearchParams(searchParams)
        nextParams.set(key, String(page))
        setSearchParams(nextParams)
    }

    const handleSearchSubmit = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const trimmedQuery = searchQuery.trim()
        if (!trimmedQuery) {
            return
        }
        setSearchParams({
            q: trimmedQuery,
            playlist_page: '1',
            track_page: '1',
        })
    }

    const handlePlayEmsPlaylist = async (playlist: EmsCollectionPlaylistItem) => {
        setCollectionError(null)
        setFloSpecialError(null)
        setPreparingPlaylistId(playlist.id)

        try {
            const detail = await fetchEmsCollectedPlaylistDetail(playlist.id)
            const playbackItems = detail.tracks.map((track) => toEmsTrackPlaybackItem(track, detail.playlist.title))
            if (playbackItems.length === 0) {
                setCollectionError('EMS playlist has no stored tracks to play.')
                return
            }

            await playQueue(playbackItems, 0)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to load EMS playlist tracks for playback.'
            setCollectionError(message)
        } finally {
            setPreparingPlaylistId(null)
        }
    }

    const handleRefreshFloSpecial = async () => {
        setIsRefreshingFloSpecial(true)
        setFloSpecialError(null)

        try {
            const refreshResult = await refreshEmsFloSpecial()
            if (refreshResult.status === 'failed') {
                setFloSpecialError(refreshResult.message || 'FLO Special refresh failed.')
                return
            }
            const response = await fetchEmsFloSpecial(undefined, FLO_SPECIAL_DISPLAY_LIMIT)
            startTransition(() => setFloSpecialSections(response.sections))
            if (refreshResult.failures.length > 0) {
                setFloSpecialError(refreshResult.message)
            }
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to refresh FLO Special playlists.'
            setFloSpecialError(message)
        } finally {
            setIsRefreshingFloSpecial(false)
        }
    }

    const openSearchPlaylistDetail = (playlist: EmsCollectionSearchPlaylistItem) => {
        writeSearchPlaylistCache(playlist)
        navigate(buildEmsSearchPlaylistDetailPath(playlist.source_platform, playlist.external_playlist_id))
    }

    return (
        <div className="space-y-6">
            <HudCard
                title="EMS Search"
                subtitle="Search connected providers, inspect playlist tracks, and play from the result page"
                action={
                    isSearching ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            Searching
                        </span>
                    ) : null
                }
            >
                <div className="space-y-5">
                    <form className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_auto]" onSubmit={handleSearchSubmit}>
                        <label className="sr-only" htmlFor="ems-search-query">Search query</label>
                        <input
                            id="ems-search-query"
                            value={searchQuery}
                            onChange={(event) => setSearchQuery(event.target.value)}
                            placeholder="Search playlists or tracks"
                            className="h-12 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary px-4 text-sm text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-border-primary"
                        />
                        <Button type="submit" variant="primary" glow disabled={isSearching || !searchQuery.trim()}>
                            {isSearching ? <RefreshCw size={18} className="animate-spin" /> : <Search size={18} />}
                            Search
                        </Button>
                    </form>

                    {searchError && (
                        <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {searchError}
                        </div>
                    )}

                    {searchResult && (
                        <div className="space-y-6">
                            <div className="flex flex-wrap gap-3">
                                <span className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    {searchResult.result_playlist_count} playlists
                                </span>
                                <span className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    {searchResult.result_track_count} tracks
                                </span>
                            </div>

                            {searchResult.playlists.length > 0 && (
                                <section className="space-y-3">
                                    <div className="flex flex-wrap items-center justify-between gap-3">
                                        <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            <ListMusic size={15} />
                                            Playlists
                                        </div>
                                        <ResultPager
                                            page={safePlaylistPage}
                                            pageCount={playlistPageCount}
                                            onPrevious={() => updateSearchPage('playlist_page', Math.max(1, safePlaylistPage - 1))}
                                            onNext={() => updateSearchPage('playlist_page', Math.min(playlistPageCount, safePlaylistPage + 1))}
                                        />
                                    </div>
                                    <div className="grid gap-4 xl:grid-cols-2">
                                        {pagedSearchPlaylists.map((playlist) => (
                                            <PlaylistFeatureCard
                                                key={`${playlist.source_platform}-${playlist.external_playlist_id}`}
                                                title={playlist.title}
                                                sourcePlatform={playlist.source_platform}
                                                curator={playlist.curator || playlist.source_platform}
                                                trackCount={playlist.track_count}
                                                description={playlist.description || 'No description available.'}
                                                imageUrl={playlist.cover_image_url}
                                                actionLabel="Open Tracks"
                                                detailPath={buildEmsSearchPlaylistDetailPath(playlist.source_platform, playlist.external_playlist_id)}
                                                onOpenDetail={() => writeSearchPlaylistCache(playlist)}
                                                onSelect={() => openSearchPlaylistDetail(playlist)}
                                                onOpenExternal={() => openExternal(playlist.platform_external_url)}
                                            />
                                        ))}
                                    </div>
                                </section>
                            )}

                            {searchResult.tracks.length > 0 && (
                                <section className="space-y-3">
                                    <div className="flex flex-wrap items-center justify-between gap-3">
                                        <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            <Search size={15} />
                                            Tracks
                                        </div>
                                        <ResultPager
                                            page={safeTrackPage}
                                            pageCount={trackPageCount}
                                            onPrevious={() => updateSearchPage('track_page', Math.max(1, safeTrackPage - 1))}
                                            onNext={() => updateSearchPage('track_page', Math.min(trackPageCount, safeTrackPage + 1))}
                                        />
                                    </div>
                                    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                                        {pagedSearchTracks.map((track) => (
                                            <TrackFeatureCard
                                                key={`${track.source_platform}-${track.external_track_id}`}
                                                title={track.title}
                                                artistName={track.artist_name}
                                                sourcePlatform={track.source_platform}
                                                albumTitle={track.album_title}
                                                imageUrl={track.album_image_url}
                                                durationMs={track.duration_ms}
                                                badges={track.isrc ? ['ISRC'] : []}
                                                artistDetailPath={buildArtistDetailPath(track.artist_name)}
                                                onPlay={() => void playItem(toEmsSearchTrackPlaybackItem(track, 'EMS Search'))}
                                                onOpenExternal={() => openExternal(track.platform_external_url)}
                                            />
                                        ))}
                                    </div>
                                </section>
                            )}

                            {searchResult.playlists.length === 0 && searchResult.tracks.length === 0 && (
                                <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                                    No provider results found.
                                </div>
                            )}
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="FLO Special"
                subtitle="FLO topics and playlists stored in EMS"
                action={
                    <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => void handleRefreshFloSpecial()}
                        disabled={isRefreshingFloSpecial}
                    >
                        <RefreshCw size={15} className={isRefreshingFloSpecial ? 'animate-spin' : undefined} />
                        Refresh
                    </Button>
                }
            >
                <div className="space-y-5">
                    {(isLoadingFloSpecial || isRefreshingFloSpecial) && (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            {isRefreshingFloSpecial ? 'Updating FLO' : 'Loading FLO'}
                        </span>
                    )}

                    {floSpecialError && (
                        <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {floSpecialError}
                        </div>
                    )}

                    {floSpecialSections.length > 0 ? (
                        <div className="space-y-7">
                            {floSpecialSections.map((section) => (
                                <FloSpecialSectionView
                                    key={section.title}
                                    section={section}
                                    preparingPlaylistId={preparingPlaylistId}
                                    onPlay={handlePlayEmsPlaylist}
                                />
                            ))}
                        </div>
                    ) : (
                        <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                            FLO Special playlists will appear here after the first refresh stores them in EMS.
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="Melon Hot 100"
                subtitle="The latest Melon chart materialized as an EMS playlist"
                action={
                    isLoadingMelonHot100 ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            Loading chart
                        </span>
                    ) : null
                }
            >
                <div className="space-y-5">
                    {melonHot100Error && (
                        <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {melonHot100Error}
                        </div>
                    )}

                    {melonHot100Playlists.length > 0 ? (
                        <div className="grid gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(280px,0.9fr)]">
                            {melonHot100Playlists.map((playlist) => (
                                <PlaylistFeatureCard
                                    key={playlist.id}
                                    title={playlist.title}
                                    sourcePlatform={playlist.source_platform}
                                    curator={playlist.curator || 'Melon'}
                                    trackCount={playlist.track_count}
                                    description={playlist.description || 'Melon Hot 100 chart stored in EMS.'}
                                    supportingText={floPlaylistSupportingText(playlist)}
                                    imageUrl={playlist.cover_image_url}
                                    actionLabel="Open Playlist"
                                    detailPath={buildEmsPlaylistDetailPath(playlist.id)}
                                    isPlayLoading={preparingPlaylistId === playlist.id}
                                    onPlay={() => void handlePlayEmsPlaylist(playlist)}
                                    onOpenExternal={() => openExternal(playlist.platform_external_url)}
                                />
                            ))}
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-5">
                                <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                    <Tags size={15} />
                                    EMS chart corner
                                </div>
                                <h3 className="mt-3 text-xl font-semibold text-hud-text-primary">
                                    매일 바뀌는 차트를 EMS 후보군으로 보관
                                </h3>
                                <p className="mt-3 text-sm leading-6 text-hud-text-secondary">
                                    Melon 스크래프가 갱신될 때 같은 순서로 EMS 플레이리스트 링크를 다시 구성합니다.
                                    상세 페이지에서는 기존 EMS 재생 큐와 같은 방식으로 연속 재생됩니다.
                                </p>
                            </div>
                        </div>
                    ) : (
                        <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                            Melon Hot 100 will appear here after the chart scraper materializes it into EMS.
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="EMS Curated Playlist Atlas"
                subtitle="Personalized genre, mood, quality, and fresh sections generated from the EMS pool"
                action={
                    isLoadingCollection ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            Loading pool
                        </span>
                    ) : null
                }
            >
                <div className="space-y-5">
                    {collectionError && (
                        <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {collectionError}
                        </div>
                    )}

                    {playlistSections.length > 0 ? (
                        <div className="space-y-6">
                            <div className="flex flex-wrap gap-3">
                                <span className="inline-flex items-center gap-2 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-3 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                    <Sparkles size={14} />
                                    {isPlaylistPersonalized ? 'Personalized' : 'General EMS'}
                                </span>
                            </div>
                            {playlistSections.map((section) => (
                                <EmsPlaylistSectionView
                                    key={section.section_id}
                                    section={section}
                                    preparingPlaylistId={preparingPlaylistId}
                                    onPlay={handlePlayEmsPlaylist}
                                />
                            ))}
                        </div>
                    ) : (
                        <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                            EMS public playlists will appear here after the scheduled collector stores provider results.
                        </div>
                    )}
                </div>
            </HudCard>
        </div>
    )
}

const ResultPager = ({
    page,
    pageCount,
    onPrevious,
    onNext,
}: {
    page: number
    pageCount: number
    onPrevious: () => void
    onNext: () => void
}) => (
    <div className="flex items-center gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onPrevious} disabled={page <= 1}>
            Prev
        </Button>
        <span className="min-w-20 text-center text-xs uppercase tracking-[0.18em] text-hud-text-muted">
            {page}/{pageCount}
        </span>
        <Button type="button" variant="ghost" size="sm" onClick={onNext} disabled={page >= pageCount}>
            Next
        </Button>
    </div>
)

const playlistSupportingText = (item: EmsCollectionPlaylistSectionItem) => {
    const coverage = item.playlist.audio_feature_coverage
    const coverageText = `${coverage.filled_track_count}/${coverage.track_count} audio features · ${formatPercent(coverage.coverage_ratio)}`
    const signals = item.match_signals.slice(0, 2).join(' · ')
    return signals ? `${signals} · ${coverageText}` : coverageText
}

const floPlaylistSupportingText = (playlist: EmsCollectionPlaylistItem) => {
    const coverage = playlist.audio_feature_coverage
    return `${coverage.track_count} stored tracks · ${formatPercent(coverage.coverage_ratio)} audio features`
}

const FloSpecialSectionView = ({
    section,
    preparingPlaylistId,
    onPlay,
}: {
    section: EmsFloSpecialSection
    preparingPlaylistId: number | null
    onPlay: (playlist: EmsCollectionPlaylistItem) => Promise<void>
}) => {
    if (section.playlists.length === 0) {
        return null
    }

    return (
        <section className="space-y-3">
            <div className="flex flex-wrap items-end justify-between gap-3">
                <div>
                    <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                        <Sparkles size={15} />
                        FLO · {section.source_type}
                    </div>
                    <h2 className="mt-2 text-2xl font-semibold text-hud-text-primary">{section.title}</h2>
                </div>
                <span className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-2 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                    {section.playlists.length} playlists
                </span>
            </div>

            <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                {section.playlists.map((playlist) => (
                    <PlaylistFeatureCard
                        key={playlist.id}
                        title={playlist.title}
                        sourcePlatform={playlist.source_platform}
                        curator={playlist.curator || 'FLO Special'}
                        trackCount={playlist.track_count}
                        description={playlist.description || section.title}
                        supportingText={floPlaylistSupportingText(playlist)}
                        imageUrl={playlist.cover_image_url}
                        actionLabel="Open Playlist"
                        detailPath={buildEmsPlaylistDetailPath(playlist.id)}
                        isPlayLoading={preparingPlaylistId === playlist.id}
                        onPlay={() => void onPlay(playlist)}
                        onOpenExternal={() => openExternal(playlist.platform_external_url)}
                    />
                ))}
            </div>
        </section>
    )
}

const EmsPlaylistSectionView = ({
    section,
    preparingPlaylistId,
    onPlay,
}: {
    section: EmsCollectionPlaylistSection
    preparingPlaylistId: number | null
    onPlay: (playlist: EmsCollectionPlaylistItem) => Promise<void>
}) => {
    const cards = section.playlists
    if (cards.length === 0) {
        return null
    }

    return (
        <section className="space-y-3">
            <div className="flex flex-wrap items-end justify-between gap-3">
                <div>
                    <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                        <Tags size={15} />
                        {section.category_type} · {section.category_label}
                    </div>
                    <h2 className="mt-2 text-2xl font-semibold text-hud-text-primary">{section.title}</h2>
                    <p className="mt-1 text-sm leading-6 text-hud-text-secondary">{section.subtitle}</p>
                </div>
                <span className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-2 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                    {section.display_style}
                </span>
            </div>

            {section.display_style === 'hero' && (
                <div className="grid gap-4 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
                    <PlaylistFeatureCard
                        title={cards[0].playlist.title}
                        sourcePlatform={cards[0].playlist.source_platform}
                        curator={cards[0].playlist.curator}
                        trackCount={cards[0].playlist.track_count}
                        description={cards[0].playlist.description}
                        supportingText={playlistSupportingText(cards[0])}
                        imageUrl={cards[0].playlist.cover_image_url}
                        actionLabel="Open Playlist"
                        detailPath={buildEmsPlaylistDetailPath(cards[0].playlist.id)}
                        isPlayLoading={preparingPlaylistId === cards[0].playlist.id}
                        onPlay={() => void onPlay(cards[0].playlist)}
                        onOpenExternal={() => openExternal(cards[0].playlist.platform_external_url)}
                    />
                    <div className="grid gap-4">
                        {cards.slice(1).map((item) => (
                            <PlaylistFeatureCard
                                key={item.playlist.id}
                                title={item.playlist.title}
                                sourcePlatform={item.playlist.source_platform}
                                curator={item.playlist.curator}
                                trackCount={item.playlist.track_count}
                                description={item.playlist.description}
                                supportingText={playlistSupportingText(item)}
                                imageUrl={item.playlist.cover_image_url}
                                actionLabel="Open Playlist"
                                detailPath={buildEmsPlaylistDetailPath(item.playlist.id)}
                                isPlayLoading={preparingPlaylistId === item.playlist.id}
                                onPlay={() => void onPlay(item.playlist)}
                                onOpenExternal={() => openExternal(item.playlist.platform_external_url)}
                            />
                        ))}
                    </div>
                </div>
            )}

            {section.display_style === 'rail' && (
                <div className="flex gap-4 overflow-x-auto pb-2">
                    {cards.map((item) => (
                        <div key={item.playlist.id} className="min-w-[320px] max-w-[440px] flex-1">
                            <PlaylistFeatureCard
                                title={item.playlist.title}
                                sourcePlatform={item.playlist.source_platform}
                                curator={item.playlist.curator}
                                trackCount={item.playlist.track_count}
                                description={item.playlist.description}
                                supportingText={playlistSupportingText(item)}
                                imageUrl={item.playlist.cover_image_url}
                                actionLabel="Open Playlist"
                                detailPath={buildEmsPlaylistDetailPath(item.playlist.id)}
                                isPlayLoading={preparingPlaylistId === item.playlist.id}
                                onPlay={() => void onPlay(item.playlist)}
                                onOpenExternal={() => openExternal(item.playlist.platform_external_url)}
                            />
                        </div>
                    ))}
                </div>
            )}

            {section.display_style === 'compact' && (
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                    {cards.map((item) => (
                        <CompactPlaylistRow
                            key={item.playlist.id}
                            item={item}
                            isPlayLoading={preparingPlaylistId === item.playlist.id}
                            onPlay={() => void onPlay(item.playlist)}
                        />
                    ))}
                </div>
            )}

            {section.display_style !== 'hero' && section.display_style !== 'rail' && section.display_style !== 'compact' && (
                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                    {cards.map((item) => (
                        <PlaylistFeatureCard
                            key={item.playlist.id}
                            title={item.playlist.title}
                            sourcePlatform={item.playlist.source_platform}
                            curator={item.playlist.curator}
                            trackCount={item.playlist.track_count}
                            description={item.playlist.description}
                            supportingText={playlistSupportingText(item)}
                            imageUrl={item.playlist.cover_image_url}
                            actionLabel="Open Playlist"
                            detailPath={buildEmsPlaylistDetailPath(item.playlist.id)}
                            isPlayLoading={preparingPlaylistId === item.playlist.id}
                            onPlay={() => void onPlay(item.playlist)}
                            onOpenExternal={() => openExternal(item.playlist.platform_external_url)}
                        />
                    ))}
                </div>
            )}
        </section>
    )
}

const CompactPlaylistRow = ({
    item,
    isPlayLoading,
    onPlay,
}: {
    item: EmsCollectionPlaylistSectionItem
    isPlayLoading: boolean
    onPlay: () => void
}) => {
    const navigate = useNavigate()
    const playlist = item.playlist
    return (
        <div
            role="button"
            tabIndex={0}
            onClick={() => navigate(buildEmsPlaylistDetailPath(playlist.id))}
            onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    navigate(buildEmsPlaylistDetailPath(playlist.id))
                }
            }}
            className="grid cursor-pointer grid-cols-[76px_minmax(0,1fr)] gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-3 transition-hud hover:border-hud-border-primary"
        >
            <div className="h-[76px] overflow-hidden rounded-xl">
                <MusicArtwork imageUrl={playlist.cover_image_url} seed={`${playlist.source_platform}-${playlist.title}`} label={playlist.title} />
            </div>
            <div className="min-w-0">
                <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-hud-text-primary">{playlist.title}</p>
                        <p className="mt-1 truncate text-xs text-hud-text-secondary">{playlist.curator}</p>
                    </div>
                    <span className="shrink-0 rounded-full border border-hud-border-secondary px-2 py-1 text-[10px] uppercase tracking-[0.16em] text-hud-text-muted">
                        {playlist.source_platform}
                    </span>
                </div>
                <p className="mt-2 truncate text-xs uppercase tracking-[0.16em] text-hud-accent-primary">
                    {playlistSupportingText(item)}
                </p>
                <div className="mt-3 flex gap-2">
                    <button
                        type="button"
                        disabled={isPlayLoading}
                        onClick={(event) => {
                            event.stopPropagation()
                            onPlay()
                        }}
                        className="flex h-9 w-9 items-center justify-center rounded-lg border border-hud-border-secondary text-hud-text-primary transition-hud hover:border-hud-border-primary disabled:opacity-50"
                        aria-label={`Play ${playlist.title}`}
                    >
                        {isPlayLoading ? <RefreshCw size={15} className="animate-spin" /> : <Play size={15} />}
                    </button>
                    {playlist.platform_external_url && (
                        <button
                            type="button"
                            onClick={(event) => {
                                event.stopPropagation()
                                openExternal(playlist.platform_external_url)
                            }}
                            className="flex h-9 w-9 items-center justify-center rounded-lg border border-hud-border-secondary text-hud-text-primary transition-hud hover:border-hud-border-primary"
                            aria-label={`Open ${playlist.title}`}
                        >
                            <ExternalLink size={15} />
                        </button>
                    )}
                </div>
            </div>
        </div>
    )
}

export default EmsPage
