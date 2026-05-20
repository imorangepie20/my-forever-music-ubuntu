const normalizeDiacritics = (value: string) =>
    value.normalize('NFD').replace(/[\u0300-\u036f]/g, '')

export const artistSlugFromName = (artistName?: string | null) => {
    const normalized = normalizeDiacritics((artistName ?? '').trim().toLowerCase())
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')

    return normalized || 'unknown-artist'
}

export const readableArtistNameFromSlug = (artistSlug?: string | null) => {
    const normalized = (artistSlug ?? '').trim().replace(/-/g, ' ')
    return normalized || 'Unknown Artist'
}

export const buildArtistDetailPath = (artistName: string) =>
    `/artists/${encodeURIComponent(artistSlugFromName(artistName))}?name=${encodeURIComponent(artistName)}`
