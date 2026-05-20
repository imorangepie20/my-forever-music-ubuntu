import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { AlertTriangle, BrainCircuit, Database, Gauge, ListChecks, Music2, RefreshCw, Search, ShieldCheck } from 'lucide-react'
import Button from '@/components/common/Button'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { fetchFeatureCoverageForAdmin } from '@/services/api'
import type {
    FeatureCoverageAdminResponse,
    FeatureCoverageAudioFeatureCompletion,
    FeatureCoverageAudioFeatureSourceClass,
    FeatureCoverageSummary,
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

const FeatureCoverageAdminPage = () => {
    const { session } = useAuthSession()
    const [report, setReport] = useState<FeatureCoverageAdminResponse | null>(null)
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
            const response = await fetchFeatureCoverageForAdmin(
                session.userId,
                targetUserId?.trim() || undefined,
                signal,
            )
            setReport(response)
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
                        <h2 className="text-xl font-semibold">Feature Coverage Admin</h2>
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
                <div className="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <Gauge size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">Feature Coverage</p>
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

                    <section className="grid gap-5 md:grid-cols-2 xl:grid-cols-5">
                        <CoveragePanel
                            icon={<Music2 size={20} />}
                            title="PMS Library"
                            rows={[
                                ['Playlists', formatCount(report.pms_library.playlist_count)],
                                ['Tracks', formatCount(report.pms_library.track_count)],
                                ['Audio Features', audioCoverageText(report.pms_library)],
                                ['Stale Audio', staleAudioText(report.pms_library)],
                                ['ISRC', ratioText(report.pms_library.isrc_count, report.pms_library.track_count, report.pms_library.isrc_coverage_ratio)],
                                ['Playback Target', ratioText(report.pms_library.playback_target_available_count, report.pms_library.track_count, report.pms_library.playback_target_coverage_ratio)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<Database size={20} />}
                            title="EMS Pool"
                            rows={[
                                ['Tracks', formatCount(report.ems_pool.track_count)],
                                ['Audio Features', audioCoverageText(report.ems_pool)],
                                ['Stale Audio', staleAudioText(report.ems_pool)],
                                ['ISRC', ratioText(report.ems_pool.isrc_count, report.ems_pool.track_count, report.ems_pool.isrc_coverage_ratio)],
                                ['Canonical Link', ratioText(report.ems_pool.canonical_track_count, report.ems_pool.track_count, report.ems_pool.canonical_track_coverage_ratio)],
                                ['Sources', formatCount(report.ems_pool.sources.length)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<RefreshCw size={20} />}
                            title="EMS Acquisition"
                            rows={[
                                ['Recent Runs', formatCount(report.ems_acquisition.recent_run_count)],
                                ['Articles Skipped', ratioText(report.ems_acquisition.skipped_article_count, report.ems_acquisition.article_count, report.ems_acquisition.article_count > 0 ? report.ems_acquisition.skipped_article_count / report.ems_acquisition.article_count : 0)],
                                ['Seeds Skipped', ratioText(report.ems_acquisition.skipped_seed_count, report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count, report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count > 0 ? report.ems_acquisition.skipped_seed_count / (report.ems_acquisition.seed_count + report.ems_acquisition.skipped_seed_count) : 0)],
                                ['Overall Skip Ratio', ratioText(report.ems_acquisition.skipped_item_count, report.ems_acquisition.checked_item_count, report.ems_acquisition.skipped_item_ratio)],
                            ]}
                        />
                        <CoveragePanel
                            icon={<ListChecks size={20} />}
                            title="Audio Completion"
                            rows={[
                                ['Recent Jobs', formatCount(report.audio_feature_completion.recent_job_count)],
                                ['Queued', formatCount(statusJobCount(report.audio_feature_completion, 'queued'))],
                                ['Retry Wait', formatCount(statusJobCount(report.audio_feature_completion, 'retry_wait'))],
                                ['Unresolved', formatCount(statusJobCount(report.audio_feature_completion, 'unresolved'))],
                                ['Failed', formatCount(statusJobCount(report.audio_feature_completion, 'failed'))],
                            ]}
                        />
                        <CoveragePanel
                            icon={<BrainCircuit size={20} />}
                            title="Learning Data"
                            rows={[
                                ['Events', formatCount(report.learning_data.event_count)],
                                ['Recent Snapshots', formatCount(report.learning_data.recent_recommendation_snapshot_count)],
                                ['Snapshot Limit', formatCount(report.learning_data.recent_recommendation_snapshot_limit)],
                                ['Status', report.status],
                            ]}
                        />
                    </section>

                    <section className="grid gap-5 xl:grid-cols-2">
                        <SourceClassCoverageTable
                            title="PMS Audio Source Classes"
                            items={report.pms_library.audio_feature_source_classes}
                        />
                        <SourceClassCoverageTable
                            title="EMS Audio Source Classes"
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
