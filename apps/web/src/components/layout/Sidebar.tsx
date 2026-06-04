import type { ReactNode } from 'react'
import {
    Activity,
    AlertTriangle,
    BadgeCheck,
    BarChart3,
    CalendarClock,
    ChevronLeft,
    ChevronRight,
    Compass,
    Gauge,
    Globe,
    Home,
    Music2,
    Radio,
    Rss,
    SlidersHorizontal,
    Sparkles,
    Smile,
} from 'lucide-react'
import { NavLink } from 'react-router-dom'
import { useAuthSession } from '@/contexts/AuthSessionContext'

interface SidebarProps {
    collapsed: boolean
    open: boolean
    onClose: () => void
    onCollapseToggle: () => void
}

interface MenuItem {
    label: string
    description: string
    icon: ReactNode
    path: string
}

const menuItems: MenuItem[] = [
    {
        label: '홈',
        description: '오늘 들을 음악과 추천 흐름',
        icon: <Home size={20} />,
        path: '/',
    },
    {
        label: '플랫폼 연결',
        description: '스트리밍 계정과 플레이리스트 가져오기',
        icon: <Radio size={20} />,
        path: '/platforms',
    },
    {
        label: '내 음악(PMS)',
        description: '플랫폼을 바꿔도 남는 내 음악',
        icon: <Music2 size={20} />,
        path: '/pms',
    },
    {
        label: '음악 탐색(EMS)',
        description: '외부 공개 플레이리스트 후보',
        icon: <SlidersHorizontal size={20} />,
        path: '/ems',
    },
    {
        label: '추천 플레이리스트',
        description: '취향 모델이 고른 묶음',
        icon: <Sparkles size={20} />,
        path: '/gms-playlists',
    },
    {
        label: '추천 검토(GMS)',
        description: '후보를 듣고 저장하거나 넘기기',
        icon: <Sparkles size={20} />,
        path: '/gms-preview',
    },
    {
        label: '지금 내 기분은...',
        description: '기분과 곡을 입력해 맞춤 추천받기',
        icon: <Smile size={20} />,
        path: '/mood-recommendation',
    },
]

const adminMenuItems: MenuItem[] = [
    {
        label: '공개 큐레이션',
        description: '외부 공유 mix 생성과 발행',
        icon: <Globe size={20} />,
        path: '/admin/public-curations',
    },
    {
        label: '스케줄 관리',
        description: '주기 작업과 상태 확인',
        icon: <CalendarClock size={20} />,
        path: '/admin/schedules',
    },
    {
        label: '에러 로그',
        description: '주요 서비스 오류 관제',
        icon: <AlertTriangle size={20} />,
        path: '/admin/error-logs',
    },
    {
        label: 'EMS 수집',
        description: '음악 소스 수집 실행',
        icon: <Rss size={20} />,
        path: '/ems/acquisition-admin',
    },
    {
        label: 'EMS 큐',
        description: '검색 적재 큐 모니터',
        icon: <Activity size={20} />,
        path: '/ems/pool-admin',
    },
    {
        label: '추천 품질',
        description: '최근 6축 평가 요약',
        icon: <BarChart3 size={20} />,
        path: '/recommendations/quality-admin',
    },
    {
        label: '특성 커버리지',
        description: 'PMS, EMS, 학습 신호 준비도',
        icon: <Gauge size={20} />,
        path: '/recommendations/feature-coverage',
    },
    {
        label: 'SASRec 모델',
        description: '승격, 비활성화, 롤백 정책',
        icon: <BadgeCheck size={20} />,
        path: '/recommendations/sasrec-admin',
    },
    {
        label: '메타데이터 정규화',
        description: 'MusicBrainz 후보 검토',
        icon: <Compass size={20} />,
        path: '/recommendations/metadata-admin',
    },
]

const Sidebar = ({ collapsed, open, onClose, onCollapseToggle }: SidebarProps) => {
    const { session } = useAuthSession()
    const isAdmin = session?.email.toLowerCase() === 'jowoosungtidal@gmail.com'

    const renderMenuList = (items: MenuItem[]) => (
        <ul className="space-y-2">
            {items.map((item) => (
                <li key={item.path}>
                    <NavLink
                        to={item.path}
                        end={item.path === '/'}
                        onClick={onClose}
                        className={({ isActive }) =>
                            `group flex items-center gap-3 rounded-2xl border px-3 py-3 transition-hud ${
                                isActive
                                    ? 'border-hud-border-primary bg-hud-accent-primary/10 text-hud-accent-primary shadow-hud'
                                    : 'border-transparent text-hud-text-secondary hover:border-hud-border-secondary hover:bg-hud-bg-hover hover:text-hud-text-primary'
                            }`
                        }
                    >
                        <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-hud-bg-primary/80">
                            {item.icon}
                        </span>
                        {!collapsed && (
                            <span className="min-w-0">
                                <span className="block text-sm font-medium">{item.label}</span>
                                <span className="mt-0.5 block text-xs text-hud-text-muted">
                                    {item.description}
                                </span>
                            </span>
                        )}
                    </NavLink>
                </li>
            ))}
        </ul>
    )

    return (
        <>
            <div
                className={`fixed inset-0 z-40 bg-black/60 backdrop-blur-sm transition-opacity lg:hidden ${
                    open ? 'opacity-100' : 'pointer-events-none opacity-0'
                }`}
                onClick={onClose}
            />

            <aside
                className={`fixed inset-y-0 left-0 z-50 flex ${
                    collapsed ? 'lg:w-24' : 'lg:w-72'
                } w-72 flex-col border-r border-hud-border-secondary bg-hud-bg-secondary/95 backdrop-blur-xl transition-transform duration-300 lg:translate-x-0 ${
                    open ? 'translate-x-0' : '-translate-x-full'
                }`}
            >
                <div className="border-b border-hud-border-secondary px-5 py-5">
                    <div className="flex items-center gap-3">
                        <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-hud-accent-primary via-cyan-300 to-hud-accent-secondary text-sm font-black tracking-[0.24em] text-hud-bg-primary">
                            FM
                        </div>
                        {!collapsed && (
                            <div>
                                <p className="text-[11px] font-semibold uppercase tracking-[0.3em] text-hud-accent-primary">
                                    My Forever Music
                                </p>
                                <h2 className="mt-1 text-lg font-semibold text-hud-text-primary">
                                    음악 추천 홈
                                </h2>
                            </div>
                        )}
                    </div>
                </div>

                <nav className="min-h-0 flex-1 overflow-y-auto px-3 py-5">
                    <div className="mb-3 px-3 text-[11px] font-semibold uppercase tracking-[0.28em] text-hud-text-muted">
                        {!collapsed ? '음악 공간' : '공간'}
                    </div>
                    {renderMenuList(menuItems)}
                    {isAdmin && (
                        <div className="mt-6">
                            <div className="mb-3 px-3 text-[11px] font-semibold uppercase tracking-[0.28em] text-hud-text-muted">
                                관리
                            </div>
                            {renderMenuList(adminMenuItems)}
                        </div>
                    )}
                </nav>

                <div className="border-t border-hud-border-secondary px-3 py-4">
                    <div className="rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 p-4">
                        <div className="flex items-center gap-3">
                            <span className="rounded-xl bg-hud-accent-primary/10 p-2 text-hud-accent-primary">
                                <Globe size={18} />
                            </span>
                            {!collapsed && (
                                <div>
                                    <p className="text-xs uppercase tracking-[0.22em] text-hud-text-muted">
                                        추천 흐름
                                    </p>
                                    <p className="mt-1 text-sm text-hud-text-primary">
                                        {'PMS -> EMS -> GMS'}
                                    </p>
                                </div>
                            )}
                        </div>
                        {!collapsed && (
                            <p className="mt-3 text-xs leading-5 text-hud-text-muted">
                                내 음악을 기준으로 외부 후보를 모으고 추천 결과를 다시 저장합니다.
                            </p>
                        )}
                    </div>

                    <button
                        onClick={onCollapseToggle}
                        className="mt-3 hidden w-full items-center justify-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-3 py-2.5 text-sm text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary lg:flex"
                    >
                        {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
                        {!collapsed && '접기'}
                    </button>

                    <button
                        onClick={onClose}
                        className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl border border-hud-border-secondary bg-hud-bg-primary/80 px-3 py-2.5 text-sm text-hud-text-secondary transition-hud hover:border-hud-border-primary hover:text-hud-text-primary lg:hidden"
                    >
                        <Activity size={16} />
                        메뉴 닫기
                    </button>
                </div>
            </aside>
        </>
    )
}

export default Sidebar
