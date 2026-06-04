import { useEffect, useState } from 'react'
import { Activity, ArrowRight, Globe, Server, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'
import Button from '@/components/common/Button'
import AlgorithmIntroSection from '@/components/home/AlgorithmIntroSection'
import FloSpecialHomeSection from '@/components/home/FloSpecialHomeSection'
import GmsRecommendedPlaylistsSection from '@/components/home/GmsRecommendedPlaylistsSection'
import HeroEqBanner from '@/components/home/HeroEqBanner'
import LatestTracksSection from '@/components/home/LatestTracksSection'
import MagazineSection from '@/components/home/MagazineSection'
import MelonHot100Section from '@/components/home/MelonHot100Section'
import PopularPlaylistsSection from '@/components/home/PopularPlaylistsSection'
import PopularSpotifyFeaturedChartsSection from '@/components/home/PopularSpotifyFeaturedChartsSection'
import PopularTidalPlaylistsSection from '@/components/home/PopularTidalPlaylistsSection'
import HudCard from '@/components/common/HudCard'
import StatCard from '@/components/common/StatCard'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { ApiError, fetchSystemInfo, getAiDocsUrl, getApiDocsUrl } from '@/services/api'
import type { SystemInfoResponse } from '@/types/api'

const architectureCards = [
    {
        title: '웹 음악 홈',
        subtitle: 'React + Vite',
        body: '브라우저에서 PMS, EMS, GMS 흐름을 따라 음악을 가져오고 추천을 검토합니다.',
        icon: <Activity size={22} />,
    },
    {
        title: 'API 연결',
        subtitle: 'Spring Boot',
        body: '인증, 플랫폼 연결, PMS 저장, EMS 수집, GMS 추천 요청을 한 흐름으로 묶습니다.',
        icon: <Globe size={22} />,
    },
    {
        title: '추천 모델',
        subtitle: 'FastAPI',
        body: '오디오 특성, 행동 신호, 추천 게이트 결과를 바탕으로 다음에 들을 후보를 고릅니다.',
        icon: <Sparkles size={22} />,
    },
]

const deliveryTracks = [
    '회원가입과 기본 플랫폼 선택',
    '플랫폼 연결과 플레이리스트 가져오기',
    'PMS 행동 신호를 EMS와 GMS 추천 루프로 환류',
]

const HomePage = () => {
    const { session } = useAuthSession()
    const { workspace } = useRecommendationWorkspace()
    const [systemInfo, setSystemInfo] = useState<SystemInfoResponse | null>(null)
    const [statusError, setStatusError] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        fetchSystemInfo(controller.signal)
            .then((response) => {
                setSystemInfo(response)
                setStatusError(null)
            })
            .catch((error: unknown) => {
                if (error instanceof DOMException && error.name === 'AbortError') {
                    return
                }

                if (error instanceof ApiError) {
                    setStatusError(error.message)
                    return
                }

                setStatusError('Unable to reach the Spring Boot system endpoint.')
            })

        return () => controller.abort()
    }, [])

    return (
        <div className="space-y-6">
            <HeroEqBanner />

            <LatestTracksSection />

            <PopularPlaylistsSection />

            <PopularSpotifyFeaturedChartsSection />

            <PopularTidalPlaylistsSection />

            <AlgorithmIntroSection />

            <GmsRecommendedPlaylistsSection />

            <FloSpecialHomeSection />

            <MelonHot100Section />

            <MagazineSection />

            <section className="grid gap-6 xl:grid-cols-[1.3fr_0.9fr]">
                <HudCard className="overflow-hidden">
                    <div className="relative">
                        <div className="absolute inset-x-0 top-0 h-40 rounded-3xl bg-gradient-to-r from-hud-accent-primary/20 via-cyan-300/10 to-hud-accent-secondary/15 blur-3xl" />
                        <div className="relative">
                            <p className="text-xs font-semibold uppercase tracking-[0.28em] text-hud-accent-primary">
                                제품 흐름
                            </p>
                            <h2 className="mt-4 max-w-3xl text-3xl font-semibold tracking-tight text-hud-text-primary sm:text-4xl">
                                플랫폼에서 가져온 음악을 내 보관함에 남기고, 추천으로 다시 이어갑니다.
                            </h2>
                            <p className="mt-4 max-w-2xl text-base leading-7 text-hud-text-secondary">
                                My Forever Music은 PMS, EMS, GMS 흐름으로 내 음악을 보존하고 새로운 후보를 추천합니다.
                            </p>

                            <div className="mt-8 flex flex-wrap gap-3">
                                {session ? (
                                    <Link
                                        to={session.nextStepPath || '/platforms'}
                                        className="btn-glow inline-flex items-center gap-2 rounded-xl bg-hud-accent-primary px-5 py-3 text-sm font-semibold text-hud-bg-primary transition-hud"
                                    >
                                        이어서 설정하기
                                        <ArrowRight size={16} />
                                    </Link>
                                ) : (
                                    <Link
                                        to="/signup"
                                        className="btn-glow inline-flex items-center gap-2 rounded-xl bg-hud-accent-primary px-5 py-3 text-sm font-semibold text-hud-bg-primary transition-hud"
                                    >
                                        회원가입 시작
                                        <ArrowRight size={16} />
                                    </Link>
                                )}
                                {!session && (
                                    <Link
                                        to="/login"
                                        className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-5 py-3 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                    >
                                        로그인
                                    </Link>
                                )}
                                <Link
                                    to="/platforms"
                                    className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-5 py-3 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                >
                                    플랫폼 연결 열기
                                </Link>
                                <Link
                                    to="/gms-preview"
                                    className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-5 py-3 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                >
                                    추천 검토 열기
                                </Link>
                                <a
                                    href={getApiDocsUrl()}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-5 py-3 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                >
                                    API 문서
                                </a>
                                <a
                                    href={getAiDocsUrl()}
                                    target="_blank"
                                    rel="noreferrer"
                                    className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-5 py-3 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                                >
                                    AI 문서
                                </a>
                            </div>
                        </div>
                    </div>
                </HudCard>

                <HudCard
                    title="시스템 상태"
                    subtitle="/api/v1/system/info 실시간 확인"
                >
                    {systemInfo ? (
                        <div className="space-y-4">
                            <div className="rounded-2xl border border-hud-border-primary bg-hud-accent-primary/10 p-4">
                                <p className="text-xs uppercase tracking-[0.24em] text-hud-accent-primary">
                                    {systemInfo.service}
                                </p>
                                <p className="mt-2 text-2xl font-semibold text-hud-text-primary">
                                    {systemInfo.status}
                                </p>
                                <p className="mt-2 text-sm leading-6 text-hud-text-secondary">
                                    {systemInfo.message}
                                </p>
                            </div>
                            <div className="flex items-center gap-3 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                                <span className="rounded-xl bg-hud-accent-info/10 p-2 text-hud-accent-info">
                                    <Server size={18} />
                                </span>
                                <div>
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        확인 시각
                                    </p>
                                    <p className="mt-1 text-sm text-hud-text-primary">
                                        {new Date(systemInfo.timestamp).toLocaleString()}
                                    </p>
                                </div>
                            </div>
                        </div>
                    ) : (
                        <div className="space-y-3">
                            <div className="rounded-2xl border border-dashed border-hud-border-secondary bg-hud-bg-primary/60 p-5 text-sm leading-6 text-hud-text-secondary">
                                {statusError ??
                                    'Spring Boot API 응답을 기다리는 중입니다. services/api를 실행한 뒤 새로고침하면 상태를 확인할 수 있습니다.'}
                            </div>
                        </div>
                    )}
                </HudCard>
            </section>

            <section className="grid gap-6 md:grid-cols-2 xl:grid-cols-4">
                <StatCard
                    title="PMS 기준"
                    value={workspace.playlistId ? '준비됨' : '열기'}
                    icon={<Activity size={22} />}
                    variant="primary"
                />
                <StatCard
                    title="EMS 모델"
                    value={`${workspace.energyLevel}/${workspace.familiarityBias}`}
                    icon={<Globe size={22} />}
                    variant="secondary"
                />
                <StatCard
                    title="GMS 후보 수"
                    value={workspace.limit}
                    icon={<Sparkles size={22} />}
                    variant="warning"
                />
                <StatCard
                    title="현재 분위기"
                    value={workspace.mood}
                    icon={<Server size={22} />}
                    variant="default"
                />
            </section>

            <section className="grid gap-6 xl:grid-cols-[1.2fr_1fr]">
                <HudCard title="제품 구조" subtitle="현재 연결된 핵심 흐름">
                    <div className="grid gap-4 md:grid-cols-3">
                        {architectureCards.map((card) => (
                            <div
                                key={card.title}
                                className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-5"
                            >
                                <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-hud-accent-primary/10 text-hud-accent-primary">
                                    {card.icon}
                                </div>
                                <p className="mt-4 text-lg font-semibold text-hud-text-primary">{card.title}</p>
                                <p className="mt-1 text-sm uppercase tracking-[0.18em] text-hud-text-muted">
                                    {card.subtitle}
                                </p>
                                <p className="mt-4 text-sm leading-6 text-hud-text-secondary">{card.body}</p>
                            </div>
                        ))}
                    </div>
                </HudCard>

                <HudCard title="다음 진행 흐름" subtitle="제품 완성에 필요한 이어지는 작업">
                    <div className="space-y-4">
                        {deliveryTracks.map((track, index) => (
                            <div
                                key={track}
                                className="flex items-start gap-4 rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4"
                            >
                                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-hud-accent-primary/10 text-sm font-semibold text-hud-accent-primary">
                                    {index + 1}
                                </span>
                                <div>
                                    <p className="text-sm font-medium text-hud-text-primary">{track}</p>
                                    <p className="mt-1 text-sm leading-6 text-hud-text-secondary">
                                        추천 계약, API 연결, 웹 화면이 안정적으로 맞물릴 때까지 실제 음악 흐름에 집중합니다.
                                    </p>
                                </div>
                            </div>
                        ))}
                    </div>
                    <div className="mt-5 flex flex-wrap gap-3">
                        <Link to="/pms">
                            <Button type="button" variant="outline">
                                PMS 열기
                            </Button>
                        </Link>
                        <Link to="/platforms">
                            <Button type="button" variant="outline">
                                플랫폼 연결
                            </Button>
                        </Link>
                        <Link to="/ems">
                            <Button type="button" variant="outline">
                                EMS 열기
                            </Button>
                        </Link>
                        <Link to="/gms-preview">
                            <Button type="button" variant="primary" glow>
                                GMS 열기
                            </Button>
                        </Link>
                    </div>
                </HudCard>
            </section>
        </div>
    )
}

export default HomePage
