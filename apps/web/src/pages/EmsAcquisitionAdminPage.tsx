import { Children, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { AlertTriangle, Bot, DatabaseZap, Play, RefreshCw, Rss, ShieldCheck, Target } from 'lucide-react'
import Button from '@/components/common/Button'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    fetchEmsAcquisitionRuns,
    fetchEmsAcquisitionSourcePresets,
    fetchEmsAcquisitionSourceQuality,
    fetchEmsAcquisitionStatus,
    runEmsAcquisition,
} from '@/services/api'
import type {
    EmsAcquisitionRunItem,
    EmsAcquisitionRunResponse,
    EmsAcquisitionSeedItem,
    EmsAcquisitionSignalItem,
    EmsAcquisitionSourcePresetItem,
    EmsAcquisitionSourceQualityItem,
    EmsAcquisitionSourceRequest,
} from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

const DEFAULT_SOURCES = [
    'Pitchfork News|https://pitchfork.com/feed/feed-news/rss|1.0',
    'Pitchfork Track Reviews|https://pitchfork.com/feed/feed-track-reviews/rss|1.1',
    'Pitchfork Best New Tracks|https://pitchfork.com/feed/reviews/best/tracks/rss|1.4',
    'Stereogum|https://www.stereogum.com/feed/|1.1',
    'BrooklynVegan|https://www.brooklynvegan.com/feed/|1.0',
    'FACT Magazine|https://www.factmag.com/feed/|1.0',
    'The FADER|https://www.thefader.com/feed.rss|1.0',
    'Billboard Music News|https://www.billboard.com/c/music/music-news/feed/|1.0',
    'Rolling Stone Music News|https://www.rollingstone.com/music/music-news/feed/|1.0',
    'The Line of Best Fit|https://www.thelineofbestfit.com/feed|1.0',
    'SPIN|https://www.spinmagazine.com/feed/|0.9',
    'NME|https://www.nme.com/?alt=rss|1.0',
].join('\n')

const statusTone: Record<string, string> = {
    not_run: 'border-hud-border-secondary bg-hud-bg-primary text-hud-text-muted',
    skipped: 'border-hud-border-secondary bg-hud-bg-primary text-hud-text-muted',
    running: 'border-cyan-300/40 bg-cyan-300/10 text-cyan-100',
    completed: 'border-emerald-300/40 bg-emerald-300/10 text-emerald-100',
    completed_with_failures: 'border-orange-300/40 bg-orange-300/10 text-orange-100',
    failed: 'border-rose-300/40 bg-rose-500/10 text-rose-100',
}

const triggerTone = (trigger: string | null | undefined) => {
    switch (trigger) {
        case 'scheduled':
            return 'border-cyan-300/40 bg-cyan-300/10 text-cyan-100'
        case 'manual':
            return 'border-hud-accent-primary/40 bg-hud-accent-primary/10 text-hud-accent-primary'
        default:
            return 'border-hud-border-secondary bg-hud-bg-primary text-hud-text-muted'
    }
}

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

const EmsAcquisitionAdminPage = () => {
    const { session } = useAuthSession()
    const [targetUserId, setTargetUserId] = useState('')
    const [platforms, setPlatforms] = useState<string[]>(['spotify', 'tidal'])
    const [selectedPresetId, setSelectedPresetId] = useState('custom')
    const [sourceLines, setSourceLines] = useState(DEFAULT_SOURCES)
    const [maxArticlesPerSource, setMaxArticlesPerSource] = useState(15)
    const [maxSignalsPerRun, setMaxSignalsPerRun] = useState(40)
    const [perSeedLimit, setPerSeedLimit] = useState(5)
    const [status, setStatus] = useState<EmsAcquisitionRunResponse | null>(null)
    const [runs, setRuns] = useState<EmsAcquisitionRunItem[]>([])
    const [sourcePresets, setSourcePresets] = useState<EmsAcquisitionSourcePresetItem[]>([])
    const [sourceQuality, setSourceQuality] = useState<EmsAcquisitionSourceQualityItem[]>([])
    const [sourceQualityDays, setSourceQualityDays] = useState(14)
    const [loading, setLoading] = useState(false)
    const [running, setRunning] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const statusAbortRef = useRef<AbortController | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL
    const latestRun = status?.run ?? null
    const signals = status?.signals ?? []
    const seeds = status?.seeds ?? []

    const parsedSources = useMemo(() => parseSources(sourceLines), [sourceLines])
    const collectionTarget = useMemo(() => {
        const sourceCount = parsedSources.length
        const articleTarget = sourceCount * maxArticlesPerSource
        const signalTarget = Math.min(maxSignalsPerRun, articleTarget)
        const seedQueryTarget = signalTarget * Math.max(platforms.length, 1) * 4
        const trackTarget = seedQueryTarget * perSeedLimit
        return {
            sourceCount,
            articleTarget,
            signalTarget,
            seedQueryTarget,
            trackTarget,
        }
    }, [maxArticlesPerSource, maxSignalsPerRun, parsedSources.length, perSeedLimit, platforms.length])

    const schedulerHealth = useMemo(() => {
        const scheduledRuns = runs.filter((run) => run.trigger_type === 'scheduled')
        const manualRuns = runs.filter((run) => run.trigger_type === 'manual')
        const latestScheduled = scheduledRuns[0] ?? null
        const skippedScheduled = scheduledRuns.filter((run) => run.status === 'skipped').length
        return {
            scheduledCount: scheduledRuns.length,
            manualCount: manualRuns.length,
            skippedScheduledCount: skippedScheduled,
            latestScheduled,
        }
    }, [runs])

    const skipDrift = useMemo(() => {
        const completedRuns = runs.filter((run) =>
            run.status === 'completed' || run.status === 'completed_with_failures'
        )
        const totals = completedRuns.reduce(
            (acc, run) => {
                acc.articlesAttempted += run.article_count + run.skipped_article_count
                acc.articlesSkipped += run.skipped_article_count
                acc.seedsAttempted += run.seed_count + run.skipped_seed_count
                acc.seedsSkipped += run.skipped_seed_count
                return acc
            },
            { articlesAttempted: 0, articlesSkipped: 0, seedsAttempted: 0, seedsSkipped: 0 },
        )
        const totalAttempted = totals.articlesAttempted + totals.seedsAttempted
        const totalSkipped = totals.articlesSkipped + totals.seedsSkipped
        const skipRatio = totalAttempted === 0 ? 0 : totalSkipped / totalAttempted
        let severity: 'ok' | 'warn' | 'critical' = 'ok'
        if (skipRatio >= 0.8) {
            severity = 'critical'
        } else if (skipRatio >= 0.5) {
            severity = 'warn'
        }
        return {
            runsConsidered: completedRuns.length,
            totalAttempted,
            totalSkipped,
            skipRatio,
            articlesAttempted: totals.articlesAttempted,
            articlesSkipped: totals.articlesSkipped,
            seedsAttempted: totals.seedsAttempted,
            seedsSkipped: totals.seedsSkipped,
            severity,
        }
    }, [runs])

    useEffect(() => {
        if (session?.userId && !targetUserId) {
            setTargetUserId(session.userId)
        }
    }, [session?.userId, targetUserId])

    const loadSourceQuality = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        try {
            const response = await fetchEmsAcquisitionSourceQuality(sourceQualityDays, signal)
            setSourceQuality(response.sources)
        } catch {
            // Quality summary failures are non-fatal — leave existing entries.
        }
    }, [isAdmin, session, sourceQualityDays])

    const loadStatus = useCallback(async () => {
        if (!session || !isAdmin) {
            return
        }
        statusAbortRef.current?.abort()
        const controller = new AbortController()
        statusAbortRef.current = controller
        setLoading(true)
        setError(null)
        try {
            const [latest, recent, presets] = await Promise.all([
                fetchEmsAcquisitionStatus(controller.signal),
                fetchEmsAcquisitionRuns(controller.signal),
                fetchEmsAcquisitionSourcePresets(controller.signal),
            ])
            if (controller.signal.aborted) {
                return
            }
            setStatus(latest)
            setRuns(recent.runs)
            setSourcePresets(presets.presets)
            void loadSourceQuality(controller.signal)
        } catch (err) {
            if (!controller.signal.aborted) {
                setError(err instanceof Error ? err.message : 'EMS acquisition 상태를 불러오지 못했습니다.')
            }
        } finally {
            if (statusAbortRef.current === controller) {
                statusAbortRef.current = null
                setLoading(false)
            }
        }
    }, [isAdmin, session, loadSourceQuality])

    useEffect(() => {
        void loadStatus()
        return () => {
            statusAbortRef.current?.abort()
            statusAbortRef.current = null
        }
    }, [loadStatus])

    useEffect(() => {
        if (!session || !isAdmin) {
            return undefined
        }
        const intervalId = window.setInterval(() => {
            if (document.visibilityState === 'visible') {
                void loadStatus()
            }
        }, 7000)
        return () => window.clearInterval(intervalId)
    }, [isAdmin, loadStatus, session])

    const togglePlatform = (platform: string) => {
        setPlatforms((current) =>
            current.includes(platform)
                ? current.filter((item) => item !== platform)
                : [...current, platform],
        )
    }

    const applySourcePreset = (presetId: string) => {
        setSelectedPresetId(presetId)
        const preset = sourcePresets.find((item) => item.id === presetId)
        if (!preset) {
            return
        }
        setSourceLines(formatSources(preset.sources))
        setMaxArticlesPerSource(preset.max_articles_per_source)
        setMaxSignalsPerRun(preset.max_signals_per_run)
        setPerSeedLimit(preset.per_seed_limit)
    }

    const handleRun = async () => {
        if (!session) {
            return
        }
        if (!platforms.length) {
            setError('최소 1개 provider platform을 선택하세요.')
            return
        }
        const userId = targetUserId.trim()
        if (!userId) {
            setError('수집 대상 user id를 입력하세요.')
            return
        }
        if (!parsedSources.length) {
            setError('최소 1개 RSS source를 입력하세요.')
            return
        }

        setRunning(true)
        setError(null)
        try {
            const response = await runEmsAcquisition({
                user_id: userId,
                platforms,
                source_preset: selectedPresetId === 'custom' ? undefined : selectedPresetId,
                sources: parsedSources,
                max_articles_per_source: maxArticlesPerSource,
                max_signals_per_run: maxSignalsPerRun,
                per_seed_limit: perSeedLimit,
            })
            setStatus(response)
            await loadStatus()
        } catch (err) {
            setError(err instanceof Error ? err.message : 'EMS acquisition 실행이 실패했습니다.')
        } finally {
            setRunning(false)
        }
    }

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
                <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <Bot size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.24em]">EMS 수집 관리</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            Editorial source 기반 EMS 수집
                        </h2>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                        <Button type="button" variant="outline" onClick={() => void loadStatus()} disabled={loading}>
                            <RefreshCw size={16} />
                            새로고침
                        </Button>
                        <Button type="button" onClick={() => void handleRun()} disabled={running}>
                            <Play size={16} />
                            실행
                        </Button>
                    </div>
                </div>
                {error && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{error}</span>
                    </div>
                )}
            </section>

            <section className="grid gap-5 xl:grid-cols-[minmax(0,0.8fr)_minmax(0,1.2fr)]">
                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                    <div className="flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                        <Rss size={18} />
                        실행 입력
                    </div>
                    <div className="mt-5 space-y-4">
                        <label className="block text-sm text-hud-text-secondary">
                            User ID
                            <input
                                type="text"
                                value={targetUserId}
                                onChange={(event) => setTargetUserId(event.target.value)}
                                className="mt-2 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 font-mono text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                        </label>

                        <label className="block text-sm text-hud-text-secondary">
                            Platforms
                            <div className="mt-2 flex gap-2">
                                {['spotify', 'tidal'].map((platform) => (
                                    <button
                                        key={platform}
                                        type="button"
                                        onClick={() => togglePlatform(platform)}
                                        className={`rounded-lg border px-3 py-2 text-sm transition-hud ${
                                            platforms.includes(platform)
                                                ? 'border-hud-accent-primary bg-hud-accent-primary/15 text-hud-accent-primary'
                                                : 'border-hud-border-secondary bg-hud-bg-primary/60 text-hud-text-secondary'
                                        }`}
                                    >
                                        {platform}
                                    </button>
                                ))}
                            </div>
                        </label>

                        <label className="block text-sm text-hud-text-secondary">
                            Source preset
                            <select
                                value={selectedPresetId}
                                onChange={(event) => applySourcePreset(event.target.value)}
                                className="mt-2 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            >
                                <option value="custom">Custom sources</option>
                                {sourcePresets.map((preset) => (
                                    <option key={preset.id} value={preset.id}>
                                        {preset.name} ({preset.source_count})
                                    </option>
                                ))}
                            </select>
                        </label>

                        <label className="block text-sm text-hud-text-secondary">
                            RSS sources
                            <textarea
                                value={sourceLines}
                                onChange={(event) => {
                                    setSourceLines(event.target.value)
                                    setSelectedPresetId('custom')
                                }}
                                className="mt-2 min-h-[140px] w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-3 py-3 font-mono text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                spellCheck={false}
                            />
                        </label>

                        <div className="grid gap-3 sm:grid-cols-3">
                            <NumberInput label="Articles/source" value={maxArticlesPerSource} onChange={setMaxArticlesPerSource} min={1} max={50} />
                            <NumberInput label="Signals/run" value={maxSignalsPerRun} onChange={setMaxSignalsPerRun} min={1} max={200} />
                            <NumberInput label="Seed limit" value={perSeedLimit} onChange={setPerSeedLimit} min={1} max={50} />
                        </div>
                        <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4">
                            <div className="mb-3 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                                <Target size={16} />
                                Collection target
                            </div>
                            <div className="grid gap-2 sm:grid-cols-5">
                                <MiniStat label="Sources" value={collectionTarget.sourceCount} />
                                <MiniStat label="Articles" value={collectionTarget.articleTarget} />
                                <MiniStat label="Signals" value={collectionTarget.signalTarget} />
                                <MiniStat label="Seed queries" value={collectionTarget.seedQueryTarget} />
                                <MiniStat label="Track cap" value={collectionTarget.trackTarget} />
                            </div>
                        </div>
                    </div>
                </div>

                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                    <div className="flex flex-col gap-3 border-b border-hud-border-secondary pb-4 lg:flex-row lg:items-start lg:justify-between">
                        <div>
                            <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                Latest Run
                            </p>
                            <h3 className="mt-2 text-xl font-semibold text-hud-text-primary">
                                {latestRun ? `Run #${latestRun.id}` : '아직 실행 기록 없음'}
                            </h3>
                            <p className="mt-1 text-sm text-hud-text-secondary">
                                {latestRun ? formatDateTime(latestRun.started_at) : '-'}
                            </p>
                        </div>
                        <span className={`w-fit rounded-full border px-3 py-1 text-xs ${statusTone[status?.status ?? 'not_run'] ?? statusTone.not_run}`}>
                            {status?.status ?? 'not_run'}
                        </span>
                    </div>

                    {latestRun && (
                        <>
                            <div className="mt-4 grid gap-3 sm:grid-cols-3">
                                <Stat label="Articles" value={latestRun.article_count} />
                                <Stat label="Signals" value={latestRun.signal_count} />
                                <Stat label="Seeds" value={latestRun.seed_count} />
                                <Stat label="Pool Runs" value={latestRun.pool_run_count} />
                                <Stat label="Skipped" value={latestRun.skipped_article_count + latestRun.skipped_seed_count} />
                                <Stat label="Failures" value={latestRun.failed_source_count + latestRun.failed_seed_count} />
                            </div>
                            {(latestRun.message || latestRun.last_error) && (
                                <p className="mt-4 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-3 text-sm text-hud-text-secondary">
                                    {latestRun.last_error ?? latestRun.message}
                                </p>
                            )}
                        </>
                    )}
                </div>
            </section>

            <section className="grid gap-5 xl:grid-cols-2">
                <DataTable title="Signals" icon={<Bot size={18} />} empty="최근 signal이 없습니다.">
                    {signals.map((signal) => (
                        <SignalRow key={signal.id} signal={signal} />
                    ))}
                </DataTable>
                <DataTable title="Seeds" icon={<DatabaseZap size={18} />} empty="최근 seed가 없습니다.">
                    {seeds.map((seed) => (
                        <SeedRow key={seed.id} seed={seed} />
                    ))}
                </DataTable>
            </section>

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                <div className="mb-4 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                    <RefreshCw size={18} />
                    Scheduler
                </div>
                <div className="grid gap-3 sm:grid-cols-4">
                    <Stat label="Scheduled runs" value={schedulerHealth.scheduledCount} />
                    <Stat label="Manual runs" value={schedulerHealth.manualCount} />
                    <Stat label="Skipped (scheduled)" value={schedulerHealth.skippedScheduledCount} />
                    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3">
                        <p className="text-[10px] uppercase tracking-[0.22em] text-hud-text-muted">Last scheduled</p>
                        <p className="mt-1 truncate text-sm text-hud-text-primary">
                            {schedulerHealth.latestScheduled
                                ? formatDateTime(schedulerHealth.latestScheduled.started_at)
                                : '-'}
                        </p>
                    </div>
                </div>
                {schedulerHealth.latestScheduled && (schedulerHealth.latestScheduled.last_error || schedulerHealth.latestScheduled.message) && (
                    <p className="mt-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 p-3 text-xs text-hud-text-secondary">
                        Latest scheduled run #{schedulerHealth.latestScheduled.id} ({schedulerHealth.latestScheduled.status}):
                        {' '}{schedulerHealth.latestScheduled.last_error ?? schedulerHealth.latestScheduled.message}
                    </p>
                )}
                {schedulerHealth.scheduledCount === 0 && (
                    <p className="mt-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 p-3 text-xs text-hud-text-muted">
                        최근 기록에 scheduled trigger run이 없습니다. `app.ems.acquisition.user-id` 설정 또는 scheduler 가 disabled 인지 확인하세요.
                    </p>
                )}
            </section>

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
                    <div className="flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                        <Rss size={18} />
                        Source quality
                    </div>
                    <div className="flex items-center gap-2">
                        <label className="text-[10px] uppercase tracking-[0.18em] text-hud-text-muted" htmlFor="source-quality-days">
                            Lookback (days)
                        </label>
                        <input
                            id="source-quality-days"
                            type="number"
                            min={1}
                            max={90}
                            value={sourceQualityDays}
                            onChange={(e) => setSourceQualityDays(Math.max(1, Math.min(90, Number(e.target.value) || 1)))}
                            className="w-20 rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-2 py-1 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                        />
                        <Button type="button" variant="outline" onClick={() => void loadSourceQuality()}>
                            <RefreshCw size={14} />
                            Reload
                        </Button>
                    </div>
                </div>
                {sourceQuality.length === 0 ? (
                    <p className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 p-3 text-xs text-hud-text-muted">
                        최근 {sourceQualityDays}일 동안 source 별 signal 기록이 없습니다.
                    </p>
                ) : (
                    <div className="overflow-hidden rounded-xl border border-hud-border-secondary">
                        <table className="w-full text-left text-xs">
                            <thead className="bg-hud-bg-primary/80 text-[10px] uppercase tracking-[0.22em] text-hud-text-muted">
                                <tr>
                                    <th className="px-3 py-2">Source</th>
                                    <th className="px-3 py-2 text-right">Signals</th>
                                    <th className="px-3 py-2 text-right">Avg confidence</th>
                                    <th className="px-3 py-2 text-right">Last signal</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-hud-border-secondary">
                                {sourceQuality.map((source) => (
                                    <tr key={source.source_name} className="bg-hud-bg-secondary/40">
                                        <td className="px-3 py-2 text-hud-text-primary">{source.source_name}</td>
                                        <td className="px-3 py-2 text-right text-hud-text-primary">{source.signal_count}</td>
                                        <td className="px-3 py-2 text-right text-hud-text-secondary">
                                            {source.avg_confidence.toFixed(3)}
                                        </td>
                                        <td className="px-3 py-2 text-right text-hud-text-muted">
                                            {formatDateTime(source.last_signal_at)}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            <section className={`rounded-2xl border p-5 ${
                skipDrift.severity === 'critical'
                    ? 'border-rose-300/40 bg-rose-500/10'
                    : skipDrift.severity === 'warn'
                        ? 'border-amber-300/40 bg-amber-300/10'
                        : 'border-hud-border-secondary bg-hud-bg-secondary/80'
            }`}>
                <div className="mb-4 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                    <AlertTriangle size={18} className={
                        skipDrift.severity === 'critical' ? 'text-rose-300'
                            : skipDrift.severity === 'warn' ? 'text-amber-200'
                                : 'text-hud-text-muted'
                    } />
                    Skip drift
                    <span className={`ml-2 rounded-full border px-2 py-0.5 text-[10px] uppercase tracking-[0.18em] ${
                        skipDrift.severity === 'critical'
                            ? 'border-rose-300/40 bg-rose-500/10 text-rose-100'
                            : skipDrift.severity === 'warn'
                                ? 'border-amber-300/40 bg-amber-300/10 text-amber-100'
                                : 'border-hud-border-secondary bg-hud-bg-primary text-hud-text-muted'
                    }`}>
                        {skipDrift.severity}
                    </span>
                </div>
                {skipDrift.runsConsidered === 0 ? (
                    <p className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 p-3 text-xs text-hud-text-muted">
                        완료된 run이 아직 없습니다. 실행 후 다시 확인하세요.
                    </p>
                ) : (
                    <>
                        <div className="grid gap-3 sm:grid-cols-4">
                            <Stat label="Skip ratio" value={`${(skipDrift.skipRatio * 100).toFixed(1)}%`} />
                            <Stat label="Skipped (total)" value={skipDrift.totalSkipped} />
                            <Stat label="Skipped articles" value={skipDrift.articlesSkipped} />
                            <Stat label="Skipped seeds" value={skipDrift.seedsSkipped} />
                        </div>
                        <p className="mt-3 text-xs text-hud-text-secondary">
                            최근 {skipDrift.runsConsidered}개 완료 run 기준 ·
                            attempts: {skipDrift.totalAttempted} (articles {skipDrift.articlesAttempted} + seeds {skipDrift.seedsAttempted})
                        </p>
                        {skipDrift.severity !== 'ok' && (
                            <p className="mt-2 text-xs text-hud-text-primary">
                                skip ratio가 임계치({skipDrift.severity === 'critical' ? '80%' : '50%'})를 초과했습니다.
                                source 품질, AI signal cutoff, dedupe 기준을 점검하세요.
                            </p>
                        )}
                    </>
                )}
            </section>

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                <div className="mb-4 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                    <RefreshCw size={18} />
                    최근 실행
                </div>
                <div className="grid gap-3 lg:grid-cols-2">
                    {runs.map((run) => (
                        <div key={run.id ?? run.started_at} className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4">
                            <div className="flex items-start justify-between gap-3">
                                <div>
                                    <p className="text-sm font-semibold text-hud-text-primary">Run #{run.id}</p>
                                    <p className="mt-1 text-xs text-hud-text-muted">{formatDateTime(run.started_at)}</p>
                                </div>
                                <div className="flex flex-wrap items-center justify-end gap-2">
                                    <span className={`rounded-full border px-2.5 py-1 text-[11px] ${triggerTone(run.trigger_type)}`}>
                                        {run.trigger_type}
                                    </span>
                                    <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[run.status] ?? statusTone.not_run}`}>
                                        {run.status}
                                    </span>
                                </div>
                            </div>
                            <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-hud-text-secondary sm:grid-cols-5">
                                <span>{run.signal_count} signals</span>
                                <span>{run.seed_count} seeds</span>
                                <span>{run.pool_run_count} pool</span>
                                <span>{run.skipped_article_count + run.skipped_seed_count} skipped</span>
                                <span>{run.failed_source_count + run.failed_seed_count} failed</span>
                            </div>
                            {(run.last_error || run.message) && (
                                <p className="mt-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/40 p-2 text-xs text-hud-text-muted">
                                    {run.last_error ?? run.message}
                                </p>
                            )}
                        </div>
                    ))}
                    {!runs.length && (
                        <p className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm text-hud-text-muted">
                            아직 EMS acquisition 실행 기록이 없습니다.
                        </p>
                    )}
                </div>
            </section>
        </main>
    )
}

const NumberInput = ({
    label,
    value,
    onChange,
    min,
    max,
}: {
    label: string
    value: number
    onChange: (value: number) => void
    min: number
    max: number
}) => (
    <label className="block text-sm text-hud-text-secondary">
        {label}
        <input
            type="number"
            min={min}
            max={max}
            value={value}
            onChange={(event) => onChange(Math.min(Math.max(Number(event.target.value), min), max))}
            className="mt-2 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
        />
    </label>
)

const Stat = ({ label, value }: { label: string; value: number | string }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
        <p className="text-xs uppercase tracking-[0.16em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">{value}</p>
    </div>
)

const MiniStat = ({ label, value }: { label: string; value: number | string }) => (
    <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/60 p-3">
        <p className="text-[10px] uppercase tracking-[0.14em] text-hud-text-muted">{label}</p>
        <p className="mt-1 text-lg font-semibold text-hud-text-primary">{value}</p>
    </div>
)

const DataTable = ({
    title,
    icon,
    empty,
    children,
}: {
    title: string
    icon: ReactNode
    empty: string
    children: ReactNode
}) => (
    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
        <div className="mb-4 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
            {icon}
            {title}
        </div>
        <div className="space-y-3">
            {Children.count(children) > 0 ? children : (
                <p className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm text-hud-text-muted">
                    {empty}
                </p>
            )}
        </div>
    </div>
)

const SignalRow = ({ signal }: { signal: EmsAcquisitionSignalItem }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4">
        <div className="flex items-start justify-between gap-3">
            <div>
                <p className="text-sm font-semibold text-hud-text-primary">{signal.query}</p>
                <p className="mt-1 text-xs text-hud-text-muted">
                    {signal.signal_type} / {signal.source_name}
                </p>
            </div>
            <span className="rounded-full border border-hud-accent-primary/30 bg-hud-accent-primary/10 px-2.5 py-1 text-xs text-hud-accent-primary">
                {Math.round(signal.confidence_score * 100)}%
            </span>
        </div>
        {signal.rationale && (
            <p className="mt-3 text-xs leading-5 text-hud-text-secondary">{signal.rationale}</p>
        )}
    </div>
)

const SeedRow = ({ seed }: { seed: EmsAcquisitionSeedItem }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4">
        <div className="flex items-start justify-between gap-3">
            <div>
                <p className="text-sm font-semibold text-hud-text-primary">{seed.query}</p>
                <p className="mt-1 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                    {seed.platform_id}
                </p>
            </div>
            <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[seed.status] ?? statusTone.not_run}`}>
                {seed.status}
            </span>
        </div>
        <div className="mt-3 grid grid-cols-3 gap-2 text-xs text-hud-text-secondary">
            <span>Pool #{seed.pool_run_id ?? '-'}</span>
            <span>{seed.result_playlist_count} playlists</span>
            <span>{seed.result_track_count} tracks</span>
        </div>
        {seed.last_error && (
            <p className="mt-3 text-xs leading-5 text-rose-200">{seed.last_error}</p>
        )}
    </div>
)

const parseSources = (value: string): EmsAcquisitionSourceRequest[] =>
    value
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
            const parts = line.split('|').map((part) => part.trim())
            const [name, url, weight] = parts.length === 1 ? [parts[0], parts[0], '1.0'] : parts
            return {
                name: name || url,
                type: 'rss',
                url,
                weight: weight ? Number(weight) : 1.0,
            }
        })
        .filter((source) => Boolean(source.url))

const formatSources = (sources: EmsAcquisitionSourceRequest[]) =>
    sources
        .map((source) => `${source.name || source.url}|${source.url}|${source.weight ?? 1.0}`)
        .join('\n')

export default EmsAcquisitionAdminPage
