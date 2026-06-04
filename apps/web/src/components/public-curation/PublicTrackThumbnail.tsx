import { useEffect, useState } from 'react'

interface PublicTrackThumbnailProps {
    imageUrl?: string | null
    title?: string | null
    fallbackLabel?: string
    className?: string
}

const resolveFallbackLabel = (title?: string | null, fallbackLabel = '?') =>
    title?.trim().charAt(0).toLocaleUpperCase() || fallbackLabel

const PublicTrackThumbnail = ({
    imageUrl,
    title,
    fallbackLabel = '?',
    className = '',
}: PublicTrackThumbnailProps) => {
    const [imageFailed, setImageFailed] = useState(false)

    useEffect(() => {
        setImageFailed(false)
    }, [imageUrl])

    return (
        <div
            className={`flex shrink-0 items-center justify-center overflow-hidden rounded-lg border text-lg font-black ${className}`}
        >
            {imageUrl && !imageFailed ? (
                <img
                    src={imageUrl}
                    alt=""
                    className="h-full w-full object-cover"
                    onError={() => setImageFailed(true)}
                />
            ) : (
                <span aria-hidden="true">{resolveFallbackLabel(title, fallbackLabel)}</span>
            )}
        </div>
    )
}

export default PublicTrackThumbnail
