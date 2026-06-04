import { useMemo, useState, type FormEvent } from 'react'
import { ListMusic, Music2, Play, RefreshCw, Sparkles, Wand2 } from 'lucide-react'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import PageExplanation from '@/components/common/PageExplanation'
import TrackFeatureCard from '@/components/music/TrackFeatureCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { buildArtistDetailPath } from '@/lib/artistLinks'
import type { PlaybackMediaItem } from '@/lib/musicPlayback'
import { ApiError, previewGmsRecommendations } from '@/services/api'
import type { GmsRecommendationPreviewRequest, GmsRecommendationPreviewResponse } from '@/types/api'

type MoodValue = NonNullable<GmsRecommendationPreviewRequest['mood']>
type PreviewItem = GmsRecommendationPreviewResponse['items'][number]

const moodOptions: Array<{
    value: MoodValue
    label: string
    description: string
}> = [
    { value: 'focus', label: '집중', description: '일이나 생각을 이어가기 좋은 흐름' },
    { value: 'calm', label: '차분', description: '긴장을 낮추고 오래 듣기 좋은 곡' },
    { value: 'upbeat', label: '신남', description: '기분을 끌어올리는 밝은 에너지' },
    { value: 'melancholy', label: '쓸쓸함', description: '감정을 깊게 눌러주는 선곡' },
    { value: 'discovery', label: '새 발견', description: '익숙함에서 조금 벗어난 추천' },
]

const uniqueValues = (values: string[]) => {
    const seen = new Set<string>()
    return values.filter((value) => {
        const key = value.trim().toLowerCase()
        if (!key || seen.has(key)) {
            return false
        }
        seen.add(key)
        return true
    })
}

const splitExampleSongs = (value: string) =>
    uniqueValues(
        value
            .split('\n')
            .map((line) => line.trim())
            .filter(Boolean),
    ).slice(0, 8)

const artistFromExampleSong = (value: string) => {
    const match = value.match(/^.+?\s*[-–—]\s*(.+)$/)
    return match?.[1]?.trim() ?? ''
}

const toPlaybackItem = (item: PreviewItem): PlaybackMediaItem => ({
    id: `mood:${item.track_id}`,
    kind: 'track',
    title: item.title,
    subtitle: `${item.artist_name} · ${item.source_platform}`,
    sourcePlatform: item.source_platform,
    imageUrl: item.album_image_url,
    albumTitle: item.album_title,
    externalUrl: item.platform_external_url,
    platformUri: item.platform_uri,
    previewUrl: item.preview_url,
    spotifyTrackId: item.audio_feature_track_id ?? item.spotify_track_id,
    durationMs: item.duration_ms,
    supportingText: item.source_playlist_title ?? '기분 기반 추천',
})

const MoodRecommendationPage = () => {
    const { session } = useAuthSession()
    const { playItem, playQueue } = usePlayback()
    const [mood, setMood] = useState<MoodValue>('calm')
    const [energyLevel, setEnergyLevel] = useState(3)
    const [limit, setLimit] = useState(20)
    const [exampleSongText, setExampleSongText] = useState('')
    const [response, setResponse] = useState<GmsRecommendationPreviewResponse | null>(null)
    const [isSubmitting, setIsSubmitting] = useState(false)
    const [message, setMessage] = useState<string | null>(null)

    const exampleSongs = useMemo(() => splitExampleSongs(exampleSongText), [exampleSongText])
    const seedArtists = useMemo(
        () => uniqueValues(exampleSongs.map(artistFromExampleSong).filter(Boolean)),
        [exampleSongs],
    )
    const playbackItems = useMemo(() => response?.items.map(toPlaybackItem) ?? [], [response])
    const selectedMood = moodOptions.find((option) => option.value === mood) ?? moodOptions[1]

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault()
        if (!session) {
            setMessage('추천을 받으려면 먼저 로그인하세요.')
            return
        }

        setIsSubmitting(true)
        setMessage(null)

        try {
            const preview = await previewGmsRecommendations({
                request_id: `mood-recommendation-${Date.now()}`,
                user_id: session.userId,
                mood,
                energy_level: energyLevel,
                familiarity_bias: mood === 'discovery' ? 2 : 3,
                limit,
                seed_artist_names: seedArtists,
                include_explanations: false,
            })
            setResponse(preview)
        } catch (error: unknown) {
            setMessage(
                error instanceof ApiError
                    ? error.message
                    : '지금은 추천 결과를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.',
            )
        } finally {
            setIsSubmitting(false)
        }
    }

    return (
        <div className="space-y-6">
            <PageExplanation
                eyebrow="개인 추천"
                title="지금 내 기분은..."
                body="오늘 듣고 싶은 기분과 예시 곡 몇 개를 알려주면, 내 PMS 보관함과 GMS 후보를 기준으로 바로 들을 추천곡을 고릅니다."
                flow="기분 선택 -> 예시 곡 입력 -> 추천받기 -> 바로 재생"
            />

            <section className="grid gap-6 xl:grid-cols-[0.9fr_1.1fr]">
                <HudCard title="추천 기준 입력" subtitle="예시 곡은 한 줄에 하나씩 적어 주세요. `곡명 - 아티스트` 형식이면 아티스트 취향 신호로 반영됩니다.">
                    <form className="space-y-6" onSubmit={handleSubmit}>
                        <div>
                            <p className="mb-3 text-sm font-medium text-hud-text-secondary">기분</p>
                            <div className="grid gap-3 sm:grid-cols-2">
                                {moodOptions.map((option) => {
                                    const active = option.value === mood
                                    return (
                                        <button
                                            key={option.value}
                                            type="button"
                                            onClick={() => setMood(option.value)}
                                            className={`rounded-2xl border p-4 text-left transition-hud ${
                                                active
                                                    ? 'border-hud-border-primary bg-hud-accent-primary/12 text-hud-text-primary'
                                                    : 'border-hud-border-secondary bg-hud-bg-primary/70 text-hud-text-secondary hover:border-hud-border-primary'
                                            }`}
                                        >
                                            <span className="inline-flex items-center gap-2 text-sm font-semibold">
                                                <Sparkles size={16} className={active ? 'text-hud-accent-primary' : 'text-hud-text-muted'} />
                                                {option.label}
                                            </span>
                                            <span className="mt-2 block text-xs leading-5 text-hud-text-muted">{option.description}</span>
                                        </button>
                                    )
                                })}
                            </div>
                        </div>

                        <label className="block">
                            <span className="mb-2 block text-sm font-medium text-hud-text-secondary">예시 곡</span>
                            <textarea
                                value={exampleSongText}
                                onChange={(event) => setExampleSongText(event.target.value)}
                                rows={6}
                                placeholder={'Billie Jean - Michael Jackson\nLike Crazy - Jimin\nDitto - NewJeans'}
                                className="w-full resize-y rounded-2xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm leading-6 text-hud-text-primary outline-none transition-hud placeholder:text-hud-text-muted focus:border-hud-border-primary"
                            />
                            <span className="mt-2 block text-xs leading-5 text-hud-text-muted">
                                최대 8곡까지 참고합니다. 곡명이 정확히 매칭되지 않아도 아티스트 신호가 추천 방향을 잡습니다.
                            </span>
                        </label>

                        <div className="grid gap-4 sm:grid-cols-2">
                            <label className="block">
                                <span className="mb-2 block text-sm font-medium text-hud-text-secondary">에너지</span>
                                <input
                                    type="range"
                                    min="1"
                                    max="5"
                                    value={energyLevel}
                                    onChange={(event) => setEnergyLevel(Number(event.target.value))}
                                    className="w-full accent-hud-accent-primary"
                                />
                                <span className="mt-2 block text-xs text-hud-text-muted">{energyLevel} / 5</span>
                            </label>

                            <label className="block">
                                <span className="mb-2 block text-sm font-medium text-hud-text-secondary">추천 개수</span>
                                <select
                                    value={limit}
                                    onChange={(event) => setLimit(Number(event.target.value))}
                                    className="w-full rounded-xl border border-hud-border-secondary bg-hud-bg-primary px-4 py-3 text-sm text-hud-text-primary outline-none transition-hud focus:border-hud-border-primary"
                                >
                                    <option value={10}>10곡</option>
                                    <option value={15}>15곡</option>
                                    <option value={20}>20곡</option>
                                </select>
                            </label>
                        </div>

                        {message && (
                            <div className="rounded-2xl border border-hud-accent-danger/40 bg-hud-accent-danger/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {message}
                            </div>
                        )}

                        <Button type="submit" variant="primary" glow fullWidth disabled={isSubmitting || !session}>
                            {isSubmitting ? (
                                <>
                                    <RefreshCw size={18} className="animate-spin" />
                                    추천을 고르는 중
                                </>
                            ) : (
                                <>
                                    <Wand2 size={18} />
                                    추천받기
                                </>
                            )}
                        </Button>
                    </form>
                </HudCard>

                <div className="space-y-6">
                    <HudCard title="현재 요청 감각" subtitle="사용자가 입력한 기분과 예시 곡이 추천 요청으로 정리됩니다.">
                        <div className="grid gap-4 md:grid-cols-3">
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">기분</p>
                                <p className="mt-2 text-lg font-semibold text-hud-text-primary">{selectedMood.label}</p>
                                <p className="mt-2 text-xs leading-5 text-hud-text-muted">{selectedMood.description}</p>
                            </div>
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">예시 곡</p>
                                <p className="mt-2 text-lg font-semibold text-hud-text-primary">{exampleSongs.length}곡</p>
                                <p className="mt-2 text-xs leading-5 text-hud-text-muted">아티스트 seed {seedArtists.length}개</p>
                            </div>
                            <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">출력</p>
                                <p className="mt-2 text-lg font-semibold text-hud-text-primary">{limit}곡</p>
                                <p className="mt-2 text-xs leading-5 text-hud-text-muted">GMS preview 기반</p>
                            </div>
                        </div>

                        {exampleSongs.length > 0 && (
                            <div className="mt-5 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <p className="text-xs font-semibold text-hud-text-secondary">입력한 예시 곡</p>
                                <div className="mt-3 flex flex-wrap gap-2">
                                    {exampleSongs.map((example) => (
                                        <span key={example} className="rounded-full border border-hud-border-secondary px-3 py-1 text-xs text-hud-text-muted">
                                            {example}
                                        </span>
                                    ))}
                                </div>
                            </div>
                        )}
                    </HudCard>

                    <HudCard title="바로 듣기" subtitle="추천을 받은 뒤 한 곡씩 듣거나 전체 추천 결과를 큐로 재생합니다.">
                        {response ? (
                            <div className="space-y-4">
                                <div className="flex flex-col gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4 sm:flex-row sm:items-center sm:justify-between">
                                    <div>
                                        <p className="text-sm font-semibold text-hud-text-primary">{response.items.length}곡이 준비됐습니다.</p>
                                        <p className="mt-1 text-xs leading-5 text-hud-text-muted">
                                            {new Date(response.generated_at).toLocaleString()} 생성
                                        </p>
                                    </div>
                                    <Button
                                        type="button"
                                        variant="primary"
                                        disabled={playbackItems.length === 0}
                                        onClick={() => {
                                            void playQueue(playbackItems, 0)
                                        }}
                                    >
                                        <Play size={18} />
                                        전체 재생
                                    </Button>
                                </div>

                                {response.warnings.length > 0 && (
                                    <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                        {response.warnings[0]}
                                    </div>
                                )}
                            </div>
                        ) : (
                            <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-6 text-sm leading-6 text-hud-text-secondary">
                                <Music2 size={24} className="mb-3 text-hud-accent-primary" />
                                아직 추천을 요청하지 않았습니다. 기분과 예시 곡을 입력하면 결과가 여기에 준비됩니다.
                            </div>
                        )}
                    </HudCard>
                </div>
            </section>

            <HudCard
                title="추천곡"
                subtitle={response ? '지금 선택한 기분과 예시 곡 흐름에 맞춰 정렬된 곡입니다.' : '추천받기를 누르면 곡 카드가 표시됩니다.'}
            >
                {response ? (
                    <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
                        {response.items.map((item) => (
                            <TrackFeatureCard
                                key={item.track_id}
                                title={item.title}
                                artistName={item.artist_name}
                                sourcePlatform={item.source_platform}
                                albumTitle={item.album_title}
                                imageUrl={item.album_image_url}
                                durationMs={item.duration_ms}
                                artistDetailPath={buildArtistDetailPath(item.artist_name)}
                                badges={[`#${item.rank}`, `에너지 ${item.energy_level}`, item.source_playlist_title ? 'PMS 연결' : item.source_space]}
                                onPlay={() => {
                                    void playItem(toPlaybackItem(item))
                                }}
                                onOpenExternal={
                                    item.platform_external_url
                                        ? () => window.open(item.platform_external_url ?? undefined, '_blank', 'noopener,noreferrer')
                                        : undefined
                                }
                            />
                        ))}
                    </div>
                ) : (
                    <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-8 text-center text-sm leading-6 text-hud-text-secondary">
                        <ListMusic size={28} className="mx-auto mb-3 text-hud-accent-primary" />
                        지금의 기분에 맞는 추천 목록이 이곳에 쌓입니다.
                    </div>
                )}
            </HudCard>
        </div>
    )
}

export default MoodRecommendationPage
