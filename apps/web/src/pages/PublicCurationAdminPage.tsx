import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { CheckCircle2, Copy, ExternalLink, Loader2, Rocket, Sparkles, Trash2, Wand2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { formatDuration } from '@/lib/musicPlayback'
import {
    ApiError,
    createPublicCurationDraft,
    deletePublicCurationPlaylist,
    fetchPublicCurationAdminPlaylists,
    publishPublicCurationPlaylist,
} from '@/services/api'
import type {
    PublicCurationAdminPlaylistSummary,
    PublicCurationAdminRunRequest,
    PublicCurationAdminRunResponse,
    PublicCurationCandidatePreparationSummary,
} from '@/types/api'

const splitTags = (value: string) =>
    value
        .split(',')
        .map((tag) => tag.trim())
        .filter((tag) => tag.length > 0)

const parseNumberInput = (value: string) => {
    if (!value.trim()) {
        return null
    }
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
}

const toSlug = (value: string) =>
    value
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 120)

const createPublicMixSlug = () => {
    const now = new Date()
    const datePart = [
        now.getFullYear(),
        String(now.getMonth() + 1).padStart(2, '0'),
        String(now.getDate()).padStart(2, '0'),
    ].join('')
    const randomPart = Math.random().toString(36).slice(2, 8)
    return `mix-${datePart}-${randomPart}`
}

const publicMixPath = (slug: string) => `/mix/${encodeURIComponent(slug)}`

const publicMixUrl = (slug: string) => {
    const path = publicMixPath(slug)
    if (typeof window === 'undefined') {
        return path
    }
    return `${window.location.origin}${path}`
}

const statusLabel = (status: string) => {
    if (status === 'published') {
        return '발행됨'
    }
    if (status === 'draft') {
        return '초안'
    }
    if (status === 'archived') {
        return '보관됨'
    }
    return status
}

const formatDateTime = (value: string | null) => {
    if (!value) {
        return '아직 없음'
    }
    return new Date(value).toLocaleString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    })
}

const errorMessage = (error: unknown) => {
    if (error instanceof ApiError) {
        return error.message
    }
    if (error instanceof Error) {
        return error.message
    }
    return '공개 큐레이션 실행에 실패했습니다.'
}

const FieldHelp = ({ children }: { children: ReactNode }) => (
    <span className="mt-2 block text-xs leading-5 text-hud-text-muted">{children}</span>
)

const scoreAxisLabel: Record<string, string> = {
    semantic_theme_fit: '테마',
    audio_fit: '오디오',
    metadata_quality: '메타데이터',
    source_quality: '출처',
    freshness: '신선도',
    audience_response: '청취 반응',
    discovery_value: '발견성',
    sequence_adjustment: '흐름 보정',
}

const candidatePreparationLabels: Partial<Record<keyof PublicCurationCandidatePreparationSummary, string>> = {
    raw_count: 'Raw 후보',
    playable_count: '재생 가능',
    native_tidal_count: '기존 TIDAL',
    resolved_count: 'Resolve 성공',
    resolve_failed_count: 'Resolve 실패',
    excluded_count: '제외',
}

const scoreBreakdownEntries = (scoreBreakdown: Record<string, number>) =>
    Object.entries(scoreBreakdown)
        .filter(([axis]) => axis !== 'score' && axis !== 'tidal_readiness')
        .slice(0, 8)

const PublicCurationAdminPage = () => {
    const { session } = useAuthSession()
    const [adminUserId, setAdminUserId] = useState(session?.userId ?? 'admin-001')
    const [prompt, setPrompt] = useState('비 오는 밤에 듣기 좋은 한국 인디와 재즈 감성. 너무 처지지 않고 카페에 공유하기 좋은 30곡.')
    const [slug, setSlug] = useState(() => createPublicMixSlug())
    const [moodTags, setMoodTags] = useState('rainy, night, cafe')
    const [genreTags, setGenreTags] = useState('indie, jazz')
    const [targetTrackCount, setTargetTrackCount] = useState(30)
    const [candidateLimit, setCandidateLimit] = useState(220)
    const [energyMin, setEnergyMin] = useState('0.2')
    const [energyMax, setEnergyMax] = useState('0.7')
    const [valenceMin, setValenceMin] = useState('')
    const [valenceMax, setValenceMax] = useState('0.72')
    const [draft, setDraft] = useState<PublicCurationAdminRunResponse | null>(null)
    const [savedPlaylists, setSavedPlaylists] = useState<PublicCurationAdminPlaylistSummary[]>([])
    const [isRunning, setIsRunning] = useState(false)
    const [isPublishing, setIsPublishing] = useState(false)
    const [isListLoading, setIsListLoading] = useState(false)
    const [deletingPlaylistId, setDeletingPlaylistId] = useState<number | null>(null)
    const [deleteConfirmPlaylist, setDeleteConfirmPlaylist] = useState<PublicCurationAdminPlaylistSummary | null>(null)
    const [message, setMessage] = useState<string | null>(null)
    const [copiedSlug, setCopiedSlug] = useState<string | null>(null)
    const audioFeatureFields = [
        { label: 'energy min', value: energyMin, setter: setEnergyMin, help: '최소 에너지. 0은 차분함, 1은 강한 에너지입니다. 예: 0.2' },
        { label: 'energy max', value: energyMax, setter: setEnergyMax, help: '최대 에너지. 지나치게 강한 곡을 제외합니다. 예: 0.7' },
        { label: 'valence min', value: valenceMin, setter: setValenceMin, help: '최소 정서 밝기. 비워두면 하한을 제한하지 않습니다. 예: 0.25' },
        { label: 'valence max', value: valenceMax, setter: setValenceMax, help: '최대 정서 밝기. 0은 어두움, 1은 밝음입니다. 예: 0.72' },
    ]

    const shareUrl = useMemo(() => {
        if (!draft?.playlist.slug || draft.playlist.status !== 'published') {
            return ''
        }
        return publicMixUrl(draft.playlist.slug)
    }, [draft])
    const candidatePreparation = draft?.playlist.score_summary.candidate_preparation

    const refreshSavedPlaylists = useCallback(async (signal?: AbortSignal) => {
        setIsListLoading(true)
        try {
            const response = await fetchPublicCurationAdminPlaylists(signal)
            setSavedPlaylists(response.playlists)
        } catch (error) {
            if (!signal?.aborted) {
                setMessage(errorMessage(error))
            }
        } finally {
            if (!signal?.aborted) {
                setIsListLoading(false)
            }
        }
    }, [])

    useEffect(() => {
        const controller = new AbortController()
        void refreshSavedPlaylists(controller.signal)
        return () => controller.abort()
    }, [refreshSavedPlaylists])

    const buildRequest = (): PublicCurationAdminRunRequest => ({
        admin_user_id: adminUserId.trim() || 'admin-001',
        slug: toSlug(slug) || createPublicMixSlug(),
        prompt: prompt.trim(),
        target_track_count: Math.max(1, targetTrackCount),
        candidate_limit: Math.max(10, candidateLimit),
        cover_style: 'poster-dark',
        filters: {
            mood_tags: splitTags(moodTags),
            genre_tags: splitTags(genreTags),
            audio_feature_ranges: {
                energy: {
                    min: parseNumberInput(energyMin),
                    max: parseNumberInput(energyMax),
                },
                valence: {
                    min: parseNumberInput(valenceMin),
                    max: parseNumberInput(valenceMax),
                },
            },
        },
    })

    const handleGenerate = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        setIsRunning(true)
        setMessage(null)
        setCopiedSlug(null)

        try {
            const response = await createPublicCurationDraft(buildRequest())
            setDraft(response)
            setSlug(response.playlist.slug)
            setMessage('모델 실행이 완료되어 초안이 만들어졌습니다.')
            void refreshSavedPlaylists()
        } catch (error) {
            setMessage(errorMessage(error))
        } finally {
            setIsRunning(false)
        }
    }

    const handlePublish = async () => {
        if (!draft) {
            return
        }
        setIsPublishing(true)
        setMessage(null)

        try {
            const response = await publishPublicCurationPlaylist(draft.playlist.playlist_id)
            setDraft(response)
            setMessage('공개 공유 페이지가 발행되었습니다.')
            void refreshSavedPlaylists()
        } catch (error) {
            setMessage(errorMessage(error))
        } finally {
            setIsPublishing(false)
        }
    }

    const handlePublishFromList = async (playlistId: number) => {
        setIsPublishing(true)
        setMessage(null)

        try {
            const response = await publishPublicCurationPlaylist(playlistId)
            setDraft(response)
            setSlug(response.playlist.slug)
            setMessage('선택한 공개 큐레이션을 발행했습니다.')
            void refreshSavedPlaylists()
        } catch (error) {
            setMessage(errorMessage(error))
        } finally {
            setIsPublishing(false)
        }
    }

    const handleConfirmDelete = async () => {
        if (!deleteConfirmPlaylist) {
            return
        }

        const playlist = deleteConfirmPlaylist
        setDeletingPlaylistId(playlist.playlist_id)
        setMessage(null)

        try {
            await deletePublicCurationPlaylist(playlist.playlist_id)
            setSavedPlaylists((currentPlaylists) =>
                currentPlaylists.filter((item) => item.playlist_id !== playlist.playlist_id),
            )
            if (draft?.playlist.playlist_id === playlist.playlist_id) {
                setDraft(null)
                setCopiedSlug(null)
            }
            setDeleteConfirmPlaylist(null)
            setMessage('공유 플레이리스트를 삭제했습니다.')
        } catch (error) {
            setMessage(errorMessage(error))
            setDeleteConfirmPlaylist(null)
        } finally {
            setDeletingPlaylistId(null)
        }
    }

    const handleCopy = async (url: string, nextCopiedSlug: string) => {
        if (!url || typeof navigator === 'undefined') {
            return
        }
        await navigator.clipboard.writeText(url)
        setCopiedSlug(nextCopiedSlug)
    }

    return (
        <div className="space-y-6">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                <div>
                    <p className="mb-2 inline-flex items-center gap-2 rounded-lg border border-hud-accent-primary/35 px-3 py-1.5 text-xs font-bold text-hud-accent-primary">
                        <Sparkles className="h-4 w-4" />
                        운영자 생성
                    </p>
                    <h1 className="text-3xl font-bold text-hud-text-primary">공개 큐레이션 생성</h1>
                    <p className="mt-2 max-w-3xl text-sm leading-7 text-hud-text-secondary">
                        prompt와 조건을 입력하면 Public Curation Model이 TIDAL-ready 트랙을 평가해 외부 공유용 플레이리스트 초안을 만듭니다.
                    </p>
                </div>
                <Link
                    to={draft?.playlist.slug && draft.playlist.status === 'published' ? publicMixPath(draft.playlist.slug) : '/gms-preview'}
                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-hud-border-primary px-4 py-2 text-sm font-semibold text-hud-text-secondary transition-hud hover:border-hud-accent-primary hover:text-hud-accent-primary"
                >
                    <ExternalLink className="h-4 w-4" />
                    공개 페이지 보기
                </Link>
            </div>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)]">
                <HudCard title="모델 실행" subtitle="운영자는 의도와 조건을 정하고, 선별은 모델이 수행합니다.">
                    <form onSubmit={handleGenerate} className="space-y-5">
                        <label className="block">
                            <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">관리자 ID</span>
                            <input
                                value={adminUserId}
                                onChange={(event) => setAdminUserId(event.target.value)}
                                className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                            <FieldHelp>실행자를 기록합니다. 예: admin-001</FieldHelp>
                        </label>

                        <label className="block">
                            <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">공개 코드</span>
                            <input
                                value={slug}
                                onChange={(event) => setSlug(toSlug(event.target.value))}
                                className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                            <FieldHelp>공유 URL `/mix/공개 코드`에 사용됩니다. 예: rainy-night-jazz</FieldHelp>
                        </label>

                        <label className="block">
                            <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">프롬프트</span>
                            <textarea
                                value={prompt}
                                onChange={(event) => setPrompt(event.target.value)}
                                rows={5}
                                className="w-full resize-none rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm leading-7 text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                            <FieldHelp>장면, 분위기, 곡의 흐름을 자연어로 적습니다. 예: 비 오는 밤 카페에서 듣기 좋은 인디와 재즈 30곡</FieldHelp>
                        </label>

                        <div className="grid gap-4 md:grid-cols-2">
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">무드 태그</span>
                                <input
                                    value={moodTags}
                                    onChange={(event) => setMoodTags(event.target.value)}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                                <FieldHelp>쉼표로 구분합니다. 예: rainy, night, cafe</FieldHelp>
                            </label>
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">장르 태그</span>
                                <input
                                    value={genreTags}
                                    onChange={(event) => setGenreTags(event.target.value)}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                                <FieldHelp>쉼표로 구분합니다. 예: indie, jazz</FieldHelp>
                            </label>
                        </div>

                        <div className="grid gap-4 md:grid-cols-2">
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">목표 곡 수</span>
                                <input
                                    type="number"
                                    min={1}
                                    max={80}
                                    value={targetTrackCount}
                                    onChange={(event) => setTargetTrackCount(Number(event.target.value))}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                                <FieldHelp>발행할 최종 곡 수입니다. 예: 30</FieldHelp>
                            </label>
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">후보 풀 크기</span>
                                <input
                                    type="number"
                                    min={10}
                                    max={240}
                                    value={candidateLimit}
                                    onChange={(event) => setCandidateLimit(Number(event.target.value))}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                                <FieldHelp>관리자가 지정하는 최종 playable 후보 수입니다. 서버 안정성을 위해 최대 240까지 사용합니다. 예: 220</FieldHelp>
                            </label>
                        </div>

                        <div className="grid gap-4 md:grid-cols-4">
                            {audioFeatureFields.map(({ label, value, setter, help }) => (
                                <label key={label} className="block">
                                    <span className="mb-2 block text-xs font-semibold uppercase text-hud-text-muted">
                                        {label}
                                    </span>
                                    <input
                                        value={value}
                                        onChange={(event) => setter(event.target.value)}
                                        className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                    />
                                    <FieldHelp>{help}</FieldHelp>
                                </label>
                            ))}
                        </div>

                        <Button
                            type="submit"
                            size="lg"
                            fullWidth
                            disabled={isRunning || prompt.trim().length === 0}
                            leftIcon={isRunning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wand2 className="h-4 w-4" />}
                        >
                            모델 실행
                        </Button>
                    </form>
                </HudCard>

                <div className="space-y-6">
                    <HudCard title="초안 결과" subtitle="생성 결과와 선별 트랙 preview를 확인합니다.">
                        {draft ? (
                            <div className="space-y-5">
                                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary p-4">
                                    <p className="text-xs font-semibold uppercase text-hud-text-muted">{draft.playlist.status}</p>
                                    <h2 className="mt-2 text-2xl font-bold text-hud-text-primary">{draft.playlist.title}</h2>
                                    {draft.playlist.subtitle && (
                                        <p className="mt-2 text-sm leading-7 text-hud-text-secondary">{draft.playlist.subtitle}</p>
                                    )}
                                    <div className="mt-4 flex flex-wrap gap-2 text-xs text-hud-text-muted">
                                        <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5">
                                            {draft.playlist.track_count}곡
                                        </span>
                                        <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5">
                                            {formatDuration(draft.playlist.duration_ms) ?? '시간 미정'}
                                        </span>
                                        <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5">
                                            {draft.playlist.model_version ?? 'model'}
                                        </span>
                                        <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5">
                                            {String(draft.playlist.score_summary.semantic_profile_status ?? 'semantic 상태 없음')}
                                        </span>
                                    </div>
                                    {candidatePreparation ? (
                                        <div className="mt-4 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary p-3">
                                            <p className="mb-2 text-xs font-semibold uppercase text-hud-text-muted">
                                                후보 준비 결과
                                            </p>
                                            <div className="flex flex-wrap gap-2">
                                                {Object.entries(candidatePreparationLabels).map(([key, label]) => {
                                                    const value = candidatePreparation[key as keyof PublicCurationCandidatePreparationSummary]
                                                    if (typeof value !== 'number') {
                                                        return null
                                                    }
                                                    return (
                                                        <span
                                                            key={key}
                                                            className="rounded-lg border border-hud-border-secondary px-3 py-1.5 text-xs text-hud-text-secondary"
                                                        >
                                                            {label} {value}
                                                        </span>
                                                    )
                                                })}
                                                {typeof candidatePreparation.resolve_success_ratio === 'number' ? (
                                                    <span className="rounded-lg border border-hud-border-secondary px-3 py-1.5 text-xs text-hud-text-secondary">
                                                        Resolve 성공률 {Math.round(candidatePreparation.resolve_success_ratio * 100)}%
                                                    </span>
                                                ) : null}
                                            </div>
                                        </div>
                                    ) : null}
                                </div>

                                <div className="space-y-3">
                                    {draft.playlist.tracks.slice(0, 8).map((track) => (
                                        <div
                                            key={track.track_id}
                                            className="grid gap-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary p-4 md:grid-cols-[48px_1fr_auto]"
                                        >
                                            <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-hud-accent-primary/15 text-sm font-bold text-hud-accent-primary">
                                                {String(track.track_order).padStart(2, '0')}
                                            </div>
                                            <div>
                                                <p className="font-semibold text-hud-text-primary">{track.title}</p>
                                                <p className="mt-1 text-sm text-hud-text-muted">
                                                    {track.artist_name}
                                                    {track.album_title ? ` · ${track.album_title}` : ''}
                                                </p>
                                                {track.reason && (
                                                    <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{track.reason}</p>
                                                )}
                                                <div className="mt-3 flex flex-wrap gap-1.5">
                                                    {scoreBreakdownEntries(track.score_breakdown).map(([axis, score]) => (
                                                        <span
                                                            key={axis}
                                                            className="rounded-lg border border-hud-border-secondary px-2 py-1 text-[11px] text-hud-text-muted"
                                                        >
                                                            {scoreAxisLabel[axis] ?? axis} {Math.round(score * 100)}
                                                        </span>
                                                    ))}
                                                </div>
                                            </div>
                                            <div className="text-left text-xs text-hud-text-muted md:text-right">
                                                <p>TIDAL {track.tidal_track_id}</p>
                                                <p className="mt-1">{Math.round(track.score * 100)}점</p>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        ) : (
                            <div className="rounded-lg border border-dashed border-hud-border-secondary bg-hud-bg-primary p-8 text-center text-sm leading-7 text-hud-text-muted">
                                모델 실행 후 생성된 제목, 트랙 순서, 추천 이유가 여기에 표시됩니다.
                            </div>
                        )}
                    </HudCard>

                    <HudCard title="발행" subtitle="초안을 공개 공유 URL로 활성화합니다.">
                        <div className="space-y-4">
                            <Button
                                type="button"
                                fullWidth
                                disabled={!draft || isPublishing || draft.playlist.status === 'published'}
                                leftIcon={isPublishing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Rocket className="h-4 w-4" />}
                                onClick={handlePublish}
                            >
                                발행하기
                            </Button>

                            {draft && shareUrl && (
                                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary p-4">
                                    <p className="mb-2 text-xs font-semibold uppercase text-hud-text-muted">공유 링크</p>
                                    <div className="flex flex-col gap-2 lg:flex-row">
                                        <input
                                            readOnly
                                            value={shareUrl}
                                            className="min-w-0 flex-1 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary px-3 py-2 text-sm text-hud-text-secondary outline-none"
                                        />
                                        <Button
                                            type="button"
                                            variant="outline"
                                            leftIcon={copiedSlug === draft.playlist.slug ? <CheckCircle2 className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                                            onClick={() => handleCopy(shareUrl, draft.playlist.slug)}
                                        >
                                            {copiedSlug === draft.playlist.slug ? '복사됨' : '복사'}
                                        </Button>
                                    </div>
                                </div>
                            )}

                            {message && (
                                <p className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-secondary">
                                    {message}
                                </p>
                            )}
                        </div>
                    </HudCard>
                </div>
            </div>

            <HudCard title="저장된 공개 큐레이션" subtitle="생성된 초안과 발행된 mix를 한 곳에서 관리합니다.">
                <div className="space-y-3">
                    {isListLoading ? (
                        <div className="flex items-center gap-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-5 text-sm text-hud-text-secondary">
                            <Loader2 className="h-4 w-4 animate-spin text-hud-accent-primary" />
                            저장된 공개 큐레이션을 불러오는 중입니다.
                        </div>
                    ) : null}

                    {!isListLoading && savedPlaylists.length === 0 ? (
                        <div className="rounded-lg border border-dashed border-hud-border-secondary bg-hud-bg-primary p-6 text-sm leading-7 text-hud-text-muted">
                            아직 저장된 공개 큐레이션이 없습니다. 모델 실행을 완료하면 초안이 이 목록에 저장됩니다.
                        </div>
                    ) : null}

                    {savedPlaylists.map((playlist) => {
                        const isPublished = playlist.status === 'published'
                        const nextShareUrl = publicMixUrl(playlist.slug)
                        return (
                            <div
                                key={playlist.playlist_id}
                                className="grid gap-4 rounded-lg border border-hud-border-secondary bg-hud-bg-primary p-4 lg:grid-cols-[minmax(0,1fr)_auto]"
                            >
                                <div className="min-w-0">
                                    <div className="flex flex-wrap items-center gap-2">
                                        <span className="rounded-lg border border-hud-border-secondary px-2.5 py-1 text-xs font-semibold text-hud-text-muted">
                                            {statusLabel(playlist.status)}
                                        </span>
                                        <span className="rounded-lg border border-hud-border-secondary px-2.5 py-1 text-xs font-semibold text-hud-text-muted">
                                            {playlist.track_count}곡
                                        </span>
                                        <span className="rounded-lg border border-hud-border-secondary px-2.5 py-1 text-xs font-semibold text-hud-text-muted">
                                            {formatDuration(playlist.duration_ms) ?? '시간 미정'}
                                        </span>
                                    </div>
                                    <h2 className="mt-3 truncate text-lg font-bold text-hud-text-primary">{playlist.title}</h2>
                                    {playlist.subtitle ? (
                                        <p className="mt-1 truncate text-sm text-hud-text-secondary">{playlist.subtitle}</p>
                                    ) : null}
                                    <div className="mt-3 grid gap-2 text-xs text-hud-text-muted md:grid-cols-2">
                                        <p className="truncate">공개 코드: {playlist.slug}</p>
                                        <p>발행일: {formatDateTime(playlist.published_at)}</p>
                                        <p>생성일: {formatDateTime(playlist.created_at)}</p>
                                        <p className="truncate">{playlist.model_version ?? 'model version 없음'}</p>
                                    </div>
                                    {isPublished ? (
                                        <input
                                            readOnly
                                            value={nextShareUrl}
                                            className="mt-3 w-full rounded-lg border border-hud-border-secondary bg-hud-bg-secondary px-3 py-2 text-xs text-hud-text-secondary outline-none"
                                        />
                                    ) : null}
                                </div>
                                <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                                    <Button
                                        type="button"
                                        variant="danger"
                                        disabled={deletingPlaylistId === playlist.playlist_id}
                                        leftIcon={
                                            deletingPlaylistId === playlist.playlist_id
                                                ? <Loader2 className="h-4 w-4 animate-spin" />
                                                : <Trash2 className="h-4 w-4" />
                                        }
                                        onClick={() => {
                                            setDeleteConfirmPlaylist(playlist)
                                        }}
                                    >
                                        삭제
                                    </Button>
                                    {isPublished ? (
                                        <>
                                            <Button
                                                type="button"
                                                variant="outline"
                                                leftIcon={copiedSlug === playlist.slug ? <CheckCircle2 className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                                                onClick={() => handleCopy(nextShareUrl, playlist.slug)}
                                            >
                                                {copiedSlug === playlist.slug ? '복사됨' : '링크 복사'}
                                            </Button>
                                            <Link
                                                to={publicMixPath(playlist.slug)}
                                                className="inline-flex items-center justify-center gap-2 rounded-lg border border-hud-border-primary px-4 py-2 text-sm font-semibold text-hud-text-secondary transition-hud hover:border-hud-accent-primary hover:text-hud-accent-primary"
                                            >
                                                <ExternalLink className="h-4 w-4" />
                                                열기
                                            </Link>
                                        </>
                                    ) : (
                                        <Button
                                            type="button"
                                            variant="outline"
                                            disabled={isPublishing}
                                            leftIcon={isPublishing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Rocket className="h-4 w-4" />}
                                            onClick={() => handlePublishFromList(playlist.playlist_id)}
                                        >
                                            발행하기
                                        </Button>
                                    )}
                                </div>
                            </div>
                        )
                    })}
                </div>
            </HudCard>

            {deleteConfirmPlaylist ? (
                <div
                    className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm"
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="public-curation-delete-title"
                >
                    <div className="w-full max-w-md rounded-lg border border-hud-border-primary bg-hud-bg-secondary p-5 shadow-2xl shadow-black/45">
                        <div className="flex items-start gap-4">
                            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-hud-accent-danger/15 text-hud-accent-danger">
                                <Trash2 className="h-5 w-5" />
                            </div>
                            <div className="min-w-0">
                                <p className="text-xs font-bold uppercase tracking-wide text-hud-accent-danger">
                                    삭제 확인
                                </p>
                                <h2 id="public-curation-delete-title" className="mt-2 text-xl font-bold leading-8 text-hud-text-primary">
                                    {deleteConfirmPlaylist.title}
                                </h2>
                                <p className="mt-3 text-sm leading-7 text-hud-text-secondary">
                                    이 공유 플레이리스트를 삭제할까요? 삭제하면 발행된 공유 링크도 더 이상 열리지 않습니다.
                                </p>
                                <div className="mt-4 grid gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-primary p-3 text-xs text-hud-text-muted">
                                    <p>공개 코드: {deleteConfirmPlaylist.slug}</p>
                                    <p>{deleteConfirmPlaylist.track_count}곡 · {formatDuration(deleteConfirmPlaylist.duration_ms) ?? '시간 미정'}</p>
                                </div>
                            </div>
                        </div>

                        <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                            <Button
                                type="button"
                                variant="ghost"
                                disabled={deletingPlaylistId === deleteConfirmPlaylist.playlist_id}
                                onClick={() => setDeleteConfirmPlaylist(null)}
                            >
                                취소
                            </Button>
                            <Button
                                type="button"
                                variant="danger"
                                disabled={deletingPlaylistId === deleteConfirmPlaylist.playlist_id}
                                leftIcon={
                                    deletingPlaylistId === deleteConfirmPlaylist.playlist_id
                                        ? <Loader2 className="h-4 w-4 animate-spin" />
                                        : <Trash2 className="h-4 w-4" />
                                }
                                onClick={() => {
                                    void handleConfirmDelete()
                                }}
                            >
                                삭제
                            </Button>
                        </div>
                    </div>
                </div>
            ) : null}
        </div>
    )
}

export default PublicCurationAdminPage
