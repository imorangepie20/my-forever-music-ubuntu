import { useCallback, useEffect, useMemo, useState } from 'react'
import { AlertTriangle, CheckCircle2, RefreshCw, ShieldCheck } from 'lucide-react'
import Button from '@/components/common/Button'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { fetchApplicationErrorLogs, resolveApplicationErrorLog } from '@/services/api'
import type { ApplicationErrorLogItem } from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

const severityClass = (severity: string) => {
    if (severity === 'error') return 'border-rose-300/40 bg-rose-500/10 text-rose-100'
    if (severity === 'warning') return 'border-amber-300/40 bg-amber-300/10 text-amber-100'
    return 'border-slate-300/30 bg-white/5 text-hud-text-secondary'
}

const formatDateTime = (value: string | null) => {
    if (!value) return '-'
    return new Intl.DateTimeFormat('ko-KR', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    }).format(new Date(value))
}

const ErrorLogsAdminPage = () => {
    const { session } = useAuthSession()
    const [entries, setEntries] = useState<ApplicationErrorLogItem[]>([])
    const [selectedId, setSelectedId] = useState<number | null>(null)
    const [severity, setSeverity] = useState('')
    const [source, setSource] = useState('')
    const [unresolvedOnly, setUnresolvedOnly] = useState(true)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [resolvingId, setResolvingId] = useState<number | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL
    const selectedEntry = useMemo(
        () => entries.find((entry) => entry.error_log_id === selectedId) ?? entries[0] ?? null,
        [entries, selectedId],
    )

    const load = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) return
        setLoading(true)
        setError(null)
        try {
            const response = await fetchApplicationErrorLogs(
                session.userId,
                {
                    severity: severity || undefined,
                    source: source || undefined,
                    unresolvedOnly,
                    limit: 100,
                },
                signal,
            )
            setEntries(response.entries)
            setSelectedId((current) => {
                if (current && response.entries.some((entry) => entry.error_log_id === current)) {
                    return current
                }
                return response.entries[0]?.error_log_id ?? null
            })
        } catch (err) {
            if (signal?.aborted) return
            setError(err instanceof Error ? err.message : '에러 로그를 불러오지 못했습니다.')
        } finally {
            setLoading(false)
        }
    }, [isAdmin, session, severity, source, unresolvedOnly])

    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal)
        return () => controller.abort()
    }, [load])

    const handleResolve = async (entry: ApplicationErrorLogItem) => {
        if (!session) return
        setResolvingId(entry.error_log_id)
        setError(null)
        try {
            await resolveApplicationErrorLog(session.userId, entry.error_log_id)
            await load()
        } catch (err) {
            setError(err instanceof Error ? err.message : '에러 로그 해결 처리에 실패했습니다.')
        } finally {
            setResolvingId(null)
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
                        주요 서비스 에러 로그는 관리자 계정에만 노출됩니다.
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
                            <AlertTriangle size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">에러 로그</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            주요 서비스 에러 관제
                        </h2>
                        <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                            API, EMS, PMS, GMS, playback, public curation, scheduler, web runtime 에러를 한 화면에서 확인합니다.
                        </p>
                    </div>
                    <Button type="button" variant="outline" onClick={() => void load()} disabled={loading}>
                        <RefreshCw size={16} className={loading ? 'animate-spin' : undefined} />
                        새로고침
                    </Button>
                </div>

                <div className="mt-5 grid gap-3 lg:grid-cols-[160px_180px_auto]">
                    <select
                        value={severity}
                        onChange={(event) => setSeverity(event.target.value)}
                        className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none"
                    >
                        <option value="">전체 심각도</option>
                        <option value="error">error</option>
                        <option value="warning">warning</option>
                        <option value="info">info</option>
                    </select>
                    <input
                        value={source}
                        onChange={(event) => setSource(event.target.value)}
                        placeholder="source 필터"
                        className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none placeholder:text-hud-text-muted"
                    />
                    <label className="inline-flex items-center gap-2 text-sm text-hud-text-secondary">
                        <input
                            type="checkbox"
                            checked={unresolvedOnly}
                            onChange={(event) => setUnresolvedOnly(event.target.checked)}
                        />
                        미해결만 보기
                    </label>
                </div>

                {error && (
                    <div className="mt-5 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        {error}
                    </div>
                )}
            </section>

            <section className="grid gap-4 xl:grid-cols-[minmax(320px,0.95fr)_minmax(0,1.05fr)]">
                <div className="space-y-3">
                    {entries.length === 0 ? (
                        <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-secondary/70 p-6 text-sm text-hud-text-secondary">
                            표시할 에러 로그가 없습니다.
                        </div>
                    ) : entries.map((entry) => (
                        <button
                            key={entry.error_log_id}
                            type="button"
                            onClick={() => setSelectedId(entry.error_log_id)}
                            className={`w-full rounded-2xl border p-4 text-left transition-hud ${
                                selectedEntry?.error_log_id === entry.error_log_id
                                    ? 'border-hud-accent-primary bg-hud-accent-primary/10'
                                    : 'border-hud-border-secondary bg-hud-bg-secondary/80 hover:border-hud-border-primary'
                            }`}
                        >
                            <div className="flex flex-wrap items-center gap-2">
                                <span className={`rounded-full border px-2.5 py-1 text-xs ${severityClass(entry.severity)}`}>
                                    {entry.severity}
                                </span>
                                <span className="rounded-full border border-hud-border-secondary px-2.5 py-1 text-xs text-hud-text-muted">
                                    {entry.source}
                                </span>
                                {entry.resolved_at && (
                                    <span className="rounded-full border border-emerald-300/30 px-2.5 py-1 text-xs text-emerald-100">
                                        resolved
                                    </span>
                                )}
                            </div>
                            <p className="mt-3 line-clamp-2 text-sm font-semibold text-hud-text-primary">
                                {entry.message}
                            </p>
                            <p className="mt-2 text-xs text-hud-text-muted">
                                {formatDateTime(entry.last_seen_at)} · {entry.request_path ?? entry.error_type ?? 'unknown'}
                            </p>
                        </button>
                    ))}
                </div>

                <article className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-5">
                    {selectedEntry ? (
                        <div className="space-y-5">
                            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                                <div>
                                    <h3 className="text-lg font-semibold text-hud-text-primary">
                                        #{selectedEntry.error_log_id} {selectedEntry.source}
                                    </h3>
                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                        {selectedEntry.message}
                                    </p>
                                </div>
                                {!selectedEntry.resolved_at && (
                                    <Button
                                        type="button"
                                        variant="outline"
                                        disabled={resolvingId === selectedEntry.error_log_id}
                                        onClick={() => void handleResolve(selectedEntry)}
                                    >
                                        <CheckCircle2 size={16} />
                                        해결 처리
                                    </Button>
                                )}
                            </div>

                            <div className="grid gap-3 sm:grid-cols-2">
                                <Detail label="status" value={selectedEntry.status_code?.toString() ?? '-'} />
                                <Detail label="type" value={selectedEntry.error_type ?? '-'} />
                                <Detail label="method" value={selectedEntry.request_method ?? '-'} />
                                <Detail label="path" value={selectedEntry.request_path ?? '-'} />
                                <Detail label="user" value={selectedEntry.user_id ?? '-'} />
                                <Detail label="last seen" value={formatDateTime(selectedEntry.last_seen_at)} />
                            </div>

                            {selectedEntry.stack_trace && (
                                <pre className="max-h-[520px] overflow-auto rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/80 p-4 text-xs leading-5 text-hud-text-secondary">
                                    {selectedEntry.stack_trace}
                                </pre>
                            )}
                        </div>
                    ) : (
                        <p className="text-sm text-hud-text-secondary">왼쪽에서 에러 로그를 선택하세요.</p>
                    )}
                </article>
            </section>
        </main>
    )
}

const Detail = ({ label, value }: { label: string; value: string }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-3">
        <p className="text-[11px] uppercase tracking-[0.2em] text-hud-text-muted">{label}</p>
        <p className="mt-1 break-words text-sm text-hud-text-primary">{value}</p>
    </div>
)

export default ErrorLogsAdminPage
