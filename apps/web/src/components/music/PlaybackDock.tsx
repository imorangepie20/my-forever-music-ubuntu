import {
    AudioLines,
    ExternalLink,
    Heart,
    ListMusic,
    Loader2,
    Maximize2,
    Pause,
    Play,
    Repeat,
    Repeat1,
    Shuffle,
    SkipBack,
    SkipForward,
    Volume2,
    X,
} from 'lucide-react'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/common/Button'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { usePlayback } from '@/contexts/PlaybackContext'
import { useTrackLike } from '@/hooks/useTrackLike'
import { formatDuration, resolvePlaybackPlatformId, resolveYouTubeVideoId } from '@/lib/musicPlayback'
import { setYouTubePlayerHost } from '@/lib/youtubePlayback'

interface PlaybackDockProps {
    sidebarCollapsed?: boolean
}

interface ControlButtonProps {
    children: ReactNode
    label: string
    active?: boolean
    disabled?: boolean
    primary?: boolean
    onClick: () => void
}

const qualityTone = (label: string | null) => {
    const normalized = label?.toLowerCase() ?? ''
    if (normalized.includes('lossless') || normalized.includes('flac') || normalized.includes('master')) {
        return 'text-hud-accent-primary'
    }
    if (normalized.includes('spotify')) {
        return 'text-hud-accent-success'
    }
    return 'text-hud-text-secondary'
}

const formatQualityParts = (label: string | null, platformId?: string | null) => {
    if (!label) {
        return {
            title: '확인 중',
            detail: '스트림 품질',
        }
    }

    const parts = label.split(' · ').map((part) => part.trim()).filter(Boolean)
    if (parts.length <= 1) {
        return {
            title: parts[0] ?? platformId?.toUpperCase() ?? 'Quality',
            detail: platformId ? `${platformId.toUpperCase()} 스트림` : '플랫폼 스트림',
        }
    }

    return {
        title: parts[0],
        detail: parts.slice(1).join(' / '),
    }
}

const repeatShortLabel = (repeatMode: string) =>
    repeatMode === 'one' ? '1곡' : repeatMode === 'all' ? '전체' : '끔'

const ControlButton = ({ children, label, active = false, disabled = false, primary = false, onClick }: ControlButtonProps) => (
    <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        className={`relative flex items-center justify-center rounded-full border transition-hud disabled:cursor-not-allowed disabled:opacity-50 ${primary
            ? 'h-12 w-12 border-hud-accent-primary/50 bg-hud-accent-primary text-hud-bg-primary shadow-hud-glow hover:bg-hud-accent-primary/90'
            : active
                ? 'h-10 w-10 border-hud-accent-primary/50 bg-hud-accent-primary/10 text-hud-accent-primary'
                : 'h-10 w-10 border-hud-border-secondary bg-white/[0.02] text-hud-text-secondary hover:border-hud-border-primary hover:bg-white/[0.05] hover:text-hud-text-primary'
            }`}
        aria-label={label}
        title={label}
    >
        {children}
        {active && !primary && <span className="absolute -bottom-1 h-1 w-1 rounded-full bg-hud-accent-primary" />}
    </button>
)

const PlaybackDock = ({ sidebarCollapsed = false }: PlaybackDockProps) => {
    const {
        currentItem,
        queue,
        currentIndex,
        isPlaying,
        isLoading,
        error,
        notice,
        positionMs,
        durationMs,
        volume,
        shuffleEnabled,
        repeatMode,
        audioQualityLabel,
        pause,
        resume,
        skipNext,
        skipPrevious,
        seek,
        setVolume,
        toggleShuffle,
        cycleRepeatMode,
        clearItem,
    } = usePlayback()
    const navigate = useNavigate()
    const { session } = useAuthSession()
    const [youtubePipOpen, setYoutubePipOpen] = useState(false)
    const youtubePlayerHostCallbackRef = useCallback((element: HTMLDivElement | null) => {
        setYouTubePlayerHost(element)
    }, [])
    const playbackPlatformId = currentItem ? resolvePlaybackPlatformId(currentItem) : null
    const youtubeVideoId = currentItem ? resolveYouTubeVideoId(currentItem) : null
    const isYouTubeActive = playbackPlatformId === 'youtube' && Boolean(youtubeVideoId)

    useEffect(() => {
        if (!isYouTubeActive) {
            setYoutubePipOpen(false)
        }
    }, [isYouTubeActive])

    const likeIdentity = {
        userId: session?.userId ?? null,
        sourcePlatform: currentItem?.sourcePlatform ?? null,
        externalTrackId: currentItem?.externalTrackId ?? currentItem?.id ?? null,
    }
    const likeController = useTrackLike(likeIdentity, {
        title: currentItem?.title,
        artistName: currentItem?.subtitle?.split(' · ')[0] ?? currentItem?.subtitle ?? null,
        albumTitle: currentItem?.albumTitle ?? null,
        imageUrl: currentItem?.imageUrl ?? null,
        spotifyTrackId: currentItem?.spotifyTrackId ?? null,
        platformExternalUrl: currentItem?.externalUrl ?? null,
    })

    if (!currentItem) {
        return null
    }

    const totalDuration = durationMs || currentItem.durationMs || 0
    const progressValue = totalDuration > 0 ? Math.min(positionMs, totalDuration) : 0
    const repeatLabel = repeatMode === 'one' ? '현재 곡 반복' : repeatMode === 'all' ? '재생목록 반복' : '반복 끄기'
    const RepeatIcon = repeatMode === 'one' ? Repeat1 : Repeat
    const qualityParts = formatQualityParts(audioQualityLabel, playbackPlatformId)
    const qualityClassName = qualityTone(audioQualityLabel)
    const subtitleParts = currentItem.subtitle?.split(' · ').map((part) => part.trim()).filter(Boolean) ?? []
    const currentArtistName = subtitleParts[0] ?? null
    const currentSubtitleRemainder = subtitleParts.slice(1).join(' · ')

    const handleTogglePlayback = () => {
        if (isPlaying) {
            void pause()
            return
        }
        void resume()
    }

    return (
        <div className={`fixed bottom-0 left-0 right-0 z-40 border-t border-hud-border-secondary bg-[rgba(11,18,32,0.96)] shadow-[0_-18px_55px_rgba(0,0,0,0.38)] backdrop-blur-xl transition-all duration-300 ${sidebarCollapsed ? 'lg:left-24' : 'lg:left-72'}`}>
            <div className="mx-auto grid max-w-[1680px] gap-3 px-3 py-3 sm:px-4 lg:px-6 xl:grid-cols-[minmax(240px,360px)_minmax(320px,1fr)] min-[1800px]:grid-cols-[minmax(260px,380px)_minmax(420px,1fr)_minmax(520px,620px)] min-[1800px]:gap-4 min-[1800px]:px-8 min-[1800px]:py-4">
                <div className="flex min-w-0 items-center gap-4">
                    <div className="h-16 w-16 shrink-0 overflow-hidden rounded-lg border border-hud-border-secondary bg-hud-bg-primary shadow-hud">
                        <MusicArtwork
                            imageUrl={currentItem.imageUrl}
                            seed={`${currentItem.sourcePlatform}-${currentItem.title}`}
                            label={currentItem.title}
                        />
                    </div>
                    <div className="min-w-0">
                        <p className="text-xs text-hud-text-muted">
                            {currentItem.kind} · {playbackPlatformId ?? currentItem.sourcePlatform}
                        </p>
                        <h3 className="mt-1 truncate text-base font-semibold text-hud-text-primary">
                            {currentItem.title}
                        </h3>
                        <p className="mt-0.5 truncate text-sm text-hud-text-secondary">
                            {currentArtistName ? (
                                <ArtistDetailLink
                                    artistName={currentArtistName}
                                    className="transition-hud hover:text-hud-accent-primary"
                                />
                            ) : (
                                currentItem.subtitle
                            )}
                            {currentSubtitleRemainder ? <span> · {currentSubtitleRemainder}</span> : null}
                        </p>
                    </div>
                </div>

                <div className="min-w-0">
                    <div className="flex items-center justify-center gap-3">
                        <ControlButton
                            onClick={() => void toggleShuffle()}
                            active={shuffleEnabled}
                            label={shuffleEnabled ? '무작위 재생 켜짐' : '무작위 재생 꺼짐'}
                        >
                            <Shuffle size={17} />
                        </ControlButton>
                        <ControlButton
                            onClick={() => void skipPrevious()}
                            label="이전 곡"
                        >
                            <SkipBack size={18} />
                        </ControlButton>
                        <ControlButton
                            onClick={handleTogglePlayback}
                            disabled={isLoading}
                            primary
                            label={isPlaying ? '일시정지' : '재생 계속'}
                        >
                            {isLoading ? (
                                <Loader2 size={22} className="animate-spin" />
                            ) : isPlaying ? (
                                <Pause size={22} />
                            ) : (
                                <Play size={22} className="translate-x-0.5" />
                            )}
                        </ControlButton>
                        <ControlButton
                            onClick={() => void skipNext()}
                            label="다음 곡"
                        >
                            <SkipForward size={18} />
                        </ControlButton>
                        <ControlButton
                            onClick={() => void cycleRepeatMode()}
                            active={repeatMode !== 'off'}
                            label={repeatLabel}
                        >
                            <RepeatIcon size={17} />
                            {repeatMode !== 'off' && (
                                <span className="absolute -right-2 -top-1 rounded-full border border-hud-border-secondary bg-hud-bg-primary px-1.5 text-[10px] font-semibold leading-4 text-hud-accent-primary">
                                    {repeatShortLabel(repeatMode)}
                                </span>
                            )}
                        </ControlButton>
                    </div>

                    <div className="mt-3 grid grid-cols-[48px_minmax(0,1fr)_48px] items-center gap-3">
                        <span className="text-right text-xs text-hud-text-muted">{formatDuration(progressValue)}</span>
                        <input
                            type="range"
                            min={0}
                            max={Math.max(totalDuration, 1)}
                            value={progressValue}
                            onChange={(event) => void seek(Number(event.target.value))}
                            className="h-1 w-full accent-hud-accent-primary"
                        />
                        <span className="text-xs text-hud-text-muted">{formatDuration(totalDuration)}</span>
                    </div>

                    {(error || notice || isLoading) && (
                        <p className={`mt-2 flex items-center justify-center gap-2 truncate text-center text-xs font-medium ${error ? 'text-amber-300' : 'text-hud-accent-primary'}`}>
                            {isLoading && !error && <Loader2 size={13} className="shrink-0 animate-spin" />}
                            <span className="truncate">{error ?? notice ?? '재생 준비 중...'}</span>
                        </p>
                    )}
                </div>

                <div className="grid min-w-0 items-center gap-3 sm:grid-cols-[minmax(180px,260px)_auto] sm:justify-between xl:col-span-2 xl:grid-cols-[minmax(220px,320px)_auto] min-[1800px]:col-span-1 min-[1800px]:grid-cols-[minmax(150px,180px)_auto]">
                    <div className={`relative h-[90px] min-w-[160px] ${isYouTubeActive ? '' : 'hidden'}`}>
                        <div
                            className={`${youtubePipOpen
                                ? 'fixed bottom-32 right-4 z-[60] h-[min(52vw,380px)] w-[min(92vw,680px)] overflow-hidden rounded-xl border border-hud-border-primary bg-black shadow-[0_24px_90px_rgba(0,0,0,0.62)] sm:right-6'
                                : 'absolute inset-0 overflow-hidden rounded-lg border border-hud-border-secondary bg-black shadow-hud'
                            }`}
                            role={youtubePipOpen ? 'dialog' : undefined}
                            aria-label={youtubePipOpen ? 'YouTube 미니 플레이어' : undefined}
                            title="YouTube 플레이어"
                        >
                            <div ref={youtubePlayerHostCallbackRef} className="h-full w-full" />
                            {!youtubePipOpen && (
                                <button
                                    type="button"
                                    className="absolute inset-0 flex items-center justify-center bg-black/0 text-white opacity-0 transition-hud hover:bg-black/35 hover:opacity-100 focus:bg-black/35 focus:opacity-100 focus:outline-none focus:ring-2 focus:ring-hud-accent-primary"
                                    aria-label="YouTube 미니 플레이어 열기"
                                    title="YouTube 미니 플레이어 열기"
                                    onClick={() => setYoutubePipOpen(true)}
                                >
                                    <Maximize2 size={30} />
                                </button>
                            )}
                            {youtubePipOpen && (
                                <button
                                    type="button"
                                    className="absolute right-3 top-3 z-10 flex h-10 w-10 items-center justify-center rounded-full border border-white/20 bg-black/70 text-white shadow-hud transition-hud hover:bg-black/90 focus:outline-none focus:ring-2 focus:ring-hud-accent-primary"
                                    aria-label="YouTube 미니 플레이어 닫기"
                                    title="YouTube 미니 플레이어 닫기"
                                    onClick={() => setYoutubePipOpen(false)}
                                >
                                    <X size={22} />
                                </button>
                            )}
                        </div>
                    </div>
                    {!isYouTubeActive && (
                        <div className={`flex min-w-0 items-center gap-2 px-1 py-1 ${qualityClassName}`} title={audioQualityLabel ?? undefined}>
                            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-current/20 bg-white/[0.03]">
                                <AudioLines size={18} />
                            </span>
                            <span className="min-w-0">
                                <span className="block truncate text-sm font-semibold leading-5">{qualityParts.title}</span>
                                <span className="block truncate text-xs leading-4 text-hud-text-muted">{qualityParts.detail}</span>
                            </span>
                        </div>
                    )}

                    <div className="flex min-w-0 flex-wrap items-center justify-end gap-1.5 rounded-lg border border-hud-border-secondary bg-white/[0.03] px-2 py-0">
                        <div className="inline-flex h-10 items-center gap-2 px-2 text-sm text-hud-text-secondary">
                            <ListMusic size={24} className="text-hud-accent-primary" />
                            <span className="font-semibold text-hud-text-primary">{queue.length > 0 ? `${currentIndex + 1}/${queue.length}` : '0/0'}</span>
                        </div>
                        <div className="hidden h-10 items-center gap-2 px-2 min-[1500px]:flex">
                            <Volume2 size={25} className="text-hud-text-secondary" />
                            <input
                                type="range"
                                min={0}
                                max={1}
                                step={0.01}
                                value={volume}
                                onChange={(event) => void setVolume(Number(event.target.value))}
                                className="w-14 accent-hud-accent-primary min-[1900px]:w-20"
                                aria-label="재생 음량"
                            />
                        </div>
                        {likeController.available && (
                            <Button
                                type="button"
                                variant="ghost"
                                onClick={() => void likeController.toggle()}
                                disabled={likeController.loading}
                                aria-label={likeController.liked ? '좋아요 취소' : '좋아요'}
                                aria-pressed={likeController.liked}
                                title={likeController.liked ? '좋아요 취소' : '좋아요'}
                                className={`h-12 w-12 px-0 ${likeController.liked ? 'text-rose-400 hover:text-rose-300' : ''}`}
                            >
                                <Heart
                                    size={35}
                                    fill={likeController.liked ? 'currentColor' : 'none'}
                                    strokeWidth={likeController.liked ? 1.5 : 2}
                                />
                            </Button>
                        )}
                        {playbackPlatformId === 'tidal' && (
                            <Button
                                type="button"
                                variant="ghost"
                                onClick={() => navigate('/visualizer')}
                                aria-label="비주얼라이저 열기"
                                title="비주얼라이저 열기"
                                className="h-12 w-12 px-0"
                            >
                                <Maximize2 size={35} />
                            </Button>
                        )}
                        {currentItem.externalUrl && (
                            <Button
                                type="button"
                                variant="ghost"
                                onClick={() => window.open(currentItem.externalUrl ?? undefined, '_blank', 'noopener,noreferrer')}
                                aria-label="플랫폼에서 열기"
                                className="h-12 w-12 px-0"
                            >
                                <ExternalLink size={35} />
                            </Button>
                        )}
                        <Button type="button" variant="ghost" onClick={clearItem} aria-label="플레이어 닫기" className="h-12 w-12 px-0">
                            <X size={35} />
                        </Button>
                    </div>
                </div>
            </div>
        </div>
    )
}

export default PlaybackDock
