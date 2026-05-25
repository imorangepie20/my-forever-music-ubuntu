import { useCallback, useEffect, useMemo, useState } from 'react'
import { AlertTriangle, BadgeCheck, Check, Compass, History, Link2, RefreshCw, RotateCcw, Search, ShieldCheck, X } from 'lucide-react'
import Button from '@/components/common/Button'
import ConfirmDialog from '@/components/common/ConfirmDialog'
import OperatorDiagnosticsNotice from '@/components/common/OperatorDiagnosticsNotice'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import {
    acceptMetadataCandidateForAdmin,
    applyAcceptedIsrcCandidatesForAdmin,
    autoAcceptMetadataCandidatesForAdmin,
    fetchMetadataCandidateCanonicalLinkConflictsForAdmin,
    fetchMetadataCandidateAuditForAdmin,
    listMetadataCandidatesForAdmin,
    lookupDiscogsMastersForAdmin,
    lookupMusicBrainzRecordingsForAdmin,
    lookupWikidataEntitiesForAdmin,
    promoteMetadataCandidateToCanonicalForAdmin,
    rejectMetadataCandidateForAdmin,
    rollbackAppliedIsrcCandidateForAdmin,
} from '@/services/api'
import type {
    MetadataCandidateAuditResponse,
    MetadataCandidateCanonicalLinkConflictResponse,
    MetadataExternalLookupResponse,
    MetadataLookupResponse,
    TrackIdentityCandidateItem,
} from '@/types/api'

const ADMIN_EMAIL = 'jowoosungtidal@gmail.com'

type StatusFilter = 'pending' | 'accepted' | 'applied' | 'no_match' | 'conflict' | 'rolled_back' | 'review_required' | 'rejected' | 'all'

const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
    { value: 'pending', label: 'Pending' },
    { value: 'accepted', label: 'Accepted' },
    { value: 'applied', label: 'Applied' },
    { value: 'no_match', label: 'No match' },
    { value: 'conflict', label: 'Conflict' },
    { value: 'rolled_back', label: 'Rolled back' },
    { value: 'review_required', label: 'Review' },
    { value: 'rejected', label: 'Rejected' },
    { value: 'all', label: 'All' },
]

const statusTone: Record<string, string> = {
    pending: 'border-amber-300/40 bg-amber-300/10 text-amber-100',
    accepted: 'border-emerald-300/40 bg-emerald-300/10 text-emerald-100',
    applied: 'border-sky-300/40 bg-sky-300/10 text-sky-100',
    no_match: 'border-zinc-300/40 bg-zinc-300/10 text-zinc-100',
    conflict: 'border-orange-300/40 bg-orange-300/10 text-orange-100',
    rolled_back: 'border-cyan-300/40 bg-cyan-300/10 text-cyan-100',
    review_required: 'border-violet-300/40 bg-violet-300/10 text-violet-100',
    rejected: 'border-rose-300/40 bg-rose-300/10 text-rose-100',
}

const isCanonicalPromotableCandidate = (candidate: TrackIdentityCandidateItem) => {
    const kind = candidate.candidate_kind.toLowerCase()
    const supported = kind === 'isrc'
        || kind === 'mbid'
        || kind === 'musicbrainz_recording_id'
        || kind === 'wikidata_qid'
        || kind === 'discogs_master_id'
        || kind === 'discogs_release_id'
    if (!supported) {
        return false
    }
    if (kind === 'isrc') {
        return candidate.status === 'applied'
    }
    return candidate.status === 'accepted' || candidate.status === 'applied'
}

const isCanonicalConflictReviewCandidate = (candidate: TrackIdentityCandidateItem) =>
    candidate.status === 'applied' && candidate.candidate_kind.toLowerCase() === 'isrc'

const formatDateTime = (value: string | null) => {
    if (!value) {
        return '-'
    }
    return new Intl.DateTimeFormat('ko-KR', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    }).format(new Date(value))
}

type PendingResolution =
    | { kind: 'accept'; candidate: TrackIdentityCandidateItem }
    | { kind: 'reject'; candidate: TrackIdentityCandidateItem }
    | { kind: 'autoAccept'; minScore: number; limit: number }
    | { kind: 'applyAcceptedIsrc'; limit: number }
    | { kind: 'rollbackAppliedIsrc'; candidate: TrackIdentityCandidateItem }
    | { kind: 'promoteCanonical'; candidate: TrackIdentityCandidateItem }

const MetadataNormalizationAdminPage = () => {
    const { session } = useAuthSession()
    const isAdmin = session?.email.toLowerCase() === ADMIN_EMAIL

    const [title, setTitle] = useState('')
    const [artist, setArtist] = useState('')
    const [limit, setLimit] = useState(10)
    const [persist, setPersist] = useState(true)
    const [lookupResult, setLookupResult] = useState<MetadataLookupResponse | null>(null)
    const [externalLookupResult, setExternalLookupResult] = useState<MetadataExternalLookupResponse | null>(null)
    const [lookupLoading, setLookupLoading] = useState(false)
    const [lookupError, setLookupError] = useState<string | null>(null)

    const [statusFilter, setStatusFilter] = useState<StatusFilter>('pending')
    const [candidates, setCandidates] = useState<TrackIdentityCandidateItem[]>([])
    const [candidatesLoading, setCandidatesLoading] = useState(false)
    const [candidatesError, setCandidatesError] = useState<string | null>(null)
    const [pendingResolution, setPendingResolution] = useState<PendingResolution | null>(null)
    const [resolving, setResolving] = useState(false)
    const [resolveNotes, setResolveNotes] = useState('')
    const [autoAcceptMinScore, setAutoAcceptMinScore] = useState(0.95)
    const [autoAcceptSummary, setAutoAcceptSummary] = useState<string | null>(null)
    const [applyAcceptedSummary, setApplyAcceptedSummary] = useState<string | null>(null)
    const [rollbackSummary, setRollbackSummary] = useState<string | null>(null)
    const [canonicalPromotionSummary, setCanonicalPromotionSummary] = useState<string | null>(null)
    const [auditResult, setAuditResult] = useState<MetadataCandidateAuditResponse | null>(null)
    const [auditLoadingCandidateId, setAuditLoadingCandidateId] = useState<number | null>(null)
    const [auditError, setAuditError] = useState<string | null>(null)
    const [canonicalConflictResult, setCanonicalConflictResult] =
        useState<MetadataCandidateCanonicalLinkConflictResponse | null>(null)
    const [canonicalConflictLoadingCandidateId, setCanonicalConflictLoadingCandidateId] = useState<number | null>(null)
    const [canonicalConflictError, setCanonicalConflictError] = useState<string | null>(null)

    const loadCandidates = useCallback(async (signal?: AbortSignal) => {
        if (!session || !isAdmin) {
            return
        }
        setCandidatesLoading(true)
        setCandidatesError(null)
        try {
            const status = statusFilter === 'all' ? undefined : statusFilter
            const response = await listMetadataCandidatesForAdmin(session.userId, status, 50, signal)
            setCandidates(response.candidates)
        } catch (err) {
            if (signal?.aborted) {
                return
            }
            setCandidatesError(err instanceof Error ? err.message : '메타데이터 candidate 목록을 불러오지 못했습니다.')
        } finally {
            setCandidatesLoading(false)
        }
    }, [isAdmin, session, statusFilter])

    useEffect(() => {
        const controller = new AbortController()
        void loadCandidates(controller.signal)
        return () => controller.abort()
    }, [loadCandidates])

    const handleLookup = async () => {
        if (!session || !title.trim()) {
            return
        }
        setLookupLoading(true)
        setLookupError(null)
        try {
            const response = await lookupMusicBrainzRecordingsForAdmin(
                session.userId,
                title.trim(),
                artist.trim() || undefined,
                Math.max(1, Math.min(25, limit)),
                persist,
            )
            setLookupResult(response)
            setExternalLookupResult(null)
            if (persist) {
                await loadCandidates()
            }
        } catch (err) {
            setLookupError(err instanceof Error ? err.message : 'MusicBrainz lookup 이 실패했습니다.')
        } finally {
            setLookupLoading(false)
        }
    }

    const handleExternalLookup = async (source: 'wikidata' | 'discogs') => {
        if (!session || !title.trim()) {
            return
        }
        setLookupLoading(true)
        setLookupError(null)
        try {
            const response = source === 'wikidata'
                ? await lookupWikidataEntitiesForAdmin(
                    session.userId,
                    title.trim(),
                    artist.trim() || undefined,
                    Math.max(1, Math.min(25, limit)),
                    persist,
                )
                : await lookupDiscogsMastersForAdmin(
                    session.userId,
                    title.trim(),
                    artist.trim() || undefined,
                    Math.max(1, Math.min(25, limit)),
                    persist,
                )
            setExternalLookupResult(response)
            setLookupResult(null)
            if (persist) {
                await loadCandidates()
            }
        } catch (err) {
            setLookupError(err instanceof Error ? err.message : `${source} lookup 이 실패했습니다.`)
        } finally {
            setLookupLoading(false)
        }
    }

    const handleResolutionConfirm = async () => {
        if (!session || !pendingResolution) {
            return
        }
        setResolving(true)
        try {
            if (pendingResolution.kind === 'autoAccept') {
                const result = await autoAcceptMetadataCandidatesForAdmin(
                    session.userId,
                    pendingResolution.minScore,
                    pendingResolution.limit,
                )
                setAutoAcceptSummary(
                    `threshold ${result.threshold.toFixed(2)} — reviewed ${result.reviewed_count}, accepted ${result.accepted_count}, skipped ${result.skipped_count}.`,
                )
            } else if (pendingResolution.kind === 'applyAcceptedIsrc') {
                const result = await applyAcceptedIsrcCandidatesForAdmin(session.userId, pendingResolution.limit)
                setApplyAcceptedSummary(
                    `reviewed ${result.reviewed_count}, ISRC ${result.isrc_considered_count}, applied ${result.applied_count}, no match ${result.no_match_count}, conflict ${result.conflict_count}.`,
                )
            } else if (pendingResolution.kind === 'rollbackAppliedIsrc') {
                const result = await rollbackAppliedIsrcCandidateForAdmin(
                    session.userId,
                    pendingResolution.candidate.id,
                    resolveNotes.trim() || null,
                )
                setRollbackSummary(
                    `candidate #${result.candidate.id} -> ${result.candidate.status}; cleared ${result.cleared_track_ids.length}, skipped ${result.skipped_track_ids.length}.`,
                )
            } else if (pendingResolution.kind === 'promoteCanonical') {
                const result = await promoteMetadataCandidateToCanonicalForAdmin(
                    session.userId,
                    pendingResolution.candidate.id,
                )
                const releaseParts: string[] = []
                if (result.canonical_track.release_year) {
                    releaseParts.push(`year ${result.canonical_track.release_year}`)
                }
                if (result.canonical_track.release_country) {
                    releaseParts.push(`country ${result.canonical_track.release_country}`)
                }
                if (result.canonical_track.release_label) {
                    releaseParts.push(`label ${result.canonical_track.release_label}`)
                }
                const releaseSuffix = releaseParts.length > 0 ? ` (release: ${releaseParts.join(', ')})` : ''
                setCanonicalPromotionSummary(
                    `candidate #${result.candidate.id} -> canonical track #${result.canonical_track.canonical_track_id}${releaseSuffix}, identity #${result.identity.canonical_track_identity_id}; created=${result.created_identity ? 'yes' : 'already existed'}; linked EMS ${result.links.ems_linked_count}, PMS imported ${result.links.pms_imported_linked_count}, PMS user ${result.links.pms_user_linked_count}, conflicts ${result.links.total_conflict_count}.`,
                )
            } else {
                const candidateId = pendingResolution.candidate.id
                if (pendingResolution.kind === 'accept') {
                    await acceptMetadataCandidateForAdmin(session.userId, candidateId, resolveNotes.trim() || null)
                } else {
                    await rejectMetadataCandidateForAdmin(session.userId, candidateId, resolveNotes.trim() || null)
                }
            }
            setPendingResolution(null)
            setResolveNotes('')
            await loadCandidates()
        } catch (err) {
            setCandidatesError(err instanceof Error ? err.message : 'Candidate 처리에 실패했습니다.')
        } finally {
            setResolving(false)
        }
    }

    const handleAuditLoad = async (candidate: TrackIdentityCandidateItem) => {
        if (!session) {
            return
        }
        setAuditLoadingCandidateId(candidate.id)
        setAuditError(null)
        try {
            const result = await fetchMetadataCandidateAuditForAdmin(session.userId, candidate.id)
            setAuditResult(result)
        } catch (err) {
            setAuditError(err instanceof Error ? err.message : 'Audit 이력을 불러오지 못했습니다.')
        } finally {
            setAuditLoadingCandidateId(null)
        }
    }

    const handleCanonicalConflictLoad = async (candidate: TrackIdentityCandidateItem) => {
        if (!session) {
            return
        }
        setCanonicalConflictLoadingCandidateId(candidate.id)
        setCanonicalConflictError(null)
        try {
            const result = await fetchMetadataCandidateCanonicalLinkConflictsForAdmin(session.userId, candidate.id)
            setCanonicalConflictResult(result)
        } catch (err) {
            setCanonicalConflictError(err instanceof Error ? err.message : 'Canonical link conflict 를 불러오지 못했습니다.')
        } finally {
            setCanonicalConflictLoadingCandidateId(null)
        }
    }

    const dialogTitle = useMemo(() => {
        if (!pendingResolution) {
            return ''
        }
        if (pendingResolution.kind === 'autoAccept') {
            return `Auto-accept pending candidates (score >= ${pendingResolution.minScore.toFixed(2)})`
        }
        if (pendingResolution.kind === 'applyAcceptedIsrc') {
            return 'Apply accepted ISRC candidates'
        }
        if (pendingResolution.kind === 'rollbackAppliedIsrc') {
            return `Rollback ISRC candidate #${pendingResolution.candidate.id}`
        }
        if (pendingResolution.kind === 'promoteCanonical') {
            return `Promote candidate #${pendingResolution.candidate.id}`
        }
        return pendingResolution.kind === 'accept'
            ? `Accept candidate #${pendingResolution.candidate.id}`
            : `Reject candidate #${pendingResolution.candidate.id}`
    }, [pendingResolution])

    const dialogDescription = useMemo(() => {
        if (!pendingResolution) {
            return undefined
        }
        if (pendingResolution.kind === 'autoAccept') {
            return `최근 pending candidate 최대 ${pendingResolution.limit}건 중\ncandidate_score >= ${pendingResolution.minScore.toFixed(2)} 인 후보를 한꺼번에 accept 합니다.`
        }
        if (pendingResolution.kind === 'applyAcceptedIsrc') {
            return `최근 accepted ISRC candidate 최대 ${pendingResolution.limit}건을 query title/artist 기준 EMS track 에 적용합니다.\n기존 ISRC 가 다르면 conflict 로 남기고 덮어쓰지 않습니다.`
        }
        if (pendingResolution.kind === 'rollbackAppliedIsrc') {
            return `${pendingResolution.candidate.candidate_value} 를 적용 당시 기록된 EMS track id 에서만 제거합니다.\n현재 ISRC 가 달라졌거나 적용 track id 를 찾을 수 없으면 review_required 로 남깁니다.`
        }
        if (pendingResolution.kind === 'promoteCanonical') {
            return `${pendingResolution.candidate.source}/${pendingResolution.candidate.candidate_kind} = ${pendingResolution.candidate.candidate_value}\n이 candidate 를 canonical_track_identity 에 upsert 합니다. Candidate 상태는 바꾸지 않고 audit 에 승격 이력만 남깁니다.`
        }
        return `${pendingResolution.candidate.source}/${pendingResolution.candidate.candidate_kind} = ${pendingResolution.candidate.candidate_value}\nquery: ${pendingResolution.candidate.query_title}${pendingResolution.candidate.query_artist ? ` / ${pendingResolution.candidate.query_artist}` : ''}`
    }, [pendingResolution])

    const dialogConfirmLabel = useMemo(() => {
        if (!pendingResolution) {
            return ''
        }
        if (pendingResolution.kind === 'autoAccept') return 'Auto-accept'
        if (pendingResolution.kind === 'applyAcceptedIsrc') return 'Apply ISRCs'
        if (pendingResolution.kind === 'rollbackAppliedIsrc') return 'Rollback'
        if (pendingResolution.kind === 'promoteCanonical') return 'Promote'
        return pendingResolution.kind === 'accept' ? 'Accept' : 'Reject'
    }, [pendingResolution])

    const dialogVariant = useMemo((): 'primary' | 'danger' => {
        if (!pendingResolution) return 'primary'
        return pendingResolution.kind === 'reject' || pendingResolution.kind === 'rollbackAppliedIsrc' ? 'danger' : 'primary'
    }, [pendingResolution])

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
                <div className="flex items-center gap-3 text-hud-accent-primary">
                    <Compass size={24} />
                    <p className="text-xs font-semibold uppercase tracking-[0.26em]">메타데이터 정규화</p>
                </div>
                <h2 className="mt-3 text-2xl font-semibold text-hud-text-primary">
                    메타데이터 후보 조회와 검토
                </h2>
                <p className="mt-2 text-sm text-hud-text-secondary">
                    title + artist 로 MusicBrainz, Wikidata, Discogs 후보를 조회하고, persist 옵션이 켜져 있으면 각 후보를
                    `track_identity_candidate` 에 저장합니다. 운영자는 아래 후보 목록에서 승인/거절합니다.
                </p>
            </section>

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                <div className="grid gap-3 lg:grid-cols-[2fr_2fr_120px_140px_auto_auto_auto]">
                    <input
                        type="text"
                        value={title}
                        onChange={(e) => setTitle(e.target.value)}
                        placeholder="Track title"
                        className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                    />
                    <input
                        type="text"
                        value={artist}
                        onChange={(e) => setArtist(e.target.value)}
                        placeholder="Artist (optional)"
                        className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                    />
                    <input
                        type="number"
                        value={limit}
                        min={1}
                        max={25}
                        onChange={(e) => setLimit(Number(e.target.value) || 10)}
                        className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary focus:border-hud-border-primary focus:outline-none"
                    />
                    <label className="flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-2 text-sm text-hud-text-primary">
                        <input
                            type="checkbox"
                            checked={persist}
                            onChange={(e) => setPersist(e.target.checked)}
                        />
                        Persist
                    </label>
                    <Button
                        type="button"
                        variant="primary"
                        onClick={() => void handleLookup()}
                        disabled={lookupLoading || !title.trim()}
                    >
                        <Search size={16} />
                        MusicBrainz
                    </Button>
                    <Button
                        type="button"
                        variant="outline"
                        onClick={() => void handleExternalLookup('wikidata')}
                        disabled={lookupLoading || !title.trim()}
                    >
                        <Search size={16} />
                        Wikidata
                    </Button>
                    <Button
                        type="button"
                        variant="outline"
                        onClick={() => void handleExternalLookup('discogs')}
                        disabled={lookupLoading || !title.trim()}
                    >
                        <Search size={16} />
                        Discogs
                    </Button>
                </div>
                {lookupError && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{lookupError}</span>
                    </div>
                )}
                {lookupResult && (
                    <div className="mt-5 overflow-hidden rounded-xl border border-hud-border-secondary">
                        <table className="w-full min-w-[820px] text-left text-sm">
                            <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                <tr>
                                    <th className="px-4 py-3">MBID</th>
                                    <th className="px-4 py-3">Title</th>
                                    <th className="px-4 py-3">Artist</th>
                                    <th className="px-4 py-3">Score</th>
                                    <th className="px-4 py-3">ISRCs</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-hud-border-secondary">
                                {lookupResult.candidates.map((candidate, idx) => (
                                    <tr key={`${candidate.mbid}-${idx}`} className="bg-hud-bg-secondary/40">
                                        <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">{candidate.mbid ?? '-'}</td>
                                        <td className="px-4 py-3 text-hud-text-primary">{candidate.title ?? '-'}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{candidate.artist_name ?? '-'}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{candidate.score ?? '-'}</td>
                                        <td className="px-4 py-3 text-xs text-hud-text-secondary">
                                            {candidate.isrcs.length === 0 ? '-' : candidate.isrcs.join(', ')}
                                        </td>
                                    </tr>
                                ))}
                                {lookupResult.candidates.length === 0 && (
                                    <tr>
                                        <td colSpan={5} className="px-4 py-6 text-center text-sm text-hud-text-muted">
                                            검색 결과가 없습니다.
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>
                )}
                {externalLookupResult && (
                    <div className="mt-5 overflow-hidden rounded-xl border border-hud-border-secondary">
                        <div className="flex items-center justify-between border-b border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-3">
                            <span className="text-sm font-semibold text-hud-text-primary">
                                {externalLookupResult.source} candidates
                            </span>
                            <span className="text-xs text-hud-text-muted">
                                saved {externalLookupResult.saved_candidates.length} / total {externalLookupResult.total_count}
                            </span>
                        </div>
                        <table className="w-full min-w-[860px] text-left text-sm">
                            <thead className="bg-hud-bg-primary/60 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                <tr>
                                    <th className="px-4 py-3">Kind</th>
                                    <th className="px-4 py-3">Value</th>
                                    <th className="px-4 py-3">Label</th>
                                    <th className="px-4 py-3">Description</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-hud-border-secondary">
                                {externalLookupResult.candidates.map((candidate, idx) => (
                                    <tr key={`${candidate.source}-${candidate.candidate_value}-${idx}`} className="bg-hud-bg-secondary/40">
                                        <td className="px-4 py-3 text-hud-text-secondary">{candidate.candidate_kind}</td>
                                        <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">{candidate.candidate_value}</td>
                                        <td className="px-4 py-3 text-hud-text-primary">{candidate.label ?? '-'}</td>
                                        <td className="px-4 py-3 text-hud-text-secondary">{candidate.description ?? '-'}</td>
                                    </tr>
                                ))}
                                {externalLookupResult.candidates.length === 0 && (
                                    <tr>
                                        <td colSpan={4} className="px-4 py-6 text-center text-sm text-hud-text-muted">
                                            검색 결과가 없습니다.
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>
                )}
            </section>

            <section className="rounded-2xl border border-hud-border-secondary bg-hud-bg-secondary/80 p-6">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                    <div>
                        <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">Candidates</p>
                        <h3 className="mt-2 text-lg font-semibold text-hud-text-primary">저장된 candidate</h3>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                        {STATUS_FILTERS.map((f) => (
                            <button
                                key={f.value}
                                type="button"
                                onClick={() => setStatusFilter(f.value)}
                                className={`rounded-full border px-3 py-1.5 text-xs uppercase tracking-[0.18em] transition-hud ${
                                    statusFilter === f.value
                                        ? 'border-hud-border-primary bg-hud-accent-primary/10 text-hud-accent-primary'
                                        : 'border-hud-border-secondary bg-hud-bg-primary/60 text-hud-text-secondary hover:border-hud-border-primary'
                                }`}
                            >
                                {f.label}
                            </button>
                        ))}
                        <div className="flex items-center gap-2 rounded-full border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-1.5">
                            <label className="text-[11px] uppercase tracking-[0.18em] text-hud-text-muted" htmlFor="auto-accept-threshold">
                                min
                            </label>
                            <input
                                id="auto-accept-threshold"
                                type="number"
                                min={0}
                                max={1}
                                step={0.05}
                                value={autoAcceptMinScore}
                                onChange={(e) => setAutoAcceptMinScore(Number(e.target.value) || 0)}
                                className="w-16 bg-transparent text-sm text-hud-text-primary focus:outline-none"
                            />
                        </div>
                        <Button
                            type="button"
                            variant="primary"
                            onClick={() => setPendingResolution({ kind: 'autoAccept', minScore: autoAcceptMinScore, limit: 100 })}
                            disabled={resolving}
                        >
                            <BadgeCheck size={16} />
                            Auto-accept
                        </Button>
                        <Button
                            type="button"
                            variant="outline"
                            onClick={() => setPendingResolution({ kind: 'applyAcceptedIsrc', limit: 100 })}
                            disabled={resolving}
                        >
                            <Check size={16} />
                            Apply ISRCs
                        </Button>
                        <Button type="button" variant="outline" onClick={() => void loadCandidates()} disabled={candidatesLoading}>
                            <RefreshCw size={16} />
                            새로고침
                        </Button>
                    </div>
                </div>
                {autoAcceptSummary && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-hud-accent-primary/30 bg-hud-accent-primary/10 p-4 text-sm text-hud-text-primary">
                        <BadgeCheck size={18} className="text-hud-accent-primary" />
                        <span>{autoAcceptSummary}</span>
                    </div>
                )}
                {applyAcceptedSummary && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-sky-300/30 bg-sky-500/10 p-4 text-sm text-sky-100">
                        <Check size={18} />
                        <span>{applyAcceptedSummary}</span>
                    </div>
                )}
                {rollbackSummary && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-cyan-300/30 bg-cyan-500/10 p-4 text-sm text-cyan-100">
                        <RotateCcw size={18} />
                        <span>{rollbackSummary}</span>
                    </div>
                )}
                {canonicalPromotionSummary && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-hud-accent-primary/30 bg-hud-accent-primary/10 p-4 text-sm text-hud-text-primary">
                        <Link2 size={18} className="text-hud-accent-primary" />
                        <span>{canonicalPromotionSummary}</span>
                    </div>
                )}
                {candidatesError && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{candidatesError}</span>
                    </div>
                )}
                <div className="mt-4 overflow-hidden rounded-xl border border-hud-border-secondary">
                    <table className="w-full min-w-[940px] text-left text-sm">
                        <thead className="bg-hud-bg-primary/80 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                            <tr>
                                <th className="px-4 py-3">#</th>
                                <th className="px-4 py-3">Query</th>
                                <th className="px-4 py-3">Source</th>
                                <th className="px-4 py-3">Kind</th>
                                <th className="px-4 py-3">Value</th>
                                <th className="px-4 py-3">Score</th>
                                <th className="px-4 py-3">Status</th>
                                <th className="px-4 py-3">Created</th>
                                <th className="px-4 py-3 text-right">Action</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-hud-border-secondary">
                            {candidates.map((candidate) => (
                                <tr key={candidate.id} className="bg-hud-bg-secondary/40 align-top">
                                    <td className="px-4 py-3 text-hud-text-secondary">{candidate.id}</td>
                                    <td className="px-4 py-3">
                                        <p className="text-hud-text-primary">{candidate.query_title}</p>
                                        {candidate.query_artist && (
                                            <p className="mt-1 text-xs text-hud-text-muted">{candidate.query_artist}</p>
                                        )}
                                    </td>
                                    <td className="px-4 py-3 text-hud-text-secondary">{candidate.source}</td>
                                    <td className="px-4 py-3 text-hud-text-secondary">{candidate.candidate_kind}</td>
                                    <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">{candidate.candidate_value}</td>
                                    <td className="px-4 py-3 text-hud-text-secondary">
                                        {candidate.candidate_score == null ? '-' : candidate.candidate_score.toFixed(2)}
                                    </td>
                                    <td className="px-4 py-3">
                                        <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[candidate.status] ?? statusTone.pending}`}>
                                            {candidate.status}
                                        </span>
                                    </td>
                                    <td className="px-4 py-3 text-xs text-hud-text-muted">{formatDateTime(candidate.created_at)}</td>
                                    <td className="px-4 py-3 text-right">
                                        <div className="flex flex-wrap justify-end gap-2">
                                            <Button
                                                type="button"
                                                variant="ghost"
                                                onClick={() => void handleAuditLoad(candidate)}
                                                disabled={auditLoadingCandidateId === candidate.id}
                                            >
                                                <History size={14} />
                                                Audit
                                            </Button>
                                            {(candidate.status === 'pending' || candidate.status === 'applied') && (
                                                <>
                                                    {isCanonicalPromotableCandidate(candidate) && (
                                                        <Button
                                                            type="button"
                                                            variant="outline"
                                                            onClick={() => {
                                                                setResolveNotes('')
                                                                setPendingResolution({ kind: 'promoteCanonical', candidate })
                                                            }}
                                                        >
                                                            <Link2 size={14} />
                                                            Promote
                                                        </Button>
                                                    )}
                                                    {isCanonicalConflictReviewCandidate(candidate) && (
                                                        <Button
                                                            type="button"
                                                            variant="ghost"
                                                            onClick={() => void handleCanonicalConflictLoad(candidate)}
                                                            disabled={canonicalConflictLoadingCandidateId === candidate.id}
                                                        >
                                                            <AlertTriangle size={14} />
                                                            Conflicts
                                                        </Button>
                                                    )}
                                                    {candidate.status === 'applied' && candidate.candidate_kind === 'isrc' && (
                                                        <Button
                                                            type="button"
                                                            variant="danger"
                                                            onClick={() => {
                                                                setResolveNotes('')
                                                                setPendingResolution({ kind: 'rollbackAppliedIsrc', candidate })
                                                            }}
                                                        >
                                                            <RotateCcw size={14} />
                                                            Rollback
                                                        </Button>
                                                    )}
                                                    {candidate.status === 'pending' && (
                                                        <>
                                                            <Button
                                                                type="button"
                                                                variant="outline"
                                                                onClick={() => {
                                                                    setResolveNotes('')
                                                                    setPendingResolution({ kind: 'accept', candidate })
                                                                }}
                                                            >
                                                                <Check size={14} />
                                                                Accept
                                                            </Button>
                                                            <Button
                                                                type="button"
                                                                variant="outline"
                                                                onClick={() => {
                                                                    setResolveNotes('')
                                                                    setPendingResolution({ kind: 'reject', candidate })
                                                                }}
                                                            >
                                                                <X size={14} />
                                                                Reject
                                                            </Button>
                                                        </>
                                                    )}
                                                </>
                                            )}
                                        </div>
                                    </td>
                                </tr>
                            ))}
                            {candidates.length === 0 && !candidatesLoading && (
                                <tr>
                                    <td colSpan={9} className="px-4 py-8 text-center text-sm text-hud-text-muted">
                                        candidate 가 없습니다. 위의 lookup 으로 추가하세요.
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
                {auditError && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{auditError}</span>
                    </div>
                )}
                {canonicalConflictError && (
                    <div className="mt-4 flex items-start gap-3 rounded-xl border border-rose-300/30 bg-rose-500/10 p-4 text-sm text-rose-100">
                        <AlertTriangle size={18} />
                        <span>{canonicalConflictError}</span>
                    </div>
                )}
                {canonicalConflictResult && (
                    <div className="mt-5 overflow-hidden rounded-xl border border-hud-border-secondary">
                        <div className="flex flex-col gap-1 border-b border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                            <div className="flex items-center gap-2 text-hud-text-primary">
                                <AlertTriangle size={17} />
                                <span className="text-sm font-semibold">
                                    Candidate #{canonicalConflictResult.candidate.id} canonical conflicts
                                </span>
                            </div>
                            <span className="font-mono text-xs text-hud-text-muted">
                                target canonical #{canonicalConflictResult.target_identity.canonical_track_id}
                            </span>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-[980px] text-left text-sm">
                                <thead className="bg-hud-bg-primary/60 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                    <tr>
                                        <th className="px-4 py-3">Store</th>
                                        <th className="px-4 py-3">Row</th>
                                        <th className="px-4 py-3">Track</th>
                                        <th className="px-4 py-3">Platform</th>
                                        <th className="px-4 py-3">ISRC</th>
                                        <th className="px-4 py-3">Existing canonical</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-hud-border-secondary">
                                    {canonicalConflictResult.rows.map((row) => (
                                        <tr key={`${row.track_store}-${row.row_id}`} className="bg-hud-bg-secondary/30 align-top">
                                            <td className="px-4 py-3 text-hud-text-primary">{row.track_store}</td>
                                            <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">{row.row_id}</td>
                                            <td className="px-4 py-3">
                                                <p className="text-hud-text-primary">{row.title}</p>
                                                <p className="mt-1 text-xs text-hud-text-muted">{row.artist_name}</p>
                                            </td>
                                            <td className="px-4 py-3 text-hud-text-secondary">
                                                {row.source_platform}
                                                <p className="mt-1 font-mono text-xs text-hud-text-muted">{row.external_track_id}</p>
                                            </td>
                                            <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">{row.isrc ?? '-'}</td>
                                            <td className="px-4 py-3 font-mono text-xs text-orange-100">
                                                {row.existing_canonical_track_id}
                                            </td>
                                        </tr>
                                    ))}
                                    {canonicalConflictResult.rows.length === 0 && (
                                        <tr>
                                            <td colSpan={6} className="px-4 py-6 text-center text-sm text-hud-text-muted">
                                                canonical link conflict 가 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}
                {auditResult && (
                    <div className="mt-5 overflow-hidden rounded-xl border border-hud-border-secondary">
                        <div className="flex flex-col gap-1 border-b border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                            <div className="flex items-center gap-2 text-hud-text-primary">
                                <History size={17} />
                                <span className="text-sm font-semibold">Candidate #{auditResult.candidate.id} audit</span>
                            </div>
                            <span className="font-mono text-xs text-hud-text-muted">
                                {auditResult.candidate.candidate_kind}:{auditResult.candidate.candidate_value}
                            </span>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-[940px] text-left text-sm">
                                <thead className="bg-hud-bg-primary/60 text-xs uppercase tracking-[0.18em] text-hud-text-muted">
                                    <tr>
                                        <th className="px-4 py-3">Action</th>
                                        <th className="px-4 py-3">Track</th>
                                        <th className="px-4 py-3">Value</th>
                                        <th className="px-4 py-3">Status</th>
                                        <th className="px-4 py-3">Message</th>
                                        <th className="px-4 py-3">Acted</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-hud-border-secondary">
                                    {auditResult.entries.map((entry) => (
                                        <tr key={entry.id} className="bg-hud-bg-secondary/30 align-top">
                                            <td className="px-4 py-3 text-hud-text-primary">{entry.action}</td>
                                            <td className="px-4 py-3 text-hud-text-secondary">
                                                {entry.ems_collected_track_id == null ? '-' : entry.ems_collected_track_id}
                                            </td>
                                            <td className="px-4 py-3 font-mono text-xs text-hud-text-secondary">
                                                {entry.previous_isrc || entry.new_isrc
                                                    ? (entry.previous_isrc ?? '-') + ' -> ' + (entry.new_isrc ?? '-')
                                                    : entry.candidate_value ?? '-'}
                                            </td>
                                            <td className="px-4 py-3">
                                                <span className={`rounded-full border px-2.5 py-1 text-[11px] ${statusTone[entry.status] ?? statusTone.pending}`}>
                                                    {entry.status}
                                                </span>
                                            </td>
                                            <td className="px-4 py-3 text-hud-text-secondary">{entry.message ?? '-'}</td>
                                            <td className="px-4 py-3 text-xs text-hud-text-muted">{formatDateTime(entry.acted_at)}</td>
                                        </tr>
                                    ))}
                                    {auditResult.entries.length === 0 && (
                                        <tr>
                                            <td colSpan={6} className="px-4 py-6 text-center text-sm text-hud-text-muted">
                                                audit 이력이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}
            </section>

            <ConfirmDialog
                open={pendingResolution !== null}
                title={dialogTitle}
                description={dialogDescription}
                confirmLabel={dialogConfirmLabel}
                cancelLabel="취소"
                variant={dialogVariant}
                loading={resolving}
                onConfirm={() => void handleResolutionConfirm()}
                onCancel={() => {
                    if (!resolving) {
                        setPendingResolution(null)
                        setResolveNotes('')
                    }
                }}
            />
        </main>
    )
}

export default MetadataNormalizationAdminPage
