import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { AlertTriangle, BrainCircuit, Database, Gauge, ListChecks, Music2, RefreshCw, Search, ShieldCheck } from 'lucide-react'
import Button from '@/components/common/Button'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { fetchFeatureCoverageForAdmin, fetchTasteModeRolloutSummaryForAdmin } from '@/services/api'
import type {
    FeatureCoverageAdminResponse,
    FeatureCoverageAudioFeatureCompletion,
    FeatureCoverageAudioFeatureSourceClass,
    FeatureCoverageSummary,
    RecommendationTasteModeSummaryResponse,
} from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

const formatDateTime = (value: string | null | undefined) => {
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

const formatCount = (value: number | null | undefined) =>
    new Intl.NumberFormat('ko-KR').format(value ?? 0)

const formatPercent = (value: number | null | undefined) => {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return '-'
    }
    return `${Math.round(Math.max(0, Math.min(1, value)) * 100)}%`
}

const formatDecimal = (value: number | null | undefined) => {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return '-'
    }
    return value.toFixed(4)
}

const FeatureCoverageAdminPage = () => {
    const { session } = useAuthSession()
    const [report, setReport] = useState<FeatureCoverageAdminResponse | null>(null)
    const [tasteModeSummary, setTasteModeSummary] = useState<RecommendationTasteModeSummaryResponse | null>(null)
    const [tasteModeError, setTasteModeError] = useState<string | null>(null)
    const [targetInput, setTargetInput] = useState('')
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL

    const load = useCallback(async (signal?: AbortSignal, targetUserId?: string) => {
        if (!session || !isAdmin) {
            return
        }
        setLoading(true)
        setError(null)
        try {
            const trimmedTarget = targetUserId?.trim() || undefined
            const response = await fetchFeatureCoverageForAdmin(
                session.userId,
                trimmedTarget,
                signal,
            )
            setReport(response)

            try {
                const summary = await fetchTasteModeRolloutSummaryForAdmin(session.userId, trimmedTarget, 50, signal)
                setTasteModeSummary(summary)
                setTasteModeError(null)
            } catch (summaryErr) {
                if (signal?.aborted) {
                    return
                }
                setTasteModeSummary(null)
                setTasteModeError(summaryErr instanceof Error ? summaryErr.message : 'Taste mode rollout summary를 불러오지 못했습니다.')
            }
        } catch (err) {
            if (signal?.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : 'Feature coverage를 불러오지 못했습니다.')
        } finally {
            setLoading(false)
        }
    }, [isAdmin, session])

    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal)
        return () => controller.abort()
    }, [load])

    const handleTargetSubmit = (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        void load(undefined, targetInput)
    }

    const totals = useMemo(() => {
        if (!report) {
            return null
        }
        return [
            {
                label: 'PMS Audio',
                value: report.pms_library.audio_feature_filled_count,
                total: report.pms_library.track_count,
                ratio: report.pms_library.audio_feature_coverage_ratio,
            },
            {
                label: 'EMS Audio',
                value: report.ems_pool.audio_feature_filled_count,
                total: report.ems_pool.track_count,
                ratio: report.ems_pool.audio_feature_coverage_ratio,
            },
            {
                label: 'EMS ISRC',
                value: report.ems_pool.isrc_count,
                total: report.ems_pool.track_count,
                ratio: report.ems_pool.isrc_coverage_ratio,
            },
            {
                label: 'Playback',
                value: report.pms_library.playback_target_available_count,
                total: report.pms_library.track_count,
                ratio: report.pms_library.playback_target_coverage_ratio,
            },
        ]
    }, [report])

    if (!session || !isAdmin) {
        return (
            <main className="space-y-6">
                <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                    <div className="flex items-center gap-3 text-amber-100">
                        <ShieldCheck size={22} />
                        <h2 className="text-xl font-semibold">운영자 전용 화면</h2>
                    </div>
                    <p className="mt-4 text-sm leading-6 text-hud-text-secondary">
                        이 화면은 {ADMIN_EMAIL} 관리자 계정에만 노출됩니다.
                    </p>
                    <div className="mt-4">
                        <OperatorDiagnosticsNotice compact />
                    </div>
                </section>
            </main>
        )
    }

    return (
        <main className="space-y-6">
            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/85 p-6">
                <div className="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <Gauge size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">특성 커버리지</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            추천 데이터 준비도
                        </h2>
                        <p className="mt-2 text-sm text-hud-text-secondary">
                            {report
                                ? `${report.target_user_id} · 마지막 갱신 ${formatDateTime(report.generated_at)}`
                                : 'PMS, EMS, learning signal 집계를 준비 중입니다.'}
                        </p>
                    </div>
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
                        <form className="flex min-w-0 gap-2" onSubmit={handleTargetSubmit}>
                            <input
                                type="text"
                                value={targetInput}
                                onChange={(event) => setTargetInput(event.target.value)}
                                placeholder={session.userId}
                                className="min-w-0 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/80 px-3 py-2 text-sm text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-accent-primary lg:w-72"
                            />
                            <Button type="submit" variant="outline" disabled={loading}>
                                <Search size={16} />
                                조회
                            </Button>
                        </form>
                        <Button type="button" variant="outline" onClick={() => void load(undefined, targetInput)} disabled={loading}>
                            <RefreshCw size={16} />
                            새로고침
                        </Button>
                    </div>
                </div>
                {error && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{error}</span>
                    </div>
                )}
                {report?.warnings.map((warning) => (
                    <div key={warning} className="mt-5 flex items-start gap-3 rounded-xl border border-amber-300/30 bg-amber-300/10 p-4 text-sm text-amber-100">
                        <AlertTriangle size={18} />
                        <span>{warning}</span>
                    </div>
                ))}
                {report?.drift_signals && report.drift_signals.length > 0 && (
                    <div className="mt-5 space-y-2">
                        <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">Drift signals</p>
                        {report.drift_signals.map((signal) => {
                            const isWarn = signal.severity === 'warn'
                            const containerClass = isWarn
                                ? 'border-amber-300/30 bg-amber-300/10 text-amber-100'
                                : 'border-hud-border-secondary bg-hud-bg-primary/60 text-hud-text-secondary'
                            return (
                                <div
                                    key={`${signal.category}-${signal.target_scope}`}
                                    className={`flex items-start gap-3 rounded-xl border p-3 text-xs ${containerClass}`}
                                >
                                    <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                                    <div className="space-y-1">
                                        <p>
                                            <span className="font-semibold">[{signal.severity}]</span>{' '}
                                            <span className="opacity-80">{signal.category} · {signal.target_scope}</span>
                                        </p>
                                        <p>{signal.message}</p>
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                )}
            </section>

            {report && totals && (
                <>
                    <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
                        {totals.map((item) => (
                            <CoverageStat
                                key={item.label}
                                label={item.label}
                                value={item.value}
                                total={item.total}
                                ratio={item.ratio}
                            />
                        ))}
                    </section>

                    <TasteModeRolloutPanel
                        summary={tasteModeSummary}
                        error={tasteModeError}
                    />

                    <section className="grid gap-5 md:grid-cols-2 xl:grid-cols-5">
                        <CoveragePanel
                            icon={<Music2 size={20} />}
                            title="PMS 보관함"
                            rows={[
                                ['플레이리스트', formatCount(report.pms_library.playlist_count)],
                                ['트랙', formatCount(report.pms_library.track_count)],
                                ['오디오 특성', audioCoverageText(report.pms_library)],
                                ['오래된 오디오', staleAudioText(report.pms_library)],
                                ['ISRC', ratioText(report.pms_library.isrc_count, report.pms_library.track_count, report.pms_library.isrc_coverage_ratio)],
                                ['재생 대상', ratioText(report.pms_library.playback_target_available_count, report.pms_library.track_count, report.pms_library.playback_target_coverage_ratio)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<Database size={20} />}
                            title="EMS 큐"
                            rows={[
                                ['트랙', formatCount(report.ems_pool.track_count)],
                                ['오디오 특성', audioCoverageText(report.ems_pool)],
                                ['오래된 오디오', staleAudioText(report.ems_pool)],
                                ['ISRC', ratioText(report.ems_pool.isrc_count, report.ems_pool.track_count, report.ems_pool.isrc_coverage_ratio)],
                                ['표준 링크', ratioText(report.ems_pool.canonical_track_count, report.ems_pool.track_count, report.ems_pool.canonical_track_coverage_ratio)],
                                ['출처', formatCount(report.ems_pool.sources.length)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<RefreshCw size={20} />}
                            title="EMS 수집 관리"
                            rows={[
                                ['최근 실행', formatCount(report.ems_acquisition.recent_run_count)],
                                ['건너뛴 기사', ratioText(report.ems_acquisition.skipped_article_count, report.ems_acquisition.article_count, report.ems_acquisition.article_count > 0 ? report.ems_acquisition.skipped_article_count / report.ems_acquisition.article_count : 0)],
                                ['건너뛴 시드', ratioText(report.ems_acquisition.skipped_seed_count, report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count, report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count > 0 ? report.ems_acquisition.skipped_seed_count / (report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count) : 0)],
                                ['전체 제외 비율', ratioText(report.ems_acquisition.skipped_item_count, report.ems_acquisition.checked_item_count, report.ems_acquisition.skipped_item_ratio)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<ListChecks size={20} />}
                            title="오디오 특성 보강"
                            rows={[
                                ['최근 작업', formatCount(report.audio_feature_completion.recent_job_count)],
                                ['대기', formatCount(statusJobCount(report.audio_feature_completion, 'queued'))],
                                ['재시도 대기', formatCount(statusJobCount(report.audio_feature_completion, 'retry_wait'))],
                                ['미해결', formatCount(statusJobCount(report.audio_feature_completion, 'unresolved'))],
                                ['실패', formatCount(statusJobCount(report.audio_feature_completion, 'failed'))],
                            ]}
                        />
                        <CoveragePanel
                            icon={<BrainCircuit size={20} />}
                            title="학습 데이터"
                            rows={[
                                ['이벤트', formatCount(report.learning_data.event_count)],
                                ['최근 스냅샷', formatCount(report.learning_data.recent_recommendation_snapshot_count)],
                                ['스냅샷 한도', formatCount(report.learning_data.recent_recommendation_snapshot_limit)],
                                ['상태', report.status],
                            ]}
                        />
                    </section>

                    <section className="grid gap-5 xl:grid-cols-2">
                        <SourceClassCoverageTable
                            title="PMS 오디오 출처 분류"
                            items={report.pms_library.audio_feature_source_classes}
                        />
                        <SourceClassCoverageTable
                            title="EMS 오디오 출처 분류"
                            items={report.ems_pool.audio_feature_source_classes}
                        />
                    </section>

                    <CompletionQueueCoveragePanel coverage={report.audio_feature_completion} />

                    <section className="overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80">
                        <table className="w-full min-w-[980px] text-left text-sm">
                            <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                <tr>
                                    <th className="px-4 py-3">Source</th>
                                    <th className="px-4 py-3">Tracks</th>
                                    <th className="px-4 py-3">Audio</th>
                                    <th className="px-4 py-3">Stale Audio</th>
                                    <th className="px-4 py-3">ISRC</th>
                                    <th className="px-4 py-3">Canonical</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-hud-border-secondary">
                                {report.ems_pool.sources.map((source) => (
                                    <tr key={source.source_platform} className="bg-hud-bg-secondary/40">
                                        <td className="px-4 py-3 font-medium text-hud-text-primary">{source.source_platform}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{formatCount(source.track_count)}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{audioCoverageText(source)}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{staleAudioText(source)}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">
                                            {ratioText(source.isrc_count, source.track_count, source.isrc_coverage_ratio)}
                                        </td>
                                        <td className="px-4 py-3 text-hud-text-secondary">
                                            {ratioText(source.canonical_track_count, source.track_count, source.canonical_track_coverage_ratio)}
                                        </td>
                                    </tr>
                                ))}
                                {!report.ems_pool.sources.length && (
                                    <tr>
                                        <td colSpan={6} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                                            EMS source coverage가 없습니다.
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </section>
                </>
            )}
        </main>
    )
}

const audioCoverageText = (coverage: FeatureCoverageSummary) =>
    `${formatCount(coverage.audio_feature_filled_count)} / ${formatCount(coverage.track_count)} · ${formatPercent(coverage.audio_feature_coverage_ratio)}`

const staleAudioText = (coverage: FeatureCoverageSummary) =>
    `${formatCount(coverage.stale_audio_feature_count)} / ${formatCount(coverage.audio_feature_filled_count)} · ${formatPercent(coverage.stale_audio_feature_ratio)} · latest ${formatDateTime(coverage.latest_audio_resolved_at)}`

const ratioText = (value: number, total: number, ratio: number) =>
    `${formatCount(value)} / ${formatCount(total)} · ${formatPercent(ratio)}`

const sourceClassLabel = (value: string) => value.replace(/_/g, ' ')

const statusJobCount = (coverage: FeatureCoverageAudioFeatureCompletion, status: string) =>
    coverage.status_counts.find((item) => item.status === status)?.job_count ?? 0

const CoverageStat = ({
    label,
    value,
    total,
    ratio,
}: {
    label: string
    value: number
    total: number
    ratio: number
}) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
        <p className="text-xs uppercase tracking-[0.18em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">{formatPercent(ratio)}</p>
        <p className="mt-1 text-xs text-hud-text-muted">
            {formatCount(value)} / {formatCount(total)}
        </p>
    </div>
)

const CoveragePanel = ({
    icon,
    title,
    rows,
}: {
    icon: ReactNode
    title: string
    rows: Array<[string, string]>
}) => (
    <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
        <div className="flex items-center gap-3 text-hud-accent-primary">
            {icon}
            <h3 className="text-sm font-semibold uppercase tracking-[0.2em]">{title}</h3>
        </div>
        <dl className="mt-5 divide-y divide-hud-border-secondary text-sm">
            {rows.map(([label, value]) => (
                <div key={label} className="flex items-start justify-between gap-4 py-3">
                    <dt className="text-hud-text-muted">{label}</dt>
                    <dd className="text-right font-medium text-hud-text-primary">{value}</dd>
                </div>
            ))}
        </dl>
    </section>
)

const TasteModeRolloutPanel = ({
    summary,
    error,
}: {
    summary: RecommendationTasteModeSummaryResponse | null
    error: string | null
}) => {
    const data = summary?.summary
    const reasons = Object.entries(data?.reason_counts ?? {})
        .sort(([, left], [, right]) => right - left)
        .slice(0, 5)

    return (
        <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
            <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
                <div>
                    <div className="flex items-center gap-3 text-hud-accent-primary">
                        <BrainCircuit size={20} />
                        <h3 className="text-sm font-semibold uppercase tracking-[0.2em]">취향 모드 반영 상태</h3>
                    </div>
                    <p className="mt-2 text-xs text-hud-text-muted">
                        최신 요약 {formatDateTime(data?.latest_summary?.created_at)}
                    </p>
                </div>
                <span className="w-fit rounded-full border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-1 text-xs font-semibold text-hud-text-primary">
                    {data?.recommendation ?? 'unavailable'}
                </span>
            </div>

            {error && (
                <div className="mt-4 flex items-start gap-3 rounded-xl border border-amber-300/30 bg-amber-300/10 p-3 text-sm text-amber-100">
                    <AlertTriangle size={16} className="mt-0.5 shrink-0" />
                    <span>취향 모드 반영 상태를 불러오지 못했습니다.</span>
                </div>
            )}

            <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
                <RolloutMetric label="부스트 적용" value={data?.boost_applied_total} />
                <RolloutMetric label="순위 변경" value={data?.rank_changed_total} />
                <RolloutMetric label="적격" value={data?.eligible_total} />
                <RolloutMetric label="보류" value={data?.blocked_total} />
                <RolloutMetric label="시범 적용" value={data?.dry_run_total} />
                <RolloutMetric label="파싱 오류" value={data?.parse_error_count} />
            </div>

            <div className="mt-5 grid gap-5 xl:grid-cols-[0.8fr_1.2fr]">
                <dl className="divide-y divide-hud-border-secondary rounded-xl border border-hud-border-secondary bg-hud-bg-primary/50 text-sm">
                    <RolloutRow label="분석 항목" value={formatCount(data?.entries_analyzed)} />
                    <RolloutRow label="요약 포함 항목" value={formatCount(data?.entries_with_summary)} />
                    <RolloutRow label="부스트 활성" value={formatCount(data?.boost_enabled_count)} />
                    <RolloutRow label="최대 양수 변화" value={formatDecimal(data?.max_positive_delta)} />
                </dl>

                <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/50 p-4">
                    <p className="text-xs font-semibold uppercase tracking-[0.18em] text-hud-text-muted">게이트 사유</p>
                    <div className="mt-3 space-y-2">
                        {reasons.map(([reason, count]) => (
                            <div key={reason} className="flex items-center justify-between gap-4 text-sm">
                                <span className="break-all font-medium text-hud-text-primary">{reason}</span>
                                <span className="text-hud-text-secondary">{formatCount(count)}</span>
                            </div>
                        ))}
                        {!reasons.length && (
                            <p className="text-sm text-hud-text-muted">게이트 사유 요약이 없습니다.</p>
                        )}
                    </div>
                </div>
            </div>
        </section>
    )
}

const RolloutMetric = ({ label, value }: { label: string; value: number | null | undefined }) => (
    <div
        aria-label={`${label} ${formatCount(value)}`}
        className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3"
    >
        <p className="text-[11px] uppercase tracking-[0.16em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-xl font-semibold text-hud-text-primary">{formatCount(value)}</p>
    </div>
)

const RolloutRow = ({ label, value }: { label: string; value: string }) => (
    <div className="flex items-center justify-between gap-4 px-4 py-3">
        <dt className="text-hud-text-muted">{label}</dt>
        <dd className="text-right font-medium text-hud-text-primary">{value}</dd>
    </div>
)

const CompletionQueueCoveragePanel = ({
    coverage,
}: {
    coverage: FeatureCoverageAudioFeatureCompletion
}) => (
    <section className="grid gap-5 xl:grid-cols-[0.8fr_1.2fr]">
        <section className="overflow-x-auto rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80">
            <div className="flex items-center gap-3 border-b border-hud-border-secondary bg-hud-bg-primary/60 px-5 py-4 text-hud-accent-primary">
                <ListChecks size={18} />
                <h3 className="text-xs font-semibold uppercase tracking-[0.2em]">Completion Status</h3>
            </div>
            <table className="w-full text-left text-sm">
                <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                    <tr>
                        <th className="px-4 py-3">Status</th>
                        <th className="px-4 py-3">Jobs</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-hud-border-secondary">
                    {coverage.status_counts.map((item) => (
                        <tr key={item.status} className="bg-hud-bg-secondary/40">
                            <td className="px-4 py-3 font-medium capitalize text-hud-text-primary">
                                {sourceClassLabel(item.status)}
                            </td>
                            <td className="px-4 py-3 text-hud-text-secondary">{formatCount(item.job_count)}</td>
                        </tr>
                    ))}
                    {!coverage.status_counts.length && (
                        <tr>
                            <td colSpan={2} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                                최근 completion job이 없습니다.
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>
        </section>

        <section className="overflow-x-auto rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80">
            <div className="flex items-center gap-3 border-b border-hud-border-secondary bg-hud-bg-primary/60 px-5 py-4 text-hud-accent-primary">
                <AlertTriangle size={18} />
                <h3 className="text-xs font-semibold uppercase tracking-[0.2em]">Completion Top Reasons</h3>
            </div>
            <table className="w-full table-fixed text-left text-sm">
                <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                    <tr>
                        <th className="px-4 py-3">Reason</th>
                        <th className="w-24 px-4 py-3">Jobs</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-hud-border-secondary">
                    {coverage.top_reasons.map((item) => (
                        <tr key={item.reason} className="bg-hud-bg-secondary/40">
                            <td className="max-w-[520px] px-4 py-3 font-medium text-hud-text-primary">
                                <span className="break-words">{item.reason}</span>
                            </td>
                            <td className="px-4 py-3 text-hud-text-secondary">{formatCount(item.job_count)}</td>
                        </tr>
                    ))}
                    {!coverage.top_reasons.length && (
                        <tr>
                            <td colSpan={2} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                                unresolved/retry/failed reason이 없습니다.
                            </td>
                        </tr>
                    )}
                </tbody>
            </table>
            {coverage.warnings.map((warning) => (
                <div key={warning} className="border-t border-hud-border-secondary px-5 py-3 text-xs text-amber-100">
                    {warning}
                </div>
            ))}
        </section>
    </section>
)

const SourceClassCoverageTable = ({
    title,
    items,
}: {
    title: string
    items: FeatureCoverageAudioFeatureSourceClass[]
}) => (
    <section className="overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80">
        <div className="flex items-center gap-3 border-b border-hud-border-secondary bg-hud-bg-primary/60 px-5 py-4 text-hud-accent-primary">
            <Database size={18} />
            <h3 className="text-xs font-semibold uppercase tracking-[0.2em]">{title}</h3>
        </div>
        <table className="w-full min-w-[620px] text-left text-sm">
            <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                <tr>
                    <th className="px-4 py-3">Class</th>
                    <th className="px-4 py-3">Tracks</th>
                    <th className="px-4 py-3">Filled</th>
                    <th className="px-4 py-3">Stale</th>
                    <th className="px-4 py-3">Latest</th>
                </tr>
            </thead>
            <tbody className="divide-y divide-hud-border-secondary">
                {items.map((item) => (
                    <tr key={item.source_class} className="bg-hud-bg-secondary/40">
                        <td className="px-4 py-3 font-medium capitalize text-hud-text-primary">
                            {sourceClassLabel(item.source_class)}
                        </td>
                        <td className="px-4 py-3 text-hud-text-secondary">{formatCount(item.track_count)}</td>
                        <td className="px-4 py-3 text-hud-text-secondary">
                            {ratioText(
                                item.audio_feature_filled_count,
                                item.track_count,
                                item.audio_feature_coverage_ratio,
                            )}
                        </td>
                        <td className="px-4 py-3 text-hud-text-secondary">
                            {ratioText(
                                item.stale_audio_feature_count,
                                item.audio_feature_filled_count,
                                item.stale_audio_feature_ratio,
                            )}
                        </td>
                        <td className="px-4 py-3 text-hud-text-secondary">
                            {formatDateTime(item.latest_audio_resolved_at)}
                        </td>
                    </tr>
                ))}
                {!items.length && (
                    <tr>
                        <td colSpan={5} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                            Audio source class coverage가 없습니다.
                        </td>
                    </tr>
                )}
            </tbody>
        </table>
    </section>
)

export default FeatureCoverageAdminPage
