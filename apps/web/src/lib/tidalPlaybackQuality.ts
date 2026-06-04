export type TidalPlaybackQuality = 'LOW' | 'HIGH' | 'LOSSLESS' | 'HI_RES_LOSSLESS'

export const TIDAL_PLAYBACK_QUALITY_STORAGE_KEY = 'my-forever-music.tidal-playback-quality'
export const DEFAULT_TIDAL_PLAYBACK_QUALITY: TidalPlaybackQuality = 'LOSSLESS'

export const TIDAL_PLAYBACK_QUALITY_OPTIONS: Array<{
    value: TidalPlaybackQuality
    label: string
    detail: string
}> = [
    { value: 'LOW', label: '낮음', detail: '데이터 절약' },
    { value: 'HIGH', label: '높음', detail: '안정적 고음질' },
    { value: 'LOSSLESS', label: '무손실', detail: 'CD급' },
    { value: 'HI_RES_LOSSLESS', label: 'Hi-Res', detail: '최대 품질' },
]

const isTidalPlaybackQuality = (value: string | null): value is TidalPlaybackQuality =>
    value === 'LOW' || value === 'HIGH' || value === 'LOSSLESS' || value === 'HI_RES_LOSSLESS'

export const readTidalPlaybackQuality = (): TidalPlaybackQuality => {
    if (typeof window === 'undefined') {
        return DEFAULT_TIDAL_PLAYBACK_QUALITY
    }
    const storedQuality = window.localStorage.getItem(TIDAL_PLAYBACK_QUALITY_STORAGE_KEY)
    return isTidalPlaybackQuality(storedQuality) ? storedQuality : DEFAULT_TIDAL_PLAYBACK_QUALITY
}

export const writeTidalPlaybackQuality = (quality: TidalPlaybackQuality) => {
    if (typeof window === 'undefined') {
        return
    }
    window.localStorage.setItem(TIDAL_PLAYBACK_QUALITY_STORAGE_KEY, quality)
}

export const tidalPlaybackQualityLabel = (quality: TidalPlaybackQuality) =>
    TIDAL_PLAYBACK_QUALITY_OPTIONS.find((option) => option.value === quality)?.label ?? quality
