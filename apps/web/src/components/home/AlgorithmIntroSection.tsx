import { ArrowRight, Brain, Gauge, Library, Newspaper, ShieldCheck, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'

const signalCards = [
    {
        label: 'PMS',
        title: '내가 실제로 저장한 음악',
        detail: '가져온 플레이리스트, 좋아요, 완청, 스킵을 취향 기준선으로 쌓습니다.',
        icon: <Library size={19} />,
        tone: 'text-hud-accent-primary bg-hud-accent-primary/10 border-hud-accent-primary/25',
    },
    {
        label: 'EMS',
        title: '외부에서 발견된 후보',
        detail: '매거진, FLO 스페셜, 공개 플레이리스트를 수집해 바깥 음악 풀을 넓힙니다.',
        icon: <Newspaper size={19} />,
        tone: 'text-hud-accent-info bg-hud-accent-info/10 border-hud-accent-info/25',
    },
    {
        label: 'GMS',
        title: '개인 모델이 고르는 관문',
        detail: '내 취향과 새로움을 함께 보며 메인에 올릴 후보만 다시 정렬합니다.',
        icon: <Sparkles size={19} />,
        tone: 'text-amber-300 bg-amber-400/10 border-amber-300/25',
    },
]

const axisChips = ['취향 일치도', '새로움', '흐름 안정성', '다양성', '반복 위험', '근거 신뢰도']

const AlgorithmIntroSection = () => {
    return (
        <section className="relative overflow-hidden rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 shadow-hud">
            <div className="grid gap-0 xl:grid-cols-[minmax(0,1.1fr)_minmax(360px,0.9fr)]">
                <div className="px-5 py-6 sm:px-7 sm:py-7">
                    <div className="flex flex-wrap items-center gap-2">
                        <span className="inline-flex items-center gap-2 rounded-full border border-hud-accent-primary/25 bg-hud-accent-primary/10 px-3 py-1 text-[11px] font-semibold uppercase tracking-[0.22em] text-hud-accent-primary">
                            <Brain size={14} />
                            개인 추천 엔진
                        </span>
                        <span className="rounded-full border border-hud-border-secondary bg-hud-bg-primary/60 px-3 py-1 text-[11px] uppercase tracking-[0.18em] text-hud-text-muted">
                            PMS + EMS → GMS
                        </span>
                    </div>

                    <h2 className="mt-4 max-w-3xl text-2xl font-semibold leading-tight text-hud-text-primary sm:text-3xl">
                        내가 들은 것과 세상이 발견한 것을 한 줄의 플레이리스트로 압축합니다.
                    </h2>
                    <p className="mt-3 max-w-3xl text-sm leading-7 text-hud-text-secondary">
                        메인의 추천은 단순 인기 차트가 아닙니다. 내 라이브러리의 습관, 외부 큐레이션 풀,
                        그리고 사용자별 모델 점수를 함께 읽어서 지금 들을 만한 후보를 고릅니다.
                    </p>

                    <div className="mt-5 grid gap-3 md:grid-cols-3">
                        {signalCards.map((card) => (
                            <div key={card.label} className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <div className="flex items-center gap-2">
                                    <span className={`flex h-9 w-9 items-center justify-center rounded-lg border ${card.tone}`}>
                                        {card.icon}
                                    </span>
                                    <div>
                                        <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-hud-text-muted">{card.label}</p>
                                        <p className="text-sm font-semibold text-hud-text-primary">{card.title}</p>
                                    </div>
                                </div>
                                <p className="mt-3 text-xs leading-5 text-hud-text-secondary">{card.detail}</p>
                            </div>
                        ))}
                    </div>

                    <div className="mt-5 flex flex-wrap items-center gap-2">
                        {axisChips.map((axis) => (
                            <span key={axis} className="rounded-full border border-hud-border-secondary bg-hud-bg-primary/70 px-3 py-1 text-xs text-hud-text-secondary">
                                {axis}
                            </span>
                        ))}
                    </div>
                </div>

                <div className="border-t border-hud-border-secondary bg-hud-bg-primary/60 px-5 py-6 sm:px-7 xl:border-l xl:border-t-0">
                    <div className="space-y-3">
                        <div className="flex items-start gap-3 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-4">
                            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-emerald-400/10 text-emerald-300">
                                <ShieldCheck size={19} />
                            </span>
                            <div>
                                <p className="text-sm font-semibold text-hud-text-primary">실제 데이터만 추천에 반영</p>
                                <p className="mt-1 text-xs leading-5 text-hud-text-secondary">
                                    프로바이더 매칭 실패, 오디오 특성 부족, 모델 신뢰도 저하는 숨기지 않고 점수와 화면에 남깁니다.
                                </p>
                            </div>
                        </div>
                        <div className="flex items-start gap-3 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-4">
                            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-cyan-400/10 text-cyan-300">
                                <Gauge size={19} />
                            </span>
                            <div>
                                <p className="text-sm font-semibold text-hud-text-primary">추천마다 6축 근거 제공</p>
                                <p className="mt-1 text-xs leading-5 text-hud-text-secondary">
                                    적합도, 새로움, 일관성, 다양성, 중복도, 신뢰도를 나눠서 왜 추천됐는지 추적합니다.
                                </p>
                            </div>
                        </div>
                    </div>

                    <Link
                        to="/about/recommendation"
                        className="mt-5 inline-flex w-full items-center justify-center gap-2 rounded-lg bg-hud-accent-primary px-4 py-3 text-sm font-semibold text-hud-bg-primary transition-hud hover:bg-hud-accent-primary/90"
                    >
                        추천 엔진 자세히 보기
                        <ArrowRight size={16} />
                    </Link>
                </div>
            </div>
        </section>
    )
}

export default AlgorithmIntroSection
