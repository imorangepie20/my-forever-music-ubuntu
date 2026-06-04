import { Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward } from 'lucide-react'
import PublicTrackThumbnail from '@/components/public-curation/PublicTrackThumbnail'
import SpectrumVisualizerFrame from '@/components/visualizer/SpectrumVisualizerFrame'
import type { TidalAudioAnalyserHandle } from '@/hooks/useTidalAudioAnalyser'
import { formatDuration } from '@/lib/musicPlayback'
import {
    TIDAL_PLAYBACK_QUALITY_OPTIONS,
    tidalPlaybackQualityLabel,
    type TidalPlaybackQuality,
} from '@/lib/tidalPlaybackQuality'
import type { TidalPlaybackSnapshot } from '@/lib/tidalStreamPlayback'
import type { PublicCurationShareTrack } from '@/types/api'

interface PublicMixSpectrumPlayerProps {
    track: PublicCurationShareTrack | null
    trackIndex: number | null
    trackCount: number
    snapshot: TidalPlaybackSnapshot
    analyser: TidalAudioAnalyserHandle
    isPlaying: boolean
    canPrevious: boolean
    canNext: boolean
    shuffleEnabled: boolean
    repeatMode: PublicMixRepeatMode
    tidalPlaybackQuality: TidalPlaybackQuality
    playbackProvider: 'tidal' | 'youtube'
    youtubePlayerHostRef: (element: HTMLDivElement | null) => void
    onPrevious: () => void
    onTogglePlayback: () => void
    onNext: () => void
    onToggleShuffle: () => void
    onCycleRepeat: () => void
    onQualityChange: (quality: TidalPlaybackQuality) => void
}

export type PublicMixRepeatMode = 'off' | 'all' | 'one'

const PublicMixSpectrumPlayer = ({
    track,
    trackIndex,
    trackCount,
    snapshot,
    analyser,
    isPlaying,
    canPrevious,
    canNext,
    shuffleEnabled,
    repeatMode,
    tidalPlaybackQuality,
    playbackProvider,
    youtubePlayerHostRef,
    onPrevious,
    onTogglePlayback,
    onNext,
    onToggleShuffle,
    onCycleRepeat,
    onQualityChange,
}: PublicMixSpectrumPlayerProps) => {
    const durationMs = snapshot.durationMs || track?.duration_ms || 0
    const progress = durationMs > 0 ? Math.min(100, Math.max(0, snapshot.positionMs / durationMs * 100)) : 0
    const hasTrack = Boolean(track)
    const requestedQuality = tidalPlaybackQuality
    const qualityLabel = playbackProvider === 'youtube'
        ? 'YouTube fallback'
        : [
            `요청 ${tidalPlaybackQualityLabel(requestedQuality as TidalPlaybackQuality)}`,
            snapshot.audioQuality ? `실제 ${snapshot.audioQuality}` : null,
            snapshot.codec,
        ].filter(Boolean).join(' / ')

    return (
        <section className="border-y border-white/10 bg-[#080b14]">
            <div className="mx-auto max-w-7xl px-6 py-10 lg:px-10">
                <div className="overflow-hidden rounded-lg border border-white/12 bg-[#0d1220] shadow-2xl shadow-black/35">
                    <div className="grid gap-8 px-5 pb-6 pt-5 md:grid-cols-[minmax(0,1fr)_auto] md:items-start lg:px-7">
                        <div className="flex min-w-0 items-center gap-4">
                            <PublicTrackThumbnail
                                imageUrl={track?.image_url}
                                title={track?.title}
                                fallbackLabel="FM"
                                className="h-16 w-16 border-cyan-200/20 bg-cyan-300/12 text-cyan-100"
                            />
                            <div className="min-w-0">
                                <p className="text-xs font-black uppercase tracking-[0.24em] text-cyan-200">Public Mix Player</p>
                                <div className="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1">
                                    <h2 className="truncate text-2xl font-black text-white">
                                        {track?.title ?? 'Play All을 눌러 음악을 시작하세요'}
                                    </h2>
                                    {trackIndex !== null && (
                                        <span className="text-xs font-semibold text-white/45">
                                            {String(trackIndex + 1).padStart(2, '0')} / {String(trackCount).padStart(2, '0')}
                                        </span>
                                    )}
                                </div>
                                <p className="mt-2 truncate text-sm text-white/58">
                                    {track
                                        ? `${track.artist_name}${track.album_title ? ` · ${track.album_title}` : ''}`
                                        : 'My Forever Music Public Curation'}
                                </p>
                            </div>
                        </div>

                        <div className="flex flex-wrap items-center justify-end gap-2">
                            <label className="flex items-center gap-2 rounded-lg border border-white/14 bg-white/[0.03] px-2 py-1.5">
                                <span className="text-[10px] font-bold uppercase tracking-[0.16em] text-white/44">
                                    TIDAL 재생 품질
                                </span>
                                <select
                                    value={tidalPlaybackQuality}
                                    onChange={(event) => onQualityChange(event.target.value as TidalPlaybackQuality)}
                                    className="h-8 rounded-md border border-white/12 bg-[#090b12] px-2 text-xs font-bold text-cyan-100 outline-none transition hover:border-cyan-300 focus:border-cyan-300"
                                >
                                    {TIDAL_PLAYBACK_QUALITY_OPTIONS.map((option) => (
                                        <option key={option.value} value={option.value}>
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            <button
                                type="button"
                                onClick={onToggleShuffle}
                                className={`flex h-10 w-10 items-center justify-center rounded-lg border transition ${
                                    shuffleEnabled
                                        ? 'border-cyan-300 bg-cyan-300/12 text-cyan-100'
                                        : 'border-white/14 text-white/72 hover:border-cyan-300 hover:text-cyan-100'
                                }`}
                                aria-label={shuffleEnabled ? '랜덤 재생 끄기' : '랜덤 재생 켜기'}
                                title={shuffleEnabled ? '랜덤 재생 끄기' : '랜덤 재생 켜기'}
                            >
                                <Shuffle className="h-4 w-4" />
                            </button>
                            <button
                                type="button"
                                onClick={onPrevious}
                                disabled={!hasTrack || !canPrevious}
                                className="flex h-10 w-10 items-center justify-center rounded-lg border border-white/14 text-white/72 transition hover:border-cyan-300 hover:text-cyan-100 disabled:cursor-not-allowed disabled:opacity-35"
                                aria-label="이전 곡"
                                title="이전 곡"
                            >
                                <SkipBack className="h-4 w-4 fill-current" />
                            </button>
                            <button
                                type="button"
                                onClick={onTogglePlayback}
                                className="flex h-12 w-12 items-center justify-center rounded-lg bg-cyan-300 text-slate-950 transition hover:bg-cyan-200"
                                aria-label={isPlaying ? '일시정지' : '재생'}
                                title={isPlaying ? '일시정지' : '재생'}
                            >
                                {isPlaying ? <Pause className="h-5 w-5 fill-current" /> : <Play className="h-5 w-5 fill-current" />}
                            </button>
                            <button
                                type="button"
                                onClick={onNext}
                                disabled={!hasTrack || !canNext}
                                className="flex h-10 w-10 items-center justify-center rounded-lg border border-white/14 text-white/72 transition hover:border-cyan-300 hover:text-cyan-100 disabled:cursor-not-allowed disabled:opacity-35"
                                aria-label="다음 곡"
                                title="다음 곡"
                            >
                                <SkipForward className="h-4 w-4 fill-current" />
                            </button>
                            <button
                                type="button"
                                onClick={onCycleRepeat}
                                className={`relative flex h-10 w-10 items-center justify-center rounded-lg border transition ${
                                    repeatMode !== 'off'
                                        ? 'border-cyan-300 bg-cyan-300/12 text-cyan-100'
                                        : 'border-white/14 text-white/72 hover:border-cyan-300 hover:text-cyan-100'
                                }`}
                                aria-label={
                                    repeatMode === 'one'
                                        ? '한 곡 반복 끄기'
                                        : repeatMode === 'all'
                                          ? '한 곡 반복 켜기'
                                          : '전체 반복 켜기'
                                }
                                title={
                                    repeatMode === 'one'
                                        ? '한 곡 반복'
                                        : repeatMode === 'all'
                                          ? '전체 반복'
                                          : '반복 끄기'
                                }
                            >
                                {repeatMode === 'one' ? <Repeat1 className="h-4 w-4" /> : <Repeat className="h-4 w-4" />}
                            </button>
                        </div>
                    </div>

                    <div className="relative border-y border-white/8">
                        <SpectrumVisualizerFrame
                            data-public-mix-spectrum
                            analyser={analyser}
                            accentHex="#67e8f9"
                            isPlaying={isPlaying && playbackProvider === 'tidal'}
                            className="h-60 bg-[linear-gradient(180deg,rgba(14,116,144,0.14)_0%,rgba(15,23,42,0.28)_52%,rgba(2,6,23,0.72)_100%)] sm:h-72"
                        />
                        <div
                            ref={youtubePlayerHostRef}
                            data-public-youtube-player-host
                            className={`absolute inset-0 z-20 bg-black transition ${
                                playbackProvider === 'youtube'
                                    ? 'opacity-100'
                                    : 'pointer-events-none opacity-0'
                            }`}
                            aria-hidden={playbackProvider !== 'youtube'}
                        />
                    </div>

                    <div className="px-5 py-5 lg:px-7">
                        <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
                            <div
                                className="h-full rounded-full bg-cyan-300 transition-[width] duration-300"
                                style={{ width: `${progress}%` }}
                            />
                        </div>
                        <div className="mt-3 flex items-center justify-between text-xs font-semibold text-white/44">
                            <span>{formatDuration(snapshot.positionMs) ?? '0:00'}</span>
                            <span className="uppercase tracking-[0.2em]" title={qualityLabel}>
                                {qualityLabel || 'TIDAL'}
                            </span>
                            <span>{formatDuration(durationMs) ?? '--:--'}</span>
                        </div>
                    </div>
                </div>
            </div>
        </section>
    )
}

export default PublicMixSpectrumPlayer
