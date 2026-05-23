import type { GmsAxisEvidence, GmsPlaylistPreviewResponse, GmsRecommendationPreviewResponse } from '@/types/api'

export const GMS_PREVIEW_ADMIN_EVIDENCE_STORAGE_KEY = 'my-forever-music.gms-preview-admin-evidence'

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

export const buildGmsPreviewAdminEvidenceSnapshot = (
    response: GmsRecommendationPreviewResponse,
): GmsPreviewAdminEvidenceSnapshot => ({
    request_id: response.request_id,
    generated_at: response.generated_at,
    user_id: response.input_summary.user_id,
    playlists: [],
    tracks: response.items
        .filter((item) => (item.axis_evidence?.length ?? 0) > 0)
        .map((item) => ({
            rank: item.rank,
            track_id: item.track_id,
            title: item.title,
            artist_name: item.artist_name,
            source_platform: item.source_platform,
            score: item.score,
            axis_evidence: item.axis_evidence ?? [],
        })),
})

export const buildGmsPlaylistAdminEvidenceSnapshot = (
    response: GmsPlaylistPreviewResponse,
): GmsPreviewAdminEvidenceSnapshot => ({
    request_id: `playlist-preview-${response.generated_at}`,
    generated_at: response.generated_at,
    user_id: response.user_id,
    tracks: [],
    playlists: response.candidates
        .filter((candidate) => (candidate.axis_evidence?.length ?? 0) > 0)
        .map((candidate, index) => ({
            rank: index + 1,
            playlist_id: candidate.playlist_id,
            title: candidate.title,
            source_platform: candidate.source_platform,
            track_count: candidate.track_count,
            composite_score: candidate.composite_score,
            affinity_score: candidate.affinity_score,
            confidence_score: candidate.confidence_score,
            axis_evidence: candidate.axis_evidence ?? [],
        })),
})

export const saveGmsPreviewAdminEvidenceSnapshot = (response: GmsRecommendationPreviewResponse) => {
    if (typeof window === 'undefined') {
        return
    }

    try {
        window.localStorage.setItem(
            GMS_PREVIEW_ADMIN_EVIDENCE_STORAGE_KEY,
            JSON.stringify(buildGmsPreviewAdminEvidenceSnapshot(response)),
        )
    } catch {
        // Local storage is best-effort diagnostics only; preview rendering should not fail if it is unavailable.
    }
}

export const saveGmsPlaylistAdminEvidenceSnapshot = (response: GmsPlaylistPreviewResponse) => {
    if (typeof window === 'undefined') {
        return
    }

    try {
        window.localStorage.setItem(
            GMS_PREVIEW_ADMIN_EVIDENCE_STORAGE_KEY,
            JSON.stringify(buildGmsPlaylistAdminEvidenceSnapshot(response)),
        )
    } catch {
        // Local storage is best-effort diagnostics only; preview rendering should not fail if it is unavailable.
    }
}

export const loadGmsPreviewAdminEvidenceSnapshot = (): GmsPreviewAdminEvidenceSnapshot | null => {
    if (typeof window === 'undefined') {
        return null
    }

    try {
        const rawValue = window.localStorage.getItem(GMS_PREVIEW_ADMIN_EVIDENCE_STORAGE_KEY)
        if (!rawValue) {
            return null
        }
        const parsed = JSON.parse(rawValue) as Partial<GmsPreviewAdminEvidenceSnapshot>
        if (!parsed.request_id || !parsed.generated_at) {
            return null
        }
        return {
            request_id: parsed.request_id,
            generated_at: parsed.generated_at,
            user_id: parsed.user_id ?? null,
            tracks: Array.isArray(parsed.tracks) ? parsed.tracks : [],
            playlists: Array.isArray(parsed.playlists) ? parsed.playlists : [],
        }
    } catch {
        return null
    }
}
