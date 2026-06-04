import BarsVisualizer from '@/components/visualizer/animations/BarsVisualizer'
import type { VisualizerAnimationProps } from '@/components/visualizer/animations/types'

interface SpectrumVisualizerFrameProps extends VisualizerAnimationProps {
    className?: string
    showLabels?: boolean
}

const SpectrumVisualizerFrame = ({
    analyser,
    accentHex,
    isPlaying,
    className = '',
    showLabels = true,
    ...containerProps
}: SpectrumVisualizerFrameProps) => (
    <div
        {...containerProps}
        data-spectrum-visualizer-frame
        className={`relative overflow-hidden ${className}`}
    >
        {[25, 50, 75].map((level) => (
            <span
                key={level}
                className="absolute inset-x-0 border-t border-white/[0.07]"
                style={{ bottom: `${level}%` }}
            />
        ))}
        {showLabels && (
            <div className="absolute inset-x-0 bottom-2 z-10 flex justify-between px-5 text-[10px] font-black uppercase tracking-[0.24em] text-white/34">
                <span>LOW</span>
                <span>MID</span>
                <span>HIGH</span>
            </div>
        )}
        <div className="absolute inset-x-0 bottom-0 flex h-full items-end justify-center">
            <BarsVisualizer analyser={analyser} accentHex={accentHex} isPlaying={isPlaying} />
        </div>
    </div>
)

export default SpectrumVisualizerFrame
