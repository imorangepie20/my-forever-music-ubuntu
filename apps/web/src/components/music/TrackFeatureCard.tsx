import { ExternalLink, Play } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import ArtistDetailLink from '@/components/music/ArtistDetailLink'
import MusicArtwork from '@/components/music/MusicArtwork'
import { formatDuration } from '@/lib/musicPlayback'

interface TrackFeatureCardProps {
    title: string
    artistName: string
    sourcePlatform: string
    albumTitle?: string | null
    imageUrl?: string | null
    durationMs?: number | null
    reason?: string | null
    badges?: string[]
    artistDetailPath?: string
    onPlay?: () => void
    onOpenExternal?: () => void
    feedbackActions?: Array<{
        label: string
        icon: ReactNode
        active?: boolean
        disabled?: boolean
        onClick: () => void
    }>
}

const TrackFeatureCard = ({
    title,
    artistName,
    sourcePlatform,
    albumTitle,
    imageUrl,
    durationMs,
    reason,
    badges = [],
    artistDetailPath,
    onPlay,
    onOpenExternal,
    feedbackActions = [],
}: TrackFeatureCardProps) => {
    const durationLabel = formatDuration(durationMs)

    return (
        <div className="overflow-hidden rounded-[24px] border border-hud-border-secondary bg-hud-bg-primary/75">
            <div className="aspect-[4/3]">
                <MusicArtwork imageUrl={imageUrl} seed={`${artistName}-${title}`} label={`${title} ${artistName}`} />
            </div>
            <div className="space-y-4 p-4">
                <div>
                    <div className="flex flex-wrap gap-2">
                        <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                            {sourcePlatform}
                        </span>
                        {durationLabel && (
                            <span className="rounded-full border border-hud-border-secondary px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-text-muted">
                                {durationLabel}
                            </span>
                        )}
                        {badges.map((badge) => (
                            <span
                                key={badge}
                                className="rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-3 py-1 text-[11px] uppercase tracking-[0.24em] text-hud-accent-primary"
                            >
                                {badge}
                            </span>
                        ))}
                    </div>
                    <h3 className="mt-3 text-lg font-semibold text-hud-text-primary">{title}</h3>
                    {artistDetailPath ? (
                        <Link
                            to={artistDetailPath}
                            className="mt-1 inline-flex max-w-full text-sm text-hud-text-secondary transition-hud hover:text-hud-accent-primary"
                        >
                            <span className="truncate">{artistName}</span>
                        </Link>
                    ) : (
                        <ArtistDetailLink
                            artistName={artistName}
                            className="mt-1 inline-flex max-w-full text-sm text-hud-text-secondary transition-hud hover:text-hud-accent-primary"
                        >
                            <span className="truncate">{artistName}</span>
                        </ArtistDetailLink>
                    )}
                    {albumTitle && <p className="mt-2 text-xs uppercase tracking-[0.22em] text-hud-text-muted">{albumTitle}</p>}
                </div>

                {reason && <p className="text-sm leading-6 text-hud-text-secondary">{reason}</p>}

                <div className="flex flex-wrap gap-3">
                    {onPlay && (
                        <Button type="button" variant="primary" onClick={onPlay}>
                            <Play size={18} />
                            Play
                        </Button>
                    )}
                    {onOpenExternal && (
                        <Button type="button" variant="ghost" onClick={onOpenExternal}>
                            <ExternalLink size={18} />
                            Open
                        </Button>
                    )}
                </div>

                {feedbackActions.length > 0 && (
                    <div className="flex flex-wrap gap-2 border-t border-hud-border-secondary pt-3">
                        {feedbackActions.map((action) => (
                            <Button
                                key={action.label}
                                type="button"
                                variant={action.active ? 'primary' : 'ghost'}
                                onClick={action.onClick}
                                disabled={action.disabled}
                            >
                                {action.icon}
                                {action.label}
                            </Button>
                        ))}
                    </div>
                )}
            </div>
        </div>
    )
}

export default TrackFeatureCard
