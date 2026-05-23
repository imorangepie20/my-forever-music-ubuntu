import { useCallback, useEffect, useMemo, useState } from 'react'
import { AlertTriangle, BarChart3, RefreshCw, ShieldCheck } from 'lucide-react'
import Button from '@/components/common/Button'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    loadGmsPreviewAdminEvidenceSnapshot,
    type GmsPreviewAdminEvidenceSnapshot,
} from '@/lib/gmsPreviewAdminEvidence'
import { fetchRecentPlaylistQualityForAdmin } from '@/services/api'
import type { GmsAxisEvidence, PlaylistQualityRecentItem } from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

const DEFAULT_LIMIT = 10

const formatDateTime = (value: string | null) => {
    if (!value) {
        return '-'
    }
    return new Intl.DateTimeFormat('ko-KR', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    }).format(new Date(value))
}

const formatRatio = (value: number | null) => {
    if (value === null || Number.isNaN(value)) {
        return '-'
    }
    return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`
}

const formatScore = (value: number | null) =>
    value === null || Number.isNaN(value) ? '-' : value.toFixed(2)

const averageOf = (
    playlists: PlaylistQualityRecentItem[],
    extractor: (item: PlaylistQualityRecentItem) => number | null,
) => {
    let sum = 0
    let count = 0
    for (const playlist of playlists) {
        const value = extractor(playlist)
        if (value === null || Number.isNaN(value)) {
            continue
        }
        sum += value
        count += 1
    }
    if (count === 0) {
        return null
    }
    return sum / count
}

const PlaylistQualityAdminPage = () => {
    const { session } = useAuthSession()
    const [playlists, setPlaylists] = useState<PlaylistQualityRecentItem[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [generatedAt, setGeneratedAt] = useState<string | null>(null)
    const [adminEvidenceSnapshot, setAdminEvidenceSnapshot] = useState<GmsPreviewAdminEvidenceSnapshot | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL

    const load = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        setLoading(true)
        setError(null)
        try {
            const response = await fetchRecentPlaylistQualityForAdmin(session.userId, DEFAULT_LIMIT, signal)
            setPlaylists(response.playlists)
            setGeneratedAt(response.generated_at)
        } catch (err) {
            if (signal?.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : 'Playlist quality 요약을 불러오지 못했습니다.')
        } finally {
            setLoading(false)
        }
    }, [isAdmin, session])

    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal)
        return () => controller.abort()
    }, [load])

    useEffect(() => {
        setAdminEvidenceSnapshot(isAdmin ? loadGmsPreviewAdminEvidenceSnapshot() : null)
    }, [isAdmin])

    const aggregate = useMemo(
        () => ({
            affinity: averageOf(playlists, (p) => p.avg_affinity),
            novelty: averageOf(playlists, (p) => p.avg_novelty),
            coherence: averageOf(playlists, (p) => p.coherence),
            diversity: averageOf(playlists, (p) => p.diversity),
            redundancy: averageOf(playlists, (p) => p.redundancy_penalty),
            confidence: averageOf(playlists, (p) => p.avg_confidence),
        }),
        [playlists],
    )

    if (!session || !isAdmin) {
        return (
            <main className="space-y-6">
                <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                    <div className="flex items-center gap-3 text-amber-100">
                        <ShieldCheck size={22} />
                        <h2 className="text-xl font-semibold">Playlist Quality Admin</h2>
                    </div>
                    <p className="mt-4 text-sm leading-6 text-hud-text-secondary">
                        이 화면은 {ADMIN_EMAIL} 관리자 계정에만 노출됩니다.
                    </p>
                </section>
            </main>
        )
    }

    return (
        <main className="space-y-6">
            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/85 p-6">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <BarChart3 size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">Playlist Quality</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            최근 추천 playlist 품질 지표
                        </h2>
                        <p className="mt-2 text-sm text-hud-text-secondary">
                            최근 GMS preview 추천 결과를 recommendation_id로 묶어 6축 quality를 집계합니다.
                            {generatedAt && ` 마지막 갱신 ${formatDateTime(generatedAt)}.`}
                        </p>
                    </div>
                    <Button type="button" variant="outline" onClick={() => void load()} disabled={loading}>
                        <RefreshCw size={16} />
                        새로고침
                    </Button>
                </div>
                {error && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{error}</span>
                    </div>
                )}
            </section>

            <section className="grid gap-4 sm:grid-cols-3 xl:grid-cols-6">
                <AxisStat label="Affinity" value={aggregate.affinity} />
                <AxisStat label="Novelty" value={aggregate.novelty} />
                <AxisStat label="Coherence" value={aggregate.coherence} />
                <AxisStat label="Diversity" value={aggregate.diversity} />
                <AxisStat label="Redundancy" value={aggregate.redundancy} tone="warning" />
                <AxisStat label="Confidence" value={aggregate.confidence} />
            </section>

            <GmsPreviewAdminEvidencePanel snapshot={adminEvidenceSnapshot} />

            <section className="overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80">
                <table className="w-full min-w-[1080px] text-left text-sm">
                    <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                        <tr>
                            <th className="px-4 py-3">Recommendation</th>
                            <th className="px-4 py-3">Model</th>
                            <th className="px-4 py-3">Tracks</th>
                            <th className="px-4 py-3">Affinity</th>
                            <th className="px-4 py-3">Novelty</th>
                            <th className="px-4 py-3">Coherence</th>
                            <th className="px-4 py-3">Diversity</th>
                            <th className="px-4 py-3">Redundancy</th>
                            <th className="px-4 py-3">Confidence</th>
                            <th className="px-4 py-3">Created</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-hud-border-secondary">
                        {playlists.map((playlist) => (
                            <tr key={playlist.recommendation_id ?? `${playlist.user_id}-${playlist.created_at}`} className="bg-hud-bg-secondary/40">
                                <td className="px-4 py-3 align-top">
                                    <p className="font-medium text-hud-text-primary">
                                        {playlist.recommendation_id ?? '-'}
                                    </p>
                                    <p className="mt-1 text-xs text-hud-text-muted">
                                        {playlist.user_id ?? '-'}
                                    </p>
                                </td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">
                                    {playlist.model_version ?? '-'}
                                </td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{playlist.track_count}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.avg_affinity)}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.avg_novelty)}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.coherence)}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.diversity)}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.redundancy_penalty)}</td>
                                <td className="px-4 py-3 align-top text-hud-text-secondary">{formatRatio(playlist.avg_confidence)}</td>
                                <td className="px-4 py-3 align-top text-xs text-hud-text-muted">{formatDateTime(playlist.created_at)}</td>
                            </tr>
                        ))}
                        {!playlists.length && !loading && (
                            <tr>
                                <td colSpan={10} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                                    최근 추천 snapshot이 없습니다.
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </section>
        </main>
    )
}

const AxisStat = ({ label, value, tone }: { label: string; value: number | null; tone?: 'warning' }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
        <p className="text-xs uppercase tracking-[0.18em] text-hud-text-muted">{label}</p>
        <p
            className={`mt-2 text-2xl font-semibold ${
                tone === 'warning' ? 'text-amber-200' : 'text-hud-text-primary'
            }`}
        >
            {formatRatio(value)}
        </p>
    </div>
)

const GmsPreviewAdminEvidencePanel = ({ snapshot }: { snapshot: GmsPreviewAdminEvidenceSnapshot | null }) => (
    <section className="rounded-2xl border border-amber-300/25 bg-amber-300/10 p-5">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div>
                <div className="flex flex-wrap items-center gap-2">
                    <span className="inline-flex items-center gap-1.5 rounded-full border border-amber-300/35 bg-amber-300/15 px-3 py-1 text-xs font-semibold text-amber-100">
                        <ShieldCheck size={14} />
                        운영자 전용
                    </span>
                    <p className="text-xs font-semibold uppercase tracking-[0.22em] text-hud-text-muted">
                        Raw Evidence
                    </p>
                </div>
                <h2 className="mt-3 text-xl font-semibold text-hud-text-primary">
                    GMS preview 원본 근거
                </h2>
                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                    사용자 카드에서는 숨기고, 운영자가 추천 모델의 축별 판단을 점검할 때만 확인합니다.
                </p>
            </div>
            {snapshot && (
                <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-right text-xs text-hud-text-muted">
                    <p>{snapshot.request_id}</p>
                    <p className="mt-1">{formatDateTime(snapshot.generated_at)}</p>
                </div>
            )}
        </div>

        {snapshot && (snapshot.tracks.length > 0 || snapshot.playlists.length > 0) ? (
            <div className="mt-4 grid gap-3">
                {snapshot.tracks.map((track) => (
                    <article
                        key={`${snapshot.request_id}-${track.track_id}`}
                        className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4"
                    >
                        <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                            <div>
                                <h3 className="text-sm font-semibold text-hud-text-primary">{track.title}</h3>
                                <p className="mt-1 text-xs text-hud-text-muted">
                                    {track.artist_name} · {track.source_platform} · rank {track.rank}
                                </p>
                            </div>
                            <span className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-xs font-semibold text-hud-accent-primary">
                                score {formatScore(track.score)}
                            </span>
                        </div>
                        <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                            {track.axis_evidence.map((evidence) => (
                                <AxisEvidenceDiagnostic
                                    key={`${track.track_id}-${evidence.axis}`}
                                    evidence={evidence}
                                />
                            ))}
                        </div>
                    </article>
                ))}
                {snapshot.playlists.map((playlist) => (
                    <article
                        key={`${snapshot.request_id}-${playlist.playlist_id}`}
                        className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4"
                    >
                        <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                            <div>
                                <h3 className="text-sm font-semibold text-hud-text-primary">{playlist.title}</h3>
                                <p className="mt-1 text-xs text-hud-text-muted">
                                    {playlist.source_platform} · playlist · rank {playlist.rank} · {playlist.track_count} tracks
                                </p>
                            </div>
                            <div className="flex flex-wrap gap-2 text-xs">
                                <span className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-hud-accent-primary">
                                    composite {formatScore(playlist.composite_score)}
                                </span>
                                <span className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-hud-text-secondary">
                                    affinity {formatScore(playlist.affinity_score)}
                                </span>
                                <span className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-2.5 py-1 text-hud-text-secondary">
                                    confidence {formatScore(playlist.confidence_score)}
                                </span>
                            </div>
                        </div>
                        <div className="mt-3 grid gap-2 md:grid-cols-2 xl:grid-cols-3">
                            {playlist.axis_evidence.map((evidence) => (
                                <AxisEvidenceDiagnostic
                                    key={`${playlist.playlist_id}-${evidence.axis}`}
                                    evidence={evidence}
                                />
                            ))}
                        </div>
                    </article>
                ))}
            </div>
        ) : (
            <div className="mt-4 rounded-xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/50 p-4 text-sm text-hud-text-muted">
                아직 이 브라우저에서 저장된 GMS preview 원본 근거가 없습니다.
            </div>
        )}
    </section>
)

const AxisEvidenceDiagnostic = ({ evidence }: { evidence: GmsAxisEvidence }) => (
    <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/60 p-3">
        <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-semibold uppercase tracking-[0.18em] text-hud-accent-primary">
                {evidence.axis}
            </span>
            <span className="text-xs text-hud-text-muted">
                {evidence.level} · {formatScore(evidence.score)}
            </span>
        </div>
        <p className="mt-2 text-xs leading-5 text-hud-text-secondary">{evidence.summary}</p>
    </div>
)

export default PlaylistQualityAdminPage
