import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
    ArrowDownToLine,
    CheckCircle2,
    ExternalLink,
    ListMusic,
    Pause,
    Play,
    RefreshCw,
    RotateCcw,
    SkipBack,
    SkipForward,
    Sparkles,
    Trash2,
    Volume2,
    X,
} from 'lucide-react'
import Button from '@/components/common/Button'
import ConfirmDialog from '@/components/common/ConfirmDialog'
import HudCard from '@/components/common/HudCard'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { toEmsTrackPlaybackItem } from '@/lib/emsPlayback'
import { formatDuration } from '@/lib/musicPlayback'
import {
    ApiError,
    dismissGmsPlaylist,
    fetchEmsCollectedPlaylistDetail,
    fetchGmsPlaylistPreview,
    importTidalPlaylistUrlToGms,
    saveGmsPlaylistToPms,
} from '@/services/api'
import type {
    EmsCollectionPlaylistDetailResponse,
    GmsPlaylistPreviewItem,
    GmsPlaylistPreviewResponse,
    GmsPlaylistSaveResponse,
    GmsTidalPlaylistUrlImportResponse,
} from '@/types/api'

const DEFAULT_LIMIT = 12

const formatScore = (value: number) => value.toFixed(2)

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

const formatCollectedAt = (value: string) => {
    try {
        return new Date(value).toLocaleString()
    } catch {
        return value
    }
}

const formatPosition = (value: number) => formatDuration(Number.isFinite(value) ? Math.round(value) : 0)

const GmsPlaylistsPage = () => {
    const { session } = useAuthSession()
    const {
        playQueue,
        pause,
        resume,
        skipNext,
        skipPrevious,
        seek,
        setVolume,
        currentItem,
        currentIndex,
        queue,
        isPlaying,
        isLoading: playbackLoading,
        positionMs,
        durationMs,
        volume,
        error: playbackError,
        notice: playbackNotice,
        audioQualityLabel,
    } = usePlayback()
    const userId = session?.userId ?? ''
    const playQueueRef = useRef(playQueue)

    const [preview, setPreview] = useState<GmsPlaylistPreviewResponse | null>(null)
    const [isLoading, setIsLoading] = useState(false)
    const [errorMessage, setErrorMessage] = useState<string | null>(null)
    const [pendingSaveId, setPendingSaveId] = useState<number | null>(null)
    const [pendingDismissId, setPendingDismissId] = useState<number | null>(null)
    const [confirmCandidate, setConfirmCandidate] = useState<GmsPlaylistPreviewItem | null>(null)
    const [dismissCandidate, setDismissCandidate] = useState<GmsPlaylistPreviewItem | null>(null)
    const [lastSaveResult, setLastSaveResult] = useState<GmsPlaylistSaveResponse | null>(null)
    const [savedPlaylistIds, setSavedPlaylistIds] = useState<Set<number>>(new Set())
    const [dismissedPlaylistIds, setDismissedPlaylistIds] = useState<Set<number>>(new Set())
    const [previewCandidate, setPreviewCandidate] = useState<GmsPlaylistPreviewItem | null>(null)
    const [previewDetail, setPreviewDetail] = useState<EmsCollectionPlaylistDetailResponse | null>(null)
    const [previewLoading, setPreviewLoading] = useState(false)
    const [previewError, setPreviewError] = useState<string | null>(null)
    const [removedPreviewTrackIds, setRemovedPreviewTrackIds] = useState<Set<number>>(new Set())
    const [tidalPlaylistUrl, setTidalPlaylistUrl] = useState('')
    const [isImportingTidalUrl, setIsImportingTidalUrl] = useState(false)
    const [lastTidalImportResult, setLastTidalImportResult] = useState<GmsTidalPlaylistUrlImportResponse | null>(null)
    const [includedPlaylistId, setIncludedPlaylistId] = useState<number | null>(null)

    useEffect(() => {
        playQueueRef.current = playQueue
    }, [playQueue])

    const loadPreview = useCallback(
        (signal?: AbortSignal, includeOverride?: number | null) => {
            if (!userId) {
                setPreview(null)
                setErrorMessage('Sign in to load GMS playlist candidates.')
                return
            }

            setIsLoading(true)
            setErrorMessage(null)

            fetchGmsPlaylistPreview(
                userId,
                DEFAULT_LIMIT,
                signal,
                includeOverride ?? includedPlaylistId ?? undefined,
            )
                .then((response) => {
                    setPreview(response)
                })
                .catch((requestError: unknown) => {
                    if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                        return
                    }
                    const message =
                        requestError instanceof ApiError
                            ? requestError.message
                            : 'Unable to load GMS playlist candidates.'
                    setErrorMessage(message)
                    setPreview(null)
                })
                .finally(() => {
                    setIsLoading(false)
                })
        },
        [includedPlaylistId, userId],
    )

    useEffect(() => {
        const controller = new AbortController()
        loadPreview(controller.signal)
        return () => controller.abort()
    }, [loadPreview])

    useEffect(() => {
        if (!previewCandidate) {
            setPreviewDetail(null)
            setPreviewError(null)
            setPreviewLoading(false)
            setRemovedPreviewTrackIds(new Set())
            return
        }
        const controller = new AbortController()
        setPreviewLoading(true)
        setPreviewError(null)
        setPreviewDetail(null)
        setRemovedPreviewTrackIds(new Set())

        fetchEmsCollectedPlaylistDetail(previewCandidate.playlist_id, controller.signal)
            .then((response) => {
                setPreviewDetail(response)
                const playbackItems = response.tracks.map((track) =>
                    toEmsTrackPlaybackItem(track, response.playlist.title),
                )
                if (playbackItems.length > 0) {
                    void playQueueRef.current(playbackItems, 0)
                }
            })
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : 'Unable to load EMS playlist tracks.'
                setPreviewError(message)
            })
            .finally(() => setPreviewLoading(false))

        return () => controller.abort()
    }, [previewCandidate])

    const visiblePreviewTracks = useMemo(
        () => previewDetail?.tracks.filter((track) => !removedPreviewTrackIds.has(track.id)) ?? [],
        [previewDetail, removedPreviewTrackIds],
    )

    const previewPlaybackItems = useMemo(
        () =>
            visiblePreviewTracks.map((track) =>
                toEmsTrackPlaybackItem(track, previewDetail?.playlist.title),
            ),
        [previewDetail, visiblePreviewTracks],
    )

    const previewQueueActive = useMemo(
        () => Boolean(currentItem && previewPlaybackItems.some((item) => item.id === currentItem.id)),
        [currentItem, previewPlaybackItems],
    )
    const previewSubtitleParts = previewQueueActive && currentItem
        ? currentItem.subtitle?.split(' · ').map((part) => part.trim()).filter(Boolean) ?? []
        : []
    const previewArtistName = previewSubtitleParts[0] ?? null
    const previewSubtitleRemainder = [
        ...previewSubtitleParts.slice(1),
        audioQualityLabel,
    ].filter(Boolean).join(' · ')

    const handlePreviewPlayAll = () => {
        if (previewPlaybackItems.length > 0) {
            void playQueue(previewPlaybackItems, 0)
        }
    }

    const handlePreviewPlayTrack = (index: number) => {
        const selectedItem = previewPlaybackItems[index]
        if (selectedItem) {
            void playQueue(previewPlaybackItems, index)
        }
    }

    const handlePreviewRemoveTrack = (trackId: number) => {
        setRemovedPreviewTrackIds((current) => {
            const next = new Set(current)
            next.add(trackId)
            return next
        })
    }

    const handleRestoreRemovedTracks = () => {
        setRemovedPreviewTrackIds(new Set())
    }

    const handlePreviewSave = async () => {
        if (!userId || !previewCandidate) {
            return
        }
        setPendingSaveId(previewCandidate.playlist_id)
        setErrorMessage(null)
        try {
            const result = await saveGmsPlaylistToPms(previewCandidate.playlist_id, userId, {
                title: null,
                excluded_track_ids: Array.from(removedPreviewTrackIds),
            })
            setLastSaveResult(result)
            setSavedPlaylistIds((current) => {
                const next = new Set(current)
                next.add(previewCandidate.playlist_id)
                return next
            })
            setPreviewCandidate(null)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to save this EMS playlist into PMS.'
            setErrorMessage(message)
        } finally {
            setPendingSaveId(null)
        }
    }

    const handleSaveConfirmed = async () => {
        if (!userId || !confirmCandidate) {
            return
        }

        setPendingSaveId(confirmCandidate.playlist_id)
        setErrorMessage(null)

        try {
            const result = await saveGmsPlaylistToPms(confirmCandidate.playlist_id, userId, {
                title: null,
            })
            setLastSaveResult(result)
            setSavedPlaylistIds((current) => {
                const next = new Set(current)
                next.add(confirmCandidate.playlist_id)
                return next
            })
            setConfirmCandidate(null)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to save this EMS playlist into PMS.'
            setErrorMessage(message)
        } finally {
            setPendingSaveId(null)
        }
    }

    const visibleCandidates = useMemo(
        () =>
            (preview?.candidates ?? []).filter((candidate) =>
                !dismissedPlaylistIds.has(candidate.playlist_id) && !savedPlaylistIds.has(candidate.playlist_id),
            ),
        [preview?.candidates, dismissedPlaylistIds, savedPlaylistIds],
    )

    const handleDismissConfirmed = async () => {
        if (!userId || !dismissCandidate) {
            return
        }
        setPendingDismissId(dismissCandidate.playlist_id)
        setErrorMessage(null)
        try {
            await dismissGmsPlaylist(dismissCandidate.playlist_id, userId)
            setDismissedPlaylistIds((current) => {
                const next = new Set(current)
                next.add(dismissCandidate.playlist_id)
                return next
            })
            if (previewCandidate?.playlist_id === dismissCandidate.playlist_id) {
                setPreviewCandidate(null)
            }
            setDismissCandidate(null)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to remove this playlist from GMS.'
            setErrorMessage(message)
        } finally {
            setPendingDismissId(null)
        }
    }

    const handleTidalPlaylistUrlImport = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        if (!userId) {
            setErrorMessage('Sign in to import a TIDAL playlist URL.')
            return
        }

        const normalizedUrl = tidalPlaylistUrl.trim()
        if (!/^https?:\/\/(www\.)?tidal\.com\/(browse\/)?playlist\/[A-Za-z0-9][A-Za-z0-9_-]{2,159}([/?#].*)?$/.test(normalizedUrl)) {
            setErrorMessage('Enter a valid TIDAL playlist URL.')
            return
        }

        setIsImportingTidalUrl(true)
        setErrorMessage(null)
        setLastTidalImportResult(null)

        try {
            const result = await importTidalPlaylistUrlToGms({
                user_id: userId,
                playlist_url: normalizedUrl,
            })
            setLastTidalImportResult(result)
            setIncludedPlaylistId(result.ems_playlist_id)
            setTidalPlaylistUrl('')
            loadPreview(undefined, result.ems_playlist_id)
        } catch (requestError: unknown) {
            const message =
                requestError instanceof ApiError
                    ? requestError.message
                    : 'Unable to import this TIDAL playlist URL.'
            setErrorMessage(message)
        } finally {
            setIsImportingTidalUrl(false)
        }
    }

    return (
        <div className="space-y-6">
            <HudCard
                title="GMS Playlist Candidates"
                subtitle="EMS에서 평가한 공개 플레이리스트 중 사용자의 PMS 라이브러리와 연관도가 높은 후보"
                action={
                    <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={isLoading || !userId}
                        onClick={() => loadPreview()}
                    >
                        <RefreshCw size={14} className={isLoading ? 'animate-spin' : ''} />
                        Refresh
                    </Button>
                }
            >
                <div className="grid gap-3 md:grid-cols-3">
                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                        <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">User ID</p>
                        <p className="mt-2 text-sm font-medium text-hud-text-primary">
                            {userId || 'Not signed in'}
                        </p>
                    </div>
                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                        <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Model Stage</p>
                        <p className="mt-2 text-sm font-medium capitalize text-hud-text-primary">
                            {preview?.model_stage ?? '—'}
                        </p>
                    </div>
                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                        <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">Preferred Platform</p>
                        <p className="mt-2 text-sm font-medium capitalize text-hud-text-primary">
                            {preview?.preferred_platform ?? 'any'}
                        </p>
                    </div>
                </div>

                <form
                    className="mt-4 flex flex-col gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4 md:flex-row md:items-end"
                    onSubmit={handleTidalPlaylistUrlImport}
                >
                    <label className="flex-1 text-xs font-medium uppercase tracking-[0.18em] text-hud-text-muted">
                        TIDAL Playlist URL
                        <input
                            type="url"
                            value={tidalPlaylistUrl}
                            onChange={(event) => setTidalPlaylistUrl(event.target.value)}
                            placeholder="https://tidal.com/playlist/..."
                            className="mt-2 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-3 py-2 text-sm normal-case tracking-normal text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-accent-primary"
                            disabled={isImportingTidalUrl || !userId}
                        />
                    </label>
                    <Button
                        type="submit"
                        variant="primary"
                        size="sm"
                        disabled={isImportingTidalUrl || !userId || !tidalPlaylistUrl.trim()}
                    >
                        <ArrowDownToLine size={14} className={isImportingTidalUrl ? 'animate-pulse' : ''} />
                        {isImportingTidalUrl ? 'Importing' : 'Import'}
                    </Button>
                </form>

                {lastTidalImportResult && (
                    <div className="mt-4 flex items-start gap-3 rounded-2xl border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                        <CheckCircle2 size={18} className="mt-0.5 text-hud-accent-primary" />
                        <div>
                            <p className="font-medium text-hud-text-primary">
                                Imported TIDAL playlist: {lastTidalImportResult.title}
                            </p>
                            <p className="mt-1 text-xs text-hud-text-muted">
                                {lastTidalImportResult.track_count} track(s) added to EMS for GMS review.
                            </p>
                        </div>
                    </div>
                )}

                {errorMessage && (
                    <div className="mt-4 rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                        {errorMessage}
                    </div>
                )}

                {lastSaveResult && (
                    <div className="mt-4 flex items-start gap-3 rounded-2xl border border-hud-accent-primary/40 bg-hud-accent-primary/10 p-4 text-sm leading-6 text-hud-text-secondary">
                        <CheckCircle2 size={18} className="mt-0.5 text-hud-accent-primary" />
                        <div>
                            <p className="font-medium text-hud-text-primary">
                                Saved to PMS: {lastSaveResult.personal_playlist_title}
                            </p>
                            <p className="mt-1 text-xs text-hud-text-muted">
                                Added {lastSaveResult.added_track_count} track(s) ·{' '}
                                {lastSaveResult.personal_playlist_track_count} total in this playlist
                            </p>
                        </div>
                    </div>
                )}
            </HudCard>

            <HudCard title="Candidate Playlists" subtitle="Affinity 점수가 높은 순으로 정렬됨">
                {!preview && isLoading && (
                    <div className="flex items-center gap-3 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm text-hud-text-secondary">
                        <RefreshCw size={18} className="animate-spin text-hud-accent-primary" />
                        Loading GMS playlist candidates...
                    </div>
                )}

                {preview && visibleCandidates.length === 0 && (
                    <div className="flex items-center gap-3 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm text-hud-text-secondary">
                        <Sparkles size={18} className="text-hud-accent-primary" />
                        후보 플레이리스트가 없습니다. EMS pool 적재가 더 채워질 때까지 기다리거나, 본인 PMS 라이브러리를 확장하세요.
                    </div>
                )}

                {visibleCandidates.length > 0 && (
                    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                        {visibleCandidates.map((candidate) => {
                            const isSaving = pendingSaveId === candidate.playlist_id
                            const isDismissing = pendingDismissId === candidate.playlist_id
                            const alreadySaved = savedPlaylistIds.has(candidate.playlist_id)
                            return (
                                <div
                                    key={candidate.playlist_id}
                                    className="flex flex-col gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4"
                                >
                                    {candidate.cover_image_url ? (
                                        <img
                                            src={candidate.cover_image_url}
                                            alt={candidate.title}
                                            className="aspect-[4/3] w-full rounded-xl object-cover"
                                        />
                                    ) : (
                                        <div className="flex aspect-[4/3] w-full items-center justify-center rounded-xl bg-hud-bg-secondary/60 text-hud-text-muted">
                                            <Sparkles size={28} />
                                        </div>
                                    )}

                                    <div className="space-y-1">
                                        <p className="text-sm font-semibold text-hud-text-primary line-clamp-2">
                                            {candidate.title}
                                        </p>
                                        <p className="text-xs text-hud-text-muted">
                                            {candidate.curator || 'Unknown curator'} · {candidate.source_platform}
                                        </p>
                                        {candidate.description && (
                                            <p className="text-xs leading-5 text-hud-text-secondary line-clamp-3">
                                                {candidate.description}
                                            </p>
                                        )}
                                    </div>

                                    <div className="grid grid-cols-2 gap-2 text-[11px]">
                                        <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-2">
                                            <p className="uppercase tracking-[0.18em] text-hud-text-muted">Composite</p>
                                            <p className="mt-1 font-semibold text-hud-accent-primary">
                                                {formatScore(candidate.composite_score)}
                                            </p>
                                        </div>
                                        <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-2">
                                            <p className="uppercase tracking-[0.18em] text-hud-text-muted">Affinity</p>
                                            <p className="mt-1 font-semibold text-hud-text-primary">
                                                {formatScore(candidate.affinity_score)}
                                            </p>
                                        </div>
                                        <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-2">
                                            <p className="uppercase tracking-[0.18em] text-hud-text-muted">Tracks</p>
                                            <p className="mt-1 font-semibold text-hud-text-primary">
                                                {candidate.track_count}
                                            </p>
                                        </div>
                                        <div className="rounded-xl border border-hud-border-secondary bg-hud-bg-secondary/60 px-2 py-2">
                                            <p className="uppercase tracking-[0.18em] text-hud-text-muted">Audio Feat</p>
                                            <p className="mt-1 font-semibold text-hud-text-primary">
                                                {candidate.audio_feature_filled_count}
                                            </p>
                                        </div>
                                    </div>

                                    {candidate.axis_evidence && candidate.axis_evidence.length > 0 && (
                                        <ul className="space-y-1.5 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/60 p-2">
                                            {candidate.axis_evidence.map((evidence) => (
                                                <li
                                                    key={`${candidate.playlist_id}-${evidence.axis}`}
                                                    className="flex items-start gap-2 text-[11px]"
                                                >
                                                    <span
                                                        className={`mt-0.5 inline-flex h-5 min-w-[64px] items-center justify-center rounded-full border px-2 text-[10px] uppercase tracking-[0.18em] ${axisLevelClass(evidence.level)}`}
                                                    >
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

                                    <p className="text-[11px] text-hud-text-muted">
                                        Collected {formatCollectedAt(candidate.collected_at)}
                                    </p>

                                    <div className="mt-auto flex flex-col gap-2">
                                        <div className="flex gap-2">
                                            {candidate.platform_external_url && (
                                                <a
                                                    href={candidate.platform_external_url}
                                                    target="_blank"
                                                    rel="noopener noreferrer"
                                                    className="inline-flex items-center justify-center gap-1.5 rounded-lg border border-hud-border-secondary px-3 py-1.5 text-xs text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                                >
                                                    <ExternalLink size={14} />
                                                    Open
                                                </a>
                                            )}
                                            <Button
                                                type="button"
                                                variant="outline"
                                                size="sm"
                                                fullWidth
                                                onClick={() => setPreviewCandidate(candidate)}
                                            >
                                                <ListMusic size={14} />
                                                Preview tracks
                                            </Button>
                                        </div>
                                        <Button
                                            type="button"
                                            variant={alreadySaved ? 'ghost' : 'primary'}
                                            size="sm"
                                            fullWidth
                                            disabled={isSaving || alreadySaved}
                                            onClick={() => setConfirmCandidate(candidate)}
                                        >
                                            {isSaving ? (
                                                <>
                                                    <RefreshCw size={14} className="animate-spin" />
                                                    Saving
                                                </>
                                            ) : alreadySaved ? (
                                                <>
                                                    <CheckCircle2 size={14} />
                                                    Saved
                                                </>
                                            ) : (
                                                <>
                                                    <ArrowDownToLine size={14} />
                                                    Save to PMS
                                                </>
                                            )}
                                        </Button>
                                        <Button
                                            type="button"
                                            variant="danger"
                                            size="sm"
                                            fullWidth
                                            disabled={isDismissing}
                                            onClick={() => setDismissCandidate(candidate)}
                                        >
                                            {isDismissing ? (
                                                <>
                                                    <RefreshCw size={14} className="animate-spin" />
                                                    Removing
                                                </>
                                            ) : (
                                                <>
                                                    <Trash2 size={14} />
                                                    Remove from GMS
                                                </>
                                            )}
                                        </Button>
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                )}
            </HudCard>

            <ConfirmDialog
                open={confirmCandidate !== null}
                title="이 플레이리스트를 PMS에 저장할까요?"
                description={
                    confirmCandidate
                        ? `"${confirmCandidate.title}"의 ${confirmCandidate.track_count}곡이 개인 라이브러리에 추가됩니다.`
                        : ''
                }
                confirmLabel="저장"
                cancelLabel="취소"
                variant="primary"
                loading={pendingSaveId !== null}
                onConfirm={handleSaveConfirmed}
                onCancel={() => setConfirmCandidate(null)}
            />

            <ConfirmDialog
                open={dismissCandidate !== null}
                title="이 플레이리스트를 GMS 후보에서 제거할까요?"
                description={
                    dismissCandidate
                        ? `"${dismissCandidate.title}"는 이 사용자의 GMS playlist 후보에서 숨겨지고 ignored 신호로 기록됩니다. EMS 원본 데이터는 삭제하지 않습니다.`
                        : ''
                }
                confirmLabel="제거"
                cancelLabel="취소"
                variant="danger"
                loading={pendingDismissId !== null}
                onConfirm={handleDismissConfirmed}
                onCancel={() => setDismissCandidate(null)}
            />

            {previewCandidate && (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center p-4"
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="gms-preview-modal-title"
                >
                    <div
                        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
                        onClick={() => setPreviewCandidate(null)}
                    />
                    <div className="relative hud-card hud-card-bottom flex max-h-[85vh] w-full max-w-3xl flex-col rounded-lg animate-fade-in">
                        <div className="flex items-start justify-between gap-4 border-b border-hud-border-secondary p-5">
                            <div className="flex items-start gap-3">
                                {previewCandidate.cover_image_url ? (
                                    <img
                                        src={previewCandidate.cover_image_url}
                                        alt={previewCandidate.title}
                                        className="h-16 w-16 rounded-xl object-cover"
                                    />
                                ) : (
                                    <div className="flex h-16 w-16 items-center justify-center rounded-xl bg-hud-bg-secondary/60 text-hud-text-muted">
                                        <Sparkles size={24} />
                                    </div>
                                )}
                                <div>
                                    <h3
                                        id="gms-preview-modal-title"
                                        className="text-base font-semibold text-hud-text-primary"
                                    >
                                        {previewCandidate.title}
                                    </h3>
                                    <p className="mt-1 text-xs text-hud-text-muted">
                                        {previewCandidate.curator || 'Unknown curator'} ·{' '}
                                        {previewCandidate.source_platform} ·{' '}
                                        {previewDetail ? `${visiblePreviewTracks.length}/${previewCandidate.track_count}` : previewCandidate.track_count} tracks
                                    </p>
                                </div>
                            </div>
                            <button
                                type="button"
                                onClick={() => setPreviewCandidate(null)}
                                className="rounded-lg p-2 text-hud-text-muted transition-hud hover:bg-hud-bg-hover hover:text-hud-text-primary"
                                aria-label="Close preview"
                            >
                                <X size={18} />
                            </button>
                        </div>

                        <div className="flex-1 overflow-y-auto p-5">
                            {previewLoading && (
                                <div className="flex items-center gap-3 rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm text-hud-text-secondary">
                                    <RefreshCw size={18} className="animate-spin text-hud-accent-primary" />
                                    Loading playlist tracks...
                                </div>
                            )}
                            {previewError && (
                                <div className="rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                    {previewError}
                                </div>
                            )}
                            {previewDetail && previewPlaybackItems.length === 0 && (
                                <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm text-hud-text-secondary">
                                    이 플레이리스트의 트랙 목록이 비어 있습니다.
                                    {removedPreviewTrackIds.size > 0 && (
                                        <button
                                            type="button"
                                            onClick={handleRestoreRemovedTracks}
                                            className="ml-2 text-hud-accent-primary underline-offset-4 hover:underline"
                                        >
                                            제거한 트랙 복원
                                        </button>
                                    )}
                                </div>
                            )}
                            {previewDetail && previewPlaybackItems.length > 0 && (
                                <>
                                    <div className="mb-4 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                        <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
                                            <div className="min-w-0 flex-1">
                                                <p className="text-[11px] uppercase tracking-[0.22em] text-hud-text-muted">
                                                    Preview player
                                                </p>
                                                <p className="mt-1 truncate text-sm font-semibold text-hud-text-primary">
                                                    {previewQueueActive && currentItem ? currentItem.title : '트랙을 선택해 재생을 시작하세요'}
                                                </p>
                                                <p className="mt-1 truncate text-xs text-hud-text-muted">
                                                    {previewQueueActive && currentItem ? (
                                                        <>
                                                            {previewArtistName ? (
                                                                <ArtistDetailLink
                                                                    artistName={previewArtistName}
                                                                    className="transition-hud hover:text-hud-accent-primary"
                                                                />
                                                            ) : (
                                                                currentItem.subtitle
                                                            )}
                                                            {previewSubtitleRemainder ? <span> · {previewSubtitleRemainder}</span> : null}
                                                        </>
                                                    ) : (
                                                        `${visiblePreviewTracks.length} tracks ready`
                                                    )}
                                                </p>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                <button
                                                    type="button"
                                                    onClick={() => void skipPrevious()}
                                                    disabled={!previewQueueActive || playbackLoading}
                                                    className="rounded-lg border border-hud-border-secondary p-2 text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary disabled:cursor-not-allowed disabled:opacity-50"
                                                    aria-label="Previous preview track"
                                                >
                                                    <SkipBack size={16} />
                                                </button>
                                                <button
                                                    type="button"
                                                    onClick={() => {
                                                        if (previewQueueActive && isPlaying) {
                                                            void pause()
                                                        } else if (previewQueueActive) {
                                                            void resume()
                                                        } else {
                                                            handlePreviewPlayAll()
                                                        }
                                                    }}
                                                    disabled={playbackLoading || previewPlaybackItems.length === 0}
                                                    className="rounded-lg border border-hud-accent-primary/50 bg-hud-accent-primary/10 p-2 text-hud-accent-primary transition-hud hover:bg-hud-accent-primary/20 disabled:cursor-not-allowed disabled:opacity-50"
                                                    aria-label={
                                                        previewQueueActive && isPlaying
                                                            ? 'Pause preview playback'
                                                            : previewQueueActive
                                                                ? 'Resume preview playback'
                                                                : 'Play preview playback'
                                                    }
                                                >
                                                    {previewQueueActive && isPlaying ? <Pause size={17} /> : <Play size={17} />}
                                                </button>
                                                <button
                                                    type="button"
                                                    onClick={() => void skipNext()}
                                                    disabled={!previewQueueActive || playbackLoading}
                                                    className="rounded-lg border border-hud-border-secondary p-2 text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary disabled:cursor-not-allowed disabled:opacity-50"
                                                    aria-label="Next preview track"
                                                >
                                                    <SkipForward size={16} />
                                                </button>
                                            </div>
                                        </div>

                                        <div className="mt-4 grid gap-3 lg:grid-cols-[minmax(0,1fr)_140px]">
                                            <label className="flex items-center gap-2 text-xs text-hud-text-muted">
                                                <span className="w-11 text-right">{formatPosition(positionMs)}</span>
                                                <input
                                                    type="range"
                                                    min={0}
                                                    max={Math.max(durationMs, 1)}
                                                    value={Math.min(positionMs, Math.max(durationMs, 1))}
                                                    onChange={(event) => void seek(Number(event.target.value))}
                                                    disabled={!previewQueueActive || durationMs <= 0}
                                                    className="min-w-0 flex-1 accent-hud-accent-primary disabled:opacity-40"
                                                    aria-label="Seek preview playback"
                                                />
                                                <span className="w-11">{formatPosition(durationMs)}</span>
                                            </label>
                                            <label className="flex items-center gap-2 text-xs text-hud-text-muted">
                                                <Volume2 size={14} />
                                                <input
                                                    type="range"
                                                    min={0}
                                                    max={1}
                                                    step={0.05}
                                                    value={volume}
                                                    onChange={(event) => void setVolume(Number(event.target.value))}
                                                    className="min-w-0 flex-1 accent-hud-accent-primary"
                                                    aria-label="Preview playback volume"
                                                />
                                            </label>
                                        </div>

                                        {(playbackNotice || playbackError) && previewQueueActive && (
                                            <p className={`mt-3 rounded-lg border px-3 py-2 text-xs ${
                                                playbackError
                                                    ? 'border-hud-accent-danger/40 bg-hud-accent-danger/10 text-hud-text-secondary'
                                                    : 'border-hud-border-secondary bg-hud-bg-secondary/60 text-hud-text-muted'
                                            }`}>
                                                {playbackError ?? playbackNotice}
                                            </p>
                                        )}

                                        <p className="mt-3 text-xs text-hud-text-muted">
                                            Queue {previewQueueActive ? `${currentIndex + 1}/${queue.length}` : '-'} ·{' '}
                                            {removedPreviewTrackIds.size} removed before PMS save
                                            {removedPreviewTrackIds.size > 0 && (
                                                <button
                                                    type="button"
                                                    onClick={handleRestoreRemovedTracks}
                                                    className="ml-2 inline-flex items-center gap-1 text-hud-accent-primary transition-hud hover:text-hud-text-primary"
                                                >
                                                    <RotateCcw size={12} />
                                                    Restore
                                                </button>
                                            )}
                                        </p>
                                    </div>

                                    <ul className="space-y-2">
                                        {visiblePreviewTracks.map((track, index) => (
                                            <li
                                                key={track.id}
                                                className={`flex items-center gap-3 rounded-xl border px-3 py-2 ${
                                                    currentItem?.id === `ems-track:${track.id}`
                                                        ? 'border-hud-accent-primary/50 bg-hud-accent-primary/10'
                                                        : 'border-hud-border-secondary bg-hud-bg-primary/60'
                                                }`}
                                            >
                                                <span className="w-6 shrink-0 text-right text-xs text-hud-text-muted">
                                                    {index + 1}
                                                </span>
                                                {track.album_image_url ? (
                                                    <img
                                                        src={track.album_image_url}
                                                        alt={track.album_title ?? track.title}
                                                        className="h-10 w-10 shrink-0 rounded-md object-cover"
                                                    />
                                                ) : (
                                                    <div className="h-10 w-10 shrink-0 rounded-md bg-hud-bg-secondary/60" />
                                                )}
                                                <div className="min-w-0 flex-1">
                                                    <p className="truncate text-sm text-hud-text-primary">
                                                        {track.title}
                                                    </p>
                                                    <p className="truncate text-xs text-hud-text-muted">
                                                        <ArtistDetailLink
                                                            artistName={track.artist_name}
                                                            className="transition-hud hover:text-hud-accent-primary"
                                                        />
                                                        <span> · {track.source_platform}</span>
                                                    </p>
                                                </div>
                                                <span className="shrink-0 text-xs text-hud-text-muted">
                                                    {formatDuration(track.duration_ms)}
                                                </span>
                                                <button
                                                    type="button"
                                                    onClick={() => handlePreviewPlayTrack(index)}
                                                    disabled={playbackLoading}
                                                    className="rounded-lg border border-hud-border-secondary px-2 py-1 text-xs text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary disabled:cursor-not-allowed disabled:opacity-50"
                                                    aria-label={`Play ${track.title}`}
                                                >
                                                    <Play size={14} />
                                                </button>
                                                <button
                                                    type="button"
                                                    onClick={() => handlePreviewRemoveTrack(track.id)}
                                                    className="rounded-lg border border-hud-border-secondary px-2 py-1 text-xs text-hud-text-secondary transition-hud hover:border-hud-accent-danger/60 hover:text-hud-accent-danger"
                                                    aria-label={`Remove ${track.title} from PMS save preview`}
                                                >
                                                    <Trash2 size={14} />
                                                </button>
                                            </li>
                                        ))}
                                    </ul>
                                </>
                            )}
                        </div>

                        <div className="flex flex-wrap gap-3 border-t border-hud-border-secondary p-5">
                            <Button
                                type="button"
                                variant="outline"
                                size="sm"
                                disabled={previewPlaybackItems.length === 0 || playbackLoading}
                                onClick={handlePreviewPlayAll}
                            >
                                <Play size={14} />
                                Play all
                            </Button>
                            <div className="flex-1" />
                            <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                onClick={() => setPreviewCandidate(null)}
                            >
                                닫기
                            </Button>
                            <Button
                                type="button"
                                variant="primary"
                                size="sm"
                                disabled={
                                    pendingSaveId !== null ||
                                    savedPlaylistIds.has(previewCandidate.playlist_id) ||
                                    previewPlaybackItems.length === 0
                                }
                                onClick={handlePreviewSave}
                            >
                                {pendingSaveId === previewCandidate.playlist_id ? (
                                    <>
                                        <RefreshCw size={14} className="animate-spin" />
                                        저장 중
                                    </>
                                ) : savedPlaylistIds.has(previewCandidate.playlist_id) ? (
                                    <>
                                        <CheckCircle2 size={14} />
                                        저장됨
                                    </>
                                ) : (
                                    <>
                                        <ArrowDownToLine size={14} />
                                        PMS에 저장
                                    </>
                                )}
                            </Button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}

export default GmsPlaylistsPage
