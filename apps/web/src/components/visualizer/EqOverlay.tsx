import { useEffect, useMemo, useState } from 'react'
import RadialBloomVisualizer from './animations/RadialBloomVisualizer'
import type { VisualizerAnimationProps } from './animations/types'
import SpectrumVisualizerFrame from './SpectrumVisualizerFrame'

export type AnimationId = 'bars'

const ANIMATIONS: AnimationId[] = ['bars']

interface EqOverlayProps extends VisualizerAnimationProps {
    trackKey: string
    forcedAnimation?: AnimationId | null
}

const pickRandom = (key: string): AnimationId => {
    let hash = 0
    for (let i = 0; i < key.length; i += 1) {
        hash = (hash * 31 + key.charCodeAt(i)) | 0
    }
    return ANIMATIONS[Math.abs(hash) % ANIMATIONS.length]
}

const EqOverlay = ({ trackKey, forcedAnimation, ...animationProps }: EqOverlayProps) => {
    const initial = useMemo(() => forcedAnimation ?? pickRandom(trackKey), [forcedAnimation, trackKey])
    const [active, setActive] = useState<AnimationId>(initial)

    useEffect(() => {
        setActive(forcedAnimation ?? pickRandom(trackKey))
    }, [trackKey, forcedAnimation])

    if (active === 'bars') {
        return (
            <SpectrumVisualizerFrame
                {...animationProps}
                className="h-full w-full"
            />
        )
    }
    return <RadialBloomVisualizer {...animationProps} />
}

export default EqOverlay
