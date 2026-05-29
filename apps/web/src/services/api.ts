import type {
    AuthLoginRequest,
    AuthLoginResponse,
    AuthRegistrationRequest,
    AuthRegistrationResponse,
    ArtistDetailResponse,
    PlatformAuthorizationCompleteRequest,
    PlatformAuthorizationCompleteResponse,
    PlatformAuthorizationStartRequest,
    PlatformAuthorizationStartResponse,
    PlatformConnectionBootstrapResponse,
    PlatformConnectionCommandResponse,
    PlatformPlaybackCredentialsResponse,
    TidalPlaybackManifestDiagnosticsResponse,
    TidalPlaybackStreamResponse,
    TidalPlaybackTargetResolveRequest,
    TidalPlaybackTargetResolveResponse,
    SpotifyPlaybackTargetResolveRequest,
    SpotifyPlaybackTargetResolveResponse,
    YouTubePlaybackTargetResolveRequest,
    YouTubePlaybackTargetResolveResponse,
    TidalDeviceAuthorizationPollRequest,
    TidalDeviceAuthorizationPollResponse,
    TidalDeviceAuthorizationStartRequest,
    TidalDeviceAuthorizationStartResponse,
    PlatformConnectRequest,
    PlatformDisconnectRequest,
    PlatformCatalogResponse,
    LastFmProfileConnectRequest,
    LastFmScrobbleBootstrapResponse,
    LastFmScrobbleSyncRequest,
    LastFmScrobbleSyncResponse,
    LastFmSignalPreviewResponse,
    EmsCollectionSearchRequest,
    EmsCollectionSearchResponse,
    EmsCollectionPlaylistBrowseResponse,
    EmsCollectionPlaylistSectionsResponse,
    EmsCollectionPlaylistDetailResponse,
    EmsCollectionTrackBrowseResponse,
    EmsCollectionSearchPlaylistTracksResponse,
    EmsCollectedPlaylistsCleanupResponse,
    EmsFloSpecialRefreshResponse,
    EmsFloSpecialResponse,
    EmsAcquisitionRunRequest,
    EmsAcquisitionRunResponse,
    EmsAcquisitionRunsResponse,
    EmsAcquisitionSourcePresetsResponse,
    EmsAcquisitionSourceQualityResponse,
    EmsPoolAdminEntryRetryResponse,
    EmsPoolAdminRunCommandResponse,
    EmsPoolAdminRunDeleteResponse,
    EmsPoolAdminRunDetailResponse,
    EmsPoolAdminRunsResponse,
    MetadataCandidateAuditResponse,
    MetadataCandidateApplyResponse,
    MetadataCandidateAutoAcceptResponse,
    MetadataCandidateCanonicalLinkConflictResponse,
    MetadataCandidateCanonicalPromotionResponse,
    MetadataCandidateCommandResponse,
    MetadataCandidateListResponse,
    MetadataCandidateRollbackResponse,
    MetadataExternalLookupResponse,
    FeatureCoverageAdminResponse,
    MetadataLookupResponse,
    PlaylistQualityRecentResponse,
    RecommendationAuditLogRecentResponse,
    RecommendationTasteModeSummaryResponse,
    SasrecAutoTrainAdminResponse,
    SasrecRegistryAdminResponse,
    SasrecUserModelStatusResponse,
    PersonalizationProfileRecomputeResponse,
    PersonalizationProfileResponse,
    EmsOverviewRequest,
    EmsOverviewResponse,
    EmsWorkspaceAnalysisRequest,
    EmsWorkspaceAnalysisResponse,
    GmsPlaylistDismissResponse,
    GmsPlaylistPreviewResponse,
    GmsPlaylistSaveRequest,
    GmsPlaylistSaveResponse,
    GmsTidalPlaylistUrlImportRequest,
    GmsTidalPlaylistUrlImportResponse,
    GmsRecommendationPreviewRequest,
    GmsRecommendationPreviewResponse,
    GmsRecommendationFeedbackRequest,
    GmsRecommendationFeedbackResponse,
    HeroTrackListResponse,
    HeroTrackResponse,
    MagazineArticleListResponse,
    MagazineArticleResponse,
    MelonChartListResponse,
    MelonChartTrack,
    MelonResolveResponse,
    PopularPlaylistListResponse,
    PopularPlaylistResponse,
    UserTrackLikeRequest,
    UserTrackLikeResponse,
    UserMusicEventRequest,
    UserMusicEventResponse,
    PmsWorkspaceBootstrapResponse,
    PmsPlaylistDetailResponse,
    PmsPlaylistImportBootstrapResponse,
    PmsPlaylistImportRequest,
    PmsPlaylistImportResponse,
    PmsPersonalPlaylistBootstrapResponse,
    PmsPersonalPlaylistCommandResponse,
    PmsPersonalPlaylistCreateRequest,
    PmsPersonalPlaylistTrackSaveRequest,
    PublicCurationAdminRunRequest,
    PublicCurationAdminRunResponse,
    PublicCurationPlaybackSessionResponse,
    PublicCurationShareResponse,
    PublicCurationTidalOAuthCompleteRequest,
    PublicCurationTidalOAuthCompleteResponse,
    PublicCurationTidalOAuthStartResponse,
    SchedulingAdminResponse,
    SystemInfoResponse,
} from '@/types/api'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
    readonly status: number
    readonly code: string | null

    constructor(message: string, status: number, code: string | null = null) {
        super(message)
        this.name = 'ApiError'
        this.status = status
        this.code = code
    }
}

const buildApiUrl = (path: string) => `${API_BASE_URL}${path}`

const resolveDocsUrl = (
    overrideUrl: string | undefined,
    sameOriginPath: string,
    localPort: string,
    localPath: string,
) => {
    if (overrideUrl) {
        return overrideUrl
    }

    if (typeof window === 'undefined') {
        return sameOriginPath
    }

    if (window.location.port === '5173') {
        return `${window.location.protocol}//${window.location.hostname}:${localPort}${localPath}`
    }

    return sameOriginPath
}

const readErrorPayload = async (response: Response) => {
    const contentType = response.headers.get('content-type') ?? ''

    if (contentType.includes('application/json')) {
        const payload = (await response.json()) as Record<string, unknown>
        const detail = typeof payload.detail === 'string' ? payload.detail : null
        const message = typeof payload.message === 'string' ? payload.message : null
        const code = typeof payload.code === 'string' ? payload.code : null

        return {
            code,
            message: detail ?? message ?? `Request failed with status ${response.status}`,
        }
    }

    const fallback = await response.text()
    return {
        code: null,
        message: fallback || `Request failed with status ${response.status}`,
    }
}

async function requestJson<T>(path: string, init?: RequestInit) {
    const headers = new Headers(init?.headers)
    headers.set('Accept', 'application/json')

    const response = await fetch(buildApiUrl(path), {
        ...init,
        headers,
    })

    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }

    return (await response.json()) as T
}

async function requestArrayBuffer(path: string, init?: RequestInit) {
    const response = await fetch(buildApiUrl(path), init)

    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }

    return response.arrayBuffer()
}

export const getApiConnectionLabel = () =>
    API_BASE_URL || 'same-origin (/api via Vite proxy)'

export const getApiDocsUrl = () =>
    resolveDocsUrl(import.meta.env.VITE_API_DOCS_URL, '/docs', '8081', '/docs')

export const getAiDocsUrl = () =>
    resolveDocsUrl(import.meta.env.VITE_AI_DOCS_URL, '/ai/docs', '8000', '/docs')

export const fetchSystemInfo = (signal?: AbortSignal) =>
    requestJson<SystemInfoResponse>('/api/v1/system/info', { signal })

export const fetchPublicCurationShare = (slug: string, signal?: AbortSignal) =>
    requestJson<PublicCurationShareResponse>(
        `/api/v1/public-curations/share/${encodeURIComponent(slug)}`,
        { signal, cache: 'no-store' },
    )

export const startPublicCurationTidalOAuth = (slug: string, signal?: AbortSignal) =>
    requestJson<PublicCurationTidalOAuthStartResponse>(
        `/api/v1/public-curations/share/${encodeURIComponent(slug)}/tidal/oauth/start`,
        { method: 'POST', signal },
    )

export const completePublicCurationTidalOAuth = (
    slug: string,
    body: PublicCurationTidalOAuthCompleteRequest,
    signal?: AbortSignal,
) =>
    requestJson<PublicCurationTidalOAuthCompleteResponse>(
        `/api/v1/public-curations/share/${encodeURIComponent(slug)}/tidal/oauth/complete`,
        {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
            signal,
        },
    )

export const fetchPublicCurationPlaybackSession = (
    slug: string,
    sessionId: string,
    signal?: AbortSignal,
) =>
    requestJson<PublicCurationPlaybackSessionResponse>(
        `/api/v1/public-curations/share/${encodeURIComponent(slug)}/playback/session?session_id=${encodeURIComponent(sessionId)}`,
        { signal, cache: 'no-store' },
    )

export const createPublicCurationDraft = (payload: PublicCurationAdminRunRequest) =>
    requestJson<PublicCurationAdminRunResponse>('/api/v1/public-curations/admin/runs', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const publishPublicCurationPlaylist = (playlistId: number) =>
    requestJson<PublicCurationAdminRunResponse>(
        `/api/v1/public-curations/admin/playlists/${encodeURIComponent(String(playlistId))}/publish`,
        { method: 'POST' },
    )

export const fetchArtistDetail = (
    artistSlug: string,
    artistName?: string | null,
    userId?: string | null,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams()
    if (artistName && artistName.trim()) {
        params.set('artist_name', artistName.trim())
    }
    if (userId && userId.trim()) {
        params.set('user_id', userId.trim())
    }
    const query = params.toString()
    return requestJson<ArtistDetailResponse>(
        `/api/v1/artists/${encodeURIComponent(artistSlug)}${query ? `?${query}` : ''}`,
        { signal },
    )
}

export const fetchHeroTrack = async (
    userId: string | null | undefined,
    signal?: AbortSignal,
): Promise<HeroTrackResponse | null> => {
    const query = userId && userId.trim()
        ? `?user_id=${encodeURIComponent(userId.trim())}`
        : ''
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(`/api/v1/main-page/hero-track${query}`), {
        signal,
        headers,
        cache: 'no-store',
    })
    if (response.status === 204) {
        return null
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    return (await response.json()) as HeroTrackResponse
}

export const fetchMelonHot100 = async (
    limit: number | null,
    full: boolean,
    signal?: AbortSignal,
): Promise<{ snapshotAt: string | null; tracks: MelonChartTrack[] }> => {
    const path = full
        ? '/api/v1/main-page/melon-hot-100/full'
        : `/api/v1/main-page/melon-hot-100?limit=${Math.max(1, limit ?? 10)}`
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(path), { signal, headers, cache: 'no-store' })
    if (response.status === 204) {
        return { snapshotAt: null, tracks: [] }
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    const payload = (await response.json()) as MelonChartListResponse
    return { snapshotAt: payload.snapshot_at, tracks: payload.tracks ?? [] }
}

export const resolveMelonHotTrack = (
    rank: number,
    userId?: string | null,
    signal?: AbortSignal,
) => {
    const query = userId && userId.trim()
        ? `?user_id=${encodeURIComponent(userId.trim())}`
        : ''
    return requestJson<MelonResolveResponse>(
        `/api/v1/main-page/melon-hot-100/${rank}/resolve${query}`,
        { signal, cache: 'no-store' },
    )
}

export const triggerMelonScrape = async () => {
    const response = await fetch(buildApiUrl('/api/v1/admin/melon/scrape'), {
        method: 'POST',
        headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    return (await response.json()) as { status: string; track_count: number; ran_at: string }
}

export const fetchPopularPlaylists = async (
    limit: number,
    signal?: AbortSignal,
): Promise<PopularPlaylistResponse[]> => {
    const params = new URLSearchParams()
    params.set('limit', String(Math.max(1, limit)))
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(`/api/v1/main-page/popular-playlists?${params.toString()}`), {
        signal,
        headers,
        cache: 'no-store',
    })
    if (response.status === 204) {
        return []
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    const payload = (await response.json()) as PopularPlaylistListResponse
    return payload.playlists ?? []
}

export const fetchLatestTracks = async (
    limit: number,
    signal?: AbortSignal,
): Promise<HeroTrackResponse[]> => {
    const params = new URLSearchParams()
    params.set('limit', String(Math.max(1, limit)))
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(`/api/v1/main-page/latest-tracks?${params.toString()}`), {
        signal,
        headers,
        cache: 'no-store',
    })
    if (response.status === 204) {
        return []
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    const payload = (await response.json()) as HeroTrackListResponse
    return payload.tracks ?? []
}

export const fetchMagazineArticles = async (
    limit: number,
    signal?: AbortSignal,
): Promise<MagazineArticleResponse[]> => {
    const params = new URLSearchParams()
    params.set('limit', String(Math.max(1, limit)))
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(`/api/v1/main-page/magazine-articles?${params.toString()}`), {
        signal,
        headers,
        cache: 'no-store',
    })
    if (response.status === 204) {
        return []
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    const payload = (await response.json()) as MagazineArticleListResponse
    return payload.articles ?? []
}

export const fetchUserTrackLikeState = (
    userId: string,
    sourcePlatform: string,
    externalTrackId: string,
    signal?: AbortSignal,
) =>
    requestJson<UserTrackLikeResponse>(
        `/api/v1/user/likes/state?user_id=${encodeURIComponent(userId)}&source_platform=${encodeURIComponent(sourcePlatform)}&external_track_id=${encodeURIComponent(externalTrackId)}`,
        { signal, cache: 'no-store' },
    )

export const toggleUserTrackLike = (payload: UserTrackLikeRequest) =>
    requestJson<UserTrackLikeResponse>('/api/v1/user/likes', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    })

export const fetchHeroTracks = async (
    userId: string | null | undefined,
    limit: number,
    signal?: AbortSignal,
): Promise<HeroTrackResponse[]> => {
    const params = new URLSearchParams()
    if (userId && userId.trim()) {
        params.set('user_id', userId.trim())
    }
    params.set('limit', String(Math.max(1, limit)))
    const headers = new Headers({ Accept: 'application/json' })
    const response = await fetch(buildApiUrl(`/api/v1/main-page/hero-tracks?${params.toString()}`), {
        signal,
        headers,
        cache: 'no-store',
    })
    if (response.status === 204) {
        return []
    }
    if (!response.ok) {
        const payload = await readErrorPayload(response)
        throw new ApiError(payload.message, response.status, payload.code)
    }
    const payload = (await response.json()) as HeroTrackListResponse
    return payload.tracks ?? []
}

export const fetchSchedulingAdminStatus = (userId: string, signal?: AbortSignal) =>
    requestJson<SchedulingAdminResponse>(
        `/api/v1/system/admin/schedules?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const registerAccount = (payload: AuthRegistrationRequest) =>
    requestJson<AuthRegistrationResponse>('/api/v1/auth/register', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const loginAccount = (payload: AuthLoginRequest) =>
    requestJson<AuthLoginResponse>('/api/v1/auth/login', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchPlatformCatalog = (signal?: AbortSignal) =>
    requestJson<PlatformCatalogResponse>('/api/v1/platforms/catalog', { signal })

export const fetchPlatformConnectionBootstrap = (userId: string, signal?: AbortSignal) =>
    requestJson<PlatformConnectionBootstrapResponse>(
        `/api/v1/platforms/connections/bootstrap?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const fetchPlaybackCredentials = (userId: string, platformId: string, signal?: AbortSignal) =>
    requestJson<PlatformPlaybackCredentialsResponse>(
        `/api/v1/platforms/playback/credentials?user_id=${encodeURIComponent(userId)}&platform_id=${encodeURIComponent(platformId)}`,
        { signal, cache: 'no-store' },
    )

export const fetchTidalPlaybackManifestDiagnostics = (
    userId: string,
    trackId: string,
    signal?: AbortSignal,
) =>
    requestJson<TidalPlaybackManifestDiagnosticsResponse>(
        `/api/v1/platforms/playback/tidal/manifest-diagnostics?user_id=${encodeURIComponent(userId)}&track_id=${encodeURIComponent(trackId)}`,
        { signal },
    )

export const fetchTidalPlaybackStream = (
    userId: string,
    trackId: string,
    quality = 'LOSSLESS',
    signal?: AbortSignal,
) =>
    requestJson<TidalPlaybackStreamResponse>(
        `/api/v1/platforms/playback/tidal/tracks/${encodeURIComponent(trackId)}/stream?user_id=${encodeURIComponent(userId)}&quality=${encodeURIComponent(quality)}`,
        { signal },
    )

export const fetchTidalPlaybackAnalysisAudio = (
    userId: string,
    trackId: string,
    quality = 'HIGH',
    signal?: AbortSignal,
) =>
    requestArrayBuffer(
        `/api/v1/platforms/playback/tidal/tracks/${encodeURIComponent(trackId)}/analysis-audio?user_id=${encodeURIComponent(userId)}&quality=${encodeURIComponent(quality)}`,
        { signal, cache: 'no-store' },
    )

export const resolveTidalPlaybackTarget = (payload: TidalPlaybackTargetResolveRequest, signal?: AbortSignal) =>
    requestJson<TidalPlaybackTargetResolveResponse>('/api/v1/platforms/playback/tidal/resolve-track', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal,
    })

export const resolveSpotifyPlaybackTarget = (payload: SpotifyPlaybackTargetResolveRequest, signal?: AbortSignal) =>
    requestJson<SpotifyPlaybackTargetResolveResponse>('/api/v1/platforms/playback/spotify/resolve-track', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal,
    })

export const resolveYouTubePlaybackTarget = (payload: YouTubePlaybackTargetResolveRequest, signal?: AbortSignal) =>
    requestJson<YouTubePlaybackTargetResolveResponse>('/api/v1/platforms/playback/youtube/resolve-track', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal,
    })

export const connectPlatformAccount = (payload: PlatformConnectRequest) =>
    requestJson<PlatformConnectionCommandResponse>('/api/v1/platforms/connections/connect', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const disconnectPlatformAccount = (payload: PlatformDisconnectRequest) =>
    requestJson<PlatformConnectionCommandResponse>('/api/v1/platforms/connections/disconnect', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const startPlatformAuthorization = (payload: PlatformAuthorizationStartRequest) =>
    requestJson<PlatformAuthorizationStartResponse>('/api/v1/platforms/oauth/start', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const completePlatformAuthorization = (payload: PlatformAuthorizationCompleteRequest) =>
    requestJson<PlatformAuthorizationCompleteResponse>('/api/v1/platforms/oauth/complete', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const startTidalDeviceAuthorization = (payload: TidalDeviceAuthorizationStartRequest) =>
    requestJson<TidalDeviceAuthorizationStartResponse>('/api/v1/platforms/oauth/tidal/device/start', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const pollTidalDeviceAuthorization = (payload: TidalDeviceAuthorizationPollRequest) =>
    requestJson<TidalDeviceAuthorizationPollResponse>('/api/v1/platforms/oauth/tidal/device/poll', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchLastFmSignalPreview = (
    username: string,
    period: 'overall' | '7day' | '1month' | '3month' | '6month' | '12month' = '1month',
    recentLimit = 8,
    topLimit = 6,
    signal?: AbortSignal,
) =>
    requestJson<LastFmSignalPreviewResponse>(
        `/api/v1/platforms/lastfm/preview?username=${encodeURIComponent(username)}&period=${encodeURIComponent(period)}&recent_limit=${recentLimit}&top_limit=${topLimit}`,
        { signal },
    )

export const connectLastFmProfile = (payload: LastFmProfileConnectRequest) =>
    requestJson<PlatformConnectionCommandResponse>('/api/v1/platforms/lastfm/profile', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchLastFmScrobbleBootstrap = (userId: string, signal?: AbortSignal) =>
    requestJson<LastFmScrobbleBootstrapResponse>(
        `/api/v1/platforms/lastfm/scrobbles/bootstrap?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const syncLastFmScrobbles = (payload: LastFmScrobbleSyncRequest) =>
    requestJson<LastFmScrobbleSyncResponse>('/api/v1/platforms/lastfm/scrobbles/sync', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchPmsWorkspaceBootstrap = (
    userId?: string,
    playlistId?: string,
    signal?: AbortSignal,
) => {
    const searchParams = new URLSearchParams()

    if (userId) {
        searchParams.set('user_id', userId)
    }

    if (playlistId) {
        searchParams.set('playlist_id', playlistId)
    }

    const suffix = searchParams.size > 0 ? `?${searchParams.toString()}` : ''
    return requestJson<PmsWorkspaceBootstrapResponse>(`/api/v1/pms/workspace/bootstrap${suffix}`, { signal })
}

export const fetchPmsPlaylistDetail = (
    userId: string,
    playlistId: string,
    signal?: AbortSignal,
) =>
    requestJson<PmsPlaylistDetailResponse>(
        `/api/v1/pms/playlists/${encodeURIComponent(playlistId)}?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const fetchPmsPlaylistImportBootstrap = (userId: string, signal?: AbortSignal) =>
    requestJson<PmsPlaylistImportBootstrapResponse>(
        `/api/v1/pms/import/bootstrap?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const importPmsPlaylists = (payload: PmsPlaylistImportRequest) =>
    requestJson<PmsPlaylistImportResponse>('/api/v1/pms/import/playlists', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchPmsPersonalPlaylists = (userId: string, signal?: AbortSignal) =>
    requestJson<PmsPersonalPlaylistBootstrapResponse>(
        `/api/v1/pms/personal-playlists/bootstrap?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const createPmsPersonalPlaylist = (payload: PmsPersonalPlaylistCreateRequest) =>
    requestJson<PmsPersonalPlaylistCommandResponse>('/api/v1/pms/personal-playlists', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const saveTrackToPmsPersonalPlaylist = (payload: PmsPersonalPlaylistTrackSaveRequest) =>
    requestJson<PmsPersonalPlaylistCommandResponse>('/api/v1/pms/personal-playlists/tracks', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const searchEmsCollection = (payload: EmsCollectionSearchRequest) =>
    requestJson<EmsCollectionSearchResponse>('/api/v1/ems/collection/search', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchEmsSearchPlaylistTracks = (
    platformId: string,
    externalPlaylistId: string,
    userId: string,
    signal?: AbortSignal,
) =>
    requestJson<EmsCollectionSearchPlaylistTracksResponse>(
        `/api/v1/ems/collection/search/playlists/${encodeURIComponent(platformId)}/${encodeURIComponent(externalPlaylistId)}/tracks?user_id=${encodeURIComponent(userId)}`,
        { signal },
    )

export const fetchEmsPoolAdminRuns = (userId: string, signal?: AbortSignal) =>
    requestJson<EmsPoolAdminRunsResponse>(
        `/api/v1/ems/collection/admin/pool/runs?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const fetchEmsPoolAdminRun = (runId: number, userId: string, signal?: AbortSignal) =>
    requestJson<EmsPoolAdminRunDetailResponse>(
        `/api/v1/ems/collection/admin/pool/runs/${encodeURIComponent(String(runId))}?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const processEmsPoolAdminRun = (runId: number, userId: string) =>
    requestJson<EmsPoolAdminRunCommandResponse>(
        `/api/v1/ems/collection/admin/pool/runs/${encodeURIComponent(String(runId))}/process?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const retryEmsPoolAdminEntry = (runId: number, entryId: number, userId: string) =>
    requestJson<EmsPoolAdminEntryRetryResponse>(
        `/api/v1/ems/collection/admin/pool/runs/${encodeURIComponent(String(runId))}/entries/${encodeURIComponent(String(entryId))}/retry?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const deleteEmsPoolAdminRun = (runId: number, userId: string) =>
    requestJson<EmsPoolAdminRunDeleteResponse>(
        `/api/v1/ems/collection/admin/pool/runs/${encodeURIComponent(String(runId))}?user_id=${encodeURIComponent(userId)}`,
        { method: 'DELETE' },
    )

export const cleanupEmsEmptyCollectedPlaylists = (userId: string) =>
    requestJson<EmsCollectedPlaylistsCleanupResponse>(
        `/api/v1/ems/collection/admin/playlists/cleanup-empty?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const fetchEmsFloSpecial = (signal?: AbortSignal, limit: number = 120) =>
    requestJson<EmsFloSpecialResponse>(
        `/api/v1/ems/collection/flo-special?limit=${encodeURIComponent(String(limit))}`,
        { signal, cache: 'no-store' },
    )

export const fetchEmsMelonHot100 = (signal?: AbortSignal, limit: number = 1) =>
    requestJson<EmsCollectionPlaylistBrowseResponse>(
        `/api/v1/ems/collection/melon-hot-100?limit=${encodeURIComponent(String(limit))}`,
        { signal, cache: 'no-store' },
    )

export const refreshEmsFloSpecial = () =>
    requestJson<EmsFloSpecialRefreshResponse>(
        '/api/v1/ems/collection/flo-special/refresh',
        { method: 'POST' },
    )

export const runEmsAcquisition = (payload: EmsAcquisitionRunRequest) =>
    requestJson<EmsAcquisitionRunResponse>('/api/v1/ems/acquisition/run', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchEmsAcquisitionStatus = (signal?: AbortSignal) =>
    requestJson<EmsAcquisitionRunResponse>('/api/v1/ems/acquisition/status', {
        signal,
        cache: 'no-store',
    })

export const fetchEmsAcquisitionRuns = (signal?: AbortSignal) =>
    requestJson<EmsAcquisitionRunsResponse>('/api/v1/ems/acquisition/runs', {
        signal,
        cache: 'no-store',
    })

export const fetchEmsAcquisitionSourcePresets = (signal?: AbortSignal) =>
    requestJson<EmsAcquisitionSourcePresetsResponse>('/api/v1/ems/acquisition/source-presets', {
        signal,
        cache: 'no-store',
    })

export const fetchEmsAcquisitionSourceQuality = (days: number, signal?: AbortSignal) =>
    requestJson<EmsAcquisitionSourceQualityResponse>(
        `/api/v1/ems/acquisition/source-quality?days=${encodeURIComponent(String(days))}`,
        { signal, cache: 'no-store' },
    )

export const fetchRecentPlaylistQualityForAdmin = (userId: string, limit: number, signal?: AbortSignal) =>
    requestJson<PlaylistQualityRecentResponse>(
        `/api/v1/recommendations/admin/playlist-quality/recent?user_id=${encodeURIComponent(userId)}&limit=${encodeURIComponent(String(limit))}`,
        { signal, cache: 'no-store' },
    )

export const fetchFeatureCoverageForAdmin = (userId: string, targetUserId?: string, signal?: AbortSignal) => {
    const params = new URLSearchParams({ user_id: userId })
    if (targetUserId?.trim()) {
        params.set('target_user_id', targetUserId.trim())
    }
    return requestJson<FeatureCoverageAdminResponse>(
        `/api/v1/recommendations/admin/feature-coverage?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const fetchRecentRecommendationAuditLogForAdmin = (
    userId: string,
    targetUserId?: string,
    limit = 50,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({ user_id: userId, limit: String(limit) })
    if (targetUserId?.trim()) {
        params.set('target_user_id', targetUserId.trim())
    }
    return requestJson<RecommendationAuditLogRecentResponse>(
        `/api/v1/recommendations/admin/audit-log/recent?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const fetchTasteModeRolloutSummaryForAdmin = (
    userId: string,
    targetUserId?: string,
    limit = 50,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({ user_id: userId, limit: String(limit) })
    if (targetUserId?.trim()) {
        params.set('target_user_id', targetUserId.trim())
    }
    return requestJson<RecommendationTasteModeSummaryResponse>(
        `/api/v1/recommendations/admin/audit-log/taste-mode-summary?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const fetchLatestSasrecModelForAdmin = (userId: string, signal?: AbortSignal) =>
    requestJson<SasrecRegistryAdminResponse>(
        `/api/v1/recommendations/admin/sasrec/models/latest?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const promoteSasrecModelForAdmin = (userId: string, modelVersion: string) =>
    requestJson<SasrecRegistryAdminResponse>(
        `/api/v1/recommendations/admin/sasrec/models/${encodeURIComponent(modelVersion)}/promote?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const disableSasrecModelForAdmin = (userId: string, modelVersion: string) =>
    requestJson<SasrecRegistryAdminResponse>(
        `/api/v1/recommendations/admin/sasrec/models/${encodeURIComponent(modelVersion)}/disable?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const rollbackSasrecModelForAdmin = (userId: string) =>
    requestJson<SasrecRegistryAdminResponse>(
        `/api/v1/recommendations/admin/sasrec/models/rollback?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const autoTrainSasrecForAdmin = (userId: string, targetUserId?: string) => {
    const params = new URLSearchParams({ user_id: userId })
    if (targetUserId?.trim()) {
        params.set('target_user_id', targetUserId.trim())
    }
    return requestJson<SasrecAutoTrainAdminResponse>(
        `/api/v1/recommendations/admin/sasrec/models/auto-train?${params.toString()}`,
        { method: 'POST' },
    )
}

export const fetchSasrecUserStatusForAdmin = (userId: string, targetUserId: string, signal?: AbortSignal) =>
    requestJson<SasrecUserModelStatusResponse>(
        `/api/v1/recommendations/admin/sasrec/models/users/${encodeURIComponent(targetUserId)}/status?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const fetchPersonalizationProfileForAdmin = (
    userId: string,
    targetUserId: string,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({ user_id: userId, target_user_id: targetUserId })
    return requestJson<PersonalizationProfileResponse>(
        `/api/v1/recommendations/admin/personalization-profile?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const recomputePersonalizationProfileForAdmin = (
    userId: string,
    targetUserId: string,
    eventLimit?: number,
) => {
    const params = new URLSearchParams({ user_id: userId, target_user_id: targetUserId })
    if (eventLimit != null) {
        params.set('event_limit', String(eventLimit))
    }
    return requestJson<PersonalizationProfileRecomputeResponse>(
        `/api/v1/recommendations/admin/personalization-profile/recompute?${params.toString()}`,
        { method: 'POST' },
    )
}

export const lookupMusicBrainzRecordingsForAdmin = (
    userId: string,
    title: string,
    artist: string | undefined,
    limit: number,
    persist: boolean,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({
        user_id: userId,
        title,
        limit: String(limit),
        persist: persist ? 'true' : 'false',
    })
    if (artist && artist.trim()) {
        params.set('artist', artist)
    }
    return requestJson<MetadataLookupResponse>(
        `/api/v1/recommendations/admin/metadata/musicbrainz/recordings?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const lookupWikidataEntitiesForAdmin = (
    userId: string,
    title: string,
    artist: string | undefined,
    limit: number,
    persist: boolean,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({
        user_id: userId,
        title,
        limit: String(limit),
        persist: persist ? 'true' : 'false',
    })
    if (artist && artist.trim()) {
        params.set('artist', artist)
    }
    return requestJson<MetadataExternalLookupResponse>(
        `/api/v1/recommendations/admin/metadata/wikidata/entities?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const lookupDiscogsMastersForAdmin = (
    userId: string,
    title: string,
    artist: string | undefined,
    limit: number,
    persist: boolean,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({
        user_id: userId,
        title,
        limit: String(limit),
        persist: persist ? 'true' : 'false',
    })
    if (artist && artist.trim()) {
        params.set('artist', artist)
    }
    return requestJson<MetadataExternalLookupResponse>(
        `/api/v1/recommendations/admin/metadata/discogs/masters?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const listMetadataCandidatesForAdmin = (
    userId: string,
    status: string | undefined,
    limit: number,
    signal?: AbortSignal,
) => {
    const params = new URLSearchParams({
        user_id: userId,
        limit: String(limit),
    })
    if (status) {
        params.set('status', status)
    }
    return requestJson<MetadataCandidateListResponse>(
        `/api/v1/recommendations/admin/metadata/candidates?${params.toString()}`,
        { signal, cache: 'no-store' },
    )
}

export const acceptMetadataCandidateForAdmin = (
    userId: string,
    candidateId: number,
    notes: string | null,
) =>
    requestJson<MetadataCandidateCommandResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/accept?user_id=${encodeURIComponent(userId)}`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ notes }),
        },
    )

export const rejectMetadataCandidateForAdmin = (
    userId: string,
    candidateId: number,
    notes: string | null,
) =>
    requestJson<MetadataCandidateCommandResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/reject?user_id=${encodeURIComponent(userId)}`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ notes }),
        },
    )

export const autoAcceptMetadataCandidatesForAdmin = (
    userId: string,
    minScore: number,
    limit: number,
) => {
    const params = new URLSearchParams({
        user_id: userId,
        min_score: String(minScore),
        limit: String(limit),
    })
    return requestJson<MetadataCandidateAutoAcceptResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/auto-accept?${params.toString()}`,
        { method: 'POST' },
    )
}

export const applyAcceptedIsrcCandidatesForAdmin = (userId: string, limit: number) => {
    const params = new URLSearchParams({
        user_id: userId,
        limit: String(limit),
    })
    return requestJson<MetadataCandidateApplyResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/apply-accepted-isrcs?${params.toString()}`,
        { method: 'POST' },
    )
}

export const fetchMetadataCandidateAuditForAdmin = (
    userId: string,
    candidateId: number,
    signal?: AbortSignal,
) =>
    requestJson<MetadataCandidateAuditResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/audit?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const rollbackAppliedIsrcCandidateForAdmin = (
    userId: string,
    candidateId: number,
    notes: string | null,
) =>
    requestJson<MetadataCandidateRollbackResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/rollback-applied-isrc?user_id=${encodeURIComponent(userId)}`,
        {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ notes }),
        },
    )

export const promoteMetadataCandidateToCanonicalForAdmin = (
    userId: string,
    candidateId: number,
) =>
    requestJson<MetadataCandidateCanonicalPromotionResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/promote-canonical?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )

export const fetchMetadataCandidateCanonicalLinkConflictsForAdmin = (
    userId: string,
    candidateId: number,
    signal?: AbortSignal,
) =>
    requestJson<MetadataCandidateCanonicalLinkConflictResponse>(
        `/api/v1/recommendations/admin/metadata/candidates/${encodeURIComponent(String(candidateId))}/canonical-link-conflicts?user_id=${encodeURIComponent(userId)}`,
        { signal, cache: 'no-store' },
    )

export const fetchEmsCollectedPlaylists = (platformId: string = 'spotify', signal?: AbortSignal, limit: number = 12) =>
    requestJson<EmsCollectionPlaylistBrowseResponse>(
        `/api/v1/ems/collection/playlists?platform_id=${encodeURIComponent(platformId)}&limit=${encodeURIComponent(String(limit))}&random=true`,
        { signal },
    )

export const fetchEmsPlaylistSections = ({
    userId,
    platformIds = [],
    limit = 6,
    signal,
}: {
    userId?: string | null
    platformIds?: string[]
    limit?: number
    signal?: AbortSignal
}) => {
    const params = new URLSearchParams()
    if (userId) {
        params.set('user_id', userId)
    }
    platformIds.forEach((platformId) => params.append('platform_id', platformId))
    params.set('limit', String(limit))
    return requestJson<EmsCollectionPlaylistSectionsResponse>(
        `/api/v1/ems/collection/playlists/sections?${params.toString()}`,
        { signal },
    )
}

export const fetchEmsCollectedPlaylistDetail = (playlistId: number, signal?: AbortSignal) => {
    return requestJson<EmsCollectionPlaylistDetailResponse>(
        `/api/v1/ems/collection/playlists/${playlistId}`,
        { signal },
    )
}

export const fetchEmsCollectedTracks = (platformId: string = 'spotify', signal?: AbortSignal) =>
    requestJson<EmsCollectionTrackBrowseResponse>(
        `/api/v1/ems/collection/tracks?platform_id=${encodeURIComponent(platformId)}`,
        { signal },
    )

export const fetchEmsPlaylistTracks = (playlistId: number, signal?: AbortSignal) =>
    requestJson<EmsCollectionTrackBrowseResponse>(
        `/api/v1/ems/collection/playlists/${playlistId}/tracks`,
        { signal },
    )

export const analyzeEmsWorkspace = (payload: EmsWorkspaceAnalysisRequest, signal?: AbortSignal) =>
    requestJson<EmsWorkspaceAnalysisResponse>('/api/v1/ems/workspace/analysis', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal,
    })

export const fetchEmsOverview = (payload: EmsOverviewRequest, signal?: AbortSignal) =>
    requestJson<EmsOverviewResponse>('/api/v1/ems/workspace/overview', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
        signal,
    })

export const recordUserMusicEvent = (payload: UserMusicEventRequest) =>
    requestJson<UserMusicEventResponse>('/api/v1/recommendations/events', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const previewGmsRecommendations = (payload: GmsRecommendationPreviewRequest) =>
    requestJson<GmsRecommendationPreviewResponse>('/api/v1/gms/recommendations/preview', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            mode: 'gms',
            ...payload,
        }),
    })

export const recordGmsRecommendationFeedback = (payload: GmsRecommendationFeedbackRequest) =>
    requestJson<GmsRecommendationFeedbackResponse>('/api/v1/gms/recommendations/feedback', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const fetchGmsPlaylistPreview = (
    userId: string,
    limit?: number,
    signal?: AbortSignal,
    includePlaylistId?: number,
) => {
    const params = new URLSearchParams({ user_id: userId })
    if (limit != null) {
        params.set('limit', String(limit))
    }
    if (includePlaylistId != null) {
        params.set('include_playlist_id', String(includePlaylistId))
    }
    return requestJson<GmsPlaylistPreviewResponse>(
        `/api/v1/gms/playlists/preview?${params.toString()}`,
        { signal },
    )
}

export const importTidalPlaylistUrlToGms = (payload: GmsTidalPlaylistUrlImportRequest) =>
    requestJson<GmsTidalPlaylistUrlImportResponse>('/api/v1/gms/playlists/import/tidal-url', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify(payload),
    })

export const saveGmsPlaylistToPms = (
    playlistId: number,
    userId: string,
    payload?: GmsPlaylistSaveRequest,
) =>
    requestJson<GmsPlaylistSaveResponse>(
        `/api/v1/gms/playlists/${playlistId}/save?user_id=${encodeURIComponent(userId)}`,
        {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload ?? {}),
        },
    )

export const dismissGmsPlaylist = (playlistId: number, userId: string) =>
    requestJson<GmsPlaylistDismissResponse>(
        `/api/v1/gms/playlists/${playlistId}/dismiss?user_id=${encodeURIComponent(userId)}`,
        { method: 'POST' },
    )
