import { useCallback, useEffect, useMemo, useState } from 'react'
import { AlertTriangle, CalendarClock, ExternalLink, RefreshCw, ShieldCheck, TimerReset } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    fetchSchedulingAdminStatus,
    fetchTidalWebTokenStatus,
    runEmsDiscovery,
    setTidalWebToken,
} from '@/services/api'
import type { SchedulingAdminResponse, SchedulingAdminScheduleItem, TidalWebTokenStatusResponse } from '@/types/api'

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

const formatDelay = (value: number | null | undefined) => {
    if (value === null || value === undefined) {
        return '-'
    }
    if (value === 0) {
        return '즉시'
    }
    if (value % 86_400_000 === 0) {
        const days = value / 86_400_000
        return days === 1 ? '1일' : `${days}일`
    }
    if (value % 3_600_000 === 0) {
        const hours = value / 3_600_000
        return hours === 1 ? '1시간' : `${hours}시간`
    }
    if (value % 60_000 === 0) {
        const minutes = value / 60_000
        return minutes === 1 ? '1분' : `${minutes}분`
    }
    if (value % 1_000 === 0) {
        return `${value / 1_000}초`
    }
    return `${value}ms`
}

const statusClass = (status: string) => {
    if (status === 'active') {
        return 'border-emerald-300/30 bg-emerald-500/10 text-emerald-100'
    }
    if (status === 'blocked') {
        return 'border-amber-300/30 bg-amber-300/10 text-amber-100'
    }
    if (status === 'disabled') {
        return 'border-slate-300/20 bg-white/5 text-hud-text-secondary'
    }
    return 'border-hud-border-secondary bg-hud-bg-primary/70 text-hud-text-secondary'
}

const statusLabel = (status: string) => {
    if (status === 'active') {
        return 'Active'
    }
    if (status === 'blocked') {
        return 'Blocked'
    }
    if (status === 'disabled') {
        return 'Disabled'
    }
    return status
}

const scheduleSummary = (schedules: SchedulingAdminScheduleItem[]) => {
    const active = schedules.filter((schedule) => schedule.status === 'active').length
    const blocked = schedules.filter((schedule) => schedule.status === 'blocked').length
    const disabled = schedules.filter((schedule) => schedule.status === 'disabled').length
    return { active, blocked, disabled }
}

const SchedulingAdminPage = () => {
    const { session } = useAuthSession()
    const [report, setReport] = useState<SchedulingAdminResponse | null>(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [runningId, setRunningId] = useState<string | null>(null)
    const [runResult, setRunResult] = useState<{ id: string; ok: boolean; message: string } | null>(null)
    const [tokenStatus, setTokenStatus] = useState<TidalWebTokenStatusResponse | null>(null)
    const [tokenInput, setTokenInput] = useState('')
    const [tokenSaving, setTokenSaving] = useState(false)
    const [tokenMsg, setTokenMsg] = useState<string | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL

    const load = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        setLoading(true)
        setError(null)
        try {
            const response = await fetchSchedulingAdminStatus(session.userId, signal)
            setReport(response)
        } catch (err) {
            if (signal?.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : '스케줄링 상태를 불러오지 못했습니다.')
        } finally {
            setLoading(false)
        }
    }, [isAdmin, session])

    const loadTokenStatus = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        try {
            setTokenStatus(await fetchTidalWebTokenStatus(signal))
        } catch {
            /* token status is best-effort */
        }
    }, [isAdmin, session])

    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal)
        void loadTokenStatus(controller.signal)
        return () => controller.abort()
    }, [load, loadTokenStatus])

    const handleSaveToken = useCallback(async () => {
        if (!session || !tokenInput.trim()) {
            return
        }
        setTokenSaving(true)
        setTokenMsg(null)
        try {
            const status = await setTidalWebToken(tokenInput.trim(), session.userId)
            setTokenStatus(status)
            setTokenInput('')
            setTokenMsg('TIDAL 토큰을 저장했습니다.')
        } catch (err) {
            setTokenMsg(err instanceof Error ? err.message : 'TIDAL 토큰 저장에 실패했습니다.')
        } finally {
            setTokenSaving(false)
        }
    }, [session, tokenInput])

    const handleRunDiscovery = useCallback(async () => {
        setRunningId('ems-public-discovery')
        setRunResult(null)
        try {
            const result = await runEmsDiscovery()
            setRunResult({
                id: 'ems-public-discovery',
                ok: !result.status.includes('failure'),
                message: `${result.status} · 플레이리스트 ${result.collected_playlist_count}개 · 트랙 ${result.collected_track_count}개`,
            })
            await load()
        } catch (err) {
            setRunResult({
                id: 'ems-public-discovery',
                ok: false,
                message: err instanceof Error ? err.message : '디스커버리 실행에 실패했습니다.',
            })
        } finally {
            setRunningId(null)
        }
    }, [load])

    const summary = useMemo(() => scheduleSummary(report?.schedules ?? []), [report])

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
                            <CalendarClock size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">스케줄 관리</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            스케줄링 서비스 관리
                        </h2>
                        <p className="mt-2 text-sm text-hud-text-secondary">
                            {report
                                ? `Active ${summary.active} · Blocked ${summary.blocked} · Disabled ${summary.disabled} · ${formatDateTime(report.generated_at)}`
                                : '주기 실행 상태를 준비 중입니다.'}
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

            {report?.recommendations && report.recommendations.length > 0 && (
                <section className="grid gap-3 lg:grid-cols-3">
                    {report.recommendations.map((recommendation) => (
                        <div
                            key={recommendation}
                            className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/75 p-4 text-sm leading-6 text-hud-text-secondary"
                        >
                            {recommendation}
                        </div>
                    ))}
                </section>
            )}

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <h2 className="text-lg font-semibold text-hud-text-primary">TIDAL 웹 토큰</h2>
                    <span className="text-xs text-hud-text-muted">
                        {tokenStatus?.configured
                            ? `설정됨 (${tokenStatus.masked_token ?? '****'})`
                            : '미설정 — 트랙 수집에 필요'}
                    </span>
                </div>
                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                    EMS 디스커버리가 TIDAL 플레이리스트의 트랙을 가져오려면 <code>x-tidal-token</code>이 필요합니다.
                    TIDAL 웹(로그인 상태) devtools의 요청 헤더에서 값을 복사해 붙여넣으세요. 플레이리스트 카드 수집에는 필요 없습니다.
                </p>
                <div className="mt-4 flex flex-col gap-3 sm:flex-row">
                    <input
                        type="text"
                        value={tokenInput}
                        onChange={(event) => setTokenInput(event.target.value)}
                        placeholder="x-tidal-token 값"
                        className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-2.5 font-mono text-sm text-hud-text-primary placeholder-hud-text-muted focus:border-hud-accent-primary focus:outline-none transition-hud"
                    />
                    <Button
                        type="button"
                        variant="primary"
                        glow
                        disabled={tokenSaving || !tokenInput.trim()}
                        onClick={() => void handleSaveToken()}
                    >
                        {tokenSaving ? '저장 중…' : '저장'}
                    </Button>
                </div>
                {tokenMsg && (
                    <p className="mt-3 text-xs text-hud-text-secondary">{tokenMsg}</p>
                )}
            </section>

            <section className="grid gap-4 xl:grid-cols-2">
                {(report?.schedules ?? []).map((schedule) => (
                    <article
                        key={schedule.id}
                        className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5"
                    >
                        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                            <div>
                                <div className="flex flex-wrap items-center gap-2">
                                    <span className="text-xs font-semibold uppercase tracking-[0.22em] text-hud-text-muted">
                                        {schedule.domain} · {schedule.mode}
                                    </span>
                                    <span className={`rounded-full border px-2.5 py-1 text-xs ${statusClass(schedule.status)}`}>
                                        {statusLabel(schedule.status)}
                                    </span>
                                </div>
                                <h3 className="mt-3 text-lg font-semibold text-hud-text-primary">
                                    {schedule.name}
                                </h3>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    {schedule.purpose}
                                </p>
                            </div>
                            <div className="flex shrink-0 flex-wrap items-center gap-2">
                                {schedule.id === 'ems-public-discovery' && (
                                    <Button
                                        type="button"
                                        variant="primary"
                                        glow
                                        disabled={runningId === schedule.id}
                                        onClick={() => void handleRunDiscovery()}
                                        leftIcon={
                                            runningId === schedule.id ? (
                                                <RefreshCw size={15} className="animate-spin" />
                                            ) : (
                                                <RefreshCw size={15} />
                                            )
                                        }
                                    >
                                        {runningId === schedule.id ? '실행 중…' : '지금 실행'}
                                    </Button>
                                )}
                                <Link
                                    to={schedule.management_path}
                                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-hud-accent-primary px-3 py-2 text-sm font-medium text-hud-accent-primary transition-hud hover:bg-hud-accent-primary/10"
                                >
                                    <ExternalLink size={15} />
                                    관리
                                </Link>
                            </div>
                        </div>

                        {runResult?.id === schedule.id && (
                            <div
                                className={`mt-4 rounded-xl border p-3 text-xs leading-5 ${
                                    runResult.ok
                                        ? 'border-hud-accent-primary/40 bg-hud-accent-primary/10 text-hud-text-secondary'
                                        : 'border-hud-accent-warning/40 bg-hud-accent-warning/10 text-hud-text-secondary'
                                }`}
                            >
                                {runResult.message}
                            </div>
                        )}

                        <div className="mt-5 grid gap-3 sm:grid-cols-3">
                            <Metric label="Cadence" value={schedule.cadence_label} />
                            <Metric label="Delay" value={formatDelay(schedule.fixed_delay_ms)} />
                            <Metric label="Initial" value={formatDelay(schedule.initial_delay_ms)} />
                        </div>

                        <div className="mt-5 grid gap-3 lg:grid-cols-2">
                            <Metric label="Last status" value={schedule.last_status ?? '-'} />
                            <Metric label="Last started" value={formatDateTime(schedule.last_started_at)} />
                        </div>

                        {schedule.last_message && (
                            <div className="mt-4 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3 text-xs leading-5 text-hud-text-secondary">
                                {schedule.last_message}
                            </div>
                        )}

                        {schedule.notes.length > 0 && (
                            <div className="mt-4 space-y-2">
                                {schedule.notes.map((note) => (
                                    <div key={note} className="flex items-start gap-2 text-xs leading-5 text-hud-text-muted">
                                        <TimerReset size={14} className="mt-0.5 shrink-0 text-hud-accent-primary" />
                                        <span>{note}</span>
                                    </div>
                                ))}
                            </div>
                        )}

                        <div className="mt-5 flex flex-wrap gap-2">
                            {schedule.config_keys.map((key) => (
                                <span
                                    key={key}
                                    className="rounded-full border border-hud-border-secondary bg-hud-bg-primary/70 px-2.5 py-1 font-mono text-[11px] text-hud-text-muted"
                                >
                                    {key}
                                </span>
                            ))}
                        </div>
                    </article>
                ))}
            </section>
        </main>
    )
}

const Metric = ({ label, value }: { label: string; value: string }) => (
    <div className="border-l border-hud-border-secondary pl-3">
        <p className="text-[11px] uppercase tracking-[0.2em] text-hud-text-muted">{label}</p>
        <p className="mt-1 truncate text-sm font-semibold text-hud-text-primary">{value}</p>
    </div>
)

export default SchedulingAdminPage
