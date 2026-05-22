import { startTransition, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Activity, Bookmark, Heart, RefreshCw, Sparkles, ThumbsDown } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import PlaylistFeatureCard from '@/components/music/PlaylistFeatureCard'
import TrackFeatureCard from '@/components/music/TrackFeatureCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { buildArtistDetailPath } from '@/lib/artistLinks'
import { buildPmsPlaylistDetailPath } from '@/lib/pmsPlayback'
import {
    ApiError,
    fetchPmsWorkspaceBootstrap,
    previewGmsRecommendations,
    recordGmsRecommendationFeedback,
    saveTrackToPmsPersonalPlaylist,
} from '@/services/api'
import type { GmsRecommendationFeedbackType, GmsRecommendationPreviewResponse } from '@/types/api'

const axisLevelClass = (level: string) => {
    switch (level) {
        case 'strong':
            return 'border-hud-accent-primary/40 bg-hud-accent-primary/10 text-hud-accent-primary'
        case 'moderate':
            return 'border-hud-border-primary/30 bg-hud-bg-secondary/60 text-hud-text-primary'
        case 'low':
            return 'border-amber-300/30 bg-amber-300/10 text-amber-100'
        default:
            return 'border-hud-border-secondary bg-hud-bg-secondary/60 text-hud-text-secondary'
    }
}

const formatAffinityMetric = (value: number | null | undefined) =>
    typeof value === 'number' && Number.isFinite(value) ? value.toFixed(2) : 'n/a'

const affinityTokens = (tokens: string[] | null | undefined) =>
    tokens?.filter((token) => token.trim().length > 0) ?? []

const formatGateDelta = (value: number | null | undefined) => {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
        return 'n/a'
    }
    return `${value >= 0 ? '+' : ''}${value.toFixed(4)}`
}

const gateReasonTokens = (tokens: string[] | null | undefined) =>
    tokens?.filter((token) => token.trim().length > 0) ?? []

type GmsPreviewItem = GmsRecommendationPreviewResponse['items'][number]

const evidenceRank = (level: string) => {
    switch (level) {
        case 'strong':
            return 0
        case 'moderate':
            return 1
        case 'low':
            return 2
        default:
            return 3
    }
}

const topEvidence = (items: GmsPreviewItem['axis_evidence']) =>
    [...(items ?? [])]
        .sort((left, right) => evidenceRank(left.level) - evidenceRank(right.level))
        .slice(0, 3)

const explanationVerdict = (item: GmsPreviewItem) => {
    const gateStatus = item.taste_mode_gate?.status
    if (gateStatus === 'dry_run' || gateStatus === 'eligible') {
        return '현재 취향 모드와 잘 맞아요'
    }
    if (gateStatus === 'blocked') {
        return '비슷하지만 게이트에서 보류됐어요'
    }
    if (item.taste_mode_affinity) {
        return '내 청취 모드와 비슷해요'
    }
    if ((item.axis_evidence?.length ?? 0) > 0) {
        return '전반적인 청취 신호로 추천됐어요'
    }
    return '현재 GMS 순위로 추천됐어요'
}

const gateStatusLabel = (status: string) => {
    switch (status) {
        case 'dry_run':
            return '시범 적용'
        case 'eligible':
            return '추천 가능'
        case 'blocked':
            return '보류'
        case 'not_applicable':
            return '적용 안 됨'
        default:
            return status
    }
}

const gateReasonLabel = (reason: string) => {
    switch (reason) {
        case 'eligible':
            return '추천 가능'
        case 'low_mode_similarity':
            return '취향 모드 유사도 낮음'
        default:
            return reason
    }
}

const explanationReasonLabel = (reason: string) => {
    switch (reason) {
        case 'Audio taste matched this candidate.':
            return '오디오 취향이 이 후보와 잘 맞아요.'
        case 'No nearest taste mode in this fixture.':
            return '가까운 취향 모드는 없지만 다른 추천 신호가 있습니다.'
        default:
            return reason
    }
}

const evidenceAxisLabel = (axis: string) => {
    switch (axis) {
        case 'confidence':
            return '확신도'
        case 'energy':
            return '에너지'
        case 'valence':
            return '분위기'
        case 'danceability':
            return '댄스감'
        case 'acousticness':
            return '어쿠스틱'
        case 'tempo':
            return '템포'
        default:
            return axis
    }
}

const evidenceSummaryLabel = (summary: string) => {
    switch (summary) {
        case 'Moderate confidence from broader GMS signals.':
            return '넓은 GMS 신호에서 중간 수준의 확신을 얻었어요.'
        default:
            return summary
    }
}

type TasteModeAffinityPanelProps = {
    affinity: NonNullable<GmsPreviewItem['taste_mode_affinity']>
    gate?: GmsPreviewItem['taste_mode_gate']
}

const TasteModeAffinityPanel = ({ affinity, gate }: TasteModeAffinityPanelProps) => {
    const tokens = affinityTokens(affinity.tokens)
    const reasonTokens = gateReasonTokens(gate?.reason_tokens)
    const gateTone =
        gate?.status === 'dry_run'
            ? 'border-emerald-300/30 bg-emerald-300/10 text-emerald-100'
            : gate?.status === 'blocked'
                ? 'border-amber-300/30 bg-amber-300/10 text-amber-100'
                : 'border-hud-border-secondary bg-hud-bg-primary/60 text-hud-text-secondary'

    return (
        <div
            aria-label={`Taste mode affinity ${affinity.mode_id}`}
            className="rounded-lg border border-hud-border-primary/40 bg-hud-accent-primary/10 p-3"
        >
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                    <span className="inline-flex rounded-lg border border-hud-border-primary bg-hud-bg-primary/70 px-2.5 py-1 text-[10px] font-semibold uppercase text-hud-accent-primary">
                        Taste mode
                    </span>
                    <p className="mt-2 truncate text-sm font-semibold text-hud-text-primary">
                        {affinity.label}
                    </p>
                    <p className="mt-1 text-xs text-hud-text-muted">{affinity.mode_id}</p>
                </div>
                <div className="grid grid-cols-2 gap-2 text-right">
                    <div>
                        <p className="text-[10px] uppercase text-hud-text-muted">Similarity</p>
                        <p className="mt-1 text-sm font-semibold text-hud-text-primary">
                            {formatAffinityMetric(affinity.similarity)}
                        </p>
                    </div>
                    <div>
                        <p className="text-[10px] uppercase text-hud-text-muted">Distance</p>
                        <p className="mt-1 text-sm font-semibold text-hud-text-primary">
                            {formatAffinityMetric(affinity.distance)}
                        </p>
                    </div>
                </div>
            </div>

            {tokens.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                    {tokens.map((token) => (
                        <span
                            key={`${affinity.mode_id}-${token}`}
                            className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-2.5 py-1 text-[11px] text-hud-text-secondary"
                        >
                            {token}
                        </span>
                    ))}
                </div>
            )}

            {gate && (
                <div className={`mt-3 rounded-lg border px-3 py-2 text-xs ${gateTone}`}>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="font-semibold">
                            {gate.status === 'dry_run' ? 'Gate dry run' : gate.status}
                        </span>
                        {gate.status === 'dry_run' && (
                            <span>{formatGateDelta(gate.dry_run_delta)}</span>
                        )}
                    </div>
                    <p className="mt-1 text-[11px] text-hud-text-muted">
                        {gate.reason}
                        {gate.status === 'dry_run' ? ' · ranking unchanged' : ''}
                    </p>
                    {reasonTokens.length > 0 && (
                        <div className="mt-2 flex flex-wrap gap-1.5">
                            {reasonTokens.map((token) => (
                                <span
                                    key={`${affinity.mode_id}-gate-${token}`}
                                    className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-2 py-0.5 text-[10px] text-hud-text-secondary"
                                >
                                    {token}
                                </span>
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    )
}

const RecommendationExplanationPanel = ({ item }: { item: GmsPreviewItem }) => {
    const evidence = topEvidence(item.axis_evidence)
    const tokens = [
        ...affinityTokens(item.taste_mode_affinity?.tokens),
        ...gateReasonTokens(item.taste_mode_gate?.reason_tokens),
    ].slice(0, 4)

    return (
        <section
            aria-label={`Recommendation explanation ${item.track_id}`}
            className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 p-3"
        >
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                    <h4 className="text-xs font-semibold uppercase text-hud-text-muted">
                        이 추천의 이유
                    </h4>
                    <p className="mt-2 text-sm font-semibold text-hud-text-primary">
                        {explanationVerdict(item)}
                    </p>
                </div>
                <span className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-[11px] font-semibold text-hud-accent-primary">
                    점수 {item.score.toFixed(2)}
                </span>
            </div>

            <dl className="mt-3 grid gap-2 text-xs sm:grid-cols-2">
                {item.taste_mode_affinity && (
                    <ExplanationSignal label="모드 유사도" value={formatAffinityMetric(item.taste_mode_affinity.similarity)} />
                )}
                {item.taste_mode_gate && (
                    <ExplanationSignal
                        label="게이트"
                        value={`${gateStatusLabel(item.taste_mode_gate.status)} · ${gateReasonLabel(item.taste_mode_gate.reason)}`}
                    />
                )}
                {typeof item.taste_mode_gate?.dry_run_delta === 'number' && (
                    <ExplanationSignal label="순위 변화" value={formatGateDelta(item.taste_mode_gate.dry_run_delta)} />
                )}
                <ExplanationSignal label="출처" value={item.source_playlist_title ?? item.source_space} />
            </dl>

            {item.reason && (
                <p className="mt-3 text-xs leading-5 text-hud-text-secondary">{explanationReasonLabel(item.reason)}</p>
            )}

            {evidence.length > 0 && (
                <ul className="mt-3 space-y-1.5">
                    {evidence.map((entry) => (
                        <li key={`${item.track_id}-explain-${entry.axis}`} className="text-xs leading-5 text-hud-text-secondary">
                            <span className="font-semibold text-hud-text-primary">{evidenceAxisLabel(entry.axis)}</span>
                            {entry.score !== null ? ` ${entry.score.toFixed(2)}` : ''}: {evidenceSummaryLabel(entry.summary)}
                        </li>
                    ))}
                </ul>
            )}

            {tokens.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-1.5">
                    {tokens.map((token) => (
                        <span
                            key={`${item.track_id}-explain-token-${token}`}
                            className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-0.5 text-[10px] text-hud-text-muted"
                        >
                            {token}
                        </span>
                    ))}
                </div>
            )}
        </section>
    )
}

const ExplanationSignal = ({ label, value }: { label: string; value: string }) => (
    <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/50 px-2.5 py-2">
        <dt className="text-[10px] uppercase text-hud-text-muted">{label}</dt>
        <dd className="mt-1 font-medium text-hud-text-primary">{value}</dd>
    </div>
)

const openExternal = (url?: string | null) => {
    if (!url) {
        return
    }

    window.open(url, '_blank', 'noopener,noreferrer')
}

const GmsPreviewPage = () => {
    const { session } = useAuthSession()
    const { workspace, updateWorkspace } = useRecommendationWorkspace()
    const { playItem } = usePlayback()
    const [isSubmitting, setIsSubmitting] = useState(false)
    const [isContextLoading, setIsContextLoading] = useState(true)
    const [contextError, setContextError] = useState<string | null>(null)
    const [response, setResponse] = useState<GmsRecommendationPreviewResponse | null>(null)
    const [bootstrap, setBootstrap] = useState<Awaited<ReturnType<typeof fetchPmsWorkspaceBootstrap>> | null>(null)
    const [feedbackByTrackId, setFeedbackByTrackId] = useState<Record<string, GmsRecommendationFeedbackType>>({})
    const [feedbackPendingTrackId, setFeedbackPendingTrackId] = useState<string | null>(null)
    const [saveMessage, setSaveMessage] = useState<string | null>(null)
    const activeUserId = session?.userId || workspace.userId

    useEffect(() => {
        const controller = new AbortController()
        setIsContextLoading(true)
        setContextError(null)

        fetchPmsWorkspaceBootstrap(
            activeUserId || undefined,
            workspace.playlistId || undefined,
            controller.signal,
        )
            .then((workspaceResponse) => {
                startTransition(() => {
                    setBootstrap(workspaceResponse)
                    setContextError(null)
                })
            })
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }

                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load the current PMS playlist context for GMS.'

                startTransition(() => {
                    setContextError(message)
                })
            })
            .finally(() => {
                setIsContextLoading(false)
            })

        return () => controller.abort()
    }, [activeUserId, workspace.playlistId])

    const activePlaylist = useMemo(
        () =>
            bootstrap?.playlists.find((playlist) => playlist.playlist_id === workspace.playlistId) ??
            bootstrap?.playlists[0] ??
            null,
        [bootstrap, workspace.playlistId],
    )
    const coldStartFallbackActive = useMemo(
        () => response?.warnings.some((warning) => warning.startsWith('Cold-start fallback applied:')) ?? false,
        [response],
    )
    const pmsLibraryEmpty = useMemo(() => {
        if (!bootstrap) {
            return false
        }
        const playlists = bootstrap.playlists ?? []
        if (playlists.length === 0) {
            return true
        }
        return playlists.every((playlist) => (playlist.track_count ?? 0) === 0)
    }, [bootstrap])
    const showColdStartIntro = !isContextLoading && pmsLibraryEmpty && !response

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        if (!session) {
            setContextError('Log in before requesting GMS recommendations.')
            return
        }

        setIsSubmitting(true)
        setContextError(null)

        const payload = {
            request_id: `web-preview-${Date.now()}`,
            user_id: session.userId,
            playlist_id: activePlaylist?.playlist_id,
            mood: workspace.mood,
            energy_level: workspace.energyLevel,
            familiarity_bias: workspace.familiarityBias,
            limit: workspace.limit,
            include_explanations: workspace.includeExplanations,
        }

        try {
            const preview = await previewGmsRecommendations(payload)
            startTransition(() => {
                setResponse(preview)
                setFeedbackByTrackId({})
                setSaveMessage(null)
                setContextError(null)
            })
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to get a playable preview from the Spring Boot bridge.'

            startTransition(() => {
                setContextError(message)
            })
        } finally {
            setIsSubmitting(false)
        }
    }

    const handleFeedback = async (
        item: GmsRecommendationPreviewResponse['items'][number],
        feedbackType: GmsRecommendationFeedbackType,
    ) => {
        if (!session || !response) {
            setContextError('Log in and request a GMS preview before recording recommendation feedback.')
            return
        }

        setFeedbackPendingTrackId(item.track_id)
        setContextError(null)

        try {
            await recordGmsRecommendationFeedback({
                user_id: session.userId,
                request_id: response.request_id,
                playlist_id: item.source_playlist_id ?? activePlaylist?.playlist_id ?? workspace.playlistId,
                track_id: item.track_id,
                feedback_type: feedbackType,
                score: feedbackType === 'like' || feedbackType === 'save' ? 1 : -1,
                source_space: item.source_space,
                reason: item.reason,
            })

            if (feedbackType === 'save') {
                const saveResponse = await saveTrackToPmsPersonalPlaylist({
                    user_id: session.userId,
                    target_playlist_title: 'Saved GMS Recommendations',
                    track_id: item.track_id,
                    source_context: 'gms-preview',
                })
                setSaveMessage(
                    `${item.title} was saved to ${saveResponse.playlist.title}. ${saveResponse.playlist.track_count} tracks are in that playlist.`,
                )
            }

            startTransition(() => {
                setFeedbackByTrackId((current) => ({
                    ...current,
                    [item.track_id]: feedbackType,
                }))
            })
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to record this recommendation feedback.'

            startTransition(() => {
                setContextError(message)
            })
        } finally {
            setFeedbackPendingTrackId(null)
        }
    }

    return (
        <div className="space-y-6">
            {showColdStartIntro && (
                <section className="flex flex-col gap-3 rounded-2xl border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-5 md:flex-row md:items-center md:justify-between">
                    <div className="flex items-start gap-3">
                        <Bookmark size={20} className="mt-1 text-hud-accent-primary" />
                        <div>
                            <p className="text-xs uppercase tracking-[0.24em] text-hud-accent-primary">PMS Library Empty</p>
                            <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                Submitting a preview now will return EMS-pool cold-start fallback tracks.
                                Import a connected platform playlist first to unlock personalized ranking and 6-axis evidence.
                            </p>
                        </div>
                    </div>
                    <Link to="/pms" className="md:shrink-0">
                        <Button type="button" variant="primary">
                            <Bookmark size={16} />
                            Open PMS Import
                        </Button>
                    </Link>
                </section>
            )}
            <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
                <div className="space-y-6">
                    <HudCard title="GMS Approval Request" subtitle="Generate candidates from PMS and EMS context for final user approval">
                        <form className="space-y-5" onSubmit={handleSubmit}>
                            <div>
                                <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Mood</label>
                                <select
                                    value={workspace.mood}
                                    onChange={(event) => updateWorkspace({ mood: event.target.value as typeof workspace.mood })}
                                    className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                >
                                    <option value="focus">Focus</option>
                                    <option value="calm">Calm</option>
                                    <option value="upbeat">Upbeat</option>
                                    <option value="melancholy">Melancholy</option>
                                    <option value="discovery">Discovery</option>
                                </select>
                            </div>

                            <div className="grid grid-cols-3 gap-3">
                                <div>
                                    <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Energy</label>
                                    <input
                                        value={workspace.energyLevel}
                                        onChange={(event) => updateWorkspace({ energyLevel: Number(event.target.value) })}
                                        type="number"
                                        min="1"
                                        max="5"
                                        className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                    />
                                </div>
                                <div>
                                    <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Bias</label>
                                    <input
                                        value={workspace.familiarityBias}
                                        onChange={(event) => updateWorkspace({ familiarityBias: Number(event.target.value) })}
                                        type="number"
                                        min="1"
                                        max="5"
                                        className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                    />
                                </div>
                                <div>
                                    <label className="mb-2 block text-sm font-medium text-hud-text-secondary">Limit</label>
                                    <input
                                        value={workspace.limit}
                                        onChange={(event) => updateWorkspace({ limit: Number(event.target.value) })}
                                        type="number"
                                        min="1"
                                        max="20"
                                        className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                    />
                                </div>
                            </div>

                            <label className="flex items-center gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-3 text-sm text-hud-text-secondary">
                                <input
                                    checked={workspace.includeExplanations}
                                    onChange={(event) => updateWorkspace({ includeExplanations: event.target.checked })}
                                    type="checkbox"
                                    className="h-4 w-4 rounded border-hud-border-secondary bg-hud-bg-primary text-hud-accent-primary"
                                />
                                Include explanation strings in the preview response
                            </label>

                            <div className="grid gap-4 sm:grid-cols-3">
                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">PMS Context</p>
                                    <p className="mt-2 text-sm font-semibold text-hud-text-primary">
                                        {activePlaylist ? 'Ready' : session ? 'EMS Fallback' : 'Missing'}
                                    </p>
                                </div>
                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">EMS Signal</p>
                                    <p className="mt-2 text-sm font-semibold capitalize text-hud-text-primary">{workspace.mood}</p>
                                </div>
                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Approval Path</p>
                                    <p className="mt-2 text-sm font-semibold text-hud-text-primary">{'GMS -> PMS'}</p>
                                </div>
                            </div>

                            <div className="flex flex-wrap gap-3">
                                <Link to="/ems" className="flex-1 min-w-[140px]">
                                    <Button type="button" variant="outline" fullWidth>
                                        Back to EMS
                                    </Button>
                                </Link>
                                <Button type="submit" variant="primary" glow fullWidth disabled={isSubmitting || !session}>
                                    {isSubmitting ? (
                                        <>
                                            <RefreshCw size={18} className="animate-spin" />
                                            Generating Preview
                                        </>
                                    ) : !activePlaylist ? (
                                        <>
                                            <Sparkles size={18} />
                                            Preview EMS Fallback
                                        </>
                                    ) : (
                                        <>
                                            <Sparkles size={18} />
                                            Request GMS Preview
                                        </>
                                    )}
                                </Button>
                            </div>
                        </form>
                    </HudCard>

                    <HudCard title="Response Feed" subtitle="Strategy, warnings, and bridge status">
                        {contextError ? (
                            <div className="rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {contextError}
                            </div>
                        ) : response ? (
                            <div className="space-y-4">
                                <div className="grid gap-4 md:grid-cols-2">
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Request ID</p>
                                        <p className="mt-2 text-sm font-medium text-hud-text-primary">{response.request_id}</p>
                                    </div>
                                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Strategy</p>
                                        <p className="mt-2 text-sm font-medium text-hud-text-primary">{response.context.strategy}</p>
                                    </div>
                                </div>

                                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <div className="flex flex-wrap items-center gap-3">
                                        <span className="rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-xs font-semibold uppercase tracking-[0.22em] text-hud-accent-primary">
                                            {response.context.mode}
                                        </span>
                                        <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs font-medium text-hud-text-secondary">
                                            Mood: {response.context.mood ?? 'none'}
                                        </span>
                                        <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs font-medium text-hud-text-secondary">
                                            Energy: {response.context.energy_level}
                                        </span>
                                        <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs font-medium text-hud-text-secondary">
                                            Engine: {response.context.engine}
                                        </span>
                                    </div>
                                    <p className="mt-4 text-sm leading-6 text-hud-text-secondary">
                                        Generated at {new Date(response.generated_at).toLocaleString()} with {response.items.length} playable recommendation candidates.
                                    </p>
                                </div>

                                {response.warnings.length > 0 && (
                                    <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4">
                                        <p className="text-xs uppercase tracking-[0.24em] text-hud-accent-warning">Warnings</p>
                                        <ul className="mt-3 space-y-2 text-sm leading-6 text-hud-text-secondary">
                                            {response.warnings.map((warning) => (
                                                <li key={warning}>{warning}</li>
                                            ))}
                                        </ul>
                                    </div>
                                )}

                                {coldStartFallbackActive && (
                                    <div className="rounded-2xl border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4">
                                        <p className="text-xs uppercase tracking-[0.24em] text-hud-accent-primary">Import Next</p>
                                        <p className="mt-3 text-sm leading-6 text-hud-text-secondary">
                                            These candidates came from the EMS pool because the PMS library is empty. Import a connected platform playlist to personalize the next preview.
                                        </p>
                                        <Link to="/pms" className="mt-4 inline-flex">
                                            <Button type="button" variant="primary">
                                                <Bookmark size={16} />
                                                Open PMS Import
                                            </Button>
                                        </Link>
                                    </div>
                                )}

                                {saveMessage && (
                                    <div className="rounded-2xl border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                        {saveMessage}
                                    </div>
                                )}
                            </div>
                        ) : (
                            <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                                Submit a preview request to see the bridge response and playable candidate shelf.
                            </div>
                        )}
                    </HudCard>
                </div>

                <div className="space-y-6">
                    <HudCard
                        title="Current PMS Playlist"
                        subtitle="The selected playlist and its imagery stay on-screen while GMS results are generated"
                        action={
                            isContextLoading ? (
                                <span className="inline-flex items-center gap-2 text-xs text-hud-text-muted">
                                    <RefreshCw size={14} className="animate-spin" />
                                    Loading context
                                </span>
                            ) : null
                        }
                    >
                        {activePlaylist ? (
                            <PlaylistFeatureCard
                                title={activePlaylist.title}
                                sourcePlatform={activePlaylist.source_platform}
                                curator={activePlaylist.curator}
                                trackCount={activePlaylist.track_count}
                                description={activePlaylist.highlight}
                                imageUrl={activePlaylist.cover_image_url}
                                isActive
                                actionLabel="Current GMS Basis"
                                detailPath={buildPmsPlaylistDetailPath(activePlaylist.playlist_id)}
                                onPlay={() =>
                                    playItem({
                                        id: `playlist:${activePlaylist.playlist_id}`,
                                        kind: 'playlist',
                                        title: activePlaylist.title,
                                        subtitle: `${activePlaylist.curator} · ${activePlaylist.source_platform}`,
                                        sourcePlatform: activePlaylist.source_platform,
                                        imageUrl: activePlaylist.cover_image_url,
                                        externalUrl: activePlaylist.platform_external_url,
                                        platformUri: activePlaylist.platform_uri,
                                        supportingText: activePlaylist.highlight,
                                    })
                                }
                                onOpenExternal={() => openExternal(activePlaylist.platform_external_url)}
                            />
                        ) : (
                            <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                                <p>
                                    No PMS playlist is imported yet. You can preview EMS-backed cold-start candidates now, then import a playlist for personalized ranking.
                                </p>
                                <Link to="/pms" className="mt-4 inline-flex">
                                    <Button type="button" variant="outline">
                                        <Bookmark size={16} />
                                        Open PMS Import
                                    </Button>
                                </Link>
                            </div>
                        )}
                    </HudCard>

                    <HudCard
                        title="Recommendation Candidates"
                        subtitle={coldStartFallbackActive ? 'EMS fallback tracks ready for import-driven personalization' : 'Playable tracks resolved back into the PMS user library'}
                    >
                        {response ? (
                            <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
                                {response.items.map((item) => (
                                    <div key={item.track_id} className="flex flex-col gap-3">
                                    <TrackFeatureCard
                                        title={item.title}
                                        artistName={item.artist_name}
                                        sourcePlatform={item.source_platform}
                                        albumTitle={item.album_title}
                                        imageUrl={item.album_image_url}
                                        durationMs={item.duration_ms}
                                        artistDetailPath={buildArtistDetailPath(item.artist_name)}
                                        reason={item.reason}
                                        badges={[
                                            `rank ${item.rank}`,
                                            `score ${item.score.toFixed(2)}`,
                                            item.source_playlist_title ? 'library resolved' : item.source_space,
                                        ]}
                                        onPlay={() =>
                                            playItem({
                                                id: `gms:${item.track_id}`,
                                                kind: 'track',
                                                title: item.title,
                                                subtitle: `${item.artist_name} · ${item.source_platform}`,
                                                sourcePlatform: item.source_platform,
                                                imageUrl: item.album_image_url,
                                                albumTitle: item.album_title,
                                                externalUrl: item.platform_external_url,
                                                platformUri: item.platform_uri,
                                                previewUrl: item.preview_url,
                                                spotifyTrackId: item.audio_feature_track_id ?? item.spotify_track_id,
                                                durationMs: item.duration_ms,
                                                supportingText: item.source_playlist_title ?? activePlaylist?.title ?? null,
                                            })
                                        }
                                        onOpenExternal={() => openExternal(item.platform_external_url)}
                                        feedbackActions={[
                                            {
                                                label: 'Like',
                                                icon: <Heart size={16} />,
                                                active: feedbackByTrackId[item.track_id] === 'like',
                                                disabled: feedbackPendingTrackId === item.track_id,
                                                onClick: () => handleFeedback(item, 'like'),
                                            },
                                            {
                                                label: 'Pass',
                                                icon: <ThumbsDown size={16} />,
                                                active: feedbackByTrackId[item.track_id] === 'dislike',
                                                disabled: feedbackPendingTrackId === item.track_id,
                                                onClick: () => handleFeedback(item, 'dislike'),
                                            },
                                            {
                                                label: 'Save',
                                                icon: <Bookmark size={16} />,
                                                active: feedbackByTrackId[item.track_id] === 'save',
                                                disabled: feedbackPendingTrackId === item.track_id,
                                                onClick: () => handleFeedback(item, 'save'),
                                            },
                                        ]}
                                    />
                                    <RecommendationExplanationPanel item={item} />
                                    {item.taste_mode_affinity && (
                                        <TasteModeAffinityPanel affinity={item.taste_mode_affinity} gate={item.taste_mode_gate} />
                                    )}
                                    {item.axis_evidence && item.axis_evidence.length > 0 && (
                                        <ul className="space-y-1.5 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3">
                                            {item.axis_evidence.map((evidence) => (
                                                <li key={`${item.track_id}-${evidence.axis}`} className="flex items-start gap-2 text-xs">
                                                    <span className={`mt-0.5 inline-flex h-5 min-w-[56px] items-center justify-center rounded-full border px-2 text-[10px] uppercase tracking-[0.18em] ${axisLevelClass(evidence.level)}`}>
                                                        {evidence.axis}
                                                    </span>
                                                    <span className="flex-1 leading-5 text-hud-text-secondary">
                                                        {evidence.summary}
                                                        {evidence.score !== null && (
                                                            <span className="ml-1 text-hud-text-muted">
                                                                ({evidence.score.toFixed(2)})
                                                            </span>
                                                        )}
                                                    </span>
                                                </li>
                                            ))}
                                        </ul>
                                    )}
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className="flex items-center gap-3 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm text-hud-text-secondary">
                                <Activity size={18} className="text-hud-accent-primary" />
                                Candidate tracks will appear here once the preview request completes.
                            </div>
                        )}
                    </HudCard>
                </div>
            </section>
        </div>
    )
}

export default GmsPreviewPage
