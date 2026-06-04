import { useEffect, useState } from 'react'
import { ArrowLeft, RefreshCw } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import Button from '@/components/common/Button'
import HudCard from '@/components/common/HudCard'
import MusicArtwork from '@/components/music/MusicArtwork'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import {
    buildEmsPlaylistDetailPath,
    emsSearchPlaylistCacheKey,
} from '@/lib/emsPlayback'
import { ApiError, fetchEmsSearchPlaylistTracks } from '@/services/api'
import type { EmsCollectionSearchPlaylistItem } from '@/types/api'

const readCachedPlaylist = (platformId?: string, externalPlaylistId?: string) => {
    if (!platformId || !externalPlaylistId) {
        return null
    }
    const cacheKey = emsSearchPlaylistCacheKey(platformId, externalPlaylistId)
    const cached = window.sessionStorage.getItem(cacheKey)
    if (!cached) {
        return null
    }
    try {
        return JSON.parse(cached) as EmsCollectionSearchPlaylistItem
    } catch {
        window.sessionStorage.removeItem(cacheKey)
        return null
    }
}

const EmsSearchPlaylistDetailPage = () => {
    const navigate = useNavigate()
    const { platformId, externalPlaylistId } = useParams<{ platformId: string; externalPlaylistId: string }>()
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const activeUserId = session?.userId || workspace.userId
    const [playlist] = useState(() => readCachedPlaylist(platformId, externalPlaylistId))
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        if (!platformId || !externalPlaylistId) {
            setError('EMS playlist id가 올바르지 않습니다.')
            return () => controller.abort()
        }
        if (!activeUserId) {
            setError('EMS POOL에 저장하려면 먼저 로그인하세요.')
            return () => controller.abort()
        }

        setError(null)
        fetchEmsSearchPlaylistTracks(platformId, externalPlaylistId, activeUserId, controller.signal)
            .then((response) => {
                if (response.playlist_id) {
                    navigate(buildEmsPlaylistDetailPath(response.playlist_id), { replace: true })
                    return
                }
                setError('EMS DB에 저장된 트랙이 없는 플레이리스트입니다.')
            })
            .catch((requestError: unknown) => {
                if (requestError instanceof DOMException && requestError.name === 'AbortError') {
                    return
                }
                const message =
                    requestError instanceof ApiError
                        ? requestError.message
                        : '플레이리스트를 EMS DB에 저장하지 못했습니다.'
                setError(message)
            })

        return () => controller.abort()
    }, [activeUserId, externalPlaylistId, navigate, platformId])

    return (
        <div className="space-y-4">
            <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
                <ArrowLeft size={18} />
                뒤로
            </Button>

            <HudCard
                title={playlist?.title ?? 'EMS POOL 저장 중'}
                subtitle="외부 playlist는 먼저 EMS DB에 저장한 뒤 DB 상세 화면으로 이동합니다."
            >
                <div className="grid gap-5 md:grid-cols-[96px_minmax(0,1fr)]">
                    <div className="h-24 overflow-hidden rounded-2xl border border-hud-border-secondary">
                        <MusicArtwork
                            imageUrl={playlist?.cover_image_url}
                            seed={`${platformId ?? 'ems'}-${externalPlaylistId ?? 'playlist'}`}
                            label={playlist?.title ?? 'EMS'}
                        />
                    </div>
                    <div className="min-w-0">
                        {error ? (
                            <div className="rounded-2xl border border-hud-accent-warning/40 bg-hud-accent-warning/10 p-4 text-sm leading-6 text-hud-text-secondary">
                                {error}
                            </div>
                        ) : (
                            <div className="flex items-center gap-3 text-sm leading-6 text-hud-text-secondary">
                                <RefreshCw size={16} className="animate-spin text-hud-accent-primary" />
                                provider track을 EMS DB에 저장하고 있습니다. 저장된 playlist id가 확인되면 DB 상세 화면으로 이동합니다.
                            </div>
                        )}
                    </div>
                </div>
            </HudCard>
        </div>
    )
}

export default EmsSearchPlaylistDetailPage
