import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Activity, AlertTriangle, DatabaseZap, Eraser, RefreshCw, RotateCcw, ShieldCheck, Trash2 } from 'lucide-react'
import Button from '@/components/common/Button'
import ConfirmDialog from '@/components/common/ConfirmDialog'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    cleanupEmsEmptyCollectedPlaylists,
    deleteEmsPoolAdminRun,
    fetchEmsPoolAdminRun,
    fetchEmsPoolAdminRuns,
    processEmsPoolAdminRun,
    retryEmsPoolAdminEntry,
} from '@/services/api'
import type { EmsPoolAdminEntryItem, EmsPoolAdminRunItem } from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

const statusTone: Record<string, string> = {
    queued: 'border-amber-300/40 bg-amber-300/10 text-amber-100',
    running: 'border-cyan-300/40 bg-cyan-300/10 text-cyan-100',
    completed: 'border-emerald-300/40 bg-emerald-300/10 text-emerald-100',
    completed_with_errors: 'border-orange-300/40 bg-orange-300/10 text-orange-100',
    failed: 'border-rose-300/40 bg-rose-300/10 text-rose-100',
}

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

const percent = (ratio: number) => `${Math.round(Math.max(0, Math.min(1, ratio)) * 100)}%`

const EmsPoolAdminPage = () => {
    const { session } = useAuthSession()
    const [runs, setRuns] = useState<EmsPoolAdminRunItem[]>([])
    const [selectedRunId, setSelectedRunId] = useState<number | null>(null)
    const [entries, setEntries] = useState<EmsPoolAdminEntryItem[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [processingRunId, setProcessingRunId] = useState<number | null>(null)
    const [retryingEntryId, setRetryingEntryId] = useState<number | null>(null)
    const [deletingRunId, setDeletingRunId] = useState<number | null>(null)
    const [deleteTarget, setDeleteTarget] = useState<{ runId: number; label: string } | null>(null)
    const [cleanupOpen, setCleanupOpen] = useState(false)
    const [cleaningUp, setCleaningUp] = useState(false)
    const [cleanupSummary, setCleanupSummary] = useState<string | null>(null)
    const runsAbortRef = useRef<AbortController | null>(null)
    const detailAbortRef = useRef<AbortController | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL
    const selectedRun = useMemo(
        () => runs.find((run) => run.run_id === selectedRunId) ?? null,
        [runs, selectedRunId],
    )

    const loadRuns = useCallback(async () => {
        if (!session || !isAdmin) {
            return
        }
        runsAbortRef.current?.abort()
        const controller = new AbortController()
        runsAbortRef.current = controller
        setLoading(true)
        setError(null)
        try {
            const response = await fetchEmsPoolAdminRuns(session.userId, controller.signal)
            if (controller.signal.aborted) {
                return
            }
            setRuns(response.runs)
            setSelectedRunId((current) => {
                if (current !== null && response.runs.some((run) => run.run_id === current)) {
                    return current
                }
                return response.runs[0]?.run_id ?? null
            })
        } catch (err) {
            if (controller.signal.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : 'EMS POOL 상태를 불러오지 못했습니다.')
        } finally {
            if (runsAbortRef.current === controller) {
                runsAbortRef.current = null
                setLoading(false)
            }
        }
    }, [isAdmin, session])

    const loadRunDetail = useCallback(async (runId: number) => {
        if (!session || !isAdmin) {
            return
        }
        detailAbortRef.current?.abort()
        const controller = new AbortController()
        detailAbortRef.current = controller
        try {
            const response = await fetchEmsPoolAdminRun(runId, session.userId, controller.signal)
            if (controller.signal.aborted) {
                return
            }
            setEntries(response.entries)
        } catch (err) {
            if (controller.signal.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : 'EMS POOL 상세 상태를 불러오지 못했습니다.')
        } finally {
            if (detailAbortRef.current === controller) {
                detailAbortRef.current = null
            }
        }
    }, [isAdmin, session])

    useEffect(() => {
        void loadRuns()
        return () => {
            runsAbortRef.current?.abort()
            runsAbortRef.current = null
        }
    }, [loadRuns])

    useEffect(() => {
        if (!selectedRunId) {
            detailAbortRef.current?.abort()
            detailAbortRef.current = null
            setEntries([])
            return
        }
        void loadRunDetail(selectedRunId)
        return () => {
            detailAbortRef.current?.abort()
            detailAbortRef.current = null
        }
    }, [loadRunDetail, selectedRunId])

    useEffect(() => {
        if (!session || !isAdmin) {
            return undefined
        }
        const tick = () => {
            if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
                return
            }
            void loadRuns()
            if (selectedRunId) {
                void loadRunDetail(selectedRunId)
            }
        }
        const intervalId = window.setInterval(tick, 5000)
        const onVisibility = () => {
            if (document.visibilityState === 'visible') {
                tick()
            }
        }
        document.addEventListener('visibilitychange', onVisibility)
        return () => {
            window.clearInterval(intervalId)
            document.removeEventListener('visibilitychange', onVisibility)
        }
    }, [isAdmin, loadRunDetail, loadRuns, selectedRunId, session])

    const handleProcess = async (runId: number) => {
        if (!session) {
            return
        }
        setProcessingRunId(runId)
        setError(null)
        try {
            await processEmsPoolAdminRun(runId, session.userId)
            await loadRuns()
            await loadRunDetail(runId)
        } catch (err) {
            setError(err instanceof Error ? err.message : 'EMS POOL 실행 요청이 실패했습니다.')
        } finally {
            setProcessingRunId(null)
        }
    }

    const requestDeleteRun = (runId: number, label: string) => {
        setDeleteTarget({ runId, label })
    }

    const handleConfirmCleanup = async () => {
        if (!session) {
            return
        }
        setCleaningUp(true)
        setError(null)
        try {
            const response = await cleanupEmsEmptyCollectedPlaylists(session.userId)
            setCleanupSummary(
                response.deleted_count > 0
                    ? `빈 EMS 플레이리스트 ${response.deleted_count}개를 삭제했습니다.`
                    : '삭제할 빈 EMS 플레이리스트가 없습니다.'
            )
            setCleanupOpen(false)
        } catch (err) {
            setError(err instanceof Error ? err.message : '빈 EMS 플레이리스트 정리에 실패했습니다.')
        } finally {
            setCleaningUp(false)
        }
    }

    const handleConfirmDelete = async () => {
        if (!session || !deleteTarget) {
            return
        }
        const { runId } = deleteTarget
        setDeletingRunId(runId)
        setError(null)
        try {
            await deleteEmsPoolAdminRun(runId, session.userId)
            setSelectedRunId((current) => (current === runId ? null : current))
            setDeleteTarget(null)
            await loadRuns()
        } catch (err) {
            setError(err instanceof Error ? err.message : 'EMS POOL 기록 삭제가 실패했습니다.')
        } finally {
            setDeletingRunId(null)
        }
    }

    const handleRetryEntry = async (entryId: number) => {
        if (!session || !selectedRunId) {
            return
        }
        setRetryingEntryId(entryId)
        setError(null)
        try {
            await retryEmsPoolAdminEntry(selectedRunId, entryId, session.userId)
            await loadRunDetail(selectedRunId)
            await loadRuns()
        } catch (err) {
            setError(err instanceof Error ? err.message : 'EMS POOL entry 재실행이 실패했습니다.')
        } finally {
            setRetryingEntryId(null)
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
                <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <DatabaseZap size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">EMS 큐 관리</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            검색 결과 적재 진행 상황
                        </h2>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                        <Button
                            type="button"
                            variant="outline"
                            onClick={() => {
                                setCleanupSummary(null)
                                setCleanupOpen(true)
                            }}
                            disabled={cleaningUp}
                        >
                            <Eraser size={16} />
                            빈 플레이리스트 정리
                        </Button>
                        <Button type="button" variant="outline" onClick={() => void loadRuns()} disabled={loading}>
                            <RefreshCw size={16} />
                            새로고침
                        </Button>
                    </div>
                </div>
                {cleanupSummary && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-hud-accent-primary/30 bg-hud-accent-primary/10 p-4 text-sm text-hud-text-primary">
                        <Eraser size={18} className="text-hud-accent-primary" />
                        <span>{cleanupSummary}</span>
                    </div>
                )}
                {error && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{error}</span>
                    </div>
                )}
            </section>

            <section className="grid gap-5 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-4">
                    <div className="mb-4 flex items-center gap-2 text-sm font-medium text-hud-text-primary">
                        <Activity size={18} />
                        최근 실행
                    </div>
                    <div className="space-y-3">
                        {runs.map((run) => (
                            <button
                                key={run.run_id}
                                type="button"
                                onClick={() => setSelectedRunId(run.run_id)}
                                className={`w-full rounded-xl border p-4 text-left transition-hud ${
                                    selectedRun?.run_id === run.run_id
                                        ? 'border-hud-border-primary bg-hud-accent-primary/10'
                                        : 'border-hud-border-secondary bg-hud-bg-primary/60 hover:border-hud-border-primary'
                                }`}
                            >
                                <div className="flex items-start justify-between gap-3">
                                    <div>
                                        <p className="text-sm font-semibold text-hud-text-primary">{run.search_query}</p>
                                        <p className="mt-1 text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                            {run.source_platform} / {run.collection_source}
                                        </p>
                                    </div>
                                    <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[run.status] ?? statusTone.queued}`}>
                                        {run.status}
                                    </span>
                                </div>
                                <div className="mt-4 h-2 overflow-hidden rounded-full bg-hud-bg-secondary">
                                    <div
                                        className="h-full rounded-full bg-hud-accent-primary"
                                        style={{ width: percent(run.progress_ratio) }}
                                    />
                                </div>
                                <div className="mt-3 grid grid-cols-3 gap-2 text-xs text-hud-text-secondary">
                                    <span>{percent(run.progress_ratio)}</span>
                                    <span>{run.collected_playlist_count} playlists</span>
                                    <span>{run.collected_track_count} tracks</span>
                                </div>
                            </button>
                        ))}
                        {!runs.length && (
                            <p className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm text-hud-text-muted">
                                아직 EMS POOL 실행 기록이 없습니다.
                            </p>
                        )}
                    </div>
                </div>

                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-4">
                    {selectedRun ? (
                        <>
                            <div className="flex flex-col gap-3 border-b border-hud-border-secondary pb-4 lg:flex-row lg:items-start lg:justify-between">
                                <div>
                                    <p className="text-xs uppercase tracking-[0.24em] text-hud-text-muted">
                                        Run #{selectedRun.run_id}
                                    </p>
                                    <h3 className="mt-2 text-xl font-semibold text-hud-text-primary">
                                        {selectedRun.search_query}
                                    </h3>
                                    <p className="mt-1 text-sm text-hud-text-secondary">
                                        {formatDateTime(selectedRun.created_at)} / {selectedRun.source_platform} / {selectedRun.collection_source}
                                    </p>
                                </div>
                                <div className="flex flex-wrap items-center gap-2">
                                    <Button
                                        type="button"
                                        variant="outline"
                                        disabled={processingRunId === selectedRun.run_id}
                                        onClick={() => void handleProcess(selectedRun.run_id)}
                                    >
                                        <RefreshCw size={16} />
                                        다시 실행
                                    </Button>
                                    <Button
                                        type="button"
                                        variant="outline"
                                        disabled={deletingRunId === selectedRun.run_id}
                                        onClick={() => requestDeleteRun(selectedRun.run_id, selectedRun.search_query)}
                                    >
                                        <Trash2 size={16} />
                                        삭제
                                    </Button>
                                </div>
                            </div>

                            <div className="mt-4 grid gap-3 sm:grid-cols-4">
                                <Stat label="POOL" value={`${selectedRun.total_playlist_entries + selectedRun.total_track_entries}`} />
                                <Stat label="EMS Playlist" value={`${selectedRun.collected_playlist_count}`} />
                                <Stat label="EMS Track" value={`${selectedRun.collected_track_count}`} />
                                <Stat label="Failed" value={`${selectedRun.failed_entries}`} />
                            </div>

                            <div className="mt-5 overflow-hidden rounded-xl border border-hud-border-secondary">
                                <table className="w-full min-w-[860px] text-left text-sm">
                                    <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                        <tr>
                                            <th className="px-4 py-3">Type</th>
                                            <th className="px-4 py-3">Title</th>
                                            <th className="px-4 py-3">Status</th>
                                            <th className="px-4 py-3">Attempts</th>
                                            <th className="px-4 py-3">Updated</th>
                                            <th className="px-4 py-3 text-right">Action</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-hud-border-secondary">
                                        {entries.map((entry) => (
                                            <tr key={entry.entry_id} className="bg-hud-bg-secondary/40 align-top">
                                                <td className="px-4 py-3 text-hud-text-secondary">{entry.entry_type}</td>
                                                <td className="px-4 py-3">
                                                    <p className="font-medium text-hud-text-primary">{entry.title}</p>
                                                    <p className="mt-1 text-xs text-hud-text-muted">
                                                        {entry.artist_name ?? entry.external_id}
                                                    </p>
                                                    {entry.last_error && (
                                                        <p
                                                            className="mt-2 text-xs text-rose-200/90"
                                                            title={entry.last_error}
                                                        >
                                                            {entry.last_error.length > 120
                                                                ? `${entry.last_error.slice(0, 120)}…`
                                                                : entry.last_error}
                                                        </p>
                                                    )}
                                                </td>
                                                <td className="px-4 py-3">
                                                    <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[entry.status] ?? statusTone.queued}`}>
                                                        {entry.status}
                                                    </span>
                                                </td>
                                                <td className="px-4 py-3 text-hud-text-secondary">{entry.attempts}</td>
                                                <td className="px-4 py-3 text-hud-text-secondary">{formatDateTime(entry.updated_at)}</td>
                                                <td className="px-4 py-3 text-right">
                                                    {entry.status === 'failed' && (
                                                        <Button
                                                            type="button"
                                                            variant="outline"
                                                            disabled={retryingEntryId === entry.entry_id}
                                                            onClick={() => void handleRetryEntry(entry.entry_id)}
                                                        >
                                                            <RotateCcw size={14} />
                                                            재시도
                                                        </Button>
                                                    )}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>
                    ) : (
                        <p className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm text-hud-text-muted">
                            실행 기록을 선택하면 세부 진행 상태가 표시됩니다.
                        </p>
                    )}
                </div>
            </section>

            <ConfirmDialog
                open={deleteTarget !== null}
                title="EMS POOL 기록 삭제"
                description={
                    deleteTarget
                        ? `Run #${deleteTarget.runId}${deleteTarget.label ? ` (${deleteTarget.label})` : ''} 기록을 삭제합니다.\n적재된 entry 기록도 함께 사라집니다.`
                        : undefined
                }
                confirmLabel="삭제"
                cancelLabel="취소"
                variant="danger"
                loading={deleteTarget !== null && deletingRunId === deleteTarget.runId}
                onConfirm={() => void handleConfirmDelete()}
                onCancel={() => {
                    if (deletingRunId === null) {
                        setDeleteTarget(null)
                    }
                }}
            />

            <ConfirmDialog
                open={cleanupOpen}
                title="빈 EMS 플레이리스트 정리"
                description={'트랙이 한 개도 없는 EMS 플레이리스트를 모두 삭제합니다.\n적재된 트랙이 있는 플레이리스트는 영향받지 않습니다.'}
                confirmLabel="정리"
                cancelLabel="취소"
                variant="danger"
                loading={cleaningUp}
                onConfirm={() => void handleConfirmCleanup()}
                onCancel={() => {
                    if (!cleaningUp) {
                        setCleanupOpen(false)
                    }
                }}
            />
        </main>
    )
}

const Stat = ({ label, value }: { label: string; value: string }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
        <p className="text-xs uppercase tracking-[0.18em] text-hud-text-muted">{label}</p>
        <p className="mt-2 text-2xl font-semibold text-hud-text-primary">{value}</p>
    </div>
)

export default EmsPoolAdminPage
