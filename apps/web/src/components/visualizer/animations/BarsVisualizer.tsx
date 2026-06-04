import { useEffect, useMemo, useRef } from 'react'
import type { VisualizerAnimationProps } from './types'

const BAR_COUNT = 128
const MIN_VISIBLE_HEIGHT = 2
const MAX_VISIBLE_HEIGHT = 100
const PEAK_GAIN = 0.8
const RESPONSE_GAMMA = 0.95
const NOISE_FLOOR = 0.04
const HIGH_FREQUENCY_GAIN = 2.6
const ATTACK_SMOOTHING = 0.95
const RELEASE_SMOOTHING = 0.32

const resolveLogBandRange = (index: number, binCount: number): [number, number] => {
    if (binCount <= 2) {
        return [0, binCount]
    }

    const minBin = 1
    const maxBin = binCount - 1
    const minLog = Math.log(minBin)
    const maxLog = Math.log(maxBin)
    const startRatio = index / BAR_COUNT
    const endRatio = (index + 1) / BAR_COUNT
    const start = Math.max(minBin, Math.floor(Math.exp(minLog + (maxLog - minLog) * startRatio)))
    const end = Math.min(binCount, Math.max(start + 1, Math.ceil(Math.exp(minLog + (maxLog - minLog) * endRatio))))

    return [start, end]
}

const BarsVisualizer = ({ analyser, accentHex, isPlaying }: VisualizerAnimationProps) => {
    const barRefs = useRef<Array<HTMLSpanElement | null>>([])
    const dataRef = useRef<Uint8Array>(new Uint8Array(analyser.binCount))
    const smoothedHeightsRef = useRef<number[]>(Array.from({ length: BAR_COUNT }, () => 0))

    const bars = useMemo(() => Array.from({ length: BAR_COUNT }, (_, index) => index), [])

    useEffect(() => {
        if (dataRef.current.length !== analyser.binCount) {
            dataRef.current = new Uint8Array(analyser.binCount)
        }
    }, [analyser.binCount])

    useEffect(() => {
        let frameId = 0
        let stopped = false
        let colorOffset = 0

        const tick = () => {
            if (stopped) {
                return
            }
            const buffer = dataRef.current
            analyser.read(buffer)

            let maxValue = -1
            let maxIndex = 0
            const heights: number[] = new Array(BAR_COUNT)

            for (let i = 0; i < BAR_COUNT; i += 1) {
                let sum = 0
                const [start, end] = resolveLogBandRange(i, buffer.length)
                for (let j = start; j < end; j += 1) {
                    sum += buffer[j]
                }
                const bandPosition = i / Math.max(1, BAR_COUNT - 1)
                const bandGain = 1 + bandPosition * HIGH_FREQUENCY_GAIN
                const avg = (sum / Math.max(1, end - start) / 255) * bandGain
                const gated = Math.max(0, avg - NOISE_FLOOR) / (1 - NOISE_FLOOR)
                const visualHeight = Math.min(1, Math.pow(gated, RESPONSE_GAMMA) * PEAK_GAIN)
                const previousHeight = smoothedHeightsRef.current[i] ?? 0
                const smoothing = visualHeight > previousHeight ? ATTACK_SMOOTHING : RELEASE_SMOOTHING
                const smoothedHeight = previousHeight + (visualHeight - previousHeight) * smoothing
                smoothedHeightsRef.current[i] = smoothedHeight
                heights[i] = smoothedHeight
                if (avg > maxValue) {
                    maxValue = avg
                    maxIndex = i
                }
            }

            colorOffset = (colorOffset + 0.5) % 360

            for (let i = 0; i < BAR_COUNT; i += 1) {
                const element = barRefs.current[i]
                if (!element) {
                    continue
                }
                const heightPct = Math.max(MIN_VISIBLE_HEIGHT, heights[i] * MAX_VISIBLE_HEIGHT)
                element.style.height = `${heightPct}%`
                const hue = ((i / BAR_COUNT) * 360 + colorOffset) % 360
                element.style.background = `hsl(${hue}, 85%, 60%)`
            }

            frameId = requestAnimationFrame(tick)
        }

        const handleVisibility = () => {
            if (document.visibilityState === 'hidden') {
                cancelAnimationFrame(frameId)
            } else if (!stopped) {
                frameId = requestAnimationFrame(tick)
            }
        }

        frameId = requestAnimationFrame(tick)
        document.addEventListener('visibilitychange', handleVisibility)

        return () => {
            stopped = true
            cancelAnimationFrame(frameId)
            document.removeEventListener('visibilitychange', handleVisibility)
        }
    }, [analyser, accentHex])

    return (
        <div
            className="pointer-events-none flex h-full w-full max-w-6xl items-end justify-center gap-[2px] px-6 sm:gap-[3px] md:gap-1"
            data-state={isPlaying ? 'playing' : 'paused'}
        >
            {bars.map((index) => {
                const hue = (index / BAR_COUNT) * 360
                return (
                    <span
                        key={index}
                        ref={(node) => {
                            barRefs.current[index] = node
                        }}
                        className="block min-w-[2px] max-w-[14px] flex-1 rounded-t-[2px] transition-[background-color] duration-300"
                        style={{
                            height: '6%',
                            background: `hsl(${hue}, 85%, 60%)`,
                        }}
                    />
                )
            })}
        </div>
    )
}

export default BarsVisualizer
