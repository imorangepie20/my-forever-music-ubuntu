import { ArrowLeft } from 'lucide-react'
import { Link } from 'react-router-dom'
import HudCard from '@/components/common/HudCard'
import MelonChartRow from '@/components/home/MelonChartRow'
import { useMelonHot100 } from '@/hooks/useMelonHot100'

const MelonHot100Page = () => {
    const state = useMelonHot100(null, true)

    return (
        <div className="space-y-5">
            <header className="flex items-center justify-between gap-3">
                <Link
                    to="/"
                    className="inline-flex items-center gap-2 text-sm text-hud-text-secondary transition-hud hover:text-hud-text-primary"
                >
                    <ArrowLeft size={16} />
                    홈으로 돌아가기
                </Link>
                {state.status === 'ready' && state.snapshotAt && (
                    <span className="text-xs text-hud-text-muted">
                        {new Date(state.snapshotAt).toLocaleString()}
                    </span>
                )}
            </header>

            <HudCard
                title="Melon Hot 100"
                subtitle="지금 많이 듣는 곡들을 한눈에 확인하고 바로 재생할 수 있습니다."
            >
                {state.status === 'loading' && (
                    <p className="text-sm text-hud-text-secondary">차트를 불러오는 중입니다.</p>
                )}
                {state.status === 'error' && (
                    <p className="text-sm text-amber-300">{state.error}</p>
                )}
                {state.status === 'empty' && (
                    <p className="text-sm text-hud-text-secondary">
                        아직 표시할 Melon Hot 100 데이터가 없습니다.
                    </p>
                )}
                {state.status === 'ready' && (
                    <ol className="space-y-2">
                        {state.tracks.map((track) => (
                            <li key={`${track.rank}-${track.melon_song_id ?? track.title}`}>
                                <MelonChartRow track={track} />
                            </li>
                        ))}
                    </ol>
                )}
            </HudCard>
        </div>
    )
}

export default MelonHot100Page
