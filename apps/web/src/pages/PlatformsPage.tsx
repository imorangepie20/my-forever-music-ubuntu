import { useCallback, useEffect, useRef, useState } from 'react'
import { ArrowRight, BarChart3, CheckCircle2, Disc3, ExternalLink, PlayCircle, Radio, RefreshCw, Sparkles } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { resetSpotifyWebPlayer } from '@/lib/spotifyPlaybackSdk'
import { tidalReset } from '@/lib/tidalStreamPlayback'
import {
    ApiError,
    connectLastFmProfile,
    disconnectPlatformAccount,
    fetchLastFmScrobbleBootstrap,
    fetchLastFmSignalPreview,
    fetchPlatformCatalog,
    fetchPlatformConnectionBootstrap,
    pollTidalDeviceAuthorization,
    syncLastFmScrobbles,
    startPlatformAuthorization,
    startTidalDeviceAuthorization,
} from '@/services/api'
import type {
    LastFmScrobbleBootstrapResponse,
    LastFmSignalPreviewResponse,
    PlatformCatalogResponse,
    PlatformConnectionBootstrapResponse,
    TidalDeviceAuthorizationStartResponse,
    WorkspacePlatformId,
} from '@/types/api'

const stageLabel: Record<string, string> = {
    'planned-provider-not-enabled': 'Provider Not Enabled',
    'planned-provider-next': 'Next Provider',
    'planned-provider-after-tidal': 'After TIDAL',
    'deferred-developer-account': 'Developer Account Deferred',
    'priority-import-source': 'Priority Import Source',
    'testing-provider': 'Provider Under Test',
    'analysis-signal-source': 'Analysis Signal Source',
}

const OAUTH_STORAGE_KEY = 'my-forever-music.platform-oauth-session'
const PMS_ONBOARDING_PATH = '/pms?from=platform-import&auto_import=all'
const TIDAL_DEVICE_EXPIRED_MESSAGE = 'TIDAL 인증 코드가 만료되었습니다. 새 코드를 발급받아 다시 인증해 주세요.'
const TIDAL_DEVICE_AUTO_POLL_MAX_ATTEMPTS = 30
const workspacePlatformIds: WorkspacePlatformId[] = ['spotify', 'apple-music', 'tidal', 'youtube-music', 'last-fm']

const isWorkspacePlatformId = (value: string | null): value is WorkspacePlatformId =>
    Boolean(value && workspacePlatformIds.includes(value as WorkspacePlatformId))

const getTidalVerificationUrl = (authorization: TidalDeviceAuthorizationStartResponse['authorization']) =>
    authorization.verification_uri_complete ?? authorization.verification_uri

const clearPendingOAuthStorage = () => {
    if (typeof window === 'undefined') {
        return
    }

    const prefix = `${OAUTH_STORAGE_KEY}.`
    for (let index = window.sessionStorage.length - 1; index >= 0; index -= 1) {
        const key = window.sessionStorage.key(index)
        if (key?.startsWith(prefix)) {
            window.sessionStorage.removeItem(key)
        }
    }
}

const resetLocalPlaybackAuthorization = (userId: string, platformId: WorkspacePlatformId) => {
    if (platformId === 'spotify') {
        resetSpotifyWebPlayer(userId)
        return
    }
    if (platformId === 'tidal') {
        void tidalReset().catch(() => undefined)
    }
}

const PlatformsPage = () => {
    const navigate = useNavigate()
    const [searchParams] = useSearchParams()
    const { session, updateSession } = useAuthSession()
    const { workspace, updateWorkspace } = useRecommendationWorkspace()
    const autoConnectStartedRef = useRef(false)
    const tidalAutoPollAttemptsRef = useRef(0)
    const [catalog, setCatalog] = useState<PlatformCatalogResponse | null>(null)
    const [connectionBootstrap, setConnectionBootstrap] = useState<PlatformConnectionBootstrapResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [isMutating, setIsMutating] = useState<string | null>(null)
    const [error, setError] = useState<string | null>(null)
    const [lastFmUsername, setLastFmUsername] = useState('')
    const [lastFmPeriod, setLastFmPeriod] = useState<'overall' | '7day' | '1month' | '3month' | '6month' | '12month'>('1month')
    const [lastFmPreview, setLastFmPreview] = useState<LastFmSignalPreviewResponse | null>(null)
    const [lastFmScrobbleBootstrap, setLastFmScrobbleBootstrap] = useState<LastFmScrobbleBootstrapResponse | null>(null)
    const [lastFmPreviewError, setLastFmPreviewError] = useState<string | null>(null)
    const [isLastFmPreviewLoading, setIsLastFmPreviewLoading] = useState(false)
    const [isLastFmSyncing, setIsLastFmSyncing] = useState(false)
    const [tidalDeviceAuthorization, setTidalDeviceAuthorization] = useState<TidalDeviceAuthorizationStartResponse | null>(null)
    const [tidalDeviceStatus, setTidalDeviceStatus] = useState<'idle' | 'waiting' | 'checking' | 'expired' | 'importing' | 'completed'>('idle')
    const [tidalDeviceMessage, setTidalDeviceMessage] = useState<string | null>(null)
    const preferredConnection = connectionBootstrap?.connections.find((connection) => connection.preferred) ?? null

    useEffect(() => {
        const controller = new AbortController()

        setIsLoading(true)
        setError(null)

        fetchPlatformCatalog(controller.signal)
            .then((response) => {
                setCatalog(response)
                if (!session) {
                    setConnectionBootstrap(null)
                    setLastFmScrobbleBootstrap(null)
                    return null
                }

                return Promise.all([
                    fetchPlatformConnectionBootstrap(session.userId, controller.signal),
                    fetchLastFmScrobbleBootstrap(session.userId, controller.signal),
                ])
            })
            .then((payload) => {
                if (payload) {
                    const [bootstrap, scrobbleBootstrap] = payload
                    setConnectionBootstrap(bootstrap)
                    setLastFmScrobbleBootstrap(scrobbleBootstrap)
                    setLastFmUsername((current) =>
                        current || bootstrap.user.last_fm_username || '',
                    )
                }
                setError(null)
            })
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }

                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load the platform catalog from the Spring Boot API.'

                setCatalog(null)
                setError(message)
            })
            .finally(() => {
                setIsLoading(false)
            })

        return () => controller.abort()
    }, [session])

    const reloadConnections = useCallback(async () => {
        if (!session) {
            return
        }

        const bootstrap = await fetchPlatformConnectionBootstrap(session.userId)
        setConnectionBootstrap(bootstrap)
        updateSession({
            preferredPlatformId: bootstrap.user.preferred_platform_id,
            onboardingStage: bootstrap.summary.onboarding_stage,
            nextStepPath: bootstrap.summary.next_step_path,
            nextStepMessage: bootstrap.summary.next_step_message,
            platformConnectionRequired: !bootstrap.summary.preferred_platform_connected,
        })
    }, [session, updateSession])

    const reloadLastFmScrobbles = async () => {
        if (!session) {
            return
        }

        const bootstrap = await fetchLastFmScrobbleBootstrap(session.userId)
        setLastFmScrobbleBootstrap(bootstrap)
    }

    const startTidalDeviceAuthorizationFlow = useCallback(async () => {
        if (!session) {
            throw new Error('Create an account first so platform onboarding can attach to a user.')
        }

        clearPendingOAuthStorage()
        resetLocalPlaybackAuthorization(session.userId, 'tidal')
        const response = await startTidalDeviceAuthorization({
            user_id: session.userId,
        })
        tidalAutoPollAttemptsRef.current = 0
        setTidalDeviceAuthorization(response)
        setTidalDeviceStatus('waiting')
        setTidalDeviceMessage(
            'TIDAL 로그인 페이지를 새 탭에서 열었습니다. TIDAL에서 로그인·승인한 뒤 이 화면으로 돌아오면 연결 상태가 자동으로 확인됩니다. 새 탭이 안 열렸다면 아래 "TIDAL 인증 페이지 열기" 버튼을 눌러주세요.',
        )
        const verificationUrl = getTidalVerificationUrl(response.authorization)
        if (verificationUrl && typeof window !== 'undefined') {
            window.open(verificationUrl, '_blank', 'noopener,noreferrer')
        }
    }, [session])

    const handleRestartTidalDeviceAuthorization = useCallback(async () => {
        setIsMutating('tidal')
        setError(null)

        try {
            await startTidalDeviceAuthorizationFlow()
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : requestError instanceof Error
                        ? requestError.message
                        : 'Unable to start TIDAL device authorization right now.'
            setTidalDeviceMessage(message)
            setError(message)
        } finally {
            setIsMutating(null)
        }
    }, [startTidalDeviceAuthorizationFlow])

    const handleConnectToggle = useCallback(async (
        platformId: WorkspacePlatformId,
        connected: boolean,
        reconnectRequired = false,
    ) => {
        if (!session) {
            setError('Create an account first so platform onboarding can attach to a user.')
            return
        }

        setIsMutating(platformId)
        setError(null)

        try {
            if (connected && !reconnectRequired) {
                await disconnectPlatformAccount({
                    user_id: session.userId,
                    platform_id: platformId,
                })
                resetLocalPlaybackAuthorization(session.userId, platformId)
                if (platformId === 'tidal') {
                    tidalAutoPollAttemptsRef.current = 0
                    setTidalDeviceAuthorization(null)
                    setTidalDeviceStatus('idle')
                    setTidalDeviceMessage(null)
                }

                await reloadConnections()
            } else {
                if (platformId === 'tidal') {
                    await startTidalDeviceAuthorizationFlow()
                    return
                }

                const response = await startPlatformAuthorization({
                    user_id: session.userId,
                    platform_id: platformId,
                })

                if (typeof window !== 'undefined') {
                    clearPendingOAuthStorage()
                    resetLocalPlaybackAuthorization(session.userId, platformId)
                    window.sessionStorage.setItem(
                        `${OAUTH_STORAGE_KEY}.${response.authorization.state}`,
                        JSON.stringify(response),
                    )
                }

                if (
                    response.authorization.authorization_channel === 'external_browser_redirect' &&
                    response.authorization.external_authorization_url
                ) {
                    window.location.assign(response.authorization.external_authorization_url)
                    return
                }

                if (!response.authorization.approval_page_path) {
                    throw new Error('Authorization approval page is missing for the current platform mode.')
                }

                navigate(response.authorization.approval_page_path)
                return
            }
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to update platform connection state right now.'
            setError(message)
        } finally {
            setIsMutating(null)
        }
    }, [navigate, reloadConnections, session, startTidalDeviceAuthorizationFlow])

    const handleCheckTidalDeviceAuthorization = useCallback(async () => {
        if (!session || !tidalDeviceAuthorization) {
            return
        }

        const expiresAtMs = Date.parse(tidalDeviceAuthorization.authorization.expires_at)
        if (Number.isFinite(expiresAtMs) && Date.now() >= expiresAtMs) {
            setTidalDeviceStatus('expired')
            setTidalDeviceMessage(TIDAL_DEVICE_EXPIRED_MESSAGE)
            return
        }

        setTidalDeviceStatus('checking')
        setTidalDeviceMessage('TIDAL 승인 결과를 확인하고 있습니다.')

        try {
            const response = await pollTidalDeviceAuthorization({
                user_id: session.userId,
                device_code: tidalDeviceAuthorization.authorization.device_code,
            })

            if (response.status === 'authorization_completed') {
                if (!response.connection) {
                    throw new Error('TIDAL 연결 결과를 확인할 수 없습니다.')
                }

                resetLocalPlaybackAuthorization(response.connection.user_id, 'tidal')
                setTidalDeviceStatus('importing')
                setTidalDeviceMessage('TIDAL 연결이 완료되었습니다. PMS 화면에서 원본 플레이리스트 가져오기를 바로 시작합니다.')
                await reloadConnections()
                updateWorkspace({
                    userId: response.connection.user_id,
                    preferredPlatformId: session.preferredPlatformId,
                })
                updateSession({
                    onboardingStage: session.preferredPlatformId === 'tidal' ? 'import-playlists' : 'connect-platform',
                    platformConnectionRequired: session.preferredPlatformId !== 'tidal',
                    nextStepPath: session.preferredPlatformId === 'tidal' ? '/pms' : '/platforms',
                    nextStepMessage: response.message ?? 'TIDAL 연결이 완료되었습니다.',
                })

                if (session.preferredPlatformId === 'tidal') {
                    navigate(PMS_ONBOARDING_PATH, { replace: true })
                    return
                }

                setTidalDeviceStatus('completed')
                setTidalDeviceMessage(response.message ?? 'TIDAL 연결이 완료되었습니다.')
                return
            }

            setTidalDeviceStatus('waiting')
            setTidalDeviceMessage(
                response.status === 'slow_down'
                    ? 'TIDAL 확인 간격이 늘어났습니다. 잠시 뒤 다시 연결 확인을 눌러 주세요.'
                    : tidalAutoPollAttemptsRef.current >= TIDAL_DEVICE_AUTO_POLL_MAX_ATTEMPTS
                        ? '자동 확인을 잠시 멈췄습니다. TIDAL에서 코드를 입력했다면 연결 확인을 눌러 주세요.'
                        : 'TIDAL에서 아직 코드 승인이 끝나지 않았습니다. 인증 페이지에서 위 코드를 입력하고 승인을 완료해 주세요.',
            )
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : requestError instanceof Error
                        ? requestError.message
                        : 'TIDAL device 인증 상태를 확인하지 못했습니다.'
            setTidalDeviceMessage(message)
            setTidalDeviceStatus('waiting')
            setError(message)
        }
    }, [
        navigate,
        reloadConnections,
        session,
        tidalDeviceAuthorization,
        updateSession,
        updateWorkspace,
    ])

    useEffect(() => {
        if (!session || !tidalDeviceAuthorization || tidalDeviceStatus !== 'waiting') {
            return
        }

        if (tidalAutoPollAttemptsRef.current >= TIDAL_DEVICE_AUTO_POLL_MAX_ATTEMPTS) {
            return
        }

        const expiresAtMs = Date.parse(tidalDeviceAuthorization.authorization.expires_at)
        if (Number.isFinite(expiresAtMs) && Date.now() >= expiresAtMs) {
            return
        }

        const intervalSeconds = Math.max(1, tidalDeviceAuthorization.authorization.interval_seconds || 1)
        const timeoutId = window.setTimeout(() => {
            tidalAutoPollAttemptsRef.current += 1
            void handleCheckTidalDeviceAuthorization()
        }, intervalSeconds * 1000)

        return () => window.clearTimeout(timeoutId)
    }, [handleCheckTidalDeviceAuthorization, session, tidalDeviceAuthorization, tidalDeviceStatus])

    useEffect(() => {
        if (
            !tidalDeviceAuthorization
            || (tidalDeviceStatus !== 'waiting' && tidalDeviceStatus !== 'checking')
        ) {
            return
        }

        const expiresAtMs = Date.parse(tidalDeviceAuthorization.authorization.expires_at)
        if (!Number.isFinite(expiresAtMs)) {
            return
        }

        const delayMs = expiresAtMs - Date.now()
        if (delayMs <= 0) {
            setTidalDeviceStatus('expired')
            setTidalDeviceMessage(TIDAL_DEVICE_EXPIRED_MESSAGE)
            return
        }

        const timeoutId = window.setTimeout(() => {
            setTidalDeviceStatus('expired')
            setTidalDeviceMessage(TIDAL_DEVICE_EXPIRED_MESSAGE)
        }, delayMs)

        return () => window.clearTimeout(timeoutId)
    }, [tidalDeviceAuthorization, tidalDeviceStatus])

    const handleLoadLastFmPreview = async () => {
        if (!lastFmUsername.trim()) {
            setLastFmPreviewError('Enter a Last.fm username first.')
            return
        }

        setIsLastFmPreviewLoading(true)
        setLastFmPreviewError(null)

        try {
            const preview = await fetchLastFmSignalPreview(lastFmUsername.trim(), lastFmPeriod, 8, 6)
            setLastFmPreview(preview)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to load the Last.fm signal preview right now.'
            setLastFmPreview(null)
            setLastFmPreviewError(message)
        } finally {
            setIsLastFmPreviewLoading(false)
        }
    }

    const handleSaveLastFmProfile = async () => {
        if (!session) {
            setLastFmPreviewError('Create an account first so Last.fm profile data can be attached to a user.')
            return
        }

        if (!lastFmUsername.trim()) {
            setLastFmPreviewError('Enter a Last.fm username first.')
            return
        }

        setIsMutating('last-fm')
        setLastFmPreviewError(null)

        try {
            await connectLastFmProfile({
                user_id: session.userId,
                username: lastFmUsername.trim(),
            })
            await reloadConnections()
            await reloadLastFmScrobbles()
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to save the Last.fm signal profile right now.'
            setLastFmPreviewError(message)
        } finally {
            setIsMutating(null)
        }
    }

    const handleSyncLastFmScrobbles = async () => {
        if (!session) {
            setLastFmPreviewError('Create an account first so Last.fm scrobbles can be attached to a user.')
            return
        }

        setIsLastFmSyncing(true)
        setLastFmPreviewError(null)

        try {
            await syncLastFmScrobbles({
                user_id: session.userId,
                limit: 40,
            })
            await reloadLastFmScrobbles()
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to sync recent Last.fm scrobbles right now.'
            setLastFmPreviewError(message)
        } finally {
            setIsLastFmSyncing(false)
        }
    }

    useEffect(() => {
        const requestedPlatformId = searchParams.get('connect')

        if (!requestedPlatformId || autoConnectStartedRef.current) {
            return
        }

        if (!isWorkspacePlatformId(requestedPlatformId)) {
            autoConnectStartedRef.current = true
            setError('가입 후 인증을 시작할 플랫폼을 확인할 수 없습니다.')
            navigate('/platforms', { replace: true })
            return
        }

        if (!session || !connectionBootstrap) {
            return
        }

        const requestedConnection = connectionBootstrap.connections.find(
            (connection) => connection.platform_id === requestedPlatformId,
        )

        if (!requestedConnection) {
            autoConnectStartedRef.current = true
            setError('선택한 플랫폼 인증 정보를 불러오지 못했습니다. 플랫폼 연결 화면에서 다시 시도해 주세요.')
            navigate('/platforms', { replace: true })
            return
        }

        autoConnectStartedRef.current = true

        const nextSearchParams = new URLSearchParams(searchParams)
        nextSearchParams.delete('connect')
        nextSearchParams.delete('from')
        const nextSearch = nextSearchParams.toString()
        navigate(
            {
                pathname: '/platforms',
                search: nextSearch ? `?${nextSearch}` : '',
            },
            { replace: true },
        )

        if (requestedConnection.connected && !requestedConnection.reconnect_required) {
            return
        }

        void handleConnectToggle(
            requestedConnection.platform_id,
            requestedConnection.connected,
            requestedConnection.reconnect_required,
        )
    }, [connectionBootstrap, handleConnectToggle, navigate, searchParams, session])

    const handleUseLastFmSignal = () => {
        if (!lastFmPreview) {
            return
        }

        updateWorkspace({
            preferredPlatformId: 'last-fm',
        })
    }

    const tidalVerificationUrl = tidalDeviceAuthorization
        ? getTidalVerificationUrl(tidalDeviceAuthorization.authorization)
        : null

    return (
        <div className="space-y-6">
            {tidalDeviceAuthorization && (
                <HudCard
                    title="TIDAL 인증 코드"
                    subtitle="TIDAL에서 코드를 입력한 뒤 인증이 끝나면 TIDAL 탭을 닫고 이 화면으로 돌아오세요. PMS 원본 플레이리스트 가져오기가 이어집니다"
                >
                    <div className="grid gap-5 lg:grid-cols-[0.82fr_1.18fr]">
                        <div className="rounded-2xl border border-hud-border-primary bg-hud-accent-primary/10 p-5">
                            <p className="text-xs uppercase tracking-[0.22em] text-hud-accent-primary">
                                인증 코드
                            </p>
                            <p className="mt-3 font-mono text-3xl font-semibold tracking-[0.16em] text-hud-text-primary">
                                {tidalDeviceAuthorization.authorization.user_code}
                            </p>
                            <p className="mt-3 text-sm leading-6 text-hud-text-secondary">
                                TIDAL 인증 페이지에서 이 코드를 입력하세요. 인증이 끝나면 TIDAL 탭을 닫고 이 화면으로 돌아오세요.
                            </p>
                        </div>

                        <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-5">
                            <div className="flex flex-wrap items-start justify-between gap-3">
                                <div>
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        인증 상태
                                    </p>
                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                        {tidalDeviceMessage ?? 'TIDAL 인증을 준비하고 있습니다.'}
                                    </p>
                                </div>
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs text-hud-text-secondary">
                                    {tidalDeviceStatus === 'importing'
                                        ? 'PMS 저장 중'
                                        : tidalDeviceStatus === 'completed'
                                            ? '연결 완료'
                                            : tidalDeviceStatus === 'expired'
                                                ? '코드 만료'
                                                : tidalDeviceStatus === 'checking'
                                                    ? '확인 중'
                                                    : '코드 입력 대기'}
                                </span>
                            </div>

                            <div className="mt-5 flex flex-wrap gap-3">
                                <a
                                    href={tidalVerificationUrl ?? '#'}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="btn-glow inline-flex items-center justify-center gap-2 rounded-lg bg-hud-accent-primary px-4 py-2 text-sm font-medium text-hud-bg-primary transition-hud hover:bg-hud-accent-primary/90"
                                >
                                    TIDAL 인증 페이지 열기
                                    <ExternalLink size={16} />
                                </a>
                                <Button
                                    type="button"
                                    variant="primary"
                                    disabled={
                                        tidalDeviceStatus === 'checking'
                                        || tidalDeviceStatus === 'expired'
                                        || tidalDeviceStatus === 'importing'
                                        || tidalDeviceStatus === 'completed'
                                    }
                                    onClick={handleCheckTidalDeviceAuthorization}
                                >
                                    {tidalDeviceStatus === 'checking'
                                        ? '확인 중...'
                                        : '코드 입력 완료, 연결 확인'}
                                </Button>
                                <Button
                                    type="button"
                                    variant="outline"
                                    disabled={
                                        isMutating === 'tidal'
                                        || tidalDeviceStatus === 'checking'
                                        || tidalDeviceStatus === 'importing'
                                        || tidalDeviceStatus === 'completed'
                                    }
                                    onClick={handleRestartTidalDeviceAuthorization}
                                >
                                    {isMutating === 'tidal' ? '새 코드 발급 중...' : '새 TIDAL 코드 발급'}
                                </Button>
                                <Button
                                    type="button"
                                    variant="outline"
                                    onClick={() => {
                                        tidalAutoPollAttemptsRef.current = 0
                                        setTidalDeviceAuthorization(null)
                                        setTidalDeviceStatus('idle')
                                        setTidalDeviceMessage(null)
                                    }}
                                >
                                    닫기
                                </Button>
                            </div>
                            {tidalVerificationUrl && (
                                <p className="mt-4 text-xs leading-5 text-hud-text-muted">
                                    버튼이 열리지 않으면 이 주소를 새 탭에서 직접 열고 위 인증 코드를 입력하세요:{' '}
                                    <a
                                        href={tidalVerificationUrl}
                                        target="_blank"
                                        rel="noreferrer"
                                        className="break-all text-hud-accent-primary hover:underline"
                                    >
                                        {tidalVerificationUrl}
                                    </a>
                                </p>
                            )}
                        </div>
                    </div>
                </HudCard>
            )}

            <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
                <HudCard className="overflow-hidden">
                    <div className="relative">
                        <div className="absolute inset-x-0 top-0 h-44 rounded-3xl bg-gradient-to-r from-hud-accent-primary/20 via-cyan-300/10 to-hud-accent-secondary/15 blur-3xl" />
                        <div className="relative">
                            <p className="text-xs font-semibold uppercase tracking-[0.28em] text-hud-accent-primary">
                                Platform Intake
                            </p>
                            <h2 className="mt-4 max-w-3xl text-3xl font-semibold tracking-tight text-hud-text-primary sm:text-4xl">
                                Pick the streaming platform that should feed PMS first.
                            </h2>
                            <p className="mt-4 max-w-2xl text-base leading-7 text-hud-text-secondary">
                                This route is the first implementation slice of the onboarding story from
                                `PROJECT_KEY_SERVICE.md`: choose a subscribed platform, preserve PMS metadata first,
                                and define how audio features will be enriched afterward.
                            </p>

                            <div className="mt-8 flex flex-wrap gap-3">
                                {connectionBootstrap?.summary.next_step_path ? (
                                    <Link to={connectionBootstrap.summary.next_step_path}>
                                        <Button type="button" variant="primary" glow>
                                            {connectionBootstrap.summary.preferred_platform_connected
                                                ? 'Continue to PMS'
                                                : connectionBootstrap.summary.preferred_platform_reconnect_required
                                                    ? 'Reconnect Preferred Platform'
                                                    : 'Stay in Platform Onboarding'}
                                            <ArrowRight size={16} />
                                        </Button>
                                    </Link>
                                ) : (
                                    <Link to="/signup">
                                        <Button type="button" variant="primary" glow>
                                            Start Signup
                                            <ArrowRight size={16} />
                                        </Button>
                                    </Link>
                                )}
                                <Link to="/ems">
                                    <Button type="button" variant="outline">
                                        Open EMS
                                    </Button>
                                </Link>
                            </div>
                        </div>
                    </div>
                </HudCard>

                <HudCard
                    title="Current Preference"
                    subtitle="Saved in the shared workspace context"
                    action={
                        isLoading ? (
                            <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                                <RefreshCw size={14} className="animate-spin" />
                                Loading
                            </span>
                        ) : null
                    }
                >
                    <div className="space-y-4">
                        <div className="rounded-2xl border border-hud-border-primary bg-hud-accent-primary/10 p-4">
                            <p className="text-xs uppercase tracking-[0.24em] text-hud-accent-primary">
                                {session ? 'Signed-in Member' : 'Preferred Platform'}
                            </p>
                            {session ? (
                                <>
                                    <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                        {session.displayName}
                                    </p>
                                    <p className="mt-2 text-sm text-hud-text-secondary">{session.email}</p>
                                </>
                            ) : (
                                <p className="mt-2 text-2xl font-semibold capitalize text-hud-text-primary">
                                    {workspace.preferredPlatformId.replace('-', ' ')}
                                </p>
                            )}
                        </div>

                        <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                            <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                Onboarding State
                            </p>
                            <p className="mt-2 text-sm font-medium text-hud-text-primary">
                                {connectionBootstrap?.summary.onboarding_stage ?? 'signup-required'}
                            </p>
                            <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                {connectionBootstrap?.summary.next_step_message ??
                                    'Create an account first so platform connection state can be attached to a user.'}
                            </p>
                        </div>

                        {connectionBootstrap?.summary.preferred_platform_reconnect_required && preferredConnection && (
                            <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4">
                                <p className="text-xs uppercase tracking-[0.22em] text-hud-accent-warning">
                                    Reconnect Required
                                </p>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    {preferredConnection.display_name} is still attached to this account, but the saved
                                    session is no longer usable for PMS import. Reconnect the platform to continue.
                                </p>
                                <div className="mt-4">
                                    <Button
                                        type="button"
                                        variant="outline"
                                        disabled={isMutating === preferredConnection.platform_id}
                                        onClick={() =>
                                            handleConnectToggle(
                                                preferredConnection.platform_id,
                                                preferredConnection.connected,
                                                true,
                                            )
                                        }
                                    >
                                        {isMutating === preferredConnection.platform_id
                                            ? 'Working...'
                                            : `Reconnect ${preferredConnection.display_name}`}
                                    </Button>
                                </div>
                            </div>
                        )}

                        {error && (
                            <div className="rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {error}
                            </div>
                        )}
                    </div>
                </HudCard>
            </section>

            <section className="grid gap-6 xl:grid-cols-[1fr_1.1fr]">
                <HudCard title="Onboarding Flow" subtitle="How platform selection feeds the product loop">
                    {session ? (
                        <div className="mb-4 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4 text-sm leading-6 text-hud-text-secondary">
                            Current account: <span className="font-medium text-hud-text-primary">{session.displayName}</span>
                            {' '}with preferred source{' '}
                            <span className="font-medium capitalize text-hud-text-primary">
                                {session.preferredPlatformId.replace('-', ' ')}
                            </span>
                            .
                        </div>
                    ) : (
                        <div className="mb-4 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-4 text-sm leading-6 text-hud-text-secondary">
                            Create an account first. Platform connection bootstrap needs a user to attach onboarding state.
                        </div>
                    )}

                    {catalog ? (
                        <div className="space-y-4">
                            {catalog.onboarding_flow.map((step, index) => (
                                <div
                                    key={step}
                                    className="flex items-start gap-4 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4"
                                >
                                    <span className="flex h-9 w-9 items-center justify-center rounded-full bg-hud-accent-primary/10 text-sm font-semibold text-hud-accent-primary">
                                        {index + 1}
                                    </span>
                                    <p className="text-sm leading-6 text-hud-text-secondary">{step}</p>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="text-sm leading-6 text-hud-text-secondary">
                            Platform onboarding guidance will appear here after the catalog loads.
                        </div>
                    )}
                </HudCard>

                <HudCard title="Supported Platforms" subtitle="Current product interpretation of platform roles">
                    {catalog ? (
                        <div className="grid gap-4">
                            {catalog.platforms.map((platform) => {
                                const isPreferred = workspace.preferredPlatformId === platform.platform_id
                                const currentConnection = connectionBootstrap?.connections.find(
                                    (connection) => connection.platform_id === platform.platform_id,
                                )
                                const providerEnabled = platform.pms_import_supported || platform.platform_id === 'last-fm'

                                return (
                                    <div
                                        key={platform.platform_id}
                                        className={`rounded-2xl border p-5 transition-hud ${
                                            isPreferred
                                                ? 'border-hud-border-primary bg-hud-accent-primary/10'
                                                : 'border-hud-border-secondary bg-hud-bg-primary/70'
                                        }`}
                                    >
                                        <div className="flex flex-wrap items-start justify-between gap-3">
                                            <div>
                                                <div className="flex items-center gap-3">
                                                    <span className="rounded-2xl bg-hud-accent-primary/10 p-3 text-hud-accent-primary">
                                                        {platform.platform_id === 'spotify' ? (
                                                            <Disc3 size={20} />
                                                        ) : platform.platform_id === 'apple-music' ? (
                                                            <Sparkles size={20} />
                                                        ) : platform.platform_id === 'youtube-music' ? (
                                                            <PlayCircle size={20} />
                                                        ) : platform.platform_id === 'last-fm' ? (
                                                            <BarChart3 size={20} />
                                                        ) : (
                                                            <Radio size={20} />
                                                        )}
                                                    </span>
                                                    <div>
                                                        <p className="text-lg font-semibold text-hud-text-primary">
                                                            {platform.display_name}
                                                        </p>
                                                        <p className="mt-1 text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                                            {stageLabel[platform.integration_stage] ?? platform.integration_stage}
                                                        </p>
                                                    </div>
                                                </div>
                                            </div>

                                            <Button
                                                type="button"
                                                variant={isPreferred ? 'primary' : 'outline'}
                                                disabled={!providerEnabled}
                                                onClick={() =>
                                                    updateWorkspace({ preferredPlatformId: platform.platform_id })
                                                }
                                            >
                                                {!providerEnabled ? (
                                                    'Not Enabled'
                                                ) : isPreferred ? (
                                                    <>
                                                        <CheckCircle2 size={16} />
                                                        Preferred
                                                    </>
                                                ) : (
                                                    'Use as Source'
                                                )}
                                            </Button>
                                            <Button
                                                type="button"
                                                variant={
                                                    currentConnection?.reconnect_required
                                                        ? 'primary'
                                                        : currentConnection?.connected
                                                            ? 'secondary'
                                                            : 'ghost'
                                                }
                                                disabled={!session || !providerEnabled || isMutating === platform.platform_id}
                                                onClick={() =>
                                                    platform.platform_id === 'last-fm' && !(currentConnection?.connected ?? false)
                                                        ? handleSaveLastFmProfile()
                                                        : handleConnectToggle(
                                                            platform.platform_id,
                                                            currentConnection?.connected ?? false,
                                                            currentConnection?.reconnect_required ?? false,
                                                        )
                                                }
                                            >
                                                {isMutating === platform.platform_id
                                                    ? 'Working...'
                                                    : !providerEnabled
                                                        ? 'Provider Not Enabled'
                                                        : platform.platform_id === 'last-fm' && !(currentConnection?.connected ?? false)
                                                        ? 'Save Signal Profile'
                                                        : currentConnection?.next_action_label ?? 'Connect'}
                                            </Button>
                                        </div>

                                        <div className="mt-5 grid gap-3 md:grid-cols-2">
                                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                                <p className="text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                                    PMS Role
                                                </p>
                                                <p className="mt-2 text-sm leading-6 text-hud-text-primary">
                                                    {platform.pms_role}
                                                </p>
                                            </div>
                                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                                <p className="text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                                    EMS Role
                                                </p>
                                                <p className="mt-2 text-sm leading-6 text-hud-text-primary">
                                                    {platform.ems_role}
                                                </p>
                                            </div>
                                        </div>

                                        <div className="mt-4 flex flex-wrap gap-2">
                                            <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs text-hud-text-secondary">
                                                PMS import {platform.pms_import_supported ? 'available' : 'not enabled'}
                                            </span>
                                            <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs text-hud-text-secondary">
                                                EMS collect {platform.ems_collection_supported ? 'available' : 'not enabled'}
                                            </span>
                                            <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs text-hud-text-secondary">
                                                {platform.audio_feature_strategy}
                                            </span>
                                            {currentConnection?.connected && (
                                                <span className="rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-xs text-hud-accent-primary">
                                                    connected
                                                </span>
                                            )}
                                            {currentConnection?.reconnect_required && (
                                                <span className="rounded-full border border-hud-accent-warning/40 bg-hud-accent-warning/10 px-3 py-1 text-xs text-hud-accent-warning">
                                                    reconnect required
                                                </span>
                                            )}
                                        </div>

                                        {currentConnection?.external_account_label && (
                                            <div className="mt-4 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                                <p className="text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                                    Connection Label
                                                </p>
                                                <p className="mt-2 text-sm text-hud-text-primary">
                                                    {currentConnection.external_account_label}
                                                </p>
                                            </div>
                                        )}

                                        {currentConnection?.reconnect_required && (
                                            <div className="mt-4 rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                                The saved session for {platform.display_name} has expired or no longer
                                                has a usable token. Use <span className="font-medium text-hud-text-primary">
                                                    {currentConnection.next_action_label}
                                                </span>{' '}
                                                to restore PMS import access.
                                            </div>
                                        )}

                                        <div className="mt-4 space-y-2 text-sm leading-6 text-hud-text-secondary">
                                            {platform.notes.map((note) => (
                                                <p key={note}>{note}</p>
                                            ))}
                                        </div>
                                    </div>
                                )
                            })}
                        </div>
                    ) : (
                        <div className="text-sm leading-6 text-hud-text-secondary">
                            Supported platform details will appear here after the API responds.
                        </div>
                    )}
                </HudCard>
            </section>

            <section className="grid gap-6 xl:grid-cols-[0.92fr_1.08fr]">
                <HudCard
                    title="Last.fm Signal Preview"
                    subtitle="Public scrobble and affinity snapshot before full account linking"
                >
                    <div className="space-y-4">
                        <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4 text-sm leading-6 text-hud-text-secondary">
                            Use a public Last.fm username to inspect recent scrobbles, top artists, and top tracks.
                            This preview is aimed at EMS/GMS signal shaping, not PMS playlist import.
                        </div>

                        {session?.preferredPlatformId === 'last-fm' && (
                            <div className="rounded-2xl border border-hud-accent-primary/30 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                This account prefers <span className="font-medium text-hud-text-primary">Last.fm</span>.
                                Save or sync the profile so EMS/GMS can treat scrobbles as long-term listening signal.
                            </div>
                        )}

                        <div className="grid gap-4 md:grid-cols-[1.2fr_0.8fr]">
                            <div>
                                <label className="mb-2 block text-sm text-hud-text-secondary">Last.fm Username</label>
                                <input
                                    type="text"
                                    value={lastFmUsername}
                                    onChange={(event) => setLastFmUsername(event.target.value)}
                                    placeholder="lastfm-user-name"
                                    className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-hud-text-primary placeholder-hud-text-muted focus:border-hud-accent-primary focus:outline-none transition-hud"
                                />
                            </div>
                            <div>
                                <label className="mb-2 block text-sm text-hud-text-secondary">Signal Window</label>
                                <select
                                    value={lastFmPeriod}
                                    onChange={(event) => setLastFmPeriod(event.target.value as typeof lastFmPeriod)}
                                    className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-hud-text-primary focus:border-hud-accent-primary focus:outline-none transition-hud"
                                >
                                    <option value="7day">Last 7 Days</option>
                                    <option value="1month">Last Month</option>
                                    <option value="3month">Last 3 Months</option>
                                    <option value="6month">Last 6 Months</option>
                                    <option value="12month">Last 12 Months</option>
                                    <option value="overall">Overall</option>
                                </select>
                            </div>
                        </div>

                        <div className="flex flex-wrap gap-3">
                            <Button
                                type="button"
                                variant="primary"
                                glow
                                disabled={isLastFmPreviewLoading}
                                onClick={handleLoadLastFmPreview}
                            >
                                {isLastFmPreviewLoading ? 'Loading Preview...' : 'Load Last.fm Preview'}
                            </Button>
                            {lastFmPreview && (
                                <Button
                                    type="button"
                                    variant="outline"
                                    onClick={handleUseLastFmSignal}
                                >
                                    Use Last.fm as Signal Source
                                </Button>
                            )}
                            <Button
                                type="button"
                                variant="secondary"
                                disabled={!session || isMutating === 'last-fm'}
                                onClick={handleSaveLastFmProfile}
                            >
                                {isMutating === 'last-fm' ? 'Saving...' : 'Save Last.fm Profile to Account'}
                            </Button>
                            <Button
                                type="button"
                                variant="outline"
                                disabled={!session || isLastFmSyncing || !connectionBootstrap?.user.last_fm_username}
                                onClick={handleSyncLastFmScrobbles}
                            >
                                {isLastFmSyncing ? 'Syncing...' : 'Sync Recent Scrobbles'}
                            </Button>
                            <Link to="/ems">
                                <Button type="button" variant="ghost">
                                    Open EMS
                                </Button>
                            </Link>
                        </div>

                        {connectionBootstrap?.user.last_fm_username && (
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4 text-sm leading-6 text-hud-text-secondary">
                                Saved profile: <span className="font-medium text-hud-text-primary">
                                    {connectionBootstrap.user.last_fm_username}
                                </span>
                            </div>
                        )}

                        {lastFmScrobbleBootstrap && (
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <div className="flex flex-wrap items-center justify-between gap-3">
                                    <div>
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Stored Scrobble Snapshot
                                        </p>
                                        <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                            {lastFmScrobbleBootstrap.summary.next_step_message}
                                        </p>
                                    </div>
                                </div>

                                <div className="mt-4 grid gap-3 sm:grid-cols-3">
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Stored
                                        </p>
                                        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                            {lastFmScrobbleBootstrap.summary.stored_scrobble_count}
                                        </p>
                                    </div>
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Returned
                                        </p>
                                        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                            {lastFmScrobbleBootstrap.summary.returned_scrobble_count}
                                        </p>
                                    </div>
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Last Synced
                                        </p>
                                        <p className="mt-2 text-sm font-medium text-hud-text-primary">
                                            {lastFmScrobbleBootstrap.summary.last_synced_at
                                                ? new Date(lastFmScrobbleBootstrap.summary.last_synced_at).toLocaleString()
                                                : 'Not synced yet'}
                                        </p>
                                    </div>
                                </div>
                            </div>
                        )}

                        {lastFmPreviewError && (
                            <div className="rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {lastFmPreviewError}
                            </div>
                        )}

                        {lastFmPreview && (
                            <>
                                <div className="grid gap-3 sm:grid-cols-3">
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Recent Scrobbles
                                        </p>
                                        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                            {lastFmPreview.summary.recent_track_count}
                                        </p>
                                    </div>
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Top Artists
                                        </p>
                                        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                            {lastFmPreview.summary.top_artist_count}
                                        </p>
                                    </div>
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                            Distinct Recent Artists
                                        </p>
                                        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                            {lastFmPreview.summary.distinct_recent_artist_count}
                                        </p>
                                    </div>
                                </div>

                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        Profile Snapshot
                                    </p>
                                    <p className="mt-2 text-lg font-semibold text-hud-text-primary">
                                        {lastFmPreview.user.real_name || lastFmPreview.user.username}
                                    </p>
                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                        {lastFmPreview.user.country
                                            ? `${lastFmPreview.user.country} · `
                                            : ''}
                                        {lastFmPreview.user.playcount
                                            ? `${lastFmPreview.user.playcount.toLocaleString()} total plays`
                                            : 'Total playcount unavailable'}
                                    </p>
                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                        {lastFmPreview.summary.next_step_message}
                                    </p>
                                </div>
                            </>
                        )}
                    </div>
                </HudCard>

                <HudCard title="Preview Results" subtitle="Signals you can feed into EMS/GMS planning">
                    {lastFmPreview ? (
                        <div className="space-y-4">
                            <div className="grid gap-3">
                                {lastFmPreview.insights.map((insight) => (
                                    <div
                                        key={insight.insight_id}
                                        className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4"
                                    >
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-accent-primary">
                                            {insight.title}
                                        </p>
                                        <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                            {insight.detail}
                                        </p>
                                    </div>
                                ))}
                            </div>

                            <div className="grid gap-4 md:grid-cols-3">
                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        Recent Tracks
                                    </p>
                                    <div className="mt-3 space-y-3">
                                        {lastFmPreview.recent_tracks.slice(0, 4).map((track, index) => (
                                            <div key={`${track.track_name}-${index}`} className="text-sm leading-6 text-hud-text-secondary">
                                                <p className="font-medium text-hud-text-primary">
                                                    {track.track_name || 'Unknown Track'}
                                                </p>
                                                <p>
                                                    <ArtistDetailLink
                                                        artistName={track.artist_name}
                                                        className="transition-hud hover:text-hud-accent-primary"
                                                    />
                                                    {track.now_playing ? ' · now playing' : ''}
                                                </p>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        Top Artists
                                    </p>
                                    <div className="mt-3 space-y-3">
                                        {lastFmPreview.top_artists.slice(0, 4).map((artist, index) => (
                                            <div key={`${artist.artist_name}-${index}`} className="text-sm leading-6 text-hud-text-secondary">
                                                <p className="font-medium text-hud-text-primary">
                                                    <span>#{artist.rank ?? index + 1} </span>
                                                    <ArtistDetailLink
                                                        artistName={artist.artist_name}
                                                        className="transition-hud hover:text-hud-accent-primary"
                                                    />
                                                </p>
                                                <p>{artist.playcount?.toLocaleString() ?? 'Unknown'} plays</p>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        Top Tracks
                                    </p>
                                    <div className="mt-3 space-y-3">
                                        {lastFmPreview.top_tracks.slice(0, 4).map((track, index) => (
                                            <div key={`${track.track_name}-${index}`} className="text-sm leading-6 text-hud-text-secondary">
                                                <p className="font-medium text-hud-text-primary">
                                                    #{track.rank ?? index + 1} {track.track_name || 'Unknown Track'}
                                                </p>
                                                <p>
                                                    <ArtistDetailLink
                                                        artistName={track.artist_name}
                                                        className="transition-hud hover:text-hud-accent-primary"
                                                    />
                                                </p>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>

                            {lastFmScrobbleBootstrap && lastFmScrobbleBootstrap.recent_scrobbles.length > 0 && (
                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        Stored Recent Scrobbles
                                    </p>
                                    <div className="mt-3 space-y-3">
                                        {lastFmScrobbleBootstrap.recent_scrobbles.slice(0, 5).map((track, index) => (
                                            <div
                                                key={`${track.track_name}-${track.played_at}-${index}`}
                                                className="text-sm leading-6 text-hud-text-secondary"
                                            >
                                                <p className="font-medium text-hud-text-primary">
                                                    {track.track_name}
                                                </p>
                                                <p>
                                                    <ArtistDetailLink
                                                        artistName={track.artist_name}
                                                        className="transition-hud hover:text-hud-accent-primary"
                                                    />
                                                    {track.album_name ? ` · ${track.album_name}` : ''}
                                                </p>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </div>
                    ) : (
                        <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-4 text-sm leading-6 text-hud-text-secondary">
                            Enter a Last.fm username and load the preview to inspect public listening history signals.
                        </div>
                    )}
                </HudCard>
            </section>
        </div>
    )
}

export default PlatformsPage
