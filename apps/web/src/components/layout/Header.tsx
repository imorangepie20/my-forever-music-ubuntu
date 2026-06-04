import { Activity, ExternalLink, Globe, Menu, Sparkles } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import Button from '@/components/common/Button'
import { useAuthSession } from '@/contexts/AuthSessionContext'
import { useRecommendationWorkspace } from '@/contexts/RecommendationWorkspaceContext'
import { getAiDocsUrl, getApiConnectionLabel, getApiDocsUrl } from '@/services/api'

interface HeaderProps {
    onMenuToggle: () => void
}

const pageCopy: Record<string, { title: string; subtitle: string }> = {
    '/': {
        title: '내 음악 추천 홈',
        subtitle: '플레이리스트를 모으고, 취향을 학습하고, 오늘 들을 음악을 추천받으세요.',
    },
    '/platforms': {
        title: '플랫폼 연결',
        subtitle: '사용 중인 스트리밍 플랫폼을 연결해 내 음악 보관함을 채우세요.',
    },
    '/platforms/oauth/authorize': {
        title: '플랫폼 연결 승인',
        subtitle: '제공자 동의 화면을 확인한 뒤 연결을 마무리합니다.',
    },
    '/platforms/oauth/callback': {
        title: '플랫폼 연결 완료',
        subtitle: '인증 결과를 확인하고 다음 설정 단계로 이동합니다.',
    },
    '/pms': {
        title: '내 음악 보관함(PMS)',
        subtitle: '가져온 플레이리스트와 저장한 추천곡이 쌓이는 개인 음악 기준점입니다.',
    },
    '/ems': {
        title: '음악 탐색 풀(EMS)',
        subtitle: '외부 공개 플레이리스트와 트렌드에서 새로운 후보를 찾습니다.',
    },
    '/gms-playlists': {
        title: '추천 플레이리스트',
        subtitle: '추천 게이트(GMS)를 통과한 플레이리스트를 확인하고 내 음악 보관함에 저장하세요.',
    },
    '/playback-harness': {
        title: '재생 진단 화면',
        subtitle: 'Spotify, TIDAL, YouTube 재생 경계를 운영자가 확인합니다.',
    },
    '/tidal-playlist-test': {
        title: 'TIDAL 재생 진단',
        subtitle: 'TIDAL 플레이리스트 재생을 격리해서 확인합니다.',
    },
    '/ems/pool-admin': {
        title: 'EMS 큐 관리',
        subtitle: '검색 결과가 EMS 데이터베이스로 적재되는 상태를 확인합니다.',
    },
    '/ems/acquisition-admin': {
        title: 'EMS 수집 관리',
        subtitle: '음악 소스 수집을 실행하고 acquisition 신호를 점검합니다.',
    },
    '/gms-preview': {
        title: '추천 검토(GMS)',
        subtitle: '후보를 듣고 좋아요, 넘기기, 저장하기로 취향 신호를 남깁니다.',
    },
    '/admin/schedules': {
        title: '스케줄 관리',
        subtitle: '주기 작업의 실행 주기와 최근 상태를 확인합니다.',
    },
    '/admin/error-logs': {
        title: '에러 로그',
        subtitle: '주요 서비스 오류를 한 화면에서 확인하고 해결 처리합니다.',
    },
    '/admin/public-curations': {
        title: '공개 큐레이션 관리',
        subtitle: '모델이 만든 외부 공유용 mix를 저장, 발행, 관리합니다.',
    },
    '/recommendations/quality-admin': {
        title: '추천 품질 관리',
        subtitle: '최근 GMS 추천의 6축 평가와 품질 신호를 확인합니다.',
    },
    '/recommendations/feature-coverage': {
        title: '특성 커버리지',
        subtitle: 'PMS, EMS, 학습 신호가 추천에 충분히 준비됐는지 확인합니다.',
    },
    '/recommendations/sasrec-admin': {
        title: 'SASRec 모델 관리',
        subtitle: '학습된 추천 모델의 승격, 비활성화, 롤백 상태를 관리합니다.',
    },
    '/recommendations/metadata-admin': {
        title: '메타데이터 정규화',
        subtitle: 'MusicBrainz 후보와 ISRC 보강 결과를 검토합니다.',
    },
}

const Header = ({ onMenuToggle }: HeaderProps) => {
    const location = useLocation()
    const navigate = useNavigate()
    const { session, clearSession } = useAuthSession()
    const { resetWorkspace } = useRecommendationWorkspace()
    const currentPage = pageCopy[location.pathname] ?? {
        title: 'My Forever Music',
        subtitle: '내 음악을 모으고 추천으로 이어가는 공간입니다.',
    }

    const handleSignOut = () => {
        clearSession()
        resetWorkspace()
        navigate('/login')
    }

    return (
        <header className="sticky top-0 z-40 border-b border-hud-border-secondary bg-hud-bg-secondary/85 backdrop-blur-xl">
            <div className="flex flex-col gap-4 px-4 py-4 sm:px-6 lg:flex-row lg:items-center lg:justify-between lg:px-8">
                <div className="flex items-start gap-3">
                    <button
                        onClick={onMenuToggle}
                        className="mt-1 rounded-lg border border-hud-border-secondary bg-hud-bg-primary/80 p-2 text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary lg:hidden"
                        aria-label="메뉴 열기"
                    >
                        <Menu size={20} />
                    </button>

                    <div>
                        <div className="flex items-center gap-2">
                            <span className="rounded-full border border-hud-border-primary bg-hud-accent-primary/10 px-2.5 py-1 text-[11px] font-semibold uppercase tracking-[0.24em] text-hud-accent-primary">
                                추천 홈
                            </span>
                            <span className="rounded-full border border-hud-border-secondary bg-hud-bg-primary/80 px-2.5 py-1 text-[11px] font-medium text-hud-text-muted">
                                {getApiConnectionLabel()}
                            </span>
                        </div>
                        <h1 className="mt-3 text-2xl font-semibold tracking-tight text-hud-text-primary">
                            {currentPage.title}
                        </h1>
                        <p className="mt-1 max-w-2xl text-sm text-hud-text-secondary">
                            {currentPage.subtitle}
                        </p>
                    </div>
                </div>

                <div className="flex flex-wrap items-center gap-3">
                    {session ? (
                        <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-3">
                            <p className="text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                로그인 중
                            </p>
                            <p className="mt-1 text-sm font-medium text-hud-text-primary">
                                {session.displayName}
                            </p>
                        </div>
                    ) : (
                        <Link to="/login">
                            <Button type="button" variant="ghost">
                                로그인
                            </Button>
                        </Link>
                    )}

                    {!session && (
                        <Link to="/signup">
                            <Button type="button" variant="outline">
                                회원가입
                            </Button>
                        </Link>
                    )}

                    <div className="hidden min-w-[220px] rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-3 md:block">
                        <div className="flex items-center gap-3">
                            <div className="rounded-xl bg-hud-accent-primary/10 p-2 text-hud-accent-primary">
                                <Globe size={18} />
                            </div>
                            <div>
                                <p className="text-xs uppercase tracking-[0.2em] text-hud-text-muted">
                                    동작 경로
                                </p>
                                <p className="mt-1 text-sm text-hud-text-primary">
                                    {'Web -> Spring Boot -> FastAPI'}
                                </p>
                            </div>
                        </div>
                    </div>

                    <a
                        href={getApiDocsUrl()}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-2.5 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                    >
                        <Activity size={16} />
                        API 문서
                        <ExternalLink size={14} />
                    </a>

                    <a
                        href={getAiDocsUrl()}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-4 py-2.5 text-sm font-medium text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary"
                    >
                        <Sparkles size={16} />
                        AI 문서
                        <ExternalLink size={14} />
                    </a>

                    {session && (
                        <Button type="button" variant="ghost" onClick={handleSignOut}>
                            로그아웃
                        </Button>
                    )}
                </div>
            </div>
        </header>
    )
}

export default Header
