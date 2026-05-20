import type { KeyboardEvent, MouseEvent, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { buildArtistDetailPath } from '@/lib/artistLinks'

interface ArtistDetailLinkProps {
    artistName?: string | null
    children?: ReactNode
    className?: string
    fallback?: string
    stopPropagation?: boolean
}

const ArtistDetailLink = ({
    artistName,
    children,
    className,
    fallback = 'Unknown Artist',
    stopPropagation = false,
}: ArtistDetailLinkProps) => {
    const trimmedArtistName = artistName?.trim()
    const displayName = trimmedArtistName || fallback

    if (!trimmedArtistName) {
        return <span className={className}>{children ?? displayName}</span>
    }

    const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
        if (stopPropagation) {
            event.stopPropagation()
        }
    }

    const handleKeyDown = (event: KeyboardEvent<HTMLAnchorElement>) => {
        if (stopPropagation) {
            event.stopPropagation()
        }
    }

    return (
        <Link
            to={buildArtistDetailPath(trimmedArtistName)}
            className={className}
            onClick={handleClick}
            onKeyDown={handleKeyDown}
        >
            {children ?? displayName}
        </Link>
    )
}

export default ArtistDetailLink
