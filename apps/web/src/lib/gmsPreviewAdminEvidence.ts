import type { GmsAxisEvidence } from '@/types/api'

export interface GmsPreviewAdminEvidenceTrack {
    rank: number
    track_id: string
    title: string
    artist_name: string
    source_platform: string
    score: number
    axis_evidence: GmsAxisEvidence[]
}

export interface GmsPreviewAdminEvidencePlaylist {
    rank: number
    playlist_id: number
    title: string
    source_platform: string
    track_count: number
    composite_score: number
    affinity_score: number
    confidence_score: number
    axis_evidence: GmsAxisEvidence[]
}

export interface GmsPreviewAdminEvidenceSnapshot {
    request_id: string
    generated_at: string
    user_id: string | null
    tracks: GmsPreviewAdminEvidenceTrack[]
    playlists: GmsPreviewAdminEvidencePlaylist[]
}

export const parseGmsPreviewAdminEvidenceSnapshot = (
    rawSummary: string | null | undefined,
    fallback: Pick<GmsPreviewAdminEvidenceSnapshot, 'request_id' | 'generated_at' | 'user_id'>,
): GmsPreviewAdminEvidenceSnapshot | null => {
    if (!rawSummary?.trim()) {
        return null
    }

    try {
        const parsed = JSON.parse(rawSummary) as Partial<{
            items: GmsPreviewAdminEvidenceTrack[]
            playlists: GmsPreviewAdminEvidencePlaylist[]
        }>
        if (!parsed || typeof parsed !== 'object') {
            return null
        }

        return {
            request_id: fallback.request_id,
            generated_at: fallback.generated_at,
            user_id: fallback.user_id,
            tracks: Array.isArray(parsed.items) ? parsed.items : [],
            playlists: Array.isArray(parsed.playlists) ? parsed.playlists : [],
        }
    } catch {
        return null
    }
}
