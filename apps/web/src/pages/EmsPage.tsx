import { startTransition, useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { ExternalLink, Play, RefreshCw, Search, Sparkles, Tags } from 'lucide-react'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import PageExplanation from '@/components/common/PageExplanation'
import MusicArtwork from '@/components/music/MusicArtwork'
import PlaylistFeatureCard from '@/components/music/PlaylistFeatureCard'
import TidalHomePageSections from '@/components/home/TidalHomePageSections'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import {
    buildEmsPlaylistDetailPath,
    toEmsTrackPlaybackItem,
} from '@/lib/emsPlayback'
import { PAGE_EXPLANATIONS } from '@/lib/productLanguage'
import {
    ApiError,
    fetchEmsCollectedPlaylistDetail,
    fetchEmsCollectedPlaylists,
    fetchEmsFloSpecial,
    fetchEmsMelonHot100,
    fetchEmsPlaylistSections,
    queueEmsSpotifyFeaturedCharts,
    refreshEmsFloSpecial,
    searchEmsCollection,
} from '@/services/api'
import type {
    EmsCollectionPlaylistItem,
    EmsCollectionPlaylistSection,
    EmsCollectionPlaylistSectionItem,
    EmsFloSpecialSection,
} from '@/types/api'

type DiscoveryPlatformId = string

const defaultDiscoveryPlatformIds: DiscoveryPlatformId[] = ['tidal', 'spotify']
const SPOTIFY_FEATURED_CHARTS_POOL_CACHE_PREFIX = 'ems-spotify-featured-charts-pool'
const SPOTIFY_FEATURED_CHARTS_SOURCE = 'spotify_featured_charts'
const FLO_SPECIAL_DISPLAY_LIMIT = 120

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }
    window.open(url, '_blank', 'noopener,noreferrer')
}

const formatPercent = (value?: number | null) =>
    `${Math.round((value ?? 0) * 100)}%`

type SearchPoolSummary = {
    poolRunId: number | null
    platformId: string
    query: string
    playlistCount: number
    trackCount: number
    searchedAt: string
}

const EmsPage = () => {
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const { playQueue } = usePlayback()
    const [searchParams, setSearchParams] = useSearchParams()
    const activeUserId = session?.userId || workspace.userId
    const urlQuery = searchParams.get('q')?.trim() ?? ''
    const [searchQuery, setSearchQuery] = useState(urlQuery)
    const [searchPoolSummary, setSearchPoolSummary] = useState<SearchPoolSummary | null>(null)
    const [isSearching, setIsSearching] = useState(false)
    const [searchError, setSearchError] = useState<string | null>(null)
    const [collectionRefreshToken, setCollectionRefreshToken] = useState(0)
    const [playlistSections, setPlaylistSections] = useState<EmsCollectionPlaylistSection[]>([])
    const [isPlaylistPersonalized, setIsPlaylistPersonalized] = useState(false)
    const [isLoadingCollection, setIsLoadingCollection] = useState(false)
    const [preparingPlaylistId, setPreparingPlaylistId] = useState<number | null>(null)
    const [collectionError, setCollectionError] = useState<string | null>(null)
    const [featuredChartPlaylists, setFeaturedChartPlaylists] = useState<EmsCollectionPlaylistItem[]>([])
    const [isLoadingFeaturedCharts, setIsLoadingFeaturedCharts] = useState(false)
    const [featuredChartsPoolError, setFeaturedChartsPoolError] = useState<string | null>(null)
    const [floSpecialSections, setFloSpecialSections] = useState<EmsFloSpecialSection[]>([])
    const [isLoadingFloSpecial, setIsLoadingFloSpecial] = useState(false)
    const [isRefreshingFloSpecial, setIsRefreshingFloSpecial] = useState(false)
    const [floSpecialError, setFloSpecialError] = useState<string | null>(null)
    const [melonHot100Playlists, setMelonHot100Playlists] = useState<EmsCollectionPlaylistItem[]>([])
    const [isLoadingMelonHot100, setIsLoadingMelonHot100] = useState(false)
    const [melonHot100Error, setMelonHot100Error] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        if (!activeUserId) {
            return () => controller.abort()
        }

        const cacheKey = `${SPOTIFY_FEATURED_CHARTS_POOL_CACHE_PREFIX}:${activeUserId}`
        if (window.sessionStorage.getItem(cacheKey)) {
            return () => controller.abort()
        }

        setFeaturedChartsPoolError(null)
        queueEmsSpotifyFeaturedCharts(activeUserId, controller.signal)
            .then(() => {
                window.sessionStorage.setItem(cacheKey, new Date().toISOString())
            })
            .catch((err: unknown) => {
                if (err instanceof DOMException && err.name === 'AbortError') return
                const message =
                    err instanceof ApiError
                        ? err.message
                        : 'Spotify Featured Charts를 EMS POOL에 등록하지 못했습니다.'
                startTransition(() => setFeaturedChartsPoolError(message))
            })

        return () => controller.abort()
    }, [activeUserId])

    useEffect(() => {
        const controller = new AbortController()

        setIsLoadingFeaturedCharts(true)
        fetchEmsCollectedPlaylists('spotify', controller.signal, 50, false)
            .then((response) => {
                startTransition(() => {
                    setFeaturedChartPlaylists(
                        response.playlists
                            .filter((playlist) => playlist.collection_source === SPOTIFY_FEATURED_CHARTS_SOURCE)
                            .slice(0, 4),
                    )
                })
            })
            .catch((err: unknown) => {
                if (err instanceof DOMException && err.name === 'AbortError') return
                const message =
                    err instanceof ApiError
                        ? err.message
                        : 'EMS DB에 저장된 Spotify Featured Charts를 불러오지 못했습니다.'
                startTransition(() => setFeaturedChartsPoolError(message))
            })
            .finally(() => {
                setIsLoadingFeaturedCharts(false)
            })

        return () => controller.abort()
    }, [collectionRefreshToken])

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
    }, [activeUserId, collectionRefreshToken])

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
                        : 'FLO Special 플레이리스트를 불러오지 못했습니다.'
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
                        : 'EMS에 저장된 Melon Hot 100을 불러오지 못했습니다.'
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
            setSearchPoolSummary(null)
            setSearchError(null)
            return
        }
        if (!activeUserId) {
            setSearchError('EMS POOL 수집은 로그인 후 사용할 수 있습니다.')
            return
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
                startTransition(() => {
                    setSearchPoolSummary({
                        poolRunId: response.pool_run_id,
                        platformId: response.platform_id,
                        query: response.query,
                        playlistCount: response.result_playlist_count,
                        trackCount: response.result_track_count,
                        searchedAt: response.searched_at,
                    })
                    setCollectionRefreshToken((value) => value + 1)
                })
            })
            .catch((requestError: unknown) => {
                if (!isCurrent) {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'EMS POOL 수집 요청을 등록하지 못했습니다.'
                setSearchPoolSummary(null)
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
    }, [activeUserId, urlQuery])

    const handleSearchSubmit = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        const trimmedQuery = searchQuery.trim()
        if (!trimmedQuery) {
            return
        }
        setSearchParams({
            q: trimmedQuery,
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
                setCollectionError('재생할 저장 트랙이 없는 EMS 플레이리스트입니다.')
                return
            }

            await playQueue(playbackItems, 0)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : '재생할 EMS 플레이리스트 트랙을 불러오지 못했습니다.'
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
                    : 'FLO Special 플레이리스트를 새로고침하지 못했습니다.'
            setFloSpecialError(message)
        } finally {
            setIsRefreshingFloSpecial(false)
        }
    }

    return (
        <div className="space-y-6">
            <PageExplanation {...PAGE_EXPLANATIONS.ems} />

            <HudCard
                title="Spotify Featured Charts"
                subtitle="Spotify 공식 차트는 EMS POOL에 저장된 뒤 DB 조회 결과로만 표시합니다."
                action={
                    isLoadingFeaturedCharts ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            DB 저장본 확인 중
                        </span>
                    ) : null
                }
            >
                {featuredChartsPoolError && (
                    <div className="mb-4 rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                        {featuredChartsPoolError}
                    </div>
                )}
                {featuredChartPlaylists.length > 0 ? (
                    <div className="grid gap-4 xl:grid-cols-2">
                        {featuredChartPlaylists.map((playlist) => (
                            <PlaylistFeatureCard
                                key={playlist.id}
                                title={playlist.title}
                                sourcePlatform={playlist.source_platform}
                                sourceLabel="DB 저장본"
                                curator={playlist.curator}
                                trackCount={playlist.track_count}
                                description={playlist.description || 'EMS POOL에 저장된 Spotify Featured Chart입니다.'}
                                supportingText={floPlaylistSupportingText(playlist)}
                                imageUrl={playlist.cover_image_url}
                                actionLabel="플레이리스트 열기"
                                detailPath={buildEmsPlaylistDetailPath(playlist.id)}
                                isPlayLoading={preparingPlaylistId === playlist.id}
                                onPlay={() => void handlePlayEmsPlaylist(playlist)}
                                onOpenExternal={() => openExternal(playlist.platform_external_url)}
                            />
                        ))}
                    </div>
                ) : (
                    <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                        Spotify Featured Charts 수집이 완료되면 저장된 EMS 플레이리스트 카드가 여기에 표시됩니다.
                    </div>
                )}
            </HudCard>

            <TidalHomePageSections />

            <HudCard
                title="EMS 검색"
                subtitle="검색은 EMS POOL 수집 요청만 만들고, 화면은 저장된 DB 플레이리스트와 트랙만 보여줍니다."
                action={
                    isSearching ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            EMS POOL 수집 요청 중
                        </span>
                    ) : null
                }
            >
                <div className="space-y-5">
                    <form className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_auto]" onSubmit={handleSearchSubmit}>
                        <label className="sr-only" htmlFor="ems-search-query">검색어</label>
                        <input
                            id="ems-search-query"
                            value={searchQuery}
                            onChange={(event) => setSearchQuery(event.target.value)}
                            placeholder="EMS POOL에 수집할 검색어"
                            className="h-12 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary px-4 text-sm text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-border-primary"
                        />
                        <Button type="submit" variant="primary" glow disabled={isSearching || !searchQuery.trim()}>
                            {isSearching ? <RefreshCw size={18} className="animate-spin" /> : <Search size={18} />}
                            EMS POOL 수집
                        </Button>
                    </form>

                    {searchError && (
                        <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                            {searchError}
                        </div>
                    )}

                    {searchPoolSummary && (
                        <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-5">
                            <div className="flex flex-wrap gap-3">
                                <span className="rounded-2xl border border-hud-border-secondary px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    큐 #{searchPoolSummary.poolRunId ?? '대기'}
                                </span>
                                <span className="rounded-2xl border border-hud-border-secondary px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    {searchPoolSummary.platformId}
                                </span>
                                <span className="rounded-2xl border border-hud-border-secondary px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    후보 playlist {searchPoolSummary.playlistCount}개
                                </span>
                                <span className="rounded-2xl border border-hud-border-secondary px-4 py-3 text-sm font-medium text-hud-text-primary">
                                    후보 track {searchPoolSummary.trackCount}곡
                                </span>
                            </div>
                            <p className="mt-4 text-sm leading-6 text-hud-text-secondary">
                                “{searchPoolSummary.query}” 검색 결과를 EMS POOL에 수집하도록 등록했습니다.
                                저장이 끝난 항목은 아래 EMS 큐레이션 지도와 각 DB 상세 화면에서만 표시됩니다.
                            </p>
                            <div className="mt-4">
                                <Button
                                    type="button"
                                    variant="ghost"
                                    size="sm"
                                    onClick={() => setCollectionRefreshToken((value) => value + 1)}
                                >
                                    <RefreshCw size={15} />
                                    DB 저장본 다시 불러오기
                                </Button>
                            </div>
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="FLO Special"
                subtitle="FLO 공개 주제와 플레이리스트를 EMS에 저장해 탐색합니다."
                action={
                    <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => void handleRefreshFloSpecial()}
                        disabled={isRefreshingFloSpecial}
                    >
                        <RefreshCw size={15} className={isRefreshingFloSpecial ? 'animate-spin' : undefined} />
                        새로고침
                    </Button>
                }
            >
                <div className="space-y-5">
                    {(isLoadingFloSpecial || isRefreshingFloSpecial) && (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            {isRefreshingFloSpecial ? 'FLO 갱신 중' : 'FLO 불러오는 중'}
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
                            첫 새로고침이 EMS에 FLO Special 플레이리스트를 저장하면 여기에 표시됩니다.
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="Melon Hot 100"
                subtitle="지금 많이 듣는 곡들을 EMS 플레이리스트로 탐색합니다."
                action={
                    isLoadingMelonHot100 ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            차트 불러오는 중
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
                                    description={playlist.description || 'EMS에 저장된 Melon Hot 100 차트입니다.'}
                                    supportingText={floPlaylistSupportingText(playlist)}
                                    imageUrl={playlist.cover_image_url}
                                    actionLabel="플레이리스트 열기"
                                    detailPath={buildEmsPlaylistDetailPath(playlist.id)}
                                    isPlayLoading={preparingPlaylistId === playlist.id}
                                    onPlay={() => void handlePlayEmsPlaylist(playlist)}
                                    onOpenExternal={() => openExternal(playlist.platform_external_url)}
                                />
                            ))}
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-5">
                                <div className="flex items-center gap-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                    <Tags size={15} />
                                    EMS 차트 구역
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
                            Melon Hot 100 플레이리스트가 준비되면 여기에 표시됩니다.
                        </div>
                    )}
                </div>
            </HudCard>

            <HudCard
                title="EMS 큐레이션 지도"
                subtitle="EMS 풀에서 장르, 분위기, 품질, 최신성 기준으로 만든 플레이리스트 섹션입니다."
                action={
                    isLoadingCollection ? (
                        <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                            <RefreshCw size={14} className="animate-spin" />
                            풀 불러오는 중
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
                                    {isPlaylistPersonalized ? '개인화됨' : '일반 EMS'}
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
                            예약 수집기가 제공자 결과를 저장하면 EMS 공개 플레이리스트가 여기에 표시됩니다.
                        </div>
                    )}
                </div>
            </HudCard>
        </div>
    )
}

const playlistSupportingText = (item: EmsCollectionPlaylistSectionItem) => {
    const coverage = item.playlist.audio_feature_coverage
    const coverageText = `오디오 특성 ${coverage.filled_track_count}/${coverage.track_count} · ${formatPercent(coverage.coverage_ratio)}`
    const signals = item.match_signals.slice(0, 2).join(' · ')
    return signals ? `${signals} · ${coverageText}` : coverageText
}

const floPlaylistSupportingText = (playlist: EmsCollectionPlaylistItem) => {
    const coverage = playlist.audio_feature_coverage
    return `저장 트랙 ${coverage.track_count}곡 · 오디오 특성 ${formatPercent(coverage.coverage_ratio)}`
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
                    플레이리스트 {section.playlists.length}개
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
                        actionLabel="플레이리스트 열기"
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
                        actionLabel="플레이리스트 열기"
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
                                actionLabel="플레이리스트 열기"
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
                                actionLabel="플레이리스트 열기"
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
                            actionLabel="플레이리스트 열기"
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
                            aria-label={`${playlist.title} 재생`}
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
                            aria-label={`${playlist.title} 열기`}
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
