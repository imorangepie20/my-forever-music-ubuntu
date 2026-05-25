import {
    Activity,
    ArrowLeft,
    ArrowRight,
    AudioLines,
    Brain,
    CheckCircle2,
    ChevronRight,
    CircleDot,
    Compass,
    Database,
    Gauge,
    HeartPulse,
    Library,
    ListChecks,
    LockKeyhole,
    Music2,
    Network,
    Radio,
    RefreshCcw,
    Search,
    ShieldCheck,
    SlidersHorizontal,
    Sparkles,
    Target,
    Workflow,
    Zap,
    type LucideIcon,
} from 'lucide-react'
import { Link } from 'react-router-dom'

interface SystemNode {
    id: string
    eyebrow: string
    title: string
    subtitle: string
    description: string
    inputs: string[]
    output: string
    icon: LucideIcon
    tone: string
}

interface DataStream {
    title: string
    description: string
    metric: string
    icon: LucideIcon
    tone: string
    iconTone: string
}

interface PipelineStep {
    phase: string
    title: string
    description: string
    evidence: string[]
    icon: LucideIcon
}

interface ScoreAxis {
    name: string
    label: string
    description: string
    strength: number
    direction: string
    tone: string
}

interface QualityGate {
    title: string
    description: string
    icon: LucideIcon
}

const systemNodes: SystemNode[] = [
    {
        id: 'pms-intake',
        eyebrow: '01 · 내 취향',
        title: 'PMS 입력',
        subtitle: '사용자 소유 음악 라이브러리',
        description: '구독 플랫폼에서 가져온 playlist와 track을 PMS canonical library로 승격해 장기 취향의 기준선으로 삼습니다.',
        inputs: ['가져온 플레이리스트', '저장한 GMS 추천곡', '좋아요 / 넘김 신호'],
        output: '취향 기준선',
        icon: Library,
        tone: 'border-emerald-300/45 bg-emerald-400/10 text-emerald-100',
    },
    {
        id: 'signal-normalizer',
        eyebrow: '02 · 신호 정리',
        title: '신호 정규화',
        subtitle: '트랙 식별과 오디오 특성 정규화',
        description: 'title, artist, ISRC, playback target, provider-neutral audio feature를 분리해 서로 비교 가능한 신호로 맞춥니다.',
        inputs: ['ISRC / 메타데이터', '오디오 특성 스냅샷', '재생 대상'],
        output: '비교 가능한 트랙',
        icon: AudioLines,
        tone: 'border-sky-300/40 bg-sky-400/10 text-sky-100',
    },
    {
        id: 'candidate-refinery',
        eyebrow: '03 · 발견',
        title: 'EMS 후보 정제',
        subtitle: 'EMS 후보군 압축',
        description: '외부 공개 playlist, editorial source, 트렌딩 데이터에서 후보를 가져오고 중복과 근거 부족 후보를 먼저 정리합니다.',
        inputs: ['EMS 플레이리스트 풀', '에디터 출처', '트렌딩 트랙'],
        output: '순위화 가능한 후보',
        icon: Compass,
        tone: 'border-violet-300/45 bg-violet-400/10 text-violet-100',
    },
    {
        id: 'personal-ranking',
        eyebrow: '04 · 개인 순위',
        title: '개인화 순위',
        subtitle: '개인 모델과 fast-path 재정렬',
        description: 'SASRec 시퀀스 모델, personalization profile, 최근 행동 신호를 섞어 사용자별 순서를 다시 만듭니다.',
        inputs: ['SASRec 모델', '아티스트 선호', '출처 선호'],
        output: '개인화된 순서',
        icon: Brain,
        tone: 'border-indigo-300/45 bg-indigo-400/10 text-indigo-100',
    },
    {
        id: 'gms-delivery',
        eyebrow: '05 · 게이트',
        title: 'GMS 추천 출력',
        subtitle: '추천 playlist 조립',
        description: '6축 점수와 품질 게이트를 통과한 후보만 추천 playlist와 track shelf에 올립니다.',
        inputs: ['6축 점수', '커버리지 점검', '감사 스냅샷'],
        output: '설명 가능한 플레이리스트',
        icon: Sparkles,
        tone: 'border-amber-300/45 bg-amber-400/10 text-amber-100',
    },
    {
        id: 'feedback-flywheel',
        eyebrow: '06 · 다시 학습',
        title: '피드백 학습 루프',
        subtitle: '평가와 행동 데이터 환류',
        description: '재생, 완청, 스킵, 좋아요, 저장, 거부를 다시 PMS 학습 데이터로 보내 다음 추천 batch를 선명하게 만듭니다.',
        inputs: ['재생 이벤트', '저장 / 거부', '플레이리스트 편집'],
        output: '다음 학습 신호',
        icon: RefreshCcw,
        tone: 'border-lime-300/45 bg-lime-400/10 text-lime-100',
    },
]

const dataStreams: DataStream[] = [
    {
        title: 'PMS 사용자 보관함',
        description: '사용자가 가져온 playlist와 직접 저장한 추천곡이 장기 취향의 기준점입니다.',
        metric: '소유 취향',
        icon: Database,
        tone: 'border-emerald-300/35 bg-emerald-400/10',
        iconTone: 'border-emerald-300/25 text-emerald-200',
    },
    {
        title: 'EMS 탐색 풀',
        description: '외부 공개 playlist와 트렌딩 소스가 새 후보를 계속 공급합니다.',
        metric: '새 후보',
        icon: Radio,
        tone: 'border-violet-300/35 bg-violet-400/10',
        iconTone: 'border-violet-300/25 text-violet-200',
    },
    {
        title: '행동 이벤트',
        description: '재생 완료, 반복, 스킵, 저장, 거부가 추천 모델의 가장 빠른 보정 신호입니다.',
        metric: '실시간 피드백',
        icon: Activity,
        tone: 'border-rose-300/35 bg-rose-400/10',
        iconTone: 'border-rose-300/25 text-rose-200',
    },
    {
        title: '품질 지표',
        description: 'coverage, drift, audit log가 추천 품질 저하를 숨기지 않고 드러냅니다.',
        metric: '운영 신뢰',
        icon: ShieldCheck,
        tone: 'border-sky-300/35 bg-sky-400/10',
        iconTone: 'border-sky-300/25 text-sky-200',
    },
]

const pipelineSteps: PipelineStep[] = [
    {
        phase: 'A',
        title: '가져오고 보존',
        description: 'Spotify, TIDAL 같은 출처에서 playlist를 가져오되 원본 플랫폼에 묶어두지 않고 PMS 소유 데이터로 보존합니다.',
        evidence: ['플랫폼 계정', '플레이리스트 스냅샷', '표준 사용자 플레이리스트'],
        icon: Library,
    },
    {
        phase: 'B',
        title: '식별하고 보강',
        description: '트랙 identity, album image, playback target, provider-neutral audio feature를 분리 저장하고 실패한 보강은 unresolved로 남깁니다.',
        evidence: ['ISRC', '오디오 특성 출처', '재생 대상 상태'],
        icon: Search,
    },
    {
        phase: 'C',
        title: '외부 후보 수집',
        description: 'EMS는 공개 playlist와 editorial source에서 후보를 모아 중복, 빈 playlist, 근거 부족 후보를 정리합니다.',
        evidence: ['EMS 수집 풀', '출처 프리셋', '중복 제거 결과'],
        icon: Compass,
    },
    {
        phase: 'D',
        title: '사용자별 순위화',
        description: 'PMS 기준 취향, Last.fm signal, 사이트 내부 행동, SASRec sequence score를 결합해 추천 순서를 개인별로 다르게 만듭니다.',
        evidence: ['개인화 프로필', '시퀀스 점수', '재정렬 경고'],
        icon: Brain,
    },
    {
        phase: 'E',
        title: '플레이리스트 조립',
        description: '적합도만 높이는 대신 새로움, 일관성, 다양성, 중복도, 신뢰도를 함께 보고 플레이리스트 단위의 품질을 맞춥니다.',
        evidence: ['6축 판정', '플레이리스트 일관성', '신뢰도 점수'],
        icon: SlidersHorizontal,
    },
    {
        phase: 'F',
        title: '피드백 저장',
        description: '사용자가 들은 뒤 저장하거나 거부한 결과는 audit log와 user event로 남아 다음 추천의 학습 재료가 됩니다.',
        evidence: ['추천 감사 기록', '피드백 이벤트', '다음 프로필 갱신'],
        icon: HeartPulse,
    },
]

const scoreAxes: ScoreAxis[] = [
    {
        name: '취향 적합도',
        label: '취향 적합도',
        description: '내 PMS 라이브러리의 아티스트, 장르, 오디오 특성과 얼마나 가까운지 봅니다.',
        strength: 92,
        direction: '높을수록 추천 우선',
        tone: 'bg-emerald-300',
    },
    {
        name: '새로움',
        label: '새로움',
        description: '이미 아는 음악만 반복하지 않도록 낯선 후보의 탐색 가치를 보정합니다.',
        strength: 76,
        direction: '적당히 높게 유지',
        tone: 'bg-violet-300',
    },
    {
        name: '흐름 안정성',
        label: '플레이리스트 일관성',
        description: '추천 playlist가 하나의 장면, 템포, 분위기를 자연스럽게 유지하는지 평가합니다.',
        strength: 84,
        direction: 'playlist 단위 검증',
        tone: 'bg-sky-300',
    },
    {
        name: '다양성',
        label: '다양성',
        description: '같은 아티스트와 같은 무드만 반복하지 않고 취향의 빈 공간을 넓히는지 확인합니다.',
        strength: 68,
        direction: '편향 완화',
        tone: 'bg-lime-300',
    },
    {
        name: '중복도',
        label: '중복도',
        description: '기존 PMS 목록, 최근 추천, 같은 playlist 내부에서 지나치게 겹치면 페널티를 줍니다.',
        strength: 31,
        direction: '낮을수록 좋음',
        tone: 'bg-amber-300',
    },
    {
        name: '근거 신뢰도',
        label: '근거 신뢰도',
        description: '오디오 특성, ISRC, canonical link, playback target이 충분한지 추천 근거를 분리해 표시합니다.',
        strength: 88,
        direction: '불확실성 공개',
        tone: 'bg-cyan-300',
    },
]

const qualityGates: QualityGate[] = [
    {
        title: '실제 데이터만 사용',
        description: '사용자 화면에는 mock, sandbox, 임의 생성 취향 데이터를 기본값으로 노출하지 않습니다.',
        icon: LockKeyhole,
    },
    {
        title: '미해결 상태는 보이게 유지',
        description: '오디오 특성이나 playback target을 확보하지 못한 트랙은 가짜 값으로 채우지 않고 상태를 드러냅니다.',
        icon: CircleDot,
    },
    {
        title: '콜드스타트는 명시',
        description: 'PMS가 비어 있으면 EMS fallback을 쓰되 개인화가 약하다는 사실을 경고와 audit log로 남깁니다.',
        icon: Gauge,
    },
    {
        title: '모델 승격은 측정 후 진행',
        description: 'SASRec 모델은 baseline 대비 Hit@K, MRR, nDCG 개선이 확인된 경우에만 승격합니다.',
        icon: Target,
    },
]

const operatingMetrics = [
    {
        label: '기준 데이터',
        value: 'PMS',
        detail: '소유 취향 기준',
        tone: 'border-emerald-300/35 bg-emerald-400/10',
        valueTone: 'text-emerald-100',
    },
    {
        label: '후보 데이터',
        value: 'EMS',
        detail: '외부 후보 풀',
        tone: 'border-violet-300/35 bg-violet-400/10',
        valueTone: 'text-violet-100',
    },
    {
        label: '결정 레이어',
        value: 'GMS',
        detail: '추천 게이트',
        tone: 'border-amber-300/35 bg-amber-400/10',
        valueTone: 'text-amber-100',
    },
]

const colorLegend = [
    {
        label: 'PMS · 민트',
        detail: '사용자 소유 취향',
        swatch: 'bg-emerald-300',
        tone: 'border-emerald-300/35 bg-emerald-400/10 text-emerald-100',
    },
    {
        label: 'EMS · 보라',
        detail: '외부 후보 풀',
        swatch: 'bg-violet-300',
        tone: 'border-violet-300/35 bg-violet-400/10 text-violet-100',
    },
    {
        label: 'GMS · 골드',
        detail: '추천 출력',
        swatch: 'bg-amber-300',
        tone: 'border-amber-300/35 bg-amber-400/10 text-amber-100',
    },
    {
        label: '피드백 · 그린',
        detail: '학습 환류',
        swatch: 'bg-lime-300',
        tone: 'border-lime-300/35 bg-lime-400/10 text-lime-100',
    },
]

const signalDiagramSources = [
    {
        title: '취향 그래프',
        description: 'PMS 플레이리스트, 저장곡, 좋아요, 넘김',
        icon: Library,
        tone: 'border-emerald-300/45 bg-emerald-400/10 text-emerald-100',
    },
    {
        title: '탐색 후보 풀',
        description: 'EMS 공개 플레이리스트와 에디터 출처',
        icon: Compass,
        tone: 'border-violet-300/45 bg-violet-400/10 text-violet-100',
    },
    {
        title: '행동 스트림',
        description: '재생, 반복, 저장, 거부, 조기 스킵',
        icon: Activity,
        tone: 'border-rose-300/45 bg-rose-400/10 text-rose-100',
    },
]

const scoringEngineStages = [
    '식별 정보 정규화',
    '6축 점수화',
    '개인화 재정렬',
    '플레이리스트 조립',
]

const RecommendationAlgorithmPage = () => {
    return (
        <div className="space-y-8">
            <header className="flex flex-wrap items-center justify-between gap-3">
                <Link
                    to="/"
                    className="inline-flex items-center gap-2 text-sm text-hud-text-secondary transition-hud hover:text-hud-text-primary"
                >
                    <ArrowLeft size={16} />
                    메인으로 돌아가기
                </Link>
                <div className="flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 px-3 py-2 text-xs text-hud-text-muted">
                    <Network size={14} />
                    PMS · EMS · GMS 추천 지도
                </div>
            </header>

            <section className="overflow-hidden rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/90 shadow-hud">
                <div className="grid gap-0 xl:grid-cols-[minmax(0,1.08fr)_minmax(380px,0.92fr)]">
                    <div className="px-6 py-8 sm:px-8 lg:px-10 lg:py-12">
                        <p className="inline-flex items-center gap-2 rounded-lg border border-hud-accent-primary/30 bg-hud-accent-primary/10 px-3 py-1.5 text-xs font-semibold text-hud-accent-primary">
                            <Workflow size={15} />
                            플레이리스트 추천 과정
                        </p>
                        <h1 className="mt-5 text-3xl font-semibold leading-tight text-hud-text-primary sm:text-4xl">
                            추천 운영 시스템
                        </h1>
                        <p className="mt-4 max-w-3xl text-sm leading-7 text-hud-text-secondary sm:text-base">
                            My Forever Music의 추천은 하나의 모델 결과가 아니라, 사용자의 PMS 라이브러리와 EMS 외부 후보,
                            GMS 품질 게이트가 순서대로 맞물리는 운영 흐름입니다. 이 페이지는 추천 playlist가 만들어지고,
                            검증되고, 다시 사용자 행동으로 학습되는 전체 과정을 도표로 보여줍니다.
                        </p>

                        <div className="mt-7 grid gap-3 sm:grid-cols-3">
                            {operatingMetrics.map((metric) => (
                                <div key={metric.label} className={`rounded-lg border p-4 ${metric.tone}`}>
                                    <p className="text-xs text-hud-text-muted">{metric.label}</p>
                                    <p className={`mt-2 text-2xl font-semibold ${metric.valueTone}`}>{metric.value}</p>
                                    <p className="mt-1 text-xs leading-5 text-hud-text-secondary">{metric.detail}</p>
                                </div>
                            ))}
                        </div>

                        <div
                            aria-label="추천 색상 범례"
                            className="mt-5 grid gap-2 sm:grid-cols-2 xl:grid-cols-4"
                        >
                            {colorLegend.map((item) => (
                                <div key={item.label} className={`rounded-lg border px-3 py-2 ${item.tone}`}>
                                    <div className="flex items-center gap-2">
                                        <span className={`h-2.5 w-2.5 rounded-full ${item.swatch}`} />
                                        <p className="text-xs font-semibold">{item.label}</p>
                                    </div>
                                    <p className="mt-1 text-[11px] leading-4 text-hud-text-secondary">{item.detail}</p>
                                </div>
                            ))}
                        </div>

                        <div className="mt-7 flex flex-wrap gap-3">
                            <Link
                                to="/gms-playlists"
                                className="inline-flex items-center gap-2 rounded-lg bg-hud-accent-primary px-4 py-2.5 text-sm font-semibold text-hud-bg-primary transition-hud hover:bg-hud-accent-primary/90"
                            >
                                추천 플레이리스트 보기
                                <ArrowRight size={16} />
                            </Link>
                            <Link
                                to="/recommendations/feature-coverage"
                                className="inline-flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-2.5 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                            >
                                품질 대시보드
                            </Link>
                        </div>
                    </div>

                    <aside className="border-t border-hud-border-secondary bg-hud-bg-primary/70 p-5 sm:p-6 xl:border-l xl:border-t-0">
                        <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/85 p-4">
                            <div className="flex items-center justify-between gap-3">
                                <div>
                                    <p className="text-sm font-semibold text-hud-text-primary">실시간 추천 신호</p>
                                    <p className="mt-1 text-xs leading-5 text-hud-text-muted">추천 계산에 들어가는 네 종류의 신호</p>
                                </div>
                                <Zap size={18} className="text-hud-accent-primary" />
                            </div>
                            <div className="mt-4 space-y-3">
                                {dataStreams.map((stream) => {
                                    const Icon = stream.icon
                                    return (
                                        <div key={stream.title} className={`grid grid-cols-[40px_minmax(0,1fr)_auto] items-center gap-3 rounded-lg border p-3 ${stream.tone}`}>
                                            <span className={`flex h-10 w-10 items-center justify-center rounded-lg border bg-hud-bg-primary/40 ${stream.iconTone}`}>
                                                <Icon size={18} />
                                            </span>
                                            <div className="min-w-0">
                                                <p className="truncate text-sm font-semibold text-hud-text-primary">{stream.title}</p>
                                                <p className="mt-0.5 text-xs leading-5 text-hud-text-secondary">{stream.description}</p>
                                            </div>
                                            <span className="hidden rounded-lg border border-hud-border-secondary px-2 py-1 text-xs text-hud-text-muted sm:inline-flex">
                                                {stream.metric}
                                            </span>
                                        </div>
                                    )
                                })}
                            </div>
                        </div>
                    </aside>
                </div>
            </section>

            <section
                aria-label="추천 신호 흐름 도표"
                className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/85 p-4 shadow-hud sm:p-5"
            >
                <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                    <div>
                        <p className="text-xs font-semibold text-hud-accent-primary">흐름 도표</p>
                        <h2 className="mt-2 text-2xl font-semibold text-hud-text-primary">신호가 추천 playlist로 바뀌는 지도</h2>
                    </div>
                    <p className="max-w-2xl text-sm leading-6 text-hud-text-secondary">
                        왼쪽의 사용자 취향과 외부 후보가 중앙 엔진에서 점수화되고, 오른쪽의 GMS 출력과 피드백 루프로 돌아갑니다.
                    </p>
                </div>

                <div className="mt-5 grid gap-4 2xl:grid-cols-[minmax(240px,0.9fr)_56px_minmax(320px,1.15fr)_56px_minmax(240px,0.95fr)] 2xl:items-center">
                    <div className="space-y-3">
                        {signalDiagramSources.map((source) => {
                            const Icon = source.icon
                            return (
                                <div key={source.title} className={`rounded-lg border p-4 ${source.tone}`}>
                                    <div className="flex items-center gap-3">
                                        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-current/30 bg-hud-bg-primary/30">
                                            <Icon size={18} />
                                        </span>
                                        <div>
                                            <p className="text-sm font-semibold text-hud-text-primary">{source.title}</p>
                                            <p className="mt-1 text-xs leading-5 text-hud-text-secondary">{source.description}</p>
                                        </div>
                                    </div>
                                </div>
                            )
                        })}
                    </div>

                    <div className="flex items-center justify-center">
                        <div className="flex h-12 w-full items-center justify-center rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 text-hud-accent-primary 2xl:h-24 2xl:w-12">
                            <ArrowRight size={22} className="rotate-90 2xl:rotate-0" />
                        </div>
                    </div>

                    <div className="rounded-lg border border-sky-300/45 bg-sky-400/10 p-5 shadow-[0_0_36px_rgba(56,189,248,0.12)]">
                        <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
                            <div>
                                <p className="text-xs font-semibold text-sky-200">엔진 코어</p>
                                <h3 className="mt-2 text-2xl font-semibold text-hud-text-primary">점수화 엔진</h3>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    후보를 track 단위로만 보지 않고 playlist 경험으로 조립하기 위해 identity, score, rank, assembly를 한 번에 통과시킵니다.
                                </p>
                            </div>
                            <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-lg border border-sky-300/35 bg-sky-400/10 text-sky-100">
                                <SlidersHorizontal size={24} />
                            </span>
                        </div>

                        <div className="mt-5 grid gap-3 sm:grid-cols-2">
                            {scoringEngineStages.map((stage, index) => (
                                <div key={stage} className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 p-3">
                                    <div className="flex items-center gap-2">
                                        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-sky-400/15 text-xs font-semibold text-sky-100">
                                            {index + 1}
                                        </span>
                                        <p className="text-sm font-semibold text-hud-text-primary">{stage}</p>
                                    </div>
                                </div>
                            ))}
                        </div>

                        <div className="mt-5 grid gap-3 md:grid-cols-3">
                            {['취향 적합도', '새로움 균형', '신뢰도 게이트'].map((label) => (
                                <div key={label} className="flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/70 px-3 py-2">
                                    <CheckCircle2 size={15} className="text-sky-200" />
                                    <span className="text-xs font-medium text-hud-text-secondary">{label}</span>
                                </div>
                            ))}
                        </div>
                    </div>

                    <div className="flex items-center justify-center">
                        <div className="flex h-12 w-full items-center justify-center rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 text-hud-accent-primary 2xl:h-24 2xl:w-12">
                            <ArrowRight size={22} className="rotate-90 2xl:rotate-0" />
                        </div>
                    </div>

                    <div className="space-y-3">
                        <div className="rounded-lg border border-amber-300/35 bg-amber-400/10 p-4">
                            <div className="flex items-center gap-3">
                                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-amber-300/30 bg-hud-bg-primary/30 text-amber-200">
                                    <Sparkles size={18} />
                                </span>
                                <div>
                                    <p className="text-sm font-semibold text-hud-text-primary">GMS 추천 출력</p>
                                    <p className="mt-1 text-xs leading-5 text-hud-text-secondary">추천 선반, 플레이리스트 미리보기, PMS 저장 대상</p>
                                </div>
                            </div>
                        </div>
                        <div className="rounded-lg border border-emerald-300/35 bg-emerald-400/10 p-4">
                            <div className="flex items-center gap-3">
                                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-lime-300/30 bg-hud-bg-primary/30 text-lime-200">
                                    <RefreshCcw size={18} />
                                </span>
                                <div>
                                    <p className="text-sm font-semibold text-hud-text-primary">피드백 루프</p>
                                    <p className="mt-1 text-xs leading-5 text-hud-text-secondary">재생, 스킵, 저장, 거부 이벤트가 PMS로 돌아갑니다.</p>
                                </div>
                            </div>
                        </div>
                        <div className="rounded-lg border border-lime-300/25 bg-lime-400/10 p-4">
                            <div className="flex items-center justify-between gap-3 text-xs text-hud-text-muted">
                                <span>PMS 학습 신호</span>
                                <RefreshCcw size={15} className="text-lime-200" />
                            </div>
                            <div className="mt-3 h-2 rounded-full bg-hud-bg-secondary">
                                <div className="h-2 w-4/5 rounded-full bg-lime-300" />
                            </div>
                        </div>
                    </div>
                </div>
            </section>

            <section className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-4 shadow-hud sm:p-5">
                <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                    <div>
                        <p className="text-xs font-semibold text-sky-200">시스템 도표</p>
                        <h2 className="mt-2 text-2xl font-semibold text-hud-text-primary">플레이리스트 추천 지도</h2>
                    </div>
                    <p className="max-w-2xl text-sm leading-6 text-hud-text-secondary">
                        추천은 왼쪽의 사용자 소유 데이터에서 시작해, 외부 후보를 통과시키고, 오른쪽의 피드백 루프로 다시 돌아옵니다.
                    </p>
                </div>

                <div className="mt-5 grid gap-4 xl:grid-cols-3">
                    {systemNodes.map((node, index) => {
                        const Icon = node.icon
                        return (
                            <article key={node.id} className="relative rounded-lg border border-hud-border-secondary bg-hud-bg-primary/75 p-4">
                                <div className="flex items-start justify-between gap-3">
                                    <div className="flex items-center gap-3">
                                        <span className={`flex h-11 w-11 items-center justify-center rounded-lg border ${node.tone}`}>
                                            <Icon size={20} />
                                        </span>
                                        <div>
                                            <p className="text-xs text-hud-text-muted">{node.eyebrow}</p>
                                            <h3 className="mt-0.5 text-base font-semibold text-hud-text-primary">{node.title}</h3>
                                        </div>
                                    </div>
                                    {index < systemNodes.length - 1 ? (
                                        <ChevronRight size={18} className="mt-3 hidden shrink-0 text-hud-text-muted xl:block" />
                                    ) : (
                                        <RefreshCcw size={18} className="mt-3 hidden shrink-0 text-emerald-300 xl:block" />
                                    )}
                                </div>
                                <p className="mt-3 text-sm font-medium text-hud-text-secondary">{node.subtitle}</p>
                                <p className="mt-2 text-xs leading-5 text-hud-text-secondary">{node.description}</p>
                                <div className="mt-4 space-y-2">
                                    {node.inputs.map((input) => (
                                        <div key={input} className="flex items-center gap-2 text-xs text-hud-text-muted">
                                            <CircleDot size={11} className="text-hud-accent-primary" />
                                            <span>{input}</span>
                                        </div>
                                    ))}
                                </div>
                                <div className="mt-4 rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 px-3 py-2">
                                    <p className="text-xs text-hud-text-muted">출력</p>
                                    <p className="mt-0.5 text-sm font-semibold text-hud-text-primary">{node.output}</p>
                                </div>
                            </article>
                        )
                    })}
                </div>

                <div className="mt-5 grid gap-3 lg:grid-cols-[1fr_auto_1fr_auto_1fr]">
                    <div className="rounded-lg border border-emerald-300/30 bg-emerald-400/10 p-4">
                        <p className="text-sm font-semibold text-hud-text-primary">PMS 입력</p>
                        <p className="mt-2 text-xs leading-5 text-hud-text-secondary">내가 가져온 playlist와 저장한 추천곡이 추천의 출발점입니다.</p>
                    </div>
                    <div className="hidden items-center text-hud-text-muted lg:flex">
                        <ArrowRight size={20} />
                    </div>
                    <div className="rounded-lg border border-violet-300/30 bg-violet-400/10 p-4">
                        <p className="text-sm font-semibold text-hud-text-primary">EMS 후보 정제</p>
                        <p className="mt-2 text-xs leading-5 text-hud-text-secondary">EMS 후보를 정리하고 개인화 모델로 통과시킬 준비를 합니다.</p>
                    </div>
                    <div className="hidden items-center text-hud-text-muted lg:flex">
                        <ArrowRight size={20} />
                    </div>
                    <div className="rounded-lg border border-lime-300/30 bg-lime-400/10 p-4">
                        <p className="text-sm font-semibold text-hud-text-primary">피드백 학습 루프</p>
                        <p className="mt-2 text-xs leading-5 text-hud-text-secondary">재생과 평가가 다시 PMS로 들어와 다음 추천을 더 개인화합니다.</p>
                    </div>
                </div>
            </section>

            <section className="grid gap-4 xl:grid-cols-[0.85fr_1.15fr]">
                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                    <div className="flex items-center justify-between gap-3">
                        <div>
                            <p className="text-xs font-semibold text-hud-accent-primary">데이터 계약</p>
                            <h2 className="mt-2 text-xl font-semibold text-hud-text-primary">추천 입력 데이터</h2>
                        </div>
                        <Database size={20} className="text-hud-accent-primary" />
                    </div>
                    <div className="mt-5 space-y-3">
                        {dataStreams.map((stream) => {
                            const Icon = stream.icon
                            return (
                                <div key={stream.title} className={`flex items-start gap-3 rounded-lg border p-4 ${stream.tone}`}>
                                    <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border bg-hud-bg-primary/40 ${stream.iconTone}`}>
                                        <Icon size={18} />
                                    </span>
                                    <div>
                                        <p className="text-sm font-semibold text-hud-text-primary">{stream.title}</p>
                                        <p className="mt-1 text-xs leading-5 text-hud-text-secondary">{stream.description}</p>
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                </div>

                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                    <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                        <div>
                            <p className="text-xs font-semibold text-hud-accent-primary">파이프라인</p>
                            <h2 className="mt-2 text-xl font-semibold text-hud-text-primary">추천 playlist가 만들어지는 순서</h2>
                        </div>
                        <p className="max-w-lg text-xs leading-5 text-hud-text-muted">
                            각 단계는 화면 노출 전 검증 가능한 산출물을 남깁니다.
                        </p>
                    </div>
                    <div className="mt-5 space-y-3">
                        {pipelineSteps.map((step) => {
                            const Icon = step.icon
                            return (
                                <div key={step.phase} className="grid gap-3 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 p-4 md:grid-cols-[52px_minmax(0,1fr)]">
                                    <div className="flex md:block">
                                        <span className="flex h-11 w-11 items-center justify-center rounded-lg border border-hud-border-secondary bg-hud-bg-secondary text-hud-accent-primary">
                                            <Icon size={18} />
                                        </span>
                                    </div>
                                    <div className="min-w-0">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <span className="rounded-lg border border-hud-border-secondary px-2 py-1 text-xs font-semibold text-hud-text-muted">
                                                단계 {step.phase}
                                            </span>
                                            <h3 className="text-sm font-semibold text-hud-text-primary">{step.title}</h3>
                                        </div>
                                        <p className="mt-2 text-sm leading-6 text-hud-text-secondary">{step.description}</p>
                                        <div className="mt-3 flex flex-wrap gap-2">
                                            {step.evidence.map((item) => (
                                                <span key={item} className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 px-2.5 py-1 text-xs text-hud-text-muted">
                                                    {item}
                                                </span>
                                            ))}
                                        </div>
                                    </div>
                                </div>
                            )
                        })}
                    </div>
                </div>
            </section>

            <section className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                <div className="grid gap-5 xl:grid-cols-[minmax(0,0.88fr)_minmax(0,1.12fr)]">
                    <div>
                        <p className="text-xs font-semibold text-hud-accent-primary">결정 콘솔</p>
                        <h2 className="mt-2 text-2xl font-semibold text-hud-text-primary">6축 판정 보드</h2>
                        <p className="mt-3 text-sm leading-7 text-hud-text-secondary">
                            GMS는 후보를 단순 점수 하나로 자르지 않습니다. 적합도, 새로움, 일관성, 다양성,
                            중복도, 신뢰도를 분리해 playlist로 들었을 때 자연스러운 결과만 남깁니다.
                        </p>

                        <div className="mt-5 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                            <div className="flex items-center gap-3">
                                <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-hud-accent-primary/10 text-hud-accent-primary">
                                    <Gauge size={20} />
                                </span>
                                <div>
                                    <p className="text-sm font-semibold text-hud-text-primary">Composite score는 결과가 아니라 요약입니다.</p>
                                    <p className="mt-1 text-xs leading-5 text-hud-text-secondary">
                                        운영자는 각 축을 따로 보고 어떤 근거가 추천을 밀어 올렸는지 확인할 수 있습니다.
                                    </p>
                                </div>
                            </div>
                        </div>
                    </div>

                    <div className="grid gap-3 md:grid-cols-2">
                        {scoreAxes.map((axis) => (
                            <div key={axis.name} className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <div className="flex items-start justify-between gap-3">
                                    <div>
                                        <h3 className="text-sm font-semibold text-hud-text-primary">{axis.name}</h3>
                                        <p className="mt-0.5 text-xs text-hud-text-muted">{axis.label}</p>
                                    </div>
                                    <span className="rounded-lg border border-hud-border-secondary px-2 py-1 text-xs text-hud-text-muted">
                                        {axis.direction}
                                    </span>
                                </div>
                                <p className="mt-3 text-xs leading-5 text-hud-text-secondary">{axis.description}</p>
                                <div className="mt-4 h-2 rounded-full bg-hud-bg-secondary">
                                    <div
                                        className={`h-2 rounded-full ${axis.tone}`}
                                        style={{ width: `${axis.strength}%` }}
                                    />
                                </div>
                                <p className="mt-2 text-right text-xs font-semibold text-hud-text-muted">{axis.strength}% 신호</p>
                            </div>
                        ))}
                    </div>
                </div>
            </section>

            <section className="grid gap-4 lg:grid-cols-2">
                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                    <div className="flex items-center justify-between gap-3">
                        <div>
                            <p className="text-xs font-semibold text-hud-accent-primary">모델 레이어</p>
                            <h2 className="mt-2 text-xl font-semibold text-hud-text-primary">개인화는 두 속도로 움직입니다</h2>
                        </div>
                        <Brain size={21} className="text-indigo-200" />
                    </div>
                    <div className="mt-5 grid gap-3">
                        <div className="rounded-lg border border-rose-300/25 bg-rose-400/10 p-4">
                            <div className="flex items-center gap-3">
                                <HeartPulse size={19} className="text-rose-200" />
                                <p className="text-sm font-semibold text-hud-text-primary">빠른 경로 · 개인화 프로필</p>
                            </div>
                            <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                좋아요, 저장, 완청, 반복은 긍정 신호로, 조기 스킵과 거부는 부정 신호로 누적되어 다음 GMS preview 순서를 즉시 조정합니다.
                            </p>
                        </div>
                        <div className="rounded-lg border border-indigo-300/25 bg-indigo-400/10 p-4">
                            <div className="flex items-center gap-3">
                                <Brain size={19} className="text-indigo-200" />
                                <p className="text-sm font-semibold text-hud-text-primary">느린 경로 · SASRec 시퀀스 모델</p>
                            </div>
                            <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                장기 청취 시퀀스를 학습해 “이 사용자가 다음에 자연스럽게 들을 곡”을 예측하고, baseline보다 좋아진 경우에만 승격합니다.
                            </p>
                        </div>
                    </div>
                </div>

                <div className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                    <div className="flex items-center justify-between gap-3">
                        <div>
                            <p className="text-xs font-semibold text-hud-accent-primary">안전 게이트</p>
                            <h2 className="mt-2 text-xl font-semibold text-hud-text-primary">추천 품질 안전장치</h2>
                        </div>
                        <ShieldCheck size={21} className="text-emerald-200" />
                    </div>
                    <div className="mt-5 grid gap-3 sm:grid-cols-2">
                        {qualityGates.map((gate) => {
                            const Icon = gate.icon
                            return (
                                <div key={gate.title} className="rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                    <Icon size={18} className="text-emerald-200" />
                                    <p className="mt-3 text-sm font-semibold text-hud-text-primary">{gate.title}</p>
                                    <p className="mt-2 text-xs leading-5 text-hud-text-secondary">{gate.description}</p>
                                </div>
                            )
                        })}
                    </div>
                </div>
            </section>

            <section className="rounded-lg border border-hud-border-secondary bg-hud-bg-secondary/80 p-5 shadow-hud">
                <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(280px,auto)] lg:items-center">
                    <div>
                        <div className="flex items-center gap-2 text-hud-accent-primary">
                            <Music2 size={18} />
                            <p className="text-sm font-semibold">추천을 더 개인화하는 가장 빠른 루프</p>
                        </div>
                        <p className="mt-2 max-w-3xl text-sm leading-7 text-hud-text-secondary">
                            플랫폼 playlist를 PMS로 가져오고, GMS 추천을 들어본 뒤 저장하거나 거부하면 그 행동이 다음 batch의
                            취향 모델에 바로 반영됩니다. 추천은 페이지 하나가 아니라 계속 돌아가는 사용자 소유 음악 시스템입니다.
                        </p>
                    </div>
                    <div className="flex flex-wrap gap-2 lg:justify-end">
                        <Link
                            to="/pms"
                            className="inline-flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-2.5 text-sm text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                        >
                            PMS 가져오기
                        </Link>
                        <Link
                            to="/gms-preview"
                            className="inline-flex items-center gap-2 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/70 px-4 py-2.5 text-sm text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                        >
                            GMS 검토
                            <ListChecks size={15} />
                        </Link>
                    </div>
                </div>
            </section>
        </div>
    )
}

export default RecommendationAlgorithmPage
