import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, BadgeCheck, BoxSelect, RefreshCw, RotateCcw, Search, ShieldCheck, Sparkles, Undo2 } from 'lucide-react'
import Button from '@/components/common/Button'
import ConfirmDialog from '@/components/common/ConfirmDialog'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    autoTrainSasrecForAdmin,
    disableSasrecModelForAdmin,
    fetchLatestSasrecModelForAdmin,
    fetchPersonalizationProfileForAdmin,
    fetchSasrecUserStatusForAdmin,
    promoteSasrecModelForAdmin,
    recomputePersonalizationProfileForAdmin,
    rollbackSasrecModelForAdmin,
} from '@/services/api'
import type {
    PersonalizationProfileItem,
    SasrecAutoTrainAdminResponse,
    SasrecRegistryAdminResponse,
    SasrecUserModelStatusResponse,
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

type PendingAction =
    | { kind: 'promote'; modelVersion: string }
    | { kind: 'disable'; modelVersion: string }
    | { kind: 'rollback' }
    | { kind: 'autoTrain'; targetUserId?: string }

const SasrecModelAdminPage = () => {
    const { session } = useAuthSession()
    const [registry, setRegistry] = useState<SasrecRegistryAdminResponse | null>(null)
    const [loading, setLoading] = useState(false)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [notice, setNotice] = useState<string | null>(null)
    const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
    const [versionInput, setVersionInput] = useState('')
    const [autoTrainResult, setAutoTrainResult] = useState<SasrecAutoTrainAdminResponse | null>(null)
    const [userLookupInput, setUserLookupInput] = useState('')
    const [userLookupResult, setUserLookupResult] = useState<SasrecUserModelStatusResponse | null>(null)
    const [userLookupLoading, setUserLookupLoading] = useState(false)
    const [userLookupError, setUserLookupError] = useState<string | null>(null)
    const [personalizationProfile, setPersonalizationProfile] = useState<PersonalizationProfileItem | null>(null)
    const [personalizationBusy, setPersonalizationBusy] = useState(false)
    const [personalizationError, setPersonalizationError] = useState<string | null>(null)
    const [personalizationNotice, setPersonalizationNotice] = useState<string | null>(null)

    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL

    const load = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        setLoading(true)
        setError(null)
        try {
            const response = await fetchLatestSasrecModelForAdmin(session.userId, signal)
            setRegistry(response)
        } catch (err) {
            if (signal?.aborted) {
                return
            }
            setError(err instanceof Error ? err.message : 'SASRec 모델 상태를 불러오지 못했습니다.')
        } finally {
            setLoading(false)
        }
    }, [isAdmin, session])

    useEffect(() => {
        const controller = new AbortController()
        void load(controller.signal)
        return () => controller.abort()
    }, [load])

    const runPending = async () => {
        if (!session || !pendingAction) {
            return
        }
        setBusy(true)
        setError(null)
        setNotice(null)
        try {
            if (pendingAction.kind === 'autoTrain') {
                const targetUserId = pendingAction.targetUserId?.trim()
                const result = await autoTrainSasrecForAdmin(session.userId, targetUserId)
                setAutoTrainResult(result)
                setNotice(result.summary)
                if (!targetUserId || targetUserId === session.userId) {
                    if (result.promote_result) {
                        setRegistry(result.promote_result)
                    } else {
                        await load()
                    }
                } else if (userLookupInput.trim() === targetUserId) {
                    const response = await fetchSasrecUserStatusForAdmin(session.userId, targetUserId)
                    setUserLookupResult(response)
                }
                setPendingAction(null)
                return
            }
            let response: SasrecRegistryAdminResponse
            if (pendingAction.kind === 'promote') {
                response = await promoteSasrecModelForAdmin(session.userId, pendingAction.modelVersion)
                setNotice(`Promoted ${response.model_version ?? pendingAction.modelVersion}.`)
            } else if (pendingAction.kind === 'disable') {
                response = await disableSasrecModelForAdmin(session.userId, pendingAction.modelVersion)
                setNotice(`Disabled ${pendingAction.modelVersion}. Active model is now ${response.model_version ?? 'none'}.`)
            } else {
                response = await rollbackSasrecModelForAdmin(session.userId)
                setNotice(`Rolled back. Active model is now ${response.model_version ?? 'none'}.`)
            }
            setRegistry(response)
            setPendingAction(null)
            setVersionInput('')
        } catch (err) {
            setError(err instanceof Error ? err.message : 'SASRec registry 액션이 실패했습니다.')
        } finally {
            setBusy(false)
        }
    }

    const requestPromote = () => {
        if (!versionInput.trim()) {
            setError('promote 할 model_version을 입력하세요.')
            return
        }
        setPendingAction({ kind: 'promote', modelVersion: versionInput.trim() })
    }

    const requestDisable = () => {
        if (!versionInput.trim()) {
            setError('disable 할 model_version을 입력하세요.')
            return
        }
        setPendingAction({ kind: 'disable', modelVersion: versionInput.trim() })
    }

    const requestRollback = () => {
        setPendingAction({ kind: 'rollback' })
    }

    const handleUserLookup = async () => {
        if (!session || !userLookupInput.trim()) {
            return
        }
        const targetUserId = userLookupInput.trim()
        setUserLookupLoading(true)
        setUserLookupError(null)
        setPersonalizationError(null)
        setPersonalizationNotice(null)
        try {
            const response = await fetchSasrecUserStatusForAdmin(session.userId, targetUserId)
            setUserLookupResult(response)
        } catch (err) {
            setUserLookupResult(null)
            setUserLookupError(err instanceof Error ? err.message : '사용자 모델 상태를 불러오지 못했습니다.')
        } finally {
            setUserLookupLoading(false)
        }
        try {
            const profileResp = await fetchPersonalizationProfileForAdmin(session.userId, targetUserId)
            setPersonalizationProfile(profileResp.profile)
        } catch {
            // Profile may not exist yet — silently leave it unset so the user can recompute.
            setPersonalizationProfile(null)
        }
    }

    const handleRecomputeProfile = async () => {
        if (!session || !userLookupInput.trim()) {
            return
        }
        const targetUserId = userLookupInput.trim()
        setPersonalizationBusy(true)
        setPersonalizationError(null)
        setPersonalizationNotice(null)
        try {
            const response = await recomputePersonalizationProfileForAdmin(session.userId, targetUserId)
            setPersonalizationProfile(response.profile)
            setPersonalizationNotice(
                `scanned ${response.events_scanned} events, ${response.signal_count} signals applied (limit ${response.event_limit}).`,
            )
        } catch (err) {
            setPersonalizationError(
                err instanceof Error ? err.message : '개인화 프로필을 재계산하지 못했습니다.',
            )
        } finally {
            setPersonalizationBusy(false)
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

    const dialogDescription = () => {
        if (!pendingAction) {
            return undefined
        }
        if (pendingAction.kind === 'promote') {
            return `${pendingAction.modelVersion}을 active model로 promote 합니다.\n기존 promoted version은 rollback history로 이동합니다.`
        }
        if (pendingAction.kind === 'disable') {
            return `${pendingAction.modelVersion}을 disable 합니다.\nactive 였다면 직전 history 항목으로 자동 교체됩니다.`
        }
        if (pendingAction.kind === 'autoTrain') {
            const target = pendingAction.targetUserId?.trim()
            return `${target ? target : '관리자 계정'} 기준 SASRec MVP 학습을 한 번 실행하고, qualification=true 이면 해당 사용자의 active model 로 promote 합니다.\n학습 시간이 30초~몇 분 소요될 수 있습니다.`
        }
        return 'rollback history의 가장 최근 항목을 active로 되돌립니다.\nhistory가 비어 있으면 실패합니다.'
    }

    const dialogTitle = () => {
        switch (pendingAction?.kind) {
            case 'promote': return 'SASRec 모델 promote'
            case 'disable': return 'SASRec 모델 disable'
            case 'rollback': return 'SASRec 모델 rollback'
            case 'autoTrain': return 'SASRec auto-train & promote'
            default: return ''
        }
    }

    const dialogConfirmLabel = () => {
        switch (pendingAction?.kind) {
            case 'promote': return 'Promote'
            case 'disable': return 'Disable'
            case 'rollback': return 'Rollback'
            case 'autoTrain': return 'Train'
            default: return '확인'
        }
    }

    const dialogVariant = (): 'primary' | 'danger' => {
        if (pendingAction?.kind === 'promote' || pendingAction?.kind === 'autoTrain') {
            return 'primary'
        }
        return 'danger'
    }

    return (
        <main className="space-y-6">
            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/85 p-6">
                <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                    <div>
                        <div className="flex items-center gap-3 text-hud-accent-primary">
                            <BadgeCheck size={24} />
                            <p className="text-xs font-semibold uppercase tracking-[0.26em]">SASRec 모델 관리</p>
                        </div>
                        <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                            SASRec MVP 모델 운영
                        </h2>
                        <p className="mt-2 text-sm text-hud-text-secondary">
                            현재 active 모델을 확인하고 promote/disable/rollback 정책을 적용합니다.
                        </p>
                    </div>
                    <Button type="button" variant="outline" onClick={() => void load()} disabled={loading}>
                        <RefreshCw size={16} />
                        새로고침
                    </Button>
                </div>
                {notice && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-hud-accent-primary/30 bg-hud-accent-primary/10 p-4 text-sm text-hud-text-primary">
                        <BadgeCheck size={18} className="text-hud-accent-primary" />
                        <span>{notice}</span>
                    </div>
                )}
                {error && (
                    <div className="mt-5 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{error}</span>
                    </div>
                )}
            </section>

            <section className="grid gap-5 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Active Model</p>
                    <p className="mt-3 break-all text-lg font-semibold text-hud-text-primary">
                        {registry?.model_version ?? '활성 SASRec 모델이 없습니다.'}
                    </p>
                    <dl className="mt-5 grid grid-cols-2 gap-3 text-sm">
                        <Field label="Status" value={registry?.status ?? '-'} />
                        <Field label="User" value={registry?.user_id ?? '-'} />
                        <Field label="Generated" value={formatDateTime(registry?.generated_at_ai ?? null)} />
                        <Field label="Vocabulary" value={registry?.vocabulary_size?.toString() ?? '-'} />
                        <Field label="Train Examples" value={registry?.train_example_count?.toString() ?? '-'} />
                        <Field label="Dataset Version" value={registry?.dataset_version ?? '-'} multiline />
                        <Field label="Dataset Fingerprint" value={registry?.dataset_fingerprint ?? '-'} multiline />
                        <Field label="Artifact Dir" value={registry?.artifact_dir ?? '-'} multiline />
                    </dl>
                    {registry?.warnings && registry.warnings.length > 0 && (
                        <div className="mt-5 space-y-2 rounded-xl border border-amber-300/30 bg-amber-300/10 p-4 text-xs text-amber-100">
                            {registry.warnings.map((warning, index) => (
                                <p key={`${warning}-${index}`}>{warning}</p>
                            ))}
                        </div>
                    )}
                </div>

                <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Registry 액션</p>
                    <label className="mt-4 block text-sm text-hud-text-secondary" htmlFor="sasrec-model-version">
                        Model version
                    </label>
                    <input
                        id="sasrec-model-version"
                        type="text"
                        value={versionInput}
                        onChange={(e) => setVersionInput(e.target.value)}
                        placeholder="sasrec-mvp-..."
                        className="mt-2 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                    />
                    <div className="mt-5 flex flex-wrap gap-2">
                        <Button type="button" variant="primary" onClick={requestPromote} disabled={busy}>
                            <BadgeCheck size={16} />
                            Promote
                        </Button>
                        <Button type="button" variant="outline" onClick={requestDisable} disabled={busy}>
                            <BoxSelect size={16} />
                            Disable
                        </Button>
                        <Button type="button" variant="outline" onClick={requestRollback} disabled={busy}>
                            <Undo2 size={16} />
                            Rollback
                        </Button>
                        <Button
                            type="button"
                            variant="outline"
                            onClick={() => setPendingAction({ kind: 'autoTrain' })}
                            disabled={busy}
                        >
                            <Sparkles size={16} />
                            Auto-Train
                        </Button>
                    </div>
                    <p className="mt-4 text-xs leading-6 text-hud-text-muted">
                        promote는 history에 직전 active를 push 합니다.<br />
                        disable은 disabled 목록에 추가하고 active 였다면 history에서 직전 항목으로 자동 교체합니다.<br />
                        rollback은 history pop으로 직전 active로 복원합니다.
                    </p>
                </div>
            </section>

            <ConfirmDialog
                open={pendingAction !== null}
                title={dialogTitle()}
                description={dialogDescription()}
                confirmLabel={dialogConfirmLabel()}
                cancelLabel="취소"
                variant={dialogVariant()}
                loading={busy}
                onConfirm={() => void runPending()}
                onCancel={() => {
                    if (!busy) {
                        setPendingAction(null)
                    }
                }}
            />

            {autoTrainResult && (
                <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                        Auto-Train 결과
                    </p>
                    <p className="mt-2 break-all text-base font-semibold text-hud-text-primary">
                        {autoTrainResult.summary}
                    </p>
                    <dl className="mt-4 grid grid-cols-2 gap-3 text-sm md:grid-cols-3">
                        <Field
                            label="User"
                            value={autoTrainResult.user_id}
                            multiline
                        />
                        <Field
                            label="Qualified"
                            value={autoTrainResult.qualified ? 'yes' : 'no'}
                        />
                        <Field
                            label="Promoted"
                            value={autoTrainResult.promoted ? 'yes' : 'no'}
                        />
                        <Field
                            label="Model"
                            value={autoTrainResult.model_version ?? '-'}
                            multiline
                        />
                        <Field
                            label="Dataset Version"
                            value={autoTrainResult.training.dataset_summary?.dataset_version ?? '-'}
                            multiline
                        />
                        <Field
                            label="Dataset Fingerprint"
                            value={autoTrainResult.training.dataset_summary?.dataset_fingerprint ?? '-'}
                            multiline
                        />
                        <Field
                            label="Sequence Items"
                            value={autoTrainResult.training.dataset_summary?.sequence_item_count == null
                                ? '-'
                                : String(autoTrainResult.training.dataset_summary.sequence_item_count)}
                        />
                    </dl>
                    <MetricComparisonTable
                        metrics={autoTrainResult.training.metrics}
                        baseline={autoTrainResult.training.baseline_metrics}
                        delta={autoTrainResult.training.metric_delta}
                    />
                    {autoTrainResult.training.qualification?.reason && (
                        <p className="mt-3 text-xs text-hud-text-secondary">
                            {autoTrainResult.training.qualification.reason}
                        </p>
                    )}
                    {autoTrainResult.training.warnings && autoTrainResult.training.warnings.length > 0 && (
                        <ul className="mt-3 space-y-1 rounded-xl border border-amber-300/30 bg-amber-300/10 p-3 text-xs text-amber-100">
                            {autoTrainResult.training.warnings.map((warning, idx) => (
                                <li key={`${warning}-${idx}`}>{warning}</li>
                            ))}
                        </ul>
                    )}
                </section>
            )}

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Other user lookup</p>
                <h3 className="mt-2 text-lg font-semibold text-hud-text-primary">사용자별 모델 단계 조회</h3>
                <p className="mt-2 text-xs leading-5 text-hud-text-muted">
                    cold-start: PMS 라이브러리가 비어 있는 상태 / baseline: import 완료 후 SASRec 미적용 /
                    personalized: SASRec promoted model 적용 중.
                </p>
                <div className="mt-4 flex flex-wrap items-center gap-2">
                    <input
                        type="text"
                        value={userLookupInput}
                        onChange={(e) => setUserLookupInput(e.target.value)}
                        placeholder="user-..."
                        className="flex-1 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                    />
                    <Button
                        type="button"
                        variant="primary"
                        onClick={() => void handleUserLookup()}
                        disabled={userLookupLoading || !userLookupInput.trim()}
                    >
                        <Search size={16} />
                        Lookup
                    </Button>
                    <Button
                        type="button"
                        variant="outline"
                        onClick={() => setPendingAction({ kind: 'autoTrain', targetUserId: userLookupInput.trim() })}
                        disabled={busy || !userLookupInput.trim()}
                    >
                        <Sparkles size={16} />
                        Train Target
                    </Button>
                </div>
                {userLookupError && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{userLookupError}</span>
                    </div>
                )}
                {userLookupResult && (
                    <div className="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                        <Field label="User" value={userLookupResult.user_id} multiline />
                        <Field
                            label="Stage"
                            value={userLookupResult.model_stage}
                        />
                        <Field label="PMS tracks" value={String(userLookupResult.pms_track_count)} />
                        <Field label="Total events" value={String(userLookupResult.total_event_count)} />
                        <Field
                            label="Active model"
                            value={userLookupResult.active_model_version ?? '-'}
                            multiline
                        />
                        <Field label="Active since" value={formatDateTime(userLookupResult.active_model_generated_at)} />
                        <Field
                            label="Last train"
                            value={
                                userLookupResult.latest_train_log
                                    ? `${formatDateTime(userLookupResult.latest_train_log.trained_at)} • qualified=${userLookupResult.latest_train_log.qualified ? 'Y' : 'N'} • promoted=${userLookupResult.latest_train_log.promoted ? 'Y' : 'N'}`
                                    : '-'
                            }
                            multiline
                        />
                        <Field
                            label="Events since train"
                            value={userLookupResult.events_since_last_train == null ? '-' : String(userLookupResult.events_since_last_train)}
                        />
                        <Field
                            label="Dataset Version"
                            value={userLookupResult.latest_train_log?.dataset_version ?? '-'}
                            multiline
                        />
                        <Field
                            label="Dataset Fingerprint"
                            value={userLookupResult.latest_train_log?.dataset_fingerprint ?? '-'}
                            multiline
                        />
                        <Field
                            label="Sequence Items"
                            value={userLookupResult.latest_train_log
                                ? String(userLookupResult.latest_train_log.sequence_item_count_at_train)
                                : '-'}
                        />
                        <Field
                            label="Snapshots"
                            value={userLookupResult.latest_train_log
                                ? String(userLookupResult.latest_train_log.recommendation_snapshot_count_at_train)
                                : '-'}
                        />
                    </div>
                )}
                {userLookupResult?.latest_train_log && (
                    <MetricComparisonTable
                        metrics={{
                            hit_rate_at_k: userLookupResult.latest_train_log.hit_rate_at_k ?? undefined,
                            mrr_at_k: userLookupResult.latest_train_log.mrr_at_k ?? undefined,
                            ndcg_at_k: userLookupResult.latest_train_log.ndcg_at_k ?? undefined,
                        }}
                        baseline={{
                            hit_rate_at_k: userLookupResult.latest_train_log.baseline_hit_rate_at_k ?? undefined,
                            mrr_at_k: userLookupResult.latest_train_log.baseline_mrr_at_k ?? undefined,
                            ndcg_at_k: userLookupResult.latest_train_log.baseline_ndcg_at_k ?? undefined,
                        }}
                        delta={{
                            hit_rate_at_k: userLookupResult.latest_train_log.hit_rate_delta ?? undefined,
                            mrr_at_k: userLookupResult.latest_train_log.mrr_delta ?? undefined,
                            ndcg_at_k: userLookupResult.latest_train_log.ndcg_delta ?? undefined,
                        }}
                    />
                )}
                {userLookupResult && (
                    <PersonalizationProfilePanel
                        profile={personalizationProfile}
                        busy={personalizationBusy}
                        error={personalizationError}
                        notice={personalizationNotice}
                        onRecompute={() => void handleRecomputeProfile()}
                    />
                )}
            </section>

            <p className="text-xs text-hud-text-muted">
                <RotateCcw size={12} className="mr-1 inline" />
                latest 응답은 promote된 model이 있으면 그것을, 없으면 disabled를 제외한 시간순 정렬을 사용합니다.
            </p>
        </main>
    )
}

const Field = ({ label, value, multiline }: { label: string; value: string; multiline?: boolean }) => (
    <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-3">
        <p className="text-[10px] uppercase tracking-[0.22em] text-hud-text-muted">{label}</p>
        <p className={`mt-1 text-sm text-hud-text-primary ${multiline ? 'break-all' : 'truncate'}`}>{value}</p>
    </div>
)

type MetricBundle = { hit_rate_at_k?: number; mrr_at_k?: number; ndcg_at_k?: number } | undefined | null

const formatMetric = (value: number | null | undefined) =>
    value == null || Number.isNaN(value) ? '-' : value.toFixed(4)

const formatDelta = (value: number | null | undefined) => {
    if (value == null || Number.isNaN(value)) {
        return '-'
    }
    const sign = value > 0 ? '+' : ''
    return `${sign}${value.toFixed(4)}`
}

const deltaClass = (value: number | null | undefined) => {
    if (value == null || value === 0 || Number.isNaN(value)) {
        return 'text-hud-text-secondary'
    }
    return value > 0 ? 'text-emerald-200' : 'text-rose-200'
}

const MetricComparisonTable = ({
    metrics,
    baseline,
    delta,
}: {
    metrics?: MetricBundle
    baseline?: MetricBundle
    delta?: MetricBundle
}) => {
    if (!metrics && !baseline && !delta) {
        return null
    }
    const rows: Array<{ key: 'hit_rate_at_k' | 'mrr_at_k' | 'ndcg_at_k'; label: string }> = [
        { key: 'hit_rate_at_k', label: 'Hit@K' },
        { key: 'mrr_at_k', label: 'MRR@K' },
        { key: 'ndcg_at_k', label: 'nDCG@K' },
    ]
    return (
        <div className="mt-4 overflow-hidden rounded-xl border border-hud-border-secondary">
            <table className="w-full text-left text-xs">
                <thead className="bg-hud-bg-primary/80 text-[10px] uppercase tracking-[0.22em] text-hud-text-muted">
                    <tr>
                        <th className="px-3 py-2">Metric</th>
                        <th className="px-3 py-2 text-right">SASRec</th>
                        <th className="px-3 py-2 text-right">Baseline (recency)</th>
                        <th className="px-3 py-2 text-right">Δ</th>
                    </tr>
                </thead>
                <tbody className="divide-y divide-hud-border-secondary">
                    {rows.map((row) => {
                        const sasrec = metrics ? metrics[row.key] : undefined
                        const base = baseline ? baseline[row.key] : undefined
                        const d = delta ? delta[row.key] : undefined
                        return (
                            <tr key={row.key} className="bg-hud-bg-secondary/40">
                                <td className="px-3 py-2 text-hud-text-primary">{row.label}</td>
                                <td className="px-3 py-2 text-right text-hud-text-primary">{formatMetric(sasrec)}</td>
                                <td className="px-3 py-2 text-right text-hud-text-secondary">{formatMetric(base)}</td>
                                <td className={`px-3 py-2 text-right ${deltaClass(d)}`}>{formatDelta(d)}</td>
                            </tr>
                        )
                    })}
                </tbody>
            </table>
        </div>
    )
}

const PersonalizationProfilePanel = ({
    profile,
    busy,
    error,
    notice,
    onRecompute,
}: {
    profile: PersonalizationProfileItem | null
    busy: boolean
    error: string | null
    notice: string | null
    onRecompute: () => void
}) => (
    <div className="mt-4 space-y-3 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-4">
        <div className="flex items-center justify-between gap-3">
            <div>
                <p className="text-[10px] uppercase tracking-[0.22em] text-hud-text-muted">
                    Personalization Profile (Phase 5)
                </p>
                <p className="mt-1 text-xs text-hud-text-secondary">
                    최근 user_music_event 가중치 집계로 top artist / source platform 신호를 만든다.
                    전체 모델 재학습 없이 다음 추천 batch에 반영되는 fast-path 신호.
                </p>
            </div>
            <Button type="button" variant="outline" onClick={onRecompute} disabled={busy}>
                <RefreshCw size={14} className={busy ? 'animate-spin' : ''} />
                재계산
            </Button>
        </div>
        {error && (
            <div className="rounded-lg border border-rose-300/30 bg-rose-500/10 p-3 text-xs text-rose-100">
                {error}
            </div>
        )}
        {notice && (
            <div className="rounded-lg border border-emerald-300/30 bg-emerald-500/10 p-3 text-xs text-emerald-100">
                {notice}
            </div>
        )}
        {!profile ? (
            <p className="text-xs text-hud-text-muted">
                이 사용자의 프로필이 아직 없습니다. "재계산"으로 첫 프로필을 생성하세요.
            </p>
        ) : (
            <div className="grid gap-3 md:grid-cols-2">
                <div>
                    <p className="text-[10px] uppercase tracking-[0.18em] text-hud-text-muted">Top artists</p>
                    {profile.top_artists.length === 0 ? (
                        <p className="mt-1 text-xs text-hud-text-secondary">신호 없음</p>
                    ) : (
                        <ul className="mt-2 space-y-1">
                            {profile.top_artists.map((artist) => (
                                <li
                                    key={artist.artist_name}
                                    className="flex items-center justify-between rounded-md bg-hud-bg-secondary/40 px-2 py-1 text-xs"
                                >
                                    <span className="truncate text-hud-text-primary">{artist.artist_name}</span>
                                    <span className="text-hud-text-secondary">
                                        {artist.score.toFixed(2)} <span className="text-hud-text-muted">({artist.signal_count})</span>
                                    </span>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
                <div>
                    <p className="text-[10px] uppercase tracking-[0.18em] text-hud-text-muted">Top source platforms</p>
                    {profile.top_source_platforms.length === 0 ? (
                        <p className="mt-1 text-xs text-hud-text-secondary">신호 없음</p>
                    ) : (
                        <ul className="mt-2 space-y-1">
                            {profile.top_source_platforms.map((platform) => (
                                <li
                                    key={platform.platform}
                                    className="flex items-center justify-between rounded-md bg-hud-bg-secondary/40 px-2 py-1 text-xs"
                                >
                                    <span className="truncate text-hud-text-primary">{platform.platform}</span>
                                    <span className="text-hud-text-secondary">
                                        {platform.score.toFixed(2)} <span className="text-hud-text-muted">({platform.signal_count})</span>
                                    </span>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>
                <div className="md:col-span-2 text-[11px] text-hud-text-muted">
                    Signals: {profile.event_count_at_update} · last event {profile.last_event_at ? formatDateTime(profile.last_event_at) : '-'} · recomputed {formatDateTime(profile.recomputed_at)}
                </div>
            </div>
        )}
    </div>
)

export default SasrecModelAdminPage
