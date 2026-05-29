import { useEffect, useRef, useState } from 'react'
import { CheckCircle2, Loader, XCircle } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { resetSpotifyWebPlayer } from '@/lib/spotifyPlaybackSdk'
import { tidalReset } from '@/lib/tidalStreamPlayback'
import { ApiError, completePlatformAuthorization, completePublicCurationTidalOAuth } from '@/services/api'
import type {
    PlatformAuthorizationCompleteResponse,
    PlatformAuthorizationStartResponse,
    PublicCurationTidalOAuthCompleteResponse,
    PublicCurationTidalOAuthStartResponse,
} from '@/types/api'

const STORAGE_KEY = 'my-forever-music.platform-oauth-session'
const PUBLIC_CURATION_OAUTH_STORAGE_KEY = 'my-forever-music.public-curation-oauth'

type PublicCurationPendingAuthorization = {
    flow: 'public-curation'
    slug: string
    authorization: PublicCurationTidalOAuthStartResponse['authorization']
}

const resetLocalPlaybackAuthorization = (userId: string, platformId: string) => {
    if (platformId === 'spotify') {
        resetSpotifyWebPlayer(userId)
        return
    }
    if (platformId === 'tidal') {
        void tidalReset().catch(() => undefined)
    }
}

const loadPendingAuthorization = (state: string | null): PlatformAuthorizationStartResponse | null => {
    if (!state || typeof window === 'undefined') {
        return null
    }

    const stored = window.sessionStorage.getItem(`${STORAGE_KEY}.${state}`)
    if (!stored) {
        return null
    }

    try {
        return JSON.parse(stored) as PlatformAuthorizationStartResponse
    } catch {
        return null
    }
}

const loadPendingPublicCurationAuthorization = (state: string | null): PublicCurationPendingAuthorization | null => {
    if (!state || typeof window === 'undefined') {
        return null
    }

    const stored = window.sessionStorage.getItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.${state}`)
    if (!stored) {
        return null
    }

    try {
        const parsed = JSON.parse(stored) as PublicCurationPendingAuthorization
        return parsed.flow === 'public-curation' && parsed.slug ? parsed : null
    } catch {
        return null
    }
}

const PlatformOAuthCallbackPage = () => {
    const [params] = useSearchParams()
    const navigate = useNavigate()
    const { session, updateSession } = useAuthSession()
    const { updateWorkspace } = useRecommendationWorkspace()
    const [result, setResult] = useState<PlatformAuthorizationCompleteResponse | null>(null)
    const [publicResult, setPublicResult] = useState<PublicCurationTidalOAuthCompleteResponse | null>(null)
    const [error, setError] = useState<string | null>(null)
    const completedRef = useRef(false)

    const state = params.get('state')
    const callbackCode = params.get('code')
    const providerError = params.get('error')
    const providerErrorDescription = params.get('error_description')
    const publicPending = loadPendingPublicCurationAuthorization(state)
    const pending = publicPending ? null : loadPendingAuthorization(state)

    useEffect(() => {
        if (completedRef.current) {
            return
        }

        if (publicPending) {
            if (!state) {
                completedRef.current = true
                setError('TIDAL 공개 재생 인증 상태를 확인하지 못했습니다.')
                return
            }

            if (providerError) {
                completedRef.current = true
                setError(providerErrorDescription ?? providerError)
                return
            }

            if (!callbackCode) {
                completedRef.current = true
                setError('TIDAL 인증 코드가 돌아오지 않았습니다.')
                return
            }

            completedRef.current = true

            completePublicCurationTidalOAuth(publicPending.slug, {
                state,
                authorization_code: callbackCode,
            })
                .then((response) => {
                    setPublicResult(response)
                    setError(null)

                    if (typeof window !== 'undefined') {
                        window.sessionStorage.removeItem(`${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.${state}`)
                        window.sessionStorage.setItem(
                            `${PUBLIC_CURATION_OAUTH_STORAGE_KEY}.session.${publicPending.slug}`,
                            JSON.stringify({
                                session: response.session,
                                completed_at: response.completed_at,
                            }),
                        )
                    }

                    navigate(response.return_path, { replace: true })
                })
                .catch((requestError: unknown) => {
                    const message =
                        requestError instanceof ApiError
                            ? requestError.message
                            : 'TIDAL 공개 재생 인증을 완료하지 못했습니다.'
                    setError(message)
                })
            return
        }

        if (!state || !pending) {
            completedRef.current = true
            setError('No pending platform authorization callback was found.')
            return
        }

        if (providerError) {
            completedRef.current = true
            setError(providerErrorDescription ?? providerError)
            return
        }

        if (!callbackCode) {
            completedRef.current = true
            setError('No authorization code was returned in the callback.')
            return
        }

        completedRef.current = true

        completePlatformAuthorization({
            user_id: pending.user.user_id,
            platform_id: pending.authorization.platform_id,
            state,
            approval_code:
                pending.authorization.authorization_channel === 'internal_approval_page'
                    ? callbackCode
                    : undefined,
            authorization_code:
                pending.authorization.authorization_channel === 'external_browser_redirect'
                    ? callbackCode
                    : undefined,
        })
            .then((response) => {
                setResult(response)
                setError(null)
                resetLocalPlaybackAuthorization(response.connection.user_id, response.connection.platform_id)
                updateWorkspace({
                    userId: response.connection.user_id,
                    preferredPlatformId: session?.preferredPlatformId ?? pending.authorization.platform_id,
                })
                updateSession({
                    onboardingStage: response.next_step.path === '/pms' ? 'import-playlists' : 'connect-platform',
                    platformConnectionRequired: response.next_step.path !== '/pms',
                    nextStepPath: response.next_step.path,
                    nextStepMessage: response.next_step.message,
                })

                if (typeof window !== 'undefined') {
                    window.sessionStorage.removeItem(`${STORAGE_KEY}.${state}`)
                }
            })
            .catch((requestError: unknown) => {
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to complete the platform authorization callback.'
                setError(message)
            })
    }, [
        callbackCode,
        navigate,
        pending,
        publicPending,
        providerError,
        providerErrorDescription,
        session?.preferredPlatformId,
        state,
        updateSession,
        updateWorkspace,
    ])

    if (publicPending) {
        return (
            <HudCard title="TIDAL 공개 재생 인증" subtitle="공개 플레이리스트 재생 세션을 준비하고 있습니다">
                {!publicResult && !error ? (
                    <div className="flex items-center gap-3 text-sm text-hud-text-secondary">
                        <Loader className="animate-spin text-hud-accent-primary" size={18} />
                        TIDAL 인증 결과를 확인하고 있습니다. 인증 창이 따로 남아 있으면 완료 후 닫고 공유 페이지로 돌아오세요.
                    </div>
                ) : null}

                {error ? (
                    <div className="rounded-2xl border border-rose-400/30 bg-rose-500/10 p-5">
                        <div className="flex items-start gap-3">
                            <XCircle className="mt-0.5 text-rose-300" size={18} />
                            <div>
                                <p className="text-sm font-medium text-hud-text-primary">TIDAL 인증을 완료하지 못했습니다</p>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{error}</p>
                            </div>
                        </div>
                        <div className="mt-5">
                            <Link to={`/share/playlists/${encodeURIComponent(publicPending.slug)}`}>
                                <Button variant="outline">공유 플레이리스트로 돌아가기</Button>
                            </Link>
                        </div>
                    </div>
                ) : null}

                {publicResult ? (
                    <div className="rounded-2xl border border-emerald-400/30 bg-emerald-400/10 p-5">
                        <div className="flex items-start gap-3">
                            <CheckCircle2 className="mt-0.5 text-emerald-300" size={18} />
                            <div>
                                <p className="text-sm font-medium text-hud-text-primary">TIDAL 공개 재생 세션이 준비되었습니다</p>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    공유 페이지로 돌아가 재생을 이어갑니다. 별도 로그인 창이 남아 있으면 닫아주세요.
                                </p>
                            </div>
                        </div>
                    </div>
                ) : null}
            </HudCard>
        )
    }

    if (!state || !pending) {
        return (
            <HudCard title="OAuth Callback Missing" subtitle="The authorization callback could not be reconstructed">
                <p className="text-sm leading-6 text-hud-text-secondary">
                    Return to the platforms page and start the platform authorization again.
                </p>
                <div className="mt-5">
                    <Link to="/platforms">
                        <Button variant="primary">Back to Platforms</Button>
                    </Link>
                </div>
            </HudCard>
        )
    }

    return (
        <div className="space-y-6">
            <HudCard title="OAuth Callback" subtitle="Completing the platform authorization handshake">
                {!result && !error ? (
                    <div className="flex items-center gap-3 text-sm text-hud-text-secondary">
                        <Loader className="animate-spin text-hud-accent-primary" size={18} />
                        Completing authorization for {pending.authorization.platform_display_name}...
                    </div>
                ) : null}

                {error ? (
                    <div className="rounded-2xl border border-rose-400/30 bg-rose-500/10 p-5">
                        <div className="flex items-start gap-3">
                            <XCircle className="mt-0.5 text-rose-300" size={18} />
                            <div>
                                <p className="text-sm font-medium text-hud-text-primary">Authorization failed</p>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{error}</p>
                            </div>
                        </div>
                        <div className="mt-5">
                            <Link to="/platforms">
                                <Button variant="outline">Back to Platforms</Button>
                            </Link>
                        </div>
                    </div>
                ) : null}

                {result ? (
                    <div className="space-y-5">
                        <div className="rounded-2xl border border-emerald-400/30 bg-emerald-400/10 p-5">
                            <div className="flex items-start gap-3">
                                <CheckCircle2 className="mt-0.5 text-emerald-300" size={18} />
                                <div>
                                    <p className="text-sm font-medium text-hud-text-primary">
                                        {pending.authorization.platform_display_name} connected
                                    </p>
                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                        {result.next_step.message}
                                    </p>
                                </div>
                            </div>
                        </div>

                        <div className="grid gap-4 sm:grid-cols-2">
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Connection Mode</p>
                                <p className="mt-2 text-sm text-hud-text-primary">{result.connection.connection_mode}</p>
                            </div>
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Next Step</p>
                                <p className="mt-2 text-sm text-hud-text-primary">{result.next_step.path}</p>
                            </div>
                        </div>

                        <div className="flex flex-wrap gap-3">
                            <Button variant="primary" glow onClick={() => navigate(result.next_step.path)}>
                                Continue
                            </Button>
                            <Link to="/platforms">
                                <Button variant="outline">Back to Platforms</Button>
                            </Link>
                        </div>
                    </div>
                ) : null}
            </HudCard>
        </div>
    )
}

export default PlatformOAuthCallbackPage
