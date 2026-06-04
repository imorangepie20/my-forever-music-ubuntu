import { ExternalLink, Loader2, Music4, Play } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import Button from '@/components/common/Button'
import MusicArtwork from '@/components/music/MusicArtwork'

interface PlaylistFeatureCardProps {
    title: string
    sourcePlatform: string
    curator: string
    trackCount: number
    description: string
    sourceLabel?: string
    selectButtonLabel?: string
    supportingText?: string
    imageUrl?: string | null
    isActive?: boolean
    showStatusBadge?: boolean
    onSelect?: () => void
    onPlay?: () => void
    onOpenExternal?: () => void
    onOpenDetail?: () => void
    actionLabel?: string
    isPlayLoading?: boolean
    detailPath?: string
}

const PlaylistFeatureCard = ({
    title,
    sourcePlatform,
    curator,
    trackCount,
    description,
    sourceLabel,
    selectButtonLabel,
    supportingText,
    imageUrl,
    isActive = false,
    showStatusBadge = true,
    onSelect,
    onPlay,
    onOpenExternal,
    onOpenDetail,
    actionLabel = '플레이리스트 사용',
    isPlayLoading = false,
    detailPath,
}: PlaylistFeatureCardProps) => {
    const navigate = useNavigate()
    const openDetail = () => {
        if (detailPath) {
            onOpenDetail?.()
            navigate(detailPath)
        }
    }

    return (
        <div
            role={detailPath ? 'button' : undefined}
            tabIndex={detailPath ? 0 : undefined}
            onClick={openDetail}
            onKeyDown={(event) => {
                if (!detailPath) {
                    return
                }
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    openDetail()
                }
            }}
            className={`overflow-hidden rounded-[28px] border transition-hud ${
                isActive
                    ? 'border-hud-border-primary bg-hud-accent-primary/10 shadow-[0_0_0_1px_rgba(87,172,255,0.18)]'
                    : 'border-hud-border-secondary bg-hud-bg-primary/75'
            } ${detailPath ? 'cursor-pointer hover:border-hud-border-primary' : ''}`}
        >
            <div className="grid gap-0 md:grid-cols-[180px_minmax(0,1fr)]">
                <div className="h-44 md:h-full">
                    <MusicArtwork imageUrl={imageUrl} seed={`${sourcePlatform}-${title}`} label={title} />
                </div>
                <div className="space-y-4 p-5">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                            <div className="flex flex-wrap gap-2">
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {sourcePlatform}
                                </span>
                                {sourceLabel && (
                                    <span className="rounded-full border border-hud-accent-primary/30 bg-hud-accent-primary/10 px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-accent-primary">
                                        {sourceLabel}
                                    </span>
                                )}
                                <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                    {trackCount}곡
                                </span>
                            </div>
                            <h3 className="mt-3 text-xl font-semibold text-hud-text-primary">{title}</h3>
                            <p className="mt-2 text-sm text-hud-text-secondary">큐레이터 {curator}</p>
                        </div>
                        {showStatusBadge && (
                            <span className="rounded-2xl bg-hud-bg-secondary/80 px-4 py-3 text-right">
                                <span className="block text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">상태</span>
                                <span className="mt-1 block text-sm font-medium text-hud-text-primary">
                                    {isActive ? '선택됨' : '사용 가능'}
                                </span>
                            </span>
                        )}
                    </div>

                    <p className="text-sm leading-6 text-hud-text-secondary">{description}</p>
                    {supportingText && (
                        <p className="text-xs uppercase tracking-[0.18em] text-hud-accent-primary">
                            {supportingText}
                        </p>
                    )}

                    <div className="flex flex-wrap gap-3">
                        {onSelect && (
                            <Button
                                type="button"
                                variant={isActive ? 'primary' : 'ghost'}
                                aria-label={selectButtonLabel}
                                onClick={(event) => {
                                    event.stopPropagation()
                                    onSelect()
                                }}
                            >
                                <Music4 size={18} />
                                {actionLabel}
                            </Button>
                        )}
                        {onPlay && (
                            <Button
                                type="button"
                                variant="outline"
                                disabled={isPlayLoading}
                                onClick={(event) => {
                                    event.stopPropagation()
                                    onPlay()
                                }}
                            >
                                {isPlayLoading ? <Loader2 size={18} className="animate-spin" /> : <Play size={18} />}
                                {isPlayLoading ? '준비 중' : '재생'}
                            </Button>
                        )}
                        {onOpenExternal && (
                            <Button
                                type="button"
                                variant="ghost"
                                onClick={(event) => {
                                    event.stopPropagation()
                                    onOpenExternal()
                                }}
                            >
                                <ExternalLink size={18} />
                                열기
                            </Button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    )
}

export default PlaylistFeatureCard
