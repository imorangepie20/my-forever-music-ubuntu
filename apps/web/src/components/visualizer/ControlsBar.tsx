import { Loader2, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Volume2 } from 'lucide-react'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import { usePlayback } from '@/contexts/PlaybackContext'
import { formatDuration } from '@/lib/musicPlayback'

interface ControlsBarProps {
    accentHex: string
}

const ControlsBar = ({ accentHex }: ControlsBarProps) => {
    const {
        currentItem,
        isPlaying,
        isLoading,
        positionMs,
        durationMs,
        volume,
        shuffleEnabled,
        repeatMode,
        pause,
        resume,
        skipNext,
        skipPrevious,
        seek,
        setVolume,
        toggleShuffle,
        cycleRepeatMode,
    } = usePlayback()

    if (!currentItem) {
        return null
    }

    const total = durationMs || currentItem.durationMs || 0
    const progress = total > 0 ? Math.min(positionMs, total) : 0
    const RepeatIcon = repeatMode === 'one' ? Repeat1 : Repeat
    const subtitleParts = currentItem.subtitle?.split(' · ').map((part) => part.trim()).filter(Boolean) ?? []
    const currentArtistName = subtitleParts[0] ?? null
    const currentSubtitleRemainder = subtitleParts.slice(1).join(' · ')

    const handleToggle = () => {
        if (isPlaying) {
            void pause()
            return
        }
        void resume()
    }

    return (
        <div className="relative z-10 flex w-full flex-col items-center gap-4 px-6 pb-6 pt-4">
            <div className="w-full max-w-3xl text-center">
                <p className="text-[11px] uppercase tracking-[0.32em] text-white/50">Now playing</p>
                <h1 className="mt-2 truncate text-2xl font-semibold text-white sm:text-3xl">{currentItem.title}</h1>
                <p className="mt-1 truncate text-sm text-white/70">
                    {currentArtistName ? (
                        <ArtistDetailLink
                            artistName={currentArtistName}
                            className="transition hover:text-white"
                        />
                    ) : (
                        currentItem.subtitle
                    )}
                    {currentSubtitleRemainder ? <span> · {currentSubtitleRemainder}</span> : null}
                </p>
            </div>

            <div className="flex w-full max-w-3xl items-center gap-4">
                <span className="w-12 text-right font-mono text-xs text-white/60">
                    {formatDuration(progress) ?? '0:00'}
                </span>
                <input
                    type="range"
                    min={0}
                    max={Math.max(total, 1)}
                    value={progress}
                    onChange={(event) => void seek(Number(event.target.value))}
                    className="h-1 flex-1"
                    style={{ accentColor: accentHex }}
                    aria-label="Playback progress"
                />
                <span className="w-12 font-mono text-xs text-white/60">
                    {formatDuration(total) ?? '--:--'}
                </span>
            </div>

            <div className="flex items-center gap-3">
                <button
                    type="button"
                    onClick={() => void toggleShuffle()}
                    className={`flex h-11 w-11 items-center justify-center rounded-full border transition ${
                        shuffleEnabled ? 'border-white/40 bg-white/10 text-white' : 'border-white/15 bg-transparent text-white/60 hover:text-white'
                    }`}
                    style={shuffleEnabled ? { color: accentHex, borderColor: accentHex } : undefined}
                    aria-label={shuffleEnabled ? 'Shuffle on' : 'Shuffle off'}
                >
                    <Shuffle size={18} />
                </button>
                <button
                    type="button"
                    onClick={() => void skipPrevious()}
                    className="flex h-12 w-12 items-center justify-center rounded-full border border-white/15 text-white/80 transition hover:text-white"
                    aria-label="Previous track"
                >
                    <SkipBack size={20} />
                </button>
                <button
                    type="button"
                    onClick={handleToggle}
                    disabled={isLoading}
                    className="flex h-16 w-16 items-center justify-center rounded-full text-black shadow-[0_18px_45px_-12px_rgba(0,0,0,0.65)] transition disabled:cursor-not-allowed disabled:opacity-60"
                    style={{ background: accentHex }}
                    aria-label={isPlaying ? 'Pause playback' : 'Resume playback'}
                >
                    {isLoading ? (
                        <Loader2 size={26} className="animate-spin" />
                    ) : isPlaying ? (
                        <Pause size={26} />
                    ) : (
                        <Play size={26} className="translate-x-0.5" />
                    )}
                </button>
                <button
                    type="button"
                    onClick={() => void skipNext()}
                    className="flex h-12 w-12 items-center justify-center rounded-full border border-white/15 text-white/80 transition hover:text-white"
                    aria-label="Next track"
                >
                    <SkipForward size={20} />
                </button>
                <button
                    type="button"
                    onClick={() => void cycleRepeatMode()}
                    className={`flex h-11 w-11 items-center justify-center rounded-full border transition ${
                        repeatMode !== 'off' ? 'border-white/40 bg-white/10 text-white' : 'border-white/15 bg-transparent text-white/60 hover:text-white'
                    }`}
                    style={repeatMode !== 'off' ? { color: accentHex, borderColor: accentHex } : undefined}
                    aria-label={repeatMode === 'one' ? 'Repeat one' : repeatMode === 'all' ? 'Repeat queue' : 'Repeat off'}
                >
                    <RepeatIcon size={18} />
                </button>
            </div>

            <div className="flex items-center gap-3 rounded-full border border-white/15 bg-black/30 px-4 py-2">
                <Volume2 size={16} className="text-white/70" />
                <input
                    type="range"
                    min={0}
                    max={1}
                    step={0.01}
                    value={volume}
                    onChange={(event) => void setVolume(Number(event.target.value))}
                    className="h-1 w-32"
                    style={{ accentColor: accentHex }}
                    aria-label="Volume"
                />
            </div>
        </div>
    )
}

export default ControlsBar
