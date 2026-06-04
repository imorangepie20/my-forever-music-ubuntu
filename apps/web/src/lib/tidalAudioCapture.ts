import Hls, { type BufferAppendingData, type BufferCodecsData, type SourceBufferName } from 'hls.js'

export type CapturedSegmentKind = 'init' | 'media' | 'unknown'

export interface CapturedTidalAudioSegment {
    type: SourceBufferName
    kind: CapturedSegmentKind
    initSegment: Uint8Array | null
    payload: Uint8Array
    startTime: number | null
    endTime: number | null
    duration: number | null
    byteLength: number
}

export interface PublicCurationAnalysisSource {
    slug: string
    publicSessionId: string
    publicTrackId: number
}

export type TidalAudioCaptureEvent =
    | { type: 'reset' }
    | { type: 'segment'; segment: CapturedTidalAudioSegment }
    | { type: 'source'; source: 'hls' | 'native-hls' | 'direct' }
    | {
        type: 'direct-stream'
        url: string
        startTime: number | null
        userId: string
        trackId: string
        quality: string
        publicCuration: PublicCurationAnalysisSource | null
    }

type Subscriber = (event: TidalAudioCaptureEvent) => void

let attachedHls: Hls | null = null
let detach: (() => void) | null = null
const subscribers = new Set<Subscriber>()
const initSegments = new Map<SourceBufferName, Uint8Array>()
let currentSource: TidalAudioCaptureEvent | null = null
let currentDirectStream: TidalAudioCaptureEvent | null = null

const cloneBytes = (bytes?: Uint8Array | null) =>
    bytes ? bytes.slice() : null

const emit = (event: TidalAudioCaptureEvent) => {
    if (event.type === 'reset') {
        currentSource = null
        currentDirectStream = null
    } else if (event.type === 'source') {
        currentSource = event
        if (event.source !== 'direct') {
            currentDirectStream = null
        }
    } else if (event.type === 'direct-stream') {
        currentDirectStream = event
    }
    subscribers.forEach((subscriber) => subscriber(event))
}

const readAscii = (bytes: Uint8Array, start: number, length: number) =>
    Array.from(bytes.slice(start, start + length))
        .map((byte) => String.fromCharCode(byte))
        .join('')

const readUint32 = (bytes: Uint8Array, offset: number) => {
    if (offset + 4 > bytes.length) {
        return null
    }
    return (
        bytes[offset] * 0x1000000 +
        bytes[offset + 1] * 0x10000 +
        bytes[offset + 2] * 0x100 +
        bytes[offset + 3]
    )
}

const readTopLevelBoxes = (bytes: Uint8Array) => {
    const boxes: string[] = []
    let offset = 0
    while (offset + 8 <= bytes.length && boxes.length < 4) {
        const size = readUint32(bytes, offset)
        const type = readAscii(bytes, offset + 4, 4)
        if (!size || size < 8 || !/^[\x20-\x7e]{4}$/.test(type)) {
            break
        }
        boxes.push(type)
        offset += size
    }
    return boxes
}

const classifySegment = (data: Uint8Array, frag: BufferAppendingData['frag']): CapturedSegmentKind => {
    if (frag.sn === 'initSegment') {
        return 'init'
    }
    const boxes = readTopLevelBoxes(data)
    if (boxes.includes('ftyp') || boxes.includes('moov')) {
        return 'init'
    }
    if (boxes.includes('moof') || boxes.includes('mdat')) {
        return 'media'
    }
    return 'unknown'
}

const shouldCaptureType = (type: SourceBufferName) =>
    type === 'audio' || type === 'audiovideo'

const pickInitSegment = (type: SourceBufferName) =>
    initSegments.get(type) ??
    (type !== 'audio' ? initSegments.get('audio') : null) ??
    (type !== 'audiovideo' ? initSegments.get('audiovideo') : null) ??
    null

const rememberCodecInitSegments = (data: BufferCodecsData) => {
    ;(['audio', 'audiovideo'] as SourceBufferName[]).forEach((type) => {
        const initSegment = cloneBytes(data[type]?.initSegment)
        if (initSegment) {
            initSegments.set(type, initSegment)
        }
    })
}

const rememberAppendingInitSegment = (data: BufferAppendingData, payload: Uint8Array, kind: CapturedSegmentKind) => {
    if (kind === 'init' && shouldCaptureType(data.type)) {
        initSegments.set(data.type, payload.slice())
    }
}

export const attachTidalAudioCapture = (hls: Hls) => {
    if (attachedHls === hls) {
        return
    }

    detachTidalAudioCapture()
    attachedHls = hls
    initSegments.clear()
    emit({ type: 'reset' })
    emit({ type: 'source', source: 'hls' })

    const onCodecs = (_event: typeof Hls.Events.BUFFER_CODECS, data: BufferCodecsData) => {
        rememberCodecInitSegments(data)
    }

    const onAppending = (_event: typeof Hls.Events.BUFFER_APPENDING, data: BufferAppendingData) => {
        if (!shouldCaptureType(data.type)) {
            return
        }

        const payload = data.data.slice()
        const kind = classifySegment(payload, data.frag)
        rememberAppendingInitSegment(data, payload, kind)

        emit({
            type: 'segment',
            segment: {
                type: data.type,
                kind,
                initSegment: pickInitSegment(data.type)?.slice() ?? null,
                payload,
                startTime: data.frag.startPTS ?? data.frag.start ?? null,
                endTime: data.frag.endPTS ?? (
                    typeof data.frag.start === 'number' && typeof data.frag.duration === 'number'
                        ? data.frag.start + data.frag.duration
                        : null
                ),
                duration: data.frag.duration ?? null,
                byteLength: payload.byteLength,
            },
        })
    }

    hls.on(Hls.Events.BUFFER_CODECS, onCodecs)
    hls.on(Hls.Events.BUFFER_APPENDING, onAppending)
    detach = () => {
        hls.off(Hls.Events.BUFFER_CODECS, onCodecs)
        hls.off(Hls.Events.BUFFER_APPENDING, onAppending)
    }
}

export const notifyNativeHlsTidalAudioSource = () => {
    detachTidalAudioCapture()
    emit({ type: 'source', source: 'native-hls' })
}

export const notifyDirectTidalAudioSource = (
    url: string,
    userId: string,
    trackId: string,
    quality: string,
    startTime: number | null = null,
    publicCuration: PublicCurationAnalysisSource | null = null,
) => {
    detachTidalAudioCapture()
    emit({ type: 'source', source: 'direct' })
    emit({ type: 'direct-stream', url, userId, trackId, quality, startTime, publicCuration })
}

export const detachTidalAudioCapture = () => {
    detach?.()
    detach = null
    attachedHls = null
    initSegments.clear()
    emit({ type: 'reset' })
}

export const subscribeTidalAudioCapture = (subscriber: Subscriber) => {
    subscribers.add(subscriber)
    if (currentSource) {
        subscriber(currentSource)
    }
    if (currentDirectStream) {
        subscriber(currentDirectStream)
    }
    return () => {
        subscribers.delete(subscriber)
    }
}
