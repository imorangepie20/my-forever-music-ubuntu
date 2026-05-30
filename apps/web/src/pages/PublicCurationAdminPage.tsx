import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { CheckCircle2, Copy, ExternalLink, Loader2, Rocket, Sparkles, Wand2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { formatDuration } from '@/lib/musicPlayback'
import {
    ApiError,
    createPublicCurationDraft,
    fetchPublicCurationAdminPlaylists,
    publishPublicCurationPlaylist,
} from '@/services/api'
import type {
    PublicCurationAdminPlaylistSummary,
    PublicCurationAdminRunRequest,
    PublicCurationAdminRunResponse,
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
    const [message, setMessage] = useState<string | null>(null)
    const [copiedSlug, setCopiedSlug] = useState<string | null>(null)

    const shareUrl = useMemo(() => {
        if (!draft?.playlist.slug || draft.playlist.status !== 'published') {
            return ''
        }
        return publicMixUrl(draft.playlist.slug)
    }, [draft])

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
                        </label>

                        <label className="block">
                            <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">공개 코드</span>
                            <input
                                value={slug}
                                onChange={(event) => setSlug(toSlug(event.target.value))}
                                className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                        </label>

                        <label className="block">
                            <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">프롬프트</span>
                            <textarea
                                value={prompt}
                                onChange={(event) => setPrompt(event.target.value)}
                                rows={5}
                                className="w-full resize-none rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm leading-7 text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                            />
                        </label>

                        <div className="grid gap-4 md:grid-cols-2">
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">무드 태그</span>
                                <input
                                    value={moodTags}
                                    onChange={(event) => setMoodTags(event.target.value)}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                            </label>
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">장르 태그</span>
                                <input
                                    value={genreTags}
                                    onChange={(event) => setGenreTags(event.target.value)}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
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
                            </label>
                            <label className="block">
                                <span className="mb-2 block text-sm font-semibold text-hud-text-secondary">후보 풀 크기</span>
                                <input
                                    type="number"
                                    min={10}
                                    max={500}
                                    value={candidateLimit}
                                    onChange={(event) => setCandidateLimit(Number(event.target.value))}
                                    className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                />
                            </label>
                        </div>

                        <div className="grid gap-4 md:grid-cols-4">
                            {[
                                ['energy min', energyMin, setEnergyMin],
                                ['energy max', energyMax, setEnergyMax],
                                ['valence min', valenceMin, setValenceMin],
                                ['valence max', valenceMax, setValenceMax],
                            ].map(([label, value, setter]) => (
                                <label key={label as string} className="block">
                                    <span className="mb-2 block text-xs font-semibold uppercase text-hud-text-muted">
                                        {label as string}
                                    </span>
                                    <input
                                        value={value as string}
                                        onChange={(event) => (setter as (next: string) => void)(event.target.value)}
                                        className="w-full rounded-lg border border-hud-border-secondary bg-hud-bg-primary px-3 py-2 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-accent-primary"
                                    />
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
                                    </div>
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
        </div>
    )
}

export default PublicCurationAdminPage
