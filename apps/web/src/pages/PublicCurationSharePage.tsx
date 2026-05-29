import { useEffect, useMemo, useState } from 'react'
import { AlertCircle, Clock3, Disc3, Headphones, Loader2, Music2, Play, Sparkles } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { formatDuration } from '@/lib/musicPlayback'
import { ApiError, fetchPublicCurationShare } from '@/services/api'
import type { PublicCurationShareResponse, PublicCurationShareTrack } from '@/types/api'

const formatTotalDuration = (durationMs: number | null | undefined) => {
    if (!durationMs || durationMs <= 0) {
        return '재생 시간 미정'
    }
    const totalMinutes = Math.round(durationMs / 60000)
    const hours = Math.floor(totalMinutes / 60)
    const minutes = totalMinutes % 60
    if (hours <= 0) {
        return `${minutes}분`
    }
    return `${hours}시간 ${minutes}분`
}

const trackInitials = (track: PublicCurationShareTrack) =>
    `${track.title.trim().slice(0, 1)}${track.artist_name.trim().slice(0, 1)}`.toUpperCase()

const scoreLabel = (score: number) =>
    Number.isFinite(score) ? `${Math.round(score * 100)}점` : '평가 없음'

const PublicCurationSharePage = () => {
    const { slug = '' } = useParams()
    const [payload, setPayload] = useState<PublicCurationShareResponse | null>(null)
    const [isLoading, setIsLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()
        setIsLoading(true)
        setError(null)

        fetchPublicCurationShare(slug, controller.signal)
            .then((nextPayload) => {
                setPayload(nextPayload)
            })
            .catch((nextError: unknown) => {
                if (controller.signal.aborted) {
                    return
                }
                if (nextError instanceof ApiError && nextError.status === 404) {
                    setError('공개된 플레이리스트를 찾을 수 없습니다.')
                    return
                }
                setError(nextError instanceof Error ? nextError.message : '공개 큐레이션을 불러오지 못했습니다.')
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setIsLoading(false)
                }
            })

        return () => controller.abort()
    }, [slug])

    const playlist = payload?.playlist ?? null
    const highlights = useMemo(() => playlist?.tracks.slice(0, 3) ?? [], [playlist])
    const totalDuration = formatTotalDuration(playlist?.duration_ms)

    if (isLoading) {
        return (
            <main className="min-h-screen bg-[#090b12] text-white">
                <div className="flex min-h-screen items-center justify-center px-6">
                    <div className="flex items-center gap-3 text-sm text-white/70">
                        <Loader2 className="h-5 w-5 animate-spin text-cyan-300" />
                        공개 큐레이션을 여는 중
                    </div>
                </div>
            </main>
        )
    }

    if (error || !playlist) {
        return (
            <main className="min-h-screen bg-[#090b12] text-white">
                <section className="mx-auto flex min-h-screen max-w-3xl flex-col items-center justify-center px-6 text-center">
                    <AlertCircle className="mb-5 h-10 w-10 text-rose-300" />
                    <p className="mb-3 text-xs font-semibold uppercase text-cyan-200">Public Curation</p>
                    <h1 className="mb-4 text-3xl font-bold">공개 큐레이션을 열 수 없습니다</h1>
                    <p className="mb-8 text-sm leading-7 text-white/70">{error}</p>
                    <Link
                        to="/"
                        className="inline-flex items-center justify-center rounded-lg border border-white/20 px-5 py-3 text-sm font-semibold text-white transition hover:border-cyan-300 hover:text-cyan-200"
                    >
                        My Forever Music으로 이동
                    </Link>
                </section>
            </main>
        )
    }

    return (
        <main className="min-h-screen bg-[#090b12] text-white">
            <section className="relative min-h-[92vh] overflow-hidden border-b border-white/10">
                <div className="absolute inset-0 bg-[radial-gradient(circle_at_16%_18%,rgba(45,212,191,0.28),transparent_28%),radial-gradient(circle_at_80%_12%,rgba(244,114,182,0.24),transparent_28%),linear-gradient(135deg,#111827_0%,#090b12_48%,#161027_100%)]" />
                <div className="absolute inset-x-0 bottom-0 h-56 bg-gradient-to-t from-[#090b12] to-transparent" />
                <div className="relative mx-auto grid min-h-[92vh] max-w-7xl grid-cols-1 gap-10 px-6 py-8 lg:grid-cols-[1.1fr_0.9fr] lg:px-10">
                    <div className="flex flex-col justify-between">
                        <header className="flex items-center justify-between gap-4">
                            <Link to="/" className="flex items-center gap-3">
                                <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-cyan-300 text-sm font-black text-slate-950">
                                    FM
                                </span>
                                <span>
                                    <span className="block text-xs font-bold uppercase text-cyan-200">My Forever Music</span>
                                    <span className="block text-sm font-semibold text-white/80">공개 큐레이션</span>
                                </span>
                            </Link>
                            <span className="rounded-lg border border-white/15 px-3 py-2 text-xs font-semibold text-white/70">
                                TIDAL-ready
                            </span>
                        </header>

                        <div className="max-w-3xl py-16 lg:py-20">
                            <p className="mb-5 inline-flex items-center gap-2 rounded-lg border border-cyan-300/30 bg-cyan-300/10 px-3 py-2 text-xs font-semibold uppercase text-cyan-100">
                                <Sparkles className="h-4 w-4" />
                                Public Curation
                            </p>
                            <h1 className="text-5xl font-black leading-tight text-white md:text-7xl">
                                {playlist.title}
                            </h1>
                            {playlist.subtitle && (
                                <p className="mt-6 max-w-2xl text-xl font-semibold leading-8 text-white/82">
                                    {playlist.subtitle}
                                </p>
                            )}
                            {playlist.description && (
                                <p className="mt-5 max-w-2xl text-base leading-8 text-white/68">
                                    {playlist.description}
                                </p>
                            )}

                            <div className="mt-8 flex flex-wrap gap-3">
                                <button
                                    type="button"
                                    disabled
                                    className="inline-flex cursor-not-allowed items-center justify-center gap-2 rounded-lg bg-cyan-300 px-5 py-3 text-sm font-black text-slate-950 opacity-70"
                                    title="공개 TIDAL 재생 세션 연결 후 활성화됩니다."
                                >
                                    <Play className="h-4 w-4 fill-current" />
                                    TIDAL로 여기서 듣기
                                </button>
                                <a
                                    href="#public-track-list"
                                    className="inline-flex items-center justify-center gap-2 rounded-lg border border-white/18 px-5 py-3 text-sm font-semibold text-white transition hover:border-cyan-300 hover:text-cyan-200"
                                >
                                    <Music2 className="h-4 w-4" />
                                    전체 곡 보기
                                </a>
                            </div>
                        </div>

                        <dl className="grid max-w-2xl grid-cols-3 gap-3 pb-8">
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Disc3 className="h-4 w-4" />
                                    트랙
                                </dt>
                                <dd className="text-2xl font-black">{playlist.track_count}</dd>
                            </div>
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Clock3 className="h-4 w-4" />
                                    길이
                                </dt>
                                <dd className="text-2xl font-black">{totalDuration}</dd>
                            </div>
                            <div className="rounded-lg border border-white/12 bg-white/[0.04] p-4">
                                <dt className="mb-2 flex items-center gap-2 text-xs text-white/50">
                                    <Headphones className="h-4 w-4" />
                                    재생
                                </dt>
                                <dd className="text-2xl font-black">TIDAL</dd>
                            </div>
                        </dl>
                    </div>

                    <aside className="flex items-center justify-center py-10 lg:py-0">
                        <div className="relative aspect-[4/5] w-full max-w-md rounded-lg border border-white/15 bg-black/30 p-4 shadow-2xl shadow-black/50">
                            <div className="grid h-full grid-cols-2 grid-rows-3 gap-3">
                                {highlights.map((track, index) => (
                                    <div
                                        key={track.track_id}
                                        className={`relative overflow-hidden rounded-lg border border-white/12 p-4 ${
                                            index === 0 ? 'col-span-2 bg-cyan-300/18' : 'bg-fuchsia-300/14'
                                        }`}
                                    >
                                        {track.image_url ? (
                                            <img
                                                src={track.image_url}
                                                alt=""
                                                className="absolute inset-0 h-full w-full object-cover opacity-45"
                                            />
                                        ) : (
                                            <div className="absolute inset-0 bg-[linear-gradient(135deg,rgba(45,212,191,0.38),rgba(244,114,182,0.22),rgba(250,204,21,0.16))]" />
                                        )}
                                        <div className="relative flex h-full flex-col justify-end">
                                            <p className="text-4xl font-black">{trackInitials(track)}</p>
                                            <p className="mt-2 text-xs font-semibold uppercase text-white/68">
                                                My Forever Music
                                            </p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </aside>
                </div>
            </section>

            <section id="public-track-list" className="mx-auto max-w-7xl px-6 py-14 lg:px-10">
                <div className="mb-8 flex flex-col justify-between gap-4 md:flex-row md:items-end">
                    <div>
                        <p className="mb-2 text-xs font-bold uppercase text-cyan-200">Track List</p>
                        <h2 className="text-3xl font-black">모델이 고른 흐름</h2>
                    </div>
                    <p className="max-w-xl text-sm leading-7 text-white/58">
                        각 곡은 TIDAL 재생 준비 상태와 테마 적합도, 흐름, 다양성 기준을 통과한 후보입니다.
                    </p>
                </div>

                <div className="grid gap-3">
                    {playlist.tracks.map((track) => (
                        <article
                            key={track.track_id}
                            className="grid gap-4 rounded-lg border border-white/10 bg-white/[0.04] p-4 md:grid-cols-[64px_1fr_auto] md:items-center"
                        >
                            <div className="flex h-16 w-16 items-center justify-center overflow-hidden rounded-lg border border-white/10 bg-cyan-300/12">
                                {track.image_url ? (
                                    <img src={track.image_url} alt="" className="h-full w-full object-cover" />
                                ) : (
                                    <span className="text-lg font-black text-cyan-100">{String(track.track_order).padStart(2, '0')}</span>
                                )}
                            </div>
                            <div>
                                <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-white/48">
                                    <span>#{track.track_order}</span>
                                    <span>{formatDuration(track.duration_ms) ?? '--:--'}</span>
                                    <span>{scoreLabel(track.score)}</span>
                                </div>
                                <h3 className="text-lg font-black text-white">{track.title}</h3>
                                <p className="mt-1 text-sm text-white/62">
                                    {track.artist_name}
                                    {track.album_title ? ` · ${track.album_title}` : ''}
                                </p>
                                {track.reason && (
                                    <p className="mt-3 text-sm leading-7 text-white/68">
                                        <span className="font-semibold text-cyan-200">추천 이유</span> {track.reason}
                                    </p>
                                )}
                            </div>
                            <div className="flex flex-wrap items-center gap-2 md:justify-end">
                                <span className="rounded-lg border border-white/12 px-3 py-2 text-xs font-semibold text-white/60">
                                    TIDAL {track.tidal_track_id}
                                </span>
                            </div>
                        </article>
                    ))}
                </div>
            </section>
        </main>
    )
}

export default PublicCurationSharePage
