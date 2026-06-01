# Main Page Spotify Featured Charts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EMS DB에 저장된 Spotify Featured Charts 플레이리스트 최대 4개를 메인 페이지 전용 섹션으로 노출한다.

**Architecture:** 새 API나 provider 호출을 추가하지 않는다. 프론트 컴포넌트가 기존 `fetchEmsCollectedPlaylists('spotify', ..., 50, false)`를 호출하고, EMS DB 응답 중 `collection_source === 'spotify_featured_charts'`인 카드만 최대 4개 선택한다. 데이터가 없거나 조회에 실패하면 메인 페이지 흐름을 방해하지 않도록 섹션을 숨긴다.

**Tech Stack:** React, TypeScript, Vite, Node 기반 product-flow regression harness

---

### Task 1: 메인 페이지 Spotify Featured Charts 섹션

**Files:**
- Create: `apps/web/src/components/home/PopularSpotifyFeaturedChartsSection.tsx`
- Modify: `apps/web/src/pages/HomePage.tsx`
- Modify: `apps/web/scripts/product-flow-regression-harness.mjs`
- Modify: `docs/PROJECT_GUIDE.md`

- [ ] **Step 1: 회귀 하네스에 실패 조건 추가**

`apps/web/scripts/product-flow-regression-harness.mjs`의 `userFacingFlowFiles`에 새 컴포넌트를 읽는 항목을 추가한다.

```js
spotifyFeaturedCharts: readOptional('src/components/home/PopularSpotifyFeaturedChartsSection.tsx'),
```

기존 EMS Spotify Featured Charts 검사 다음에 메인 페이지 전용 검사를 추가한다.

```js
check(
    'Home page surfaces DB-collected Spotify Featured Charts',
    /PopularSpotifyFeaturedChartsSection/.test(userFacingFlowFiles.home) &&
        /fetchEmsCollectedPlaylists\('spotify'/.test(userFacingFlowFiles.spotifyFeaturedCharts) &&
        /spotify_featured_charts/.test(userFacingFlowFiles.spotifyFeaturedCharts) &&
        /slice\(0,\s*LIMIT\)/.test(userFacingFlowFiles.spotifyFeaturedCharts),
    'Home should display up to four Spotify Featured Charts cards from EMS DB reads.',
)
```

- [ ] **Step 2: 회귀 하네스를 실행해 실패 확인**

Run:

```bash
cd apps/web
npm run test:product-flow
```

Expected: `FAIL Home page surfaces DB-collected Spotify Featured Charts`

- [ ] **Step 3: 전용 섹션 컴포넌트 구현**

`apps/web/src/components/home/PopularSpotifyFeaturedChartsSection.tsx`를 생성한다.

```tsx
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ListMusic } from 'lucide-react'
import MusicArtwork from '@/components/music/MusicArtwork'
import { fetchEmsCollectedPlaylists } from '@/services/api'
import type { EmsCollectionPlaylistItem } from '@/types/api'

const LIMIT = 4
const SOURCE = 'spotify_featured_charts'

type State =
    | { status: 'loading' }
    | { status: 'ready'; playlists: EmsCollectionPlaylistItem[] }
    | { status: 'empty' }

const PopularSpotifyFeaturedChartsSection = () => {
    const [state, setState] = useState<State>({ status: 'loading' })

    useEffect(() => {
        const controller = new AbortController()
        fetchEmsCollectedPlaylists('spotify', controller.signal, 50, false)
            .then((response) => {
                if (controller.signal.aborted) return
                const playlists = response.playlists
                    .filter((playlist) => playlist.collection_source === SOURCE)
                    .slice(0, LIMIT)
                setState(playlists.length > 0 ? { status: 'ready', playlists } : { status: 'empty' })
            })
            .catch(() => {
                if (!controller.signal.aborted) setState({ status: 'empty' })
            })
        return () => controller.abort()
    }, [])

    if (state.status === 'empty') return null

    return (
        <section className="space-y-4">
            <header className="flex items-baseline justify-between">
                <h2 className="text-lg font-semibold text-hud-text-primary">Spotify Featured Charts</h2>
                <span className="text-xs text-hud-text-muted">
                    {state.status === 'loading' ? 'Loading...' : 'Spotify 공개 차트 플레이리스트'}
                </span>
            </header>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {state.status === 'loading'
                    ? Array.from({ length: LIMIT }).map((_, index) => (
                        <div
                            key={index}
                            className="aspect-square animate-pulse rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/60"
                        />
                    ))
                    : state.playlists.map((playlist) => (
                        <Link
                            key={playlist.id}
                            to={`/playlists/ems/${playlist.id}`}
                            className="group flex flex-col overflow-hidden rounded-2xl border border-hud-border-secondary bg-hud-bg-primary/70 transition-hud hover:border-hud-border-primary hover:bg-hud-bg-primary/90"
                        >
                            <div className="relative aspect-square overflow-hidden">
                                <MusicArtwork
                                    imageUrl={playlist.cover_image_url}
                                    seed={`spotify-${playlist.external_playlist_id}`}
                                    label={playlist.title}
                                />
                            </div>
                            <div className="space-y-1 p-3">
                                <p className="truncate text-sm font-semibold text-hud-text-primary">{playlist.title}</p>
                                <p className="truncate text-xs text-hud-text-secondary">{playlist.curator || 'Spotify'}</p>
                                <p className="flex items-center gap-1 text-[11px] text-hud-text-muted">
                                    <ListMusic size={12} />
                                    {playlist.track_count} tracks
                                </p>
                            </div>
                        </Link>
                    ))}
            </div>
        </section>
    )
}

export default PopularSpotifyFeaturedChartsSection
```

- [ ] **Step 4: 메인 페이지에 섹션 배치**

`apps/web/src/pages/HomePage.tsx`에서 컴포넌트를 import한다.

```tsx
import PopularSpotifyFeaturedChartsSection from '@/components/home/PopularSpotifyFeaturedChartsSection'
```

전체 인기 목록과 TIDAL 목록 사이에 추가한다.

```tsx
<PopularPlaylistsSection />

<PopularSpotifyFeaturedChartsSection />

<PopularTidalPlaylistsSection />
```

- [ ] **Step 5: 프로젝트 가이드에 구현 상태 기록**

`docs/PROJECT_GUIDE.md`의 메인 페이지 구현 현황에 아래 항목을 추가한다.

```md
- `apps/web` 메인 페이지 `PopularSpotifyFeaturedChartsSection` 이 EMS DB의 `spotify_featured_charts` 출처 플레이리스트를 최대 4개 카드로 표시함. 외부 provider를 직접 조회하지 않고 `GET /api/v1/ems/collection/playlists?platform_id=spotify` 저장 결과만 사용함
```

- [ ] **Step 6: 회귀 하네스, lint, build 실행**

Run:

```bash
cd apps/web
npm run test:product-flow
npm run lint
npm run build
```

Expected: 모두 exit code `0`

- [ ] **Step 7: 변경 파일만 커밋**

```bash
git add \
  apps/web/scripts/product-flow-regression-harness.mjs \
  apps/web/src/components/home/PopularSpotifyFeaturedChartsSection.tsx \
  apps/web/src/pages/HomePage.tsx \
  docs/PROJECT_GUIDE.md
git commit -m "feat: add spotify featured charts to home"
```
