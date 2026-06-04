import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { cwd, exit } from 'node:process'

const root = cwd()
const read = (path) => {
    const absolutePath = join(root, path)
    return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : ''
}

const frame = read('src/components/visualizer/SpectrumVisualizerFrame.tsx')
const bars = read('src/components/visualizer/animations/BarsVisualizer.tsx')
const hero = read('src/components/home/HeroEqBanner.tsx')
const overlay = read('src/components/visualizer/EqOverlay.tsx')
const publicPlayer = read('src/components/public-curation/PublicMixSpectrumPlayer.tsx')

const passed =
    /data-spectrum-visualizer-frame/.test(frame) &&
    /BarsVisualizer/.test(frame) &&
    /LOW/.test(frame) &&
    /MID/.test(frame) &&
    /HIGH/.test(frame) &&
    /\.\.\.containerProps/.test(frame) &&
    /pointer-events-none flex h-full/.test(bars) &&
    !/pointer-events-none flex h-60/.test(bars) &&
    /SpectrumVisualizerFrame/.test(hero) &&
    /SpectrumVisualizerFrame/.test(overlay) &&
    /SpectrumVisualizerFrame/.test(publicPlayer)

if (!passed) {
    console.error('FAIL Shared spectrum frame should be reused by home, visualizer, and public mix player.')
    exit(1)
}

console.log('PASS Shared spectrum frame is reused by home, visualizer, and public mix player.')
